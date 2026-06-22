package com.example.playback

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import com.example.data.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class LoopMode {
    NO_LOOP,
    LOOP_ALL,
    LOOP_ONE
}

class PlaybackManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentPlayer: MediaPlayer? = null
    private var nextPlayer: MediaPlayer? = null

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _audioSessionId = MutableStateFlow<Int?>(null)
    val audioSessionId: StateFlow<Int?> = _audioSessionId.asStateFlow()

    private val _loopMode = MutableStateFlow(LoopMode.NO_LOOP)
    val loopMode: StateFlow<LoopMode> = _loopMode.asStateFlow()

    private val _isShuffle = MutableStateFlow(false)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

    private var originalQueue = listOf<Song>()
    private var currentQueue = listOf<Song>()
    private var currentIndex = -1

    private var progressJob: Job? = null

    init {
        startProgressTracker()
    }

    fun setQueue(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty()) return
        originalQueue = songs
        currentIndex = startIndex
        
        applyQueueOrdering()
        playSongAtIndex(currentIndex)
    }

    private fun applyQueueOrdering() {
        if (_isShuffle.value) {
            val current = originalQueue.getOrNull(currentIndex)
            val pool = originalQueue.toMutableList()
            if (current != null) {
                pool.remove(current)
                pool.shuffle()
                currentQueue = listOf(current) + pool
                currentIndex = 0
            } else {
                pool.shuffle()
                currentQueue = pool
                currentIndex = 0
            }
        } else {
            currentQueue = originalQueue
        }
    }

    fun setShuffle(shuffle: Boolean) {
        _isShuffle.value = shuffle
        if (currentSong.value != null) {
            // Find current song in original queue to maintain continuity in index
            val activeSong = currentSong.value
            applyQueueOrdering()
            val newIndex = currentQueue.indexOf(activeSong)
            if (newIndex != -1) {
                currentIndex = newIndex
            }
            // Re-preload next song with the new queue ordering
            preloadNextSong()
        }
    }

    fun setLoopMode(mode: LoopMode) {
        _loopMode.value = mode
        preloadNextSong()
    }

    fun togglePlayPause() {
        val player = currentPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            _isPlaying.value = false
        } else {
            player.start()
            _isPlaying.value = true
        }
    }

    fun seekTo(positionMs: Long) {
        currentPlayer?.seekTo(positionMs.toInt())
        _currentPosition.value = positionMs
    }

    fun next() {
        advanceQueue(manual = true)
    }

    fun previous() {
        if (currentQueue.isEmpty()) return
        
        // If current position is > 3 seconds, restart the song
        if (_currentPosition.value > 3000) {
            seekTo(0)
            return
        }

        currentIndex--
        if (currentIndex < 0) {
            currentIndex = if (_loopMode.value == LoopMode.LOOP_ALL) currentQueue.size - 1 else 0
        }
        playSongAtIndex(currentIndex)
    }

    private fun playSongAtIndex(index: Int) {
        if (currentQueue.isEmpty() || index < 0 || index >= currentQueue.size) {
            stop()
            return
        }

        currentIndex = index
        val song = currentQueue[index]
        _currentSong.value = song
        _currentPosition.value = 0L

        try {
            // Clean up any existing player
            currentPlayer?.reset() ?: run { currentPlayer = MediaPlayer() }
            
            val player = currentPlayer!!
            player.setDataSource(context, Uri.fromFile(File(song.absolutePath)))
            player.prepare()
            
            _duration.value = player.duration.toLong()
            _audioSessionId.value = player.audioSessionId

            // Configure single loop repeating inside MediaPlayer itself for efficiency
            player.isLooping = (_loopMode.value == LoopMode.LOOP_ONE)

            player.setOnCompletionListener {
                onCurrentPlayerCompleted()
            }

            player.start()
            _isPlaying.value = true

            // Set up gapless preload for next track
            preloadNextSong()

        } catch (e: Exception) {
            Log.e("PlaybackManager", "Error preparing player for: ${song.absolutePath}", e)
            // Attempt to recover by playing next song
            advanceQueue(manual = false)
        }
    }

    /**
     * Set up our dual-player buffer for true gapless playback
     */
    private fun preloadNextSong() {
        if (currentQueue.isEmpty() || currentIndex == -1) return

        // Cancel previous nextPlayer prep
        nextPlayer?.release()
        nextPlayer = null
        currentPlayer?.setNextMediaPlayer(null)

        val nextIndex = getNextIndex()
        if (nextIndex == -1 || _loopMode.value == LoopMode.LOOP_ONE) {
            // No next track available or single looping active
            return
        }

        val nextSong = currentQueue[nextIndex]
        try {
            val preloader = MediaPlayer()
            preloader.setDataSource(context, Uri.fromFile(File(nextSong.absolutePath)))
            preloader.prepare()
            
            nextPlayer = preloader
            
            // Set up native Android-level zero-latency gapless handoff
            currentPlayer?.setNextMediaPlayer(preloader)
            
            Log.d("PlaybackManager", "Preloaded gapless track: ${nextSong.title}")
        } catch (e: Exception) {
            Log.e("PlaybackManager", "Error preloading next track: ${nextSong.absolutePath}", e)
            nextPlayer?.release()
            nextPlayer = null
        }
    }

    private fun getNextIndex(): Int {
        if (currentQueue.isEmpty() || currentIndex == -1) return -1
        
        var next = currentIndex + 1
        if (next >= currentQueue.size) {
            next = if (_loopMode.value == LoopMode.LOOP_ALL) 0 else -1
        }
        return next
    }

    private fun onCurrentPlayerCompleted() {
        if (_loopMode.value == LoopMode.LOOP_ONE) {
            // Single loop active: seek to beginning and repeat
            seekTo(0)
            currentPlayer?.start()
            _isPlaying.value = true
            return
        }

        val nextIndex = getNextIndex()
        if (nextIndex == -1) {
            // Queue completed and NO_LOOP selected
            stop()
            return
        }

        // Handoff to previously compiled gapless nextPlayer!
        val promotedNextPlayer = nextPlayer
        if (promotedNextPlayer != null) {
            currentPlayer?.release()
            
            currentPlayer = promotedNextPlayer
            currentIndex = nextIndex
            val activeSong = currentQueue[currentIndex]
            
            _currentSong.value = activeSong
            _duration.value = promotedNextPlayer.duration.toLong()
            _audioSessionId.value = promotedNextPlayer.audioSessionId
            _currentPosition.value = 0L
            _isPlaying.value = true

            promotedNextPlayer.isLooping = (_loopMode.value == LoopMode.LOOP_ONE)
            promotedNextPlayer.setOnCompletionListener {
                onCurrentPlayerCompleted()
            }

            // Realize next preloaded slot
            nextPlayer = null
            preloadNextSong()
        } else {
            // Preloader was absent/errored, fallback to standard load
            playSongAtIndex(nextIndex)
        }
    }

    private fun advanceQueue(manual: Boolean) {
        if (currentQueue.isEmpty()) return
        val nextIndex = getNextIndex()
        if (nextIndex != -1) {
            playSongAtIndex(nextIndex)
        } else if (manual && _loopMode.value != LoopMode.LOOP_ALL) {
            // Manual Next at end of non-looping playlist wraps around
            playSongAtIndex(0)
        } else {
            stop()
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(250)
                currentPlayer?.let { player ->
                    if (player.isPlaying) {
                        _currentPosition.value = player.currentPosition.toLong()
                    }
                }
            }
        }
    }

    fun stop() {
        currentPlayer?.stop()
        currentPlayer?.release()
        currentPlayer = null

        nextPlayer?.release()
        nextPlayer = null

        _currentSong.value = null
        _isPlaying.value = false
        _currentPosition.value = 0L
        _duration.value = 0L
        _audioSessionId.value = null
    }

    fun release() {
        stop()
        progressJob?.cancel()
        scope.cancel()
    }
}
