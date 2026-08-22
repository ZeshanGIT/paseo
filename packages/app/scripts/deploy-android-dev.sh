#!/usr/bin/env bash
# Deploy the generated development APKs to a selected phone/Wear target.
#
# This intentionally does not run Expo prebuild. The generated android/
# project must already exist; native integration belongs in tracked modules
# and config plugins.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
ANDROID_DIR="$APP_DIR/android"

: "${ANDROID_SDK_ROOT:=/Users/seshan-12821/Library/Android/sdk}"
: "${ANDROID_HOME:=$ANDROID_SDK_ROOT}"
export ANDROID_SDK_ROOT ANDROID_HOME
export PATH="/opt/homebrew/opt/node@22/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$PATH"

java_home_is_supported() {
  [ -x "${JAVA_HOME:-}/bin/java" ] || return 1
  "$JAVA_HOME/bin/java" -version 2>&1 | grep -qE 'version "(1[7-9]|[2-9][0-9])\.'
}

if ! java_home_is_supported; then
  JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
fi
if ! java_home_is_supported; then
  printf 'error: Java 17+ not found; expected %s\n' "$JAVA_HOME" >&2
  exit 1
fi
export JAVA_HOME

ADB_BIN="${ADB_BIN:-$ANDROID_SDK_ROOT/platform-tools/adb}"
TARGET="both"
PHONE_SERIAL="${PHONE_SERIAL:-}"
WATCH_SERIAL="${WATCH_SERIAL:-}"
SKIP_BUILD=0
LAUNCH=1
METRO_PORT="${PASEO_METRO_PORT:-8081}"
DAEMON_PORT="${PASEO_SERVICE_DAEMON_PORT:-6768}"
BOOT_WAIT_SECONDS="${ANDROID_BOOT_WAIT_SECONDS:-180}"
PHONE_APP_ID="${PHONE_APP_ID:-sh.paseo.debug}"
WEAR_APP_ID="${WEAR_APP_ID:-sh.paseo.debug}"

PHONE_APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
WEAR_APK="$APP_DIR/modules/paseo-wear/build/outputs/apk/debug/paseo-wear-debug.apk"

usage() {
  cat <<'EOF'
Usage: ./scripts/deploy-android-dev.sh [options]

Build and deploy the development phone app, Wear app, or both.

Options:
  --target phone|watch|both       What to build/install (default: both)
  --phone-serial SERIAL           adb serial for the phone target
  --watch-serial SERIAL           adb serial for the Wear target
  --skip-build                    Reuse existing debug APKs
  --no-launch                     Install only; do not configure/launch apps
  -h, --help                      Show this help

The serials can also be supplied with PHONE_SERIAL and WATCH_SERIAL. When
omitted, attached emulator phone/Wear targets are preferred automatically.
EOF
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

log() {
  printf '==> %s\n' "$*"
}

launch_activity() {
  local serial="$1"
  local component="$2"
  local launch_output
  if ! launch_output="$("$ADB_BIN" -s "$serial" shell am start -W -n "$component" 2>&1)"; then
    printf '%s\n' "$launch_output" >&2
    die "could not launch $component on $serial"
  fi
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --target)
      [ "$#" -ge 2 ] || die "--target requires phone, watch, or both"
      TARGET="$2"
      shift 2
      ;;
    --phone-serial)
      [ "$#" -ge 2 ] || die "--phone-serial requires an adb serial"
      PHONE_SERIAL="$2"
      shift 2
      ;;
    --watch-serial)
      [ "$#" -ge 2 ] || die "--watch-serial requires an adb serial"
      WATCH_SERIAL="$2"
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD=1
      shift
      ;;
    --no-launch)
      LAUNCH=0
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

case "$TARGET" in
  phone|watch|both) ;;
  *) die "invalid target '$TARGET'; expected phone, watch, or both" ;;
esac

[ -d "$ANDROID_DIR" ] || die "$ANDROID_DIR does not exist; run Expo prebuild once first"
[ -x "$ADB_BIN" ] || die "adb not found at $ADB_BIN"

device_list() {
  "$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" { print $1 }'
}

device_characteristics() {
  "$ADB_BIN" -s "$1" shell getprop ro.build.characteristics 2>/dev/null | tr -d '\r\n'
}

device_product() {
  "$ADB_BIN" -s "$1" shell getprop ro.product.name 2>/dev/null | tr -d '\r\n'
}

pick_phone_serial() {
  local candidate characteristics product
  while IFS= read -r candidate; do
    [ -n "$candidate" ] || continue
    case "$candidate" in
      emulator-*)
        characteristics="$(device_characteristics "$candidate")"
        product="$(device_product "$candidate")"
        if [[ "$characteristics" != *watch* && "$product" != *gwear* ]]; then
          printf '%s\n' "$candidate"
          return 0
        fi
        ;;
    esac
  done < <(device_list)

  while IFS= read -r candidate; do
    [ -n "$candidate" ] || continue
    characteristics="$(device_characteristics "$candidate")"
    product="$(device_product "$candidate")"
    if [[ "$characteristics" != *watch* && "$product" != *gwear* ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done < <(device_list)

  return 1
}

pick_watch_serial() {
  local avoid="${1:-}"
  local candidate characteristics product
  while IFS= read -r candidate; do
    [ -n "$candidate" ] || continue
    [ "$candidate" = "$avoid" ] && continue
    case "$candidate" in
      emulator-*)
        characteristics="$(device_characteristics "$candidate")"
        product="$(device_product "$candidate")"
        if [[ "$characteristics" == *watch* || "$product" == *gwear* ]]; then
          printf '%s\n' "$candidate"
          return 0
        fi
        ;;
    esac
  done < <(device_list)

  while IFS= read -r candidate; do
    [ -n "$candidate" ] || continue
    [ "$candidate" = "$avoid" ] && continue
    characteristics="$(device_characteristics "$candidate")"
    product="$(device_product "$candidate")"
    if [[ "$characteristics" == *watch* || "$product" == *gwear* ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done < <(device_list)

  return 1
}

require_attached() {
  local serial="$1"
  local state
  state="$("$ADB_BIN" -s "$serial" get-state 2>/dev/null | tr -d '\r\n' || true)"
  [ "$state" = device ] || die "adb target '$serial' is not attached and ready"
}

wait_for_boot() {
  local serial="$1"
  local deadline=$((SECONDS + BOOT_WAIT_SECONDS))
  local state boot

  log "Waiting for $serial to finish booting"
  while [ "$SECONDS" -lt "$deadline" ]; do
    state="$("$ADB_BIN" -s "$serial" get-state 2>/dev/null | tr -d '\r\n' || true)"
    boot="$("$ADB_BIN" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n' || true)"
    if [ "$state" = device ] && [ "$boot" = 1 ]; then
      return 0
    fi
    sleep 2
  done

  die "$serial did not finish booting within ${BOOT_WAIT_SECONDS}s"
}

build_targets() {
  [ "$SKIP_BUILD" -eq 1 ] && return 0
  case "$TARGET" in
    phone)
      log "Building :app:assembleDebug"
      (cd "$ANDROID_DIR" && ./gradlew :app:assembleDebug --no-daemon --console=plain)
      ;;
    watch)
      log "Building :paseo-wear:assembleDebug"
      (cd "$ANDROID_DIR" && ./gradlew :paseo-wear:assembleDebug --no-daemon --console=plain)
      ;;
    both)
      log "Building phone and Wear debug APKs"
      (cd "$ANDROID_DIR" && ./gradlew :app:assembleDebug :paseo-wear:assembleDebug --no-daemon --console=plain)
      ;;
  esac
}

install_phone() {
  log "Installing phone APK on $PHONE_SERIAL"
  "$ADB_BIN" -s "$PHONE_SERIAL" install -r -d "$PHONE_APK" >/dev/null
  "$ADB_BIN" -s "$PHONE_SERIAL" reverse "tcp:$METRO_PORT" "tcp:$METRO_PORT" >/dev/null
  "$ADB_BIN" -s "$PHONE_SERIAL" reverse "tcp:$DAEMON_PORT" "tcp:$DAEMON_PORT" >/dev/null
  if [ "$LAUNCH" -eq 1 ]; then
    log "Launching $PHONE_APP_ID on $PHONE_SERIAL"
    launch_activity "$PHONE_SERIAL" "$PHONE_APP_ID/.MainActivity"
  fi
}

install_watch() {
  log "Installing Wear APK on $WATCH_SERIAL"
  "$ADB_BIN" -s "$WATCH_SERIAL" install -r -d "$WEAR_APK" >/dev/null
  if [ "$LAUNCH" -eq 1 ]; then
    log "Launching $WEAR_APP_ID on $WATCH_SERIAL"
    launch_activity "$WATCH_SERIAL" "$WEAR_APP_ID/sh.paseo.glance.wear.MainActivity"
  fi
}

if [ "$TARGET" = phone ] || [ "$TARGET" = both ]; then
  [ -n "$PHONE_SERIAL" ] || PHONE_SERIAL="$(pick_phone_serial || true)"
  [ -n "$PHONE_SERIAL" ] || die "no attached phone target found; pass --phone-serial SERIAL"
  require_attached "$PHONE_SERIAL"
  wait_for_boot "$PHONE_SERIAL"
fi

if [ "$TARGET" = watch ] || [ "$TARGET" = both ]; then
  [ -n "$WATCH_SERIAL" ] || WATCH_SERIAL="$(pick_watch_serial "$PHONE_SERIAL" || true)"
  [ -n "$WATCH_SERIAL" ] || die "no attached Wear target found; pass --watch-serial SERIAL"
  require_attached "$WATCH_SERIAL"
  wait_for_boot "$WATCH_SERIAL"
fi

build_targets

if [ "$TARGET" = phone ] || [ "$TARGET" = both ]; then
  [ -f "$PHONE_APK" ] || die "phone APK not found at $PHONE_APK"
  install_phone
fi

if [ "$TARGET" = watch ] || [ "$TARGET" = both ]; then
  [ -f "$WEAR_APK" ] || die "Wear APK not found at $WEAR_APK"
  install_watch
fi

log "Deployment complete"
