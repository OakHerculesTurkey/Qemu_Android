# shellcheck shell=bash
# Common settings for the QEMU -> Android cross build.
# Every value can be overridden from the environment before running build.sh.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---- Target ---------------------------------------------------------------
# Android ABI: arm64-v8a (phones/tablets) or x86_64 (emulators, Chromebooks)
: "${ANDROID_ABI:=arm64-v8a}"
# Minimum Android API level. 28 (Android 9) is the lowest level where bionic
# ships iconv, which GLib needs; going lower would require building libiconv.
: "${ANDROID_API:=28}"
# QEMU system emulators to build (comma separated, see `configure --help`)
: "${QEMU_TARGETS:=aarch64-softmmu,x86_64-softmmu}"

# ---- Versions -------------------------------------------------------------
: "${NDK_VERSION:=r28c}"
: "${QEMU_VERSION:=v11.1.3}"
: "${GLIB_VERSION:=2.88.4}"
: "${PIXMAN_VERSION:=pixman-0.46.4}"
: "${LIBFFI_VERSION:=3.5.2}"
: "${LIBFFI_SHA256:=f3a3082a23b37c293a4fcd1053147b371f2ff91fa7ea1b2a52e335676bac82dc}"
: "${PCRE2_VERSION:=pcre2-10.45}"
: "${ZLIB_VERSION:=v1.3.1}"
: "${SLIRP_VERSION:=v4.9.1}"

# ---- Source locations (git) -----------------------------------------------
: "${QEMU_GIT:=https://gitlab.com/qemu-project/qemu.git}"
: "${GLIB_GIT:=https://github.com/GNOME/glib.git}"
: "${PIXMAN_GIT:=https://gitlab.com/freedesktop-sdk/mirrors/freedesktop/pixman/pixman.git}"
# libffi: the release tarball ships a pre-generated configure script
# (building from git needs a newer libtool than most distros have).
: "${LIBFFI_URL:=https://github.com/libffi/libffi/releases/download/v$LIBFFI_VERSION/libffi-$LIBFFI_VERSION.tar.gz}"
: "${PCRE2_GIT:=https://github.com/PCRE2Project/pcre2.git}"
: "${ZLIB_GIT:=https://github.com/madler/zlib.git}"
: "${SLIRP_GIT:=https://gitlab.com/qemu-project/libslirp.git}"
# GLib needs libintl; bionic has none, so GLib's proxy-libintl wrap is used.
: "${LIBINTL_URL:=https://github.com/mesonbuild/wrapdb/releases/download/proxy-libintl_0.5-1/proxy-libintl-0.5.tar.gz}"

# ---- Directories ----------------------------------------------------------
: "${WORK_DIR:=$REPO_ROOT/work}"
: "${DL_DIR:=$WORK_DIR/dl}"
: "${SRC_DIR:=$WORK_DIR/src}"
: "${BUILD_DIR:=$WORK_DIR/build/$ANDROID_ABI}"
# Sysroot-like prefix where the static dependencies get installed
: "${DEPS_PREFIX:=$WORK_DIR/deps/$ANDROID_ABI}"
# Final install prefix for QEMU (what gets packaged)
: "${QEMU_PREFIX:=$WORK_DIR/out/$ANDROID_ABI}"
: "${ANDROID_NDK_HOME:=$WORK_DIR/android-ndk-$NDK_VERSION}"
: "${JOBS:=$(nproc)}"

case "$ANDROID_ABI" in
  arm64-v8a) TRIPLE=aarch64-linux-android; CPU_FAMILY=aarch64; CPU=armv8-a ;;
  x86_64)    TRIPLE=x86_64-linux-android;  CPU_FAMILY=x86_64;  CPU=x86_64 ;;
  *) echo "Unsupported ANDROID_ABI: $ANDROID_ABI" >&2; exit 1 ;;
esac

TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
SYSROOT="$TOOLCHAIN/sysroot"

export CC="$TOOLCHAIN/bin/${TRIPLE}${ANDROID_API}-clang"
export CXX="$TOOLCHAIN/bin/${TRIPLE}${ANDROID_API}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export NM="$TOOLCHAIN/bin/llvm-nm"
export OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"
export READELF="$TOOLCHAIN/bin/llvm-readelf"

# pkg-config must only ever see the Android dependencies, never host .pc files
export PKG_CONFIG_LIBDIR="$DEPS_PREFIX/lib/pkgconfig:$DEPS_PREFIX/share/pkgconfig"
export PKG_CONFIG_PATH=""
export PKG_CONFIG_SYSROOT_DIR=""

COMMON_CFLAGS="-fPIC -O2 -I$DEPS_PREFIX/include"
COMMON_LDFLAGS="-L$DEPS_PREFIX/lib"

log() { printf '\033[1;32m==>\033[0m %s\n' "$*"; }

# Generates a meson cross file for the selected ABI and prints its path.
meson_cross_file() {
  local f="$BUILD_DIR/android-$ANDROID_ABI.cross"
  mkdir -p "$BUILD_DIR"
  sed -e "s|@CC@|$CC|g" -e "s|@CXX@|$CXX|g" -e "s|@AR@|$AR|g" \
      -e "s|@STRIP@|$STRIP|g" -e "s|@RANLIB@|$RANLIB|g" -e "s|@NM@|$NM|g" \
      -e "s|@OBJCOPY@|$OBJCOPY|g" -e "s|@CPU_FAMILY@|$CPU_FAMILY|g" \
      -e "s|@CPU@|$CPU|g" -e "s|@PREFIX@|$DEPS_PREFIX|g" \
      "$REPO_ROOT/cross/android.cross.in" > "$f"
  echo "$f"
}

# git_fetch <dir> <url> <ref>: shallow clone of a tag/branch, idempotent.
git_fetch() {
  local dir="$1" url="$2" ref="$3"
  if [ -d "$dir/.git" ]; then
    return 0
  fi
  log "Cloning $url ($ref)"
  git -c advice.detachedHead=false clone --depth 1 -b "$ref" "$url" "$dir"
}
