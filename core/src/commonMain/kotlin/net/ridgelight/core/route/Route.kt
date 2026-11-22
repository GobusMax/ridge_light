package net.ridgelight.core.route

import net.ridgelight.core.geo.Geo
import net.ridgelight.core.geo.LatLon
import kotlin.math.abs

/** A raw input point, e.g. from a GPX `trkpt`/`rtept`. [timeMillis] is UTC epoch millis. */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double = Double.NaN,
    val timeMillis: Long? = null,
)

/** A cleaned route point with its cumulative distance from the start in metres. */
data class RoutePoint(
    val lat: Double,
    val lon: Double,
    val ele: Double,
    val timeMillis: Long?,
    val distance: Double,
) {
    val latLon get() = LatLon(lat, lon)
}

class RouteException(message: String) : IllegalArgumentException(message)

/**
 * A continuous route: consecutive near-duplicate points removed, cumulative distances attached.
 * Segments of a multi-segment track are joined; joins wider than [gapThresholdM] are reported in
 * [gaps] because the route is then assumed to follow a straight line there.
 */
class Route private constructor(
    val points: List<RoutePoint>,
    val gaps: List<Gap>,
) {
    data class Gap(val distance: Double, val length: Double)

    val length: Double get() = points.last().distance
    val start: RoutePoint get() = points.first()
    val hasTimestamps: Boolean get() = points.all { it.timeMillis != null }

    companion object {
        const val MIN_LENGTH_M = 30.0
        const val MAX_LENGTH_M = 250_000.0
        private const val DUPLICATE_M = 0.5

        fun of(points: List<TrackPoint>): Route = ofSegments(listOf(points))

        fun ofSegments(segments: List<List<TrackPoint>>, gapThresholdM: Double = 100.0): Route {
            val clean = ArrayList<RoutePoint>()
            val gaps = ArrayList<Gap>()
            var total = 0.0
            for (segment in segments) {
                var firstOfSegment = true
                for (p in segment) {
                    if (!p.lat.isFinite() || !p.lon.isFinite() || abs(p.lat) > 85.0 || abs(p.lon) > 180.0) {
                        throw RouteException("The route contains invalid coordinates (${p.lat}, ${p.lon}).")
                    }
                    val last = clean.lastOrNull()
                    if (last != null) {
                        val d = Geo.distance(last.lat, last.lon, p.lat, p.lon)
                        if (d < DUPLICATE_M) continue
                        if (firstOfSegment && d > gapThresholdM) gaps += Gap(total, d)
                        total += d
                    }
                    clean += RoutePoint(p.lat, p.lon, p.ele, p.timeMillis, total)
                    firstOfSegment = false
                }
            }
            if (clean.size < 2 || total < MIN_LENGTH_M) throw RouteException("The route must be at least ${MIN_LENGTH_M.toInt()} m long.")
            if (total > MAX_LENGTH_M) throw RouteException("Routes longer than ${(MAX_LENGTH_M / 1000).toInt()} km are not supported; split the track.")
            return Route(clean, gaps)
        }
    }
}
