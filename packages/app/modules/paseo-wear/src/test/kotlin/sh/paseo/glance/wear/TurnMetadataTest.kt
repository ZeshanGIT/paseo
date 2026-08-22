package sh.paseo.glance.wear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnMetadataTest {
  private val assistant = WatchMessage(
    id = "assistant",
    role = WatchMessageRole.ASSISTANT,
    text = "I’ll inspect the workspace.",
    timestamp = 20,
    sequence = 20,
  )
  private val completedSession = WatchSession(
    workspaceId = "workspace",
    projectId = "project",
    agentId = "agent",
    label = "Workspace",
    state = WatchState.COMPLETE,
    shortStatus = "Complete",
    updatedAt = 30,
    pinnedAt = null,
    latestReply = assistant.text,
    lastTurnDurationMs = 4_000,
  )

  @Test fun hidesMarkerWhenLaterWorkIsStillPartOfTheTurn() {
    val laterActivity = WatchActivity(
      id = "shell",
      kind = WatchActivityKind.TOOL,
      icon = "shell",
      label = "Shell",
      detail = "rg --files",
      state = WatchActivityState.RUNNING,
      timestamp = 25,
      sequence = 25,
    )

    assertFalse(shouldShowCompletedTurnMetadata(completedSession, listOf(laterActivity), assistant))
  }

  @Test fun hidesMarkerWhenAnAssistantMessagePrecedesLaterCompletedWork() {
    val laterActivity = WatchActivity(
      id = "shell",
      kind = WatchActivityKind.TOOL,
      icon = "shell",
      label = "Shell",
      detail = "rg --files",
      state = WatchActivityState.COMPLETED,
      timestamp = 25,
      sequence = 25,
    )

    assertFalse(shouldShowCompletedTurnMetadata(completedSession, listOf(laterActivity), assistant))
  }

  @Test fun showsMarkerForTheFinalAssistantMessageAfterCompletedWork() {
    val earlierActivity = WatchActivity(
      id = "shell",
      kind = WatchActivityKind.TOOL,
      icon = "shell",
      label = "Shell",
      detail = "rg --files",
      state = WatchActivityState.COMPLETED,
      timestamp = 15,
      sequence = 15,
    )

    assertTrue(shouldShowCompletedTurnMetadata(completedSession, listOf(earlierActivity), assistant))
  }
}
