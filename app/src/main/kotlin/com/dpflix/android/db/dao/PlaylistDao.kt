package com.dpflix.android.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.dpflix.android.db.entity.PlaylistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    /** Ordonnée par `sortOrder` : ordre d'affichage dans Réglages → Playlists (§4.3). */
    @Query("SELECT * FROM playlists ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    /**
     * La playlist affichée à l'accueil (§4.4) : au plus une ligne, `isActive = 1`
     * étant maintenu exclusif par [setActive].
     */
    @Query("SELECT * FROM playlists WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: String): PlaylistEntity?

    /** Sert à la couche repository (étape 4d) pour appliquer la limite de 5 playlists (§4.3). */
    @Query("SELECT COUNT(*) FROM playlists")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: PlaylistEntity)

    @Delete
    suspend fun delete(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Réinitialisation complète (§5.6) : supprime toutes les playlists. Les chaînes liées
     *  (4b) sont supprimées en cascade par la contrainte de clé étrangère, pas ici. */
    @Query("DELETE FROM playlists")
    suspend fun deleteAll()

    @Query("UPDATE playlists SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE playlists SET isActive = 1 WHERE id = :id")
    suspend fun activateById(id: String)

    /**
     * Bascule "playlist active" (§4.3/§4.4) : une seule playlist active à la fois,
     * les autres étant désactivées dans la même transaction. Évite un état transitoire
     * avec 0 ou 2 playlists actives si l'app est interrompue entre les deux updates
     * (ex. tuée par le système entre `deactivateAll()` et `activateById(id)`).
     */
    @Transaction
    suspend fun setActive(id: String) {
        deactivateAll()
        activateById(id)
    }
}
