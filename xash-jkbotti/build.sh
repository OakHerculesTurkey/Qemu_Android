#!/usr/bin/env bash
# jk_botti for Xash3D FWGS on Android (arm64-v8a, Snapdragon 8 Elite / Android 17).
#
#   ./build.sh            # fetch + patch + build libs + APK
#   ./build.sh libs       # only the four .so files (dist/libs/<abi>/)
#
# Environment:
#   ANDROID_NDK_HOME  NDK to use (default: downloads NDK r28c into work/)
#   ANDROID_HOME      Android SDK (default: work/android-sdk, installed on demand)
#   JK_ABI            arm64-v8a (default) | x86_64 | armeabi-v7a
#   JK_KEYSTORE / JK_KEYSTORE_PASSWORD   release signing key (optional)
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$PWD"
WORK="$ROOT/work"
SRC="$WORK/src"
DL="$WORK/dl"
DIST="$ROOT/dist"
: "${JK_ABI:=arm64-v8a}"
: "${NDK_VERSION:=r28c}"
: "${ANDROID_NDK_HOME:=$WORK/android-ndk-$NDK_VERSION}"
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$WORK/android-sdk}}"
: "${ANDROID_PLATFORM:=android-28}"
export ANDROID_HOME

# Pinned upstream sources (bump deliberately; patches are made against these)
JK_BOTTI_URL=https://github.com/Bots-United/jk_botti
JK_BOTTI_REV=b8d46629d330928b36f3a523d5f1c1f21b83c628
METAMOD_URL=https://github.com/FWGS/metamod-fwgs
METAMOD_REV=7cb9a6cc94c1b0248080691137a6adfb55a468c2
HLSDK_URL=https://github.com/FWGS/hlsdk-portable
HLSDK_REV=89e83b92d561f5ceb11594c2f15e2e1519bc6c36

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

retry() {
  local n=0
  until "$@"; do
    n=$((n + 1)); [ $n -ge 4 ] && return 1
    sleep $((2 ** n))
  done
}

# fetch <name> <url> <rev>: shallow checkout of one commit, submodules included,
# then apply patches/<name>/*.patch (once).
fetch() {
  local name=$1 url=$2 rev=$3 dir="$SRC/$1"
  if [ ! -f "$dir/.jk-$rev" ]; then
    log "Fetching $name @ ${rev:0:10}"
    rm -rf "$dir"; mkdir -p "$dir"
    git -C "$dir" init -q
    git -C "$dir" remote add origin "$url"
    retry git -C "$dir" fetch -q --depth 1 origin "$rev"
    git -C "$dir" checkout -q FETCH_HEAD
    retry git -C "$dir" submodule update -q --init --recursive --depth 1
    for p in "$ROOT/patches/$name"/*.patch; do
      [ -e "$p" ] || continue
      log "Applying $(basename "$p")"
      git -C "$dir" apply --whitespace=nowarn "$p"
    done
    touch "$dir/.jk-$rev"
  fi
}

# ---- NDK ---------------------------------------------------------------------
if [ ! -f "$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" ]; then
  log "Downloading Android NDK $NDK_VERSION"
  mkdir -p "$DL"
  retry curl -fL -o "$DL/ndk.zip" "https://dl.google.com/android/repository/android-ndk-$NDK_VERSION-linux.zip"
  unzip -q -o "$DL/ndk.zip" -d "$WORK"
  rm -f "$DL/ndk.zip"
fi

fetch jk_botti     "$JK_BOTTI_URL" "$JK_BOTTI_REV"
fetch metamod-fwgs "$METAMOD_URL"  "$METAMOD_REV"
fetch hlsdk-portable "$HLSDK_URL"  "$HLSDK_REV"

NDK_CMAKE=(
  -G Ninja
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
  -DANDROID_ABI="$JK_ABI"
  -DANDROID_PLATFORM="$ANDROID_PLATFORM"
)
B="$WORK/build/$JK_ABI"

log "Building Metamod-FWGS"
cmake -S "$SRC/metamod-fwgs" -B "$B/metamod" "${NDK_CMAKE[@]}" >/dev/null
cmake --build "$B/metamod"

log "Building Half-Life (hlsdk-portable: server + client)"
cmake -S "$SRC/hlsdk-portable" -B "$B/hlsdk" "${NDK_CMAKE[@]}" -D64BIT=ON >/dev/null
cmake --build "$B/hlsdk"

log "Building jk_botti"
cmake -S "$ROOT/jk_botti-android" -B "$B/jk_botti" "${NDK_CMAKE[@]}" -DJK_BOTTI_SRC="$SRC/jk_botti" >/dev/null
cmake --build "$B/jk_botti"

OUT="$DIST/libs/$JK_ABI"
rm -rf "$OUT"; mkdir -p "$OUT"
cp "$B"/metamod/metamod/libmetamod_android_*.so \
   "$B"/hlsdk/dlls/libhl_android_*.so \
   "$B"/hlsdk/cl_dll/libclient_android_*.so \
   "$B"/jk_botti/libjk_botti_mm_android_*.so "$OUT/"
ls -l "$OUT"

[ "${1:-}" = "libs" ] && exit 0

# ---- Android SDK (platform 37 = Android 17) ----------------------------------
if [ ! -d "$ANDROID_HOME/platforms/android-37.0" ] || [ ! -d "$ANDROID_HOME/build-tools/37.0.0" ]; then
  log "Installing Android SDK into $ANDROID_HOME"
  if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    mkdir -p "$DL" "$ANDROID_HOME/cmdline-tools"
    retry curl -fL -o "$DL/cmdline-tools.zip" \
      https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
    rm -rf "$ANDROID_HOME/cmdline-tools/latest" "$ANDROID_HOME/cmdline-tools/cmdline-tools"
    unzip -q "$DL/cmdline-tools.zip" -d "$ANDROID_HOME/cmdline-tools"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  fi
  # `yes` dies of SIGPIPE once sdkmanager stops reading: ignore the pipe status
  # and check the result instead.
  (set +o pipefail; yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
    "platforms;android-37.0" "build-tools;37.0.0" "platform-tools" >/dev/null) || true
  [ -d "$ANDROID_HOME/platforms/android-37.0" ] && [ -d "$ANDROID_HOME/build-tools/37.0.0" ] \
    || { echo "Android SDK installation failed" >&2; exit 1; }
fi

log "Building launcher APK"
APP="$ROOT/launcher"
JNI="$APP/app/src/main/jniLibs"
rm -rf "$JNI"; mkdir -p "$JNI/$JK_ABI"
cp "$OUT"/*.so "$JNI/$JK_ABI/"
echo "sdk.dir=$ANDROID_HOME" > "$APP/local.properties"
(cd "$APP" && ./gradlew --no-daemon -q -PjkAbis="$JK_ABI" assembleRelease)

ver="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$APP/app/build.gradle.kts")"
if [ -n "${JK_KEYSTORE:-}" ] && [ -f "$JK_KEYSTORE" ] && [ -n "${JK_KEYSTORE_PASSWORD:-}" ]; then
  apk="$DIST/jk_botti-xash-$ver.apk"
else
  echo "NOTE: JK_KEYSTORE/JK_KEYSTORE_PASSWORD not set, signing with the local debug key" >&2
  apk="$DIST/jk_botti-xash-$ver-debugkey.apk"
fi
cp "$APP/app/build/outputs/apk/release/app-release.apk" "$apk"
log "APK: $apk ($(du -h "$apk" | cut -f1))"
