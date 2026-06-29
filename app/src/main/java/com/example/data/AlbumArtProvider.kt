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

        val cacheDir = context?.cacheDir ?: return null
        val thumbDir = File(cacheDir, "thumbnails").apply { mkdirs() }
        val id = file.hashCode().toLong()
        val cacheFile = File(thumbDir, "thumb_$id.jpg")

        if (cacheFile.exists()) {
            return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        try {
            val isVideo = Song.isVideoFile(file.absolutePath)
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)

            if (isVideo) {
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val durationUs = (durationStr?.toLongOrNull() ?: 0L) * 1000L
                val timeUs = if (durationUs > 0) durationUs / 2 else 1000000L
                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                try {
                    retriever.release()
                } catch (ex: Exception) {}

                if (bitmap != null) {
                    val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 180, 180, true)
                    bitmap.recycle()
                    FileOutputStream(cacheFile).use { fos ->
                        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, fos)
                    }
                    scaled.recycle()
                    return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
                }
            } else {
                val artBytes = retriever.embeddedPicture
                try {
                    retriever.release()
                } catch (ex: Exception) {}

                if (artBytes != null) {
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                    if (bitmap != null) {
                        val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 180, 180, true)
                        bitmap.recycle()
                        FileOutputStream(cacheFile).use { fos ->
                            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, fos)
                        }
                        scaled.recycle()
                        return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AlbumArtProvider", "Error extracting artwork for ${file.name}", e)
        }
        return null
    }
}
