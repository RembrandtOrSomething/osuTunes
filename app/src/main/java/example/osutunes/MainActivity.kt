package com.example.osutunes

import android.app.Activity
import android.app.AlertDialog
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import android.os.Process
import com.example.osutunes.R

class MainActivity : AppCompatActivity() {

    private companion object {
        const val PREFS_NAME = "osuTunesPrefs"
        const val SAVED_URI_KEY = "savedFolderUri"
        const val SONGS_FILE_NAME = "songs.json"
        const val TAG = "OsuTunes"
        const val TEMPO_KEY = "savedPlaybackTempo"
        const val PITCH_KEY = "savedPlaybackPitch"  
        val Json = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    private data class SongEntry(
        val label: String,
        val uriString: String,
        val artist: String,
        val title: String,
        val bpm: Double? = null,
        val bpmRange: String? = null,  // NEW: "xbpm to ybpm (mostly zbpm)"
        val bpms: List<Double>? = null  // NEW: Store all BPM values
    )
    
    @Serializable
    private data class CachedSongData(
        val songs: List<SongEntry>,
        val folderModificationTimes: Map<String, Long> // folderName -> lastModified
    )
    
    private data class OsuMetadata(
        val audioFilename: String, 
        val artist: String, 
        val title: String,
        val bpm: Double? = null,
        val bpmRange: String? = null,  // NEW
        val bpms: List<Double>? = null  // NEW
    )

    // UI Components
    private lateinit var listView: ListView
    private lateinit var playButton: Button
    private lateinit var nextButton: Button
    private lateinit var prevButton: Button
    private lateinit var folderButton: Button
    private lateinit var reloadButton: Button
    private lateinit var importOszButton: Button
    private lateinit var shuffleButton: Button
    private lateinit var repeatButton: Button
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var loadingText: TextView
    private lateinit var scanProgressBar: ProgressBar
    private lateinit var scanningStatus: TextView
    private lateinit var folderCountLabel: TextView
    private lateinit var songSeekBar: SeekBar
    private lateinit var sortSpinner: Spinner
    private lateinit var searchEditText: EditText
    private lateinit var currentTimeTextView: TextView
    private lateinit var totalTimeTextView: TextView
    private lateinit var tempoSeekBar: SeekBar
    private lateinit var tempoTextView: TextView
    private lateinit var pitchSeekBar: SeekBar
    private lateinit var pitchTextView: TextView
    private lateinit var tempoResetButton: Button
    private lateinit var pitchResetButton: Button
    private lateinit var nowPlayingBar: LinearLayout
    private lateinit var nowPlayingText: TextView
    private lateinit var nowPlayingClose: Button

    // State Variables
    private var allSongEntries = mutableListOf<SongEntry>()
    private var isRepeating = false
    private var currentTempo = 1.0f
    private var currentPitch = 1.0f
    private var currentIndex = 0
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentDirUri: Uri? = null
    private var isUserSeeking = false
    private val handler = Handler(Looper.getMainLooper())
    private var songAdapter: SongAdapter? = null
    
    // Playback list - permanent shuffled playlist that doesn't change with search
    private var currentPlaybackList = mutableListOf<SongEntry>()
    private var currentPlayingSong: SongEntry? = null

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                currentDirUri = uri
                saveFolderUri(uri)
                Log.d(TAG, "Folder selected: $uri")
                loadBeatmapSongs(uri)
            }
        }
    }

    private val oszPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uris = mutableListOf<Uri>()

            if (data?.data != null) {
                uris.add(data.data!!)
            } else if (data?.clipData != null) {
                for (i in 0 until data.clipData!!.itemCount) {
                    uris.add(data.clipData!!.getItemAt(i).uri)
                }
            }
            
            // Filter to only accept .osz files
            val otherUris = uris.filter { uri ->
                val fileName = DocumentFile.fromSingleUri(this, uri)?.name.orEmpty()
                fileName.endsWith(".osz", ignoreCase = true)
            }
            
            if (otherUris.isEmpty()) {
                Toast.makeText(this, "Please select only .osz files.", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            
            if (otherUris.isNotEmpty() && currentDirUri != null) {
                importOszFiles(otherUris, currentDirUri!!)
            } else if (currentDirUri == null) {
                Toast.makeText(this, "Please select the 'Songs' folder first.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupCrashHandler()
        setContentView(R.layout.activity_main)
        
        // Initialize WakeLock to keep device awake during playback
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OsuTunes:playback")

        initViews()
        setupListeners()
        loadInitialData()
    }

    private fun setupCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            Log.e(TAG, "FATAL CRASH on Thread: ${thread.name}", exception)
            Handler(Looper.getMainLooper()).post {
                val errorMsg = "FATAL CRASH: ${exception.javaClass.simpleName} - ${exception.message}"
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
            }
            try {
                Thread.sleep(3000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            Process.killProcess(Process.myPid())
            System.exit(10)
        }
    }

    private fun initViews() {
        listView = findViewById(R.id.songList)
        playButton = findViewById(R.id.playButton)
        nextButton = findViewById(R.id.nextButton)
        prevButton = findViewById(R.id.prevButton)
        loadingSpinner = findViewById(R.id.loadingSpinner)
        loadingText = findViewById(R.id.loadingText)
        scanProgressBar = findViewById(R.id.scanProgressBar)
        scanningStatus = findViewById(R.id.scanningStatus)
        folderCountLabel = findViewById(R.id.folderCountLabel)
        songSeekBar = findViewById(R.id.songSeekBar)
        folderButton = findViewById(R.id.folderButton)
        reloadButton = findViewById(R.id.reloadButton)
        importOszButton = findViewById(R.id.importOszButton)
        shuffleButton = findViewById(R.id.shuffleButton)
        repeatButton = findViewById(R.id.repeatButton)
        sortSpinner = findViewById(R.id.sortSpinner)
        searchEditText = findViewById(R.id.searchEditText)
        // disable until initial load/scan finished
        searchEditText.isEnabled = false
        searchEditText.hint = "Loading..."
        currentTimeTextView = findViewById(R.id.currentTimeTextView)
        totalTimeTextView = findViewById(R.id.totalTimeTextView)
        tempoSeekBar = findViewById(R.id.tempoSeekBar)
        tempoTextView = findViewById(R.id.tempoTextView)
        pitchSeekBar = findViewById(R.id.pitchSeekBar)
        pitchTextView = findViewById(R.id.pitchTextView)
        tempoResetButton = findViewById(R.id.tempoResetButton)
        pitchResetButton = findViewById(R.id.pitchResetButton)
        nowPlayingBar = findViewById(R.id.nowPlayingBar)
        nowPlayingText = findViewById(R.id.nowPlayingText)
        nowPlayingClose = findViewById(R.id.nowPlayingClose)
        
        // HIDE shuffle button since shuffle is always on now
        shuffleButton.visibility = View.GONE
    }

    private fun setupListeners() {
        folderButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            folderPickerLauncher.launch(intent)
        }

        reloadButton.setOnClickListener {
            val uri = currentDirUri
            if (uri == null) {
                Toast.makeText(this, "No folder selected to reload.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Reload Songs")
                .setMessage("Rescan the entire current folder for songs?\n\nChoose 'Force Refresh' to clear cached data.")
                .setPositiveButton("Normal Reload") { _, _ -> loadBeatmapSongs(uri) }
                .setNeutralButton("Force Refresh") { _, _ -> 
                    clearCache()
                    loadBeatmapSongs(uri)
                    Toast.makeText(this, "Cache cleared. Performing full scan...", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        importOszButton.setOnClickListener {
            if (currentDirUri == null) {
                Toast.makeText(this, "Please select the 'Songs' folder first.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            oszPickerLauncher.launch(intent)
        }

        // REMOVED: shuffleButton listener
        
        repeatButton.setOnClickListener { toggleRepeat() }
        playButton.setOnClickListener { togglePlayback() }
        nextButton.setOnClickListener { playNext() }
        prevButton.setOnClickListener { playPrevious() }

        nowPlayingClose.setOnClickListener {
            nowPlayingBar.visibility = View.GONE
        }

        songSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && mediaPlayer != null) {
                    mediaPlayer!!.seekTo(progress)
                    currentTimeTextView.text = formatTime(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isUserSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { isUserSeeking = false }
        })

        tempoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val newTempo = (progress / 400.0f * 2.0f) + 0.5f
                currentTempo = newTempo
                tempoTextView.text = String.format(Locale.getDefault(), "%.2fx", newTempo)
                if (fromUser && mediaPlayer != null) {
                    applyPlaybackParams()
                    savePlaybackSetting(TEMPO_KEY, currentTempo)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        pitchSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val newPitch = (progress / 400.0f * 2.0f) + 0.5f
                currentPitch = newPitch
                pitchTextView.text = String.format(Locale.getDefault(), "%.2fx", newPitch)
                if (fromUser && mediaPlayer != null) {
                    applyPlaybackParams()
                    savePlaybackSetting(PITCH_KEY, currentPitch)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        tempoResetButton.setOnClickListener { resetTempoToNormal() }
        tempoResetButton.setOnLongClickListener { showTempoInputDialog(); true }
        pitchResetButton.setOnClickListener { resetPitchToNormal() }
        pitchResetButton.setOnLongClickListener { showPitchInputDialog(); true }

        sortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val sortBy = parent?.getItemAtPosition(position).toString()
                songAdapter?.sortSongs(sortBy)
                updateCurrentPlaybackList()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Only filter the display, don't update playback list
                songAdapter?.filter?.filter(s)
                
                // DO NOT call updateCurrentPlaybackList() here at all!
                // The playback list should remain unchanged during search
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedSong = songAdapter?.getItem(position)
            if (selectedSong != null) {
                // Find the song in the permanent playback list
                val indexInPlaybackList = currentPlaybackList.indexOfFirst { it.uriString == selectedSong.uriString }
                
                if (indexInPlaybackList != -1) {
                    // Song found in playback list, play it
                    playSong(indexInPlaybackList)
                } else {
                    // Song not in playback list (this shouldn't happen with our fix)
                    // Add it to playback list temporarily and play
                    Toast.makeText(this, "Song added to playback queue", Toast.LENGTH_SHORT).show()
                    currentPlaybackList.add(selectedSong)
                    playSong(currentPlaybackList.size - 1)
                }
            }
        }
    }

    private fun updateCurrentPlaybackList(preserveCurrentSong: Boolean = true) {
        // We ONLY update the playback list when ALL songs are shown (no search filter)
        // OR when the app first loads
        
        val isSearching = searchEditText.text?.isNotEmpty() == true
        
        if (!isSearching) {
            // Only update playback list when NOT searching
            val previousPlaybackList = currentPlaybackList.toList()
            
            currentPlaybackList.clear()
            currentPlaybackList.addAll(allSongEntries)
            
            // Only shuffle if the full list has actually changed
            val shouldShuffle = previousPlaybackList.isEmpty() || 
                               previousPlaybackList.size != currentPlaybackList.size ||
                               !previousPlaybackList.containsAll(currentPlaybackList)
            
            if (shouldShuffle) {
                currentPlaybackList.shuffle()
                Log.d(TAG, "Playback list shuffled. New size: ${currentPlaybackList.size}")
            }
            
            // Update current index if a song is playing
            if (preserveCurrentSong && currentPlayingSong != null) {
                val newIndex = currentPlaybackList.indexOfFirst { it.uriString == currentPlayingSong!!.uriString }
                if (newIndex != -1) {
                    currentIndex = newIndex
                    Log.d(TAG, "Updated current index to $currentIndex for playing song")
                } else {
                    // This shouldn't happen if we're using the full list
                    Log.w(TAG, "Playing song not found in full list, resetting to first song")
                    currentIndex = 0
                }
            }
        } else {
            // When searching, DO NOT update the playback list at all
            // Keep using the existing shuffled playlist
            Log.d(TAG, "Searching active, playback list unchanged (size: ${currentPlaybackList.size})")
        }
    }

    private fun resetTempoToNormal() {
        currentTempo = 1.0f
        tempoTextView.text = String.format(Locale.getDefault(), "%.2fx", currentTempo)
        val tempoProgress = ((currentTempo - 0.5f) / 2.0f * 400.0f).toInt().coerceIn(0, 400)
        tempoSeekBar.progress = tempoProgress
        if (mediaPlayer != null) applyPlaybackParams()
        savePlaybackSetting(TEMPO_KEY, currentTempo)
        Toast.makeText(this, "Tempo reset to 1.00x", Toast.LENGTH_SHORT).show()
    }

    private fun resetPitchToNormal() {
        currentPitch = 1.0f
        pitchTextView.text = String.format(Locale.getDefault(), "%.2fx", currentPitch)
        val pitchProgress = ((currentPitch - 0.5f) / 2.0f * 400.0f).toInt().coerceIn(0, 400)
        pitchSeekBar.progress = pitchProgress
        if (mediaPlayer != null) applyPlaybackParams()
        savePlaybackSetting(PITCH_KEY, currentPitch)
        Toast.makeText(this, "Pitch reset to 1.00x", Toast.LENGTH_SHORT).show()
    }

    private fun showTempoInputDialog() {
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        // Force dot decimal separator in the dialog text
        input.setText(String.format(Locale.US, "%.2f", currentTempo))
        AlertDialog.Builder(this)
            .setTitle("Set Tempo")
            .setMessage("Enter tempo value (0.50 - 2.50):")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val text = input.text.toString().replace(',', '.')
                try {
                    val newTempo = text.toFloat().coerceIn(0.5f, 2.5f)
                    currentTempo = newTempo
                    tempoTextView.text = String.format(Locale.getDefault(), "%.2fx", newTempo)
                    val tempoProgress = ((newTempo - 0.5f) / 2.0f * 400.0f).toInt().coerceIn(0, 400)
                    tempoSeekBar.progress = tempoProgress
                    if (mediaPlayer != null) applyPlaybackParams()
                    savePlaybackSetting(TEMPO_KEY, currentTempo)
                    Toast.makeText(this, "Tempo set to ${String.format(Locale.getDefault(), "%.2fx", newTempo)}", Toast.LENGTH_SHORT).show()
                } catch (e: NumberFormatException) {
                    Toast.makeText(this, "Invalid number format", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPitchInputDialog() {
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        // Force dot decimal separator in the dialog text
        input.setText(String.format(Locale.US, "%.2f", currentPitch))
        AlertDialog.Builder(this)
            .setTitle("Set Pitch")
            .setMessage("Enter pitch value (0.50 - 2.50):")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val text = input.text.toString().replace(',', '.')
                try {
                    val newPitch = text.toFloat().coerceIn(0.5f, 2.5f)
                    currentPitch = newPitch
                    pitchTextView.text = String.format(Locale.getDefault(), "%.2fx", newPitch)
                    val pitchProgress = ((newPitch - 0.5f) / 2.0f * 400.0f).toInt().coerceIn(0, 400)
                    pitchSeekBar.progress = pitchProgress
                    if (mediaPlayer != null) applyPlaybackParams()
                    savePlaybackSetting(PITCH_KEY, currentPitch)
                    Toast.makeText(this, "Pitch set to ${String.format(Locale.getDefault(), "%.2fx", newPitch)}", Toast.LENGTH_SHORT).show()
                } catch (e: NumberFormatException) {
                    Toast.makeText(this, "Invalid number format", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadInitialData() {
        val savedUriString = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(SAVED_URI_KEY, null)
        if (savedUriString != null) {
            try {
                val uri = Uri.parse(savedUriString)
                currentDirUri = uri
                Log.d(TAG, "Restored folder from preferences: $uri")
                loadSongsFromFile()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading saved URI.", e)
            }
        }

        // REMOVED: shuffle button initialization
        repeatButton.text = "🔁 Off"
        isRepeating = false

        currentTempo = loadPlaybackSetting(TEMPO_KEY)
        currentPitch = loadPlaybackSetting(PITCH_KEY)

        tempoTextView.text = String.format(Locale.getDefault(), "%.2fx", currentTempo)
        val tempoProgress = ((currentTempo - 0.5f) / 2.0f * 400.0f).toInt().coerceIn(0, 400)
        tempoSeekBar.progress = tempoProgress

        pitchTextView.text = String.format(Locale.getDefault(), "%.2fx", currentPitch)
        val pitchProgress = ((currentPitch - 0.5f) / 2.0f * 400.0f).toInt().coerceIn(0, 400)
        pitchSeekBar.progress = pitchProgress
    }

    private fun loadPlaybackSetting(key: String): Float {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getFloat(key, 1.0f)
    }

    private fun savePlaybackSetting(key: String, value: Float) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putFloat(key, value).apply()
    }

    private fun loadCache(): CachedSongData? {
        return try {
            val cacheFile = File(filesDir, "songs_cache.json")
            if (cacheFile.exists()) {
                val jsonString = cacheFile.readText()
                Json.decodeFromString(jsonString)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cache", e)
            null
        }
    }

    private fun saveCache(data: CachedSongData) {
        try {
            val cacheFile = File(filesDir, "songs_cache.json")
            val jsonString = Json.encodeToString(data)
            cacheFile.writeText(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving cache", e)
        }
    }

    private fun clearCache() {
        try {
            val cacheFile = File(filesDir, "songs_cache.json")
            cacheFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache", e)
        }
    }

    private fun loadBeatmapSongs(uri: Uri) = lifecycleScope.launch(Dispatchers.Main) {
        setLoading(true)
        val songs = withContext(Dispatchers.IO) {
            scanDirectoryForSongs(uri)
        }
        setLoading(false)

        if (songs != null) {
            allSongEntries.clear()
            allSongEntries.addAll(songs)
            refreshListView("Finished scanning! Found ${songs.size} playable songs.")
        } else {
            handlePermissionLoss(uri)
        }
    }

    private fun setLoading(isLoading: Boolean) {
        if (isLoading) {
            loadingSpinner.visibility = View.VISIBLE
            loadingText.visibility = View.VISIBLE
            scanProgressBar.visibility = View.VISIBLE
            scanningStatus.visibility = View.VISIBLE
            folderCountLabel.visibility = View.VISIBLE
            loadingText.text = "Scanning..."
            scanningStatus.text = "Scanning folders..."
            scanProgressBar.progress = 0
            folderCountLabel.text = "Scanned 0 of 0 folders"
            // Disable search during loading to prevent user input until list is ready
            searchEditText.isEnabled = false
            searchEditText.hint = "Loading..."
        } else {
            loadingSpinner.visibility = View.GONE
            loadingText.visibility = View.GONE
            scanProgressBar.visibility = View.GONE
            scanningStatus.visibility = View.GONE
            folderCountLabel.visibility = View.GONE
            // Re-enable search when loading finished
            searchEditText.isEnabled = true
            searchEditText.hint = "Search"
        }
    }

    private fun saveFolderUri(uri: Uri) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(SAVED_URI_KEY, uri.toString()).apply()
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    // REMOVED: toggleShuffle function entirely

    private fun toggleRepeat() {
        isRepeating = !isRepeating
        if (isRepeating) {
            repeatButton.text = "🔁 On"
            Toast.makeText(this, "Repeat ON (Single Song).", Toast.LENGTH_SHORT).show()
        } else {
            repeatButton.text = "🔁 Off"
            Toast.makeText(this, "Repeat OFF.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyPlaybackParams() {
        if (mediaPlayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val params = mediaPlayer!!.playbackParams
                params.speed = currentTempo
                params.pitch = currentPitch
                mediaPlayer!!.playbackParams = params
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set PlaybackParams.", e)
                Toast.makeText(this, "Speed/Pitch control unavailable or failed.", Toast.LENGTH_SHORT).show()
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "Speed/Pitch control requires Android 6.0 (API 23) or higher.", Toast.LENGTH_LONG).show()
        }
    }

    private fun togglePlayback() {
        if (currentPlaybackList.isEmpty()) {
            Toast.makeText(this, "Song list is empty.", Toast.LENGTH_SHORT).show()
            return
        }

        if (mediaPlayer == null) {
            playSong(currentIndex)
        } else {
            try {
                if (mediaPlayer!!.isPlaying) {
                    mediaPlayer!!.pause()
                    playButton.text = "▶"
                    // Release wakelock when pausing
                    if (wakeLock != null && wakeLock!!.isHeld) {
                        wakeLock!!.release()
                        Log.d(TAG, "WakeLock released on pause")
                    }
                } else {
                    mediaPlayer!!.start()
                    applyPlaybackParams()
                    playButton.text = "⏸"
                    // Acquire wakelock when resuming
                    if (wakeLock != null && !wakeLock!!.isHeld) {
                        wakeLock!!.acquire()
                        Log.d(TAG, "WakeLock acquired on resume")
                    }
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "IllegalStateException during togglePlayback.", e)
                Toast.makeText(this, "Playback error, trying to restart song.", Toast.LENGTH_SHORT).show()
                playSong(currentIndex)
            }
        }
    }

    private val updateSeekBar = object : Runnable {
        override fun run() {
            if (mediaPlayer != null && !isUserSeeking) {
                try {
                    val currentPos = mediaPlayer!!.currentPosition
                    val totalDuration = mediaPlayer!!.duration
                    songSeekBar.progress = currentPos
                    currentTimeTextView.text = formatTime(currentPos)
                    if (totalDuration > 0 && totalTimeTextView.text == "0:00") {
                        totalTimeTextView.text = formatTime(totalDuration)
                    }
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Ignoring IllegalStateException during seekBar update.")
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun playSong(index: Int) {
        // Always use the permanent shuffled playback list!
        val playbackList = currentPlaybackList
        if (playbackList.isEmpty() || index < 0 || index >= playbackList.size) {
            Log.e(TAG, "Invalid play index: $index, list size: ${playbackList.size}")
            return
        }

        currentIndex = index
        val songEntry = playbackList[index]
        currentPlayingSong = songEntry

        Log.d(TAG, "Playing song at index $index: ${songEntry.label}")

        try {
            mediaPlayer?.release()
            // Release wakelock when switching songs
            if (wakeLock != null && wakeLock!!.isHeld) {
                wakeLock!!.release()
                Log.d(TAG, "WakeLock released on song switch")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing old media player.", e)
        }
        mediaPlayer = null
        handler.removeCallbacks(updateSeekBar)
        songSeekBar.progress = 0
        currentTimeTextView.text = "0:00"
        totalTimeTextView.text = "0:00"

        mediaPlayer = MediaPlayer()

        try {
            contentResolver.openAssetFileDescriptor(Uri.parse(songEntry.uriString), "r")?.use { descriptor ->
                mediaPlayer!!.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            } ?: run {
                Toast.makeText(this, "Failed to load descriptor for: ${songEntry.label}", Toast.LENGTH_LONG).show()
                playButton.text = "▶"
                return
            }

            mediaPlayer!!.setOnPreparedListener {
                applyPlaybackParams()
                it.start()
                
                // Acquire wakelock to keep device awake during playback
                if (wakeLock != null && !wakeLock!!.isHeld) {
                    wakeLock!!.acquire()
                    Log.d(TAG, "WakeLock acquired for playback")
                }
                
                playButton.text = "⏸"
                
                // Show now playing bar and highlight song
                nowPlayingText.text = "Now Playing: ${songEntry.label}"
                nowPlayingBar.visibility = View.VISIBLE
                songAdapter?.setCurrentlyPlaying(songEntry)
                
                Toast.makeText(this, "Playing: ${songEntry.label}", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Playing: ${songEntry.label}")

                songSeekBar.max = it.duration
                totalTimeTextView.text = formatTime(it.duration)
                handler.post(updateSeekBar)
            }

            mediaPlayer!!.setOnErrorListener { _, what, extra ->
                 Log.e(TAG, "MediaPlayer Error: what=$what, extra=$extra for ${songEntry.label}")
                 Toast.makeText(this, "Playback Error ($what).", Toast.LENGTH_LONG).show()
                 // Release wakelock on error
                 if (wakeLock != null && wakeLock!!.isHeld) {
                     wakeLock!!.release()
                     Log.d(TAG, "WakeLock released on playback error")
                 }
                 mediaPlayer?.release()
                 mediaPlayer = null
                 playButton.text = "▶"
                 false
            }

            mediaPlayer!!.setOnCompletionListener {
                if (isRepeating) {
                    mediaPlayer!!.seekTo(0)
                    mediaPlayer!!.start()
                } else {
                    playNext()
                }
            }
            mediaPlayer!!.prepareAsync()
            playButton.text = "⏳"

        } catch (e: Exception) {
            Toast.makeText(this, "Fatal error setting up player for: ${songEntry.label}", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Failed to setup media player.", e)
            // Release wakelock on exception
            if (wakeLock != null && wakeLock!!.isHeld) {
                wakeLock!!.release()
                Log.d(TAG, "WakeLock released on exception")
            }
            mediaPlayer?.release()
            mediaPlayer = null
            playButton.text = "▶"
        }
    }

    private fun playNext() {
        // Always use the permanent shuffled playback list!
        val playbackList = currentPlaybackList
        if (playbackList.isEmpty()) {
            Log.d(TAG, "Playback list is empty, cannot play next")
            return
        }
        
        val nextIndex = (currentIndex + 1) % playbackList.size
        Log.d(TAG, "Playing next song. Current: $currentIndex, Next: $nextIndex, List size: ${playbackList.size}")
        playSong(nextIndex)
    }

    private fun playPrevious() {
        // Always use the permanent shuffled playback list!
        val playbackList = currentPlaybackList
        if (playbackList.isEmpty()) {
            Log.d(TAG, "Playback list is empty, cannot play previous")
            return
        }
        
        val prevIndex = if (currentIndex - 1 < 0) playbackList.size - 1 else currentIndex - 1
        Log.d(TAG, "Playing previous song. Current: $currentIndex, Previous: $prevIndex, List size: ${playbackList.size}")
        playSong(prevIndex)
    }

    // OPTIMIZED: Faster OSZ import with parallel extraction
    private fun importOszFiles(oszUris: List<Uri>, targetDirUri: Uri) {
        var successfulImports = 0
        var failedImports = 0

        loadingSpinner.visibility = View.VISIBLE
        loadingText.visibility = View.VISIBLE
        loadingText.text = "Importing 0/${oszUris.size} files..."

        lifecycleScope.launch(Dispatchers.Main) {
            val targetDir = DocumentFile.fromTreeUri(this@MainActivity, targetDirUri)
            if (targetDir == null || !targetDir.isDirectory) {
                Toast.makeText(this@MainActivity, "Invalid target folder.", Toast.LENGTH_LONG).show()
                hideLoading()
                return@launch
            }

            val newlyCreatedFolders = mutableListOf<DocumentFile>()

            // Process OSZ files in parallel for faster import
            val importJobs = oszUris.map { oszUri ->
                async(Dispatchers.IO) {
                    performOszExtraction(oszUri, targetDir)
                }
            }

            importJobs.forEachIndexed { index, deferred ->
                val newFolder = deferred.await()
                withContext(Dispatchers.Main) {
                    loadingText.text = "Importing ${index + 1}/${oszUris.size} files..."
                }

                if (newFolder != null) {
                    successfulImports++
                    newlyCreatedFolders.add(newFolder)
                    // Delete source OSZ file
                    withContext(Dispatchers.IO) {
                        try {
                            DocumentFile.fromSingleUri(this@MainActivity, oszUris[index])?.delete()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to delete source OSZ file", e)
                        }
                    }
                } else {
                    failedImports++
                }
            }

            // Scan newly created folders in parallel using coroutines
            val newSongs = withContext(Dispatchers.IO) {
                newlyCreatedFolders.map { folder ->
                    async { processBeatmapFolder(folder) }
                }.awaitAll().flatten()
            }

            hideLoading()

            if (newSongs.isNotEmpty()) {
                allSongEntries.addAll(newSongs)
                refreshListView("Import complete: $successfulImports successful. Added ${newSongs.size} new songs.")
            } else {
                Toast.makeText(this@MainActivity, "Import complete: $successfulImports successful, $failedImports failed. No new playable songs found.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun performOszExtraction(oszUri: Uri, targetDir: DocumentFile): DocumentFile? = withContext(Dispatchers.IO) {
        val oszFileName = DocumentFile.fromSingleUri(this@MainActivity, oszUri)?.name ?: return@withContext null
        val folderName = oszFileName.substringBeforeLast('.')

        return@withContext try {
            contentResolver.openInputStream(oszUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipStream ->
                    val newFolder = targetDir.createDirectory(folderName) ?: return@withContext null

                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val fileName = entry.name.substringAfterLast('/')
                            if (fileName.isNotEmpty()) {
                                val file = newFolder.createFile("application/octet-stream", fileName)
                                contentResolver.openOutputStream(file?.uri ?: continue)?.use { outputStream ->
                                    zipStream.copyTo(outputStream)
                                }
                            }
                        }
                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                    newFolder
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract OSZ file: $oszFileName", e)
            null
        }
    }

    private fun hideLoading() {
        loadingSpinner.visibility = View.GONE
        loadingText.visibility = View.GONE
        scanProgressBar.visibility = View.GONE
        scanningStatus.visibility = View.GONE
        folderCountLabel.visibility = View.GONE
    }

    // OPTIMIZED: Faster folder processing with parallel execution
    private suspend fun processBeatmapFolder(folder: DocumentFile): List<SongEntry> = withContext(Dispatchers.IO) {
        val osuFiles = folder.listFiles()?.filter { it.name?.endsWith(".osu") == true } ?: return@withContext emptyList()

        // Process .osu files in parallel
        val deferredMetadata = osuFiles.map { file ->
            async { parseOsuFile(file) }
        }
        val metadataList = deferredMetadata.awaitAll().filterNotNull()

        val entries = mutableListOf<SongEntry>()
        metadataList
            .groupBy { it.audioFilename }
            .forEach { (audioFilename, beatmaps) ->
                val metadata = beatmaps.first()
                val audioFile = folder.listFiles()?.find {
                    it.name?.equals(audioFilename, ignoreCase = true) == true
                }
                if (audioFile != null && audioFile.isFile) {
                    val label = if (beatmaps.size > 1) {
                        "${metadata.artist} - ${metadata.title} (${beatmaps.size} versions)"
                    } else {
                        "${metadata.artist} - ${metadata.title}"
                    }
                    
                    // NEW: Include BPM range in the song entry
                    entries.add(SongEntry(
                        label, 
                        audioFile.uri.toString(), 
                        metadata.artist, 
                        metadata.title, 
                        metadata.bpm,
                        metadata.bpmRange,
                        metadata.bpms
                    ))
                }
            }
        entries
    }

    private fun handlePermissionLoss(uri: Uri) {
        Log.w(TAG, "Permission for $uri lost.")
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(SAVED_URI_KEY).apply()
        currentDirUri = null
        allSongEntries.clear()
        songAdapter?.notifyDataSetChanged()
        AlertDialog.Builder(this)
            .setTitle("Storage Access Required")
            .setMessage("Access to the previously selected 'Songs' folder has been lost. You must re-select the folder to grant permanent access again.")
            .setPositiveButton("Select Folder") { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                folderPickerLauncher.launch(intent)
            }
            .setCancelable(false)
            .show()
    }

    // OPTIMIZED: Faster directory scanning with parallel folder processing
    private suspend fun scanDirectoryForSongs(uri: Uri): List<SongEntry>? = withContext(Dispatchers.IO) {
        try {
            if (contentResolver.persistedUriPermissions.none { it.uri == uri }) {
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking URI permissions.", e)
            return@withContext null
        }

        val pickedDir = DocumentFile.fromTreeUri(this@MainActivity, uri) ?: return@withContext null
        val allFolders = pickedDir.listFiles().filter { it.isDirectory }
        val total = allFolders.size

        // Try to load from cache first
        val cachedData = loadCache()
        val folderModTimes = mutableMapOf<String, Long>()
        var needsFullRescan = cachedData == null

        // Check if any folder has been modified since last scan
        if (!needsFullRescan && cachedData != null) {
            for (folder in allFolders) {
                val folderName = folder.name ?: continue
                val currentModTime = folder.lastModified()
                folderModTimes[folderName] = currentModTime
                
                val cachedModTime = cachedData.folderModificationTimes[folderName]
                if (cachedModTime == null || cachedModTime != currentModTime) {
                    needsFullRescan = true
                    break
                }
            }
        }

        // Return cached data if nothing has changed
        if (!needsFullRescan && cachedData != null) {
            Log.d(TAG, "Using cached song data")
            return@withContext cachedData.songs
        }

        // Perform full scan
        Log.d(TAG, "Performing full scan of $total folders")
        val progressCounter = AtomicInteger(0)

        // Process folders in parallel with optimized concurrency
        val folderResults = allFolders.map { folder ->
            async { 
                val folderEntries = processBeatmapFolder(folder)
                val currentProgress = progressCounter.incrementAndGet()
                
                // Update progress on main thread
                if (currentProgress % 5 == 0 || currentProgress == total) {
                    withContext(Dispatchers.Main) {
                        scanProgressBar.progress = if (total > 0) (currentProgress * 100 / total) else 0
                        folderCountLabel.text = "Scanned $currentProgress of $total folders"
                    }
                }
                
                // Track folder modification time
                val folderName = folder.name ?: ""
                folderModTimes[folderName] = folder.lastModified()
                
                folderEntries
            }
        }.awaitAll().flatten()

        // Cache the results
        try {
            saveCache(CachedSongData(folderResults, folderModTimes))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save cache", e)
        }

        withContext(Dispatchers.Main) {
            scanningStatus.text = "Finalizing song list..."
        }
        
        folderResults
    }

    /**
     * Enhanced .osu file parser that extracts metadata AND BPM with duration analysis
     */
    private fun parseOsuFile(file: DocumentFile): OsuMetadata? {
        return try {
            contentResolver.openInputStream(file.uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                var audioFilename: String? = null
                var artist: String? = null
                var title: String? = null
                val allBpms = mutableListOf<Double>()
                var inTimingSection = false
                var line: String?
                
                // Variables for BPM timing analysis
                var lastTime = 0.0
                var currentBpm: Double? = null
                val bpmSegments = mutableListOf<Pair<Double, Double>>() // (duration, bpm)

                while (reader.readLine().also { line = it } != null) {
                    when {
                        line == "[TimingPoints]" -> {
                            inTimingSection = true
                            lastTime = 0.0
                            currentBpm = null
                            continue
                        }
                        line?.startsWith("[") == true && line != "[TimingPoints]" -> {
                            inTimingSection = false
                        }
                        line?.startsWith("AudioFilename:") == true -> {
                            audioFilename = line?.substringAfter(":")?.trim()
                        }
                        line?.startsWith("Artist:") == true -> {
                            artist = line?.substringAfter(":")?.trim()
                        }
                        line?.startsWith("Title:") == true -> {
                            title = line?.substringAfter(":")?.trim()
                        }
                        inTimingSection -> {
                            val timingParts = line?.split(",")
                            if (timingParts != null && timingParts.size >= 2) {
                                try {
                                    val time = timingParts[0].toDouble()
                                    val beatLength = timingParts[1].toDouble()
                                    
                                    // Only process uninherited timing points (BPM changes)
                                    val uninherited = if (timingParts.size >= 7) {
                                        timingParts[6].toInt() == 1
                                    } else {
                                        true  // Assume uninherited if field doesn't exist
                                    }
                                    
                                    if (uninherited && beatLength > 0) {
                                        val calculatedBpm = 60000.0 / beatLength
                                        
                                        // Validate BPM range
                                        if (calculatedBpm >= 30 && calculatedBpm <= 600) {
                                            allBpms.add(calculatedBpm)
                                            
                                            // Track BPM segments for duration analysis
                                            if (currentBpm != null) {
                                                bpmSegments.add(Pair(time - lastTime, currentBpm))
                                            }
                                            
                                            currentBpm = calculatedBpm
                                            lastTime = time
                                        }
                                    }
                                } catch (e: NumberFormatException) {
                                    // Ignore malformed timing points
                                }
                            }
                        }
                    }

                    // Stop reading after we've processed TimingPoints section (if it exists)
                    // This ensures we read all BPM data before stopping
                    if (audioFilename != null && artist != null && title != null) {
                        // Check if we've seen the TimingPoints section and are now in a different section
                        // OR if we've reached the end of the file after seeing timing points
                        if (inTimingSection && line?.startsWith("[") == true && line != "[TimingPoints]") {
                            // We're leaving the TimingPoints section
                            if (currentBpm != null) {
                                bpmSegments.add(Pair(100000.0, currentBpm))
                            }
                            break
                        }
                    }
                }

                if (audioFilename != null && artist != null && title != null) {
                    // Calculate the most accurate BPM representation
                    val (mainBpm, bpmRange) = analyzeBpms(allBpms, bpmSegments, file.name ?: "unknown")
                    
                    Log.d(TAG, "BPM Analysis for ${file.name}: $bpmRange")
                    OsuMetadata(audioFilename, artist, title, mainBpm, bpmRange, allBpms.distinct())
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing .osu file: ${file.name}", e)
            null
        }
    }

    /**
     * Enhanced BPM analysis that considers both frequency and duration
     */
    private fun analyzeBpms(allBpms: List<Double>, bpmSegments: List<Pair<Double, Double>>, fileName: String): Pair<Double?, String?> {
        if (allBpms.isEmpty()) return Pair(null, null)
        
        // If only one BPM, return it
        if (allBpms.size == 1) {
            val bpm = allBpms.first()
            return Pair(bpm, "${formatBpm(bpm)} BPM")
        }
        
        // Group similar BPMs (within 1% tolerance)
        val tolerance = 0.01 // 1% tolerance
        val groupedBpms = mutableMapOf<Double, Int>()
        
        for (bpm in allBpms) {
            // Find if this BPM is similar to any existing group
            val similarGroup = groupedBpms.keys.find { 
                Math.abs(it - bpm) / it < tolerance 
            }
            
            if (similarGroup != null) {
                // Add to existing group (weighted average)
                val newBpm = (similarGroup * groupedBpms[similarGroup]!! + bpm) / (groupedBpms[similarGroup]!! + 1)
                val count = groupedBpms.remove(similarGroup)!!
                groupedBpms[newBpm] = count + 1
            } else {
                // Create new group
                groupedBpms[bpm] = 1
            }
        }
        
        // Calculate min and max BPM
        val minBpm = allBpms.minOrNull() ?: 0.0
        val maxBpm = allBpms.maxOrNull() ?: 0.0
        
        // Find the most frequent BPM (by count)
        val mostFrequentByCount = groupedBpms.maxByOrNull { it.value }?.key
        
        // Find the dominant BPM by duration (if we have segment data)
        val dominantBpm = if (bpmSegments.isNotEmpty()) {
            val durationByBpm = mutableMapOf<Double, Double>()
            for ((duration, bpm) in bpmSegments) {
                // Find the closest grouped BPM
                val closestGroup = groupedBpms.keys.minByOrNull { Math.abs(it - bpm) } ?: bpm
                durationByBpm[closestGroup] = durationByBpm.getOrDefault(closestGroup, 0.0) + duration
            }
            durationByBpm.maxByOrNull { it.value }?.key
        } else {
            mostFrequentByCount
        }
        
        // Format the BPM range string
        val rangeString = if (Math.abs(maxBpm - minBpm) < 5) {
            // If BPM range is small, just show the average
            val avgBpm = allBpms.average()
            "${formatBpm(avgBpm)} BPM"
        } else {
            // Show range with dominant BPM
            val dominant = dominantBpm ?: mostFrequentByCount ?: allBpms.average()
            "${formatBpm(minBpm)} to ${formatBpm(maxBpm)} BPM (mostly ${formatBpm(dominant)})"
        }
        
        Log.d(TAG, "BPM Analysis for $fileName: Min=${formatBpm(minBpm)}, Max=${formatBpm(maxBpm)}, Dominant=${dominantBpm?.let { formatBpm(it) }}, Range=$rangeString")
        
        return Pair(dominantBpm ?: mostFrequentByCount, rangeString)
    }

    /**
     * Helper function to format BPM
     */
    private fun formatBpm(bpm: Double): String {
        return String.format(Locale.getDefault(), "%.0f", bpm)
    }

    // UPDATED: SongAdapter with BPM range display and BPM search
    private class SongAdapter(
        context: Context,
        songs: List<SongEntry>,
        private val searchEditText: EditText
    ) : ArrayAdapter<SongEntry>(context, 0, songs.toMutableList()), Filterable {

        private var allSongs: List<SongEntry> = songs
        private var currentFilteredSongs: List<SongEntry> = songs.toMutableList()
        private var currentlyPlaying: SongEntry? = null
        private val layoutInflater = LayoutInflater.from(context)
        
        // No automatic scrolling; allow manual horizontal swipe on each ScrollingTextView
        


        override fun getCount(): Int = currentFilteredSongs.size
        override fun getItem(position: Int): SongEntry? = currentFilteredSongs[position]
        
        fun getAllSongs(): List<SongEntry> = allSongs
        fun getAllFilteredSongs(): List<SongEntry> = currentFilteredSongs

        fun setCurrentlyPlaying(song: SongEntry?) {
            currentlyPlaying = song
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val song = getItem(position)
            val view = convertView ?: layoutInflater.inflate(R.layout.list_item_song, parent, false)

            val titleTextView = view.findViewById<TextView>(R.id.textTitle)
            val artistTextView = view.findViewById<ScrollingTextView>(R.id.textArtist)

            if (song != null) {
                titleTextView.text = song.title
                
                // Display BPM range if available, otherwise just artist
                val bpmText = song.bpmRange ?: if (song.bpm != null) {
                    "${String.format(Locale.getDefault(), "%.0f", song.bpm)} BPM"
                } else {
                    null
                }
                
                val artistText = if (bpmText != null) {
                    "${song.artist} • $bpmText"
                } else {
                    song.artist
                }
                
                artistTextView.setText(artistText)
                
                // Start scrolling animation
                artistTextView.post {
                    val textWidth = artistTextView.measureText(artistText)
                    val viewWidth = (artistTextView.width - artistTextView.paddingLeft - artistTextView.paddingRight).toFloat()
                    
                    startTextScrolling(artistTextView, textWidth, viewWidth)
                }
                
                // Highlight currently playing song
                if (song == currentlyPlaying) {
                    view.setBackgroundColor(0xFF45475A.toInt())
                    titleTextView.setTextColor(0xFFCBA6F7.toInt())
                } else {
                    view.setBackgroundColor(0x0024273A)
                    titleTextView.setTextColor(0xFFBAC2DE.toInt())
                }
            }

            return view
        }

        private fun startTextScrolling(textView: ScrollingTextView, textWidth: Float, viewWidth: Float) {
            // Only scroll if text is actually longer than view
            if (textWidth <= viewWidth) {
                textView.setScrollPosition(0f)
                return
            }
            
            // Calculate animation parameters
            val scrollDistance = textWidth + viewWidth
            val duration = (scrollDistance * 20).toLong().coerceAtLeast(5000)
            
            // Manual swipe mode: nothing to register, individual view handles touch panning

        }
        


        override fun getFilter(): Filter {
            return object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    val results = FilterResults()
                    val query = constraint.toString().toLowerCase(Locale.getDefault()).trim()
                    val filteredList = if (query.isEmpty()) {
                        allSongs
                    } else {
                        allSongs.filter { song ->
                            // Search in title and artist
                            val matchesText = song.title.toLowerCase(Locale.getDefault()).contains(query) ||
                                            song.artist.toLowerCase(Locale.getDefault()).contains(query)
                            
                            // Search by BPM (exact or approximate)
                            val matchesBpm = if (song.bpm != null) {
                                // Try to parse the query as a number for BPM search
                                try {
                                    val bpmQuery = query.toDoubleOrNull()
                                    if (bpmQuery != null) {
                                        // Check if BPM matches within ±5 BPM
                                        song.bpm in (bpmQuery - 5)..(bpmQuery + 5)
                                    } else {
                                        // Check if query contains "bpm" and a number
                                        val bpmRegex = "(\\d+)\\s*bpm".toRegex(RegexOption.IGNORE_CASE)
                                        val match = bpmRegex.find(query)
                                        if (match != null) {
                                            val bpmValue = match.groupValues[1].toDoubleOrNull()
                                            bpmValue != null && song.bpm in (bpmValue - 5)..(bpmValue + 5)
                                        } else {
                                            false
                                        }
                                    }
                                } catch (e: NumberFormatException) {
                                    false
                                }
                            } else {
                                false
                            }
                            
                            matchesText || matchesBpm
                        }
                    }
                    results.values = filteredList
                    results.count = filteredList.size
                    return results
                }

                @Suppress("UNCHECKED_CAST")
                override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                    currentFilteredSongs = results.values as List<SongEntry>
                    notifyDataSetChanged()
                }
            }
        }

        fun sortSongs(sortBy: String) {
            allSongs = when (sortBy) {
                "Title" -> allSongs.sortedBy { it.title.toLowerCase(Locale.getDefault()) }
                "Artist" -> allSongs.sortedBy { it.artist.toLowerCase(Locale.getDefault()) }
                "Versions" -> allSongs.sortedByDescending { it.label.count { c -> c == '(' } }
                "BPM" -> allSongs.sortedBy { it.bpm ?: 0.0 }
                else -> allSongs
            }
            filter.filter(searchEditText.text?.toString())
        }
    }

    private fun refreshListView(logMessage: String) {
        saveSongsToFile()
        songAdapter = SongAdapter(this, allSongEntries, searchEditText)
        listView.adapter = songAdapter
        
        // Always update the playback list when refreshing (app loads or scans)
        // This creates the permanent shuffled playlist
        updateCurrentPlaybackList()
        
        val currentSortOption = sortSpinner.selectedItem?.toString() ?: "Title"
        songAdapter?.sortSongs(currentSortOption)
        songAdapter?.filter?.filter("")

        Toast.makeText(this, logMessage, Toast.LENGTH_SHORT).show()
        Log.d(TAG, logMessage)
        // Re-enable search input now that the list is refreshed
        searchEditText.isEnabled = true
        searchEditText.hint = "Search"
    }

    private fun saveSongsToFile() = lifecycleScope.launch(Dispatchers.IO) {
        try {
            val json = Json.encodeToString(allSongEntries)
            File(filesDir, SONGS_FILE_NAME).writeText(json)
            Log.d(TAG, "Saved ${allSongEntries.size} songs to internal file")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving songs to file.", e)
        }
    }

    private fun loadSongsFromFile() = lifecycleScope.launch(Dispatchers.Main) {
        val savedEntries = withContext(Dispatchers.IO) {
            try {
                val file = File(filesDir, SONGS_FILE_NAME)
                if (!file.exists()) return@withContext null
                val json = file.readText()
                val entries = Json.decodeFromString<List<SongEntry>>(json)
                entries.filter {
                    try {
                        DocumentFile.fromSingleUri(this@MainActivity, Uri.parse(it.uriString))?.exists() == true
                    } catch (e: Exception) {
                        false
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
        if (savedEntries != null) {
            allSongEntries.clear()
            allSongEntries.addAll(savedEntries)
            refreshListView("Loaded ${savedEntries.size} songs from cache.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        savePlaybackSetting(TEMPO_KEY, currentTempo)
        savePlaybackSetting(PITCH_KEY, currentPitch)
        handler.removeCallbacks(updateSeekBar)
        
        // Release wakelock
        if (wakeLock != null && wakeLock!!.isHeld) {
            wakeLock!!.release()
            Log.d(TAG, "WakeLock released on activity destroy")
        }
        
        mediaPlayer?.release()
        mediaPlayer = null
        Log.d(TAG, "App destroyed, media player released")
    }
}

/**
 * Custom View for rendering scrolling text without clipping issues
 */
class ScrollingTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f * context.resources.displayMetrics.scaledDensity
        color = 0xB3FFFFFF.toInt()
    }
    
    private var text: String = ""
    private var scrollX: Float = 0f

    // Touch handling
    private var lastTouchX: Float = 0f
    private var isDragging: Boolean = false
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawText(text, scrollX, (height * 0.7f), paint)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
    
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val textHeight = paint.fontMetrics.let { it.descent - it.ascent }
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            (textHeight + paddingTop + paddingBottom).toInt()
        )
    }
    
    fun setText(newText: String) {
        text = newText
        // Reset scroll so new text starts at left
        scrollX = 0f
        invalidate()
    }
    
    fun setScrollPosition(x: Float) {
        scrollX = x
        invalidate()
    }
    
    fun measureText(text: String): Float = paint.measureText(text)

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        // Allow horizontal panning when text is wider than available view
        val textWidth = measureText(text)
        val visibleWidth = (width - paddingLeft - paddingRight).toFloat()
        if (textWidth <= visibleWidth) return false

        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                performClick()
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                if (!isDragging && kotlin.math.abs(dx) > 4f) isDragging = true
                if (isDragging) {
                    lastTouchX = event.x
                    val minScroll = -(textWidth - visibleWidth)
                    scrollX = (scrollX + dx).coerceIn(minScroll, 0f)
                    invalidate()
                    return true
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
    
    // Property for ObjectAnimator
    @Suppress("UNUSED")
    fun getScrollPosition(): Float = scrollX
}