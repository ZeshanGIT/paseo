package sh.paseo.wearbridge

import android.content.Context
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

data class QueuedWatchCommand(
  val deliveryId: String,
  val nodeId: String,
  val command: String,
)

interface WatchCommandStorage {
  fun read(): String
  fun write(value: String)
}

class DurableWatchCommandQueue(private val storage: WatchCommandStorage) {
  private fun encode(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

  private fun decode(value: String): String =
    String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)

  private fun readAll(): MutableList<QueuedWatchCommand> =
    storage.read().lineSequence().mapNotNull { line ->
      val fields = line.split('\t')
      if (fields.size != 3) return@mapNotNull null
      runCatching { QueuedWatchCommand(fields[0], decode(fields[1]), decode(fields[2])) }.getOrNull()
    }.toMutableList()

  private fun writeAll(commands: List<QueuedWatchCommand>) {
    storage.write(commands.joinToString("\n") { "${it.deliveryId}\t${encode(it.nodeId)}\t${encode(it.command)}" })
  }

  @Synchronized
  fun enqueue(nodeId: String, command: String): QueuedWatchCommand {
    val queued = QueuedWatchCommand(UUID.randomUUID().toString(), nodeId, command)
    val commands = readAll()
    commands += queued
    writeAll(commands)
    return queued
  }

  @Synchronized
  fun peek(): QueuedWatchCommand? = readAll().firstOrNull()

  @Synchronized
  fun acknowledge(deliveryId: String): Boolean {
    val commands = readAll()
    val removed = commands.removeAll { it.deliveryId == deliveryId }
    if (removed) writeAll(commands)
    return removed
  }
}

object PendingWatchCommandStore {
  private const val PREFERENCES = "paseo_wear_bridge"
  private const val COMMANDS_KEY = "pending_commands_v1"
  private var queue: DurableWatchCommandQueue? = null

  @Synchronized
  private fun get(context: Context): DurableWatchCommandQueue {
    queue?.let { return it }
    val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    return DurableWatchCommandQueue(object : WatchCommandStorage {
      override fun read(): String = preferences.getString(COMMANDS_KEY, "") ?: ""
      override fun write(value: String) {
        check(preferences.edit().putString(COMMANDS_KEY, value).commit())
      }
    }).also { queue = it }
  }

  fun enqueue(context: Context, nodeId: String, command: String): QueuedWatchCommand =
    get(context).enqueue(nodeId, command)

  fun peek(context: Context): QueuedWatchCommand? = get(context).peek()

  fun acknowledge(context: Context, deliveryId: String): Boolean =
    get(context).acknowledge(deliveryId)
}

class ForegroundLaunchGate {
  private var listenerLaunchesPending = 0

  @Synchronized
  fun markListenerLaunchRequested() {
    listenerLaunchesPending += 1
  }

  @Synchronized
  fun markListenerLaunchFailed() {
    if (listenerLaunchesPending > 0) listenerLaunchesPending -= 1
  }

  /** Returns true when no matching listener launch exists for the coordinator call. */
  @Synchronized
  fun shouldCoordinatorLaunch(): Boolean {
    if (listenerLaunchesPending == 0) return true
    listenerLaunchesPending -= 1
    return false
  }
}

object WearBridgeRuntimeState {
  @Volatile var observing = false
  val foregroundLaunch = ForegroundLaunchGate()
}
