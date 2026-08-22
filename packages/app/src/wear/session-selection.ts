import type { SessionState } from "@/stores/session-store";

function compareSessionData(left: SessionState, right: SessionState): number {
  const projectCount = right.projects.size - left.projects.size;
  if (projectCount !== 0) return projectCount;

  const workspaceCount = right.workspaces.size - left.workspaces.size;
  if (workspaceCount !== 0) return workspaceCount;

  const agentCount = right.agents.size - left.agents.size;
  if (agentCount !== 0) return agentCount;

  const directorySnapshot =
    Number(right.hasWorkspaceDirectorySnapshot) - Number(left.hasWorkspaceDirectorySnapshot);
  if (directorySnapshot !== 0) return directorySnapshot;

  const hydratedWorkspaces =
    Number(right.hasHydratedWorkspaces) - Number(left.hasHydratedWorkspaces);
  if (hydratedWorkspaces !== 0) return hydratedWorkspaces;

  return Number(right.hasHydratedAgents) - Number(left.hasHydratedAgents);
}

export function selectBestWearSession(sessions: readonly SessionState[]): SessionState | null {
  let selected: SessionState | null = null;
  for (const session of sessions) {
    if (!session.client?.isConnected) continue;
    if (!selected || compareSessionData(selected, session) > 0) selected = session;
  }
  return selected;
}
