package net.ridgelight.core.analysis

import net.ridgelight.core.dem.TerrainModel
import net.ridgelight.core.geo.LatLon
import net.ridgelight.core.horizon.Horizon
import net.ridgelight.core.horizon.HorizonCalculator
import net.ridgelight.core.horizon.HorizonParams
import net.ridgelight.core.pace.Schedule
import net.ridgelight.core.pace.ScheduledBreak
import net.ridgelight.core.route.SampledRoute
import net.ridgelight.core.sun.SolarPosition
import net.ridgelight.core.sun.SunPosition
import net.ridgelight.core.weather.Sky
import net.ridgelight.core.weather.SkyLight
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

/**
 * A stretch of time in one kind of light: a run of consecutive steps walked in that light, or
 * (with [breakIndex] set) part of a break spent in it, where both distances are the break's place.
 */
class LightRun(
    val light: Light,
    val firstStep: Int,
    /** Exclusive; equal to [firstStep] for a break. */
    val endStep: Int,
    val startSeconds: Double,
    val endSeconds: Double,
    val startDistance: Double,
    val endDistance: Double,
    /** Index into [Schedule.breaks] if this is (part of) a break. */
    val breakIndex: Int? = null,
) {
    val seconds: Double get() = endSeconds - startSeconds
    val isBreak: Boolean get() = breakIndex != null
}

/** Light during one break, sampled every [BREAK_SAMPLE_SECONDS] or less. */
class BreakLight(val stop: ScheduledBreak, val distance: Double, val pieces: List<Piece>) {
    class Piece(val startSeconds: Double, val endSeconds: Double, val sample: LightSample)
}

/** Breaks are sampled at this interval; the sun moves about a quarter degree per minute. */
const val BREAK_SAMPLE_SECONDS = 60.0

/** Where the hiker is at one moment, and in what light. */
class Moment(val distance: Double, val sample: LightSample, val breakIndex: Int?)

class SunResult(
    val route: SampledRoute,
    val departureUtc: Long,
    val schedule: Schedule,
    /** Light for each walked step, at its midpoint and mid-time. */
    val samples: List<LightSample>,
    /** One per [Schedule.breaks], in the same order. */
    val breaks: List<BreakLight> = emptyList(),
) {
    /** Departure to arrival, breaks included. */
    val duration: Double get() = schedule.duration
    val lights: List<Light> get() = samples.map { it.light }

    /** Seconds spent in each kind of light, walking and on breaks. */
    val totals: Map<Light, Double> by lazy {
        val m = Light.entries.associateWith { 0.0 }.toMutableMap()
        samples.forEachIndexed { i, s -> m[s.light] = m.getValue(s.light) + schedule.stepSpan[i] }
        for (b in breaks) for (p in b.pieces) m[p.sample.light] = m.getValue(p.sample.light) + (p.endSeconds - p.startSeconds)
        m
    }

    fun seconds(light: Light): Double = totals.getValue(light)

    /** Fraction of the hike (walking and breaks) in direct sun. */
    val sunShare: Double get() = if (duration > 0) seconds(Light.SUN) / duration else 0.0

    /** Walking runs: consecutive steps in the same light, split where a break interrupts them. */
    val runs: List<LightRun> by lazy {
        val breakNodes = schedule.breaks.map { it.node }.toSet()
        val out = ArrayList<LightRun>()
        var i = 0
        val steps = route.steps
        while (i < samples.size) {
            val light = samples[i].light
            var j = i + 1
            while (j < samples.size && samples[j].light == light && j !in breakNodes) j++
            out += LightRun(
                light, i, j,
                schedule.stepStart[i], schedule.stepStart[j - 1] + schedule.stepSpan[j - 1],
                steps[i].start.distance, steps[j - 1].end.distance,
            )
            i = j
        }
        out
    }

    /** Walking runs and break periods (merged per light) in time order. */
    val timeline: List<LightRun> by lazy {
        val pauses = breaks.flatMapIndexed { k, b ->
            val merged = ArrayList<LightRun>()
            for (p in b.pieces) {
                val last = merged.lastOrNull()
                if (last != null && last.light == p.sample.light) {
                    merged[merged.size - 1] = LightRun(last.light, b.stop.node, b.stop.node, last.startSeconds, p.endSeconds, b.distance, b.distance, k)
                } else {
                    merged += LightRun(p.sample.light, b.stop.node, b.stop.node, p.startSeconds, p.endSeconds, b.distance, b.distance, k)
                }
            }
            merged
        }
        (runs + pauses).sortedBy { it.startSeconds }
    }

    /** Position and light at [seconds] after departure. */
    fun momentAt(seconds: Double): Moment {
        for ((k, b) in breaks.withIndex()) {
            if (seconds >= b.stop.startSeconds && seconds < b.stop.endSeconds) {
                val piece = b.pieces.firstOrNull { seconds < it.endSeconds } ?: b.pieces.last()
                return Moment(b.distance, piece.sample, k)
            }
        }
        val i = schedule.stepAt(seconds)
        val step = route.steps[i]
        val f = ((seconds - schedule.stepStart[i]) / schedule.stepSpan[i]).let { if (it.isFinite()) it.coerceIn(0.0, 1.0) else 0.0 }
        return Moment(step.start.distance + step.length * f, samples[i], null)
    }

    /**
     * Seconds in each [SkyLight]: the terrain light combined with the sky that [skyAt] gives for a
     * place (metres along the route) and time. Null if [skyAt] has no answer for some sunny
     * stretch (e.g. beyond the forecast); shade and darkness need no sky.
     */
    fun skyTotals(skyAt: (distance: Double, utcMillis: Long) -> Sky?): Map<SkyLight, Double>? {
        val m = SkyLight.entries.associateWith { 0.0 }.toMutableMap()
        for ((i, s) in samples.withIndex()) {
            val sky = if (s.light == Light.SUN) {
                val step = route.steps[i]
                skyAt((step.start.distance + step.end.distance) / 2, timeAt(schedule.midTime(i))) ?: return null
            } else null
            val light = SkyLight.of(s.light, sky)
            m[light] = m.getValue(light) + schedule.stepSpan[i]
        }
        for (b in breaks) for (p in b.pieces) {
            val sky = if (p.sample.light == Light.SUN) skyAt(b.distance, timeAt((p.startSeconds + p.endSeconds) / 2)) ?: return null else null
            val light = SkyLight.of(p.sample.light, sky)
            m[light] = m.getValue(light) + (p.endSeconds - p.startSeconds)
        }
        return m
    }

    fun timeAt(seconds: Double): Long = departureUtc + (seconds * 1000).toLong()

    /**
     * Seconds after departure at which the hiker passes [distance] metres along the route; at a
     * break, when they walk on.
     */
    fun secondsAt(distance: Double): Double {
        val steps = route.steps
        if (steps.isEmpty()) return 0.0
        var lo = 0
        var hi = steps.size - 1
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            if (steps[m].end.distance <= distance) lo = m + 1 else hi = m
        }
        val step = steps[lo]
        val f = ((distance - step.start.distance) / step.length).let { if (it.isFinite()) it.coerceIn(0.0, 1.0) else 0.0 }
        return schedule.stepStart[lo] + schedule.stepSpan[lo] * f
    }
}

data class SweepEntry(
    val departureUtc: Long,
    val totals: Map<Light, Double>,
    val duration: Double,
    /** Terrain and clouds combined (see [SunResult.skyTotals]), if a sky was given and covers it. */
    val skyTotals: Map<SkyLight, Double>? = null,
) {
    val sunShare: Double get() = if (duration > 0) totals.getValue(Light.SUN) / duration else 0.0

    /** [skyTotals], or the terrain light alone without them. */
    val combined: Map<SkyLight, Double> get() = skyTotals ?: SkyLight.ofTerrain(totals)

    /** Sun to rank departures by (see [SkyLight.expectedSun]); terrain sun without a sky. */
    val expectedSun: Double get() = SkyLight.expectedSun(combined)
}

/**
 * Sun and terrain shade along a route. Light is evaluated at each step's midpoint at the time
 * the [Schedule] says it is reached, and through each break at the break's place. Horizons are
 * cached, so re-running with other departure times is cheap.
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

    /**
     * Light through each break. Evaluated at the midpoint of the step that follows the break
     * (half a step from the stop, which reuses that step's cached horizons). Runs on the calling
     * thread, after the chunks are done, so it never shares a step with a worker.
     */
    private fun breakLights(schedule: Schedule, departureUtc: Long): List<BreakLight> = schedule.breaks.map { b ->
        val step = b.node.coerceAtMost(route.steps.size - 1)
        val pieces = ArrayList<BreakLight.Piece>()
        var t = b.startSeconds
        while (t < b.endSeconds) {
            val end = minOf(t + BREAK_SAMPLE_SECONDS, b.endSeconds)
            pieces += BreakLight.Piece(t, end, lightAt(step, departureUtc + ((t + end) / 2 * 1000).toLong()))
            t = end
        }
        BreakLight(b, route.nodes[b.node].distance, pieces)
    }

    fun analyse(schedule: Schedule, departureUtc: Long): SunResult {
        require(schedule.stepStart.size == route.steps.size) { "Schedule does not match route" }
        val samples = arrayOfNulls<LightSample>(route.steps.size)
        forEachChunk { r -> for (i in r) samples[i] = lightAt(i, timeAt(schedule, departureUtc, i)) }
        return SunResult(route, departureUtc, schedule, samples.map { it!! }, breakLights(schedule, departureUtc))
    }

    /**
     * Totals for each departure; the same as [analyse] would give for each, but much cheaper.
     * With [skyAt] (see [SunResult.skyTotals]) each entry also gets terrain and clouds combined.
     */
    fun sweep(
        schedule: Schedule,
        departures: List<Long>,
        skyAt: ((distance: Double, utcMillis: Long) -> Sky?)? = null,
    ): List<SweepEntry> {
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
            var combined: MutableMap<SkyLight, Double>? = if (skyAt != null) SkyLight.entries.associateWith { 0.0 }.toMutableMap() else null
            fun add(light: Light, seconds: Double, sky: () -> Sky?) {
                totals[light] = totals.getValue(light) + seconds
                val c = combined ?: return
                val s = if (light == Light.SUN) sky() ?: run { combined = null; return } else null
                val l = SkyLight.of(light, s)
                c[l] = c.getValue(l) + seconds
            }
            for (i in 0 until n) {
                val step = route.steps[i]
                add(kinds[lights[k * n + i].toInt()], schedule.stepSpan[i]) {
                    skyAt!!((step.start.distance + step.end.distance) / 2, timeAt(schedule, t, i))
                }
            }
            for (b in breakLights(schedule, t)) for (p in b.pieces) {
                add(p.sample.light, p.endSeconds - p.startSeconds) {
                    skyAt!!(b.distance, t + ((p.startSeconds + p.endSeconds) / 2 * 1000).toLong())
                }
            }
            SweepEntry(t, totals, schedule.duration, combined)
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
