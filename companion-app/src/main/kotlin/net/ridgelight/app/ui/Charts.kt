package net.ridgelight.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ridgelight.core.analysis.Light
import net.ridgelight.core.analysis.SunResult
import kotlin.math.max
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

/** Light along the route over walking time (runs in sequence). */
@Composable
fun TimelineBar(result: SunResult, modifier: Modifier = Modifier.fillMaxWidth().height(10.dp)) {
    Canvas(modifier) {
        val d = result.duration
        if (d <= 0) return@Canvas
        for (run in result.runs) {
            val x0 = (run.startSeconds / d).toFloat() * size.width
            val x1 = (run.endSeconds / d).toFloat() * size.width
            drawRect(lightColor(run.light), Offset(x0, 0f), Size(max(1f, x1 - x0), size.height))
        }
    }
}

/**
 * Elevation profile coloured by the light at the time each stretch is reached, with a cursor at
 * [cursorDistance] metres.
 */
@Composable
fun ProfileChart(result: SunResult, cursorDistance: Double?, modifier: Modifier = Modifier.fillMaxWidth().height(170.dp)) {
    val measurer = rememberTextMeasurer()
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val cursorColor = MaterialTheme.colorScheme.onSurface
    val nodes = result.route.nodes
    val elevations = nodes.map { it.elevation }.filter { it.isFinite() }
    if (elevations.isEmpty()) return
    val minE = elevations.min()
    val maxE = max(elevations.max(), minE + 50)
    val length = result.route.length

    Canvas(modifier) {
        val left = 44.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val top = 6.dp.toPx()
        val right = size.width - 4.dp.toPx()
        fun x(d: Double) = left + (right - left) * (d / length).toFloat()
        fun y(e: Double) = bottom - (bottom - top) * ((e - minE) / (maxE - minE)).toFloat()

        label(measurer, "${maxE.roundToInt()} m", Offset(0f, top), axis)
        label(measurer, "${minE.roundToInt()} m", Offset(0f, bottom - 14.sp.toPx()), axis)
        label(measurer, "0 km", Offset(left, bottom + 2.dp.toPx()), axis)
        val end = "%.1f km".format(length / 1000)
        label(measurer, end, Offset(right - measurer.measure(end, TextStyle(fontSize = 11.sp)).size.width, bottom + 2.dp.toPx()), axis)

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

        cursorDistance?.let { d ->
            val cx = x(d)
            drawLine(cursorColor, Offset(cx, top), Offset(cx, bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val i = nodes.indexOfFirst { it.distance >= d }.coerceAtLeast(1)
            val p = nodes[i - 1]
            val q = nodes[i]
            val f = if (q.distance > p.distance) (d - p.distance) / (q.distance - p.distance) else 0.0
            val e = p.elevation + (q.elevation - p.elevation) * f
            if (e.isFinite()) {
                drawCircle(cursorColor, 5.dp.toPx(), Offset(cx, y(e)))
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.label(m: TextMeasurer, text: String, at: Offset, color: androidx.compose.ui.graphics.Color) {
    drawText(m, text, at, TextStyle(fontSize = 11.sp, color = color))
}
