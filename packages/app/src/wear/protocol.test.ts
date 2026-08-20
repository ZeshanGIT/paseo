import { describe, expect, it } from "vitest";
import { parseWatchCommand } from "./protocol";

describe("parseWatchCommand", () => {
  it("accepts a compact prompt command", () => {
    expect(
      parseWatchCommand({
        v: 1,
        id: "request-1",
        kind: "SEND_PROMPT",
        workspaceId: "workspace-1",
        agentId: "agent-1",
        text: "Run the tests",
      }),
    ).toEqual({
      v: 1,
      id: "request-1",
      kind: "SEND_PROMPT",
      workspaceId: "workspace-1",
      agentId: "agent-1",
      text: "Run the tests",
    });
  });

  it("rejects an unversioned command", () => {
    expect(parseWatchCommand({ id: "request-1", kind: "GET_SNAPSHOT" })).toBeNull();
  });

  it("accepts loading an older conversation page", () => {
    expect(
      parseWatchCommand({
        v: 1,
        id: "request-2",
        kind: "LOAD_OLDER_MESSAGES",
        workspaceId: "workspace-1",
        agentId: "agent-1",
      }),
    ).toMatchObject({ kind: "LOAD_OLDER_MESSAGES", workspaceId: "workspace-1" });
  });

  it("accepts the frozen voice command envelope fields", () => {
    expect(
      parseWatchCommand({
        v: 1,
        id: "voice-1",
        kind: "START_VOICE_INPUT",
        workspaceId: "workspace-1",
        agentId: "agent-1",
        voiceId: "voice-id",
        format: "pcm_s16le_16000_mono",
      }),
    ).toMatchObject({ kind: "START_VOICE_INPUT", voiceId: "voice-id" });
    expect(
      parseWatchCommand({
        v: 1,
        id: "voice-2",
        kind: "STOP_VOICE_INPUT",
        voiceId: "voice-id",
        byteLength: 32,
        sha256: "a".repeat(64),
      }),
    ).toMatchObject({ kind: "STOP_VOICE_INPUT", byteLength: 32 });
  });

  it("rejects an unsupported voice recording format", () => {
    expect(
      parseWatchCommand({
        v: 1,
        id: "voice-1",
        kind: "START_VOICE_INPUT",
        voiceId: "voice-id",
        format: "opus",
      }),
    ).toBeNull();
  });

  it("rejects incomplete voice envelopes before they reach the native bridge", () => {
    expect(
      parseWatchCommand({
        v: 1,
        id: "voice-1",
        kind: "START_VOICE_INPUT",
        voiceId: "voice-id",
        format: "pcm_s16le_16000_mono",
      }),
    ).toBeNull();
    expect(
      parseWatchCommand({
        v: 1,
        id: "voice-2",
        kind: "STOP_VOICE_INPUT",
        voiceId: "voice-id",
        byteLength: 32,
        sha256: "not-a-digest",
      }),
    ).toBeNull();
    expect(
      parseWatchCommand({
        v: 1,
        id: "voice-3",
        kind: "SEND_TRANSCRIPT",
        voiceId: "voice-id",
        workspaceId: "workspace-1",
        agentId: "agent-1",
        text: "   ",
      }),
    ).toBeNull();
  });
});
