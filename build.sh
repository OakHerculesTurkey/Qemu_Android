#!/usr/bin/env bash
# One-shot build: fetch -> deps -> qemu -> package.
#
#   ./build.sh                       # arm64-v8a (default)
#   ANDROID_ABI=x86_64 ./build.sh    # x86_64 Android (emulator / Chromebook)
#   QEMU_TARGETS=aarch64-softmmu ./build.sh
#
# See scripts/env.sh for every tunable.
set -euo pipefail
cd "$(dirname "$0")"

./scripts/fetch.sh
./scripts/build-deps.sh
./scripts/build-qemu.sh
./scripts/package.sh
