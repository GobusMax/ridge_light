package net.ridgelight.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ridgelight.app.Phase
import net.ridgelight.app.RidgeLightViewModel
import net.ridgelight.app.Settings
import net.ridgelight.app.UiState
import net.ridgelight.app.WeatherState
import net.ridgelight.core.analysis.Light
import net.ridgelight.core.analysis.SunResult
import net.ridgelight.core.weather.OpenMeteo
import net.ridgelight.core.weather.Sky
import net.ridgelight.core.weather.SkyLight
import net.ridgelight.core.weather.SkyReport
import net.ridgelight.core.weather.SkyStretch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** The overview: plan, the sun with the profile to drag along, stretches, and the way to the other pages. */
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
    var cloudsDialog by remember { mutableStateOf(false) }
    val toggleClouds = { on: Boolean -> if (on && !vm.cloudsExplained) cloudsDialog = true else vm.setClouds(on) }
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
                                        Text("Time zone")
                                        Text(state.settings.zone.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = { menu = false; zoneDialog = true },
                            )
                            DropdownMenuItem(text = { Text("About") }, onClick = { menu = false; open(Page.ABOUT) })
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
            val route = state.route ?: return@Column
            PlanRow(state, vm)
            if (route.missingTiles > 0) {
                Text("${route.missingTiles} terrain tiles could not be loaded; affected light shows as unknown.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (route.sampled.route.gaps.isNotEmpty()) {
                Text("Gaps between track segments are assumed to be straight lines.", style = MaterialTheme.typography.bodySmall)
            }
            val result = state.result
            if (result == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                return@Column
            }
            SunCard(state, result, scrub.value.coerceIn(0.0, result.duration), showHint, onScrub, vm, toggleClouds)
            PreviewTiles(state, vm, open)
            OsmAndButton(state, showInOsmAnd)
        }
    }
    if (zoneDialog) ZoneDialog(state.settings.zone, { zoneDialog = false }) { vm.update(state.settings.copy(zone = it)); zoneDialog = false }
    if (cloudsDialog) CloudsDialog({ cloudsDialog = false }, vm::acceptClouds)
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

/** Date, start and return, pace and breaks: one row of cells, each opening its picker. */
@Composable
private fun PlanRow(state: UiState, vm: RidgeLightViewModel) {
    val settings = state.settings
    val result = state.result
    val context = LocalContext.current
    var paceDialog by remember { mutableStateOf(false) }
    var breaksDialog by remember { mutableStateOf(false) }
    var breakDialog by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            PlanCell("Date", settings.date.format(dayFormat), Modifier.weight(1f)) {
                DatePickerDialog(context, { _, y, m, d -> vm.update(settings.copy(date = LocalDate.of(y, m + 1, d))) },
                    settings.date.year, settings.date.monthValue - 1, settings.date.dayOfMonth).show()
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PlanCell(if (result != null) "Start – back" else "Start", result?.let { "${settings.start}–${vm.localTime(it.timeAt(it.duration))}" } ?: "${settings.start}", Modifier.weight(1.15f)) {
                TimePickerDialog(context, { _, h, m -> vm.update(settings.copy(start = LocalTime.of(h, m))) },
                    settings.start.hour, settings.start.minute, true).show()
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PlanCell("Pace", paceName(settings.paceFactor), Modifier.weight(0.95f)) { paceDialog = true }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PlanCell("Breaks", if (settings.breaks.isEmpty()) "None" else "☕ ${settings.breaks.size}", Modifier.weight(0.7f)) {
                if (settings.breaks.isEmpty()) {
                    if (result != null) breakDialog = true
                } else {
                    breaksDialog = true
                }
            }
        }
    }
    if (paceDialog) {
        val walkingAtOne = result?.let { (it.duration - it.schedule.breakSeconds) / settings.paceFactor }
        PaceDialog(settings.paceFactor, walkingAtOne, result?.schedule?.breakSeconds ?: 0.0, { paceDialog = false }) { vm.update(settings.copy(paceFactor = it)) }
    }
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

/**
 * The answer: time in sun (with the clouds when they are on), the Clouds switch, the profile and
 * its bars to drag along, and where the hiker is at the cursor. It looks the same with and
 * without clouds; explanations are behind the ⓘ buttons.
 */
@Composable
private fun SunCard(
    state: UiState,
    result: SunResult,
    t: Double,
    showHint: Boolean,
    onScrub: (Double) -> Unit,
    vm: RidgeLightViewModel,
    toggleClouds: (Boolean) -> Unit,
) = Section(padding = 10.dp) {
    val sky = state.sky
    val combined = sky?.totals ?: SkyLight.ofTerrain(result.totals)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Headline(combined)
            CloudStatus(state, result, combined, vm)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 8.dp)) {
            Switch(checked = state.cloudsEnabled, onCheckedChange = toggleClouds)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Clouds", style = MaterialTheme.typography.labelSmall)
                InfoButton("Clouds") { CloudsInfo(state) { vm.loadClouds(force = true) } }
            }
        }
    }
    Box {
        ProfileChart(
            result, sky, state.settings.zone, t, onScrub,
            hint = if (showHint) "Drag along the profile" else null,
            cloudsNote = cloudsNote(state),
        )
        InfoButton("Reading the profile", Modifier.align(Alignment.TopEnd)) { ProfileInfo(combined, state.cloudsEnabled) }
    }
    Readout(result, sky, t, state.settings, vm)
}

/** Sun, and sun between clouds as "+…" in its own colour, like the profile; one line. */
@Composable
private fun Headline(combined: Map<SkyLight, Double>) {
    val sun = combined.getValue(SkyLight.SUN)
    val partly = combined.getValue(SkyLight.SUN_AND_CLOUDS)
    val onlyBetween = sun < 60 && partly >= 60
    Row {
        if (!onlyBetween) Text(duration(sun), fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.alignByBaseline())
        if (partly >= 60) {
            if (!onlyBetween) Spacer(Modifier.width(6.dp))
            Text(
                (if (onlyBetween) "" else "+") + duration(partly),
                fontSize = if (onlyBetween) 30.sp else 22.sp,
                fontWeight = FontWeight.Bold,
                color = PartlyText,
                modifier = Modifier.alignByBaseline(),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("in sun", style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.alignByBaseline())
    }
}

/** One line under the headline: what the number includes, or how the cloud forecast is getting on. */
@Composable
private fun CloudStatus(state: UiState, result: SunResult, combined: Map<SkyLight, Double>, vm: RidgeLightViewModel) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (val weather = state.weather) {
        WeatherState.Idle, WeatherState.Loading -> if (state.cloudsEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text("Loading the cloud forecast…", style = small, color = muted, maxLines = 1)
            }
        } else {
            Text("${(result.sunShare * 100).roundToInt()} % of the hike · terrain alone", style = small, color = muted, maxLines = 1)
        }
        is WeatherState.Unavailable -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(weather.message, Modifier.weight(1f, fill = false), style = small, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "Retry",
                Modifier.clickable { vm.loadClouds(force = true) }.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is WeatherState.Ready -> {
            val partly = combined.getValue(SkyLight.SUN_AND_CLOUDS)
            val text = when {
                state.sky == null -> "No cloud forecast for this date yet · terrain alone"
                partly < 60 -> "Terrain alone ${duration(result.seconds(Light.SUN))}"
                combined.getValue(SkyLight.SUN) < 60 -> "Between clouds · terrain alone ${duration(result.seconds(Light.SUN))}"
                else -> "+ between clouds · terrain alone ${duration(result.seconds(Light.SUN))}"
            }
            Text(text, style = small, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Written into the empty Clouds bar when there is no cloud forecast to show. */
private fun cloudsNote(state: UiState): String? = if (!state.cloudsEnabled) "off" else when (state.weather) {
    WeatherState.Idle, WeatherState.Loading -> "loading…"
    is WeatherState.Unavailable -> "no forecast"
    is WeatherState.Ready -> if (state.sky == null) "no forecast for this date yet" else null
}

/** The colours, the two bars and how to use the profile. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileInfo(combined: Map<SkyLight, Double>, clouds: Boolean) {
    val shown = if (clouds) SkyLight.entries else listOf(SkyLight.SUN, SkyLight.SHADE, SkyLight.DARK, SkyLight.UNKNOWN)
    for (light in shown) {
        val s = combined.getValue(light)
        if (light == SkyLight.UNKNOWN && s < 30) continue
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(skyLightColor(light), 10.dp)
            Spacer(Modifier.width(8.dp))
            Text(label(light), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(duration(s), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
    InfoText("Terrain bar: sun and shade from the mountains alone. Clouds bar: the sky above you when you get there:")
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (s in listOf(Sky.CLEAR, Sky.THIN_CLOUD, Sky.PARTLY_CLOUDY, Sky.OVERCAST, Sky.IN_CLOUD)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(width = 14.dp, height = 8.dp)
                        .background(grey.copy(alpha = skyAlpha(s)), RoundedCornerShape(1.dp))
                        .border(0.5.dp, grey.copy(alpha = 0.4f), RoundedCornerShape(1.dp)),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    when (s) {
                        Sky.CLEAR -> "clear"
                        Sky.THIN_CLOUD -> "thin"
                        Sky.PARTLY_CLOUDY -> "broken"
                        Sky.OVERCAST -> "overcast"
                        Sky.IN_CLOUD -> "fog"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    InfoText("Drag along the profile or the bars to follow the hike; near a break (ring) the cursor jumps to it. The marks underneath show where you are at each full hour.")
}

/** What Clouds does, what it sends, the current forecast and how far to trust it. */
@Composable
private fun CloudsInfo(state: UiState, refresh: () -> Unit) {
    InfoText(
        "Combines the terrain with the cloud forecast at your altitude and time: under a clear sky sun stays sun, " +
            "under broken cloud it becomes “sun and clouds”, overcast or fog hides it.",
    )
    InfoText("The forecast comes from Open-Meteo for up to six points of your route and is kept for three hours.")
    val weather = state.weather
    val sky = state.sky
    if (state.cloudsEnabled && weather is WeatherState.Ready) {
        if (sky != null && sky.inCloudSeconds >= 60) {
            val where = sky.inCloudRanges.joinToString(", ") { "km %.1f–%.1f".format(it.start / 1000, it.endInclusive / 1000) }
            InfoText("In fog or cloud for ${duration(sky.inCloudSeconds)} ($where).")
        }
        if (sky != null && sky.sunAboveClouds >= 60) InfoText("Above a sea of cloud for ${duration(sky.sunAboveClouds)} of the sun.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            val at = Instant.ofEpochMilli(weather.fetchedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
            Text("Forecast from $at", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = refresh) { Text("Refresh") }
        }
    }
    Text(
        "Forecast models work on a grid of a few kilometres and on levels a few hundred metres apart, often misplace low fog, " +
            "and are rough more than a week ahead. ${OpenMeteo.ATTRIBUTION}.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Where the hiker is at the cursor, in what light, and "Break here". */
@Composable
private fun Readout(result: SunResult, sky: SkyReport?, t: Double, settings: Settings, vm: RidgeLightViewModel) {
    var breakDialog by remember { mutableStateOf(false) }
    val moment = result.momentAt(t)
    val k = moment.breakIndex
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (k != null) {
            val b = result.breaks[k]
            val byLight = b.pieces.indices.groupBy { p -> SkyLight.of(b.pieces[p].sample.light, sky?.breakSky?.get(k)?.get(p)?.sky) }
                .mapValues { (_, ps) -> ps.sumOf { b.pieces[it].endSeconds - b.pieces[it].startSeconds } }
            Dot(skyLightColor(byLight.maxBy { it.value }.key))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "☕ Break ${vm.localTime(result.timeAt(b.stop.startSeconds))}–${vm.localTime(result.timeAt(b.stop.endSeconds))} · km %.1f".format(b.distance / 1000),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(byLight.entries.sortedBy { it.key.ordinal }.joinToString(" · ") { "${label(it.key)} ${duration(it.value)}" }, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val i = result.schedule.stepAt(t)
            val skyAt = sky?.stepSky?.get(i)
            val light = SkyLight.of(result.samples[i].light, skyAt?.sky)
            Dot(skyLightColor(light))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                val elevation = result.route.elevationAt(moment.distance).let { if (it.isFinite()) " · ${it.roundToInt()} m" else "" }
                Text(
                    "${vm.localTime(result.timeAt(t))} · km %.1f%s".format(moment.distance / 1000, elevation),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val sun = moment.sample.sun.apparentAltitude.let { if (it > 0) " · sun %.0f° up".format(it) else "" }
                val clear = skyAt?.takeIf { light == SkyLight.SUN }?.let { " · " + skyLabel(it.sky) } ?: ""
                Text(label(light, skyAt) + sun + clear, style = MaterialTheme.typography.bodySmall)
            }
            // Not at the very start or end: a break there would only move the start or the arrival.
            if (moment.distance > 50 && moment.distance < result.route.length - 50) {
                TextButton(onClick = { breakDialog = true }) { Text("☕ Break here") }
            }
        }
    }
    if (breakDialog) BreakDialog(result, moment.distance, settings.zone, { breakDialog = false }, vm::addBreak)
}

@Composable
private fun PreviewTiles(state: UiState, vm: RidgeLightViewModel, open: (Page) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile("Start times", Modifier.weight(1f), true, { open(Page.START_TIMES) }) { StartTimesPreview(state, vm::localTime) }
        // Always there, so nothing moves when Clouds is switched; it needs the forecast to work.
        Tile("Which day?", Modifier.weight(1f), state.cloudsEnabled, {
            if (state.weather is WeatherState.Unavailable) vm.loadClouds(force = true)
            open(Page.DAYS)
        }) { DaysPreview(state) }
    }
}

@Composable
private fun Tile(title: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.cardColors(containerColor = container, disabledContainerColor = container),
        modifier = modifier.height(104.dp),
    ) {
        Column(
            Modifier.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 10.dp).alpha(if (enabled) 1f else 0.6f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(AppIcons.Chevron, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun ColumnScope.StartTimesPreview(state: UiState, localTime: (Long) -> String) {
    val sweep = state.sweep
    if (sweep == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        return
    }
    val best = sweep.maxByOrNull { it.expectedSun }?.takeIf { it.expectedSun >= 60 }
    Text(best?.let { "Best ${localTime(it.departureUtc)}" } ?: "No sun", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    Text(best?.let { sunPhrase(it.combined) } ?: "at any start", style = MaterialTheme.typography.bodySmall, maxLines = 1)
    val chosen = sweep.indexOfFirst { Instant.ofEpochMilli(it.departureUtc).atZone(state.settings.zone).toLocalTime() == state.settings.start }
    MiniColumns(
        sweep.map { if (it.duration > 0) it.expectedSun / it.duration else 0.0 },
        skyLightColor(SkyLight.SUN),
        Modifier.fillMaxWidth().weight(1f),
        selected = chosen.takeIf { it >= 0 },
    )
}

@Composable
private fun ColumnScope.DaysPreview(state: UiState) {
    val small = MaterialTheme.typography.bodySmall
    if (!state.cloudsEnabled) {
        Text("Needs Clouds", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text("Switch on Clouds to compare the forecast days", style = small)
        return
    }
    when (state.weather) {
        WeatherState.Idle, WeatherState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        is WeatherState.Unavailable -> {
            Text("No forecast", style = MaterialTheme.typography.bodyMedium)
            Text("Tap to try again", style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is WeatherState.Ready -> {
            val days = state.days
            if (days == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                return
            }
            val best = days.maxByOrNull { it.expectedSun }?.takeIf { it.expectedSun >= 600 }
            Text(best?.let { "Best " + it.date.format(DateTimeFormatter.ofPattern("EEE d")) } ?: "Little sun", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(best?.let { sunPhrase(it.combined) } ?: "in the forecast", style = small, maxLines = 1)
            val today = LocalDate.now(state.settings.zone)
            MiniColumns(
                days.map { if (it.duration > 0) it.expectedSun / it.duration else 0.0 },
                skyLightColor(SkyLight.SUN),
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

/** Sun between clouds as text: the legend's amber, darkened to read on the card. */
private val PartlyText = Color(0xFFA8823A)

/** "Tue 24 Nov · start 09:00 · pace ×1.00", for the other pages. */
internal fun planLine(s: Settings) = "${s.date.format(dayFormat)} · start ${s.start} · pace ${paceName(s.paceFactor)}"
