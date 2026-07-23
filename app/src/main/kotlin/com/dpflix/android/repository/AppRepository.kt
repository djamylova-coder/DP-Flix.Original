package com.dpflix.android.repository

import kotlinx.coroutines.flow.first

/**
 * Point d'entrée unique consommé par la couche métier partagée (3a-3d), l'UI (étape 6/7)
 * et le lecteur (étape 5) : compose `PlaylistRepository` (Room, 4a), `ChannelRepository`
 * (Room, 4b), `SettingsRepository` (DataStore, 4c) et [EpgRepository] (cache mémoire,
 * étape 9a), et porte la seule logique qui traverse réellement plusieurs de ces couches
 * (réinitialisation complète, activation de la playlist par défaut au démarrage).
 */
class AppRepository(
    val playlists: PlaylistRepository,
    val channels: ChannelRepository,
    val settings: SettingsRepository,
    val epg: EpgRepository
) {

    /**
     * Réinitialisation complète (§5.6 "Réinitialisation complète") : playlists + chaînes
     * (cascade FK, 4b) + réglages globaux (4c) + cache mémoire EPG (9a).
     *
     * Ne vide **pas** le cache disque ExoPlayer (`MediaCacheProvider`, existe depuis
     * l'étape 5c) : ce module vit dans le package `player`, qui ne dépend aujourd'hui de
     * rien dans `repository` — lui faire l'inverse ici inverserait cette dépendance sans
     * réel besoin. C'est donc l'appelant (`SettingsViewModel.confirmReset`, étape 6d) qui
     * orchestre les deux appels côte à côte.
     *
     * Fix (2026-07-23) : `epg.clearAll()` ajouté — sans lui, les guides EPG restaient en
     * cache mémoire pour des `playlistId` qui n'existent plus après la suppression des
     * playlists (orphelins sans impact visible, juste de la mémoire non libérée).
     */
    suspend fun resetAll() {
        playlists.deleteAll()
        settings.resetAll()
        epg.clearAll()
    }

    /**
     * À appeler une fois au lancement de l'app (§5.6 "Playlist par défaut au lancement").
     * Ne fait rien si une playlist est déjà active (ex. l'app n'a pas été tuée depuis la
     * dernière session) : ce réglage ne sert qu'à choisir la playlist de départ, jamais à
     * forcer une bascule pendant l'utilisation.
     */
    suspend fun applyDefaultPlaylistOnStartup() {
        if (playlists.observeActive().first() != null) return

        val defaultPlaylistId = settings.generalSettings.first().defaultPlaylistId
        val target = defaultPlaylistId?.let { playlists.getById(it) }
            ?: playlists.observeAll().first().firstOrNull()

        target?.let { playlists.setActivePlaylist(it.id) }
    }
}
