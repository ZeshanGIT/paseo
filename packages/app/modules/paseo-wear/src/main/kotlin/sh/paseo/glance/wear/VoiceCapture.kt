package sh.paseo.glance.wear

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.google.android.gms.wearable.Wearable
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Bounded, ephemeral microphone capture. Audio never leaves the paired-phone Data Layer. */
class VoiceCapture(private val context: Context) {
  private val controller = VoiceCaptureController()
  companion object {
    const val FORMAT = "pcm_s16le_16000_mono"
    private const val SAMPLE_RATE = 16_000
    private const val MAX_BYTES = 1_920_000 // 60 seconds of PCM16 mono.
    private const val MAX_CAPTURE_MS = 45_000L
  }

  data class Result(val byteLength: Int, val sha256: String)

  fun finish(): Boolean = controller.finish()

  fun cancel(): Boolean = controller.cancel()

  suspend fun recordAndUpload(voiceId: String, nodeId: String, isCurrent: () -> Boolean): Result = withContext(Dispatchers.IO) {
    fun ensureValid() {
      ensureActive()
      if (!isCurrent() || controller.state() == VoiceCaptureController.State.CANCELED) {
        throw CancellationException("Voice capture canceled")
      }
    }
    val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    check(minBuffer > 0) { "Microphone unavailable" }
    val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 2, 4096))
    check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
    val pcm = ByteArrayOutputStream(minOf(MAX_BYTES, SAMPLE_RATE * 2 * 10))
    try {
      val buffer = ByteArray(4096)
      recorder.startRecording()
      controller.attachRecorderStop { runCatching { recorder.stop() } }
      Log.i("PaseoVoice", "recording voiceId=$voiceId")
      val deadline = System.currentTimeMillis() + MAX_CAPTURE_MS
      while (controller.state() == VoiceCaptureController.State.RECORDING && System.currentTimeMillis() < deadline && pcm.size() < MAX_BYTES) {
        ensureValid()
        val read = recorder.read(buffer, 0, minOf(buffer.size, MAX_BYTES - pcm.size()))
        if (read <= 0) {
          if (controller.state() != VoiceCaptureController.State.RECORDING) break
          error("Audio interrupted")
        }
        pcm.write(buffer, 0, read)
      }
    } finally {
      controller.detachRecorderStop()
      runCatching { recorder.stop() }
      recorder.release()
    }

    ensureValid()
    val bytes = pcm.toByteArray()
    check(bytes.isNotEmpty()) { "No audio captured" }
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    ensureValid()
    val channelClient = Wearable.getChannelClient(context)
    Log.i("PaseoVoice", "captured voiceId=$voiceId bytes=${bytes.size}")
    val channel = withTimeout(10_000) {
      ensureValid()
      channelClient.openChannel(nodeId, "${WatchProtocol.VOICE_CHANNEL_PREFIX}$voiceId").await()
    }
    try {
      ensureValid()
      Log.i("PaseoVoice", "channel opened voiceId=$voiceId")
      withTimeout(10_000) { channelClient.getOutputStream(channel).await() }.use { output ->
        ensureValid()
        output.write(bytes)
        output.flush()
        ensureValid()
      }
    } finally {
      runCatching { withTimeout(5_000) { channelClient.close(channel).await() } }
    }
    ensureValid()
    Result(bytes.size, digest.joinToString("") { "%02x".format(it) })
  }
}
