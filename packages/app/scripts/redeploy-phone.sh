#!/usr/bin/env bash
# redeploy-phone.sh — incremental rebuild + install of the phone debug APK
# (:app) to a running Android phone emulator, wires up Metro/daemon port
# forwarding, and launches the app.
#
# Usage:
#   ./scripts/redeploy-phone.sh [serial]
#   PHONE_SERIAL=emulator-5556 ./scripts/redeploy-phone.sh
#
# Requires packages/app/android to already exist (run `npm run android` or
# `expo prebuild --platform android` once first). Does NOT run `expo prebuild
# --clean` — this is meant for fast native-only iteration.
set -euo pipefail

cd "$(dirname "$0")/.."

if [ ! -d android ]; then
  echo "error: packages/app/android does not exist. Run 'npm run android' once to prebuild it." >&2
  exit 1
fi

SERIAL="${1:-${PHONE_SERIAL:-}}"
if [ -z "$SERIAL" ]; then
  SERIAL="emulator-5556"
  if command -v adb >/dev/null 2>&1; then
    phone_devices="$(adb devices -l | awk '/device product:sdk_gphone/ {print $1}')"
    phone_count="$(printf '%s\n' "$phone_devices" | grep -c .)"
    if [ "$phone_count" -eq 1 ]; then
      SERIAL="$phone_devices"
    fi
  fi
fi

APP_ID="${PHONE_APP_ID:-sh.paseo.debug}"
DAEMON_PORT="${PASEO_SERVICE_DAEMON_PORT:-6768}"

# Android Gradle Plugin requires Java 17+. Fall back to the mise-pinned
# Java 21 (or Homebrew's openjdk@21) if JAVA_HOME isn't already set to one.
if [ -z "${JAVA_HOME:-}" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -qE '"(1[7-9]|[2-9][0-9])\.'; then
  for candidate in \
    "$HOME/.local/share/mise/installs/java"/21*/Contents/Home \
    /opt/homebrew/Cellar/openjdk@21/*/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; do
    if [ -x "$candidate/bin/java" ]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi

if [ -z "${ANDROID_HOME:-}" ]; then
  for candidate in \
    "$HOME"/.local/share/mise/installs/android-sdk/* \
    "$HOME/Library/Android/sdk"; do
    if [ -d "$candidate" ]; then
      export ANDROID_HOME="$candidate"
      break
    fi
  done
fi

echo "==> Building :app:assembleDebug"
(cd android && ./gradlew :app:assembleDebug --console=plain)

echo "==> Installing to $SERIAL"
adb -s "$SERIAL" install -r android/app/build/outputs/apk/debug/app-debug.apk

echo "==> Forwarding Metro (8081) and daemon ($DAEMON_PORT) ports"
adb -s "$SERIAL" reverse tcp:8081 tcp:8081
adb -s "$SERIAL" reverse "tcp:$DAEMON_PORT" "tcp:$DAEMON_PORT"

echo "==> Launching $APP_ID on $SERIAL"
adb -s "$SERIAL" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1

echo "==> Done"
