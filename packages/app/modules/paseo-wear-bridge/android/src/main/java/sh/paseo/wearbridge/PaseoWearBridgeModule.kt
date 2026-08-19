package sh.paseo.wearbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

object PaseoWearBridge {
  const val CAPABILITY = "paseo_glance_bridge_v1"
  const val REQUEST_PATH = "/paseo-glance/request"
  const val RESPONSE_PATH = "/paseo-glance/response"
  const val SNAPSHOT_PATH = "/paseo-glance/snapshot"
  const val COMMAND_ACTION = "sh.paseo.wearbridge.COMMAND"
  const val NODE_ID_EXTRA = "nodeId"
  const val COMMAND_EXTRA = "command"
}

class PaseoWearBridgeModule : Module() {
  private var receiver: BroadcastReceiver? = null

  override fun definition() = ModuleDefinition {
    Name("PaseoWearBridge")
    Events("onWatchCommand")

    OnCreate {
      val context = appContext.reactContext ?: return@OnCreate
      Wearable.getCapabilityClient(context).addLocalCapability(PaseoWearBridge.CAPABILITY)
      val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
          val nodeId = intent.getStringExtra(PaseoWearBridge.NODE_ID_EXTRA) ?: return
          val command = intent.getStringExtra(PaseoWearBridge.COMMAND_EXTRA) ?: return
          sendEvent("onWatchCommand", mapOf("nodeId" to nodeId, "command" to command))
        }
      }
      receiver = commandReceiver
      val filter = IntentFilter(PaseoWearBridge.COMMAND_ACTION)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
      } else {
        context.registerReceiver(commandReceiver, filter)
      }
    }

    OnDestroy {
      val context = appContext.reactContext
      val registeredReceiver = receiver
      if (context != null && registeredReceiver != null) context.unregisterReceiver(registeredReceiver)
      receiver = null
    }

    AsyncFunction("sendResponse") { nodeId: String, response: String ->
      val context = appContext.reactContext ?: throw IllegalStateException("Paseo Wear Bridge is unavailable")
      Tasks.await(Wearable.getMessageClient(context).sendMessage(nodeId, PaseoWearBridge.RESPONSE_PATH, response.toByteArray()))
      Unit
    }

    AsyncFunction("publishSnapshot") { snapshot: String ->
      val context = appContext.reactContext ?: throw IllegalStateException("Paseo Wear Bridge is unavailable")
      val request = PutDataMapRequest.create(PaseoWearBridge.SNAPSHOT_PATH)
      request.dataMap.putString("snapshot", snapshot)
      request.dataMap.putLong("updatedAt", System.currentTimeMillis())
      Tasks.await(Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()))
      Unit
    }
  }
}
