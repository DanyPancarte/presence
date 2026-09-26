# Présence

Assistant vocal perso, Android natif (Kotlin + Compose + OpenGL ES 3.0). La sphère est l'interface.

## État — étape 2 : validation du rendu

| Étape | Statut |
|---|---|
| 1. Squelette Gradle, permissions, immersive | ✅ |
| 2. Rendu de la sphère (4 variantes) | ⏳ en validation — voir `docs/captures/` |
| 3–6. Micro, cerveau, modules, rituel | à venir |

## Build

```bash
# JDK 17+ et Android SDK (platform 36, build-tools 36) requis
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Gestes de test (temporaires) : **tap** = cycle des états, **swipe horizontal** = variante de filaments.

## Architecture du rendu

- `sphere/` — génération procédurale des filaments (Kotlin pur, JVM) : champ de densité « carte de nuit », 4 styles de croissance, noyau spirale.
- `app/src/main/assets/shaders/` — filaments instanciés + DOF géométrique, bloom 7 niveaux, composite (halo, corps sombre, ACES, aberration, vignette, grain).
- `tools/preview/` — banc WebGL2 headless qui exécute **les mêmes shaders** pour produire des captures sans téléphone :

```bash
./gradlew :sphere-preview:run --args="$PWD/tools/preview/data"
cd tools/preview && npm i && node capture.mjs '[{"out":"out/a.png","variant":"metropole","t":12}]'
```
