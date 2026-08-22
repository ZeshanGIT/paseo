package sh.paseo.glance.wear

import org.json.JSONArray
import org.json.JSONObject

/** The intentionally small, phone-owned V1 payload contract. */
object WatchProtocol {
  const val VERSION = 1
  const val PHONE_CAPABILITY = "paseo_glance_bridge_v1"
  const val REQUEST_PATH = "/paseo-glance/request"
  const val RESPONSE_PATH = "/paseo-glance/response"
  const val SNAPSHOT_PATH = "/paseo-glance/snapshot"
  const val VOICE_CHANNEL_PREFIX = "/paseo-glance/voice/v1/"

  fun encode(command: WatchCommand): ByteArray = JSONObject().apply {
    put("v", VERSION)
    put("id", command.id)
    put("kind", command.kind.name)
    command.projectId?.let { put("projectId", it) }
    command.workspaceId?.let { put("workspaceId", it) }
    command.agentId?.let { put("agentId", it) }
    command.text?.let { put("text", it) }
    command.voiceId?.let { put("voiceId", it) }
    command.format?.let { put("format", it) }
    command.byteLength?.let { put("byteLength", it) }
    command.sha256?.let { put("sha256", it) }
  }.toString().encodeToByteArray()

  fun decodeResponse(bytes: ByteArray): WatchResponse? = runCatching {
    val root = JSONObject(bytes.decodeToString())
    if (root.optInt("v") != VERSION || root.optString("id").isBlank()) return null
    WatchResponse(
      id = root.getString("id"),
      ok = root.optBoolean("ok"),
      snapshot = root.optJSONObject("snapshot")?.let(::decodeSnapshot),
      error = root.optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank),
    )
  }.getOrNull()

  fun decodeSnapshotJson(value: String): WatchSnapshot? = runCatching {
    decodeSnapshot(JSONObject(value))
  }.getOrNull()

  fun decodeSnapshot(json: JSONObject): WatchSnapshot? = runCatching {
    if (json.optInt("v") != VERSION) return null
    WatchSnapshot(
      generation = json.optLong("generation"),
      connection = when (json.optString("connection")) {
        "paired_phone_available" -> WatchConnection.PAIRED_PHONE_AVAILABLE
        else -> WatchConnection.UNAVAILABLE
      },
      projects = json.optJSONArray("projects").toProjects(),
      sessions = json.optJSONArray("sessions").toSessions(),
      active = json.optJSONObject("active")?.toSession(),
      conversation = json.optJSONObject("conversation")?.toConversation(),
      voice = json.optJSONObject("voice")?.toVoice(),
    )
  }.getOrNull()

  private fun JSONArray?.toProjects(): List<WatchProject> = buildList {
    this@toProjects?.let { values ->
      for (index in 0 until values.length()) {
        values.optJSONObject(index)?.let { item ->
          val projectId = item.optString("projectId")
          if (projectId.isNotBlank()) add(WatchProject(projectId, item.optString("label", projectId)))
        }
      }
    }
  }

  private fun JSONObject.toVoice(): WatchVoice? {
    val voiceId = optString("voiceId")
    val phase = WatchVoicePhase.fromWire(optString("phase"))
    if (voiceId.isBlank() || phase == null) return null
    return WatchVoice(
      voiceId = voiceId,
      phase = phase,
      transcript = optString("transcript").takeIf(String::isNotBlank),
      errorCode = WatchVoiceErrorCode.fromWire(optString("errorCode")),
      errorMessage = optString("errorMessage").takeIf(String::isNotBlank),
    )
  }

  private fun JSONArray?.toSessions(): List<WatchSession> = buildList {
    this@toSessions?.let { values ->
      for (index in 0 until values.length()) values.optJSONObject(index)?.toSession()?.let(::add)
    }
  }

  private fun JSONObject.toSession(): WatchSession? {
    val workspaceId = optString("workspaceId")
    if (workspaceId.isBlank()) return null
    return WatchSession(
      workspaceId = workspaceId,
      projectId = optString("projectId"),
      agentId = optString("agentId").takeIf(String::isNotBlank),
      label = optString("label", workspaceId),
      state = WatchState.fromWire(optString("state")),
      shortStatus = optString("shortStatus"),
      updatedAt = optLong("updatedAt"),
      pinnedAt = optLong("pinnedAt").takeIf { has("pinnedAt") },
      latestReply = optString("latestReply").takeIf(String::isNotBlank),
      activeTurnStartedAt = optLong("activeTurnStartedAt").takeIf { has("activeTurnStartedAt") },
      lastTurnDurationMs = optLong("lastTurnDurationMs").takeIf { has("lastTurnDurationMs") },
      model = optString("model").takeIf(String::isNotBlank),
      effort = optString("effort").takeIf(String::isNotBlank),
      currentActivity = optJSONObject("currentActivity")?.toActivity(),
    )
  }

  private fun JSONObject.toConversation(): WatchConversation? {
    val workspaceId = optString("workspaceId")
    val agentId = optString("agentId")
    if (workspaceId.isBlank() || agentId.isBlank()) return null
    return WatchConversation(
      workspaceId = workspaceId,
      agentId = agentId,
      messages = optJSONArray("messages").toMessages(),
      activities = optJSONArray("activities").toActivities(),
      hasOlder = optBoolean("hasOlder"),
      currentStatus = optString("currentStatus").takeIf(String::isNotBlank),
      summary = optString("summary").takeIf(String::isNotBlank),
    )
  }

  private fun JSONArray?.toMessages(): List<WatchMessage> = buildList {
    this@toMessages?.let { values ->
      for (index in 0 until values.length()) {
        val item = values.optJSONObject(index) ?: continue
        val id = item.optString("id")
        val text = item.optString("text")
        val role = WatchMessageRole.fromWire(item.optString("role")) ?: continue
        if (id.isBlank() || text.isBlank()) continue
        add(
          WatchMessage(
            id = id,
            role = role,
            text = text,
            timestamp = item.optLong("timestamp"),
            sequence = item.optLong("sequence"),
            pending = item.optBoolean("pending"),
          ),
        )
      }
    }
  }

  private fun JSONObject.toActivity(): WatchActivity? {
    val id = optString("id")
    val label = optString("label")
    if (id.isBlank() || label.isBlank()) return null
    return WatchActivity(
      id = id,
      kind = WatchActivityKind.fromWire(optString("kind")),
      icon = optString("icon", "tool"),
      label = label,
      detail = optString("detail").takeIf(String::isNotBlank),
      state = WatchActivityState.fromWire(optString("state")),
      timestamp = optLong("timestamp"),
      sequence = optLong("sequence"),
    )
  }

  private fun JSONArray?.toActivities(): List<WatchActivity> = buildList {
    this@toActivities?.let { values ->
      for (index in 0 until values.length()) values.optJSONObject(index)?.toActivity()?.let(::add)
    }
  }
}

enum class CommandKind { GET_SNAPSHOT, GET_PROJECTS, GET_SESSIONS, OPEN_SESSION, LOAD_OLDER_MESSAGES, CREATE_SESSION, SEND_PROMPT, START_VOICE_INPUT, STOP_VOICE_INPUT, CANCEL_VOICE_INPUT, SEND_TRANSCRIPT, OPEN_ON_PHONE }
enum class WatchVoicePhase { READY, RECORDING, PREVIEW, UPLOADING, TRANSCRIBING, CONFIRMATION, ERROR;
  companion object { fun fromWire(value: String) = entries.firstOrNull { it.name.equals(value, true) } }
}
enum class WatchVoiceErrorCode { PHONE_UNAVAILABLE, MIC_PERMISSION_DENIED, AUDIO_INTERRUPTED, UPLOAD_FAILED, TRANSCRIPTION_FAILED, TRANSCRIPT_EMPTY, CANCELED;
  companion object { fun fromWire(value: String) = entries.firstOrNull { it.name.equals(value, true) } }
}
enum class WatchConnection { PAIRED_PHONE_AVAILABLE, UNAVAILABLE }
enum class WatchMessageRole {
  USER, ASSISTANT;
  companion object {
    fun fromWire(value: String) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
  }
}
enum class WatchState {
  IDLE, CONNECTING, WORKING, WAITING_FOR_INPUT, NEEDS_ATTENTION, COMPLETE, FAILED, UNAVAILABLE;
  companion object {
    fun fromWire(value: String) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNAVAILABLE
  }
}
enum class WatchActivityKind {
  TOOL, REASONING, TODO, ERROR, COMPACTION;
  companion object {
    fun fromWire(value: String) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: TOOL
  }
}
enum class WatchActivityState {
  RUNNING, COMPLETED, FAILED, CANCELED;
  companion object {
    fun fromWire(value: String) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: COMPLETED
  }
}

data class WatchCommand(
  val id: String,
  val kind: CommandKind,
  val projectId: String? = null,
  val workspaceId: String? = null,
  val agentId: String? = null,
  val text: String? = null,
  val voiceId: String? = null,
  val format: String? = null,
  val byteLength: Int? = null,
  val sha256: String? = null,
)
data class WatchProject(val projectId: String, val label: String)
data class WatchSession(
  val workspaceId: String,
  val projectId: String,
  val agentId: String?,
  val label: String,
  val state: WatchState,
  val shortStatus: String,
  val updatedAt: Long,
  val pinnedAt: Long?,
  val latestReply: String?,
  val activeTurnStartedAt: Long? = null,
  val lastTurnDurationMs: Long? = null,
  val model: String? = null,
  val effort: String? = null,
  val currentActivity: WatchActivity? = null,
)
data class WatchMessage(
  val id: String,
  val role: WatchMessageRole,
  val text: String,
  val timestamp: Long,
  val sequence: Long,
  val pending: Boolean = false,
)
data class WatchActivity(
  val id: String,
  val kind: WatchActivityKind,
  val icon: String,
  val label: String,
  val detail: String?,
  val state: WatchActivityState,
  val timestamp: Long,
  val sequence: Long,
)
data class WatchConversation(
  val workspaceId: String,
  val agentId: String,
  val messages: List<WatchMessage>,
  val activities: List<WatchActivity> = emptyList(),
  val hasOlder: Boolean,
  val currentStatus: String? = null,
  val summary: String? = null,
)
data class WatchSnapshot(
  val generation: Long,
  val connection: WatchConnection,
  val projects: List<WatchProject>,
  val sessions: List<WatchSession>,
  val active: WatchSession?,
  val conversation: WatchConversation?,
  val voice: WatchVoice? = null,
)
data class WatchVoice(val voiceId: String, val phase: WatchVoicePhase, val transcript: String?, val errorCode: WatchVoiceErrorCode?, val errorMessage: String?)
data class WatchResponse(val id: String, val ok: Boolean, val snapshot: WatchSnapshot?, val error: String?)
