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
 * The words in the tracks and waypoints sent to OsmAnd. English by default; an app overrides them
 * for the phone's language.
 */
interface ExportLabels {
    fun light(light: Light): String = LightStyle.label(light)
    fun duration(seconds: Double): String = LightStyle.duration(seconds)
    fun track(routeName: String, light: Light): String = "$routeName · ${light(light)}"

    /** Waypoint name where [light] begins at [time]. */
    fun change(light: Light, time: String): String = when (light) {
        Light.SUN -> "Sun from $time"
        Light.TERRAIN_SHADE -> "Shade from $time"
        Light.BELOW_HORIZON -> "Sun down $time"
        Light.UNKNOWN -> "Unknown from $time"
    }

    /** Waypoint description: [light] lasts [walkingSeconds] of walking. */
    fun changeDetail(light: Light, walkingSeconds: Double): String = "${light(light)} for ${duration(walkingSeconds)} of walking"

    fun breakName(from: String, to: String): String = "Break $from–$to"

    object English : ExportLabels
}

/**
 * Turns a result into GPX files for OsmAnd. OsmAnd colours whole files, so each kind of light
 * gets its own file; the sun file also carries waypoints where the light changes.
 */
object OsmAndExport {
    class File(val light: Light, val fileName: String, val color: String, val gpx: String)

    fun fileName(light: Light) = "ridgelight-${light.name.lowercase().replace('_', '-')}.gpx"

    /** @param localTime formats a UTC instant as local clock time, e.g. "10:42"; [labels] word the names. */
    fun files(result: SunResult, routeName: String, labels: ExportLabels = ExportLabels.English, localTime: (Long) -> String): List<File> {
        val waypoints = transitions(result, labels, localTime)
        return Light.entries.filter { light -> result.runs.any { it.light == light } }.map { light ->
            File(
                light,
                fileName(light),
                LightStyle.color(light),
                GpxWriter.lightTrack(
                    result, light, labels.track(routeName, light), LightStyle.color(light),
                    if (light == Light.SUN) waypoints else emptyList(),
                ),
            )
        }
    }

    /** Waypoints where the light changes along the walk, plus one per break. */
    fun transitions(result: SunResult, labels: ExportLabels = ExportLabels.English, localTime: (Long) -> String): List<GpxWaypoint> {
        val changes = result.runs.zipWithNext().filter { (a, b) -> a.light != b.light }.map { (_, run) ->
            // Until the light changes again, which may be after a break.
            val next = result.runs.firstOrNull { it.startSeconds > run.startSeconds && it.light != run.light }
            val walked = result.runs.filter { it.startSeconds >= run.startSeconds && (next == null || it.startSeconds < next.startSeconds) }
            GpxWaypoint(
                result.route.steps[run.firstStep].start.position,
                labels.change(run.light, localTime(result.timeAt(run.startSeconds))),
                labels.changeDetail(run.light, walked.sumOf { it.seconds }),
                LightStyle.color(run.light),
            )
        }
        val stops = result.breaks.map { b ->
            val byLight = b.pieces.groupBy { it.sample.light }.mapValues { (_, ps) -> ps.sumOf { it.endSeconds - it.startSeconds } }
            val main = byLight.maxBy { it.value }.key
            GpxWaypoint(
                result.route.nodes[b.stop.node].position,
                labels.breakName(localTime(result.timeAt(b.stop.startSeconds)), localTime(result.timeAt(b.stop.endSeconds))),
                byLight.entries.sortedByDescending { it.value }.joinToString(", ") { "${labels.light(it.key)} ${labels.duration(it.value)}" },
                LightStyle.color(main),
            )
        }
        return changes + stops
    }
}
