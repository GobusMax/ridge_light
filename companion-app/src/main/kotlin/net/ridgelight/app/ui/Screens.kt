package net.ridgelight.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.ridgelight.app.Phase
import net.ridgelight.app.PlannedBreak
import net.ridgelight.app.RidgeLightViewModel
import net.ridgelight.app.RouteInfo
import net.ridgelight.app.Settings
import net.ridgelight.app.UiState
import net.ridgelight.app.WeatherState
import net.ridgelight.core.analysis.Light
import net.ridgelight.core.analysis.SunResult
import net.ridgelight.core.analysis.SweepEntry
import net.ridgelight.core.dem.Terrarium
import net.ridgelight.core.gpx.LightStyle
import net.ridgelight.core.weather.OpenMeteo
import net.ridgelight.core.weather.RouteForecast
import net.ridgelight.core.weather.WeatherHour
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RidgeLightApp(vm: RidgeLightViewModel, openFile: () -> Unit, showInOsmAnd: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ridge Light") },
                actions = { TextButton(onClick = openFile) { Text("Open GPX") } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            when (val phase = state.phase) {
                Phase.Idle -> item { Intro(openFile) }
                Phase.Reading -> item { Progress("Reading route…", null) }
                is Phase.LoadingTerrain -> item { Progress("Loading terrain ${phase.done}/${phase.total}", phase.done.toFloat() / phase.total.coerceAtLeast(1)) }
                is Phase.Failed -> item { Failure(phase.message, openFile) }
                Phase.Ready -> Unit
            }
            val route = state.route
            if (route != null) {
                item { RouteHeader(route, state.result) }
                item { SettingsCard(state.settings, state.result, vm::update, vm::addBreak, vm::removeBreak, vm::localTime) }
                state.result?.let { result ->
                    item { ResultCard(result, state.expectedSun) }
                    item { WeatherCard(state.weather, result, vm::localTime) }
                    item { ProfileCard(result, state.settings.zone, vm::localTime, vm::addBreak) }
                    item { OsmAndCard(state, showInOsmAnd) }
                    item { StretchesCard(result, vm::localTime) }
                    item { DeparturesCard(state.sweep, state.settings, vm::localTime) { vm.update(state.settings.copy(start = it)) } }
                }
                item { Footer() }
            }
        }
    }
}

@Composable
private fun Section(title: String? = null, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            content()
        }
    }
}

@Composable
private fun Intro(openFile: () -> Unit) = Section {
    Text("See where the mountains block the sun on your hike.", style = MaterialTheme.typography.titleMedium)
    Text(
        "Open a GPX track, pick a date and start time, and Ridge Light estimates when you walk in direct sun or terrain shade, then shows the result on OsmAnd's map.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text("From OsmAnd: open a track → Share → Ridge Light.", style = MaterialTheme.typography.bodySmall)
    Button(onClick = openFile) { Text("Open GPX file") }
}

@Composable
private fun Progress(text: String, fraction: Float?) = Section {
    Text(text)
    if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    else LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun Failure(message: String, openFile: () -> Unit) = Section {
    Text(message, color = MaterialTheme.colorScheme.error)
    OutlinedButton(onClick = openFile) { Text("Open another file") }
}

@Composable
private fun RouteHeader(route: RouteInfo, result: SunResult?) {
    val s = route.sampled
    Column {
        Text(route.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        val walk = result?.let { r ->
            " · ${LightStyle.duration(r.duration)}" +
                if (r.schedule.breaks.isNotEmpty()) " incl. ${LightStyle.duration(r.schedule.breakSeconds)} breaks" else ""
        } ?: ""
        Text(
            "%.1f km · ↑ %d m ↓ %d m%s".format(s.length / 1000, s.ascent.roundToInt(), s.descent.roundToInt(), walk),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (route.missingTiles > 0) {
            Text(
                "${route.missingTiles} terrain tiles could not be loaded; affected light shows as unknown.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (s.route.gaps.isNotEmpty()) {
            Text("Gaps between track segments are assumed to be straight lines.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingsCard(
    settings: Settings,
    result: SunResult?,
    update: (Settings) -> Unit,
    addBreak: (Double, Int) -> Unit,
    removeBreak: (PlannedBreak) -> Unit,
    localTime: (Long) -> String,
) = Section("Your hike") {
    val context = LocalContext.current
    var zoneDialog by remember { mutableStateOf(false) }
    var breakDialog by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(
            onClick = {
                DatePickerDialog(context, { _, y, m, d -> update(settings.copy(date = settings.date.withYear(y).withMonth(m + 1).withDayOfMonth(d))) },
                    settings.date.year, settings.date.monthValue - 1, settings.date.dayOfMonth).show()
            },
            label = { Text(settings.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))) },
        )
        AssistChip(
            onClick = {
                TimePickerDialog(context, { _, h, m -> update(settings.copy(start = LocalTime.of(h, m))) },
                    settings.start.hour, settings.start.minute, true).show()
            },
            label = { Text("Start ${settings.start}") },
        )
    }
    var factor by remember(settings.paceFactor) { mutableFloatStateOf(settings.paceFactor.toFloat()) }
    val speed = when {
        factor < 0.95f -> "quicker than hiking-sign times"
        factor > 1.05f -> "slower than hiking-sign times"
        else -> "times on hiking signs"
    }
    Text("Pace ×%.2f · %s".format(factor, speed), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = factor,
        onValueChange = { factor = (it * 20).roundToInt() / 20f },
        onValueChangeFinished = { update(settings.copy(paceFactor = factor.toDouble())) },
        valueRange = 0.6f..1.6f,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Times in ${settings.zone.id}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { zoneDialog = true }) { Text("Change") }
    }
    if (zoneDialog) ZoneDialog(settings.zone, { zoneDialog = false }) { update(settings.copy(zone = it)); zoneDialog = false }

    for (b in settings.breaks) {
        val placed = result?.breaks?.minByOrNull { abs(it.distance - b.distance) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("☕", Modifier.width(28.dp))
            val time = placed?.let { localTime(result.timeAt(it.stop.startSeconds)) + " · " } ?: ""
            Text("Break ${time}km %.1f · %d min".format(b.distance / 1000, b.minutes), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { removeBreak(b) }) { Text("Remove") }
        }
    }
    TextButton(onClick = { breakDialog = true }, enabled = result != null) { Text("Add a break") }
    if (breakDialog && result != null) BreakDialog(result, null, settings.zone, { breakDialog = false }, addBreak)
}

/**
 * Adds a break, either at a given [place] (metres along the route) or at the place reached at a
 * chosen time with the current plan.
 */
@Composable
private fun BreakDialog(result: SunResult, place: Double?, zone: ZoneId, dismiss: () -> Unit, add: (Double, Int) -> Unit) {
    val context = LocalContext.current
    val departure = Instant.ofEpochMilli(result.departureUtc).atZone(zone)
    val arrival = Instant.ofEpochMilli(result.timeAt(result.duration)).atZone(zone)
    var minutes by remember { mutableIntStateOf(30) }
    var at by remember { mutableStateOf(Instant.ofEpochMilli(result.timeAt(result.duration / 2)).atZone(zone).toLocalTime().withSecond(0).withNano(0)) }
    val elapsed = Duration.between(departure, departure.with(at)).seconds.toDouble()
    val distance = place ?: elapsed.takeIf { it in 0.0..result.duration }?.let { result.momentAt(it).distance }
    val clock = DateTimeFormatter.ofPattern("HH:mm")
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Add a break") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (place == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(
                            onClick = { TimePickerDialog(context, { _, h, m -> at = LocalTime.of(h, m) }, at.hour, at.minute, true).show() },
                            label = { Text("At ${at.format(clock)}") },
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            when {
                                elapsed < 0 -> "Before your start (${departure.format(clock)})"
                                elapsed > result.duration -> "After you arrive (${arrival.format(clock)})"
                                else -> "at km %.1f".format(distance!! / 1000)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Text("At km %.1f".format(place / 1000), style = MaterialTheme.typography.bodyMedium)
                }
                Text("Duration", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in listOf(10, 15, 20, 30, 45, 60, 90)) {
                        FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("$m min") })
                    }
                }
                Text("The break stays at this place when you change the start time or pace.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { add(distance!!, minutes); dismiss() }, enabled = distance != null) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ZoneDialog(current: ZoneId, dismiss: () -> Unit, pick: (ZoneId) -> Unit) {
    var query by remember { mutableStateOf("") }
    val zones = remember { ZoneId.getAvailableZoneIds().filter { '/' in it && !it.startsWith("Etc") }.sorted() }
    AlertDialog(
        onDismissRequest = dismiss,
        confirmButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        title = { Text("Time zone of the hike") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(current.id) })
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(zones.filter { it.contains(query, ignoreCase = true) }.take(200)) { z ->
                        Text(z.replace('_', ' '), Modifier.fillMaxWidth().clickable { pick(ZoneId.of(z)) }.padding(vertical = 10.dp))
                    }
                }
            }
        },
    )
}

@Composable
private fun ResultCard(result: SunResult, expectedSun: Double?) = Section {
    Text(LightStyle.duration(result.seconds(Light.SUN)), fontSize = 34.sp, fontWeight = FontWeight.Bold)
    Text("${(result.sunShare * 100).roundToInt()} % of the hike in direct sun, if the sky is clear", style = MaterialTheme.typography.bodyMedium)
    if (expectedSun != null) {
        Text(
            if (expectedSun < 60) "☁ With the forecast clouds: no direct sun expected"
            else "☁ With the forecast clouds: about ${LightStyle.duration(expectedSun)} (${(100 * expectedSun / result.duration).roundToInt()} %)",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
    LightBar(result.totals)
    for (light in Light.entries) {
        val s = result.seconds(light)
        if (light == Light.UNKNOWN && s == 0.0) continue
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(lightColor(light), CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(LightStyle.label(light), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(LightStyle.duration(s), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ProfileCard(result: SunResult, zone: ZoneId, localTime: (Long) -> String, addBreak: (Double, Int) -> Unit) = Section("Along the way") {
    // Kept across re-analyses (e.g. after adding a break here), clamped to the new duration.
    var scrub by remember { mutableDoubleStateOf(0.0) }
    var breakDialog by remember { mutableStateOf(false) }
    val t = scrub.coerceIn(0.0, result.duration)
    val moment = result.momentAt(t)
    val sample = moment.sample
    ProfileChart(result, moment.distance)
    TimelineBar(result)
    Slider(value = t.toFloat(), onValueChange = { scrub = it.toDouble() }, valueRange = 0f..result.duration.toFloat().coerceAtLeast(1f))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(lightColor(sample.light), CircleShape))
        Spacer(Modifier.width(8.dp))
        val sun = if (sample.sun.apparentAltitude > 0) " · sun %.0f° up".format(sample.sun.apparentAltitude) else ""
        val doing = if (moment.breakIndex != null) "Break · " else ""
        Text(
            "${localTime(result.timeAt(t))} · km %.1f · $doing${LightStyle.label(sample.light)}$sun".format(moment.distance / 1000),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    // Not at the very start or end: a break there would only move the start or the arrival.
    val inside = moment.distance > 50 && moment.distance < result.route.length - 50
    if (moment.breakIndex == null && inside) TextButton(onClick = { breakDialog = true }) { Text("Add a break here") }
    if (breakDialog) BreakDialog(result, moment.distance, zone, { breakDialog = false }, addBreak)
}

@Composable
private fun WeatherCard(weather: WeatherState?, result: SunResult, localTime: (Long) -> String) = Section("Weather along the way") {
    when (weather) {
        null, WeatherState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
        is WeatherState.Unavailable -> Text(weather.message, style = MaterialTheme.typography.bodySmall)
        is WeatherState.Ready -> {
            val rows = remember(result, weather) { hourlyWeather(result, weather.forecast) }
            if (rows.isEmpty()) Text("The forecast does not cover this hike.", style = MaterialTheme.typography.bodySmall)
            val small = MaterialTheme.typography.bodySmall
            for ((time, w) in rows) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(localTime(time), Modifier.width(44.dp), style = small)
                    Text(weatherIcon(w.weatherCode), Modifier.width(28.dp))
                    Text(if (w.temperature.isNaN()) "–" else "${w.temperature.roundToInt()} °C", Modifier.width(48.dp), style = small)
                    Text(percent("☁", w.cloudCover), Modifier.width(58.dp), style = small)
                    Text(percent("☔", w.precipitationProbability), Modifier.width(58.dp), style = small)
                    Text(if (w.windSpeed.isNaN()) "" else "%.0f km/h".format(w.windSpeed), Modifier.weight(1f), style = small, textAlign = TextAlign.End)
                }
            }
            Text(
                "For where you are each hour; temperatures at your altitude. Clouds turn terrain sun into expected sun.",
                style = small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun percent(icon: String, fraction: Double) = if (fraction.isNaN()) "" else "$icon ${(fraction * 100).roundToInt()} %"

/** The forecast at the hiker's place at departure and at each full hour until arrival. */
private fun hourlyWeather(result: SunResult, forecast: RouteForecast): List<Pair<Long, WeatherHour>> {
    val start = result.departureUtc
    val end = result.timeAt(result.duration)
    val times = buildList {
        add(start)
        var t = (start / 3_600_000L + 1) * 3_600_000L
        while (t <= end) {
            add(t)
            t += 3_600_000L
        }
    }
    val nodes = result.route.nodes
    return times.mapNotNull { t ->
        val m = result.momentAt((t - start) / 1000.0)
        val elevation = nodes.minBy { abs(it.distance - m.distance) }.elevation
        forecast.at(m.distance, t, elevation)?.let { t to it }
    }
}

private fun weatherIcon(code: Int) = when (code) {
    0 -> "☀️"
    1 -> "🌤️"
    2 -> "⛅"
    3 -> "☁️"
    45, 48 -> "🌫️"
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67 -> "🌧️"
    71, 73, 75, 77, 85, 86 -> "🌨️"
    80, 81, 82 -> "🌦️"
    95, 96, 99 -> "⛈️"
    else -> "·"
}

@Composable
private fun OsmAndCard(state: UiState, showInOsmAnd: () -> Unit) = Section {
    Button(onClick = showInOsmAnd, enabled = state.osmandInstalled, modifier = Modifier.fillMaxWidth()) { Text("Show on OsmAnd's map") }
    if (!state.osmandInstalled) Text("Install OsmAnd to see the sunny and shaded stretches on the map.", style = MaterialTheme.typography.bodySmall)
    state.osmandMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun StretchesCard(result: SunResult, localTime: (Long) -> String) = Section("Stretches") {
    for (run in result.timeline) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = lightColor(run.light).let { if (run.isBreak) it.copy(alpha = 0.5f) else it }
            Box(Modifier.size(width = 4.dp, height = 28.dp).background(color, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                val what = (if (run.isBreak) "☕ Break · " else "") + LightStyle.label(run.light)
                Text("${localTime(result.timeAt(run.startSeconds))}–${localTime(result.timeAt(run.endSeconds))} · $what", style = MaterialTheme.typography.bodyMedium)
                val where = if (run.isBreak) "at km %.1f".format(run.startDistance / 1000) else "km %.1f–%.1f".format(run.startDistance / 1000, run.endDistance / 1000)
                Text("$where · ${LightStyle.duration(run.seconds)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DeparturesCard(sweep: List<SweepEntry>?, settings: Settings, localTime: (Long) -> String, pick: (LocalTime) -> Unit) =
    Section("Other start times") {
        if (sweep == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Section
        }
        val withClouds = sweep.isNotEmpty() && sweep.all { it.expectedShare != null }
        val share = { e: SweepEntry -> if (withClouds) e.expectedShare!! else e.sunShare }
        // Ties (e.g. overcast all day) go to the most terrain sun; no star if there is no sun to speak of.
        val best = sweep.maxWithOrNull(compareBy<SweepEntry>({ (share(it) * 100).roundToInt() }, { it.sunShare }))
            ?.takeIf { share(it) >= 0.01 }
        Text(
            if (withClouds) "Same route, pace and breaks. Sun share with the forecast clouds; terrain-only share in grey. Tap a time to use it."
            else "Same route, pace and breaks. Tap a time to use it.",
            style = MaterialTheme.typography.bodySmall,
        )
        for (e in sweep) {
            val time = Instant.ofEpochMilli(e.departureUtc).atZone(settings.zone).toLocalTime()
            val chosen = time == settings.start
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f), RoundedCornerShape(6.dp))
                    .clickable { pick(time) }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            ) {
                Text(localTime(e.departureUtc), Modifier.width(52.dp), fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal)
                LightBar(e.totals, Modifier.weight(1f).height(10.dp))
                if (withClouds) {
                    Text("${(e.sunShare * 100).roundToInt()} %", Modifier.width(44.dp).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${(share(e) * 100).roundToInt()} %", Modifier.width(44.dp).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Text(if (e === best) "★" else "", Modifier.width(16.dp), color = MaterialTheme.colorScheme.secondary)
            }
        }
    }

@Composable
private fun Footer() {
    Spacer(Modifier.height(4.dp))
    Text(
        "Sun is estimated from the terrain; trees, buildings and snow are not modelled. With a forecast, its clouds turn that into expected sun. " +
            "Walking times use the rule behind hiking-sign times (DIN 33466). ${Terrarium.ATTRIBUTION}. " +
            "${OpenMeteo.ATTRIBUTION}; the forecast request contains a few points of the route.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
