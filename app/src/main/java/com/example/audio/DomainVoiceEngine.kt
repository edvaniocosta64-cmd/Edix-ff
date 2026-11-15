package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DomainVoiceEngine(context: Context) : TextToSpeech.OnInitListener {

  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val customAudioFile = File(appContext.filesDir, "custom_domain_voice.audio")

  private var tts: TextToSpeech? = null
  private var mediaPlayer: MediaPlayer? = null
  private var isReady = false
  private var japaneseSupported = false
  private var pendingPhrase: String? = null

  init {
    try {
      tts = TextToSpeech(appContext, this)
    } catch (_: Exception) {
      isReady = false
    }
  }

  override fun onInit(status: Int) {
    if (status == TextToSpeech.SUCCESS) {
      val jaResult = tts?.setLanguage(Locale.JAPAN) ?: TextToSpeech.LANG_NOT_SUPPORTED
      val fallbackResult =
        if (jaResult == TextToSpeech.LANG_MISSING_DATA || jaResult == TextToSpeech.LANG_NOT_SUPPORTED) {
          tts?.setLanguage(Locale.JAPANESE) ?: TextToSpeech.LANG_NOT_SUPPORTED
        } else {
          jaResult
        }
      japaneseSupported =
        fallbackResult != TextToSpeech.LANG_MISSING_DATA &&
          fallbackResult != TextToSpeech.LANG_NOT_SUPPORTED

      try {
        val voices = tts?.voices.orEmpty().filter { it.locale.language == "ja" }
        val preferredVoice =
          voices
            .sortedWith(
              compareByDescending<Voice> { it.quality }
                .thenByDescending {
                  val n = it.name.lowercase(Locale.ROOT)
                  n.contains("jad") || n.contains("jac") || n.contains("male")
                }
                .thenByDescending { !it.isNetworkConnectionRequired }
            )
            .firstOrNull()
        if (preferredVoice != null) {
          tts?.voice = preferredVoice
        }
      } catch (_: Exception) {
        // Ignore if voices query unsupported
      }

      // Natural pitch & cadence (avoid robotic low-pitch distortion)
      tts?.setPitch(0.95f)
      tts?.setSpeechRate(0.90f)
      isReady = true
      pendingPhrase?.let { phrase ->
        pendingPhrase = null
        if (!customAudioFile.exists()) {
          speakJapaneseInternal(phrase)
        }
      }
    }
  }

  fun hasCustomAudio(): Boolean = customAudioFile.exists() && customAudioFile.length() > 0L

  fun importCustomAudio(uri: Uri): String? {
    return try {
      val displayName = resolveDisplayName(uri) ?: "expansao_custom.mp3"
      val tempFile = File(appContext.filesDir, "custom_domain_voice.tmp")
      appContext.contentResolver.openInputStream(uri)?.use { input ->
        tempFile.outputStream().use { output ->
          input.copyTo(output)
        }
      } ?: return null

      if (tempFile.length() <= 0L) {
        tempFile.delete()
        return null
      }
      if (customAudioFile.exists()) {
        customAudioFile.delete()
      }
      tempFile.renameTo(customAudioFile)
      displayName
    } catch (_: Exception) {
      null
    }
  }

  fun clearCustomAudio() {
    try {
      stopMediaPlayer()
      if (customAudioFile.exists()) {
        customAudioFile.delete()
      }
    } catch (_: Exception) {
      // Ignore
    }
  }

  fun playDomainExpansion(enabled: Boolean) {
    if (!enabled) return
    if (hasCustomAudio() && playCustomAudioFile()) {
      return
    }
    playCosmicResonance(isDomain = true)
    speakJapanese("領域展開。無量空処。")
  }

  fun playHollowPurpleBoost(enabled: Boolean) {
    if (!enabled) return
    playCosmicResonance(isDomain = false)
    speakJapanese("虚式、茈。")
  }

  fun playCoolerCue(enabled: Boolean) {
    if (!enabled) return
    speakJapanese("冷却システム起動。")
  }

  private fun playCustomAudioFile(): Boolean {
    return try {
      stopMediaPlayer()
      tts?.stop()
      val player =
        MediaPlayer().apply {
          setAudioAttributes(
            AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
              .build()
          )
          setDataSource(customAudioFile.absolutePath)
          setOnCompletionListener { mp ->
            try {
              mp.release()
            } catch (_: Exception) {}
            if (mediaPlayer === mp) mediaPlayer = null
          }
          setOnErrorListener { mp, _, _ ->
            try {
              mp.release()
            } catch (_: Exception) {}
            if (mediaPlayer === mp) mediaPlayer = null
            true
          }
          prepare()
          start()
        }
      mediaPlayer = player
      true
    } catch (_: Exception) {
      false
    }
  }

  private fun stopMediaPlayer() {
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

  private fun speakJapanese(text: String) {
    if (!isReady) {
      pendingPhrase = text
      return
    }
    speakJapaneseInternal(text)
  }

  private fun speakJapaneseInternal(text: String) {
    try {
      if (japaneseSupported) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "infinit_${System.currentTimeMillis()}")
      }
    } catch (_: Exception) {
      // Safe fallback when TTS service is unavailable
    }
  }

  private fun playCosmicResonance(isDomain: Boolean) {
    scope.launch {
      try {
        val sampleRate = 22050
        val durationSec = if (isDomain) 1.35 else 0.85
        val numSamples = (sampleRate * durationSec).toInt()
        val pcm = ShortArray(numSamples)

        for (i in 0 until numSamples) {
          val t = i.toDouble() / sampleRate
          val envelope =
            (1.0 - exp(-t * 14.0)) * exp(-t * if (isDomain) 2.2 else 3.4)
          val subBass = sin(2.0 * PI * (if (isDomain) 68.0 else 82.0) * t)
          val harmonic = sin(2.0 * PI * (if (isDomain) 272.0 else 328.0) * t) * 0.45
          val shimmer = sin(2.0 * PI * (if (isDomain) 544.0 else 656.0) * t) * 0.25
          val sample = (subBass + harmonic + shimmer) * envelope * 0.28
          pcm[i] =
            (sample * Short.MAX_VALUE)
              .toInt()
              .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
              .toShort()
        }

        val minBuf =
          AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
          )
        val bufferBytes = maxOf(minBuf, pcm.size * 2)

        val track =
          AudioTrack.Builder()
            .setAudioAttributes(
              AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            )
            .setAudioFormat(
              AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(pcm, 0, pcm.size)
        track.play()
        kotlinx.coroutines.delay((durationSec * 1000).toLong() + 150L)
        track.stop()
        track.release()
      } catch (_: Exception) {
        // Safe fallback if audio device is unavailable
      }
    }
  }

  fun release() {
    stopMediaPlayer()
    scope.cancel()
    try {
      tts?.stop()
      tts?.shutdown()
    } catch (_: Exception) {
      // Ignore
    }
  }
}
