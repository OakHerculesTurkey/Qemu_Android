#!/usr/bin/env bash
# Installs the host (build machine) tools on Debian/Ubuntu.
set -euo pipefail

SUDO=""
[ "$(id -u)" = 0 ] || SUDO=sudo

$SUDO apt-get update
$SUDO apt-get install -y --no-install-recommends \
  build-essential git curl ca-certificates unzip xz-utils \
  python3 python3-pip python3-venv ninja-build cmake pkg-config \
  autoconf automake libtool bison flex

# GLib, pixman and libslirp need a recent meson (QEMU uses its own bundled copy).
if ! command -v meson >/dev/null || \
   ! python3 -c 'import sys,subprocess; v=subprocess.check_output(["meson","--version"]).decode().split("."); sys.exit(0 if (int(v[0]),int(v[1]))>=(1,5) else 1)'; then
  python3 -m pip install --user --break-system-packages 'meson>=1.5' 2>/dev/null \
    || python3 -m pip install --user 'meson>=1.5'
  echo "meson installed into ~/.local/bin - make sure it is on PATH"
fi
