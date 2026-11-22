package net.ridgelight.core.analysis

import net.ridgelight.core.dem.TerrainModel
import net.ridgelight.core.geo.LatLon
import net.ridgelight.core.horizon.Horizon
import net.ridgelight.core.horizon.HorizonCalculator
import net.ridgelight.core.horizon.HorizonParams
import net.ridgelight.core.pace.Schedule
import net.ridgelight.core.route.SampledRoute
import net.ridgelight.core.sun.SolarPosition
import net.ridgelight.core.sun.SunPosition
import kotlin.math.roundToInt

enum class Light {
    /** The sun's centre is above the terrain horizon. */
    SUN,

    /** The sun is up, but terrain hides it. */
    TERRAIN_SHADE,

    /** The sun is below the unobstructed horizon (night or twilight). */
    BELOW_HORIZON,

    /** Terrain data is missing in the sun's direction, and what is there does not block it. */
    UNKNOWN,
}

class LightSample(val light: Light, val sun: SunPosition, val horizonAngle: Double)

/**
 * Classifies the light at a point. Both angles are apparent (refracted) elevations in degrees.
 */
fun classify(sun: SunPosition, horizon: Horizon?, unobstructed: Double): Light = when {
    sun.apparentAltitude <= unobstructed -> Light.BELOW_HORIZON
    horizon == null || horizon.angle.isNaN() -> Light.UNKNOWN
    sun.apparentAltitude <= horizon.angle -> Light.TERRAIN_SHADE
    !horizon.complete -> Light.UNKNOWN
    else -> Light.SUN
}

/**
 * Runs independent tasks and returns once all are done, possibly on several threads. The engine
 * has no threads of its own (it is platform-neutral); apps pass one backed by their thread pool.
 * Implementations must make the tasks' writes visible to the caller when [runAll] returns (any
 * executor's invokeAll or a coroutine join does).
 */
interface TaskRunner {
    /** How many tasks can run at once; work is split into a few times as many tasks. */
    val parallelism: Int

    fun runAll(tasks: List<() -> Unit>)

    object Sequential : TaskRunner {
        override val parallelism: Int get() = 1
        override fun runAll(tasks: List<() -> Unit>) = tasks.forEach { it() }
    }
}

/**
 * Caches terrain horizons per azimuth bin for a set of fixed points, so repeated questions about
 * the same places at different times (departure sweeps, scrubbing) stay cheap. Different points
 * may be queried from different threads at the same time; one point must not be.
 */
internal class HorizonCache(
    private val calc: HorizonCalculator,
    private val points: List<LatLon>,
    private val grounds: DoubleArray,
    private val binsPerDegree: Int = 2,
) {
    private val bins = 360 * binsPerDegree
    private val angles = arrayOfNulls<FloatArray>(points.size)
    private val state = arrayOfNulls<ByteArray>(points.size) // 0 = not computed, 1 = complete, 2 = partial

    fun eye(i: Int): Double = calc.eyeAltitude(grounds[i])

    fun horizon(i: Int, azimuth: Double): Horizon? {
        if (!grounds[i].isFinite()) return null
        val bin = (azimuth * binsPerDegree).roundToInt().mod(bins)
        val a = angles[i] ?: FloatArray(bins).also { angles[i] = it }
        val s = state[i] ?: ByteArray(bins).also { state[i] = it }
        if (s[bin] == 0.toByte()) {
            val h = calc.horizon(points[i].lat, points[i].lon, eye(i), bin.toDouble() / binsPerDegree)
            a[bin] = h.angle.toFloat()
            s[bin] = if (h.complete) 1 else 2
        }
        return Horizon(a[bin].toDouble(), s[bin] == 1.toByte())
    }

    fun sample(i: Int, utcMillis: Long): LightSample {
        val p = points[i]
        val sun = SolarPosition.at(utcMillis, p.lat, p.lon)
        val unobstructed = calc.unobstructedHorizon(eye(i))
        if (sun.apparentAltitude <= unobstructed) return LightSample(Light.BELOW_HORIZON, sun, Double.NaN)
        val h = horizon(i, sun.azimuth)
        return LightSample(classify(sun, h, unobstructed), sun, h?.angle ?: Double.NaN)
    }
}

/** A run of consecutive steps with the same light. */
class LightRun(
    val light: Light,
    val firstStep: Int,
    /** Exclusive. */
    val endStep: Int,
    val startSeconds: Double,
    val endSeconds: Double,
    val startDistance: Double,
    val endDistance: Double,
) {
    val seconds: Double get() = endSeconds - startSeconds
}

class SunResult(
    val route: SampledRoute,
    val departureUtc: Long,
    val schedule: Schedule,
    val samples: List<LightSample>,
) {
    val duration: Double get() = schedule.duration
    val lights: List<Light> get() = samples.map { it.light }

    /** Seconds spent in each kind of light. */
    val totals: Map<Light, Double> by lazy {
        val m = Light.entries.associateWith { 0.0 }.toMutableMap()
        samples.forEachIndexed { i, s -> m[s.light] = m.getValue(s.light) + schedule.stepSpan[i] }
        m
    }

    fun seconds(light: Light): Double = totals.getValue(light)

    /** Fraction of walking time in direct sun. */
    val sunShare: Double get() = if (duration > 0) seconds(Light.SUN) / duration else 0.0

    val runs: List<LightRun> by lazy {
        val out = ArrayList<LightRun>()
        var i = 0
        val steps = route.steps
        while (i < samples.size) {
            val light = samples[i].light
            var j = i + 1
            while (j < samples.size && samples[j].light == light) j++
            out += LightRun(
                light, i, j,
                schedule.stepStart[i], schedule.stepStart[j - 1] + schedule.stepSpan[j - 1],
                steps[i].start.distance, steps[j - 1].end.distance,
            )
            i = j
        }
        out
    }

    fun timeAt(seconds: Double): Long = departureUtc + (seconds * 1000).toLong()
}

data class SweepEntry(val departureUtc: Long, val totals: Map<Light, Double>, val duration: Double) {
    val sunShare: Double get() = if (duration > 0) totals.getValue(Light.SUN) / duration else 0.0
}

/**
 * Sun and terrain shade along a route. Light is evaluated at each step's midpoint at the time
 * the [Schedule] says it is reached. Horizons are cached, so re-running with other departure
 * times is cheap.
 *
 * [analyse] and [sweep] split the route into chunks and hand them to [runner], so they use
 * several cores when the platform provides them. Calls must not overlap: run one at a time.
 */
class SunAnalyzer(
    val route: SampledRoute,
    terrain: TerrainModel,
    params: HorizonParams = HorizonParams(),
    private val runner: TaskRunner = TaskRunner.Sequential,
) {
    val horizonCalculator = HorizonCalculator(terrain, params)
    private val cache = HorizonCache(
        horizonCalculator,
        route.steps.map { it.mid },
        DoubleArray(route.steps.size) { route.steps[it].midGround },
    )

    /**
     * Contiguous step ranges (nearby steps share terrain tiles), a few per thread so that uneven
     * chunks even out: some steps need new horizons, others hit the cache or have the sun down.
     */
    private val chunks: List<IntRange> = run {
        val n = route.steps.size
        val count = if (runner.parallelism <= 1) 1 else minOf(n, runner.parallelism * 4)
        List(count) { k -> (k * n / count) until ((k + 1) * n / count) }
    }

    private fun forEachChunk(body: (IntRange) -> Unit) {
        if (chunks.size == 1) body(chunks[0]) else runner.runAll(chunks.map { r -> { body(r) } })
    }

    fun lightAt(step: Int, utcMillis: Long): LightSample = cache.sample(step, utcMillis)

    private fun timeAt(schedule: Schedule, departureUtc: Long, step: Int): Long =
        departureUtc + (schedule.midTime(step) * 1000).toLong()

    fun analyse(schedule: Schedule, departureUtc: Long): SunResult {
        require(schedule.stepStart.size == route.steps.size) { "Schedule does not match route" }
        val samples = arrayOfNulls<LightSample>(route.steps.size)
        forEachChunk { r -> for (i in r) samples[i] = lightAt(i, timeAt(schedule, departureUtc, i)) }
        return SunResult(route, departureUtc, schedule, samples.map { it!! })
    }

    fun sweep(schedule: Schedule, departures: List<Long>): List<SweepEntry> {
        require(schedule.stepStart.size == route.steps.size) { "Schedule does not match route" }
        val n = route.steps.size
        val lights = ByteArray(departures.size * n)
        forEachChunk { r ->
            for (i in r) for ((k, t) in departures.withIndex()) {
                lights[k * n + i] = lightAt(i, timeAt(schedule, t, i)).light.ordinal.toByte()
            }
        }
        // Summed in step order on this thread, exactly like SunResult.totals.
        val kinds = Light.entries
        return departures.mapIndexed { k, t ->
            val totals = kinds.associateWith { 0.0 }.toMutableMap()
            for (i in 0 until n) {
                val light = kinds[lights[k * n + i].toInt()]
                totals[light] = totals.getValue(light) + schedule.stepSpan[i]
            }
            SweepEntry(t, totals, schedule.duration)
        }
    }
}

/** Light over time at one fixed place, e.g. to check a village's winter sun hours. */
class PointSunlight(terrain: TerrainModel, params: HorizonParams = HorizonParams(), val point: LatLon) {
    val horizonCalculator = HorizonCalculator(terrain, params)
    val ground: Double = terrain.height(point.lat, point.lon)
    private val cache = HorizonCache(horizonCalculator, listOf(point), doubleArrayOf(ground))

    fun lightAt(utcMillis: Long): LightSample = cache.sample(0, utcMillis)

    class Interval(val light: Light, val startUtc: Long, val endUtc: Long)

    /** Runs of equal light between [fromUtc] and [toUtc], sampled every [stepSeconds]. */
    fun intervals(fromUtc: Long, toUtc: Long, stepSeconds: Int = 60): List<Interval> {
        val out = ArrayList<Interval>()
        var t = fromUtc
        var current: Light? = null
        var start = fromUtc
        while (t <= toUtc) {
            val light = lightAt(t).light
            if (light != current) {
                if (current != null) out += Interval(current, start, t)
                current = light
                start = t
            }
            t += stepSeconds * 1000L
        }
        if (current != null) out += Interval(current, start, toUtc)
        return out
    }
}

/** Departure times every [stepMinutes] from [fromUtc] to [toUtc] inclusive. */
fun departures(fromUtc: Long, toUtc: Long, stepMinutes: Int = 30): List<Long> {
    val out = ArrayList<Long>()
    var t = fromUtc
    while (t <= toUtc) {
        out += t
        t += stepMinutes * 60_000L
    }
    return out
}
