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
import net.ridgelight.core.weather.WeatherException
import net.ridgelight.dem.TerrainTileCache
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.io.File
import kotlin.math.log2
import kotlin.math.roundToInt

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
    data class Failed(val message: String) : Phase
}

data class RouteInfo(val name: String, val sampled: SampledRoute, val missingTiles: Int)

/** The cloud forecast: loaded by itself while the Clouds switch is on, [Idle] while it is off. */
sealed interface WeatherState {
    data object Idle : WeatherState
    data object Loading : WeatherState
    data class Ready(val forecast: RouteForecast, val fetchedAt: Long) : WeatherState
    data class Unavailable(val message: String) : WeatherState
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
    val osmandMessage: String? = null,
    val osmandInstalled: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class RidgeLightViewModel(app: Application) : AndroidViewModel(app) {
    private val tiles = TerrainTileCache(app)
    val osmand = OsmAndBridge(app)

    private val prefs = app.getSharedPreferences("ridgelight", Application.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        UiState(osmandInstalled = osmand.installedPackage() != null, cloudsEnabled = prefs.getBoolean(PREF_CLOUDS, false)),
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
    private val weatherClient = WeatherClient()
    private var weatherJob: Job? = null
    /** What the day list was computed for, so it is only redone when that changes. */
    private var daysKey: List<Any?>? = null

    private val clock = DateTimeFormatter.ofPattern("HH:mm")
    fun localTime(utc: Long): String = Instant.ofEpochMilli(utc).atZone(_state.value.settings.zone).format(clock)

    fun refreshOsmAnd() = _state.update { it.copy(osmandInstalled = osmand.installedPackage() != null) }

    /** A copy of the last opened track (content links from other apps do not outlive a restart). */
    private val lastTrack = File(app.filesDir, "last.gpx")

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
        if (_state.value.phase != Phase.Idle || !lastTrack.exists()) return
        load({ lastTrack.readText() }, prefs.getString(PREF_LAST_NAME, null), savedPlan(), keep = false)
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
                phase = Phase.Reading, route = null, result = null, sweep = null, osmandMessage = null,
                weather = WeatherState.Idle, sky = null, days = null, settings = plan ?: it.settings.copy(breaks = emptyList()),
            )
        }
        loadJob = viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { runCatching(read).getOrNull() } ?: throw RouteException("The file could not be read.")
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
                val name = gpx.name ?: fallbackName ?: "Route"
                if (keep) {
                    withContext(Dispatchers.IO) { runCatching { lastTrack.writeText(text) } }
                    prefs.edit().putString(PREF_LAST_NAME, name).apply()
                    savePlan(_state.value.settings)
                }
                _state.update { it.copy(route = RouteInfo(name, sampled, loaded.missing.size), phase = Phase.Ready) }
                analyse(withSweep = true)
                loadClouds()
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
        savePlan(settings)
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

    /** The plan for the last track, so it comes back with it. */
    private fun savePlan(s: Settings) {
        prefs.edit()
            .putString(PREF_PLAN_DATE, s.date.toString())
            .putString(PREF_PLAN_START, s.start.toString())
            .putString(PREF_PLAN_ZONE, s.zone.id)
            .putFloat(PREF_PLAN_PACE, s.paceFactor.toFloat())
            .putString(PREF_PLAN_BREAKS, s.breaks.joinToString(";") { "${it.distance}:${it.minutes}" })
            .apply()
    }

    /** The saved plan; a date in the past becomes today. */
    private fun savedPlan(): Settings? = runCatching {
        val zone = ZoneId.of(prefs.getString(PREF_PLAN_ZONE, null))
        val today = LocalDate.now(zone)
        Settings(
            date = LocalDate.parse(prefs.getString(PREF_PLAN_DATE, null)).let { if (it < today) today else it },
            start = LocalTime.parse(prefs.getString(PREF_PLAN_START, null)),
            zone = zone,
            paceFactor = (prefs.getFloat(PREF_PLAN_PACE, 1f) * 20).roundToInt() / 20.0,
            breaks = prefs.getString(PREF_PLAN_BREAKS, "")!!.split(';').filter { it.isNotBlank() }.map {
                val (d, m) = it.split(':')
                PlannedBreak(d.toDouble(), m.toInt())
            },
        )
    }.getOrNull()

    /** Whether the user has been told, once, what turning Clouds on sends where. */
    val cloudsExplained: Boolean get() = prefs.getBoolean(PREF_CLOUDS_EXPLAINED, false)

    /** Clouds on after the explanation was accepted. */
    fun acceptClouds() {
        prefs.edit().putBoolean(PREF_CLOUDS_EXPLAINED, true).apply()
        setClouds(true)
    }

    /** The Clouds switch. Off drops any forecast; on loads it for the route. */
    fun setClouds(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_CLOUDS, enabled).apply()
        if (enabled == _state.value.cloudsEnabled) return
        _state.update { it.copy(cloudsEnabled = enabled) }
        if (enabled) {
            loadClouds()
        } else {
            weatherJob?.cancel()
            val had = _state.value.weather is WeatherState.Ready
            _state.update { it.copy(weather = WeatherState.Idle, sky = null, days = null) }
            if (had) analyse(withSweep = true)
        }
    }

    /**
     * Fetches the cloud forecast for the whole forecast period, while Clouds is on; the only time
     * the app sends route points to the weather service. A forecast less than an hour old is
     * reused unless [force]d.
     */
    fun loadClouds(force: Boolean = false) {
        if (!_state.value.cloudsEnabled) return
        val route = _state.value.route?.sampled ?: return
        val current = _state.value.weather
        if (current is WeatherState.Loading) return
        if (!force && current is WeatherState.Ready && System.currentTimeMillis() - current.fetchedAt < FORECAST_MAX_AGE) return
        weatherJob?.cancel()
        _state.update { it.copy(weather = WeatherState.Loading) }
        weatherJob = viewModelScope.launch {
            val weather = try {
                WeatherState.Ready(weatherClient.fetch(route), System.currentTimeMillis())
            } catch (e: WeatherException) {
                WeatherState.Unavailable(e.message ?: "No forecast.")
            }
            if (_state.value.route?.sampled !== route) return@launch
            _state.update { it.copy(weather = weather) }
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
        if (weather is WeatherState.Ready && System.currentTimeMillis() - weather.fetchedAt >= FORECAST_MAX_AGE) loadClouds(force = true)
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
private const val PREF_CLOUDS = "clouds"
private const val PREF_LAST_NAME = "last_name"
private const val PREF_PLAN_DATE = "plan_date"
private const val PREF_PLAN_START = "plan_start"
private const val PREF_PLAN_ZONE = "plan_zone"
private const val PREF_PLAN_PACE = "plan_pace"
private const val PREF_PLAN_BREAKS = "plan_breaks"
private const val PREF_CLOUDS_EXPLAINED = "clouds_explained"
private const val FORECAST_MAX_AGE = 3_600_000L
