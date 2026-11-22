package net.ridgelight.core.gpx

import net.ridgelight.core.analysis.Light
import net.ridgelight.core.analysis.SunResult
import kotlin.math.roundToInt

/** Display names and colours shared by all front ends. */
object LightStyle {
    fun label(light: Light): String = when (light) {
        Light.SUN -> "Direct sun"
        Light.TERRAIN_SHADE -> "Terrain shade"
        Light.BELOW_HORIZON -> "Below horizon"
        Light.UNKNOWN -> "Unknown"
    }

    /** #RRGGBB, chosen to stay distinguishable for common colour-vision deficiencies. */
    fun color(light: Light): String = when (light) {
        Light.SUN -> "#F0A830"
        Light.TERRAIN_SHADE -> "#3F6FB5"
        Light.BELOW_HORIZON -> "#6A4C93"
        Light.UNKNOWN -> "#8A8A8A"
    }

    fun argb(light: Light): Int = (0xFF000000 or color(light).removePrefix("#").toLong(16)).toInt()

    /** "1h 05m" or "42 min". */
    fun duration(seconds: Double): String {
        val m = (seconds / 60).roundToInt()
        return if (m >= 60) "${m / 60}h ${(m % 60).toString().padStart(2, '0')}m" else "$m min"
    }
}

/**
 * Turns a result into GPX files for OsmAnd. OsmAnd colours whole files, so each kind of light
 * gets its own file; the sun file also carries waypoints where the light changes.
 */
object OsmAndExport {
    class File(val light: Light, val fileName: String, val color: String, val gpx: String)

    fun fileName(light: Light) = "ridgelight-${light.name.lowercase().replace('_', '-')}.gpx"

    /** @param localTime formats a UTC instant as local clock time, e.g. "10:42". */
    fun files(result: SunResult, routeName: String, localTime: (Long) -> String): List<File> {
        val waypoints = transitions(result, localTime)
        return Light.entries.filter { light -> result.runs.any { it.light == light } }.map { light ->
            File(
                light,
                fileName(light),
                LightStyle.color(light),
                GpxWriter.lightTrack(
                    result, light, "$routeName · ${LightStyle.label(light)}", LightStyle.color(light),
                    if (light == Light.SUN) waypoints else emptyList(),
                ),
            )
        }
    }

    fun transitions(result: SunResult, localTime: (Long) -> String): List<GpxWaypoint> =
        result.runs.drop(1).map { run ->
            val verb = when (run.light) {
                Light.SUN -> "Sun from"
                Light.TERRAIN_SHADE -> "Shade from"
                Light.BELOW_HORIZON -> "Sun down"
                Light.UNKNOWN -> "Unknown from"
            }
            GpxWaypoint(
                result.route.steps[run.firstStep].start.position,
                "$verb ${localTime(result.timeAt(run.startSeconds))}",
                "${LightStyle.label(run.light)} for ${LightStyle.duration(run.seconds)}",
                LightStyle.color(run.light),
            )
        }
}
