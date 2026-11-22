package net.ridgelight.core.pace

import net.ridgelight.core.route.SampledRoute
import kotlin.math.max
import kotlin.math.min

/** When each step is walked, in seconds after departure. */
class Schedule(val stepStart: DoubleArray, val stepSpan: DoubleArray) {
    init {
        require(stepStart.size == stepSpan.size)
    }

    val duration: Double get() = if (stepStart.isEmpty()) 0.0 else stepStart.last() + stepSpan.last()
    fun midTime(step: Int): Double = stepStart[step] + stepSpan[step] / 2

    /** Index of the step being walked at [seconds] after departure. */
    fun stepAt(seconds: Double): Int {
        var lo = 0
        var hi = stepStart.size - 1
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            if (stepStart[m] + stepSpan[m] < seconds) lo = m + 1 else hi = m
        }
        return lo
    }

    companion object {
        fun fromSpans(spans: DoubleArray): Schedule {
            val start = DoubleArray(spans.size)
            var t = 0.0
            for (i in spans.indices) {
                start[i] = t
                t += spans[i]
            }
            return Schedule(start, spans)
        }
    }
}

/** Estimates how long each step of a route takes. */
interface PaceModel {
    fun schedule(route: SampledRoute): Schedule
}

private fun climb(route: SampledRoute, step: Int): Pair<Double, Double> {
    val s = route.steps[step]
    val dz = s.end.elevation - s.start.elevation
    if (!dz.isFinite()) return 0.0 to 0.0
    return max(0.0, dz) to max(0.0, -dz)
}

/**
 * DIN 33466 hiking time, the standard used for signposted times in Germany, Austria and
 * Switzerland: horizontal and vertical times are computed separately, and the total is the larger
 * plus half the smaller. Applied per step so steep and flat stretches each get their own rate.
 *
 * @param factor multiplies all times, e.g. 0.8 for a fast hiker or 1.2 to include short breaks.
 */
data class Din33466Pace(
    val horizontalKmh: Double = 4.0,
    val ascentMetresPerHour: Double = 300.0,
    val descentMetresPerHour: Double = 500.0,
    val factor: Double = 1.0,
) : PaceModel {
    init {
        require(horizontalKmh > 0 && ascentMetresPerHour > 0 && descentMetresPerHour > 0 && factor > 0)
    }

    override fun schedule(route: SampledRoute): Schedule = Schedule.fromSpans(
        DoubleArray(route.steps.size) { i ->
            val (up, down) = climb(route, i)
            val horizontal = route.steps[i].length / 1000.0 / horizontalKmh
            val vertical = up / ascentMetresPerHour + down / descentMetresPerHour
            (max(horizontal, vertical) + min(horizontal, vertical) / 2) * 3600.0 * factor
        },
    )
}

/** Constant speed plus a fixed time per metre climbed, as in the original web prototype. */
data class ConstantSpeedPace(val kmh: Double = 4.0, val secondsPerMetreClimbed: Double = 6.0) : PaceModel {
    override fun schedule(route: SampledRoute): Schedule = Schedule.fromSpans(
        DoubleArray(route.steps.size) { i ->
            route.steps[i].length * 3.6 / kmh + climb(route, i).first * secondsPerMetreClimbed
        },
    )
}

/**
 * Replays the timing of a recorded track (relative to its own start), e.g. to see how last
 * summer's walk would fare in winter. Requires timestamps on every point.
 */
data class RecordedPace(val factor: Double = 1.0) : PaceModel {
    override fun schedule(route: SampledRoute): Schedule {
        require(route.route.hasTimestamps) { "The track has no timestamps" }
        return Schedule.fromSpans(
            DoubleArray(route.steps.size) { i ->
                val s = route.steps[i]
                max(0.0, (s.end.timeMillis!! - s.start.timeMillis!!) / 1000.0) * factor
            },
        )
    }
}
