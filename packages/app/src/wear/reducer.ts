import {
  WEAR_PROTOCOL_VERSION,
  type WatchProject,
  type WatchSession,
  type WatchSnapshot,
} from "./protocol";

export interface WearWorkspace {
  id: string;
  projectId: string;
  projectDisplayName: string;
  projectCustomName?: string | null;
  title?: string | null;
  name: string;
  pinnedAt?: string | null;
  statusEnteredAt: Date | null;
}

export interface WearAgent {
  id: string;
  workspaceId?: string;
  status: "closed" | "error" | "idle" | "initializing" | "running";
  updatedAt: Date;
  requiresAttention?: boolean;
  attentionReason?: "finished" | "error" | "permission" | null;
  pendingPermissions: unknown[];
  lastError?: string | null;
  title: string | null;
  activeTurn?: { startedAt: Date | null } | null;
  model?: string | null;
  runtimeInfo?: { model?: string | null };
  thinkingOptionId?: string | null;
}

export interface WearSnapshotInput {
  generation: number;
  phoneAvailable: boolean;
  workspaces: Iterable<WearWorkspace>;
  agents: Iterable<WearAgent>;
  latestRepliesByAgentId?: ReadonlyMap<string, string>;
  currentActivityByAgentId?: ReadonlyMap<string, WatchSession["currentActivity"]>;
}

function toWatchState(agent: WearAgent | null): WatchSession["state"] {
  if (!agent) return "idle";
  if (agent.requiresAttention)
    return agent.attentionReason === "finished" ? "complete" : "needs_attention";
  if (agent.pendingPermissions.length > 0) return "waiting_for_input";
  if (agent.status === "error") return "failed";
  if (agent.status === "running") return "working";
  return "idle";
}

function toShortStatus(agent: WearAgent | null): string {
  if (!agent) return "Ready";
  if (agent.requiresAttention && agent.attentionReason === "finished") return "Complete";
  if (agent.requiresAttention) return "Needs your input";
  if (agent.pendingPermissions.length > 0) return "Waiting for approval";
  if (agent.status === "error") return agent.lastError ?? "Task failed";
  if (agent.status === "running") return "Working";
  return "Ready";
}

// Optional wire fields are intentionally assembled in one compatibility boundary.
// eslint-disable-next-line complexity
export function createWatchSnapshot(input: WearSnapshotInput): WatchSnapshot {
  const projectsById = new Map<string, WatchProject>();
  const agentsByWorkspace = new Map<string, WearAgent>();
  for (const agent of input.agents) {
    if (agent.workspaceId) agentsByWorkspace.set(agent.workspaceId, agent);
  }

  const sessions: WatchSession[] = [];
  for (const workspace of input.workspaces) {
    projectsById.set(workspace.projectId, {
      projectId: workspace.projectId,
      label: workspace.projectCustomName ?? workspace.projectDisplayName,
    });
    const agent = agentsByWorkspace.get(workspace.id) ?? null;
    const latestReply = agent ? input.latestRepliesByAgentId?.get(agent.id)?.trim() : undefined;
    sessions.push({
      workspaceId: workspace.id,
      projectId: workspace.projectId,
      ...(agent ? { agentId: agent.id } : {}),
      label: workspace.title ?? agent?.title ?? workspace.name,
      state: toWatchState(agent),
      shortStatus: toShortStatus(agent),
      updatedAt: (agent?.updatedAt ?? workspace.statusEnteredAt ?? new Date(0)).getTime(),
      ...(workspace.pinnedAt ? { pinnedAt: new Date(workspace.pinnedAt).getTime() } : {}),
      ...(latestReply ? { latestReply: latestReply.slice(-1_200) } : {}),
      ...(agent?.activeTurn?.startedAt
        ? { activeTurnStartedAt: agent.activeTurn.startedAt.getTime() }
        : {}),
      ...((agent?.runtimeInfo?.model ?? agent?.model)
        ? { model: (agent?.runtimeInfo?.model ?? agent?.model) as string }
        : {}),
      ...(agent?.thinkingOptionId ? { effort: agent.thinkingOptionId } : {}),
      ...(agent && input.currentActivityByAgentId?.get(agent.id)
        ? { currentActivity: input.currentActivityByAgentId.get(agent.id) }
        : {}),
    });
  }
  sessions.sort((left, right) => right.updatedAt - left.updatedAt);
  const active = sessions.find(
    (session) => session.state === "working" || session.state === "needs_attention",
  );
  return {
    v: WEAR_PROTOCOL_VERSION,
    generation: input.generation,
    connection: input.phoneAvailable ? "paired_phone_available" : "unavailable",
    projects: [...projectsById.values()].sort((left, right) =>
      left.label.localeCompare(right.label),
    ),
    sessions,
    ...(active ? { active } : {}),
  };
}
