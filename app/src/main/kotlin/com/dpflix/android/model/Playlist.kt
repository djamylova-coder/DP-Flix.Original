package com.dpflix.android.model

import java.util.UUID

/**
 * Type de playlist supporté (§4.2 du cahier des charges).
 * Le portail Stalker est hors périmètre.
 */
enum class PlaylistType {
    M3U,
    XTREAM
}

/**
 * Une playlist telle que gérée dans Réglages → Playlists (§4.3).
 * Max 5 playlists en base (contrainte appliquée au niveau du repository, pas ici).
 *
 * Isolation totale par playlist (§4.3) : EPG, numérotation des chaînes et dernière
 * chaîne regardée sont donc portés par CETTE classe, pas par un état global de l'app.
 */
data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: PlaylistType,
    val isActive: Boolean = false,
    val sortOrder: Int = 0,

    // --- Spécifique M3U (§4.2, Étape 2b) ---
    val m3uUrl: String? = null,
    val m3uLocalFilePath: String? = null,

    // --- Spécifique Xtream Codes (§4.2, Étape 2a) ---
    val xtreamServerUrl: String? = null,
    val xtreamUsername: String? = null,
    val xtreamPassword: String? = null,
    val includeTvChannels: Boolean = true,

    // --- EPG (§4.6 + §5.4) : priorité manuel > auto-détecté > aucun ---
    val manualEpgUrl: String? = null,
    val autoDetectedEpgUrl: String? = null,

    /**
     * Fichier EPG local importé via le sélecteur système (Storage Access Framework),
     * en alternative à [manualEpgUrl] (§5.4, sous-étape 6g-2-1) — même logique
     * "URL ou fichier, mutuellement exclusifs" que [m3uUrl]/[m3uLocalFilePath].
     *
     * Contrairement à [m3uLocalFilePath], ce n'est PAS un chemin de fichier recopié dans
     * le stockage privé de l'app : c'est l'`Uri` `content://` retournée par le sélecteur,
     * conservée telle quelle (avec prise de permission persistante côté appelant, voir
     * `SettingsViewModel.setManualEpgLocalFile`). Aucun chargement/parsing ne lit encore ce
     * champ à cette sous-étape — arrivera avec le bouton "Rafraîchir l'EPG" en 6g-2-2, qui
     * lira le contenu à la demande plutôt qu'une copie recopiée par avance : contrairement
     * au M3U importé pendant l'onboarding (parsé immédiatement), un fichier EPG manuel peut
     * rester en place sans être rafraîchi avant longtemps, une copie recopiée à l'import
     * deviendrait silencieusement périmée si le fichier source est modifié entre-temps.
     */
    val manualEpgLocalFileUri: String? = null,


    // --- État propre à la playlist (§4.3, §5.6) ---
    val lastWatchedChannelId: String? = null,
    val defaultVideoQuality: String? = null,

    /**
     * Horodatage (epoch millis) du dernier chargement EPG réussi pour cette playlist,
     * affiché par Réglages → Guide TV (EPG) (§5.4 : "date de dernière mise à jour").
     * `null` tant qu'aucun chargement n'a encore eu lieu — c'est le cas de TOUTES les
     * playlists à cette sous-étape (6g-1) : ce champ existe déjà dans le modèle, mais
     * rien ne l'écrit encore. Le rafraîchissement réel (bouton "Rafraîchir l'EPG",
     * chargement manuel URL/fichier) arrive à la sous-étape 6g-2.
     */
    val lastEpgUpdateMillis: Long? = null,

    /**
     * Reprise automatique de la dernière chaîne au démarrage (§5.6), interrupteur
     * distinct de [lastWatchedChannelId] : ce dernier peut être renseigné sans que
     * la reprise auto soit activée (ex. utilisateur qui désactive temporairement).
     */
    val resumeLastChannelOnStart: Boolean = true,

    // --- Numérotation des chaînes personnalisée (§5.3), par playlist ---
    val useCustomChannelNumbering: Boolean = false
) {
    init {
        require(name.isNotBlank()) { "Le nom de la playlist ne peut pas être vide" }
        when (type) {
            PlaylistType.M3U -> require(!m3uUrl.isNullOrBlank() || !m3uLocalFilePath.isNullOrBlank()) {
                "Une playlist M3U doit avoir une URL ou un fichier local"
            }
            PlaylistType.XTREAM -> require(
                !xtreamServerUrl.isNullOrBlank() && !xtreamUsername.isNullOrBlank() && !xtreamPassword.isNullOrBlank()
            ) { "Une playlist Xtream doit avoir un serveur, un utilisateur et un mot de passe" }
        }
    }

    /**
     * EPG effectivement utilisé pour cette playlist, selon la priorité du §4.6.
     * Ne couvre que le cas URL : quand le statut manuel provient d'un fichier local
     * ([manualEpgLocalFileUri]), il n'y a pas d'URL à retourner ici — le futur chargeur
     * EPG (6g-2-2) devra distinguer les deux cas plutôt que de s'appuyer uniquement sur
     * cette propriété.
     */
    val effectiveEpgUrl: String?
        get() = manualEpgUrl?.takeIf { it.isNotBlank() } ?: autoDetectedEpgUrl?.takeIf { it.isNotBlank() }

    /** Statut affiché par Réglages → Guide TV (EPG), §5.4 : "auto-détecté / manuel / aucun". */
    val epgStatus: EpgStatus
        get() = when {
            !manualEpgUrl.isNullOrBlank() || !manualEpgLocalFileUri.isNullOrBlank() -> EpgStatus.MANUAL
            !autoDetectedEpgUrl.isNullOrBlank() -> EpgStatus.AUTO_DETECTED
            else -> EpgStatus.NONE
        }
}

/** Voir [Playlist.epgStatus]. */
enum class EpgStatus {
    AUTO_DETECTED,
    MANUAL,
    NONE
}
