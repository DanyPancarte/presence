package app.murmure.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import app.murmure.ai.LocalBrain
import app.murmure.ui.Routes
import app.murmure.ui.components.Banner
import app.murmure.ui.components.PrimaryButton
import app.murmure.ui.components.PulsingDot
import app.murmure.ui.components.Tag
import app.murmure.ui.components.Waveform
import app.murmure.ui.components.moodFaces
import app.murmure.ui.components.moodLabels
import app.murmure.ui.theme.Fraunces
import app.murmure.ui.theme.M
import app.murmure.ui.theme.Manrope
import app.murmure.ui.theme.Palette
import app.murmure.voice.Phase
import kotlinx.coroutines.delay

private val dailyPrompts = listOf(
    "Qu'est-ce que t'as fait aujourd'hui ?",
    "Qui as-tu croisé ou vu ?",
    "Un moment qui t'a marqué ?",
    "Qu'est-ce qui t'a donné de l'énergie ?",
    "Et qu'est-ce qui t'en a pris ?",
    "T'as bougé ? Gym, marche, sortie ?",
    "Une chose dont t'es fier·e ?",
)

@Composable
fun RecordScreen(nav: NavHostController, daily: Boolean) {
    val vm: RecordViewModel = viewModel()
    vm.daily = daily
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    var confirmQuit by remember { mutableStateOf(false) }
    var moodStep by rememberSaveable { mutableStateOf(daily) }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.start() else permissionDenied = true
    }
    fun begin() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.start()
        else launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(moodStep) { if (!moodStep) begin() }

    LaunchedEffect(ui.savedId) {
        val id = ui.savedId ?: return@LaunchedEffect
        if (id.isEmpty()) nav.popBackStack()
        else nav.navigate(Routes.review(id)) { popUpTo(Routes.HOME) }
    }

    fun quit() {
        if (ui.voice.text.isBlank()) { vm.cancel(); nav.popBackStack() } else confirmQuit = true
    }
    BackHandler { quit() }

    if (moodStep) {
        MoodStep(
            onPick = { vm.setMood(it); moodStep = false },
            onSkip = { moodStep = false },
            onClose = { nav.popBackStack() },
        )
        return
    }

    RecordContent(ui, daily, permissionDenied, onClose = { quit() }, onStop = { vm.stopAndSave() })

    if (confirmQuit) AlertDialog(
        onDismissRequest = { confirmQuit = false },
        containerColor = M.Surface,
        title = { Text("Garder cette dictée ?", style = MaterialTheme.typography.headlineSmall) },
        text = { Text("Ta note sera sauvegardée et tu pourras la classer plus tard.", color = M.Muted) },
        confirmButton = { TextButton(onClick = { confirmQuit = false; vm.stopAndSave() }) { Text("Garder", color = M.Lilac) } },
        dismissButton = { TextButton(onClick = { confirmQuit = false; vm.cancel(); nav.popBackStack() }) { Text("Jeter", color = M.Coral) } },
    )
}

/** Écran de dictée sans état : rend l'écriture en direct à partir de [LiveUi]. */
@Composable
fun RecordContent(ui: LiveUi, daily: Boolean, permissionDenied: Boolean, onClose: () -> Unit, onStop: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        // ---------- Barre du haut ----------
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Fermer", tint = M.Muted) }
            Spacer(Modifier.weight(1f))
            EngineBadge(ui)
            Spacer(Modifier.weight(1f))
            val shown = if (daily) (RecordViewModel.DAILY_LIMIT_SEC - ui.elapsedSec).coerceAtLeast(0) else ui.elapsedSec
            Text(
                "%d:%02d".format(shown / 60, shown % 60),
                style = MaterialTheme.typography.titleMedium, color = if (daily && shown < 30) M.Peach else M.Text,
                modifier = Modifier.padding(end = 16.dp),
            )
        }

        if (daily) DailyPrompt()

        // ---------- Intention détectée ----------
        AnimatedVisibility(ui.declaredFolder != null, enter = fadeIn() + expandVertically()) {
            Row(Modifier.padding(horizontal = 22.dp, vertical = 4.dp)) {
                Tag("Dossier détecté : ${ui.declaredFolder}", color = M.Rose, leading = "📁")
            }
        }
        ui.voice.notice?.let { Banner(it, M.Butter, Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) }
        ui.voice.error?.let { Banner(it, M.Coral, Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) }
        if (permissionDenied) Banner("Le micro est refusé. Active-le dans les paramètres Android pour dicter.", M.Coral, Modifier.padding(horizontal = 22.dp, vertical = 6.dp))

        // ---------- Écriture en direct ----------
        LiveText(ui, Modifier.weight(1f))

        // ---------- Liens détectés ----------
        LinkChips(ui.hits)

        // ---------- Bas : onde + stop ----------
        Column(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Waveform(ui.levels, Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 28.dp), color = if (daily) M.Peach else M.Lilac)
            Spacer(Modifier.height(14.dp))
            AnimatedContent(ui.saving || ui.voice.phase == Phase.FINALIZING, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "stop") { busy ->
                if (busy) {
                    Row(Modifier.height(84.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = M.Lilac, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(14.dp))
                        Text("Je finalise ta note…", style = MaterialTheme.typography.titleMedium, color = M.Text)
                    }
                } else StopButton(
                    progress = if (daily) ui.elapsedSec / RecordViewModel.DAILY_LIMIT_SEC.toFloat() else null,
                    onClick = onStop,
                )
            }
            Text(
                if (daily) "5 minutes max · arrête quand tu veux" else "Terminer",
                style = MaterialTheme.typography.labelMedium, color = M.Faint, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun EngineBadge(ui: LiveUi) {
    val v = ui.voice
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(M.Surface).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (v.phase) {
            Phase.LISTENING -> PulsingDot(M.Peach)
            else -> PulsingDot(M.Muted)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when (v.phase) {
                Phase.IDLE, Phase.STARTING -> "Connexion…"
                Phase.LISTENING -> "J'écoute · ${v.engine}"
                Phase.FINALIZING -> "Finalisation"
                Phase.DONE -> "Terminé"
            },
            style = MaterialTheme.typography.labelMedium, color = M.Text,
        )
    }
}

@Composable
private fun DailyPrompt() {
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(9_000); i = (i + 1) % dailyPrompts.size } }
    AnimatedContent(i, transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(400)) }, label = "prompt") { idx ->
        Text(
            dailyPrompts[idx], style = MaterialTheme.typography.headlineSmall, color = M.Peach,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun LiveText(ui: LiveUi, modifier: Modifier) {
    val scroll = rememberScrollState()
    val v = ui.voice
    val committed = v.committed.replace(Regex("\\s+"), " ").trim()
    val full = v.text
    LaunchedEffect(full.length) { scroll.animateScrollTo(scroll.maxValue) }
    val caret = rememberInfiniteTransition(label = "caret")
    val caretA by caret.animateFloat(0f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "c")

    Box(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 12.dp)) {
            if (full.isBlank()) {
                Text(
                    if (v.phase == Phase.LISTENING) "Vas-y, je t'écoute…" else "Je prépare le micro…",
                    fontFamily = Fraunces, fontSize = 30.sp, lineHeight = 38.sp, color = M.Faint,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Astuce : commence par « une note pour le dossier… » et je la classe toute seule.",
                    style = MaterialTheme.typography.bodyMedium, color = M.Faint,
                )
            } else {
                val boundary = committed.length.coerceAtMost(full.length)
                val text = buildAnnotatedString {
                    fun plain(from: Int, to: Int) {
                        if (from >= to) return
                        val mid = boundary.coerceIn(from, to)
                        if (mid > from) append(full.substring(from, mid))
                        if (to > mid) withStyle(SpanStyle(color = M.Muted, fontStyle = FontStyle.Italic)) { append(full.substring(mid, to)) }
                    }
                    var i = 0
                    ui.hits.filter { it.end <= full.length }.forEach { h ->
                        if (h.start < i) return@forEach
                        plain(i, h.start)
                        val c = Palette.kind(h.kind)
                        withStyle(SpanStyle(color = c, background = c.copy(alpha = 0.16f), fontWeight = FontWeight.SemiBold)) {
                            append(full.substring(h.start, h.end))
                        }
                        i = h.end
                    }
                    plain(i, full.length)
                    withStyle(SpanStyle(color = M.Peach.copy(alpha = if (v.phase == Phase.LISTENING) caretA else 0f))) { append(" ▍") }
                }
                Text(text, fontFamily = Manrope, fontSize = 24.sp, lineHeight = 36.sp, color = M.Text, fontWeight = FontWeight.Medium)
                if (v.pending > 0) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulsingDot(M.Lilac, 7.dp); Spacer(Modifier.width(6.dp))
                        Text("transcription de la dernière phrase…", style = MaterialTheme.typography.labelSmall, color = M.Muted)
                    }
                }
            }
        }
        // Fondus haut/bas pour l'effet « page qui s'écrit »
        Box(Modifier.fillMaxWidth().height(24.dp).background(Brush.verticalGradient(listOf(M.Ink, M.Ink.copy(alpha = 0f)))))
        Box(Modifier.fillMaxWidth().height(36.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(M.Ink.copy(alpha = 0f), M.Ink))))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkChips(hits: List<LocalBrain.Hit>) {
    val unique = hits.distinctBy { it.label.lowercase() }.takeLast(10)
    AnimatedVisibility(unique.isNotEmpty()) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            unique.forEach { h -> Tag(h.label, color = Palette.kind(h.kind), leading = Palette.kindIcon(h.kind)) }
        }
    }
}

@Composable
private fun StopButton(progress: Float?, onClick: () -> Unit) {
    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 4.dp.toPx()
            drawCircle(M.Line, size.minDimension / 2 - sw / 2, style = Stroke(sw))
            if (progress != null) drawArc(
                M.Peach, -90f, 360f * progress.coerceIn(0f, 1f), false,
                topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round),
            )
        }
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(M.Peach).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Stop, "Terminer", tint = M.Ink, modifier = Modifier.size(30.dp)) }
    }
}

@Composable
private fun MoodStep(onPick: (Int) -> Unit, onSkip: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Fermer", tint = M.Muted) } }
        Spacer(Modifier.weight(1f))
        Text("Rituel du jour", style = MaterialTheme.typography.labelSmall, color = M.Peach)
        Spacer(Modifier.height(8.dp))
        Text("Comment ça va,\nlà, maintenant ?", style = MaterialTheme.typography.displayMedium, color = M.Text)
        Spacer(Modifier.height(32.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            moodFaces.forEachIndexed { i, f ->
                val c = Palette.mood(i + 1f)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(58.dp).clip(CircleShape).background(c.copy(alpha = 0.18f))
                            .border(1.dp, c.copy(alpha = 0.5f), CircleShape).clickable { onPick(i + 1) },
                        contentAlignment = Alignment.Center,
                    ) { Text(f, fontSize = 28.sp) }
                    Spacer(Modifier.height(6.dp))
                    Text(moodLabels[i], style = MaterialTheme.typography.labelSmall, color = M.Muted)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            "Ensuite, raconte ta journée. 5 minutes max, pas de pression.",
            style = MaterialTheme.typography.bodyMedium, color = M.Muted,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onSkip) { Text("Passer cette question", color = M.Faint) }
    }
}
