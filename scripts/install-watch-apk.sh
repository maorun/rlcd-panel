#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_TARGET="192.168.2.91:46089"
DEFAULT_APK="${REPO_ROOT}/wearos/RlcdWatch/app/build/outputs/apk/debug/app-debug.apk"

TARGET="${1:-${RLCD_WATCH_TARGET:-${DEFAULT_TARGET}}}"
APK_PATH="${2:-${DEFAULT_APK}}"

if ! command -v adb >/dev/null 2>&1; then
  echo "Error: 'adb' not found in PATH. Install via: brew install --cask android-platform-tools" >&2
  exit 1
fi

if [[ ! -f "${APK_PATH}" ]]; then
  echo "Error: APK not found at ${APK_PATH}" >&2
  exit 2
fi

echo "Connecting to Galaxy Watch at ${TARGET}..."
CONNECT_OUT="$(adb connect "${TARGET}" 2>&1)"
echo "${CONNECT_OUT}"
if [[ "${CONNECT_OUT}" != *"connected to ${TARGET}"* ]]; then
  echo "Error: Failed to connect to ${TARGET}. Verify Wireless Debugging is enabled on the watch." >&2
  exit 3
fi

echo "Installing ${APK_PATH} onto ${TARGET}..."
adb -s "${TARGET}" install -r "${APK_PATH}"

echo "Launching RLCD Watch on ${TARGET}..."
adb -s "${TARGET}" shell am start -n com.rlcdpanel.watch/.MainActivity
echo "Done."
