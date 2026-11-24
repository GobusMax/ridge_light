package net.ridgelight.app.ui

import net.ridgelight.core.gpx.LightStyle
import net.ridgelight.core.weather.Sky
import net.ridgelight.core.weather.SkyAt
import net.ridgelight.core.weather.SkyLight
import java.time.format.DateTimeFormatter

val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

fun duration(seconds: Double): String = LightStyle.duration(seconds)

fun label(light: SkyLight): String = when (light) {
    SkyLight.SUN -> "Sun"
    SkyLight.SUN_AND_CLOUDS -> "Sun and clouds"
    SkyLight.CLOUDED -> "Behind clouds"
    SkyLight.SHADE -> "Terrain shade"
    SkyLight.DARK -> "Sun below horizon"
    SkyLight.UNKNOWN -> "Unknown"
}

/** One or two words, for legends and tiles. */
fun shortLabel(light: SkyLight): String = when (light) {
    SkyLight.SUN -> "Sun"
    SkyLight.SUN_AND_CLOUDS -> "Sun & clouds"
    SkyLight.CLOUDED -> "Clouded"
    SkyLight.SHADE -> "Shade"
    SkyLight.DARK -> "Dark"
    SkyLight.UNKNOWN -> "Unknown"
}

/** [light] with what the sky adds to it: fog, or a sea of cloud below. */
fun label(light: SkyLight, sky: SkyAt?): String = when {
    light == SkyLight.CLOUDED && sky?.sky == Sky.IN_CLOUD -> "In fog, sun hidden"
    light == SkyLight.SUN && sky?.cloudsBelow == true -> "Sun, above the clouds"
    else -> label(light)
}

/** "clear sky", "thin high cloud", … for a sky as seen from the trail. */
fun skyLabel(s: Sky): String = when (s) {
    Sky.CLEAR -> "clear sky"
    Sky.THIN_CLOUD -> "thin high cloud"
    Sky.PARTLY_CLOUDY -> "partly cloudy"
    Sky.OVERCAST -> "overcast"
    Sky.IN_CLOUD -> "in fog"
}

/** "2h 40m" of sun, with "+42 min" between clouds when there is some ("+2h 28m" alone if that is all). */
fun sunText(combined: Map<SkyLight, Double>): String {
    val sun = combined[SkyLight.SUN] ?: 0.0
    val partly = combined[SkyLight.SUN_AND_CLOUDS] ?: 0.0
    return when {
        partly < 60 -> duration(sun)
        sun < 60 -> "+" + duration(partly)
        else -> duration(sun) + " +" + duration(partly)
    }
}

/** The same in words, for tight spaces: "2h 40m sun +42m", "2h 28m between clouds", "no sun". */
fun sunPhrase(combined: Map<SkyLight, Double>): String {
    val sun = combined[SkyLight.SUN] ?: 0.0
    val partly = combined[SkyLight.SUN_AND_CLOUDS] ?: 0.0
    return when {
        sun >= 60 && partly >= 60 -> "${duration(sun)} sun +${duration(partly)}"
        sun >= 60 -> "${duration(sun)} sun"
        partly >= 60 -> "${duration(partly)} between clouds"
        else -> "no sun"
    }
}
