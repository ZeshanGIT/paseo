export const WEAR_PROTOCOL_VERSION = 1 as const;
export const WEAR_BRIDGE_CAPABILITY = "paseo_glance_bridge_v1";
export const WEAR_REQUEST_PATH = "/paseo-glance/request";
export const WEAR_RESPONSE_PATH = "/paseo-glance/response";
export const WEAR_SNAPSHOT_PATH = "/paseo-glance/snapshot";
export const WEAR_VOICE_CHANNEL_PREFIX = "/paseo-glance/voice/v1/";
export const WEAR_VOICE_FORMAT = "pcm_s16le_16000_mono" as const;

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
  | "SEND_PROMPT"
  | "START_VOICE_INPUT"
  | "STOP_VOICE_INPUT"
  | "CANCEL_VOICE_INPUT"
  | "SEND_TRANSCRIPT"
  | "OPEN_ON_PHONE";

export interface WatchCommand {
  v: typeof WEAR_PROTOCOL_VERSION;
  id: string;
  kind: WatchCommandKind;
  projectId?: string;
  workspaceId?: string;
  agentId?: string;
  text?: string;
  voiceId?: string;
  format?: typeof WEAR_VOICE_FORMAT;
  byteLength?: number;
  sha256?: string;
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
  lastTurnDurationMs?: number;
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

export type WatchVoicePhase =
  | "ready"
  | "recording"
  | "uploading"
  | "transcribing"
  | "confirmation"
  | "error";

export type WatchVoiceErrorCode =
  | "PHONE_UNAVAILABLE"
  | "MIC_PERMISSION_DENIED"
  | "AUDIO_INTERRUPTED"
  | "UPLOAD_FAILED"
  | "TRANSCRIPTION_FAILED"
  | "TRANSCRIPT_EMPTY"
  | "CANCELED";

export interface WatchVoiceState {
  voiceId: string;
  phase: WatchVoicePhase;
  transcript?: string;
  errorCode?: WatchVoiceErrorCode;
  errorMessage?: string;
}

export interface WatchSnapshot {
  v: typeof WEAR_PROTOCOL_VERSION;
  generation: number;
  connection: "unavailable" | "paired_phone_available";
  projects: WatchProject[];
  sessions: WatchSession[];
  active?: WatchSession;
  conversation?: WatchConversation;
  voice?: WatchVoiceState;
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
    value === "SEND_PROMPT" ||
    value === "START_VOICE_INPUT" ||
    value === "STOP_VOICE_INPUT" ||
    value === "CANCEL_VOICE_INPUT" ||
    value === "SEND_TRANSCRIPT" ||
    value === "OPEN_ON_PHONE"
  );
}

function hasValidOptionalCommandFields(value: Record<string, unknown>): boolean {
  return (
    isOptionalString(value.projectId) &&
    isOptionalString(value.workspaceId) &&
    isOptionalString(value.agentId) &&
    isOptionalString(value.text) &&
    isOptionalString(value.voiceId) &&
    (value.format === undefined || value.format === WEAR_VOICE_FORMAT) &&
    (value.byteLength === undefined ||
      (typeof value.byteLength === "number" && Number.isSafeInteger(value.byteLength))) &&
    isOptionalString(value.sha256)
  );
}

function hasRequiredCommandFields(value: WatchCommand): boolean {
  switch (value.kind) {
    case "START_VOICE_INPUT":
      return Boolean(
        value.workspaceId && value.agentId && value.voiceId && value.format === WEAR_VOICE_FORMAT,
      );
    case "STOP_VOICE_INPUT":
      return Boolean(
        value.voiceId &&
        typeof value.byteLength === "number" &&
        value.byteLength >= 0 &&
        value.sha256 &&
        /^[a-fA-F0-9]{64}$/.test(value.sha256),
      );
    case "CANCEL_VOICE_INPUT":
      return Boolean(value.voiceId);
    case "SEND_TRANSCRIPT":
      return Boolean(value.voiceId && value.workspaceId && value.agentId && value.text?.trim());
    default:
      return true;
  }
}

export function parseWatchCommand(value: unknown): WatchCommand | null {
  if (!isRecord(value) || value.v !== WEAR_PROTOCOL_VERSION || typeof value.id !== "string") {
    return null;
  }
  if (!isWatchCommandKind(value.kind)) {
    return null;
  }
  if (!hasValidOptionalCommandFields(value)) {
    return null;
  }
  const command: WatchCommand = {
    v: WEAR_PROTOCOL_VERSION,
    id: value.id,
    kind: value.kind,
    ...(typeof value.projectId === "string" ? { projectId: value.projectId } : {}),
    ...(typeof value.workspaceId === "string" ? { workspaceId: value.workspaceId } : {}),
    ...(typeof value.agentId === "string" ? { agentId: value.agentId } : {}),
    ...(typeof value.text === "string" ? { text: value.text } : {}),
    ...(typeof value.voiceId === "string" ? { voiceId: value.voiceId } : {}),
    ...(value.format === WEAR_VOICE_FORMAT ? { format: WEAR_VOICE_FORMAT } : {}),
    ...(typeof value.byteLength === "number" ? { byteLength: value.byteLength } : {}),
    ...(typeof value.sha256 === "string" ? { sha256: value.sha256 } : {}),
  };
  return hasRequiredCommandFields(command) ? command : null;
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
