package sh.paseo.glance.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceCaptureControllerTest {
  @Test fun finishIsDistinctFromCancelAndDoubleTapIsSafe() {
    val controller = VoiceCaptureController()
    var stops = 0
    controller.attachRecorderStop { stops += 1 }

    assertTrue(controller.finish())
    assertEquals(VoiceCaptureController.State.FINISHING, controller.state())
    assertEquals(1, stops)
    assertFalse(controller.finish())
    assertEquals(1, stops)
  }

  @Test fun cancelDoesNotBecomeFinishOrUploadIntent() {
    val controller = VoiceCaptureController()
    var stops = 0
    controller.attachRecorderStop { stops += 1 }

    assertTrue(controller.cancel())
    assertEquals(VoiceCaptureController.State.CANCELED, controller.state())
    assertFalse(controller.finish())
    assertEquals(1, stops)
  }

  @Test fun finishBeforeRecorderAttachStillStopsItOnce() {
    val controller = VoiceCaptureController()
    var stops = 0
    assertTrue(controller.finish())
    controller.attachRecorderStop { stops += 1 }
    assertEquals(1, stops)
  }

  @Test fun cancelDuringBlockingReadStopsRecorderAndWins() {
    val controller = VoiceCaptureController()
    val readReleased = CountDownLatch(1)
    controller.attachRecorderStop { readReleased.countDown() }

    assertTrue(controller.cancel())
    assertTrue(readReleased.await(1, TimeUnit.SECONDS))
    assertEquals(VoiceCaptureController.State.CANCELED, controller.state())
  }

  @Test fun cancelConcurrentWithFinishIsTerminalAndPreventsFinishRetry() {
    val controller = VoiceCaptureController()
    controller.attachRecorderStop { }
    assertTrue(controller.finish())
    assertTrue(controller.cancel())
    assertEquals(VoiceCaptureController.State.CANCELED, controller.state())
    assertFalse(controller.finish())
  }

  @Test fun invalidatedGenerationRejectsStaleCoroutine() {
    val generations = VoiceCaptureGeneration()
    val stale = generations.next()
    generations.invalidate()
    val current = generations.next()

    assertFalse(generations.isCurrent(stale))
    assertTrue(generations.isCurrent(current))
  }
}
