package net.ridgelight.app.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import net.ridgelight.app.ForecastException
import net.ridgelight.app.LoadProblem
import net.ridgelight.app.OsmAndProblem
import net.ridgelight.app.R
import net.ridgelight.app.Settings
import net.ridgelight.core.analysis.Light
import net.ridgelight.core.gpx.ExportLabels
import net.ridgelight.core.route.Route
import net.ridgelight.core.weather.Sky
import net.ridgelight.core.weather.SkyAt
import net.ridgelight.core.weather.SkyLight
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Everything the app writes that is more than a fixed string: durations, distances, light and sky
 * names, dates, in the language of [res]. One per configuration, from [LocalWords].
 */
class Words(private val res: Resources) {
    val locale: Locale = res.configuration.locales[0]
    val day: DateTimeFormatter = DateTimeFormatter.ofPattern(res.getString(R.string.day_pattern), locale)
    val shortDay: DateTimeFormatter = DateTimeFormatter.ofPattern(res.getString(R.string.short_day_pattern), locale)
    val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", locale)

    fun get(@StringRes id: Int, vararg args: Any): String = res.getString(id, *args)

    /** "2h 40m" or "42 min". */
    fun duration(seconds: Double): String {
        val m = (seconds / 60).roundToInt()
        return when {
            m < 60 -> res.getString(R.string.duration_minutes, m)
            m % 60 == 0 -> res.getString(R.string.duration_whole_hours, m / 60)
            else -> res.getString(R.string.duration_hours, m / 60, m % 60)
        }
    }

    /** "2 hours 40 minutes", for screen readers ("2h 40m" would be read as metres). */
    fun spokenDuration(seconds: Double): String {
        val m = (seconds / 60).roundToInt()
        val minutes = res.getQuantityString(R.plurals.spoken_minutes, m % 60, m % 60)
        if (m < 60) return minutes
        val hours = res.getQuantityString(R.plurals.spoken_hours, m / 60, m / 60)
        return if (m % 60 == 0) hours else "$hours $minutes"
    }

    /** "4.2" (or "4,2"): kilometres with one decimal, without the unit. */
    fun kmNumber(metres: Double): String = String.format(locale, "%.1f", metres / 1000)

    /** "km 4.2", a place on the route. */
    fun km(metres: Double): String = res.getString(R.string.km, kmNumber(metres))

    fun kmRange(from: Double, to: Double): String = res.getString(R.string.km_range, kmNumber(from), kmNumber(to))

    fun metres(m: Double): String = res.getString(R.string.metres, m.roundToInt())

    /** "×1.10". */
    fun factor(f: Double): String = String.format(locale, "×%.2f", f)

    fun label(light: SkyLight): String = res.getString(
        when (light) {
            SkyLight.SUN -> R.string.light_sun
            SkyLight.SUN_AND_CLOUDS -> R.string.light_sun_and_clouds
            SkyLight.CLOUDED -> R.string.light_clouded
            SkyLight.SHADE -> R.string.light_shade
            SkyLight.DARK -> R.string.light_dark
            SkyLight.UNKNOWN -> R.string.light_unknown
        },
    )

    /** [light] with what the sky adds to it: fog, or a sea of cloud below. */
    fun label(light: SkyLight, sky: SkyAt?): String = when {
        light == SkyLight.CLOUDED && sky?.sky == Sky.IN_CLOUD -> res.getString(R.string.light_in_fog)
        light == SkyLight.SUN && sky?.cloudsBelow == true -> res.getString(R.string.light_above_clouds)
        else -> label(light)
    }

    /** "clear sky", "thin high cloud", … for a sky as seen from the trail. */
    fun skyLabel(s: Sky): String = res.getString(
        when (s) {
            Sky.CLEAR -> R.string.sky_clear
            Sky.THIN_CLOUD -> R.string.sky_thin
            Sky.PARTLY_CLOUDY -> R.string.sky_partly
            Sky.OVERCAST -> R.string.sky_overcast
            Sky.IN_CLOUD -> R.string.sky_fog
        },
    )

    /** One word per sky, for the legend. */
    fun skyShort(s: Sky): String = res.getString(
        when (s) {
            Sky.CLEAR -> R.string.sky_short_clear
            Sky.THIN_CLOUD -> R.string.sky_short_thin
            Sky.PARTLY_CLOUDY -> R.string.sky_short_partly
            Sky.OVERCAST -> R.string.sky_short_overcast
            Sky.IN_CLOUD -> R.string.sky_short_fog
        },
    )

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
            sun >= 60 && partly >= 60 -> res.getString(R.string.sun_phrase_both, duration(sun), duration(partly))
            sun >= 60 -> res.getString(R.string.sun_phrase_sun, duration(sun))
            partly >= 60 -> res.getString(R.string.sun_phrase_between, duration(partly))
            else -> res.getString(R.string.sun_phrase_none)
        }
    }

    /** [sunPhrase] for screen readers. */
    fun spokenSun(combined: Map<SkyLight, Double>): String {
        val sun = combined[SkyLight.SUN] ?: 0.0
        val partly = combined[SkyLight.SUN_AND_CLOUDS] ?: 0.0
        return when {
            sun >= 60 && partly >= 60 -> res.getString(R.string.spoken_sun_both, spokenDuration(sun), spokenDuration(partly))
            sun >= 60 -> res.getString(R.string.spoken_sun, spokenDuration(sun))
            partly >= 60 -> res.getString(R.string.spoken_between, spokenDuration(partly))
            else -> res.getString(R.string.sun_phrase_none)
        }
    }

    /** The preset's name for [factor], or "×1.10" between presets. */
    fun paceName(factor: Double): String = PacePreset.of(factor)?.let { res.getString(it.label) } ?: factor(factor)

    /** "Tue 24 Nov · start 09:00 · pace Relaxed", for the other pages. */
    fun planLine(s: Settings): String = res.getString(R.string.plan_line, s.date.format(day), s.start.format(clock), paceName(s.paceFactor))

    fun problem(p: LoadProblem): String = when (p) {
        LoadProblem.UNREADABLE -> get(R.string.problem_unreadable)
        LoadProblem.NOT_GPX -> get(R.string.problem_not_gpx)
        LoadProblem.NO_POINTS -> get(R.string.problem_no_points)
        LoadProblem.BAD_COORDINATES -> get(R.string.problem_bad_coordinates)
        LoadProblem.TOO_SHORT -> get(R.string.problem_too_short, Route.MIN_LENGTH_M.toInt())
        LoadProblem.TOO_LONG -> get(R.string.problem_too_long, (Route.MAX_LENGTH_M / 1000).toInt())
        LoadProblem.NO_TERRAIN -> get(R.string.problem_no_terrain)
        LoadProblem.TOO_LARGE -> get(R.string.problem_too_large)
    }

    /** "No forecast: no connection." */
    fun problem(e: ForecastException): String = get(R.string.forecast_none, reason(e))

    /** "Not refreshed: no connection.", while the older forecast stays in use. */
    fun refreshProblem(e: ForecastException): String = get(R.string.forecast_not_refreshed, reason(e))

    private fun reason(e: ForecastException): String = when (e.kind) {
        ForecastException.Kind.NO_CONNECTION -> get(R.string.forecast_offline)
        ForecastException.Kind.SERVICE -> get(R.string.forecast_service, e.code)
        ForecastException.Kind.UNREADABLE -> get(R.string.forecast_unreadable)
    }

    fun problem(p: OsmAndProblem): String = when (p) {
        OsmAndProblem.NOT_INSTALLED -> get(R.string.osmand_not_installed)
        OsmAndProblem.NOT_CONNECTED -> get(R.string.osmand_not_connected)
        OsmAndProblem.ASK_PERMISSION -> get(R.string.osmand_allow)
        OsmAndProblem.REFUSED -> get(R.string.osmand_refused)
    }

    /** The words of the tracks sent to OsmAnd. */
    val export: ExportLabels = object : ExportLabels {
        override fun light(light: Light) = res.getString(
            when (light) {
                Light.SUN -> R.string.export_light_sun
                Light.TERRAIN_SHADE -> R.string.export_light_shade
                Light.BELOW_HORIZON -> R.string.export_light_dark
                Light.UNKNOWN -> R.string.export_light_unknown
            },
        )

        override fun duration(seconds: Double) = this@Words.duration(seconds)
        override fun track(routeName: String, light: Light) = res.getString(R.string.export_track, routeName, light(light))
        override fun change(light: Light, time: String) = res.getString(
            when (light) {
                Light.SUN -> R.string.export_sun_from
                Light.TERRAIN_SHADE -> R.string.export_shade_from
                Light.BELOW_HORIZON -> R.string.export_sun_down
                Light.UNKNOWN -> R.string.export_unknown_from
            },
            time,
        )

        override fun changeDetail(light: Light, walkingSeconds: Double) = res.getString(R.string.export_change_detail, light(light), duration(walkingSeconds))
        override fun breakName(from: String, to: String) = res.getString(R.string.export_break, from, to)
    }
}

val LocalWords = staticCompositionLocalOf<Words> { error("Words are provided by RidgeLightTheme") }

/** The [Words] for the current configuration. */
val words: Words
    @Composable @ReadOnlyComposable get() = LocalWords.current
