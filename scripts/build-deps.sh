#!/usr/bin/env bash
# Cross-compiles QEMU's runtime dependencies as static libraries for Android:
#   zlib, libffi, pcre2, glib, pixman
source "$(dirname "$0")/env.sh"

mkdir -p "$DEPS_PREFIX" "$BUILD_DIR"
CROSS="$(meson_cross_file)"

stamp() { echo "$BUILD_DIR/.$1.done"; }

# ---- zlib -----------------------------------------------------------------
if [ ! -f "$(stamp zlib)" ]; then
  log "Building zlib"
  rm -rf "$BUILD_DIR/zlib"
  cmake -S "$SRC_DIR/zlib" -B "$BUILD_DIR/zlib" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" -DANDROID_PLATFORM="android-$ANDROID_API" \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$DEPS_PREFIX" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DZLIB_BUILD_EXAMPLES=OFF
  cmake --build "$BUILD_DIR/zlib" -j "$JOBS"
  cmake --install "$BUILD_DIR/zlib"
  # Keep only the static library so everything links statically.
  rm -f "$DEPS_PREFIX"/lib/libz.so*
  touch "$(stamp zlib)"
fi

# ---- libffi (autotools) ---------------------------------------------------
if [ ! -f "$(stamp libffi)" ]; then
  log "Building libffi"
  rm -rf "$BUILD_DIR/libffi" && mkdir -p "$BUILD_DIR/libffi"
  (cd "$BUILD_DIR/libffi" &&
    CFLAGS="$COMMON_CFLAGS" LDFLAGS="$COMMON_LDFLAGS" \
    "$SRC_DIR/libffi/configure" --host="$TRIPLE" --prefix="$DEPS_PREFIX" \
      --disable-shared --enable-static --with-pic --disable-docs \
      --disable-multi-os-directory &&
    make -j "$JOBS" && make install)
  touch "$(stamp libffi)"
fi

# ---- pcre2 ----------------------------------------------------------------
if [ ! -f "$(stamp pcre2)" ]; then
  log "Building pcre2"
  rm -rf "$BUILD_DIR/pcre2"
  cmake -S "$SRC_DIR/pcre2" -B "$BUILD_DIR/pcre2" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" -DANDROID_PLATFORM="android-$ANDROID_API" \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$DEPS_PREFIX" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_STATIC_LIBS=ON -DPCRE2_BUILD_PCRE2GREP=OFF -DPCRE2_BUILD_TESTS=OFF \
    -DPCRE2_SUPPORT_JIT=OFF
  cmake --build "$BUILD_DIR/pcre2" -j "$JOBS"
  cmake --install "$BUILD_DIR/pcre2"
  touch "$(stamp pcre2)"
fi

# ---- glib -----------------------------------------------------------------
if [ ! -f "$(stamp glib)" ]; then
  log "Building glib"
  rm -rf "$BUILD_DIR/glib"
  meson setup "$BUILD_DIR/glib" "$SRC_DIR/glib" --cross-file "$CROSS" \
    --prefix="$DEPS_PREFIX" --libdir=lib --buildtype=release \
    --default-library=static --force-fallback-for=intl \
    -Dtests=false -Dinstalled_tests=false -Dintrospection=disabled \
    -Dnls=disabled -Dselinux=disabled -Dxattr=false -Dlibmount=disabled \
    -Dman-pages=disabled -Ddocumentation=false -Dsysprof=disabled \
    -Dglib_debug=disabled
  meson compile -C "$BUILD_DIR/glib" -j "$JOBS"
  meson install -C "$BUILD_DIR/glib"
  touch "$(stamp glib)"
fi

# ---- pixman ---------------------------------------------------------------
if [ ! -f "$(stamp pixman)" ]; then
  log "Building pixman"
  rm -rf "$BUILD_DIR/pixman"
  meson setup "$BUILD_DIR/pixman" "$SRC_DIR/pixman" --cross-file "$CROSS" \
    --prefix="$DEPS_PREFIX" --libdir=lib --buildtype=release \
    --default-library=static -Dtests=disabled -Ddemos=disabled \
    -Dgtk=disabled -Dlibpng=disabled -Dopenmp=disabled
  meson compile -C "$BUILD_DIR/pixman" -j "$JOBS"
  meson install -C "$BUILD_DIR/pixman"
  touch "$(stamp pixman)"
fi

# ---- libslirp (user-mode networking, "-netdev user") ----------------------
if [ ! -f "$(stamp slirp)" ]; then
  log "Building libslirp"
  apply_patches "$SRC_DIR/libslirp" "$REPO_ROOT/patches/libslirp"
  rm -rf "$BUILD_DIR/slirp"
  meson setup "$BUILD_DIR/slirp" "$SRC_DIR/libslirp" --cross-file "$CROSS" \
    --prefix="$DEPS_PREFIX" --libdir=lib --buildtype=release \
    --default-library=static
  meson compile -C "$BUILD_DIR/slirp" -j "$JOBS"
  meson install -C "$BUILD_DIR/slirp"
  touch "$(stamp slirp)"
fi

log "Dependencies installed in $DEPS_PREFIX"
