package com.example.osutunes

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
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

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var playButton: Button
    private lateinit var nextButton: Button
    private lateinit var prevButton: Button
    private lateinit var folderButton: Button
    private lateinit var reloadButton: Button
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var loadingText: TextView
    private lateinit var scanProgressBar: ProgressBar
    private lateinit var scanningStatus: TextView
    private lateinit var folderCountLabel: TextView
    private lateinit var songSeekBar: SeekBar

    @Serializable
    data class SongEntry(val label: String, val uriString: String)

    private var songEntries = mutableListOf<SongEntry>()
    private var currentIndex = 0
    private var mediaPlayer: MediaPlayer? = null
    private var currentDirUri: Uri? = null
    private var isUserSeeking = false
    private val handler = Handler()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listView = findViewById(R.id.songList)
        playButton = findViewById(R.id.playButton)
        nextButton = findViewById(R.id.nextButton)
        prevButton = findViewById(R.id.prevButton)
        folderButton = findViewById(R.id.folderButton)
        reloadButton = findViewById(R.id.reloadButton)
        loadingSpinner = findViewById(R.id.loadingSpinner)
        loadingText = findViewById(R.id.loadingText)
        scanProgressBar = findViewById(R.id.scanProgressBar)
        scanningStatus = findViewById(R.id.scanningStatus)
        folderCountLabel = findViewById(R.id.folderCountLabel)
        songSeekBar = findViewById(R.id.songSeekBar)

        folderButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            folderPickerLauncher.launch(intent)
        }

        reloadButton.setOnClickListener {
    if (currentDirUri != null) {
        AlertDialog.Builder(this)
            .setTitle("Reload Songs")
            .setMessage("Are you sure you want to reload songs?")
            .setPositiveButton("Yes") { _, _ -> loadBeatmapSongs(currentDirUri!!) }
            .setNegativeButton("Cancel", null)
            .show()
    } else {
        Toast.makeText(this, "No folder selected", Toast.LENGTH_SHORT).show()
    }
}

        playButton.setOnClickListener {
            if (mediaPlayer == null) {
                playSong(currentIndex)
            } else {
                if (mediaPlayer!!.isPlaying) {
                    mediaPlayer!!.pause()
                    playButton.text = "▶"
                } else {
                    mediaPlayer!!.start()
                    playButton.text = "⏸"
                }
            }
        }

        nextButton.setOnClickListener { playNext() }
        prevButton.setOnClickListener { playPrevious() }

        songSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && mediaPlayer != null) {
                    mediaPlayer!!.seekTo(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
            }
        })

        val savedUriString = getSharedPreferences("osuTunesPrefs", MODE_PRIVATE)
            .getString("savedFolderUri", null)

        if (savedUriString != null) {
            try {
                val uri = Uri.parse(savedUriString)
                currentDirUri = uri
                loadSongsFromFile()
            } catch (_: Exception) {}
        }
    }

    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri: Uri? = result.data?.data
                uri?.let {
                    contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    currentDirUri = it
                    getSharedPreferences("osuTunesPrefs", MODE_PRIVATE)
                        .edit()
                        .putString("savedFolderUri", it.toString())
                        .apply()
                    loadBeatmapSongs(it)
                }
            }
        }

    private fun saveSongsToFile() {
        currentDirUri?.let { uri ->
            try {
                val pickedDir = DocumentFile.fromTreeUri(this, uri)
                pickedDir?.findFile("songs.json")?.delete()
                val jsonFile = pickedDir?.createFile("application/json", "songs.json")
                val json = Json.encodeToString(songEntries)
                contentResolver.openOutputStream(jsonFile!!.uri)?.use {
                    it.write(json.toByteArray())
                }
            } catch (_: Exception) {}
        }
    }

    private fun loadSongsFromFile() {
        currentDirUri?.let { uri ->
            try {
                val pickedDir = DocumentFile.fromTreeUri(this, uri)
                val jsonFile = pickedDir?.findFile("songs.json")
                val json = contentResolver.openInputStream(jsonFile!!.uri)?.bufferedReader()?.use { it.readText() }
                val savedEntries = Json.decodeFromString<List<SongEntry>>(json!!)
                songEntries.clear()
                songEntries.addAll(savedEntries)

                val titles = songEntries.map { it.label }
                val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, titles)
                listView.adapter = adapter
                listView.setOnItemClickListener { _, _, position, _ -> playSong(position) }
            } catch (_: Exception) {}
        }
    }

    private fun loadBeatmapSongs(uri: Uri) {
        loadingSpinner.visibility = View.VISIBLE
        loadingText.visibility = View.VISIBLE
        scanProgressBar.visibility = View.VISIBLE
        scanningStatus.visibility = View.VISIBLE
        folderCountLabel.visibility = View.VISIBLE

        lifecycleScope.launch {
            val newSongs = withContext(Dispatchers.IO) {
                val pickedDir = DocumentFile.fromTreeUri(this@MainActivity, uri)
                val entries = mutableListOf<SongEntry>()
                val allFolders = pickedDir?.listFiles()?.filter { it.isDirectory } ?: emptyList()
                val total = allFolders.size

                for ((i, folder) in allFolders.withIndex()) {
                    runOnUiThread {
                        scanningStatus.text = "Scanning: ${folder.name}"
                        scanProgressBar.progress = ((i + 1) * 100 / total)
                        folderCountLabel.text = "Scanned ${i + 1} of $total folders"
                    }

                    val osuFiles = folder.listFiles()?.filter { it.name?.endsWith(".osu") == true } ?: continue
                    val metadataList = osuFiles.mapNotNull { parseOsuFile(it) }
                    val audioGroups = metadataList.groupBy { it.audioFilename }

                    if (audioGroups.size == 1) {
                        val metadata = metadataList.first()
                        val audioFile = folder.listFiles()?.find {
                            it.name?.equals(metadata.audioFilename, ignoreCase = true) == true
                        }
                        if (audioFile != null && audioFile.isFile) {
                            val label = "${metadata.artist} - ${metadata.title}"
                            entries.add(SongEntry(label, audioFile.uri.toString()))
                        }
                    } else {
                        metadataList.forEach { metadata ->
                            val audioFile = folder.listFiles()?.find {
                                it.name?.equals(metadata.audioFilename, ignoreCase = true) == true
                            }
                            if (audioFile != null && audioFile.isFile) {
                                val label = "${metadata.artist} - ${metadata.title} (${metadata.audioFilename})"
                                entries.add(SongEntry(label, audioFile.uri.toString()))
                            }
                        }
                    }

                    delay(50)
                }

                entries
            }

            songEntries.clear()
            songEntries.addAll(newSongs)
            saveSongsToFile()

            val titles = songEntries.map { it.label }
            val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, titles)
            listView.adapter = adapter
            listView.setOnItemClickListener { _, _, position, _ -> playSong(position) }

            loadingSpinner.visibility = View.GONE
            loadingText.visibility = View.GONE
            scanProgressBar.visibility = View.GONE
            scanningStatus.visibility = View.GONE
            folderCountLabel.visibility = View.GONE
        }
    }

    private data class OsuMetadata(val audioFilename: String, val artist: String, val title: String)

    private fun parseOsuFile(file: DocumentFile): OsuMetadata? {
    return try {
        val inputStream = contentResolver.openInputStream(file.uri) ?: return null
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

        reader.close()
        if (audioFilename.isNotEmpty() && artist.isNotEmpty() && title.isNotEmpty()) {
            OsuMetadata(audioFilename, artist, title)
        } else null
    } catch (e: Exception) {
        null
    }
}

private fun playSong(index: Int) {
    if (songEntries.isEmpty()) return

    currentIndex = index
    mediaPlayer?.release()
    mediaPlayer = MediaPlayer()

    val songUri = Uri.parse(songEntries[index].uriString)
    try {
        val descriptor = contentResolver.openAssetFileDescriptor(songUri, "r") ?: return
        mediaPlayer!!.setDataSource(descriptor.fileDescriptor)
        descriptor.close()
        mediaPlayer!!.prepare()
        mediaPlayer!!.start()
        playButton.text = "⏸"
        Toast.makeText(this, "Playing: ${songEntries[index].label}", Toast.LENGTH_SHORT).show()

        songSeekBar.max = mediaPlayer!!.duration
        handler.post(object : Runnable {
            override fun run() {
                if (mediaPlayer != null && !isUserSeeking) {
                    songSeekBar.progress = mediaPlayer!!.currentPosition
                }
                handler.postDelayed(this, 500)
            }
        })
    } catch (e: Exception) {
        Toast.makeText(this, "Failed to play: ${songEntries[index].label}", Toast.LENGTH_SHORT).show()
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

override fun onDestroy() {
    super.onDestroy()
    mediaPlayer?.release()
    mediaPlayer = null
}
}
