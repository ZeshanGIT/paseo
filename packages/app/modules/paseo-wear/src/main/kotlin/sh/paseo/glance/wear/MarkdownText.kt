package sh.paseo.glance.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val InlineCodeStyle = SpanStyle(
  color = PaseoPalette.AccentBright,
  fontFamily = FontFamily.Monospace,
)

internal sealed interface WatchMarkdownBlock {
  data class TextBlock(val markdown: String) : WatchMarkdownBlock
  data class TableBlock(val headers: List<String>, val rows: List<List<String>>) : WatchMarkdownBlock
  data object HorizontalRule : WatchMarkdownBlock
}

@Composable
fun WatchMarkdownText(
  markdown: String,
  modifier: Modifier = Modifier,
  color: Color = PaseoPalette.Foreground,
  fontSize: TextUnit = 15.sp,
  lineHeight: TextUnit = 20.sp,
) {
  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(7.dp),
  ) {
    parseWatchMarkdownBlocks(markdown).forEach { block ->
      when (block) {
        is WatchMarkdownBlock.TextBlock -> Text(
          text = renderWatchMarkdown(block.markdown),
          color = color,
          fontSize = fontSize,
          lineHeight = lineHeight,
        )
        is WatchMarkdownBlock.TableBlock -> AdaptiveTable(block, color)
        WatchMarkdownBlock.HorizontalRule -> androidx.compose.foundation.layout.Box(
          modifier = Modifier.fillMaxWidth().height(1.dp).background(PaseoPalette.BorderAccent),
        )
      }
    }
  }
}

@Composable
private fun AdaptiveTable(table: WatchMarkdownBlock.TableBlock, color: Color) {
  val visibleRows = table.rows.take(6)
  Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
    visibleRows.forEach { row ->
      val stackTwoColumns = table.headers.size == 2 && (
        row.getOrElse(0) { "" }.length > 14 || row.getOrElse(1) { "" }.length > 28
      )
      if (table.headers.size == 2 && !stackTwoColumns) {
        Row(
          modifier = Modifier.fillMaxWidth().background(PaseoPalette.Surface2, RoundedCornerShape(9.dp)).padding(8.dp),
          horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
          Text(
            renderWatchMarkdown(row.getOrElse(0) { "" }),
            modifier = Modifier.weight(0.42f),
            color = PaseoPalette.ForegroundMuted,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            renderWatchMarkdown(row.getOrElse(1) { "" }),
            modifier = Modifier.weight(0.58f),
            color = color,
            fontSize = 12.sp,
            lineHeight = 15.sp,
          )
        }
      } else if (table.headers.size == 2) {
        Column(
          modifier = Modifier.fillMaxWidth().background(PaseoPalette.Surface2, RoundedCornerShape(9.dp)).padding(8.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Text(
            renderWatchMarkdown(row.getOrElse(0) { "" }),
            color = PaseoPalette.ForegroundMuted,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            renderWatchMarkdown(row.getOrElse(1) { "" }),
            color = color,
            fontSize = 12.sp,
            lineHeight = 15.sp,
          )
        }
      } else {
        Column(
          modifier = Modifier.fillMaxWidth().background(PaseoPalette.Surface2, RoundedCornerShape(9.dp)).padding(8.dp),
          verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
          table.headers.forEachIndexed { index, header ->
            Text(
              text = buildAnnotatedString {
                withStyle(SpanStyle(color = PaseoPalette.ForegroundMuted, fontWeight = FontWeight.SemiBold)) {
                  append(header)
                  append(": ")
                }
                append(renderWatchMarkdown(row.getOrElse(index) { "" }))
              },
              color = color,
              fontSize = 11.sp,
              lineHeight = 14.sp,
            )
          }
        }
      }
    }
    if (table.rows.size > visibleRows.size) {
      Text(
        "+${table.rows.size - visibleRows.size} more rows · Open on phone",
        color = PaseoPalette.ForegroundMuted,
        fontSize = 10.sp,
      )
    }
  }
}

private fun tableCells(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|")
  .split('|').map(String::trim)

private fun isTableSeparator(line: String): Boolean {
  val cells = tableCells(line)
  return cells.size >= 2 && cells.all { it.matches(Regex(":?-{3,}:?")) }
}

internal fun parseWatchMarkdownBlocks(markdown: String): List<WatchMarkdownBlock> {
  val lines = markdown.lines()
  val blocks = mutableListOf<WatchMarkdownBlock>()
  val text = mutableListOf<String>()
  fun flushText() {
    if (text.isNotEmpty()) {
      blocks += WatchMarkdownBlock.TextBlock(text.joinToString("\n"))
      text.clear()
    }
  }
  var index = 0
  var fenced = false
  while (index < lines.size) {
    val line = lines[index]
    if (line.trim().startsWith("```")) {
      fenced = !fenced
      text += line
      index += 1
      continue
    }
    val separator = lines.getOrNull(index + 1)
    if (!fenced && line.trim().matches(Regex("(-{3,}|\\*{3,}|_{3,})"))) {
      flushText()
      blocks += WatchMarkdownBlock.HorizontalRule
      index += 1
      continue
    }
    if (!fenced && line.contains('|') && separator != null && isTableSeparator(separator)) {
      val headers = tableCells(line)
      val rows = mutableListOf<List<String>>()
      var rowIndex = index + 2
      while (rowIndex < lines.size && lines[rowIndex].contains('|') && lines[rowIndex].isNotBlank()) {
        val cells = tableCells(lines[rowIndex])
        if (cells.size != headers.size) break
        rows += cells
        rowIndex += 1
      }
      if (rows.isNotEmpty()) {
        flushText()
        blocks += WatchMarkdownBlock.TableBlock(headers, rows)
        index = rowIndex
        continue
      }
    }
    text += line
    index += 1
  }
  flushText()
  return blocks
}

/** A deliberately compact Markdown subset for readable watch-sized chat bubbles. */
internal fun renderWatchMarkdown(markdown: String): AnnotatedString = buildAnnotatedString {
  var fenced = false
  var emittedLine = false
  markdown.lines().forEach { sourceLine ->
    val trimmed = sourceLine.trim()
    if (trimmed.startsWith("```")) {
      fenced = !fenced
    } else {
      if (emittedLine) append('\n')
      val heading = Regex("^(#{1,6})\\s+(.*)$").matchEntire(sourceLine)
      val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$").matchEntire(sourceLine)
      when {
        fenced -> withStyle(InlineCodeStyle) { append(sourceLine) }
        trimmed.matches(Regex("(-{3,}|\\*{3,}|_{3,})")) -> append("────────")
        heading != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
          appendInlineMarkdown(heading.groupValues[2])
        }
        bullet != null -> {
          append(bullet.groupValues[1])
          append("• ")
          appendInlineMarkdown(bullet.groupValues[2])
        }
        else -> appendInlineMarkdown(sourceLine)
      }
      emittedLine = true
    }
  }
}

private fun AnnotatedString.Builder.appendInlineMarkdown(source: String) {
  var cursor = 0
  while (cursor < source.length) {
    val remaining = source.substring(cursor)
    val link = Regex("^\\[([^]]+)]\\(([^)]+)\\)").find(remaining)
    when {
      link != null -> {
        withStyle(
          SpanStyle(
            color = PaseoPalette.AccentBright,
            textDecoration = TextDecoration.Underline,
          ),
        ) { append(link.groupValues[1]) }
        cursor += link.value.length
      }
      remaining.startsWith("`") -> {
        val end = source.indexOf('`', cursor + 1)
        if (end < 0) {
          append('`')
          cursor += 1
        } else {
          withStyle(InlineCodeStyle) { append(source.substring(cursor + 1, end)) }
          cursor = end + 1
        }
      }
      remaining.startsWith("**") || remaining.startsWith("__") -> {
        val delimiter = remaining.substring(0, 2)
        val end = source.indexOf(delimiter, cursor + 2)
        if (end < 0) {
          append(delimiter)
          cursor += 2
        } else {
          withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(source.substring(cursor + 2, end))
          }
          cursor = end + 2
        }
      }
      remaining.startsWith("*") || remaining.startsWith("_") -> {
        val delimiter = remaining.first()
        val end = source.indexOf(delimiter, cursor + 1)
        if (end < 0) {
          append(delimiter)
          cursor += 1
        } else {
          withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            append(source.substring(cursor + 1, end))
          }
          cursor = end + 1
        }
      }
      else -> {
        val next = listOf('`', '*', '_', '[')
          .map { source.indexOf(it, cursor).takeIf { position -> position >= 0 } ?: source.length }
          .minOrNull() ?: source.length
        if (next == cursor) {
          append(source[cursor])
          cursor += 1
        } else {
          append(source.substring(cursor, next))
          cursor = next
        }
      }
    }
  }
}
