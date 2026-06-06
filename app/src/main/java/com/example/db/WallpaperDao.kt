package com.example.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WallpaperDao {
    @Query("SELECT * FROM wallpaper_config WHERE id = 1 LIMIT 1")
    fun getConfigFlow(): Flow<WallpaperConfigEntity?>

    @Query("SELECT * FROM wallpaper_config WHERE id = 1 LIMIT 1")
    suspend fun getConfig(): WallpaperConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: WallpaperConfigEntity)

    @Query("SELECT * FROM wallpaper_logs ORDER BY timestamp DESC LIMIT 50")
    fun getLogsFlow(): Flow<List<WallpaperLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: WallpaperLogEntity)

    @Query("DELETE FROM wallpaper_logs")
    suspend fun clearLogs()
}
