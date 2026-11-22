package net.ridgelight.core.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

const val EARTH_RADIUS_M = 6_371_008.8
const val DEG = PI / 180.0

data class LatLon(val lat: Double, val lon: Double)

object Geo {
    /** Great-circle distance in metres (haversine). */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * DEG
        val dLon = (lon2 - lon1) * DEG
        val h = sin(dLat / 2).let { it * it } +
            cos(lat1 * DEG) * cos(lat2 * DEG) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    fun distance(a: LatLon, b: LatLon): Double = distance(a.lat, a.lon, b.lat, b.lon)

    /** Wraps a longitude into [-180, 180). */
    fun wrapLon(lon: Double): Double = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    /** Linear interpolation between two positions; fine for the short segments of a track. */
    fun lerp(a: LatLon, b: LatLon, f: Double): LatLon {
        val dLon = wrapLon(b.lon - a.lon)
        return LatLon(a.lat + (b.lat - a.lat) * f, wrapLon(a.lon + dLon * f))
    }

    fun bearing(a: LatLon, b: LatLon): Double {
        val p1 = a.lat * DEG
        val p2 = b.lat * DEG
        val dl = (b.lon - a.lon) * DEG
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (atan2(y, x) / DEG + 360.0) % 360.0
    }
}

/**
 * Walks great circles from one origin. Precomputes the trigonometry that only depends on the
 * origin and bearing so that the per-sample cost of a horizon ray stays small.
 */
class GreatCircleRay(lat: Double, lon: Double, bearingDeg: Double) {
    private val lon0 = lon * DEG
    private val sinLat0 = sin(lat * DEG)
    private val cosLat0 = cos(lat * DEG)
    private val sinB = sin(bearingDeg * DEG)
    private val cosB = cos(bearingDeg * DEG)

    var lat = 0.0
        private set
    var lon = 0.0
        private set

    /** Moves the cursor to the point at [distanceM] along the ray; read it from [lat]/[lon]. */
    fun moveTo(distanceM: Double) {
        val delta = distanceM / EARTH_RADIUS_M
        val sinD = sin(delta)
        val cosD = cos(delta)
        val sinLat = sinLat0 * cosD + cosLat0 * sinD * cosB
        val latR = asin(sinLat.coerceIn(-1.0, 1.0))
        val lonR = lon0 + atan2(sinB * sinD * cosLat0, cosD - sinLat0 * sinLat)
        lat = latR / DEG
        lon = Geo.wrapLon(lonR / DEG)
    }
}
