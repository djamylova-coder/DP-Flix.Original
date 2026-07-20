# DP-Flix — Étape 2b : manifest, permissions et points d'entrée

Cette livraison contient **l'intégralité du projet** (structure Gradle de l'étape 2a inchangée + les ajouts de cette étape 2b). Rien n'a été retiré de l'étape 2a.

## Contenu de cette livraison

### AndroidManifest.xml (`app/src/main/AndroidManifest.xml`)
- **Double point d'entrée**, comme demandé au §7 étape 2b du cahier des charges :
  - `.MainActivity` → mobile (téléphone / tablette), `category.LAUNCHER`
  - `.tv.TvMainActivity` → boîtier Android TV, `category.LEANBACK_LAUNCHER`
- **Permissions déclarées** (§2.1 du cahier des charges) :
  - `INTERNET`, `ACCESS_NETWORK_STATE` (réseau)
  - `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (lecture en arrière-plan)
  - `POST_NOTIFICATIONS` (notification de lecture en cours, Android 13+)
- `uses-feature` `leanback` et `touchscreen` déclarés en `required="false"` : l'app doit s'installer aussi bien sur un téléphone (sans leanback) que sur un boîtier TV (sans écran tactile).
- **Non inclus volontairement à cette étape** : `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (popup système déclenchée depuis le code, pas une simple déclaration manifeste — arrivera avec l'écran Réglages) et la demande runtime de `POST_NOTIFICATIONS` (la déclaration suffit ici, la demande du popup se fera au premier démarrage d'une lecture, étape lecteur).

### Écrans "Hello DP-Flix"
- `MainActivity.kt` (mobile) : écran Compose (Material3) minimal, fond noir.
- `tv/TvMainActivity.kt` (TV) : écran Compose for TV (`tv-material3`) avec deux boutons focusables empilés, focus initial demandé explicitement au lancement — valide la navigation D-pad haut/bas et l'indication visuelle de focus native de `tv-material`.
- Les deux sont indépendants : lancer l'un ne dépend pas de l'autre.

### Ressources ajoutées
- `res/values/themes.xml` : thème `Theme.DpFlix` minimal (fond noir, pas de barre d'action) — juste ce qu'il faut pour habiller les deux activités ; l'app restant 100 % Compose, aucune dépendance Material Components XML ajoutée.
- `res/drawable/ic_launcher_placeholder.xml`, `res/drawable/tv_banner_placeholder.xml` : **placeholders volontaires**, simple pastille/losange rouge sur fond noir. Ce ne sont pas les vraies icônes — celles-ci (adaptive icon mobile + banner TV définitif, fournis par l'utilisateur) arrivent à l'étape 2c, comme prévu par la feuille de route.

## ⚠️ Toujours en attente : le wrapper Gradle
Comme signalé à l'étape 2a, `gradle/wrapper/gradle-wrapper.jar` et les scripts `gradlew`/`gradlew.bat` manquent toujours (pas d'accès réseau ici pour les générer). Rien n'a changé de ce côté — voir `README-etape-2a.md` pour les deux façons de le générer (Codemagic ou `gradle wrapper` en local/Termux).

## Validation de fin d'étape 2b
Une fois le wrapper généré :
- `./gradlew assembleDebug` doit réussir.
- Installé sur mobile → `MainActivity` se lance, écran noir "Hello DP-Flix — mobile".
- Installé sur boîtier Android TV → `TvMainActivity` apparaît dans le launcher TV (LEANBACK_LAUNCHER), se lance, écran noir "Hello DP-Flix — TV" avec deux boutons ; la télécommande (haut/bas) doit déplacer le focus visuellement entre les deux boutons.

Prochaine étape (2c, cahier des charges §7) : vraies icônes (adaptive icon + banner TV), intégration de la vidéo de splash dans `res/raw`, `codemagic.yaml` à la racine + premier build Codemagic réussi.
