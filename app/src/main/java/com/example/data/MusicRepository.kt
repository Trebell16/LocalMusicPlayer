package com.example.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.data.db.PlaylistDao
import com.example.data.db.PlaylistEntity
import com.example.data.db.PlaylistSongEntity
import com.example.data.db.PlaylistWithSongs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sin


class MusicRepository(
    private val context: Context,
    private val playlistDao: PlaylistDao
) {

    val playlistsFlow: Flow<List<PlaylistEntity>> = playlistDao.getAllPlaylists()
    val playlistsWithSongsFlow: Flow<List<PlaylistWithSongs>> = playlistDao.getAllPlaylistsWithSongs()

    suspend fun getPlaylistWithSongs(playlistId: Int): Flow<PlaylistWithSongs?> {
        return playlistDao.getPlaylistWithSongs(playlistId)
    }

    suspend fun createPlaylist(name: String): Int {
        return withContext(Dispatchers.IO) {
            playlistDao.insertPlaylist(PlaylistEntity(name = name)).toInt()
        }
    }

    suspend fun deletePlaylist(playlistId: Int) {
        withContext(Dispatchers.IO) {
            playlistDao.deletePlaylist(playlistId)
        }
    }

    suspend fun addSongToPlaylist(playlistId: Int, song: Song) {
        withContext(Dispatchers.IO) {
            playlistDao.insertPlaylistSong(
                PlaylistSongEntity(
                    playlistId = playlistId,
                    songPath = song.absolutePath,
                    title = song.title,
                    artist = song.artist,
                    duration = song.duration
                )
            )
        }
    }

    suspend fun removeSongFromPlaylist(playlistId: Int, songPath: String) {
        withContext(Dispatchers.IO) {
            playlistDao.removeSongFromPlaylist(playlistId, songPath)
        }
    }

    /**
     * Scans both MediaStore and custom directories, then groups them by their parent folder.
     */
    suspend fun scanSongs(): List<Song> = withContext(Dispatchers.IO) {
        val songsList = mutableListOf<Song>()

        // 1. Scan via MediaStore
        try {
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            
            context.contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val path = cursor.getString(dataCol)
                    val title = cursor.getString(titleCol) ?: "Unknown Track"
                    var artist = cursor.getString(artistCol) ?: "Unknown Artist"
                    if (artist == "<unknown>") artist = "Unknown Artist"
                    val album = cursor.getString(albumCol) ?: "Unknown Album"
                    val duration = cursor.getLong(durationCol)
                    val size = cursor.getLong(sizeCol)

                    val file = File(path)
                    if (file.exists()) {
                        val parentFile = file.parentFile
                        val folderName = parentFile?.name ?: "Root"
                        val folderPath = parentFile?.absolutePath ?: "/"
                        
                        val albumArtUri = ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"),
                            id
                        )

                        songsList.add(
                            Song(
                                id = id,
                                absolutePath = path,
                                title = title,
                                artist = artist,
                                album = album,
                                duration = duration,
                                size = size,
                                folderName = folderName,
                                folderPath = folderPath,
                                albumArtUri = albumArtUri
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("MusicRepository", "MediaStore scan skipped or unsupported: ${e.message}")
        }

        // 2. Direct recursive scanning of primary external and public storage directories
        val foundFiles = mutableListOf<File>()
        val visitedPaths = mutableSetOf<String>()
        val rootsToScan = mutableListOf<File>()

        try {
            rootsToScan.add(Environment.getExternalStorageDirectory())
        } catch (e: Exception) {
            Log.d("MusicRepository", "External storage root directories bypassed: ${e.message}")
        }

        try {
            rootsToScan.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC))
        } catch (e: Exception) {}

        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            rootsToScan.add(downloadsDir)
        } catch (e: Exception) {}

        try {
            context.getExternalFilesDirs(null)?.forEach { dir ->
                if (dir != null) rootsToScan.add(dir)
            }
        } catch (e: Exception) {}

        // Add standard cache / external files music path
        try {
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.let { rootsToScan.add(it) }
        } catch (e: Exception) {}

        val uniqueRoots = rootsToScan.filter { it.exists() && it.isDirectory && it.canRead() }
            .distinctBy { try { it.canonicalPath } catch (e: Exception) { it.absolutePath } }

        for (root in uniqueRoots) {
            scanDirectoryRecursively(root, foundFiles, visitedPaths)
        }

        // Process found files with metadata retriever
        for (file in foundFiles) {
            // Avoid duplicates from MediaStore
            if (songsList.none { it.absolutePath == file.absolutePath }) {
                val parentFile = file.parentFile
                val folderName = parentFile?.name ?: "Roots"
                val folderPath = parentFile?.absolutePath ?: "/"

                var title = file.nameWithoutExtension.replace("_", " ")
                var artist = "Unknown Artist"
                var album = "Unknown Album"
                var duration = 0L

                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { title = it }
                    retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { artist = it }
                    retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { album = it }
                    retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { duration = it }
                } catch (e: Exception) {
                    Log.w("MusicRepository", "Could not parse metadata for ${file.name}: ${e.message}")
                } finally {
                    try {
                        retriever.release()
                    } catch (ex: Exception) {}
                }

                // Fallbacks for duration estimation
                if (duration <= 0L) {
                    duration = if (file.extension.lowercase() == "wav") {
                        getWavDuration(file)
                    } else {
                        180000L // 3 minutes default fallback
                    }
                }

                songsList.add(
                    Song(
                        id = file.hashCode().toLong(),
                        absolutePath = file.absolutePath,
                        title = title,
                        artist = artist,
                        album = album,
                        duration = duration,
                        size = file.length(),
                        folderName = folderName,
                        folderPath = folderPath,
                        albumArtUri = null
                    )
                )
            }
        }

        songsList.sortBy { it.title }
        songsList
    }

    /**
     * Recursively traverses folders, respecting .nomedia exclusions and skipping hidden or sluggish paths.
     */
    private fun scanDirectoryRecursively(
        dir: File,
        foundFiles: MutableList<File>,
        visitedPaths: MutableSet<String>,
        maxDepth: Int = 12,
        currentDepth: Int = 0
    ) {
        if (currentDepth > maxDepth) return
        if (!dir.exists() || !dir.isDirectory || !dir.canRead()) return

        val canonicalPath = try { dir.canonicalPath } catch (e: Exception) { dir.absolutePath }
        if (!visitedPaths.add(canonicalPath)) return

        // Skip hidden directories (starting with ".")
        val name = dir.name
        if (name.startsWith(".")) return

        // Skip standard high-volume Android folders under root
        if (name.equals("Android", ignoreCase = true) && currentDepth <= 1) return

        val files = dir.listFiles() ?: return
        
        // Scan for .nomedia file
        if (files.any { it.isFile && it.name.equals(".nomedia", ignoreCase = true) }) {
            return
        }

        for (file in files) {
            if (file.isDirectory) {
                scanDirectoryRecursively(file, foundFiles, visitedPaths, maxDepth, currentDepth + 1)
            } else if (file.isFile) {
                if (file.name.startsWith(".")) continue
                val ext = file.extension.lowercase()
                if (ext == "mp3" || ext == "flac" || ext == "wav" || ext == "ogg" || ext == "m4a") {
                    foundFiles.add(file)
                }
            }
        }
    }

    private fun getWavDuration(file: File): Long {
        // Estimates duration of standard 16-bit 44.1kHz mono WAV files we generate
        val sampleRate = 44100
        val bytesPerSample = 2
        val channels = 1
        val byteRate = sampleRate * bytesPerSample * channels
        val dataSize = file.length() - 44
        return if (dataSize > 0) {
            (dataSize * 1000) / byteRate
        } else {
            3000L // 3 seconds default
        }
    }

    /**
     * Group lists of songs by folder path
     */
    fun groupSongsByFolder(songs: List<Song>): List<Folder> {
        return songs.groupBy { it.folderPath }
            .map { (path, folderSongs) ->
                Folder(
                    name = folderSongs.firstOrNull()?.folderName ?: "Unknown Folder",
                    path = path,
                    songs = folderSongs
                )
            }.sortedBy { it.name }
    }

    /**
     * Dynamically generates high-quality sample tracks for offline testing.
     * Generates beautiful melodic tones which represent different notes of a chord.
     */
    suspend fun generateSampleTracks(): Boolean = withContext(Dispatchers.IO) {
        val storageDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "SynthesizedLocalApp"
        )
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }

        val tracksToGenerate = listOf(
            Triple("Aura_Melody_C.wav", 261.63, 4.0), // Middle C
            Triple("Cosmic_Drone_G.wav", 392.00, 4.0),  // G4
            Triple("Midnight_Pulse_E.wav", 329.63, 4.0), // E4
            Triple("Zen_Bell_F_Sharp.wav", 369.99, 4.0) // F#4
        )

        var generatedAny = false
        for ((filename, freq, dur) in tracksToGenerate) {
            val file = File(storageDir, filename)
            if (!file.exists()) {
                writeWavHeaderAndData(file, freq, dur)
                generatedAny = true
                
                // Trigger media scanner so the file shows up in MediaStore
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf("audio/wav"),
                    null
                )
            }
        }
        generatedAny
    }

    private fun writeWavHeaderAndData(file: File, frequency: Double, durationSeconds: Double) {
        val sampleRate = 44100
        val numSamples = (sampleRate * durationSeconds).toInt()
        val dataSize = numSamples * 2 // 16-bit
        val fileSize = 36 + dataSize

        val header = ByteArray(44)
        // RIFF chunk descriptor
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (fileSize and 0xff).toByte()
        header[5] = ((fileSize shr 8) and 0xff).toByte()
        header[6] = ((fileSize shr 16) and 0xff).toByte()
        header[7] = ((fileSize shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        
        // fmt sub-chunk
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16 // Subchunk1Size (16 for PCM)
        header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1 // AudioFormat = PCM
        header[21] = 0
        header[22] = 1 // NumChannels = Mono
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte() // SampleRate
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        
        val byteRate = sampleRate * 2
        header[28] = (byteRate and 0xff).toByte() // ByteRate
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = 2 // BlockAlign
        header[33] = 0
        header[34] = 16 // BitsPerSample = 16
        header[35] = 0
        
        // data sub-chunk
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (dataSize and 0xff).toByte()
        header[41] = ((dataSize shr 8) and 0xff).toByte()
        header[42] = ((dataSize shr 16) and 0xff).toByte()
        header[43] = ((dataSize shr 24) and 0xff).toByte()

        FileOutputStream(file).use { out ->
            out.write(header)
            val buffer = ByteArray(2)
            
            // Generate elegant sounding audio note with exponential decay (gives bell/woodblock ring)
            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                val decay = Math.exp(-t * 0.95) // Exponential sound decay
                
                // Soft tremolo effect for modern synth textures
                val tremolo = 1.0 + 0.15 * sin(2.0 * Math.PI * t * 5.0)
                
                // Add rich harmonics (overtones) to make synth note sound incredible!
                val fundamental = sin(2.0 * Math.PI * t * frequency)
                val overtone1 = 0.5 * sin(2.0 * Math.PI * t * (frequency * 2.0))
                val overtone2 = 0.2 * sin(2.0 * Math.PI * t * (frequency * 3.0))
                
                val combined = (fundamental + overtone1 + overtone2) / 1.7
                val rawSample = (combined * decay * tremolo * 28000.0).toInt()
                
                // Clip protection
                val sample = rawSample.coerceIn(-32768, 32767)
                
                buffer[0] = (sample and 0xff).toByte()
                buffer[1] = ((sample shr 8) and 0xff).toByte()
                out.write(buffer)
            }
        }
    }
}
