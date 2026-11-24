package net.ridgelight.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.ridgelight.core.route.SampledRoute
import net.ridgelight.core.weather.OpenMeteo
import net.ridgelight.core.weather.RouteForecast
import net.ridgelight.core.weather.WeatherException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneOffset

/** Fetches Open-Meteo forecasts for a route. */
class WeatherClient {
    /**
     * The whole forecast period (today to 15 days ahead, plus yesterday for time zones behind
     * UTC) for a few places on [route]; throws [WeatherException] with a readable message.
     */
    suspend fun fetch(route: SampledRoute): RouteForecast = withContext(Dispatchers.IO) {
        val today = LocalDate.now(ZoneOffset.UTC)
        val places = OpenMeteo.places(route)
        val url = OpenMeteo.url(places, today.minusDays(1).toString(), today.plusDays(OpenMeteo.FORECAST_DAYS - 1L).toString())
        val body = try {
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
        OpenMeteo.parse(body, places)
    }
}
