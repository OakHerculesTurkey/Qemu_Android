#!/usr/bin/env bash
# Cross-compiles QEMU for Android against the dependencies from build-deps.sh.
source "$(dirname "$0")/env.sh"

QEMU_SRC="$SRC_DIR/qemu"
QEMU_BUILD="$BUILD_DIR/qemu"

# Apply Android patches once (tracked with a stamp inside the source tree).
apply_patches "$QEMU_SRC" "$REPO_ROOT/patches/qemu"

export PKG_CONFIG=pkg-config

mkdir -p "$QEMU_BUILD"
cd "$QEMU_BUILD"

if [ ! -f build.ninja ]; then
  log "Configuring QEMU $QEMU_VERSION for $ANDROID_ABI (API $ANDROID_API)"
  "$QEMU_SRC/configure" \
    --prefix="$QEMU_PREFIX" \
    --cross-prefix="$TOOLCHAIN/bin/llvm-" \
    --cc="$CC" --cxx="$CXX" --host-cc=cc \
    --extra-cflags="$COMMON_CFLAGS" \
    --extra-ldflags="$COMMON_LDFLAGS" \
    --target-list="$QEMU_TARGETS" \
    --without-default-features \
    --disable-docs --disable-werror --disable-rust \
    --enable-tcg --enable-kvm \
    --enable-tools \
    --enable-slirp \
    --enable-vnc \
    --enable-pixman \
    --enable-fdt=internal \
    --enable-coroutine-pool \
    --enable-strip \
    ${QEMU_EXTRA_CONFIGURE:-}
fi

log "Compiling QEMU"
ninja -j "$JOBS"
log "Installing QEMU into $QEMU_PREFIX"
rm -rf "$QEMU_PREFIX"
ninja install
