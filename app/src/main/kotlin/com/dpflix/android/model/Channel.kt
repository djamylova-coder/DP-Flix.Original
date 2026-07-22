package com.dpflix.android.model

/**
 * Une chaîne appartenant à une playlist (§4.4 : rangées horizontales groupées par catégorie).
 *
 * Commune aux deux sources (M3U et Xtream) : le parseur M3U et le client Xtream
 * (étapes 3b / 3c) produisent tous les deux des `Channel`, ce qui permet à l'accueil
 * et au lecteur de ne jamais avoir à distinguer la provenance de la chaîne.
 */
data class Channel(
    val id: String,
    val playlistId: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val category: String? = null,

    /** Identifiant utilisé pour le rattachement EPG (`tvg-id` en M3U, `epg_channel_id` en Xtream). */
    val tvgId: String? = null,

    /** Numéro d'origine tel que fourni par la source (ordre de la playlist / attribut `tvg-chno`). */
    val originalNumber: Int? = null,

    /** Numéro personnalisé défini par l'utilisateur pour CETTE playlist (§5.3). Prioritaire sur originalNumber. */
    val customNumber: Int? = null
) {
    /** Numéro affiché à l'écran : priorité à la numérotation personnalisée. */
    val displayNumber: Int?
        get() = customNumber ?: originalNumber
}

/**
 * Regroupement de chaînes par catégorie, utilisé pour construire les rangées
 * horizontales de l'écran d'accueil (§4.4). Simple structure de présentation,
 * pas une entité persistée.
 */
data class ChannelCategory(
    val name: String,
    val channels: List<Channel>
)
