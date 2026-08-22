#!/usr/bin/env bash
# redeploy-watch.sh — incremental rebuild + install of the Wear (:paseo-wear) APK
# to a running Wear OS emulator, then launches its MainActivity.
#
# Usage:
#   ./scripts/redeploy-watch.sh [serial]
#   WATCH_SERIAL=emulator-5554 ./scripts/redeploy-watch.sh
#
# Requires packages/app/android to already exist (run `npm run android` or
# `expo prebuild --platform android` once first). Does NOT run `expo prebuild
# --clean` — this is meant for fast native-only iteration on the wear module.
set -euo pipefail

cd "$(dirname "$0")/.."

if [ ! -d android ]; then
  echo "error: packages/app/android does not exist. Run 'npm run android' once to prebuild it." >&2
  exit 1
fi

SERIAL="${1:-${WATCH_SERIAL:-}}"
if [ -z "$SERIAL" ]; then
  SERIAL="emulator-5554"
  if command -v adb >/dev/null 2>&1; then
    wear_devices="$(adb devices -l | awk '/device product:sdk_gwear/ {print $1}')"
    wear_count="$(printf '%s\n' "$wear_devices" | grep -c .)"
    if [ "$wear_count" -eq 1 ]; then
      SERIAL="$wear_devices"
    fi
  fi
fi

APP_ID="${WEAR_APP_ID:-sh.paseo.debug}"
ACTIVITY="sh.paseo.glance.wear.MainActivity"

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

echo "==> Building :paseo-wear:assembleDebug"
(cd android && ./gradlew :paseo-wear:assembleDebug --console=plain)

echo "==> Installing to $SERIAL"
adb -s "$SERIAL" install -r modules/paseo-wear/build/outputs/apk/debug/paseo-wear-debug.apk

echo "==> Launching $APP_ID/$ACTIVITY on $SERIAL"
adb -s "$SERIAL" shell am start -n "$APP_ID/$ACTIVITY"

echo "==> Done"
