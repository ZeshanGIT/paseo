import { requireNativeModule } from "expo-modules-core";

interface NativeWearBridge {
  sendResponse(nodeId: string, response: string): Promise<void>;
  publishSnapshot(snapshot: string): Promise<void>;
  registerVoiceTransfer(nodeId: string, voiceId: string): Promise<void>;
  finishVoiceTransfer(voiceId: string, byteLength: number, sha256: string): Promise<void>;
  readVoiceTransferChunk(
    voiceId: string,
    offset: number,
    maxBytes: number,
  ): Promise<NativeVoiceTransferChunk>;
  cancelVoiceTransfer(voiceId: string): Promise<void>;
  openOnPhone(route: string | null): Promise<void>;
  addListener(
    name: "onWatchCommand",
    listener: (event: NativeWatchCommandEvent) => void,
  ): {
    remove(): void;
  };
}

export interface NativeWatchCommandEvent {
  deliveryId?: string;
  nodeId: string;
  command: string;
}

export interface NativeVoiceTransferChunk {
  base64: string;
  nextOffset: number;
  eof: boolean;
}

const nativeModule = requireNativeModule<NativeWearBridge>("PaseoWearBridge");

export const nativeWearBridge = {
  sendResponse(nodeId: string, response: string): Promise<void> {
    return nativeModule.sendResponse(nodeId, response);
  },
  publishSnapshot(snapshot: string): Promise<void> {
    return nativeModule.publishSnapshot(snapshot);
  },
  registerVoiceTransfer(nodeId: string, voiceId: string): Promise<void> {
    return nativeModule.registerVoiceTransfer(nodeId, voiceId);
  },
  finishVoiceTransfer(voiceId: string, byteLength: number, sha256: string): Promise<void> {
    return nativeModule.finishVoiceTransfer(voiceId, byteLength, sha256);
  },
  readVoiceTransferChunk(
    voiceId: string,
    offset: number,
    maxBytes: number,
  ): Promise<NativeVoiceTransferChunk> {
    return nativeModule.readVoiceTransferChunk(voiceId, offset, maxBytes);
  },
  cancelVoiceTransfer(voiceId: string): Promise<void> {
    return nativeModule.cancelVoiceTransfer(voiceId);
  },
  openOnPhone(route: string | null): Promise<void> {
    return nativeModule.openOnPhone(route);
  },
  subscribe(listener: (event: NativeWatchCommandEvent) => void): () => void {
    const subscription = nativeModule.addListener("onWatchCommand", listener);
    return () => subscription.remove();
  },
};
