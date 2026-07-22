package com.dpflix.android.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.dpflix.android.db.entity.ChannelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {

    /**
     * Triée par catégorie puis par numéro affiché (personnalisé en priorité, §5.3),
     * puis par nom à défaut de numéro — sert directement les rangées de l'accueil (§4.4).
     */
    @Query(
        "SELECT * FROM channels WHERE playlistId = :playlistId " +
            "ORDER BY category, COALESCE(customNumber, originalNumber), name"
    )
    fun observeByPlaylist(playlistId: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun getById(id: String): ChannelEntity?

    @Query("SELECT customNumber FROM channels WHERE id = :id")
    suspend fun getCustomNumber(id: String): Int?

    @Query("UPDATE channels SET customNumber = :number WHERE id = :id")
    suspend fun setCustomNumber(id: String, number: Int?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteAllForPlaylist(playlistId: String)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId AND id NOT IN (:keepIds)")
    suspend fun deleteMissing(playlistId: String, keepIds: List<String>)

    /**
     * Rafraîchit les chaînes d'une playlist à partir d'un nouveau parsing (M3U 3b /
     * Xtream 3c) : pour chaque chaîne fraîche, récupère le `customNumber` déjà enregistré
     * sous la même clé stable (`ChannelMapper.stableId`, cf. `ChannelEntity`) et le
     * reporte avant d'écrire, puis supprime les chaînes de cette playlist qui ont
     * disparu de la source. Sans cette fusion, chaque rafraîchissement effacerait
     * silencieusement toute la numérotation personnalisée (§5.3) de la playlist.
     *
     * `freshChannels` doit provenir de `Channel.toEntity()` (donc déjà avec l'`id`
     * recalculé en clé stable) ; leur `customNumber` est ignoré ici et remplacé par la
     * valeur trouvée en base, le parseur ne connaissant jamais la numérotation
     * personnalisée.
     */
    @Transaction
    suspend fun replaceChannelsPreservingCustomNumbers(playlistId: String, freshChannels: List<ChannelEntity>) {
        val merged = freshChannels.map { fresh ->
            val existingCustomNumber = getCustomNumber(fresh.id)
            if (existingCustomNumber != null) fresh.copy(customNumber = existingCustomNumber) else fresh
        }
        upsertAll(merged)
        deleteMissing(playlistId, merged.map { it.id })
    }
}
