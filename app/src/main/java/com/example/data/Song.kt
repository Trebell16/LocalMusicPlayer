package com.example.data

import android.net.Uri

data class Song(
    val id: Long,
    val absolutePath: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val size: Long,
    val folderName: String,
    val folderPath: String,
    val albumArtUri: Uri? = null
) {
    val isVideo: Boolean
        get() = isVideoFile(absolutePath)

    companion object {
        fun isVideoFile(path: String): Boolean {
            val ext = path.substringAfterLast('.', "").lowercase()
            return ext in listOf("mp4", "mkv", "webm", "3gp", "avi", "mov", "flv", "wmv")
        }
    }
}
