package app.murmure.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.murmure.MurmureApp
import app.murmure.ui.capture.Wordmark
import app.murmure.ui.components.Banner
import app.murmure.ui.components.Orb
import app.murmure.ui.components.PrimaryButton
import app.murmure.ui.settings.ApiKeyField
import app.murmure.ui.settings.testAndConfigure
import app.murmure.ui.theme.M
import kotlinx.coroutines.launch

private data class Page(val emoji: String, val title: String, val lines: List<String>)

private val pages = listOf(
    Page("", "Parle.\nÇa s'organise.", listOf("Un carnet de notes entièrement vocal.", "Tu ouvres, tu parles : la note s'écrit, se structure et se classe.")),
    Page("⚡", "Pensé pour les cerveaux qui vont vite", listOf(
        "Zéro décision avant de parler : un seul gros bouton.",
        "Dis « une note pour le dossier X » : je comprends.",
        "Je propose le classement, les liens et les tâches. Tu confirmes d'un tap. Rien n'est imposé.",
    )),
    Page("🔒", "Privé par défaut", listOf(
        "Tes notes restent sur ton téléphone, dans une base chiffrée.",
        "Export et suppression totale en tout temps.",
        "Un miroir bienveillant, jamais un outil clinique.",
    )),
)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val app = MurmureApp.instance
    val context = LocalContext.current
    val pager = rememberPagerState { pages.size + 1 }
    val scope = rememberCoroutineScope()
    var micOk by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micOk = it }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var notifAsked by remember { mutableStateOf(false) }

    fun finish() {
        // Une clé collée mais pas vérifiée est quand même gardée (vérifiée à l'usage).
        app.settings.update { s -> if (key.isNotBlank() && s.apiKey.isBlank()) s.copy(apiKey = key.trim(), onboarded = true) else s.copy(onboarded = true) }
        onDone()
    }

    Column(Modifier.fillMaxSize().background(M.Ink)) {
        Row(Modifier.fillMaxWidth().padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Wordmark()
            Spacer(Modifier.weight(1f))
            if (pager.currentPage < pages.size) TextButton(onClick = { scope.launch { pager.animateScrollToPage(pages.size) } }) { Text("Passer", color = M.Muted) }
        }
        HorizontalPager(pager, Modifier.weight(1f)) { i ->
            if (i < pages.size) {
                val p = pages[i]
                Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), verticalArrangement = Arrangement.Center) {
                    if (i == 0) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Orb(200.dp) { scope.launch { pager.animateScrollToPage(1) } } }
                    else Text(p.emoji, fontSize = 56.sp)
                    Spacer(Modifier.height(28.dp))
                    Text(p.title, style = MaterialTheme.typography.displayMedium, color = M.Text)
                    Spacer(Modifier.height(16.dp))
                    p.lines.forEach {
                        Text(it, style = MaterialTheme.typography.bodyLarge, color = M.Muted, modifier = Modifier.padding(bottom = 10.dp))
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp)) {
                    Spacer(Modifier.height(20.dp))
                    Text("On se branche.", style = MaterialTheme.typography.displayMedium, color = M.Text)
                    Spacer(Modifier.height(24.dp))
                    Text("1 · Le micro", style = MaterialTheme.typography.titleLarge, color = M.Text)
                    Spacer(Modifier.height(8.dp))
                    if (micOk) Banner("Micro autorisé ✓", M.Mint)
                    else PrimaryButton("Autoriser le micro", color = M.Peach, leading = Icons.Rounded.Mic) { mic.launch(Manifest.permission.RECORD_AUDIO) }
                    Spacer(Modifier.height(26.dp))
                    Text("2 · Ta clé IA (Google Gemini)", style = MaterialTheme.typography.titleLarge, color = M.Text)
                    Text(
                        "Elle active la transcription en direct et toute l'intelligence. Gratuite sur aistudio.google.com/apikey. Tu peux aussi l'ajouter plus tard.",
                        style = MaterialTheme.typography.bodySmall, color = M.Muted, modifier = Modifier.padding(vertical = 8.dp),
                    )
                    ApiKeyField(key, { key = it; msg = null }, "Colle ta clé ici")
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PrimaryButton("Vérifier la clé", enabled = key.isNotBlank() && !busy) {
                            busy = true
                            scope.launch {
                                msg = testAndConfigure(key).fold({ true to it }, { false to (it.message ?: "Échec") })
                                busy = false
                            }
                        }
                        if (busy) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(color = M.Lilac, strokeWidth = 2.dp, modifier = Modifier.size(22.dp)) }
                    }
                    msg?.let { (ok, m) -> Spacer(Modifier.height(10.dp)); Banner(m, if (ok) M.Mint else M.Coral) }
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        Spacer(Modifier.height(26.dp))
                        Text("3 · Le rappel du soir", style = MaterialTheme.typography.titleLarge, color = M.Text)
                        Text(
                            "Une notification douce vers 20h30 pour raconter ta journée. Modifiable dans Réglages.",
                            style = MaterialTheme.typography.bodySmall, color = M.Muted, modifier = Modifier.padding(vertical = 8.dp),
                        )
                        if (notifAsked) Banner("C'est noté ✓", M.Mint)
                        else PrimaryButton("Activer le rappel", color = M.Butter) {
                            notifAsked = true
                            notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    Spacer(Modifier.height(30.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(pages.size + 1) { i ->
                    Box(Modifier.size(if (i == pager.currentPage) 22.dp else 8.dp, 8.dp).clip(CircleShape).background(if (i == pager.currentPage) M.Lilac else M.Line))
                }
            }
            Spacer(Modifier.weight(1f))
            if (pager.currentPage < pages.size) PrimaryButton("Suivant") { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }
            else PrimaryButton(if (app.settings.current.hasAiKey) "C'est parti" else "Commencer", color = M.Peach) { finish() }
        }
    }
}
