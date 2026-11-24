package net.ridgelight.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.ridgelight.core.route.SampledRoute
import net.ridgelight.core.weather.OpenMeteo
import net.ridgelight.core.weather.RouteForecast
import net.ridgelight.core.weather.WeatherException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Fetches Open-Meteo cloud forecasts for a route, and keeps the last ones (in memory, and as the
 * raw answer in [cacheDir]) so the same route is not fetched again while its forecast is fresh.
 */
class WeatherClient(private val cacheDir: File) {
    class Fetched(val forecast: RouteForecast, val fetchedAt: Long)

    private var memory: Pair<String, Fetched>? = null

    /** The request for [route]: up to six places, the whole forecast period (from yesterday, for time zones behind UTC). */
    private fun request(route: SampledRoute): Pair<List<OpenMeteo.Place>, String> {
        val today = LocalDate.now(ZoneOffset.UTC)
        val places = OpenMeteo.places(route)
        return places to OpenMeteo.url(places, today.minusDays(1).toString(), today.plusDays(OpenMeteo.FORECAST_DAYS - 1L).toString())
    }

    /** The forecast for [route] if one younger than [maxAge] ms is in memory; never touches disk or network. */
    fun cached(route: SampledRoute, maxAge: Long): Fetched? {
        val (_, url) = request(route)
        return memory?.takeIf { it.first == url && System.currentTimeMillis() - it.second.fetchedAt < maxAge }?.second
    }

    /**
     * The forecast for [route]: from memory or disk if younger than [maxAge] ms, otherwise from
     * Open-Meteo. Throws [WeatherException] with a readable message.
     */
    suspend fun fetch(route: SampledRoute, maxAge: Long): Fetched = withContext(Dispatchers.IO) {
        cached(route, maxAge)?.let { return@withContext it }
        val (places, url) = request(route)
        val now = System.currentTimeMillis()
        val file = File(cacheDir, "forecast-${hash(url)}.json")
        if (file.exists() && now - file.lastModified() < maxAge) {
            val stored = runCatching { OpenMeteo.parse(file.readText(), places) }.getOrNull()
            if (stored != null) return@withContext Fetched(stored, file.lastModified()).also { memory = url to it }
        }
        val body = download(url)
        val forecast = OpenMeteo.parse(body, places)
        runCatching {
            cacheDir.listFiles { f -> f.name.startsWith("forecast-") && now - f.lastModified() > DAY }?.forEach { it.delete() }
            file.writeText(body)
        }
        Fetched(forecast, now).also { memory = url to it }
    }

    private fun download(url: String): String = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "RidgeLight-Android")
        try {
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream?.use { it.readBytes().decodeToString() } ?: throw WeatherException("The weather service answered ${conn.responseCode}.")
        } finally {
            conn.disconnect()
        }
    } catch (e: IOException) {
        Log.w("RidgeLight", "Forecast request failed", e)
        throw WeatherException("No forecast: no connection.")
    }

    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    private companion object {
        const val DAY = 86_400_000L
    }
}
