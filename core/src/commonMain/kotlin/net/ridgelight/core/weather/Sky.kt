package net.ridgelight.core.weather

import net.ridgelight.core.analysis.Light
import net.ridgelight.core.analysis.LightRun
import net.ridgelight.core.analysis.SunResult
import net.ridgelight.core.route.SampledRoute
import kotlin.math.max
import kotlin.math.min

/** The sky above a hiker, from the forecast's clouds at the hiker's altitude and above. */
enum class Sky {
    /** No cloud worth mentioning above. */
    CLEAR,

    /** Only high (ice) cloud above, which the sun usually shines through, dimmed. */
    THIN_CLOUD,

    /** Low or mid-level cloud above, with gaps. */
    PARTLY_CLOUDY,

    /** Low or mid-level cloud above that hides the sun. */
    OVERCAST,

    /** The hiker is inside a cloud layer: fog, or cloud on the mountain. */
    IN_CLOUD,
}

/** [sky] at one place and time; [cloudsBelow] marks a cloud layer (e.g. valley fog) just below the hiker. */
class SkyAt(val sky: Sky, val cloudsBelow: Boolean) {
    override fun equals(other: Any?) = other is SkyAt && other.sky == sky && other.cloudsBelow == cloudsBelow
    override fun hashCode() = sky.hashCode() * 2 + if (cloudsBelow) 1 else 0
}

/** The light a hiker gets from terrain and clouds together. */
enum class SkyLight {
    /** Terrain sun under a clear or thinly clouded sky. */
    SUN,

    /** Terrain sun, partly cloudy: sun now and then. */
    SUN_AND_CLOUDS,

    /** Terrain sun, but overcast or in fog: the clouds hide it. */
    CLOUDED,

    /** Terrain shade, whatever the sky. */
    SHADE,

    /** The sun is below the horizon. */
    DARK,
    UNKNOWN;

    companion object {
        /** [light] from the terrain under [sky] (null: no forecast, the terrain alone decides). */
        fun of(light: Light, sky: Sky?): SkyLight = when (light) {
            Light.SUN -> when (sky) {
                null, Sky.CLEAR, Sky.THIN_CLOUD -> SUN
                Sky.PARTLY_CLOUDY -> SUN_AND_CLOUDS
                Sky.OVERCAST, Sky.IN_CLOUD -> CLOUDED
            }
            Light.TERRAIN_SHADE -> SHADE
            Light.BELOW_HORIZON -> DARK
            Light.UNKNOWN -> UNKNOWN
        }

        /** Terrain totals as combined totals, for when there is no forecast. */
        fun ofTerrain(totals: Map<Light, Double>): Map<SkyLight, Double> {
            val m = entries.associateWith { 0.0 }.toMutableMap()
            for ((light, s) in totals) m[of(light, null)] = m.getValue(of(light, null)) + s
            return m
        }

        /** Sun to rank plans by: in full under a clear sky, half between clouds. */
        fun expectedSun(totals: Map<SkyLight, Double>): Double = (totals[SUN] ?: 0.0) + 0.5 * (totals[SUN_AND_CLOUDS] ?: 0.0)
    }
}

/**
 * Turns a forecast hour into what a hiker at a given altitude sees. Forecast models resolve cloud
 * only on grid cells of a few kilometres and on pressure levels a few hundred metres apart, and
 * low fog is among the things they get wrong most, so this is an estimate.
 */
object SkyModel {
    /** Cloud cover at the hiker's altitude from which they count as inside the cloud. */
    const val IN_CLOUD_COVER = 0.5

    /** Above this height, cloud is ice cloud (cirrus) that the sun usually shines through. */
    const val HIGH_CLOUD_M = 6_000.0

    /** How far below the hiker a cloud layer counts as "below" (a sea of fog, not the lowlands). */
    const val BELOW_RANGE_M = 500.0

    fun classify(hour: WeatherHour, altitude: Double): SkyAt {
        val p = hour.clouds
        if (p == null || !altitude.isFinite()) {
            val c = hour.cloudCover
            return SkyAt(
                when {
                    c.isNaN() -> Sky.PARTLY_CLOUDY
                    c >= 0.75 -> Sky.OVERCAST
                    c >= 0.3 -> Sky.PARTLY_CLOUDY
                    else -> Sky.CLEAR
                },
                false,
            )
        }
        if (p.coverAt(altitude) >= IN_CLOUD_COVER) return SkyAt(Sky.IN_CLOUD, false)

        // Low and mid-level layers above: levels with cloud next to each other are one layer
        // (its densest level counts); separate layers overlap at random.
        var clear = 1.0
        var layer = 0.0
        var high = 0.0
        for (k in p.heights.indices) {
            val h = p.heights[k]
            if (h <= altitude) continue
            val c = p.cover[k]
            if (h >= HIGH_CLOUD_M) {
                high = max(high, c)
            } else if (c >= 0.1) {
                layer = max(layer, c)
            } else {
                clear *= 1 - layer
                layer = 0.0
            }
        }
        clear *= 1 - layer
        val blocked = 1 - clear
        val sky = when {
            blocked >= 0.75 -> Sky.OVERCAST
            blocked >= 0.3 -> Sky.PARTLY_CLOUDY
            high >= 0.5 -> Sky.THIN_CLOUD
            else -> Sky.CLEAR
        }
        val below = p.heights.indices.any { k ->
            p.heights[k] < altitude && p.heights[k] > altitude - BELOW_RANGE_M && p.cover[k] >= 0.6
        }
        return SkyAt(sky, below && (sky == Sky.CLEAR || sky == Sky.THIN_CLOUD))
    }

    /** The sky at a place (metres along [route], at the route's elevation there) and time; null outside the forecast. */
    fun skyAt(forecast: RouteForecast, route: SampledRoute): (Double, Long) -> Sky? = { d, t ->
        forecast.at(d, t)?.let { classify(it, route.elevationAt(d)).sky }
    }
}

/**
 * A stretch of the hike in one [light] (terrain and clouds combined); [sky] is the sky over most
 * of it, null without a forecast. [run] gives its times and places; its own light is the terrain's.
 */
class SkyStretch(val run: LightRun, val light: SkyLight, val sky: SkyAt?)

/** Clouds over a part of the route, as forecast for when the hiker passes it. */
class CloudColumn(val fromDistance: Double, val toDistance: Double, val profile: CloudProfile?)

/** The forecast clouds over one planned hike, combined with its terrain light. */
class SkyReport(
    /** The sky at each walking step, at its midpoint and mid-time. */
    val stepSky: List<SkyAt>,
    /** For each break, the sky through each of its pieces. */
    val breakSky: List<List<SkyAt>>,
    /** Seconds in each combined light, walking and breaks. */
    val totals: Map<SkyLight, Double>,
    /** The hike in stretches of combined light, split at breaks. */
    val stretches: List<SkyStretch>,
    /** Seconds of sun with a cloud layer just below (above a sea of fog). */
    val sunAboveClouds: Double,
    /** Seconds spent inside cloud, and where (metres along the route). */
    val inCloudSeconds: Double,
    val inCloudRanges: List<ClosedFloatingPointRange<Double>>,
    /** For drawing the clouds along the route, about [COLUMNS] of them. */
    val columns: List<CloudColumn>,
) {
    fun seconds(light: SkyLight): Double = totals.getValue(light)

    /** The stretch at [seconds] after departure. */
    fun stretchAt(seconds: Double): SkyStretch? =
        stretches.firstOrNull { seconds >= it.run.startSeconds && seconds < it.run.endSeconds } ?: stretches.lastOrNull()

    companion object {
        const val COLUMNS = 120

        /** Sky changes shorter than this are folded into their neighbour, so the list stays readable. */
        const val MIN_STRETCH_SECONDS = 300.0

        /** The report for [result], or null if [forecast] does not cover the whole hike. */
        fun of(result: SunResult, forecast: RouteForecast): SkyReport? {
            val route = result.route
            val steps = route.steps
            val schedule = result.schedule
            // The same places, times and altitudes as SkyModel.skyAt, so totals match sweeps.
            val hours = steps.indices.map { i ->
                val s = steps[i]
                forecast.at((s.start.distance + s.end.distance) / 2, result.timeAt(schedule.midTime(i))) ?: return null
            }
            val stepSky = steps.indices.map { i ->
                val s = steps[i]
                SkyModel.classify(hours[i], route.elevationAt((s.start.distance + s.end.distance) / 2))
            }
            val breakSky = result.breaks.map { b ->
                val altitude = route.elevationAt(b.distance)
                b.pieces.map { p ->
                    val h = forecast.at(b.distance, result.timeAt((p.startSeconds + p.endSeconds) / 2)) ?: return null
                    SkyModel.classify(h, altitude)
                }
            }
            fun stepLight(i: Int) = SkyLight.of(result.samples[i].light, stepSky[i].sky)
            fun pieceLight(k: Int, p: Int) = SkyLight.of(result.breaks[k].pieces[p].sample.light, breakSky[k][p].sky)

            val totals = SkyLight.entries.associateWith { 0.0 }.toMutableMap()
            var above = 0.0
            var inCloud = 0.0
            fun count(light: SkyLight, sky: SkyAt, seconds: Double) {
                totals[light] = totals.getValue(light) + seconds
                if (light == SkyLight.SUN && sky.cloudsBelow) above += seconds
                if (sky.sky == Sky.IN_CLOUD) inCloud += seconds
            }
            for (i in steps.indices) count(stepLight(i), stepSky[i], schedule.stepSpan[i])
            for ((k, b) in result.breaks.withIndex()) for ((p, piece) in b.pieces.withIndex()) {
                count(pieceLight(k, p), breakSky[k][p], piece.endSeconds - piece.startSeconds)
            }

            val ranges = ArrayList<ClosedFloatingPointRange<Double>>()
            for (i in steps.indices) {
                if (stepSky[i].sky != Sky.IN_CLOUD) continue
                val last = ranges.lastOrNull()
                if (last != null && steps[i].start.distance <= last.endInclusive + 1) {
                    ranges[ranges.size - 1] = last.start..steps[i].end.distance
                } else {
                    ranges += steps[i].start.distance..steps[i].end.distance
                }
            }

            // Terrain runs (and break periods) split where the combined light changes.
            val stretches = ArrayList<SkyStretch>()
            for (run in result.timeline) {
                if (run.breakIndex == null) {
                    val items = (run.firstStep until run.endStep).toList()
                    for ((from, to, light) in split(items, ::stepLight) { a, b -> schedule.stepStart[b] + schedule.stepSpan[b] - schedule.stepStart[a] }) {
                        val r = LightRun(
                            run.light, from, to + 1, schedule.stepStart[from], schedule.stepStart[to] + schedule.stepSpan[to],
                            steps[from].start.distance, steps[to].end.distance,
                        )
                        stretches += SkyStretch(r, light, mostOf((from..to).map { stepSky[it] to schedule.stepSpan[it] }))
                    }
                } else {
                    val k = run.breakIndex
                    val pieces = result.breaks[k].pieces
                    val items = pieces.indices.filter { pieces[it].startSeconds >= run.startSeconds && pieces[it].endSeconds <= run.endSeconds }
                    for ((from, to, light) in split(items, { pieceLight(k, it) }) { a, b -> pieces[b].endSeconds - pieces[a].startSeconds }) {
                        val b = result.breaks[k]
                        val r = LightRun(run.light, run.firstStep, run.endStep, pieces[from].startSeconds, pieces[to].endSeconds, b.distance, b.distance, k)
                        stretches += SkyStretch(r, light, mostOf((from..to).map { breakSky[k][it] to pieces[it].endSeconds - pieces[it].startSeconds }))
                    }
                }
            }

            val columns = run {
                val n = min(COLUMNS, steps.size)
                List(n) { c ->
                    val from = c * steps.size / n
                    val to = (c + 1) * steps.size / n - 1
                    CloudColumn(steps[from].start.distance, steps[to].end.distance, hours[(from + to) / 2].clouds)
                }
            }
            return SkyReport(stepSky, breakSky, totals, stretches, above, inCloud, ranges, columns)
        }

        /** The sky that covers most of the time in [skies] (sky, seconds). */
        private fun mostOf(skies: List<Pair<SkyAt, Double>>): SkyAt? =
            skies.groupBy({ it.first }, { it.second }).maxByOrNull { (_, s) -> s.sum() }?.key

        /**
         * Groups consecutive [items] (step or piece indices, in order) by [keyOf] as (first, last,
         * key), folding groups shorter than [MIN_STRETCH_SECONDS] ([seconds] from first to last)
         * into the one before (or after, at the start).
         */
        private fun <K> split(items: List<Int>, keyOf: (Int) -> K, seconds: (from: Int, to: Int) -> Double): List<Triple<Int, Int, K>> {
            if (items.isEmpty()) return emptyList()
            val groups = ArrayList<Triple<Int, Int, K>>()
            for (i in items) {
                val key = keyOf(i)
                val last = groups.lastOrNull()
                if (last != null && last.third == key) groups[groups.size - 1] = Triple(last.first, i, key) else groups += Triple(i, i, key)
            }
            var merged = true
            while (merged && groups.size > 1) {
                merged = false
                for (g in groups.indices) {
                    if (seconds(groups[g].first, groups[g].second) >= MIN_STRETCH_SECONDS) continue
                    if (g > 0) {
                        groups[g - 1] = Triple(groups[g - 1].first, groups[g].second, groups[g - 1].third)
                    } else {
                        groups[1] = Triple(groups[0].first, groups[1].second, groups[1].third)
                    }
                    groups.removeAt(g)
                    // Neighbours with the same key join up.
                    for (j in groups.size - 1 downTo 1) {
                        if (groups[j].third == groups[j - 1].third) {
                            groups[j - 1] = Triple(groups[j - 1].first, groups[j].second, groups[j].third)
                            groups.removeAt(j)
                        }
                    }
                    merged = true
                    break
                }
            }
            return groups
        }
    }
}
