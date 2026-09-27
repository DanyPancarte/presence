package com.dany.presence.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dany.presence.core.AppSat
import com.dany.presence.core.World
import com.dany.presence.hud.HudTheme
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The apps as a constellation: bone dots on nested orbits around the screen centre, each with a tiny
 * label along its orbit. Order is World.apps (alphabetical), starting at the top of the outer orbit and
 * going clockwise, then inward. Tap a star → [onLaunch]; tap empty space or swipe down → [onDismiss].
 * Enters with a fade and a slight scale from the centre over 500 ms. No icons: labels are enough.
 */
@Composable
fun AppDrawer(world: StateFlow<World>, visible: Boolean, onLaunch: (String) -> Unit, onDismiss: () -> Unit) {
    val w by world.collectAsState()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(500)) + scaleIn(tween(500), initialScale = 0.9f),
        exit = fadeOut(tween(300)) + scaleOut(tween(300), targetScale = 0.96f),
    ) {
        Constellation(w.apps, onLaunch, onDismiss)
    }
}

// ---- geometry ----------------------------------------------------------------------------------

private class Star(
    val packageName: String, val label: String,
    val x: Float, val y: Float,        // the dot, on the orbit (px)
    val lx: Float, val ly: Float,      // the label centre, just inside the orbit (px)
    val rot: Float,                    // label rotation along the orbit (degrees, never upside down)
)

private class Sky(val stars: List<Star>, val orbits: List<Pair<Float, Float>>, val cx: Float, val cy: Float) {
    /** The star whose dot or label is within [r] px of [p], nearest first. */
    fun nearest(p: Offset, r: Float): Star? = stars
        .map { s -> s to minOf(hypot(p.x - s.x, p.y - s.y), hypot(p.x - s.lx, p.y - s.ly)) }
        .filter { it.second <= r }
        .minByOrNull { it.second }?.first
}

/** Ramanujan's perimeter of an ellipse with semi-axes a, b. */
private fun perimeter(a: Float, b: Float): Float {
    val h = ((a - b) * (a - b)) / ((a + b) * (a + b))
    return (PI * (a + b) * (1 + 3 * h / (10 + sqrt(4 - 3 * h)))).toFloat()
}

/** Cumulative arc length along the ellipse, sampled at [n] parameter steps from t = -π/2 (the top). */
private fun arcTable(a: Float, b: Float, n: Int): FloatArray {
    val t = FloatArray(n + 1)
    var px = 0f; var py = -b
    for (i in 1..n) {
        val u = -PI.toFloat() / 2 + 2 * PI.toFloat() * i / n
        val x = a * cos(u); val y = b * sin(u)
        t[i] = t[i - 1] + hypot(x - px, y - py)
        px = x; py = y
    }
    return t
}

/** Places [apps] on nested orbits. Spacing tightens (and labels shorten) when many apps must fit. */
private fun placeSky(apps: List<AppSat>, w: Float, h: Float, density: Density): Sky {
    val dp = density.density
    val cx = w / 2f; val cy = h / 2f
    val a0 = cx - 26 * dp
    val b0 = cy - 64 * dp
    val pitch = 54 * dp
    data class Plan(val step: Float, val maxChars: Int)
    val plans = listOf(Plan(88 * dp, 10), Plan(72 * dp, 8), Plan(60 * dp, 7))

    fun rings(): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        var a = a0; var b = b0
        while (a >= 40 * dp && b >= 40 * dp) { out += a to b; a -= pitch; b -= pitch * 1.4f }
        return out
    }
    val orbits = rings()
    val plan = plans.firstOrNull { p -> orbits.sumOf { floor(perimeter(it.first, it.second) / p.step).toInt() } >= apps.size } ?: plans.last()

    val stars = ArrayList<Star>(apps.size)
    var i = 0
    for ((a, b) in orbits) {
        if (i >= apps.size) break
        val cap = floor(perimeter(a, b) / plan.step).toInt().coerceAtLeast(1)
        val n = minOf(cap, apps.size - i)
        val samples = 720
        val arc = arcTable(a, b, samples)
        val total = arc[samples]
        var k = 0
        for (j in 0 until n) {
            val target = total * j / n
            while (k < samples && arc[k + 1] < target) k++
            val u = -PI.toFloat() / 2 + 2 * PI.toFloat() * k / samples
            val x = cx + a * cos(u); val y = cy + b * sin(u)
            // tangent and outward normal at u
            val tx = -a * sin(u); val ty = b * cos(u)
            var rot = atan2(ty, tx)
            // never upside down; text "up" (sin rot, -cos rot) must point outward
            val nx = b * cos(u); val ny = a * sin(u)
            val nl = hypot(nx, ny).coerceAtLeast(1e-3f)
            if (sin(rot) * nx / nl - cos(rot) * ny / nl < 0) rot += PI.toFloat()
            val inset = 10 * dp
            val app = apps[i++]
            val label = app.label.trim().let { if (it.length > plan.maxChars) it.take(plan.maxChars - 1) + "…" else it }
            stars += Star(app.packageName, label.uppercase(), x, y, x - nx / nl * inset, y - ny / nl * inset, rot * 180f / PI.toFloat())
        }
    }
    return Sky(stars, orbits, cx, cy)
}

// ---- drawing -----------------------------------------------------------------------------------

@Composable
private fun Constellation(apps: List<AppSat>, onLaunch: (String) -> Unit, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().background(HudTheme.night.copy(alpha = 0.74f))) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val sky = remember(apps, wPx, hPx, density) { placeSky(apps, wPx, hPx, density) }
        val hitR = with(density) { 40.dp.toPx() }

        Canvas(Modifier.fillMaxSize()) {
            val hair = Stroke(1f)
            sky.orbits.forEach { (a, b) ->
                drawOval(HudTheme.ash.copy(alpha = 0.14f), Offset(sky.cx - a, sky.cy - b), Size(2 * a, 2 * b), style = hair)
            }
            val r = 2.5.dp.toPx()
            sky.stars.forEach { s ->
                drawCircle(HudTheme.gold.copy(alpha = 0.18f), r * 2.6f, Offset(s.x, s.y))
                drawCircle(HudTheme.bone, r, Offset(s.x, s.y))
            }
        }

        // Labels: one Layout, each text centred on its point and rotated along the orbit.
        val style = HudTheme.label(9.sp, color = HudTheme.bone.copy(alpha = 0.85f))
        Layout(content = { sky.stars.forEach { BasicText(it.label, style = style, maxLines = 1, overflow = TextOverflow.Clip) } }) { measurables, c ->
            val placeables = measurables.map { it.measure(Constraints()) }
            layout(c.maxWidth, c.maxHeight) {
                placeables.forEachIndexed { i, p ->
                    val s = sky.stars[i]
                    p.placeWithLayer((s.lx - p.width / 2f).roundToInt(), (s.ly - p.height / 2f).roundToInt()) { rotationZ = s.rot }
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(sky) {
                    detectTapGestures { p -> sky.nearest(p, hitR)?.let { onLaunch(it.packageName) } ?: onDismiss() }
                }
                .pointerInput(Unit) {
                    var dy = 0f
                    detectVerticalDragGestures(onDragStart = { dy = 0f }, onDragEnd = { if (dy > 120f) onDismiss() }) { _, d -> dy += d }
                },
        )
    }
}
