package app.murmure.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.murmure.ui.components.Feedback
import app.murmure.ui.components.IconAction
import app.murmure.ui.components.PulsingDot
import app.murmure.voice.WakeListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.platform.LocalView
import app.murmure.ui.components.pressScale
import androidx.navigation.NavHostController
import app.murmure.MurmureApp
import app.murmure.ai.NoteTypes
import app.murmure.core.Dates
import app.murmure.data.NoteEntity
import app.murmure.data.NoteStatus
import app.murmure.insights.Insights
import app.murmure.ui.Routes
import app.murmure.ui.components.Eyebrow
import app.murmure.ui.components.Orb
import app.murmure.ui.theme.M
import app.murmure.ui.theme.Palette
import java.time.LocalTime

@Composable
fun HomeScreen(nav: NavHostController) {
    val app = MurmureApp.instance
    val notes by app.repo.notes.collectAsState(initial = emptyList())
    val pendingCaptures by app.repo.pendingCaptures.collectAsState(initial = emptyList())
    val settings by app.settings.state.collectAsState()
    val (streak, _) = remember(notes) { Insights.streak(notes) }
    val dailyDone = notes.any { it.isDaily && it.dayKey == Dates.dayKey() }
    val recent = notes.filter { it.status == NoteStatus.FILED }.take(8)
    val hour = LocalTime.now().hour
    val hello = when (hour) { in 5..11 -> "Bon matin."; in 12..17 -> "Bon après-midi."; else -> "Bonsoir." }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- Insight du jour (calcul local) ----
    var insight by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notes.size) { insight = runCatching { app.repo.homeInsight() }.getOrNull() }

    // ---- Écoute passive : on parle, ça démarre ----
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Wordmark()
            Spacer(Modifier.weight(1f))
            IconAction(Icons.Rounded.Settings, "Réglages") { nav.navigate(Routes.SETTINGS) }
        }
        Column(Modifier.padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(18.dp))
            Text(hello, style = MaterialTheme.typography.displayMedium, color = M.Text)
            Text("Qu'est-ce qui te trotte dans la tête ?", style = MaterialTheme.typography.bodyLarge, color = M.Muted)
        }

        Box(Modifier.fillMaxWidth().padding(vertical = 22.dp), contentAlignment = Alignment.Center) {
            Orb(size = 250.dp, level = wakeLevel, listening = armed) { wake.stop(); nav.navigate(Routes.record()) }
        }
        Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            if (armed) { PulsingDot(M.Peach, 7.dp); Spacer(Modifier.width(8.dp)) }
            Text(
                if (armed) "Je t'écoute. Parle, ou touche." else "Touche et parle. Je m'occupe du reste.",
                style = MaterialTheme.typography.labelMedium, color = if (armed) M.Peach else M.Faint,
            )
        }
        insight?.let {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.padding(horizontal = 22.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(M.Surface).padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { Text(it, style = MaterialTheme.typography.bodySmall, color = M.Text) }
        }
        if (!settings.hasAiKey) {
            Text(
                "Mode appareil · ajoute ta clé IA dans Réglages",
                style = MaterialTheme.typography.labelSmall, color = M.Butter,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp).clickable { nav.navigate(Routes.SETTINGS) },
            )
        }
        Spacer(Modifier.height(24.dp))

        RitualCard(done = dailyDone, streak = streak) { nav.navigate(Routes.record(daily = true)) }

        if (pendingCaptures.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.padding(horizontal = 22.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(M.Butter.copy(alpha = 0.12f)).border(1.dp, M.Butter.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                    .clickable { nav.navigate(Routes.review(pendingCaptures.first().id)) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("📥", fontSize = 22.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${pendingCaptures.size} dictée${if (pendingCaptures.size > 1) "s" else ""} à valider", style = MaterialTheme.typography.titleMedium, color = M.Text)
                    Text(pendingCaptures.first().transcript.take(70) + "…", style = MaterialTheme.typography.bodySmall, color = M.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = M.Butter)
            }
        }

        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            Eyebrow("Récemment", Modifier.padding(horizontal = 22.dp))
            Spacer(Modifier.height(10.dp))
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recent, key = { it.id }) { n -> NoteMini(n) { nav.navigate(Routes.note(n.id)) } }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(M.Peach, M.Lilac)))
        )
        Spacer(Modifier.width(8.dp))
        Text("murmure", style = MaterialTheme.typography.headlineSmall, color = M.Text)
    }
}

@Composable
private fun RitualCard(done: Boolean, streak: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    val interaction = remember { MutableInteractionSource() }
    val view = LocalView.current
    Row(
        Modifier.padding(horizontal = 22.dp).fillMaxWidth().pressScale(interaction, 0.97f).clip(shape)
            .background(
                if (done) Brush.linearGradient(listOf(M.Mint.copy(alpha = 0.20f), M.Mint.copy(alpha = 0.05f)))
                else Brush.linearGradient(listOf(M.Peach.copy(alpha = 0.30f), M.Lilac.copy(alpha = 0.18f)))
            )
            .border(1.dp, (if (done) M.Mint else M.Peach).copy(alpha = 0.4f), shape)
            .clickable(interactionSource = interaction, indication = null) { Feedback.tap(view); onClick() }
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (done) "Rituel complété ✓" else "Raconte ta journée", style = MaterialTheme.typography.headlineSmall, color = M.Text)
            Spacer(Modifier.height(2.dp))
            Text(
                if (done) "Boucle fermée pour aujourd'hui. Tu peux en rajouter." else "5 minutes max · ton mood, tes moments, tes gens",
                style = MaterialTheme.typography.bodySmall, color = M.Muted,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (streak > 0) "🔥" else "🌱", fontSize = 26.sp)
            Text("$streak j", style = MaterialTheme.typography.titleMedium, color = if (done) M.Mint else M.Peach)
        }
    }
}

@Composable
fun NoteMini(n: NoteEntity, onClick: () -> Unit) {
    val c = Palette.type(n.type)
    Column(
        Modifier.width(190.dp).height(128.dp).clip(RoundedCornerShape(22.dp))
            .background(M.Surface).border(1.dp, M.Line, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(NoteTypes.emoji(n.type), fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
            Text(NoteTypes.label(n.type), style = MaterialTheme.typography.labelSmall, color = c)
        }
        Spacer(Modifier.height(6.dp))
        Text(n.title, style = MaterialTheme.typography.titleMedium, color = M.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Text(Dates.pretty(n.createdAt), style = MaterialTheme.typography.labelSmall, color = M.Faint)
    }
}
