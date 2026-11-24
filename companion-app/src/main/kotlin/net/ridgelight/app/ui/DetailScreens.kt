package net.ridgelight.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.ridgelight.app.UiState
import net.ridgelight.app.WeatherState
import net.ridgelight.core.analysis.SunResult
import net.ridgelight.core.weather.OpenMeteo
import net.ridgelight.core.weather.Sky
import net.ridgelight.core.weather.SkyAt
import net.ridgelight.core.weather.SkyStretch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** The pages behind the overview's previews. */
enum class Page { MAIN, STRETCHES, START_TIMES, DAYS, SKY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(title: String, subtitle: String?, back: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        subtitle?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = back) { Icon(AppIcons.Back, "Back") } },
            )
        },
        content = content,
    )
}

private fun listPadding(padding: PaddingValues) =
    PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp)

@Composable
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A row that can be picked; [chosen] is highlighted. */
@Composable
private fun PickRow(chosen: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 7.dp)
            .then(modifier),
        content = content,
    )
}

/**
 * Every stretch, under the profile. Dragging the profile highlights the stretch there (and
 * scrolls to it); tapping a stretch moves the profile's cursor to its start.
 */
@Composable
fun StretchesScreen(state: UiState, result: SunResult, scrub: State<Double>, onScrub: (Double) -> Unit, localTime: (Long) -> String, back: () -> Unit) =
    DetailScaffold("Stretches", planLine(state.settings), back) { padding ->
        val sky = state.sky
        val rows = sky?.stretches ?: result.timeline.map { SkyStretch(it, null) }
        val t = scrub.value.coerceIn(0.0, result.duration)
        val current = rows.indexOfFirst { t >= it.run.startSeconds && t < it.run.endSeconds }.let { if (it < 0) rows.lastIndex else it }
        val list = rememberLazyListState()
        LaunchedEffect(current) {
            val info = list.layoutInfo
            val shown = info.visibleItemsInfo.any { it.index == current && it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }
            if (!shown && current >= 0) list.animateScrollToItem(current)
        }
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val run = rows.getOrNull(current)?.run
                ProfileChart(result, result.momentAt(t).distance, sky?.columns, onScrub = onScrub, highlight = run?.let { it.startDistance..it.endDistance })
                TimelineBar(result, Modifier.fillMaxWidth().height(22.dp), cursorSeconds = t, onScrub = onScrub)
                Text("${localTime(result.timeAt(t))} · km %.1f · ${momentDetails(result, sky, t)}".format(result.momentAt(t).distance / 1000), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(Modifier.padding(top = 6.dp))
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 6.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(rows) { i, st -> StretchRow(result, st, i == current, localTime) { onScrub(st.run.startSeconds) } }
            }
        }
    }

@Composable
private fun StretchRow(result: SunResult, st: SkyStretch, chosen: Boolean, localTime: (Long) -> String, onClick: () -> Unit) =
    PickRow(chosen, onClick = onClick) {
        val run = st.run
        val color = lightColor(run.light).let { if (run.isBreak) it.copy(alpha = 0.5f) else it }
        Box(Modifier.size(width = 4.dp, height = 30.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            val what = (if (run.isBreak) "☕ Break · " else "") + lightLabel(run, st.sky) + (st.sky?.let { " · " + skyLabel(it) } ?: "")
            Text("${localTime(result.timeAt(run.startSeconds))}–${localTime(result.timeAt(run.endSeconds))} · $what", style = MaterialTheme.typography.bodyMedium)
            val where = if (run.isBreak) "at km %.1f".format(run.startDistance / 1000) else "km %.1f–%.1f".format(run.startDistance / 1000, run.endDistance / 1000)
            Text("$where · ${duration(run.seconds)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

/** Sun for each start time of the day; tapping one makes it the plan's start. */
@Composable
fun StartTimesScreen(state: UiState, localTime: (Long) -> String, pick: (LocalTime) -> Unit, back: () -> Unit) =
    DetailScaffold("Start times", planLine(state.settings), back) { padding ->
        val sweep = state.sweep
        val withSky = state.skyEnabled && sweep?.any { it.weightedSun != null } == true
        LazyColumn(contentPadding = listPadding(padding), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                Note(
                    "Same day, route, pace and breaks. Bar and %: time in direct sun as far as the terrain goes. Tap a time to use it." +
                        if (withSky) " Icon: the forecast sky over that start's sunny stretches." else "",
                )
                Spacer(Modifier.height(6.dp))
            }
            if (sweep == null) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                return@LazyColumn
            }
            val best = sweep.maxByOrNull { it.sunShare }?.takeIf { it.sunShare >= 0.01 }
            items(sweep) { e ->
                val time = Instant.ofEpochMilli(e.departureUtc).atZone(state.settings.zone).toLocalTime()
                val chosen = time == state.settings.start
                PickRow(chosen, onClick = { pick(time) }) {
                    Text(localTime(e.departureUtc), Modifier.width(52.dp), fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal)
                    LightBar(e.totals, Modifier.weight(1f).height(10.dp))
                    Text("${(e.sunShare * 100).roundToInt()} %", Modifier.width(52.dp).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                    if (withSky) Text(e.weightedShareOfSun?.let(::skyIcon) ?: "", Modifier.width(28.dp))
                    Text(if (e === best) "★" else "", Modifier.width(16.dp), color = MaterialTheme.colorScheme.secondary)
                }
            }
            item {
                Spacer(Modifier.height(6.dp))
                Note("★ most time in direct sun.")
            }
        }
    }

/** The plan on each forecast day; tapping one makes it the plan's date. */
@Composable
fun DaysScreen(state: UiState, pick: (LocalDate) -> Unit, checkSky: (Boolean) -> Unit, back: () -> Unit) =
    DetailScaffold("Which day?", "Start ${state.settings.start} · pace ×%.2f".format(state.settings.paceFactor), back) { padding ->
        LazyColumn(contentPadding = listPadding(padding), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            weatherStates(state, checkSky) { weather ->
                val days = state.days
                item {
                    Note("Your start, pace and breaks on each forecast day. Bar and %: direct sun as far as the terrain goes; icon: the forecast sky over the sunny stretches. Tap a day to use it.")
                    Spacer(Modifier.height(6.dp))
                }
                if (days == null) {
                    item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    return@weatherStates
                }
                val today = LocalDate.now(state.settings.zone)
                val best = days.maxByOrNull { it.skyWeightedSun }?.takeIf { it.skyWeightedSun >= 600 }
                items(days) { d ->
                    val chosen = d.date == state.settings.date
                    // Forecasts lose skill after about a week.
                    PickRow(chosen, Modifier.alpha(if (d.date > today.plusDays(7)) 0.55f else 1f), onClick = { pick(d.date) }) {
                        Text(d.date.format(dayFormat), Modifier.width(104.dp), fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal)
                        LightBar(d.totals, Modifier.weight(1f).height(10.dp))
                        Text("${(d.sunShare * 100).roundToInt()} %", Modifier.width(44.dp).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                        Text(d.clearShareOfSun?.let(::skyIcon) ?: "", Modifier.width(28.dp).padding(start = 4.dp))
                        Text(if (d === best) "★" else "", Modifier.width(16.dp), color = MaterialTheme.colorScheme.secondary)
                    }
                }
                item {
                    Spacer(Modifier.height(6.dp))
                    Note("★ most sun under a clear sky. Days more than a week ahead are greyed: their forecast is rough.")
                    Spacer(Modifier.height(4.dp))
                    Note(forecastLine(weather))
                }
            }
        }
    }

/** The forecast sky over the hike in full, and the switch to turn it off. */
@Composable
fun SkyScreen(
    state: UiState,
    result: SunResult,
    scrub: State<Double>,
    onScrub: (Double) -> Unit,
    checkSky: (Boolean) -> Unit,
    setSkyEnabled: (Boolean) -> Unit,
    openDays: () -> Unit,
    back: () -> Unit,
) = DetailScaffold("Sky", planLine(state.settings), back) { padding ->
    LazyColumn(contentPadding = listPadding(padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        weatherStates(state, checkSky) { weather ->
            val sky = state.sky
            if (sky == null) {
                val last = Instant.ofEpochMilli(weather.forecast.period.last).atZone(state.settings.zone).toLocalDate()
                item {
                    Text("No forecast for this date yet. Forecasts reach ${last.format(dayFormat)}.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = openDays) { Text("Compare the forecast days") }
                }
            } else {
                item {
                    val t = scrub.value.coerceIn(0.0, result.duration)
                    ProfileChart(result, result.momentAt(t).distance, sky.columns, Modifier.fillMaxWidth().height(220.dp), onScrub = onScrub)
                }
                item {
                    Note(
                        "Grey: the forecast cloud at each height, as it will be when you pass. A dark strip along the top: cloud above the chart. " +
                            "Weather models work on a grid of a few kilometres and on levels a few hundred metres apart, and often misplace low fog.",
                    )
                }
                item {
                    val body = MaterialTheme.typography.bodyMedium
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (sky.sunSeconds > 0) {
                            val parts = Sky.entries.filter { sky.sunBySky.getValue(it) >= 60 }
                                .joinToString(" · ") { "${duration(sky.sunBySky.getValue(it))} ${skyLabel(SkyAt(it, false))}" }
                            Text("☀ Your ${duration(sky.sunSeconds)} of terrain sun: $parts", style = body)
                        } else {
                            Text("☀ The terrain hides the sun for the whole hike.", style = body)
                        }
                        if (sky.sunAboveClouds >= 60) Text("⛰ Above a sea of cloud for ${duration(sky.sunAboveClouds)} of it", style = body)
                        if (sky.inCloudSeconds >= 60) {
                            val where = sky.inCloudRanges.joinToString(", ") { "km %.1f–%.1f".format(it.start / 1000, it.endInclusive / 1000) }
                            Text("🌫 In cloud or fog for ${duration(sky.inCloudSeconds)} ($where)", style = body)
                        }
                        Text("🌡 ${weatherLine(sky, long = true)}", style = body)
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(forecastLine(weather), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { checkSky(true) }) { Text("Refresh") }
                }
            }
        }
        item {
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Sky forecast", style = MaterialTheme.typography.bodyLarge)
                    Note("Off: no clouds or weather anywhere, and nothing is sent to Open-Meteo.")
                }
                Switch(checked = state.skyEnabled, onCheckedChange = setSkyEnabled)
            }
        }
    }
}

private fun forecastLine(weather: WeatherState.Ready): String {
    val at = Instant.ofEpochMilli(weather.fetchedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    return "Forecast from $at · ${OpenMeteo.ATTRIBUTION}"
}

/** Content for a ready forecast, or the button, progress or error before it. */
private fun LazyListScope.weatherStates(state: UiState, checkSky: (Boolean) -> Unit, ready: LazyListScope.(WeatherState.Ready) -> Unit) {
    when (val weather = state.weather) {
        WeatherState.Idle -> item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("See the forecast clouds and fog at the heights you walk, and which days look best.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { checkSky(false) }) { Text("Check the sky") }
                Note("This sends a few points of your route to Open-Meteo (open-meteo.com).")
            }
        }
        WeatherState.Loading -> item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Loading the forecast…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        is WeatherState.Unavailable -> item {
            Column {
                Text(weather.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { checkSky(true) }) { Text("Try again") }
            }
        }
        is WeatherState.Ready -> ready(weather)
    }
}
