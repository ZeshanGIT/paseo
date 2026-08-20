package sh.paseo.wearbridge

import android.content.Intent
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

private const val VOICE_EXPIRY_MS = 60_000L
private const val MAX_VOICE_BYTES = 2_000_000L
internal const val MAX_VOICE_CHUNK_BYTES = 64 * 1024

data class PendingVoiceTransfer(
  val nodeId: String,
  val file: File,
  val expiresAtMs: Long,
  @Volatile var uploadedBytes: Long? = null,
  @Volatile var verifiedBytes: Long? = null,
  val receiving: AtomicBoolean = AtomicBoolean(false),
)

internal data class VoiceTransferChunk(
  val bytes: ByteArray,
  val eof: Boolean,
)

internal fun readVerifiedVoiceTransferChunk(
  transfer: PendingVoiceTransfer,
  offset: Long,
  maxBytes: Int,
): VoiceTransferChunk {
  val verifiedBytes = transfer.verifiedBytes ?: throw IllegalStateException("Voice transfer is not verified")
  require(offset in 0..verifiedBytes) { "Voice chunk offset is out of range" }
  require(maxBytes in 1..MAX_VOICE_CHUNK_BYTES) { "Voice chunk size is out of range" }
  if (transfer.file.length() != verifiedBytes) throw IllegalStateException("Voice transfer changed after verification")
  if (offset == verifiedBytes) return VoiceTransferChunk(ByteArray(0), eof = true)

  val bytesToRead = minOf(maxBytes.toLong(), verifiedBytes - offset).toInt()
  val buffer = ByteArray(bytesToRead)
  RandomAccessFile(transfer.file, "r").use { input ->
    input.seek(offset)
    input.readFully(buffer)
  }
  return VoiceTransferChunk(buffer, eof = offset + bytesToRead == verifiedBytes)
}

/** Private, bounded, one-use channel state. Raw audio never leaves app cache. */
object VoiceTransferRegistry {
  private val pending = ConcurrentHashMap<String, PendingVoiceTransfer>()

  fun register(context: android.content.Context, nodeId: String, voiceId: String) {
    cancel(voiceId)
    val file = File(context.cacheDir, "wear-voice-$voiceId.pcm")
    pending[voiceId] = PendingVoiceTransfer(nodeId, file, System.currentTimeMillis() + VOICE_EXPIRY_MS)
  }

  fun claim(voiceId: String, nodeId: String): PendingVoiceTransfer? {
    val transfer = pending[voiceId] ?: return null
    if (transfer.nodeId != nodeId || System.currentTimeMillis() > transfer.expiresAtMs) {
      cancel(voiceId)
      return null
    }
    return transfer.takeIf { it.receiving.compareAndSet(false, true) }
  }

  fun markUploaded(voiceId: String, byteLength: Long) {
    pending[voiceId]?.uploadedBytes = byteLength
  }

  fun finish(voiceId: String, byteLength: Long, sha256: String) {
    val transfer = pending[voiceId] ?: throw IllegalStateException("Unknown voice transfer")
    if (System.currentTimeMillis() > transfer.expiresAtMs || transfer.uploadedBytes != byteLength || byteLength > MAX_VOICE_BYTES) {
      cancel(voiceId)
      throw IllegalStateException("Voice transfer is incomplete")
    }
    val actual = MessageDigest.getInstance("SHA-256").digest(transfer.file.readBytes()).joinToString("") { "%02x".format(it) }
    if (!actual.equals(sha256, ignoreCase = true)) {
      cancel(voiceId)
      throw IllegalStateException("Voice transfer checksum failed")
    }
    transfer.verifiedBytes = byteLength
  }

  internal fun readChunk(voiceId: String, offset: Long, maxBytes: Int): VoiceTransferChunk {
    val transfer = pending[voiceId] ?: throw IllegalStateException("Unknown voice transfer")
    if (System.currentTimeMillis() > transfer.expiresAtMs) {
      cancel(voiceId)
      throw IllegalStateException("Voice transfer expired")
    }
    return readVerifiedVoiceTransferChunk(transfer, offset, maxBytes)
  }

  fun cancel(voiceId: String) {
    pending.remove(voiceId)?.file?.delete()
  }
}

class PaseoWearListenerService : WearableListenerService() {
  private val voiceExecutor = Executors.newSingleThreadExecutor()
  override fun onMessageReceived(event: MessageEvent) {
    if (event.path != PaseoWearBridge.REQUEST_PATH) return
    val command = event.data.toString(Charsets.UTF_8)
    PendingWatchCommandStore.enqueue(this, event.sourceNodeId, command)
    val kind = commandKind(command)
    if (shouldForeground(kind)) {
      packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (kind == "OPEN_ON_PHONE") {
          WearBridgeRuntimeState.foregroundLaunch.markListenerLaunchRequested()
        }
        try {
          startActivity(launch)
        } catch (error: Exception) {
          if (kind == "OPEN_ON_PHONE") {
            WearBridgeRuntimeState.foregroundLaunch.markListenerLaunchFailed()
          }
          throw error
        }
      }
    }
    val intent = Intent(PaseoWearBridge.COMMAND_ACTION).apply {
      setPackage(packageName)
    }
    sendBroadcast(intent)
  }

  private fun commandKind(command: String): String? =
    runCatching { JSONObject(command).optString("kind") }.getOrNull()

  private fun shouldForeground(kind: String?): Boolean {
    return kind == "OPEN_ON_PHONE" ||
      kind == "START_VOICE_INPUT" ||
      kind == "STOP_VOICE_INPUT" ||
      kind == "CANCEL_VOICE_INPUT" ||
      kind == "SEND_TRANSCRIPT"
  }

  override fun onChannelOpened(channel: ChannelClient.Channel) {
    val voiceId = voiceIdFromChannelPath(channel.path) ?: return
    val transfer = VoiceTransferRegistry.claim(voiceId, channel.nodeId)
    if (transfer == null) {
      Log.w("PaseoWearBridge", "Rejected voice channel voiceId=$voiceId node=${channel.nodeId}")
      return
    }
    Log.i("PaseoWearBridge", "Receiving voice channel voiceId=$voiceId node=${channel.nodeId}")
    voiceExecutor.execute {
      try {
        Tasks.await(Wearable.getChannelClient(this).getInputStream(channel)).use { input ->
          transfer.file.outputStream().use { output ->
            val buffer = ByteArray(16 * 1024)
            var written = 0L
            while (true) {
              val read = input.read(buffer)
              if (read < 0) break
              written += read
              if (written > MAX_VOICE_BYTES || System.currentTimeMillis() > transfer.expiresAtMs) {
                throw IllegalStateException("Voice transfer exceeded its bounds")
              }
              output.write(buffer, 0, read)
            }
            VoiceTransferRegistry.markUploaded(voiceId, written)
            Log.i("PaseoWearBridge", "Received voice channel voiceId=$voiceId bytes=$written")
          }
        }
      } catch (error: Exception) {
        Log.e("PaseoWearBridge", "Voice channel failed voiceId=$voiceId", error)
        VoiceTransferRegistry.cancel(voiceId)
      }
    }
  }
}

internal fun voiceIdFromChannelPath(path: String): String? {
  if (!path.startsWith(PaseoWearBridge.VOICE_CHANNEL_PREFIX)) return null
  return path.removePrefix(PaseoWearBridge.VOICE_CHANNEL_PREFIX).takeIf { it.isNotBlank() }
}
