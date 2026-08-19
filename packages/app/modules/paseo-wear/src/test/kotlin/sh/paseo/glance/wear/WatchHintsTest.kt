package sh.paseo.glance.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchHintsTest {
  @Test
  fun rendersSummaryContentWithoutControlTagsInSessionPreview() {
    assertEquals(
      "Completed three simulated steps",
      watchPreviewText(
        "<WATCH_CURRENT_STATUS>Wrapping up</WATCH_CURRENT_STATUS>\n" +
          "<WATCH_SUMMARY>Completed three simulated steps</WATCH_SUMMARY>",
      ),
    )
  }

  @Test
  fun removesControlBlocksFromGlanceFallbackOnly() {
    assertEquals(
      "Visible before\n\nVisible after",
      withoutWatchControlBlocks(
        "Visible before\n\n<WATCH_CURRENT_STATUS>Testing</WATCH_CURRENT_STATUS>\n\n" +
          "<WATCH_SUMMARY>**Done**</WATCH_SUMMARY>\n\nVisible after",
      ),
    )
  }

  @Test
  fun preservesControlTagExamplesInsideFencedCode() {
    assertEquals(
      "Before\n\n```xml\n<WATCH_SUMMARY>Example</WATCH_SUMMARY>\n```\n\nAfter",
      withoutWatchControlBlocks(
        "Before\n\n```xml\n<WATCH_SUMMARY>Example</WATCH_SUMMARY>\n```\n\n" +
          "<WATCH_CURRENT_STATUS>Real hint</WATCH_CURRENT_STATUS>\n\nAfter",
      ),
    )
  }

  @Test
  fun projectsCompleteControlsAndHidesIncompleteStreamingControl() {
    assertEquals(
      listOf(
        WatchControlSegment.Markdown("Before\n"),
        WatchControlSegment.Status("Testing"),
        WatchControlSegment.Markdown("\n"),
        WatchControlSegment.Summary("**Done**"),
      ),
      parseWatchControlSegments(
        "Before\n<WATCH_CURRENT_STATUS>Testing</WATCH_CURRENT_STATUS>\n" +
          "<WATCH_SUMMARY>**Done**</WATCH_SUMMARY>",
      ),
    )
    assertEquals(
      listOf(WatchControlSegment.Markdown("Visible\n")),
      parseWatchControlSegments("Visible\n<WATCH_CURRENT_STATUS>Still working"),
    )
  }

  @Test
  fun compactsEveryTurnWithAllStatusesAndSummaryOrFinalFallback() {
    val messages = listOf(
      WatchMessage("u1", WatchMessageRole.USER, "First", 1, 1),
      WatchMessage(
        "a1",
        WatchMessageRole.ASSISTANT,
        "<WATCH_CURRENT_STATUS>Reading</WATCH_CURRENT_STATUS>",
        2,
        2,
      ),
      WatchMessage(
        "a2",
        WatchMessageRole.ASSISTANT,
        "<WATCH_CURRENT_STATUS>Testing</WATCH_CURRENT_STATUS>\n" +
          "<WATCH_SUMMARY>**Done**</WATCH_SUMMARY>",
        3,
        3,
      ),
      WatchMessage("u2", WatchMessageRole.USER, "Second", 4, 4),
      WatchMessage("a3", WatchMessageRole.ASSISTANT, "Final fallback", 5, 5),
    )

    assertEquals(
      listOf(
        "u1",
        "glance:a1:status:0",
        "glance:a2:status:0",
        "glance:a2:summary:1",
        "u2",
        "glance:a3:reply",
      ),
      projectGlanceMessages(messages).map { it.id },
    )
    assertEquals(
      "<WATCH_CURRENT_STATUS>Reading</WATCH_CURRENT_STATUS>",
      projectGlanceMessages(messages)[1].text,
    )
    assertEquals("Final fallback", projectGlanceMessages(messages).last().text)
  }

  @Test
  fun preservesEarlierFinalReplyWhenTrailingAssistantContainsOnlyStatus() {
    val projected = projectGlanceMessages(
      listOf(
        WatchMessage("u1", WatchMessageRole.USER, "Question", 1, 1),
        WatchMessage("a1", WatchMessageRole.ASSISTANT, "Final answer", 2, 2),
        WatchMessage(
          "a2",
          WatchMessageRole.ASSISTANT,
          "<WATCH_CURRENT_STATUS>Wrapping up</WATCH_CURRENT_STATUS>",
          3,
          3,
        ),
      ),
    )

    assertEquals(
      listOf("u1", "glance:a2:status:0", "glance:a1:reply"),
      projected.map { it.id },
    )
    assertEquals("Final answer", projected.last().text)
  }

  @Test
  fun keepsExistingStatusIdsStableAsLaterStreamingStatusesArrive() {
    val first = WatchMessage(
      "a1",
      WatchMessageRole.ASSISTANT,
      "<WATCH_CURRENT_STATUS>Reading</WATCH_CURRENT_STATUS>",
      2,
      2,
    )
    val updated = first.copy(
      text = first.text + "\n<WATCH_CURRENT_STATUS>Testing</WATCH_CURRENT_STATUS>",
    )

    assertEquals(
      listOf("glance:a1:status:0"),
      projectGlanceMessages(listOf(first)).map { it.id },
    )
    assertEquals(
      listOf("glance:a1:status:0", "glance:a1:status:1"),
      projectGlanceMessages(listOf(updated)).map { it.id },
    )
  }
}
