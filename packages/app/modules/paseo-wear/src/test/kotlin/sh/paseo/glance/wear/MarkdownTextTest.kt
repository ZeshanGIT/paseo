package sh.paseo.glance.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTextTest {
  @Test
  fun rendersCompactBlockAndInlineMarkdown() {
    val rendered = renderWatchMarkdown(
      "# Results\n- **Repo** at `./repo`\n---\n[Open](https://example.com)",
    )

    assertEquals("Results\n• Repo at ./repo\n────────\nOpen", rendered.text)
  }

  @Test
  fun removesFencedCodeMarkers() {
    assertEquals("val answer = 42", renderWatchMarkdown("```kotlin\nval answer = 42\n```").text)
  }

  @Test
  fun parsesTwoColumnTablesAsAdaptiveBlocks() {
    val blocks = parseWatchMarkdownBlocks(
      "Before\n\n| Item | Value |\n| --- | ---: |\n| Memory | **3.5 GB** |\n| Disk | `21 GB` |",
    )

    assertEquals(2, blocks.size)
    assertEquals(
      WatchMarkdownBlock.TableBlock(
        headers = listOf("Item", "Value"),
        rows = listOf(listOf("Memory", "**3.5 GB**"), listOf("Disk", "`21 GB`")),
      ),
      blocks[1],
    )
  }

  @Test
  fun leavesMalformedTablesAsText() {
    val blocks = parseWatchMarkdownBlocks("| A | B |\n| nope | --- |\n| 1 | 2 |")
    assertEquals(1, blocks.size)
    assertEquals("| A | B |\n| nope | --- |\n| 1 | 2 |", (blocks.single() as WatchMarkdownBlock.TextBlock).markdown)
  }

  @Test
  fun parsesThematicBreakAsAFullWidthBlock() {
    assertEquals(
      listOf(
        WatchMarkdownBlock.TextBlock("Before"),
        WatchMarkdownBlock.HorizontalRule,
        WatchMarkdownBlock.TextBlock("After"),
      ),
      parseWatchMarkdownBlocks("Before\n---\nAfter"),
    )
  }
}
