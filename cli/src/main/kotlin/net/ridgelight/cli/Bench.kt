package net.ridgelight.cli

import net.ridgelight.core.analysis.SunAnalyzer
import net.ridgelight.core.analysis.SunResult
import net.ridgelight.core.analysis.SweepEntry
import net.ridgelight.core.analysis.TaskRunner
import net.ridgelight.core.analysis.departures
import net.ridgelight.core.gpx.GpxReader
import net.ridgelight.core.pace.Din33466Pace
import net.ridgelight.core.route.Route
import net.ridgelight.core.route.SampledRoute
import net.ridgelight.core.sun.SunTimes
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.io.path.readText

/**
 * Replays what the app does when a route is opened and parameters change, with a fresh analyzer
 * per round, and prints timings plus a checksum of every result (to prove optimisations exact).
 */
internal fun bench(opts: Options) {
    val file = opts.positional.firstOrNull() ?: throw IllegalArgumentException("Missing GPX file")
    val gpx = GpxReader.read(Path.of(file).readText())
    val route = Route.ofSegments(gpx.segments)
    val zone = zone(opts)
    val date = LocalDate.parse(opts.require("date"))
    val start = LocalTime.parse(opts.require("start"))
    val rounds = opts.get("rounds")?.toInt() ?: 5
    val threads = opts.get("threads")?.toInt() ?: 1
    val pool = if (threads > 1) java.util.concurrent.Executors.newFixedThreadPool(threads) else null
    val runner = pool?.let { ExecutorRunner(it, threads) } ?: TaskRunner.Sequential
    val terrain = loadTerrain(opts, route.points.map { it.latLon })
    val sampled = SampledRoute.of(route, terrain)
    System.err.println("%.1f km, %d steps, %d tiles".format(route.length / 1000, sampled.steps.size, terrain.tileCount))

    class Step(val name: String, val date: LocalDate, val start: LocalTime, val factor: Double, val sweep: Boolean)
    val steps = listOf(
        Step("open", date, start, 1.0, true),
        Step("start +1h", date, start.plusHours(1), 1.0, false),
        Step("pace 0.8", date, start.plusHours(1), 0.8, true),
        Step("date +1", date.plusDays(1), start.plusHours(1), 0.8, true),
        Step("date +30", date.plusDays(30), start.plusHours(1), 0.8, true),
    )
    val times = Array(steps.size) { DoubleArray(rounds) }
    var checksum = 0L
    val dump = opts.get("dump")?.let { java.io.File(it).printWriter() }
    repeat(rounds) { round ->
        val analyzer = SunAnalyzer(sampled, terrain, runner = runner)
        var sum = 0L
        steps.forEachIndexed { k, s ->
            val t0 = System.nanoTime()
            val schedule = Din33466Pace(factor = s.factor).schedule(sampled)
            val departure = ZonedDateTime.of(s.date, s.start, zone).toInstant().toEpochMilli()
            val result = analyzer.analyse(schedule, departure)
            val sweep = if (s.sweep) analyzer.sweep(schedule, dayDepartures(route, s.date, zone)) else emptyList()
            times[k][round] = (System.nanoTime() - t0) / 1e6
            sum = sum * 31 + fingerprint(result, sweep)
            if (round == 0 && dump != null) {
                dump.println("# ${s.name}")
                result.samples.forEach { dump.println("${it.light.ordinal} ${it.horizonAngle}") }
                sweep.forEach { e -> dump.println("S " + e.totals.entries.joinToString(" ") { "${it.key.ordinal}:${it.value}" }) }
            }
        }
        if (round == 0) checksum = sum else check(sum == checksum) { "results differ between rounds" }
    }
    dump?.close()
    pool?.shutdown()
    for ((k, s) in steps.withIndex()) {
        val later = times[k].drop(1).sorted()
        println("  %-10s first %6.0f ms   warm median %6.0f ms".format(s.name, times[k][0], later.getOrElse(later.size / 2) { Double.NaN }))
    }
    println("  checksum %016x".format(checksum))
}

private fun fingerprint(result: SunResult, sweep: List<SweepEntry>): Long {
    var h = 17L
    for (s in result.samples) h = h * 31 + s.light.ordinal * 1_000_003L + s.horizonAngle.toRawBits()
    for (e in sweep) for ((light, seconds) in e.totals) h = h * 31 + light.ordinal + seconds.toRawBits()
    return h
}

/** Same departures as the app: every 30 min from an hour before sunrise to half an hour before sunset. */
internal fun dayDepartures(route: Route, date: LocalDate, zone: ZoneId): List<Long> {
    val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val day = SunTimes.day(route.start.lat, route.start.lon, midnight)
    val from = ((day.sunriseUtc ?: midnight) - 3_600_000L).let { it - Math.floorMod(it - midnight, 1_800_000L) }
    val to = (day.sunsetUtc ?: (midnight + 86_400_000L)) - 1_800_000L
    return departures(from, to, 30)
}


internal class ExecutorRunner(private val executor: java.util.concurrent.ExecutorService, override val parallelism: Int) : TaskRunner {
    override fun runAll(tasks: List<() -> Unit>) {
        executor.invokeAll(tasks.map { task -> java.util.concurrent.Callable { task() } }).forEach { it.get() }
    }
}
