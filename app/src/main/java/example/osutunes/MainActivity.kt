package com.example.osutunes

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var playButton: Button
    private lateinit var nextButton: Button
    private lateinit var prevButton: Button
    private lateinit var folderButton: Button

    private var songFiles = mutableListOf<File>()
    private var currentIndex = 0
    private var mediaPlayer: MediaPlayer? = null
    private var currentDir: File = File("/storage/emulated/0/osu!droid/Songs")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listView = findViewById(R.id.songList)
        playButton = findViewById(R.id.playButton)
        nextButton = findViewById(R.id.nextButton)
        prevButton = findViewById(R.id.prevButton)
        folderButton = findViewById(R.id.folderButton)

        checkPermissions()

        folderButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            folderPickerLauncher.launch(intent)
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

        loadSongsFromDirectory(currentDir)
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                1
            )
        }
    }

    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri: Uri? = result.data?.data
                uri?.let {
                    val path = getPathFromUri(it)
                    path?.let { p ->
                        val dir = File(p)
                        if (dir.exists()) {
                            currentDir = dir
                            loadSongsFromDirectory(dir)
                        }
                    }
                }
            }
        }

    private fun getPathFromUri(uri: Uri): String? {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val split = docId.split(":")
        val type = split[0]
        val relativePath = if (split.size > 1) split[1] else ""
        val basePath = when (type) {
            "primary" -> Environment.getExternalStorageDirectory().path
            else -> "/storage/$type"
        }
        return "$basePath/$relativePath"
    }

    private fun loadSongsFromDirectory(directory: File) {
        val validExtensions = listOf("mp3", "ogg")
        val badKeywords = listOf(
            "hit", "spinnerspin", "spinnerbonus", "applause", "combobreak", "failsound"
        )

        songFiles.clear()
        directory.walkTopDown().forEach {
            if (it.isFile) {
                val ext = it.extension.lowercase()
                val name = it.nameWithoutExtension.lowercase()
                if (ext in validExtensions &&
                    it.length() > 500_000 && // skip tiny hitsounds
                    badKeywords.none { bad -> name.contains(bad) }) {
                    songFiles.add(it)
                }
            }
        }

        val titles = songFiles.map { it.nameWithoutExtension }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, titles)
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            playSong(position)
        }

        Toast.makeText(this, "Loaded ${songFiles.size} songs", Toast.LENGTH_SHORT).show()
    }

    private fun playSong(index: Int) {
        if (songFiles.isEmpty()) return

        currentIndex = index
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer()

        val song = songFiles[index]
        mediaPlayer!!.setDataSource(song.absolutePath)
        mediaPlayer!!.prepare()
        mediaPlayer!!.start()
        playButton.text = "⏸"

        Toast.makeText(this, "Playing: ${song.name}", Toast.LENGTH_SHORT).show()
    }

    private fun playNext() {
        if (songFiles.isEmpty()) return
        currentIndex = (currentIndex + 1) % songFiles.size
        playSong(currentIndex)
    }

    private fun playPrevious() {
        if (songFiles.isEmpty()) return
        currentIndex = if (currentIndex - 1 < 0) songFiles.size - 1 else currentIndex - 1
        playSong(currentIndex)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
