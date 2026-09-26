package com.dany.presence

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dany.presence.audio.AudioReactor
import com.dany.presence.brain.Conversation
import com.dany.presence.brain.Scene
import com.dany.presence.data.Module
import com.dany.presence.data.Modules
import com.dany.presence.render.HoloState
import com.dany.presence.render.HoloView
import com.dany.presence.ritual.Ritual
import com.dany.presence.ui.Overlays
import com.dany.presence.voice.Speech
import kotlin.math.abs

/**
 * Zero chrome: the hologram is the interface.
 *  - tap              → parle-lui (ÉCOUTE → RÉFLEXION → RÉPONSE) ; re-tap = annule
 *  - swipe vertical   → aperçu du module suivant / précédent
 *  - doigt posé       → repousse les particules
 *  - appui long       → réglage caché (clé API Gemini)
 */
class MainActivity : ComponentActivity() {
    private val holo = HoloState()
    private lateinit var view: HoloView
    private lateinit var audio: AudioReactor
    private lateinit var speech: Speech
    private lateinit var modules: Modules
    private lateinit var convo: Conversation
    private val sceneState = mutableStateOf(Scene())
    private var pendingMode: String? = null

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) { audio.start(); pendingMode?.let { m -> pendingMode = null; convo.listen(Ritual.greeting(m)) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        view = HoloView(this, holo)
        audio = AudioReactor(holo)
        speech = Speech(this)
        modules = Modules(this)
        convo = Conversation(this, holo, speech, modules) { sceneState.value = it }
        convo.onListening = { listening -> if (listening) audio.stop() else if (hasMic()) audio.start() }
        Ritual.scheduleAll(this)
        pendingMode = intent?.getStringExtra(Ritual.EXTRA_MODE)

        setContent {
            val scene by sceneState
            var showSettings by remember { mutableStateOf(false) }
            var moduleIdx by remember { mutableStateOf(0) }
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val p = ev.changes.firstOrNull() ?: continue
                                if (ev.type == PointerEventType.Release) { holo.touchX = 99f; holo.touchY = 99f; continue }
                                val (bx, by) = view.renderer.touchToBox(p.position.x, p.position.y)
                                holo.touchX = bx; holo.touchY = by
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { convo.toggle() }, onLongPress = { showSettings = true })
                    }
                    .pointerInput(Unit) {
                        var dy = 0f; var dx = 0f
                        detectDragGestures(
                            onDragStart = { dx = 0f; dy = 0f },
                            onDragEnd = {
                                if (abs(dy) > abs(dx) && abs(dy) > 140f) {
                                    val mods = Module.entries.filter { it != Module.AUCUN }
                                    moduleIdx = (moduleIdx + (if (dy < 0) 1 else mods.size - 1)) % mods.size
                                    convo.peek(mods[moduleIdx])
                                }
                            },
                        ) { _, d -> dx += d.x; dy += d.y }
                    },
            ) {
                AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                Overlays(scene, holo, modules.dao, Modifier.fillMaxSize())
                if (showSettings) SettingsDialog(
                    key = convo.gemini.apiKey, model = convo.gemini.model,
                    onSave = { k, m -> convo.gemini.apiKey = k; convo.gemini.model = m; showSettings = false },
                    onDismiss = { showSettings = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Ritual.EXTRA_MODE)?.let { m -> if (hasMic()) convo.listen(Ritual.greeting(m)) else pendingMode = m }
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        view.onResume()
        val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            audio.start()
            pendingMode?.let { m -> pendingMode = null; convo.listen(Ritual.greeting(m)) }
        } else perms.launch(missing.toTypedArray())
    }

    override fun onPause() {
        convo.cancel()
        audio.stop()
        view.onPause()
        super.onPause()
    }

    override fun onDestroy() { speech.release(); super.onDestroy() }
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
            Column {
                OutlinedTextField(value = k, onValueChange = { k = it }, label = { Text("Clé API Gemini") }, singleLine = true)
                OutlinedTextField(value = m, onValueChange = { m = it }, label = { Text("Modèle") }, singleLine = true)
            }
        },
    )
}
