package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

class BackgroundMusicEngine(context: Context) {

  private val appContext = context.applicationContext
  private val musicFile = File(appContext.filesDir, "custom_bg_music.audio")

  private var mediaPlayer: MediaPlayer? = null
  private var currentVolume: Float = 0.45f
  private var isPrepared = false

  fun hasMusic(): Boolean = musicFile.exists() && musicFile.length() > 0L

  fun importMusic(uri: Uri): String? {
    return try {
      val displayName = resolveDisplayName(uri) ?: "musica_fundo.mp3"
      val tempFile = File(appContext.filesDir, "custom_bg_music.tmp")
      appContext.contentResolver.openInputStream(uri)?.use { input ->
        tempFile.outputStream().use { output ->
          input.copyTo(output)
        }
      } ?: return null

      if (tempFile.length() <= 0L) {
        tempFile.delete()
        return null
      }
      stopAndReleasePlayer()
      if (musicFile.exists()) {
        musicFile.delete()
      }
      tempFile.renameTo(musicFile)
      displayName
    } catch (_: Exception) {
      null
    }
  }

  fun startOrResume(volume: Float = currentVolume): Boolean {
    currentVolume = volume.coerceIn(0f, 1f)
    if (!hasMusic()) return false
    return try {
      val existing = mediaPlayer
      if (existing != null && isPrepared) {
        existing.setVolume(currentVolume, currentVolume)
        if (!existing.isPlaying) {
          existing.start()
        }
        return true
      }

      stopAndReleasePlayer()
      val player =
        MediaPlayer().apply {
          setAudioAttributes(
            AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
              .build()
          )
          setDataSource(musicFile.absolutePath)
          isLooping = true
          setVolume(currentVolume, currentVolume)
          setOnErrorListener { mp, _, _ ->
            try {
              mp.release()
            } catch (_: Exception) {}
            if (mediaPlayer === mp) {
              mediaPlayer = null
              isPrepared = false
            }
            true
          }
          prepare()
          isPrepared = true
          start()
        }
      mediaPlayer = player
      true
    } catch (_: Exception) {
      stopAndReleasePlayer()
      false
    }
  }

  fun pause() {
    try {
      mediaPlayer?.let { mp ->
        if (mp.isPlaying) {
          mp.pause()
        }
      }
    } catch (_: Exception) {
      // Ignore
    }
  }

  fun setVolume(volume: Float) {
    currentVolume = volume.coerceIn(0f, 1f)
    try {
      mediaPlayer?.setVolume(currentVolume, currentVolume)
    } catch (_: Exception) {
      // Ignore
    }
  }

  fun clearMusic() {
    stopAndReleasePlayer()
    try {
      if (musicFile.exists()) {
        musicFile.delete()
      }
    } catch (_: Exception) {
      // Ignore
    }
  }

  fun release() {
    stopAndReleasePlayer()
  }

  private fun stopAndReleasePlayer() {
    isPrepared = false
    try {
      mediaPlayer?.let { mp ->
        if (mp.isPlaying) mp.stop()
        mp.release()
      }
    } catch (_: Exception) {
      // Ignore
    } finally {
      mediaPlayer = null
    }
  }

  private fun resolveDisplayName(uri: Uri): String? {
    return try {
      appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
          if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) cursor.getString(idx) else null
          } else {
            null
          }
        }
    } catch (_: Exception) {
      null
    }
  }
}
