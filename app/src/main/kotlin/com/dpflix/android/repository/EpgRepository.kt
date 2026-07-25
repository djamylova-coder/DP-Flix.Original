package com.dpflix.android.repository

import android.content.Context
import com.dpflix.android.model.EpgLoadResult
import com.dpflix.android.model.Playlist
import com.dpflix.android.parser.EpgXmlParser
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Chargement + cache en mémoire du guide EPG par playlist (§4.6, étape 9a — première
 * sous-étape de l'écran EPG, une couche data avant tout écran de grille).
 *
 * ## Généralise deux orchestrations jusqu'ici dupliquées
 * `SettingsViewModel.refreshEpg` (Réglages, 6g-2-2, bouton "Rafraîchir") téléchargeait/
 * lisait et parsait déjà une source EPG, mais uniquement pour valider/dater — sans rien
 * garder du résultat (voir sa doc, "Ne persiste PAS les EpgProgram"). `EpgNowLookup`
 * (OSD "programme en cours", 8b) refaisait la même chose à sa façon pour chaque
 * chaîne affichée, **sans aucun cache** ("chaque appel retélécharge/relit et reparse
 * l'intégralité du guide" — explicitement noté comme à revoir "si un futur écran de
 * grille EPG multiplie les appels sur le même guide", ce qui est exactement le cas ici).
 *
 * Au passage, corrige un bug déjà présent dans `EpgNowLookup` : il visait
 * `Playlist.manualEpgLocalFilePath`, un champ qui n'a jamais existé (le vrai champ est
 * [Playlist.manualEpgLocalFileUri], une `Uri` `content://` à lire via `ContentResolver`,
 * pas un chemin de fichier lisible par `java.io.File`) — resté invisible tant qu'aucun
 * appelant réel ne forçait à recompiler ce chemin de code.
 *
 * ## Cache : invalidation explicite, pas de TTL
 * Cache en mémoire uniquement (un seul [EpgRepository], singleton via
 * [com.dpflix.android.di.AppContainer], donc partagé par tous les écrans/le lecteur pour
 * la durée de vie du process). Pas de minuteur d'expiration automatique : un guide XMLTV
 * change rarement plus de quelques fois par jour, et le cache est de toute façon vidé à
 * chaque relance de l'app (rien de persisté sur disque, cohérent avec le choix déjà pris
 * de ne stocker aucun [com.dpflix.android.model.EpgProgram] en base). [refresh] (appelé
 * par le bouton "Rafraîchir l'EPG" de Réglages) est le seul moyen de forcer un
 * rechargement ; un rafraîchissement périodique en arrière-plan reste possible plus tard
 * si le besoin se confirme à l'usage, non anticipé ici.
 *
 * Un échec de [refresh] écrase aussi un précédent succès en cache : le cahier des charges
 * ne précise rien sur ce cas, mais un guide dont on vient de découvrir qu'il est
 * périmé/injoignable ne devrait pas continuer à laisser croire à un contenu à jour.
 */
class EpgRepository(context: Context) {

    private val appContext = context.applicationContext

    // Fix (2026-07-23) : même correctif que OnboardingViewModel.httpClient — voir sa
    // doc. Un guide EPG servi par le même panel que la playlist peut être bloqué par les
    // mêmes causes (certificat auto-signé, filtrage du User-Agent par défaut d'OkHttp).
    private val httpClient = com.dpflix.android.network.IptvHttpDataSourceFactory.httpClient()

    /** Un seul résultat en cache par playlist — pas d'accès concurrent complexe attendu
     *  (mono-utilisateur, mono-appareil), une `Map` mutable simple suffit. */
    private val cache = mutableMapOf<String, EpgLoadResult>()

    /**
     * Fix (2026-07-25, crash au passage en plein écran sur un Xtream 11 000+ chaînes) :
     * fenêtre de rétention passée à [EpgXmlParser.parse] — voir sa doc pour le détail du
     * mécanisme. Seul l'OSD "programme en cours" (`PlayerScreen`) et le statut de Réglages
     * consomment [EpgLoadResult.Success.programsByChannel] (voir `README-retrait-ecran-
     * guide-tv.md` : l'écran de grille qui aurait pu justifier plusieurs jours a été retiré),
     * donc pas besoin de garder plus qu'une marge confortable autour de l'instant présent.
     *
     * `EPG_KEEP_FUTURE_MILLIS` (48h) reste volontairement généreux malgré ça : le cache
     * n'a pas de TTL (voir la doc de classe) et vit tant que le process tourne — sans cette
     * marge, une session laissée ouverte plusieurs heures verrait son "programme en cours"
     * cesser de se mettre à jour dès que l'horloge dépasse la fenêtre calculée au chargement
     * initial, pour un guide qui ne serait rechargé qu'au prochain "Rafraîchir l'EPG" manuel.
     *
     * Ce correctif traite la CAUSE du crash (des millions de [com.dpflix.android.model.
     * EpgProgram] retenus indéfiniment pour un guide à 11 000+ chaînes, largement plus que
     * ce qu'un mobile bas/moyen de gamme peut allouer) ; le `catch (OutOfMemoryError)` déjà
     * présent dans [load] reste un filet de sécurité pour les cas non couverts (guide encore
     * plus massif que prévu, autre pic mémoire concurrent), mais ne suffisait pas seul :
     * il ne rattrape que l'OOM du tas Dalvik/ART de l'app, pas un process tué directement
     * par le système (low memory killer) quand la mémoire TOTALE de l'appareil est sous
     * pression — ce qui se manifeste exactement comme décrit (l'app disparaît d'un coup,
     * retour à l'accueil du téléphone, sans dialogue de plantage) et que ce correctif vise
     * à éviter en amont en ne laissant plus la mémoire monter aussi haut.
     */
    private val epgKeepPastMillis = 3L * 60 * 60 * 1000
    private val epgKeepFutureMillis = 48L * 60 * 60 * 1000

    /** Résultat déjà en cache pour [playlistId], ou `null` si jamais chargé depuis le
     *  lancement de l'app (voir [getOrLoad]/[refresh]). */
    fun cached(playlistId: String): EpgLoadResult? = cache[playlistId]

    /** [cached] s'il existe déjà, sinon [refresh] — le point d'entrée normal pour un
     *  simple affichage (grille EPG à venir en 9b+, OSD "programme en cours" depuis 8b) :
     *  ne force jamais un rechargement réseau juste pour regarder l'écran. */
    suspend fun getOrLoad(playlist: Playlist): EpgLoadResult =
        cache[playlist.id] ?: refresh(playlist)

    /**
     * Recharge la source EPG effective de [playlist] (téléchargement URL ou lecture
     * fichier local, priorité manuel > auto-détecté, §4.6) et met à jour le cache avec
     * le résultat — succès ou échec, voir la doc de la classe.
     */
    suspend fun refresh(playlist: Playlist): EpgLoadResult {
        val result = load(playlist)
        cache[playlist.id] = result
        return result
    }

    /** Vide le cache d'une playlist précise (ex. sa source EPG manuelle vient de changer,
     *  l'ancien contenu en cache n'a plus rien à voir). Sans effet si rien n'est en cache. */
    fun invalidate(playlistId: String) {
        cache.remove(playlistId)
    }

    /** Vide tout le cache (toutes playlists confondues). À appeler par
     *  [com.dpflix.android.repository.AppRepository.resetAll] lors d'une réinitialisation
     *  complète (§5.6) : sans ça, les guides EPG des playlists supprimées restaient en
     *  mémoire pour des `playlistId` qui n'existent plus (orphelins, mémoire non libérée -
     *  sans impact visible puisque les nouvelles playlists ont de nouveaux id, mais autant
     *  repartir sur un cache propre). */
    fun clearAll() {
        cache.clear()
    }

    private suspend fun load(playlist: Playlist): EpgLoadResult = try {
        // Le téléchargement/la lecture du fichier brut (`loadRawBytes`, ex. `response
        // .body?.bytes()` dans `downloadUrl`) peut à lui seul saturer la mémoire sur un
        // guide de plusieurs dizaines de Mo — avant même d'atteindre `EpgXmlParser.parse`.
        // Les deux étapes sont donc couvertes par le même bloc `try`, plutôt que de ne
        // protéger que le parsing.
        val bytes = loadRawBytes(playlist).getOrElse { error ->
            return EpgLoadResult.Unavailable(error.message ?: "Erreur inconnue")
        }
        // Fix (2026-07-25) : `EpgXmlParser.parse` est un parsing XML pur CPU (pas d'IO),
        // potentiellement des centaines de milliers de <programme> pour un panel à
        // plusieurs dizaines de milliers de chaînes. `load` était encore appelé, au
        // moment de ce correctif, depuis l'écran Guide TV (`EpgGuideViewModel.init` via
        // `viewModelScope.launch { ... }`, retiré depuis, voir `DpFlixDestination`) —
        // dispatcher par défaut `Main.immediate`, donc sans ce `withContext` tout ce
        // parsing s'exécutait sur le thread UI, gelant l'app le temps du parsing complet
        // (plusieurs secondes, voire plus, sur un gros guide). Reste valable pour les
        // appelants actuels (OSD "programme en cours" du lecteur, Réglages → EPG) :
        // ce sont aussi des `ViewModel`/`Composable` sur Main, le correctif s'applique
        // ici une fois pour toutes plutôt qu'à charge de chaque appelant.
        // `Dispatchers.Default` (pas `IO`) car c'est un travail CPU, pas une attente
        // réseau/disque.
        val nowMillis = System.currentTimeMillis()
        val programs = withContext(Dispatchers.Default) {
            EpgXmlParser.parse(
                bytes,
                keepFromMillis = nowMillis - epgKeepPastMillis,
                keepUntilMillis = nowMillis + epgKeepFutureMillis
            )
        }
        EpgLoadResult.Success(programs.groupBy { it.channelTvgId })
    } catch (e: IllegalArgumentException) {
        EpgLoadResult.Unavailable(e.message ?: "Fichier EPG invalide")
    } catch (e: OutOfMemoryError) {
        // Un panel avec des dizaines de milliers de chaînes peut exposer un XMLTV de
        // plusieurs dizaines de Mo / centaines de milliers de <programme> : les octets
        // bruts puis la liste `programs` construite par le parseur sont entièrement en
        // mémoire (§ doc de classe). OutOfMemoryError n'est pas une Exception (c'est une
        // Error), donc sans ce catch elle remontait non rattrapée à travers l'appelant
        // (OSD/Réglages) et tuait tout le process — pas seulement l'EPG. On ne
        // conserve aucune référence à `bytes`/`programs` en sortant de ce bloc, pour
        // laisser le GC récupérer la mémoire au plus vite plutôt que de retenter quoi
        // que ce soit.
        EpgLoadResult.Unavailable("Guide EPG trop volumineux pour être chargé en mémoire")
    }

    /** Même ordre de priorité que l'ex-`SettingsViewModel.resolveEpgSource` (6g-2-2) :
     *  fichier local importé d'abord, puis [Playlist.effectiveEpgUrl] (qui gère déjà lui-même
     *  manuel > auto-détecté pour le cas URL, voir sa doc). */
    private suspend fun loadRawBytes(playlist: Playlist): Result<ByteArray> = withContext(Dispatchers.IO) {
        val localUri = playlist.manualEpgLocalFileUri
        if (!localUri.isNullOrBlank()) {
            return@withContext readLocalFile(localUri)
        }

        val url = playlist.effectiveEpgUrl
            ?: return@withContext Result.failure(IOException("Aucune source EPG disponible pour cette playlist"))
        downloadUrl(url)
    }

    /** Lecture via `ContentResolver` (Uri `content://` issue du Storage Access Framework,
     *  permission persistante déjà prise à l'import — `SettingsViewModel.setManualEpgLocalFile`,
     *  6g-2-1). Erreur explicite invitant à réimporter si la permission a été perdue malgré
     *  tout (arrive avec certains fournisseurs de stockage amovible). */
    private fun readLocalFile(uriString: String): Result<ByteArray> = try {
        val bytes = appContext.contentResolver.openInputStream(android.net.Uri.parse(uriString))
            ?.use { it.readBytes() }
            ?: return Result.failure(IOException("Impossible de lire le fichier EPG sélectionné"))
        Result.success(bytes)
    } catch (e: SecurityException) {
        Result.failure(IOException("Permission perdue sur le fichier EPG sélectionné : réimportez-le"))
    } catch (e: IOException) {
        Result.failure(IOException(e.message ?: "Impossible de lire le fichier EPG sélectionné"))
    }

    private fun downloadUrl(url: String): Result<ByteArray> = try {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Result.failure(IOException("Le serveur a répondu avec le code ${response.code}"))
            } else {
                Result.success(response.body?.bytes() ?: ByteArray(0))
            }
        }
    } catch (e: IOException) {
        Result.failure(IOException(e.message ?: "Erreur réseau"))
    } catch (e: IllegalArgumentException) {
        Result.failure(IOException("URL EPG invalide"))
    }
}
