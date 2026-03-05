package com.example.osutunes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Service used solely to hold a strong wakelock while playback is active.  Termux uses a
 * foreground service to keep the CPU awake even when the app UI is swiped away; we mimic that
 * behaviour here and also release the lock when the task is removed from recents.
 */
class PlaybackService : Service() {

    companion object {
        private const val TAG = "PlaybackService"
        const val ACTION_START = "com.example.osutunes.ACTION_START"
        const val ACTION_STOP = "com.example.osutunes.ACTION_STOP"
        private const val CHANNEL_ID = "osuTunesPlayback"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START -> {
                    acquireWakeLock()
                    startForeground(1, buildNotification())
                }
                ACTION_STOP -> {
                    releaseWakeLock()
                    stopForeground(true)
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in onStartCommand", e)
            // if we failed to start foreground or acquire lock, stop service to avoid leaving
            releaseWakeLock()
            stopSelf()
        }
        // Do not restart if the service is killed; playback state is managed by activity
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Called when the user swipes the app away from Recents.  We must release the lock
        // otherwise it would stay held indefinitely until the process dies.
        Log.d(TAG, "onTaskRemoved - releasing wakelock and stopping service")
        releaseWakeLock()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // also make sure the lock is gone when the service finally stops
        releaseWakeLock()
        Log.d(TAG, "PlaybackService destroyed")
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            // use PARTIAL_WAKE_LOCK + ON_AFTER_RELEASE just like Termux and disable ref counting
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "OsuTunes:PlaybackService")
            wakeLock?.setReferenceCounted(false)
        }
        if (wakeLock?.isHeld == false) {
            // acquire indefinitely - we explicitly release when playback stops
            // Required for Android 15+ aggressive task killing prevention
            wakeLock?.acquire()
            Log.d(TAG, "WakeLock acquired indefinitely in service")
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "WakeLock released in service")
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        builder
            .setContentTitle("osu!tunes playing")
            .setContentText("Audio playback in progress")
            .setSmallIcon(R.drawable.ic_music_note)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // older API uses setPriority rather than property access
            builder.setPriority(Notification.PRIORITY_LOW)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID,
                "Playback service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Channel for the osu!tunes playback foreground service"
            })
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
