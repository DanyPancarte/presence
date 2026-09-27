package app.murmure.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import app.murmure.ai.Emotions
import app.murmure.ai.LocalBrain
import app.murmure.ai.Moment
import app.murmure.ai.MomentKind
import app.murmure.core.Dates
import app.murmure.ui.Routes
import app.murmure.ui.components.DotField
import app.murmure.ui.components.DotWave
import app.murmure.ui.components.Banner
import app.murmure.ui.components.Feedback
import app.murmure.ui.components.IconAction
import app.murmure.ui.components.PulsingDot
import app.murmure.ui.components.Tag
import app.murmure.ui.components.Waveform
import app.murmure.ui.components.moodFaces
import app.murmure.ui.components.moodLabels
import app.murmure.ui.components.pressScale
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
    "Quelque chose à faire demain ?",
)

/** Couleur d'un moment selon sa nature. */
fun momentColor(kind: String): Color = when (kind) {
    MomentKind.TASK -> M.Mint
    MomentKind.EVENT -> M.Sky
    MomentKind.MOOD -> M.Rose
    MomentKind.IDEA -> M.Butter
    MomentKind.NOTE -> M.Lilac
    MomentKind.PERSON -> M.Peach
    else -> M.Lilac
}

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
        MoodStep(onPick = { vm.setMood(it); moodStep = false }, onSkip = { moodStep = false }, onClose = { nav.popBackStack() })
        return
    }

    RecordContent(ui, daily, permissionDenied, onClose = { quit() }, onStop = { vm.stopAndSave() }, onDismissMoment = vm::dismissMoment)

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
fun RecordContent(
    ui: LiveUi, daily: Boolean, permissionDenied: Boolean,
    onClose: () -> Unit, onStop: () -> Unit, onDismissMoment: (String) -> Unit = {},
) {
    val v = ui.voice
    Box(Modifier.fillMaxSize()) {
        DotField(
            level = v.level, valence = ui.valence,
            weight = (ui.moments.size / 6f).coerceIn(0f, 1f) * (if (ui.energy == "haute") 1f else 0.6f),
            listening = v.phase == Phase.LISTENING, modifier = Modifier.fillMaxSize(),
        )

        Column(Modifier.fillMaxSize()) {
            // ---------- Barre du haut ----------
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(Icons.Rounded.Close, "Fermer", onClick = onClose)
                Spacer(Modifier.weight(1f))
                EngineBadge(ui)
                Spacer(Modifier.weight(1f))
                val shown = if (daily) (RecordViewModel.DAILY_LIMIT_SEC - ui.elapsedSec).coerceAtLeast(0) else ui.elapsedSec
                Text(
                    "%d:%02d".format(shown / 60, shown % 60),
                    style = MaterialTheme.typography.labelLarge, color = if (daily && shown < 30) M.Copper else M.Text,
                    modifier = Modifier.padding(end = 16.dp),
                )
            }

            if (daily) DailyPrompt()

            // ---------- Sujet · dossier · émotion ----------
            ContextStrip(ui)

            ui.voice.notice?.let { Banner(it, M.Butter, Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) }
            ui.voice.error?.let { Banner(it, M.Coral, Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) }
            if (permissionDenied) Banner("Le micro est refusé. Active-le dans les paramètres Android pour dicter.", M.Coral, Modifier.padding(horizontal = 22.dp, vertical = 6.dp))

            // ---------- Écriture en direct ----------
            LiveText(ui, Modifier.weight(1f))

            // ---------- Insight ----------
            AnimatedVisibility(ui.insight != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                InsightLine(ui.insight.orEmpty())
            }

            // ---------- Moments attrapés ----------
            MomentsRail(ui.moments, ui.freshMomentId, onDismissMoment)

            // ---------- Bas : onde + stop ----------
            Column(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                DotWave(ui.levels, Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 28.dp))
                Spacer(Modifier.height(10.dp))
                AnimatedContent(ui.saving || v.phase == Phase.FINALIZING, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "stop") { busy ->
                    if (busy) {
                        Row(Modifier.height(84.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = M.Lilac, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(14.dp))
                            Text("JE RANGE.", style = MaterialTheme.typography.labelLarge, color = M.Text)
                        }
                    } else StopButton(progress = if (daily) ui.elapsedSec / RecordViewModel.DAILY_LIMIT_SEC.toFloat() else null, onClick = onStop)
                }
                Text(
                    when {
                        ui.moments.isEmpty() -> if (daily) "5 MIN MAX · ARRÊTE QUAND TU VEUX" else "TERMINER"
                        else -> "${ui.moments.size} MOMENT${if (ui.moments.size > 1) "S" else ""} · TERMINER"
                    },
                    style = MaterialTheme.typography.labelSmall, color = M.Faint, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun EngineBadge(ui: LiveUi) {
    val v = ui.voice
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(M.Surface.copy(alpha = 0.9f)).border(1.dp, M.Line, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            ui.thinking -> PulsingDot(M.Lilac)
            v.phase == Phase.LISTENING -> PulsingDot(M.Peach)
            else -> PulsingDot(M.Muted)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                ui.thinking -> "THINKING"
                v.phase == Phase.IDLE || v.phase == Phase.STARTING -> "CONNEXION"
                v.phase == Phase.LISTENING -> "REC · ${v.engine.uppercase()}"
                v.phase == Phase.FINALIZING -> "FINALISATION"
                else -> "TERMINÉ"
            },
            style = MaterialTheme.typography.labelSmall, color = M.Text,
        )
    }
}

@Composable
private fun DailyPrompt() {
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(9_000); i = (i + 1) % dailyPrompts.size } }
    AnimatedContent(i, transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(400)) }, label = "prompt") { idx ->
        Text(
            dailyPrompts[idx], style = MaterialTheme.typography.headlineSmall, color = M.Copper,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
        )
    }
}

/** Bandeau contextuel : sujet courant, dossier détecté, émotion perçue. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContextStrip(ui: LiveUi) {
    val any = ui.topic != null || ui.declaredFolder != null || ui.emotion != "neutre"
    AnimatedVisibility(any, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ui.declaredFolder?.let { AnimatedTag(it.uppercase(), M.Text, "DO") }
            ui.topic?.let { AnimatedTag(it.uppercase(), M.Lilac, "SU") }
            if (ui.emotion != "neutre") AnimatedTag(ui.emotion.uppercase(), Palette.emotion(ui.emotion), Emotions.emoji(ui.emotion))
        }
    }
}

@Composable
private fun AnimatedTag(text: String, color: Color, leading: String) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(text) { shown = true }
    val s by animateFloatAsState(if (shown) 1f else 0.6f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "tag")
    Tag(text, color, leading = leading, modifier = Modifier.scale(s))
}

@Composable
private fun InsightLine(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp)).background(M.Surface.copy(alpha = 0.9f)).border(1.dp, M.Line, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("SIGNAL", style = MaterialTheme.typography.labelSmall, color = M.Copper)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = M.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Rail des moments : chaque carte glisse en entrant, la plus récente est mise en avant. */
@Composable
private fun MomentsRail(moments: List<Moment>, freshId: String?, onDismiss: (String) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(moments.size) { if (moments.isNotEmpty()) state.animateScrollToItem(moments.lastIndex) }
    AnimatedVisibility(moments.isNotEmpty(), enter = fadeIn() + expandVertically()) {
        LazyRow(
            state = state, contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(moments, key = { it.id }) { m -> MomentCard(m, fresh = m.id == freshId, onDismiss = { onDismiss(m.id) }) }
        }
    }
}

@Composable
fun MomentCard(m: Moment, fresh: Boolean, onDismiss: (() -> Unit)? = null) {
    val c = momentColor(m.kind)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val s by animateFloatAsState(if (shown) 1f else 0.7f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow), label = "card")
    val glow by animateFloatAsState(if (fresh) 0.55f else 0.3f, tween(900), label = "glow")
    val interaction = remember { MutableInteractionSource() }
    val view = LocalView.current
    Column(
        Modifier.scale(s).widthIn(min = 150.dp, max = 230.dp)
            .pressScale(interaction, 0.96f)
            .clip(RoundedCornerShape(10.dp))
            .background(M.Surface.copy(alpha = 0.92f))
            .border(1.dp, c.copy(alpha = glow), RoundedCornerShape(10.dp))
            .then(if (onDismiss != null) Modifier.clickable(interactionSource = interaction, indication = null) { Feedback.tap(view); onDismiss() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(MomentKind.emoji(m.kind), style = MaterialTheme.typography.labelSmall, color = M.Copper)
            Spacer(Modifier.width(8.dp))
            Text(MomentKind.verb(m.kind).uppercase(), style = MaterialTheme.typography.labelSmall, color = c)
        }
        Spacer(Modifier.height(4.dp))
        Text(m.title, style = MaterialTheme.typography.titleSmall, color = M.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val sub = when (m.kind) {
            MomentKind.TASK -> Dates.prettyDue(m.due)
            MomentKind.EVENT -> m.due?.let { prettyWhen(it, m.allDay) } ?: ""
            MomentKind.MOOD -> m.mood?.let { "${moodFaces[it - 1]}/5 · ${moodLabels[it - 1]}" } ?: ""
            MomentKind.NOTE -> m.folder?.let { "→ $it" } ?: ""
            else -> ""
        }
        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.labelSmall, color = M.Muted, maxLines = 1)
        if (onDismiss != null) Text("TOUCHER · RETIRER", style = MaterialTheme.typography.labelSmall, color = M.Faint.copy(alpha = 0.7f), modifier = Modifier.padding(top = 4.dp))
    }
}

fun prettyWhen(iso: String, allDay: Boolean): String {
    val day = Dates.prettyDue(iso.take(10)).substringAfter("En retard · ")
    if (allDay || iso.length < 16) return day
    return "$day · ${iso.substring(11).replace(':', 'h')}"
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
                    if (v.phase == Phase.LISTENING) "J'écoute." else "Micro.",
                    fontFamily = Fraunces, fontSize = 34.sp, lineHeight = 40.sp, color = M.Muted,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "« NOUVELLE NOTE » · « FAUT QUE JE » · « RENDEZ-VOUS JEUDI 14H »\nJe trie pendant que tu parles.",
                    style = MaterialTheme.typography.labelSmall, color = M.Faint,
                )
            } else {
                val boundary = committed.length.coerceAtMost(full.length)
                // Plages de moments (soulignées par nature) + entités (surlignées)
                val spans = (ui.moments.filter { it.start >= 0 && it.end <= full.length }.map { Triple(it.start, it.end, momentColor(it.kind)) })
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
                    spans.forEach { (a, b, c) -> addStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline, color = c), a, b) }
                    withStyle(SpanStyle(color = M.Peach.copy(alpha = if (v.phase == Phase.LISTENING) caretA else 0f))) { append(" ▍") }
                }
                Text(text, fontFamily = Manrope, fontSize = 25.sp, lineHeight = 36.sp, color = M.Text, fontWeight = FontWeight.Normal, letterSpacing = (-0.3).sp)
                if (v.pending > 0) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulsingDot(M.Lilac, 7.dp); Spacer(Modifier.width(6.dp))
                        Text("DERNIÈRE PHRASE EN COURS", style = MaterialTheme.typography.labelSmall, color = M.Muted)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(24.dp).background(Brush.verticalGradient(listOf(M.Ink.copy(alpha = 0.7f), M.Ink.copy(alpha = 0f)))))
        Box(Modifier.fillMaxWidth().height(36.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(M.Ink.copy(alpha = 0f), M.Ink.copy(alpha = 0.7f)))))
    }
}

@Composable
private fun StopButton(progress: Float?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val view = LocalView.current
    Box(Modifier.size(84.dp).pressScale(interaction, 0.9f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 4.dp.toPx()
            drawCircle(M.Line, size.minDimension / 2 - sw / 2, style = Stroke(sw))
            if (progress != null) drawArc(
                M.Copper, -90f, 360f * progress.coerceIn(0f, 1f), false,
                topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round),
            )
        }
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(M.Text)
                .clickable(interactionSource = interaction, indication = null) { Feedback.confirm(view); onClick() },
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(18.dp).clip(RoundedCornerShape(3.dp)).background(M.Ink)) }
    }
}

@Composable
private fun MoodStep(onPick: (Int) -> Unit, onSkip: () -> Unit, onClose: () -> Unit) {
    val view = LocalView.current
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row { IconAction(Icons.Rounded.Close, "Fermer", onClick = onClose) }
        Spacer(Modifier.weight(1f))
        Text("02  RITUEL", style = MaterialTheme.typography.labelSmall, color = M.Copper)
        Spacer(Modifier.height(8.dp))
        Text("Là, maintenant.\nDe 1 à 5 ?", style = MaterialTheme.typography.displayMedium, color = M.Text)
        Spacer(Modifier.height(32.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            moodFaces.forEachIndexed { i, f ->
                val c = Palette.mood(i + 1f)
                val interaction = remember { MutableInteractionSource() }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(58.dp).pressScale(interaction, 0.85f).clip(CircleShape).background(M.Surface)
                            .border(1.dp, M.Line, CircleShape)
                            .clickable(interactionSource = interaction, indication = null) { Feedback.confirm(view); Feedback.play(Feedback.Sound.TAP); onPick(i + 1) },
                        contentAlignment = Alignment.Center,
                    ) { Text(f, style = MaterialTheme.typography.headlineMedium, color = c) }
                    Spacer(Modifier.height(6.dp))
                    Text(moodLabels[i], style = MaterialTheme.typography.labelSmall, color = M.Muted)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text("Ensuite, raconte ta journée. 5 minutes max.", style = MaterialTheme.typography.bodyMedium, color = M.Muted)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onSkip) { Text("PASSER", style = MaterialTheme.typography.labelSmall, color = M.Faint) }
    }
}
