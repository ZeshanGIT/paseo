export interface NativeWatchCommandEvent {
  deliveryId?: string;
  nodeId: string;
  command: string;
}

export const nativeWearBridge = {
  async sendResponse(_nodeId: string, _response: string): Promise<void> {},
  async publishSnapshot(_snapshot: string): Promise<void> {},
  async registerVoiceTransfer(_nodeId: string, _voiceId: string): Promise<void> {},
  async finishVoiceTransfer(
    _voiceId: string,
    _byteLength: number,
    _sha256: string,
  ): Promise<void> {},
  async cancelVoiceTransfer(_voiceId: string): Promise<void> {},
  async openOnPhone(_route: string | null): Promise<void> {},
  subscribe(_listener: (event: NativeWatchCommandEvent) => void): () => void {
    return () => {};
  },
};
