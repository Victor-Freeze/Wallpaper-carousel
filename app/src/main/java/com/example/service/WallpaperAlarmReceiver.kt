package com.example.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.db.WallpaperRepository
import com.example.utils.WallpaperHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * WallpaperAlarmReceiver handles periodic wake-up alarms dispatched by Android's AlarmManager.
 *
 * This design resolves Doze Mode, Deep Sleep, and Foreground Service Start Restrictions:
 * 1. Sleep/Doze Resilience: Wakeups are powered by Android's hardware RTC timers, working screen-off.
 * 2. Foreground Exempt Rotation: If WallpaperChangerService gets reclaimed under extreme RAM pressure,
 *    this receiver avoids calling startForegroundService (which crashes on Android 14+ via background context).
 *    Instead, it runs the wallpaper rotation directly in 'goAsync()' on an IO thread safely, updates the DB,
 *    and schedules the next alarm.
 */
class WallpaperAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TRIGGER_ALARM) {
            Log.i(TAG, "Hardware RTC alarm triggered.")
            
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val appContext = context.applicationContext
                    val repository = WallpaperRepository(appContext)
                    val config = repository.getConfig()
                    
                    if (config.isActive) {
                        if (WallpaperChangerService.isRunning) {
                            Log.i(TAG, "WallpaperChangerService is running. Forwarding intent to service.")
                            val serviceIntent = Intent(appContext, WallpaperChangerService::class.java).apply {
                                action = WallpaperChangerService.ACTION_TIMER_CHECK
                            }
                            try {
                                appContext.startService(serviceIntent)
                            } catch (secEx: SecurityException) {
                                Log.e(TAG, "SecurityException forwarding to running service. Falling back to direct evaluation.", secEx)
                                if (config.triggerType == "TIMER") {
                                    evaluateAndExecuteRotation(appContext, repository, config)
                                }
                            }
                        } else {
                            Log.i(TAG, "WallpaperChangerService is NOT running. Attempting to start service as foreground first.")
                            val serviceIntent = Intent(appContext, WallpaperChangerService::class.java).apply {
                                action = WallpaperChangerService.ACTION_TIMER_CHECK
                            }
                            try {
                                ContextCompat.startForegroundService(appContext, serviceIntent)
                                Log.i(TAG, "Successfully started WallpaperChangerService as foreground.")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to start service as foreground (Android 14 background restrictions). Falling back to background evaluations.", e)
                                if (config.triggerType == "TIMER") {
                                    evaluateAndExecuteRotation(appContext, repository, config)
                                }
                            }
                        }
                    } else {
                        Log.d(TAG, "Alarm triggered but rotation is inactive.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onReceive handling", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private suspend fun evaluateAndExecuteRotation(
        context: Context,
        repository: WallpaperRepository,
        config: com.example.db.WallpaperConfigEntity
    ) {
        val lastSuccess = config.lastChangedTimestamp
        val elapsedMs = System.currentTimeMillis() - lastSuccess
        val targetIntervalMs = config.intervalMinutes * 60 * 1000L
        val remainingMs = targetIntervalMs - elapsedMs

        if (remainingMs <= 0) {
            if (!config.hasSeenLastChange) {
                Log.i(TAG, "Interval elapsed, but user has not seen previous change. Postponing and rescheduling alarm.")
                repository.saveConfig(config.copy(lastChangedTimestamp = System.currentTimeMillis()))
                scheduleAlarm(context, targetIntervalMs)
                return
            }

            Log.i(TAG, "Interval fully elapsed. Performing direct background rotation.")
            val success = WallpaperHelper.performWallpaperChange(context, config, repository)
            
            val updatedConfig = repository.getConfig()
            val nextDelay = if (success) {
                updatedConfig.intervalMinutes * 60 * 1000L
            } else {
                5 * 60 * 1000L
            }
            scheduleAlarm(context, nextDelay)
        } else {
            val delay = remainingMs.coerceAtLeast(5000)
            Log.d(TAG, "Interval not reached yet. Next check scheduled in ${delay / 1000} seconds.")
            scheduleAlarm(context, delay)
        }
    }

    companion object {
        private const val TAG = "WallpaperAlarmReceiver"
        const val ACTION_TRIGGER_ALARM = "com.example.service.ACTION_TRIGGER_ALARM"

        /**
         * Schedules a hardware RTC alarm to wake up this receiver.
         */
        fun scheduleAlarm(context: Context, delayMs: Long) {
            cancelAlarm(context)

            Log.i(TAG, "Scheduling hardware alarm check in ${delayMs / 1000} seconds via helper.")
            val alarmIntent = Intent(context, WallpaperAlarmReceiver::class.java).apply {
                action = ACTION_TRIGGER_ALARM
            }
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                9924,
                alarmIntent,
                pendingFlags
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = System.currentTimeMillis() + delayMs

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
        }

        /**
         * Cancels any active alarms scheduled for this receiver.
         */
        fun cancelAlarm(context: Context) {
            val alarmIntent = Intent(context, WallpaperAlarmReceiver::class.java).apply {
                action = ACTION_TRIGGER_ALARM
            }
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_NO_CREATE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                9924,
                alarmIntent,
                pendingFlags
            )
            if (pendingIntent != null) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
                Log.d(TAG, "Successfully cancelled existing AlarmManager check.")
            }
        }
    }
}
