package sh.paseo.glance.wear

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

private typealias SendCommand = (CommandKind, String?, String?, String?, String?) -> Unit

private enum class WatchViewMode { GLANCE, FULL }

private sealed interface ConversationRow {
  val key: String
  val sequence: Long
  data class Message(val value: WatchMessage) : ConversationRow {
    override val key = "message:${value.id}"
    override val sequence = value.sequence
  }
  data class Activity(val value: WatchActivity) : ConversationRow {
    override val key = "activity:${value.id}"
    override val sequence = value.sequence
  }
}

private sealed interface Destination {
  data object Home : Destination
  data object Projects : Destination
  data class Project(val projectId: String, val label: String, val parent: Destination) : Destination
  data class Conversation(val workspaceId: String, val parent: Destination) : Destination
}

class MainActivity : ComponentActivity() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private lateinit var transport: WatchTransport
  private var voiceJob: Job? = null
  private var voiceCapture: VoiceCapture? = null
  private var activeVoiceId: String? = null
  private val voiceGeneration = VoiceCaptureGeneration()
  private var voiceTarget: Pair<String, String>? = null
  private val preferences by lazy { getSharedPreferences("paseo_glance", MODE_PRIVATE) }
  private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    val target = voiceTarget
    voiceTarget = null
    if (granted && target != null) startVoice(target.first, target.second) else WatchStore.failure("Microphone permission required")
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    WatchStore.abandonActiveVoice()
    transport = WatchTransport(applicationContext)
    setContent {
      var viewMode by rememberSaveable {
        mutableStateOf(
          runCatching {
            WatchViewMode.valueOf(
              preferences.getString("watch_view_mode", WatchViewMode.GLANCE.name)
                ?: WatchViewMode.GLANCE.name,
            )
          }.getOrDefault(WatchViewMode.GLANCE),
        )
      }
      MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = PaseoPalette.Background) {
          PaseoGlanceScreen(
            send = ::send,
            onMicrophone = ::requestVoice,
            onFinishVoice = ::finishVoice,
            onCancelVoice = ::cancelVoice,
            onSendTranscript = ::sendTranscript,
            viewMode = viewMode,
            onViewModeChange = { next ->
              viewMode = next
              preferences.edit().putString("watch_view_mode", next.name).apply()
            },
          )
        }
      }
    }
    send(CommandKind.GET_SNAPSHOT)
  }

  private fun requestVoice(workspaceId: String, agentId: String) {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      voiceTarget = workspaceId to agentId
      microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    } else startVoice(workspaceId, agentId)
  }

  private fun startVoice(workspaceId: String, agentId: String) {
    if (voiceJob?.isActive == true) return
    val voiceId = java.util.UUID.randomUUID().toString()
    val generation = voiceGeneration.next()
    val capture = VoiceCapture(applicationContext)
    voiceCapture = capture
    activeVoiceId = voiceId
    WatchStore.beginVoice(voiceId)
    voiceJob = scope.launch(Dispatchers.IO) {
      try {
        if (!voiceGeneration.isCurrent(generation)) return@launch
        Log.i("PaseoVoice", "start voiceId=$voiceId")
        val start = transport.request(CommandKind.START_VOICE_INPUT, workspaceId = workspaceId, agentId = agentId, voiceId = voiceId, format = VoiceCapture.FORMAT)
        transport.send(start).onFailure { WatchStore.failure("Phone unavailable") }.getOrElse { return@launch }
        if (!voiceGeneration.isCurrent(generation)) {
          transport.send(transport.request(CommandKind.CANCEL_VOICE_INPUT, voiceId = voiceId))
          return@launch
        }
        val node = transport.reachableNodes().firstOrNull() ?: run { WatchStore.failure("Phone unavailable"); return@launch }
        runCatching { capture.recordAndUpload(voiceId, node.id) { voiceGeneration.isCurrent(generation) } }
          .onSuccess { result ->
            if (!voiceGeneration.isCurrent(generation)) return@onSuccess
            Log.i("PaseoVoice", "upload complete voiceId=$voiceId bytes=${result.byteLength}; sending STOP")
            transport.send(transport.request(CommandKind.STOP_VOICE_INPUT, voiceId = voiceId, byteLength = result.byteLength, sha256 = result.sha256))
              .onFailure {
                if (voiceGeneration.isCurrent(generation)) WatchStore.voiceError(voiceId, WatchVoiceErrorCode.UPLOAD_FAILED, "Audio transfer failed")
              }
          }
          .onFailure {
            if (it is CancellationException) return@onFailure
            if (!voiceGeneration.isCurrent(generation)) return@onFailure
            Log.e("PaseoVoice", "voice failed voiceId=$voiceId", it)
            transport.send(transport.request(CommandKind.CANCEL_VOICE_INPUT, voiceId = voiceId))
            val interrupted = it.message?.contains("interrupted", true) == true
            WatchStore.voiceError(
              voiceId,
              if (interrupted) WatchVoiceErrorCode.AUDIO_INTERRUPTED else WatchVoiceErrorCode.UPLOAD_FAILED,
              if (interrupted) "Audio interrupted" else "Audio transfer failed",
            )
          }
      } finally {
        if (voiceCapture === capture) {
          voiceCapture = null
          activeVoiceId = null
        }
      }
    }
    haptic()
  }

  private fun cancelVoice(voiceId: String?) {
    val cancelId = activeVoiceId ?: voiceId
    voiceGeneration.invalidate()
    val capture = voiceCapture
    val job = voiceJob
    voiceJob = null
    voiceCapture = null
    activeVoiceId = null
    WatchStore.clearVoice(cancelId)
    capture?.cancel()
    job?.cancel()
    if (cancelId != null) send(CommandKind.CANCEL_VOICE_INPUT, text = null, workspaceId = null, agentId = null, projectId = null, voiceId = cancelId)
    haptic()
  }

  private fun finishVoice() {
    val voiceId = activeVoiceId ?: return
    if (voiceCapture?.finish() == true) {
      Log.i("PaseoVoice", "finish voiceId=$voiceId")
      WatchStore.voicePhase(voiceId, WatchVoicePhase.UPLOADING)
      haptic()
    }
  }

  private fun sendTranscript(voiceId: String, workspaceId: String, agentId: String, text: String) {
    scope.launch {
      transport.send(transport.request(CommandKind.SEND_TRANSCRIPT, workspaceId = workspaceId, agentId = agentId, text = text, voiceId = voiceId))
        .onFailure { WatchStore.failure("Couldn’t send transcript") }
    }
  }

  private fun haptic() {
    runCatching {
      (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.vibrate(
        VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE),
      )
    }
  }

  private fun send(
    kind: CommandKind,
    projectId: String? = null,
    workspaceId: String? = null,
    agentId: String? = null,
    text: String? = null,
    voiceId: String? = null,
  ) {
    if (
      kind == CommandKind.SEND_PROMPT &&
        workspaceId != null &&
        agentId != null &&
        !text.isNullOrBlank()
    ) {
      WatchStore.optimisticMessage(workspaceId, agentId, text.trim())
    }
    WatchStore.loading()
    scope.launch {
      transport.send(transport.request(kind, projectId, workspaceId, agentId, text, voiceId = voiceId))
        .onFailure { WatchStore.failure(it.message ?: "Phone unavailable") }
    }
  }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun PaseoGlanceScreen(
  send: SendCommand,
  onMicrophone: (String, String) -> Unit,
  onFinishVoice: () -> Unit,
  onCancelVoice: (String?) -> Unit,
  onSendTranscript: (String, String, String, String) -> Unit,
  viewMode: WatchViewMode,
  onViewModeChange: (WatchViewMode) -> Unit,
) {
  val ui by WatchStore.state.collectAsState()
  val snapshot = ui.snapshot
  var destination by remember { mutableStateOf<Destination>(Destination.Home) }
  val refresh = { send(CommandKind.GET_SNAPSHOT, null, null, null, null) }
  val pullRefreshState = rememberPullRefreshState(ui.loading, refresh)
  val allowPullRefresh = destination !is Destination.Conversation

  BackHandler(destination != Destination.Home) {
    destination = when (val current = destination) {
      is Destination.Project -> current.parent
      is Destination.Conversation -> current.parent
      Destination.Projects -> Destination.Home
      Destination.Home -> Destination.Home
    }
  }

  LaunchedEffect(destination) {
    val current = destination
    if (current is Destination.Conversation) {
      val session = snapshot?.sessions?.firstOrNull { it.workspaceId == current.workspaceId }
      send(CommandKind.OPEN_SESSION, null, current.workspaceId, session?.agentId, null)
    }
  }

  val screenModifier =
    if (allowPullRefresh) Modifier.fillMaxSize().pullRefresh(pullRefreshState)
    else Modifier.fillMaxSize()
  Box(modifier = screenModifier) {
    when {
      snapshot == null && ui.loading -> CompactMessage("Connecting…")
      snapshot == null -> CompactMessage(ui.error ?: "Phone unavailable", refresh)
      snapshot.connection == WatchConnection.UNAVAILABLE -> CompactMessage("Phone unavailable", refresh)
      else -> when (val current = destination) {
        Destination.Home -> HomeScreen(snapshot, refresh) { destination = it }
        Destination.Projects -> ProjectsScreen(snapshot, refresh) { destination = it }
        is Destination.Project -> ProjectScreen(snapshot, current, refresh) { destination = it }
        is Destination.Conversation -> ConversationScreen(
          snapshot = snapshot,
          destination = current,
          loading = ui.loading,
          send = send,
          viewMode = viewMode,
          onViewModeChange = onViewModeChange,
          onMicrophone = onMicrophone,
          onFinishVoice = onFinishVoice,
          onCancelVoice = onCancelVoice,
          onSendTranscript = onSendTranscript,
        ) { destination = current.parent }
      }
    }

    if (allowPullRefresh) {
      PullRefreshIndicator(
        refreshing = ui.loading,
        state = pullRefreshState,
        modifier = Modifier.align(Alignment.TopCenter),
        backgroundColor = PaseoPalette.Surface2,
        contentColor = PaseoPalette.AccentBright,
      )
    }
  }
}

@Composable
private fun HomeScreen(snapshot: WatchSnapshot, refresh: () -> Unit, navigate: (Destination) -> Unit) {
  val pinned = snapshot.sessions.filter { it.pinnedAt != null }.sortedByDescending { it.pinnedAt }
  CompactList(refresh) {
    if (pinned.isNotEmpty()) {
      items(pinned, key = { it.workspaceId }) { session ->
        SessionRow(session) {
          navigate(Destination.Conversation(session.workspaceId, Destination.Home))
        }
      }
      if (snapshot.projects.isNotEmpty()) {
        item { CompactRow("All projects", "Browse") { navigate(Destination.Projects) } }
      }
    } else {
      items(snapshot.projects, key = { it.projectId }) { project ->
        CompactRow(project.label, null) {
          navigate(Destination.Project(project.projectId, project.label, Destination.Home))
        }
      }
    }
    if (pinned.isEmpty() && snapshot.projects.isEmpty()) item { CompactMessage("No projects") }
  }
}

@Composable
private fun ProjectsScreen(
  snapshot: WatchSnapshot,
  refresh: () -> Unit,
  navigate: (Destination) -> Unit,
) {
  CompactList(refresh) {
    item { BackRow("Projects") { navigate(Destination.Home) } }
    items(snapshot.projects, key = { it.projectId }) { project ->
      CompactRow(project.label, null) {
        navigate(Destination.Project(project.projectId, project.label, Destination.Projects))
      }
    }
  }
}

@Composable
private fun ProjectScreen(
  snapshot: WatchSnapshot,
  destination: Destination.Project,
  refresh: () -> Unit,
  navigate: (Destination) -> Unit,
) {
  val sessions = snapshot.sessions.filter { it.projectId == destination.projectId }
  CompactList(refresh) {
    item { BackRow(destination.label) { navigate(destination.parent) } }
    items(sessions, key = { it.workspaceId }) { session ->
      SessionRow(session) {
        navigate(Destination.Conversation(session.workspaceId, destination))
      }
    }
    if (sessions.isEmpty()) item { CompactMessage("No conversations") }
  }
}

@Composable
private fun ConversationScreen(
  snapshot: WatchSnapshot,
  destination: Destination.Conversation,
  loading: Boolean,
  send: SendCommand,
  viewMode: WatchViewMode,
  onViewModeChange: (WatchViewMode) -> Unit,
  onMicrophone: (String, String) -> Unit,
  onFinishVoice: () -> Unit,
  onCancelVoice: (String?) -> Unit,
  onSendTranscript: (String, String, String, String) -> Unit,
  goBack: () -> Unit,
) {
  val session = snapshot.sessions.firstOrNull { it.workspaceId == destination.workspaceId }
  val conversation = snapshot.conversation?.takeIf { it.workspaceId == destination.workspaceId }
  val messages = conversation?.messages.orEmpty()
  val activities = conversation?.activities.orEmpty()
  val fullRows = remember(messages, activities) {
    (messages.map(ConversationRow::Message) + activities.map(ConversationRow::Activity))
      .sortedWith(compareBy<ConversationRow> { it.sequence }.thenBy { it.key })
  }
  val glanceMessages = remember(messages) { projectGlanceMessages(messages) }
  val timelineRows = if (viewMode == WatchViewMode.FULL) {
    fullRows
  } else {
    glanceMessages.map(ConversationRow::Message)
  }
  var prompt by remember(destination.workspaceId) { mutableStateOf("") }
  var composing by remember(destination.workspaceId) { mutableStateOf(false) }
  var positionedAtNewest by remember(destination.workspaceId, viewMode) { mutableStateOf(false) }
  var loadingOlder by remember(destination.workspaceId, viewMode) { mutableStateOf(false) }
  var rowCountBeforeOlderLoad by remember(destination.workspaceId, viewMode) { mutableStateOf(0) }
  var lastOlderRequestBoundary by remember(destination.workspaceId, viewMode) { mutableStateOf<String?>(null) }
  var awaitingFirstAssistantFragment by remember(destination.workspaceId, viewMode) { mutableStateOf(false) }
  var assistantSequenceAtSend by remember(destination.workspaceId, viewMode) { mutableStateOf<Long?>(null) }
  var positionedGlanceIds by remember(destination.workspaceId) { mutableStateOf(emptySet<String>()) }
  var glanceFocusSeeded by remember(destination.workspaceId) { mutableStateOf(false) }
  val focusRequester = remember { FocusRequester() }
  val listState = rememberLazyListState()

  BackHandler(composing) { composing = false }
  LaunchedEffect(composing) {
    if (composing) focusRequester.requestFocus()
  }
  LaunchedEffect(timelineRows.size, loading, viewMode) {
    if (!positionedAtNewest && conversation != null && !loading) {
      listState.scrollToItem(timelineRows.size + 2)
      positionedAtNewest = true
    } else if (loadingOlder && !loading) {
      val added = (timelineRows.size - rowCountBeforeOlderLoad).coerceAtLeast(0)
      if (added > 0) listState.scrollToItem(added + 2)
      loadingOlder = false
    }
  }
  val tail = messages.lastOrNull()
  val messageSignature = messages.joinToString("|") { "${it.id}:${it.text.hashCode()}:${it.pending}" }
  LaunchedEffect(messageSignature) {
    if (
      viewMode == WatchViewMode.FULL &&
      positionedAtNewest &&
        !loadingOlder &&
        tail?.pending == true &&
        !awaitingFirstAssistantFragment
    ) {
      assistantSequenceAtSend = messages
        .filter { it.role == WatchMessageRole.ASSISTANT }
        .maxOfOrNull { it.sequence } ?: Long.MIN_VALUE
      awaitingFirstAssistantFragment = true
      val pendingRow = timelineRows.indexOfFirst {
        it is ConversationRow.Message && it.value.id == tail.id
      }
      if (pendingRow >= 0) listState.scrollToItem(pendingRow + 2)
    }
    val baseline = assistantSequenceAtSend
    val firstNewAssistantIndex = if (baseline == null) {
      -1
    } else {
      messages.indexOfFirst {
        it.role == WatchMessageRole.ASSISTANT && it.sequence > baseline
      }
    }
    if (
      viewMode == WatchViewMode.FULL &&
      positionedAtNewest &&
        !loadingOlder &&
        awaitingFirstAssistantFragment &&
        firstNewAssistantIndex >= 0
    ) {
      val assistantId = messages[firstNewAssistantIndex].id
      val assistantRow = timelineRows.indexOfFirst {
        it is ConversationRow.Message &&
          it.value.id == assistantId
      }
      if (assistantRow >= 0) {
        listState.scrollToItem(assistantRow + 2)
        awaitingFirstAssistantFragment = false
        assistantSequenceAtSend = null
      }
    }
  }
  val latestUserSequenceForFocus = messages.lastOrNull {
    it.role == WatchMessageRole.USER
  }?.sequence ?: Long.MIN_VALUE
  val glanceFocusCandidates = glanceMessages.filter {
    it.role == WatchMessageRole.ASSISTANT &&
      it.sequence > latestUserSequenceForFocus &&
      (":status:" in it.id || ":summary:" in it.id)
  }.map { message ->
    val token = if (
      ":summary:" in message.id &&
        session?.lastTurnDurationMs != null &&
        session.state != WatchState.WORKING
    ) {
      "${message.id}:completed"
    } else {
      message.id
    }
    message to token
  }
  val newestGlanceFocus = glanceFocusCandidates.lastOrNull()?.first
  LaunchedEffect(viewMode, positionedAtNewest, glanceFocusSeeded) {
    if (viewMode == WatchViewMode.GLANCE && positionedAtNewest && !glanceFocusSeeded) {
      positionedGlanceIds = positionedGlanceIds + glanceFocusCandidates.map { it.second }
      glanceFocusSeeded = true
    }
  }
  val pendingGlanceFocus = if (glanceFocusSeeded) {
    glanceFocusCandidates.firstOrNull { (_, token) -> token !in positionedGlanceIds }
  } else {
    null
  }
  LaunchedEffect(viewMode, pendingGlanceFocus?.second, positionedAtNewest) {
    val (focusMessage, focusToken) = pendingGlanceFocus ?: return@LaunchedEffect
    if (
      viewMode == WatchViewMode.GLANCE &&
        positionedAtNewest
    ) {
      val statusRow = timelineRows.indexOfFirst {
        it is ConversationRow.Message && it.value.id == focusMessage.id
      }
      if (statusRow >= 0) {
        val precedingStatusRow = if (":summary:" in focusMessage.id) {
          val sourcePrefix = focusMessage.id.substringBefore(":summary:")
          timelineRows.subList(0, statusRow).indexOfLast {
            it is ConversationRow.Message &&
              it.sequence > latestUserSequenceForFocus &&
              it.value.id.startsWith("$sourcePrefix:status:")
          }
        } else {
          -1
        }
        val groupStartRow = precedingStatusRow.takeIf { it >= 0 } ?: statusRow
        listState.scrollToItem(groupStartRow + 2)
        withFrameNanos { }
        withFrameNanos { }
        val layout = listState.layoutInfo
        val focusedItem = layout.visibleItemsInfo.firstOrNull {
          it.key == "message:${focusMessage.id}"
        }
        if (focusedItem != null) {
          val precedingItem = if (precedingStatusRow >= 0) {
            val precedingRow = timelineRows[precedingStatusRow] as ConversationRow.Message
            layout.visibleItemsInfo.firstOrNull { it.key == precedingRow.key }
          } else {
            null
          }
          val groupStart = minOf(focusedItem.offset, precedingItem?.offset ?: focusedItem.offset)
          val groupEnd = maxOf(
            focusedItem.offset + focusedItem.size,
            precedingItem?.let { it.offset + it.size } ?: focusedItem.offset + focusedItem.size,
          )
          val groupSize = groupEnd - groupStart
          val viewportSize = layout.viewportEndOffset - layout.viewportStartOffset
          val desiredOffset = if (groupSize < viewportSize) {
            layout.viewportStartOffset + (viewportSize - groupSize) / 2
          } else {
            layout.viewportStartOffset
          }
          listState.scrollBy((groupStart - desiredOffset).toFloat())
        }
        delay(1_000)
        positionedGlanceIds = positionedGlanceIds + focusToken
      }
    }
  }
  LaunchedEffect(listState, conversation?.hasOlder, loading, messages.firstOrNull()?.id) {
    snapshotFlow { listState.firstVisibleItemIndex }.collect { firstVisibleItem ->
      if (
        positionedAtNewest &&
          firstVisibleItem <= 1 &&
          conversation?.hasOlder == true &&
          !loading &&
          !loadingOlder &&
          lastOlderRequestBoundary != (timelineRows.firstOrNull()?.key ?: "__empty__")
      ) {
        loadingOlder = true
        rowCountBeforeOlderLoad = timelineRows.size
        lastOlderRequestBoundary = timelineRows.firstOrNull()?.key ?: "__empty__"
        send(
          CommandKind.LOAD_OLDER_MESSAGES,
          null,
          destination.workspaceId,
          session?.agentId,
          null,
        )
      }
    }
  }

  LazyColumn(
    state = listState,
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 26.dp, vertical = 20.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    item(key = "header") {
      BackRow(
        label = session?.label ?: "Conversation",
        status = statusColor(session?.state),
        goBack = goBack,
        trailing = if (viewMode == WatchViewMode.GLANCE) "Glance" else "Full",
        onTrailingClick = {
          onViewModeChange(
            if (viewMode == WatchViewMode.GLANCE) WatchViewMode.FULL else WatchViewMode.GLANCE,
          )
        },
      )
    }
    item(key = "history-loader") {
      Box(
        modifier = Modifier.fillMaxWidth().height(if (loadingOlder) 28.dp else 1.dp),
        contentAlignment = Alignment.Center,
      ) {
        if (loadingOlder) {
          CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = PaseoPalette.StatusRunning,
            strokeWidth = 2.dp,
          )
        }
      }
    }
    items(timelineRows, key = ConversationRow::key) { row ->
      when (row) {
        is ConversationRow.Message -> Column(modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
              if (row.value.role == WatchMessageRole.USER) Arrangement.End else Arrangement.Start,
          ) {
          val latestUserSequence = messages.lastOrNull {
            it.role == WatchMessageRole.USER
          }?.sequence ?: Long.MIN_VALUE
          val metadataTargetId = if (viewMode == WatchViewMode.FULL) {
            messages.lastOrNull {
              it.role == WatchMessageRole.ASSISTANT && it.sequence > latestUserSequence
            }?.id
          } else {
            glanceMessages.lastOrNull {
              it.role == WatchMessageRole.ASSISTANT &&
                it.sequence > latestUserSequence &&
                (":summary:" in it.id || ":reply" in it.id)
            }?.id
          }
            ConversationMessage(
              message = row.value,
              metadata = if (
                row.value.role == WatchMessageRole.ASSISTANT &&
                  row.value.id == metadataTargetId &&
                  session?.state != WatchState.WORKING &&
                  (
                    session?.lastTurnDurationMs != null ||
                      session?.state == WatchState.COMPLETE ||
                      session?.state == WatchState.FAILED
                  )
              ) completedTurnMetadata(messages, row.value, session) else null,
            )
          }
          if (
            viewMode == WatchViewMode.GLANCE &&
              row.value.id == newestGlanceFocus?.id &&
              session?.state == WatchState.WORKING
          ) {
            GlanceWorkingMetadata(session)
          }
        }
        is ConversationRow.Activity -> ActivityTimelineRow(row.value)
      }
    }
    if (timelineRows.isEmpty()) {
      item(key = "empty") {
        Text(
          if (loading) "Loading conversation…" else "No messages yet.",
          color = PaseoPalette.ForegroundMuted,
          fontSize = 13.sp,
        )
      }
    }
    if (viewMode == WatchViewMode.FULL && session != null && (
      session.state == WatchState.WORKING ||
        session.state == WatchState.WAITING_FOR_INPUT ||
        session.state == WatchState.NEEDS_ATTENTION
    )) {
      item(key = "live-status") {
        LiveStatusRow(
          session = session,
        )
      }
    }
    item(key = "actions") {
      if (session?.agentId == null) {
        Text(
          "Open this conversation on the phone to start an agent.",
          color = PaseoPalette.ForegroundMuted,
          fontSize = 12.sp,
        )
      } else if (!composing) {
        VoiceFooter(
          voice = snapshot.voice,
          viewMode = viewMode,
          phoneAvailable = snapshot.connection == WatchConnection.PAIRED_PHONE_AVAILABLE,
          onMicrophone = { onMicrophone(session!!.workspaceId, session.agentId!!) },
          onFinishVoice = onFinishVoice,
          onKeyboard = { composing = true },
          onSwitch = { onViewModeChange(if (viewMode == WatchViewMode.GLANCE) WatchViewMode.FULL else WatchViewMode.GLANCE) },
          onOpenPhone = { send(CommandKind.OPEN_ON_PHONE, null, session!!.workspaceId, session.agentId, null) },
          onCancel = { onCancelVoice(snapshot.voice?.voiceId) },
          onSendTranscript = { voiceId, text -> onSendTranscript(voiceId, session!!.workspaceId, session.agentId!!, text) },
        )
      } else {
        val submitPrompt = {
          val value = prompt.trim()
          if (value.isNotEmpty()) {
            send(CommandKind.SEND_PROMPT, null, session!!.workspaceId, session.agentId, value)
            prompt = ""
            composing = false
          }
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          BasicTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.weight(1f).focusRequester(focusRequester).background(PaseoPalette.Surface2, RoundedCornerShape(12.dp)).semantics { contentDescription = "Message" }.padding(horizontal = 10.dp, vertical = 8.dp),
            textStyle = TextStyle(color = PaseoPalette.Foreground, fontSize = 13.sp),
            cursorBrush = SolidColor(PaseoPalette.AccentBright),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submitPrompt() }),
            decorationBox = { field ->
              if (prompt.isEmpty()) Text("Message", color = PaseoPalette.ForegroundMuted, fontSize = 13.sp)
              field()
            },
          )
          Text(
            "↑",
            modifier = Modifier.background(PaseoPalette.Accent, RoundedCornerShape(12.dp))
              .semantics { contentDescription = "Send message" }
              .clickable(role = Role.Button) {
              submitPrompt()
            }.padding(horizontal = 12.dp, vertical = 6.dp),
            color = PaseoPalette.Foreground,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
          )
        }
      }
    }
  }
}

@Composable
private fun VoiceFooter(
  voice: WatchVoice?,
  viewMode: WatchViewMode,
  phoneAvailable: Boolean,
  onMicrophone: () -> Unit,
  onFinishVoice: () -> Unit,
  onKeyboard: () -> Unit,
  onSwitch: () -> Unit,
  onOpenPhone: () -> Unit,
  onCancel: () -> Unit,
  onSendTranscript: (String, String) -> Unit,
) {
  var transcript by remember(voice?.voiceId, voice?.transcript) { mutableStateOf(voice?.transcript.orEmpty()) }
  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    when (voice?.phase) {
      WatchVoicePhase.CONFIRMATION -> {
        Text("Review transcript", color = PaseoPalette.ForegroundMuted, fontSize = 11.sp)
        BasicTextField(
          value = transcript,
          onValueChange = { transcript = it },
          modifier = Modifier.fillMaxWidth().background(PaseoPalette.Surface2, RoundedCornerShape(12.dp)).semantics { contentDescription = "Transcript" }.padding(10.dp),
          textStyle = TextStyle(color = PaseoPalette.Foreground, fontSize = 13.sp),
          cursorBrush = SolidColor(PaseoPalette.AccentBright),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
          keyboardActions = KeyboardActions(onSend = {
            if (transcript.isNotBlank()) onSendTranscript(voice.voiceId, transcript.trim())
          }),
        )
        FooterButton("Send", enabled = transcript.isNotBlank()) { onSendTranscript(voice.voiceId, transcript.trim()) }
        FooterButton("Record again") { onMicrophone() }
        FooterButton("Cancel") { onCancel() }
      }
      WatchVoicePhase.RECORDING, WatchVoicePhase.UPLOADING, WatchVoicePhase.TRANSCRIBING -> {
        Text(if (voice.phase == WatchVoicePhase.RECORDING) "Recording…" else if (voice.phase == WatchVoicePhase.UPLOADING) "Sending audio…" else "Transcribing…", color = PaseoPalette.AccentBright, fontSize = 12.sp, modifier = Modifier.semantics { contentDescription = "Voice input ${voice.phase.name.lowercase()}" })
        if (voice.phase == WatchVoicePhase.RECORDING) FooterButton("Finish recording") { onFinishVoice() }
        FooterButton("Cancel") { onCancel() }
      }
      WatchVoicePhase.ERROR -> {
        Text(voice.errorMessage ?: voiceErrorLabel(voice.errorCode), color = PaseoPalette.StatusDanger, fontSize = 12.sp)
        FooterButton("Record again", enabled = phoneAvailable) { onMicrophone() }
        FooterButton("Cancel") { onCancel() }
      }
      else -> {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
          FooterCircleButton("Microphone", "●", phoneAvailable, onMicrophone)
          FooterCircleButton("Keyboard", "⌨", true, onKeyboard)
        }
        FooterButton(if (viewMode == WatchViewMode.GLANCE) "Switch to Full" else "Switch to Glance", onClick = onSwitch)
        FooterButton("Open on phone", enabled = phoneAvailable, onClick = onOpenPhone)
        if (!phoneAvailable) Text("Phone unavailable. Keyboard remains available.", color = PaseoPalette.ForegroundMuted, fontSize = 10.sp)
      }
    }
  }
}

@Composable private fun FooterCircleButton(label: String, glyph: String, enabled: Boolean, onClick: () -> Unit) {
  Text(glyph, modifier = Modifier.size(48.dp).background(if (enabled) PaseoPalette.Accent else PaseoPalette.Surface2, CircleShape).semantics { contentDescription = label }.clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(top = 12.dp), color = PaseoPalette.Foreground, fontSize = 18.sp)
}
@Composable private fun FooterButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
  Text(label, modifier = Modifier.fillMaxWidth().background(if (enabled) PaseoPalette.Surface2 else PaseoPalette.Background, RoundedCornerShape(18.dp)).semantics { contentDescription = label }.clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), color = if (enabled) PaseoPalette.Foreground else PaseoPalette.ForegroundMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}
private fun voiceErrorLabel(code: WatchVoiceErrorCode?) = when (code) {
  WatchVoiceErrorCode.MIC_PERMISSION_DENIED -> "Microphone permission required"
  WatchVoiceErrorCode.AUDIO_INTERRUPTED -> "Audio interrupted"
  WatchVoiceErrorCode.UPLOAD_FAILED -> "Audio transfer failed"
  WatchVoiceErrorCode.TRANSCRIPT_EMPTY -> "No speech detected"
  WatchVoiceErrorCode.TRANSCRIPTION_FAILED -> "Couldn’t transcribe"
  else -> "Voice input unavailable"
}

@Composable
private fun GlanceWorkingMetadata(session: WatchSession) {
  var now by remember(session.activeTurnStartedAt) { mutableStateOf(System.currentTimeMillis()) }
  LaunchedEffect(session.activeTurnStartedAt) {
    while (true) {
      now = System.currentTimeMillis()
      delay(1_000)
    }
  }
  val elapsed = session.activeTurnStartedAt?.let { (now - it).coerceAtLeast(0L) }
  Row(
    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 5.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    CircularProgressIndicator(
      modifier = Modifier.size(10.dp),
      color = PaseoPalette.StatusRunning,
      strokeWidth = 1.5.dp,
    )
    Text(
      buildList {
        add("Working${elapsed?.let { " for ${formatDuration(it)}" } ?: ""}")
        friendlyModel(session.model)?.let(::add)
        friendlyLabel(session.effort)?.let(::add)
      }.joinToString(" · "),
      color = PaseoPalette.ForegroundMuted,
      fontSize = 10.sp,
      lineHeight = 12.sp,
    )
  }
}

@Composable
private fun ConversationMessage(message: WatchMessage, metadata: String? = null) {
  val isUser = message.role == WatchMessageRole.USER
  Column(
    modifier = if (isUser) Modifier else Modifier.fillMaxWidth(),
    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
  ) {
    if (isUser) {
      WatchMarkdownText(
        markdown = message.text,
        modifier = Modifier.fillMaxWidth(0.88f).background(
          PaseoPalette.Accent,
          RoundedCornerShape(16.dp),
        ).padding(horizontal = 12.dp, vertical = 10.dp),
        color = PaseoPalette.Foreground,
      )
    } else {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        parseWatchControlSegments(message.text).forEach { segment ->
          when (segment) {
            is WatchControlSegment.Markdown -> if (segment.text.isNotBlank()) {
              WatchMarkdownText(
                markdown = segment.text.trim(),
                modifier = Modifier.fillMaxWidth(),
                color = PaseoPalette.Foreground,
              )
            }
            is WatchControlSegment.Status -> WatchStatusChip(segment.text)
            is WatchControlSegment.Summary -> WatchSummarySection(segment.markdown)
          }
        }
      }
    }
    if (message.pending) {
      Row(
        modifier = Modifier.padding(top = 3.dp, end = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        CircularProgressIndicator(
          modifier = Modifier.size(10.dp),
          color = PaseoPalette.StatusRunning,
          strokeWidth = 1.5.dp,
        )
        Text("Sending…", color = PaseoPalette.ForegroundMuted, fontSize = 10.sp)
      }
    }
    if (!metadata.isNullOrBlank()) {
      Text(
        metadata,
        modifier = Modifier.padding(top = 3.dp, end = 5.dp),
        color = PaseoPalette.ForegroundMuted,
        fontSize = 10.sp,
        lineHeight = 12.sp,
      )
    }
  }
}

@Composable
private fun WatchStatusChip(status: String) {
  Text(
    text = buildAnnotatedString {
      withStyle(SpanStyle(color = PaseoPalette.AccentBright, fontWeight = FontWeight.SemiBold)) {
        append("Glance · ")
      }
      append(status)
    },
    modifier = Modifier.background(PaseoPalette.Surface2, RoundedCornerShape(16.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
    color = PaseoPalette.ForegroundMuted,
    fontSize = 11.sp,
    lineHeight = 14.sp,
  )
}

@Composable
private fun WatchSummarySection(markdown: String) {
  Column(
    modifier = Modifier.fillMaxWidth().background(PaseoPalette.Surface1, RoundedCornerShape(12.dp)).padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    Text(
      "Glance summary",
      color = PaseoPalette.AccentBright,
      fontSize = 10.sp,
      fontWeight = FontWeight.SemiBold,
    )
    WatchMarkdownText(markdown = markdown, modifier = Modifier.fillMaxWidth())
  }
}

@Composable
private fun ActivityTimelineRow(activity: WatchActivity) {
  val alpha = activityRowAlpha(activity.state)
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp).graphicsLayer(alpha = alpha).semantics {
      contentDescription = "${activity.label}, ${activity.state.name.lowercase()}"
    },
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(activityGlyph(activity.icon), color = activityColor(activity.state), fontSize = 13.sp)
    Text(
      buildString {
        if (activity.state == WatchActivityState.RUNNING) append("Running · ")
        append(activity.label)
        activity.detail?.takeIf(String::isNotBlank)?.let { append(" · $it") }
      },
      modifier = Modifier.weight(1f),
      color = PaseoPalette.ForegroundMuted,
      fontSize = 11.sp,
      lineHeight = 14.sp,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun activityRowAlpha(state: WatchActivityState): Float {
  if (state != WatchActivityState.RUNNING) return 1f
  val transition = rememberInfiniteTransition(label = "activity-shimmer")
  val shimmerAlpha by transition.animateFloat(
    initialValue = 0.45f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
    label = "activity-alpha",
  )
  return shimmerAlpha
}

@Composable
private fun LiveStatusRow(session: WatchSession) {
  var now by remember(session.activeTurnStartedAt) { mutableStateOf(System.currentTimeMillis()) }
  LaunchedEffect(session.activeTurnStartedAt, session.state) {
    while (session.state == WatchState.WORKING) {
      now = System.currentTimeMillis()
      delay(1_000)
    }
  }
  val label = when (session.state) {
    WatchState.WORKING -> "Working"
    else -> session.shortStatus
  }
  val elapsed = session.activeTurnStartedAt?.let { (now - it).coerceAtLeast(0L) }
  Column(
    modifier = Modifier.fillMaxWidth().background(PaseoPalette.Surface1, RoundedCornerShape(13.dp)).padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
      if (session.state == WatchState.WORKING) {
        CircularProgressIndicator(
          modifier = Modifier.size(12.dp),
          color = PaseoPalette.StatusRunning,
          strokeWidth = 1.5.dp,
        )
      } else {
        Text("●", color = statusColor(session.state), fontSize = 10.sp)
      }
      Text(
        buildString {
          append(label)
          if (elapsed != null) append(" · ${formatDuration(elapsed)}")
        },
        modifier = Modifier.weight(1f),
        color = PaseoPalette.Foreground,
        fontSize = 12.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    val runtime = runtimeMetadata(session)
    if (runtime.isNotBlank()) {
      Text(runtime, color = PaseoPalette.ForegroundMuted, fontSize = 10.sp)
    }
  }
}

private fun activityGlyph(icon: String): String = when (icon) {
  "shell" -> "▣"
  "sub_agent" -> "◉"
  "read" -> "◫"
  "edit" -> "✎"
  "search" -> "⌕"
  "web" -> "↗"
  "reasoning" -> "✦"
  "todo" -> "☑"
  "error" -> "!"
  else -> "◆"
}

private fun activityColor(state: WatchActivityState): Color = when (state) {
  WatchActivityState.RUNNING -> PaseoPalette.StatusRunning
  WatchActivityState.FAILED -> PaseoPalette.StatusDanger
  WatchActivityState.CANCELED -> PaseoPalette.ForegroundExtraMuted
  WatchActivityState.COMPLETED -> PaseoPalette.ForegroundMuted
}

private fun completedTurnMetadata(
  messages: List<WatchMessage>,
  assistant: WatchMessage,
  session: WatchSession?,
): String? {
  val user = messages.lastOrNull {
    it.role == WatchMessageRole.USER && it.sequence < assistant.sequence
  }
  val duration = session?.lastTurnDurationMs
    ?: user?.let { (assistant.timestamp - it.timestamp).coerceAtLeast(0L) }
  val parts = buildList {
    if (duration != null) add("Worked for ${formatDuration(duration)}")
    friendlyModel(session?.model)?.let(::add)
    friendlyLabel(session?.effort)?.let(::add)
  }
  return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun runtimeMetadata(session: WatchSession): String = listOfNotNull(
  friendlyModel(session.model),
  friendlyLabel(session.effort),
).joinToString(" · ")

private fun friendlyModel(value: String?): String? {
  val model = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
  val lower = model.lowercase()
  return when {
    lower.endsWith("-sol") || lower == "sol" -> "Sol"
    lower.endsWith("-terra") || lower == "terra" -> "Terra"
    lower.endsWith("-luna") || lower == "luna" -> "Luna"
    lower.startsWith("gpt-") -> model.replaceFirst("gpt-", "GPT-")
    else -> model
  }
}

private fun friendlyLabel(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)
  ?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }

private fun formatDuration(durationMs: Long): String {
  val seconds = (durationMs / 1_000).coerceAtLeast(0)
  return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}

@Composable
private fun CompactList(refresh: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 26.dp, vertical = 18.dp),
    verticalArrangement = Arrangement.spacedBy(7.dp),
    content = content,
  )
}

@Composable
private fun BackRow(
  label: String,
  status: Color? = null,
  trailing: String? = null,
  onTrailingClick: (() -> Unit)? = null,
  goBack: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = goBack).padding(vertical = 5.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("‹", color = PaseoPalette.AccentBright, fontSize = 24.sp)
    Text(
      label,
      modifier = Modifier.weight(1f).padding(start = 5.dp),
      color = PaseoPalette.Foreground,
      fontWeight = FontWeight.Bold,
      fontSize = 15.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (status != null) Text("•", color = status, fontSize = 18.sp)
    if (trailing != null) {
      Text(
        trailing,
        modifier = Modifier.background(PaseoPalette.Surface2, RoundedCornerShape(8.dp))
          .clickable(enabled = onTrailingClick != null, role = Role.Button) { onTrailingClick?.invoke() }
          .padding(horizontal = 7.dp, vertical = 4.dp),
        color = PaseoPalette.AccentBright,
        fontSize = 10.sp,
      )
    }
  }
}

@Composable
private fun SessionRow(session: WatchSession, onClick: () -> Unit) {
  CompactRow(
    session.label,
    session.latestReply?.let(::watchPreviewText) ?: session.shortStatus,
    statusColor(session.state),
    onClick,
  )
}

@Composable
private fun CompactRow(
  title: String,
  detail: String?,
  accent: Color = PaseoPalette.AccentBright,
  onClick: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth().background(PaseoPalette.Surface1, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
  ) {
    Text(
      title,
      color = PaseoPalette.Foreground,
      fontWeight = FontWeight.SemiBold,
      fontSize = 15.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (!detail.isNullOrBlank()) {
      Spacer(Modifier.height(2.dp))
      Text(
        detail,
        color = accent,
        fontSize = 12.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun CompactMessage(message: String, retry: (() -> Unit)? = null) {
  Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Text(
      message,
      modifier = Modifier.clickable(enabled = retry != null) { retry?.invoke() }.padding(8.dp),
      color = PaseoPalette.ForegroundMuted,
      fontSize = 15.sp,
    )
  }
}

private fun statusColor(state: WatchState?): Color = when (state) {
  WatchState.WORKING -> PaseoPalette.StatusRunning
  WatchState.NEEDS_ATTENTION, WatchState.WAITING_FOR_INPUT -> PaseoPalette.StatusWarning
  WatchState.FAILED -> PaseoPalette.StatusDanger
  WatchState.COMPLETE -> PaseoPalette.StatusSuccess
  else -> PaseoPalette.ForegroundMuted
}
