package net.ridgelight.app

import kotlinx.coroutines.test.runTest
import net.ridgelight.core.geo.LatLon
import net.ridgelight.core.weather.OpenMeteo
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WeatherClientTest {
    private val dir: File = Files.createTempDirectory("forecast").toFile()
    private val places = listOf(OpenMeteo.Place(0.0, LatLon(46.6, 7.9)), OpenMeteo.Place(4000.0, LatLon(46.62, 7.92)))
    private var now = 1_795_000_000_000L // late November 2026
    private val urls = mutableListOf<String>()
    private var answer: (String) -> String = ::forecastFor
    private fun client() = WeatherClient(dir, download = { urls += it; answer(it) }, now = { now })

    @Test
    fun aFreshForecastIsNotFetchedAgain() = runTest {
        val client = client()
        val first = client.fetch(places)
        assertEquals(1, urls.size)
        assertTrue("latitude=46.6000,46.6200" in urls.single())
        assertEquals(now, first.fetchedAt)

        now += 3_600_000
        assertSame(first, client.cached(places), "from memory")
        assertSame(first, client.fetch(places))
        assertEquals(1, urls.size)
    }

    @Test
    fun theDiskCopySurvivesARestart() = runTest {
        client().fetch(places)
        now += 3_600_000
        val restarted = client()
        assertNull(restarted.cached(places), "memory is empty after a restart")
        val f = restarted.fetch(places)
        assertEquals(1, urls.size, "read from disk")
        assertEquals(now - 3_600_000, f.fetchedAt)
    }

    @Test
    fun anOldOrForcedForecastIsFetchedAgain() = runTest {
        val client = client()
        client.fetch(places)
        now += WeatherClient.MAX_AGE
        assertNull(client.cached(places))
        assertFalse(client.isFresh(now - WeatherClient.MAX_AGE))
        client.fetch(places)
        assertEquals(2, urls.size)
        client.fetch(places, maxAge = 0)
        assertEquals(3, urls.size, "refresh")
    }

    @Test
    fun otherPlacesAreAnotherForecast() = runTest {
        val client = client()
        client.fetch(places)
        client.fetch(places.take(1))
        assertEquals(2, urls.size)
        assertEquals(2, dir.listFiles()!!.count { it.name.startsWith("forecast-") })
    }

    @Test
    fun dayOldFilesAreRemoved() = runTest {
        client().fetch(places)
        now += 25 * 3_600_000L
        client().fetch(places.take(1))
        assertEquals(1, dir.listFiles()!!.count { it.name.startsWith("forecast-") })
    }

    @Test
    fun problemsAreTyped() = runTest {
        answer = { throw ForecastException(ForecastException.Kind.NO_CONNECTION) }
        assertEquals(ForecastException.Kind.NO_CONNECTION, assertFailsWith<ForecastException> { client().fetch(places) }.kind)
        answer = { """{"error":true,"reason":"Parameter start_date is out of range"}""" }
        assertEquals(ForecastException.Kind.UNREADABLE, assertFailsWith<ForecastException> { client().fetch(places) }.kind)
        answer = { "<html>" }
        assertEquals(ForecastException.Kind.UNREADABLE, assertFailsWith<ForecastException> { client().fetch(places) }.kind)
        assertEquals(0, dir.listFiles()!!.size, "nothing unreadable is kept")
        answer = ::forecastFor
        assertNotNull(client().fetch(places))
    }

    private companion object {
        const val ONE = """{"hourly":{"time":[1795000000,1795003600],"cloud_cover":[20,80]}}"""

        /** Open-Meteo's answer shape: one forecast per requested latitude. */
        fun forecastFor(url: String): String {
            val n = url.substringAfter("latitude=").substringBefore('&').split(',').size
            return List(n) { ONE }.joinToString(",", "[", "]")
        }
    }
}
