import type { DaemonClient } from "@getpaseo/client/internal/daemon-client";
import {
  selectAgentTurnPresentation,
  useSessionStore,
  type SessionState,
} from "@/stores/session-store";
import { latestWatchActivity } from "./activity";
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
} from "./protocol";
import { createWatchSnapshot } from "./reducer";

interface WearSessionSource {
  session: SessionState;
  client: DaemonClient;
}

const INITIAL_CONVERSATION_PAGE_SIZE = 8;
const OLDER_CONVERSATION_PAGE_SIZE = 8;

function findOnlineSession(): WearSessionSource | null {
  for (const session of Object.values(useSessionStore.getState().sessions)) {
    if (session.client?.isConnected) return { session, client: session.client };
  }
  return null;
}

function getSnapshot(
  generation: number,
  cachedLatestReplies: ReadonlyMap<string, string> = new Map(),
  conversationCache: WatchConversationCache | null = null,
  lastTurnDurationByAgentId: ReadonlyMap<string, number> = new Map(),
): WatchSnapshot {
  const source = findOnlineSession();
  if (!source) {
    return createWatchSnapshot({ generation, phoneAvailable: false, workspaces: [], agents: [] });
  }
  const latestRepliesByAgentId = new Map(cachedLatestReplies);
  const currentActivityByAgentId = new Map<string, WatchActivity>();
  const turnPresentationByAgentId = new Map<
    string,
    { isActive: boolean; startedAt: Date | null }
  >();
  for (const agent of source.session.agents.values()) {
    turnPresentationByAgentId.set(agent.id, selectAgentTurnPresentation(source.session, agent.id));
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
    workspaces: source.session.workspaces.values(),
    agents: source.session.agents.values(),
    latestRepliesByAgentId,
    currentActivityByAgentId,
    turnPresentationByAgentId,
    lastTurnDurationByAgentId,
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
  private replyPollTimer: ReturnType<typeof setTimeout> | null = null;
  private replyPollGeneration = 0;
  private replyPollingActive = false;
  private watchedAgentId: string | null = null;
  private watchedWorkspaceId: string | null = null;
  private conversationCache: WatchConversationCache | null = null;
  private latestRepliesByAgentId = new Map<string, string>();
  private lastTurnDurationByAgentId = new Map<string, number>();
  private watchedTurnActive = false;
  private watchedTurnStartedAt: number | null = null;

  start(): void {
    if (this.stopNative) return;
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
    this.publishTimer = null;
    this.cancelReplyPolling();
  }

  private schedulePublish(): void {
    const source = findOnlineSession();
    const presentation =
      source && this.watchedAgentId
        ? selectAgentTurnPresentation(source.session, this.watchedAgentId)
        : null;
    if (presentation?.isActive && !this.watchedTurnActive && this.watchedAgentId) {
      this.watchedTurnActive = true;
      this.watchedTurnStartedAt = presentation.startedAt?.getTime() ?? Date.now();
      this.lastTurnDurationByAgentId.delete(this.watchedAgentId);
      if (this.publishTimer) clearTimeout(this.publishTimer);
      this.publishTimer = null;
      void this.publish().catch((error) =>
        console.warn("[WearBridge] Start publish failed", error),
      );
    } else if (
      presentation &&
      !presentation.isActive &&
      this.watchedTurnActive &&
      this.watchedAgentId
    ) {
      const completedAgentId = this.watchedAgentId;
      const startedAt = this.watchedTurnStartedAt;
      this.watchedTurnActive = false;
      this.watchedTurnStartedAt = null;
      if (startedAt != null) {
        this.lastTurnDurationByAgentId.set(completedAgentId, Math.max(0, Date.now() - startedAt));
      }
      if (this.publishTimer) clearTimeout(this.publishTimer);
      this.publishTimer = null;
      void this.refreshWatchedConversation()
        .then(() => this.publish())
        .catch((error) => console.warn("[WearBridge] Completion publish failed", error));
    }
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
        getSnapshot(
          this.generation,
          this.latestRepliesByAgentId,
          this.conversationCache,
          this.lastTurnDurationByAgentId,
        ),
      ),
    );
  }

  private async refreshConversation(
    client: DaemonClient,
    workspaceId: string,
    agentId: string,
  ): Promise<string | null> {
    const page = await client.fetchAgentTimeline(agentId, {
      direction: "tail",
      limit: INITIAL_CONVERSATION_PAGE_SIZE,
      projection: "projected",
      timeout: 10_000,
    });
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

  private async loadOlderConversation(client: DaemonClient): Promise<void> {
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

    const poll = async () => {
      if (pollGeneration !== this.replyPollGeneration) return;
      this.replyPollTimer = null;
      attempts += 1;
      try {
        const reply = await this.refreshConversation(client, workspaceId, agentId);
        await this.publish();
        if (reply && reply !== previousReply) sawNewReply = true;
        const liveSession = findOnlineSession()?.session;
        const agent = liveSession?.agents.get(agentId);
        const running = liveSession
          ? selectAgentTurnPresentation(liveSession, agentId).isActive
          : false;
        const explicitlyFinished = isExplicitlyFinishedAgent(agent);
        if (sawNewReply && !running && reply === lastReply) {
          stableFinishedPolls += 1;
          if (stableFinishedPolls >= (explicitlyFinished ? 1 : 8)) {
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
      if (pollGeneration === this.replyPollGeneration && attempts < 80) {
        this.replyPollTimer = setTimeout(() => void poll(), 1_500);
      } else {
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

  private finishReplyPolling(generation: number): void {
    if (generation !== this.replyPollGeneration) return;
    this.replyPollTimer = null;
    this.replyPollingActive = false;
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
    await this.refreshConversation(
      source.client,
      this.watchedWorkspaceId,
      this.watchedAgentId,
    ).catch((error) => console.warn("[WearBridge] Manual conversation refresh failed", error));
  }

  private async handleNativeCommand(event: NativeWatchCommandEvent): Promise<void> {
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
    const response = await this.dispatch(command).catch((error) => {
      console.warn("[WearBridge] Command failed", { kind: command.kind, error });
      return createWatchErrorResponse({
        id: command.id,
        code: "ACTION_FAILED",
        message: "The phone could not complete this request",
      });
    });
    await nativeWearBridge.sendResponse(event.nodeId, JSON.stringify(response));
  }

  private async dispatch(command: WatchCommand): Promise<WatchResponse> {
    if (command.kind === "GET_SNAPSHOT") await this.refreshWatchedConversation();
    const snapshot = getSnapshot(
      this.generation,
      this.latestRepliesByAgentId,
      this.conversationCache,
      this.lastTurnDurationByAgentId,
    );
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
    return this.sendPrompt(command);
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
      this.watchedAgentId = session.agentId;
      this.watchedWorkspaceId = session.workspaceId;
      const source = findOnlineSession();
      if (source) {
        await this.refreshConversation(source.client, session.workspaceId, session.agentId).catch(
          (error) => console.warn("[WearBridge] Conversation refresh failed", error),
        );
        snapshot = getSnapshot(
          this.generation,
          this.latestRepliesByAgentId,
          this.conversationCache,
          this.lastTurnDurationByAgentId,
        );
        session = snapshot.sessions.find((item) => item.workspaceId === command.workspaceId);
        this.ensureWatchedConversationPolling();
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
    await this.loadOlderConversation(source.client);
    return this.success(
      command.id,
      getSnapshot(
        this.generation,
        this.latestRepliesByAgentId,
        this.conversationCache,
        this.lastTurnDurationByAgentId,
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
        ),
      );
    } catch {
      return createWatchErrorResponse({
        id: command.id,
        code: "ACTION_FAILED",
        message: "Paseo could not send the prompt",
      });
    }
  }

  private success(id: string, snapshot: WatchSnapshot): WatchResponse {
    return { v: 1, id, ok: true, snapshot };
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
