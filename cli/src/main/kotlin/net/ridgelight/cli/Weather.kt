package net.ridgelight.cli

import net.ridgelight.core.analysis.Light
import net.ridgelight.core.analysis.SunResult
import net.ridgelight.core.gpx.LightStyle
import net.ridgelight.core.route.SampledRoute
import net.ridgelight.core.weather.OpenMeteo
import net.ridgelight.core.weather.RouteForecast
import net.ridgelight.core.weather.Sky
import net.ridgelight.core.weather.SkyReport
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

internal fun skyLabel(sky: Sky): String = when (sky) {
    Sky.CLEAR -> "clear sky"
    Sky.THIN_CLOUD -> "thin high cloud"
    Sky.PARTLY_CLOUDY -> "partly cloudy"
    Sky.OVERCAST -> "overcast"
    Sky.IN_CLOUD -> "in cloud or fog"
}

/** ☀ when most of the sunny stretches are under clear sky, ⛅ for some, ☁ for few. */
internal fun skyIcon(shareOfSun: Double) = when {
    shareOfSun >= 0.7 -> "☀"
    shareOfSun >= 0.3 -> "⛅"
    else -> "☁"
}

internal fun printWeather(result: SunResult, forecast: RouteForecast, local: (Long) -> String) {
    println()
    val report = SkyReport.of(result, forecast)
    if (report == null) {
        println("  Sky: the forecast does not cover this hike.")
        return
    }
    println("  Sky (${OpenMeteo.ATTRIBUTION}):")
    val sun = report.sunBySky.filterValues { it > 0 }.entries.joinToString(" · ") { "${LightStyle.duration(it.value)} ${skyLabel(it.key)}" }
    if (report.sunSeconds > 0) println("   Terrain sun ${LightStyle.duration(report.sunSeconds)}: $sun")
    if (report.sunAboveClouds > 0) println("   Above a sea of cloud for ${LightStyle.duration(report.sunAboveClouds)} of it")
    if (report.inCloudSeconds > 0) {
        val where = report.inCloudRanges.joinToString(", ") { "km %.1f–%.1f".format(it.start / 1000, it.endInclusive / 1000) }
        println("   In cloud or fog for ${LightStyle.duration(report.inCloudSeconds)} ($where)")
    }
    val wet = listOfNotNull("rain".takeIf { report.rain }, "snow".takeIf { report.snow }, "thunderstorms".takeIf { report.thunder })
    println("   %d to %d °C at your altitudes · %s · wind up to %d km/h (gusts %d)".format(
        report.minTemperature.roundToInt(), report.maxTemperature.roundToInt(),
        if (wet.isEmpty()) "dry (rain chance up to %d %%)".format((report.maxRainChance * 100).roundToInt()) else wet.joinToString(", ") + " possible",
        report.maxWind.roundToInt(), report.maxGusts.roundToInt(),
    ))
    println("   Stretches:")
    for (st in report.stretches) {
        val r = st.run
        val where = if (r.isBreak) "break at %.1f km".format(r.startDistance / 1000) else "%5.1f–%.1f km".format(r.startDistance / 1000, r.endDistance / 1000)
        val sky = st.sky?.let { s -> skyLabel(s.sky) + if (s.cloudsBelow) ", above the clouds" else "" } ?: ""
        val light = if (r.light == Light.SUN && st.sky?.sky in setOf(Sky.OVERCAST, Sky.IN_CLOUD)) "Terrain sun" else LightStyle.label(r.light)
        println("    %s–%s  %-14s %-18s %s".format(local(result.timeAt(r.startSeconds)), local(result.timeAt(r.endSeconds)), light, where, sky))
    }
}
