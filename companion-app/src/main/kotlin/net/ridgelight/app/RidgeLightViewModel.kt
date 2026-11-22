package net.ridgelight.app

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ridgelight.core.analysis.SunAnalyzer
import net.ridgelight.core.analysis.SunResult
import net.ridgelight.core.analysis.SweepEntry
import net.ridgelight.core.analysis.departures
import net.ridgelight.core.dem.TerrainModel
import net.ridgelight.core.dem.TilePlanner
import net.ridgelight.core.dem.WebMercator
import net.ridgelight.core.geo.LatLon
import net.ridgelight.core.gpx.GpxReader
import net.ridgelight.core.gpx.OsmAndExport
import net.ridgelight.core.pace.Din33466Pace
import net.ridgelight.core.pace.Schedule
import net.ridgelight.core.route.Route
import net.ridgelight.core.route.RouteException
import net.ridgelight.core.route.SampledRoute
import net.ridgelight.core.sun.SunTimes
import net.ridgelight.dem.TerrainTileCache
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.log2

data class Settings(
    val date: LocalDate = LocalDate.now(),
    val start: LocalTime = LocalTime.of(9, 0),
    val zone: ZoneId = ZoneId.systemDefault(),
    /** Multiplies DIN 33466 (hiking-sign) times: 0.8 = 20 % less time than the signs. */
    val paceFactor: Double = 1.0,
) {
    fun departureUtc(): Long = ZonedDateTime.of(date, start, zone).toInstant().toEpochMilli()
}

sealed interface Phase {
    data object Idle : Phase
    data object Reading : Phase
    data class LoadingTerrain(val done: Int, val total: Int) : Phase
    data object Ready : Phase
    data class Failed(val message: String) : Phase
}

data class RouteInfo(val name: String, val sampled: SampledRoute, val missingTiles: Int)

data class UiState(
    val settings: Settings = Settings(),
    val phase: Phase = Phase.Idle,
    val route: RouteInfo? = null,
    val result: SunResult? = null,
    val sweep: List<SweepEntry>? = null,
    val osmandMessage: String? = null,
    val osmandInstalled: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class RidgeLightViewModel(app: Application) : AndroidViewModel(app) {
    private val tiles = TerrainTileCache(app)
    val osmand = OsmAndBridge(app)

    private val _state = MutableStateFlow(UiState(osmandInstalled = osmand.installedPackage() != null))
    val state: StateFlow<UiState> = _state

    // Analyses run one at a time (the analyzer's calls must not overlap); each one spreads its
    // terrain rays over all cores through the worker pool.
    private val analysisThread = Dispatchers.Default.limitedParallelism(1)
    private val workers = WorkerPool(Runtime.getRuntime().availableProcessors())
    private var analyzer: SunAnalyzer? = null
    /** Walking schedule, keyed by the route (analyzer) and pace factor it was made for. */
    private var schedule: Triple<SunAnalyzer, Double, Schedule>? = null
    private var loadJob: Job? = null
    private var analysisJob: Job? = null

    private val clock = DateTimeFormatter.ofPattern("HH:mm")
    fun localTime(utc: Long): String = Instant.ofEpochMilli(utc).atZone(_state.value.settings.zone).format(clock)

    fun refreshOsmAnd() = _state.update { it.copy(osmandInstalled = osmand.installedPackage() != null) }

    fun open(uri: Uri) {
        loadJob?.cancel()
        analysisJob?.cancel()
        _state.update { it.copy(phase = Phase.Reading, route = null, result = null, sweep = null, osmandMessage = null) }
        loadJob = viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                } ?: throw RouteException("The file could not be read.")
                val gpx = withContext(Dispatchers.Default) { GpxReader.read(text) }
                val route = Route.ofSegments(gpx.segments)
                val keys = TilePlanner.forRoute(route.points.map { it.latLon })
                _state.update { it.copy(phase = Phase.LoadingTerrain(0, keys.size)) }
                val loaded = tiles.load(keys) { done, total -> _state.update { it.copy(phase = Phase.LoadingTerrain(done, total)) } }
                if (loaded.tiles.isEmpty()) throw RouteException("Terrain data could not be loaded. Check your connection and try again.")
                val (sampled, newAnalyzer) = withContext(analysisThread) {
                    val terrain = TerrainModel(loaded.tiles)
                    val s = SampledRoute.of(route, terrain)
                    s to SunAnalyzer(s, terrain, runner = workers)
                }
                analyzer = newAnalyzer
                val name = gpx.name ?: uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".gpx") ?: "Route"
                _state.update { it.copy(route = RouteInfo(name, sampled, loaded.missing.size), phase = Phase.Ready) }
                analyse(withSweep = true)
            } catch (e: RouteException) {
                _state.update { it.copy(phase = Phase.Failed(e.message ?: "Invalid route")) }
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(phase = Phase.Failed("This route needs too much terrain data. Try a shorter section.")) }
            }
        }
    }

    fun update(settings: Settings) {
        val old = _state.value.settings
        _state.update { it.copy(settings = settings, osmandMessage = null) }
        val sweepNeeded = settings.date != old.date || settings.paceFactor != old.paceFactor || settings.zone != old.zone
        analyse(withSweep = sweepNeeded)
    }

    private fun analyse(withSweep: Boolean) {
        val a = analyzer ?: return
        val settings = _state.value.settings
        analysisJob?.cancel()
        if (withSweep) _state.update { it.copy(sweep = null) }
        analysisJob = viewModelScope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val result = withContext(analysisThread) {
                val sch = schedule?.takeIf { it.first === a && it.second == settings.paceFactor }?.third
                    ?: Din33466Pace(factor = settings.paceFactor).schedule(a.route).also { schedule = Triple(a, settings.paceFactor, it) }
                a.analyse(sch, settings.departureUtc())
            }
            _state.update { it.copy(result = result) }
            val t1 = SystemClock.elapsedRealtime()
            if (withSweep) {
                val sweep = withContext(analysisThread) { sweep(a, result.schedule, settings) }
                _state.update { it.copy(sweep = sweep) }
            }
            Log.i(TAG, "analysis ${t1 - t0} ms" + if (withSweep) ", sweep ${SystemClock.elapsedRealtime() - t1} ms" else "")
        }
    }

    /** Departures every 30 minutes from an hour before sunrise until half an hour before sunset. */
    private fun sweep(a: SunAnalyzer, schedule: Schedule, s: Settings): List<SweepEntry> {
        val start = a.route.route.start
        val midnight = s.date.atStartOfDay(s.zone).toInstant().toEpochMilli()
        val day = SunTimes.day(start.lat, start.lon, midnight)
        val from = ((day.sunriseUtc ?: midnight) - 3_600_000L).let { it - Math.floorMod(it - midnight, 1_800_000L) }
        val to = (day.sunsetUtc ?: (midnight + 86_400_000L)) - 1_800_000L
        return a.sweep(schedule, departures(from, to, 30))
    }

    fun showInOsmAnd(onNeedsPermission: (android.content.Intent) -> Unit) {
        val state = _state.value
        val result = state.result ?: return
        val route = state.route ?: return
        viewModelScope.launch {
            val files = withContext(Dispatchers.Default) { OsmAndExport.files(result, route.name, ::localTime) }
            val view = mapViewFor(route.sampled)
            val message = when (osmand.show(files, view)) {
                OsmAndBridge.Outcome.SHOWN -> {
                    osmand.launchIntent()?.let { getApplication<Application>().startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    // If OsmAnd was not running, the first map move went nowhere.
                    launch {
                        delay(2_000)
                        osmand.moveMap(view)
                    }
                    null
                }
                OsmAndBridge.Outcome.NOT_INSTALLED -> "OsmAnd is not installed."
                OsmAndBridge.Outcome.NOT_CONNECTED -> "Could not connect to OsmAnd. Open OsmAnd once, then try again."
                OsmAndBridge.Outcome.NOT_ALLOWED -> {
                    val intent = osmand.permissionRequestIntent()
                    if (intent != null) {
                        onNeedsPermission(intent)
                        "Allow Ridge Light to show tracks in OsmAnd, then tap the button again."
                    } else {
                        "OsmAnd refused. Enable Ridge Light under OsmAnd → Plugins → Connected apps, then try again."
                    }
                }
            }
            _state.update { it.copy(osmandMessage = message) }
        }
    }

    /** The whole route on OsmAnd's map: its bounding box centre, at the closest zoom that fits it. */
    private fun mapViewFor(route: SampledRoute): OsmAndBridge.MapView {
        val pts = route.nodes.map { it.position }
        val minLat = pts.minOf { it.lat }
        val maxLat = pts.maxOf { it.lat }
        val minLon = pts.minOf { it.lon }
        val maxLon = pts.maxOf { it.lon }
        // OsmAnd draws 256 px tiles scaled by the display density, like this app's own screen.
        val metrics = getApplication<Application>().resources.displayMetrics
        val tile = 256 * metrics.density
        val tilesAcross = 0.8 * metrics.widthPixels / tile
        val tilesDown = 0.6 * metrics.heightPixels / tile // OsmAnd's bars and buttons cover part of it
        val spanX = (maxLon - minLon) / 360.0
        val spanY = WebMercator.y(minLat) - WebMercator.y(maxLat)
        val zoom = minOf(log2(tilesAcross / spanX), log2(tilesDown / spanY))
        return OsmAndBridge.MapView(
            LatLon((minLat + maxLat) / 2, (minLon + maxLon) / 2),
            if (zoom.isNaN()) 13 else zoom.coerceIn(5.0, 16.0).toInt(),
        )
    }

    override fun onCleared() {
        osmand.disconnect()
        workers.shutdown()
    }
}

private const val TAG = "RidgeLight"
