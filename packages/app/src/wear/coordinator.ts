import type { DaemonClient } from "@getpaseo/client/internal/daemon-client";
import {
  selectAgentTurnPresentation,
  useSessionStore,
  type SessionState,
} from "@/stores/session-store";
import { hasRunningWatchActivity, latestWatchActivity } from "./activity";
import { nativeWearBridge, type NativeWatchCommandEvent } from "./bridge";
import {
  latestAssistantReply,
  mergeConversationPage,
  toWatchConversation,
  type WatchConversationCache,
} from "./conversation-cache";
import {
  createWatchErrorResponse,
  parseWatchCommand,
  type WatchCommand,
  type WatchActivity,
  type WatchResponse,
  type WatchSnapshot,
  type WatchVoiceState,
} from "./protocol";
import { createWatchSnapshot } from "./reducer";
import { selectBestWearSession } from "./session-selection";
import { WatchCommandDeliveryGuard } from "./command-delivery-guard";
import {
  createWearDaemonDictationProvider,
  type TranscriptionProvider,
} from "./transcription-provider";
import { navigateToHostWorkspaceRoute } from "@/navigation/workspace-route-navigation";
import { buildHostAgentDetailRoute, buildHostRootRoute } from "@/utils/host-routes";

interface WearSessionSource {
  session: SessionState;
  client: DaemonClient;
}

const INITIAL_CONVERSATION_PAGE_SIZE = 8;
const OLDER_CONVERSATION_PAGE_SIZE = 8;

function describeWearSession(session: SessionState) {
  return {
    serverId: session.serverId,
    connected: Boolean(session.client?.isConnected),
    projectIds: [...session.projects.keys()],
    projectLabels: [...session.projects.values()].map(
      (project) => project.projectCustomName ?? project.projectDisplayName,
    ),
    workspaceCount: session.workspaces.size,
    agentCount: session.agents.size,
    hasHydratedAgents: session.hasHydratedAgents,
    hasHydratedWorkspaces: session.hasHydratedWorkspaces,
    hasWorkspaceDirectorySnapshot: session.hasWorkspaceDirectorySnapshot,
  };
}

function findOnlineSession(): WearSessionSource | null {
  const sessions = Object.values(useSessionStore.getState().sessions);
  console.warn("[WearDebug] Online session candidates", sessions.map(describeWearSession));
  const source = selectBestWearSession(sessions);
  if (source?.client) {
    console.warn("[WearDebug] Selected session", describeWearSession(source));
    return { session: source, client: source.client };
  }
  console.warn("[WearDebug] No connected session", { sessionCount: sessions.length });
  return null;
}

function getSnapshot(
  generation: number,
  cachedLatestReplies: ReadonlyMap<string, string> = new Map(),
  conversationCache: WatchConversationCache | null = null,
  lastTurnDurationByAgentId: ReadonlyMap<string, number> = new Map(),
  forcedWorkingAgentId: string | null = null,
): WatchSnapshot {
  const source = findOnlineSession();
  if (!source) {
    console.warn("[WearDebug] Snapshot unavailable", { generation });
    return createWatchSnapshot({ generation, phoneAvailable: false, workspaces: [], agents: [] });
  }
  console.warn("[WearDebug] Snapshot source", {
    generation,
    ...describeWearSession(source.session),
  });
  const latestRepliesByAgentId = new Map(cachedLatestReplies);
  const currentActivityByAgentId = new Map<string, WatchActivity>();
  const turnPresentationByAgentId = new Map<
    string,
    { isActive: boolean; startedAt: Date | null }
  >();
  for (const agent of source.session.agents.values()) {
    const presentation = selectAgentTurnPresentation(source.session, agent.id);
    turnPresentationByAgentId.set(
      agent.id,
      agent.id === forcedWorkingAgentId
        ? {
            ...presentation,
            isActive: true,
            startedAt: presentation.startedAt ?? new Date(),
          }
        : presentation,
    );
    const items = [
      ...(source.session.agentStreamTail.get(agent.id) ?? []),
      ...(source.session.agentStreamHead.get(agent.id) ?? []),
    ];
    for (let index = items.length - 1; index >= 0; index -= 1) {
      const item = items[index];
      if (item?.kind === "assistant_message" && item.text.trim()) {
        latestRepliesByAgentId.set(agent.id, item.text.trim());
        break;
      }
    }
    const activity = latestWatchActivity(items);
    if (activity) currentActivityByAgentId.set(agent.id, activity);
  }
  const snapshot = createWatchSnapshot({
    generation,
    phoneAvailable: true,
    projects: source.session.projects.values(),
    workspaces: source.session.workspaces.values(),
    agents: source.session.agents.values(),
    latestRepliesByAgentId,
    currentActivityByAgentId,
    turnPresentationByAgentId,
    lastTurnDurationByAgentId,
  });
  console.warn("[WearDebug] Snapshot built", {
    generation: snapshot.generation,
    connection: snapshot.connection,
    projectIds: snapshot.projects.map((project) => project.projectId),
    projectLabels: snapshot.projects.map((project) => project.label),
    sessionCount: snapshot.sessions.length,
  });
  const conversation = toWatchConversation(conversationCache);
  return conversation ? { ...snapshot, conversation } : snapshot;
}

function findWorkspace(
  command: WatchCommand,
): { source: WearSessionSource; agentId: string } | null {
  if (!command.workspaceId) return null;
  const source = findOnlineSession();
  if (!source) return null;
  const agentId =
    command.agentId ??
    source.session.agents.values().find((agent) => agent.workspaceId === command.workspaceId)?.id;
  return agentId ? { source, agentId } : null;
}

function isExplicitlyFinishedAgent(
  agent:
    | { status: string; requiresAttention?: boolean; attentionReason?: string | null }
    | undefined,
): boolean {
  return (
    agent?.status === "closed" ||
    agent?.status === "error" ||
    (agent?.requiresAttention === true && agent.attentionReason === "finished")
  );
}

export class WearBridgeCoordinator {
  private generation = 0;
  private stopNative: (() => void) | null = null;
  private stopStore: (() => void) | null = null;
  private publishTimer: ReturnType<typeof setTimeout> | null = null;
  private watchedRefreshTimer: ReturnType<typeof setTimeout> | null = null;
  private replyPollTimer: ReturnType<typeof setTimeout> | null = null;
  private replyPollGeneration = 0;
  private replyPollingActive = false;
  private watchedAgentId: string | null = null;
  private watchedWorkspaceId: string | null = null;
  private conversationCache: WatchConversationCache | null = null;
  private latestRepliesByAgentId = new Map<string, string>();
  private lastTurnDurationByAgentId = new Map<string, number>();
  private watchedTurnActive = false;
  private watchedPresentationActive = false;
  private watchedTurnStartedAt: number | null = null;
  private lastObservedUserMessageId: string | null = null;
  private watchedSessionGeneration = 0;
  private watchedRefreshChain: Promise<void> = Promise.resolve();
  private voice: WatchVoiceState | null = null;
  private readonly voiceClients = new Map<string, DaemonClient>();
  private readonly transcriptionProvider: TranscriptionProvider;
  private readonly commandDeliveryGuard = new WatchCommandDeliveryGuard();

  constructor(transcriptionProvider?: TranscriptionProvider) {
    this.transcriptionProvider =
      transcriptionProvider ??
      createWearDaemonDictationProvider({
        getConnectedClient: (voiceId) => this.voiceClients.get(voiceId) ?? null,
        readVoiceTransferChunk: (voiceId, offset, maxBytes) =>
          nativeWearBridge.readVoiceTransferChunk(voiceId, offset, maxBytes),
      });
  }

  start(): void {
    if (this.stopNative) return;
    console.warn("[WearDebug] Coordinator started");
    this.stopNative = nativeWearBridge.subscribe((event) => void this.handleNativeCommand(event));
    this.stopStore = useSessionStore.subscribe(() => this.schedulePublish());
    void this.publish().catch((error) =>
      console.warn("[WearBridge] Initial publish failed", error),
    );
  }

  stop(): void {
    this.stopNative?.();
    this.stopStore?.();
    this.stopNative = null;
    this.stopStore = null;
    if (this.publishTimer) clearTimeout(this.publishTimer);
    if (this.watchedRefreshTimer) clearTimeout(this.watchedRefreshTimer);
    this.publishTimer = null;
    this.watchedRefreshTimer = null;
    this.watchedSessionGeneration += 1;
    this.cancelReplyPolling();
  }

  private schedulePublish(): void {
    const source = findOnlineSession();
    const presentation =
      source && this.watchedAgentId
        ? selectAgentTurnPresentation(source.session, this.watchedAgentId)
        : null;
    if (presentation?.isActive && !this.watchedPresentationActive && this.watchedAgentId) {
      this.watchedPresentationActive = true;
      this.beginWatchedTurn(presentation.startedAt?.getTime() ?? Date.now());
      if (this.publishTimer) clearTimeout(this.publishTimer);
      this.publishTimer = null;
      void this.publish().catch((error) =>
        console.warn("[WearBridge] Start publish failed", error),
      );
    } else if (presentation && !presentation.isActive) {
      this.watchedPresentationActive = false;
    }
    this.scheduleWatchedConversationRefresh();
    this.ensureWatchedConversationPolling();
    if (this.publishTimer) clearTimeout(this.publishTimer);
    this.publishTimer = setTimeout(() => {
      this.publishTimer = null;
      void this.publish().catch((error) => console.warn("[WearBridge] Publish failed", error));
    }, 250);
  }

  private async publish(): Promise<void> {
    this.generation += 1;
    await nativeWearBridge.publishSnapshot(
      JSON.stringify(
        this.withVoice(
          getSnapshot(
            this.generation,
            this.latestRepliesByAgentId,
            this.conversationCache,
            this.lastTurnDurationByAgentId,
            this.watchedTurnActive ? this.watchedAgentId : null,
          ),
        ),
      ),
    );
  }

  private async refreshConversation(
    client: DaemonClient,
    workspaceId: string,
    agentId: string,
    isCurrent: () => boolean = () => true,
  ): Promise<string | null> {
    const page = await client.fetchAgentTimeline(agentId, {
      direction: "tail",
      limit: INITIAL_CONVERSATION_PAGE_SIZE,
      projection: "projected",
      timeout: 10_000,
    });
    if (!isCurrent()) return null;
    this.conversationCache = mergeConversationPage({
      current: this.conversationCache,
      workspaceId,
      agentId,
      page,
    });
    const reply = latestAssistantReply(this.conversationCache);
    if (reply) this.latestRepliesByAgentId.set(agentId, reply);
    return reply;
  }

  private enqueueWatchedRefresh(
    client: DaemonClient,
    workspaceId: string,
    agentId: string,
  ): Promise<string | null> {
    const generation = this.watchedSessionGeneration;
    let result: string | null = null;
    const task = this.watchedRefreshChain.then(async () => {
      if (generation !== this.watchedSessionGeneration) return undefined;
      result = await this.refreshConversation(
        client,
        workspaceId,
        agentId,
        () =>
          generation === this.watchedSessionGeneration &&
          this.watchedWorkspaceId === workspaceId &&
          this.watchedAgentId === agentId,
      );
      return undefined;
    });
    this.watchedRefreshChain = task.catch(() => undefined);
    return task.then(() => result);
  }

  private enqueueOlderConversation(client: DaemonClient): Promise<void> {
    const generation = this.watchedSessionGeneration;
    const workspaceId = this.watchedWorkspaceId;
    const agentId = this.watchedAgentId;
    const task = this.watchedRefreshChain.then(async () => {
      if (generation !== this.watchedSessionGeneration) return undefined;
      await this.loadOlderConversation(
        client,
        () =>
          generation === this.watchedSessionGeneration &&
          this.watchedWorkspaceId === workspaceId &&
          this.watchedAgentId === agentId,
      );
      return undefined;
    });
    this.watchedRefreshChain = task.catch(() => undefined);
    return task;
  }

  private async loadOlderConversation(
    client: DaemonClient,
    isCurrent: () => boolean = () => true,
  ): Promise<void> {
    for (let attempt = 0; attempt < 8; attempt += 1) {
      const cache = this.conversationCache;
      if (!cache?.hasOlder || !cache.startCursor) return;
      const previousMessageCount = cache.messages.length;
      const page = await client.fetchAgentTimeline(cache.agentId, {
        direction: "before",
        cursor: cache.startCursor,
        limit: OLDER_CONVERSATION_PAGE_SIZE,
        projection: "projected",
        timeout: 10_000,
      });
      if (!isCurrent()) return;
      this.conversationCache = mergeConversationPage({
        current: cache,
        workspaceId: cache.workspaceId,
        agentId: cache.agentId,
        page,
      });
      if (
        this.conversationCache.messages.length > previousMessageCount ||
        !this.conversationCache.hasOlder
      ) {
        return;
      }
    }
  }

  private startReplyPolling(
    client: DaemonClient,
    workspaceId: string,
    agentId: string,
    previousReply: string | null,
  ): void {
    this.cancelReplyPolling();
    const pollGeneration = this.replyPollGeneration;
    this.replyPollingActive = true;
    let attempts = 0;
    let sawNewReply = false;
    let lastReply = previousReply;
    let stableFinishedPolls = 0;
    let activityRunning = false;

    const poll = async () => {
      if (pollGeneration !== this.replyPollGeneration) return;
      this.replyPollTimer = null;
      attempts += 1;
      try {
        const reply = await this.enqueueWatchedRefresh(client, workspaceId, agentId);
        if (pollGeneration !== this.replyPollGeneration) return;
        await this.publish();
        if (reply && reply !== previousReply) sawNewReply = true;
        const liveSession = findOnlineSession()?.session;
        const agent = liveSession?.agents.get(agentId);
        const running = liveSession
          ? selectAgentTurnPresentation(liveSession, agentId).isActive
          : false;
        activityRunning = this.hasRunningWatchedActivity(liveSession, agentId);
        const explicitlyFinished = isExplicitlyFinishedAgent(agent);
        if (sawNewReply && !running && !activityRunning && reply === lastReply) {
          stableFinishedPolls += 1;
          if (stableFinishedPolls >= (explicitlyFinished ? 1 : 2)) {
            this.completeWatchedTurn();
            await this.publish();
            this.finishReplyPolling(pollGeneration);
            return;
          }
        } else {
          stableFinishedPolls = 0;
        }
        lastReply = reply;
      } catch (error) {
        console.warn("[WearBridge] Reply refresh failed", error);
      }
      if (pollGeneration !== this.replyPollGeneration) return;
      if (pollGeneration === this.replyPollGeneration && (attempts < 80 || activityRunning)) {
        this.replyPollTimer = setTimeout(() => void poll(), 1_500);
      } else {
        this.completeWatchedTurn();
        await this.publish();
        this.finishReplyPolling(pollGeneration);
      }
    };

    this.replyPollTimer = setTimeout(() => void poll(), 750);
  }

  private cancelReplyPolling(): void {
    if (this.replyPollTimer) clearTimeout(this.replyPollTimer);
    this.replyPollTimer = null;
    this.replyPollingActive = false;
    this.replyPollGeneration += 1;
  }

  private hasRunningWatchedActivity(session: SessionState | undefined, agentId: string): boolean {
    const streamItems = session
      ? [
          ...(session.agentStreamTail.get(agentId) ?? []),
          ...(session.agentStreamHead.get(agentId) ?? []),
        ]
      : [];
    if (hasRunningWatchActivity(streamItems)) return true;
    return (
      this.conversationCache?.agentId === agentId &&
      this.conversationCache.activities.some((activity) => activity.state === "running")
    );
  }

  private finishReplyPolling(generation: number): void {
    if (generation !== this.replyPollGeneration) return;
    this.replyPollTimer = null;
    this.replyPollingActive = false;
  }

  private beginWatchedTurn(startedAt: number): void {
    if (!this.watchedAgentId) return;
    this.watchedTurnActive = true;
    this.watchedTurnStartedAt = startedAt;
    this.lastTurnDurationByAgentId.delete(this.watchedAgentId);
  }

  private completeWatchedTurn(): void {
    if (!this.watchedAgentId || !this.watchedTurnActive) return;
    const startedAt = this.watchedTurnStartedAt;
    this.watchedTurnActive = false;
    this.watchedTurnStartedAt = null;
    if (startedAt != null) {
      this.lastTurnDurationByAgentId.set(this.watchedAgentId, Math.max(0, Date.now() - startedAt));
    }
  }

  private scheduleWatchedConversationRefresh(): void {
    if (!this.watchedAgentId || !this.watchedWorkspaceId || this.watchedRefreshTimer) return;
    this.watchedRefreshTimer = setTimeout(() => {
      this.watchedRefreshTimer = null;
      const source = findOnlineSession();
      const agentId = this.watchedAgentId;
      const workspaceId = this.watchedWorkspaceId;
      if (!source || !agentId || !workspaceId) return;
      const refreshGeneration = this.watchedSessionGeneration;
      const previousReply = latestAssistantReply(this.conversationCache);
      void this.enqueueWatchedRefresh(source.client, workspaceId, agentId)
        .then(() => {
          if (refreshGeneration !== this.watchedSessionGeneration) return;
          const latestUser = this.conversationCache?.messages.findLast(
            (message) => message.role === "user",
          )?.id;
          if (latestUser && latestUser !== this.lastObservedUserMessageId) {
            this.lastObservedUserMessageId = latestUser;
            this.beginWatchedTurn(Date.now());
            if (!this.replyPollingActive) {
              this.startReplyPolling(source.client, workspaceId, agentId, previousReply);
            }
          }
          return this.publish();
        })
        .catch((error) => console.warn("[WearBridge] Watched refresh failed", error))
        .finally(() => {
          if (
            refreshGeneration === this.watchedSessionGeneration &&
            this.watchedWorkspaceId === workspaceId &&
            this.watchedAgentId === agentId &&
            !this.watchedRefreshTimer
          ) {
            this.watchedRefreshTimer = setTimeout(() => {
              this.watchedRefreshTimer = null;
              this.scheduleWatchedConversationRefresh();
            }, 1_250);
          }
        });
    }, 100);
  }

  private ensureWatchedConversationPolling(): void {
    if (this.replyPollingActive || !this.watchedAgentId || !this.watchedWorkspaceId) return;
    const source = findOnlineSession();
    if (!source || !selectAgentTurnPresentation(source.session, this.watchedAgentId).isActive)
      return;
    this.startReplyPolling(
      source.client,
      this.watchedWorkspaceId,
      this.watchedAgentId,
      latestAssistantReply(this.conversationCache),
    );
  }

  private async refreshWatchedConversation(): Promise<void> {
    if (!this.watchedAgentId || !this.watchedWorkspaceId) return;
    const source = findOnlineSession();
    if (!source) return;
    await this.enqueueWatchedRefresh(
      source.client,
      this.watchedWorkspaceId,
      this.watchedAgentId,
    ).catch((error) => console.warn("[WearBridge] Manual conversation refresh failed", error));
  }

  private async handleNativeCommand(event: NativeWatchCommandEvent): Promise<void> {
    if (!this.commandDeliveryGuard.accept(event.deliveryId)) return;
    let value: unknown;
    try {
      value = JSON.parse(event.command);
    } catch {
      await nativeWearBridge.sendResponse(
        event.nodeId,
        JSON.stringify(
          createWatchErrorResponse({
            id: "unknown",
            code: "BAD_REQUEST",
            message: "Invalid command",
          }),
        ),
      );
      return;
    }
    const command = parseWatchCommand(value);
    if (!command) {
      await nativeWearBridge.sendResponse(
        event.nodeId,
        JSON.stringify(
          createWatchErrorResponse({
            id: "unknown",
            code: "BAD_REQUEST",
            message: "Invalid command",
          }),
        ),
      );
      return;
    }
    const response = await this.dispatch(command, event.nodeId).catch((error) => {
      console.warn("[WearBridge] Command failed", { kind: command.kind, error });
      return createWatchErrorResponse({
        id: command.id,
        code: "ACTION_FAILED",
        message: "The phone could not complete this request",
      });
    });
    await nativeWearBridge.sendResponse(event.nodeId, JSON.stringify(response));
  }

  private async dispatch(command: WatchCommand, nodeId: string): Promise<WatchResponse> {
    console.warn("[WearDebug] Command received", {
      kind: command.kind,
      id: command.id,
      nodeId,
    });
    if (command.kind === "GET_SNAPSHOT") await this.refreshWatchedConversation();
    const snapshot = this.withVoice(
      getSnapshot(
        this.generation,
        this.latestRepliesByAgentId,
        this.conversationCache,
        this.lastTurnDurationByAgentId,
        this.watchedTurnActive ? this.watchedAgentId : null,
      ),
    );
    console.warn("[WearDebug] Command response snapshot", {
      kind: command.kind,
      id: command.id,
      projectIds: snapshot.projects.map((project) => project.projectId),
      sessionCount: snapshot.sessions.length,
    });
    if (command.kind === "GET_SNAPSHOT" || command.kind === "GET_PROJECTS") {
      return { v: 1, id: command.id, ok: true, snapshot };
    }
    if (command.kind === "GET_SESSIONS") {
      if (!command.projectId)
        return createWatchErrorResponse({
          id: command.id,
          code: "BAD_REQUEST",
          message: "A project is required",
        });
      return {
        ...this.success(command.id, {
          ...snapshot,
          sessions: snapshot.sessions.filter((session) => {
            const workspace = findOnlineSession()?.session.workspaces.get(session.workspaceId);
            return workspace?.projectId === command.projectId;
          }),
        }),
      };
    }
    if (command.kind === "OPEN_SESSION") return this.openSession(command, snapshot);
    if (command.kind === "LOAD_OLDER_MESSAGES") return this.loadOlderMessages(command);
    if (command.kind === "CREATE_SESSION") {
      return createWatchErrorResponse({
        id: command.id,
        code: "ACTION_FAILED",
        message: "Create session is not available in this build",
      });
    }
    if (command.kind === "START_VOICE_INPUT") return this.startVoiceInput(command, nodeId);
    if (command.kind === "STOP_VOICE_INPUT") return this.stopVoiceInput(command);
    if (command.kind === "CANCEL_VOICE_INPUT") return this.cancelVoiceInput(command);
    if (command.kind === "SEND_TRANSCRIPT") return this.sendTranscript(command);
    if (command.kind === "OPEN_ON_PHONE") return this.openOnPhone(command);
    return this.sendPrompt(command);
  }

  private withVoice(snapshot: WatchSnapshot): WatchSnapshot {
    return this.voice ? { ...snapshot, voice: this.voice } : snapshot;
  }

  private async setVoice(voice: WatchVoiceState | null): Promise<void> {
    this.voice = voice;
    await this.publish();
  }

  private async startVoiceInput(command: WatchCommand, nodeId: string): Promise<WatchResponse> {
    if (!command.voiceId || command.format !== "pcm_s16le_16000_mono") {
      return createWatchErrorResponse({
        id: command.id,
        code: "BAD_REQUEST",
        message: "Invalid voice request",
      });
    }
    const target = findWorkspace(command);
    if (!target) {
      return createWatchErrorResponse({
        id: command.id,
        code: "NOT_FOUND",
        message: "Agent session not found",
      });
    }
    if (this.voice) await this.cancelVoiceInput({ ...command, kind: "CANCEL_VOICE_INPUT" });
    try {
      await nativeWearBridge.registerVoiceTransfer(nodeId, command.voiceId);
      this.voiceClients.set(command.voiceId, target.source.client);
      await this.setVoice({ voiceId: command.voiceId, phase: "recording" });
      return this.success(command.id, this.withVoice(getSnapshot(this.generation)));
    } catch {
      await this.setVoice({
        voiceId: command.voiceId,
        phase: "error",
        errorCode: "PHONE_UNAVAILABLE",
        errorMessage: "Phone voice transfer is unavailable",
      });
      return this.success(command.id, this.withVoice(getSnapshot(this.generation)));
    }
  }

  private async stopVoiceInput(command: WatchCommand): Promise<WatchResponse> {
    if (
      !command.voiceId ||
      !command.sha256 ||
      command.byteLength === undefined ||
      this.voice?.voiceId !== command.voiceId
    ) {
      return createWatchErrorResponse({
        id: command.id,
        code: "BAD_REQUEST",
        message: "Unknown voice recording",
      });
    }
    try {
      await this.setVoice({ voiceId: command.voiceId, phase: "uploading" });
      await nativeWearBridge.finishVoiceTransfer(
        command.voiceId,
        command.byteLength,
        command.sha256,
      );
    } catch (error) {
      console.warn("[WearBridge] Watch audio transfer failed", {
        voiceId: command.voiceId,
        error: error instanceof Error ? error.message : String(error),
      });
      await this.setVoice({
        voiceId: command.voiceId,
        phase: "error",
        errorCode: "UPLOAD_FAILED",
        errorMessage: "Audio transfer to phone failed",
      });
      await nativeWearBridge.cancelVoiceTransfer(command.voiceId).catch(() => undefined);
      this.voiceClients.delete(command.voiceId);
      return this.success(command.id, this.withVoice(getSnapshot(this.generation)));
    }

    try {
      await this.setVoice({ voiceId: command.voiceId, phase: "transcribing" });
      const transcript = (
        await this.transcriptionProvider.transcribe({ voiceId: command.voiceId })
      ).trim();
      await this.setVoice(
        transcript
          ? { voiceId: command.voiceId, phase: "confirmation", transcript }
          : {
              voiceId: command.voiceId,
              phase: "error",
              errorCode: "TRANSCRIPT_EMPTY",
              errorMessage: "No speech detected",
            },
      );
    } catch (error) {
      console.warn("[WearBridge] Paseo transcription failed", {
        voiceId: command.voiceId,
        error: error instanceof Error ? error.message : String(error),
      });
      await this.setVoice({
        voiceId: command.voiceId,
        phase: "error",
        errorCode: "TRANSCRIPTION_FAILED",
        errorMessage: "Couldn’t transcribe through Paseo",
      });
    } finally {
      // V1 never retains raw Watch audio after the one transcription attempt.
      await nativeWearBridge.cancelVoiceTransfer(command.voiceId).catch(() => undefined);
      this.voiceClients.delete(command.voiceId);
    }
    return this.success(command.id, this.withVoice(getSnapshot(this.generation)));
  }

  private async cancelVoiceInput(command: WatchCommand): Promise<WatchResponse> {
    if (command.voiceId) {
      await Promise.allSettled([
        nativeWearBridge.cancelVoiceTransfer(command.voiceId),
        this.transcriptionProvider.cancel(command.voiceId),
      ]);
      this.voiceClients.delete(command.voiceId);
    }
    if (!command.voiceId || this.voice?.voiceId === command.voiceId) await this.setVoice(null);
    return this.success(command.id, this.withVoice(getSnapshot(this.generation)));
  }

  private async sendTranscript(command: WatchCommand): Promise<WatchResponse> {
    if (
      !command.voiceId ||
      !command.text?.trim() ||
      this.voice?.voiceId !== command.voiceId ||
      this.voice.phase !== "confirmation"
    ) {
      return createWatchErrorResponse({
        id: command.id,
        code: "BAD_REQUEST",
        message: "A confirmed transcript is required",
      });
    }
    const response = await this.sendPrompt({ ...command, kind: "SEND_PROMPT" });
    if (response.ok) await this.setVoice(null);
    return response;
  }

  private async openOnPhone(command: WatchCommand): Promise<WatchResponse> {
    const target = command.workspaceId ? findWorkspace(command) : null;
    const source = target?.source ?? findOnlineSession();
    const agent = target?.source.session.agents.get(target.agentId);
    let route = "/";
    if (source) route = buildHostRootRoute(source.session.serverId);
    if (source && target && agent?.serverId) {
      route = buildHostAgentDetailRoute(agent.serverId, target.agentId, command.workspaceId);
    }
    try {
      await nativeWearBridge.openOnPhone(route);
      if (target) navigateToHostWorkspaceRoute(route);
      return this.success(command.id, this.withVoice(getSnapshot(this.generation)));
    } catch {
      return createWatchErrorResponse({
        id: command.id,
        code: "ACTION_FAILED",
        message: "Could not open Paseo on the phone",
      });
    }
  }

  private async openSession(
    command: WatchCommand,
    initialSnapshot: WatchSnapshot,
  ): Promise<WatchResponse> {
    if (!command.workspaceId)
      return createWatchErrorResponse({
        id: command.id,
        code: "BAD_REQUEST",
        message: "A workspace is required",
      });
    let snapshot = initialSnapshot;
    let session = snapshot.sessions.find((item) => item.workspaceId === command.workspaceId);
    if (session?.agentId) {
      this.cancelReplyPolling();
      if (this.watchedRefreshTimer) clearTimeout(this.watchedRefreshTimer);
      this.watchedRefreshTimer = null;
      this.watchedSessionGeneration += 1;
      this.watchedTurnActive = false;
      this.watchedPresentationActive = false;
      this.watchedTurnStartedAt = null;
      this.lastObservedUserMessageId = null;
      this.watchedAgentId = session.agentId;
      this.watchedWorkspaceId = session.workspaceId;
      const source = findOnlineSession();
      if (source) {
        await this.enqueueWatchedRefresh(source.client, session.workspaceId, session.agentId).catch(
          (error) => console.warn("[WearBridge] Conversation refresh failed", error),
        );
        this.lastObservedUserMessageId =
          this.conversationCache?.messages.findLast((message) => message.role === "user")?.id ??
          null;
        snapshot = getSnapshot(
          this.generation,
          this.latestRepliesByAgentId,
          this.conversationCache,
          this.lastTurnDurationByAgentId,
          this.watchedTurnActive ? this.watchedAgentId : null,
        );
        session = snapshot.sessions.find((item) => item.workspaceId === command.workspaceId);
        this.ensureWatchedConversationPolling();
        this.scheduleWatchedConversationRefresh();
      }
    }
    return session
      ? this.success(command.id, { ...snapshot, active: session })
      : createWatchErrorResponse({
          id: command.id,
          code: "NOT_FOUND",
          message: "Session not found",
        });
  }

  private async loadOlderMessages(command: WatchCommand): Promise<WatchResponse> {
    const source = findOnlineSession();
    if (!source || !this.conversationCache)
      return createWatchErrorResponse({
        id: command.id,
        code: "UNAVAILABLE",
        message: "Conversation history is unavailable",
      });
    await this.enqueueOlderConversation(source.client);
    return this.success(
      command.id,
      getSnapshot(
        this.generation,
        this.latestRepliesByAgentId,
        this.conversationCache,
        this.lastTurnDurationByAgentId,
        this.watchedTurnActive ? this.watchedAgentId : null,
      ),
    );
  }

  private async sendPrompt(command: WatchCommand): Promise<WatchResponse> {
    if (!command.text?.trim())
      return createWatchErrorResponse({
        id: command.id,
        code: "BAD_REQUEST",
        message: "A prompt is required",
      });
    const target = findWorkspace(command);
    if (!target)
      return createWatchErrorResponse({
        id: command.id,
        code: "NOT_FOUND",
        message: "Agent session not found",
      });
    try {
      this.watchedAgentId = target.agentId;
      this.watchedWorkspaceId = command.workspaceId ?? null;
      const previousReply =
        latestAssistantReply(this.conversationCache) ??
        this.latestRepliesByAgentId.get(target.agentId) ??
        null;
      this.beginWatchedTurn(Date.now());
      // Publish the optimistic working state before awaiting the daemon call.
      // `sendAgentMessage` can remain pending until the first stream/tool event,
      // which otherwise leaves the watch with no visible progress indicator.
      await this.publish().catch((error) =>
        console.warn("[WearBridge] Initial working publish failed", error),
      );
      await target.source.client.sendAgentMessage(target.agentId, command.text.trim());
      await this.publish();
      if (this.watchedWorkspaceId) {
        this.startReplyPolling(
          target.source.client,
          this.watchedWorkspaceId,
          target.agentId,
          previousReply,
        );
      }
      return this.success(
        command.id,
        getSnapshot(
          this.generation,
          this.latestRepliesByAgentId,
          this.conversationCache,
          this.lastTurnDurationByAgentId,
          this.watchedTurnActive ? this.watchedAgentId : null,
        ),
      );
    } catch {
      this.completeWatchedTurn();
      await this.publish().catch((error) =>
        console.warn("[WearBridge] Failed to clear working state", error),
      );
      return createWatchErrorResponse({
        id: command.id,
        code: "ACTION_FAILED",
        message: "Paseo could not send the prompt",
      });
    }
  }

  private success(id: string, snapshot: WatchSnapshot): WatchResponse {
    return { v: 1, id, ok: true, snapshot: this.withVoice(snapshot) };
  }
}

let coordinator: WearBridgeCoordinator | null = null;

export function startWearBridge(): () => void {
  if (!coordinator) {
    coordinator = new WearBridgeCoordinator();
    coordinator.start();
  }
  return () => {
    coordinator?.stop();
    coordinator = null;
  };
}
