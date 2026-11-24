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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ridgelight.core.analysis.Light
import net.ridgelight.core.analysis.SunResult
import net.ridgelight.core.weather.CloudColumn
import net.ridgelight.core.weather.SkyModel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Horizontal bar split by time spent in each kind of light. */
@Composable
fun LightBar(totals: Map<Light, Double>, modifier: Modifier = Modifier.fillMaxWidth().height(14.dp)) {
    val total = totals.values.sum()
    Canvas(modifier) {
        if (total <= 0) return@Canvas
        val r = CornerRadius(size.height / 2)
        drawRoundRect(lightColor(Light.UNKNOWN).copy(alpha = 0.25f), cornerRadius = r)
        clipRect {
            var x = 0f
            for (light in Light.entries) {
                val w = (totals[light] ?: 0.0).div(total).toFloat() * size.width
                if (w <= 0f) continue
                drawRect(lightColor(light), Offset(x, 0f), Size(w, size.height))
                x += w
            }
        }
    }
}

/**
 * Light over the hike's time, walking and breaks (breaks drawn lighter). With [onScrub], tapping
 * or dragging along it picks a moment (seconds after departure), marked at [cursorSeconds].
 */
@Composable
fun TimelineBar(
    result: SunResult,
    modifier: Modifier = Modifier.fillMaxWidth().height(10.dp),
    cursorSeconds: Double? = null,
    onScrub: ((Double) -> Unit)? = null,
) {
    val cursorColor = MaterialTheme.colorScheme.onSurface
    val scrub by rememberUpdatedState(onScrub)
    val d = result.duration
    val input = if (onScrub == null) Modifier else Modifier.scrubbing(result) { x, width -> scrub?.invoke((x / width).coerceIn(0f, 1f) * d) }
    Canvas(modifier.then(input)) {
        if (d <= 0) return@Canvas
        val barHeight = min(size.height, 10.dp.toPx())
        val top = (size.height - barHeight) / 2
        for (run in result.timeline) {
            val x0 = (run.startSeconds / d).toFloat() * size.width
            val x1 = (run.endSeconds / d).toFloat() * size.width
            val color = lightColor(run.light).let { if (run.isBreak) it.copy(alpha = 0.5f) else it }
            drawRect(color, Offset(x0, top), Size(max(1f, x1 - x0), barHeight))
        }
        cursorSeconds?.let {
            val x = (it / d).toFloat().coerceIn(0f, 1f) * size.width
            drawLine(cursorColor, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

/**
 * Elevation profile coloured by the light at the time each stretch is reached, with a cursor at
 * [cursorDistance] metres. With [clouds], the forecast cloud at each height is drawn in grey
 * behind it, as it will be when the hiker passes. With [onScrub], tapping or dragging along the
 * profile picks the moment the hiker passes that point (seconds after departure). [highlight]
 * shades a part of the route (metres); [hint] is written faintly in the corner.
 */
@Composable
fun ProfileChart(
    result: SunResult,
    cursorDistance: Double?,
    clouds: List<CloudColumn>? = null,
    modifier: Modifier = Modifier.fillMaxWidth().height(if (clouds != null) 180.dp else 150.dp),
    onScrub: ((Double) -> Unit)? = null,
    highlight: ClosedFloatingPointRange<Double>? = null,
    hint: String? = null,
) {
    val measurer = rememberTextMeasurer()
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val cursorColor = MaterialTheme.colorScheme.onSurface
    val cursorFill = MaterialTheme.colorScheme.surface
    val highlightColor = MaterialTheme.colorScheme.primary
    val nodes = result.route.nodes
    val elevations = nodes.map { it.elevation }.filter { it.isFinite() }
    if (elevations.isEmpty()) return
    val minE = elevations.min()
    val routeTop = max(elevations.max(), minE + 50)
    // With clouds, leave room above the route to show what hangs over it.
    val maxE = if (clouds != null) routeTop + max(300.0, 0.3 * (routeTop - minE)) else routeTop
    val length = result.route.length
    val cloudColor = MaterialTheme.colorScheme.onSurfaceVariant
    val scrub by rememberUpdatedState(onScrub)
    val input = if (onScrub == null) Modifier else Modifier.scrubbing(result) { x, width ->
        val left = 44.dp.toPx()
        val right = width - 4.dp.toPx()
        val d = ((x - left) / (right - left)).coerceIn(0f, 1f) * length
        scrub?.invoke(result.secondsAt(d))
    }

    Canvas(modifier.then(input)) {
        val left = 44.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val top = 6.dp.toPx()
        val right = size.width - 4.dp.toPx()
        fun x(d: Double) = left + (right - left) * (d / length).toFloat()
        fun y(e: Double) = bottom - (bottom - top) * ((e - minE) / (maxE - minE)).toFloat()

        clouds?.let { drawClouds(it, cloudColor, minE, maxE, left, right, top, bottom, ::x) }
        highlight?.let { h ->
            val x0 = x(h.start)
            drawRect(highlightColor.copy(alpha = 0.12f), Offset(x0, top), Size(max(2f, x(h.endInclusive) - x0), bottom - top))
        }
        label(measurer, "${maxE.roundToInt()} m", Offset(0f, top), axis)
        label(measurer, "${minE.roundToInt()} m", Offset(0f, bottom - 14.sp.toPx()), axis)
        label(measurer, "0 km", Offset(left, bottom + 2.dp.toPx()), axis)
        val end = "%.1f km".format(length / 1000)
        label(measurer, end, Offset(right - measurer.measure(end, TextStyle(fontSize = 11.sp)).size.width, bottom + 2.dp.toPx()), axis)
        hint?.let { label(measurer, it, Offset(left + 6.dp.toPx(), top), axis.copy(alpha = 0.8f)) }

        for ((i, step) in result.route.steps.withIndex()) {
            val a = step.start
            val b = step.end
            if (!a.elevation.isFinite() || !b.elevation.isFinite()) continue
            val color = lightColor(result.samples[i].light)
            val area = Path().apply {
                moveTo(x(a.distance), bottom)
                lineTo(x(a.distance), y(a.elevation))
                lineTo(x(b.distance), y(b.elevation))
                lineTo(x(b.distance), bottom)
                close()
            }
            drawPath(area, color.copy(alpha = 0.28f))
            drawLine(color, Offset(x(a.distance), y(a.elevation)), Offset(x(b.distance), y(b.elevation)), 2.5.dp.toPx(), StrokeCap.Round)
        }
        drawLine(axis.copy(alpha = 0.5f), Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())

        // Breaks: a ring in the colour of the light most of the break is spent in.
        for (b in result.breaks) {
            val node = nodes[b.stop.node]
            if (!node.elevation.isFinite()) continue
            val main = b.pieces.groupBy { it.sample.light }.maxBy { (_, ps) -> ps.sumOf { it.endSeconds - it.startSeconds } }.key
            val at = Offset(x(node.distance), y(node.elevation))
            drawCircle(lightColor(main), 6.dp.toPx(), at)
            drawCircle(cursorColor, 6.dp.toPx(), at, style = Stroke(1.5.dp.toPx()))
        }

        cursorDistance?.let { d ->
            val cx = x(d)
            drawLine(cursorColor, Offset(cx, top), Offset(cx, bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val e = result.route.elevationAt(d)
            if (e.isFinite()) {
                drawCircle(cursorColor, 6.dp.toPx(), Offset(cx, y(e)))
                drawCircle(cursorFill, 3.dp.toPx(), Offset(cx, y(e)))
            }
        }
    }
}

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

/** Taps and horizontal drags report their x position (and the element's width) to [at]. */
private fun Modifier.scrubbing(key: Any, at: androidx.compose.ui.unit.Density.(x: Float, width: Float) -> Unit): Modifier =
    pointerInput(key) {
        detectTapGestures { at(it.x, size.width.toFloat()) }
    }.pointerInput(key) {
        detectHorizontalDragGestures(onDragStart = { at(it.x, size.width.toFloat()) }) { change, _ ->
            change.consume()
            at(change.position.x, size.width.toFloat())
        }
    }

/**
 * Cloud cover by height between levels, as grey with opacity by cover; cloud above the chart
 * shows as a strip along its top edge.
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
                drawRect(color.copy(alpha = (0.5 * cover).toFloat()), Offset(x0, y(hi)), Size(x1 - x0, y(lo) - y(hi)))
            }
            // Low and mid-level cloud above the chart (not the thin high cloud).
            val above = p.heights.indices.filter { p.heights[it] > maxE && p.heights[it] < SkyModel.HIGH_CLOUD_M }.maxOfOrNull { p.cover[it] } ?: 0.0
            if (above >= 0.05) drawRect(color.copy(alpha = (0.6 * above).toFloat()), Offset(x0, top), Size(x1 - x0, strip))
        }
    }
}

private fun DrawScope.label(m: TextMeasurer, text: String, at: Offset, color: Color) {
    drawText(m, text, at, TextStyle(fontSize = 11.sp, color = color))
}
