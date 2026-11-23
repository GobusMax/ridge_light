package net.ridgelight.app

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
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Fetches Open-Meteo forecasts for a route. */
class WeatherClient {
    /** Forecast for the hike's local [date]; throws [WeatherException] with a readable message. */
    suspend fun fetch(route: SampledRoute, date: LocalDate, zone: ZoneId): RouteForecast = withContext(Dispatchers.IO) {
        // The UTC days covering the local day.
        val from = date.atStartOfDay(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDate()
        val to = date.plusDays(1).atStartOfDay(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDate()
        val today = LocalDate.now(ZoneOffset.UTC)
        val last = today.plusDays(OpenMeteo.FORECAST_DAYS - 1L)
        val first = today.minusDays(OpenMeteo.PAST_DAYS.toLong())
        if (from > last) {
            val until = last.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
            throw WeatherException("Forecasts reach ${OpenMeteo.FORECAST_DAYS} days ahead (until $until). Check again closer to the hike.")
        }
        if (to < first) throw WeatherException("No forecast is kept for dates this far back.")
        val places = OpenMeteo.places(route)
        val url = OpenMeteo.url(places, maxOf(from, first).toString(), minOf(to, last).toString())
        val body = try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "RidgeLight-Android")
            try {
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                stream?.use { it.readBytes().decodeToString() } ?: throw WeatherException("Weather service answered ${conn.responseCode}")
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            throw WeatherException("No weather forecast: no connection.")
        }
        OpenMeteo.parse(body, places)
    }
}
