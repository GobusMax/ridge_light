package net.ridgelight.cli

import net.ridgelight.core.analysis.SunResult
import net.ridgelight.core.gpx.LightStyle
import net.ridgelight.core.route.SampledRoute
import net.ridgelight.core.weather.OpenMeteo
import net.ridgelight.core.weather.RouteForecast
import net.ridgelight.core.weather.WeatherCodes
import net.ridgelight.core.weather.WeatherException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Open-Meteo forecast for the route's local [date] (UTC days around it), or null with a warning. */
internal fun fetchForecast(route: SampledRoute, date: LocalDate, zone: ZoneId): RouteForecast? {
    val places = OpenMeteo.places(route)
    val from = date.atStartOfDay(zone).toInstant().atZone(ZoneId.of("UTC")).toLocalDate()
    val to = date.plusDays(1).atStartOfDay(zone).toInstant().atZone(ZoneId.of("UTC")).toLocalDate()
    val request = HttpRequest.newBuilder(URI(OpenMeteo.url(places, from.toString(), to.toString())))
        .header("User-Agent", "RidgeLight-cli").timeout(Duration.ofSeconds(20)).build()
    return try {
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        OpenMeteo.parse(response.body(), places)
    } catch (e: WeatherException) {
        System.err.println("Weather: ${e.message}")
        null
    } catch (e: java.io.IOException) {
        System.err.println("Weather: ${e.message}")
        null
    }
}

internal fun printWeather(result: SunResult, forecast: RouteForecast, local: (Long) -> String) {
    println()
    val expected = result.expectedSunSeconds(forecast::clearSky)
    println("  Direct sun after forecast clouds: " + (expected?.let { "${LightStyle.duration(it)} (%d %%)".format((100 * it / result.duration).roundToInt()) } ?: "no forecast for the whole hike"))
    println("  Weather along the way (${OpenMeteo.ATTRIBUTION}):")
    var t = Math.ceil(result.timeAt(0.0) / 3_600_000.0).toLong() * 3_600_000L
    while (t <= result.timeAt(result.duration)) {
        val m = result.momentAt((t - result.departureUtc) / 1000.0)
        val ele = result.route.nodes.minBy { kotlin.math.abs(it.distance - m.distance) }.elevation
        val w = forecast.at(m.distance, t, ele)
        if (w != null) {
            println("   %s  km %4.1f  %-14s %5.1f °C  rain %3d %%  wind %3.0f km/h  clouds %3d %% (low %d, mid %d, high %d)".format(
                local(t), m.distance / 1000, WeatherCodes.describe(w.weatherCode), w.temperature,
                (w.precipitationProbability * 100).roundToInt(), w.windSpeed, (w.cloudCover * 100).roundToInt(),
                (w.cloudLow * 100).roundToInt(), (w.cloudMid * 100).roundToInt(), (w.cloudHigh * 100).roundToInt(),
            ))
        }
        t += 3_600_000L
    }
}
