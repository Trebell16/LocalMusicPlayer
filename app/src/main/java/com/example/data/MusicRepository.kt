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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sin


class MusicRepository(
    private val context: Context,
    private val playlistDao: PlaylistDao,
    private val recentlyPlayedDao: com.example.data.db.RecentlyPlayedDao,
    private val cachedSongDao: com.example.data.db.CachedSongDao
) {

    val playlistsFlow: Flow<List<PlaylistEntity>> = playlistDao.getAllPlaylists()
    val playlistsWithSongsFlow: Flow<List<PlaylistWithSongs>> = playlistDao.getAllPlaylistsWithSongs()
    
    val cachedSongsFlow: Flow<List<Song>> = cachedSongDao.getAllCachedSongsFlow().map { list ->
        list.map { cached ->
            Song(
                id = cached.id,
                absolutePath = cached.absolutePath,
                title = cached.title,
                artist = cached.artist,
                album = cached.album,
                duration = cached.duration,
                size = cached.size,
                folderName = cached.folderName,
                folderPath = cached.folderPath,
                albumArtUri = cached.albumArtUri?.let { Uri.parse(it) }
            )
        }.sortedBy { it.title }
    }

    val recentlyPlayedFlow: Flow<List<Song>> = recentlyPlayedDao.getRecentlyPlayed().map { list ->
        list.map { entity ->
            Song(
                id = entity.id,
                absolutePath = entity.songPath,
                title = entity.title,
                artist = entity.artist,
                album = entity.album,
                duration = entity.duration,
                size = entity.size,
                folderName = entity.folderName,
                folderPath = entity.folderPath,
                albumArtUri = entity.albumArtUri?.let { Uri.parse(it) }
            )
        }
    }

    private val repositoryScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val _isScanning = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isScanningFlow: Flow<Boolean> = _isScanning.asStateFlow()

    fun triggerBackgroundScan() {
        repositoryScope.launch {
            try {
                scanSongs()
            } catch (e: Exception) {
                Log.e("MusicRepository", "Background scan exception: ${e.message}", e)
            }
        }
    }

    suspend fun addSongToRecentlyPlayed(song: Song) {
        withContext(Dispatchers.IO) {
            recentlyPlayedDao.insertRecentlyPlayed(
                com.example.data.db.RecentlyPlayedEntity(
                    songPath = song.absolutePath,
                    id = song.id,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    duration = song.duration,
                    size = song.size,
                    folderName = song.folderName,
                    folderPath = song.folderPath,
                    albumArtUri = song.albumArtUri?.toString(),
                    playedAt = System.currentTimeMillis()
                )
            )
        }
    }

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

    suspend fun loadCachedSongs(): List<Song> = withContext(Dispatchers.IO) {
        cachedSongDao.getAllCachedSongs().map { cached ->
            Song(
                id = cached.id,
                absolutePath = cached.absolutePath,
                title = cached.title,
                artist = cached.artist,
                album = cached.album,
                duration = cached.duration,
                size = cached.size,
                folderName = cached.folderName,
                folderPath = cached.folderPath,
                albumArtUri = cached.albumArtUri?.let { Uri.parse(it) }
            )
        }.sortedBy { it.title }
    }

    /**
     * Scans both MediaStore and custom directories, then groups them by their parent folder.
     */
    suspend fun scanSongs(): List<Song> = withContext(Dispatchers.IO) {
        _isScanning.value = true
        try {
            val songsList = mutableListOf<Song>()
            val newCacheEntries = mutableListOf<com.example.data.db.CachedSongEntity>()

            // Load all existing cached songs from database
            val existingCache = cachedSongDao.getAllCachedSongs()
            val cacheMap = existingCache.associateBy { it.absolutePath }
            val processedPaths = mutableSetOf<String>()

            // Helper to get or scan metadata
            fun getOrScanSong(path: String, titleFallback: String, sizeFallback: Long): Song? {
                val file = File(path)
                if (!file.exists()) return null
                processedPaths.add(path)

                val cached = cacheMap[path]
                if (cached != null && cached.size == file.length()) { // check size as an integrity/edit check
                    return Song(
                        id = cached.id,
                        absolutePath = cached.absolutePath,
                        title = cached.title,
                        artist = cached.artist,
                        album = cached.album,
                        duration = cached.duration,
                        size = cached.size,
                        folderName = cached.folderName,
                        folderPath = cached.folderPath,
                        albumArtUri = cached.albumArtUri?.let { Uri.parse(it) }
                    )
                }

                // Otherwise, we must scan the metadata as this is a new or edited file
                val parentFile = file.parentFile
                val folderName = parentFile?.name ?: "Root"
                val folderPath = parentFile?.absolutePath ?: "/"
                var title = titleFallback
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

                if (duration <= 0L) {
                    duration = if (file.extension.lowercase() == "wav") {
                        getWavDuration(file)
                    } else {
                        180000L // 3 minutes default fallback
                    }
                }

                val id = file.hashCode().toLong()
                val albumArtUri = Uri.parse("content://com.example.provider.albumart?path=${Uri.encode(file.absolutePath)}")

                val newEntity = com.example.data.db.CachedSongEntity(
                    absolutePath = file.absolutePath,
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    duration = duration,
                    size = file.length(),
                    folderName = folderName,
                    folderPath = folderPath,
                    albumArtUri = albumArtUri?.toString()
                )
                newCacheEntries.add(newEntity)

                return Song(
                    id = id,
                    absolutePath = file.absolutePath,
                    title = title,
                    artist = artist,
                    album = album,
                    duration = duration,
                    size = file.length(),
                    folderName = folderName,
                    folderPath = folderPath,
                    albumArtUri = albumArtUri
                )
            }

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
                val selection = "${MediaStore.Audio.Media.SIZE} > 0"
                
                context.contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

                    while (cursor.moveToNext()) {
                        val path = cursor.getString(dataCol)
                        if (path.isNullOrBlank()) continue
                        
                        val lowerPath = path.lowercase()
                        if (lowerPath.endsWith(".mp3") || lowerPath.endsWith(".wav") || 
                            lowerPath.endsWith(".m4a") || lowerPath.endsWith(".ogg") || 
                            lowerPath.endsWith(".flac") || lowerPath.endsWith(".aac")) {
                            
                            val title = cursor.getString(titleCol) ?: "Unknown Track"
                            val size = cursor.getLong(sizeCol)

                            getOrScanSong(path, title, size)?.let { songsList.add(it) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("MusicRepository", "MediaStore scan skipped or unsupported: ${e.message}")
            }

            try {
                val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.TITLE,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE
                )
                val selection = "${MediaStore.Video.Media.SIZE} > 0"
                
                context.contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

                    while (cursor.moveToNext()) {
                        val path = cursor.getString(dataCol)
                        if (path.isNullOrBlank()) continue
                        
                        val lowerPath = path.lowercase()
                        val videoExtensions = listOf("mp4", "mkv", "webm", "3gp", "avi", "mov", "flv", "wmv")
                        if (videoExtensions.any { lowerPath.endsWith(".$it") }) {
                            val title = cursor.getString(titleCol) ?: "Unknown Video"
                            val size = cursor.getLong(sizeCol)

                            getOrScanSong(path, title, size)?.let { songsList.add(it) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("MusicRepository", "MediaStore video scan skipped or unsupported: ${e.message}")
            }

            // 2. Direct recursive scanning of specific app/music folders only to keep scanning instant
            val foundFiles = mutableListOf<File>()
            val visitedPaths = mutableSetOf<String>()
            val rootsToScan = mutableListOf<File>()

            try {
                rootsToScan.add(context.filesDir)
            } catch (e: Exception) {
                Log.d("MusicRepository", "Internal files directory bypassed: ${e.message}")
            }

            try {
                val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                if (musicDir.exists()) rootsToScan.add(musicDir)
            } catch (e: Exception) {}

            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir.exists()) rootsToScan.add(downloadsDir)
            } catch (e: Exception) {}

            try {
                context.getExternalFilesDirs(null)?.forEach { dir ->
                    if (dir != null) rootsToScan.add(dir)
                }
            } catch (e: Exception) {}

            try {
                context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.let { rootsToScan.add(it) }
            } catch (e: Exception) {}

            val uniqueRoots = rootsToScan.filter { 
                try {
                    it.exists() && it.isDirectory && it.canRead()
                } catch (e: Exception) {
                    false
                }
            }.distinctBy { try { it.canonicalPath } catch (e: Exception) { it.absolutePath } }

            for (root in uniqueRoots) {
                try {
                    val depthLimit = if (root == context.filesDir || root.absolutePath.contains(context.packageName)) 4 else 2
                    scanDirectoryRecursively(root, foundFiles, visitedPaths, maxDepth = depthLimit)
                } catch (e: Exception) {
                    Log.e("MusicRepository", "Error scanning root dir ${root.absolutePath}: ${e.message}")
                }
            }

            // Process found files
            val existingNamesLower = songsList.map { it.absolutePath.substringAfterLast('/').lowercase() }.toMutableSet()
            val existingPaths = songsList.map { it.absolutePath }.toMutableSet()

            for (file in foundFiles) {
                try {
                    val path = file.absolutePath
                    val filename = file.name
                    val filenameLower = filename.lowercase()

                    val isDupe = existingNamesLower.contains(filenameLower)
                    val hasPath = existingPaths.contains(path)

                    if (!isDupe && !hasPath) {
                        val titleFallback = file.nameWithoutExtension.replace("_", " ")
                        getOrScanSong(path, titleFallback, file.length())?.let {
                            songsList.add(it)
                            existingNamesLower.add(filenameLower)
                            existingPaths.add(path)
                        }
                    } else {
                        processedPaths.add(path)
                    }
                } catch (e: Exception) {
                    Log.e("MusicRepository", "Error processing file ${file.name}: ${e.message}")
                }
            }

            // Write new cache entries to DB
            if (newCacheEntries.isNotEmpty()) {
                try {
                    cachedSongDao.insertSongs(newCacheEntries)
                } catch (e: Exception) {
                    Log.e("MusicRepository", "DB failed to write cached songs: ${e.message}", e)
                }
            }

            // Detect songs that have been deleted physically and remove them from the cached DB
            val deletedPaths = cacheMap.keys.filter { it !in processedPaths }
            if (deletedPaths.isNotEmpty()) {
                try {
                    cachedSongDao.deleteSongsByPaths(deletedPaths)
                } catch (e: Exception) {
                    Log.e("MusicRepository", "DB failed to delete cached songs: ${e.message}", e)
                }
            }

            songsList.sortBy { it.title }
            songsList
        } finally {
            _isScanning.value = false
        }
    }

    /**
     * Recursively traverses folders, respecting .nomedia exclusions and skipping hidden or sluggish paths.
     */
    private fun scanDirectoryRecursively(
        dir: File,
        foundFiles: MutableList<File>,
        visitedPaths: MutableSet<String>,
        maxDepth: Int = 5,
        currentDepth: Int = 0
    ) {
        if (currentDepth > maxDepth) return
        if (!dir.exists() || !dir.isDirectory || !dir.canRead()) return

        val canonicalPath = try { dir.canonicalPath } catch (e: Exception) { dir.absolutePath }
        if (!visitedPaths.add(canonicalPath)) return

        // Skip hidden directories (starting with ".")
        val name = dir.name
        if (name.startsWith(".")) return

        // Skip standard high-volume Android/system/non-media folders to ensure scanning is lightning-fast
        val lowerName = name.lowercase()
        val ignoredNames = setOf(
            "android", "dcim", "pictures", "alarms", "ringtones", "notifications",
            "podcasts", "screenshots", "whatsapp", "telegram", "facebook", "instagram",
            "movies", "backups", "miui", "samsung", "cache", "logs", "temp", "tmp"
        )
        if (ignoredNames.contains(lowerName)) return

        val files = try {
            dir.listFiles()
        } catch (e: Exception) {
            Log.e("MusicRepository", "Failed to list files in ${dir.absolutePath}: ${e.message}")
            null
        } ?: return
        
        // Scan for .nomedia file
        if (try { files.any { it.isFile && it.name.equals(".nomedia", ignoreCase = true) } } catch (e: Exception) { false }) {
            return
        }

        for (file in files) {
            if (file.isDirectory) {
                scanDirectoryRecursively(file, foundFiles, visitedPaths, maxDepth, currentDepth + 1)
            } else if (file.isFile) {
                if (file.name.startsWith(".")) continue
                val ext = file.extension.lowercase()
                val videoExtensions = listOf("mp4", "mkv", "webm", "3gp", "avi", "mov", "flv", "wmv")
                if (ext == "mp3" || ext == "flac" || ext == "wav" || ext == "ogg" || ext == "m4a" || ext in videoExtensions) {
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
        val baseDirs = mutableListOf<File>()
        try {
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.let { baseDirs.add(it) }
        } catch (e: Exception) {
            Log.d("MusicRepository", "External files dir bypassed: ${e.message}")
        }
        baseDirs.add(context.filesDir)

        val tracksToGenerate = listOf(
            Triple("Aura_Melody_C.wav", 261.63, 4.0), // Middle C
            Triple("Cosmic_Drone_G.wav", 392.00, 4.0),  // G4
            Triple("Midnight_Pulse_E.wav", 329.63, 4.0), // E4
            Triple("Zen_Bell_F_Sharp.wav", 369.99, 4.0) // F#4
        )

        var generatedAny = false
        for (baseDir in baseDirs.distinct()) {
            val storageDir = File(baseDir, "SynthesizedLocalApp")
            try {
                if (!storageDir.exists()) {
                    storageDir.mkdirs()
                }
            } catch (e: Exception) {
                Log.e("MusicRepository", "Failed to create storage directory: ${storageDir.absolutePath}", e)
                continue
            }

            for ((filename, freq, dur) in tracksToGenerate) {
                try {
                    val file = File(storageDir, filename)
                    if (!file.exists()) {
                        writeWavHeaderAndData(file, freq, dur)
                        generatedAny = true
                        
                        // Trigger media scanner so the file shows up in MediaStore if applicable
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(file.absolutePath),
                            arrayOf("audio/wav"),
                            null
                        )
                    }
                } catch (e: Exception) {
                    Log.e("MusicRepository", "Failed to generate track $filename: ${e.message}", e)
                }
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

    suspend fun generateFolderThumbnails(folderPath: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val files = File(folderPath).listFiles() ?: return@withContext
        val cacheDir = context.cacheDir
        val thumbDir = File(cacheDir, "thumbnails").apply { mkdirs() }
        
        for (file in files) {
            if (file.isFile && !file.name.startsWith(".")) {
                val isVideo = Song.isVideoFile(file.absolutePath)
                val id = file.hashCode().toLong()
                val cacheFile = File(thumbDir, "thumb_$id.jpg")
                
                if (!cacheFile.exists()) {
                    try {
                        val retriever = android.media.MediaMetadataRetriever()
                        retriever.setDataSource(file.absolutePath)
                        
                        if (isVideo) {
                            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            val durationUs = (durationStr?.toLongOrNull() ?: 0L) * 1000L
                            val timeUs = if (durationUs > 0) durationUs / 2 else 1000000L
                            val bitmap = retriever.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            
                            try { retriever.release() } catch (e: Exception) {}
                            
                            if (bitmap != null) {
                                val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 180, 180, true)
                                bitmap.recycle()
                                java.io.FileOutputStream(cacheFile).use { fos ->
                                    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, fos)
                                }
                                scaled.recycle()
                            }
                        } else {
                            val artBytes = retriever.embeddedPicture
                            try { retriever.release() } catch (e: Exception) {}
                            
                            if (artBytes != null) {
                                val bitmap = android.graphics.BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                                if (bitmap != null) {
                                    val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 180, 180, true)
                                    bitmap.recycle()
                                    java.io.FileOutputStream(cacheFile).use { fos ->
                                        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, fos)
                                    }
                                    scaled.recycle()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("MusicRepository", "Failed thumbnail generation for ${file.name}: ${e.message}")
                    }
                }
            }
        }
    }
}
