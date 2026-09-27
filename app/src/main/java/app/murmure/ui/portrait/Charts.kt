package app.murmure.ui.portrait

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.murmure.core.Dates
import app.murmure.ui.components.moodFaces
import app.murmure.ui.theme.M
import app.murmure.ui.theme.Manrope
import app.murmure.ui.theme.Viz
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Courbe d'humeur (série unique) : 2 px, points 8 px, grille discrète, tap = infobulle. */
@Composable
fun MoodLine(series: List<Pair<LocalDate, Float?>>, modifier: Modifier = Modifier) {
    var picked by remember(series) { mutableStateOf<Int?>(series.indexOfLast { it.second != null }.takeIf { it >= 0 }) }
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontFamily = Manrope, fontSize = 11.sp, color = M.Muted)
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(34.dp)) {
            picked?.let { i ->
                val (d, v) = series[i]
                if (v != null) Text(
                    "${d.format(DateTimeFormatter.ofPattern("EEE d MMM", Dates.fr))} · mood ${app.murmure.core.Text.d1(v)} ${moodFaces[(kotlin.math.round(v).toInt() - 1).coerceIn(0, 4)]}",
                    style = MaterialTheme.typography.labelLarge, color = M.Text,
                )
            }
        }
        Canvas(
            Modifier.fillMaxWidth().height(170.dp).pointerInput(series) {
                detectTapGestures { tap ->
                    val left = 34.dp.toPx(); val w = size.width - left - 8.dp.toPx()
                    val step = w / (series.size - 1).coerceAtLeast(1)
                    val i = ((tap.x - left) / step).let { kotlin.math.round(it).toInt() }.coerceIn(0, series.lastIndex)
                    // point avec donnée le plus proche
                    val best = series.indices.filter { series[it].second != null }.minByOrNull { abs(it - i) }
                    picked = best
                }
            }
        ) {
            val left = 34.dp.toPx(); val right = 8.dp.toPx(); val top = 8.dp.toPx(); val bottom = 20.dp.toPx()
            val w = size.width - left - right; val h = size.height - top - bottom
            fun y(v: Float) = top + h * (1f - (v - 1f) / 4f)
            fun x(i: Int) = left + w * i / (series.size - 1).coerceAtLeast(1)
            // grille horizontale 1..5
            for (m in 1..5) {
                drawLine(if (m == 3) Viz.Axis else Viz.Grid, Offset(left, y(m.toFloat())), Offset(left + w, y(m.toFloat())), 1.dp.toPx())
            }
            listOf(1, 3, 5).forEach { m ->
                val l = measurer.measure(moodFaces[m - 1], TextStyle(fontSize = 12.sp))
                drawText(l, topLeft = Offset(0f, y(m.toFloat()) - l.size.height / 2f))
            }
            // axe temps : 3 repères
            listOf(0, series.size / 2, series.lastIndex).forEach { i ->
                val l = measurer.measure(series[i].first.format(DateTimeFormatter.ofPattern("d MMM", Dates.fr)), axisStyle)
                drawText(l, topLeft = Offset((x(i) - l.size.width / 2f).coerceIn(left, size.width - l.size.width), size.height - l.size.height))
            }
            // ligne : segments seulement entre jours consécutifs renseignés
            val path = Path()
            var penDown = false
            series.forEachIndexed { i, (_, v) ->
                if (v == null) { penDown = false; return@forEachIndexed }
                if (!penDown) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v))
                penDown = true
            }
            drawPath(path, Viz.Violet, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            series.forEachIndexed { i, (_, v) ->
                if (v == null) return@forEachIndexed
                val c = Offset(x(i), y(v))
                val sel = i == picked
                if (sel) drawLine(Viz.Axis, Offset(c.x, top), Offset(c.x, top + h), 1.dp.toPx())
                drawCircle(M.Surface, (if (sel) 7 else 5).dp.toPx(), c)
                drawCircle(Viz.Violet, (if (sel) 5 else 4).dp.toPx(), c)
            }
        }
    }
}

/** Barre divergente centrée sur zéro (écart d'humeur), ±[range]. */
@Composable
fun DivergingBar(value: Float, range: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.height(12.dp)) {
        val mid = size.width / 2
        val len = (abs(value) / range).coerceIn(0f, 1f) * (size.width / 2)
        val col = if (value >= 0) Viz.Positive else Viz.Negative
        drawRoundRect(Viz.Grid, Offset(0f, size.height / 2 - 1), Size(size.width, 2f))
        val x0 = if (value >= 0) mid else mid - len
        drawRoundRect(col, Offset(x0, 0f), Size(len.coerceAtLeast(3f), size.height), CornerRadius(4.dp.toPx()))
        drawLine(Viz.Axis, Offset(mid, -3f), Offset(mid, size.height + 3f), 2f)
    }
}

/** Barre de magnitude (une seule teinte), extrémité arrondie ancrée à la base. */
@Composable
fun MagnitudeBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Viz.Violet) {
    Box(modifier.height(10.dp).clip(RoundedCornerShape(4.dp)).background(Viz.Grid)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(10.dp).clip(RoundedCornerShape(4.dp)).background(color))
    }
}

/** Point sur une échelle 1..5 (dot plot). */
@Composable
fun ScaleDot(value: Float, min: Float, max: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.height(14.dp)) {
        val y = size.height / 2
        drawLine(Viz.Grid, Offset(0f, y), Offset(size.width, y), 2.dp.toPx(), StrokeCap.Round)
        val t = ((value - min) / (max - min)).coerceIn(0f, 1f)
        drawCircle(M.Surface, 7.dp.toPx(), Offset(size.width * t, y))
        drawCircle(color, 5.dp.toPx(), Offset(size.width * t, y))
    }
}

@Composable
fun LabeledRow(label: String, trailing: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = M.Text, modifier = Modifier.width(118.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Box(Modifier.weight(1f)) { content() }
        Spacer(Modifier.width(10.dp))
        Text(trailing, style = MaterialTheme.typography.labelMedium, color = M.Muted, modifier = Modifier.width(52.dp))
    }
}
