# Présence

Assistant vocal perso, Android natif (Kotlin + Compose + OpenGL ES 3.0). La sphère est l'interface.

## État — étape 2 : validation du rendu

| Étape | Statut |
|---|---|
| 1. Squelette Gradle, permissions, immersive | ✅ |
| 2. Rendu : organisme de particules (Tentacules) | ⏳ en validation |
| 3. Réactivité micro (volume, ton, hauteur, transitoires) | ✅ dans l'APK |
| 4–6. Cerveau, modules, rituel | à venir |

## Build

```bash
# JDK 17+ et Android SDK (platform 36, build-tools 36) requis
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Gestes de test (temporaires) : **tap** = cycle des états, **swipe horizontal** = comportement de l'organisme.

## Architecture du rendu

- **Simulation** (`sim.vert`, transform feedback) : 150k particules naissent sur la coque, sont aspirées vers le noyau par une attraction anisotrope (facteurs X/Y/Z qui respirent chacun à leur rythme), brassées par un champ de turbulence 3D sans divergence et un vortex dont l'axe précesse. Absorbées au noyau, elles renaissent : flux constant, jamais identique.
- **Rendu** (`particle.vert` + `streak.frag`) : chaque particule = une traînée de vitesse, avec persistance d'image (les filaments sont les traînées) et profondeur de champ géométrique.
- **Post** : bloom 7 niveaux, corps sombre, halo, ACES, aberration, vignette, grain.
- `tools/preview/` : banc WebGL2 headless qui exécute **les mêmes shaders** que l'app.

```bash
cd tools/preview && npm i && node capture.mjs '[{"out":"out/a.png","preset":"vortex","t":4,"n":60000}]'
```
