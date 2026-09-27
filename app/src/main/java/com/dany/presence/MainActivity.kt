package com.dany.presence

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.dany.presence.core.Bus
import com.dany.presence.core.Signal
import com.dany.presence.core.WorldRepo
import com.dany.presence.ears.EarsSystem
import com.dany.presence.hud.Hud
import com.dany.presence.hud.SettingsDialog
import com.dany.presence.mind.MindSystem
import com.dany.presence.mind.Provider
import com.dany.presence.ritual.Ritual
import com.dany.presence.scene.SceneView
import com.dany.presence.shell.AppDrawer
import com.dany.presence.shell.BusHolder
import com.dany.presence.shell.Launcher
import com.dany.presence.shell.presenceGestures
import com.dany.presence.sound.SoundSystem

/**
 * Présence — the shell. One Bus, five systems, zero chrome (see ARCHITECTURE.md).
 *  - hands-free: the mic is open while the screen is on; talking is the interface
 *  - swipe up    → the constellation of apps (Présence is the home screen)
 *  - long press  → hidden settings (brain keys, sound, home)
 *  - finger      → pushes the scene
 */
class MainActivity : ComponentActivity() {
    private val bus = Bus()
    private lateinit var world: WorldRepo
    private lateinit var scene: SceneView
    private lateinit var ears: EarsSystem
    private lateinit var mind: MindSystem
    private lateinit var sound: SoundSystem
    private var pendingRitual: String? = null
    private var running = false

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) startSystems()
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

        BusHolder.bus = bus
        world = WorldRepo(this, lifecycleScope)
        scene = SceneView(this, bus, world.world, lifecycleScope)
        ears = EarsSystem(this, bus, lifecycleScope)
        mind = MindSystem(this, bus, lifecycleScope)
        sound = SoundSystem(this, bus, lifecycleScope)
        pendingRitual = intent?.getStringExtra(Ritual.EXTRA_MODE)

        setContent {
            var drawer by remember { mutableStateOf(false) }
            var settings by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .fillMaxSize()
                    .presenceGestures(bus, onSwipeUp = { drawer = true }, onLongPress = { settings = true }),
            ) {
                AndroidView(factory = { scene }, modifier = Modifier.fillMaxSize())
                Hud(bus, world.world, Modifier.fillMaxSize())
                AppDrawer(world.world, drawer, onLaunch = { Launcher.launch(this@MainActivity, it, bus); drawer = false }, onDismiss = { drawer = false })
                if (settings) {
                    val llm = mind.llm
                    SettingsDialog(
                        provider = llm.provider.name,
                        keys = Provider.entries.associate { it.name to llm.key(it) },
                        models = Provider.entries.associate { it.name to llm.model(it) },
                        onSave = { p, keys, models ->
                            runCatching { Provider.valueOf(p) }.getOrNull()?.let { llm.provider = it }
                            keys.forEach { (id, k) -> runCatching { Provider.valueOf(id) }.getOrNull()?.let { llm.setKey(it, k) } }
                            models.forEach { (id, m) -> runCatching { Provider.valueOf(id) }.getOrNull()?.let { llm.setModel(it, m) } }
                            settings = false
                        },
                        onDismiss = { settings = false },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val mode = intent.getStringExtra(Ritual.EXTRA_MODE) ?: return
        if (running) bus.emit(Signal.Ritual(mode)) else pendingRitual = mode
    }

    private fun startSystems() {
        if (running) return
        running = true
        mind.start()
        sound.start()
        ears.start()
        pendingRitual?.let { bus.emit(Signal.Ritual(it)); pendingRitual = null }
    }

    override fun onResume() {
        super.onResume()
        scene.onResume()
        val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startSystems() else perms.launch(missing.toTypedArray())
    }

    override fun onPause() {
        if (running) {
            running = false
            ears.stop()
            sound.stop()
            mind.stop()
        }
        scene.onPause()
        super.onPause()
    }
}
