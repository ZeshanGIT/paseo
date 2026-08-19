import { describe, expect, it } from "vitest";
import { parseWatchControlSegments } from "./watch-control-blocks";

describe("parseWatchControlSegments", () => {
  it("preserves surrounding Markdown and orders complete directives", () => {
    expect(
      parseWatchControlSegments(
        "Before\n\n<WATCH_CURRENT_STATUS>Testing</WATCH_CURRENT_STATUS>\n\n" +
          "<WATCH_SUMMARY>## Done\n\n- Passed</WATCH_SUMMARY>\n\nAfter",
      ),
    ).toEqual([
      { kind: "markdown", text: "Before\n\n" },
      { kind: "status", text: "Testing" },
      { kind: "markdown", text: "\n\n" },
      { kind: "summary", text: "## Done\n\n- Passed" },
      { kind: "markdown", text: "\n\nAfter" },
    ]);
  });

  it("preserves directive examples inside fenced code", () => {
    const source = "```xml\n<WATCH_SUMMARY>Example</WATCH_SUMMARY>\n```";
    expect(parseWatchControlSegments(source)).toEqual([{ kind: "markdown", text: source }]);
  });

  it("hides an incomplete in-flight directive without losing preceding text", () => {
    expect(parseWatchControlSegments("Visible\n<WATCH_CURRENT_STATUS>Still working")).toEqual([
      { kind: "markdown", text: "Visible\n" },
    ]);
  });
});
