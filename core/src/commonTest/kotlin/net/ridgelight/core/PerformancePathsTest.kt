package net.ridgelight.core

import net.ridgelight.core.analysis.SunAnalyzer
import net.ridgelight.core.analysis.TaskRunner
import net.ridgelight.core.dem.TerrainModel
import net.ridgelight.core.dem.TileKey
import net.ridgelight.core.dem.TileLevel
import net.ridgelight.core.geo.LatLon
import net.ridgelight.core.gpx.IsoTime
import net.ridgelight.core.horizon.HorizonParams
import net.ridgelight.core.pace.Din33466Pace
import net.ridgelight.core.route.Route
import net.ridgelight.core.route.SampledRoute
import net.ridgelight.core.route.TrackPoint
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The fast paths (tile grid, chunked analysis) must give exactly what the plain ones give. */
class PerformancePathsTest {
    /** Runs chunks in reverse order on one thread, to catch any dependence on chunk order. */
    private object Shuffled : TaskRunner {
        override val parallelism: Int get() = 3
        override fun runAll(tasks: List<() -> Unit>) = tasks.asReversed().forEach { it() }
    }

    @Test
    fun chunkedAnalysisMatchesSequential() {
        val origin = LatLon(46.5, 8.0)
        val hills = { lat: Double, lon: Double -> 800 + 400 * sin(lat * 900) * cos(lon * 700) }
        val pts = List(60) { TrackPoint(origin.lat + 0.0004 * it, origin.lon + 0.0003 * it) }
        val terrain = Synthetic.terrain(pts.map { LatLon(it.lat, it.lon) }, listOf(TileLevel(12, 6_000.0)), height = hills)
        val sampled = SampledRoute.of(Route.of(pts), terrain)
        val schedule = Din33466Pace().schedule(sampled)
        val departure = IsoTime.parse("2025-12-21T08:00:00Z")!!
        val departures = List(8) { departure + it * 1_800_000L }
        val params = HorizonParams(maxDistance = 5_000.0)

        val plain = SunAnalyzer(sampled, terrain, params)
        val chunked = SunAnalyzer(sampled, terrain, params, Shuffled)
        val a = plain.analyse(schedule, departure)
        val b = chunked.analyse(schedule, departure)
        assertEquals(a.samples.map { it.light }, b.samples.map { it.light })
        assertEquals(a.samples.map { it.horizonAngle }, b.samples.map { it.horizonAngle })
        assertEquals(plain.sweep(schedule, departures), chunked.sweep(schedule, departures))
        assertTrue(a.totals.values.sum() > 0)
    }

    @Test
    fun tilesAcrossTheAntimeridianAreFound() {
        val z = 4
        val last = (1 shl z) - 1
        val keys = listOf(TileKey(z, last, 6), TileKey(z, 0, 6)) // row 6 holds 40° N
        val height = { _: Double, lon: Double -> if (lon > 0) 100.0 else 200.0 }
        val terrain = TerrainModel(keys.map { Synthetic.tile(it, 16, height) })
        val lat = 40.0
        assertEquals(100.0, terrain.height(lat, 175.0), 1e-6)
        assertEquals(200.0, terrain.height(lat, -175.0), 1e-6)
        assertTrue(terrain.height(lat, 0.0).isNaN(), "no tile there")
    }
}
