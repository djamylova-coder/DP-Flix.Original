package com.dpflix.android.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Représentation Room de [com.dpflix.android.model.Channel] (modèle métier, étape 3a).
 *
 * ## `id` ≠ l'`id` produit par le parseur (3b/3c)
 * Rappel du README 3c : `M3uParser` génère un UUID aléatoire à **chaque** parsing, alors
 * que `XtreamClient` construit un id déterministe. Utiliser tel quel l'`id` du parseur
 * comme clé primaire ferait perdre la numérotation personnalisée (§5.3) et la dernière
 * chaîne regardée (§4.3) de toute chaîne M3U dès le rafraîchissement suivant (reconnexion,
 * changement réseau, relance de l'app) : une nouvelle ligne serait insérée à la place de
 * l'ancienne, l'ancienne ne serait jamais mise à jour.
 *
 * Cette entité utilise donc comme clé primaire une **clé stable calculée**
 * (`ChannelMapper.stableId()` : `"$playlistId:${tvgId ?: streamUrl}"`), la même pour une
 * chaîne donnée d'un parsing à l'autre, que la source soit M3U ou Xtream. Le champ `id` du
 * modèle métier issu du parseur n'est donc jamais persisté tel quel ; voir `ChannelMapper`.
 */
@Entity(
    tableName = "channels",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("playlistId")]
)
data class ChannelEntity(
    @PrimaryKey val id: String,
    val playlistId: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String?,
    val category: String?,

    /** Rattachement EPG (`tvg-id` M3U / `epg_channel_id` Xtream), §4.6. */
    val tvgId: String?,

    /** Numéro fourni par la source (ordre playlist / `tvg-chno`). */
    val originalNumber: Int?,

    /** Numéro personnalisé (§5.3), prioritaire sur `originalNumber`. Préservé d'un rafraîchissement à l'autre. */
    val customNumber: Int?
)
