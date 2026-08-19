import { describe, expect, it } from "vitest";
import type { FetchAgentTimelinePayload } from "@getpaseo/client/internal/daemon-client";
import { mergeConversationPage } from "./conversation-cache";

function page(
  direction: "tail" | "before",
  entries: FetchAgentTimelinePayload["entries"],
  hasOlder: boolean,
): FetchAgentTimelinePayload {
  return {
    requestId: direction,
    agentId: "agent-1",
    agent: null,
    direction,
    projection: "projected",
    epoch: "epoch-1",
    reset: false,
    staleCursor: false,
    gap: false,
    window: { minSeq: 1, maxSeq: 20, nextSeq: 21 },
    startCursor: { epoch: "epoch-1", seq: entries[0]?.seqStart ?? 0 },
    endCursor: { epoch: "epoch-1", seq: entries.at(-1)?.seqEnd ?? 0 },
    hasOlder,
    hasNewer: false,
    entries,
    error: null,
  };
}

function entry(seq: number, type: "user_message" | "assistant_message", text: string) {
  return {
    provider: "codex" as const,
    item: { type, text },
    timestamp: `2026-01-01T00:00:${String(seq).padStart(2, "0")}Z`,
    seqStart: seq,
    seqEnd: seq,
    sourceSeqRanges: [{ startSeq: seq, endSeq: seq }],
    collapsed: [],
  };
}

describe("mergeConversationPage", () => {
  it("keeps user and assistant messages and prepends an older page", () => {
    const recent = mergeConversationPage({
      current: null,
      workspaceId: "workspace-1",
      agentId: "agent-1",
      page: page(
        "tail",
        [entry(10, "user_message", "Question"), entry(20, "assistant_message", "Answer")],
        true,
      ),
    });
    const merged = mergeConversationPage({
      current: recent,
      workspaceId: "workspace-1",
      agentId: "agent-1",
      page: page("before", [entry(1, "user_message", "Earlier")], false),
    });

    expect(merged.messages.map(({ role, text }) => ({ role, text }))).toEqual([
      { role: "user", text: "Earlier" },
      { role: "user", text: "Question" },
      { role: "assistant", text: "Answer" },
    ]);
    expect(merged.hasOlder).toBe(false);
    expect(merged.activities).toEqual([]);
    expect(merged.startCursor).toEqual({ epoch: "epoch-1", seq: 1 });
  });

  it("projects tool calls into bounded Full-mode activities", () => {
    const toolEntry: FetchAgentTimelinePayload["entries"][number] = {
      provider: "codex",
      item: {
        type: "tool_call",
        callId: "call-1",
        name: "shell",
        status: "running",
        error: null,
        detail: { type: "shell", command: "npm test" },
      },
      timestamp: "2026-01-01T00:00:02Z",
      seqStart: 2,
      seqEnd: 2,
      sourceSeqRanges: [{ startSeq: 2, endSeq: 2 }],
      collapsed: [],
    };
    const cache = mergeConversationPage({
      current: null,
      workspaceId: "workspace-1",
      agentId: "agent-1",
      page: page("tail", [toolEntry], false),
    });

    expect(cache.activities).toEqual([
      expect.objectContaining({
        kind: "tool",
        icon: "shell",
        label: "Shell",
        detail: "npm test",
        state: "running",
      }),
    ]);
  });

  it("replaces evolving timeline entries by stable sequence identity", () => {
    const initial = mergeConversationPage({
      current: null,
      workspaceId: "workspace-1",
      agentId: "agent-1",
      page: page("tail", [{ ...entry(4, "assistant_message", "Gathering"), seqEnd: 5 }], false),
    });
    const updated = mergeConversationPage({
      current: initial,
      workspaceId: "workspace-1",
      agentId: "agent-1",
      page: page(
        "tail",
        [{ ...entry(4, "assistant_message", "Gathering\nProcessing"), seqEnd: 7 }],
        false,
      ),
    });

    expect(updated.messages).toHaveLength(1);
    expect(updated.messages[0]?.text).toBe("Gathering\nProcessing");
  });
});
