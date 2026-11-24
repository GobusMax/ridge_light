package net.ridgelight.cli

import net.ridgelight.core.analysis.SunResult
import net.ridgelight.core.gpx.LightStyle
import net.ridgelight.core.route.SampledRoute
import net.ridgelight.core.weather.OpenMeteo
import net.ridgelight.core.weather.RouteForecast
import net.ridgelight.core.weather.Sky
import net.ridgelight.core.weather.SkyLight
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

internal fun skyLightLabel(light: SkyLight): String = when (light) {
    SkyLight.SUN -> "Sun"
    SkyLight.SUN_AND_CLOUDS -> "Sun and clouds"
    SkyLight.CLOUDED -> "Behind clouds"
    SkyLight.SHADE -> "Terrain shade"
    SkyLight.DARK -> "Below horizon"
    SkyLight.UNKNOWN -> "Unknown"
}

/** Terrain light combined with the forecast clouds. */
internal fun printWeather(result: SunResult, forecast: RouteForecast, local: (Long) -> String) {
    println()
    val report = SkyReport.of(result, forecast)
    if (report == null) {
        println("  Clouds: the forecast does not cover this hike.")
        return
    }
    println("  With the forecast clouds (${OpenMeteo.ATTRIBUTION}):")
    for (light in SkyLight.entries) {
        val s = report.seconds(light)
        if (s < 30) continue
        println("  %-14s %8s  %3d %%".format(skyLightLabel(light), LightStyle.duration(s), (100 * s / result.duration).roundToInt()))
    }
    if (report.sunAboveClouds >= 60) println("   Above a sea of cloud for ${LightStyle.duration(report.sunAboveClouds)} of the sun")
    if (report.inCloudSeconds >= 60) {
        val where = report.inCloudRanges.joinToString(", ") { "km %.1f–%.1f".format(it.start / 1000, it.endInclusive / 1000) }
        println("   In cloud or fog for ${LightStyle.duration(report.inCloudSeconds)} ($where)")
    }
    println("  Stretches:")
    for (st in report.stretches) {
        val r = st.run
        val where = if (r.isBreak) "break at %.1f km".format(r.startDistance / 1000) else "%5.1f–%.1f km".format(r.startDistance / 1000, r.endDistance / 1000)
        val sunny = st.light == SkyLight.SUN || st.light == SkyLight.SUN_AND_CLOUDS || st.light == SkyLight.CLOUDED
        val sky = st.sky?.takeIf { sunny }?.let { s -> skyLabel(s.sky) + if (s.cloudsBelow) ", above the clouds" else "" } ?: ""
        println("   %s–%s  %-14s %-18s %s".format(local(result.timeAt(r.startSeconds)), local(result.timeAt(r.endSeconds)), skyLightLabel(st.light), where, sky))
    }
}
