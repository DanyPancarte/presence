package app.murmure.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.murmure.ui.theme.M
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Le vocabulaire visuel unique de Brainmeat : une matrice de points.
 * Tout — voix, mood, entourage, aura — est dessiné avec le même champ.
 */

/** Inclinaison du téléphone (gyroscope / gravité), lissée, en [-1, 1] sur x et y. */
@Composable
fun rememberTilt(): State<Offset> {
    val context = LocalContext.current
    val tilt = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(context) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val x = (-e.values[0] / 9.81f).coerceIn(-1f, 1f)
                val y = ((e.values[1] / 9.81f) - 0.6f).coerceIn(-1f, 1f)
                val cur = tilt.value
                tilt.value = Offset(cur.x + (x - cur.x) * 0.08f, cur.y + (y - cur.y) * 0.08f)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensor != null && sm != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm?.unregisterListener(listener) }
    }
    return tilt
}

/** Dessine une grille de points ; [intensity] renvoie 0..1 pour (u, v) normalisés dans [-1, 1]. */
fun DrawScope.dotGrid(
    pitch: Float,
    color: Color,
    accent: Color = M.Copper,
    parallax: Offset = Offset.Zero,
    dotMax: Float = pitch * 0.42f,
    intensity: (u: Float, v: Float) -> Float,
    accentAt: ((u: Float, v: Float) -> Float)? = null,
) {
    val cols = (size.width / pitch).toInt() + 2
    val rows = (size.height / pitch).toInt() + 2
    val ox = (size.width - (cols - 1) * pitch) / 2f + parallax.x
    val oy = (size.height - (rows - 1) * pitch) / 2f + parallax.y
    for (j in 0 until rows) for (i in 0 until cols) {
        val x = ox + i * pitch; val y = oy + j * pitch
        val u = (x / size.width) * 2f - 1f; val v = (y / size.height) * 2f - 1f
        val a = intensity(u, v).coerceIn(0f, 1f)
        if (a < 0.02f) continue
        val acc = accentAt?.invoke(u, v)?.coerceIn(0f, 1f) ?: 0f
        val c = if (acc > 0f) androidx.compose.ui.graphics.lerp(color, accent, acc) else color
        drawCircle(c.copy(alpha = 0.08f + a * 0.92f), dotMax * (0.35f + 0.65f * a), Offset(x, y))
    }
}

/**
 * L'orbe de Brainmeat : un disque de points qui respire, dont les anneaux s'écartent avec la voix.
 * [listening] : armé (écoute passive) — l'anneau extérieur bat au cuivre.
 */
@Composable
fun DotOrb(size: Dp, level: Float, listening: Boolean, modifier: Modifier = Modifier, pitch: Dp = 9.dp) {
    val t = rememberInfiniteTransition(label = "orb")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "p")
    val tilt by rememberTilt()
    Canvas(modifier.then(Modifier.size(size))) {
        val p = pitch.toPx()
        val wave = phase * 2f * Math.PI.toFloat()
        val lvl = level.coerceIn(0f, 1f)
        dotGrid(
            pitch = p, color = M.Text, parallax = Offset(tilt.x * p * 0.6f, tilt.y * p * 0.6f),
            intensity = { u, v ->
                val r = sqrt(u * u + v * v)
                if (r > 0.98f) 0f else {
                    val breath = 0.5f + 0.5f * sin(wave - r * 4f)
                    val core = exp(-r * r * 3.2f)
                    val ring = exp(-((r - (0.55f + lvl * 0.3f)) * (r - (0.55f + lvl * 0.3f))) / 0.012f) * (0.3f + lvl)
                    (core * (0.55f + 0.25f * breath) + ring * 0.9f + (1f - r) * 0.05f).coerceIn(0f, 1f)
                }
            },
            accentAt = { u, v ->
                val r = sqrt(u * u + v * v)
                val ring = exp(-((r - (0.55f + lvl * 0.3f)) * (r - (0.55f + lvl * 0.3f))) / 0.012f)
                if (listening) (ring * (0.5f + lvl)).coerceIn(0f, 1f) else lvl * ring
            },
        )
    }
}

/**
 * Champ de fond plein écran : l'aura de la dictée. Se densifie avec la voix,
 * se réchauffe (cuivre) avec la polarité positive, s'assombrit vers le corail avec la négative.
 */
@Composable
fun DotField(level: Float, valence: Float, weight: Float, listening: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "field")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "d")
    val tilt by rememberTilt()
    Canvas(modifier) {
        val p = 18.dp.toPx()
        val a = drift * 2f * Math.PI.toFloat()
        val lvl = level.coerceIn(0f, 1f)
        val cx = 0.3f * sin(a) - 0.2f; val cy = 0.55f + 0.15f * cos(a * 0.7f)
        val accent = if (valence >= 0) M.Copper else M.Coral
        dotGrid(
            pitch = p, color = M.Text, accent = accent, dotMax = p * 0.34f,
            parallax = Offset(tilt.x * p * 1.2f, tilt.y * p * 1.2f),
            intensity = { u, v ->
                val d = sqrt((u - cx) * (u - cx) + (v - cy) * (v - cy))
                val cloud = exp(-d * d * (2.2f - weight * 0.6f)) * (0.18f + lvl * 0.45f)
                val grain = 0.02f + 0.015f * sin(u * 9f + a) * cos(v * 7f - a)
                ((cloud + grain) * (if (listening) 1f else 0.5f)).coerceIn(0f, 0.4f)
            },
            accentAt = { u, v ->
                val d = sqrt((u - cx) * (u - cx) + (v - cy) * (v - cy))
                (abs(valence) * exp(-d * d * 2.2f) * (0.6f + lvl * 0.6f)).coerceIn(0f, 1f)
            },
        )
    }
}

/** Onde vocale en points : colonnes dont la hauteur suit l'historique du niveau. */
@Composable
fun DotWave(levels: List<Float>, modifier: Modifier = Modifier, accent: Color = M.Copper) {
    Canvas(modifier) {
        val p = 6.dp.toPx()
        val cols = levels.size
        val step = size.width / cols
        val rows = (size.height / p).toInt().coerceAtLeast(3)
        levels.forEachIndexed { i, l ->
            val h = (rows * (0.12f + l.coerceIn(0f, 1f) * 0.88f)).toInt().coerceAtLeast(1)
            val x = i * step + step / 2
            val fade = 0.25f + 0.75f * (i.toFloat() / cols)
            for (k in 0 until h) {
                val y = center.y + (k - (h - 1) / 2f) * p
                val edge = if (k == 0 || k == h - 1) 1f else 0.55f
                val c = if (k == h - 1 && l > 0.5f) accent else M.Text
                drawCircle(c.copy(alpha = fade * edge), p * 0.28f, Offset(x, y))
            }
        }
    }
}

/** Jauge circulaire en points : [fraction] 0..1 allumé au cuivre. */
@Composable
fun DotRing(fraction: Float, size: Dp, modifier: Modifier = Modifier, dots: Int = 48, accent: Color = M.Copper) {
    Canvas(modifier.then(Modifier.size(size))) {
        val r = this.size.minDimension / 2 - 4.dp.toPx()
        val lit = (dots * fraction.coerceIn(0f, 1f)).toInt()
        for (i in 0 until dots) {
            val a = -Math.PI.toFloat() / 2 + i * 2f * Math.PI.toFloat() / dots
            val on = i < lit
            drawCircle(if (on) accent else M.Line, if (on) 2.4.dp.toPx() else 1.6.dp.toPx(), Offset(center.x + cos(a) * r, center.y + sin(a) * r))
        }
    }
}

/** Série temporelle en points : valeurs 0..1 (null = trou). */
@Composable
fun DotSeries(values: List<Float?>, modifier: Modifier = Modifier, rows: Int = 9, accentLast: Boolean = true, picked: Int? = null) {
    Canvas(modifier) {
        val cols = values.size.coerceAtLeast(1)
        val step = size.width / cols
        val p = size.height / rows
        values.forEachIndexed { i, v ->
            val x = i * step + step / 2
            for (k in 0 until rows) {
                val y = size.height - p * (k + 0.5f)
                val base = M.Line.copy(alpha = 0.6f)
                if (v == null) { drawCircle(base, p * 0.14f, Offset(x, y)); continue }
                val lvl = (v.coerceIn(0f, 1f) * (rows - 1)).let { kotlin.math.round(it).toInt() }
                val on = k == lvl
                val under = k < lvl
                val c = when {
                    on && (i == picked || (accentLast && i == values.lastIndex && picked == null)) -> M.Copper
                    on -> M.Text
                    under -> M.Text.copy(alpha = 0.18f)
                    else -> base
                }
                drawCircle(c, if (on) p * 0.34f else p * 0.14f, Offset(x, y))
            }
        }
    }
}

/** Barre horizontale en points, centrée (divergente) ou depuis la gauche. */
@Composable
fun DotBar(value: Float, modifier: Modifier = Modifier, diverging: Boolean = false, dots: Int = 24) {
    Canvas(modifier) {
        val step = size.width / dots
        val v = value.coerceIn(-1f, 1f)
        for (i in 0 until dots) {
            val x = i * step + step / 2
            val on = if (diverging) {
                val mid = dots / 2
                if (v >= 0) i >= mid && i < mid + (v * mid).toInt() else i < mid && i >= mid - (-v * mid).toInt()
            } else i < (v * dots).toInt()
            val c = if (!on) M.Line else if (diverging && v < 0) M.Coral else if (diverging) M.Mint else M.Copper
            drawCircle(c, if (on) step * 0.32f else step * 0.14f, Offset(x, center.y))
        }
        if (diverging) drawCircle(M.Muted, step * 0.16f, Offset(size.width / 2, center.y))
    }
}
