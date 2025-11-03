package com.example.osutunes // IMPORTANT: Must match the namespace in app/build.gradle

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.PlaybackParams // Import for speed/pitch control
import android.net.Uri
import android.os.Build // Import for version check
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.Toast // Explicitly import Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.*
import java.util.zip.ZipInputStream
import android.os.Process
import com.example.osutunes.R // Corrected: This import is now redundant but kept for clarity if using a different package structure.

class MainActivity : AppCompatActivity() {

    // --- Constants and Tags ---
    private companion object {
        const val PREFS_NAME = "osuTunesPrefs"
        const val SAVED_URI_KEY = "savedFolderUri"
        const val SONGS_FILE_NAME = "songs.json"
        const val TAG = "OsuTunes"
        const val TEMPO_KEY = "savedPlaybackTempo"
        const val PITCH_KEY = "savedPlaybackPitch"  

        // JSON parser configured to ignore keys we might not recognize, increasing stability
        val Json = Json { ignoreUnknownKeys = true }
    }

    // --- Data Classes ---
    @Serializable
    private data class SongEntry(
        val label: String,
        val uriString: String,
        val artist: String,
        val title: String
    )
    private data class OsuMetadata(val audioFilename: String, val artist: String, val title: String)

    // --- UI Components ---
    // All IDs matched against the provided activity_main.xml (not shown in full, but based on recent changes)
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


    // --- State Variables ---
    private var allSongEntries = mutableListOf<SongEntry>()
    private var isShuffling = false
    private var isRepeating = false
    private var currentTempo = 1.0f
    private var currentPitch = 1.0f
    private var shuffledSongEntries = mutableListOf<SongEntry>()
    private var currentIndex = 0
    private var mediaPlayer: MediaPlayer? = null
    private var currentDirUri: Uri? = null
    private var isUserSeeking = false
    private val handler = Handler(Looper.getMainLooper())
    private var songAdapter: SongAdapter? = null // Custom Adapter

    // --- Activity Result Launchers (Unchanged) ---
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
            if (uris.isNotEmpty() && currentDirUri != null) {
                importOszFiles(uris, currentDirUri!!)
            } else if (currentDirUri == null) {
                Toast.makeText(this, "Please select the 'Songs' folder first.", Toast.LENGTH_LONG).show()
            }
        }
    }


    // --- Lifecycle and Initialization ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupCrashHandler()
        // CRITICAL: R.layout.activity_main must exist
        setContentView(R.layout.activity_main) 

        initViews()
        setupListeners()
        loadInitialData()
    }

    private fun setupCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            Log.e(TAG, "FATAL CRASH on Thread: ${thread.name}", exception)
            Handler(Looper.getMainLooper()).post {
                val errorMsg = "FATAL CRASH: ${exception.javaClass.simpleName} - ${exception.message}"
                // NOTE: This Toast won't show if the crash happens before Looper is ready.
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
        // Double-check all these IDs exist in activity_main.xml
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

        // NEW UI ELEMENTS (Sorting, Filtering, Time Display, Tempo, Pitch)
        sortSpinner = findViewById(R.id.sortSpinner)
        searchEditText = findViewById(R.id.searchEditText)
        currentTimeTextView = findViewById(R.id.currentTimeTextView)
        totalTimeTextView = findViewById(R.id.totalTimeTextView)
        tempoSeekBar = findViewById(R.id.tempoSeekBar)
        tempoTextView = findViewById(R.id.tempoTextView)
        pitchSeekBar = findViewById(R.id.pitchSeekBar)
        pitchTextView = findViewById(R.id.pitchTextView)
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
                .setMessage("Rescan the entire current folder for songs?")
                .setPositiveButton("Yes") { _, _ ->
                    loadBeatmapSongs(uri)
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
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/zip",
                    "application/octet-stream",
                    "application/x-osu-beatmap"
                ))
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            oszPickerLauncher.launch(intent)
        }

        shuffleButton.setOnClickListener { toggleShuffle() }
        repeatButton.setOnClickListener { toggleRepeat() }

        playButton.setOnClickListener { togglePlayback() }
        nextButton.setOnClickListener { playNext() }
        prevButton.setOnClickListener { playPrevious() }

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

        // UPDATED: Tempo (Speed) Seek Bar Listener
        tempoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Range: 0.5x (0) to 2.5x (400)
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

        // ADDED: Pitch Seek Bar Listener
        pitchSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Range: 0.5x (0) to 2.5x (400)
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

        // Sort Spinner Listener (Unchanged)
        sortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val sortBy = parent?.getItemAtPosition(position).toString()
                songAdapter?.sortSongs(sortBy)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Search/Filter Text Listener (Unchanged)
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                songAdapter?.filter?.filter(s)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Item click listener (Unchanged)
        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedSong = songAdapter?.getItem(position)
            val indexInPlaybackList = currentPlaybackList.indexOf(selectedSong)
            if (indexInPlaybackList != -1) {
                playSong(indexInPlaybackList)
            }
        }
    }

    private fun loadInitialData() {
        val savedUriString = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(SAVED_URI_KEY, null)

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

        // Set initial states for shuffle and repeat
        shuffleButton.text = "🔀 Off"
        isShuffling = false
        shuffledSongEntries.clear()

        repeatButton.text = "🔁 Off"
        isRepeating = false

        // Load and set saved playback settings
        currentTempo = loadPlaybackSetting(TEMPO_KEY)
        currentPitch = loadPlaybackSetting(PITCH_KEY) // ADDED

        // Set Tempo UI
        tempoTextView.text = String.format(Locale.getDefault(), "%.2fx", currentTempo)
        // Convert speed (0.5 to 2.5) back to Seekbar progress (0 to 400)
        val tempoProgress = ((currentTempo - 0.5f) / 2.0f * 400.0f).toInt().coerceIn(0, 400)
        tempoSeekBar.progress = tempoProgress

        // Set Pitch UI
        pitchTextView.text = String.format(Locale.getDefault(), "%.2fx", currentPitch)
        val pitchProgress = ((currentPitch - 0.5f) / 2.0f * 400.0f).toInt().coerceIn(0, 400)
        pitchSeekBar.progress = pitchProgress
    }

    // Helper function for persistence (UPDATED)
    private fun loadPlaybackSetting(key: String): Float {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getFloat(key, 1.0f)
    }

    // Helper function for persistence (UPDATED)
    private fun savePlaybackSetting(key: String, value: Float) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putFloat(key, value)
            .apply()
    }

    // Non-suspending wrapper to start the coroutine (Unchanged)
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
        } else {
            loadingSpinner.visibility = View.GONE
            loadingText.visibility = View.GONE
            scanProgressBar.visibility = View.GONE
            scanningStatus.visibility = View.GONE
            folderCountLabel.visibility = View.GONE
        }
    }

    private fun saveFolderUri(uri: Uri) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(SAVED_URI_KEY, uri.toString())
            .apply()
    }

    // --- Time Formatting Utility (Unchanged) ---
    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    // --- Helper for Playback List (Unchanged) ---
    private val currentPlaybackList: List<SongEntry>
        get() = if (isShuffling) shuffledSongEntries else allSongEntries

    // --- Shuffle Logic (Unchanged) ---
    private fun toggleShuffle() {
        if (allSongEntries.isEmpty()) {
            Toast.makeText(this, "Song list is empty.", Toast.LENGTH_SHORT).show()
            return
        }

        isShuffling = !isShuffling

        val currentlyPlayingSong = currentPlaybackList.getOrNull(currentIndex)

        if (isShuffling) {
            // 1. Create shuffled list (copy and shuffle)
            shuffledSongEntries = allSongEntries.toMutableList().apply { shuffle() }

            // 2. Update currentIndex to the position in the new shuffled list
            if (currentlyPlayingSong != null) {
                val newIndex = shuffledSongEntries.indexOf(currentlyPlayingSong)
                if (newIndex != -1) currentIndex = newIndex else currentIndex = 0
            } else {
                currentIndex = 0
            }

            shuffleButton.text = "🔀 On"
            Toast.makeText(this, "Shuffle ON.", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Shuffle mode ON. New current index: $currentIndex")
        } else {
            // 1. Update currentIndex to the position in the original list
            if (currentlyPlayingSong != null) {
                val newIndex = allSongEntries.indexOf(currentlyPlayingSong)
                if (newIndex != -1) currentIndex = newIndex else currentIndex = 0
            } else {
                currentIndex = 0
            }

            shuffledSongEntries.clear()
            shuffleButton.text = "🔀 Off"
            Toast.makeText(this, "Shuffle OFF.", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Shuffle mode OFF. New current index: $currentIndex")
        }
    }

    // --- Repeat Logic (Unchanged) ---
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

    // --- Speed/Pitch Logic (UPDATED FOR SEPARATE CONTROL) ---
    private fun applyPlaybackParams() {
        // PlaybackParams is available from API 23 (Marshmallow)
        if (mediaPlayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val params = mediaPlayer!!.playbackParams

                // Use currentTempo for speed (rate)
                params.speed = currentTempo

                // Use currentPitch for pitch
                params.pitch = currentPitch

                mediaPlayer!!.playbackParams = params

            } catch (e: Exception) {
                Log.e(TAG, "Failed to set PlaybackParams. Required API 23. Current API ${Build.VERSION.SDK_INT}", e)
                Toast.makeText(this, "Speed/Pitch control unavailable or failed.", Toast.LENGTH_SHORT).show()
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "Speed/Pitch control requires Android 6.0 (API 23) or higher.", Toast.LENGTH_LONG).show()
        }
    }

    // --- Media Playback & Control (Unchanged logic, minor param call update) ---

    private fun togglePlayback() {
        if (allSongEntries.isEmpty()) {
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
                } else {
                    mediaPlayer!!.start()
                    applyPlaybackParams() // Re-apply params on resume
                    playButton.text = "⏸"
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "IllegalStateException during togglePlayback. Player in bad state.", e)
                Toast.makeText(this, "Playback error, trying to restart song.", Toast.LENGTH_SHORT).show()
                playSong(currentIndex)
            }
        }
    }

    // Runnable to update the seek bar and time display (Unchanged)
    private val updateSeekBar = object : Runnable {
        override fun run() {
            if (mediaPlayer != null && !isUserSeeking) {
                try {
                    val currentPos = mediaPlayer!!.currentPosition
                    val totalDuration = mediaPlayer!!.duration

                    // Update SeekBar
                    songSeekBar.progress = currentPos

                    // Update time display TextViews
                    currentTimeTextView.text = formatTime(currentPos)

                    // Total time is set on preparation, but update here just in case of race condition
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
        val playbackList = currentPlaybackList
        if (playbackList.isEmpty() || index < 0 || index >= playbackList.size) return

        currentIndex = index
        val songEntry = playbackList[index]
        val songUri = Uri.parse(songEntry.uriString)

        try {
            mediaPlayer?.release()
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
            contentResolver.openAssetFileDescriptor(songUri, "r")?.use { descriptor ->
                mediaPlayer!!.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            } ?: run {
                Toast.makeText(this, "Failed to load descriptor for: ${songEntry.label}", Toast.LENGTH_LONG).show()
                playButton.text = "▶"
                return
            }

            mediaPlayer!!.setOnPreparedListener {
                // APPLY SPEED/PITCH HERE
                applyPlaybackParams()

                it.start()
                playButton.text = "⏸"
                Toast.makeText(this, "Playing: ${songEntry.label}", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Playing: ${songEntry.label}")

                songSeekBar.max = it.duration
                totalTimeTextView.text = formatTime(it.duration)
                handler.post(updateSeekBar)
            }

            mediaPlayer!!.setOnErrorListener { _, what, extra ->
                 Log.e(TAG, "MediaPlayer Error: what=$what, extra=$extra for ${songEntry.label}")
                 Toast.makeText(this, "Playback Error ($what).", Toast.LENGTH_LONG).show()
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
            mediaPlayer?.release()
            mediaPlayer = null
            playButton.text = "▶"
        }
    }

    private fun playNext() {
        val playbackList = currentPlaybackList
        if (playbackList.isEmpty()) return
        currentIndex = (currentIndex + 1) % playbackList.size
        playSong(currentIndex)
    }

    private fun playPrevious() {
        val playbackList = currentPlaybackList
        if (playbackList.isEmpty()) return
        currentIndex = if (currentIndex - 1 < 0) playbackList.size - 1 else currentIndex - 1
        playSong(currentIndex)
    }

    // --- OSZ Import Logic (Unchanged) ---

    private fun importOszFiles(oszUris: List<Uri>, targetDirUri: Uri) {
        // ... (Unchanged logic) ...
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

            for ((index, oszUri) in oszUris.withIndex()) {
                val newFolder = withContext(Dispatchers.IO) {
                    performOszExtraction(oszUri, targetDir)
                }

                // Update UI on main thread
                withContext(Dispatchers.Main) {
                    loadingText.text = "Importing ${index + 1}/${oszUris.size} files..."
                }

                if (newFolder != null) {
                    successfulImports++
                    newlyCreatedFolders.add(newFolder)

                    // Delete the source OSZ file after successful extraction
                    withContext(Dispatchers.IO) {
                        try {
                            DocumentFile.fromSingleUri(this@MainActivity, oszUri)?.delete()
                            Log.d(TAG, "Successfully deleted source OSZ file: ${oszUri.lastPathSegment}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to delete source OSZ file: ${oszUri.lastPathSegment}", e)
                        }
                    }
                } else {
                    failedImports++
                }
            }

            // Scan only the newly created folders and append songs
            val newSongs = withContext(Dispatchers.IO) {
                newlyCreatedFolders.flatMap { folder ->
                    processBeatmapFolder(folder)
                }
            }

            hideLoading()

            val message: String
            if (newSongs.isNotEmpty()) {
                allSongEntries.addAll(newSongs)
                message = "Import complete: $successfulImports successful. Added ${newSongs.size} new songs."
                refreshListView(message)
            } else {
                 message = "Import complete: $successfulImports successful, $failedImports failed. No new playable songs found."
                 Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
            Log.d(TAG, message)
        }
    }

    private suspend fun performOszExtraction(oszUri: Uri, targetDir: DocumentFile): DocumentFile? = withContext(Dispatchers.IO) {
        val oszFileName = DocumentFile.fromSingleUri(this@MainActivity, oszUri)?.name ?: return@withContext null
        val folderName = oszFileName.substringBeforeLast('.')

        return@withContext try {
            contentResolver.openInputStream(oszUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipStream ->
                    val newFolder = targetDir.createDirectory(folderName)
                        ?: throw IOException("Failed to create folder: $folderName. Maybe it already exists?")

                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val fileName = entry.name.substringAfterLast(File.separator)
                            if (fileName.isNotEmpty()) {
                                // Use application/octet-stream for general file type
                                val file = newFolder.createFile("application/octet-stream", fileName)
                                    ?: throw IOException("Failed to create file: $fileName")

                                contentResolver.openOutputStream(file.uri)?.use { outputStream ->
                                    zipStream.copyTo(outputStream)
                                }
                            }
                        }
                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                    Log.d(TAG, "Successfully extracted $oszFileName into folder ${newFolder.name}")
                    newFolder
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract OSZ file: $oszFileName. Error: ${e.message}", e)
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

    // --- Beatmap Folder Processing Logic (Unchanged) ---

    private suspend fun processBeatmapFolder(folder: DocumentFile): List<SongEntry> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<SongEntry>()
        val osuFiles = folder.listFiles()?.filter { it.name?.endsWith(".osu") == true } ?: return@withContext emptyList()

        // Use async/await to process multiple .osu files in parallel within the folder
        val deferredMetadata = osuFiles.map { file ->
            async { parseOsuFile(file) }
        }
        val metadataList = deferredMetadata.awaitAll().filterNotNull()


        metadataList
            .groupBy { it.audioFilename }
            .forEach { (_, beatmaps) ->
                val metadata = beatmaps.first()
                val audioFile = folder.listFiles()?.find {
                    it.name?.equals(metadata.audioFilename, ignoreCase = true) == true
                }
                if (audioFile != null && audioFile.isFile) {
                    // Determine the label based on version count
                    val label = if (beatmaps.size > 1) {
                        "${metadata.artist} - ${metadata.title} (${beatmaps.size} versions)"
                    } else {
                        "${metadata.artist} - ${metadata.title}"
                    }
                    entries.add(SongEntry(label, audioFile.uri.toString(), metadata.artist, metadata.title))
                }
            }
        entries
    }

    private fun handlePermissionLoss(uri: Uri) {
        Log.w(TAG, "Permission for $uri lost. Asking user to re-select folder.")

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

    private suspend fun scanDirectoryForSongs(uri: Uri): List<SongEntry>? = withContext(Dispatchers.IO) {
        // Ensure we have access to the directory
        try {
            if (contentResolver.persistedUriPermissions.none { it.uri == uri }) {
                 // Permission may have been revoked externally
                 return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking URI permissions.", e)
            return@withContext null
        }

        val pickedDir = DocumentFile.fromTreeUri(this@MainActivity, uri)
        if (pickedDir == null || !pickedDir.isDirectory) return@withContext null

        val entries = mutableListOf<SongEntry>()
        // Filter out files that aren't directories, like the songs.json file
        val allFolders = pickedDir.listFiles().filter { it.isDirectory }
        val total = allFolders.size

        for ((i, folder) in allFolders.withIndex()) {
            val folderEntries = processBeatmapFolder(folder)
            entries.addAll(folderEntries)

            // Update UI frequently
            withContext(Dispatchers.Main) {
                // Keep progress bar and counter up to date
                scanProgressBar.progress = if (total > 0) ((i + 1) * 100 / total) else 0
                folderCountLabel.text = "Scanned ${i + 1} of $total folders"
            }
        }

        withContext(Dispatchers.Main) {
            scanningStatus.text = "Finalizing song list..."
        }
        entries
    }

    /**
     * OPTIMIZATION: Reads the .osu file only until all necessary metadata (Audio, Artist, Title)
     * is found, then stops reading and closes the stream immediately.
     */
    private fun parseOsuFile(file: DocumentFile): OsuMetadata? {
        return try {
            contentResolver.openInputStream(file.uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                var audioFilename: String? = null
                var artist: String? = null
                var title: String? = null
                var line: String?

                // Stop reading if all three required fields are found
                while (audioFilename == null || artist == null || title == null) {
                    line = reader.readLine() ?: break

                    when {
                        line.startsWith("AudioFilename:") -> audioFilename = line.substringAfter(":").trim()
                        line.startsWith("Artist:") -> artist = line.substringAfter(":").trim()
                        line.startsWith("Title:") -> title = line.substringAfter(":").trim()
                    }
                }

                if (audioFilename != null && artist != null && title != null) {
                    OsuMetadata(audioFilename, artist, title)
                } else null

            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing .osu file: ${file.name}", e)
            null
        }
    }

    // --- Custom Adapter for Sorting and Filtering (Unchanged) ---
    private class SongAdapter(
        context: Context,
        songs: List<SongEntry>,
        private val searchEditText: EditText
    ) :
        ArrayAdapter<SongEntry>(context, 0, songs.toMutableList()), Filterable {

        private var allSongs: List<SongEntry> = songs
        private var currentFilteredSongs: List<SongEntry> = songs.toMutableList()
        private val layoutInflater = LayoutInflater.from(context)

        override fun getCount(): Int = currentFilteredSongs.size
        override fun getItem(position: Int): SongEntry? = currentFilteredSongs[position]

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val song = getItem(position)

            val view = convertView ?: layoutInflater.inflate(R.layout.list_item_song, parent, false)

            val titleTextView = view.findViewById<TextView>(R.id.textTitle)
            val artistTextView = view.findViewById<TextView>(R.id.textArtist)

            if (song != null) {
                titleTextView.text = song.title
                artistTextView.text = song.artist
            }

            return view
        }

        override fun getFilter(): Filter {
            return object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    val results = FilterResults()
                    val query = constraint.toString().toLowerCase(Locale.getDefault()).trim()

                    val filteredList = if (query.isEmpty()) {
                        allSongs
                    } else {
                        allSongs.filter {
                            it.title.toLowerCase(Locale.getDefault()).contains(query) ||
                            it.artist.toLowerCase(Locale.getDefault()).contains(query)
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
            val sortedList = when (sortBy) {
                "Title" -> allSongs.sortedBy { it.title.toLowerCase(Locale.getDefault()) }
                "Artist" -> allSongs.sortedBy { it.artist.toLowerCase(Locale.getDefault()) }
                "Versions" -> allSongs.sortedByDescending { it.label.count { c -> c == '(' } }
                else -> allSongs
            }
            allSongs = sortedList
            filter.filter(searchEditText.text?.toString())
        }
    }


    // Simplified UI update and save function (Unchanged)
    private fun refreshListView(logMessage: String) {
        saveSongsToFile()

        songAdapter = SongAdapter(this, allSongEntries, searchEditText)
        listView.adapter = songAdapter

        val currentSortOption = sortSpinner.selectedItem?.toString() ?: "Title"
        songAdapter?.sortSongs(currentSortOption)

        songAdapter?.filter?.filter("")

        Toast.makeText(this, logMessage, Toast.LENGTH_SHORT).show()
        Log.d(TAG, logMessage)
    }

    // --- Data Persistence (Unchanged) ---

    private fun saveSongsToFile() = lifecycleScope.launch(Dispatchers.IO) {
        try {
            val json = Json.encodeToString(allSongEntries)
            val file = File(filesDir, SONGS_FILE_NAME)
            file.writeText(json)
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
                        Log.w(TAG, "Invalid URI found during load: ${it.uriString}", e)
                        false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading/parsing songs from file during startup.", e)
                null
            }
        }

        if (savedEntries != null) {
            allSongEntries.clear()
            allSongEntries.addAll(savedEntries)
            refreshListView("Loaded ${savedEntries.size} songs from cache.")
        }
    }

    // --- Cleanup ---

    override fun onDestroy() {
        super.onDestroy()
        // Save the current tempo and pitch when the app is destroyed
        savePlaybackSetting(TEMPO_KEY, currentTempo)
        savePlaybackSetting(PITCH_KEY, currentPitch)

        handler.removeCallbacks(updateSeekBar)
        mediaPlayer?.release()
        mediaPlayer = null
        Log.d(TAG, "App destroyed, media player released")
    }
}
