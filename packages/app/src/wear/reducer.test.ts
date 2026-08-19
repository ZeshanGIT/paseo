import { describe, expect, it } from "vitest";
import { createWatchSnapshot } from "./reducer";

describe("createWatchSnapshot", () => {
  it("reduces a running agent into a compact working session", () => {
    const currentActivity = {
      id: "tool-1",
      kind: "tool" as const,
      icon: "shell",
      label: "Shell",
      detail: "npm test",
      state: "running" as const,
      timestamp: 6,
      sequence: 7,
    };
    const snapshot = createWatchSnapshot({
      generation: 3,
      phoneAvailable: true,
      workspaces: [
        {
          id: "workspace-1",
          projectId: "project-1",
          projectDisplayName: "Paseo",
          projectCustomName: null,
          title: "General assistance",
          name: "main",
          pinnedAt: "2026-01-01T00:02:00Z",
          statusEnteredAt: new Date("2026-01-01T00:00:00Z"),
        },
      ],
      latestRepliesByAgentId: new Map([["agent-1", "The sync fix is ready."]]),
      currentActivityByAgentId: new Map([["agent-1", currentActivity]]),
      agents: [
        {
          id: "agent-1",
          status: "running",
          updatedAt: new Date("2026-01-01T00:01:00Z"),
          pendingPermissions: [],
          title: "Fix sync",
          workspaceId: "workspace-1",
          activeTurn: { startedAt: new Date("2026-01-01T00:00:30Z") },
          model: "gpt-5.6-sol",
          thinkingOptionId: "medium",
        },
      ],
    });
    expect(snapshot).toEqual({
      v: 1,
      generation: 3,
      connection: "paired_phone_available",
      projects: [{ projectId: "project-1", label: "Paseo" }],
      sessions: [
        {
          workspaceId: "workspace-1",
          projectId: "project-1",
          agentId: "agent-1",
          label: "General assistance",
          state: "working",
          shortStatus: "Working",
          updatedAt: 1767225660000,
          pinnedAt: 1767225720000,
          latestReply: "The sync fix is ready.",
          activeTurnStartedAt: 1767225630000,
          model: "gpt-5.6-sol",
          effort: "medium",
          currentActivity,
        },
      ],
      active: {
        workspaceId: "workspace-1",
        projectId: "project-1",
        agentId: "agent-1",
        label: "General assistance",
        state: "working",
        shortStatus: "Working",
        updatedAt: 1767225660000,
        pinnedAt: 1767225720000,
        latestReply: "The sync fix is ready.",
        activeTurnStartedAt: 1767225630000,
        model: "gpt-5.6-sol",
        effort: "medium",
        currentActivity,
      },
    });
  });
});
