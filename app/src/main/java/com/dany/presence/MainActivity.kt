package com.dany.presence

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dany.presence.render.Mood
import com.dany.presence.render.SphereState
import com.dany.presence.render.SphereView
import com.dany.presence.render.Organism
import kotlin.math.abs

/**
 * Zero chrome: the sphere is the interface. Étape 2 (validation du rendu) — gestes de test :
 *  - tap           → cycle des états (VEILLE → ÉCOUTE → RÉFLEXION → RÉPONSE → ALERTE)
 *  - swipe horizontal → comportement de l'organisme (A/B/C)
 * Le swipe vertical est réservé aux modules (étape 5).
 */
class MainActivity : ComponentActivity() {
    private val sphereState = SphereState()
    private lateinit var sphereView: SphereView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        sphereView = SphereView(this, sphereState)

        setContent {
            var line by remember { mutableStateOf("") }
            var styleIdx by remember { mutableStateOf(0) }
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            val next = Mood.entries[(sphereState.mood.ordinal + 1) % Mood.entries.size]
                            sphereState.mood = next
                            line = next.name
                        })
                    }
                    .pointerInput(Unit) {
                        var dx = 0f
                        var dy = 0f
                        detectDragGestures(
                            onDragStart = { dx = 0f; dy = 0f },
                            onDragEnd = {
                                if (abs(dx) > abs(dy) && abs(dx) > 120f) {
                                    val n = Organism.entries.size
                                    styleIdx = (styleIdx + (if (dx < 0) 1 else n - 1)) % n
                                    val o = Organism.entries[styleIdx]
                                    sphereView.renderer.preset = o
                                    line = o.label
                                }
                            },
                        ) { _, drag -> dx += drag.x; dy += drag.y }
                    },
            ) {
                AndroidView(factory = { sphereView }, modifier = Modifier.fillMaxSize())
                BottomLine(line, Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    override fun onResume() { super.onResume(); sphereView.onResume() }
    override fun onPause() { sphereView.onPause(); super.onPause() }
}

private val Amber = Color(0xFFFFD9A0)

@androidx.compose.runtime.Composable
fun BottomLine(text: String, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(500)) },
        modifier = modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 56.dp),
        label = "line",
    ) { t ->
        Text(
            t,
            color = Amber.copy(alpha = 0.85f),
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Light,
                fontSize = 17.sp,
                letterSpacing = 1.2.sp,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
