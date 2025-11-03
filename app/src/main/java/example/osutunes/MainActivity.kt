package com.example.osutunes

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
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
import android.widget.Toast
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

class MainActivity : AppCompatActivity() {

    // --- Constants and Tags ---
    private companion object {
        const val PREFS_NAME = "osuTunesPrefs"
        const val SAVED_URI_KEY = "savedFolderUri"
        const val SONGS_FILE_NAME = "songs.json"
        const val TAG = "OsuTunes"
        const val TEMPO_KEY = "savedPlaybackTempo"
        const val PITCH_KEY = "savedPlaybackPitch"

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
    private lateinit var listView: ListView
    private lateinit var playButton: Button
    private lateinit var nextButton: Button
    private lateinit var prevButton: Button
    private lateinit var folderButton: Button
    private lateinit var reloadButton: Button
    private lateinit var importOszButton: Button
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

    // Playback settings launcher (main screen)
    private lateinit var playbackSettingsButton: Button

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

    // --- Activity Result Launchers ---
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

    // ...existing code...

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupCrashHandler()
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
        // Bind main-screen views. Tempo/pitch/shuffle/repeat removed from main layout.
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

        sortSpinner = findViewById(R.id.sortSpinner)
        searchEditText = findViewById(R.id.searchEditText)
        currentTimeTextView = findViewById(R.id.currentTimeTextView)
        totalTimeTextView = findViewById(R.id.totalTimeTextView)

        playbackSettingsButton = findViewById(R.id.playbackSettingsButton)
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

        // playback control buttons
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

        // Sort Spinner Listener
        sortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val sortBy = parent?.getItemAtPosition(position).toString()
                songAdapter?.sortSongs(sortBy)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Search/Filter Text Listener
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                songAdapter?.filter?.filter(s)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedSong = songAdapter?.getItem(position)
            val indexInPlaybackList = currentPlaybackList.indexOf(selectedSong)
            if (indexInPlaybackList != -1) {
                playSong(indexInPlaybackList)
            }
        }

        // Open playback settings dialog
        playbackSettingsButton.setOnClickListener { showPlaybackSettingsDialog() }
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

        // initial shuffle/repeat state
        isShuffling = false
        shuffledSongEntries.clear()
        isRepeating = false

        // Load saved tempo/pitch
        currentTempo = loadPlaybackSetting(TEMPO_KEY)
        currentPitch = loadPlaybackSetting(PITCH_KEY)
    }

    // Helper functions for persistence
    private fun loadPlaybackSetting(key: String): Float {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getFloat(key, 1.0f)
    }

    private fun savePlaybackSetting(key: String, value: Float) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putFloat(key, value)
            .apply()
    }

    // Non-suspending wrapper to start the coroutine
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

    // Time formatting
    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    // Playback list accessor
    private val currentPlaybackList: List<SongEntry>
        get() = if (isShuffling) shuffledSongEntries else allSongEntries

    // Shuffle logic (no direct main-screen button; dialog toggles call this)
    private fun toggleShuffle() {
        if (allSongEntries.isEmpty()) {
            Toast.makeText(this, "Song list is empty.", Toast.LENGTH_SHORT).show()
            return
        }

        isShuffling = !isShuffling

        val currentlyPlayingSong = currentPlaybackList.getOrNull(currentIndex)

        if (isShuffling) {
            shuffledSongEntries = allSongEntries.toMutableList().apply { shuffle() }
            if (currentlyPlayingSong != null) {
                val newIndex = shuffledSongEntries.indexOf(currentlyPlayingSong)
                currentIndex = if (newIndex != -1) newIndex else 0
            } else {
                currentIndex = 0
            }
            Toast.makeText(this, "Shuffle ON.", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Shuffle mode ON. New current index: $currentIndex")
        } else {
            if (currentlyPlayingSong != null) {
                val newIndex = allSongEntries.indexOf(currentlyPlayingSong)
                currentIndex = if (newIndex != -1) newIndex else 0
            } else {
                currentIndex = 0
            }
            shuffledSongEntries.clear()
            Toast.makeText(this, "Shuffle OFF.", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Shuffle mode OFF. New current index: $currentIndex")
        }
    }

    // Repeat logic
    private fun toggleRepeat() {
        isRepeating = !isRepeating
        if (isRepeating) {
            Toast.makeText(this, "Repeat ON (Single Song).", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Repeat OFF.", Toast.LENGTH_SHORT).show()
        }
    }

    // Apply PlaybackParams (speed + pitch) safely
    private fun applyPlaybackParams() {
        try {
            val mp = mediaPlayer ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val params = PlaybackParams()
                params.speed = currentTempo
                try { params.pitch = currentPitch } catch (_: Throwable) { /* device may ignore */ }
                mp.playbackParams = params
            } else {
                Toast.makeText(this, "Speed/Pitch control requires Android 6.0+.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply playback params", e)
        }
    }

    // Media playback controls (kept from original)
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
                    applyPlaybackParams()
                    playButton.text = "⏸"
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "IllegalStateException during togglePlayback. Player in bad state.", e)
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
        val playbackList = currentPlaybackList
        if (playbackList.isEmpty() || index < 0 || index >= playbackList.size) return

        currentIndex = index
        val songEntry = playbackList[index]
        val songUri = Uri.parse(songEntry.uriString)

        try { mediaPlayer?.release() } catch (e: Exception) { Log.w(TAG, "Error releasing old media player.", e) }
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

    // OSZ import and processing functions (kept from original)
    private fun importOszFiles(oszUris: List<Uri>, targetDirUri: Uri) {
        // ...existing code...
    }

    private suspend fun performOszExtraction(oszUri: Uri, targetDir: DocumentFile): DocumentFile? = withContext(Dispatchers.IO) {
        // ...existing code...
        null
    }

    private fun hideLoading() {
        loadingSpinner.visibility = View.GONE
        loadingText.visibility = View.GONE
        scanProgressBar.visibility = View.GONE
        scanningStatus.visibility = View.GONE
        folderCountLabel.visibility = View.GONE
    }

    private suspend fun processBeatmapFolder(folder: DocumentFile): List<SongEntry> = withContext(Dispatchers.IO) {
        // ...existing code...
        emptyList()
    }

    private fun handlePermissionLoss(uri: Uri) {
        // ...existing code...
    }

    private suspend fun scanDirectoryForSongs(uri: Uri): List<SongEntry>? = withContext(Dispatchers.IO) {
        // ...existing code...
        null
    }

    private fun parseOsuFile(file: DocumentFile): OsuMetadata? {
        // ...existing code...
        return null
    }

    // Custom adapter (unchanged)
    private class SongAdapter(
        context: Context,
        songs: List<SongEntry>,
        private val searchEditText: EditText
    ) : ArrayAdapter<SongEntry>(context, 0, songs.toMutableList()), Filterable {
        // ...existing code...
        override fun getCount(): Int = 0
        override fun getItem(position: Int): SongEntry? = null
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return View(context)
        }
        override fun getFilter(): Filter { return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults { return FilterResults() }
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {}
        } }
        fun sortSongs(sortBy: String) {}
    }

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
        // ...existing code...
    }

    override fun onDestroy() {
        super.onDestroy()
        savePlaybackSetting(TEMPO_KEY, currentTempo)
        savePlaybackSetting(PITCH_KEY, currentPitch)

        handler.removeCallbacks(updateSeekBar)
        mediaPlayer?.release()
        mediaPlayer = null
        Log.d(TAG, "App destroyed, media player released")
    }

    // --- New: Playback settings dialog (tempo/pitch + reset + shuffle/repeat) ---
    private fun showPlaybackSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_playback_settings, null)

        val tempoSeek = dialogView.findViewById<SeekBar>(R.id.dialogTempoSeekBar)
        val tempoText = dialogView.findViewById<TextView>(R.id.dialogTempoTextView)
        val resetTempo = dialogView.findViewById<Button>(R.id.dialogResetTempoButton)

        val pitchSeek = dialogView.findViewById<SeekBar>(R.id.dialogPitchSeekBar)
        val pitchText = dialogView.findViewById<TextView>(R.id.dialogPitchTextView)
        val resetPitch = dialogView.findViewById<Button>(R.id.dialogResetPitchButton)

        val shuffleBtn = dialogView.findViewById<Button>(R.id.dialogShuffleButton)
        val repeatBtn = dialogView.findViewById<Button>(R.id.dialogRepeatButton)

        tempoSeek.max = 400
        pitchSeek.max = 400

        val tempoProgress = ((currentTempo - 0.5f) / 2.0f * 400.0f).toInt().coerceIn(0, 400)
        val pitchProgress = ((currentPitch - 0.5f) / 2.0f * 400.0f).toInt().coerceIn(0, 400)

        tempoSeek.progress = tempoProgress
        tempoText.text = String.format(Locale.getDefault(), "%.2fx", currentTempo)

        pitchSeek.progress = pitchProgress
        pitchText.text = String.format(Locale.getDefault(), "%.2fx", currentPitch)

        tempoSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val newTempo = (progress / 400.0f * 2.0f) + 0.5f
                currentTempo = newTempo
                tempoText.text = String.format(Locale.getDefault(), "%.2fx", newTempo)
                if (mediaPlayer != null) {
                    applyPlaybackParams()
                    savePlaybackSetting(TEMPO_KEY, currentTempo)
                } else {
                    savePlaybackSetting(TEMPO_KEY, currentTempo)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        pitchSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val newPitch = (progress / 400.0f * 2.0f) + 0.5f
                currentPitch = newPitch
                pitchText.text = String.format(Locale.getDefault(), "%.2fx", newPitch)
                if (mediaPlayer != null) {
                    applyPlaybackParams()
                    savePlaybackSetting(PITCH_KEY, currentPitch)
                } else {
                    savePlaybackSetting(PITCH_KEY, currentPitch)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        resetTempo.setOnClickListener {
            val target = 100
            tempoSeek.progress = target
            currentTempo = 1.0f
            tempoText.text = String.format(Locale.getDefault(), "%.2fx", currentTempo)
            applyPlaybackParams()
            savePlaybackSetting(TEMPO_KEY, currentTempo)
        }

        resetPitch.setOnClickListener {
            val target = 100
            pitchSeek.progress = target
            currentPitch = 1.0f
            pitchText.text = String.format(Locale.getDefault(), "%.2fx", currentPitch)
            applyPlaybackParams()
            savePlaybackSetting(PITCH_KEY, currentPitch)
        }

        shuffleBtn.text = if (isShuffling) "Shuffle: On" else "Shuffle: Off"
        repeatBtn.text = if (isRepeating) "Loop: On" else "Loop: Off"

        shuffleBtn.setOnClickListener {
            toggleShuffle()
            shuffleBtn.text = if (isShuffling) "Shuffle: On" else "Shuffle: Off"
        }

        repeatBtn.setOnClickListener {
            toggleRepeat()
            repeatBtn.text = if (isRepeating) "Loop: On" else "Loop: Off"
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Playback Settings")
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .create()

        dialog.show()
    }
}