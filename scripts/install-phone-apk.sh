#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_APK="${REPO_ROOT}/android/RlcdPhone/app/build/outputs/apk/debug/app-debug.apk"

# Optional first argument: ADB serial or host:port. Without it, adb uses the only attached device.
TARGET="${1:-${RLCD_PHONE_TARGET:-}}"
APK_PATH="${2:-${DEFAULT_APK}}"

if ! command -v adb >/dev/null 2>&1; then
  echo "Error: 'adb' not found in PATH. Install via: brew install --cask android-platform-tools" >&2
  exit 1
fi

if [[ ! -f "${APK_PATH}" ]]; then
  echo "Error: APK not found at ${APK_PATH}" >&2
  exit 2
fi

ADB=(adb)
if [[ -n "${TARGET}" ]]; then
  if [[ "${TARGET}" == *:* ]]; then
    echo "Connecting to phone at ${TARGET}..."
    CONNECT_OUT="$(adb connect "${TARGET}" 2>&1)"
    echo "${CONNECT_OUT}"
    if [[ "${CONNECT_OUT}" != *"connected to ${TARGET}"* ]]; then
      echo "Error: Failed to connect to ${TARGET}. Verify Wireless Debugging is enabled." >&2
      exit 3
    fi
  fi
  ADB=(adb -s "${TARGET}")
fi

echo "Installing ${APK_PATH}..."
"${ADB[@]}" install -r "${APK_PATH}"

echo "Launching RLCD Phone..."
"${ADB[@]}" shell am start -n com.rlcdpanel.phone/.MainActivity
echo "Done."
