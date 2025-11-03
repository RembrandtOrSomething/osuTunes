// File: DataClasses.kt

package com.example.osutunes.data

import kotlinx.serialization.Serializable // CRITICAL: Required for JSON deserialization

/**
 * Data class representing a playable song entry.
 * This is saved to and loaded from songs.json.
 */
@Serializable
data class SongEntry(
    val label: String, // Combined label (Artist - Title (X versions))
    val uriString: String,
    val artist: String,
    val title: String
    // Note: If you need to include more properties like length or lastModified,
    // they should be added here and handled in SongScanner.
)

/**
 * Data class for metadata parsed from a single .osu beatmap file.
 * Used internally during the scanning process.
 */
data class OsuMetadata(
    val audioFilename: String, 
    val artist: String, 
    val title: String
)