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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.ridgelight.app.DayEntry
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
import net.ridgelight.core.weather.Sky
import net.ridgelight.core.weather.SkyAt
import net.ridgelight.core.weather.SkyReport
import net.ridgelight.core.weather.SkyStretch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
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
                    item { ResultCard(result) }
                    item { SkyCard(state, vm::checkSky) }
                    item { ProfileCard(result, state.sky, state.settings.zone, vm::localTime, vm::addBreak) }
                    item { OsmAndCard(state, showInOsmAnd) }
                    item { StretchesCard(result, state.sky, vm::localTime) }
                    item { DeparturesCard(state.sweep, state.settings, vm::localTime) { vm.update(state.settings.copy(start = it)) } }
                    if (state.weather is WeatherState.Ready) {
                        item { DaysCard(state.days, state.settings) { vm.update(state.settings.copy(date = it)) } }
                    }
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
private fun ResultCard(result: SunResult) = Section {
    Text(LightStyle.duration(result.seconds(Light.SUN)), fontSize = 34.sp, fontWeight = FontWeight.Bold)
    Text("${(result.sunShare * 100).roundToInt()} % of the hike in direct sun, as far as the terrain goes", style = MaterialTheme.typography.bodyMedium)
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
private fun ProfileCard(result: SunResult, sky: SkyReport?, zone: ZoneId, localTime: (Long) -> String, addBreak: (Double, Int) -> Unit) = Section("Along the way") {
    // Kept across re-analyses (e.g. after adding a break here), clamped to the new duration.
    var scrub by remember { mutableDoubleStateOf(0.0) }
    var breakDialog by remember { mutableStateOf(false) }
    val t = scrub.coerceIn(0.0, result.duration)
    val moment = result.momentAt(t)
    val sample = moment.sample
    ProfileChart(result, moment.distance, sky?.columns)
    TimelineBar(result)
    Slider(value = t.toFloat(), onValueChange = { scrub = it.toDouble() }, valueRange = 0f..result.duration.toFloat().coerceAtLeast(1f))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(lightColor(sample.light), CircleShape))
        Spacer(Modifier.width(8.dp))
        val sun = if (sample.sun.apparentAltitude > 0) " · sun %.0f° up".format(sample.sun.apparentAltitude) else ""
        val doing = if (moment.breakIndex != null) "Break · " else ""
        val skyAt = sky?.stretchAt(t)?.sky?.let { " · " + skyLabel(it) } ?: ""
        Text(
            "${localTime(result.timeAt(t))} · km %.1f · $doing${LightStyle.label(sample.light)}$sun$skyAt".format(moment.distance / 1000),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (sky != null) {
        Text(
            "Grey: forecast cloud at the heights and times you pass. Weather models often misplace low fog.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // Not at the very start or end: a break there would only move the start or the arrival.
    val inside = moment.distance > 50 && moment.distance < result.route.length - 50
    if (moment.breakIndex == null && inside) TextButton(onClick = { breakDialog = true }) { Text("Add a break here") }
    if (breakDialog) BreakDialog(result, moment.distance, zone, { breakDialog = false }, addBreak)
}

@Composable
private fun SkyCard(state: UiState, checkSky: (Boolean) -> Unit) = Section("Sky") {
    val small = MaterialTheme.typography.bodySmall
    when (val weather = state.weather) {
        WeatherState.Idle -> {
            Text("See the forecast clouds and fog at the heights you walk, and which days look best.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { checkSky(false) }) { Text("Check the sky") }
            Text("This sends a few points of your route to Open-Meteo (open-meteo.com).", style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        WeatherState.Loading -> {
            Text("Loading the forecast…", style = small)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is WeatherState.Unavailable -> {
            Text(weather.message, style = small, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { checkSky(true) }) { Text("Try again") }
        }
        is WeatherState.Ready -> {
            val sky = state.sky
            if (sky == null) {
                val last = Instant.ofEpochMilli(weather.forecast.period.last).atZone(state.settings.zone).toLocalDate()
                Text(
                    "No forecast for this date. Forecasts reach ${last.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}; pick a day below.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                if (sky.sunSeconds > 0) {
                    val parts = Sky.entries.filter { sky.sunBySky.getValue(it) >= 60 }
                        .joinToString(" · ") { "${LightStyle.duration(sky.sunBySky.getValue(it))} ${skyLabel(SkyAt(it, false))}" }
                    Text("☀ Your ${LightStyle.duration(sky.sunSeconds)} of terrain sun: $parts", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("☀ The terrain hides the sun for the whole hike.", style = MaterialTheme.typography.bodyMedium)
                }
                if (sky.sunAboveClouds >= 60) Text("⛰ Above a sea of cloud for ${LightStyle.duration(sky.sunAboveClouds)} of it", style = MaterialTheme.typography.bodyMedium)
                if (sky.inCloudSeconds >= 60) {
                    val where = sky.inCloudRanges.joinToString(", ") { "km %.1f–%.1f".format(it.start / 1000, it.endInclusive / 1000) }
                    Text("🌫 In cloud or fog for ${LightStyle.duration(sky.inCloudSeconds)} ($where)", style = MaterialTheme.typography.bodyMedium)
                }
                Text("🌡 ${weatherLine(sky)}", style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val at = Instant.ofEpochMilli(weather.fetchedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
                Text("Forecast from $at · ${OpenMeteo.ATTRIBUTION}", Modifier.weight(1f), style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { checkSky(true) }) { Text("Refresh") }
            }
        }
    }
}

private fun weatherLine(sky: SkyReport): String {
    val temp = if (sky.minTemperature.isNaN()) "" else "${sky.minTemperature.roundToInt()} to ${sky.maxTemperature.roundToInt()} °C at your altitudes · "
    val wet = listOfNotNull("rain".takeIf { sky.rain }, "snow".takeIf { sky.snow }, "thunderstorms".takeIf { sky.thunder })
    val precip = when {
        wet.isNotEmpty() -> wet.joinToString(", ") + " possible"
        sky.maxRainChance.isNaN() -> "dry"
        else -> "dry (rain chance up to ${(sky.maxRainChance * 100).roundToInt()} %)"
    }
    val wind = if (sky.maxWind.isNaN()) "" else " · wind up to ${sky.maxWind.roundToInt()} km/h" + if (sky.maxGusts.isNaN()) "" else ", gusts ${sky.maxGusts.roundToInt()}"
    return (temp + precip + wind).replaceFirstChar { it.uppercase() }
}

/** "clear sky", "thin high cloud", … for a sky as seen from the trail. */
private fun skyLabel(s: SkyAt): String = when (s.sky) {
    Sky.CLEAR -> if (s.cloudsBelow) "clear sky, above the clouds" else "clear sky"
    Sky.THIN_CLOUD -> if (s.cloudsBelow) "thin high cloud, above the clouds" else "thin high cloud"
    Sky.PARTLY_CLOUDY -> "partly cloudy"
    Sky.OVERCAST -> "overcast"
    Sky.IN_CLOUD -> "in cloud or fog"
}

/** ☀ when most of the sunny stretches are under clear sky, ⛅ for some, ☁ for few. */
private fun skyIcon(clearShareOfSun: Double) = when {
    clearShareOfSun >= 0.7 -> "☀️"
    clearShareOfSun >= 0.3 -> "⛅"
    else -> "☁️"
}

@Composable
private fun DaysCard(days: List<DayEntry>?, settings: Settings, pick: (LocalDate) -> Unit) = Section("Which day?") {
    if (days == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        return@Section
    }
    Text(
        "Your start, pace and breaks on each forecast day. Bar and % from the terrain; icon: the forecast sky over the sunny stretches. Tap a day to use it.",
        style = MaterialTheme.typography.bodySmall,
    )
    val today = LocalDate.now(settings.zone)
    val best = days.maxByOrNull { it.skyWeightedSun }?.takeIf { it.skyWeightedSun >= 600 }
    val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM")
    for (d in days) {
        val chosen = d.date == settings.date
        // Forecasts lose skill after about a week.
        val alpha = if (d.date > today.plusDays(7)) 0.55f else 1f
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f), RoundedCornerShape(6.dp))
                .clickable { pick(d.date) }
                .padding(horizontal = 6.dp, vertical = 6.dp)
                .alpha(alpha),
        ) {
            Text(d.date.format(dayFormat), Modifier.width(104.dp), fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal)
            LightBar(d.totals, Modifier.weight(1f).height(10.dp))
            Text("${(d.sunShare * 100).roundToInt()} %", Modifier.width(44.dp).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
            Text(d.clearShareOfSun?.let(::skyIcon) ?: "", Modifier.width(28.dp).padding(start = 4.dp))
            Text(if (d === best) "★" else "", Modifier.width(16.dp), color = MaterialTheme.colorScheme.secondary)
        }
    }
    Text("★ most sun under a clear sky. Days more than a week ahead are greyed: their forecast is rough.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun OsmAndCard(state: UiState, showInOsmAnd: () -> Unit) = Section {
    Button(onClick = showInOsmAnd, enabled = state.osmandInstalled, modifier = Modifier.fillMaxWidth()) { Text("Show on OsmAnd's map") }
    if (!state.osmandInstalled) Text("Install OsmAnd to see the sunny and shaded stretches on the map.", style = MaterialTheme.typography.bodySmall)
    state.osmandMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun StretchesCard(result: SunResult, sky: SkyReport?, localTime: (Long) -> String) = Section("Stretches") {
    val rows = sky?.stretches ?: result.timeline.map { SkyStretch(it, null) }
    for ((run, skyAt) in rows.map { it.run to it.sky }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = lightColor(run.light).let { if (run.isBreak) it.copy(alpha = 0.5f) else it }
            Box(Modifier.size(width = 4.dp, height = 28.dp).background(color, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                // Under overcast or in fog, the terrain lets the sun through but the clouds don't.
                val hidden = skyAt?.sky == Sky.OVERCAST || skyAt?.sky == Sky.IN_CLOUD
                val light = if (run.light == Light.SUN && hidden) "Terrain sun" else LightStyle.label(run.light)
                val what = (if (run.isBreak) "☕ Break · " else "") + light + (skyAt?.let { " · " + skyLabel(it) } ?: "")
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
        val withSky = sweep.any { it.weightedSun != null }
        val best = sweep.maxByOrNull { it.sunShare }?.takeIf { it.sunShare >= 0.01 }
        Text(
            "Same route, pace and breaks. Tap a time to use it." +
                if (withSky) " Icon: the forecast sky over that start's sunny stretches." else "",
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
                Text("${(e.sunShare * 100).roundToInt()} %", Modifier.width(52.dp).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                if (withSky) Text(e.weightedShareOfSun?.let(::skyIcon) ?: "", Modifier.width(28.dp))
                Text(if (e === best) "★" else "", Modifier.width(16.dp), color = MaterialTheme.colorScheme.secondary)
            }
        }
    }

@Composable
private fun Footer() {
    Spacer(Modifier.height(4.dp))
    Text(
        "Sun is estimated from the terrain; trees, buildings and snow are not modelled. Clouds come from the forecast, only when you check the sky. " +
            "Walking times use the rule behind hiking-sign times (DIN 33466). ${Terrarium.ATTRIBUTION}. ${OpenMeteo.ATTRIBUTION}.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
