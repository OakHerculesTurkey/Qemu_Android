#!/usr/bin/env bash
# Runs the *arm64* Android build on this x86_64 machine under QEMU's own
# aarch64 user-mode emulator with "-cpu max" (pointer authentication, BTI, ...)
# and Android's real bionic (taken from an arm64 emulator image).
#
# Regression test for SIGILL on PAC-capable phones (Snapdragon 8 Elite, ...):
# Android gives every thread its own PAC keys, so coroutines that QEMU moves
# between threads must not use bionic's PAC-signing sigsetjmp/siglongjmp.
# Disk I/O runs in coroutines, so the guests below attach disks.
#
#   ./build.sh && ./scripts/test-arm64-pac.sh
ANDROID_ABI=arm64-v8a
source "$(dirname "$0")/env.sh"

: "${SYSIMG_ARM64_URL:=https://dl.google.com/android/repository/sys-img/android/arm64-v8a-33_r02.zip}"
BIONIC="$WORK_DIR/bionic-arm64"
HOSTQ="$WORK_DIR/build/host-user/qemu-aarch64"
BIN="$QEMU_PREFIX/bin"
DATA="$QEMU_PREFIX/share/qemu"
T="$WORK_DIR/pac-test"

[ -x "$BIN/qemu-system-x86_64" ] || { echo "Build arm64-v8a first" >&2; exit 1; }

if [ ! -x "$HOSTQ" ]; then
  log "Building host qemu-aarch64 (linux-user)"
  mkdir -p "$WORK_DIR/build/host-user"
  (cd "$WORK_DIR/build/host-user" &&
    "$SRC_DIR/qemu/configure" --target-list=aarch64-linux-user --disable-system \
      --disable-tools --disable-docs --disable-werror --without-default-features \
      --disable-rust >/dev/null &&
    ninja -j "$JOBS" qemu-aarch64 >/dev/null)
fi

if [ ! -x "$BIONIC/linker64" ]; then
  zip="$DL_DIR/$(basename "$SYSIMG_ARM64_URL")"
  [ -f "$zip" ] || { log "Downloading arm64 Android system image"; curl -fL --retry 4 -o "$zip" "$SYSIMG_ARM64_URL"; }
  tmp="$WORK_DIR/sysimg-arm64-tmp"
  rm -rf "$tmp" && mkdir -p "$tmp"
  unzip -q -o "$zip" 'arm64-v8a/system.img' -d "$tmp"
  python3 -I "$REPO_ROOT/scripts/extract-bionic.py" "$tmp/arm64-v8a/system.img" "$BIONIC"
  rm -rf "$tmp"
fi

rm -rf "$T" && mkdir -p "$T"
fail=0

# qemu-img (itself an arm64 Android binary) creates the disk
"$HOSTQ" -cpu max -E LD_LIBRARY_PATH="$BIONIC" "$BIONIC/linker64" "$BIN/qemu-img" \
  create -f qcow2 "$T/disk.qcow2" 2G 2>&1 | grep -v linkerconfig || true
truncate -s 64M "$T/cd.iso"

run_vm() { # <name> <seconds> <binary> <args...>
  local name="$1" secs="$2" bin="$3"; shift 3
  local rc=0
  timeout "$secs" "$HOSTQ" -cpu max -E LD_LIBRARY_PATH="$BIONIC" "$BIONIC/linker64" \
    "$BIN/$bin" -L "$DATA" "$@" > "$T/$name.out" 2>&1 || rc=$?
  if [ $rc -eq 124 ] && ! grep -q "QEMU-ANDROID CRASH" "$T/$name.out"; then
    echo "PASS: $name kept running (no crash)"
  else
    echo "FAIL: $name exited with $rc"; grep -v "linkerconfig\|tzdata" "$T/$name.out" | head -30
    fail=1
  fi
}

log "x86_64 PC guest, IDE disk + CD-ROM, MTTCG (the configuration that crashed)"
run_vm x86-ide 120 qemu-system-x86_64 -machine q35 -accel tcg,thread=multi -cpu max -smp 4 -m 2048M \
  -display none -vga std -monitor none -serial none -parallel none \
  -device qemu-xhci -device usb-kbd -device usb-tablet \
  -drive file="$T/disk.qcow2",if=none,id=hd0,format=qcow2,cache=writeback \
  -device ide-hd,drive=hd0,bus=ide.0,bootindex=1 \
  -drive file="$T/cd.iso",if=none,id=cd0,media=cdrom,readonly=on,format=raw \
  -device ide-cd,drive=cd0,bus=ide.1,bootindex=0 \
  -netdev user,id=n0 -device e1000,netdev=n0 \
  -chardev file,id=dbg,path="$T/bios.log" -device isa-debugcon,iobase=0x402,chardev=dbg
if grep -q "Booting from Hard Disk" "$T/bios.log" 2>/dev/null; then
  echo "PASS: SeaBIOS read the CD-ROM and the hard disk"
else
  echo "FAIL: SeaBIOS did not reach the disks"; tail -5 "$T/bios.log"; fail=1
fi

log "aarch64 guest, UEFI + virtio-blk disk"
run_vm arm-virtio 120 qemu-system-aarch64 -machine virt,gic-version=3 -accel tcg,thread=multi \
  -cpu max,pauth-impdef=on -smp 2 -m 1024M -display none -monitor none -serial none \
  -bios "$DATA/edk2-aarch64-code.fd" \
  -drive file="$T/disk.qcow2",if=none,id=hd0,format=qcow2 -device virtio-blk-pci,drive=hd0

[ "$fail" = 0 ] && log "PAC regression test passed" || { echo "PAC regression test FAILED" >&2; exit 1; }
