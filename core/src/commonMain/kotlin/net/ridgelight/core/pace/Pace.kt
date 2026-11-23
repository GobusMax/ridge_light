package net.ridgelight.core.pace

import net.ridgelight.core.route.SampledRoute
import kotlin.math.max
import kotlin.math.min

/** A planned stop at a place on the route: [distance] metres from the start, for [seconds]. */
data class Break(val distance: Double, val seconds: Double) {
    init {
        require(seconds > 0) { "A break needs a duration" }
    }
}

/**
 * A break placed in a schedule: at route node [node] (after step `node - 1`, before step
 * `node`), from [startSeconds] after departure.
 */
class ScheduledBreak(val node: Int, val startSeconds: Double, val seconds: Double) {
    val endSeconds: Double get() = startSeconds + seconds
}

/** When each step is walked, in seconds after departure, and where the hiker stops. */
class Schedule(
    val stepStart: DoubleArray,
    val stepSpan: DoubleArray,
    /** Ordered by [ScheduledBreak.node]; the steps after each break start that much later. */
    val breaks: List<ScheduledBreak> = emptyList(),
) {
    init {
        require(stepStart.size == stepSpan.size)
    }

    /** Departure to arrival, breaks included. */
    val duration: Double get() = if (stepStart.isEmpty()) 0.0 else stepStart.last() + stepSpan.last()
    val breakSeconds: Double get() = breaks.sumOf { it.seconds }
    fun midTime(step: Int): Double = stepStart[step] + stepSpan[step] / 2

    /**
     * This schedule with [stops] added. Each break snaps to the nearest node inside the route
     * (a break at the very start or end would only shift or extend the day); breaks at the same
     * node add up.
     */
    fun withBreaks(route: SampledRoute, stops: List<Break>): Schedule {
        require(breaks.isEmpty()) { "Schedule already has breaks" }
        require(route.steps.size == stepStart.size) { "Schedule does not match route" }
        if (stops.isEmpty() || stepStart.size < 2) return this
        val extra = DoubleArray(stepStart.size)
        for (b in stops) extra[nearestInnerNode(route, b.distance)] += b.seconds
        val start = DoubleArray(stepStart.size)
        val placed = ArrayList<ScheduledBreak>()
        var shift = 0.0
        for (i in stepStart.indices) {
            if (extra[i] > 0) {
                placed += ScheduledBreak(i, stepStart[i] + shift, extra[i])
                shift += extra[i]
            }
            start[i] = stepStart[i] + shift
        }
        return Schedule(start, stepSpan, placed)
    }

    private fun nearestInnerNode(route: SampledRoute, distance: Double): Int {
        val nodes = route.nodes
        var lo = 1
        var hi = nodes.size - 2
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            if (nodes[m].distance < distance) lo = m + 1 else hi = m
        }
        // lo is the first inner node at or past the distance; the one before may be closer.
        return if (lo > 1 && distance - nodes[lo - 1].distance < nodes[lo].distance - distance) lo - 1 else lo
    }

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
