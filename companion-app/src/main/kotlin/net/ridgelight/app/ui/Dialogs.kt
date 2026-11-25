package net.ridgelight.app.ui

import android.app.TimePickerDialog
import androidx.annotation.StringRes
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import net.ridgelight.app.PlannedBreak
import net.ridgelight.app.R
import net.ridgelight.core.analysis.SunResult
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/** A named pace: a factor on hiking-sign (DIN 33466) times, and who it suits. */
enum class PacePreset(@StringRes val label: Int, val factor: Double, @StringRes val suits: Int) {
    FAST(R.string.pace_fast, 0.75, R.string.pace_fast_suits),
    BRISK(R.string.pace_brisk, 0.9, R.string.pace_brisk_suits),
    SIGNS(R.string.pace_signs, 1.0, R.string.pace_signs_suits),
    RELAXED(R.string.pace_relaxed, 1.2, R.string.pace_relaxed_suits),
    LEISURELY(R.string.pace_leisurely, 1.4, R.string.pace_leisurely_suits),
    ;

    /** The whole hike at this pace: [walkingAtOne] seconds of walking at factor 1, plus [breakSeconds]. */
    fun hikeSeconds(walkingAtOne: Double, breakSeconds: Double): Double = hikeSeconds(walkingAtOne, breakSeconds, factor)

    companion object {
        /** The preset with exactly this [factor], or null between presets. */
        fun of(factor: Double): PacePreset? = entries.firstOrNull { abs(it.factor - factor) < 1e-3 }

        /** The whole hike at any pace [factor]: breaks keep their length. */
        fun hikeSeconds(walkingAtOne: Double, breakSeconds: Double, factor: Double): Double = walkingAtOne * factor + breakSeconds
    }
}

/**
 * Pace: a preset, or any factor on the slider, applied when the dialog closes. Each choice shows
 * the hike's resulting time: [walkingAtOne] seconds of walking at factor 1, plus [breakSeconds].
 */
@Composable
fun PaceDialog(current: Double, walkingAtOne: Double?, breakSeconds: Double, dismiss: () -> Unit, set: (Double) -> Unit) {
    val w = words
    var factor by remember { mutableFloatStateOf(current.toFloat()) }
    fun time(f: Double) = walkingAtOne?.let { w.duration(PacePreset.hikeSeconds(it, breakSeconds, f)) } ?: ""
    fun spokenTime(f: Double) = walkingAtOne?.let { w.spokenDuration(PacePreset.hikeSeconds(it, breakSeconds, f)) } ?: ""
    val title = stringResource(R.string.pace)
    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f))
                InfoButton(title) {
                    InfoText(stringResource(R.string.pace_rule))
                    for (p in PacePreset.entries) InfoText(stringResource(R.string.pace_preset_info, stringResource(p.label), w.factor(p.factor), stringResource(p.suits)))
                    InfoText(stringResource(R.string.pace_slider_info))
                }
            }
        },
        text = {
            Column(Modifier.selectableGroup()) {
                for (p in PacePreset.entries) {
                    val chosen = abs(factor - p.factor) < 1e-3
                    val name = stringResource(p.label)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .selectable(chosen, role = Role.RadioButton) { factor = p.factor.toFloat() }
                            .semantics { contentDescription = name + ", " + spokenTime(p.factor) }
                            .padding(vertical = 2.dp),
                    ) {
                        RadioButton(selected = chosen, onClick = null, modifier = Modifier.padding(12.dp))
                        Text(name, Modifier.weight(1f).hiddenFromReaders(), style = MaterialTheme.typography.bodyLarge)
                        Text(time(p.factor), Modifier.hiddenFromReaders(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.hiddenFromReaders()) {
                    Text(w.factor(factor.toDouble()), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(time(factor.toDouble()), style = MaterialTheme.typography.bodyMedium)
                }
                val sliderState = w.factor(factor.toDouble()) + ", " + spokenTime(factor.toDouble())
                Slider(
                    value = factor,
                    onValueChange = { factor = (it * 20).roundToInt() / 20f },
                    valueRange = 0.6f..1.6f,
                    modifier = Modifier.semantics {
                        contentDescription = title
                        stateDescription = sliderState
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = { set(factor.toDouble()); dismiss() }) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
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
    val w = words
    val title = stringResource(R.string.breaks)
    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f))
                InfoButton(title) {
                    InfoText(stringResource(R.string.breaks_info_stay))
                    InfoText(stringResource(R.string.breaks_info_add))
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (breaks.isEmpty()) Text(stringResource(R.string.no_breaks), style = MaterialTheme.typography.bodyMedium)
                for (b in breaks) {
                    val placed = result?.breaks?.minByOrNull { abs(it.distance - b.distance) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val where = listOfNotNull(placed?.let { localTime(result.timeAt(it.stop.startSeconds)) }, w.km(b.distance)).joinToString(" · ")
                        val text = w.get(R.string.break_row, where, w.duration(b.minutes * 60.0))
                        val spoken = where + ", " + w.spokenDuration(b.minutes * 60.0)
                        Text(text, Modifier.weight(1f).semantics { contentDescription = spoken }, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { remove(b) }) { Text(stringResource(R.string.remove)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = add, enabled = result != null) { Text(stringResource(R.string.add_a_break)) } },
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
    val w = words
    val clock = w.clock
    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val title = stringResource(R.string.add_a_break)
                Text(title, Modifier.weight(1f))
                InfoButton(title) { InfoText(stringResource(R.string.break_info)) }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (place == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(
                            onClick = { TimePickerDialog(context, { _, h, m -> at = LocalTime.of(h, m) }, at.hour, at.minute, true).show() },
                            label = { Text(stringResource(R.string.break_at_time, at.format(clock))) },
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            when {
                                elapsed < 0 -> stringResource(R.string.break_before_start, departure.format(clock))
                                elapsed > result.duration -> stringResource(R.string.break_after_arrival, arrival.format(clock))
                                else -> w.km(distance!!)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Text(stringResource(R.string.break_at_place, w.km(place)), style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.duration), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in listOf(10, 15, 20, 30, 45, 60, 90)) {
                        val spoken = w.spokenDuration(m * 60.0)
                        FilterChip(
                            selected = minutes == m,
                            onClick = { minutes = m },
                            label = { Text(w.duration(m * 60.0)) },
                            modifier = Modifier.semantics { contentDescription = spoken },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { add(distance!!, minutes); dismiss() }, enabled = distance != null) { Text(stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ZoneDialog(current: ZoneId, dismiss: () -> Unit, pick: (ZoneId) -> Unit) {
    var query by remember { mutableStateOf("") }
    val zones = remember { ZoneId.getAvailableZoneIds().filter { '/' in it && !it.startsWith("Etc") }.sorted() }
    AlertDialog(
        onDismissRequest = dismiss,
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.zone_title)) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(current.id) })
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(zones.filter { it.contains(query, ignoreCase = true) }.take(200)) { z ->
                        Text(z.replace('_', ' '), Modifier.fillMaxWidth().clickable(role = Role.Button) { pick(ZoneId.of(z)) }.padding(vertical = 14.dp))
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
        title = { Text(stringResource(R.string.clouds_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.clouds_dialog_what), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.clouds_dialog_sent), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = { accept(); dismiss() }) { Text(stringResource(R.string.turn_on)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
