package net.ridgelight.app

import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * What is kept between launches: the last track (a copy, since links from other apps do not
 * outlive a restart) with its name and plan, and the Clouds switch with whether it was explained.
 */
class PlanStore(private val prefs: SharedPreferences, dir: File) {
    val lastTrack = File(dir, "last.gpx")

    var cloudsEnabled: Boolean
        get() = prefs.getBoolean(CLOUDS, false)
        set(value) = prefs.edit { putBoolean(CLOUDS, value) }

    /** Whether the user has been told, once, what turning Clouds on sends where. */
    var cloudsExplained: Boolean
        get() = prefs.getBoolean(CLOUDS_EXPLAINED, false)
        set(value) = prefs.edit { putBoolean(CLOUDS_EXPLAINED, value) }

    var lastName: String?
        get() = prefs.getString(LAST_NAME, null)
        set(value) = prefs.edit { putString(LAST_NAME, value) }

    /** The plan for the last track, so it comes back with it. */
    fun save(s: Settings) {
        prefs.edit {
            putString(PLAN_DATE, s.date.toString())
            putString(PLAN_START, s.start.toString())
            putString(PLAN_ZONE, s.zone.id)
            putFloat(PLAN_PACE, s.paceFactor.toFloat())
            putString(PLAN_BREAKS, encodeBreaks(s.breaks))
        }
    }

    /** The saved plan, or null if there is none or it cannot be read; a date in the past becomes [today]. */
    fun load(today: (ZoneId) -> LocalDate = { LocalDate.now(it) }): Settings? = runCatching {
        val zone = ZoneId.of(prefs.getString(PLAN_ZONE, null))
        Settings(
            date = maxOf(LocalDate.parse(prefs.getString(PLAN_DATE, null)), today(zone)),
            start = LocalTime.parse(prefs.getString(PLAN_START, null)),
            zone = zone,
            // Stored as a float: back to the slider's steps of 0.05.
            paceFactor = (prefs.getFloat(PLAN_PACE, 1f) * 20).roundToInt() / 20.0,
            breaks = decodeBreaks(prefs.getString(PLAN_BREAKS, "")!!),
        )
    }.getOrNull()

    companion object {
        private const val CLOUDS = "clouds"
        private const val CLOUDS_EXPLAINED = "clouds_explained"
        private const val LAST_NAME = "last_name"
        private const val PLAN_DATE = "plan_date"
        private const val PLAN_START = "plan_start"
        private const val PLAN_ZONE = "plan_zone"
        private const val PLAN_PACE = "plan_pace"
        private const val PLAN_BREAKS = "plan_breaks"

        /** "1200.0:30;4500.5:15". */
        fun encodeBreaks(breaks: List<PlannedBreak>): String = breaks.joinToString(";") { "${it.distance}:${it.minutes}" }

        fun decodeBreaks(s: String): List<PlannedBreak> = s.split(';').filter { it.isNotBlank() }.map {
            val (d, m) = it.split(':')
            PlannedBreak(d.toDouble(), m.toInt())
        }
    }
}
