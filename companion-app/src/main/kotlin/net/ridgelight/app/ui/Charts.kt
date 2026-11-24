package net.ridgelight.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ridgelight.core.analysis.SunResult
import net.ridgelight.core.weather.CloudColumn
import net.ridgelight.core.weather.SkyLight
import net.ridgelight.core.weather.SkyModel
import net.ridgelight.core.weather.SkyReport
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Horizontal bar split into [segments] (colour, amount), in order. */
@Composable
fun SegmentBar(segments: List<Pair<Color, Double>>, modifier: Modifier = Modifier.fillMaxWidth().height(10.dp)) {
    val total = segments.sumOf { it.second }
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    Canvas(modifier) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        if (total <= 0) return@Canvas
        clipRect {
            var x = 0f
            for ((color, amount) in segments) {
                val w = (amount / total).toFloat() * size.width
                if (w <= 0f) continue
                drawRect(color, Offset(x, 0f), Size(w, size.height))
                x += w
            }
        }
    }
}

/** Combined totals as segments, in the order of [SkyLight]. */
fun segments(totals: Map<SkyLight, Double>): List<Pair<Color, Double>> =
    SkyLight.entries.map { skyLightColor(it) to (totals[it] ?: 0.0) }

/**
 * Small columns side by side, heights 0..1 from [values]; [selected] is drawn with a mark under
 * it, and [faded] columns lighter.
 */
@Composable
fun MiniColumns(
    values: List<Double>,
    color: Color,
    modifier: Modifier,
    selected: Int? = null,
    faded: (Int) -> Boolean = { false },
) {
    val mark = MaterialTheme.colorScheme.onSurface
    val base = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val gap = min(2.dp.toPx(), size.width / values.size / 4)
        val w = size.width / values.size - gap
        val markHeight = 3.dp.toPx()
        val bottom = size.height - markHeight - 2.dp.toPx()
        for ((i, v) in values.withIndex()) {
            val x = i * (w + gap)
            drawRect(base, Offset(x, bottom - 1.dp.toPx()), Size(w, 1.dp.toPx()))
            val h = (v.coerceIn(0.0, 1.0) * bottom).toFloat()
            if (h > 0) drawRect(if (faded(i)) color.copy(alpha = 0.45f) else color, Offset(x, bottom - h), Size(w, h))
            if (i == selected) drawRect(mark, Offset(x, size.height - markHeight), Size(w, markHeight))
        }
    }
}

private val LEFT = 44.dp
private val RIGHT = 4.dp
private val TOP = 6.dp
private val BAR = 10.dp
private val BAR_GAP = 4.dp
private val AXIS = 20.dp
private val SNAP = 10.dp

/**
 * Elevation profile coloured by the light at the time each part is reached: terrain and clouds
 * combined when there is a [sky] report, terrain alone otherwise. With [sky], two bars under it
 * on the same x-axis show the causes: the terrain's light and the clouds above the hiker; the
 * forecast cloud at each height is drawn faintly behind the profile. Along the bottom, the
 * places reached at each full hour.
 *
 * Tapping or dragging anywhere picks the moment the hiker passes that point ([onScrub], seconds
 * after departure); near a break it snaps to the break. [highlight] shades a part of the route.
 */
@Composable
fun ProfileChart(
    result: SunResult,
    sky: SkyReport?,
    zone: ZoneId,
    cursorSeconds: Double,
    onScrub: (Double) -> Unit,
    modifier: Modifier = Modifier,
    highlight: ClosedFloatingPointRange<Double>? = null,
    hint: String? = null,
) {
    val measurer = rememberTextMeasurer()
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val cursorColor = MaterialTheme.colorScheme.onSurface
    val cursorFill = MaterialTheme.colorScheme.surface
    val highlightColor = MaterialTheme.colorScheme.primary
    val cloudColor = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
    val nodes = result.route.nodes
    val elevations = nodes.map { it.elevation }.filter { it.isFinite() }
    if (elevations.isEmpty()) return
    val minE = elevations.min()
    val routeTop = max(elevations.max(), minE + 50)
    // With clouds, leave room above the route to show what hangs over it.
    val maxE = if (sky != null) routeTop + max(300.0, 0.3 * (routeTop - minE)) else routeTop
    val length = result.route.length
    val profileHeight = if (sky != null) 150.dp else 140.dp
    val bars = if (sky != null) BAR_GAP * 2 + BAR * 2 + BAR_GAP else 0.dp
    val scrub by rememberUpdatedState(onScrub)

    val input = Modifier.scrubbing(result) { x, width ->
        val left = LEFT.toPx()
        val right = width - RIGHT.toPx()
        fun xOf(d: Double) = left + (right - left) * (d / length).toFloat()
        val near = result.breaks.minByOrNull { abs(xOf(it.distance) - x) }?.takeIf { abs(xOf(it.distance) - x) <= SNAP.toPx() }
        val seconds = near?.stop?.startSeconds ?: result.secondsAt(((x - left) / (right - left)).coerceIn(0f, 1f) * length)
        scrub(seconds)
    }

    Canvas(modifier.fillMaxWidth().height(TOP + profileHeight + bars + AXIS).then(input)) {
        val left = LEFT.toPx()
        val right = size.width - RIGHT.toPx()
        val top = TOP.toPx()
        val bottom = top + profileHeight.toPx()
        fun x(d: Double) = left + (right - left) * (d / length).toFloat()
        fun y(e: Double) = bottom - (bottom - top) * ((e - minE) / (maxE - minE)).toFloat()
        val terrainTop = bottom + BAR_GAP.toPx() * 2
        val cloudsTop = terrainTop + BAR.toPx() + BAR_GAP.toPx()
        val barsBottom = if (sky != null) cloudsTop + BAR.toPx() else bottom
        val steps = result.route.steps

        sky?.let { drawClouds(it.columns, cloudColor, minE, maxE, left, right, top, bottom, ::x) }
        highlight?.let { h ->
            val x0 = x(h.start)
            drawRect(highlightColor.copy(alpha = 0.14f), Offset(x0, top), Size(max(2f, x(h.endInclusive) - x0), barsBottom - top))
        }
        label(measurer, "${maxE.roundToInt()} m", Offset(0f, top), axis)
        label(measurer, "${minE.roundToInt()} m", Offset(0f, bottom - 14.sp.toPx()), axis)
        hint?.let { label(measurer, it, Offset(left + 6.dp.toPx(), top), axis.copy(alpha = 0.8f)) }

        for ((i, step) in steps.withIndex()) {
            val a = step.start
            val b = step.end
            if (!a.elevation.isFinite() || !b.elevation.isFinite()) continue
            val color = skyLightColor(SkyLight.of(result.samples[i].light, sky?.stepSky?.get(i)?.sky))
            val area = Path().apply {
                moveTo(x(a.distance), bottom)
                lineTo(x(a.distance), y(a.elevation))
                lineTo(x(b.distance), y(b.elevation))
                lineTo(x(b.distance), bottom)
                close()
            }
            drawPath(area, color.copy(alpha = 0.3f))
            drawLine(color, Offset(x(a.distance), y(a.elevation)), Offset(x(b.distance), y(b.elevation)), 2.5.dp.toPx(), StrokeCap.Round)
        }
        drawLine(axis.copy(alpha = 0.5f), Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())

        // The causes, on the same x-axis: terrain light, and the clouds above the hiker.
        if (sky != null) {
            val barHeight = BAR.toPx()
            drawRect(track, Offset(left, terrainTop), Size(right - left, barHeight))
            drawRect(track, Offset(left, cloudsTop), Size(right - left, barHeight))
            for ((i, step) in steps.withIndex()) {
                val x0 = x(step.start.distance)
                val w = max(1f, x(step.end.distance) - x0)
                drawRect(lightColor(result.samples[i].light), Offset(x0, terrainTop), Size(w, barHeight))
                val alpha = skyAlpha(sky.stepSky[i].sky)
                if (alpha > 0f) drawRect(cloudColor.copy(alpha = alpha), Offset(x0, cloudsTop), Size(w, barHeight))
            }
            barLabel(measurer, "Terrain", terrainTop + barHeight / 2, axis)
            barLabel(measurer, "Clouds", cloudsTop + barHeight / 2, axis)
        }

        // Breaks: a ring in the colour of most of the break, and a mark across the bars.
        for ((k, b) in result.breaks.withIndex()) {
            val node = nodes[b.stop.node]
            val bx = x(node.distance)
            if (sky != null) drawLine(cursorColor.copy(alpha = 0.5f), Offset(bx, terrainTop), Offset(bx, barsBottom), 1.5.dp.toPx())
            if (!node.elevation.isFinite()) continue
            val main = b.pieces.indices.groupBy { p -> SkyLight.of(b.pieces[p].sample.light, sky?.breakSky?.get(k)?.get(p)?.sky) }
                .maxBy { (_, ps) -> ps.sumOf { b.pieces[it].endSeconds - b.pieces[it].startSeconds } }.key
            val at = Offset(bx, y(node.elevation))
            drawCircle(skyLightColor(main), 6.dp.toPx(), at)
            drawCircle(cursorColor, 6.dp.toPx(), at, style = Stroke(1.5.dp.toPx()))
        }

        // Full hours, where the hiker is then.
        val tickTop = barsBottom + 2.dp.toPx()
        val clock = DateTimeFormatter.ofPattern("HH:mm")
        var hour = Instant.ofEpochMilli(result.departureUtc).atZone(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1)
        val arrival = result.timeAt(result.duration)
        var lastLabelEnd = Float.NEGATIVE_INFINITY
        while (hour.toInstant().toEpochMilli() <= arrival) {
            val seconds = (hour.toInstant().toEpochMilli() - result.departureUtc) / 1000.0
            val hx = x(result.momentAt(seconds).distance)
            drawLine(axis.copy(alpha = 0.6f), Offset(hx, tickTop), Offset(hx, tickTop + 4.dp.toPx()), 1.dp.toPx())
            val text = hour.format(clock)
            val w = measurer.measure(text, TextStyle(fontSize = 10.sp)).size.width
            val lx = (hx - w / 2).coerceIn(left - 8.dp.toPx(), size.width - w)
            if (lx > lastLabelEnd + 4.dp.toPx()) {
                drawText(measurer, text, Offset(lx, tickTop + 4.dp.toPx()), TextStyle(fontSize = 10.sp, color = axis))
                lastLabelEnd = lx + w
            }
            hour = hour.plusHours(1)
        }

        // Cursor, across the profile and the bars.
        val d = result.momentAt(cursorSeconds.coerceIn(0.0, result.duration)).distance
        val cx = x(d)
        drawLine(cursorColor, Offset(cx, top), Offset(cx, barsBottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        val e = result.route.elevationAt(d)
        if (e.isFinite()) {
            drawCircle(cursorColor, 6.dp.toPx(), Offset(cx, y(e)))
            drawCircle(cursorFill, 3.dp.toPx(), Offset(cx, y(e)))
        }
    }
}

/** Taps and horizontal drags report their x position (and the element's width) to [at]. */
private fun Modifier.scrubbing(key: Any, at: Density.(x: Float, width: Float) -> Unit): Modifier =
    pointerInput(key) {
        detectTapGestures { at(it.x, size.width.toFloat()) }
    }.pointerInput(key) {
        detectHorizontalDragGestures(onDragStart = { at(it.x, size.width.toFloat()) }) { change, _ ->
            change.consume()
            at(change.position.x, size.width.toFloat())
        }
    }

/**
 * Cloud cover by height between levels, as faint grey with opacity by cover; cloud above the
 * chart shows as a strip along its top edge.
 */
private fun DrawScope.drawClouds(
    columns: List<CloudColumn>,
    color: Color,
    minE: Double,
    maxE: Double,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    x: (Double) -> Float,
) {
    fun y(e: Double) = bottom - (bottom - top) * ((e - minE) / (maxE - minE)).toFloat()
    val strip = 5.dp.toPx()
    clipRect(left, top, right, bottom) {
        for (c in columns) {
            val p = c.profile ?: continue
            val x0 = x(c.fromDistance)
            val x1 = max(x(c.toDistance), x0 + 1f)
            for (k in 0 until p.heights.size - 1) {
                val lo = max(p.heights[k], minE)
                val hi = min(p.heights[k + 1], maxE)
                if (hi <= lo) continue
                val cover = (p.cover[k] + p.cover[k + 1]) / 2
                if (cover < 0.05) continue
                drawRect(color.copy(alpha = (0.35 * cover).toFloat()), Offset(x0, y(hi)), Size(x1 - x0, y(lo) - y(hi)))
            }
            // Low and mid-level cloud above the chart (not the thin high cloud).
            val above = p.heights.indices.filter { p.heights[it] > maxE && p.heights[it] < SkyModel.HIGH_CLOUD_M }.maxOfOrNull { p.cover[it] } ?: 0.0
            if (above >= 0.05) drawRect(color.copy(alpha = (0.5 * above).toFloat()), Offset(x0, top), Size(x1 - x0, strip))
        }
    }
}

private fun DrawScope.label(m: TextMeasurer, text: String, at: Offset, color: Color) {
    drawText(m, text, at, TextStyle(fontSize = 11.sp, color = color))
}

/** A bar's name in the left margin, centred on [centreY]. */
private fun DrawScope.barLabel(m: TextMeasurer, text: String, centreY: Float, color: Color) {
    val layout = m.measure(text, TextStyle(fontSize = 10.sp, color = color))
    drawText(layout, topLeft = Offset(0f, centreY - layout.size.height / 2))
}
