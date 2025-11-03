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
        val bpm: Double? = null  // NEW: BPM field
    )
    
    private data class OsuMetadata(
        val audioFilename: String, 
        val artist: String, 
        val title: String,
        val bpm: Double? = null  // NEW: BPM field
    )

    // UI Components
    private lateinit var listView: ListView
    private lateinit var playButton: Button
    private lateinit var nextButton: Button
    private lateinit var prevButton: Button
    private lateinit var folderButton: Button
    private lateinit var reloadButton: Button
    private lateinit var importOszButton: Button
    private lateinit var shuffleButton: Button  // REMOVED: Will hide this
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
    private var currentDirUri: Uri? = null
    private var isUserSeeking = false
    private val handler = Handler(Looper.getMainLooper())
    private var songAdapter: SongAdapter? = null
    
    // Playback list - now always shuffled (the "feature")
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
            if (uris.isNotEmpty() && currentDirUri != null) {
                importOszFiles(uris, currentDirUri!!)
            } else if (currentDirUri == null) {
                Toast.makeText(this, "Please select the 'Songs' folder first.", Toast.LENGTH_LONG).show()
            }
        }
    }

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
        shuffleButton = findViewById(R.id.shuffleButton)  // REMOVED: Will hide this
        repeatButton = findViewById(R.id.repeatButton)
        sortSpinner = findViewById(R.id.sortSpinner)
        searchEditText = findViewById(R.id.searchEditText)
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
                .setMessage("Rescan the entire current folder for songs?")
                .setPositiveButton("Yes") { _, _ -> loadBeatmapSongs(uri) }
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
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream", "application/x-osu-beatmap"))
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
                songAdapter?.filter?.filter(s)
                updateCurrentPlaybackList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedSong = songAdapter?.getItem(position)
            if (selectedSong != null) {
                val indexInPlaybackList = currentPlaybackList.indexOfFirst { it.uriString == selectedSong.uriString }
                if (indexInPlaybackList != -1) {
                    playSong(indexInPlaybackList)
                } else {
                    currentPlaybackList.add(selectedSong)
                    playSong(currentPlaybackList.size - 1)
                }
            }
        }
    }

    private fun updateCurrentPlaybackList() {
        currentPlaybackList.clear()
        val filteredSongs = songAdapter?.getAllFilteredSongs() ?: allSongEntries
        currentPlaybackList.addAll(filteredSongs)
        
        // FEATURE: Always shuffle the playback list for that chaotic randomness!
        currentPlaybackList.shuffle()
        
        Log.d(TAG, "Playback list updated and shuffled. Size: ${currentPlaybackList.size}")
        
        // Update current index if a song is playing
        currentPlayingSong?.let { playingSong ->
            val newIndex = currentPlaybackList.indexOfFirst { it.uriString == playingSong.uriString }
            if (newIndex != -1) {
                currentIndex = newIndex
                Log.d(TAG, "Updated current index to $currentIndex for playing song")
            } else {
                // Song no longer in filtered list, stop playback
                Log.d(TAG, "Playing song no longer in filtered list, stopping playback")
                mediaPlayer?.release()
                mediaPlayer = null
                playButton.text = "▶"
                nowPlayingBar.visibility = View.GONE
                currentPlayingSong = null
                songAdapter?.setCurrentlyPlaying(null)
            }
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
        input.setText(String.format(Locale.getDefault(), "%.2f", currentTempo))
        AlertDialog.Builder(this)
            .setTitle("Set Tempo")
            .setMessage("Enter tempo value (0.50 - 2.50):")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val text = input.text.toString()
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
        input.setText(String.format(Locale.getDefault(), "%.2f", currentPitch))
        AlertDialog.Builder(this)
            .setTitle("Set Pitch")
            .setMessage("Enter pitch value (0.50 - 2.50):")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val text = input.text.toString()
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
                } else {
                    mediaPlayer!!.start()
                    applyPlaybackParams()
                    playButton.text = "⏸"
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
        // FEATURE: Always use the shuffled playback list!
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
        // FEATURE: Always use the shuffled playback list!
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
        // FEATURE: Always use the shuffled playback list!
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
                    
                    // NEW: Include BPM in the song entry
                    entries.add(SongEntry(label, audioFile.uri.toString(), metadata.artist, metadata.title, metadata.bpm))
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
        val progressCounter = AtomicInteger(0)

        // Process folders in parallel for much faster scanning
        val folderResults = allFolders.map { folder ->
            async { 
                val folderEntries = processBeatmapFolder(folder)
                val currentProgress = progressCounter.incrementAndGet()
                
                // Update progress on main thread (but less frequently for performance)
                if (currentProgress % 5 == 0 || currentProgress == total) {
                    withContext(Dispatchers.Main) {
                        scanProgressBar.progress = if (total > 0) (currentProgress * 100 / total) else 0
                        folderCountLabel.text = "Scanned $currentProgress of $total folders"
                    }
                }
                folderEntries
            }
        }.awaitAll().flatten()

        withContext(Dispatchers.Main) {
            scanningStatus.text = "Finalizing song list..."
        }
        
        folderResults
    }

    /**
     * Enhanced .osu file parser that extracts metadata AND BPM
     * Now uses the most frequent BPM (mode) instead of average
     */
    private fun parseOsuFile(file: DocumentFile): OsuMetadata? {
        return try {
            contentResolver.openInputStream(file.uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                var audioFilename: String? = null
                var artist: String? = null
                var title: String? = null
                val bpms = mutableListOf<Double>()
                var inTimingSection = false
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    when {
                        line == "[TimingPoints]" -> {
                            inTimingSection = true
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
                            if (timingParts != null && timingParts.size >= 8) {
                                try {
                                    val beatLength = timingParts[1].toDouble()
                                    val uninherited = timingParts[6].toInt() == 1
                                    
                                    // Only consider uninherited timing points (main BPM changes)
                                    if (uninherited && beatLength > 0) {
                                        val calculatedBpm = 60000.0 / beatLength
                                        bpms.add(calculatedBpm)
                                        Log.d(TAG, "Found BPM: $calculatedBpm in ${file.name}")
                                    }
                                } catch (e: NumberFormatException) {
                                    // Ignore malformed timing points
                                }
                            }
                        }
                    }

                    // Stop reading early if we have all required metadata
                    if (audioFilename != null && artist != null && title != null) {
                        // Continue reading timing points even after we have basic metadata
                        if (!inTimingSection && bpms.isNotEmpty()) {
                            break
                        }
                    }
                }

                if (audioFilename != null && artist != null && title != null) {
                    // NEW: Calculate the most frequent BPM (mode) instead of average
                    val bpm = if (bpms.isNotEmpty()) {
                        findMostFrequentBpm(bpms)
                    } else {
                        null
                    }
                    
                    OsuMetadata(audioFilename, artist, title, bpm)
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing .osu file: ${file.name}", e)
            null
        }
    }

    /**
     * NEW: Finds the most frequent BPM value (mode) from a list of BPMs
     * Groups similar BPM values together to account for slight variations
     */
    private fun findMostFrequentBpm(bpms: List<Double>): Double {
        if (bpms.isEmpty()) return 0.0
        if (bpms.size == 1) return bpms.first()

        // Group similar BPM values (within ±1 BPM tolerance)
        val groupedBpms = mutableMapOf<Double, Int>()
        
        for (bpm in bpms) {
            // Round to nearest integer for grouping
            val roundedBpm = bpm.roundToNearestInteger()
            
            // Count occurrences of this rounded BPM
            groupedBpms[roundedBpm] = groupedBpms.getOrDefault(roundedBpm, 0) + 1
        }
        
        // Find the BPM with the highest count
        val mostFrequent = groupedBpms.maxByOrNull { it.value }?.key ?: bpms.first()
        
        Log.d(TAG, "BPM analysis: Most frequent = $mostFrequent from ${bpms.size} timing points")
        Log.d(TAG, "BPM distribution: $groupedBpms")
        
        return mostFrequent
    }

    /**
     * Helper function to round BPM to nearest integer
     */
    private fun Double.roundToNearestInteger(): Double {
        return Math.round(this).toDouble()
    }

    // UPDATED: SongAdapter with BPM display and BPM search
    private class SongAdapter(
        context: Context,
        songs: List<SongEntry>,
        private val searchEditText: EditText
    ) : ArrayAdapter<SongEntry>(context, 0, songs.toMutableList()), Filterable {

        private var allSongs: List<SongEntry> = songs
        private var currentFilteredSongs: List<SongEntry> = songs.toMutableList()
        private var currentlyPlaying: SongEntry? = null
        private val layoutInflater = LayoutInflater.from(context)

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
            val artistTextView = view.findViewById<TextView>(R.id.textArtist)

            if (song != null) {
                titleTextView.text = song.title
                
                // NEW: Display BPM if available
                val bpmText = if (song.bpm != null) {
                    " (${String.format(Locale.getDefault(), "%.0f", song.bpm)} BPM)"
                } else {
                    ""
                }
                artistTextView.text = "${song.artist}$bpmText"
                
                // Highlight currently playing song
                if (song == currentlyPlaying) {
                    view.setBackgroundColor(0xFF45475A.toInt())
                    titleTextView.setTextColor(0xFFCBA6F7.toInt())
                    artistTextView.setTextColor(0xFFCBA6F7.toInt())
                } else {
                    view.setBackgroundColor(0x0024273A)
                    titleTextView.setTextColor(0xFFBAC2DE.toInt())
                    artistTextView.setTextColor(0xFFBAC2DE.toInt())
                }
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
                        allSongs.filter { song ->
                            // Search in title and artist as before
                            val matchesText = song.title.toLowerCase(Locale.getDefault()).contains(query) ||
                                            song.artist.toLowerCase(Locale.getDefault()).contains(query)
                            
                            // NEW: Also search by BPM
                            val matchesBpm = if (song.bpm != null) {
                                // Try to parse the query as a number for BPM search
                                try {
                                    val bpmQuery = query.toDoubleOrNull()
                                    if (bpmQuery != null) {
                                        // Allow approximate BPM matching (within ±5 BPM)
                                        val bpmTolerance = 5.0
                                        song.bpm in (bpmQuery - bpmTolerance)..(bpmQuery + bpmTolerance)
                                    } else {
                                        false
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
                "BPM" -> allSongs.sortedBy { it.bpm ?: 0.0 }  // NEW: BPM sorting
                else -> allSongs
            }
            filter.filter(searchEditText.text?.toString())
        }
    }

    private fun refreshListView(logMessage: String) {
        saveSongsToFile()
        songAdapter = SongAdapter(this, allSongEntries, searchEditText)
        listView.adapter = songAdapter
        updateCurrentPlaybackList()
        
        val currentSortOption = sortSpinner.selectedItem?.toString() ?: "Title"
        songAdapter?.sortSongs(currentSortOption)
        songAdapter?.filter?.filter("")

        Toast.makeText(this, logMessage, Toast.LENGTH_SHORT).show()
        Log.d(TAG, logMessage)
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
        mediaPlayer?.release()
        mediaPlayer = null
        Log.d(TAG, "App destroyed, media player released")
    }
}