export const WEAR_PROTOCOL_VERSION = 1 as const;
export const WEAR_BRIDGE_CAPABILITY = "paseo_glance_bridge_v1";
export const WEAR_REQUEST_PATH = "/paseo-glance/request";
export const WEAR_RESPONSE_PATH = "/paseo-glance/response";
export const WEAR_SNAPSHOT_PATH = "/paseo-glance/snapshot";

export type WatchState =
  | "idle"
  | "connecting"
  | "working"
  | "waiting_for_input"
  | "needs_attention"
  | "complete"
  | "failed"
  | "unavailable";

export type WatchCommandKind =
  | "GET_SNAPSHOT"
  | "GET_PROJECTS"
  | "GET_SESSIONS"
  | "OPEN_SESSION"
  | "LOAD_OLDER_MESSAGES"
  | "CREATE_SESSION"
  | "SEND_PROMPT";

export interface WatchCommand {
  v: typeof WEAR_PROTOCOL_VERSION;
  id: string;
  kind: WatchCommandKind;
  projectId?: string;
  workspaceId?: string;
  agentId?: string;
  text?: string;
}

export interface WatchProject {
  projectId: string;
  label: string;
}

export interface WatchSession {
  workspaceId: string;
  projectId: string;
  agentId?: string;
  label: string;
  state: WatchState;
  shortStatus: string;
  updatedAt: number;
  pinnedAt?: number;
  latestReply?: string;
  activeTurnStartedAt?: number;
  model?: string;
  effort?: string;
  currentActivity?: WatchActivity;
}

export interface WatchMessage {
  id: string;
  role: "user" | "assistant";
  text: string;
  timestamp: number;
  sequence: number;
  pending?: boolean;
}

export type WatchActivityKind = "tool" | "reasoning" | "todo" | "error" | "compaction";
export type WatchActivityState = "running" | "completed" | "failed" | "canceled";

export interface WatchActivity {
  id: string;
  kind: WatchActivityKind;
  icon: string;
  label: string;
  detail?: string;
  state: WatchActivityState;
  timestamp: number;
  sequence: number;
}

export interface WatchConversation {
  workspaceId: string;
  agentId: string;
  messages: WatchMessage[];
  activities: WatchActivity[];
  hasOlder: boolean;
  currentStatus?: string;
  summary?: string;
}

export interface WatchSnapshot {
  v: typeof WEAR_PROTOCOL_VERSION;
  generation: number;
  connection: "unavailable" | "paired_phone_available";
  projects: WatchProject[];
  sessions: WatchSession[];
  active?: WatchSession;
  conversation?: WatchConversation;
}

export interface WatchError {
  code: "BAD_REQUEST" | "UNAVAILABLE" | "NOT_FOUND" | "ACTION_FAILED";
  message: string;
}

export interface WatchResponse {
  v: typeof WEAR_PROTOCOL_VERSION;
  id: string;
  ok: boolean;
  snapshot?: WatchSnapshot;
  error?: WatchError;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isOptionalString(value: unknown): value is string | undefined {
  return value === undefined || typeof value === "string";
}

function isWatchCommandKind(value: unknown): value is WatchCommandKind {
  return (
    value === "GET_SNAPSHOT" ||
    value === "GET_PROJECTS" ||
    value === "GET_SESSIONS" ||
    value === "OPEN_SESSION" ||
    value === "LOAD_OLDER_MESSAGES" ||
    value === "CREATE_SESSION" ||
    value === "SEND_PROMPT"
  );
}

export function parseWatchCommand(value: unknown): WatchCommand | null {
  if (!isRecord(value) || value.v !== WEAR_PROTOCOL_VERSION || typeof value.id !== "string") {
    return null;
  }
  if (!isWatchCommandKind(value.kind)) {
    return null;
  }
  if (
    !isOptionalString(value.projectId) ||
    !isOptionalString(value.workspaceId) ||
    !isOptionalString(value.agentId) ||
    !isOptionalString(value.text)
  ) {
    return null;
  }
  return {
    v: WEAR_PROTOCOL_VERSION,
    id: value.id,
    kind: value.kind,
    ...(typeof value.projectId === "string" ? { projectId: value.projectId } : {}),
    ...(typeof value.workspaceId === "string" ? { workspaceId: value.workspaceId } : {}),
    ...(typeof value.agentId === "string" ? { agentId: value.agentId } : {}),
    ...(typeof value.text === "string" ? { text: value.text } : {}),
  };
}

export function createWatchErrorResponse(input: {
  id: string;
  code: WatchError["code"];
  message: string;
}): WatchResponse {
  return {
    v: WEAR_PROTOCOL_VERSION,
    id: input.id,
    ok: false,
    error: { code: input.code, message: input.message },
  };
}
