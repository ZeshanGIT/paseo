package sh.paseo.glance.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import java.util.UUID
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

private const val WEAR_DEBUG_TAG = "PaseoGlance"

class WatchTransport(context: Context) {
  private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context)
  private val messageClient: MessageClient = Wearable.getMessageClient(context)

  suspend fun send(command: WatchCommand): Result<Unit> {
    val result = runCatching {
      val capabilityNodes = reachableNodes()
      Log.i(
        WEAR_DEBUG_TAG,
        "Sending command kind=${command.kind} id=${command.id} nodes=${capabilityNodes.joinToString { it.id }}",
      )
      check(capabilityNodes.isNotEmpty()) { "Phone unavailable" }
      capabilityNodes.forEach { node -> messageClient.sendMessage(node.id, WatchProtocol.REQUEST_PATH, WatchProtocol.encode(command)).await() }
    }
    result.onSuccess {
      Log.i(WEAR_DEBUG_TAG, "Command sent kind=${command.kind} id=${command.id}")
    }.onFailure { error ->
      Log.w(WEAR_DEBUG_TAG, "Command failed kind=${command.kind} id=${command.id}: ${error.message}")
    }
    return result
  }

  suspend fun reachableNodes(): Set<Node> {
    val nodes = withTimeoutOrNull(2_500) {
      capabilityClient.getCapability(WatchProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
    }.orEmpty()
    Log.i(WEAR_DEBUG_TAG, "Reachable capability=${WatchProtocol.PHONE_CAPABILITY} nodes=${nodes.joinToString { it.id }}")
    return nodes
  }

  fun request(kind: CommandKind, projectId: String? = null, workspaceId: String? = null, agentId: String? = null, text: String? = null, voiceId: String? = null, format: String? = null, byteLength: Int? = null, sha256: String? = null): WatchCommand =
    WatchCommand(id = UUID.randomUUID().toString(), kind = kind, projectId = projectId, workspaceId = workspaceId, agentId = agentId, text = text, voiceId = voiceId, format = format, byteLength = byteLength, sha256 = sha256)
}
