import { requireNativeModule } from "expo-modules-core";

interface NativeWearBridge {
  sendResponse(nodeId: string, response: string): Promise<void>;
  publishSnapshot(snapshot: string): Promise<void>;
  addListener(
    name: "onWatchCommand",
    listener: (event: NativeWatchCommandEvent) => void,
  ): {
    remove(): void;
  };
}

export interface NativeWatchCommandEvent {
  nodeId: string;
  command: string;
}

const nativeModule = requireNativeModule<NativeWearBridge>("PaseoWearBridge");

export const nativeWearBridge = {
  sendResponse(nodeId: string, response: string): Promise<void> {
    return nativeModule.sendResponse(nodeId, response);
  },
  publishSnapshot(snapshot: string): Promise<void> {
    return nativeModule.publishSnapshot(snapshot);
  },
  subscribe(listener: (event: NativeWatchCommandEvent) => void): () => void {
    const subscription = nativeModule.addListener("onWatchCommand", listener);
    return () => subscription.remove();
  },
};
