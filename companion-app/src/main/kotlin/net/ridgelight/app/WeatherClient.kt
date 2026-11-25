package net.ridgelight.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.ridgelight.core.weather.OpenMeteo
import net.ridgelight.core.weather.RouteForecast
import net.ridgelight.core.weather.WeatherException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset

/** Why there is no forecast; the UI words it. [code] is the HTTP status for [Kind.SERVICE]. */
class ForecastException(val kind: Kind, val code: Int = 0) : Exception("$kind $code") {
    enum class Kind { NO_CONNECTION, SERVICE, UNREADABLE }
}

/**
 * Fetches Open-Meteo cloud forecasts for places on a route, and keeps the last ones (in memory,
 * and as the raw answer in [cacheDir]) so the same places are not fetched again while their
 * forecast is fresh. [download] and [now] are replaceable for tests.
 */
class WeatherClient(
    private val cacheDir: File,
    private val download: (String) -> String = ::httpGet,
    private val now: () -> Long = System::currentTimeMillis,
) {
    class Fetched(val forecast: RouteForecast, val fetchedAt: Long)

    private var memory: Pair<String, Fetched>? = null

    /** The request for [places] over the whole forecast period (from yesterday, for time zones behind UTC). */
    private fun url(places: List<OpenMeteo.Place>): String {
        val today = Instant.ofEpochMilli(now()).atOffset(ZoneOffset.UTC).toLocalDate()
        return OpenMeteo.url(places, today.minusDays(1).toString(), today.plusDays(OpenMeteo.FORECAST_DAYS - 1L).toString())
    }

    /** Whether a forecast fetched at [fetchedAt] is younger than [maxAge] ms. */
    fun isFresh(fetchedAt: Long, maxAge: Long = MAX_AGE): Boolean = now() - fetchedAt < maxAge

    /** The forecast for [places] if one younger than [maxAge] ms is in memory; never touches disk or network. */
    fun cached(places: List<OpenMeteo.Place>, maxAge: Long = MAX_AGE): Fetched? {
        val url = url(places)
        return memory?.takeIf { it.first == url && isFresh(it.second.fetchedAt, maxAge) }?.second
    }

    /**
     * The forecast for [places]: from memory or disk if younger than [maxAge] ms, otherwise from
     * Open-Meteo. Throws [ForecastException].
     */
    suspend fun fetch(places: List<OpenMeteo.Place>, maxAge: Long = MAX_AGE): Fetched = withContext(Dispatchers.IO) {
        cached(places, maxAge)?.let { return@withContext it }
        val url = url(places)
        val time = now()
        val file = File(cacheDir, "forecast-${hash(url)}.json")
        if (file.exists() && isFresh(file.lastModified(), maxAge)) {
            val stored = runCatching { OpenMeteo.parse(file.readText(), places) }.getOrNull()
            if (stored != null) return@withContext Fetched(stored, file.lastModified()).also { memory = url to it }
        }
        val body = download(url)
        val forecast = try {
            OpenMeteo.parse(body, places)
        } catch (e: WeatherException) {
            throw ForecastException(ForecastException.Kind.UNREADABLE)
        }
        runCatching {
            cacheDir.listFiles { f -> f.name.startsWith("forecast-") && time - f.lastModified() > DAY }?.forEach { it.delete() }
            file.writeText(body)
            file.setLastModified(time)
        }
        Fetched(forecast, time).also { memory = url to it }
    }

    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    companion object {
        /** Forecast models are rerun every few hours; a younger forecast is not fetched again. */
        const val MAX_AGE = 3 * 3_600_000L
        private const val DAY = 86_400_000L

        /** GET [url] with a generic user agent; the answer's body, or [ForecastException]. */
        fun httpGet(url: String): String = try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "RidgeLight-Android")
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw ForecastException(ForecastException.Kind.SERVICE, code)
                conn.inputStream.use { it.readBytes().decodeToString() }
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            Log.w("RidgeLight", "Forecast request failed", e)
            throw ForecastException(ForecastException.Kind.NO_CONNECTION)
        }
    }
}
