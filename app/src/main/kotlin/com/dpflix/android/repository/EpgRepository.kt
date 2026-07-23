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

    private suspend fun load(playlist: Playlist): EpgLoadResult {
        val bytes = loadRawBytes(playlist).getOrElse { error ->
            return EpgLoadResult.Unavailable(error.message ?: "Erreur inconnue")
        }
        return try {
            val programs = EpgXmlParser.parse(bytes)
            EpgLoadResult.Success(programs.groupBy { it.channelTvgId })
        } catch (e: IllegalArgumentException) {
            EpgLoadResult.Unavailable(e.message ?: "Fichier EPG invalide")
        }
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
