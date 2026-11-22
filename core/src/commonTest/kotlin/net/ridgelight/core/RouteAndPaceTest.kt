package net.ridgelight.core

import net.ridgelight.core.analysis.Light
import net.ridgelight.core.analysis.SunAnalyzer
import net.ridgelight.core.dem.TileLevel
import net.ridgelight.core.geo.DEG
import net.ridgelight.core.geo.EARTH_RADIUS_M
import net.ridgelight.core.geo.LatLon
import net.ridgelight.core.gpx.GpxReader
import net.ridgelight.core.gpx.IsoTime
import net.ridgelight.core.horizon.HorizonParams
import net.ridgelight.core.pace.ConstantSpeedPace
import net.ridgelight.core.pace.Din33466Pace
import net.ridgelight.core.pace.RecordedPace
import net.ridgelight.core.route.ElevationSource
import net.ridgelight.core.route.Route
import net.ridgelight.core.route.SampledRoute
import net.ridgelight.core.route.TrackPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteAndPaceTest {
    private val origin = LatLon(46.5, 8.0)
    private val levels = listOf(TileLevel(13, 6_000.0))

    private fun near(expected: Double, actual: Double, tol: Double, what: String) =
        assertTrue(abs(expected - actual) <= tol, "$what: expected $expected ± $tol, got $actual")

    private fun northLine(metres: Double, points: Int = 50) = List(points + 1) {
        TrackPoint(origin.lat + metres * it / points / EARTH_RADIUS_M / DEG, origin.lon)
    }

    @Test
    fun resamplingKeepsLengthAndEqualSteps() {
        val terrain = Synthetic.terrain(listOf(origin), levels) { _, _ -> 700.0 }
        val route = Route.of(northLine(1_000.0, 7))
        val s = SampledRoute.of(route, terrain, maxStepM = 25.0)
        near(1_000.0, route.length, 0.5, "route length")
        assertEquals(kotlin.math.ceil(route.length / 25.0).toInt(), s.steps.size)
        assertTrue(s.steps.all { abs(it.length - route.length / s.steps.size) < 0.01 })
        near(route.length, s.steps.sumOf { it.length }, 1e-6, "sum of steps")
        near(700.0, s.steps[3].midGround, 0.01, "ground")
    }

    @Test
    fun dinTimesOnFlatAndSteepGround() {
        val flat = Synthetic.terrain(listOf(origin), listOf(TileLevel(13, 6_000.0))) { _, _ -> 700.0 }
        val flatRoute = SampledRoute.of(Route.of(northLine(4_000.0)), flat)
        near(3_600.0, Din33466Pace().schedule(flatRoute).duration, 1.0, "4 km flat")

        val slope = Synthetic.terrain(listOf(origin), levels) { lat, _ -> 500 + 0.3 * Synthetic.northOf(origin, lat) }
        val steep = SampledRoute.of(Route.of(northLine(1_000.0)), slope)
        // 1 km and 300 m up: max(0.25 h, 1 h) + 0.25 h / 2 = 1.125 h.
        near(300.0, steep.ascent, 1.0, "smoothing keeps the net climb of a ramp")
        near(4_050.0, Din33466Pace().schedule(steep).duration, 10.0, "1 km at 30 %")
        near(4_050.0 * 0.8, Din33466Pace(factor = 0.8).schedule(steep).duration, 10.0, "faster hiker")
        near(900.0 + 300 * 6, ConstantSpeedPace(4.0, 6.0).schedule(steep).duration, 5.0, "legacy pace")
    }

    @Test
    fun smoothingRemovesNoiseButKeepsEnds() {
        val noisy = DoubleArray(41) { 1000.0 + if (it % 2 == 0) 5.0 else -5.0 }
        val smooth = SampledRoute.smooth(noisy, 4)
        assertEquals(noisy.first(), smooth.first())
        assertEquals(noisy.last(), smooth.last())
        assertTrue(smooth.slice(4..36).all { abs(it - 1000.0) < 1.5 }, smooth.joinToString())
    }

    @Test
    fun elevationSourceChoice() {
        val terrain = Synthetic.terrain(listOf(origin), levels) { _, _ -> 700.0 }
        val withEle = northLine(1_000.0, 10).mapIndexed { i, p -> p.copy(ele = 1000.0 + 10 * i) }
        val auto = SampledRoute.of(Route.of(withEle), terrain)
        near(100.0, auto.ascent, 0.5, "GPX elevations used when complete")
        val dem = SampledRoute.of(Route.of(withEle), terrain, elevationSource = ElevationSource.DEM)
        near(0.0, dem.ascent, 1e-6, "flat DEM")
        val partial = withEle.mapIndexed { i, p -> if (i == 5) p.copy(ele = Double.NaN) else p }
        near(0.0, SampledRoute.of(Route.of(partial), terrain).ascent, 1e-6, "incomplete GPX elevations fall back to DEM")
    }

    @Test
    fun recordedPaceReplaysTimestamps() {
        val terrain = Synthetic.terrain(listOf(origin), levels) { _, _ -> 700.0 }
        val t0 = IsoTime.parse("2025-07-01T08:00:00Z")!!
        val pts = northLine(2_000.0, 2).mapIndexed { i, p -> p.copy(timeMillis = t0 + i * 900_000L) }
        val s = SampledRoute.of(Route.of(pts), terrain)
        near(1_800.0, RecordedPace().schedule(s).duration, 1.0, "recorded duration")
    }

    @Test
    fun shadowedValleyAnalysis() {
        // East-west valley: a 1500 m wall 1 km to the south. The midwinter sun never clears it.
        val terrain = Synthetic.terrain(listOf(origin), levels) { lat, _ ->
            if (Synthetic.northOf(origin, lat) < -1_000) 2_000.0 else 500.0
        }
        val east = List(20) { TrackPoint(origin.lat, origin.lon + 0.0005 * it) }
        val s = SampledRoute.of(Route.of(east), terrain)
        val analyzer = SunAnalyzer(s, terrain, HorizonParams(maxDistance = 5_000.0))
        val schedule = Din33466Pace().schedule(s)
        val noonWinter = IsoTime.parse("2025-12-21T11:00:00Z")!!
        val result = analyzer.analyse(schedule, noonWinter)
        assertEquals(setOf(Light.TERRAIN_SHADE), result.lights.toSet())
        val noonSummer = IsoTime.parse("2025-06-21T11:00:00Z")!!
        assertEquals(setOf(Light.SUN), analyzer.analyse(schedule, noonSummer).lights.toSet())
        val night = IsoTime.parse("2025-06-21T23:00:00Z")!!
        assertEquals(setOf(Light.BELOW_HORIZON), analyzer.analyse(schedule, night).lights.toSet())
        near(schedule.duration, result.totals.values.sum(), 1e-6, "totals add up")
    }

    @Test
    fun gpxParsing() {
        val xml = """<?xml version="1.0"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata><name>Meta name</name></metadata>
              <trk><name>Ridge &amp; Valley</name>
                <trkseg>
                  <trkpt lat="46.1" lon="8.1"><ele>1000</ele><time>2025-07-01T08:00:00Z</time></trkpt>
                  <trkpt lon="8.2" lat="46.2"><ele>1100.5</ele><time>2025-07-01T09:30:00.500+02:00</time></trkpt>
                </trkseg>
                <trkseg><trkpt lat="46.3" lon="8.3"/></trkseg>
              </trk>
            </gpx>"""
        val gpx = GpxReader.read(xml)
        assertEquals("Ridge & Valley", gpx.name)
        assertEquals(listOf(2, 1), gpx.segments.map { it.size })
        assertEquals(8.2, gpx.points[1].lon)
        assertEquals(1100.5, gpx.points[1].ele)
        assertEquals(IsoTime.parse("2025-07-01T07:30:00.500Z"), gpx.points[1].timeMillis)
        assertTrue(gpx.points[2].ele.isNaN())
    }

    @Test
    fun isoTime() {
        assertEquals(0L, IsoTime.parse("1970-01-01T00:00:00Z"))
        assertEquals(951_782_400_000L, IsoTime.parse("2000-02-29T00:00:00Z"))
        assertEquals(IsoTime.parse("2025-01-01T00:00:00Z")!! - 3_600_000, IsoTime.parse("2025-01-01T00:00:00+01:00"))
    }
}
