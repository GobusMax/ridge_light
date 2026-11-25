package net.ridgelight.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.ridgelight.app.R
import net.ridgelight.app.UiState
import net.ridgelight.app.WeatherState
import net.ridgelight.core.weather.SkyLight
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** The pages: the overview and the ones it opens. */
enum class Page { MAIN, START_TIMES, DAYS, ABOUT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(
    title: String,
    subtitle: String?,
    back: () -> Unit,
    info: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, Modifier.semantics { heading() })
                        subtitle?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = back) { Icon(AppIcons.Back, stringResource(R.string.back)) } },
                actions = { info?.let { InfoButton(title, Modifier.padding(end = 8.dp), it) } },
            )
        },
        content = content,
    )
}

private fun listPadding(padding: PaddingValues) =
    PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp)

/** A row that can be picked, read out as [spoken]; [chosen] is highlighted. */
@Composable
private fun PickRow(chosen: Boolean, spoken: String, modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(6.dp))
            .selectable(chosen, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = spoken }
            .padding(horizontal = 6.dp, vertical = 7.dp)
            .then(modifier),
    ) {
        Row(Modifier.hiddenFromReaders(), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** Light bar, sun (and sun between clouds) and a star: one start time or day. [textWidth] fits every row's sun text. */
@Composable
private fun RowScope.Comparison(combined: Map<SkyLight, Double>, best: Boolean, textWidth: Dp) {
    SegmentBar(segments(combined), Modifier.weight(1f).height(10.dp))
    Text(words.sunText(combined), Modifier.width(textWidth).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
    Text(if (best) "★" else "", Modifier.width(16.dp), color = MaterialTheme.colorScheme.secondary)
}

/** The width of the widest sun text among [totals], so all rows line up in any language or font size. */
@Composable
private fun sunTextWidth(totals: List<Map<SkyLight, Double>>): Dp {
    val w = words
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodySmall
    val density = LocalDensity.current
    return remember(totals, w, style, density) {
        val widest = totals.maxOfOrNull { measurer.measure(w.sunText(it), style).size.width } ?: 0
        with(density) { widest.toDp() } + 12.dp
    }
}

/** "09:00, 2 hours 40 minutes of sun, most sun". */
private fun spokenRow(label: String, combined: Map<SkyLight, Double>, best: Boolean, w: Words) =
    listOfNotNull(label, w.spokenSun(combined), w.get(R.string.best_spoken).takeIf { best }).joinToString(", ")

/** Sun for each start time of the day; tapping one makes it the plan's start. */
@Composable
fun StartTimesScreen(state: UiState, localTime: (Long) -> String, pick: (LocalTime) -> Unit, back: () -> Unit) =
    DetailScaffold(stringResource(R.string.start_times), words.planLine(state.settings), back, info = {
        val withClouds = state.sweep?.any { it.skyTotals != null } == true
        InfoText(stringResource(R.string.start_times_info_what))
        InfoText(stringResource(if (withClouds) R.string.start_times_info_clouds else R.string.start_times_info_terrain))
        InfoText(stringResource(if (withClouds) R.string.start_times_info_best_clouds else R.string.start_times_info_best))
    }) { padding ->
        val w = words
        val sweep = state.sweep
        val textWidth = sunTextWidth(sweep.orEmpty().map { it.combined })
        LazyColumn(Modifier.selectableGroup(), contentPadding = listPadding(padding), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (sweep == null) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                return@LazyColumn
            }
            val best = sweep.maxByOrNull { it.expectedSun }?.takeIf { it.expectedSun >= 60 }
            items(sweep) { e ->
                val time = Instant.ofEpochMilli(e.departureUtc).atZone(state.settings.zone).toLocalTime()
                val chosen = time == state.settings.start
                val label = localTime(e.departureUtc)
                PickRow(chosen, spokenRow(label, e.combined, e === best, w), onClick = { pick(time) }) {
                    Text(label, Modifier.width(52.dp), fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal)
                    Comparison(e.combined, e === best, textWidth)
                }
            }
        }
    }

/** The plan on each forecast day; tapping one makes it the plan's date. */
@Composable
fun DaysScreen(state: UiState, pick: (LocalDate) -> Unit, retry: () -> Unit, back: () -> Unit) =
    DetailScaffold(
        stringResource(R.string.which_day),
        stringResource(R.string.days_subtitle, state.settings.start.format(words.clock), words.paceName(state.settings.paceFactor)),
        back,
        info = {
            InfoText(stringResource(R.string.days_info_what))
            InfoText(stringResource(R.string.days_info_numbers))
            InfoText(stringResource(R.string.days_info_rough))
            Text(stringResource(R.string.attribution_weather), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    ) { padding ->
        val w = words
        val textWidth = sunTextWidth(state.days.orEmpty().map { it.combined })
        LazyColumn(Modifier.selectableGroup(), contentPadding = listPadding(padding), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val weather = state.weather
            val days = state.days
            when {
                weather is WeatherState.Unavailable -> item {
                    Text(w.problem(weather.problem), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = retry) { Text(stringResource(R.string.try_again)) }
                }
                weather !is WeatherState.Ready || days == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else -> {
                    val today = LocalDate.now(state.settings.zone)
                    val best = days.maxByOrNull { it.expectedSun }?.takeIf { it.expectedSun >= 600 }
                    items(days) { d ->
                        val chosen = d.date == state.settings.date
                        val label = d.date.format(w.day)
                        // Forecasts lose skill after about a week.
                        PickRow(chosen, spokenRow(label, d.combined, d === best, w), Modifier.alpha(if (d.date > today.plusDays(7)) 0.6f else 1f), onClick = { pick(d.date) }) {
                            Text(label, Modifier.width(100.dp), fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal)
                            Comparison(d.combined, d === best, textWidth)
                        }
                    }
                }
            }
        }
    }

/** What the app does, how far to trust it, what leaves the phone, and where the data comes from. */
@Composable
fun AboutScreen(back: () -> Unit) = DetailScaffold(stringResource(R.string.about), null, back) { padding ->
    val context = LocalContext.current
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    LazyColumn(contentPadding = listPadding(padding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.about_tagline) + (version?.let { "\n" + stringResource(R.string.about_version, it) } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            AboutPart(
                stringResource(R.string.about_how),
                stringResource(R.string.about_how_terrain),
                stringResource(R.string.about_how_timing),
                stringResource(R.string.about_how_clouds),
            )
        }
        item { AboutPart(stringResource(R.string.about_limits), stringResource(R.string.about_limits_terrain), stringResource(R.string.about_limits_clouds)) }
        item {
            AboutPart(
                stringResource(R.string.about_privacy),
                stringResource(R.string.about_privacy_tiles),
                stringResource(R.string.about_privacy_clouds),
                stringResource(R.string.about_privacy_osmand),
            )
        }
        item { AboutPart(stringResource(R.string.about_data), stringResource(R.string.attribution_terrain) + ".", stringResource(R.string.attribution_weather) + ".") }
        item { AboutPart(stringResource(R.string.about_licence), stringResource(R.string.about_licence_text)) }
    }
}

@Composable
private fun AboutPart(title: String, vararg paragraphs: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        for (p in paragraphs) Text(p, style = MaterialTheme.typography.bodyMedium)
    }
}
