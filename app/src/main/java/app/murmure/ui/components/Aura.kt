package app.murmure.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.murmure.ui.theme.M
import app.murmure.ui.theme.Viz
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fond vivant plein écran : trois nappes de lumière qui dérivent lentement,
 * se gonflent avec la voix et prennent la teinte de l'émotion détectée.
 */
@Composable
fun Aura(level: Float, valence: Float, energy: String, listening: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "aura")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(18_000, easing = LinearEasing)), label = "d")
    val lvl by animateFloatAsState(level, tween(120), label = "l")
    val tint by animateColorAsState(
        when {
            valence > 0.25f -> androidx.compose.ui.graphics.lerp(M.Lilac, M.Mint, (valence - 0.25f) / 0.75f)
            valence < -0.25f -> androidx.compose.ui.graphics.lerp(M.Lilac, M.Coral, (-valence - 0.25f) / 0.75f)
            else -> M.Lilac
        }, tween(1200), label = "tint",
    )
    val warm by animateColorAsState(if (valence >= 0) M.Peach else Color(0xFFB88CFF), tween(1200), label = "warm")
    val energyK = when (energy) { "haute" -> 1.25f; "basse" -> 0.8f; else -> 1f }
    val alpha by animateFloatAsState(if (listening) 1f else 0.5f, tween(600), label = "a")

    Canvas(modifier) {
        val w = size.width; val h = size.height
        val a = drift * 2f * Math.PI.toFloat()
        val boost = 1f + lvl * 0.55f * energyK
        fun blob(color: Color, cx: Float, cy: Float, r: Float, al: Float) {
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = al * alpha), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
        }
        blob(tint, w * (0.25f + 0.1f * sin(a)), h * (0.18f + 0.06f * cos(a * 1.3f)), w * 0.75f * boost, 0.26f)
        blob(warm, w * (0.85f + 0.08f * cos(a * 0.7f)), h * (0.45f + 0.1f * sin(a * 0.9f)), w * 0.6f * boost, 0.18f)
        blob(Viz.Positive.copy(alpha = 1f), w * (0.4f + 0.15f * sin(a * 0.5f + 1f)), h * (0.92f), w * 0.7f * (1f + lvl * 0.3f), 0.10f)
        // voile d'encre pour garder le texte lisible
        drawRect(Brush.verticalGradient(listOf(M.Ink.copy(alpha = 0.35f), M.Ink.copy(alpha = 0.55f), M.Ink.copy(alpha = 0.35f))))
    }
}
