package app.murmure.ui.portrait

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import app.murmure.MurmureApp
import app.murmure.ai.Emotions
import app.murmure.core.Dates
import app.murmure.data.NoteStatus
import app.murmure.insights.Correlation
import app.murmure.insights.Insights
import app.murmure.insights.PersonPresence
import app.murmure.ui.components.Banner
import app.murmure.ui.components.Card
import app.murmure.ui.components.Dot
import app.murmure.ui.components.EmptyState
import app.murmure.ui.components.Eyebrow
import app.murmure.ui.components.MarkdownText
import app.murmure.ui.components.PrimaryButton
import app.murmure.ui.components.StatTile
import app.murmure.ui.theme.M
import app.murmure.ui.theme.Palette
import app.murmure.ui.theme.Viz
import kotlinx.coroutines.launch
import app.murmure.core.Text as T

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PortraitScreen(nav: NavHostController) {
    val repo = MurmureApp.instance.repo
    val notes by repo.notes.collectAsState(initial = emptyList())
    val entities by repo.entities.collectAsState(initial = emptyList())
    val mentions by repo.mentions.collectAsState(initial = emptyList())
    val reading by repo.report("portrait").collectAsState(initial = null)
    val p = remember(notes, entities, mentions) { Insights.build(notes, entities, mentions) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val filedCount = notes.count { it.status == NoteStatus.FILED }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp))
        Text("Portrait", style = MaterialTheme.typography.displayMedium, color = M.Text)
        Text("Un miroir, pas un verdict. Il se dessine avec le temps.", style = MaterialTheme.typography.bodyMedium, color = M.Muted)
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("${p.streak} j", "série 🔥", M.Peach, Modifier.weight(1f))
            StatTile("$filedCount", "notes", M.Lilac, Modifier.weight(1f))
            StatTile(p.avgMood?.let { T.d1(it) } ?: "—", "mood /5", M.Mint, Modifier.weight(1f))
        }
        if (p.bestStreak > p.streak) Text(
            "Record : ${p.bestStreak} jours d'affilée",
            style = MaterialTheme.typography.labelSmall, color = M.Faint, modifier = Modifier.padding(top = 8.dp),
        )

        if (filedCount < 3) {
            EmptyState(
                "🌱", "Ton portrait se dessine",
                "Encore ${3 - filedCount} note${if (3 - filedCount > 1) "s" else ""} et les premières tendances apparaissent. Le rituel quotidien l'enrichit le plus.",
            )
            PrivacyNote()
            return@Column
        }

        Spacer(Modifier.height(16.dp))
        Card {
            Eyebrow("Ton mood · 30 derniers jours")
            MoodLine(p.moodByDay)
            Text("Humeur déclarée au rituel, sinon estimée d'après le ton de tes notes. Touche un point pour le détail.", style = MaterialTheme.typography.labelSmall, color = M.Faint)
        }

        if (p.correlations.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Card {
                Eyebrow("Ce qui semble aller avec ton mood")
                Spacer(Modifier.height(6.dp))
                p.correlations.firstOrNull()?.let { c -> Observation(c) }
                Spacer(Modifier.height(8.dp))
                p.correlations.forEach { c ->
                    LabeledRow("${Palette.kindIcon(c.entity.kind)} ${c.entity.name}", T.signed1(c.delta)) { DivergingBar(c.delta, 2f, Modifier.fillMaxWidth()) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(Viz.Negative); Spacer(Modifier.width(6.dp)); Text("mood plus bas", style = MaterialTheme.typography.labelSmall, color = M.Muted)
                    Spacer(Modifier.width(14.dp))
                    Dot(Viz.Positive); Spacer(Modifier.width(6.dp)); Text("mood plus haut", style = MaterialTheme.typography.labelSmall, color = M.Muted)
                }
                Text(
                    "Écart d'humeur moyenne entre les jours où c'est mentionné et les autres. Corrélation ≠ cause.",
                    style = MaterialTheme.typography.labelSmall, color = M.Faint, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        if (p.people.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            PresenceCard("Ton entourage", "Qui revient, et la couleur des moments associés", p.people)
        }
        if (p.activities.isNotEmpty() || p.places.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            PresenceCard("Ce que tu fais, où tu vas", "Activités et lieux les plus présents", (p.activities + p.places).sortedByDescending { it.mentions }.take(10))
        }

        if (p.timeOfDay.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Card {
                Eyebrow("Moments de la journée")
                Spacer(Modifier.height(6.dp))
                p.timeOfDay.forEach { (k, v) ->
                    LabeledRow(k.replaceFirstChar { it.uppercase() }, T.d1(v)) { ScaleDot(v, 1f, 5f, Viz.diverging((v - 3f) / 2f), Modifier.fillMaxWidth()) }
                }
                if (p.energyByTime.isNotEmpty()) {
                    val best = p.energyByTime.maxByOrNull { it.second }!!
                    Text(
                        "Ton énergie perçue est la plus haute ${if (best.first == "nuit") "la" else "le"} ${best.first} (${T.d1(best.second)}/3).",
                        style = MaterialTheme.typography.bodySmall, color = M.Muted, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        if (p.emotions.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Card {
                Eyebrow("Émotions dominantes")
                Spacer(Modifier.height(6.dp))
                val max = p.emotions.first().second.toFloat()
                p.emotions.take(6).forEach { (e, n) ->
                    LabeledRow("${Emotions.emoji(e)} $e", "$n") { MagnitudeBar(n / max, Modifier.fillMaxWidth()) }
                }
            }
        }

        if (p.themes.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Card {
                Eyebrow("Autour de quoi tu parles")
                Spacer(Modifier.height(10.dp))
                val max = p.themes.first().second.toFloat()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    p.themes.forEach { (t, n) ->
                        val w = n / max
                        Text(
                            t, color = if (w > 0.6f) M.Text else if (w > 0.3f) M.Muted else M.Faint,
                            fontSize = (13 + 13 * w).sp, fontWeight = if (w > 0.6f) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Card(tint = M.Lilac) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Lecture IA · observations", Modifier.weight(1f), color = M.Lilac)
                reading?.let { Text(Dates.pretty(it.createdAt), style = MaterialTheme.typography.labelSmall, color = M.Faint) }
            }
            Spacer(Modifier.height(8.dp))
            err?.let { Banner(it, M.Coral); Spacer(Modifier.height(8.dp)) }
            when {
                busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = M.Lilac, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp)); Text("Je regarde tes tendances…", color = M.Muted)
                }
                reading != null -> MarkdownText(reading!!.content)
                else -> Text(
                    "Où tu sembles le plus fonctionnel·le, ce qui mérite d'être répété ou évité. Seules des statistiques agrégées sont envoyées, jamais tes notes.",
                    style = MaterialTheme.typography.bodyMedium, color = M.Muted,
                )
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(if (reading == null) "Générer ma lecture" else "Actualiser", leading = Icons.Rounded.AutoAwesome, enabled = !busy) {
                busy = true; err = null
                scope.launch {
                    runCatching { repo.portraitReading(Insights.statsForAi(p)) }.onFailure { err = it.message }
                    busy = false
                }
            }
        }
        PrivacyNote()
    }
}

@Composable
private fun Observation(c: Correlation) {
    val up = c.delta > 0
    Text(
        buildAnnotatedString {
            append("Les jours où tu mentionnes ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(c.entity.name) }
            append(", ton mood moyen est de ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(T.d1(c.avgWith)) }
            append(" contre ${T.d1(c.avgWithout)} les autres jours")
            append(if (up) " — un lien positif observé" else " — un lien plus lourd observé")
            append(" sur ${c.daysWith} jour${if (c.daysWith > 1) "s" else ""}.")
        },
        style = MaterialTheme.typography.bodyMedium, color = M.Text,
    )
}

@Composable
private fun PresenceCard(title: String, subtitle: String, list: List<PersonPresence>) {
    Card {
        Eyebrow(title)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = M.Faint)
        Spacer(Modifier.height(8.dp))
        val max = list.maxOf { it.mentions }.toFloat()
        list.forEach { pp ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${Palette.kindIcon(pp.entity.kind)} ${pp.entity.name}", style = MaterialTheme.typography.bodyMedium, color = M.Text, modifier = Modifier.width(128.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                MagnitudeBar(pp.mentions / max, Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Dot(Viz.diverging(pp.tone), 10.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    when { pp.tone > 0.2f -> "bien-être"; pp.tone < -0.2f -> "tension"; else -> "neutre" },
                    style = MaterialTheme.typography.labelSmall, color = M.Muted, modifier = Modifier.width(58.dp),
                )
            }
        }
        Text(
            "Barre = nombre de mentions · pastille = tonalité moyenne des moments associés.",
            style = MaterialTheme.typography.labelSmall, color = M.Faint, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun PrivacyNote() {
    Text(
        "🔒 Tout est chiffré et stocké sur ton appareil. Murmure n'est pas un outil clinique et ne pose aucun diagnostic. Export et suppression totale dans Réglages.",
        style = MaterialTheme.typography.labelSmall, color = M.Faint,
        modifier = Modifier.padding(vertical = 22.dp),
    )
}
