package com.example.db

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * WallpaperRepository represents the Repository Pattern in OOP & Clean Architecture.
 *
 * Design Goals:
 * 1. Abstraction / Decoupling: Hides database implementation details (Room DB, DAOs, SQLite queries) from
 *    the consumer classes (ViewModels, Services).
 * 2. Unification of Sources: Acts as a single point of truth for accessing/updating config states or logs.
 *    If the storage engine changes in the future (e.g. from Room to Firestore or SharedPreferences),
 *    the ViewModels/Services do not need to change because they interact solely with this stable API.
 */
class WallpaperRepository(context: Context) {
    // Encapsulated data source fields that cannot be directly accessed outside this boundary (Information Hiding)
    private val db = DatabaseProvider.getDatabase(context)
    private val dao = db.wallpaperDao()

    // Expose flows representing reactive queries on SQLite database tables
    val configFlow: Flow<WallpaperConfigEntity?> = dao.getConfigFlow()
    val logsFlow: Flow<List<WallpaperLogEntity>> = dao.getLogsFlow()

    /**
     * Retrieves the current configuration model or spawns a fallback default entity if none is registered.
     */
    suspend fun getConfig(): WallpaperConfigEntity {
        return dao.getConfig() ?: WallpaperConfigEntity()
    }

    /**
     * Saves or overwrites the configuration entity state inside SQLite table records.
     */
    suspend fun saveConfig(config: WallpaperConfigEntity) {
        dao.insertConfig(config)
    }

    /**
     * Inserts a historical transition result entry log to keep track of wallpaper change cycles.
     */
    suspend fun insertLog(log: WallpaperLogEntity) {
        dao.insertLog(log)
    }

    /**
     * Clears all log events stored in the repository.
     */
    suspend fun clearLogs() {
        dao.clearLogs()
    }
}
