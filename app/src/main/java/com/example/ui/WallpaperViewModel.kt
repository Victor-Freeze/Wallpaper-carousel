package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.db.WallpaperConfigEntity
import com.example.db.WallpaperRepository
import com.example.service.WallpaperChangerService
import com.example.utils.WallpaperHelper
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * WallpaperViewModel is a subclass of `AndroidViewModel`.
 *
 * In Model-View-ViewModel (MVVM) architecture and OOP design:
 * 1. MVVM Role: Acts as the intermediary "Presenter/Controller" block. It retrieves raw database state
 *    from the repository, maps or processes it, and exposes it as reactive event streams to the view.
 * 2. Unidirectional Data Flow (UDF): The UI consumes the read-only states (`configState`, `logsState`)
 *    and propagates user commands (clicks, folder picks, toggle switches) down as method calls here.
 * 3. Lifecycle-Aware Concurrency: Combines Kotlin Coroutines `viewModelScope.launch` so that
 *    long-running database operations are bound to the view's active presence. If the user navigates
 *    away and the UI is dismissed, the VM scope is cancelled automatically to prevent leaks or zombie threads.
 */
class WallpaperViewModel(application: Application) : AndroidViewModel(application) {

    // Composition: Enclosing a reference to the data layer source (Repository pattern)
    private val repository = WallpaperRepository(application)
    private val context = application.applicationContext

    // StateFlow exposes a hot, replayable state stream.
    // In OOP systems, exposing read-only interfaces (`StateFlow`) while modifying states privately
    // protects structural integrity (Encapsulation).
    val configState: StateFlow<WallpaperConfigEntity?> = repository.configFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000), // Resource safety: stops fetching the DB flow if there are no observers for 5 seconds
            initialValue = null
        )

    val logsState = repository.logsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        viewModelScope.launch {
            try {
                val config = repository.getConfig()
                if (config.isActive) {
                    Log.i("WallpaperViewModel", "Initializing WallpaperViewModel. Active configuration found. Proactively starting background service.")
                    val serviceIntent = Intent(context, WallpaperChangerService::class.java)
                    try {
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } catch (e: Exception) {
                        Log.e("WallpaperViewModel", "Deny background service start on init. Service will resume on next alarm or app interaction.", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("WallpaperViewModel", "Failed to start wallpaper service on ViewModel initialization", e)
            }
        }
    }

    /**
     * Toggles the automatic background wallpaper rotation state.
     * Starts or stops the background Foreground Service orchestrator accordingly.
     */
    fun toggleActive(enabled: Boolean) {
        viewModelScope.launch {
            val current = repository.getConfig()
            var updated = current.copy(isActive = enabled)
            if (enabled) {
                // If restarting, refresh/re-shufle the folder images queue
                updated = WallpaperHelper.rebuildQueue(context, updated)
            }
            repository.saveConfig(updated)

            // Dynamic Intent Command: Start/stop the background service engine depending on the toggle state
            val serviceIntent = Intent(context, WallpaperChangerService::class.java)
            if (enabled) {
                Log.i("WallpaperViewModel", "Enabling Auto-Wallpaper rotation. Starting Service.")
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    Log.e("WallpaperViewModel", "Failed to start service on toggle. Rotation will occur on next hardware alarm.", e)
                    // If start fails, we ensure at least the alarm is scheduled for TIMER mode
                    if (updated.triggerType == "TIMER") {
                        val delay = updated.intervalMinutes * 60 * 1000L
                        com.example.service.WallpaperAlarmReceiver.scheduleAlarm(context, delay)
                    }
                }
            } else {
                Log.i("WallpaperViewModel", "Disabling Auto-Wallpaper rotation. Stopping Service.")
                try {
                    context.stopService(serviceIntent)
                } catch (e: Exception) {
                    Log.e("WallpaperViewModel", "Error stopping service", e)
                }
            }
        }
    }

    fun updateImageSource(sourceType: String) {
        viewModelScope.launch {
            val current = repository.getConfig()
            var updated = current.copy(imageSourceType = sourceType)
            updated = WallpaperHelper.rebuildQueue(context, updated)
            repository.saveConfig(updated)
            restartServiceIfActive()
        }
    }

    fun updateLocalFolder(uri: String, name: String) {
        viewModelScope.launch {
            val current = repository.getConfig()
            var updated = current.copy(
                localFolderUri = uri,
                localFolderName = name
            )
            updated = WallpaperHelper.rebuildQueue(context, updated)
            repository.saveConfig(updated)
            restartServiceIfActive()
        }
    }

    fun updateGooglePhotosAlbum(albumId: String, albumName: String) {
        viewModelScope.launch {
            val current = repository.getConfig()
            var updated = current.copy(
                googlePhotosAlbumId = albumId,
                googlePhotosAlbumName = albumName
            )
            updated = WallpaperHelper.rebuildQueue(context, updated)
            repository.saveConfig(updated)
            restartServiceIfActive()
        }
    }

    fun updateChangeTarget(target: String) {
        viewModelScope.launch {
            val current = repository.getConfig()
            repository.saveConfig(current.copy(changeTarget = target))
            restartServiceIfActive()
        }
    }

    fun updateTriggerType(type: String) {
        viewModelScope.launch {
            val current = repository.getConfig()
            repository.saveConfig(current.copy(triggerType = type))
            restartServiceIfActive()
        }
    }

    fun updateIntervalMinutes(minutes: Int) {
        viewModelScope.launch {
            val current = repository.getConfig()
            repository.saveConfig(current.copy(intervalMinutes = minutes))
            restartServiceIfActive()
        }
    }

    fun updatePauseMinutes(minutes: Int) {
        viewModelScope.launch {
            val current = repository.getConfig()
            repository.saveConfig(current.copy(pauseMinutes = minutes))
            restartServiceIfActive()
        }
    }

    fun updateScaleMode(mode: String) {
        viewModelScope.launch {
            val current = repository.getConfig()
            repository.saveConfig(current.copy(scaleMode = mode))
            restartServiceIfActive()
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearLogs()
        }
    }

    fun triggerManualChange(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val current = repository.getConfig()
            val success = WallpaperHelper.performWallpaperChange(context, current, repository)
            onComplete(success)
        }
    }

    private fun restartServiceIfActive() {
        viewModelScope.launch {
            val current = repository.getConfig()
            if (current.isActive) {
                val serviceIntent = Intent(context, WallpaperChangerService::class.java)
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    Log.e("WallpaperViewModel", "Failed to restart service after config change.", e)
                }
            }
        }
    }
}
