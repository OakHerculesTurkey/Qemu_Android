#!/usr/bin/env bash
# Packs the installed QEMU into dist/qemu-<version>-android-<abi>.tar.gz
source "$(dirname "$0")/env.sh"

[ -x "$QEMU_PREFIX/bin/qemu-img" ] || { echo "Run build-qemu.sh first" >&2; exit 1; }

STAGE="$WORK_DIR/stage/qemu-android-$ANDROID_ABI"
rm -rf "$STAGE" && mkdir -p "$STAGE"
cp -a "$QEMU_PREFIX/bin" "$QEMU_PREFIX/share" "$STAGE/"
rm -rf "$STAGE/share/applications" "$STAGE/share/icons" "$STAGE/share/locale"

# QEMU installs the firmware of *every* architecture. Unless asked not to,
# drop the blobs that the aarch64/x86_64 system emulators never use.
if [ "${QEMU_PRUNE_BLOBS:-1}" = 1 ]; then
  ( cd "$STAGE/share/qemu" &&
    rm -f -- *riscv* *loongarch* openbios-* slof.bin skiboot.lid vof*.bin \
             s390-* hppa-firmware* palcode-* u-boot* opensbi-* npcm*.bin \
             qemu_vga.ndrv canyonlands.dtb bamboo.dtb petalogix-*.dtb \
             pnv-*.dtb edk2-arm-code.fd )
fi

cp "$REPO_ROOT/android/qemu-env.sh" "$STAGE/"
cat > "$STAGE/BUILDINFO" <<EOF
qemu=$QEMU_VERSION
targets=$QEMU_TARGETS
abi=$ANDROID_ABI
min_api=$ANDROID_API
ndk=$NDK_VERSION
glib=$GLIB_VERSION
pixman=$PIXMAN_VERSION
slirp=$SLIRP_VERSION
built=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

mkdir -p "$REPO_ROOT/dist"
out="$REPO_ROOT/dist/qemu-${QEMU_VERSION#v}-android-$ANDROID_ABI.tar.gz"
tar -C "$(dirname "$STAGE")" -czf "$out" "$(basename "$STAGE")"
log "Package: $out ($(du -h "$out" | cut -f1))"
