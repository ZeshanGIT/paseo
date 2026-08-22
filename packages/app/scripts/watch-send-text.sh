#!/usr/bin/env bash
# Open a workspace and chat in Paseo Glance, then send text through the Wear UI.
#
# This is intentionally UI-driven: it exercises the same phone/Data Layer path
# that a watch user uses, without reaching into the phone app or daemon.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
: "${ANDROID_SDK_ROOT:=/Users/seshan-12821/Library/Android/sdk}"
export ANDROID_SDK_ROOT
ADB_BIN="${ADB_BIN:-$ANDROID_SDK_ROOT/platform-tools/adb}"
APP_ID="${PASEO_WEAR_APP_ID:-sh.paseo.debug}"
WATCH_ACTIVITY="$APP_ID/sh.paseo.glance.wear.MainActivity"
SERIAL="${PASEO_WEAR_SERIAL:-}"
WORKSPACE=""
TEXT="Hello"
CHAT_INDEX=0
WAIT_SECONDS="${PASEO_WEAR_UI_WAIT_SECONDS:-120}"
INPUT_DELAY="${PASEO_WEAR_INPUT_DELAY:-0.5}"
EDITOR_CLEAR_EVENTS="${PASEO_WEAR_EDITOR_CLEAR_EVENTS:-32}"
TEXT_ENTRY_SETTLE_SECONDS="${PASEO_WEAR_TEXT_ENTRY_SETTLE_SECONDS:-3}"
RESET=1
# A unique device-side path keeps concurrent local invocations from racing on one
# uiautomator dump before the XML parser has read it.
UI_PATH="/sdcard/paseo-glance-ui-$$.xml"
CAPTURE_DIR=""
VERIFY_BACK=0

usage() {
  cat <<'EOF'
Usage: ./scripts/watch-send-text.sh [options]

Open a workspace and chat in the Wear app, then send text from the watch UI.

Options:
  --workspace ID       Workspace/project label to open (required)
  --text TEXT          Text to send (default: Hello)
  --chat-index INDEX   Zero-based chat index in the workspace (default: 0)
  --serial SERIAL      adb serial; auto-selects an attached Wear target otherwise
  --wait SECONDS       Timeout for each UI state (default: 120)
  --no-reset            Reuse the current Wear activity instead of restarting it
  --capture-dir DIR    Save screenshots and accessibility XML after each step
  --verify-back        Back out to the chat list and require no ProgressBar
  -h, --help           Show this help

Example:
  ./scripts/watch-send-text.sh \
    --serial emulator-5556 \
    --workspace id360_workspace \
    --text Hello
EOF
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

log() {
  printf '==> %s\n' "$*"
}

device_list() {
  "$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" { print $1 }'
}

device_characteristics() {
  "$ADB_BIN" -s "$1" shell getprop ro.build.characteristics 2>/dev/null | tr -d '\r\n'
}

device_product() {
  "$ADB_BIN" -s "$1" shell getprop ro.product.name 2>/dev/null | tr -d '\r\n'
}

pick_watch_serial() {
  local candidate characteristics product
  while IFS= read -r candidate; do
    [ -n "$candidate" ] || continue
    characteristics="$(device_characteristics "$candidate")"
    product="$(device_product "$candidate")"
    if [[ "$characteristics" == *watch* || "$product" == *gwear* ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done < <(device_list)
  return 1
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --workspace)
      [ "$#" -ge 2 ] || die "--workspace requires an ID"
      WORKSPACE="$2"
      shift 2
      ;;
    --text)
      [ "$#" -ge 2 ] || die "--text requires a value"
      TEXT="$2"
      shift 2
      ;;
    --chat-index)
      [ "$#" -ge 2 ] || die "--chat-index requires a zero-based index"
      CHAT_INDEX="$2"
      shift 2
      ;;
    --serial)
      [ "$#" -ge 2 ] || die "--serial requires an adb serial"
      SERIAL="$2"
      shift 2
      ;;
    --wait)
      [ "$#" -ge 2 ] || die "--wait requires seconds"
      WAIT_SECONDS="$2"
      shift 2
      ;;
    --no-reset)
      RESET=0
      shift
      ;;
    --capture-dir)
      [ "$#" -ge 2 ] || die "--capture-dir requires a directory"
      CAPTURE_DIR="$2"
      shift 2
      ;;
    --verify-back)
      VERIFY_BACK=1
      shift
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

[ -n "$WORKSPACE" ] || die "--workspace is required"
[[ "$CHAT_INDEX" =~ ^[0-9]+$ ]] || die "--chat-index must be a non-negative integer"
[[ "$WAIT_SECONDS" =~ ^[1-9][0-9]*$ ]] || die "--wait must be a positive integer"
[ -x "$ADB_BIN" ] || die "adb not found at $ADB_BIN"
command -v python3 >/dev/null 2>&1 || die "python3 is required to inspect the accessibility tree"

if [ -n "$CAPTURE_DIR" ]; then
  mkdir -p "$CAPTURE_DIR"
fi

if [ -z "$SERIAL" ]; then
  SERIAL="$(pick_watch_serial)" || die "no attached Wear target found; pass --serial"
fi

state="$("$ADB_BIN" -s "$SERIAL" get-state 2>/dev/null | tr -d '\r\n' || true)"
[ "$state" = "device" ] || die "adb target '$SERIAL' is not attached and ready"

dump_ui() {
  "$ADB_BIN" -s "$SERIAL" shell uiautomator dump "$UI_PATH" >/dev/null 2>&1 || return 1
  "$ADB_BIN" -s "$SERIAL" exec-out cat "$UI_PATH" 2>/dev/null | tr -d '\r\n'
}

ui_query() {
  local mode="$1"
  local needle="$2"
  local index="$3"
  local xml
  xml="$(dump_ui)" || return 1
  PASEO_UI_XML="$xml" python3 - "$mode" "$needle" "$index" <<'PY'
import os
import re
import sys
import xml.etree.ElementTree as ET

mode = sys.argv[1]
needle = sys.argv[2]
index = int(sys.argv[3])
try:
    root = ET.fromstring(os.environ["PASEO_UI_XML"])
except (KeyError, ET.ParseError):
    raise SystemExit(1)

parents = {}
def visit(node):
    for child in list(node):
        parents[id(child)] = node
        visit(child)
visit(root)

def parse_bounds(node):
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
    if not match:
        return None
    return tuple(int(value) for value in match.groups())

def clickable(node):
    return node.attrib.get("clickable") == "true"

def clickable_ancestor(node):
    current = node
    while current is not None:
        if clickable(current):
            return current
        current = parents.get(id(current))
    return None

def print_center(node):
    bounds = parse_bounds(node)
    if not bounds:
        raise SystemExit(1)
    left, top, right, bottom = bounds
    print((left + right) // 2, (top + bottom) // 2)

if mode in ("text", "content"):
    attribute = "text" if mode == "text" else "content-desc"
    match = next(
        (node for node in root.iter() if node.attrib.get(attribute) == needle),
        None,
    )
    if match is None:
        raise SystemExit(1)
    target = clickable_ancestor(match)
    if target is None:
        raise SystemExit(1)
    print_center(target)
elif mode == "editor":
    match = next(
        (
            node
            for node in root.iter()
            if node.attrib.get("class") == "android.widget.EditText"
            and node.attrib.get("text") == needle
        ),
        None,
    )
    if match is None:
        raise SystemExit(1)
    print_center(match)
elif mode == "focused-editor":
    match = next(
        (
            node
            for node in root.iter()
            if node.attrib.get("class") == "android.widget.EditText"
            and node.attrib.get("focused") == "true"
        ),
        None,
    )
    if match is None:
        raise SystemExit(1)
    print_center(match)
elif mode == "sent-message":
    match = next(
        (
            node
            for node in root.iter()
            if node.attrib.get("text") == needle
            and node.attrib.get("class") != "android.widget.EditText"
        ),
        None,
    )
    if match is None:
        raise SystemExit(1)
    print_center(match)
elif mode == "class":
    match = next(
        (node for node in root.iter() if node.attrib.get("class") == needle),
        None,
    )
    if match is None:
        raise SystemExit(1)
    print_center(match)
elif mode == "chat":
    minimum_top = int(needle)
    candidates = []
    for node in root.iter():
        bounds = parse_bounds(node)
        if clickable(node) and bounds and bounds[1] >= minimum_top:
            candidates.append((bounds[1], node))
    candidates.sort(key=lambda item: item[0])
    if index >= len(candidates):
        raise SystemExit(1)
    print_center(candidates[index][1])
else:
    raise SystemExit(2)
PY
}

has_query() {
  ui_query "$1" "$2" "$3" >/dev/null 2>&1
}

wait_for_absent() {
  local mode="$1"
  local needle="$2"
  local index="$3"
  local description="$4"
  local deadline=$((SECONDS + WAIT_SECONDS))
  while [ "$SECONDS" -le "$deadline" ]; do
    if ! has_query "$mode" "$needle" "$index"; then
      return 0
    fi
    sleep 1
  done
  die "timed out waiting for $description to disappear on $SERIAL"
}

capture_step() {
  local step="$1"
  [ -n "$CAPTURE_DIR" ] || return 0
  "$ADB_BIN" -s "$SERIAL" exec-out screencap -p > "$CAPTURE_DIR/$step.png"
  "$ADB_BIN" -s "$SERIAL" shell uiautomator dump "$UI_PATH" >/dev/null
  "$ADB_BIN" -s "$SERIAL" pull "$UI_PATH" "$CAPTURE_DIR/$step.xml" >/dev/null
}

wait_for_query() {
  local mode="$1"
  local needle="$2"
  local index="$3"
  local description="$4"
  local deadline=$((SECONDS + WAIT_SECONDS))
  local bounds
  while [ "$SECONDS" -le "$deadline" ]; do
    if bounds="$(ui_query "$mode" "$needle" "$index" 2>/dev/null)" && [ -n "$bounds" ]; then
      printf '%s\n' "$bounds"
      return 0
    fi
    sleep 1
  done
  die "timed out waiting for $description on $SERIAL"
}

tap_query() {
  local mode="$1"
  local needle="$2"
  local index="$3"
  local description="$4"
  local bounds x y
  bounds="$(wait_for_query "$mode" "$needle" "$index" "$description")"
  read -r x y <<< "$bounds"
  log "Tapping $description"
  "$ADB_BIN" -s "$SERIAL" shell input tap "$x" "$y"
}

if [ "$RESET" -eq 1 ]; then
  log "Restarting $APP_ID on $SERIAL"
  "$ADB_BIN" -s "$SERIAL" shell am force-stop "$APP_ID"
fi
"$ADB_BIN" -s "$SERIAL" shell am start -W -n "$WATCH_ACTIVITY" >/dev/null
wait_for_query text "$WORKSPACE" 0 "workspace list" >/dev/null
capture_step "01-home"

tap_query text "$WORKSPACE" 0 "workspace $WORKSPACE"
chat_bounds="$(wait_for_query chat 120 "$CHAT_INDEX" "chat index $CHAT_INDEX")"
capture_step "02-workspace"
read -r chat_x chat_y <<< "$chat_bounds"
log "Tapping chat index $CHAT_INDEX"
"$ADB_BIN" -s "$SERIAL" shell input tap "$chat_x" "$chat_y"

tap_query content Keyboard 0 "Keyboard"
wait_for_absent text "Loading conversation…" 0 "Loading conversation"
capture_step "03-conversation-loaded"
sleep "$TEXT_ENTRY_SETTLE_SECONDS"
tap_query content Message 0 "message editor"
wait_for_query focused-editor "" 0 "focused message editor" >/dev/null
sleep 1

# `adb shell input keyevent` accepts one key code. Passing a large argument list
# leaves existing editor text behind on some Wear emulator images. Some Wear
# IMEs also leave the caret before a trailing blank, so clear both directions
# with individual delete events.
"$ADB_BIN" -s "$SERIAL" shell input keyevent 123
for ((delete_index=0; delete_index<EDITOR_CLEAR_EVENTS; delete_index++)); do
  "$ADB_BIN" -s "$SERIAL" shell input keyevent 67
done
for ((delete_index=0; delete_index<EDITOR_CLEAR_EVENTS; delete_index++)); do
  "$ADB_BIN" -s "$SERIAL" shell input keyevent 112
done
sleep 0.5

log "Typing message"
# A single escaped input event is more reliable on the Wear emulator than a
# burst of per-character events, which can race the IME and lose the field
# contents while the accessibility tree is being polled.
encoded_text="${TEXT// /%s}"
"$ADB_BIN" -s "$SERIAL" shell input text "$encoded_text"
sleep "$INPUT_DELAY"
wait_for_query editor "$TEXT" 0 "typed message" >/dev/null
capture_step "04-editor-with-text"
tap_query content "Send message" 0 "Send message"
wait_for_query sent-message "$TEXT" 0 "sent message" >/dev/null
capture_step "05-after-send"

if [ "$VERIFY_BACK" -eq 1 ]; then
  "$ADB_BIN" -s "$SERIAL" shell input keyevent 4
  wait_for_query chat 120 "$CHAT_INDEX" "chat list after back" >/dev/null
  wait_for_absent class "android.widget.ProgressBar" 0 "chat-list ProgressBar"
  capture_step "06-chat-list-after-back"
fi

log "Sent \"$TEXT\" in $WORKSPACE chat index $CHAT_INDEX"
