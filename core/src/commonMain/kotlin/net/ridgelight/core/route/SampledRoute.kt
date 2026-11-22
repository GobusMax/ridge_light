package net.ridgelight.core.route

import net.ridgelight.core.dem.TerrainModel
import net.ridgelight.core.geo.Geo
import net.ridgelight.core.geo.LatLon
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** A point at an equal-spacing boundary along the route. */
class RouteNode(
    val distance: Double,
    val position: LatLon,
    /** DEM terrain height (NaN if unknown). */
    val ground: Double,
    /** Smoothed elevation used for walking times (see [ElevationSource]). */
    val elevation: Double,
    /** GPX time interpolated along the route, if every input point had one. */
    val timeMillis: Long?,
)

/**
 * The stretch between two nodes. Light is evaluated at its midpoint; [path] keeps the original
 * geometry (both end nodes plus any input points in between) for drawing.
 */
class RouteStep(
    val index: Int,
    val start: RouteNode,
    val end: RouteNode,
    val mid: LatLon,
    val midGround: Double,
    val path: List<LatLon>,
) {
    val length: Double get() = end.distance - start.distance
}

/** Where walking-time elevations come from. */
enum class ElevationSource {
    /** The track's own elevations if every point has one, else the terrain model. */
    AUTO,

    /** The track's elevations (planned routes usually carry good ones; GPS recordings are noisier). */
    GPX,

    /**
     * The terrain model. Robust when the track has no elevations, but on trails cut into a slope
     * the samples pick up the hillside beside the trail, hence the stronger smoothing.
     */
    DEM,
}

class SampledRoute(
    val route: Route,
    val nodes: List<RouteNode>,
    val steps: List<RouteStep>,
) {
    val length: Double get() = route.length

    /** Total climb and descent in metres, from the smoothed elevations. */
    val ascent: Double by lazy { climb(1.0) }
    val descent: Double by lazy { climb(-1.0) }

    private fun climb(sign: Double) = nodes.zipWithNext().sumOf { (a, b) ->
        val dz = (b.elevation - a.elevation) * sign
        if (dz.isFinite() && dz > 0) dz else 0.0
    }

    companion object {
        const val GPX_SMOOTHING_M = 50.0
        const val DEM_SMOOTHING_M = 150.0

        /**
         * Splits [route] into equal steps of at most [maxStepM] metres and attaches terrain heights.
         * Elevations for the pace model come from [elevationSource] and are smoothed over about
         * ±[smoothingM] metres (default depends on the source) so that DEM or GPS noise does not
         * turn into phantom climbing.
         */
        fun of(
            route: Route,
            terrain: TerrainModel,
            maxStepM: Double = 25.0,
            elevationSource: ElevationSource = ElevationSource.AUTO,
            smoothingM: Double? = null,
        ): SampledRoute {
            val count = max(1, ceil(route.length / maxStepM).toInt())
            val pts = route.points
            val useTime = route.hasTimestamps

            var cursor = 0
            fun at(d: Double): Pair<LatLon, Pair<Double, Long?>> {
                while (cursor < pts.size - 2 && pts[cursor + 1].distance < d) cursor++
                val a = pts[cursor]
                val b = pts[cursor + 1]
                val f = ((d - a.distance) / (b.distance - a.distance)).coerceIn(0.0, 1.0)
                val pos = Geo.lerp(a.latLon, b.latLon, f)
                val ele = if (a.ele.isFinite() && b.ele.isFinite()) a.ele + (b.ele - a.ele) * f else Double.NaN
                val time = if (useTime) a.timeMillis!! + ((b.timeMillis!! - a.timeMillis) * f).toLong() else null
                return pos to (ele to time)
            }

            val positions = ArrayList<LatLon>(count + 1)
            val gpxEle = DoubleArray(count + 1)
            val times = arrayOfNulls<Long>(count + 1)
            val distances = DoubleArray(count + 1) { route.length * it / count }
            for (i in 0..count) {
                val (pos, rest) = at(distances[i])
                positions += pos
                gpxEle[i] = rest.first
                times[i] = rest.second
            }
            val ground = DoubleArray(count + 1) { terrain.height(positions[it].lat, positions[it].lon) }
            val useGpx = when (elevationSource) {
                ElevationSource.GPX -> true
                ElevationSource.DEM -> false
                ElevationSource.AUTO -> route.points.all { it.ele.isFinite() }
            }
            val raw = DoubleArray(count + 1) {
                val preferred = if (useGpx) gpxEle[it] else ground[it]
                val fallback = if (useGpx) ground[it] else gpxEle[it]
                if (preferred.isFinite()) preferred else fallback
            }
            val window = smoothingM ?: if (useGpx) GPX_SMOOTHING_M else DEM_SMOOTHING_M
            val smooth = smooth(raw, (window / (route.length / count)).roundToInt())

            val nodes = List(count + 1) { RouteNode(distances[it], positions[it], ground[it], smooth[it], times[it]) }

            val steps = ArrayList<RouteStep>(count)
            var ip = 0
            for (j in 0 until count) {
                val a = nodes[j]
                val b = nodes[j + 1]
                val path = ArrayList<LatLon>()
                path += a.position
                while (ip < pts.size && pts[ip].distance <= a.distance) ip++
                while (ip < pts.size && pts[ip].distance < b.distance) path += pts[ip++].latLon
                path += b.position
                val mid = midpoint(path, b.distance - a.distance)
                steps += RouteStep(j, a, b, mid, terrain.height(mid.lat, mid.lon), path)
            }
            return SampledRoute(route, nodes, steps)
        }

        /** Position halfway (by distance) along a step's path. */
        private fun midpoint(path: List<LatLon>, length: Double): LatLon {
            if (path.size == 2) return Geo.lerp(path[0], path[1], 0.5)
            val half = length / 2
            var acc = 0.0
            for (k in 1 until path.size) {
                val seg = Geo.distance(path[k - 1], path[k])
                if (acc + seg >= half && seg > 0) return Geo.lerp(path[k - 1], path[k], (half - acc) / seg)
                acc += seg
            }
            return path.last()
        }

        /**
         * Triangular moving average that ignores NaNs. The window shrinks symmetrically near the
         * ends, so endpoint elevations (and thus net climb) are preserved and ramps stay ramps.
         */
        internal fun smooth(values: DoubleArray, radius: Int): DoubleArray {
            if (radius <= 0) return values.copyOf()
            return DoubleArray(values.size) { i ->
                val r = minOf(radius, i, values.size - 1 - i)
                if (r == 0) return@DoubleArray values[i]
                var sum = 0.0
                var weight = 0.0
                for (k in i - r..i + r) {
                    val v = values[k]
                    if (!v.isFinite()) continue
                    val w = (r + 1 - kotlin.math.abs(k - i)).toDouble()
                    sum += v * w
                    weight += w
                }
                if (weight > 0) sum / weight else Double.NaN
            }
        }
    }
}
