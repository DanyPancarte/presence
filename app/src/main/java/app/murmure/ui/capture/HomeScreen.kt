package app.murmure.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import app.murmure.MurmureApp
import app.murmure.ai.NanoClient
import app.murmure.ai.NoteTypes
import app.murmure.core.AiProvider
import app.murmure.core.Dates
import app.murmure.data.NoteEntity
import app.murmure.data.NoteStatus
import app.murmure.insights.Insights
import app.murmure.ui.Routes
import app.murmure.ui.components.DotOrb
import app.murmure.ui.components.DotRing
import app.murmure.ui.components.Eyebrow
import app.murmure.ui.components.Feedback
import app.murmure.ui.components.IconAction
import app.murmure.ui.components.PulsingDot
import app.murmure.ui.components.Tile
import app.murmure.ui.components.pressScale
import app.murmure.ui.theme.M
import app.murmure.voice.WakeListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalTime

@Composable
fun HomeScreen(nav: NavHostController) {
    val app = MurmureApp.instance
    val notes by app.repo.notes.collectAsState(initial = emptyList())
    val pendingCaptures by app.repo.pendingCaptures.collectAsState(initial = emptyList())
    val settings by app.settings.state.collectAsState()
    val nanoState by app.nano.state.collectAsState()
    val (streak, _) = remember(notes) { Insights.streak(notes) }
    val dailyDone = notes.any { it.isDaily && it.dayKey == Dates.dayKey() }
    val recent = notes.filter { it.status == NoteStatus.FILED }.take(6)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    var insight by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notes.size) { insight = runCatching { app.repo.homeInsight() }.getOrNull() }

    val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val wake = remember { WakeListener(scope) { scope.launch(Dispatchers.Main) { Feedback.wake(context); nav.navigate(Routes.record()) { launchSingleTop = true } } } }
    val wakeLevel by wake.level.collectAsState()
    val armed by wake.armed.collectAsState()
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, settings.voiceStart, hasMic) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> if (settings.voiceStart && hasMic) wake.start()
                Lifecycle.Event.ON_PAUSE -> wake.stop()
                else -> {}
            }
        }
        lifecycle.lifecycle.addObserver(obs)
        if (settings.voiceStart && hasMic && lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) wake.start()
        onDispose { lifecycle.lifecycle.removeObserver(obs); wake.stop() }
    }

    val hour = LocalTime.now().hour
    val greeting = when (hour) { in 5..11 -> "MATIN"; in 12..17 -> "APRÈS-MIDI"; else -> "SOIR" }
    val engineLabel = when {
        nanoState == NanoClient.State.READY && settings.provider == AiProvider.NANO -> "NANO · LOCAL"
        settings.activeProvider == AiProvider.CLAUDE -> "CLAUDE · API"
        settings.apiKey.isNotBlank() -> "GEMINI · API"
        nanoState == NanoClient.State.DOWNLOADING -> "NANO · TÉLÉCHARGEMENT"
        else -> "RÈGLES LOCALES"
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // ---- En-tête : marque + état système ----
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Wordmark()
            Spacer(Modifier.weight(1f))
            IconAction(Icons.Outlined.Tune, "Réglages") { nav.navigate(Routes.SETTINGS) }
        }
        Row(Modifier.padding(horizontal = 22.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            PulsingDot(if (settings.hasAiKey) M.Copper else M.Muted, 6.dp)
            Spacer(Modifier.width(8.dp))
            Text("$engineLabel · ${if (armed) "ÉCOUTE" else "VEILLE"}", style = MaterialTheme.typography.labelSmall, color = M.Muted)
        }

        // ---- L'orbe ----
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier.pressScale(interaction, 0.96f)
                    .clickable(interactionSource = interaction, indication = null) { Feedback.tap(view); wake.stop(); nav.navigate(Routes.record()) },
            ) { DotOrb(size = 264.dp, level = wakeLevel, listening = armed) }
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (armed) "PARLE." else "TOUCHE. PARLE.", style = MaterialTheme.typography.labelLarge, color = if (armed) M.Copper else M.Text)
            Spacer(Modifier.height(4.dp))
            Text(
                if (armed) "Je démarre au premier mot." else "Je m'occupe du reste.",
                style = MaterialTheme.typography.bodySmall, color = M.Faint,
            )
        }

        // ---- Signal du jour ----
        insight?.let {
            Spacer(Modifier.height(22.dp))
            Tile(index = "01", title = "SIGNAL", modifier = Modifier.padding(horizontal = 18.dp)) {
                Text(it.replace(Regex("^[^\\p{L}\\p{N}]+"), ""), style = MaterialTheme.typography.bodyLarge, color = M.Text)
            }
        }

        // ---- Rituel + à valider ----
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(index = "02", title = "RITUEL", modifier = Modifier.weight(1f), onClick = { nav.navigate(Routes.record(daily = true)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DotRing(fraction = if (dailyDone) 1f else 0f, size = 44.dp, dots = 24)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("$streak", style = MaterialTheme.typography.displayMedium, color = M.Text)
                        Text(if (dailyDone) "JOURS · FAIT" else "JOURS · CE SOIR", style = MaterialTheme.typography.labelSmall, color = if (dailyDone) M.Mint else M.Copper)
                    }
                }
            }
            Tile(
                index = "03", title = "À VALIDER", modifier = Modifier.weight(1f),
                onClick = { pendingCaptures.firstOrNull()?.let { nav.navigate(Routes.review(it.id)) } },
            ) {
                Text("${pendingCaptures.size}", style = MaterialTheme.typography.displayMedium, color = if (pendingCaptures.isEmpty()) M.Faint else M.Copper)
                Text(
                    pendingCaptures.firstOrNull()?.transcript?.take(40)?.uppercase() ?: "RIEN EN ATTENTE",
                    style = MaterialTheme.typography.labelSmall, color = M.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // ---- Récent ----
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Eyebrow("04  RÉCENT", Modifier.padding(horizontal = 22.dp))
            Spacer(Modifier.height(6.dp))
            recent.forEach { n -> NoteRow(n) { nav.navigate(Routes.note(n.id)) } }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DotRing(fraction = 0.33f, size = 18.dp, dots = 12)
        Spacer(Modifier.width(10.dp))
        Text("BRAINMEAT", style = MaterialTheme.typography.labelLarge, color = M.Text)
    }
}

@Composable
fun NoteRow(n: NoteEntity, onClick: () -> Unit) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 3.dp).pressScale(interaction, 0.985f)
            .clip(RoundedCornerShape(10.dp)).border(1.dp, M.Line, RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null) { Feedback.tap(view); onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(NoteTypes.emoji(n.type), style = MaterialTheme.typography.labelSmall, color = M.Copper, modifier = Modifier.width(26.dp))
        Column(Modifier.weight(1f)) {
            Text(n.title, style = MaterialTheme.typography.titleMedium, color = M.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Dates.pretty(n.createdAt).uppercase(), style = MaterialTheme.typography.labelSmall, color = M.Faint)
        }
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(app.murmure.ui.theme.Palette.tone(n.valence ?: 0f)))
    }
}
