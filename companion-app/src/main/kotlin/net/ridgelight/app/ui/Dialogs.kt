package net.ridgelight.app.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.ridgelight.app.PlannedBreak
import net.ridgelight.core.analysis.SunResult
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/** A named pace: a factor on hiking-sign (DIN 33466) times, and who it suits. */
class PacePreset(val name: String, val factor: Double, val suits: String)

val PACES = listOf(
    PacePreset("Fast", 0.75, "very fit, light pack, few stops"),
    PacePreset("Brisk", 0.9, "fit hikers, a little quicker than the signs"),
    PacePreset("Hiking signs", 1.0, "the times on yellow hiking signs"),
    PacePreset("Relaxed", 1.2, "photo stops, or less fit"),
    PacePreset("Leisurely", 1.4, "with children or a heavy pack"),
)

/** The preset's name for [factor], or "×1.10" between presets. */
fun paceName(factor: Double): String = PACES.firstOrNull { abs(it.factor - factor) < 1e-3 }?.name ?: "×%.2f".format(factor)

/**
 * Pace: a preset, or any factor on the slider, applied when the dialog closes. Each choice shows
 * the hike's resulting time: [walkingAtOne] seconds of walking at factor 1, plus [breakSeconds].
 */
@Composable
fun PaceDialog(current: Double, walkingAtOne: Double?, breakSeconds: Double, dismiss: () -> Unit, set: (Double) -> Unit) {
    var factor by remember { mutableFloatStateOf(current.toFloat()) }
    fun time(f: Double) = walkingAtOne?.let { duration(it * f + breakSeconds) } ?: ""
    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pace", Modifier.weight(1f))
                InfoButton("Pace") {
                    InfoText("Walking times follow the rule behind hiking-sign times (DIN 33466): 4 km/h on the flat, 300 m up or 500 m down an hour, whichever takes longer plus half the other.")
                    for (p in PACES) InfoText("${p.name} (×%.2f): ${p.suits}.".format(p.factor))
                    InfoText("The slider sets any pace in between. Times include your breaks.")
                }
            }
        },
        text = {
            Column {
                for (p in PACES) {
                    val chosen = abs(factor - p.factor) < 1e-3
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { factor = p.factor.toFloat() }.padding(vertical = 2.dp),
                    ) {
                        RadioButton(selected = chosen, onClick = { factor = p.factor.toFloat() })
                        Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(time(p.factor), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Text("×%.2f".format(factor), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(time(factor.toDouble()), style = MaterialTheme.typography.bodyMedium)
                }
                Slider(value = factor, onValueChange = { factor = (it * 20).roundToInt() / 20f }, valueRange = 0.6f..1.6f)
            }
        },
        confirmButton = { TextButton(onClick = { set(factor.toDouble()); dismiss() }) { Text("Done") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

/** The planned breaks, each removable, and a way to add one. */
@Composable
fun BreaksDialog(
    breaks: List<PlannedBreak>,
    result: SunResult?,
    localTime: (Long) -> String,
    dismiss: () -> Unit,
    remove: (PlannedBreak) -> Unit,
    add: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Breaks", Modifier.weight(1f))
                InfoButton("Breaks") {
                    InfoText("A break stays at its place on the route when you change the start time or pace, and the light during it counts in the totals.")
                    InfoText("To add one at a place, drag along the profile and tap “Break here”.")
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (breaks.isEmpty()) Text("No breaks planned.", style = MaterialTheme.typography.bodyMedium)
                for (b in breaks) {
                    val placed = result?.breaks?.minByOrNull { abs(it.distance - b.distance) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val time = placed?.let { localTime(result.timeAt(it.stop.startSeconds)) + " · " } ?: ""
                        Text("☕ ${time}km %.1f · %d min".format(b.distance / 1000, b.minutes), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { remove(b) }) { Text("Remove") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = add, enabled = result != null) { Text("Add a break") } },
    )
}

/**
 * Adds a break, either at a given [place] (metres along the route) or at the place reached at a
 * chosen time with the current plan.
 */
@Composable
fun BreakDialog(result: SunResult, place: Double?, zone: ZoneId, dismiss: () -> Unit, add: (Double, Int) -> Unit) {
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
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Add a break", Modifier.weight(1f))
                InfoButton(null) { InfoText("The break stays at this place when you change the start time or pace.") }
            }
        },
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
            }
        },
        confirmButton = {
            TextButton(onClick = { add(distance!!, minutes); dismiss() }, enabled = distance != null) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
fun ZoneDialog(current: ZoneId, dismiss: () -> Unit, pick: (ZoneId) -> Unit) {
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

/** Asked once, before the first cloud forecast is fetched. */
@Composable
fun CloudsDialog(dismiss: () -> Unit, accept: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Include the clouds?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Ridge Light then combines the terrain with the cloud forecast: sun under a clear sky stays sun, under broken cloud it becomes “sun and clouds”, and overcast or fog hides it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "For this, the coordinates of up to six points of your route are sent to Open-Meteo (open-meteo.com), for every route you open while Clouds is on. Nothing else is sent.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { TextButton(onClick = { accept(); dismiss() }) { Text("Turn on") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}
