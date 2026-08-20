import { describe, expect, it, vi } from "vitest";
import type { SessionOutboundMessage } from "@getpaseo/protocol/messages";
import {
  PhoneLocalTranscriptionUnavailableError,
  WEAR_PCM_CHUNK_BYTES,
  WearDaemonDictationProvider,
  WearDictationCancelledError,
  type WearVoiceTransferChunk,
} from "@/wear/transcription-provider";

interface SentChunk {
  audio: string;
  format: string;
  seq: number;
}

interface PendingRead {
  resolve: ((chunk: WearVoiceTransferChunk) => void) | null;
}

class FakeDaemonClient {
  isConnected = true;
  readonly starts: string[] = [];
  readonly chunks: SentChunk[] = [];
  readonly finishes: number[] = [];
  readonly cancels: string[] = [];
  private readonly listeners = new Set<(message: SessionOutboundMessage) => void>();

  async startDictationStream(dictationId: string): Promise<void> {
    this.starts.push(dictationId);
  }

  sendDictationStreamChunk(dictationId: string, seq: number, audio: string, format: string): void {
    this.chunks.push({ audio, format, seq });
    for (const listener of this.listeners) {
      listener({
        type: "dictation_stream_ack",
        payload: { dictationId, ackSeq: seq },
      });
    }
  }

  async finishDictationStream(
    _dictationId: string,
    finalSeq: number,
  ): Promise<{ dictationId: string; text: string }> {
    this.finishes.push(finalSeq);
    return { dictationId: "dictation", text: "hello from the phone" };
  }

  cancelDictationStream(dictationId: string): void {
    this.cancels.push(dictationId);
  }

  subscribeRawMessages(handler: (message: SessionOutboundMessage) => void): () => void {
    this.listeners.add(handler);
    return () => this.listeners.delete(handler);
  }
}

function createChunkReader(chunks: WearVoiceTransferChunk[]) {
  const read = vi.fn(async (_voiceId: string, offset: number, maxBytes: number) => {
    expect(maxBytes).toBe(WEAR_PCM_CHUNK_BYTES);
    const chunk = chunks.shift();
    if (!chunk) {
      throw new Error(`Unexpected read at ${offset}`);
    }
    return chunk;
  });
  return read;
}

describe("WearDaemonDictationProvider", () => {
  it("streams verified chunks in order through the selected phone client", async () => {
    const client = new FakeDaemonClient();
    const readVoiceTransferChunk = createChunkReader([
      { base64: "first", nextOffset: 16_384, eof: false },
      { base64: "second", nextOffset: 20_000, eof: true },
    ]);
    const provider = new WearDaemonDictationProvider({
      getConnectedClient: () => client,
      readVoiceTransferChunk,
    });

    await expect(provider.transcribe({ voiceId: "voice-1" })).resolves.toBe("hello from the phone");

    expect(readVoiceTransferChunk.mock.calls).toEqual([
      ["voice-1", 0, WEAR_PCM_CHUNK_BYTES],
      ["voice-1", 16_384, WEAR_PCM_CHUNK_BYTES],
    ]);
    expect(client.chunks).toEqual([
      { audio: "first", format: "audio/pcm;rate=16000;bits=16", seq: 0 },
      { audio: "second", format: "audio/pcm;rate=16000;bits=16", seq: 1 },
    ]);
    expect(client.finishes).toEqual([1]);
    expect(client.cancels).toEqual([]);
  });

  it("finishes an empty verified transfer without sending an audio chunk", async () => {
    const client = new FakeDaemonClient();
    const provider = new WearDaemonDictationProvider({
      getConnectedClient: () => client,
      readVoiceTransferChunk: async () => ({ base64: "", nextOffset: 0, eof: true }),
    });

    await provider.transcribe({ voiceId: "empty" });

    expect(client.chunks).toEqual([]);
    expect(client.finishes).toEqual([-1]);
  });

  it("does not read or create a fallback client when the selected client is unavailable", async () => {
    const readVoiceTransferChunk = vi.fn();
    const provider = new WearDaemonDictationProvider({
      getConnectedClient: () => null,
      readVoiceTransferChunk,
    });

    await expect(provider.transcribe({ voiceId: "voice-1" })).rejects.toBeInstanceOf(
      PhoneLocalTranscriptionUnavailableError,
    );
    expect(readVoiceTransferChunk).not.toHaveBeenCalled();
  });

  it("cancels one active stream exactly once while a native read is pending", async () => {
    const client = new FakeDaemonClient();
    const pendingSecondRead: PendingRead = { resolve: null };
    const provider = new WearDaemonDictationProvider({
      getConnectedClient: () => client,
      readVoiceTransferChunk: (_voiceId, offset) => {
        if (offset === 0) {
          return Promise.resolve({ base64: "first", nextOffset: 4, eof: false });
        }
        return new Promise<WearVoiceTransferChunk>((resolve) => {
          pendingSecondRead.resolve = resolve;
        });
      },
    });

    const transcription = provider.transcribe({ voiceId: "voice-1" });
    await vi.waitFor(() => expect(pendingSecondRead.resolve).not.toBeNull());
    await vi.waitFor(() => expect(client.starts).toHaveLength(1));
    await provider.cancel("voice-1");
    await provider.cancel("voice-1");
    const resolveSecondRead = pendingSecondRead.resolve;
    if (!resolveSecondRead) {
      throw new Error("Expected a pending second voice chunk read");
    }
    resolveSecondRead({ base64: "late", nextOffset: 8, eof: true });

    await expect(transcription).rejects.toBeInstanceOf(WearDictationCancelledError);
    expect(client.cancels).toEqual([client.starts[0]]);
  });
});
