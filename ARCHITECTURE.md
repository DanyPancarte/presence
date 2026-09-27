# Présence — architecture (v2, « l'instrument »)

Assistant vocal perso pour Dany. Android natif, Kotlin + Compose + OpenGL ES 3.0, Room, aucun backend.
**Présence ne parle pas une langue** : elle montre (la scène réagit) et elle sonne (une voix synthétique
sans mots, à prosodie). Une seule ligne de texte discrète reste lisible en bas.

Le jumeau navigateur de la scène et du son est `tools/preview/mockup.html` : c'est la référence visuelle
et sonore, à reproduire fidèlement.

## Principe : un bus d'événements

Tout est un `Signal` qui traverse un bus (`core/Bus.kt`, `MutableSharedFlow<Signal>`). Chaque sous-système
écoute ce qui le concerne et émet ce qu'il produit. Aucun sous-système n'appelle un autre directement.

```
Ears ──Speech*──▶ Bus ◀──Mind (intent local, agent LLM, mémoire, rituel)
                   │
        ┌──────────┼──────────┐
        ▼          ▼          ▼
      Scene      Sound       Hud        (Body : ce que Dany voit et entend)
```

### `core/Signal.kt` (contrat, ne pas modifier sans mettre à jour tout le monde)

```kotlin
sealed interface Signal {
    // ---- Ears → Bus
    data class Level(val amp: Float, val bands: FloatArray, val pitch: Float) : Signal   // ~30 Hz, amp 0..1, 8 bandes 0..1, pitch -1..1
    data object SpeechStart : Signal
    data class SpeechPartial(val text: String) : Signal
    data class SpeechFinal(val text: String) : Signal
    data object SpeechIdle : Signal                       // silence, micro toujours ouvert
    // ---- Mind → Bus
    data class Captured(val module: Module, val op: String, val word: String) : Signal  // détection locale instantanée
    data class Thinking(val model: String) : Signal
    data class Executed(val module: Module, val action: String, val summary: String) : Signal
    data class Said(val text: String, val prosody: Prosody) : Signal                     // la ligne de texte + comment la « voix » la dit
    data class Alert(val reason: String) : Signal
    data class Failed(val message: String) : Signal
    data class Proactive(val text: String, val prosody: Prosody, val module: Module?) : Signal // l'agent prend l'initiative
    data class Ritual(val mode: String) : Signal
    // ---- Body → Bus
    data class Touch(val x: Float, val y: Float, val down: Boolean) : Signal
    data class Tilt(val x: Float, val y: Float) : Signal
    data class Launch(val packageName: String) : Signal    // le shell a ouvert une app
    data class Notified(val app: String, val title: String) : Signal
}
enum class Prosody { HELLO, THINK, CONFIRM, NOTED, QUESTION, ALERT }
enum class Module { AUCUN, TACHES, MOOD, NOTES, MEDS, BUDGET, AGENDA }
```

`Bus.emit(signal)` est thread-safe. `Bus.signals` est le flux.

### `core/World.kt` — l'état lisible par la scène (snapshot immuable)

```kotlin
data class World(
    val tasks: List<Task>, val notes: List<Note>, val moods: List<MoodEntry>,
    val medTakenAt: Long?, val budgetTotal: Double, val spent: Double,
    val events: List<Event>, val apps: List<AppSat>,   // AppSat(packageName, label, icon?, usage)
)
```
`WorldRepo.world: StateFlow<World>` (construit sur Room + PackageManager). La scène lit ce flux.

## Sous-systèmes et paquets

| Paquet | Rôle | Écoute | Émet |
|---|---|---|---|
| `ears/` | Micro toujours ouvert. `SpeechRecognizer` sur l'appareil, une seule instance réutilisée, relancée après chaque résultat/silence. Analyse audio (RMS, 8 bandes, hauteur) **pendant** la reconnaissance via `onRmsChanged` + estimation locale ; entre deux écoutes via `AudioRecord` + FFT. | — | `Level`, `SpeechStart`, `SpeechPartial`, `SpeechFinal`, `SpeechIdle` |
| `mind/` | `Intent` (lexical, instantané) ; `Agent` (LLM : Gemini par défaut, Claude en option ; sortie JSON `{dire, prosodie, module, actions[]}`) ; `Memory` (Room + `Modules.apply`) ; `Proactive` (boucle d'initiative : 97 % qui dort, question ouverte à refermer, médicament non confirmé) ; `Ritual` (alarmes exactes). | `SpeechPartial`, `SpeechFinal`, `Notified`, `Ritual` | `Captured`, `Thinking`, `Executed`, `Said`, `Alert`, `Failed`, `Proactive` |
| `scene/` | Rendu GLES 3.0 de `mockup.html` : terrain topographique (contours dans le fragment), disque d'anneaux (ticks, segments de tâches, trou du 97 %), rubans, bloom, tilt-shift, grain, fuite de lumière. Caméra qui orbite, s'avance avec la voix, parallaxe gyroscope. | `Level`, tous les signaux Mind (chaleur, alerte, écoute), `Touch`, `Tilt`, `World` | — |
| `sound/` | Synthèse temps réel (`AudioTrack`, 48 kHz) : nappe, tick par mot, onde d'écoute, balayage, signatures par module, alerte, **voix à prosodie** (source scie + sous-octave, 2 formants, consonnes bruit, contours de hauteur selon `Prosody`). | `Captured`, `Thinking`, `Executed`, `Said`, `Alert`, `SpeechStart`, `Proactive` | — |
| `hud/` | Compose : état (2 lignes), horloge, pile de captures, ligne dite en bas. Rien d'autre. Micro-étiquettes Archivo Narrow / Plex Mono. | tout | `Touch` |
| `shell/` | Launcher : `MainActivity` répond à `HOME`. Glissement vers le haut = constellation des apps (satellites sur l'orbite externe, tirés de `World.apps`), toucher = `Launch`. `NotificationListenerService` → `Notified`. Écran verrouillé + always-on. | `Launch` | `Launch`, `Notified` |

## Règles

- **Rien n'est décoratif** : chaque forme lit `World` ou un `Signal`.
- **Jamais de coupure** : tout changement d'état est un morph ≥ 600 ms.
- **Le micro est ouvert** tant que l'écran est allumé. Il se ferme pendant que la voix synthétique joue.
- **Réponse à plusieurs niveaux** : amplitude → relief et caméra ; hauteur → inclinaison du disque ; bandes → respiration des anneaux et rubans ; mots → captures (braise) ; transitoires → ondes.
- Les clés API restent dans `SharedPreferences` (réglages cachés : appui long) ou `local.properties`. Jamais commitées.
- Fichiers partagés (`MainActivity.kt`, `AndroidManifest.xml`, `build.gradle.kts`) : intégrés par le mainteneur ; les sous-systèmes exposent une classe d'entrée `XxxSystem(context, bus, scope)` avec `start()` / `stop()`.
