package sh.paseo.glance.wear

internal sealed interface WatchControlSegment {
  data class Markdown(val text: String) : WatchControlSegment
  data class Status(val text: String) : WatchControlSegment
  data class Summary(val markdown: String) : WatchControlSegment
}

private val WatchOpeningTag = Regex(
  "<WATCH_(CURRENT_STATUS|SUMMARY)>",
  RegexOption.IGNORE_CASE,
)

private fun MutableList<WatchControlSegment>.appendMarkdown(text: String) {
  if (text.isEmpty()) return
  val previous = lastOrNull()
  if (previous is WatchControlSegment.Markdown) {
    this[lastIndex] = WatchControlSegment.Markdown(previous.text + text)
  } else {
    add(WatchControlSegment.Markdown(text))
  }
}

private fun parsePlainWatchControls(text: String): List<WatchControlSegment> {
  val segments = mutableListOf<WatchControlSegment>()
  var cursor = 0
  while (cursor < text.length) {
    val opening = WatchOpeningTag.find(text, cursor)
    if (opening == null) {
      segments.appendMarkdown(text.substring(cursor))
      break
    }
    segments.appendMarkdown(text.substring(cursor, opening.range.first))
    val tag = opening.groupValues[1].uppercase()
    val contentStart = opening.range.last + 1
    val closing = Regex("</WATCH_$tag>", RegexOption.IGNORE_CASE).find(text, contentStart)
    if (closing == null) break // Hide an in-flight directive until it is complete.
    val value = text.substring(contentStart, closing.range.first).trim()
    if (value.isNotEmpty()) {
      segments += if (tag == "CURRENT_STATUS") {
        WatchControlSegment.Status(value.take(240))
      } else {
        WatchControlSegment.Summary(value.take(4_000))
      }
    }
    cursor = closing.range.last + 1
  }
  return segments
}

/** Display-only projection; the raw protocol message remains unchanged. */
internal fun parseWatchControlSegments(text: String): List<WatchControlSegment> {
  val segments = mutableListOf<WatchControlSegment>()
  val plainText = StringBuilder()
  var fenced = false
  fun flushPlainText() {
    parsePlainWatchControls(plainText.toString()).forEach { segment ->
      if (segment is WatchControlSegment.Markdown) segments.appendMarkdown(segment.text)
      else segments += segment
    }
    plainText.clear()
  }

  val lines = text.split('\n')
  lines.forEachIndexed { index, line ->
    val segment = line + if (index < lines.lastIndex) "\n" else ""
    if (line.trimStart().startsWith("```")) {
      if (!fenced) flushPlainText()
      segments.appendMarkdown(segment)
      fenced = !fenced
    } else if (fenced) {
      segments.appendMarkdown(segment)
    } else {
      plainText.append(segment)
    }
  }
  flushPlainText()
  return segments
}

internal fun withoutWatchControlBlocks(text: String): String = parseWatchControlSegments(text)
  .filterIsInstance<WatchControlSegment.Markdown>()
  .joinToString("") { it.text }
  .replace(Regex("\\n{3,}"), "\n\n")
  .trim()

/** Plain-text list preview that never exposes the transport control tags. */
internal fun watchPreviewText(text: String): String? {
  val segments = parseWatchControlSegments(text)
  val summary = segments.filterIsInstance<WatchControlSegment.Summary>().lastOrNull()?.markdown
  val markdown = segments.filterIsInstance<WatchControlSegment.Markdown>()
    .joinToString("") { it.text }
    .trim()
  val status = segments.filterIsInstance<WatchControlSegment.Status>().lastOrNull()?.text
  return sequenceOf(summary, markdown, status).firstOrNull { !it.isNullOrBlank() }?.trim()
}

/** Projects every loaded turn to user text, individual status hints, then summary or final reply. */
internal fun projectGlanceMessages(messages: List<WatchMessage>): List<WatchMessage> {
  val projected = mutableListOf<WatchMessage>()
  val assistants = mutableListOf<WatchMessage>()

  fun flushTurn() {
    if (assistants.isEmpty()) return
    var latestSummary: Triple<WatchMessage, Int, String>? = null
    assistants.forEach { message ->
      var controlOrdinal = 0
      parseWatchControlSegments(message.text).forEach { segment ->
        when (segment) {
          is WatchControlSegment.Status -> {
            projected += message.copy(
              id = "glance:${message.id}:status:$controlOrdinal",
              text = "<WATCH_CURRENT_STATUS>${segment.text}</WATCH_CURRENT_STATUS>",
            )
            controlOrdinal += 1
          }
          is WatchControlSegment.Summary -> {
            latestSummary = Triple(message, controlOrdinal, segment.markdown)
            controlOrdinal += 1
          }
          is WatchControlSegment.Markdown -> Unit
        }
      }
    }
    if (latestSummary != null) {
      val (message, ordinal, summary) = latestSummary!!
      projected += message.copy(
        id = "glance:${message.id}:summary:$ordinal",
        text = "<WATCH_SUMMARY>$summary</WATCH_SUMMARY>",
      )
    } else {
      val fallback = assistants.asReversed().firstNotNullOfOrNull { message ->
        withoutWatchControlBlocks(message.text).takeIf(String::isNotBlank)?.let { message to it }
      }
      fallback?.let { (message, text) ->
        projected += message.copy(id = "glance:${message.id}:reply", text = text)
      }
    }
    assistants.clear()
  }

  messages.forEach { message ->
    if (message.role == WatchMessageRole.USER) {
      flushTurn()
      projected += message
    } else {
      assistants += message
    }
  }
  flushTurn()
  return projected
}
