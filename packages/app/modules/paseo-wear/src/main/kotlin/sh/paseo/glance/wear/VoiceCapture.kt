package sh.paseo.glance.wear

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.OutputStream
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

  data class Result(val byteLength: Int, val sha256: String, val pcm: ByteArray = ByteArray(0))

  fun finish(): Boolean = controller.finish()

  fun cancel(): Boolean = controller.cancel()

  suspend fun record(
    voiceId: String,
    isCurrent: () -> Boolean,
    onLevel: (Float) -> Unit = {},
    onChunk: (ByteArray, Int) -> Unit = { _, _ -> },
  ): Result = recordInternal(voiceId, isCurrent, onLevel, onChunk, retainPcm = true)

  /** Records and writes each PCM buffer to the paired phone before reading the next one. */
  suspend fun recordToChannel(
    voiceId: String,
    nodeId: String,
    isCurrent: () -> Boolean,
    onLevel: (Float) -> Unit = {},
  ): Result = withContext(Dispatchers.IO) {
    fun ensureValid() {
      ensureActive()
      if (!isCurrent() || controller.state() == VoiceCaptureController.State.CANCELED) {
        throw CancellationException("Voice capture canceled")
      }
    }

    val channelClient = com.google.android.gms.wearable.Wearable.getChannelClient(context)
    val channel = withTimeout(10_000) {
      ensureValid()
      channelClient.openChannel(nodeId, "${WatchProtocol.VOICE_CHANNEL_PREFIX}$voiceId").await()
    }
    var output: OutputStream? = null
    try {
      output = withTimeout(10_000) {
        ensureValid()
        channelClient.getOutputStream(channel).await()
      }
      val stream = requireNotNull(output)
      var unflushedBytes = 0
      Log.i("PaseoVoice", "channel opened voiceId=$voiceId")
      val result = recordInternal(
        voiceId = voiceId,
        isCurrent = { isCurrent() },
        onLevel = onLevel,
        onChunk = { bytes, length ->
          stream.write(bytes, 0, length)
          unflushedBytes += length
          if (unflushedBytes >= 16 * 1024) {
            stream.flush()
            unflushedBytes = 0
          }
        },
        retainPcm = false,
      )
      stream.flush()
      result
    } finally {
      runCatching { output?.close() }
      runCatching { withTimeout(5_000) { channelClient.close(channel).await() } }
    }
  }

  private suspend fun recordInternal(
    voiceId: String,
    isCurrent: () -> Boolean,
    onLevel: (Float) -> Unit,
    onChunk: (ByteArray, Int) -> Unit,
    retainPcm: Boolean,
  ): Result = withContext(Dispatchers.IO) {
    fun ensureValid() {
      ensureActive()
      if (!isCurrent() || controller.state() == VoiceCaptureController.State.CANCELED) {
        throw CancellationException("Voice capture canceled")
      }
    }
    val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    check(minBuffer > 0) { "Microphone unavailable" }
    val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 2, 4096))
    check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
    val pcm = if (retainPcm) ByteArrayOutputStream(minOf(MAX_BYTES, SAMPLE_RATE * 2 * 10)) else null
    val digest = MessageDigest.getInstance("SHA-256")
    var byteLength = 0
    var capturePeak = 0
    var captureSampleCount = 0L
    var captureNonZeroSamples = 0L
    var captureSumSquares = 0.0
    try {
      val buffer = ByteArray(4096)
      recorder.startRecording()
      controller.attachRecorderStop { runCatching { recorder.stop() } }
      Log.i("PaseoVoice", "recording voiceId=$voiceId")
      val deadline = System.currentTimeMillis() + MAX_CAPTURE_MS
      while (controller.state() == VoiceCaptureController.State.RECORDING && System.currentTimeMillis() < deadline && byteLength < MAX_BYTES) {
        ensureValid()
        val read = recorder.read(buffer, 0, minOf(buffer.size, MAX_BYTES - byteLength))
        if (read <= 0) {
          if (controller.state() != VoiceCaptureController.State.RECORDING) break
          error("Audio interrupted")
        }
        var peak = 0
        var bufferSumSquares = 0.0
        var bufferSamples = 0
        var index = 0
        while (index + 1 < read) {
          val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xff))
          val absoluteSample = kotlin.math.abs(sample)
          peak = maxOf(peak, absoluteSample)
          capturePeak = maxOf(capturePeak, absoluteSample)
          captureSampleCount += 1
          bufferSamples += 1
          if (sample != 0) captureNonZeroSamples += 1
          val square = sample.toDouble() * sample.toDouble()
          bufferSumSquares += square
          captureSumSquares += square
          index += 2
        }
        ensureValid()
        val bufferRms = if (bufferSamples == 0) 0f else (
          kotlin.math.sqrt(bufferSumSquares / bufferSamples) / Short.MAX_VALUE
        ).toFloat()
        val peakLevel = peak / 32_767f
        onLevel((bufferRms * 3.5f + peakLevel * 0.1f).coerceIn(0f, 1f))
        onChunk(buffer, read)
        digest.update(buffer, 0, read)
        pcm?.write(buffer, 0, read)
        byteLength += read
      }
    } finally {
      controller.detachRecorderStop()
      runCatching { recorder.stop() }
      recorder.release()
    }

    ensureValid()
    val bytes = pcm?.toByteArray() ?: ByteArray(0)
    check(byteLength > 0) { "No audio captured" }
    ensureValid()
    val rms = if (captureSampleCount == 0L) 0.0 else kotlin.math.sqrt(captureSumSquares / captureSampleCount) / Short.MAX_VALUE
    val nonZeroRatio = if (captureSampleCount == 0L) 0.0 else captureNonZeroSamples.toDouble() / captureSampleCount
    Log.i("PaseoVoice", "captured voiceId=$voiceId bytes=$byteLength peak=$capturePeak rms=${"%.4f".format(rms)} nonZeroRatio=${"%.4f".format(nonZeroRatio)}")
    Result(byteLength, digest.digest().joinToString("") { "%02x".format(it) }, bytes)
  }

  suspend fun upload(
    voiceId: String,
    nodeId: String,
    result: Result,
    isCurrent: () -> Boolean,
  ): Unit = withContext(Dispatchers.IO) {
    fun ensureValid() {
      ensureActive()
      if (!isCurrent()) {
        throw CancellationException("Voice upload canceled")
      }
    }

    val channelClient = com.google.android.gms.wearable.Wearable.getChannelClient(context)
    val channel = withTimeout(10_000) {
      ensureValid()
      channelClient.openChannel(nodeId, "${WatchProtocol.VOICE_CHANNEL_PREFIX}$voiceId").await()
    }
    try {
      ensureValid()
      Log.i("PaseoVoice", "channel opened voiceId=$voiceId")
      withTimeout(10_000) { channelClient.getOutputStream(channel).await() }.use { output ->
        ensureValid()
        output.write(result.pcm)
        output.flush()
        ensureValid()
      }
    } finally {
      runCatching { withTimeout(5_000) { channelClient.close(channel).await() } }
    }
    ensureValid()
  }
}
