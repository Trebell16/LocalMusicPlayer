package com.example.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.Song

class MediaPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "media_playback_channel"
        const val NOTIFICATION_ID = 101

        const val ACTION_PLAY_PAUSE = "com.example.playback.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.playback.ACTION_NEXT"
        const val ACTION_PREV = "com.example.playback.ACTION_PREV"
        const val ACTION_STOP = "com.example.playback.ACTION_STOP"
        const val ACTION_SHUFFLE = "com.example.playback.ACTION_SHUFFLE"
        const val ACTION_LOOP = "com.example.playback.ACTION_LOOP"
        const val ACTION_UPDATE_NOTIFICATION = "com.example.playback.ACTION_UPDATE_NOTIFICATION"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = PlaybackManager.instance
        if (manager == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val action = intent?.action
        if (action != null) {
            when (action) {
                ACTION_PLAY_PAUSE -> manager.togglePlayPause()
                ACTION_NEXT -> manager.next()
                ACTION_PREV -> manager.previous()
                ACTION_SHUFFLE -> manager.setShuffle(!manager.isShuffle.value)
                ACTION_LOOP -> {
                    val nextMode = when (manager.loopMode.value) {
                        LoopMode.NO_LOOP -> LoopMode.LOOP_ALL
                        LoopMode.LOOP_ALL -> LoopMode.LOOP_ONE
                        LoopMode.LOOP_ONE -> LoopMode.NO_LOOP
                    }
                    manager.setLoopMode(nextMode)
                }
                ACTION_STOP -> {
                    manager.pause()
                    stopForegroundService()
                    return START_NOT_STICKY
                }
            }
        }

        val song = manager.currentSong.value
        val playing = manager.isPlaying.value
        val shuffle = manager.isShuffle.value
        val loop = manager.loopMode.value

        if (song == null) {
            stopForegroundService()
        } else {
            showNotification(song, playing, shuffle, loop)
        }

        return START_STICKY
    }

    private fun loadThumbnailBitmap(song: Song): android.graphics.Bitmap? {
        try {
            val file = java.io.File(song.absolutePath)
            val id = file.hashCode().toLong()
            val cacheFile = java.io.File(cacheDir, "thumbnails/thumb_$id.jpg")
            if (cacheFile.exists()) {
                return android.graphics.BitmapFactory.decodeFile(cacheFile.absolutePath)
            }

            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(song.absolutePath)
            if (song.isVideo) {
                val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                val durationUs = (durationStr?.toLongOrNull() ?: 0L) * 1000L
                val timeUs = if (durationUs > 0) durationUs / 2 else 1000000L
                val bitmap = retriever.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                try { retriever.release() } catch (e: Exception) {}
                if (bitmap != null) {
                    val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 180, 180, true)
                    bitmap.recycle()
                    val thumbDir = java.io.File(cacheDir, "thumbnails").apply { mkdirs() }
                    java.io.FileOutputStream(cacheFile).use { fos ->
                        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, fos)
                    }
                    return scaled
                }
            } else {
                val artBytes = retriever.embeddedPicture
                try { retriever.release() } catch (e: Exception) {}
                if (artBytes != null) {
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                    if (bitmap != null) {
                        val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 180, 180, true)
                        bitmap.recycle()
                        val thumbDir = java.io.File(cacheDir, "thumbnails").apply { mkdirs() }
                        java.io.FileOutputStream(cacheFile).use { fos ->
                            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, fos)
                        }
                        return scaled
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MediaPlaybackService", "Error loading thumbnail bitmap", e)
        }
        return null
    }

    private fun showNotification(song: Song, isPlaying: Boolean, isShuffle: Boolean, loopMode: LoopMode) {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_PLAY_PAUSE }
        val playPausePendingIntent = PendingIntent.getService(
            this, 1, playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_NEXT }
        val nextPendingIntent = PendingIntent.getService(
            this, 2, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_PREV }
        val prevPendingIntent = PendingIntent.getService(
            this, 3, prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 4, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val shuffleIntent = Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_SHUFFLE }
        val shufflePendingIntent = PendingIntent.getService(
            this, 5, shuffleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val loopIntent = Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_LOOP }
        val loopPendingIntent = PendingIntent.getService(
            this, 6, loopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseText = if (isPlaying) "Pause" else "Play"

        val loopIconRes = when (loopMode) {
            LoopMode.LOOP_ONE -> com.example.R.drawable.ic_loop_one
            else -> com.example.R.drawable.ic_loop
        }
        val loopText = when (loopMode) {
            LoopMode.NO_LOOP -> "No Loop"
            LoopMode.LOOP_ALL -> "Loop All"
            LoopMode.LOOP_ONE -> "Loop One"
        }

        val largeIcon = loadThumbnailBitmap(song)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSubText(song.album)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .setSilent(true)
            .setShowWhen(false)
            .addAction(com.example.R.drawable.ic_shuffle, if (isShuffle) "Shuffle On" else "Shuffle Off", shufflePendingIntent)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevPendingIntent)
            .addAction(playPauseIcon, playPauseText, playPausePendingIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextPendingIntent)
            .addAction(loopIconRes, loopText, loopPendingIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(1, 2, 3)
            )

        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon)
        }

        val notification = builder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Music Playback"
            val descriptionText = "Shows controls for current playing music"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
