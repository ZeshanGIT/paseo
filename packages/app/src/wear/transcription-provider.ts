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
