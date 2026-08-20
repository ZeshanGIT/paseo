const MAX_REMEMBERED_DELIVERIES = 256;

/** Suppresses a native queue event if a lifecycle edge delivers it twice. */
export class WatchCommandDeliveryGuard {
  private readonly remembered = new Set<string>();

  accept(deliveryId: string | undefined): boolean {
    if (!deliveryId) return true;
    if (this.remembered.has(deliveryId)) return false;
    this.remembered.add(deliveryId);
    if (this.remembered.size > MAX_REMEMBERED_DELIVERIES) {
      const oldest = this.remembered.values().next().value;
      if (oldest) this.remembered.delete(oldest);
    }
    return true;
  }
}
