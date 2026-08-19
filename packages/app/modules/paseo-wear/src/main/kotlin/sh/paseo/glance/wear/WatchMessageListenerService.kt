package sh.paseo.glance.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WatchMessageListenerService : WearableListenerService() {
  override fun onMessageReceived(event: MessageEvent) {
    if (event.path != WatchProtocol.RESPONSE_PATH) return
    WatchProtocol.decodeResponse(event.data)?.let(WatchStore::response)
  }

  override fun onDataChanged(events: DataEventBuffer) {
    for (event in events) {
      if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != WatchProtocol.SNAPSHOT_PATH) continue
      val value = DataMapItem.fromDataItem(event.dataItem).dataMap.getString("snapshot") ?: continue
      WatchProtocol.decodeSnapshotJson(value)?.let(WatchStore::snapshot)
    }
  }
}
