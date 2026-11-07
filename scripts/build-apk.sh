#!/usr/bin/env bash
# Builds the Android app (APK) around the cross-compiled QEMU.
#
#   ./build.sh && ./scripts/build-apk.sh          # arm64-v8a APK (phones)
#   APK_ABIS=arm64-v8a,x86_64 ./scripts/build-apk.sh
#
# Output: dist/qemu-android-<version>.apk
source "$(dirname "$0")/env.sh"

: "${APK_ABIS:=arm64-v8a}"
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$WORK_DIR/android-sdk}}"
export ANDROID_HOME
APP="$REPO_ROOT/android-app"

# ---- Android SDK (platform 37 = Android 17) ---------------------------------
if [ ! -d "$ANDROID_HOME/platforms/android-37.0" ] || [ ! -d "$ANDROID_HOME/build-tools/37.0.0" ]; then
  log "Installing Android SDK into $ANDROID_HOME"
  if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    mkdir -p "$DL_DIR" "$ANDROID_HOME/cmdline-tools"
    curl -fL --retry 4 -o "$DL_DIR/cmdline-tools.zip" \
      https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
    rm -rf "$ANDROID_HOME/cmdline-tools/latest" "$ANDROID_HOME/cmdline-tools/cmdline-tools"
    unzip -q "$DL_DIR/cmdline-tools.zip" -d "$ANDROID_HOME/cmdline-tools"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  fi
  yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
    "platforms;android-37.0" "build-tools;37.0.0" "platform-tools" >/dev/null
fi

# ---- QEMU binaries -> jniLibs, firmware -> assets ---------------------------
JNI="$APP/app/src/main/jniLibs"
ASSETS="$APP/app/src/main/assets/qemu"
rm -rf "$JNI" "$ASSETS"
first_abi=""
IFS=',' read -ra abis <<<"$APK_ABIS"
for abi in "${abis[@]}"; do
  out="$WORK_DIR/out/$abi"
  [ -x "$out/bin/qemu-system-aarch64" ] || { echo "QEMU for $abi not built: ANDROID_ABI=$abi ./build.sh" >&2; exit 1; }
  mkdir -p "$JNI/$abi"
  for b in qemu-system-aarch64 qemu-system-x86_64 qemu-img; do
    [ -f "$out/bin/$b" ] && cp "$out/bin/$b" "$JNI/$abi/lib$b.so"
  done
  [ -n "$first_abi" ] || first_abi="$abi"
done

# Firmware is architecture independent: take the pruned set from package.sh
stage="$WORK_DIR/stage/qemu-android-$first_abi/share/qemu"
if [ ! -d "$stage" ]; then
  ANDROID_ABI="$first_abi" "$REPO_ROOT/scripts/package.sh"
fi
mkdir -p "$ASSETS"
cp -a "$stage/." "$ASSETS/"
log "Packed $(du -sh "$JNI" | cut -f1) of binaries, $(du -sh "$ASSETS" | cut -f1) of firmware"

# ---- Gradle -------------------------------------------------------------------
cd "$APP"
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew --no-daemon -PqemuAbis="$APK_ABIS" assembleRelease

ver="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
mkdir -p "$REPO_ROOT/dist"
apk="$REPO_ROOT/dist/qemu-android-$ver.apk"
cp app/build/outputs/apk/release/app-release.apk "$apk"
log "APK: $apk ($(du -h "$apk" | cut -f1))"
