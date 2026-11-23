package net.ridgelight.core

import net.ridgelight.core.analysis.Light
import net.ridgelight.core.analysis.SunAnalyzer
import net.ridgelight.core.dem.TileLevel
import net.ridgelight.core.geo.LatLon
import net.ridgelight.core.gpx.IsoTime
import net.ridgelight.core.gpx.OsmAndExport
import net.ridgelight.core.horizon.HorizonParams
import net.ridgelight.core.pace.Break
import net.ridgelight.core.pace.Din33466Pace
import net.ridgelight.core.route.Route
import net.ridgelight.core.route.SampledRoute
import net.ridgelight.core.route.TrackPoint
import net.ridgelight.core.weather.Json
import net.ridgelight.core.weather.OpenMeteo
import net.ridgelight.core.weather.SkyCover
import net.ridgelight.core.weather.WeatherException
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BreaksAndWeatherTest {
    private val origin = LatLon(46.5, 8.0)

    /** 1.2 km eastward on flat ground, with a wall south of the middle third. */
    private fun setup(): Pair<SampledRoute, SunAnalyzer> {
        val lonA = origin.lon + 0.004
        val lonB = origin.lon + 0.008
        val terrain = Synthetic.terrain(listOf(origin, LatLon(origin.lat, origin.lon + 0.016)), listOf(TileLevel(13, 6_000.0))) { lat, lon ->
            if (Synthetic.northOf(origin, lat) < -300 && lon in lonA..lonB) 1_500.0 else 500.0
        }
        val sampled = SampledRoute.of(Route.of(List(33) { TrackPoint(origin.lat, origin.lon + 0.0005 * it) }), terrain)
        return sampled to SunAnalyzer(sampled, terrain, HorizonParams(maxDistance = 5_000.0))
    }

    @Test
    fun breaksSnapToNodesAndDelayLaterSteps() {
        val (route, _) = setup()
        val base = Din33466Pace().schedule(route)
        val s = base.withBreaks(route, listOf(Break(510.0, 600.0), Break(495.0, 300.0), Break(-5.0, 60.0)))
        assertEquals(2, s.breaks.size, "two breaks at the same node merge; one before the start snaps to node 1")
        val first = s.breaks.first()
        assertEquals(1, first.node)
        val mid = s.breaks[1]
        assertEquals(900.0, mid.seconds)
        assertTrue(abs(route.nodes[mid.node].distance - 500) <= 12.5, "nearest node to 500 m")
        assertEquals(base.duration + 960.0, s.duration, 1e-9)
        assertEquals(base.stepStart[mid.node] + 60.0, mid.startSeconds, 1e-9)
        assertEquals(base.stepStart[mid.node] + 960.0, s.stepStart[mid.node], 1e-9)
        assertEquals(base.stepStart[mid.node - 1] + 60.0, s.stepStart[mid.node - 1], 1e-9)
    }

    @Test
    fun breakLightCountsAndSweepMatchesAnalysis() {
        val (route, analyzer) = setup()
        val schedule = Din33466Pace().schedule(route).withBreaks(route, listOf(Break(450.0, 45 * 60.0))) // mid-wall (306–612 m)
        val departure = IsoTime.parse("2025-12-21T10:30:00Z")!!
        val r = analyzer.analyse(schedule, departure)

        assertEquals(r.duration, r.totals.values.sum(), 1e-6, "walking and breaks add up to the whole hike")
        val stop = r.breaks.single()
        assertEquals(45, stop.pieces.size, "one sample per minute")
        assertTrue(stop.pieces.all { it.sample.light == Light.TERRAIN_SHADE }, "the break is behind the wall")

        val tl = r.timeline
        assertTrue(tl.zipWithNext().all { (a, b) -> abs(a.endSeconds - b.startSeconds) < 1e-6 }, "timeline is continuous")
        assertEquals(1, tl.count { it.isBreak })
        val moment = r.momentAt(stop.stop.startSeconds + 600)
        assertEquals(0, moment.breakIndex)
        assertEquals(stop.distance, moment.distance)

        // A sweep entry is the same as a full analysis for that departure, breaks and clouds included.
        val clear = { d: Double, _: Long -> if (d < 300) 0.25 else 0.5 }
        val entry = analyzer.sweep(schedule, listOf(departure), clear).single()
        assertEquals(r.totals, entry.totals)
        assertEquals(r.expectedSunSeconds(clear)!!, entry.expectedSun!!, 1e-6)
        assertTrue(entry.expectedSun!! < r.seconds(Light.SUN))
        assertNull(r.expectedSunSeconds { _, _ -> null }, "no forecast, no expected sun")

        val wpts = OsmAndExport.transitions(r) { "12:00" }
        assertTrue(wpts.any { it.name.startsWith("Break ") })
        assertTrue(wpts.zipWithNext().none { (a, b) -> a.name == b.name && a.name.startsWith("Sun from") }, "no transition between same-light runs")
    }

    private val sample = """
        [{"latitude":46.5,"longitude":8.0,"elevation":800.0,"hourly":{
          "time":[1766300400,1766304000,1766307600],
          "temperature_2m":[-2.0,0.0,null],
          "precipitation_probability":[10,20,30],
          "precipitation":[0.0,0.2,0.0],
          "weather_code":[3,61,1],
          "cloud_cover":[100,80,10],
          "cloud_cover_low":[50,0,0],
          "cloud_cover_mid":[0,40,0],
          "cloud_cover_high":[100,100,20],
          "wind_speed_10m":[5.0,7.5,10.0],
          "wind_gusts_10m":[12.0,null,20.0]}},
         {"latitude":46.5,"longitude":8.01,"elevation":1200.0,"hourly":{
          "time":[1766300400],"temperature_2m":[-5.5],"cloud_cover":[0],"weather_code":[0]}}]
    """.trimIndent()

    @Test
    fun openMeteoAnswerIsReadAndInterpolated() {
        val places = listOf(
            OpenMeteo.Place(0.0, LatLon(46.5, 8.0), 800.0),
            OpenMeteo.Place(1000.0, LatLon(46.5, 8.01), 1200.0),
        )
        val f = OpenMeteo.parse(sample, places)
        val t0 = 1766300400_000L
        val half = f.at(100.0, t0 + 1_800_000)
        assertNotNull(half)
        assertEquals(-1.0, half.temperature, 1e-9)
        assertEquals(0.15, half.precipitationProbability, 1e-9)
        assertEquals(0.25, half.cloudLow, 1e-9)
        assertEquals(61, half.weatherCode, "the code is taken from the nearer hour")
        assertEquals(12.0, half.windGusts, 1e-9, "a missing value falls back to its neighbour")
        assertEquals(-1.0 - 0.65, f.at(100.0, t0 + 1_800_000, elevation = 900.0)!!.temperature, 1e-9, "lapse rate")
        assertNull(f.at(100.0, t0 - 1), "before the forecast")
        assertEquals(-5.5, f.at(900.0, t0)!!.temperature, "nearest place")
        assertEquals(1.0, f.clearSky(900.0, t0)!!, 1e-9, "layers missing: total cover is used")
        // 50 % low, no mid, 100 % high: half of the half that low clouds leave.
        assertEquals(0.25, SkyCover.clearFraction(f.at(0.0, t0)!!), 1e-9)

        val url = OpenMeteo.url(places, "2025-12-21", "2025-12-22")
        assertTrue("latitude=46.5000,46.5000&longitude=8.0000,8.0100&elevation=800,1200" in url, url)
        assertFailsWith<WeatherException> { OpenMeteo.parse("""{"error":true,"reason":"out of range"}""", places) }
        assertFailsWith<WeatherException> { OpenMeteo.parse(sample, places.take(1)) }
    }

    @Test
    fun jsonReader() {
        val v = Json.parse("""{"a":[1,-2.5e2,true,null],"s":"x\"é\n","o":{}}""") as Map<*, *>
        assertEquals(listOf(1.0, -250.0, true, null), v["a"])
        assertEquals("x\"é\n", v["s"])
        assertEquals(emptyMap<String, Any?>(), v["o"])
        assertFailsWith<Json.SyntaxError> { Json.parse("[1,]") }
    }
}
