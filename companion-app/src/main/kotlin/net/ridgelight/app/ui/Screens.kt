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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.ridgelight.app.Phase
import net.ridgelight.app.RidgeLightViewModel
import net.ridgelight.app.RouteInfo
import net.ridgelight.app.Settings
import net.ridgelight.app.UiState
import net.ridgelight.core.analysis.Light
import net.ridgelight.core.analysis.SunResult
import net.ridgelight.core.analysis.SweepEntry
import net.ridgelight.core.dem.Terrarium
import net.ridgelight.core.gpx.LightStyle
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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
                item { SettingsCard(state.settings, state.result, vm::update) }
                state.result?.let { result ->
                    item { ResultCard(result) }
                    item { ProfileCard(result, vm::localTime) }
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
        val walk = result?.let { " · ${LightStyle.duration(it.duration)}" } ?: ""
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
private fun SettingsCard(settings: Settings, result: SunResult?, update: (Settings) -> Unit) = Section("Your hike") {
    val context = LocalContext.current
    var zoneDialog by remember { mutableStateOf(false) }
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
    Text("${(result.sunShare * 100).roundToInt()} % of the walk in direct sun", style = MaterialTheme.typography.bodyMedium)
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
private fun ProfileCard(result: SunResult, localTime: (Long) -> String) = Section("Along the way") {
    var t by remember(result) { mutableDoubleStateOf(0.0) }
    val i = result.schedule.stepAt(t)
    val step = result.route.steps[i]
    val f = ((t - result.schedule.stepStart[i]) / result.schedule.stepSpan[i]).coerceIn(0.0, 1.0)
    val d = step.start.distance + step.length * f
    val sample = result.samples[i]
    ProfileChart(result, d)
    TimelineBar(result)
    Slider(value = t.toFloat(), onValueChange = { t = it.toDouble() }, valueRange = 0f..result.duration.toFloat().coerceAtLeast(1f))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(lightColor(sample.light), CircleShape))
        Spacer(Modifier.width(8.dp))
        val sun = if (sample.sun.apparentAltitude > 0) " · sun %.0f° up".format(sample.sun.apparentAltitude) else ""
        Text(
            "${localTime(result.timeAt(t))} · km %.1f · ${LightStyle.label(sample.light)}$sun".format(d / 1000),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun OsmAndCard(state: UiState, showInOsmAnd: () -> Unit) = Section {
    Button(onClick = showInOsmAnd, enabled = state.osmandInstalled, modifier = Modifier.fillMaxWidth()) { Text("Show on OsmAnd's map") }
    if (!state.osmandInstalled) Text("Install OsmAnd to see the sunny and shaded stretches on the map.", style = MaterialTheme.typography.bodySmall)
    state.osmandMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun StretchesCard(result: SunResult, localTime: (Long) -> String) = Section("Stretches") {
    for (run in result.runs) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 4.dp, height = 28.dp).background(lightColor(run.light), RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${localTime(result.timeAt(run.startSeconds))}–${localTime(result.timeAt(run.endSeconds))} · ${LightStyle.label(run.light)}", style = MaterialTheme.typography.bodyMedium)
                Text("km %.1f–%.1f · %s".format(run.startDistance / 1000, run.endDistance / 1000, LightStyle.duration(run.seconds)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        val best = sweep.maxByOrNull { it.sunShare }
        Text("Same route and pace. Tap a time to use it.", style = MaterialTheme.typography.bodySmall)
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
                Text(if (e === best) "★" else "", Modifier.width(16.dp), color = MaterialTheme.colorScheme.secondary)
            }
        }
    }

@Composable
private fun Footer() {
    Spacer(Modifier.height(4.dp))
    Text(
        "Terrain-only estimate: trees, buildings, clouds and snow are not modelled. Walking times use the rule behind hiking-sign times (DIN 33466). " +
            "${Terrarium.ATTRIBUTION}.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
