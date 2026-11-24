package net.ridgelight.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.ridgelight.app.UiState
import net.ridgelight.app.WeatherState
import net.ridgelight.core.dem.Terrarium
import net.ridgelight.core.weather.OpenMeteo
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
                        Text(title)
                        subtitle?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = back) { Icon(AppIcons.Back, "Back") } },
                actions = { info?.let { InfoButton(title, Modifier.padding(end = 8.dp), it) } },
            )
        },
        content = content,
    )
}

private fun listPadding(padding: PaddingValues) =
    PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp)

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

/** Light bar, sun (and sun between clouds) and a star: one start time or day. */
@Composable
private fun RowScope.Comparison(combined: Map<net.ridgelight.core.weather.SkyLight, Double>, best: Boolean) {
    SegmentBar(segments(combined), Modifier.weight(1f).height(10.dp))
    Text(sunText(combined), Modifier.width(108.dp).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
    Text(if (best) "★" else "", Modifier.width(16.dp), color = MaterialTheme.colorScheme.secondary)
}

/** Sun for each start time of the day; tapping one makes it the plan's start. */
@Composable
fun StartTimesScreen(state: UiState, localTime: (Long) -> String, pick: (LocalTime) -> Unit, back: () -> Unit) =
    DetailScaffold("Start times", planLine(state.settings), back, info = {
        val withClouds = state.sweep?.any { it.skyTotals != null } == true
        InfoText("Your plan with each start time through the day: same date, route, pace and breaks. Tap a time to use it.")
        InfoText(
            if (withClouds) "Terrain and the cloud forecast combined. The number is sun; after “+”, sun between clouds."
            else "From the terrain alone. With Clouds switched on, the cloud forecast is included.",
        )
        InfoText("★ most sun" + if (withClouds) ", with sun between clouds counted half." else ".")
    }) { padding ->
        val sweep = state.sweep
        LazyColumn(contentPadding = listPadding(padding), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (sweep == null) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                return@LazyColumn
            }
            val best = sweep.maxByOrNull { it.expectedSun }?.takeIf { it.expectedSun >= 60 }
            items(sweep) { e ->
                val time = Instant.ofEpochMilli(e.departureUtc).atZone(state.settings.zone).toLocalTime()
                val chosen = time == state.settings.start
                PickRow(chosen, onClick = { pick(time) }) {
                    Text(localTime(e.departureUtc), Modifier.width(52.dp), fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal)
                    Comparison(e.combined, e === best)
                }
            }
        }
    }

/** The plan on each forecast day; tapping one makes it the plan's date. */
@Composable
fun DaysScreen(state: UiState, pick: (LocalDate) -> Unit, retry: () -> Unit, back: () -> Unit) =
    DetailScaffold("Which day?", "Start ${state.settings.start} · pace ${paceName(state.settings.paceFactor)}", back, info = {
        InfoText("Your start, pace and breaks on each day of the cloud forecast, terrain and clouds combined. Tap a day to use it.")
        InfoText("The number is sun; after “+”, sun between clouds. ★ most sun, with sun between clouds counted half.")
        InfoText("Days more than a week ahead are greyed: their forecast is rough.")
        Text(OpenMeteo.ATTRIBUTION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }) { padding ->
        LazyColumn(contentPadding = listPadding(padding), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val weather = state.weather
            val days = state.days
            when {
                weather is WeatherState.Unavailable -> item {
                    Text(weather.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = retry) { Text("Try again") }
                }
                weather !is WeatherState.Ready || days == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else -> {
                    val today = LocalDate.now(state.settings.zone)
                    val best = days.maxByOrNull { it.expectedSun }?.takeIf { it.expectedSun >= 600 }
                    items(days) { d ->
                        val chosen = d.date == state.settings.date
                        // Forecasts lose skill after about a week.
                        PickRow(chosen, Modifier.alpha(if (d.date > today.plusDays(7)) 0.55f else 1f), onClick = { pick(d.date) }) {
                            Text(d.date.format(dayFormat), Modifier.width(100.dp), fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal)
                            Comparison(d.combined, d === best)
                        }
                    }
                }
            }
        }
    }

/** What the app does, how far to trust it, what leaves the phone, and where the data comes from. */
@Composable
fun AboutScreen(back: () -> Unit) = DetailScaffold("About", null, back) { padding ->
    val context = LocalContext.current
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    LazyColumn(contentPadding = listPadding(padding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Ridge Light", style = MaterialTheme.typography.headlineSmall)
            Text(
                "When are you in the sun on your hike, and when do the mountains hide it?" + (version?.let { "\nVersion $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            AboutPart(
                "How it works",
                "Terrain: for each point of the route, at the time you get there, Ridge Light looks from your eyes towards the sun across " +
                    "elevation data up to 45 km away, allowing for the curve of the earth, and checks whether a ridge is in the way.",
                "Timing: your start time and the rule behind hiking-sign times (DIN 33466: 4 km/h on the flat, 300 m up or 500 m down an hour), " +
                    "scaled by your pace. Breaks stay at their place on the route.",
                "Clouds (when switched on): the forecast cloud cover at different heights for a few points of the route, at your altitude and " +
                    "time. Terrain sun under a clear or thin sky stays sun; under broken cloud it becomes “sun and clouds”, counted half when " +
                    "comparing start times and days; overcast or fog hides it.",
            )
        }
        item {
            AboutPart(
                "Limits",
                "Trees, buildings and snow are not modelled. The elevation data is a few tens of metres coarse, so narrow gorges and single " +
                    "rocks can be missed.",
                "Cloud forecasts are coarse too: models work on a grid of a few kilometres and on levels a few hundred metres apart, often " +
                    "misplace low fog, and get rough more than a week ahead.",
            )
        }
        item {
            AboutPart(
                "Privacy",
                "No account, no tracking. Terrain tiles for the area of your route are downloaded from Amazon's open-data server, which therefore sees that area; they are kept on the phone.",
                "Only while Clouds is on, the coordinates of up to six points of your route are sent to Open-Meteo for the forecast. Nothing else is sent.",
                "Results go to OsmAnd through its app interface, on the phone.",
            )
        }
        item {
            AboutPart("Data", "${Terrarium.ATTRIBUTION}.", "${OpenMeteo.ATTRIBUTION}.")
        }
    }
}

@Composable
private fun AboutPart(title: String, vararg paragraphs: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        for (p in paragraphs) Text(p, style = MaterialTheme.typography.bodyMedium)
    }
}
