import type { WatchMessage } from "./protocol";
import { parseWatchControlSegments } from "../watch-control-blocks";

export interface WatchHints {
  currentStatus?: string;
  summary?: string;
}

export function extractWatchHints(messages: readonly WatchMessage[]): WatchHints {
  let currentStatus: string | undefined;
  let summary: string | undefined;
  const latestUserIndex = messages.findLastIndex((message) => message.role === "user");
  for (const message of messages.slice(latestUserIndex + 1)) {
    if (message.role !== "assistant") continue;
    for (const segment of parseWatchControlSegments(message.text)) {
      if (segment.kind === "status") currentStatus = segment.text;
      if (segment.kind === "summary") summary = segment.text;
    }
  }
  return {
    ...(currentStatus ? { currentStatus } : {}),
    ...(summary ? { summary } : {}),
  };
}
