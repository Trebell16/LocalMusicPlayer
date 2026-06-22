package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.Folder
import com.example.data.MusicRepository
import com.example.data.Song
import com.example.data.db.PlaylistEntity
import com.example.data.db.PlaylistWithSongs
import com.example.playback.LoopMode
import com.example.playback.PlaybackManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class SongSortOrder(val displayName: String) {
    DATE_ADDED("Date Added"),
    ARTIST_NAME("Artist Name"),
    TRACK_DURATION("Track Duration")
}

class MusicViewModel(
    private val repository: MusicRepository,
    private val playbackManager: PlaybackManager
) : ViewModel() {

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOrder = MutableStateFlow(SongSortOrder.DATE_ADDED)
    val sortOrder: StateFlow<SongSortOrder> = _sortOrder.asStateFlow()

    // Playlist States
    val playlists: StateFlow<List<PlaylistEntity>> = repository.playlistsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlistsWithSongs: StateFlow<List<PlaylistWithSongs>> = repository.playlistsWithSongsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Player State Bindings
    val currentSong: StateFlow<Song?> = playbackManager.currentSong
    val isPlaying: StateFlow<Boolean> = playbackManager.isPlaying
    val currentPosition: StateFlow<Long> = playbackManager.currentPosition
    val duration: StateFlow<Long> = playbackManager.duration
    val loopMode: StateFlow<LoopMode> = playbackManager.loopMode
    val isShuffle: StateFlow<Boolean> = playbackManager.isShuffle
    val audioSessionId: StateFlow<Int?> = playbackManager.audioSessionId

    // Filtered and sorted lists of songs based on search query and sort order
    val filteredSongs: StateFlow<List<Song>> = combine(
        combine(_songs, _searchQuery) { list, query ->
            if (query.isBlank()) {
                list
            } else {
                list.filter {
                    it.title.contains(query, ignoreCase = true) ||
                            it.artist.contains(query, ignoreCase = true) ||
                            it.album.contains(query, ignoreCase = true)
                }
            }
        },
        _sortOrder
    ) { filteredList, order ->
        when (order) {
            SongSortOrder.DATE_ADDED -> filteredList.sortedBy { it.id }
            SongSortOrder.ARTIST_NAME -> filteredList.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist })
            SongSortOrder.TRACK_DURATION -> filteredList.sortedBy { it.duration }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Filtered list of folders based on search query
    val filteredFolders: StateFlow<List<Folder>> = combine(_folders, _searchQuery) { folderList, query ->
        if (query.isBlank()) {
            folderList
        } else {
            folderList.filter { folder ->
                folder.name.contains(query, ignoreCase = true) ||
                        folder.songs.any { song ->
                            song.title.contains(query, ignoreCase = true) ||
                                    song.artist.contains(query, ignoreCase = true)
                        }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun scanDevice() {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                val scanned = repository.scanSongs()
                _songs.value = scanned
                _folders.value = repository.groupSongsByFolder(scanned)
            } catch (e: Exception) {
                // handle logs safely
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun makeSampleTracks() {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                repository.generateSampleTracks()
                val scanned = repository.scanSongs()
                _songs.value = scanned
                _folders.value = repository.groupSongsByFolder(scanned)
            } catch (e: Exception) {
                // handle logs safely
            } finally {
                _isScanning.value = false
            }
        }
    }

    // Playback control delegates
    fun playSongFromList(songsList: List<Song>, song: Song) {
        val index = songsList.indexOf(song)
        if (index != -1) {
            playbackManager.setQueue(songsList, index)
        }
    }

    fun playAllInQueue(songsList: List<Song>) {
        if (songsList.isNotEmpty()) {
            playbackManager.setQueue(songsList, 0)
        }
    }

    fun togglePlayPause() = playbackManager.togglePlayPause()
    fun next() = playbackManager.next()
    fun previous() = playbackManager.previous()
    fun seekTo(positionMs: Long) = playbackManager.seekTo(positionMs)
    fun setLoopMode(mode: LoopMode) = playbackManager.setLoopMode(mode)
    fun toggleShuffle() = playbackManager.setShuffle(!isShuffle.value)

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateSortOrder(order: SongSortOrder) {
        _sortOrder.value = order
    }

    // Playlist Database Operations
    fun createPlaylist(name: String) {
        viewModelScope.launch {
            repository.createPlaylist(name)
        }
    }

    fun deletePlaylist(playlistId: Int) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
        }
    }

    fun addSongToPlaylist(playlistId: Int, song: Song) {
        viewModelScope.launch {
            repository.addSongToPlaylist(playlistId, song)
        }
    }

    fun removeSongFromPlaylist(playlistId: Int, songPath: String) {
        viewModelScope.launch {
            repository.removeSongFromPlaylist(playlistId, songPath)
        }
    }

    fun deleteSong(song: Song) {
        viewModelScope.launch {
            // If current playing, skip or stop
            if (playbackManager.currentSong.value?.absolutePath == song.absolutePath) {
                playbackManager.next()
                if (playbackManager.currentSong.value?.absolutePath == song.absolutePath) {
                    playbackManager.release()
                }
            }
            try {
                val file = java.io.File(song.absolutePath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                // Handle or ignore gracefully
            }
            val updatedSongs = _songs.value.filter { it.absolutePath != song.absolutePath }
            _songs.value = updatedSongs
            _folders.value = repository.groupSongsByFolder(updatedSongs)
        }
    }

    override fun onCleared() {
        super.onCleared()
        playbackManager.release()
    }
}

class MusicViewModelFactory(
    private val repository: MusicRepository,
    private val playbackManager: PlaybackManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MusicViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MusicViewModel(repository, playbackManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
