export interface NativeWatchCommandEvent {
  nodeId: string;
  command: string;
}

export const nativeWearBridge = {
  async sendResponse(_nodeId: string, _response: string): Promise<void> {},
  async publishSnapshot(_snapshot: string): Promise<void> {},
  subscribe(_listener: (event: NativeWatchCommandEvent) => void): () => void {
    return () => {};
  },
};
