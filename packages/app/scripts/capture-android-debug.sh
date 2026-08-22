#!/usr/bin/env bash
# Capture an Android screenshot, focused activity state, and logcat without
# streaming the noisy output into the development conversation.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
: "${ANDROID_SDK_ROOT:=/Users/seshan-12821/Library/Android/sdk}"
export ANDROID_SDK_ROOT
export PATH="$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$PATH"
ADB_BIN="${ADB_BIN:-$ANDROID_SDK_ROOT/platform-tools/adb}"
SERIAL="${ANDROID_SERIAL:-}"
LABEL="android"
CAPTURE_ROOT="${PASEO_CAPTURE_DIR:-$APP_DIR/../../.dev/device-captures}"
LOG_PATTERN="${PASEO_LOG_PATTERN:-WearDebug|PaseoWearBridge|PaseoGlance|ReactNativeJS|BridgelessReact}"

usage() {
  cat <<'EOF'
Usage: ./scripts/capture-android-debug.sh [options]

Options:
  --serial SERIAL       adb serial to capture
  --label LABEL         name used for the capture folder (default: android)
  --out DIRECTORY       capture root (default: paseo/.dev/device-captures)
  --pattern REGEXP      logcat filter regexp
  -h, --help            show this help

The capture includes screenshot.png, activity.txt, device.txt, logcat.txt,
and focused-logcat.txt. The default directory is ignored by git.
EOF
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --serial)
      [ "$#" -ge 2 ] || die "--serial requires an adb serial"
      SERIAL="$2"
      shift 2
      ;;
    --label)
      [ "$#" -ge 2 ] || die "--label requires a name"
      LABEL="$2"
      shift 2
      ;;
    --out)
      [ "$#" -ge 2 ] || die "--out requires a directory"
      CAPTURE_ROOT="$2"
      shift 2
      ;;
    --pattern)
      [ "$#" -ge 2 ] || die "--pattern requires a regexp"
      LOG_PATTERN="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown option: $1 (use --help)"
      ;;
  esac
done

[ -x "$ADB_BIN" ] || die "adb not found at $ADB_BIN"

device_list() {
  "$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" { print $1 }'
}

if [ -z "$SERIAL" ]; then
  while IFS= read -r candidate; do
    case "$candidate" in
      emulator-*) SERIAL="$candidate"; break ;;
    esac
  done < <(device_list)
fi

if [ -z "$SERIAL" ]; then
  SERIAL="$(device_list | head -n 1)"
fi

[ -n "$SERIAL" ] || die "no attached adb device found; pass --serial SERIAL"
state="$("$ADB_BIN" -s "$SERIAL" get-state 2>/dev/null | tr -d '\r\n' || true)"
[ "$state" = device ] || die "adb target '$SERIAL' is not attached and ready"

safe_label="$(printf '%s' "$LABEL" | tr -c '[:alnum:]._-' '_')"
timestamp="$(date '+%Y%m%d-%H%M%S')"
capture_dir="$CAPTURE_ROOT/${timestamp}-${safe_label}-${SERIAL//[^[:alnum:]_.-]/_}"
mkdir -p "$capture_dir"

"$ADB_BIN" -s "$SERIAL" exec-out screencap -p > "$capture_dir/screenshot.png"
"$ADB_BIN" -s "$SERIAL" shell dumpsys activity activities > "$capture_dir/activity.txt"
"$ADB_BIN" -s "$SERIAL" shell getprop > "$capture_dir/device.txt"
"$ADB_BIN" -s "$SERIAL" logcat -d -v threadtime > "$capture_dir/logcat.txt"
rg -i "$LOG_PATTERN" "$capture_dir/logcat.txt" > "$capture_dir/focused-logcat.txt" || true

printf '%s\n' "$capture_dir"
