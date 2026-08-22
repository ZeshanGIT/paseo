package sh.paseo.wearbridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class DurableWatchCommandQueueTest {
  private class MemoryStorage : WatchCommandStorage {
    var value = ""
    override fun read(): String = value
    override fun write(value: String) {
      this.value = value
    }
  }

  @Test
  fun coldStartQueueSurvivesNewQueueInstance() {
    val storage = MemoryStorage()
    val first = DurableWatchCommandQueue(storage).enqueue("node-1", "{\"kind\":\"OPEN_ON_PHONE\"}")

    assertEquals(first, DurableWatchCommandQueue(storage).peek())
  }

  @Test
  fun liveDeliveryPreservesNodeAndPayloadThenAcknowledgesOnce() {
    val storage = MemoryStorage()
    val queue = DurableWatchCommandQueue(storage)
    val queued = queue.enqueue("watch-node", "{\"kind\":\"STOP_VOICE_INPUT\"}")

    assertEquals("watch-node", queue.peek()?.nodeId)
    assertEquals("{\"kind\":\"STOP_VOICE_INPUT\"}", queue.peek()?.command)
    assertTrue(queue.acknowledge(queued.deliveryId))
    assertNull(queue.peek())
    assertFalse(queue.acknowledge(queued.deliveryId))
  }

  @Test
  fun queuedDeliveryMaintainsOrder() {
    val storage = MemoryStorage()
    val queue = DurableWatchCommandQueue(storage)
    val first = queue.enqueue("node-1", "first")
    queue.enqueue("node-2", "second")

    assertEquals("first", queue.peek()?.command)
    queue.acknowledge(first.deliveryId)
    assertEquals("second", queue.peek()?.command)
  }

  @Test
  fun coldLaunchIsNotRepeatedWhenQueuedCommandReachesCoordinator() {
    val gate = ForegroundLaunchGate()
    gate.markListenerLaunchRequested()
    assertFalse(gate.shouldCoordinatorLaunch())
  }

  @Test
  fun liveListenerLaunchIsNotRepeatedByCoordinator() {
    val gate = ForegroundLaunchGate()
    gate.markListenerLaunchRequested()
    assertFalse(gate.shouldCoordinatorLaunch())
  }

  @Test
  fun eachListenerLaunchSuppressesExactlyOneCoordinatorLaunch() {
    val gate = ForegroundLaunchGate()
    gate.markListenerLaunchRequested()
    gate.markListenerLaunchRequested()

    assertFalse(gate.shouldCoordinatorLaunch())
    assertFalse(gate.shouldCoordinatorLaunch())
    assertTrue(gate.shouldCoordinatorLaunch())
  }

  @Test
  fun failedBackgroundLaunchCanBeRetriedByCoordinator() {
    val gate = ForegroundLaunchGate()
    gate.markListenerLaunchRequested()
    gate.markListenerLaunchFailed()
    assertTrue(gate.shouldCoordinatorLaunch())
  }

  @Test
  fun everyWatchCommandStartsThePhoneRuntimeForColdDelivery() {
    listOf(
      "GET_SNAPSHOT",
      "GET_PROJECTS",
      "GET_SESSIONS",
      "OPEN_SESSION",
      "LOAD_OLDER_MESSAGES",
      "CREATE_SESSION",
      "SEND_PROMPT",
      "START_VOICE_INPUT",
      "STOP_VOICE_INPUT",
      "CANCEL_VOICE_INPUT",
      "SEND_TRANSCRIPT",
      "OPEN_ON_PHONE",
    ).forEach { kind -> assertTrue(shouldForeground(kind)) }
  }

  @Test
  fun malformedWatchCommandsDoNotStartThePhoneRuntime() {
    assertFalse(shouldForeground(null))
    assertFalse(shouldForeground("UNKNOWN"))
  }

  @Test
  fun voiceChannelPathAcceptsOnlyTheRegisteredVoicePrefix() {
    assertEquals("voice-1", voiceIdFromChannelPath("/paseo-glance/voice/v1/voice-1"))
    assertNull(voiceIdFromChannelPath("/paseo-glance/request"))
    assertNull(voiceIdFromChannelPath("/paseo-glance/voice/v1/"))
  }

  @Test
  fun verifiedVoiceChunksAreBoundedAndReportEndOfFile() {
    val file = File.createTempFile("wear-voice", ".pcm")
    try {
      file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
      val transfer = PendingVoiceTransfer(
        nodeId = "watch-node",
        file = file,
        expiresAtMs = Long.MAX_VALUE,
        verifiedBytes = 5,
      )

      val first = readVerifiedVoiceTransferChunk(transfer, offset = 0, maxBytes = 3)
      val last = readVerifiedVoiceTransferChunk(transfer, offset = 3, maxBytes = 3)
      val end = readVerifiedVoiceTransferChunk(transfer, offset = 5, maxBytes = 1)

      assertEquals(listOf<Byte>(1, 2, 3), first.bytes.toList())
      assertFalse(first.eof)
      assertEquals(listOf<Byte>(4, 5), last.bytes.toList())
      assertTrue(last.eof)
      assertEquals(0, end.bytes.size)
      assertTrue(end.eof)
    } finally {
      file.delete()
    }
  }

  @Test(expected = IllegalStateException::class)
  fun unverifiedVoiceTransfersCannotBeRead() {
    val file = File.createTempFile("wear-voice", ".pcm")
    try {
      file.writeBytes(byteArrayOf(1))
      readVerifiedVoiceTransferChunk(
        PendingVoiceTransfer("watch-node", file, Long.MAX_VALUE),
        offset = 0,
        maxBytes = 1,
      )
    } finally {
      file.delete()
    }
  }

  @Test(expected = IllegalArgumentException::class)
  fun voiceChunkSizeHasAnUpperBound() {
    val file = File.createTempFile("wear-voice", ".pcm")
    try {
      file.writeBytes(byteArrayOf(1))
      readVerifiedVoiceTransferChunk(
        PendingVoiceTransfer("watch-node", file, Long.MAX_VALUE, verifiedBytes = 1),
        offset = 0,
        maxBytes = MAX_VOICE_CHUNK_BYTES + 1,
      )
    } finally {
      file.delete()
    }
  }

  @Test
  fun finishingWaitsForTheChannelWriterToComplete() {
    val file = File.createTempFile("wear-voice", ".pcm")
    try {
      val transfer = PendingVoiceTransfer("watch-node", file, System.currentTimeMillis() + 5_000)
      thread {
        Thread.sleep(50)
        transfer.uploadedBytes = 4
        transfer.uploadComplete.countDown()
      }

      assertTrue(VoiceTransferRegistry.awaitUploadCompletion(transfer, timeoutMs = 1_000))
      assertEquals(4L, transfer.uploadedBytes)
    } finally {
      file.delete()
    }
  }
}
