import { describe, expect, it } from "vitest";
import { WatchCommandDeliveryGuard } from "./command-delivery-guard";

describe("WatchCommandDeliveryGuard", () => {
  it("accepts a queued native delivery exactly once", () => {
    const guard = new WatchCommandDeliveryGuard();
    expect(guard.accept("delivery-1")).toBe(true);
    expect(guard.accept("delivery-1")).toBe(false);
  });

  it("keeps compatibility with live events that predate delivery ids", () => {
    const guard = new WatchCommandDeliveryGuard();
    expect(guard.accept(undefined)).toBe(true);
    expect(guard.accept(undefined)).toBe(true);
  });
});
