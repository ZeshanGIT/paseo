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
  @Volatile private var activeVoiceOverride: WatchVoice? = null
  @Volatile private var terminalVoiceOverride: WatchVoice? = null
  @Volatile private var suppressedVoiceId: String? = null
  @Volatile private var captureControllerVoiceId: String? = null
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

  fun voicePhase(voiceId: String, phase: WatchVoicePhase) {
    val state = mutableState.value
    val snapshot = state.snapshot ?: return
    val voice = activeVoiceOverride?.takeIf { it.voiceId == voiceId }
      ?: snapshot.voice?.takeIf { it.voiceId == voiceId }
      ?: return
    val active = voice.copy(phase = phase)
    activeVoiceOverride = active
    mutableState.value = state.copy(
      snapshot = snapshot.copy(voice = active),
      loading = phase == WatchVoicePhase.UPLOADING || phase == WatchVoicePhase.TRANSCRIBING,
      error = null,
    )
  }

  fun beginVoice(voiceId: String) {
    val active = WatchVoice(voiceId, WatchVoicePhase.RECORDING, null, null, null)
    activeVoiceOverride = active
    terminalVoiceOverride = null
    suppressedVoiceId = null
    captureControllerVoiceId = voiceId
    val state = mutableState.value
    val snapshot = state.snapshot ?: return
    mutableState.value = state.copy(
      snapshot = snapshot.copy(
        voice = active,
      ),
      loading = false,
      error = null,
    )
  }

  fun clearVoice(voiceId: String?) {
    activeVoiceOverride = null
    terminalVoiceOverride = null
    suppressedVoiceId = voiceId
    captureControllerVoiceId = null
    val state = mutableState.value
    val snapshot = state.snapshot ?: return
    mutableState.value = state.copy(
      snapshot = snapshot.copy(
        voice = snapshot.voice?.takeUnless { voiceId == null || it.voiceId == voiceId },
      ),
      loading = false,
      error = null,
    )
  }

  fun voiceError(voiceId: String, code: WatchVoiceErrorCode, message: String) {
    val state = mutableState.value
    val snapshot = state.snapshot ?: return
    val voice = snapshot.voice?.takeIf { it.voiceId == voiceId }
      ?: WatchVoice(voiceId, WatchVoicePhase.ERROR, null, code, message)
    val terminal = voice.copy(
      phase = WatchVoicePhase.ERROR,
      errorCode = code,
      errorMessage = message,
    )
    terminalVoiceOverride = terminal
    activeVoiceOverride = null
    suppressedVoiceId = null
    captureControllerVoiceId = null
    mutableState.value = state.copy(
      snapshot = snapshot.copy(
        voice = terminal,
      ),
      loading = false,
      error = null,
    )
  }

  /** Activity/process recreation has no AudioRecord controller to resume an in-flight capture. */
  fun abandonActiveVoice() {
    captureControllerVoiceId = null
    val state = mutableState.value
    val source = activeVoiceOverride ?: state.snapshot?.voice
    activeVoiceOverride = null
    if (source?.phase !in listOf(WatchVoicePhase.RECORDING, WatchVoicePhase.PREVIEW, WatchVoicePhase.UPLOADING)) return
    val interrupted = interruptedVoice(source!!)
    terminalVoiceOverride = interrupted
    state.snapshot?.let { snapshot ->
      mutableState.value = state.copy(
        snapshot = snapshot.copy(voice = interrupted),
        loading = false,
        error = null,
      )
    }
  }

  private fun reconcile(previous: WatchSnapshot?, incoming: WatchSnapshot): WatchSnapshot {
    val priorVoice = previous?.voice
    val nextVoice = incoming.voice
    if (
      activeVoiceOverride?.voiceId == nextVoice?.voiceId &&
        nextVoice?.phase in listOf(WatchVoicePhase.CONFIRMATION, WatchVoicePhase.ERROR)
    ) {
      activeVoiceOverride = null
      captureControllerVoiceId = null
    }
    val orphanActiveSnapshot = activeVoiceOverride == null &&
      terminalVoiceOverride == null &&
      captureControllerVoiceId == null
    val reconciledVoice = reconcileVoiceState(
      priorVoice,
      nextVoice,
      activeVoiceOverride,
      terminalVoiceOverride,
      suppressedVoiceId,
      orphanActiveSnapshot,
    )
    if (
      orphanActiveSnapshot &&
        nextVoice?.phase in listOf(WatchVoicePhase.RECORDING, WatchVoicePhase.UPLOADING) &&
        reconciledVoice?.phase == WatchVoicePhase.ERROR
    ) terminalVoiceOverride = reconciledVoice
    val withVoice = incoming.copy(voice = reconciledVoice)
    val priorConversation = previous?.conversation ?: return withVoice
    val nextConversation = incoming.conversation ?: return withVoice
    if (
      priorConversation.workspaceId != nextConversation.workspaceId ||
        priorConversation.agentId != nextConversation.agentId
    ) return withVoice

    val pending = priorConversation.messages.filter(WatchMessage::pending)
    if (pending.isEmpty()) return withVoice
    val priorCanonicalIds = priorConversation.messages.filterNot(WatchMessage::pending).mapTo(mutableSetOf()) { it.id }
    val unmatched = pending.filterNot { optimistic ->
      nextConversation.messages.any { canonical ->
        canonical.role == WatchMessageRole.USER &&
          canonical.text == optimistic.text &&
          (canonical.id !in priorCanonicalIds || canonical.timestamp >= optimistic.timestamp - 30_000L)
      }
    }
    return withVoice.copy(
      conversation = nextConversation.copy(messages = nextConversation.messages + unmatched),
    )
  }
}

internal fun reconcileVoiceState(
  previous: WatchVoice?,
  incoming: WatchVoice?,
  activeOverride: WatchVoice?,
  terminalOverride: WatchVoice?,
  suppressedVoiceId: String?,
  orphanActiveSnapshot: Boolean = false,
): WatchVoice? {
  if (suppressedVoiceId != null && incoming?.voiceId == suppressedVoiceId) return null
  terminalOverride?.let { return it }
  activeOverride?.let { active ->
    if (
      incoming?.voiceId == active.voiceId &&
        incoming.phase in listOf(WatchVoicePhase.CONFIRMATION, WatchVoicePhase.ERROR)
    ) return incoming
    return active
  }
  if (
    orphanActiveSnapshot &&
      incoming?.phase in listOf(WatchVoicePhase.RECORDING, WatchVoicePhase.UPLOADING)
  ) return interruptedVoice(requireNotNull(incoming))
  return when {
    previous?.voiceId != incoming?.voiceId -> incoming
    previous?.phase == WatchVoicePhase.UPLOADING && incoming?.phase == WatchVoicePhase.RECORDING -> previous
    previous?.phase == WatchVoicePhase.TRANSCRIBING && incoming?.phase in listOf(WatchVoicePhase.RECORDING, WatchVoicePhase.UPLOADING) -> previous
    previous?.phase == WatchVoicePhase.ERROR && incoming?.phase in listOf(WatchVoicePhase.RECORDING, WatchVoicePhase.UPLOADING) -> previous
    else -> incoming
  }
}

private fun interruptedVoice(voice: WatchVoice) = voice.copy(
  phase = WatchVoicePhase.ERROR,
  errorCode = WatchVoiceErrorCode.AUDIO_INTERRUPTED,
  errorMessage = "Recording interrupted",
)
