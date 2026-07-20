# DP-Flix — Étape 2c-3 : codemagic.yaml et build signé

Cette livraison contient **l'intégralité du projet** (étapes 2a + 2b + 2c-2 inchangées + les ajouts de cette sous-étape 2c-3). Rien n'a été retiré.

## Contenu de cette livraison

### `codemagic.yaml` (racine du projet)
- Un seul workflow `android-release`, déclenché à chaque `git push` sur la branche `main` (§9 du cahier des charges).
- Étape 1 : `gradle wrapper --gradle-version 8.9` — génère `gradlew` + le jar du wrapper, absents du dépôt depuis l'étape 2a (Codemagic a Gradle préinstallé, voir `README-etape-2a.md`).
- Étape 2 : `./gradlew assembleRelease` — build de l'APK release.
- Artefact récupéré à la fin du build : `app/build/outputs/apk/release/*.apk` (téléchargeable depuis l'interface Codemagic).

### `app/build.gradle.kts` — signature release
- Ajout d'un `signingConfigs.release` qui lit 4 variables d'environnement : `CM_KEYSTORE_PATH`, `CM_KEYSTORE_PASSWORD`, `CM_KEY_ALIAS`, `CM_KEY_PASSWORD`.
- `buildTypes.release` utilise désormais ce `signingConfig` : sans keystore configuré, `assembleRelease` échoue proprement (pas d'APK non signé produit par erreur).
- Ces 4 variables ne sont **pas** à définir à la main : Codemagic les injecte automatiquement grâce au bloc `android_signing: [dpflix_keystore]` dans `codemagic.yaml`, une fois le keystore uploadé côté Codemagic (voir ci-dessous).

## ⚠️ Deux actions manuelles restantes (impossibles à faire depuis ici)

### 1. Générer un keystore de signature
Je n'ai pas d'accès réseau ni de `keytool` disponible ici. À faire une fois, en Termux ou sur n'importe quelle machine avec un JDK :

```bash
keytool -genkeypair -v \
  -keystore dpflix-release.jks \
  -alias dpflix \
  -keyalg RSA -keysize 2048 -validity 10000
```
Choisis un mot de passe de keystore et un mot de passe de clé (peuvent être identiques), et note bien l'alias utilisé (`dpflix` ci-dessus).
⚠️ Garde ce fichier `.jks` et ses mots de passe en lieu sûr : le perdre signifie ne plus jamais pouvoir republier une mise à jour signée avec la même identité.

### 2. Uploader ce keystore dans Codemagic
Dans l'interface Codemagic : **Teams → Code signing identities → Android keystores → Add keystore**.
- Uploader le fichier `dpflix-release.jks`.
- Renseigner le mot de passe du keystore, l'alias, le mot de passe de la clé.
- Nommer cette référence **exactement** `dpflix_keystore` (c'est ce nom qui est référencé dans `codemagic.yaml`, ligne `android_signing:`).

Une fois ces deux actions faites, tout push sur `main` déclenche automatiquement un build produisant un APK signé téléchargeable — aucune autre configuration nécessaire.

## Volontairement hors périmètre à cette sous-étape
- Pas de variante `bundleRelease` (AAB) : inutile tant que la distribution Play Store n'est pas décidée (§8 du cahier des charges — point encore ouvert).
- Pas de publication automatique (email/Slack/Play Store) à la fin du build : simple récupération manuelle de l'APK depuis Codemagic pour l'instant, cohérent avec l'usage personnel (§2).
- Pas de `isMinifyEnabled = true` / ProGuard : hors périmètre à ce stade du squelette.

## Validation de fin de sous-étape 2c-3
Ce fichier seul ne peut pas encore être validé « vert » : il faut le keystore uploadé (ci-dessus) **et** un vrai push sur GitHub pour déclencher Codemagic. C'est précisément l'objet de la sous-étape suivante :

Prochaine sous-étape (2c-4) : premier push réel + premier build Codemagic réussi → APK signé récupéré avec succès, comme validation de fin d'étape 2c.
