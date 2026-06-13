package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * WallpaperAlarmReceiver handles periodic wake-up alarms dispatched by Android's AlarmManager.
 *
 * This design resolves Doze Mode and Deep Sleep concerns:
 * 1. Sleep/Doze Resilience: Unlike memory-only timers (e.g. Handler, Coroutines) which are completely suspended
 *    when the device screen is off and CPU sleeps, AlarmManager is backed by OS hardware RTC/timers.
 * 2. Self-Healing Background Restart: If the service gets unloaded/reclaimed of resources under extreme RAM pressure,
 *    this receiver intercepts the scheduled alarm and triggers ContextCompat.startForegroundService, automatically
 *    restoring the service.
 */
class WallpaperAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TRIGGER_ALARM) {
            Log.i(TAG, "Hardware RTC alarm triggered. Invoking WallpaperChangerService wake up.")
            val serviceIntent = Intent(context, WallpaperChangerService::class.java).apply {
                action = WallpaperChangerService.ACTION_TIMER_CHECK
            }
            try {
                ContextCompat.startForegroundService(context, serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start wallpaper service via foreground service intent from alarm receiver", e)
            }
        }
    }

    companion object {
        private const val TAG = "WallpaperAlarmReceiver"
        const val ACTION_TRIGGER_ALARM = "com.example.service.ACTION_TRIGGER_ALARM"
    }
}
