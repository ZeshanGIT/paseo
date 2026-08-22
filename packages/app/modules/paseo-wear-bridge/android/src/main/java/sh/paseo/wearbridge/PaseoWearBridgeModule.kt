package sh.paseo.wearbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Base64
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import org.json.JSONObject

private const val WEAR_DEBUG_TAG = "PaseoWearBridge"

object PaseoWearBridge {
  const val CAPABILITY = "paseo_glance_bridge_v1"
  const val REQUEST_PATH = "/paseo-glance/request"
  const val RESPONSE_PATH = "/paseo-glance/response"
  const val SNAPSHOT_PATH = "/paseo-glance/snapshot"
  const val COMMAND_ACTION = "sh.paseo.wearbridge.COMMAND"
  const val NODE_ID_EXTRA = "nodeId"
  const val COMMAND_EXTRA = "command"
  const val VOICE_CHANNEL_PREFIX = "/paseo-glance/voice/v1/"
  const val OPEN_ROUTE_EXTRA = "sh.paseo.wearbridge.OPEN_ROUTE"
}

class PaseoWearBridgeModule : Module() {
  private var receiver: BroadcastReceiver? = null
  private var draining = false

  private fun drainPendingCommands(context: Context) {
    if (!WearBridgeRuntimeState.observing || draining) return
    draining = true
    try {
      while (WearBridgeRuntimeState.observing) {
        val queued = PendingWatchCommandStore.peek(context) ?: break
        sendEvent(
          "onWatchCommand",
          mapOf(
            "deliveryId" to queued.deliveryId,
            "nodeId" to queued.nodeId,
            "command" to queued.command,
          ),
        )
        PendingWatchCommandStore.acknowledge(context, queued.deliveryId)
      }
    } finally {
      draining = false
    }
  }

  override fun definition() = ModuleDefinition {
    Name("PaseoWearBridge")
    Events("onWatchCommand")

    OnCreate {
      val context = appContext.reactContext ?: return@OnCreate
      Log.i(WEAR_DEBUG_TAG, "Bridge module created capability=${PaseoWearBridge.CAPABILITY}")
      Wearable.getCapabilityClient(context).addLocalCapability(PaseoWearBridge.CAPABILITY)
        .addOnSuccessListener {
          Log.i(WEAR_DEBUG_TAG, "Local capability registered=${PaseoWearBridge.CAPABILITY}")
        }
        .addOnFailureListener { error ->
          Log.e(WEAR_DEBUG_TAG, "Local capability registration failed", error)
        }
      val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
          drainPendingCommands(receiverContext)
        }
      }
      receiver = commandReceiver
      val filter = IntentFilter(PaseoWearBridge.COMMAND_ACTION)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
      } else {
        context.registerReceiver(commandReceiver, filter)
      }
      Log.i(WEAR_DEBUG_TAG, "Command receiver registered")
    }

    OnStartObserving {
      WearBridgeRuntimeState.observing = true
      Log.i(WEAR_DEBUG_TAG, "Bridge observing started")
      appContext.reactContext?.let(::drainPendingCommands)
    }

    OnStopObserving {
      WearBridgeRuntimeState.observing = false
    }

    OnActivityEntersForeground {
      appContext.reactContext?.let(::drainPendingCommands)
    }

    OnDestroy {
      val context = appContext.reactContext
      val registeredReceiver = receiver
      if (context != null && registeredReceiver != null) context.unregisterReceiver(registeredReceiver)
      receiver = null
      WearBridgeRuntimeState.observing = false
    }

    AsyncFunction("sendResponse") { nodeId: String, response: String ->
      val context = appContext.reactContext ?: throw IllegalStateException("Paseo Wear Bridge is unavailable")
      Log.i(WEAR_DEBUG_TAG, "Sending response node=$nodeId bytes=${response.toByteArray().size}")
      Tasks.await(Wearable.getMessageClient(context).sendMessage(nodeId, PaseoWearBridge.RESPONSE_PATH, response.toByteArray()))
      Unit
    }

    AsyncFunction("publishSnapshot") { snapshot: String ->
      val context = appContext.reactContext ?: throw IllegalStateException("Paseo Wear Bridge is unavailable")
      val json = runCatching { JSONObject(snapshot) }.getOrNull()
      Log.i(
        WEAR_DEBUG_TAG,
        "Publishing snapshot generation=${json?.optLong("generation", -1)} connection=${json?.optString("connection")} projects=${json?.optJSONArray("projects")?.length() ?: -1} sessions=${json?.optJSONArray("sessions")?.length() ?: -1} bytes=${snapshot.toByteArray().size}",
      )
      val request = PutDataMapRequest.create(PaseoWearBridge.SNAPSHOT_PATH)
      request.dataMap.putString("snapshot", snapshot)
      request.dataMap.putLong("updatedAt", System.currentTimeMillis())
      Tasks.await(Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()))
      Unit
    }

    AsyncFunction("registerVoiceTransfer") { nodeId: String, voiceId: String ->
      VoiceTransferRegistry.register(context = appContext.reactContext ?: throw IllegalStateException("Paseo Wear Bridge is unavailable"), nodeId = nodeId, voiceId = voiceId)
      Unit
    }

    AsyncFunction("finishVoiceTransfer") { voiceId: String, byteLength: Long, sha256: String ->
      VoiceTransferRegistry.finish(voiceId, byteLength, sha256)
      Unit
    }

    AsyncFunction("readVoiceTransferChunk") { voiceId: String, offset: Long, maxBytes: Int ->
      val chunk = VoiceTransferRegistry.readChunk(voiceId, offset, maxBytes)
      mapOf(
        "base64" to Base64.encodeToString(chunk.bytes, Base64.NO_WRAP),
        "nextOffset" to offset + chunk.bytes.size,
        "eof" to chunk.eof,
      )
    }

    AsyncFunction("cancelVoiceTransfer") { voiceId: String ->
      VoiceTransferRegistry.cancel(voiceId)
      Unit
    }

    AsyncFunction("openOnPhone") { route: String? ->
      val context = appContext.reactContext ?: throw IllegalStateException("Paseo Wear Bridge is unavailable")
      if (WearBridgeRuntimeState.foregroundLaunch.shouldCoordinatorLaunch()) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
          ?: throw IllegalStateException("Paseo launch activity is unavailable")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (!route.isNullOrBlank()) launch.putExtra(PaseoWearBridge.OPEN_ROUTE_EXTRA, route)
        context.startActivity(launch)
      }
      Unit
    }
  }
}
