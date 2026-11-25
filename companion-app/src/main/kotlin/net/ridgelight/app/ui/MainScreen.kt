package net.ridgelight.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ridgelight.app.Phase
import net.ridgelight.app.R
import net.ridgelight.app.RidgeLightViewModel
import net.ridgelight.app.Settings
import net.ridgelight.app.UiState
import net.ridgelight.app.WeatherState
import net.ridgelight.core.analysis.Light
import net.ridgelight.core.analysis.SunResult
import net.ridgelight.core.analysis.SweepEntry
import net.ridgelight.core.weather.Sky
import net.ridgelight.core.weather.SkyLight
import net.ridgelight.core.weather.SkyReport
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/** The overview: plan, the sun with the profile to drag along, and the way to the other pages. */
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
    val w = words
    var menu by remember { mutableStateOf(false) }
    var zoneDialog by remember { mutableStateOf(false) }
    var cloudsDialog by remember { mutableStateOf(false) }
    val toggleClouds = { on: Boolean -> if (on && !vm.cloudsExplained) cloudsDialog = true else vm.setClouds(on) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { RouteTitle(state) },
                actions = {
                    TextButton(onClick = openFile) { Text(stringResource(R.string.open_gpx)) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(AppIcons.More, stringResource(R.string.more_options)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(stringResource(R.string.time_zone))
                                        Text(state.settings.zone.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = { menu = false; zoneDialog = true },
                            )
                            DropdownMenuItem(text = { Text(stringResource(R.string.about)) }, onClick = { menu = false; open(Page.ABOUT) })
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
                Phase.Reading -> Progress(stringResource(R.string.reading_route), null)
                is Phase.LoadingTerrain -> Progress(
                    stringResource(R.string.loading_terrain, phase.done, phase.total),
                    phase.done.toFloat() / phase.total.coerceAtLeast(1),
                )
                is Phase.Failed -> Failure(w.problem(phase.problem), openFile)
                Phase.Ready -> Unit
            }
            val route = state.route ?: return@Column
            PlanRow(state, vm)
            if (route.missingTiles > 0) {
                Text(
                    pluralStringResource(R.plurals.missing_tiles, route.missingTiles, route.missingTiles),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (route.sampled.route.gaps.isNotEmpty()) {
                Text(stringResource(R.string.gaps_note), style = MaterialTheme.typography.bodySmall)
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
        Text(stringResource(R.string.app_name))
        return
    }
    val w = words
    val s = route.sampled
    Column {
        Text(route.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val up = s.ascent.roundToInt()
        val down = s.descent.roundToInt()
        val zone = if (state.settings.zone != ZoneId.systemDefault()) " · ${state.settings.zone.id}" else ""
        val spoken = w.get(R.string.route_stats_spoken, w.kmNumber(s.length), up, down) +
            (state.result?.let { ", " + w.spokenDuration(it.duration) } ?: "") + zone
        Text(
            w.get(R.string.route_stats, w.kmNumber(s.length), up, down) + (state.result?.let { " · " + w.duration(it.duration) } ?: "") + zone,
            Modifier.semantics { contentDescription = spoken },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Date, start and return, pace and breaks: one row of cells, each opening its picker. With large
 * fonts, two rows of two, so nothing is cut off.
 */
@Composable
private fun PlanRow(state: UiState, vm: RidgeLightViewModel) {
    val w = words
    val settings = state.settings
    val result = state.result
    val context = LocalContext.current
    var paceDialog by remember { mutableStateOf(false) }
    var breaksDialog by remember { mutableStateOf(false) }
    var breakDialog by remember { mutableStateOf(false) }
    val date: @Composable (Modifier) -> Unit = { m ->
        PlanCell(stringResource(R.string.plan_date), settings.date.format(w.day), m) {
            DatePickerDialog(context, { _, y, mo, d -> vm.update(settings.copy(date = LocalDate.of(y, mo + 1, d))) },
                settings.date.year, settings.date.monthValue - 1, settings.date.dayOfMonth).show()
        }
    }
    val start: @Composable (Modifier) -> Unit = { m ->
        val from = settings.start.format(w.clock)
        PlanCell(
            stringResource(if (result != null) R.string.plan_start_back else R.string.plan_start),
            result?.let { "$from–${vm.localTime(it.timeAt(it.duration))}" } ?: from,
            m,
        ) {
            TimePickerDialog(context, { _, h, min -> vm.update(settings.copy(start = LocalTime.of(h, min))) },
                settings.start.hour, settings.start.minute, true).show()
        }
    }
    val pace: @Composable (Modifier) -> Unit = { m ->
        PlanCell(stringResource(R.string.plan_pace), w.paceName(settings.paceFactor), m) { paceDialog = true }
    }
    val breaks: @Composable (Modifier) -> Unit = { m ->
        val n = settings.breaks.size
        PlanCell(
            stringResource(R.string.plan_breaks),
            if (n == 0) stringResource(R.string.plan_no_breaks) else "☕ $n",
            m,
            spoken = if (n == 0) null else pluralStringResource(R.plurals.spoken_breaks, n, n),
        ) {
            if (n == 0) {
                if (result != null) breakDialog = true
            } else {
                breaksDialog = true
            }
        }
    }
    val divider = MaterialTheme.colorScheme.outlineVariant
    OutlinedCard(Modifier.fillMaxWidth()) {
        if (largeFont()) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                date(Modifier.weight(1f))
                VerticalDivider(color = divider)
                start(Modifier.weight(1f))
            }
            HorizontalDivider(color = divider)
            Row(Modifier.height(IntrinsicSize.Min)) {
                pace(Modifier.weight(1f))
                VerticalDivider(color = divider)
                breaks(Modifier.weight(1f))
            }
        } else {
            Row(Modifier.height(IntrinsicSize.Min)) {
                date(Modifier.weight(1f))
                VerticalDivider(color = divider)
                start(Modifier.weight(1.15f))
                VerticalDivider(color = divider)
                pace(Modifier.weight(0.95f))
                VerticalDivider(color = divider)
                breaks(Modifier.weight(0.7f))
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

/** One tappable setting: a small caption over its value; read out as "caption, value". */
@Composable
private fun PlanCell(caption: String, value: String, modifier: Modifier, spoken: String? = null, onClick: () -> Unit) {
    Column(
        modifier
            .clickable(onClickLabel = stringResource(R.string.change), role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$caption, ${spoken ?: value}" }
            .heightIn(min = 48.dp)
            .padding(horizontal = 4.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(caption, Modifier.hiddenFromReaders(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, Modifier.hiddenFromReaders(), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    Text(stringResource(R.string.intro_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.intro_text), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.intro_osmand), style = MaterialTheme.typography.bodySmall)
    Button(onClick = openFile) { Text(stringResource(R.string.open_gpx_file)) }
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
    OutlinedButton(onClick = openFile) { Text(stringResource(R.string.open_another_file)) }
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
    val w = words
    val sky = state.sky
    val combined = sky?.totals ?: SkyLight.ofTerrain(result.totals)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Headline(combined)
            CloudStatus(state, result, combined, vm)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 8.dp)) {
            val clouds = stringResource(R.string.clouds)
            Switch(checked = state.cloudsEnabled, onCheckedChange = toggleClouds, modifier = Modifier.semantics { contentDescription = clouds })
            // Room below for the ⓘ's full 48 dp touch target.
            Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(clouds, Modifier.hiddenFromReaders(), style = MaterialTheme.typography.labelSmall)
                InfoButton(clouds) { CloudsInfo(state) { vm.loadClouds(force = true) } }
            }
        }
    }
    val readout = readoutAt(result, sky, t, w, vm::localTime)
    val changes = remember(result, sky) { lightChanges(result, sky) }
    val nextChange = stringResource(R.string.profile_next_change)
    val previousChange = stringResource(R.string.profile_previous_change)
    val nextBreak = stringResource(R.string.profile_next_break)
    Box {
        ProfileChart(
            result, sky, state.settings.zone, t, onScrub,
            hint = if (showHint) stringResource(R.string.drag_hint) else null,
            cloudsNote = cloudsNote(state),
            description = w.get(R.string.profile_description, w.spokenSun(combined)),
            position = readout.spoken,
            actions = listOf(
                CustomAccessibilityAction(nextChange) { changes.firstOrNull { it > t + 1 }?.let { onScrub(it); true } ?: false },
                CustomAccessibilityAction(previousChange) { changes.lastOrNull { it < t - 1 }?.let { onScrub(it); true } ?: false },
                CustomAccessibilityAction(nextBreak) {
                    result.breaks.firstOrNull { it.stop.startSeconds > t + 1 }?.let { onScrub(it.stop.startSeconds); true } ?: false
                },
            ),
        )
        // 2 dp down, so its touch target does not reach into the Clouds ⓘ's across the 6 dp gap.
        InfoButton(stringResource(R.string.reading_the_profile), Modifier.align(Alignment.TopEnd).offset(y = 2.dp)) { ProfileInfo(combined, state.cloudsEnabled) }
    }
    Readout(result, readout, state.settings, vm)
}

/** Font scale set high enough in the system that single-line layouts would cut text off. */
@Composable
internal fun largeFont(): Boolean = LocalDensity.current.fontScale > 1.25f

/** Seconds after departure at which the light (terrain and clouds combined) changes. */
private fun lightChanges(result: SunResult, sky: SkyReport?): List<Double> {
    val steps = result.route.steps
    fun light(i: Int) = SkyLight.of(result.samples[i].light, sky?.stepSky?.get(i)?.sky)
    return (1 until steps.size).filter { light(it) != light(it - 1) }.map { result.secondsAt(steps[it].start.distance) }
}

/** Sun, and sun between clouds as "+…" in its own colour, like the profile; wraps with large fonts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Headline(combined: Map<SkyLight, Double>) {
    val w = words
    val sun = combined.getValue(SkyLight.SUN)
    val partly = combined.getValue(SkyLight.SUN_AND_CLOUDS)
    val onlyBetween = sun < 60 && partly >= 60
    val spoken = w.spokenSun(combined)
    FlowRow(Modifier.clearAndSetSemantics { contentDescription = spoken }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!onlyBetween) Text(w.duration(sun), fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.alignByBaseline())
        if (partly >= 60) {
            Text(
                (if (onlyBetween) "" else "+") + w.duration(partly),
                fontSize = if (onlyBetween) 30.sp else 22.sp,
                fontWeight = FontWeight.Bold,
                color = partlyText,
                modifier = Modifier.alignByBaseline(),
            )
        }
        Text(stringResource(R.string.in_sun), Modifier.alignByBaseline().padding(start = 2.dp), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
    }
}

/** One line under the headline: what the number includes, or how the cloud forecast is getting on. */
@Composable
private fun CloudStatus(state: UiState, result: SunResult, combined: Map<SkyLight, Double>, vm: RidgeLightViewModel) {
    val w = words
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // One line, so nothing moves when Clouds is switched; with large fonts, room to wrap instead.
    val lines = if (largeFont()) 3 else 1
    when (val weather = state.weather) {
        WeatherState.Idle, WeatherState.Loading -> if (state.cloudsEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.status_loading), style = small, color = muted, maxLines = lines)
            }
        } else {
            Text(stringResource(R.string.status_terrain_share, (result.sunShare * 100).roundToInt()), style = small, color = muted, maxLines = lines)
        }
        is WeatherState.Unavailable -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(w.problem(weather.problem), Modifier.weight(1f, fill = false), style = small, color = MaterialTheme.colorScheme.error, maxLines = lines, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.retry),
                Modifier.clickable(role = Role.Button) { vm.loadClouds(force = true) }.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is WeatherState.Ready -> {
            val terrain = result.seconds(Light.SUN)
            val id = when {
                state.sky == null -> R.string.status_no_forecast_for_date
                combined.getValue(SkyLight.SUN_AND_CLOUDS) < 60 -> R.string.status_terrain_alone
                combined.getValue(SkyLight.SUN) < 60 -> R.string.status_only_between
                else -> R.string.status_plus_between
            }
            val spoken = w.get(id, w.spokenDuration(terrain))
            Text(
                w.get(id, w.duration(terrain)),
                Modifier.semantics { contentDescription = spoken },
                style = small,
                color = muted,
                maxLines = lines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Written into the empty Clouds bar when there is no cloud forecast to show. */
@Composable
private fun cloudsNote(state: UiState): String? = if (!state.cloudsEnabled) {
    stringResource(R.string.clouds_note_off)
} else {
    when (state.weather) {
        WeatherState.Idle, WeatherState.Loading -> stringResource(R.string.clouds_note_loading)
        is WeatherState.Unavailable -> stringResource(R.string.clouds_note_none)
        is WeatherState.Ready -> if (state.sky == null) stringResource(R.string.clouds_note_not_yet) else null
    }
}

/** The colours, the two bars and how to use the profile. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileInfo(combined: Map<SkyLight, Double>, clouds: Boolean) {
    val w = words
    val shown = if (clouds) SkyLight.entries else listOf(SkyLight.SUN, SkyLight.SHADE, SkyLight.DARK, SkyLight.UNKNOWN)
    for (light in shown) {
        val s = combined.getValue(light)
        if (light == SkyLight.UNKNOWN && s < 30) continue
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(skyLightColor(light), 10.dp)
            Spacer(Modifier.width(8.dp))
            Text(w.label(light), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(w.duration(s), Modifier.semantics { contentDescription = w.spokenDuration(s) }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
    InfoText(stringResource(R.string.profile_bars_info))
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
                Text(w.skyShort(s), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    InfoText(stringResource(R.string.profile_use_info))
}

/** What Clouds does, what it sends, the current forecast and how far to trust it. */
@Composable
private fun CloudsInfo(state: UiState, refresh: () -> Unit) {
    val w = words
    InfoText(stringResource(R.string.clouds_info_what))
    InfoText(stringResource(R.string.clouds_info_source))
    val weather = state.weather
    val sky = state.sky
    if (state.cloudsEnabled && weather is WeatherState.Ready) {
        if (sky != null && sky.inCloudSeconds >= 60) {
            val where = sky.inCloudRanges.joinToString(", ") { w.kmRange(it.start, it.endInclusive) }
            InfoText(w.get(R.string.clouds_info_fog, w.duration(sky.inCloudSeconds), where))
        }
        if (sky != null && sky.sunAboveClouds >= 60) InfoText(w.get(R.string.clouds_info_above, w.duration(sky.sunAboveClouds)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val at = Instant.ofEpochMilli(weather.fetchedAt).atZone(ZoneId.systemDefault()).format(w.clock)
            Text(stringResource(R.string.forecast_from, at), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = refresh) { Text(stringResource(R.string.refresh)) }
        }
        state.refreshProblem?.let { Text(w.refreshProblem(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    Text(
        stringResource(R.string.clouds_info_trust, stringResource(R.string.attribution_weather)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** What the readout says at one moment: two lines, the same for screen readers, and its colour. */
private class ReadoutText(val title: String, val detail: String, val spoken: String, val light: SkyLight, val atBreak: Boolean, val distance: Double)

private fun readoutAt(result: SunResult, sky: SkyReport?, t: Double, w: Words, localTime: (Long) -> String): ReadoutText {
    val moment = result.momentAt(t)
    val k = moment.breakIndex
    if (k != null) {
        val b = result.breaks[k]
        val byLight = b.pieces.indices.groupBy { p -> SkyLight.of(b.pieces[p].sample.light, sky?.breakSky?.get(k)?.get(p)?.sky) }
            .mapValues { (_, ps) -> ps.sumOf { b.pieces[it].endSeconds - b.pieces[it].startSeconds } }
            .entries.sortedBy { it.key.ordinal }
        val from = localTime(result.timeAt(b.stop.startSeconds))
        val to = localTime(result.timeAt(b.stop.endSeconds))
        return ReadoutText(
            title = w.get(R.string.readout_break, from, to, w.km(b.distance)),
            detail = byLight.joinToString(" · ") { "${w.label(it.key)} ${w.duration(it.value)}" },
            spoken = w.get(R.string.readout_break_spoken, from, to, w.km(b.distance)) + ". " +
                byLight.joinToString(", ") { "${w.label(it.key)} ${w.spokenDuration(it.value)}" },
            light = byLight.maxBy { it.value }.key,
            atBreak = true,
            distance = moment.distance,
        )
    }
    val i = result.schedule.stepAt(t)
    val skyAt = sky?.stepSky?.get(i)
    val light = SkyLight.of(result.samples[i].light, skyAt?.sky)
    val elevation = result.route.elevationAt(moment.distance).let { if (it.isFinite()) " · " + w.metres(it) else "" }
    val title = w.get(R.string.readout_place, localTime(result.timeAt(t)), w.km(moment.distance)) + elevation
    val sun = moment.sample.sun.apparentAltitude.let { if (it > 0) " · " + w.get(R.string.readout_sun_up, it.roundToInt()) else "" }
    val clear = skyAt?.takeIf { light == SkyLight.SUN }?.let { " · " + w.skyLabel(it.sky) } ?: ""
    val detail = w.label(light, skyAt) + sun + clear
    return ReadoutText(title, detail, "$title. $detail".replace(" · ", ", "), light, atBreak = false, distance = moment.distance)
}

/** Where the hiker is at the cursor, in what light, and "Break here". */
@Composable
private fun Readout(result: SunResult, readout: ReadoutText, settings: Settings, vm: RidgeLightViewModel) {
    var breakDialog by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(skyLightColor(readout.light))
        Spacer(Modifier.width(8.dp))
        // The profile already reads this out as its position.
        Column(Modifier.weight(1f).hiddenFromReaders()) {
            Text(readout.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(readout.detail, style = MaterialTheme.typography.bodySmall)
        }
        // Not at the very start or end: a break there would only move the start or the arrival.
        if (!readout.atBreak && readout.distance > 50 && readout.distance < result.route.length - 50) {
            val label = stringResource(R.string.add_a_break)
            TextButton(onClick = { breakDialog = true }, modifier = Modifier.semantics { contentDescription = label }) {
                Text(stringResource(R.string.break_here), Modifier.hiddenFromReaders())
            }
        }
    }
    if (breakDialog) BreakDialog(result, readout.distance, settings.zone, { breakDialog = false }, vm::addBreak)
}

/** A preview's headline and line, and both for screen readers. */
private class Summary(val headline: String, val line: String, val spoken: String)

private fun startTimesSummary(sweep: List<SweepEntry>, w: Words, localTime: (Long) -> String): Summary {
    val best = sweep.maxByOrNull { it.expectedSun }?.takeIf { it.expectedSun >= 60 }
        ?: return Summary(w.get(R.string.no_sun), w.get(R.string.at_any_start), w.get(R.string.no_sun) + " " + w.get(R.string.at_any_start))
    val headline = w.get(R.string.best, localTime(best.departureUtc))
    return Summary(headline, w.sunPhrase(best.combined), headline + ", " + w.spokenSun(best.combined))
}

@Composable
private fun PreviewTiles(state: UiState, vm: RidgeLightViewModel, open: (Page) -> Unit) {
    val w = words
    // Both tiles as tall as the taller one (they differ only with large fonts).
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val sweep = state.sweep
        val starts = sweep?.let { startTimesSummary(it, w, vm::localTime) }
        Tile(stringResource(R.string.start_times), starts?.spoken, Modifier.weight(1f), true, { open(Page.START_TIMES) }) {
            StartTimesPreview(state, sweep, starts)
        }
        // Always there, so nothing moves when Clouds is switched; it needs the forecast to work.
        val days = daysSummary(state, w)
        Tile(stringResource(R.string.which_day), days?.spoken, Modifier.weight(1f), state.cloudsEnabled, {
            if (state.weather is WeatherState.Unavailable) vm.loadClouds(force = true)
            open(Page.DAYS)
        }) { DaysPreview(state, days) }
    }
}

/** A tile that opens a page; read out as its title and [spoken] summary. */
@Composable
private fun Tile(title: String, spoken: String?, modifier: Modifier, enabled: Boolean, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    Card(
        onClick = onClick,
        enabled = enabled,
        // Disabled only fades the content a little (below), keeping it readable: it says what to do.
        colors = CardDefaults.cardColors(
            containerColor = container,
            disabledContainerColor = container,
            disabledContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier.fillMaxHeight().heightIn(min = 104.dp).semantics { contentDescription = listOfNotNull(title, spoken).joinToString(". ") },
    ) {
        Column(
            Modifier.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 10.dp).height(IntrinsicSize.Min).heightIn(min = 86.dp)
                .alpha(if (enabled) 1f else 0.8f).hiddenFromReaders(),
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
private fun ColumnScope.StartTimesPreview(state: UiState, sweep: List<SweepEntry>?, summary: Summary?) {
    if (sweep == null || summary == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        return
    }
    Text(summary.headline, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    Text(summary.line, style = MaterialTheme.typography.bodySmall, maxLines = if (largeFont()) 3 else 1)
    val chosen = sweep.indexOfFirst { Instant.ofEpochMilli(it.departureUtc).atZone(state.settings.zone).toLocalTime() == state.settings.start }
    MiniColumns(
        sweep.map { if (it.duration > 0) it.expectedSun / it.duration else 0.0 },
        skyLightColor(SkyLight.SUN),
        Modifier.fillMaxWidth().weight(1f).heightIn(min = 20.dp),
        selected = chosen.takeIf { it >= 0 },
    )
}

/** What the "Which day?" tile says; null while loading. */
private fun daysSummary(state: UiState, w: Words): Summary? {
    if (!state.cloudsEnabled) {
        return Summary(w.get(R.string.needs_clouds), w.get(R.string.needs_clouds_text), w.get(R.string.needs_clouds_text))
    }
    return when (state.weather) {
        WeatherState.Idle, WeatherState.Loading -> null
        is WeatherState.Unavailable -> Summary(w.get(R.string.no_forecast), w.get(R.string.tap_to_retry), w.get(R.string.no_forecast))
        is WeatherState.Ready -> {
            val days = state.days ?: return null
            val best = days.maxByOrNull { it.expectedSun }?.takeIf { it.expectedSun >= 600 }
                ?: return Summary(w.get(R.string.little_sun), w.get(R.string.in_the_forecast), w.get(R.string.little_sun) + " " + w.get(R.string.in_the_forecast))
            val headline = w.get(R.string.best, best.date.format(w.shortDay))
            Summary(headline, w.sunPhrase(best.combined), w.get(R.string.best, best.date.format(w.day)) + ", " + w.spokenSun(best.combined))
        }
    }
}

@Composable
private fun ColumnScope.DaysPreview(state: UiState, summary: Summary?) {
    if (summary == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        return
    }
    val days = state.days
    val ready = state.cloudsEnabled && state.weather is WeatherState.Ready && days != null
    Text(summary.headline, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = if (largeFont()) 2 else 1)
    Text(
        summary.line,
        style = MaterialTheme.typography.bodySmall,
        color = if (state.weather is WeatherState.Unavailable) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
        maxLines = if (largeFont()) 3 else if (ready) 1 else 2,
    )
    if (ready && days != null) {
        val today = LocalDate.now(state.settings.zone)
        MiniColumns(
            days.map { if (it.duration > 0) it.expectedSun / it.duration else 0.0 },
            skyLightColor(SkyLight.SUN),
            Modifier.fillMaxWidth().weight(1f).heightIn(min = 20.dp),
            selected = days.indexOfFirst { it.date == state.settings.date }.takeIf { it >= 0 },
            faded = { days[it].date > today.plusDays(7) },
        )
    }
}

@Composable
private fun OsmAndButton(state: UiState, showInOsmAnd: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = showInOsmAnd, enabled = state.osmandInstalled, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.show_on_osmand)) }
        if (!state.osmandInstalled) Text(stringResource(R.string.install_osmand), style = MaterialTheme.typography.bodySmall)
        state.osmandProblem?.let { Text(words.problem(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
