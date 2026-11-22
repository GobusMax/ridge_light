package net.ridgelight.core

import net.ridgelight.core.dem.ElevationTile
import net.ridgelight.core.dem.TerrainModel
import net.ridgelight.core.dem.TileKey
import net.ridgelight.core.dem.TileLevel
import net.ridgelight.core.dem.TilePlanner
import net.ridgelight.core.geo.DEG
import net.ridgelight.core.geo.EARTH_RADIUS_M
import net.ridgelight.core.geo.LatLon
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/** Builds terrain tiles from an analytic height function, sampled at pixel centres. */
object Synthetic {
    fun tile(key: TileKey, size: Int = 256, height: (lat: Double, lon: Double) -> Double): ElevationTile {
        val n = (1 shl key.z).toDouble()
        val heights = FloatArray(size * size)
        for (j in 0 until size) {
            val y = key.y + (j + 0.5) / size
            val lat = atan(sinh(PI * (1 - 2 * y / n))) / DEG
            for (i in 0 until size) {
                val lon = (key.x + (i + 0.5) / size) / n * 360.0 - 180.0
                heights[j * size + i] = height(lat, lon).toFloat()
            }
        }
        return ElevationTile(key, heights)
    }

    fun terrain(around: List<LatLon>, levels: List<TileLevel>, size: Int = 64, height: (Double, Double) -> Double): TerrainModel =
        TerrainModel(TilePlanner.forRoute(around, levels).map { tile(it, size, height) })

    /** Metres north of [origin] (positive) along the meridian. */
    fun northOf(origin: LatLon, lat: Double) = (lat - origin.lat) * DEG * EARTH_RADIUS_M
}
