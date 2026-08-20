package sh.paseo.glance.wear

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import java.util.UUID
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

class WatchTransport(context: Context) {
  private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context)
  private val messageClient: MessageClient = Wearable.getMessageClient(context)

  suspend fun send(command: WatchCommand): Result<Unit> = runCatching {
    val capabilityNodes = reachableNodes()
    check(capabilityNodes.isNotEmpty()) { "Phone unavailable" }
    capabilityNodes.forEach { node -> messageClient.sendMessage(node.id, WatchProtocol.REQUEST_PATH, WatchProtocol.encode(command)).await() }
  }

  suspend fun reachableNodes(): Set<Node> = withTimeoutOrNull(2_500) {
    capabilityClient.getCapability(WatchProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
  }.orEmpty()

  fun request(kind: CommandKind, projectId: String? = null, workspaceId: String? = null, agentId: String? = null, text: String? = null, voiceId: String? = null, format: String? = null, byteLength: Int? = null, sha256: String? = null): WatchCommand =
    WatchCommand(id = UUID.randomUUID().toString(), kind = kind, projectId = projectId, workspaceId = workspaceId, agentId = agentId, text = text, voiceId = voiceId, format = format, byteLength = byteLength, sha256 = sha256)
}
