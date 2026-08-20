package sh.paseo.wearbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
  fun voiceChannelPathAcceptsOnlyTheRegisteredVoicePrefix() {
    assertEquals("voice-1", voiceIdFromChannelPath("/paseo-glance/voice/v1/voice-1"))
    assertNull(voiceIdFromChannelPath("/paseo-glance/request"))
    assertNull(voiceIdFromChannelPath("/paseo-glance/voice/v1/"))
  }
}
