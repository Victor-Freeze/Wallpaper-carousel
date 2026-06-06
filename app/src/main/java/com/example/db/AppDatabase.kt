package com.example.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [WallpaperConfigEntity::class, WallpaperLogEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun wallpaperDao(): WallpaperDao
}
