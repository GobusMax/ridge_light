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

    /** How much a stretch of terrain sun counts as sun under this sky: 1, ½ or 0. */
    fun sunWeight(sky: Sky): Double = when (sky) {
        Sky.CLEAR, Sky.THIN_CLOUD -> 1.0
        Sky.PARTLY_CLOUDY -> 0.5
        Sky.OVERCAST, Sky.IN_CLOUD -> 0.0
    }

    /**
     * Weight for [SunResult.weightedSunSeconds] and sweeps: [sunWeight] of the sky at a place
     * (metres along [route]) and time, or null outside the forecast.
     */
    fun sunWeights(forecast: RouteForecast, route: SampledRoute): (Double, Long) -> Double? = { d, t ->
        val altitude = route.elevationAt(d)
        forecast.at(d, t, altitude)?.let { sunWeight(classify(it, altitude).sky) }
    }
}

/** A stretch of the hike in one kind of light under one sky (null where there is no forecast). */
class SkyStretch(val run: LightRun, val sky: SkyAt?)

/** Clouds over a part of the route, as forecast for when the hiker passes it. */
class CloudColumn(val fromDistance: Double, val toDistance: Double, val profile: CloudProfile?)

/** The forecast sky over one planned hike. */
class SkyReport(
    /** The hike's walking runs and breaks, split where the sky changes. */
    val stretches: List<SkyStretch>,
    /** Seconds of terrain sun under each sky. */
    val sunBySky: Map<Sky, Double>,
    /** Seconds of terrain sun with a cloud layer just below (above a sea of fog). */
    val sunAboveClouds: Double,
    /** Seconds spent inside cloud, and where. */
    val inCloudSeconds: Double,
    val inCloudRanges: List<ClosedFloatingPointRange<Double>>,
    /** Over the hike, at the hiker's altitude. */
    val minTemperature: Double,
    val maxTemperature: Double,
    val maxRainChance: Double,
    val maxWind: Double,
    val maxGusts: Double,
    val rain: Boolean,
    val snow: Boolean,
    val thunder: Boolean,
    /** For drawing the clouds along the route, about [COLUMNS] of them. */
    val columns: List<CloudColumn>,
) {
    val sunSeconds: Double get() = sunBySky.values.sum()

    /** The stretch at [seconds] after departure. */
    fun stretchAt(seconds: Double): SkyStretch? =
        stretches.firstOrNull { seconds >= it.run.startSeconds && seconds < it.run.endSeconds } ?: stretches.lastOrNull()

    companion object {
        const val COLUMNS = 120

        /** Stretches shorter than this are folded into their neighbour, so the list stays readable. */
        const val MIN_STRETCH_SECONDS = 300.0

        /** The report for [result], or null if [forecast] does not cover the whole hike. */
        fun of(result: SunResult, forecast: RouteForecast): SkyReport? {
            val route = result.route
            val steps = route.steps
            val schedule = result.schedule
            // The sky at each step, at its midpoint and mid-time.
            val hours = steps.indices.map { i ->
                val s = steps[i]
                val altitude = (s.start.elevation + s.end.elevation) / 2
                forecast.at((s.start.distance + s.end.distance) / 2, result.timeAt(schedule.midTime(i)), altitude) ?: return null
            }
            val skies = steps.indices.map { i -> SkyModel.classify(hours[i], (steps[i].start.elevation + steps[i].end.elevation) / 2) }
            val breakSkies = result.breaks.map { b ->
                val altitude = route.nodes[b.stop.node].elevation
                b.pieces.map { p ->
                    val h = forecast.at(b.distance, result.timeAt((p.startSeconds + p.endSeconds) / 2), altitude) ?: return null
                    SkyModel.classify(h, altitude)
                }
            }

            val stretches = ArrayList<SkyStretch>()
            for (run in result.timeline) {
                val parts = if (run.breakIndex == null) {
                    split((run.firstStep until run.endStep).toList(), { skies[it] }) { from, to ->
                        LightRun(
                            run.light, from, to + 1, schedule.stepStart[from], schedule.stepStart[to] + schedule.stepSpan[to],
                            steps[from].start.distance, steps[to].end.distance,
                        )
                    }
                } else {
                    val b = result.breaks[run.breakIndex]
                    val pieces = b.pieces.indices.filter { b.pieces[it].startSeconds >= run.startSeconds && b.pieces[it].endSeconds <= run.endSeconds }
                    split(pieces, { breakSkies[run.breakIndex][it] }) { from, to ->
                        LightRun(run.light, run.firstStep, run.endStep, b.pieces[from].startSeconds, b.pieces[to].endSeconds, b.distance, b.distance, run.breakIndex)
                    }
                }
                stretches += parts
            }

            val sunBySky = Sky.entries.associateWith { 0.0 }.toMutableMap()
            var above = 0.0
            var inCloud = 0.0
            for (st in stretches) {
                val sky = st.sky ?: continue
                if (sky.sky == Sky.IN_CLOUD) inCloud += st.run.seconds
                if (st.run.light != Light.SUN) continue
                sunBySky[sky.sky] = sunBySky.getValue(sky.sky) + st.run.seconds
                if (sky.cloudsBelow) above += st.run.seconds
            }
            val ranges = ArrayList<ClosedFloatingPointRange<Double>>()
            for (st in stretches.filter { it.sky?.sky == Sky.IN_CLOUD }) {
                val last = ranges.lastOrNull()
                if (last != null && st.run.startDistance <= last.endInclusive + 1) {
                    ranges[ranges.size - 1] = last.start..max(last.endInclusive, st.run.endDistance)
                } else {
                    ranges += st.run.startDistance..st.run.endDistance
                }
            }

            val columns = run {
                val n = min(COLUMNS, steps.size)
                List(n) { k ->
                    val from = k * steps.size / n
                    val to = (k + 1) * steps.size / n - 1
                    CloudColumn(steps[from].start.distance, steps[to].end.distance, hours[(from + to) / 2].clouds)
                }
            }
            fun stat(f: (WeatherHour) -> Double) = hours.map(f).filter { it.isFinite() }
            val temps = stat { it.temperature }
            val codes = hours.map { it.weatherCode }
            return SkyReport(
                stretches, sunBySky, above, inCloud, ranges,
                temps.minOrNull() ?: Double.NaN, temps.maxOrNull() ?: Double.NaN,
                stat { it.precipitationProbability }.maxOrNull() ?: Double.NaN,
                stat { it.windSpeed }.maxOrNull() ?: Double.NaN,
                stat { it.windGusts }.maxOrNull() ?: Double.NaN,
                codes.any(WeatherCodes::isRain), codes.any(WeatherCodes::isSnow), codes.any(WeatherCodes::isThunder),
                columns,
            )
        }

        /**
         * Groups consecutive [items] by their sky, folding groups shorter than
         * [MIN_STRETCH_SECONDS] into the one before (or after, at the start).
         */
        private fun split(items: List<Int>, skyOf: (Int) -> SkyAt, make: (from: Int, to: Int) -> LightRun): List<SkyStretch> {
            if (items.isEmpty()) return emptyList()
            val groups = ArrayList<Triple<Int, Int, SkyAt>>() // first item, last item, sky
            for (i in items) {
                val sky = skyOf(i)
                val last = groups.lastOrNull()
                if (last != null && last.third == sky) groups[groups.size - 1] = Triple(last.first, i, sky) else groups += Triple(i, i, sky)
            }
            var merged = true
            while (merged && groups.size > 1) {
                merged = false
                for (g in groups.indices) {
                    val run = make(groups[g].first, groups[g].second)
                    if (run.seconds >= MIN_STRETCH_SECONDS) continue
                    if (g > 0) {
                        groups[g - 1] = Triple(groups[g - 1].first, groups[g].second, groups[g - 1].third)
                    } else {
                        groups[1] = Triple(groups[0].first, groups[1].second, groups[1].third)
                    }
                    groups.removeAt(g)
                    // Neighbours with the same sky join up.
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
            return groups.map { (from, to, sky) -> SkyStretch(make(from, to), sky) }
        }
    }
}
