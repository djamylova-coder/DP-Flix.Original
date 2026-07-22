package com.dpflix.android.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.dpflix.android.db.dao.ChannelDao
import com.dpflix.android.db.dao.PlaylistDao
import com.dpflix.android.db.entity.ChannelEntity
import com.dpflix.android.db.entity.PlaylistEntity

/**
 * Base de données locale (§2 : "Room et/ou DataStore... remplace localStorage").
 *
 * `exportSchema = false` volontairement à cette sous-étape : le schéma n'a pas
 * encore d'historique de migrations à tracer (aucune release publiée). À activer
 * (avec un dossier `schemas/` versionné + argument KSP correspondant) dès qu'une
 * vraie migration entre deux versions devra être testée/tracée.
 *
 * Version 2 (6g-1) : ajout de `PlaylistEntity.lastEpgUpdateMillis` (§5.4). Version 3
 * (6g-2-1) : ajout de `PlaylistEntity.manualEpgLocalFileUri` (§5.4, import fichier EPG
 * local). Pas de `Migration` écrite pour ces bumps — voir
 * `AppContainer.fallbackToDestructiveMigration()` et sa doc pour la justification (app non
 * publiée, aucune donnée utilisateur à préserver).
 */
@Database(entities = [PlaylistEntity::class, ChannelEntity::class], version = 3, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao

    companion object {
        const val DATABASE_NAME = "dpflix.db"
    }
}
