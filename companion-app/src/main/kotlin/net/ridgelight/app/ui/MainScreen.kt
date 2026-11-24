package net.ridgelight.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ridgelight.app.RidgeLightViewModel
import net.ridgelight.app.Settings
import net.ridgelight.app.UiState
import net.ridgelight.app.WeatherState
import net.ridgelight.app.Phase
import net.ridgelight.core.analysis.Light
import net.ridgelight.core.analysis.SunResult
import net.ridgelight.core.dem.Terrarium
import net.ridgelight.core.gpx.LightStyle
import net.ridgelight.core.weather.OpenMeteo
import net.ridgelight.core.weather.Sky
import net.ridgelight.core.weather.SkyReport
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * The overview: plan, result, sky in brief and the profile to drag along, with previews of the
 * detail pages. Meant to fit on one phone screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: UiState,
    vm: RidgeLightViewModel,
    scrub: State<Double>,
    showHint: Boolean,
    onScrub: (Double) -> Unit,
    openFile: () -> Unit,
    showInOsmAnd: () -> Unit,
    open: (Page) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var zoneDialog by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { RouteTitle(state) },
                actions = {
                    TextButton(onClick = openFile) { Text("Open GPX") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(AppIcons.More, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("Sky forecast")
                                        Text("Clouds and fog from Open-Meteo, when you ask", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                trailingIcon = { Switch(checked = state.skyEnabled, onCheckedChange = null) },
                                onClick = { vm.setSkyEnabled(!state.skyEnabled) },
                            )
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("Time zone")
                                        Text(state.settings.zone.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = { menu = false; zoneDialog = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (val phase = state.phase) {
                Phase.Idle -> Intro(openFile)
                Phase.Reading -> Progress("Reading route…", null)
                is Phase.LoadingTerrain -> Progress("Loading terrain ${phase.done}/${phase.total}", phase.done.toFloat() / phase.total.coerceAtLeast(1))
                is Phase.Failed -> Failure(phase.message, openFile)
                Phase.Ready -> Unit
            }
            val route = state.route
            if (route != null) {
                PlanChips(state, vm)
                if (route.missingTiles > 0) {
                    Text("${route.missingTiles} terrain tiles could not be loaded; affected light shows as unknown.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (route.sampled.route.gaps.isNotEmpty()) {
                    Text("Gaps between track segments are assumed to be straight lines.", style = MaterialTheme.typography.bodySmall)
                }
                val result = state.result
                if (result == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    ResultCard(state, result, vm::checkSky) { open(Page.SKY) }
                    ProfileCard(state, result, scrub, showHint, onScrub, vm)
                    PreviewTiles(state, result, vm, open)
                    OsmAndButton(state, showInOsmAnd)
                }
                Footer(state.skyEnabled)
            }
        }
    }
    if (zoneDialog) ZoneDialog(state.settings.zone, { zoneDialog = false }) { vm.update(state.settings.copy(zone = it)); zoneDialog = false }
}

@Composable
private fun RouteTitle(state: UiState) {
    val route = state.route
    if (route == null) {
        Text("Ridge Light")
        return
    }
    val s = route.sampled
    Column {
        Text(route.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val walk = state.result?.let { " · " + duration(it.duration) } ?: ""
        val zone = if (state.settings.zone != ZoneId.systemDefault()) " · ${state.settings.zone.id}" else ""
        Text(
            "%.1f km · ↑%d ↓%d m%s%s".format(s.length / 1000, s.ascent.roundToInt(), s.descent.roundToInt(), walk, zone),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Date, start (and arrival), pace and breaks, each a chip that opens its picker. */
@Composable
private fun PlanChips(state: UiState, vm: RidgeLightViewModel) {
    val settings = state.settings
    val result = state.result
    val context = LocalContext.current
    var paceDialog by remember { mutableStateOf(false) }
    var breaksDialog by remember { mutableStateOf(false) }
    var breakDialog by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            PlanCell("Date", settings.date.format(dayFormat), Modifier.weight(1.05f)) {
                DatePickerDialog(context, { _, y, m, d -> vm.update(settings.copy(date = LocalDate.of(y, m + 1, d))) },
                    settings.date.year, settings.date.monthValue - 1, settings.date.dayOfMonth).show()
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PlanCell(if (result != null) "Start – back" else "Start", result?.let { "${settings.start}–${vm.localTime(it.timeAt(it.duration))}" } ?: "${settings.start}", Modifier.weight(1.2f)) {
                TimePickerDialog(context, { _, h, m -> vm.update(settings.copy(start = LocalTime.of(h, m))) },
                    settings.start.hour, settings.start.minute, true).show()
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PlanCell("Pace", "×%.2f".format(settings.paceFactor), Modifier.weight(0.75f)) { paceDialog = true }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PlanCell("Breaks", if (settings.breaks.isEmpty()) "None" else "☕ ${settings.breaks.size}", Modifier.weight(0.8f)) {
                if (settings.breaks.isEmpty()) {
                    if (result != null) breakDialog = true
                } else {
                    breaksDialog = true
                }
            }
        }
    }
    if (paceDialog) PaceDialog(settings.paceFactor, { paceDialog = false }) { vm.update(settings.copy(paceFactor = it)) }
    if (breaksDialog) {
        BreaksDialog(settings.breaks, result, vm::localTime, { breaksDialog = false }, vm::removeBreak) {
            breaksDialog = false
            breakDialog = true
        }
    }
    if (breakDialog && result != null) BreakDialog(result, null, settings.zone, { breakDialog = false }, vm::addBreak)
}

/** One tappable setting: a small caption over its value. */
@Composable
private fun PlanCell(caption: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun Section(modifier: Modifier = Modifier, padding: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
internal fun Dot(color: Color, size: Dp = 10.dp) = Box(Modifier.size(size).background(color, CircleShape))

@Composable
private fun Intro(openFile: () -> Unit) = Section(padding = 16.dp) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultCard(state: UiState, result: SunResult, checkSky: (Boolean) -> Unit, openSky: () -> Unit) = Section {
    Row {
        Text(duration(result.seconds(Light.SUN)), fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.alignByBaseline())
        Spacer(Modifier.width(8.dp))
        Text(
            "in direct sun · ${(result.sunShare * 100).roundToInt()} % of the hike",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.alignByBaseline(),
        )
    }
    LightBar(result.totals, Modifier.fillMaxWidth().height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (light in Light.entries) {
            val s = result.seconds(light)
            if (s < 30) continue
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(lightColor(light), 8.dp)
                Spacer(Modifier.width(4.dp))
                Text("${shortLabel(light)} ${duration(s)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (state.skyEnabled) {
        HorizontalDivider(Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SkyBrief(state, checkSky, openSky)
    }
}

/** The sky in two lines, or the button to check it; opens the sky page. */
@Composable
private fun SkyBrief(state: UiState, checkSky: (Boolean) -> Unit, openSky: () -> Unit) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (val weather = state.weather) {
        WeatherState.Idle -> Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Clouds or fog on the way?", style = MaterialTheme.typography.bodyMedium)
                Text("Checking sends a few points of your route to Open-Meteo.com.", style = small, color = muted)
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { checkSky(false) }) { Text("Check the sky") }
        }
        WeatherState.Loading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Checking the sky…", style = small)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is WeatherState.Unavailable -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(weather.message, Modifier.weight(1f), style = small, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { checkSky(true) }) { Text("Try again") }
        }
        is WeatherState.Ready -> Column(Modifier.fillMaxWidth().clickable(onClick = openSky), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val sky = state.sky
            if (sky != null) {
                Text(skyHeadline(sky), style = MaterialTheme.typography.bodyMedium)
                Text("🌡 " + weatherLine(sky), style = MaterialTheme.typography.bodyMedium)
            } else {
                val last = Instant.ofEpochMilli(weather.forecast.period.last).atZone(state.settings.zone).toLocalDate()
                Text("No forecast for this date yet; it reaches ${last.format(dayFormat)}.", style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Forecast by Open-Meteo.com", Modifier.weight(1f), style = small, color = muted)
                Text("Details", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Icon(AppIcons.Chevron, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** The profile and timeline to drag along, and where the hiker is at that moment. */
@Composable
private fun ProfileCard(state: UiState, result: SunResult, scrub: State<Double>, showHint: Boolean, onScrub: (Double) -> Unit, vm: RidgeLightViewModel) =
    Section(padding = 10.dp) {
        var breakDialog by remember { mutableStateOf(false) }
        val sky = state.sky
        val t = scrub.value.coerceIn(0.0, result.duration)
        val moment = result.momentAt(t)
        ProfileChart(result, moment.distance, sky?.columns, onScrub = onScrub, hint = if (showHint) "Drag along the profile" else null)
        TimelineBar(result, Modifier.fillMaxWidth().height(22.dp), cursorSeconds = t, onScrub = onScrub)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(lightColor(moment.sample.light))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                val elevation = result.route.elevationAt(moment.distance).let { if (it.isFinite()) " · ${it.roundToInt()} m" else "" }
                Text(
                    "${vm.localTime(result.timeAt(t))} · km %.1f%s".format(moment.distance / 1000, elevation),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(momentDetails(result, sky, t), style = MaterialTheme.typography.bodySmall)
            }
            // Not at the very start or end: a break there would only move the start or the arrival.
            val inside = moment.distance > 50 && moment.distance < result.route.length - 50
            if (moment.breakIndex == null && inside) TextButton(onClick = { breakDialog = true }) { Text("☕ Break here") }
        }
        if (breakDialog) BreakDialog(result, moment.distance, state.settings.zone, { breakDialog = false }, vm::addBreak)
    }

/** "Terrain shade · sun 12° up · overcast" for the moment [t] seconds after departure. */
internal fun momentDetails(result: SunResult, sky: SkyReport?, t: Double): String {
    val moment = result.momentAt(t)
    val sample = moment.sample
    val skyAt = sky?.stretchAt(t)?.sky
    val hidden = skyAt?.sky == Sky.OVERCAST || skyAt?.sky == Sky.IN_CLOUD
    val light = if (sample.light == Light.SUN && hidden) "Terrain sun" else LightStyle.label(sample.light)
    val sun = if (sample.sun.apparentAltitude > 0) " · sun %.0f° up".format(sample.sun.apparentAltitude) else ""
    val doing = if (moment.breakIndex != null) "☕ Break · " else ""
    return "$doing$light$sun" + (skyAt?.let { " · " + skyLabel(it) } ?: "")
}

@Composable
private fun PreviewTiles(state: UiState, result: SunResult, vm: RidgeLightViewModel, open: (Page) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile("Stretches", Modifier.weight(1f), { open(Page.STRETCHES) }) { StretchesPreview(state, result, vm::localTime) }
        Tile("Start times", Modifier.weight(1f), { open(Page.START_TIMES) }) { StartTimesPreview(state, vm::localTime) }
        if (state.skyEnabled) {
            Tile("Which day?", Modifier.weight(1f), {
                vm.checkSky(false)
                open(Page.DAYS)
            }) { DaysPreview(state) }
        }
    }
}

@Composable
private fun Tile(title: String, modifier: Modifier, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        modifier = modifier.height(112.dp),
    ) {
        Column(Modifier.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(AppIcons.Chevron, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/** The first stretches with their start time and light. */
@Composable
private fun StretchesPreview(state: UiState, result: SunResult, localTime: (Long) -> String) {
    val runs = state.sky?.stretches?.map { it.run } ?: result.timeline
    val shown = if (runs.size <= 3) runs else runs.take(2)
    for (run in shown) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(localTime(result.timeAt(run.startSeconds)), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(5.dp))
            Dot(lightColor(run.light).let { if (run.isBreak) it.copy(alpha = 0.5f) else it }, 7.dp)
            Spacer(Modifier.width(4.dp))
            Text(if (run.isBreak) "Break" else shortLabel(run.light), style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
    if (runs.size > shown.size) {
        Text("+${runs.size - shown.size} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ColumnScope.StartTimesPreview(state: UiState, localTime: (Long) -> String) {
    val sweep = state.sweep
    if (sweep == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        return
    }
    val best = sweep.maxByOrNull { it.sunShare }?.takeIf { it.sunShare >= 0.01 }
    Text(best?.let { "Best ${localTime(it.departureUtc)}" } ?: "No sun", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    Text(best?.let { "${(it.sunShare * 100).roundToInt()} % in sun" } ?: "at any start", style = MaterialTheme.typography.bodySmall)
    val chosen = sweep.indexOfFirst { Instant.ofEpochMilli(it.departureUtc).atZone(state.settings.zone).toLocalTime() == state.settings.start }
    MiniColumns(sweep.map { it.sunShare }, lightColor(Light.SUN), Modifier.fillMaxWidth().weight(1f), selected = chosen.takeIf { it >= 0 })
}

@Composable
private fun ColumnScope.DaysPreview(state: UiState) {
    val small = MaterialTheme.typography.bodySmall
    when (state.weather) {
        WeatherState.Idle, is WeatherState.Unavailable -> {
            Text("Compare forecast days", style = small)
            Text("Tap to check the sky", style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        WeatherState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        is WeatherState.Ready -> {
            val days = state.days
            if (days == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                return
            }
            val best = days.maxByOrNull { it.skyWeightedSun }?.takeIf { it.skyWeightedSun >= 600 }
            Text(
                best?.let { it.date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d")) + " " + (it.clearShareOfSun?.let(::skyIcon) ?: "") } ?: "No clear sun",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(if (best != null) "most clear sun" else "in the forecast", style = small)
            val today = LocalDate.now(state.settings.zone)
            MiniColumns(
                days.map { if (it.duration > 0) it.skyWeightedSun / it.duration else 0.0 },
                lightColor(Light.SUN),
                Modifier.fillMaxWidth().weight(1f),
                selected = days.indexOfFirst { it.date == state.settings.date }.takeIf { it >= 0 },
                faded = { days[it].date > today.plusDays(7) },
            )
        }
    }
}

@Composable
private fun OsmAndButton(state: UiState, showInOsmAnd: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = showInOsmAnd, enabled = state.osmandInstalled, modifier = Modifier.fillMaxWidth()) { Text("Show on OsmAnd's map") }
        if (!state.osmandInstalled) Text("Install OsmAnd to see the sunny and shaded stretches on the map.", style = MaterialTheme.typography.bodySmall)
        state.osmandMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun Footer(sky: Boolean) {
    Text(
        "Sun is estimated from the terrain; trees, buildings and snow are not modelled. " +
            (if (sky) "Clouds come from the forecast, only when you check the sky. " else "") +
            "Walking times use the rule behind hiking-sign times (DIN 33466). ${Terrarium.ATTRIBUTION}." +
            if (sky) " ${OpenMeteo.ATTRIBUTION}." else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** "Tue 24 Nov · start 09:00 · pace ×1.00", for the detail pages. */
internal fun planLine(s: Settings) = "${s.date.format(dayFormat)} · start ${s.start} · pace ×%.2f".format(s.paceFactor)
