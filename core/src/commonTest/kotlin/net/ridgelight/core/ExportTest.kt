package net.ridgelight.core

import net.ridgelight.core.analysis.Light
import net.ridgelight.core.analysis.SunAnalyzer
import net.ridgelight.core.dem.TileLevel
import net.ridgelight.core.geo.LatLon
import net.ridgelight.core.gpx.ExportLabels
import net.ridgelight.core.gpx.GpxReader
import net.ridgelight.core.gpx.IsoTime
import net.ridgelight.core.gpx.LightStyle
import net.ridgelight.core.gpx.OsmAndExport
import net.ridgelight.core.horizon.HorizonParams
import net.ridgelight.core.pace.Din33466Pace
import net.ridgelight.core.route.Route
import net.ridgelight.core.route.RouteException
import net.ridgelight.core.route.SampledRoute
import net.ridgelight.core.route.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExportTest {
    private val origin = LatLon(46.5, 8.0)

    @Test
    fun exportRoundTripsOneSegmentPerStretch() {
        // A wall to the south that only covers the middle third of an eastward walk.
        val lonA = origin.lon + 0.004
        val lonB = origin.lon + 0.008
        val terrain = Synthetic.terrain(listOf(origin, LatLon(origin.lat, origin.lon + 0.012)), listOf(TileLevel(13, 6_000.0))) { lat, lon ->
            if (Synthetic.northOf(origin, lat) < -300 && lon in lonA..lonB) 1_500.0 else 500.0
        }
        val pts = List(25) { TrackPoint(origin.lat, origin.lon + 0.0005 * it) }
        val sampled = SampledRoute.of(Route.of(pts), terrain)
        val analyzer = SunAnalyzer(sampled, terrain, HorizonParams(maxDistance = 5_000.0))
        val result = analyzer.analyse(Din33466Pace().schedule(sampled), IsoTime.parse("2025-12-21T11:00:00Z")!!)
        assertEquals(listOf(Light.SUN, Light.TERRAIN_SHADE, Light.SUN), result.runs.map { it.light })

        val files = OsmAndExport.files(result, "Walk") { "12:00" }
        assertEquals(setOf("ridgelight-sun.gpx", "ridgelight-terrain-shade.gpx"), files.map { it.fileName }.toSet())
        val sun = files.first { it.light == Light.SUN }
        assertTrue(sun.gpx.contains("<osmand:color>${LightStyle.color(Light.SUN)}</osmand:color>"))
        assertEquals(2, GpxReader.read(sun.gpx).segments.size, "two sunny stretches")
        assertEquals(1, GpxReader.read(files.first { it.light == Light.TERRAIN_SHADE }.gpx).segments.size)
        assertEquals(2, Regex("<wpt ").findAll(sun.gpx).count(), "transitions as waypoints")
        assertEquals("Shade from 12:00", OsmAndExport.transitions(result) { "12:00" }.first().name)
        assertEquals("Terrain shade for ${LightStyle.duration(result.runs[1].seconds)} of walking", OsmAndExport.transitions(result) { "12:00" }.first().description)

        // Another language: every name comes from the labels.
        val german = object : ExportLabels {
            override fun light(light: Light) = if (light == Light.SUN) "Sonne" else "Schatten"
            override fun change(light: Light, time: String) = "${light(light)} ab $time"
        }
        assertEquals("Schatten ab 12:00", OsmAndExport.transitions(result, german) { "12:00" }.first().name)
        assertTrue(OsmAndExport.files(result, "Weg", german) { "12:00" }.first { it.light == Light.SUN }.gpx.contains("Weg · Sonne"))
    }

    @Test
    fun routeErrorsCarryAReason() {
        val e = assertFailsWith<RouteException> { GpxReader.read("<kml/>") }
        assertEquals(RouteException.Reason.NOT_GPX, e.reason)
        assertEquals(RouteException.Reason.TOO_SHORT, assertFailsWith<RouteException> { Route.of(listOf(TrackPoint(46.0, 8.0), TrackPoint(46.0001, 8.0))) }.reason)
        assertEquals(RouteException.Reason.NO_POINTS, assertFailsWith<RouteException> { GpxReader.read("<gpx><wpt lat=\"46\" lon=\"8\"/></gpx>") }.reason)
    }

    @Test
    fun durationFormatting() {
        assertEquals("42 min", LightStyle.duration(42 * 60.0))
        assertEquals("1h 05m", LightStyle.duration(65 * 60.0))
    }
}
