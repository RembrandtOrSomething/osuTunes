package com.example.osutunes

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.*
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
import java.io.InputStreamReader
import java.util.zip.ZipInputStream
import android.os.Process
import java.io.IOException

class MainActivity : AppCompatActivity() {

    // --- Constants and Tags ---
    private companion object {
        const val PREFS_NAME = "osuTunesPrefs"
        const val SAVED_URI_KEY = "savedFolderUri"
        const val SONGS_FILE_NAME = "songs.json"
        const val TAG = "OsuTunes"

        // JSON parser configured to ignore keys we might not recognize, increasing stability
        val Json = Json { ignoreUnknownKeys = true }
    }

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

    // --- State Variables ---
    @Serializable
    private data class SongEntry(val label: String, val uriString: String)
    private data class OsuMetadata(val audioFilename: String, val artist: String, val title: String)

    private var songEntries = mutableListOf<SongEntry>()
    private var currentIndex = 0
    private var mediaPlayer: MediaPlayer? = null
    private var currentDirUri: Uri? = null
    private var isUserSeeking = false
    private val handler = Handler(Looper.getMainLooper())

    // --- Activity Result Launchers ---

    // Launcher for selecting the main Songs folder
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                // Persist read/write permissions for the selected URI
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                currentDirUri = uri
                saveFolderUri(uri)
                Log.d(TAG, "Folder selected: $uri")
                loadBeatmapSongs(uri) // Full scan on initial folder selection/reload
            }
        }
    }

    // Launcher for importing multiple OSZ files
    private val oszPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uris = mutableListOf<Uri>()

            if (data?.data != null) {
                // Single file selected
                uris.add(data.data!!)
            } else if (data?.clipData != null) {
                // Multiple files selected
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

        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        loadInitialData()
    }

    private fun setupCrashHandler() {
        // Sets a global handler to catch unhandled exceptions on the main thread
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            Log.e(TAG, "FATAL CRASH on Thread: ${thread.name}", exception)

            // Display the error in a Toast on the Main thread (requires Looper.prepare() if this thread wasn't main, but here it is)
            Handler(Looper.getMainLooper()).post {
                val errorMsg = "FATAL CRASH: ${exception.javaClass.simpleName} - ${exception.message}"
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
            }

            // Wait a moment for the toast to show, then kill the process
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
                    loadBeatmapSongs(uri) // Full scan
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        importOszButton.setOnClickListener {
            if (currentDirUri == null) {
                Toast.makeText(this, "Please select the 'Songs' folder first.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            
            // Filter hint for OSZ files (zip/octet-stream)
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

        playButton.setOnClickListener { togglePlayback() }
        nextButton.setOnClickListener { playNext() }
        prevButton.setOnClickListener { playPrevious() }

        songSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && mediaPlayer != null) mediaPlayer!!.seekTo(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
            }
        })

        listView.setOnItemClickListener { _, _, position, _ -> playSong(position) }
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
    }

    private fun saveFolderUri(uri: Uri) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(SAVED_URI_KEY, uri.toString())
            .apply()
    }

    // --- Media Playback & Control ---

    private fun togglePlayback() {
        if (songEntries.isEmpty()) {
            Toast.makeText(this, "Song list is empty.", Toast.LENGTH_SHORT).show()
            return
        }

        if (mediaPlayer == null) {
            playSong(currentIndex)
        } else {
            try {
                if (mediaPlayer!!.isPlaying) {
                    mediaPlayer!!.pause()
                    playButton.text = "▶" // Set to Play symbol when paused
                } else {
                    mediaPlayer!!.start()
                    playButton.text = "⏸" // Set to Pause symbol when playing
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "IllegalStateException during togglePlayback. Player in bad state.", e)
                Toast.makeText(this, "Playback error, trying to restart song.", Toast.LENGTH_SHORT).show()
                playSong(currentIndex) // Attempt to reset and restart
            }
        }
    }

    private val updateSeekBar = object : Runnable {
        override fun run() {
            if (mediaPlayer != null && !isUserSeeking) {
                try {
                    songSeekBar.progress = mediaPlayer!!.currentPosition
                } catch (e: IllegalStateException) {
                    // Ignore if player is currently releasing or in an uninitialized state
                    Log.w(TAG, "Ignoring IllegalStateException during seekBar update.")
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun playSong(index: Int) {
        if (songEntries.isEmpty()) return

        currentIndex = index
        val songEntry = songEntries[index]
        val songUri = Uri.parse(songEntry.uriString)
        
        // 1. Release old player safely
        try {
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing old media player.", e)
        }
        mediaPlayer = null
        handler.removeCallbacks(updateSeekBar)
        songSeekBar.progress = 0

        // 2. New player initialization
        mediaPlayer = MediaPlayer()
        
        // 3. Setup listeners and data source
        try {
            contentResolver.openAssetFileDescriptor(songUri, "r")?.use { descriptor ->
                mediaPlayer!!.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            } ?: run {
                Toast.makeText(this, "Failed to load descriptor for: ${songEntry.label}", Toast.LENGTH_LONG).show()
                playButton.text = "▶"
                return
            }

            // Set up success listener for async preparation
            mediaPlayer!!.setOnPreparedListener { 
                it.start() 
                playButton.text = "⏸"
                Toast.makeText(this, "Playing: ${songEntry.label}", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Playing: ${songEntry.label}")

                songSeekBar.max = it.duration
                handler.post(updateSeekBar)
            }
            
            // Set up error listener for failure cases
            mediaPlayer!!.setOnErrorListener { _, what, extra ->
                 Log.e(TAG, "MediaPlayer Error: what=$what, extra=$extra for ${songEntry.label}")
                 Toast.makeText(this, "Playback Error ($what).", Toast.LENGTH_LONG).show()
                 mediaPlayer?.release()
                 mediaPlayer = null
                 playButton.text = "▶"
                 false // Do not consume the error
            }

            // Set up completion listener
            mediaPlayer!!.setOnCompletionListener { playNext() }

            // Move to asynchronous preparation
            mediaPlayer!!.prepareAsync() 

            // Update UI immediately to show preparation is happening
            playButton.text = "⏳" 
            

        } catch (e: Exception) {
            Toast.makeText(this, "Fatal error setting up player for: ${songEntry.label}", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Failed to setup media player.", e)
            mediaPlayer?.release()
            mediaPlayer = null
            playButton.text = "▶" // Reset button state
        }
    }

    private fun playNext() {
        if (songEntries.isEmpty()) return
        currentIndex = (currentIndex + 1) % songEntries.size
        playSong(currentIndex)
    }

    private fun playPrevious() {
        if (songEntries.isEmpty()) return
        currentIndex = if (currentIndex - 1 < 0) songEntries.size - 1 else currentIndex - 1
        playSong(currentIndex)
    }

    // --- OSZ Import Logic ---

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
            
            withContext(Dispatchers.IO) {
                for ((index, oszUri) in oszUris.withIndex()) {
                    val newFolder = performOszExtraction(oszUri, targetDir)

                    withContext(Dispatchers.Main) {
                        if (newFolder != null) {
                            successfulImports++
                            newlyCreatedFolders.add(newFolder) 
                        } else {
                            failedImports++
                        }
                        loadingText.text = "Importing ${index + 1}/${oszUris.size} files..."
                    }
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
                songEntries.addAll(newSongs) 
                message = "Import complete: $successfulImports successful. Added ${newSongs.size} new songs."
                refreshListView(message) // Saves the updated list and refreshes UI
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
                                val file = newFolder.createFile("*/*", fileName)
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
    
    // --- Beatmap Folder Processing Logic ---
    
    private suspend fun processBeatmapFolder(folder: DocumentFile): List<SongEntry> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<SongEntry>()
        // Find all .osu files in the folder
        val osuFiles = folder.listFiles()?.filter { it.name?.endsWith(".osu") == true } ?: return@withContext emptyList()
        
        // Parse metadata from each .osu file
        val metadataList = osuFiles.mapNotNull { parseOsuFile(it) }

        // Group beatmaps by their audio file to find the unique songs
        metadataList
            .groupBy { it.audioFilename }
            .forEach { (_, beatmaps) ->
                val metadata = beatmaps.first()
                // Find the actual audio file
                val audioFile = folder.listFiles()?.find {
                    it.name?.equals(metadata.audioFilename, ignoreCase = true) == true
                }
                if (audioFile != null && audioFile.isFile) {
                    val label = if (beatmaps.size > 1) {
                        "${metadata.artist} - ${metadata.title} (${beatmaps.size} versions)"
                    } else {
                        "${metadata.artist} - ${metadata.title}"
                    }
                    entries.add(SongEntry(label, audioFile.uri.toString()))
                }
            }
        entries
    }

    // --- Song Scanning Logic (Full Scan) ---

    private fun loadBeatmapSongs(uri: Uri) {
        val hasPermission = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }

        if (!hasPermission) {
            handlePermissionLoss(uri)
            return
        }

        // Show loading UI
        loadingSpinner.visibility = View.VISIBLE
        loadingText.visibility = View.VISIBLE
        scanProgressBar.visibility = View.VISIBLE
        scanningStatus.visibility = View.VISIBLE
        folderCountLabel.visibility = View.VISIBLE
        loadingText.text = "Scanning beatmaps..."

        lifecycleScope.launch(Dispatchers.Main) {
            val newSongs = withContext(Dispatchers.IO) {
                scanDirectoryForSongs(uri)
            }

            hideLoading()
            if (newSongs != null) {
                songEntries.clear()
                songEntries.addAll(newSongs)
                refreshListView("Loaded ${songEntries.size} songs from full scan.")
            } else {
                Toast.makeText(this@MainActivity, "Failed to scan folder.", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Failed to scan folder: $uri")
            }
        }
    }

    private fun handlePermissionLoss(uri: Uri) {
        Log.w(TAG, "Permission for $uri lost. Asking user to re-select folder.")

        // Clean up state
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(SAVED_URI_KEY).apply()
        currentDirUri = null
        songEntries.clear()
        (listView.adapter as? ArrayAdapter<*>)?.notifyDataSetChanged()

        // Use AlertDialog to clearly explain the issue before launching the picker
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
        val pickedDir = DocumentFile.fromTreeUri(this@MainActivity, uri)
        if (pickedDir == null || !pickedDir.isDirectory) return@withContext null

        val entries = mutableListOf<SongEntry>()
        val allFolders = pickedDir.listFiles().filter { it.isDirectory }
        val total = allFolders.size

        for ((i, folder) in allFolders.withIndex()) {
            withContext(Dispatchers.Main) {
                scanningStatus.text = "Scanning: ${folder.name}"
                scanProgressBar.progress = ((i + 1) * 100 / total)
                folderCountLabel.text = "Scanned ${i + 1} of $total folders"
            }
            
            val folderEntries = processBeatmapFolder(folder)
            entries.addAll(folderEntries)

            delay(5)
        }
        entries
    }

    // Simplified UI update and save function
    private fun refreshListView(logMessage: String) {
        saveSongsToFile()

        val titles = songEntries.map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, titles)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> playSong(position) } // Re-set listener

        Toast.makeText(this, logMessage, Toast.LENGTH_SHORT).show()
        Log.d(TAG, logMessage)
    }

    private fun parseOsuFile(file: DocumentFile): OsuMetadata? {
        return try {
            contentResolver.openInputStream(file.uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                var audioFilename = ""
                var artist = ""
                var title = ""

                reader.forEachLine { line ->
                    when {
                        line.startsWith("AudioFilename:") -> audioFilename = line.substringAfter(":").trim()
                        line.startsWith("Artist:") -> artist = line.substringAfter(":").trim()
                        line.startsWith("Title:") -> title = line.substringAfter(":").trim()
                    }
                }

                if (audioFilename.isNotEmpty() && artist.isNotEmpty() && title.isNotEmpty()) {
                    OsuMetadata(audioFilename, artist, title)
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing .osu file: ${file.name}", e)
            null
        }
    }

    // --- Data Persistence ---

    private fun saveSongsToFile() = lifecycleScope.launch(Dispatchers.IO) {
        try {
            val json = Json.encodeToString(songEntries)
            val file = File(filesDir, SONGS_FILE_NAME)
            file.writeText(json)
            Log.d(TAG, "Saved ${songEntries.size} songs to internal file")
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

                // Critical: Filter entries by checking URI existence off the Main Thread
                entries.filter {
                    try {
                        DocumentFile.fromSingleUri(this@MainActivity, Uri.parse(it.uriString))?.exists() == true
                    } catch (e: Exception) {
                        Log.w(TAG, "Invalid URI found during load: ${it.uriString}", e)
                        false
                    }
                }
            } catch (e: Exception) {
                // This exception handler is critical for preventing startup crashes
                Log.e(TAG, "Error loading/parsing songs from file during startup.", e)
                null
            }
        }

        if (savedEntries != null) {
            songEntries.clear()
            songEntries.addAll(savedEntries)

            val titles = songEntries.map { it.label }
            val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, titles)
            listView.adapter = adapter
            
            Toast.makeText(this@MainActivity, "Loaded ${savedEntries.size} songs from cache.", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Loaded ${savedEntries.size} valid songs from internal file")
        }
    }

    // --- Cleanup ---

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateSeekBar)
        mediaPlayer?.release()
        mediaPlayer = null
        Log.d(TAG, "App destroyed, media player released")
    }
}
