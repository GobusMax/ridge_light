package net.ridgelight.app.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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

/** Pace factor on hiking-sign (DIN 33466) times, applied when the dialog closes. */
@Composable
fun PaceDialog(current: Double, dismiss: () -> Unit, set: (Double) -> Unit) {
    var factor by remember { mutableFloatStateOf(current.toFloat()) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Pace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("×%.2f · %s".format(factor, paceWords(factor.toDouble())), style = MaterialTheme.typography.bodyLarge)
                Slider(value = factor, onValueChange = { factor = (it * 20).roundToInt() / 20f }, valueRange = 0.6f..1.6f)
                Text(
                    "Walking times follow the rule behind hiking-sign times (DIN 33466). Below 1 you walk faster than the signs, above 1 slower.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = { set(factor.toDouble()); dismiss() }) { Text("Done") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

fun paceWords(factor: Double) = when {
    factor < 0.95 -> "quicker than hiking signs"
    factor > 1.05 -> "slower than hiking signs"
    else -> "hiking-sign times"
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
        title = { Text("Breaks") },
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
                Text(
                    "Tip: drag along the profile to a place and tap “Break here”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
