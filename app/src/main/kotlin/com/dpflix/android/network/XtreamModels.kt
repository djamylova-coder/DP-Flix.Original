package com.dpflix.android.network

import com.dpflix.android.model.Channel

/**
 * Identifiants de connexion à un serveur Xtream Codes (§4.2, Étape 2a du cahier des charges).
 * Correspond aux champs `xtreamServerUrl` / `xtreamUsername` / `xtreamPassword` de
 * [com.dpflix.android.model.Playlist], regroupés ici pour ne pas faire dépendre le
 * client réseau du modèle de persistance complet.
 */
data class XtreamCredentials(
    val serverUrl: String,
    val username: String,
    val password: String
)

/**
 * Informations de compte renvoyées par `player_api.php` lors de l'authentification.
 * `expDateMillis` est converti depuis le timestamp epoch en secondes fourni par l'API
 * (ou `null` si absent/illimité).
 */
data class XtreamUserInfo(
    val username: String,
    val status: String,
    val expDateMillis: Long?,
    val isTrial: Boolean,
    val maxConnections: Int?
)

/**
 * Résultat de la récupération des chaînes live (`get_live_streams`), accompagné de
 * l'EPG auto-détecté "lié au compte" (§4.6 : priorité 2, après l'EPG manuel) —
 * conceptuellement l'équivalent, côté Xtream, du `detectedEpgUrl` de [com.dpflix.android.parser.M3uParseResult]
 * côté M3U.
 */
data class XtreamLiveChannelsData(
    val channels: List<Channel>,
    val detectedEpgUrl: String?
)

/**
 * Résultat générique d'un appel [XtreamClient] : succès typé, ou l'une des erreurs
 * distinguables qu'une UI d'onboarding (§4.2 Étape 2a) doit pouvoir afficher
 * différemment (identifiants refusés, compte suspendu/expiré, serveur en erreur,
 * pas de réseau).
 */
sealed class XtreamResult<out T> {
    data class Success<T>(val data: T) : XtreamResult<T>()

    /** `auth = 0` ou `user_info` absent de la réponse : identifiants incorrects. */
    data class InvalidCredentials(val rawMessage: String? = null) : XtreamResult<Nothing>()

    /** Authentifié mais compte non utilisable (`status` != "Active" : expiré, banni, désactivé...). */
    data class AccountInactive(val status: String) : XtreamResult<Nothing>()

    /** Réponse HTTP non exploitable (code d'erreur, JSON invalide, format inattendu). */
    data class ServerError(val message: String) : XtreamResult<Nothing>()

    /** Impossible de joindre le serveur (pas de connexion, DNS, timeout...). */
    data class NetworkError(val message: String) : XtreamResult<Nothing>()
}
