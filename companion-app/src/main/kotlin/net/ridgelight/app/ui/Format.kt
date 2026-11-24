package net.ridgelight.app.ui

import net.ridgelight.core.analysis.Light
import net.ridgelight.core.analysis.LightRun
import net.ridgelight.core.gpx.LightStyle
import net.ridgelight.core.weather.Sky
import net.ridgelight.core.weather.SkyAt
import net.ridgelight.core.weather.SkyReport
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

fun duration(seconds: Double): String = LightStyle.duration(seconds)

/** One word for the light, for tight spaces. */
fun shortLabel(light: Light): String = when (light) {
    Light.SUN -> "Sun"
    Light.TERRAIN_SHADE -> "Shade"
    Light.BELOW_HORIZON -> "Dark"
    Light.UNKNOWN -> "Unknown"
}

/** "clear sky", "thin high cloud", … for a sky as seen from the trail. */
fun skyLabel(s: SkyAt): String = when (s.sky) {
    Sky.CLEAR -> if (s.cloudsBelow) "clear sky, above the clouds" else "clear sky"
    Sky.THIN_CLOUD -> if (s.cloudsBelow) "thin high cloud, above the clouds" else "thin high cloud"
    Sky.PARTLY_CLOUDY -> "partly cloudy"
    Sky.OVERCAST -> "overcast"
    Sky.IN_CLOUD -> "in cloud or fog"
}

/** ☀ when most of the sunny stretches are under clear sky, ⛅ for some, ☁ for few. */
fun skyIcon(clearShareOfSun: Double) = when {
    clearShareOfSun >= 0.7 -> "☀️"
    clearShareOfSun >= 0.3 -> "⛅"
    else -> "☁️"
}

/** The light of a stretch; terrain sun that clouds hide is called that. */
fun lightLabel(run: LightRun, sky: SkyAt?): String {
    val hidden = sky?.sky == Sky.OVERCAST || sky?.sky == Sky.IN_CLOUD
    return if (run.light == Light.SUN && hidden) "Terrain sun" else LightStyle.label(run.light)
}

/** Terrain sun under a clear (or thin high) sky. */
fun SkyReport.clearSunSeconds(): Double = sunBySky.getValue(Sky.CLEAR) + sunBySky.getValue(Sky.THIN_CLOUD)

/** The sky in one line, for the overview. */
fun skyHeadline(sky: SkyReport): String {
    val sun = when {
        sky.sunSeconds < 60 -> {
            val mostly = sky.stretches.filter { it.sky != null }.groupBy { it.sky!!.sky }
                .maxByOrNull { (_, st) -> st.sumOf { it.run.seconds } }?.key
            if (mostly == null) "No forecast sky" else "Mostly ${skyLabel(SkyAt(mostly, false))}"
        }
        sky.clearSunSeconds() >= sky.sunSeconds - 60 -> "☀ All of the sun under clear sky"
        sky.clearSunSeconds() < 60 -> "☁ None of the sun under clear sky"
        else -> "⛅ ${duration(sky.clearSunSeconds())} of the sun under clear sky"
    }
    val fog = if (sky.inCloudSeconds >= 60) " · 🌫 ${duration(sky.inCloudSeconds)} in cloud" else ""
    return sun + fog
}

/** Temperature, rain and wind; [long] adds details. */
fun weatherLine(sky: SkyReport, long: Boolean = false): String {
    val temp = if (sky.minTemperature.isNaN()) "" else {
        val range = if (sky.minTemperature.roundToInt() == sky.maxTemperature.roundToInt()) "${sky.minTemperature.roundToInt()} °C"
        else "${sky.minTemperature.roundToInt()} to ${sky.maxTemperature.roundToInt()} °C"
        range + (if (long) " at your altitudes" else "") + " · "
    }
    val wet = listOfNotNull("rain".takeIf { sky.rain }, "snow".takeIf { sky.snow }, "thunderstorms".takeIf { sky.thunder })
    val precip = when {
        wet.isNotEmpty() -> wet.joinToString(", ") + " possible"
        sky.maxRainChance.isNaN() || sky.maxRainChance < 0.05 || !long -> "dry"
        else -> "dry (rain chance up to ${(sky.maxRainChance * 100).roundToInt()} %)"
    }
    val wind = when {
        sky.maxWind.isNaN() -> ""
        long && !sky.maxGusts.isNaN() -> " · wind up to ${sky.maxWind.roundToInt()} km/h, gusts ${sky.maxGusts.roundToInt()}"
        else -> " · wind ${sky.maxWind.roundToInt()} km/h"
    }
    return (temp + precip + wind).replaceFirstChar { it.uppercase() }
}
