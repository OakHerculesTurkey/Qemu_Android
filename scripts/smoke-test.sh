#!/usr/bin/env bash
# Runs the x86_64 *Android* build on this x86_64 Linux machine, using the real
# bionic dynamic linker/libc taken from an Android emulator system image:
#
#   linker64 ./qemu-system-aarch64 ...   (LD_LIBRARY_PATH = bionic libs)
#
# This proves the binaries load and work against Android's libc, and boots a
# tiny bare-metal aarch64 guest through the TCG JIT.
#
#   ANDROID_ABI=x86_64 ./build.sh && ./scripts/smoke-test.sh
ANDROID_ABI=x86_64
source "$(dirname "$0")/env.sh"

: "${SYSIMG_ZIP_URL:=https://dl.google.com/android/repository/sys-img/android/x86_64-30_r11.zip}"
BIONIC_DIR="$WORK_DIR/bionic-x86_64"
BIN="$QEMU_PREFIX/bin"

[ -x "$BIN/qemu-system-aarch64" ] || { echo "Build ANDROID_ABI=x86_64 first" >&2; exit 1; }

if [ ! -x "$BIONIC_DIR/linker64" ]; then
  zip="$DL_DIR/$(basename "$SYSIMG_ZIP_URL")"
  [ -f "$zip" ] || { log "Downloading Android system image"; curl -fL --retry 4 -o "$zip" "$SYSIMG_ZIP_URL"; }
  tmp="$WORK_DIR/sysimg-tmp"
  rm -rf "$tmp" && mkdir -p "$tmp"
  unzip -q -o "$zip" 'x86_64/system.img' -d "$tmp"
  python3 -I "$REPO_ROOT/scripts/extract-bionic.py" "$tmp/x86_64/system.img" "$BIONIC_DIR"
  rm -rf "$tmp"
fi

# Note: started through linker64, /proc/self/exe points at the linker, so
# QEMU's relocatable firmware lookup fails; pass -L explicitly. (On a device
# the binaries are executed directly and find ../share/qemu on their own.)
DATA="$QEMU_PREFIX/share/qemu"
run() { LD_LIBRARY_PATH="$BIONIC_DIR" "$BIONIC_DIR/linker64" "$@"; }
export TMPDIR="$WORK_DIR/tmp"; mkdir -p "$TMPDIR"
fail=0
check() { if "$@"; then echo "PASS: $*" | cut -c1-100; else echo "FAIL: $*"; fail=1; fi; }

log "Versions"
for b in qemu-system-aarch64 qemu-system-x86_64 qemu-img; do
  check run "$BIN/$b" --version
done

log "qemu-img round trip"
img="$TMPDIR/smoke.qcow2"
rm -f "$img"
check run "$BIN/qemu-img" create -f qcow2 "$img" 1G
check run "$BIN/qemu-img" info "$img"

log "Booting bare-metal aarch64 guest (TCG)"
guest="$TMPDIR/hello-aarch64.elf"
"$TOOLCHAIN/bin/clang" --target=aarch64-none-elf -nostdlib -ffreestanding \
  -fuse-ld=lld -Wl,-T,"$REPO_ROOT/tests/baremetal/link.ld" \
  -o "$guest" "$REPO_ROOT/tests/baremetal/hello-aarch64.S"
out="$(timeout 60 bash -c 'LD_LIBRARY_PATH="$0" "$0/linker64" "$1" -L "$3" -M virt -cpu cortex-a57 \
        -display none -serial stdio -monitor none -semihosting -kernel "$2" 2>/dev/null' \
        "$BIONIC_DIR" "$BIN/qemu-system-aarch64" "$guest" "$DATA")" || fail=1
echo "$out"
if grep -q "Hello from an aarch64 guest" <<<"$out"; then echo "PASS: guest boot"; else echo "FAIL: guest boot"; fail=1; fi

log "x86_64 guest: SeaBIOS starts"
out="$(timeout 20 bash -c 'LD_LIBRARY_PATH="$0" "$0/linker64" "$1" -L "$2" -display none \
        -chardev stdio,id=dbg -device isa-debugcon,iobase=0x402,chardev=dbg \
        -monitor none -serial none -no-reboot' \
        "$BIONIC_DIR" "$BIN/qemu-system-x86_64" "$DATA" 2>&1 | head -c 4000)" || true
if grep -q "SeaBIOS" <<<"$out"; then echo "PASS: SeaBIOS banner"; else echo "$out" | head; echo "FAIL: SeaBIOS"; fail=1; fi

[ "$fail" = 0 ] && log "All smoke tests passed" || { echo "Some smoke tests failed" >&2; exit 1; }
