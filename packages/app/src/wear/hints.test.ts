import { describe, expect, it } from "vitest";
import type { WatchMessage } from "./protocol";
import { extractWatchHints } from "./hints";

function assistant(text: string, sequence = 1): WatchMessage {
  return { id: String(sequence), role: "assistant", text, timestamp: sequence, sequence };
}

describe("extractWatchHints", () => {
  it("uses the latest complete status and preserves Markdown summaries", () => {
    expect(
      extractWatchHints([
        assistant("<WATCH_CURRENT_STATUS>Reading files…</WATCH_CURRENT_STATUS>"),
        assistant(
          "<WATCH_CURRENT_STATUS>Running tests…</WATCH_CURRENT_STATUS>\n" +
            "<WATCH_SUMMARY>**Done**\n\n- Tests passed</WATCH_SUMMARY>",
          2,
        ),
      ]),
    ).toEqual({ currentStatus: "Running tests…", summary: "**Done**\n\n- Tests passed" });
  });

  it("ignores incomplete tags and examples inside fenced code", () => {
    expect(
      extractWatchHints([
        assistant("<WATCH_CURRENT_STATUS>Stable</WATCH_CURRENT_STATUS>"),
        assistant("<WATCH_CURRENT_STATUS>Still streaming"),
        assistant("```xml\n<WATCH_SUMMARY>Example only</WATCH_SUMMARY>\n```"),
      ]),
    ).toEqual({ currentStatus: "Stable" });
  });

  it("does not carry hints across a newer user turn", () => {
    expect(
      extractWatchHints([
        assistant("<WATCH_SUMMARY>Old result</WATCH_SUMMARY>"),
        { id: "user", role: "user", text: "New request", timestamp: 2, sequence: 2 },
      ]),
    ).toEqual({});
  });
});
