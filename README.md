# Présence

Assistant vocal perso, Android natif (Kotlin + Compose + OpenGL ES 3.0). La sphère est l'interface.

## État — étape 2 : validation du rendu

| Étape | Statut |
|---|---|
| 1. Squelette Gradle, permissions, immersive | ✅ |
| 2. Rendu : organisme de particules (Tentacules) | ⏳ en validation |
| 3. Réactivité micro (volume, ton, hauteur, transitoires) | ✅ dans l'APK |
| 4. Cerveau : STT → Gemini (JSON) → TTS fr-CA, réglage caché | ✅ dans l'APK |
| 5–6. Modules (Room), rituel | à venir |

## Build

```bash
# JDK 17+ et Android SDK (platform 36, build-tools 36) requis
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Gestes : **tap** = parle-lui (re-tap = annule), **appui long** = clé API Gemini, **swipe horizontal** = comportement de l'organisme (validation).

Clé Gemini : `gemini.api.key=...` dans `local.properties` (jamais commité) ou via l'appui long.

## Architecture du rendu

- **Simulation** (`sim.vert`, transform feedback) : 150k particules naissent sur la coque, sont aspirées vers le noyau par une attraction anisotrope (facteurs X/Y/Z qui respirent chacun à leur rythme), brassées par un champ de turbulence 3D sans divergence et un vortex dont l'axe précesse. Absorbées au noyau, elles renaissent : flux constant, jamais identique.
- **Rendu** (`particle.vert` + `streak.frag`) : chaque particule = une traînée de vitesse, avec persistance d'image (les filaments sont les traînées) et profondeur de champ géométrique.
- **Post** : bloom 7 niveaux, corps sombre, halo, ACES, aberration, vignette, grain.
- `tools/preview/` : banc WebGL2 headless qui exécute **les mêmes shaders** que l'app.

```bash
cd tools/preview && npm i && node capture.mjs '[{"out":"out/a.png","preset":"vortex","t":4,"n":60000}]'
```
