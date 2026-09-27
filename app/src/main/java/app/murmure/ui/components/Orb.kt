package app.murmure.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.murmure.ui.theme.M
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Le grand bouton de dictée : une sphère qui respire. */
@Composable
fun Orb(size: Dp = 220.dp, level: Float = 0f, listening: Boolean = false, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "orb")
    val interaction = remember { MutableInteractionSource() }
    val view = androidx.compose.ui.platform.LocalView.current
    val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "b")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "s")
    Box(
        Modifier.size(size).pressScale(interaction, 0.94f).clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null) { Feedback.tap(view); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            val c = center
            // halo
            drawCircle(
                Brush.radialGradient(listOf(M.Lilac.copy(alpha = 0.28f + level * 0.2f), Color.Transparent), c, r),
                r, c,
            )
            // blob organique
            val core = r * (0.58f + breath * 0.04f + level * 0.08f)
            rotate(spin, c) {
                val path = androidx.compose.ui.graphics.Path()
                val n = 64
                for (i in 0..n) {
                    val a = (i.toFloat() / n) * 2f * PI.toFloat()
                    val wob = 1f + 0.035f * sin(a * 3 + breath * 6f) + 0.025f * cos(a * 5 - breath * 4f) + level * 0.05f * sin(a * 7)
                    val p = Offset(c.x + cos(a) * core * wob, c.y + sin(a) * core * wob)
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close()
                drawPath(path, Brush.linearGradient(listOf(M.Peach, Color(0xFFE9B4F0), M.Lilac), Offset(c.x - core, c.y - core), Offset(c.x + core, c.y + core)))
            }
            // anneaux : ils s'écartent avec la voix quand l'écoute passive est armée
            val spread = if (listening) level * 0.25f else 0f
            drawCircle((if (listening) M.Peach else M.Text).copy(alpha = 0.10f + spread * 0.6f), core * (1.22f + spread), c, style = Stroke(1.2f.dp.toPx()))
            drawCircle((if (listening) M.Peach else M.Text).copy(alpha = 0.05f + spread * 0.4f), core * (1.42f + spread * 1.6f), c, style = Stroke(1f.dp.toPx()))
        }
        Icon(Icons.Rounded.Mic, "Parler", tint = M.Ink, modifier = Modifier.size(size * 0.2f))
    }
}

/** Visualiseur d'onde : barres arrondies nourries par l'historique du niveau. */
@Composable
fun Waveform(levels: List<Float>, modifier: Modifier = Modifier, color: Color = M.Lilac) {
    Canvas(modifier) {
        val n = levels.size.coerceAtLeast(1)
        val gap = size.width / n
        val w = gap * 0.55f
        levels.forEachIndexed { i, l ->
            val h = (size.height * (0.08f + l.coerceIn(0f, 1f) * 0.92f))
            val x = i * gap + gap / 2
            val alpha = 0.35f + 0.65f * (i.toFloat() / n)
            drawLine(color.copy(alpha = alpha), Offset(x, center.y - h / 2), Offset(x, center.y + h / 2), w, androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}
