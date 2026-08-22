package sh.paseo.glance.wear

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Plays the last captured PCM buffer locally on the watch for microphone verification. */
internal class VoicePreviewPlayer {
  private var track: AudioTrack? = null

  @Synchronized
  fun play(pcm: ByteArray) {
    require(pcm.isNotEmpty()) { "Voice preview is empty" }
    stop()

    val preview = AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build(),
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setSampleRate(SAMPLE_RATE)
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build(),
      )
      .setTransferMode(AudioTrack.MODE_STATIC)
      .setBufferSizeInBytes(maxOf(pcm.size, 4_096))
      .build()

    try {
      check(preview.write(pcm, 0, pcm.size) == pcm.size) { "Could not load voice preview" }
      preview.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
        override fun onMarkerReached(audioTrack: AudioTrack) {
          synchronized(this@VoicePreviewPlayer) {
            if (track !== audioTrack) return
            track = null
            runCatching { audioTrack.stop() }
            audioTrack.release()
          }
        }

        override fun onPeriodicNotification(audioTrack: AudioTrack) = Unit
      })
      check(preview.setNotificationMarkerPosition(maxOf(1, pcm.size / BYTES_PER_FRAME)) == AudioTrack.SUCCESS) {
        "Could not schedule voice preview"
      }
      track = preview
      preview.play()
    } catch (error: Throwable) {
      preview.release()
      throw error
    }
  }

  @Synchronized
  fun stop() {
    track?.let { active ->
      runCatching { active.stop() }
      active.release()
    }
    track = null
  }

  private companion object {
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_FRAME = 2
  }
}
