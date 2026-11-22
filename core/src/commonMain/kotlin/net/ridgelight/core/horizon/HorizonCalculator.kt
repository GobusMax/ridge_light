package net.ridgelight.core.horizon

import net.ridgelight.core.dem.TerrainModel
import net.ridgelight.core.dem.WebMercator
import net.ridgelight.core.geo.DEG
import net.ridgelight.core.geo.EARTH_RADIUS_M
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

data class HorizonParams(
    /** Eye height above the ground. */
    val eyeHeight: Double = 1.7,
    /**
     * First sample distance. Starting too close makes the observer "see" the smoothed DEM surface
     * right next to them (e.g. on a sharp ridge), so this is raised to 1.5 DEM pixels if larger.
     */
    val minDistance: Double = 30.0,
    /** How far terrain is searched. Should not exceed the coarsest tile ring of the TilePlanner. */
    val maxDistance: Double = 45_000.0,
    /** Terrestrial refraction coefficient; light bends around the Earth with ~1/k of its radius. */
    val refractionK: Double = 0.13,
    /** Sample spacing: max(nearStep, relativeStep * distance). */
    val nearStep: Double = 15.0,
    val relativeStep: Double = 0.025,
)

/**
 * Terrain horizon in one direction.
 *
 * @property angle apparent elevation angle of the highest terrain in degrees (NaN if no terrain
 *   data at all). With `complete == false` some samples were missing, so the true horizon may be
 *   higher.
 */
data class Horizon(val angle: Double, val complete: Boolean)

/**
 * Ray-marches through the terrain from an observer toward an azimuth and returns the highest
 * apparent elevation angle, accounting for Earth curvature and terrestrial refraction.
 */
class HorizonCalculator(private val terrain: TerrainModel, val params: HorizonParams = HorizonParams()) {
    private val distances: DoubleArray
    private val drops: DoubleArray
    // Sine and cosine of each sample's angular distance, so a ray step costs no trigonometry for it.
    private val sinDist: DoubleArray
    private val cosDist: DoubleArray

    init {
        val list = ArrayList<Double>()
        var d = params.minDistance
        while (d < params.maxDistance) {
            list += d
            d += max(params.nearStep, d * params.relativeStep)
        }
        list += params.maxDistance
        distances = list.toDoubleArray()
        val effectiveRadius = EARTH_RADIUS_M / (1 - params.refractionK)
        drops = DoubleArray(distances.size) { distances[it] * distances[it] / (2 * effectiveRadius) }
        sinDist = DoubleArray(distances.size) { sin(distances[it] / EARTH_RADIUS_M) }
        cosDist = DoubleArray(distances.size) { cos(distances[it] / EARTH_RADIUS_M) }
    }

    val sampleCount: Int get() = distances.size

    /** Observer eye altitude for a ground height. */
    fun eyeAltitude(ground: Double): Double = ground + params.eyeHeight

    /**
     * @param eyeAltitude absolute altitude of the observer's eyes in metres.
     * @param stopAboveDeg stop early once terrain higher than this is found (the returned angle is
     *   then only a lower bound); infinity computes the full horizon.
     */
    fun horizon(lat: Double, lon: Double, eyeAltitude: Double, azimuthDeg: Double, stopAboveDeg: Double = Double.POSITIVE_INFINITY): Horizon {
        if (!eyeAltitude.isFinite()) return Horizon(Double.NaN, false)
        val stopTan = if (stopAboveDeg.isFinite()) tan(stopAboveDeg * DEG) else Double.POSITIVE_INFINITY
        val start = max(params.minDistance, 1.5 * WebMercator.pixelSize(lat, terrain.finestZoom, terrain.tileSize))
        // Points along the great circle (see GreatCircleRay), straight into Web-Mercator coordinates.
        val sinLat0 = sin(lat * DEG)
        val cosLat0 = cos(lat * DEG)
        val sinB = sin(azimuthDeg * DEG)
        val cosB = cos(azimuthDeg * DEG)
        val x0 = WebMercator.x(lon)
        var best = Double.NEGATIVE_INFINITY
        var missing = false
        for (i in distances.indices) {
            val d = distances[i]
            if (d < start) continue
            val sinD = sinDist[i]
            val cosD = cosDist[i]
            val sinLat = (sinLat0 * cosD + cosLat0 * sinD * cosB).coerceIn(-1.0, 1.0)
            val x = x0 + atan2(sinB * sinD * cosLat0, cosD - sinLat0 * sinLat) / (2 * PI)
            val h = terrain.heightMercator(x - floor(x), WebMercator.yOfSinLat(sinLat))
            if (h.isNaN()) {
                missing = true
                continue
            }
            val t = (h - eyeAltitude - drops[i]) / d
            if (t > best) {
                best = t
                if (best > stopTan) break
            }
        }
        val angle = if (best == Double.NEGATIVE_INFINITY) Double.NaN else atan(best) / DEG
        return Horizon(angle, !missing)
    }

    /** Full horizon profile, e.g. for display or debugging. */
    fun profile(lat: Double, lon: Double, eyeAltitude: Double, stepDeg: Double = 1.0): List<Pair<Double, Horizon>> {
        val n = (360.0 / stepDeg).toInt()
        return List(n) { i -> (i * stepDeg).let { az -> az to horizon(lat, lon, eyeAltitude, az) } }
    }

    /**
     * Angle of the sea-level horizon below the observer's eye level, in degrees (≤ 0). The sun
     * counts as "below the horizon" when it is lower than this, even with no terrain in the way.
     */
    fun unobstructedHorizon(eyeAltitude: Double): Double {
        if (!(eyeAltitude > 0)) return 0.0
        val r = EARTH_RADIUS_M / (1 - params.refractionK)
        return -acos(r / (r + eyeAltitude)) / DEG
    }
}
