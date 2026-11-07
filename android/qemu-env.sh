# shellcheck shell=sh
# Source this on the Android device (adb shell or Termux) from the unpacked
# package directory:
#
#   cd /data/local/tmp/qemu-android-arm64-v8a && . ./qemu-env.sh
#
# QEMU is built "relocatable": it finds its firmware in ../share/qemu relative
# to the binary, so no -L option is needed as long as the layout is kept.

QEMU_ANDROID_HOME="$(cd "$(dirname "$0" 2>/dev/null || echo .)" && pwd)"
case "$QEMU_ANDROID_HOME" in
  */qemu-android-*) ;;
  *) QEMU_ANDROID_HOME="$(pwd)" ;;
esac
export QEMU_ANDROID_HOME
export PATH="$QEMU_ANDROID_HOME/bin:$PATH"
# Android has no /tmp; QEMU and GLib use TMPDIR for temporary files.
[ -n "${TMPDIR:-}" ] || export TMPDIR="$QEMU_ANDROID_HOME/tmp"
mkdir -p "$TMPDIR"
chmod 755 "$QEMU_ANDROID_HOME"/bin/* 2>/dev/null || true

echo "QEMU ready: $(qemu-system-aarch64 --version 2>/dev/null | head -n1)"
