package app.murmure.ui.settings

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.murmure.MurmureApp
import app.murmure.ai.GeminiClient
import app.murmure.core.Engine
import app.murmure.core.AiProvider
import app.murmure.ai.ClaudeClient
import app.murmure.data.DemoData
import app.murmure.reminder.DailyReminder
import app.murmure.ui.Routes
import app.murmure.ui.components.Banner
import app.murmure.ui.components.Card
import app.murmure.ui.components.Eyebrow
import app.murmure.ui.components.GhostButton
import app.murmure.ui.components.PrimaryButton
import app.murmure.ui.components.Tag
import app.murmure.ui.components.TopBar
import app.murmure.ui.theme.M
import kotlinx.coroutines.launch

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = M.Lilac, unfocusedBorderColor = M.Line,
    focusedTextColor = M.Text, unfocusedTextColor = M.Text,
    cursorColor = M.Peach, focusedLabelColor = M.Lilac, unfocusedLabelColor = M.Muted,
)

@Composable
fun ApiKeyField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, label = { Text(label) },
        leadingIcon = { Icon(Icons.Rounded.Key, null, tint = M.Muted) },
        trailingIcon = {
            IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Afficher", tint = M.Muted) }
        },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        shape = RoundedCornerShape(16.dp), colors = fieldColors(), modifier = modifier.fillMaxWidth(),
    )
}

/** Teste la clé : liste les modèles accessibles et choisit les meilleurs pour le direct et l'analyse. */
suspend fun testAndConfigure(key: String): Result<String> = runCatching {
    val app = MurmureApp.instance
    val models = try {
        app.client.listModels(key.trim())
    } catch (e: app.murmure.ai.AiException) {
        throw e
    } catch (e: java.io.IOException) {
        // Réseau indisponible : on garde la clé, la vérification se fera à l'usage.
        app.settings.update { it.copy(apiKey = key.trim()) }
        return@runCatching "Clé enregistrée. Vérification impossible hors ligne (${e.message ?: "réseau"})."
    }
    val (text, live) = GeminiClient.pickModels(models)
    app.settings.update { s -> s.copy(apiKey = key.trim(), textModel = text ?: s.textModel, liveModel = live ?: s.liveModel) }
    buildString {
        append("✓ Clé valide · ${models.size} modèles accessibles\n")
        append("Analyse : ${(text ?: app.settings.current.textModel).removePrefix("models/")}\n")
        append(if (live != null) "Direct : ${live.removePrefix("models/")}" else "Direct : aucun modèle Live → mode segments automatique")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(nav: NavHostController) {
    val app = MurmureApp.instance
    val s by app.settings.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(s.apiKey) }
    var stt by remember { mutableStateOf(s.cloudSttKey) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var claudeKey by remember { mutableStateOf(s.claudeKey) }
    var claudeTesting by remember { mutableStateOf(false) }
    var claudeResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching {
                val json = app.repo.exportJson()
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            }.onSuccess { Toast.makeText(context, "Export terminé", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "Export impossible : ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(Modifier.fillMaxSize()) {
        TopBar("Réglages", onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {

            // ---------- IA ----------
            Card(tint = M.Lilac) {
                Eyebrow("Intelligence · clé API Google Gemini", color = M.Lilac)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Une seule clé active la transcription en direct, le classement, les liens, les comptes rendus et le portrait.",
                    style = MaterialTheme.typography.bodySmall, color = M.Muted,
                )
                Spacer(Modifier.height(12.dp))
                ApiKeyField(key, { key = it; testResult = null }, "Clé API Gemini")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(if (testing) "Test…" else "Tester et enregistrer", enabled = key.isNotBlank() && !testing) {
                        testing = true
                        scope.launch {
                            val r = testAndConfigure(key)
                            testResult = r.fold({ true to it }, { false to (it.message ?: "Échec") })
                            testing = false
                        }
                    }
                    if (testing) CircularProgressIndicator(color = M.Lilac, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
                testResult?.let { (ok, msg) -> Spacer(Modifier.height(10.dp)); Banner(msg, if (ok) M.Mint else M.Coral) }
                if (s.hasAiKey && testResult == null) {
                    Spacer(Modifier.height(8.dp)); Text("Clé enregistrée (chiffrée sur l'appareil).", style = MaterialTheme.typography.labelSmall, color = M.Mint)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Obtenir une clé gratuite → aistudio.google.com/apikey",
                    style = MaterialTheme.typography.labelMedium, color = M.Sky,
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
                    },
                )
                if (s.hasAiKey) TextButton(onClick = { app.settings.update { it.copy(apiKey = "") }; key = "" }) { Text("Retirer la clé", color = M.Coral) }
            }

            // ---------- Claude (analyse) ----------
            Spacer(Modifier.height(14.dp))
            Card(tint = M.Peach) {
                Eyebrow("Analyse · Anthropic Claude (option)", color = M.Peach)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Alternative à Gemini pour le classement, les moments, les comptes rendus et le portrait. " +
                        "La transcription en direct reste Gemini Live ou l'appareil. Clé prépayée sur console.anthropic.com (5 $ minimum ≈ des semaines d'usage).",
                    style = MaterialTheme.typography.bodySmall, color = M.Muted,
                )
                Spacer(Modifier.height(12.dp))
                ApiKeyField(claudeKey, { claudeKey = it; claudeResult = null }, "Clé Anthropic (sk-ant-…)")
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ClaudeClient.models.forEach { (id, label) ->
                        Tag(label.substringBefore(" ·"), M.Peach, selected = s.claudeModel == id) { app.settings.update { it.copy(claudeModel = id) } }
                    }
                }
                ClaudeClient.models.firstOrNull { it.first == s.claudeModel }?.let {
                    Text(it.second, style = MaterialTheme.typography.labelSmall, color = M.Faint, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(if (claudeTesting) "Test…" else "Tester et enregistrer", color = M.Peach, enabled = claudeKey.isNotBlank() && !claudeTesting) {
                        claudeTesting = true
                        scope.launch {
                            claudeResult = runCatching { app.claude.ping(claudeKey, s.claudeModel) }
                                .onSuccess { app.settings.update { it.copy(claudeKey = claudeKey, provider = AiProvider.CLAUDE) } }
                                .fold({ true to it }, { false to (it.message ?: "Échec") })
                            claudeTesting = false
                        }
                    }
                    if (claudeTesting) CircularProgressIndicator(color = M.Peach, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
                claudeResult?.let { (ok, msg) -> Spacer(Modifier.height(10.dp)); Banner(msg, if (ok) M.Mint else M.Coral) }
                if (s.claudeKey.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Moteur d'analyse", style = MaterialTheme.typography.labelMedium, color = M.Muted)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AiProvider.entries.forEach { p ->
                            Tag(p.label, if (p == AiProvider.CLAUDE) M.Peach else M.Lilac, selected = s.provider == p) { app.settings.update { it.copy(provider = p) } }
                        }
                    }
                    TextButton(onClick = { app.settings.update { it.copy(claudeKey = "", provider = AiProvider.GEMINI) }; claudeKey = "" }) { Text("Retirer la clé Claude", color = M.Coral) }
                }
                Text(
                    "Obtenir une clé → console.anthropic.com",
                    style = MaterialTheme.typography.labelMedium, color = M.Sky,
                    modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys"))) },
                )
            }

            Spacer(Modifier.height(14.dp))
            Card {
                Eyebrow("Moteur de transcription")
                Spacer(Modifier.height(8.dp))
                Engine.entries.forEach { e ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { app.settings.update { it.copy(engine = e) } }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (s.engine == e) M.Lilac else M.Line, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (s.engine == e) Box(Modifier.size(10.dp).clip(CircleShape).background(M.Lilac)) }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(e.label, style = MaterialTheme.typography.titleSmall, color = M.Text)
                            Text(e.detail, style = MaterialTheme.typography.bodySmall, color = M.Muted)
                        }
                    }
                }
                if (s.engine == Engine.CLOUD_STT) {
                    Spacer(Modifier.height(8.dp))
                    ApiKeyField(stt, { stt = it }, "Clé Google Cloud (Speech-to-Text)")
                    Spacer(Modifier.height(8.dp))
                    GhostButton("Enregistrer la clé Speech") { app.settings.update { it.copy(cloudSttKey = stt) }; Toast.makeText(context, "Enregistrée", Toast.LENGTH_SHORT).show() }
                    Text(
                        "Vide = la clé Gemini est utilisée (le projet doit avoir l'API Speech-to-Text activée).",
                        style = MaterialTheme.typography.labelSmall, color = M.Faint, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("Langue", style = MaterialTheme.typography.labelMedium, color = M.Muted)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("fr-CA" to "Français (Québec)", "fr-FR" to "Français (France)", "en-US" to "English").forEach { (code, label) ->
                        Tag(label, M.Sky, selected = s.language == code) { app.settings.update { it.copy(language = code) } }
                    }
                }
                Spacer(Modifier.height(12.dp))
                ToggleRow("Surlignage IA en direct", "Détecte concepts et personnes pendant que tu parles", s.liveHighlights) { v ->
                    app.settings.update { it.copy(liveHighlights = v) }
                }
                ToggleRow("Démarrage à la voix", "Sur l'accueil, commence à parler : la dictée démarre seule", s.voiceStart) { v ->
                    app.settings.update { it.copy(voiceStart = v) }
                }
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Masquer les modèles" else "Modèles (avancé)", color = M.Muted) }
                if (advanced) {
                    var tm by remember { mutableStateOf(s.textModel) }
                    var lm by remember { mutableStateOf(s.liveModel) }
                    OutlinedTextField(tm, { tm = it }, label = { Text("Modèle d'analyse") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(lm, { lm = it }, label = { Text("Modèle Live (direct)") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    GhostButton("Appliquer") { app.settings.update { it.copy(textModel = tm.trim(), liveModel = lm.trim()) } }
                }
            }

            Spacer(Modifier.height(14.dp))
            Card {
                Eyebrow("Retours")
                Spacer(Modifier.height(6.dp))
                ToggleRow("Sons", "Petits repères sonores : début, fin, moment attrapé", s.sounds) { v -> app.settings.update { it.copy(sounds = v) } }
                ToggleRow("Vibrations", "Retour haptique sous le doigt", s.haptics) { v -> app.settings.update { it.copy(haptics = v) } }
            }

            // ---------- Rituel ----------
            Spacer(Modifier.height(14.dp))
            Card(tint = M.Peach) {
                Eyebrow("Rituel quotidien", color = M.Peach)
                Spacer(Modifier.height(6.dp))
                ToggleRow("Rappel « raconte ta journée »", "Une notification douce, une fois par jour", s.reminderEnabled) { v ->
                    if (v && Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    app.settings.update { it.copy(reminderEnabled = v) }
                    DailyReminder.schedule(context, app.settings.current)
                }
                if (s.reminderEnabled) {
                    Spacer(Modifier.height(6.dp))
                    Tag("Heure : %02dh%02d".format(s.reminderMinutes / 60, s.reminderMinutes % 60), M.Peach, leading = "⏰") {
                        TimePickerDialog(context, { _, h, m ->
                            app.settings.update { it.copy(reminderMinutes = h * 60 + m) }
                            DailyReminder.schedule(context, app.settings.current)
                        }, s.reminderMinutes / 60, s.reminderMinutes % 60, true).show()
                    }
                }
            }

            // ---------- Vie privée ----------
            Spacer(Modifier.height(14.dp))
            Card(tint = M.Mint) {
                Eyebrow("Vie privée", color = M.Mint)
                Spacer(Modifier.height(6.dp))
                Text(
                    "• Notes stockées uniquement sur cet appareil, base chiffrée AES-256 (SQLCipher), clé protégée par l'Android Keystore.\n" +
                        "• Clé API chiffrée. Aucune sauvegarde cloud automatique.\n" +
                        "• L'audio part vers Google seulement pour la transcription ; rien n'est conservé par Murmure hors de ton téléphone.\n" +
                        "• Murmure n'est pas un outil clinique et ne pose aucun diagnostic.",
                    style = MaterialTheme.typography.bodySmall, color = M.Muted,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Exporter (JSON)", leading = Icons.Rounded.FileDownload) { exporter.launch("murmure-export.json") }
                    GhostButton("Tout effacer", color = M.Coral, leading = Icons.Rounded.DeleteForever) { confirmWipe = true }
                }
                Text(
                    "L'export n'est pas chiffré : range-le en lieu sûr.",
                    style = MaterialTheme.typography.labelSmall, color = M.Faint, modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Card {
                Eyebrow("Mode démo")
                Spacer(Modifier.height(6.dp))
                Text(
                    "Remplit l'app avec trois semaines de notes d'exemple pour voir le graphe et le portrait en action. « Tout effacer » les retire.",
                    style = MaterialTheme.typography.bodySmall, color = M.Muted,
                )
                Spacer(Modifier.height(10.dp))
                GhostButton("Charger les exemples") {
                    scope.launch {
                        DemoData.seed(app.repo)
                        Toast.makeText(context, "Exemples chargés", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Murmure 1.0 · fait pour les cerveaux qui vont vite", style = MaterialTheme.typography.labelSmall, color = M.Faint, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(30.dp))
        }
    }

    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false },
        containerColor = M.Surface,
        title = { Text("Tout effacer ?") },
        text = { Text("Notes, dossiers, tâches, portrait et clé API seront supprimés définitivement de cet appareil. Irréversible.", color = M.Muted) },
        confirmButton = {
            TextButton(onClick = {
                confirmWipe = false
                scope.launch {
                    app.repo.wipeEverything()
                    app.repo.ensureDefaults()
                    key = ""; stt = ""; claudeKey = ""
                    Toast.makeText(context, "Tout a été effacé.", Toast.LENGTH_LONG).show()
                    nav.navigate(Routes.HOME) { popUpTo(0) }
                }
            }) { Text("Effacer définitivement", color = M.Coral) }
        },
        dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Annuler", color = M.Text) } },
    )
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = M.Text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = M.Muted)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = M.Ink, checkedTrackColor = M.Lilac, uncheckedTrackColor = M.Surface2, uncheckedThumbColor = M.Muted, uncheckedBorderColor = M.Line),
        )
    }
}
