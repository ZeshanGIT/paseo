import { describe, expect, it } from "vitest";
import type { StreamItem } from "@/types/stream";
import { hasRunningWatchActivity } from "./activity";

function thought(status: string): StreamItem {
  return {
    kind: "thought",
    id: `thought-${status}`,
    text: "working",
    status,
    timestamp: new Date("2026-01-01T00:00:00Z"),
  } as StreamItem;
}

describe("hasRunningWatchActivity", () => {
  it("keeps a turn active while a stream activity is loading", () => {
    expect(hasRunningWatchActivity([thought("loading")])).toBe(true);
  });

  it("allows completion after the stream activity settles", () => {
    expect(hasRunningWatchActivity([thought("completed")])).toBe(false);
  });
});
