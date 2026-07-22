package com.dpflix.android.db

import com.dpflix.android.db.entity.ChannelEntity
import com.dpflix.android.model.Channel

/**
 * Identifiant stable d'une chaîne, indépendant de l'`id` transitoire produit par le
 * parseur (voir `ChannelEntity`). Basé sur `tvgId` quand disponible (rattachement EPG,
 * §4.6 — donc déjà l'identifiant le plus fiable côté source), sinon sur `streamUrl`
 * (déterministe pour Xtream comme pour la plupart des flux M3U statiques).
 *
 * Préfixé par `playlistId` : deux playlists différentes pointant par coïncidence vers
 * la même URL de flux ne doivent jamais partager une ligne (isolation totale, §4.3).
 */
fun Channel.stableId(): String {
    val key = tvgId?.takeIf { it.isNotBlank() } ?: streamUrl
    return "$playlistId:$key"
}

/**
 * Conversion modèle métier → entité Room. Fonction pure, aucune IO.
 *
 * `id` est **recalculé** via [stableId] : l'`id` transitoire du modèle métier
 * (aléatoire pour M3U, cf. `ChannelEntity`) n'est jamais persisté tel quel.
 */
fun Channel.toEntity(): ChannelEntity = ChannelEntity(
    id = stableId(),
    playlistId = playlistId,
    name = name,
    streamUrl = streamUrl,
    logoUrl = logoUrl,
    category = category,
    tvgId = tvgId,
    originalNumber = originalNumber,
    customNumber = customNumber
)

/**
 * Conversion entité Room → modèle métier. L'`id` du `Channel` reconstruit est donc la
 * clé stable, pas un `id` de parsing : c'est cette valeur qui doit être utilisée partout
 * en aval (dernière chaîne regardée §4.3, sélection à l'écran §4.4) dès qu'une chaîne a
 * transité par la persistance.
 */
fun ChannelEntity.toDomain(): Channel = Channel(
    id = id,
    playlistId = playlistId,
    name = name,
    streamUrl = streamUrl,
    logoUrl = logoUrl,
    category = category,
    tvgId = tvgId,
    originalNumber = originalNumber,
    customNumber = customNumber
)
