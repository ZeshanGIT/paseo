package sh.paseo.glance.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchVoiceStateTest {
  private val recording = WatchVoice("voice", WatchVoicePhase.RECORDING, null, null, null)
  private val uploadError = WatchVoice(
    "voice",
    WatchVoicePhase.ERROR,
    null,
    WatchVoiceErrorCode.UPLOAD_FAILED,
    "Audio transfer failed",
  )

  @Test fun terminalUploadErrorSurvivesEmptyAndStalePhoneSnapshots() {
    assertEquals(uploadError, reconcileVoiceState(recording, null, null, uploadError, null))
    assertEquals(uploadError, reconcileVoiceState(recording, recording, null, uploadError, null))
  }

  @Test fun canceledVoiceIdSuppressesLateRecordingSnapshot() {
    assertNull(reconcileVoiceState(null, recording, null, null, "voice"))
  }

  @Test fun optimisticUploadingSurvivesStaleRecordingSnapshot() {
    val uploading = recording.copy(phase = WatchVoicePhase.UPLOADING)
    assertEquals(uploading, reconcileVoiceState(uploading, recording, uploading, null, null))
  }

  @Test fun unrelatedSnapshotCannotReplaceLocalRecording() {
    val unrelated = WatchVoice("other", WatchVoicePhase.CONFIRMATION, "other text", null, null)
    assertEquals(recording, reconcileVoiceState(recording, unrelated, recording, null, null))
    assertEquals(recording, reconcileVoiceState(recording, null, recording, null, null))
  }

  @Test fun staleSameIdRecordingCannotReplaceLocalUploading() {
    val uploading = recording.copy(phase = WatchVoicePhase.UPLOADING)
    assertEquals(uploading, reconcileVoiceState(uploading, recording, uploading, null, null))
  }

  @Test fun processRestoreWithoutRecorderBecomesActionableError() {
    val restored = reconcileVoiceState(null, recording, null, null, null, orphanActiveSnapshot = true)
    assertEquals(WatchVoicePhase.ERROR, restored?.phase)
    assertEquals(WatchVoiceErrorCode.AUDIO_INTERRUPTED, restored?.errorCode)
    assertEquals("Recording interrupted", restored?.errorMessage)
  }

  @Test fun matchingPhoneTerminalTakesOverLocalRecording() {
    val confirmation = recording.copy(phase = WatchVoicePhase.CONFIRMATION, transcript = "hello")
    assertEquals(confirmation, reconcileVoiceState(recording, confirmation, recording, null, null))
  }
}
