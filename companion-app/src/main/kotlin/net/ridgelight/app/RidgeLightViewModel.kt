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
import net.ridgelight.core.pace.Break
import net.ridgelight.core.pace.Din33466Pace
import net.ridgelight.core.pace.Schedule
import net.ridgelight.core.route.Route
import net.ridgelight.core.route.RouteException
import net.ridgelight.core.route.SampledRoute
import net.ridgelight.core.sun.SunTimes
import net.ridgelight.core.weather.RouteForecast
import net.ridgelight.core.weather.Sky
import net.ridgelight.core.weather.SkyLight
import net.ridgelight.core.weather.SkyModel
import net.ridgelight.core.weather.SkyReport
import net.ridgelight.core.weather.OpenMeteo
import net.ridgelight.app.ui.Words
import net.ridgelight.dem.TerrainTileCache
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.log2

/** A planned stop at a place on the route, [distance] metres from the start. */
data class PlannedBreak(val distance: Double, val minutes: Int)

data class Settings(
    val date: LocalDate = LocalDate.now(),
    val start: LocalTime = LocalTime.of(9, 0),
    val zone: ZoneId = ZoneId.systemDefault(),
    /** Multiplies DIN 33466 (hiking-sign) times: 0.8 = 20 % less time than the signs. */
    val paceFactor: Double = 1.0,
    /** Breaks stay at their place when start time or pace change. */
    val breaks: List<PlannedBreak> = emptyList(),
) {
    fun departureUtc(): Long = ZonedDateTime.of(date, start, zone).toInstant().toEpochMilli()
}

sealed interface Phase {
    data object Idle : Phase
    data object Reading : Phase
    data class LoadingTerrain(val done: Int, val total: Int) : Phase
    data object Ready : Phase
    data class Failed(val problem: LoadProblem) : Phase
}

/** Why a track could not be opened; the UI words it. */
enum class LoadProblem { UNREADABLE, NOT_GPX, NO_POINTS, BAD_COORDINATES, TOO_SHORT, TOO_LONG, NO_TERRAIN, TOO_LARGE }

/** Why the tracks did not reach OsmAnd's map. */
enum class OsmAndProblem { NOT_INSTALLED, NOT_CONNECTED, ASK_PERMISSION, REFUSED }

data class RouteInfo(val name: String, val sampled: SampledRoute, val missingTiles: Int)

/** The cloud forecast: loaded by itself while the Clouds switch is on, [Idle] while it is off. */
sealed interface WeatherState {
    data object Idle : WeatherState
    data object Loading : WeatherState
    data class Ready(val forecast: RouteForecast, val fetchedAt: Long) : WeatherState
    data class Unavailable(val problem: ForecastException) : WeatherState
}

/** One day in the "which day" list: the planned hike on that day, terrain and clouds combined. */
data class DayEntry(val date: LocalDate, val combined: Map<SkyLight, Double>, val duration: Double) {
    val expectedSun: Double get() = SkyLight.expectedSun(combined)
}

data class UiState(
    val settings: Settings = Settings(),
    val phase: Phase = Phase.Idle,
    val route: RouteInfo? = null,
    val result: SunResult? = null,
    val sweep: List<SweepEntry>? = null,
    /** Clouds switched on (kept across launches): the forecast is loaded and combined with the terrain. Off: never fetched. */
    val cloudsEnabled: Boolean = false,
    val weather: WeatherState = WeatherState.Idle,
    /** Terrain and clouds over the planned hike; null without a forecast or if it does not cover the date. */
    val sky: SkyReport? = null,
    /** Forecast days for the planned start, pace and breaks. */
    val days: List<DayEntry>? = null,
    /** Why the last Refresh failed, while the older forecast stays in use. */
    val refreshProblem: ForecastException? = null,
    val osmandProblem: OsmAndProblem? = null,
    val osmandInstalled: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class RidgeLightViewModel(app: Application) : AndroidViewModel(app) {
    private val tiles = TerrainTileCache(app)
    val osmand = OsmAndBridge(app)

    private val store = PlanStore(app.getSharedPreferences("ridgelight", Application.MODE_PRIVATE), app.filesDir)

    private val _state = MutableStateFlow(
        UiState(osmandInstalled = osmand.installedPackage() != null, cloudsEnabled = store.cloudsEnabled),
    )
    val state: StateFlow<UiState> = _state

    /** The point of the hike followed on the profile, in seconds after departure; apart from [state] so dragging stays cheap. */
    private val _scrub = MutableStateFlow(0.0)
    val scrub: StateFlow<Double> = _scrub
    fun scrubTo(seconds: Double) {
        _scrub.value = seconds
    }

    // Analyses run one at a time (the analyzer's calls must not overlap); each one spreads its
    // terrain rays over all cores through the worker pool.
    private val analysisThread = Dispatchers.Default.limitedParallelism(1)
    private val workers = WorkerPool(Runtime.getRuntime().availableProcessors())
    private var analyzer: SunAnalyzer? = null
    /** Walking schedule, keyed by the route (analyzer) and pace factor it was made for. */
    private var schedule: Triple<SunAnalyzer, Double, Schedule>? = null
    private var loadJob: Job? = null
    private var analysisJob: Job? = null
    private val weatherClient = WeatherClient(app.cacheDir)
    private var weatherJob: Job? = null
    /** What the day list was computed for, so it is only redone when that changes. */
    private var daysKey: List<Any?>? = null

    private val clock = DateTimeFormatter.ofPattern("HH:mm")
    fun localTime(utc: Long): String = Instant.ofEpochMilli(utc).atZone(_state.value.settings.zone).format(clock)

    fun refreshOsmAnd() = _state.update { it.copy(osmandInstalled = osmand.installedPackage() != null) }

    fun open(uri: Uri) {
        val app = getApplication<Application>()
        load(
            read = { app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } },
            fallbackName = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".gpx"),
            plan = null,
            keep = true,
        )
    }

    /** Reopens the last track with its plan when the app starts with nothing else to open. */
    fun reopenLast() {
        if (_state.value.phase != Phase.Idle || !store.lastTrack.exists()) return
        load({ store.lastTrack.readText() }, store.lastName, store.load(), keep = false)
    }

    /**
     * Reads a track, loads its terrain and analyses it. A new track starts without breaks; [plan]
     * restores a saved one. With [keep], the track is kept as the one to reopen.
     */
    private fun load(read: () -> String?, fallbackName: String?, plan: Settings?, keep: Boolean) {
        loadJob?.cancel()
        analysisJob?.cancel()
        weatherJob?.cancel()
        _scrub.value = 0.0
        _state.update {
            it.copy(
                phase = Phase.Reading, route = null, result = null, sweep = null, osmandProblem = null,
                weather = WeatherState.Idle, refreshProblem = null, sky = null, days = null, settings = plan ?: it.settings.copy(breaks = emptyList()),
            )
        }
        loadJob = viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { runCatching(read).getOrNull() } ?: return@launch fail(LoadProblem.UNREADABLE)
                val gpx = withContext(Dispatchers.Default) { GpxReader.read(text) }
                val route = Route.ofSegments(gpx.segments)
                val keys = TilePlanner.forRoute(route.points.map { it.latLon })
                _state.update { it.copy(phase = Phase.LoadingTerrain(0, keys.size)) }
                val loaded = tiles.load(keys) { done, total -> _state.update { it.copy(phase = Phase.LoadingTerrain(done, total)) } }
                if (loaded.tiles.isEmpty()) return@launch fail(LoadProblem.NO_TERRAIN)
                val (sampled, newAnalyzer) = withContext(analysisThread) {
                    val terrain = TerrainModel(loaded.tiles)
                    val s = SampledRoute.of(route, terrain)
                    s to SunAnalyzer(s, terrain, runner = workers)
                }
                analyzer = newAnalyzer
                val name = gpx.name ?: fallbackName ?: getApplication<Application>().getString(R.string.route)
                if (keep) {
                    withContext(Dispatchers.IO) { runCatching { store.lastTrack.writeText(text) } }
                    store.lastName = name
                    store.save(_state.value.settings)
                }
                _state.update { it.copy(route = RouteInfo(name, sampled, loaded.missing.size), phase = Phase.Ready) }
                analyse(withSweep = true)
                loadClouds()
            } catch (e: RouteException) {
                fail(
                    when (e.reason) {
                        RouteException.Reason.NOT_GPX -> LoadProblem.NOT_GPX
                        RouteException.Reason.NO_POINTS -> LoadProblem.NO_POINTS
                        RouteException.Reason.BAD_COORDINATES -> LoadProblem.BAD_COORDINATES
                        RouteException.Reason.TOO_SHORT -> LoadProblem.TOO_SHORT
                        RouteException.Reason.TOO_LONG -> LoadProblem.TOO_LONG
                    },
                )
            } catch (e: OutOfMemoryError) {
                fail(LoadProblem.TOO_LARGE)
            }
        }
    }

    private fun fail(problem: LoadProblem) = _state.update { it.copy(phase = Phase.Failed(problem)) }

    fun update(settings: Settings) {
        val old = _state.value.settings
        _state.update { it.copy(settings = settings, osmandProblem = null) }
        store.save(settings)
        val dayChanged = settings.date != old.date || settings.zone != old.zone
        analyse(withSweep = dayChanged || settings.paceFactor != old.paceFactor || settings.breaks != old.breaks)
    }

    /** A break just added: the cursor goes to it once the analysis is done. */
    private var newBreakAt: Double? = null

    fun addBreak(distance: Double, minutes: Int) {
        newBreakAt = distance
        val s = _state.value.settings
        update(s.copy(breaks = (s.breaks + PlannedBreak(distance, minutes)).sortedBy { it.distance }))
    }

    fun removeBreak(b: PlannedBreak) {
        val s = _state.value.settings
        update(s.copy(breaks = s.breaks - b))
    }

    /** Whether the user has been told, once, what turning Clouds on sends where. */
    val cloudsExplained: Boolean get() = store.cloudsExplained

    /** Clouds on after the explanation was accepted. */
    fun acceptClouds() {
        store.cloudsExplained = true
        setClouds(true)
    }

    /** The Clouds switch. Off drops any forecast; on loads it for the route. */
    fun setClouds(enabled: Boolean) {
        store.cloudsEnabled = enabled
        if (enabled == _state.value.cloudsEnabled) return
        _state.update { it.copy(cloudsEnabled = enabled) }
        if (enabled) {
            loadClouds()
        } else {
            weatherJob?.cancel()
            val had = _state.value.weather is WeatherState.Ready
            _state.update { it.copy(weather = WeatherState.Idle, refreshProblem = null, sky = null, days = null) }
            if (had) analyse(withSweep = true)
        }
    }

    /**
     * The cloud forecast for the whole forecast period, while Clouds is on. A forecast for the same
     * route younger than [WeatherClient.MAX_AGE] is reused (from memory or disk), so switching Clouds
     * off and on, or reopening the app, does not fetch again; [force] fetches anyway (Refresh).
     * Fetching is the only time the app sends route points to the weather service.
     */
    fun loadClouds(force: Boolean = false) {
        if (!_state.value.cloudsEnabled) return
        val route = _state.value.route?.sampled ?: return
        val current = _state.value.weather
        if (current is WeatherState.Loading) return
        if (!force && current is WeatherState.Ready && weatherClient.isFresh(current.fetchedAt)) return
        val maxAge = if (force) 0L else WeatherClient.MAX_AGE
        val places = OpenMeteo.places(route)
        weatherJob?.cancel()
        weatherClient.cached(places, maxAge)?.let { f ->
            _state.update { it.copy(weather = WeatherState.Ready(f.forecast, f.fetchedAt)) }
            analyse(withSweep = true)
            return
        }
        _state.update { it.copy(weather = WeatherState.Loading) }
        weatherJob = viewModelScope.launch {
            val weather = try {
                weatherClient.fetch(places, maxAge).let { WeatherState.Ready(it.forecast, it.fetchedAt) }
            } catch (e: ForecastException) {
                // A failed refresh keeps the forecast there is; the Clouds ⓘ says it could not be renewed.
                if (current is WeatherState.Ready) {
                    if (_state.value.route?.sampled === route) _state.update { it.copy(weather = current, refreshProblem = e) }
                    return@launch
                }
                WeatherState.Unavailable(e)
            }
            if (_state.value.route?.sampled !== route) return@launch
            _state.update { it.copy(weather = weather, refreshProblem = null) }
            if (weather is WeatherState.Ready) analyse(withSweep = true)
        }
    }

    private fun analyse(withSweep: Boolean) {
        val a = analyzer ?: return
        val settings = _state.value.settings
        analysisJob?.cancel()
        if (withSweep) _state.update { it.copy(sweep = null) }
        val weather = _state.value.weather
        // An old forecast is replaced; the new one triggers another analysis.
        if (weather is WeatherState.Ready && !weatherClient.isFresh(weather.fetchedAt)) loadClouds(force = true)
        val forecast = (weather as? WeatherState.Ready)?.forecast
        val skyAt = forecast?.let { SkyModel.skyAt(it, a.route) }
        analysisJob = viewModelScope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val (result, sky) = withContext(analysisThread) {
                val walking = schedule?.takeIf { it.first === a && it.second == settings.paceFactor }?.third
                    ?: Din33466Pace(factor = settings.paceFactor).schedule(a.route).also { schedule = Triple(a, settings.paceFactor, it) }
                val sch = walking.withBreaks(a.route, settings.breaks.map { Break(it.distance, it.minutes * 60.0) })
                val r = a.analyse(sch, settings.departureUtc())
                r to forecast?.let { SkyReport.of(r, it) }
            }
            val old = _state.value.result
            val added = newBreakAt?.let { at -> result.breaks.minByOrNull { kotlin.math.abs(it.distance - at) } }
            newBreakAt = null
            when {
                added != null -> _scrub.value = added.stop.startSeconds
                old != null && old.route === result.route -> _scrub.value = samePlace(old, result, _scrub.value)
            }
            _state.update { it.copy(result = result, sky = sky) }
            val t1 = SystemClock.elapsedRealtime()
            if (withSweep) {
                val sweep = withContext(analysisThread) { sweep(a, result.schedule, settings, skyAt) }
                _state.update { it.copy(sweep = sweep) }
            }
            if (forecast != null && skyAt != null) {
                val key = listOf(forecast, a, settings.start, settings.zone, settings.paceFactor, settings.breaks)
                if (key != daysKey) {
                    _state.update { it.copy(days = null) }
                    val days = withContext(analysisThread) { days(a, result.schedule, settings, forecast, skyAt) }
                    daysKey = key
                    _state.update { it.copy(days = days) }
                }
            } else {
                daysKey = null
                _state.update { it.copy(days = null) }
            }
            Log.i(TAG, "analysis ${t1 - t0} ms" + if (withSweep) ", sweep ${SystemClock.elapsedRealtime() - t1} ms" else "")
        }
    }

    /**
     * Where [seconds] marks the hike in [old], as a moment of [new]: the same place on the route,
     * or the same point of a break that is still planned there.
     */
    private fun samePlace(old: SunResult, new: SunResult, seconds: Double): Double {
        val t = seconds.coerceIn(0.0, old.duration)
        val moment = old.momentAt(t)
        moment.breakIndex?.let { k ->
            val was = old.breaks[k].stop
            new.breaks.firstOrNull { it.stop.node == was.node }?.let { b ->
                return (b.stop.startSeconds + (t - was.startSeconds)).coerceAtMost(b.stop.endSeconds)
            }
        }
        return new.secondsAt(moment.distance)
    }

    /** Departures every 30 minutes from an hour before sunrise until half an hour before sunset. */
    private fun sweep(a: SunAnalyzer, schedule: Schedule, s: Settings, skyAt: ((Double, Long) -> Sky?)?): List<SweepEntry> {
        val start = a.route.route.start
        val midnight = s.date.atStartOfDay(s.zone).toInstant().toEpochMilli()
        val day = SunTimes.day(start.lat, start.lon, midnight)
        val from = ((day.sunriseUtc ?: midnight) - 3_600_000L).let { it - Math.floorMod(it - midnight, 1_800_000L) }
        val to = (day.sunsetUtc ?: (midnight + 86_400_000L)) - 1_800_000L
        return a.sweep(schedule, departures(from, to, 30), skyAt)
    }

    /** The planned hike on each day the forecast fully covers, from today on. */
    private fun days(a: SunAnalyzer, schedule: Schedule, s: Settings, forecast: RouteForecast, skyAt: (Double, Long) -> Sky?): List<DayEntry> {
        val today = LocalDate.now(s.zone)
        val last = Instant.ofEpochMilli(forecast.period.last).atZone(s.zone).toLocalDate()
        return generateSequence(today) { it.plusDays(1) }.takeWhile { it <= last }.mapNotNull { date ->
            val r = a.analyse(schedule, ZonedDateTime.of(date, s.start, s.zone).toInstant().toEpochMilli())
            DayEntry(date, r.skyTotals(skyAt) ?: return@mapNotNull null, r.duration)
        }.toList()
    }

    fun showInOsmAnd(onNeedsPermission: (android.content.Intent) -> Unit) {
        val state = _state.value
        val result = state.result ?: return
        val route = state.route ?: return
        viewModelScope.launch {
            val labels = Words(getApplication<Application>().resources).export
            val files = withContext(Dispatchers.Default) { OsmAndExport.files(result, route.name, labels, ::localTime) }
            val view = mapViewFor(route.sampled)
            val problem = when (osmand.show(files, view)) {
                OsmAndBridge.Outcome.SHOWN -> {
                    osmand.launchIntent()?.let { getApplication<Application>().startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    // If OsmAnd was not running, the first map move went nowhere.
                    launch {
                        delay(2_000)
                        osmand.moveMap(view)
                    }
                    null
                }
                OsmAndBridge.Outcome.NOT_INSTALLED -> OsmAndProblem.NOT_INSTALLED
                OsmAndBridge.Outcome.NOT_CONNECTED -> OsmAndProblem.NOT_CONNECTED
                OsmAndBridge.Outcome.NOT_ALLOWED -> {
                    val intent = osmand.permissionRequestIntent()
                    if (intent != null) {
                        onNeedsPermission(intent)
                        OsmAndProblem.ASK_PERMISSION
                    } else {
                        OsmAndProblem.REFUSED
                    }
                }
            }
            _state.update { it.copy(osmandProblem = problem) }
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
