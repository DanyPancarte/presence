package com.dany.presence.hud

import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dany.presence.shell.Launcher

/** Provider ids (plain strings, match the mind package's enum names) and their labels. */
val PROVIDERS = listOf("GEMINI" to "Gemini", "CLAUDE" to "Claude")

private const val PREFS = "presence"
const val PREF_SOUND_ON = "sound_on"

/** Whether the synthetic sound is on (SharedPreferences "presence" / "sound_on", default true). */
fun soundOn(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_SOUND_ON, true)

/**
 * Hidden settings (long press). Dark, minimal, poster type. Keys never leave the phone.
 * The LLM settings are plain values: [provider] is one of the PROVIDERS ids; [keys]/[models] are keyed by id.
 * [onSave] receives the edited values; the "son" switch is written to SharedPreferences here.
 */
@Composable
fun SettingsDialog(
    provider: String,
    keys: Map<String, String>,
    models: Map<String, String>,
    onSave: (provider: String, keys: Map<String, String>, models: Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var prov by remember { mutableStateOf(provider) }
    var k by remember { mutableStateOf(keys) }
    var m by remember { mutableStateOf(models) }
    var sound by remember { mutableStateOf(soundOn(context)) }
    val isHome = remember { Launcher.isDefaultHome(context) }
    val notifOk = remember { Launcher.hasNotificationAccess(context) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(320.dp)
                .background(HudTheme.night.copy(alpha = 0.96f))
                .border(1.dp, HudTheme.ash.copy(alpha = 0.35f))
                .padding(horizontal = 22.dp, vertical = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tiny("réglages", HudTheme.bone, FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                Tiny("cerveau")
            }
            Spacer(Modifier.height(16.dp))

            PROVIDERS.forEach { (id, label) ->
                Row(
                    Modifier.fillMaxWidth().plain { prov = id }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Radio(prov == id)
                    Spacer(Modifier.width(10.dp))
                    Tiny(label, if (prov == id) HudTheme.bone else HudTheme.ash)
                }
                Field("clé", k[id].orEmpty(), secret = true) { k = k + (id to it) }
                Field("modèle", m[id].orEmpty()) { m = m + (id to it) }
                Spacer(Modifier.height(10.dp))
            }

            Hairline()
            Row(Modifier.fillMaxWidth().plain { sound = !sound }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Tiny("son", HudTheme.bone)
                Spacer(Modifier.weight(1f))
                Toggle(sound)
            }
            Hairline()

            Row(
                Modifier.fillMaxWidth().plain { Launcher.openHomeSettings(context) }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tiny("définir comme écran d'accueil", if (isHome) HudTheme.ash else HudTheme.gold)
                Spacer(Modifier.weight(1f))
                Tiny(if (isHome) "actif" else "—", HudTheme.ash)
            }
            Row(
                Modifier.fillMaxWidth().plain { Launcher.openNotificationAccess(context) }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tiny("accès aux notifications", if (notifOk) HudTheme.ash else HudTheme.gold)
                Spacer(Modifier.weight(1f))
                Tiny(if (notifOk) "actif" else "—", HudTheme.ash)
            }
            Hairline()

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(Modifier.plain(onDismiss).padding(8.dp)) { Tiny("annuler") }
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier.plain {
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_SOUND_ON, sound).apply()
                        onSave(prov, k, m)
                    }.padding(8.dp),
                ) { Tiny("garder", HudTheme.gold, FontWeight.Medium) }
            }
        }
    }
}

// ---- primitives -------------------------------------------------------------------------------

@Composable
private fun Tiny(text: String, color: Color = HudTheme.ash, weight: FontWeight = FontWeight.Normal) =
    BasicText(text.uppercase(), style = HudTheme.label(9.sp, weight, color))

@Composable
private fun Hairline() = Box(Modifier.fillMaxWidth().height(1.dp).background(HudTheme.ash.copy(alpha = 0.25f)))

/** Click without ripple: the dialog is a sheet of type, not a set of buttons. */
@Composable
private fun Modifier.plain(onClick: () -> Unit): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)

@Composable
private fun Radio(on: Boolean) = Box(
    Modifier.size(9.dp).border(1.dp, if (on) HudTheme.bone else HudTheme.ash, CircleShape),
    contentAlignment = Alignment.Center,
) { if (on) Box(Modifier.size(4.dp).background(HudTheme.bone, CircleShape)) }

@Composable
private fun Toggle(on: Boolean) {
    val x by animateDpAsState(if (on) 16.dp else 0.dp, label = "toggle")
    Box(Modifier.width(30.dp).height(14.dp).border(1.dp, if (on) HudTheme.gold else HudTheme.ash)) {
        Box(Modifier.padding(3.dp).offset(x = x).size(6.dp).background(if (on) HudTheme.gold else HudTheme.ash))
    }
}

/** Tiny label over a mono field with a hairline under it. */
@Composable
private fun Field(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Tiny(label)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = HudTheme.number(11.sp, HudTheme.bone),
            cursorBrush = SolidColor(HudTheme.gold),
            visualTransformation = if (secret) PasswordVisualTransformation('·') else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(HudTheme.ash.copy(alpha = 0.45f)))
    }
}
