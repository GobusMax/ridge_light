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
import androidx.compose.ui.draw.rotate
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
    var stretchesOpen by rememberSaveable { mutableStateOf(false) }
    var forecastOpen by rememberSaveable { mutableStateOf(false) }
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
            val stretches = state.sky?.stretches ?: result.timeline.map { SkyStretch(it, SkyLight.of(it.light, null), null) }
            val t = scrub.value.coerceIn(0.0, result.duration)
            val current = stretches.indexOfFirst { t >= it.run.startSeconds && t < it.run.endSeconds }.let { if (it < 0) stretches.lastIndex else it }
            val highlight = stretches.getOrNull(current)?.run?.takeIf { stretchesOpen }?.let { it.startDistance..it.endDistance }
            SunCard(state, result, t, showHint, onScrub, vm, toggleClouds, highlight)
            Expandable("Stretches", "${stretches.size}", stretchesOpen, { stretchesOpen = !stretchesOpen }) {
                stretches.forEachIndexed { i, st -> StretchRow(result, st, i == current, vm::localTime) { onScrub(st.run.startSeconds) } }
                Text(
                    "Tap a stretch to find it on the profile.",
                    Modifier.padding(start = 6.dp, top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val weather = state.weather
            if (state.cloudsEnabled && weather is WeatherState.Ready) {
                val at = Instant.ofEpochMilli(weather.fetchedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
                Expandable("Cloud forecast", "from $at", forecastOpen, { forecastOpen = !forecastOpen }) {
                    ForecastDetails(state.sky) { vm.loadClouds(force = true) }
                }
            }
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

/** A card whose header opens and closes its [content]. */
@Composable
internal fun Expandable(title: String, trailing: String?, expanded: Boolean, toggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = toggle).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            trailing?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val turn by animateFloatAsState(if (expanded) 90f else 0f, label = "chevron")
            Icon(AppIcons.Chevron, if (expanded) "Close" else "Open", Modifier.padding(start = 6.dp).size(20.dp).rotate(turn), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 10.dp), content = content)
        }
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
 * its bars to drag along, the legend, and where the hiker is at the cursor.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SunCard(
    state: UiState,
    result: SunResult,
    t: Double,
    showHint: Boolean,
    onScrub: (Double) -> Unit,
    vm: RidgeLightViewModel,
    toggleClouds: (Boolean) -> Unit,
    highlight: ClosedFloatingPointRange<Double>?,
) = Section(padding = 10.dp) {
    val sky = state.sky
    val combined = sky?.totals ?: SkyLight.ofTerrain(result.totals)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            // Sun, and sun between clouds as "+…" in its own colour, like the profile.
            val sun = combined.getValue(SkyLight.SUN)
            val partly = combined.getValue(SkyLight.SUN_AND_CLOUDS)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val onlyBetween = sun < 60 && partly >= 60
                if (!onlyBetween) Text(duration(sun), fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.alignByBaseline())
                if (partly >= 60) {
                    Text(
                        (if (onlyBetween) "" else "+") + duration(partly),
                        fontSize = if (onlyBetween) 30.sp else 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = PartlyText,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                Text(
                    when {
                        onlyBetween -> "of sun between clouds"
                        partly >= 60 -> "in sun, + between clouds"
                        else -> "in sun"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            CloudStatus(state, result, vm)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 8.dp)) {
            Switch(checked = state.cloudsEnabled, onCheckedChange = toggleClouds)
            Text("Clouds", style = MaterialTheme.typography.labelSmall)
        }
    }
    ProfileChart(result, sky, state.settings.zone, t, onScrub, highlight = highlight, hint = if (showHint) "Drag along the profile" else null)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (light in SkyLight.entries) {
            val s = combined.getValue(light)
            if (s < 30) continue
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(skyLightColor(light), 8.dp)
                Spacer(Modifier.width(4.dp))
                Text("${shortLabel(light)} ${duration(s)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (sky != null) CloudsLegend()
    Readout(result, sky, t, state.settings, vm)
}

/** Under the headline: what the number includes, or how the cloud forecast is getting on. */
@Composable
private fun CloudStatus(state: UiState, result: SunResult, vm: RidgeLightViewModel) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val terrainOnly = "${(result.sunShare * 100).roundToInt()} % of the hike · from the terrain alone"
    when (val weather = state.weather) {
        WeatherState.Idle -> Text(if (state.cloudsEnabled) "Waiting for the cloud forecast…" else terrainOnly, style = small, color = muted)
        WeatherState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
            Text("Loading the cloud forecast…", style = small, color = muted)
        }
        is WeatherState.Unavailable -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(weather.message, Modifier.weight(1f, fill = false), style = small, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { vm.loadClouds(force = true) }) { Text("Retry") }
        }
        is WeatherState.Ready -> {
            val sky = state.sky
            if (sky == null) {
                val last = Instant.ofEpochMilli(weather.forecast.period.last).atZone(state.settings.zone).toLocalDate()
                Text("No cloud forecast for this date yet (it reaches ${last.format(dayFormat)}) · terrain alone", style = small, color = muted)
            } else {
                Text("Terrain alone ${duration(result.seconds(Light.SUN))}", style = small, color = muted)
            }
        }
    }
}

/** What the grey in the Clouds bar means. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CloudsLegend() {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Clouds:", style = MaterialTheme.typography.bodySmall, color = grey)
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
                    color = grey,
                )
            }
        }
    }
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
private fun StretchRow(result: SunResult, st: SkyStretch, chosen: Boolean, localTime: (Long) -> String, onClick: () -> Unit) {
    val run = st.run
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        val color = skyLightColor(st.light).let { if (run.isBreak) it.copy(alpha = 0.5f) else it }
        Box(Modifier.size(width = 4.dp, height = 30.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            val what = (if (run.isBreak) "☕ Break · " else "") + label(st.light, st.sky)
            Text("${localTime(result.timeAt(run.startSeconds))}–${localTime(result.timeAt(run.endSeconds))} · $what", style = MaterialTheme.typography.bodyMedium)
            val where = if (run.isBreak) "at km %.1f".format(run.startDistance / 1000) else "km %.1f–%.1f".format(run.startDistance / 1000, run.endDistance / 1000)
            Text("$where · ${duration(run.seconds)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Fog, a sea of cloud below, how far to trust it, and where it comes from. */
@Composable
private fun ForecastDetails(sky: SkyReport?, refresh: () -> Unit) {
    Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val body = MaterialTheme.typography.bodyMedium
        if (sky != null) {
            if (sky.inCloudSeconds >= 60) {
                val where = sky.inCloudRanges.joinToString(", ") { "km %.1f–%.1f".format(it.start / 1000, it.endInclusive / 1000) }
                Text("In fog or cloud for ${duration(sky.inCloudSeconds)} ($where).", style = body)
            }
            if (sky.sunAboveClouds >= 60) Text("Above a sea of cloud for ${duration(sky.sunAboveClouds)} of the sun.", style = body)
        }
        Text(
            "The profile's colours combine terrain and clouds. Grey behind the profile: the forecast cloud at each height when you pass. " +
                "Forecast models work on a grid of a few kilometres and on levels a few hundred metres apart, and often misplace low fog; " +
                "more than a week ahead they are rough.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(OpenMeteo.ATTRIBUTION, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = refresh) { Text("Refresh") }
        }
    }
}

@Composable
private fun PreviewTiles(state: UiState, vm: RidgeLightViewModel, open: (Page) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile("Start times", Modifier.weight(1f), { open(Page.START_TIMES) }) { StartTimesPreview(state, vm::localTime) }
        if (state.cloudsEnabled) {
            Tile("Which day?", Modifier.weight(1f), {
                if (state.weather is WeatherState.Unavailable) vm.loadClouds(force = true)
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
        modifier = modifier.height(104.dp),
    ) {
        Column(Modifier.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
internal fun planLine(s: Settings) = "${s.date.format(dayFormat)} · start ${s.start} · pace ×%.2f".format(s.paceFactor)
