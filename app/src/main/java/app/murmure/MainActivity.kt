package app.murmure

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import app.murmure.ui.MurmureRoot
import app.murmure.ui.theme.M
import app.murmure.ui.theme.MurmureTheme
import androidx.compose.ui.graphics.toArgb

class MainActivity : ComponentActivity() {
    private val openRitual = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Murmure)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(M.Ink.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(M.Ink.toArgb()),
        )
        openRitual.value = intent?.getBooleanExtra(EXTRA_RITUAL, false) == true
        setContent {
            MurmureTheme {
                MurmureRoot(openRitual = openRitual.value, onRitualHandled = { openRitual.value = false })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_RITUAL, false)) openRitual.value = true
    }

    companion object {
        const val EXTRA_RITUAL = "ritual"
    }
}
