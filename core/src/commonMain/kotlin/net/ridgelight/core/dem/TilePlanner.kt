package net.ridgelight.core.dem

import net.ridgelight.core.geo.DEG
import net.ridgelight.core.geo.EARTH_RADIUS_M
import net.ridgelight.core.geo.Geo
import net.ridgelight.core.geo.LatLon
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Tiles at [zoom] are needed within [radiusM] of the route. */
data class TileLevel(val zoom: Int, val radiusM: Double)

/** Decides which elevation tiles an analysis needs, so the platform can fetch them up front. */
object TilePlanner {
    /**
     * Fine tiles (z13, ~13 m pixels in the Alps) close to the route, where nearby slopes decide
     * the light; coarse ones (z11) out to the horizon distance for distant ridges.
     */
    val DEFAULT_LEVELS = listOf(TileLevel(13, 3_600.0), TileLevel(11, 46_000.0))

    fun forRoute(points: List<LatLon>, levels: List<TileLevel> = DEFAULT_LEVELS): Set<TileKey> {
        val out = LinkedHashSet<TileKey>()
        for (level in levels) {
            // Spacing between probe points well below the radius keeps the union gap-free.
            val spacing = min(level.radiusM / 4, 500.0)
            var last: LatLon? = null
            for ((i, p) in points.withIndex()) {
                val isLast = i == points.size - 1
                if (last != null && !isLast && Geo.distance(last, p) < spacing) continue
                addAround(p, level, out)
                last = p
            }
        }
        return out
    }

    fun forPoint(point: LatLon, levels: List<TileLevel> = DEFAULT_LEVELS): Set<TileKey> =
        forRoute(listOf(point), levels)

    private fun addAround(p: LatLon, level: TileLevel, out: MutableSet<TileKey>) {
        val z = level.zoom
        val r = level.radiusM + 2 * WebMercator.pixelSize(p.lat, z)
        val dLat = r / EARTH_RADIUS_M / DEG
        val north = min(85.0, p.lat + dLat)
        val south = max(-85.0, p.lat - dLat)
        val dLon = r / (EARTH_RADIUS_M * cos(max(abs(north), abs(south)) * DEG)) / DEG
        val x0 = floor(WebMercator.tileX(p.lon - dLon, z)).toInt()
        val x1 = floor(WebMercator.tileX(p.lon + dLon, z)).toInt()
        val y0 = floor(WebMercator.tileY(north, z)).toInt().coerceAtLeast(0)
        val y1 = floor(WebMercator.tileY(south, z)).toInt().coerceAtMost((1 shl z) - 1)
        for (y in y0..y1) for (x in x0..x1) out += TileKey(z, WebMercator.wrapX(x, z), y)
    }
}
