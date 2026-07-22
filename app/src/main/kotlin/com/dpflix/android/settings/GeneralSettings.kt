package com.dpflix.android.settings

/**
 * Réglages généraux (§5.6), partie **globale** uniquement.
 *
 * La reprise automatique de la dernière chaîne au démarrage est, selon le cahier des
 * charges, un réglage **par playlist** — elle ne peut donc pas vivre ici (voir le
 * README de cette sous-étape, section "Point ouvert").
 */
data class GeneralSettings(
    /**
     * Plafond/valeur par défaut de qualité vidéo, utilisé tant qu'une playlist ne
     * définit pas la sienne (`Playlist.defaultVideoQuality`, 3a/4a — un override
     * par playlist reste possible, ce champ n'est que le repli global).
     */
    val defaultVideoQualityCap: String? = null,

    /** Playlist activée automatiquement au lancement de l'app, si définie (§5.6). */
    val defaultPlaylistId: String? = null
)
