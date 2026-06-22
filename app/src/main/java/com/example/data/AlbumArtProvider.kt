package com.example.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import android.media.MediaMetadataRetriever
import android.util.Log

class AlbumArtProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = "image/jpeg"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val path = uri.getQueryParameter("path") ?: return null
        val file = File(path)
        if (!file.exists()) return null

        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val artBytes = retriever.embeddedPicture
            try {
                retriever.release()
            } catch (ex: Exception) {}

            if (artBytes != null) {
                // Write to a temporary file in context's cache directory or use pipe
                val cacheDir = context?.cacheDir ?: return null
                val cacheFile = File(cacheDir, "art_${file.name.hashCode()}.jpg")
                if (!cacheFile.exists()) {
                    FileOutputStream(cacheFile).use { it.write(artBytes) }
                }
                return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        } catch (e: Exception) {
            Log.e("AlbumArtProvider", "Error extracting artwork for ${file.name}", e)
        }
        return null
    }
}
