#!/usr/bin/env bash
# Downloads the Android NDK and all sources needed for the build.
source "$(dirname "$0")/env.sh"

mkdir -p "$DL_DIR" "$SRC_DIR"

# ---- Android NDK ----------------------------------------------------------
if [ ! -x "$ANDROID_NDK_HOME/ndk-build" ]; then
  zip="$DL_DIR/android-ndk-$NDK_VERSION-linux.zip"
  if [ ! -f "$zip" ]; then
    log "Downloading Android NDK $NDK_VERSION"
    curl -fL --retry 4 -o "$zip.part" \
      "https://dl.google.com/android/repository/android-ndk-$NDK_VERSION-linux.zip"
    mv "$zip.part" "$zip"
  fi
  log "Extracting NDK"
  unzip -q "$zip" -d "$(dirname "$ANDROID_NDK_HOME")"
fi

# ---- git sources ----------------------------------------------------------
git_fetch "$SRC_DIR/qemu"     "$QEMU_GIT"   "$QEMU_VERSION"
git_fetch "$SRC_DIR/glib"     "$GLIB_GIT"   "$GLIB_VERSION"
git_fetch "$SRC_DIR/pixman"   "$PIXMAN_GIT" "$PIXMAN_VERSION"
git_fetch "$SRC_DIR/pcre2"    "$PCRE2_GIT"  "$PCRE2_VERSION"
git_fetch "$SRC_DIR/zlib"     "$ZLIB_GIT"   "$ZLIB_VERSION"
git_fetch "$SRC_DIR/libslirp" "$SLIRP_GIT"  "$SLIRP_VERSION"

# ---- libffi release tarball -----------------------------------------------
if [ ! -x "$SRC_DIR/libffi/configure" ]; then
  tgz="$DL_DIR/libffi-$LIBFFI_VERSION.tar.gz"
  [ -f "$tgz" ] || { log "Downloading libffi $LIBFFI_VERSION"; curl -fL --retry 4 -o "$tgz" "$LIBFFI_URL"; }
  echo "$LIBFFI_SHA256  $tgz" | sha256sum -c -
  rm -rf "$SRC_DIR/libffi" "$SRC_DIR/libffi-$LIBFFI_VERSION"
  tar -C "$SRC_DIR" -xf "$tgz"
  mv "$SRC_DIR/libffi-$LIBFFI_VERSION" "$SRC_DIR/libffi"
fi

# ---- GLib's proxy-libintl wrap: pre-seed meson's package cache ------------
# (GLib's primary URL for it is a GitHub archive link that is often slow or
#  blocked behind proxies; the wrapdb release asset is identical.)
cache="$SRC_DIR/glib/subprojects/packagecache"
if [ ! -f "$cache/proxy-libintl-0.5.tar.gz" ]; then
  mkdir -p "$cache"
  log "Downloading proxy-libintl"
  curl -fL --retry 4 -o "$cache/proxy-libintl-0.5.tar.gz" "$LIBINTL_URL"
fi

# ---- QEMU meson subprojects (softfloat, dtc, keycodemapdb, ...) -----------
# They are fetched from gitlab.com/qemu-project by configure; nothing to do.

log "All sources are in $SRC_DIR"
