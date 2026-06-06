package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * WallpaperConfigEntity represents a Data Carrier model (Entity Pattern) in OOP.
 *
 * Concepts illustrated:
 * 1. Object-Relational Mapping (ORM): Annotated with `@Entity` for Room Database translation.
 *    Hides SQL creation queries by mapping Kotlin class structures directly to SQLite rows & columns.
 * 2. Kotlin Data Class: Handled by the compiler specifically for holding data. It automatically
 *    generates value-based helpers such as `equals()`, `hashCode()`, `toString()`, and `copy()`.
 * 3. Immutability Principle: Fields are declared as `val` (read-only, final values). To alter
 *    values, we use `.copy(...)` to create a fresh model instance, safeguarding the state against
 *    unintended execution side-effects.
 */
@Entity(tableName = "wallpaper_config")
data class WallpaperConfigEntity(
    @PrimaryKey val id: Int = 1, // Singleton row id guarantees only one configuration instance exists in the database table
    val isActive: Boolean = false,
    val imageSourceType: String = "LOCAL_FOLDER", // "LOCAL_FOLDER", "GOOGLE_PHOTOS"
    val localFolderUri: String? = null,
    val localFolderName: String? = null,
    val googlePhotosAlbumId: String? = null,
    val googlePhotosAlbumName: String? = null,
    val changeTarget: String = "BOTH", // "BOTH", "LOCK_SCREEN", "HOME_SCREEN"
    val triggerType: String = "INTERACTION", // "INTERACTION", "TIMER"
    val intervalMinutes: Int = 30, // Default 30 minutes for timer
    val pauseMinutes: Int = 5, // Default 5 minutes for interactions
    val scaleMode: String = "FILL", // "FILL", "CENTER", "FIT"
    val lastChangedTimestamp: Long = 0L,
    val shuffledQueue: String? = null,
    val queueIndex: Int = 0,
    val hasSeenLastChange: Boolean = true
)
