package app.murmure.data

import app.murmure.ai.EntityGuess
import app.murmure.ai.TaskGuess
import app.murmure.core.Dates
import app.murmure.ai.Moment
import app.murmure.ai.MomentKind
import app.murmure.core.Text
import java.time.LocalDate

/** Trois semaines de notes d'exemple, pour démontrer le graphe et le portrait. */
object DemoData {
    private data class D(
        val daysAgo: Int, val hour: Int, val title: String, val folder: String, val type: String,
        val emotion: String, val valence: Float, val energy: String, val body: String,
        val entities: List<Pair<String, String>> = emptyList(), val keywords: List<String> = emptyList(),
        val daily: Boolean = false, val mood: Int? = null, val tasks: List<Pair<String, Int?>> = emptyList(),
        val links: List<String> = emptyList(),
    )

    private val gym = "Gym" to "activity"
    private val julie = "Julie" to "person"
    private val marc = "Marc" to "person"
    private val maman = "Maman" to "person"
    private val atlas = "Projet Atlas" to "project"
    private val cafe = "Café Olimpico" to "place"
    private val bureau = "Bureau" to "place"
    private val course = "Course" to "activity"
    private val lecture = "Lecture" to "activity"

    private val notes = listOf(
        D(20, 21, "Premier soir, on commence doucement", "Journal", "journal", "calme", 0.3f, "moyenne",
            "Journée correcte. Bureau le matin, puis une marche. J'ai envie de mieux suivre mon énergie.\n\n- Tester le rituel du soir\n- Voir si la [[Gym]] change quelque chose",
            listOf(bureau, gym), listOf("énergie", "routine"), daily = true, mood = 3),
        D(19, 20, "Séance de gym et bonne fatigue", "Journal", "journal", "fierté", 0.7f, "haute",
            "Grosse séance à la [[Gym]] ce matin. Tête plus claire toute la journée. Appel avec [[Maman]] le soir, ça m'a fait du bien.",
            listOf(gym, maman), listOf("énergie", "clarté"), daily = true, mood = 4),
        D(18, 10, "Kickoff du Projet Atlas", "Travail", "travail", "excitation", 0.6f, "haute",
            "## Objectifs\n- Livrer la maquette interactive d'ici trois semaines\n- Clarifier le rôle de [[Marc]] sur le design\n\n## Idées\n- Une vue carte pour les clients\n- Onboarding en trois écrans",
            listOf(atlas, marc), listOf("maquette", "onboarding", "clients"), tasks = listOf("Envoyer le brief à Marc" to 1, "Préparer la maquette v1" to 7)),
        D(18, 22, "Journée dense mais satisfaisante", "Journal", "journal", "fierté", 0.5f, "moyenne",
            "Kickoff du [[Projet Atlas]] ce matin. Beaucoup d'idées. Un peu vidé ce soir mais content.",
            listOf(atlas), listOf("idées", "fatigue"), daily = true, mood = 4, links = listOf("Kickoff du Projet Atlas")),
        D(17, 21, "Mal dormi, journée lourde", "Journal", "journal", "fatigue", -0.4f, "basse",
            "Réveil difficile. Rien fait de sportif. Réunion longue au [[Bureau]] qui m'a vidé. Je dois me coucher plus tôt.",
            listOf(bureau), listOf("sommeil", "fatigue"), daily = true, mood = 2, tasks = listOf("Me coucher avant 23h" to 0)),
        D(16, 14, "Idée : un mode focus de 25 minutes", "Idées", "idee", "excitation", 0.6f, "haute",
            "Et si le [[Projet Atlas]] avait un mode focus ? Minuteur, une seule tâche à l'écran, et une récompense visuelle à la fin.",
            listOf(atlas), listOf("focus", "minuteur", "récompense")),
        D(16, 21, "Course au parc et souper avec Julie", "Journal", "journal", "joie", 0.8f, "haute",
            "Petite [[Course]] au parc. Souper avec [[Julie]] au [[Café Olimpico]], on a ri tout le long. Grosse différence avec hier.",
            listOf(course, julie, cafe), listOf("amitié", "énergie"), daily = true, mood = 5),
        D(15, 11, "Feedback de Marc sur la maquette", "Travail", "travail", "frustration", -0.5f, "moyenne",
            "[[Marc]] trouve la navigation confuse. Pas faux, mais le ton était sec. À retravailler :\n- simplifier le menu\n- enlever un écran d'onboarding",
            listOf(marc, atlas), listOf("maquette", "navigation", "feedback"), tasks = listOf("Simplifier le menu de la maquette" to 2)),
        D(15, 21, "Tension au travail", "Journal", "journal", "stress", -0.6f, "basse",
            "Le retour de [[Marc]] m'est resté sur le cœur. Pas de gym aujourd'hui. Je rumine un peu.",
            listOf(marc), listOf("feedback", "rumination"), daily = true, mood = 2),
        D(14, 20, "Gym + lecture, retour au calme", "Journal", "journal", "calme", 0.5f, "moyenne",
            "[[Gym]] en fin d'après-midi, puis [[Lecture]] tranquille. Le stress d'hier est retombé.",
            listOf(gym, lecture), listOf("calme", "récupération"), daily = true, mood = 4),
        D(13, 9, "Pourquoi je procrastine le lundi", "Personnel", "reflexion", "neutre", 0.0f, "moyenne",
            "Je remarque que le lundi matin je repousse tout. Hypothèse : trop de petites décisions d'un coup. Essayer de préparer la liste le dimanche soir.",
            emptyList(), listOf("procrastination", "routine", "décisions"), tasks = listOf("Préparer la liste du lundi dimanche soir" to 6)),
        D(13, 21, "Lundi en demi-teinte", "Journal", "journal", "fatigue", -0.2f, "basse",
            "Lent à démarrer. Beaucoup de courriels au [[Bureau]]. Appel avec [[Maman]] qui m'a remonté le moral.",
            listOf(bureau, maman), listOf("courriels", "routine"), daily = true, mood = 3),
        D(12, 21, "Julie m'a présenté son projet", "Journal", "journal", "joie", 0.7f, "haute",
            "Café avec [[Julie]] au [[Café Olimpico]]. Elle lance son studio. Inspirant. [[Gym]] le matin aussi.",
            listOf(julie, cafe, gym), listOf("inspiration", "amitié"), daily = true, mood = 5),
        D(11, 15, "Maquette v2 du Projet Atlas", "Travail", "travail", "fierté", 0.6f, "haute",
            "Menu simplifié, onboarding réduit à deux écrans. [[Marc]] a validé cette fois.\n\n- Tester avec trois clients\n- Préparer la démo",
            listOf(atlas, marc), listOf("maquette", "onboarding", "clients"), tasks = listOf("Tester la maquette avec 3 clients" to 5),
            links = listOf("Feedback de Marc sur la maquette")),
        D(11, 21, "Une vraie belle journée de travail", "Journal", "journal", "fierté", 0.6f, "haute",
            "La v2 du [[Projet Atlas]] est validée. [[Course]] rapide le soir. Je me sens aligné.",
            listOf(atlas, course), listOf("alignement", "énergie"), daily = true, mood = 4),
        D(10, 21, "Soirée écrans, peu d'énergie", "Journal", "journal", "fatigue", -0.3f, "basse",
            "Pas bougé. Trop de téléphone. Bureau toute la journée.",
            listOf(bureau), listOf("écrans", "fatigue"), daily = true, mood = 2),
        D(9, 21, "Gym et appel avec Maman", "Journal", "journal", "gratitude", 0.7f, "moyenne",
            "[[Gym]] le matin, et long appel avec [[Maman]]. Reconnaissant pour ces moments simples.",
            listOf(gym, maman), listOf("gratitude", "famille"), daily = true, mood = 4),
        D(8, 18, "Liste d'idées pour le studio de Julie", "Idées", "idee", "excitation", 0.6f, "haute",
            "- Un nom court et chaleureux\n- Une identité pastel\n- Des ateliers du samedi\n\nEn parler à [[Julie]] au prochain café.",
            listOf(julie), listOf("studio", "identité", "ateliers"), tasks = listOf("Envoyer les idées à Julie" to 2)),
        D(7, 21, "Semaine chargée qui commence", "Journal", "journal", "stress", -0.5f, "moyenne",
            "Beaucoup de rencontres au [[Bureau]]. Échéance du [[Projet Atlas]] qui approche. Je dois déléguer.",
            listOf(bureau, atlas), listOf("échéance", "délégation"), daily = true, mood = 2, tasks = listOf("Déléguer les tests à Marc" to 1)),
        D(6, 21, "Course matinale, meilleure humeur", "Journal", "journal", "joie", 0.6f, "haute",
            "[[Course]] à 7h. Journée beaucoup plus fluide. Diner avec [[Julie]].",
            listOf(course, julie), listOf("matin", "fluidité"), daily = true, mood = 4),
        D(5, 21, "Démo du Projet Atlas réussie", "Journal", "journal", "fierté", 0.8f, "haute",
            "La démo s'est super bien passée. [[Marc]] et moi on formait une bonne équipe finalement.",
            listOf(atlas, marc), listOf("démo", "équipe"), daily = true, mood = 5),
        D(4, 13, "Ce que j'apprends sur mon énergie", "Personnel", "reflexion", "calme", 0.4f, "moyenne",
            "Les jours où je bouge le matin (gym, course), j'ai l'impression d'avoir plus de patience. Les journées entières au bureau me vident. À observer.",
            listOf(gym, course, bureau), listOf("énergie", "mouvement", "patience")),
        D(3, 21, "Dimanche tranquille", "Journal", "journal", "calme", 0.4f, "moyenne",
            "[[Lecture]] au [[Café Olimpico]]. Liste du lundi préparée. Petit moment pour moi.",
            listOf(lecture, cafe), listOf("repos", "routine"), daily = true, mood = 4),
        D(2, 21, "Lundi mieux préparé", "Journal", "journal", "calme", 0.3f, "moyenne",
            "La liste de la veille a vraiment aidé. Un peu de [[Bureau]], puis [[Gym]].",
            listOf(bureau, gym), listOf("routine", "préparation"), daily = true, mood = 4, links = listOf("Pourquoi je procrastine le lundi")),
        D(1, 21, "Fatigue et petite anxiété", "Journal", "journal", "anxiété", -0.5f, "basse",
            "Mal dormi. Beaucoup de pensées sur la suite du [[Projet Atlas]]. Je dois en parler à [[Marc]] demain.",
            listOf(atlas, marc), listOf("sommeil", "anxiété"), daily = true, mood = 2, tasks = listOf("Faire le point avec Marc sur la suite" to 1)),
    )

    suspend fun seed(repo: Repository) {
        repo.ensureDefaults()
        val today = LocalDate.now(Dates.zone)
        notes.forEach { d ->
            val day = today.minusDays(d.daysAgo.toLong())
            val ts = day.atTime(d.hour, 12).atZone(Dates.zone).toInstant().toEpochMilli()
            val id = Text.uuid()
            repo.dao.upsertNote(
                NoteEntity(
                    id = id, title = d.title, body = d.body, rawTranscript = d.body.replace(Regex("[\\[\\]#-]"), "").trim(),
                    createdAt = ts, updatedAt = ts, dayKey = day.toString(), durationSec = 40 + d.body.length / 6,
                    isDaily = d.daily, mood = d.mood, timeOfDay = Dates.timeOfDay(ts),
                )
            )
            repo.file(
                id,
                Validation(
                    title = d.title, body = d.body,
                    summary = d.body.lineSequence().first { it.isNotBlank() && !it.startsWith("#") }.replace(Regex("[\\[\\]#]"), "").trim().take(120),
                    folderName = d.folder, type = d.type, emotion = d.emotion, intensity = 0.6f, valence = d.valence, energy = d.energy,
                    entities = d.entities.map { EntityGuess(it.first, it.second, d.valence.toDouble()) },
                    keywords = d.keywords, links = d.links,
                    acceptedTasks = d.tasks.map { (t, due) -> TaskGuess(t, due?.let { today.minusDays(d.daysAgo.toLong()).plusDays(it.toLong()).toString() }, "Exemple") },
                ),
            )
        }
        // Quelques rendez-vous à venir + une dictée en attente de validation
        val ev = listOf(
            Triple("Point Atlas avec Marc", today.plusDays(1).atTime(10, 0).toString().take(16), false),
            Triple("Souper chez Julie", today.plusDays(2).atTime(18, 30).toString().take(16), false),
            Triple("Gym", today.toString(), true),
        )
        ev.forEach { (t, at, allDay) ->
            repo.dao.upsertEvent(EventEntity(Text.uuid(), null, null, t, at, allDay, null, "", EventStatus.CONFIRMED, "Exemple", System.currentTimeMillis()))
        }
        val pendingText = "Bonjour, une note pour le dossier Projet Atlas. Avec Marc on a revu la maquette, faut que j'envoie le brief demain. " +
            "Nouvelle note : idées pour le studio de Julie, un nom court et des ateliers le samedi. Rendez-vous jeudi 14h avec Julie au café. Je me sens motivé."
        repo.saveCapture(pendingText, 74, false, null, app.murmure.ai.LocalBrain.detectMoments(pendingText))
        // Réalisme : les vieilles tâches sont faites, une seule reste en retard.
        val overdue = repo.dao.allTasks().filter { t -> Dates.parseDay(t.dueDate)?.isBefore(today) == true }.sortedBy { it.dueDate }
        overdue.dropLast(1).forEach { repo.dao.setTaskStatus(it.id, TaskStatus.DONE) }
    }
}
