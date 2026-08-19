import { buildToolCallDisplayModel } from "@getpaseo/protocol/tool-call-display";
import type { StreamItem } from "@/types/stream";
import type { WatchActivity } from "./protocol";

function bounded(value: string | undefined, limit = 240): string | undefined {
  const text = value?.trim();
  return text ? text.slice(0, limit) : undefined;
}

function iconForDetail(type: string): string {
  if (type === "shell") return "shell";
  if (type === "read") return "read";
  if (type === "edit" || type === "write") return "edit";
  if (type === "search") return "search";
  if (type === "fetch") return "web";
  if (type === "sub_agent") return "sub_agent";
  if (type === "plan") return "todo";
  return "tool";
}

function orchestratorActivityState(
  status: "executing" | "completed" | "failed",
): WatchActivity["state"] {
  if (status === "executing") return "running";
  if (status === "failed") return "failed";
  return "completed";
}

// The branches exhaustively project the finite StreamItem union into a small watch DTO.
// eslint-disable-next-line complexity
function fromStreamItem(item: StreamItem): WatchActivity | undefined {
  const common = {
    id: item.id,
    timestamp: item.timestamp.getTime(),
    sequence: item.timelineCursor?.seq ?? item.timestamp.getTime(),
  };
  if (item.kind === "tool_call") {
    if (item.payload.source === "agent") {
      const data = item.payload.data;
      const display = buildToolCallDisplayModel(data);
      return {
        ...common,
        kind: "tool",
        icon: iconForDetail(data.detail.type),
        label: display.displayName,
        ...(bounded(display.summary) ? { detail: bounded(display.summary) } : {}),
        state: data.status,
      };
    }
    const data = item.payload.data;
    return {
      ...common,
      kind: "tool",
      icon: data.toolName.toLowerCase() === "task" ? "sub_agent" : "tool",
      label: data.toolName,
      state: orchestratorActivityState(data.status),
    };
  }
  if (item.kind === "thought") {
    return {
      ...common,
      kind: "reasoning",
      icon: "reasoning",
      label: "Thinking",
      ...(bounded(item.text) ? { detail: bounded(item.text) } : {}),
      state: item.status === "loading" ? "running" : "completed",
    };
  }
  if (item.kind === "todo_list") {
    const active = item.items.find((entry) => entry.status === "in_progress");
    return {
      ...common,
      kind: "todo",
      icon: "todo",
      label: "Tasks",
      ...(bounded(active?.activeForm ?? active?.text)
        ? { detail: bounded(active?.activeForm ?? active?.text) }
        : {}),
      state: active ? "running" : "completed",
    };
  }
  if (item.kind === "activity_log") {
    return {
      ...common,
      kind: item.activityType === "error" ? "error" : "tool",
      icon: item.activityType === "error" ? "error" : "tool",
      label: item.activityType === "error" ? "Error" : "Activity",
      ...(bounded(item.message) ? { detail: bounded(item.message) } : {}),
      state: item.activityType === "error" ? "failed" : "completed",
    };
  }
  if (item.kind === "compaction") {
    return {
      ...common,
      kind: "compaction",
      icon: "reasoning",
      label: "Compacting context",
      state: item.status === "loading" ? "running" : "completed",
    };
  }
  return undefined;
}

export function latestWatchActivity(items: readonly StreamItem[]): WatchActivity | undefined {
  const activities = items.flatMap((item) => {
    const activity = fromStreamItem(item);
    return activity ? [activity] : [];
  });
  for (let index = activities.length - 1; index >= 0; index -= 1) {
    if (activities[index]?.state === "running") return activities[index];
  }
  return activities.at(-1);
}
