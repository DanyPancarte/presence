package app.murmure.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.murmure.MurmureApp
import app.murmure.core.Dates
import app.murmure.core.Text as T
import app.murmure.data.EntityKind
import app.murmure.data.MentionEntity
import app.murmure.data.NoteEntity
import app.murmure.data.NoteMention
import app.murmure.data.NoteStatus
import app.murmure.insights.Adherence
import app.murmure.insights.Adherences
import app.murmure.insights.Influence
import app.murmure.insights.Insights
import app.murmure.insights.Portrait
import app.murmure.ui.components.Banner
import app.murmure.ui.components.DotBar
import app.murmure.ui.components.DotRing
import app.murmure.ui.components.DotSeries
import app.murmure.ui.components.MarkdownText
import app.murmure.ui.components.PrimaryButton
import app.murmure.ui.components.Tile
import app.murmure.ui.components.moodLabels
import app.murmure.ui.theme.M
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

private enum class Lens(val code: String, val label: String) {
    MOOD("01", "MOOD"), PEOPLE("02", "ENTOURAGE"), ADHERENCE("03", "ADHÉRENCE"), LINKS("04", "CORRÉLATIONS"),
}

/** Le BI de Brainmeat : quatre lentilles sur la même matière. */
@Composable
fun InsightsScreen(nav: NavHostController) {
    val repo = MurmureApp.instance.repo
    val notes by repo.notes.collectAsState(initial = emptyList())
    val entities by repo.entities.collectAsState(initial = emptyList())
    val mentions by repo.mentions.collectAsState(initial = emptyList())
    val tasks by repo.allTasks.collectAsState(initial = emptyList())
    val reading by repo.report("portrait").collectAsState(initial = null)
    var lens by rememberSaveable { mutableStateOf(Lens.MOOD) }
    val portrait = remember(notes, entities, mentions) { Insights.build(notes, entities, mentions) }
    val adherence = remember(tasks, notes) { Adherences.compute(tasks, notes) }
    val people = remember(notes, entities, mentions) { Adherences.influences(notes, entities, mentions, setOf(EntityKind.PERSON)) }
    val contexts = remember(notes, entities, mentions) { Adherences.influences(notes, entities, mentions, setOf(EntityKind.ACTIVITY, EntityKind.PLACE, EntityKind.PROJECT)) }
    val filed = notes.count { it.status == NoteStatus.FILED }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 14.dp), verticalAlignment = Alignment.Bottom) {
            Text("Insights", style = MaterialTheme.typography.displayMedium, color = M.Text, modifier = Modifier.weight(1f))
            Text("$filed NOTES · ${adherence.total} TÂCHES", style = MaterialTheme.typography.labelSmall, color = M.Faint)
        }
        Text("Un miroir descriptif. Jamais un verdict.", style = MaterialTheme.typography.bodySmall, color = M.Faint, modifier = Modifier.padding(horizontal = 22.dp))
        Spacer(Modifier.height(14.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Lens.entries.forEach { l -> LensChip(l, l == lens) { lens = l } }
        }
        Spacer(Modifier.height(14.dp))
        if (filed < 3) {
            Tile(index = null, title = "Pas encore assez de matière", modifier = Modifier.padding(horizontal = 18.dp)) {
                Text("Trois notes classées et les premières lignes apparaissent. Le rituel du soir nourrit tout le reste.", style = MaterialTheme.typography.bodyMedium, color = M.Muted)
            }
        } else when (lens) {
            Lens.MOOD -> MoodLens(portrait, notes, entities, mentions)
            Lens.PEOPLE -> PeopleLens(people, contexts)
            Lens.ADHERENCE -> AdherenceLens(adherence)
            Lens.LINKS -> LinksLens(portrait, reading?.content, reading?.createdAt) { repo.portraitReading(Insights.statsForAi(portrait)) }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun LensChip(l: Lens, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) M.Surface2 else M.Ink)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(l.code, style = MaterialTheme.typography.labelSmall, color = if (selected) M.Copper else M.Faint)
        Spacer(Modifier.width(8.dp))
        Text(l.label, style = MaterialTheme.typography.labelSmall, color = if (selected) M.Text else M.Muted)
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineLarge, color = if (accent) M.Copper else M.Text)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = M.Faint)
    }
}

@Composable
private fun BarRow(label: String, value: Float, trailing: String, diverging: Boolean = false, labelWidth: Int = 96) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = M.Muted, modifier = Modifier.width(labelWidth.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        DotBar(value, Modifier.weight(1f).height(14.dp), diverging = diverging)
        Spacer(Modifier.width(10.dp))
        Text(trailing, style = MaterialTheme.typography.labelMedium, color = M.Text)
    }
}

// ------------------------------------------------------------------ MOOD

@Composable
private fun MoodLens(p: Portrait, notes: List<NoteEntity>, entities: List<MentionEntity>, mentions: List<NoteMention>) {
    var picked by remember(p) { mutableStateOf(p.moodByDay.indexOfLast { it.second != null }.takeIf { it >= 0 }) }
    Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile(index = "01", title = "Mood · 30 jours", trailing = p.avgMood?.let { "moy ${T.d1(it)}" }) {
            val sel = picked?.let { p.moodByDay[it] }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(sel?.second?.let { T.d1(it) } ?: "—", style = MaterialTheme.typography.displayMedium, color = M.Text)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.padding(bottom = 6.dp)) {
                    Text(sel?.first?.format(DateTimeFormatter.ofPattern("EEE d MMM", Dates.fr))?.uppercase() ?: "", style = MaterialTheme.typography.labelSmall, color = M.Muted)
                    Text(sel?.second?.let { moodLabels[(kotlin.math.round(it).toInt() - 1).coerceIn(0, 4)].uppercase() } ?: "", style = MaterialTheme.typography.labelSmall, color = M.Copper)
                }
            }
            Spacer(Modifier.height(10.dp))
            DotSeries(values = p.moodByDay.map { it.second?.let { v -> (v - 1f) / 4f } }, modifier = Modifier.fillMaxWidth().height(96.dp), picked = picked)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(0, 15, 29).forEach { i -> Text(p.moodByDay[i].first.format(DateTimeFormatter.ofPattern("d MMM", Dates.fr)).uppercase(), style = MaterialTheme.typography.labelSmall, color = M.Faint) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                p.moodByDay.withIndex().filter { it.value.second != null }.takeLast(10).forEach { (i, v) ->
                    Text(
                        v.first.dayOfMonth.toString(), style = MaterialTheme.typography.labelMedium, color = if (i == picked) M.Copper else M.Muted,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (i == picked) M.Surface2 else M.Surface).clickable { picked = i }.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        val why = picked?.let { Adherences.whyOf(p.moodByDay[it].first, notes, entities, mentions) }.orEmpty()
        Tile(index = "02", title = "Pourquoi ce jour-là") {
            if (why.isEmpty()) Text("Aucune note classée ce jour-là.", style = MaterialTheme.typography.bodyMedium, color = M.Muted)
            else Text(why.joinToString("  ·  "), style = MaterialTheme.typography.bodyLarge, color = M.Text)
        }
        if (p.timeOfDay.isNotEmpty()) Tile(index = "03", title = "Selon le moment") {
            p.timeOfDay.forEach { (k, v) -> BarRow(k.uppercase(), (v - 1f) / 4f, T.d1(v)) }
        }
        if (p.emotions.isNotEmpty()) Tile(index = "04", title = "Émotions dominantes") {
            val max = p.emotions.first().second.toFloat()
            p.emotions.take(6).forEach { (e, n) -> BarRow(e.uppercase(), n / max, "$n") }
        }
    }
}

// ------------------------------------------------------------------ ENTOURAGE

@Composable
private fun PeopleLens(people: List<Influence>, contexts: List<Influence>) {
    Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile(index = "01", title = "Qui pèse", trailing = "${people.size} personnes") {
            if (people.isEmpty()) Text("Personne encore. Nomme les gens quand tu parles.", style = MaterialTheme.typography.bodyMedium, color = M.Muted)
            val max = people.maxOfOrNull { it.weight } ?: 1f
            people.take(8).forEach { InfluenceRow(it, max) }
            Text("Poids = présence récente. Barre = tonalité des moments partagés. Lecture descriptive : soutien, neutre, tension.", style = MaterialTheme.typography.labelSmall, color = M.Faint, modifier = Modifier.padding(top = 8.dp))
        }
        val support = people.filter { it.reading == "soutien" }
        val tension = people.filter { it.reading == "tension" }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(index = "02", title = "Soutien", modifier = Modifier.weight(1f)) {
                Text("${support.size}", style = MaterialTheme.typography.displayMedium, color = M.Mint)
                Text(support.take(3).joinToString(" · ") { it.entity.name }.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, color = M.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Tile(index = "03", title = "Tension", modifier = Modifier.weight(1f)) {
                Text("${tension.size}", style = MaterialTheme.typography.displayMedium, color = if (tension.isEmpty()) M.Faint else M.Coral)
                Text(tension.take(3).joinToString(" · ") { it.entity.name }.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, color = M.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (contexts.isNotEmpty()) Tile(index = "04", title = "Contextes · activités, lieux, projets") {
            val max = contexts.maxOfOrNull { it.weight } ?: 1f
            contexts.take(8).forEach { InfluenceRow(it, max) }
        }
        val rising = (people + contexts).filter { it.trend >= 2f }.sortedByDescending { it.trend }.take(3)
        val fading = (people + contexts).filter { it.trend <= -2f && it.mentions >= 3 }.sortedBy { it.trend }.take(3)
        if (rising.isNotEmpty() || fading.isNotEmpty()) Tile(index = "05", title = "Mouvement · 2 semaines") {
            rising.forEach { Text("↑  ${it.entity.name}  ·  plus présent", style = MaterialTheme.typography.bodyMedium, color = M.Text) }
            fading.forEach { Text("↓  ${it.entity.name}  ·  s'éloigne", style = MaterialTheme.typography.bodyMedium, color = M.Muted) }
        }
    }
}

@Composable
private fun InfluenceRow(i: Influence, max: Float) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        DotRing(fraction = (i.weight / max).coerceIn(0.08f, 1f), size = 30.dp, dots = 16, accent = when (i.reading) { "soutien" -> M.Mint; "tension" -> M.Coral; else -> M.Copper })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(i.entity.name, style = MaterialTheme.typography.titleMedium, color = M.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${i.mentions}× · ${i.reading.uppercase()}" + (i.moodDelta?.let { " · MOOD ${T.signed1(it)}" } ?: "") + " · VU IL Y A ${i.lastSeenDaysAgo} J", style = MaterialTheme.typography.labelSmall, color = M.Faint)
        }
        Spacer(Modifier.width(8.dp))
        DotBar(i.tone, Modifier.width(72.dp).height(14.dp), diverging = true, dots = 12)
    }
}

// ------------------------------------------------------------------ ADHÉRENCE

@Composable
private fun AdherenceLens(a: Adherence) {
    Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile(index = "01", title = "Ce que je dis vs ce que je fais") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DotRing(fraction = a.rate, size = 92.dp, dots = 40)
                Spacer(Modifier.width(18.dp))
                Column {
                    Text("${(a.rate * 100).toInt()} %", style = MaterialTheme.typography.displayMedium, color = M.Text)
                    Text("TENUES · ${a.done} SUR ${a.done + a.abandoned + a.late}", style = MaterialTheme.typography.labelSmall, color = M.Faint)
                    Spacer(Modifier.height(6.dp))
                    Text("${(a.onTimeRate * 100).toInt()} % À TEMPS", style = MaterialTheme.typography.labelSmall, color = M.Copper)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(index = "02", title = "Abandonnées", modifier = Modifier.weight(1f)) { Stat("${a.abandoned}", "lâchées volontairement", accent = a.abandoned > 0) }
            Tile(index = "03", title = "Repoussées", modifier = Modifier.weight(1f)) { Stat("${a.postponedTasks}", "glissement ${T.d1(a.avgSlipDays)} j") }
        }
        if (a.byWeekday.isNotEmpty()) Tile(index = "04", title = "Selon le jour où je m'engage") {
            a.byWeekday.forEach { (d, r) -> BarRow(d.uppercase(), r, "${(r * 100).toInt()} %", labelWidth = 56) }
        }
        if (a.doneWhenLowMood != null || a.doneWhenHighMood != null) Tile(index = "05", title = "Engagements pris selon le mood") {
            a.doneWhenHighMood?.let { Text("Mood haut → ${(it * 100).toInt()} % tenues", style = MaterialTheme.typography.bodyMedium, color = M.Text) }
            a.doneWhenLowMood?.let { Text("Mood bas → ${(it * 100).toInt()} % tenues", style = MaterialTheme.typography.bodyMedium, color = M.Text) }
            Text("Les promesses faites un mauvais jour sont-elles tenues ? Observation, pas verdict.", style = MaterialTheme.typography.labelSmall, color = M.Faint, modifier = Modifier.padding(top = 6.dp))
        }
        if (a.recentAbandoned.isNotEmpty()) Tile(index = "06", title = "Dernières lâchées") {
            a.recentAbandoned.forEach { Text("—  ${it.text}", style = MaterialTheme.typography.bodyMedium, color = M.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

// ------------------------------------------------------------------ CORRÉLATIONS

@Composable
private fun LinksLens(p: Portrait, reading: String?, readAt: Long?, generate: suspend () -> String) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile(index = "01", title = "Ce qui va avec mon mood") {
            if (p.correlations.isEmpty()) Text("Encore trop tôt : il faut des jours avec et sans chaque chose.", style = MaterialTheme.typography.bodyMedium, color = M.Muted)
            p.correlations.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(120.dp)) {
                        Text(c.entity.name, style = MaterialTheme.typography.titleMedium, color = M.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${c.daysWith} J AVEC", style = MaterialTheme.typography.labelSmall, color = M.Faint)
                    }
                    DotBar(c.delta / 2f, Modifier.weight(1f).height(14.dp), diverging = true)
                    Spacer(Modifier.width(10.dp))
                    Text(T.signed1(c.delta), style = MaterialTheme.typography.labelMedium, color = if (c.delta >= 0) M.Mint else M.Coral)
                }
            }
            Text("Écart de mood moyen entre les jours où c'est mentionné et les autres. Corrélation ≠ cause.", style = MaterialTheme.typography.labelSmall, color = M.Faint, modifier = Modifier.padding(top = 6.dp))
        }
        if (p.themes.isNotEmpty()) Tile(index = "02", title = "Autour de quoi je tourne") {
            val max = p.themes.first().second.toFloat()
            p.themes.take(8).forEach { (t, n) -> BarRow(t, n / max, "$n", labelWidth = 120) }
        }
        Tile(index = "03", title = "Lecture IA", trailing = readAt?.let { Dates.pretty(it) }) {
            err?.let { Banner(it, M.Coral); Spacer(Modifier.height(8.dp)) }
            when {
                busy -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(color = M.Copper, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text("Je lis tes tendances…", color = M.Muted) }
                reading != null -> MarkdownText(reading)
                else -> Text("Où tu sembles le plus fonctionnel·le, ce qui mérite d'être répété ou évité. Seuls des agrégats sont envoyés, jamais tes notes.", style = MaterialTheme.typography.bodyMedium, color = M.Muted)
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(if (reading == null) "GÉNÉRER" else "ACTUALISER", enabled = !busy) {
                busy = true; err = null
                scope.launch { runCatching { generate() }.onFailure { err = it.message }; busy = false }
            }
        }
    }
}
