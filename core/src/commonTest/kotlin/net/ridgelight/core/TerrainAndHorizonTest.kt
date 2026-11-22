package net.ridgelight.core

import net.ridgelight.core.analysis.Light
import net.ridgelight.core.analysis.classify
import net.ridgelight.core.dem.TileLevel
import net.ridgelight.core.geo.DEG
import net.ridgelight.core.geo.Geo
import net.ridgelight.core.geo.GreatCircleRay
import net.ridgelight.core.geo.LatLon
import net.ridgelight.core.horizon.Horizon
import net.ridgelight.core.horizon.HorizonCalculator
import net.ridgelight.core.horizon.HorizonParams
import net.ridgelight.core.sun.SunPosition
import kotlin.math.abs
import kotlin.math.atan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerrainAndHorizonTest {
    private val origin = LatLon(46.5, 8.0)
    private val levels = listOf(TileLevel(13, 9_000.0))
    private val params = HorizonParams(maxDistance = 8_000.0)

    private fun near(expected: Double, actual: Double, tol: Double, what: String) =
        assertTrue(abs(expected - actual) <= tol, "$what: expected $expected ± $tol, got $actual")

    @Test
    fun greatCircleRayRoundTrips() {
        val ray = GreatCircleRay(origin.lat, origin.lon, 37.0)
        ray.moveTo(20_000.0)
        val p = LatLon(ray.lat, ray.lon)
        near(20_000.0, Geo.distance(origin, p), 0.01, "distance")
        near(37.0, Geo.bearing(origin, p), 0.01, "bearing")
    }

    @Test
    fun bilinearSamplingReproducesAPlane() {
        val terrain = Synthetic.terrain(listOf(origin), levels) { lat, _ -> 1000 + Synthetic.northOf(origin, lat) * 0.1 }
        for (d in listOf(0.0, 123.0, 2_500.0, -4_000.0)) {
            val lat = origin.lat + d / 6_371_008.8 / DEG
            near(1000 + d * 0.1, terrain.height(lat, origin.lon + 0.0123), 0.05, "height at $d m")
        }
    }

    @Test
    fun flatTerrainHorizonIsJustBelowEyeLevel() {
        val terrain = Synthetic.terrain(listOf(origin), levels) { _, _ -> 500.0 }
        val calc = HorizonCalculator(terrain, params)
        val h = calc.horizon(origin.lat, origin.lon, calc.eyeAltitude(500.0), 123.0)
        assertTrue(h.complete)
        near(-0.04, h.angle, 0.02, "flat horizon")
    }

    @Test
    fun slopeRisingNorthGivesItsAngle() {
        // Terrain rising at 30 % toward the north, flat to the south.
        val terrain = Synthetic.terrain(listOf(origin), levels) { lat, _ ->
            500 + 0.3 * Synthetic.northOf(origin, lat).coerceAtLeast(0.0)
        }
        val calc = HorizonCalculator(terrain, params)
        val north = calc.horizon(origin.lat, origin.lon, calc.eyeAltitude(500.0), 0.0)
        near(atan(0.3) / DEG, north.angle, 0.1, "northern horizon")
        val south = calc.horizon(origin.lat, origin.lon, calc.eyeAltitude(500.0), 180.0)
        assertTrue(south.angle < 0.1, "southern horizon ${south.angle}")
    }

    @Test
    fun wallAtKnownDistance() {
        // A 1000 m high step 2 km east of the observer.
        val terrain = Synthetic.terrain(listOf(origin), levels) { _, lon -> if (lon > origin.lon + 2_000.0 / (6_371_008.8 * kotlin.math.cos(origin.lat * DEG)) / DEG) 1500.0 else 500.0 }
        val calc = HorizonCalculator(terrain, params)
        val h = calc.horizon(origin.lat, origin.lon, calc.eyeAltitude(500.0), 90.0)
        near(atan(998.3 / 2000) / DEG, h.angle, 1.0, "wall angle")
    }

    @Test
    fun missingTerrainMakesTheHorizonIncomplete() {
        val terrain = Synthetic.terrain(listOf(origin), listOf(TileLevel(13, 1_000.0))) { _, _ -> 500.0 }
        val calc = HorizonCalculator(terrain, params)
        val h = calc.horizon(origin.lat, origin.lon, calc.eyeAltitude(500.0), 0.0)
        assertTrue(!h.complete)
    }

    @Test
    fun classification() {
        fun sun(alt: Double) = SunPosition(180.0, alt, alt)
        assertEquals(Light.SUN, classify(sun(20.0), Horizon(10.0, true), -0.5))
        assertEquals(Light.TERRAIN_SHADE, classify(sun(5.0), Horizon(10.0, true), -0.5))
        assertEquals(Light.TERRAIN_SHADE, classify(sun(5.0), Horizon(10.0, false), -0.5))
        assertEquals(Light.UNKNOWN, classify(sun(20.0), Horizon(10.0, false), -0.5))
        assertEquals(Light.BELOW_HORIZON, classify(sun(-1.0), Horizon(-3.0, true), -0.5))
        // From a summit the sun stays visible a little below 0°.
        assertEquals(Light.SUN, classify(sun(-0.8), Horizon(-1.2, true), -1.4))
    }
}
