package sh.paseo.glance.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WatchUiState(
  val snapshot: WatchSnapshot? = null,
  val loading: Boolean = true,
  val error: String? = null,
)

/** Process-local UI cache with optimistic outgoing messages reconciled against phone snapshots. */
object WatchStore {
  private val mutableState = MutableStateFlow(WatchUiState())
  val state = mutableState.asStateFlow()

  fun loading() {
    mutableState.value = mutableState.value.copy(loading = true, error = null)
  }

  fun response(response: WatchResponse) {
    val previous = mutableState.value
    mutableState.value = previous.copy(
      snapshot = response.snapshot?.let { reconcile(previous.snapshot, it) } ?: previous.snapshot,
      loading = false,
      error = response.error.takeUnless { response.ok },
    )
  }

  fun snapshot(snapshot: WatchSnapshot) {
    val previous = mutableState.value
    mutableState.value = WatchUiState(
      snapshot = reconcile(previous.snapshot, snapshot),
      loading = false,
    )
  }

  fun optimisticMessage(workspaceId: String, agentId: String, text: String) {
    val state = mutableState.value
    val snapshot = state.snapshot ?: return
    val conversation = snapshot.conversation?.takeIf {
      it.workspaceId == workspaceId && it.agentId == agentId
    } ?: return
    val now = System.currentTimeMillis()
    val optimistic = WatchMessage(
      id = "pending:$now:${conversation.messages.size}",
      role = WatchMessageRole.USER,
      text = text,
      timestamp = now,
      sequence = (conversation.messages.maxOfOrNull { it.sequence } ?: 0L) + 1L,
      pending = true,
    )
    mutableState.value = state.copy(
      snapshot = snapshot.copy(
        conversation = conversation.copy(
          messages = conversation.messages + optimistic,
          currentStatus = null,
          summary = null,
        ),
      ),
      loading = true,
      error = null,
    )
  }

  fun failure(message: String) {
    val state = mutableState.value
    val snapshot = state.snapshot
    val conversation = snapshot?.conversation
    mutableState.value = state.copy(
      snapshot = if (snapshot != null && conversation != null) snapshot.copy(
        conversation = conversation.copy(
          messages = conversation.messages.map { if (it.pending) it.copy(pending = false) else it },
        ),
      ) else snapshot,
      loading = false,
      error = message,
    )
  }

  private fun reconcile(previous: WatchSnapshot?, incoming: WatchSnapshot): WatchSnapshot {
    val priorConversation = previous?.conversation ?: return incoming
    val nextConversation = incoming.conversation ?: return incoming
    if (
      priorConversation.workspaceId != nextConversation.workspaceId ||
        priorConversation.agentId != nextConversation.agentId
    ) return incoming

    val pending = priorConversation.messages.filter(WatchMessage::pending)
    if (pending.isEmpty()) return incoming
    val priorCanonicalIds = priorConversation.messages.filterNot(WatchMessage::pending).mapTo(mutableSetOf()) { it.id }
    val unmatched = pending.filterNot { optimistic ->
      nextConversation.messages.any { canonical ->
        canonical.role == WatchMessageRole.USER &&
          canonical.text == optimistic.text &&
          (canonical.id !in priorCanonicalIds || canonical.timestamp >= optimistic.timestamp - 30_000L)
      }
    }
    return incoming.copy(
      conversation = nextConversation.copy(messages = nextConversation.messages + unmatched),
    )
  }
}
