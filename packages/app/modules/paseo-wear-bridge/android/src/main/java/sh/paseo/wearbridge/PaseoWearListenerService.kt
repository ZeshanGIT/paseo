package sh.paseo.wearbridge

import android.content.Intent
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class PaseoWearListenerService : WearableListenerService() {
  override fun onMessageReceived(event: MessageEvent) {
    if (event.path != PaseoWearBridge.REQUEST_PATH) return
    val command = event.data.toString(Charsets.UTF_8)
    val intent = Intent(PaseoWearBridge.COMMAND_ACTION).apply {
      setPackage(packageName)
      putExtra(PaseoWearBridge.NODE_ID_EXTRA, event.sourceNodeId)
      putExtra(PaseoWearBridge.COMMAND_EXTRA, command)
    }
    sendBroadcast(intent)
  }
}
