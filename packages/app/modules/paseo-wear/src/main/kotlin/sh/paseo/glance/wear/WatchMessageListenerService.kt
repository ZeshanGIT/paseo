package sh.paseo.glance.wear

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

private const val WEAR_DEBUG_TAG = "PaseoGlance"

class WatchMessageListenerService : WearableListenerService() {
  override fun onMessageReceived(event: MessageEvent) {
    if (event.path != WatchProtocol.RESPONSE_PATH) return
    Log.i(WEAR_DEBUG_TAG, "Response received node=${event.sourceNodeId} bytes=${event.data.size}")
    val response = WatchProtocol.decodeResponse(event.data)
    if (response == null) {
      Log.e(WEAR_DEBUG_TAG, "Response decode failed")
      return
    }
    Log.i(
      WEAR_DEBUG_TAG,
      "Response decoded id=${response.id} ok=${response.ok} connection=${response.snapshot?.connection} generation=${response.snapshot?.generation} projects=${response.snapshot?.projects?.size ?: -1} sessions=${response.snapshot?.sessions?.size ?: -1}",
    )
    WatchStore.response(response)
  }

  override fun onDataChanged(events: DataEventBuffer) {
    for (event in events) {
      if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != WatchProtocol.SNAPSHOT_PATH) continue
      val value = DataMapItem.fromDataItem(event.dataItem).dataMap.getString("snapshot") ?: continue
      Log.i(WEAR_DEBUG_TAG, "Snapshot data received bytes=${value.toByteArray().size}")
      val snapshot = WatchProtocol.decodeSnapshotJson(value)
      if (snapshot == null) {
        Log.e(WEAR_DEBUG_TAG, "Snapshot decode failed")
        continue
      }
      Log.i(
        WEAR_DEBUG_TAG,
        "Snapshot decoded connection=${snapshot.connection} generation=${snapshot.generation} projects=${snapshot.projects.size} sessions=${snapshot.sessions.size}",
      )
      WatchStore.snapshot(snapshot)
    }
  }
}
