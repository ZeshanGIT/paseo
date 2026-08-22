import { describe, expect, it } from "vitest";
import type { SessionState } from "@/stores/session-store";
import { selectBestWearSession } from "./session-selection";

function fakeSession(
  serverId: string,
  options: { connected?: boolean; projectCount?: number; workspaceCount?: number } = {},
): SessionState {
  const projectCount = options.projectCount ?? 0;
  const workspaceCount = options.workspaceCount ?? 0;
  return {
    serverId,
    client: { isConnected: options.connected ?? true },
    projects: new Map(Array.from({ length: projectCount }, (_, index) => [`project-${index}`, {}])),
    workspaces: new Map(
      Array.from({ length: workspaceCount }, (_, index) => [`workspace-${index}`, {}]),
    ),
    agents: new Map(),
    hasHydratedAgents: true,
    hasHydratedWorkspaces: true,
    hasWorkspaceDirectorySnapshot: true,
  } as unknown as SessionState;
}

describe("selectBestWearSession", () => {
  it("prefers the connected session with authoritative project data", () => {
    const empty = fakeSession("empty", { workspaceCount: 0 });
    const populated = fakeSession("populated", { projectCount: 4, workspaceCount: 24 });

    expect(selectBestWearSession([empty, populated])).toBe(populated);
  });

  it("ignores disconnected sessions even when they have more data", () => {
    const connected = fakeSession("connected", { projectCount: 1 });
    const disconnected = fakeSession("disconnected", {
      connected: false,
      projectCount: 4,
    });

    expect(selectBestWearSession([disconnected, connected])).toBe(connected);
  });
});
