package net.ridgelight.cli

import net.ridgelight.core.analysis.Light
import net.ridgelight.core.analysis.PointSunlight
import net.ridgelight.core.analysis.SunAnalyzer
import net.ridgelight.core.analysis.SunResult
import net.ridgelight.core.analysis.departures
import net.ridgelight.core.dem.TerrainModel
import net.ridgelight.core.dem.TilePlanner
import net.ridgelight.core.geo.LatLon
import net.ridgelight.core.gpx.GpxReader
import net.ridgelight.core.gpx.LightStyle
import net.ridgelight.core.gpx.OsmAndExport
import net.ridgelight.core.horizon.HorizonParams
import net.ridgelight.core.pace.Break
import net.ridgelight.core.pace.ConstantSpeedPace
import net.ridgelight.core.pace.Din33466Pace
import net.ridgelight.core.pace.PaceModel
import net.ridgelight.core.pace.RecordedPace
import net.ridgelight.core.route.ElevationSource
import net.ridgelight.core.route.Route
import net.ridgelight.core.route.RouteException
import net.ridgelight.core.route.SampledRoute
import net.ridgelight.core.sun.SunTimes
import net.ridgelight.core.weather.Sky
import net.ridgelight.core.weather.SkyLight
import net.ridgelight.core.weather.SkyModel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.readText
import kotlin.math.roundToInt
import kotlin.system.exitProcess

private const val USAGE = """Ridge Light — sun and terrain shade along a hike

Usage:
  ridgelight analyse <track.gpx> --date YYYY-MM-DD --start HH:MM [options]
  ridgelight horizon --lat <deg> --lon <deg> --date YYYY-MM-DD [options]
  ridgelight season  --lat <deg> --lon <deg> --from YYYY-MM-DD --to YYYY-MM-DD [--every <days>]

Options:
  --tz <zone>          time zone for date/start (default: system zone)
  --pace din|legacy|recorded
                       DIN 33466 hiking times (default), the web prototype's constant
                       4 km/h + 6 s per metre climbed, or the GPX's own timestamps
  --factor <x>         multiply walking times (default 1.0; 0.8 = faster hiker)
  --elevation auto|gpx|dem
                       elevations for walking times: the track's own if complete (default),
                       or force track / terrain model
  --sweep              compare departures every 30 minutes through the day
  --break <km>:<min>   a break at that distance, e.g. 6.2:45 (repeatable)
  --weather            fetch an Open-Meteo forecast: the sky over each stretch (clouds and fog
                       at your altitude) and a weather summary
  --out <dir>          write one GPX per light class for OsmAnd
  --dem-dir <dir>      extra directory with {z}-{x}-{y}.png Terrarium tiles (repeatable)
  --offline            never download tiles
  --profile            (horizon) print the full horizon profile
"""

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in setOf("-h", "--help", "help")) {
        println(USAGE)
        return
    }
    try {
        val opts = Options.parse(args.drop(1))
        when (args[0]) {
            "analyse", "analyze" -> analyse(opts)
            "horizon" -> horizon(opts)
            "season" -> season(opts)
            "bench" -> bench(opts)
            else -> fail("Unknown command ${args[0]}")
        }
    } catch (e: RouteException) {
        fail(e.message ?: "Invalid route")
    } catch (e: IllegalArgumentException) {
        fail(e.message ?: "Invalid arguments")
    }
}

private fun fail(msg: String): Nothing {
    System.err.println("Error: $msg")
    System.err.println("Run 'ridgelight --help' for usage.")
    exitProcess(2)
}

class Options(private val values: Map<String, List<String>>, val positional: List<String>) {
    fun get(name: String): String? = values[name]?.lastOrNull()
    fun all(name: String): List<String> = values[name].orEmpty()
    fun flag(name: String): Boolean = name in values
    fun require(name: String): String = get(name) ?: throw IllegalArgumentException("Missing --$name")

    companion object {
        private val flags = setOf("sweep", "offline", "profile", "weather")
        fun parse(args: List<String>): Options {
            val values = LinkedHashMap<String, MutableList<String>>()
            val positional = ArrayList<String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                if (a.startsWith("--")) {
                    val name = a.removePrefix("--")
                    if (name in flags) values.getOrPut(name) { ArrayList() }
                    else values.getOrPut(name) { ArrayList() } += args.getOrNull(++i) ?: throw IllegalArgumentException("--$name needs a value")
                } else positional += a
                i++
            }
            return Options(values, positional)
        }
    }
}

internal fun zone(opts: Options): ZoneId = opts.get("tz")?.let(ZoneId::of) ?: ZoneId.systemDefault()

private fun tileStore(opts: Options): TileStore {
    val fixtures = opts.all("dem-dir").map(Path::of)
    return TileStore(TileStore.defaultCacheDir(), fixtures, offline = opts.flag("offline"))
}

internal fun loadTerrain(opts: Options, points: List<LatLon>): TerrainModel {
    val keys = TilePlanner.forRoute(points)
    val loaded = tileStore(opts).load(keys) { done, total ->
        if (done % 10 == 0 || done == total) System.err.print("\rTerrain tiles $done/$total")
    }
    System.err.println(if (loaded.downloaded > 0) " (${loaded.downloaded} downloaded)" else "")
    if (loaded.tiles.isEmpty()) throw IllegalArgumentException("No terrain tiles could be loaded.")
    if (loaded.missing.isNotEmpty()) System.err.println("Warning: ${loaded.missing.size} terrain tiles unavailable; affected light is reported as unknown.")
    return TerrainModel(loaded.tiles)
}

private val clock = DateTimeFormatter.ofPattern("HH:mm")

private fun duration(seconds: Double) = LightStyle.duration(seconds)

private val labels = Light.entries.associateWith(LightStyle::label)

private fun pace(opts: Options): Pair<PaceModel, String> {
    val factor = opts.get("factor")?.toDouble() ?: 1.0
    return when (opts.get("pace") ?: "din") {
        "din" -> Din33466Pace(factor = factor) to "DIN 33466" + if (factor != 1.0) " ×$factor" else ""
        "legacy" -> ConstantSpeedPace() to "4 km/h + 6 s/m climbed"
        "recorded" -> RecordedPace(factor) to "recorded timestamps"
        else -> throw IllegalArgumentException("Unknown pace model ${opts.get("pace")}")
    }
}

private fun analyse(opts: Options) {
    val file = opts.positional.firstOrNull() ?: throw IllegalArgumentException("Missing GPX file")
    val gpx = GpxReader.read(Path.of(file).readText())
    val route = Route.ofSegments(gpx.segments)
    val zone = zone(opts)
    val date = LocalDate.parse(opts.require("date"))
    val start = LocalTime.parse(opts.require("start"))
    val departure = ZonedDateTime.of(date, start, zone).toInstant().toEpochMilli()

    val terrain = loadTerrain(opts, route.points.map { it.latLon })
    val source = ElevationSource.valueOf((opts.get("elevation") ?: "auto").uppercase())
    val sampled = SampledRoute.of(route, terrain, elevationSource = source)
    val (paceModel, paceName) = pace(opts)
    val breaks = opts.all("break").map {
        val (km, min) = it.split(':').map(String::toDouble).also { v -> require(v.size == 2) { "--break needs <km>:<minutes>" } }
        Break(km * 1000, min * 60)
    }
    val schedule = paceModel.schedule(sampled).withBreaks(sampled, breaks)
    val analyzer = SunAnalyzer(sampled, terrain, HorizonParams())
    val t0 = System.nanoTime()
    val result = analyzer.analyse(schedule, departure)
    val ms = (System.nanoTime() - t0) / 1e6

    val local = { utc: Long -> Instant.ofEpochMilli(utc).atZone(zone).format(clock) }
    println("${gpx.name ?: Path.of(file).fileName}")
    println("  %.1f km · ↑ %d m ↓ %d m · %s (%s)".format(
        route.length / 1000, sampled.ascent.roundToInt(), sampled.descent.roundToInt(), duration(result.duration), paceName,
    ))
    if (route.gaps.isNotEmpty()) println("  Note: ${route.gaps.size} gap(s) between track segments are assumed straight.")
    println("  Departure $date ${local(departure)} ($zone), arrival ${local(result.timeAt(result.duration))}" +
        if (schedule.breaks.isNotEmpty()) " (${duration(schedule.breakSeconds)} of breaks)" else "")
    println()
    for (light in Light.entries) {
        val s = result.seconds(light)
        if (light == Light.UNKNOWN && s == 0.0) continue
        println("  %-14s %8s  %3d %%".format(labels[light], duration(s), (100 * s / result.duration).roundToInt()))
    }
    println()
    println("  Stretches:")
    for (run in result.timeline) {
        val where = if (run.isBreak) "break at %.1f km".format(run.startDistance / 1000) else "%5.1f–%.1f km".format(run.startDistance / 1000, run.endDistance / 1000)
        println("   %s–%s  %-14s %s".format(local(result.timeAt(run.startSeconds)), local(result.timeAt(run.endSeconds)), labels[run.light], where))
    }
    val forecast = if (opts.flag("weather")) fetchForecast(sampled, date, zone) else null
    if (forecast != null) printWeather(result, forecast, local)
    System.err.println("  (light analysis: %.0f ms, %d steps)".format(ms, sampled.steps.size))

    if (opts.flag("sweep")) sweep(analyzer, schedule, route, date, zone, start, forecast?.let { SkyModel.skyAt(it, sampled) })
    opts.get("out")?.let { writeGpx(Path.of(it), result, gpx.name ?: "Route", local) }
}

private fun sweep(analyzer: SunAnalyzer, schedule: net.ridgelight.core.pace.Schedule, route: Route, date: LocalDate, zone: ZoneId, chosen: LocalTime, skyAt: ((Double, Long) -> Sky?)?) {
    val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val day = SunTimes.day(route.start.lat, route.start.lon, midnight)
    val from = ((day.sunriseUtc ?: midnight) - 3_600_000L).let { it - Math.floorMod(it - midnight, 1_800_000L) }
    val to = (day.sunsetUtc ?: (midnight + 86_400_000L)) - 1_800_000L
    val t0 = System.nanoTime()
    val entries = analyzer.sweep(schedule, departures(from, to, 30), skyAt)
    val ms = (System.nanoTime() - t0) / 1e6
    val best = entries.maxByOrNull { it.expectedSun }
    println()
    println("  Departure comparison (sun share of the hike by the terrain${if (skyAt != null) "; sun with the forecast clouds" else ""}):")
    for (e in entries) {
        val t = Instant.ofEpochMilli(e.departureUtc).atZone(zone).toLocalTime()
        val bar = "█".repeat((e.sunShare * 20).roundToInt()).padEnd(20, '·')
        val mark = (if (t == chosen) " ← chosen" else "") + (if (e === best) " ★ most sun" else "")
        val clouds = e.skyTotals?.let { "  %8s sun, %s between clouds".format(duration(it.getValue(SkyLight.SUN)), duration(it.getValue(SkyLight.SUN_AND_CLOUDS))) } ?: ""
        println("   %s  %s %3d %%%s%s".format(t.format(clock), bar, (e.sunShare * 100).roundToInt(), clouds, mark))
    }
    System.err.println("  (sweep: %.0f ms for %d departures)".format(ms, entries.size))
}

private fun writeGpx(dir: Path, result: SunResult, name: String, local: (Long) -> String) {
    Files.createDirectories(dir)
    for (f in OsmAndExport.files(result, name, local)) {
        val file = dir.resolve(f.fileName)
        Files.writeString(file, f.gpx)
        println("  wrote $file")
    }
}

private fun horizon(opts: Options) {
    val point = LatLon(opts.require("lat").toDouble(), opts.require("lon").toDouble())
    val zone = zone(opts)
    val date = LocalDate.parse(opts.require("date"))
    val terrain = loadTerrain(opts, listOf(point))
    val ps = PointSunlight(terrain, HorizonParams(), point)
    println("Point %.5f, %.5f · ground %.0f m".format(point.lat, point.lon, ps.ground))
    val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val local = { utc: Long -> Instant.ofEpochMilli(utc).atZone(zone).format(clock) }
    val day = SunTimes.day(point.lat, point.lon, midnight)
    println("Flat-horizon sunrise ${day.sunriseUtc?.let(local) ?: "—"}, sunset ${day.sunsetUtc?.let(local) ?: "—"} ($zone)")
    val intervals = ps.intervals(midnight, midnight + 86_400_000L - 60_000L, 60)
    var sun = 0L
    for (iv in intervals) {
        println("  %s–%s  %s".format(local(iv.startUtc), local(iv.endUtc), labels[iv.light]))
        if (iv.light == Light.SUN) sun += iv.endUtc - iv.startUtc
    }
    println("Direct sun on $date: ${duration(sun / 1000.0)}")
    if (opts.flag("profile")) {
        val eye = ps.horizonCalculator.eyeAltitude(ps.ground)
        println("Horizon profile (azimuth: elevation°):")
        ps.horizonCalculator.profile(point.lat, point.lon, eye, 5.0).chunked(6).forEach { row ->
            println("  " + row.joinToString("  ") { (az, h) -> "%3.0f:%5.1f%s".format(az, h.angle, if (h.complete) "" else "?") })
        }
    }
}

/** Direct-sun minutes per day at a point over a date range, e.g. to find a village's sunless weeks. */
private fun season(opts: Options) {
    val point = LatLon(opts.require("lat").toDouble(), opts.require("lon").toDouble())
    val zone = zone(opts)
    val from = LocalDate.parse(opts.require("from"))
    val to = LocalDate.parse(opts.require("to"))
    val every = opts.get("every")?.toLong() ?: 1L
    val ps = PointSunlight(loadTerrain(opts, listOf(point)), HorizonParams(), point)
    println("Point %.5f, %.5f · ground %.0f m".format(point.lat, point.lon, ps.ground))
    var date = from
    var sunless: LocalDate? = null
    while (!date.isAfter(to)) {
        val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val sun = ps.intervals(midnight, midnight + 86_400_000L - 60_000L, 60)
            .filter { it.light == Light.SUN }.sumOf { it.endUtc - it.startUtc } / 60_000
        println("  $date  %4d min  %s".format(sun, "█".repeat((sun / 15).toInt())))
        if (sun == 0L && sunless == null) sunless = date
        if (sun > 0L && sunless != null) {
            println("  ── no direct sun from $sunless until before $date")
            sunless = null
        }
        date = date.plusDays(every)
    }
}
