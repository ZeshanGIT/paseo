import type {
  FetchAgentTimelineCursor,
  FetchAgentTimelinePayload,
} from "@getpaseo/client/internal/daemon-client";
import { buildToolCallDisplayModel } from "@getpaseo/protocol/tool-call-display";
import { extractWatchHints } from "./hints";
import type { WatchActivity, WatchConversation, WatchMessage } from "./protocol";

const MAX_MESSAGE_CHARS = 4_000;

export interface WatchConversationCache {
  workspaceId: string;
  agentId: string;
  epoch: string;
  startCursor: FetchAgentTimelineCursor | null;
  hasOlder: boolean;
  messages: WatchMessage[];
  activities: WatchActivity[];
}

function bounded(value: string | undefined, limit = 320): string | undefined {
  const text = value?.trim();
  return text ? text.slice(0, limit) : undefined;
}

function activityIcon(type: string): string {
  switch (type) {
    case "shell":
      return "shell";
    case "read":
      return "read";
    case "edit":
    case "write":
      return "edit";
    case "search":
      return "search";
    case "fetch":
      return "web";
    case "sub_agent":
      return "sub_agent";
    case "plan":
    case "todo":
      return "todo";
    case "reasoning":
      return "reasoning";
    case "error":
      return "error";
    default:
      return "tool";
  }
}

function activitiesFromPage(page: FetchAgentTimelinePayload): WatchActivity[] {
  return page.entries.flatMap<WatchActivity>((entry): WatchActivity[] => {
    const common = {
      id: `${page.epoch}:${entry.seqStart}:${entry.item.type}:activity`,
      timestamp: Date.parse(entry.timestamp) || 0,
      sequence: entry.seqStart,
    };
    if (entry.item.type === "tool_call") {
      const display = buildToolCallDisplayModel(entry.item);
      return [
        {
          ...common,
          kind: "tool" as const,
          icon: activityIcon(entry.item.detail.type),
          label: display.displayName,
          ...(bounded(display.summary) ? { detail: bounded(display.summary) } : {}),
          state: entry.item.status,
        },
      ];
    }
    if (entry.item.type === "reasoning") {
      return [
        {
          ...common,
          kind: "reasoning" as const,
          icon: "reasoning",
          label: "Thinking",
          ...(bounded(entry.item.text) ? { detail: bounded(entry.item.text) } : {}),
          state: "completed" as const,
        },
      ];
    }
    if (entry.item.type === "todo") {
      const active = entry.item.items.find((item) => item.status === "in_progress");
      return [
        {
          ...common,
          kind: "todo" as const,
          icon: "todo",
          label: "Tasks",
          ...(bounded(active?.activeForm ?? active?.text)
            ? { detail: bounded(active?.activeForm ?? active?.text) }
            : {}),
          state: active ? ("running" as const) : ("completed" as const),
        },
      ];
    }
    if (entry.item.type === "error") {
      return [
        {
          ...common,
          kind: "error" as const,
          icon: "error",
          label: "Error",
          ...(bounded(entry.item.message) ? { detail: bounded(entry.item.message) } : {}),
          state: "failed" as const,
        },
      ];
    }
    if (entry.item.type === "compaction") {
      return [
        {
          ...common,
          kind: "compaction" as const,
          icon: "reasoning",
          label: "Compacting context",
          state: entry.item.status === "loading" ? ("running" as const) : ("completed" as const),
        },
      ];
    }
    return [];
  });
}

function messagesFromPage(page: FetchAgentTimelinePayload): WatchMessage[] {
  return page.entries.flatMap((entry) => {
    if (entry.item.type !== "user_message" && entry.item.type !== "assistant_message") return [];
    const text = entry.item.text.trim();
    if (!text) return [];
    return [
      {
        id: `${page.epoch}:${entry.seqStart}:${entry.item.type}:message`,
        role: entry.item.type === "user_message" ? ("user" as const) : ("assistant" as const),
        text: text.slice(0, MAX_MESSAGE_CHARS),
        timestamp: Date.parse(entry.timestamp) || 0,
        sequence: entry.seqStart,
      },
    ];
  });
}

export function mergeConversationPage(input: {
  current: WatchConversationCache | null;
  workspaceId: string;
  agentId: string;
  page: FetchAgentTimelinePayload;
}): WatchConversationCache {
  const { current, workspaceId, agentId, page } = input;
  const canMerge = current?.agentId === agentId && current.epoch === page.epoch;
  const messages = new Map<string, WatchMessage>();
  const activities = new Map<string, WatchActivity>();
  if (canMerge) {
    for (const message of current.messages) messages.set(message.id, message);
    for (const activity of current.activities) activities.set(activity.id, activity);
  }
  for (const message of messagesFromPage(page)) messages.set(message.id, message);
  for (const activity of activitiesFromPage(page)) activities.set(activity.id, activity);

  return {
    workspaceId,
    agentId,
    epoch: page.epoch,
    startCursor:
      canMerge && page.direction !== "before"
        ? (current.startCursor ?? page.startCursor)
        : page.startCursor,
    hasOlder: canMerge && page.direction !== "before" ? current.hasOlder : page.hasOlder,
    messages: [...messages.values()].sort((left, right) => left.sequence - right.sequence),
    activities: [...activities.values()].sort((left, right) => left.sequence - right.sequence),
  };
}

export function latestAssistantReply(cache: WatchConversationCache | null): string | null {
  if (!cache) return null;
  for (let index = cache.messages.length - 1; index >= 0; index -= 1) {
    const message = cache.messages[index];
    if (message?.role === "assistant") return message.text;
  }
  return null;
}

export function toWatchConversation(
  cache: WatchConversationCache | null,
): WatchConversation | undefined {
  if (!cache) return undefined;
  const hints = extractWatchHints(cache.messages);
  return {
    workspaceId: cache.workspaceId,
    agentId: cache.agentId,
    messages: cache.messages,
    activities: cache.activities,
    hasOlder: cache.hasOlder,
    ...hints,
  };
}
