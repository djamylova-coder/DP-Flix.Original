# DP-Flix — Étape 2a : structure Gradle et modules

## Contenu de cette livraison
- `settings.gradle.kts` : déclaration des dépôts + inclusion du module `app`
- `build.gradle.kts` (racine) : déclaration des plugins (non appliqués à la racine)
- `gradle/libs.versions.toml` : version catalog — toutes les versions et dépendances du projet centralisées
- `app/build.gradle.kts` : configuration du module `app` (SDK 23–35, Compose activé, toutes les dépendances de la §2 du cahier des charges déclarées : Compose, Compose for TV, Media3/ExoPlayer, Room, DataStore, OkHttp/Retrofit)
- `app/src/main/AndroidManifest.xml` : manifest **volontairement minimal** (une seule activité placeholder) — le double point d'entrée mobile/TV et les permissions (§2.1) arrivent à l'étape 2b
- `app/src/main/kotlin/com/dpflix/android/MainActivity.kt` : écran Compose minimal ("Hello DP-Flix") qui ne sert qu'à valider que tout compile
- `gradle.properties`, `.gitignore` : fichiers de configuration standard

`applicationId` / `namespace` choisi par défaut : `com.dpflix.android` (à changer facilement dans `app/build.gradle.kts` si tu préfères garder `com.dpplayer.stream`).

## ⚠️ Une seule étape manuelle restante : le wrapper Gradle
Je ne peux pas télécharger le binaire `gradle-wrapper.jar` (pas d'accès réseau ici). `gradle-wrapper.properties` est déjà en place et pointe vers Gradle 8.9, mais il manque `gradle/wrapper/gradle-wrapper.jar` et les scripts `gradlew` / `gradlew.bat`.

Deux façons de le générer, au choix :
1. **Le plus simple** : laisser Codemagic le faire — dans `codemagic.yaml` (étape 2c), ajouter en première commande `gradle wrapper --gradle-version 8.9` avant `./gradlew assembleRelease` (Codemagic a Gradle préinstallé).
2. **En local/Termux**, si `gradle` est installé : depuis la racine du projet, lancer `gradle wrapper --gradle-version 8.9`, ce qui génère `gradlew`, `gradlew.bat` et le jar automatiquement, puis les committer.

## Validation de fin d'étape 2a
Une fois le wrapper généré (via l'une des deux méthodes ci-dessus), `./gradlew assembleDebug` doit réussir : ça confirme que la structure et toutes les dépendances sont correctes, avant de passer à l'étape 2b (manifest double point d'entrée + permissions + écran minimal mobile/TV).
