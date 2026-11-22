package net.ridgelight.core.gpx

import net.ridgelight.core.analysis.Light
import net.ridgelight.core.analysis.SunResult
import net.ridgelight.core.geo.LatLon
import net.ridgelight.core.route.RouteException
import net.ridgelight.core.route.TrackPoint

class GpxTrack(val name: String?, val segments: List<List<TrackPoint>>) {
    val points: List<TrackPoint> get() = segments.flatten()
}

/**
 * Minimal, tolerant GPX reader: track segments (`trkseg`/`trkpt`), or route points (`rtept`) when
 * the file has no track. Waypoints alone are not a route.
 */
object GpxReader {
    private const val NS = "(?:[\\w-]+:)?"
    private val trksegRe = Regex("<${NS}trkseg\\b[^>]*>([\\s\\S]*?)</${NS}trkseg>")
    private val rteRe = Regex("<${NS}rte\\b[^>]*>([\\s\\S]*?)</${NS}rte>")
    private fun pointRe(tag: String) = Regex("<$NS$tag\\b([^>]*?)(?:/>|>([\\s\\S]*?)</$NS$tag>)")
    private val trkptRe = pointRe("trkpt")
    private val rteptRe = pointRe("rtept")
    private val latRe = Regex("\\blat\\s*=\\s*[\"']([^\"']*)[\"']")
    private val lonRe = Regex("\\blon\\s*=\\s*[\"']([^\"']*)[\"']")
    private val eleRe = Regex("<${NS}ele\\b[^>]*>([^<]*)<")
    private val timeRe = Regex("<${NS}time\\b[^>]*>([^<]*)<")
    private val trkNameRe = Regex("<${NS}(?:trk|rte)\\b[^>]*>\\s*<${NS}name\\b[^>]*>([^<]*)<")
    private val metaNameRe = Regex("<${NS}metadata\\b[^>]*>[\\s\\S]*?<${NS}name\\b[^>]*>([^<]*)<")

    fun read(xml: String): GpxTrack {
        if (!Regex("<${NS}gpx\\b").containsMatchIn(xml)) throw RouteException("This is not a GPX file.")
        val segments = trksegRe.findAll(xml).map { points(it.groupValues[1], trkptRe) }.filter { it.isNotEmpty() }.toList()
            .ifEmpty { rteRe.findAll(xml).map { points(it.groupValues[1], rteptRe) }.filter { it.isNotEmpty() }.toList() }
        if (segments.isEmpty()) throw RouteException("No track or route points found. Waypoints alone do not make a route.")
        val name = (trkNameRe.find(xml) ?: metaNameRe.find(xml))?.groupValues?.get(1)?.let(::unescape)?.trim()?.ifEmpty { null }
        return GpxTrack(name, segments)
    }

    private fun points(body: String, re: Regex): List<TrackPoint> = re.findAll(body).map { m ->
        val attrs = m.groupValues[1]
        val inner = m.groupValues[2]
        TrackPoint(
            lat = latRe.find(attrs)?.groupValues?.get(1)?.trim()?.toDoubleOrNull() ?: Double.NaN,
            lon = lonRe.find(attrs)?.groupValues?.get(1)?.trim()?.toDoubleOrNull() ?: Double.NaN,
            ele = eleRe.find(inner)?.groupValues?.get(1)?.trim()?.toDoubleOrNull() ?: Double.NaN,
            timeMillis = timeRe.find(inner)?.groupValues?.get(1)?.let { IsoTime.parse(it.trim()) },
        )
    }.toList()

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}

class GpxWaypoint(val position: LatLon, val name: String, val description: String? = null, val color: String? = null)

/** Writes GPX with OsmAnd appearance extensions. */
object GpxWriter {
    const val OSMAND_NS = "https://osmand.net/docs/technical/osmand-file-formats/osmand-gpx"

    /**
     * One file holding every stretch of [light] in [result] as a separate segment, coloured
     * [colorHex] (#RRGGBB). OsmAnd colours whole files, not segments, hence one file per light.
     */
    fun lightTrack(
        result: SunResult,
        light: Light,
        name: String,
        colorHex: String,
        waypoints: List<GpxWaypoint> = emptyList(),
        width: String = "bold",
    ): String {
        val segments = result.runs.filter { it.light == light }.map { run ->
            result.route.steps.subList(run.firstStep, run.endStep).flatMapIndexed { k, step ->
                if (k == 0) step.path else step.path.drop(1)
            }
        }
        return track(name, segments, colorHex, waypoints, width)
    }

    fun track(name: String, segments: List<List<LatLon>>, colorHex: String?, waypoints: List<GpxWaypoint>, width: String?): String =
        buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            append("""<gpx version="1.1" creator="Ridge Light" xmlns="http://www.topografix.com/GPX/1/1" xmlns:osmand="$OSMAND_NS">""").append('\n')
            append("<metadata><name>").append(esc(name)).append("</name></metadata>\n")
            for (w in waypoints) {
                append("<wpt lat=\"").append(fmt(w.position.lat)).append("\" lon=\"").append(fmt(w.position.lon)).append("\">")
                append("<name>").append(esc(w.name)).append("</name>")
                w.description?.let { append("<desc>").append(esc(it)).append("</desc>") }
                w.color?.let { append("<extensions><osmand:color>").append(it).append("</osmand:color><osmand:background>circle</osmand:background></extensions>") }
                append("</wpt>\n")
            }
            append("<trk><name>").append(esc(name)).append("</name>\n")
            for (seg in segments) {
                if (seg.size < 2) continue
                append("<trkseg>\n")
                for (p in seg) append("<trkpt lat=\"").append(fmt(p.lat)).append("\" lon=\"").append(fmt(p.lon)).append("\"/>\n")
                append("</trkseg>\n")
            }
            append("</trk>\n")
            append("<extensions>")
            colorHex?.let { append("<osmand:color>").append(it).append("</osmand:color>") }
            width?.let { append("<osmand:width>").append(it).append("</osmand:width>") }
            append("<osmand:show_start_finish>false</osmand:show_start_finish>")
            append("</extensions>\n")
            append("</gpx>\n")
        }

    private fun fmt(v: Double): String {
        val scaled = kotlin.math.round(v * 1e7).toLong()
        val sign = if (scaled < 0) "-" else ""
        val a = kotlin.math.abs(scaled)
        return sign + (a / 10_000_000).toString() + "." + (a % 10_000_000).toString().padStart(7, '0')
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/** ISO-8601 timestamps as used in GPX (`2024-07-01T08:15:30Z`, fractions, `±hh:mm` offsets). */
object IsoTime {
    private val re = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d+))?)?\\s*(Z|[+-]\\d{2}:?\\d{2})?$")

    fun parse(s: String): Long? {
        val m = re.find(s) ?: return null
        val g = m.groupValues
        val days = daysFromCivil(g[1].toInt(), g[2].toInt(), g[3].toInt())
        var ms = days * 86_400_000L + g[4].toInt() * 3_600_000L + g[5].toInt() * 60_000L +
            (g[6].ifEmpty { "0" }.toInt()) * 1000L
        if (g[7].isNotEmpty()) ms += (g[7] + "00").take(3).toLong()
        val zone = g[8]
        if (zone.isNotEmpty() && zone != "Z") {
            val sign = if (zone[0] == '-') -1 else 1
            val digits = zone.substring(1).replace(":", "")
            ms -= sign * (digits.substring(0, 2).toInt() * 3_600_000L + digits.substring(2, 4).toInt() * 60_000L)
        }
        return ms
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (H. Hinnant's algorithm). */
    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }
}
