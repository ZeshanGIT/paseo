# Android development workflow

Use the generated Android project for fast iteration. The deploy helper does
not run Expo prebuild, so a clean prebuild is only needed after changing native
configuration or tracked config-plugin/module code.

## Start the development services

From `paseo/`, keep the isolated development services running:

```bash
npm run dev:server
npm run dev:app
```

The phone app expects Metro on `8081` and the isolated Paseo service on `6768`.
The workflow never starts or restarts the main Paseo daemon on `6767`.

## Start the emulators

The configured AVDs on this workstation are:

- `Paseo_Phone_API_35`
- `Wear_OS_Large_Round`
- `Wear_OS_Large_Round_2`

Start one phone and one Wear AVD, then inspect their serials:

```bash
export ANDROID_SDK_ROOT="/Users/seshan-12821/Library/Android/sdk"
"$ANDROID_SDK_ROOT/emulator/emulator" -avd Paseo_Phone_API_35 -no-boot-anim
"$ANDROID_SDK_ROOT/emulator/emulator" -avd Wear_OS_Large_Round -no-boot-anim
adb devices -l
```

Run each emulator command in its own terminal or background process. Prefer
explicit serials when more than one emulator or a physical device is attached.

## Build and deploy

From `paseo/packages/app/`:

```bash
./scripts/deploy-android-dev.sh \
  --target both \
  --phone-serial emulator-5554 \
  --watch-serial emulator-5556
```

The script also supports `--target phone`, `--target watch`, `--skip-build`,
and `--no-launch`. If serials are omitted, attached emulator phone/Wear
targets are selected by their Android characteristics. `PHONE_SERIAL` and
`WATCH_SERIAL` can be used instead of flags.

The existing `redeploy-phone.sh` and `redeploy-watch.sh` wrappers remain
available for compatibility, but the target-aware script above is the normal
path for new iterations.

## Capture diagnostics

Capture a screenshot and logs into the ignored `paseo/.dev/device-captures/`
directory:

```bash
./scripts/capture-android-debug.sh --serial emulator-5554 --label phone
./scripts/capture-android-debug.sh --serial emulator-5556 --label watch
```

Each capture directory contains the screenshot, activity/device state, full
logcat, and a focused logcat containing `WearDebug`, `PaseoWearBridge`,
`PaseoGlance`, and React Native startup messages. The script prints only the
capture directory, so inspect or summarize those files separately when needed.

## Physical devices later

The same deploy script accepts physical `adb` serials, but physical-device
setup is intentionally not part of the emulator loop. Wear connectivity is
described as paired-phone/Data-Layer availability; do not infer strict
Bluetooth-only behavior from an emulator run.
