package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.db.WallpaperRepository
import com.example.utils.WallpaperHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * WallpaperChangerService is a subclass of Android's `Service()`.
 *
 * In Object-Oriented Design & Android Architecture:
 * 1. Inheritance and Polymorphism: We inherit from `Service` and override its lifecycle
 *    callbacks (`onCreate`, `onStartCommand`, `onDestroy`) to plug in our custom rotation logic.
 * 2. Foreground Service State: Runs with higher OS priority by showing an active Notification.
 *    This prevents Android from reclaiming its CPU/RAM resources during low-memory conditions.
 * 3. Event-Driven Programming: Listens to OS level broadcasts (Screen Off, Screen On, Unlocked)
 *    by implementing and registering a local `BroadcastReceiver` observer pattern.
 * 4. Composition (Dependency Injection): Combines instances of `CoroutineScope` for async work,
 *    `Handler` for high-precision main-loop timers, and `WallpaperRepository` for persistent database storage.
 */
class WallpaperChangerService : Service() {

    // Context encapsulation: A bound CoroutineScope containing SupervisorJob so that failed
    // child tasks don't cancel parent components (concurrency isolation).
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var repository: WallpaperRepository
    private var isPendingTimerChange = false

    // Handler associated with the UI thread's Looper. Used for reliable periodic checks.
    private val handler = Handler(Looper.getMainLooper())
    
    // Anonymous subclass implementing Runnable to register for repeated evaluation loops.
    private val checkRunnable = object : Runnable {
        override fun run() {
            checkTimerTrigger()
        }
    }

    // Observer Pattern: BroadcastReceiver subclass listening for screen states to trigger swaps on sleep.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "Screen off event detected. Analyzing automatic changes...")
                    handleScreenOffTrigger()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    Log.i(TAG, "User interaction detected (Screen on/Unlocked). Assessing seen status.")
                    handleUserSawWallpaper()
                }
            }
        }
    }

    /**
     * Called when the service is created. Identifies the setup lifecycle stage.
     */
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate. Initializing background wallpaper engine.")
        
        // Composition initialization
        repository = WallpaperRepository(this)

        // Elevates service to foreground immediately
        startForegroundServiceNotification()

        // Dynamically register our event observers
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)

        // Evaluate and schedule the first check dynamically
        checkTimerTrigger()
    }

    /**
     * Executes when startService/startForegroundService is called.
     * Operates somewhat like a Command Route pattern:
     * - Reads incoming action codes from the Intent payload.
     * - Triggers specific behaviors based on the command.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop request received via notification action.")
            stopWallpaperService()
            return START_NOT_STICKY // Safe non-restart policy when explicit user shutdown is called
        }
        // Force immediate recalculation of schedule dynamically (e.g. config updated in UI)
        checkTimerTrigger()
        return START_STICKY // Return code telling the OS to restart this service if it gets killed unexpectedly
    }

    private fun handleUserSawWallpaper() {
        serviceScope.launch {
            val config = repository.getConfig()
            if (!config.hasSeenLastChange) {
                repository.saveConfig(config.copy(hasSeenLastChange = true))
                Log.i(TAG, "Confirmed: User has seen the updated wallpaper. hasSeenLastChange is reset to true.")
            }
        }
    }

    private fun checkTimerTrigger() {
        serviceScope.launch {
            val config = repository.getConfig()
            if (!config.isActive) {
                Log.d(TAG, "Config deactivated. Stopping service automatically.")
                stopWallpaperService()
                return@launch
            }

            // Always clear any pending delayed runnables to avoid duplicating running loops
            handler.removeCallbacks(checkRunnable)

            if (config.triggerType == "TIMER") {
                val lastSuccess = config.lastChangedTimestamp
                val elapsedMs = System.currentTimeMillis() - lastSuccess
                val targetIntervalMs = config.intervalMinutes * 60 * 1000L
                val remainingMs = targetIntervalMs - elapsedMs

                if (remainingMs <= 0) {
                    if (!config.hasSeenLastChange) {
                        Log.i(TAG, "Timer elapsed, but user has not seen the previous wallpaper. Resetting timer and sleeping.")
                        repository.saveConfig(config.copy(lastChangedTimestamp = System.currentTimeMillis()))
                        // Reschedule for a full interval
                        handler.postDelayed(checkRunnable, targetIntervalMs)
                        return@launch
                    }

                    val pManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                    val isLockedOrScreenOff = !pManager.isInteractive

                    if (isLockedOrScreenOff) {
                        Log.i(TAG, "Timer interval elapsed. Screen is off. Performing change immediately.")
                        isPendingTimerChange = false
                        val success = WallpaperHelper.performWallpaperChange(this@WallpaperChangerService, config, repository)
                        
                        // Check updated config and reschedule next check
                        val nextConfig = repository.getConfig()
                        val nextDelay = if (success) {
                            nextConfig.intervalMinutes * 60 * 1000L
                        } else {
                            5 * 60 * 1000L // Retry in 5 minutes if it fails, to save battery and prevent crash loops
                        }
                        handler.postDelayed(checkRunnable, nextDelay)
                    } else {
                        Log.i(TAG, "Timer interval elapsed, but user is interacting (screen ON). Queuing change until sleep.")
                        isPendingTimerChange = true
                        // While interacting, check back in 30 seconds
                        handler.postDelayed(checkRunnable, 30000)
                    }
                } else {
                    // Not elapsed yet! Schedule check exactly for the remaining duration
                    // Coerce to minimum 10 seconds to avoid negative/trivial delays
                    val delay = remainingMs.coerceAtLeast(10000)
                    Log.d(TAG, "Timer not elapsed. Next check scheduled in ${delay / 60000}m ${ (delay % 60000) / 1000 }s.")
                    handler.postDelayed(checkRunnable, delay)
                }
            } else {
                Log.d(TAG, "Interactive screen-off triggers are active. Periodic check loop is suspended to save battery.")
            }
        }
    }

    private fun handleScreenOffTrigger() {
        serviceScope.launch {
            val config = repository.getConfig()
            if (!config.isActive) return@launch

            if (config.triggerType == "INTERACTION") {
                // Check if pause minutes elapsed since last change
                val lastSuccess = config.lastChangedTimestamp
                val elapsedMs = System.currentTimeMillis() - lastSuccess
                val targetPauseMs = config.pauseMinutes * 60 * 1000L

                if (elapsedMs >= targetPauseMs) {
                    Log.i(TAG, "Interaction change triggered. Elapsed time meets pause requirements.")
                    WallpaperHelper.performWallpaperChange(this@WallpaperChangerService, config, repository)
                } else {
                    val remainingMins = ((targetPauseMs - elapsedMs) / 1000 / 60) + 1
                    Log.d(TAG, "Interaction trigger ignored. Pause threshold not met. Keep sleeping. Remaining Mins: $remainingMins")
                }
            } else if (config.triggerType == "TIMER") {
                // On screen off, check if we have a pending change waiting for screen sleep
                if (isPendingTimerChange) {
                    Log.i(TAG, "Pending timer change fulfilled on screen-off.")
                    isPendingTimerChange = false
                    WallpaperHelper.performWallpaperChange(this@WallpaperChangerService, config, repository)
                } else {
                    // Check if timer elapsed while screen went off anyway
                    val lastSuccess = config.lastChangedTimestamp
                    val elapsedMs = System.currentTimeMillis() - lastSuccess
                    val targetIntervalMs = config.intervalMinutes * 60 * 1000L
                    if (elapsedMs >= targetIntervalMs) {
                        if (config.hasSeenLastChange) {
                            Log.i(TAG, "Interval elapsed as screen went off. Performing wallpaper change.")
                            WallpaperHelper.performWallpaperChange(this@WallpaperChangerService, config, repository)
                        } else {
                            Log.i(TAG, "Interval elapsed on screen off, but user has not seen previous change. Resetting timer.")
                            repository.saveConfig(config.copy(lastChangedTimestamp = System.currentTimeMillis()))
                        }
                    }
                }
            }
        }
    }

    private fun startForegroundServiceNotification() {
        val channelId = "wallpaper_changer_channel"
        val channelName = "Auto Wallpaper Service Status"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the status of automatic wallpaper rotation"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val activityIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentPendingIntent = PendingIntent.getActivity(this, 0, activityIntent, pendingIntentFlags)

        val stopIntent = Intent(this, WallpaperChangerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, pendingIntentFlags)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Wallpaper Rotation Active")
            .setContentText("Monitoring events to rotate wallpapers...")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } catch (e: Exception) {
                Log.e(TAG, "startForeground with ServiceInfo error. Trying fallback...", e)
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Terminate the service execution flow cleanly.
     * Essential for OOP/system memory hygiene:
     * - Resets active indicator status persistent states in databases.
     * - Cuts down foreground active notifications.
     * - Calls stopSelf() to instruct the Android runtime to trigger onDestroy.
     */
    private fun stopWallpaperService() {
        Log.i(TAG, "Tearing down automatic wallpaper service completely.")
        serviceScope.launch {
            // Unregister isActive in Database to sync main UI state
            val config = repository.getConfig()
            if (config.isActive) {
                repository.saveConfig(config.copy(isActive = false))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf() // Notifies system that execution is completed
        }
    }

    /**
     * Standard Android Service lifecycle destructor override.
     * Ensures all acquired hardware bindings, schedulers, and asynchronous components are released.
     */
    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy. Releasing all resources and unregistering receivers.")
        
        // 1. Threading safety: Stop pending delayed timer loops immediately
        handler.removeCallbacks(checkRunnable)
        
        // 2. Observer safety: Try/catch unregistering the dynamic BroadcastReceiver
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Receiver not registered or already released", e)
        }
        
        // 3. UI/System cleanups: Strip notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        
        // 4. Coroutine cancellation: Discard active asynchronous tasks in progress
        serviceScope.cancel()
        
        super.onDestroy() // Delegates back to base class implementation
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved detected. Application swiped away from Recents. Checking configuration auto-recovery...")
        try {
            kotlinx.coroutines.runBlocking {
                val config = repository.getConfig()
                if (config.isActive) {
                    Log.i(TAG, "Service is marked active in database. Scheduling recovery alarm in 1.5 seconds.")
                    val restartIntent = Intent(applicationContext, WallpaperChangerService::class.java)
                    val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                    } else {
                        PendingIntent.FLAG_ONE_SHOT
                    }
                    val restartPendingIntent = PendingIntent.getService(
                        applicationContext,
                        9912,
                        restartIntent,
                        pendingFlags
                    )
                    
                    val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                    val triggerAt = System.currentTimeMillis() + 1500
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setAndAllowWhileIdle(
                            android.app.AlarmManager.RTC_WAKEUP,
                            triggerAt,
                            restartPendingIntent
                        )
                    } else {
                        alarmManager.set(
                            android.app.AlarmManager.RTC_WAKEUP,
                            triggerAt,
                            restartPendingIntent
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule service recovery in onTaskRemoved", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "WallpaperService"
        private const val NOTIFICATION_ID = 8802
        const val ACTION_STOP = "com.example.service.ACTION_STOP"
    }
}
