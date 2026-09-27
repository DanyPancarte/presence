package app.murmure.ui.explore

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import app.murmure.ui.theme.M
import app.murmure.ui.theme.Manrope
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private class Sim(val n: Int) {
    val x = FloatArray(n); val y = FloatArray(n)
    val vx = FloatArray(n); val vy = FloatArray(n)
}

/** Graphe à forces : nodes dimensionnés par fréquence, pan / zoom / tap. */
@Composable
fun GraphView(
    model: GraphModel,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var tick by remember { mutableIntStateOf(0) }
    val previous = remember { HashMap<String, Offset>() }

    val radius = remember(model) { FloatArray(model.nodes.size) { i -> min(8f + sqrt(model.nodes[i].weight) * 6f, 40f) } }
    val sim = remember(model) {
        Sim(model.nodes.size).also { s ->
            model.nodes.forEachIndexed { i, node ->
                val p = previous[node.id]
                if (p != null) { s.x[i] = p.x; s.y[i] = p.y }
                else {
                    val h = node.id.hashCode()
                    val a = (h % 360) * (Math.PI / 180).toFloat()
                    val r = 60f + (h ushr 8) % 240
                    s.x[i] = cos(a) * r; s.y[i] = sin(a) * r
                }
            }
        }
    }
    val labels = remember(model) {
        HashMap<Int, TextLayoutResult>().also { map ->
            model.nodes.forEachIndexed { i, node ->
                map[i] = measurer.measure(
                    node.label,
                    TextStyle(fontFamily = Manrope, fontWeight = if (node.kind == "folder") FontWeight.Bold else FontWeight.SemiBold, fontSize = if (node.weight > 4) 13.sp else 11.sp, color = M.Text),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = 420),
                )
            }
        }
    }

    LaunchedEffect(model) {
        var alpha = 1f
        val n = model.nodes.size
        while (alpha > 0.004f) {
            withFrameNanos { }
            repeat(2) {
                for (i in 0 until n) {
                    var fx = -sim.x[i] * 0.010f
                    var fy = -sim.y[i] * 0.010f
                    for (j in 0 until n) {
                        if (i == j) continue
                        val dx = sim.x[i] - sim.x[j]; val dy = sim.y[i] - sim.y[j]
                        val d2 = max(dx * dx + dy * dy, 25f)
                        val rep = 2600f * (1f + (radius[i] + radius[j]) / 30f) / d2
                        val d = sqrt(d2)
                        fx += dx / d * rep * 10f; fy += dy / d * rep * 10f
                    }
                    sim.vx[i] = (sim.vx[i] + fx * alpha) * 0.55f
                    sim.vy[i] = (sim.vy[i] + fy * alpha) * 0.55f
                }
                model.edges.forEach { e ->
                    val dx = sim.x[e.b] - sim.x[e.a]; val dy = sim.y[e.b] - sim.y[e.a]
                    val d = max(sqrt(dx * dx + dy * dy), 1f)
                    val target = 70f + radius[e.a] + radius[e.b]
                    val k = (d - target) * 0.035f * min(e.w, 3f) * alpha
                    val ux = dx / d * k; val uy = dy / d * k
                    sim.vx[e.a] += ux; sim.vy[e.a] += uy
                    sim.vx[e.b] -= ux; sim.vy[e.b] -= uy
                }
                for (i in 0 until n) { sim.x[i] += sim.vx[i].coerceIn(-30f, 30f); sim.y[i] += sim.vy[i].coerceIn(-30f, 30f) }
                alpha *= 0.985f
            }
            model.nodes.forEachIndexed { i, node -> previous[node.id] = Offset(sim.x[i], sim.y[i]) }
            tick++
        }
    }

    Canvas(
        modifier
            .pointerInput(model) {
                detectTransformGestures { _, p, z, _ ->
                    zoom = (zoom * z).coerceIn(0.3f, 4f)
                    pan += p
                }
            }
            .pointerInput(model) {
                detectTapGestures { tap ->
                    val c = Offset(size.width / 2f, size.height / 2f) + pan
                    var best: Int? = null
                    var bestD = Float.MAX_VALUE
                    for (i in model.nodes.indices) {
                        val sx = c.x + sim.x[i] * zoom; val sy = c.y + sim.y[i] * zoom
                        val d = sqrt((sx - tap.x) * (sx - tap.x) + (sy - tap.y) * (sy - tap.y))
                        if (d < radius[i] * zoom + 22f && d < bestD) { best = i; bestD = d }
                    }
                    onSelect(best)
                }
            }
    ) {
        @Suppress("UNUSED_EXPRESSION") tick
        val c = center + pan
        val focus = selected?.let { model.neighbors[it] + it }
        val placed = ArrayList<androidx.compose.ui.geometry.Rect>()
        translate(c.x, c.y) {
            // arêtes
            model.edges.forEach { e ->
                val on = focus == null || (e.a in focus && e.b in focus && (e.a == selected || e.b == selected))
                val col = if (on && focus != null) model.nodes[selected!!].color else M.Text
                drawLine(
                    col.copy(alpha = if (focus == null) (0.10f + 0.05f * min(e.w, 4f)) else if (on) 0.6f else 0.03f),
                    Offset(sim.x[e.a] * zoom, sim.y[e.a] * zoom), Offset(sim.x[e.b] * zoom, sim.y[e.b] * zoom),
                    strokeWidth = (1f + min(e.w, 4f) * 0.5f) * zoom.coerceIn(0.6f, 1.6f),
                )
            }
            // nodes (les plus lourds en dernier pour qu'ils soient au-dessus)
            val order = model.nodes.indices.sortedBy { model.nodes[it].weight }
            order.forEach { i ->
                val node = model.nodes[i]
                val dim = focus != null && i !in focus
                val p = Offset(sim.x[i] * zoom, sim.y[i] * zoom)
                val r = radius[i] * zoom
                val a = if (dim) 0.18f else 1f
                if (node.kind != "folder") drawCircle(Brush.radialGradient(listOf(node.color.copy(alpha = 0.30f * a), Color.Transparent), p, r * 2.2f), r * 2.2f, p)
                when (node.kind) {
                    "note" -> {
                        drawCircle(M.Ink, r, p)
                        drawCircle(node.color.copy(alpha = a), r, p, style = Stroke(2.2f * zoom.coerceIn(0.7f, 1.5f)))
                    }
                    "folder" -> {
                        val tl = Offset(p.x - r, p.y - r)
                        val sz = androidx.compose.ui.geometry.Size(r * 2, r * 2)
                        val cr = androidx.compose.ui.geometry.CornerRadius(r * 0.35f)
                        drawRoundRect(M.Surface2.copy(alpha = a), tl, sz, cr)
                        drawRoundRect(M.Text.copy(alpha = 0.75f * a), tl, sz, cr, style = Stroke(2f))
                    }
                    else -> drawCircle(node.color.copy(alpha = 0.92f * a), r, p)
                }
                if (i == selected) drawCircle(M.Text, r + 6f, p, style = Stroke(2f))
            }
            // étiquettes : priorité au sélectionné puis aux plus fréquents, sans chevauchement
            val labelOrder = model.nodes.indices.sortedWith(compareByDescending<Int> { it == selected }.thenByDescending { model.nodes[it].weight })
            labelOrder.forEach { i ->
                val node = model.nodes[i]
                val show = i == selected || (focus != null && i in focus) || (focus == null && (node.weight >= 2f || zoom > 1.4f || model.nodes.size < 25))
                if (!show) return@forEach
                val l = labels[i] ?: return@forEach
                val p = Offset(sim.x[i] * zoom, sim.y[i] * zoom)
                val topLeft = Offset(p.x - l.size.width / 2f, p.y + radius[i] * zoom + 5f)
                val rect = androidx.compose.ui.geometry.Rect(topLeft, androidx.compose.ui.geometry.Size(l.size.width.toFloat(), l.size.height.toFloat()))
                if (i != selected && placed.any { it.overlaps(rect) }) return@forEach
                placed += rect
                val dim = focus != null && i !in focus
                drawRoundRect(M.Ink.copy(alpha = 0.55f), rect.topLeft - Offset(4f, 1f), androidx.compose.ui.geometry.Size(rect.width + 8f, rect.height + 2f), androidx.compose.ui.geometry.CornerRadius(8f))
                drawText(l, topLeft = topLeft, alpha = if (dim) 0.25f else 0.95f)
            }
        }
    }
}
