package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.db.WallpaperRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "System boot completed. Checking if auto-wallpaper should resume.")
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val repo = WallpaperRepository(context)
                    val config = repo.getConfig()
                    if (config.isActive) {
                        if (config.triggerType == "TIMER") {
                            Log.i("BootReceiver", "Wallpaper rotation is ACTIVE and TIMER trigger is set. Scheduling first helper alarm.")
                            WallpaperAlarmReceiver.scheduleAlarm(context, 5000) // Wakes up in 5 seconds
                        } else {
                            Log.i("BootReceiver", "Wallpaper rotation is ACTIVE and INTERACTION trigger is set. Starting WallpaperChangerService.")
                            val serviceIntent = Intent(context, WallpaperChangerService::class.java)
                            ContextCompat.startForegroundService(context, serviceIntent)
                        }
                    } else {
                        Log.i("BootReceiver", "Wallpaper rotation is INACTIVE. No action taken.")
                    }
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Error while restoring service on boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
