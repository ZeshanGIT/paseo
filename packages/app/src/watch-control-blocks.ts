export type WatchControlSegment =
  | { kind: "markdown"; text: string }
  | { kind: "status"; text: string }
  | { kind: "summary"; text: string };

const OPEN_TAG = /<WATCH_(CURRENT_STATUS|SUMMARY)>/gi;

interface TextRange {
  start: number;
  end: number;
}

function fencedCodeRanges(text: string): TextRange[] {
  const ranges: TextRange[] = [];
  const fence = /```[\s\S]*?(?:```|$)/g;
  let match: RegExpExecArray | null;
  while ((match = fence.exec(text)) !== null) {
    ranges.push({ start: match.index, end: match.index + match[0].length });
  }
  return ranges;
}

function isInsideRange(index: number, ranges: readonly TextRange[]): boolean {
  return ranges.some((range) => index >= range.start && index < range.end);
}

function appendMarkdown(segments: WatchControlSegment[], text: string): void {
  if (!text) return;
  const previous = segments.at(-1);
  if (previous?.kind === "markdown") previous.text += text;
  else segments.push({ kind: "markdown", text });
}

function findOutsideRanges(
  expression: RegExp,
  text: string,
  from: number,
  ranges: readonly TextRange[],
): RegExpExecArray | null {
  expression.lastIndex = from;
  let match: RegExpExecArray | null;
  while ((match = expression.exec(text)) !== null) {
    if (!isInsideRange(match.index, ranges)) return match;
  }
  return null;
}

/** Display-only projection. Canonical assistant text is never modified. */
export function parseWatchControlSegments(text: string): WatchControlSegment[] {
  const ranges = fencedCodeRanges(text);
  const segments: WatchControlSegment[] = [];
  let cursor = 0;

  while (cursor < text.length) {
    const opening = findOutsideRanges(OPEN_TAG, text, cursor, ranges);
    if (!opening) {
      appendMarkdown(segments, text.slice(cursor));
      break;
    }
    appendMarkdown(segments, text.slice(cursor, opening.index));
    const tag = opening[1]?.toUpperCase();
    const contentStart = opening.index + opening[0].length;
    const closing = findOutsideRanges(
      new RegExp(`</WATCH_${tag}>`, "gi"),
      text,
      contentStart,
      ranges,
    );
    if (!closing) break; // Hide an in-flight directive until its closing tag arrives.

    const value = text.slice(contentStart, closing.index).trim();
    if (value) {
      segments.push(
        tag === "CURRENT_STATUS"
          ? { kind: "status", text: value.slice(0, 240) }
          : { kind: "summary", text: value.slice(0, 4_000) },
      );
    }
    cursor = closing.index + closing[0].length;
  }

  return segments;
}
