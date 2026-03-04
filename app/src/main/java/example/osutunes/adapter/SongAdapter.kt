package com.example.osutunes.adapter

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.example.osutunes.R 
import com.example.osutunes.data.SongEntry // Import the new data class location
import java.util.Locale

class SongAdapter(
    context: Context, 
    songs: List<SongEntry>, 
    private val searchEditText: EditText,
    private var playingSong: SongEntry? // ADDED: To track the currently playing song
) : 
    ArrayAdapter<SongEntry>(context, 0, songs.toMutableList()), Filterable {

    // Master list that never changes order unless explicitly sorted
    private var allSongs: List<SongEntry> = songs.toList() // Use .toList() for immutable base
    // The list currently visible in the ListView (filtered and/or sorted)
    private var currentFilteredSongs: List<SongEntry> = allSongs.toMutableList() 
    private val layoutInflater = LayoutInflater.from(context)

    // FIX: Override getView/getCount/getItem to use the currentFilteredSongs list
    override fun getCount(): Int = currentFilteredSongs.size
    override fun getItem(position: Int): SongEntry? = currentFilteredSongs[position]

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val song = getItem(position)
        
        val view = convertView ?: layoutInflater.inflate(R.layout.list_item_song, parent, false) 

        // ADDED: Highlight the currently playing song
        if (song != null && song == playingSong) {
            // Using a bright color for highlight (you might need to define R.color.playing_song_highlight)
            // Using a system color or hardcoded color as fallback:
            view.setBackgroundColor(Color.parseColor("#3B3C4F")) // Example: Dark Purple-Gray
        } else {
            // Reset background for non-playing songs
            view.setBackgroundResource(android.R.color.transparent) 
        }


        val titleTextView = view.findViewById<TextView>(R.id.textTitle) 
        val artistTextView = view.findViewById<TextView>(R.id.textArtist) 

        if (song != null) {
            titleTextView.text = song.title
            artistTextView.text = song.artist
        }

        return view
    }

    // ADDED: Method for MainActivity to update the playing song highlight
    fun setPlayingSong(song: SongEntry?) {
        playingSong = song
        notifyDataSetChanged()
    }

    // --- Filtering Implementation ---
    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val results = FilterResults()
                val query = constraint.toString().lowercase(Locale.getDefault()).trim()

                val filteredList = if (query.isEmpty()) {
                    allSongs 
                } else {
                    allSongs.filter { 
                        it.title.lowercase(Locale.getDefault()).contains(query) ||
                        it.artist.lowercase(Locale.getDefault()).contains(query)
                    }
                }
                results.values = filteredList
                results.count = filteredList.size
                return results
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                // Update the list displayed by the adapter
                currentFilteredSongs = results.values as List<SongEntry>
                notifyDataSetChanged() 
            }
        }
    }
    
    // --- Sorting Implementation ---
    fun sortSongs(sortBy: String) {
        // Re-sort the master list
        val sortedList = when (sortBy) {
            "Title" -> allSongs.sortedBy { it.title.lowercase(Locale.getDefault()) }
            "Artist" -> allSongs.sortedBy { it.artist.lowercase(Locale.getDefault()) }
            "Versions" -> allSongs.sortedByDescending { it.label.count { c -> c == '(' } } // Proxy for version count
            else -> allSongs // Default: No sorting
        }
        // Update the master list
        allSongs = sortedList
        // CORRECTED: Use the passed-in searchEditText to get the current query and re-filter
        filter.filter(searchEditText.text?.toString()) 
    }
}