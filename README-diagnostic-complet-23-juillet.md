# Diagnostic complet (23 juillet 2026)

Revue ciblée sur les modules les plus sensibles : lecteur vidéo (`player/`), réseau/
Xtream (`network/`), persistance Room (`db/`), navigation (`nav/`), réglages
(`settings/`). Deux dysfonctionnements réels corrigés dans ce zip ; le reste des
modules revus (repository, onboarding, home, epg) n'a rien montré d'anormal.

## 1. OSD (boutons) ne réapparaissait plus au toucher après le plein écran
Voir `README-fix-osd-touch-apres-plein-ecran.md` — `onDown()` manquant dans le
`GestureDetector` de `PlayerScreen.kt`.

## 2. URL de flux Xtream invalide si identifiants avec espace/caractères spéciaux
`XtreamClient.buildStreamUrl` encodait `username`/`password` avec `URLEncoder`
(pensé pour une query string, transforme un espace en `+`) alors qu'ils sont insérés
dans le **chemin** de l'URL (`/live/{user}/{pass}/{id}.ext`). Un `+` dans un segment
de chemin n'est pas décodé en espace par la plupart des serveurs : un compte Xtream
dont l'identifiant ou le mot de passe contient un espace ou certains caractères
spéciaux produisait une URL de flux pointant vers rien → chaîne injouable, alors que
l'authentification (`player_api.php`, vraie query string) réussissait normalement —
symptôme trompeur ("ça s'authentifie mais aucune chaîne ne lit").

Corrigé : nouvelle fonction `encodePathSegment` (`android.net.Uri.encode`, encode un
espace en `%20`) utilisée uniquement pour ces deux segments de chemin.
`playerApiUrl`/`buildEpgUrl` (vraies query strings) restent inchangés, ils étaient
déjà corrects avec `URLEncoder`.

## 3. Réinitialisation complète bloquée sur l'écran lecteur en TV
`onRequestFullReset` n'était pas transmis à `PlayerScreen` dans `DpFlixTvNavHost.kt`
(contrairement à `DpFlixNavHost.kt` côté mobile), qui retombait donc sur son lambda
par défaut ne faisant rien. Une réinitialisation confirmée depuis l'incrustation
Réglages du lecteur plein écran TV vidait bien playlists/réglages
(`SettingsViewModel.confirmReset`) mais laissait l'utilisateur bloqué sur l'écran du
lecteur au lieu de revenir à l'onboarding comme sur mobile.

Corrigé : `onRequestFullReset` ajouté aux paramètres de `ResolvedChannelPlayerTv` et
transmis à `PlayerScreen`, avec navigation vers `Onboarding` (`popUpTo(0)`) au même
titre que côté mobile.

## Fichiers modifiés dans cette passe
- `app/src/main/kotlin/com/dpflix/android/player/PlayerScreen.kt` (point 1)
- `app/src/main/kotlin/com/dpflix/android/network/XtreamClient.kt` (point 2)
- `app/src/main/kotlin/com/dpflix/android/nav/DpFlixTvNavHost.kt` (point 3)

## 4. Cache mémoire EPG non vidé lors d'une réinitialisation complète
`AppRepository.resetAll()` ne vidait pas le cache mémoire `EpgRepository` des
playlists supprimées. Impact réel resté nul jusqu'ici (les nouvelles playlists ont
de nouveaux id, donc pas de collision), juste de la mémoire non libérée — mais
autant repartir sur un cache propre après une réinitialisation complète.

Corrigé : nouvelle méthode `EpgRepository.clearAll()`, appelée depuis
`AppRepository.resetAll()`.

## Fichiers modifiés dans cette passe (mise à jour)
- `app/src/main/kotlin/com/dpflix/android/repository/EpgRepository.kt` (point 4)
- `app/src/main/kotlin/com/dpflix/android/repository/AppRepository.kt` (point 4)

## Points mineurs relevés, non corrigés (impact jugé faible ou déjà assumé)
- `EpgRepository.cache` est une `MutableMap` simple sans synchronisation : un
  `refresh()` déclenché en parallèle par deux écrans différents (cas rare) pourrait
  théoriquement corrompre la map. Pas de cas d'usage concret qui déclenche ça
  actuellement dans le projet.
- `PermissiveTls` (TLS permissif, désactive la vérification de certificat serveur) :
  compromis déjà documenté et assumé dans le fichier lui-même pour un usage IPTV
  personnel — pas un bug, un choix de conception à connaître.
- Dans `PlayerController.onPlayerError`, le cas de récupération
  `ERROR_CODE_BEHIND_LIVE_WINDOW` peut provoquer un très bref passage par l'état
  `Idle` avant de repasser en `Buffering` (ordre des callbacks ExoPlayer), selon les
  appareils — effet cosmétique potentiel (pas de gel ni de crash), non reproduit
  formellement, à surveiller si un flash bref de l'écran de chargement est signalé.
