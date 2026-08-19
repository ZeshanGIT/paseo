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
});
