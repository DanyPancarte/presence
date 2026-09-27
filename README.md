# Présence

Assistant vocal perso, Android natif (Kotlin + Compose + OpenGL ES 3.0). Usage strictement perso.
L'hologramme **est** l'interface : particules 3D plein écran, overlays contextuels style Cyberpunk 2077.

## État

| Étape | Statut |
|---|---|
| 1. Squelette, permissions, plein écran immersif | ✅ |
| 2. Rendu : hologramme de particules (5 formations, une par état) | ✅ |
| 3. Micro : niveau, 24 bandes (EQ radio), transitoires → glitch | ✅ |
| 4. Cerveau : STT fr-CA hors ligne → Gemini (JSON) → TTS fr-CA | ✅ |
| 5. Modules Room : tâches 97 %, notes, mood, médicament, budget, agenda, questions ouvertes | ✅ |
| 6. Rituel : relances 17h30 / 20h30 lun–ven, 10h dim, médicament 8h, plein écran | ✅ |

## Build de l'APK (ligne de commande)

Prérequis : JDK 17+, Android SDK avec `platforms;android-36` et `build-tools;36.0.0`.

```bash
# 1. Pointer le SDK et (optionnel) la clé Gemini — jamais commité
cat > local.properties <<EOF
sdk.dir=/chemin/vers/Android/Sdk
gemini.api.key=AIza...
EOF

# 2. Builder
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## Installer sur le Pixel 10 Pro XL

1. Sur le Pixel : **Paramètres → À propos → taper 7× sur « Numéro de build »** (mode dev), puis **Options pour les développeurs → Débogage USB**.
2. Brancher en USB, accepter l'empreinte de l'ordi sur le téléphone.
3. `adb install -r app/build/outputs/apk/debug/app-debug.apk`

Sans câble : envoyer l'APK sur le téléphone (Drive, mail), l'ouvrir, autoriser « Installer des apps inconnues » pour l'app d'où il vient, puis « Installer quand même » si Play Protect rechigne (APK non signé Store).

## Permissions à accorder à la main

| Quand | Quoi | Pourquoi |
|---|---|---|
| Au premier lancement (pop-up) | **Micro** | Réactivité de l'hologramme + reconnaissance vocale |
| Au premier lancement (pop-up) | **Notifications** | Le rituel ouvre l'app en plein écran |
| Au premier lancement (pop-up) | **Appareils à proximité** (Bluetooth) | Voix dans les écouteurs |
| Paramètres → Apps → Présence → Notifications | **Notifications plein écran** (Android 14+) | Sinon la relance reste une bannière |
| Paramètres → Apps → Présence → Batterie | **Sans restriction** | Les alarmes à 17h30 / 20h30 / 8h doivent réveiller le téléphone |
| Dans l'app : **appui long 3 s** | **Clé API** : Gemini (aistudio.google.com/apikey, palier gratuit sans carte) ou Claude (console.anthropic.com, payant) | Le cerveau. Reste sur le téléphone. Modèle Gemini par défaut : `gemini-3.8-flash` ; si la clé ne peut pas l'appeler, l'app découvre toute seule le meilleur flash disponible. |

Reconnaissance hors ligne : Paramètres → Système → Langues → Saisie vocale → ajouter **Français (Canada)** au pack hors ligne.

## Gestes

- **Mains libres** : le micro est toujours ouvert quand l'app est à l'écran. Parler suffit : VEILLE → ÉCOUTE (dès la première syllabe) → RÉFLEXION → RÉPONSE, puis le micro se rouvre.
  - Ce qui déclenche une réponse : son nom (« Présence »), un mot de module (tâche, note, budget, pilule, vendredi…), une question, ou une vraie phrase (≥ 5 mots). Un fragment de deux mots est ignoré (affiché « ignoré »). Pendant 25 s après une réponse, tout passe.
- **Tap** : interrompt (voix ou analyse) et rouvre le micro.
- **Swipe vertical** : aperçu du module suivant (Tâches → Mood → Notes → Méds → Budget → Agenda).
- **Doigt posé / glissé** : repousse les particules.
- **Appui long** : réglages cachés (clé et modèle Gemini).

## Tester chaque module (à voix haute, après un tap)

| Module | Dis quelque chose comme | Ce que tu dois voir |
|---|---|---|
| **Tâches 97 %** | « Note que le site portfolio est rendu à 97, il reste le formulaire » | Panneau Tâches, la ligne en jaune, barre à 97 % |
| **Notes** | « Idée : sample de Nas sur le beat du track 4 » | Panneau Note vocale, texte enregistré |
| **Mood** | « Je suis à moins un aujourd'hui, journée plate » | Panneau Mood, carré rouge ajouté |
| **Médicament** | « Oui je l'ai pris à huit heures douze » | Anneau qui se ferme, heure affichée |
| **Budget** | « J'ai dépensé 68 $ au resto » puis « je prends les Jordan à 260 ? » | Marge qui baisse ; la 2ᵉ déclenche ALERTE rouge |
| **Agenda** | « Mets le show de vendredi 20 h » | Panneau Agenda avec l'événement |
| **Rituel** | Attends 17h30 un jour de semaine (ou change l'heure du téléphone) | L'app s'ouvre seule, salue, écoute |

Le module s'affiche aussi sans parler : swipe vertical.

## Architecture

- `render/` — `HoloRenderer` : simulation par transform feedback (90k particules), points additifs, bloom quart de résolution, composite (glitch, aberration, scanlines, vignette, grain). Shaders dans `app/src/main/assets/shaders/`.
- `audio/AudioReactor` — AudioRecord 16 kHz + FFT : niveau, 24 bandes, transitoires.
- `voice/Speech` — SpeechRecognizer + TextToSpeech fr-CA.
- `brain/` — `Llm` (Gemini ou Claude, REST, sortie JSON), `Persona`, `Intent` (détection lexicale instantanée sur la transcription partielle), `Conversation` (la boucle, la bande d'état et la scène observée par Compose).
- `voice/RadioVoice` — la voix Google passe dans une chaîne radio (bande passante, saturation, souffle, squelch) avant d'être jouée.
- `data/` — Room (`Db.kt`) et `Modules` (actions → base, contexte → agent).
- `ui/` — palette et panneaux Cyberpunk (`Theme.kt`, `Panels.kt`).
- `ritual/` — alarmes exactes + full-screen intent + reboot.
- `tools/preview/mockup.html` — jumeau WebGL2 du rendu et des panneaux, pour itérer sans téléphone.

Choix : **AlarmManager** plutôt que WorkManager pour le rituel, parce qu'une relance à 17h30 pile avec réveil plein écran exige l'heure exacte (WorkManager tolère ± plusieurs minutes).
