import { Platform } from "react-native";
import type { NativeWatchCommandEvent } from "./bridge.web";

const implementation =
  Platform.OS === "android"
    ? (require("./bridge.native") as typeof import("./bridge.native"))
    : (require("./bridge.web") as typeof import("./bridge.web"));

export const nativeWearBridge = implementation.nativeWearBridge;
export type { NativeWatchCommandEvent };
