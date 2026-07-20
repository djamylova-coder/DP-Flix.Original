# DP-Flix — Étape 2c-2 : vidéo de splash

Cette livraison contient **l'intégralité du projet** (étapes 2a + 2b inchangées + les ajouts de cette sous-étape 2c-2). Rien n'a été retiré.

## Contenu de cette livraison

### Vidéo (`app/src/main/res/raw/splash.mp4`)
- Fichier fourni (`InShot_20260718_155524084.mp4`, ~3,6 s, H.264 + piste audio), copié tel quel dans `res/raw` sous le nom `splash.mp4` (les noms de ressources `raw` doivent être en minuscules, sans points).

### Écran de splash (`splash/SplashScreen.kt`)
- Composable **commun** au mobile et à la TV (même vidéo, même comportement, §4.1 du cahier des charges).
- Lecture via ExoPlayer (`Media3`, déjà déclaré en dépendance depuis l'étape 2a) + `PlayerView` en `AndroidView`, sans aucun contrôle visible (`useController = false`).
- Son activé (`volume = 1f`), lecture automatique (`playWhenReady = true`).
- À la fin de la vidéo (`Player.STATE_ENDED`), appelle `onSplashFinished()` **une seule fois**, puis libère le lecteur (`DisposableEffect` → `release()`).

### Câblage dans les deux points d'entrée
- `MainActivity.kt` (mobile) et `tv/TvMainActivity.kt` (TV) affichent désormais `SplashScreen` en premier ; une fois celui-ci terminé, ils basculent (état Compose local, pas encore de vraie navigation) vers l'écran "Hello DP-Flix" existant de l'étape 2b.
- Aucune nouvelle dépendance Gradle nécessaire : `media3-exoplayer` et `media3-ui` étaient déjà déclarées à l'étape 2a.

### Volontairement hors périmètre à cette sous-étape
- Pas de navigation "propre" (Compose Navigation) : le passage splash → écran suivant est un simple `if` sur un `mutableStateOf`, suffisant pour valider le comportement. Une vraie navigation arrivera avec l'onboarding/accueil réel (§7).
- Pas d'immersive mode (masquage barre de statut/navigation) : le thème actuel (`Theme.DpFlix`, sans barre d'action) suffit pour l'instant.
- Pas de gestion de focus audio (`AudioManager`/`AudioFocusRequest`) : hors périmètre pour un écran de 3,6 s sans autre lecture concurrente possible à ce stade.

## Validation de fin de sous-étape 2c-2
Une fois le wrapper Gradle généré (voir `README-etape-2a.md`) :
- Sur mobile : lancement de l'app → vidéo de splash en plein écran avec le son → à la fin, retour automatique à "Hello DP-Flix — mobile".
- Sur boîtier TV : même vidéo au lancement de `TvMainActivity` → à la fin, retour automatique à "Hello DP-Flix — TV" (les deux boutons et le focus D-pad de l'étape 2b restent inchangés derrière).

Prochaine sous-étape (2c-3) : `codemagic.yaml` à la racine, workflow `assembleRelease` avec signature → APK signé téléchargeable.
