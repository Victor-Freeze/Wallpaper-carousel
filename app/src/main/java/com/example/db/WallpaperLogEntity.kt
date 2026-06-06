package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wallpaper_logs")
data class WallpaperLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val imageUri: String,
    val imageName: String,
    val targetScreen: String, // "BOTH", "LOCK_SCREEN", "HOME_SCREEN"
    val status: String, // "SUCCESS", "ERROR"
    val errorMessage: String? = null
)
