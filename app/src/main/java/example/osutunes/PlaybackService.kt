package com.example.osutunes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.osutunes.AppLogger

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
        const val ACTION_PLAY = "com.example.osutunes.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.osutunes.ACTION_PAUSE"
        const val ACTION_SEEK = "com.example.osutunes.ACTION_SEEK"
        const val ACTION_SET_PARAMS = "com.example.osutunes.ACTION_SET_PARAMS"
        const val ACTION_COMPLETION = "com.example.osutunes.ACTION_COMPLETION"
        const val ACTION_POSITION_UPDATE = "com.example.osutunes.ACTION_POSITION_UPDATE"
        const val EXTRA_URI = "uri"
        const val EXTRA_SEEK_POS = "seek_pos"
        const val EXTRA_TEMPO = "tempo"
        const val EXTRA_PITCH = "pitch"
        const val EXTRA_CURRENT_POS = "current_pos"
        const val EXTRA_DURATION = "duration"
        private const val CHANNEL_ID = "osuTunesPlayback"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null
    private var positionUpdateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var positionUpdateRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // Permanent loss, pause and release
                    pausePlayback()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    // Temporary loss, pause
                    pausePlayback()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // Can duck, lower volume
                    mediaPlayer?.setVolume(0.1f, 0.1f)
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    // Regain focus, resume if was playing
                    mediaPlayer?.setVolume(1.0f, 1.0f)
                    // Note: We don't auto-resume, let user control
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            AppLogger.info(this, TAG, "onStartCommand called with action: ${intent?.action}")
            when (intent?.action) {
                ACTION_START -> {
                    AppLogger.info(this, TAG, "ACTION_START: acquiring wakelock and starting foreground")
                    acquireWakeLock()
                    startForeground(1, buildNotification())
                }
                ACTION_PLAY -> {
                    val uriString = intent.getStringExtra(EXTRA_URI)
                    AppLogger.info(this, TAG, "ACTION_PLAY received with uri: $uriString")
                    Log.d(TAG, "ACTION_PLAY received with uri: $uriString")
                    if (uriString != null) {
                        AppLogger.info(this, TAG, "Starting playSong and acquiring foreground service status")
                        playSong(Uri.parse(uriString))
                        acquireWakeLock()
                        try {
                            startForeground(1, buildNotification())
                            AppLogger.info(this, TAG, "Foreground service started")
                        } catch (e: SecurityException) {
                            AppLogger.error(this, TAG, "SecurityException starting foreground service (requires FOREGROUND_SERVICE_MEDIA_PLAYBACK permission)", e)
                            Log.e(TAG, "SecurityException starting foreground", e)
                            // Continue anyway - playback can still work
                        }
                    }
                }
                ACTION_PAUSE -> {
                    AppLogger.info(this, TAG, "ACTION_PAUSE")
                    pausePlayback()
                }
                ACTION_SEEK -> {
                    val pos = intent.getIntExtra(EXTRA_SEEK_POS, 0)
                    AppLogger.info(this, TAG, "ACTION_SEEK to $pos")
                    mediaPlayer?.seekTo(pos)
                }
                ACTION_SET_PARAMS -> {
                    val tempo = intent.getFloatExtra(EXTRA_TEMPO, 1.0f)
                    val pitch = intent.getFloatExtra(EXTRA_PITCH, 1.0f)
                    AppLogger.info(this, TAG, "ACTION_SET_PARAMS tempo=$tempo pitch=$pitch")
                    applyPlaybackParams(tempo, pitch)
                }
                ACTION_STOP -> {
                    AppLogger.info(this, TAG, "ACTION_STOP")
                    stopPlayback()
                    releaseWakeLock()
                    stopForeground(true)
                    stopSelf()
                }
                else -> {
                    AppLogger.info(this, TAG, "onStartCommand: No action or unknown action")
                }
            }
        } catch (e: Exception) {
            AppLogger.error(this, TAG, "Exception in onStartCommand", e)
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
        mediaPlayer?.release()
        mediaPlayer = null
        audioManager?.abandonAudioFocus(audioFocusChangeListener)
        Log.d(TAG, "PlaybackService destroyed")
    }

    private fun playSong(uri: Uri) {
        AppLogger.info(this, TAG, "playSong called with uri: $uri")
        Log.d(TAG, "playSong called with uri: $uri")
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer()
            AppLogger.info(this, TAG, "MediaPlayer instance created")
            
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                mediaPlayer!!.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                AppLogger.info(this, TAG, "Data source set successfully")
                Log.d(TAG, "Data source set")
            } ?: run {
                AppLogger.error(this, TAG, "Failed to open descriptor for $uri")
                Log.e(TAG, "Failed to open descriptor for $uri")
                return
            }
            
            AppLogger.info(this, TAG, "Setting up OnPreparedListener")
            mediaPlayer!!.setOnPreparedListener { mp ->
                AppLogger.info(this, TAG, "=== OnPreparedListener CALLED ===")
                Log.d(TAG, "MediaPlayer prepared")
                val result = audioManager?.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                AppLogger.info(this, TAG, "Audio focus request result: $result")
                Log.d(TAG, "Audio focus request result: $result")
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    mp.start()
                    AppLogger.info(this, TAG, "=== Playback STARTED ===")
                    Log.d(TAG, "Playback started")
                    
                    // Send initial duration
                    val duration = mp.duration
                    val intent = Intent(ACTION_POSITION_UPDATE).apply {
                        putExtra(EXTRA_DURATION, duration)
                        putExtra(EXTRA_CURRENT_POS, 0)
                    }
                    LocalBroadcastManager.getInstance(this@PlaybackService).sendBroadcast(intent)
                    
                    // Start periodic position updates
                    startPositionUpdates()
                } else {
                    AppLogger.error(this, TAG, "Audio focus not granted. Result: $result")
                    Log.d(TAG, "Audio focus not granted")
                }
            }
            
            AppLogger.info(this, TAG, "Setting up OnErrorListener")
            mediaPlayer!!.setOnErrorListener { mp, what, extra ->
                AppLogger.error(this, TAG, "=== OnErrorListener CALLED === what=$what, extra=$extra")
                Log.e(TAG, "MediaPlayer Error: what=$what, extra=$extra")
                true // Return true to indicate error was handled
            }
            
            mediaPlayer!!.setOnCompletionListener { mp ->
                AppLogger.info(this, TAG, "=== OnCompletionListener CALLED ===")
                Log.d(TAG, "Playback completed")
                stopPositionUpdates()
                val intent = Intent(ACTION_COMPLETION)
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            }
            
            AppLogger.info(this, TAG, "Calling prepareAsync()...")
            mediaPlayer!!.prepareAsync()
            AppLogger.info(this, TAG, "prepareAsync() called successfully - waiting for callback")
            Log.d(TAG, "prepareAsync called")
        } catch (e: Exception) {
            AppLogger.error(this, TAG, "Exception in playSong", e)
            Log.e(TAG, "Failed to setup media player.", e)
        }
    }

    private fun pausePlayback() {
        stopPositionUpdates()
        mediaPlayer?.pause()
    }

    private fun stopPlayback() {
        stopPositionUpdates()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        audioManager?.abandonAudioFocus(audioFocusChangeListener)
    }

    private fun applyPlaybackParams(tempo: Float, pitch: Float) {
        if (mediaPlayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val params = mediaPlayer!!.playbackParams
                params.speed = tempo
                params.pitch = pitch
                mediaPlayer!!.playbackParams = params
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set PlaybackParams.", e)
            }
        }
    }

    private fun startPositionUpdates() {
        stopPositionUpdates() // Cancel any existing
        positionUpdateRunnable = object : Runnable {
            override fun run() {
                try {
                    if (mediaPlayer != null) {
                        val currentPos = mediaPlayer!!.currentPosition
                        val duration = mediaPlayer!!.duration
                        val intent = Intent(ACTION_POSITION_UPDATE).apply {
                            putExtra(EXTRA_CURRENT_POS, currentPos)
                            putExtra(EXTRA_DURATION, duration)
                        }
                        LocalBroadcastManager.getInstance(this@PlaybackService).sendBroadcast(intent)
                    }
                    positionUpdateHandler.postDelayed(this, 500)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in position update runnable", e)
                }
            }
        }
        positionUpdateHandler.post(positionUpdateRunnable!!)
    }

    private fun stopPositionUpdates() {
        positionUpdateRunnable?.let {
            positionUpdateHandler.removeCallbacks(it)
        }
        positionUpdateRunnable = null
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
            // acquire without timeout (since we release explicitly), but guard with a long timeout
            wakeLock?.acquire(10 * 60 * 1000L)
            Log.d(TAG, "WakeLock acquired in service")
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
