#!/usr/bin/env bash
# Tests the Android app's pure-Java core (command builder, VNC/RFB client,
# QMP client, serial console) against the real QEMU Android x86_64 build,
# executed through bionic's linker64 (see smoke-test.sh for the bionic part).
#
#   ANDROID_ABI=x86_64 ./build.sh && ./scripts/smoke-test.sh && ./scripts/test-app-core.sh
ANDROID_ABI=x86_64
source "$(dirname "$0")/env.sh"

BIONIC_DIR="$WORK_DIR/bionic-x86_64"
[ -x "$BIONIC_DIR/linker64" ] || { echo "Run scripts/smoke-test.sh first (extracts bionic)" >&2; exit 1; }

T="$WORK_DIR/app-core-test"
rm -rf "$T" && mkdir -p "$T/classes" "$T/bin" "$T/vms"

# Same file names the app uses in nativeLibraryDir
for b in qemu-system-aarch64 qemu-system-x86_64 qemu-img; do
  ln -s "$QEMU_PREFIX/bin/$b" "$T/bin/lib$b.so"
done

"$TOOLCHAIN/bin/clang" --target=aarch64-none-elf -nostdlib -ffreestanding \
  -fuse-ld=lld -Wl,-T,"$REPO_ROOT/tests/baremetal/link.ld" \
  -o "$T/hello.elf" "$REPO_ROOT/tests/baremetal/hello-aarch64.S"

javac -d "$T/classes" \
  "$REPO_ROOT"/android-app/app/src/main/java/com/oakherculesturkey/qemu/core/*.java \
  "$REPO_ROOT/tests/app-core/CoreTest.java"
java -cp "$T/classes" CoreTest "$BIONIC_DIR" "$T/bin" "$QEMU_PREFIX/share/qemu" "$T/vms" "$T/hello.elf"
