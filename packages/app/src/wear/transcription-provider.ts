import {
  DictationStreamSender,
  type DictationStreamClient,
} from "@/dictation/dictation-stream-sender";
import { PCM_DICTATION_FORMAT } from "@/hooks/use-dictation.shared";

/**
 * Phone-only transcription boundary for audio received from a paired Wear device.
 * V1 deliberately has no daemon or network fallback.
 */
export interface TranscriptionProvider {
  transcribe(input: { voiceId: string }): Promise<string>;
  cancel(voiceId: string): Promise<void>;
}

export class PhoneLocalTranscriptionUnavailableError extends Error {
  constructor() {
    super("Phone-local speech recognition is not available in this build");
  }
}

export function createUnavailablePhoneLocalTranscriptionProvider(): TranscriptionProvider {
  return {
    async transcribe(): Promise<string> {
      throw new PhoneLocalTranscriptionUnavailableError();
    },
    async cancel(): Promise<void> {},
  };
}

export interface WearVoiceTransferChunk {
  base64: string;
  nextOffset: number;
  eof: boolean;
}

export interface WearDaemonDictationProviderOptions {
  getConnectedClient: (voiceId: string) => DictationStreamClient | null;
  readVoiceTransferChunk: (
    voiceId: string,
    offset: number,
    maxBytes: number,
  ) => Promise<WearVoiceTransferChunk>;
  createSender?: (params: {
    client: DictationStreamClient;
    format: string;
  }) => DictationStreamSender;
  chunkBytes?: number;
}

interface TranscriptionAttempt {
  cancelled: boolean;
  cancelIssued: boolean;
  sender: DictationStreamSender;
}

export const WEAR_PCM_CHUNK_BYTES = 16 * 1024;

export class WearDictationCancelledError extends Error {
  constructor() {
    super("Wear dictation was cancelled");
  }
}

/** Streams a finished, checksum-verified Wear transfer through the selected phone daemon client. */
export class WearDaemonDictationProvider implements TranscriptionProvider {
  private readonly attempts = new Map<string, TranscriptionAttempt>();
  private readonly chunkBytes: number;

  constructor(private readonly options: WearDaemonDictationProviderOptions) {
    this.chunkBytes = options.chunkBytes ?? WEAR_PCM_CHUNK_BYTES;
    if (!Number.isInteger(this.chunkBytes) || this.chunkBytes <= 0) {
      throw new Error("Wear dictation chunk size must be a positive integer");
    }
  }

  async transcribe(input: { voiceId: string }): Promise<string> {
    if (this.attempts.has(input.voiceId)) {
      throw new Error("Wear dictation is already transcribing");
    }

    const client = this.options.getConnectedClient(input.voiceId);
    if (!client?.isConnected) {
      throw new PhoneLocalTranscriptionUnavailableError();
    }

    const sender =
      this.options.createSender?.({
        client,
        format: PCM_DICTATION_FORMAT,
      }) ?? new DictationStreamSender({ client, format: PCM_DICTATION_FORMAT });
    const attempt: TranscriptionAttempt = { cancelled: false, cancelIssued: false, sender };
    this.attempts.set(input.voiceId, attempt);

    let succeeded = false;
    try {
      let offset = 0;
      let eof = false;
      while (!eof) {
        this.throwIfCancelled(attempt);
        const chunk = await this.options.readVoiceTransferChunk(
          input.voiceId,
          offset,
          this.chunkBytes,
        );
        this.throwIfCancelled(attempt);
        this.validateChunk(chunk, offset);
        if (chunk.base64.length > 0) {
          sender.enqueueSegment(chunk.base64);
        }
        offset = chunk.nextOffset;
        eof = chunk.eof;
      }

      this.throwIfCancelled(attempt);
      const result = await sender.finish(sender.getFinalSeq());
      this.throwIfCancelled(attempt);
      succeeded = true;
      return result.text;
    } finally {
      if (!succeeded) {
        this.cancelAttempt(attempt);
      }
      sender.dispose();
      if (this.attempts.get(input.voiceId) === attempt) {
        this.attempts.delete(input.voiceId);
      }
    }
  }

  async cancel(voiceId: string): Promise<void> {
    const attempt = this.attempts.get(voiceId);
    if (attempt) {
      this.cancelAttempt(attempt);
    }
  }

  private throwIfCancelled(attempt: TranscriptionAttempt): void {
    if (attempt.cancelled) {
      throw new WearDictationCancelledError();
    }
  }

  private cancelAttempt(attempt: TranscriptionAttempt): void {
    if (attempt.cancelIssued) {
      return;
    }
    attempt.cancelled = true;
    attempt.cancelIssued = true;
    attempt.sender.cancel();
  }

  private validateChunk(chunk: WearVoiceTransferChunk, offset: number): void {
    const hasForwardProgress = chunk.nextOffset > offset;
    const isEmptyFinalChunk = chunk.eof && chunk.nextOffset === offset;
    if (!Number.isInteger(chunk.nextOffset) || (!hasForwardProgress && !isEmptyFinalChunk)) {
      throw new Error("Wear voice transfer returned an invalid offset");
    }
  }
}

export function createWearDaemonDictationProvider(
  options: WearDaemonDictationProviderOptions,
): TranscriptionProvider {
  return new WearDaemonDictationProvider(options);
}
