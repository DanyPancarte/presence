package com.dany.presence

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import com.dany.presence.audio.AudioReactor
import com.dany.presence.brain.Conversation
import com.dany.presence.voice.Speech
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.PointerEventType
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
 * Zero chrome: the sphere is the interface.
 *  - tap              → parle-lui (ÉCOUTE → RÉFLEXION → RÉPONSE) ; re-tap = annule
 *  - appui long       → réglage caché (clé API Gemini)
 *  - swipe horizontal → comportement de l'organisme (validation du rendu)
 * Le swipe vertical est réservé aux modules (étape 5).
 */
class MainActivity : ComponentActivity() {
    private val sphereState = SphereState()
    private lateinit var sphereView: SphereView
    private lateinit var audio: AudioReactor
    private lateinit var speech: Speech
    private lateinit var convo: Conversation
    private val lineState = mutableStateOf("")
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) audio.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        sphereView = SphereView(this, sphereState)
        audio = AudioReactor(sphereState) { sphereView.renderer.clock }
        speech = Speech(this)
        convo = Conversation(this, sphereState, speech) { lineState.value = it }
        convo.onListening = { listening -> if (listening) audio.stop() else if (hasMic()) audio.start() }

        setContent {
            var line by lineState
            var showSettings by remember { mutableStateOf(false) }
            var styleIdx by remember { mutableStateOf(0) }
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // Every finger on the glass pushes the organism, live.
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val p = ev.changes.firstOrNull() ?: continue
                                if (ev.type == PointerEventType.Release) continue
                                val (vx, vy) = sphereView.renderer.touchToView(p.position.x, p.position.y)
                                sphereState.touchX = vx; sphereState.touchY = vy; sphereState.touch = 1f
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { convo.toggle() },
                            onLongPress = { showSettings = true },
                        )
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
                if (showSettings) SettingsDialog(
                    key = convo.gemini.apiKey, model = convo.gemini.model,
                    onSave = { k, m -> convo.gemini.apiKey = k; convo.gemini.model = m; showSettings = false },
                    onDismiss = { showSettings = false },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sphereView.onResume()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) audio.start()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() { speech.release(); super.onDestroy() }

    override fun onPause() {
        convo.cancel()
        audio.stop()
        sphereView.onPause()
        super.onPause()
    }
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

/** Hidden settings (long press): the Gemini key never leaves the phone. */
@Composable
fun SettingsDialog(key: String, model: String, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var k by remember { mutableStateOf(key) }
    var m by remember { mutableStateOf(model) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(k, m) }) { Text("Garder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        title = { Text("Cerveau") },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(value = k, onValueChange = { k = it }, label = { Text("Clé API Gemini") }, singleLine = true)
                OutlinedTextField(value = m, onValueChange = { m = it }, label = { Text("Modèle") }, singleLine = true)
            }
        },
    )
}
