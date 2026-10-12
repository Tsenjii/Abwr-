#!/usr/bin/env bash
set -euo pipefail

# emulator-runner executes each script line separately. Keep conditional
# installation here, and refuse every physical device before installing.
test "${ABWR_CI_EMULATOR:-}" = 1
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
case "${ABWR_CI_PACKAGE:-}" in
  dev.local.aibrowser.dev)
    adb install emulator-apk/app-debug.apk
    ;;
  dev.local.aibrowser)
    test "${GITHUB_ACTIONS:-}" = true
    adb root
    adb wait-for-device
    test "$(adb shell id -u | tr -d '\r')" = 0
    adb install emulator-apk/app-release.apk
    ;;
  *)
    echo 'Unexpected CI package' >&2
    exit 1
    ;;
esac
