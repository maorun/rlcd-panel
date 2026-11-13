#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

PORT="${1:-}"
if [[ -z "${PORT}" ]]; then
  for candidate in /dev/cu.usbmodem* /dev/cu.usbserial* /dev/cu.wchusbserial*; do
    if [[ -e "${candidate}" ]]; then
      PORT="${candidate}"
      break
    fi
  done
fi

if [[ -z "${PORT}" ]]; then
  echo "Error: No ESP32-S3 USB serial port found. Connect the board or pass the port as argument 1." >&2
  exit 1
fi

# Read the build identity before the preview script changes tracked files. Untracked files do not count.
GIT_HASH="$(git -C "${REPO_ROOT}" rev-parse --short HEAD 2>/dev/null || echo unknown)"
if [[ "${GIT_HASH}" != "unknown" && -n "$(git -C "${REPO_ROOT}" status --porcelain --untracked-files=no 2>/dev/null)" ]]; then
  GIT_HASH="${GIT_HASH}-dirty"
fi

echo "=== 1. Generating Display Preview Image ==="
"${REPO_ROOT}/scripts/generate-display-preview.sh"

echo "=== 2. Compiling Firmware (git ${GIT_HASH}) ==="
# arduino-cli removes one quote level itself, so the hash needs a plain double quote pair.
arduino-cli compile --build-property "compiler.cpp.extra_flags=-DRLCD_GIT_HASH=\"${GIT_HASH}\"" "${REPO_ROOT}/rlcd_panel"

echo "=== 3. Uploading Firmware to ${PORT} ==="
arduino-cli upload -p "${PORT}" "${REPO_ROOT}/rlcd_panel"

echo "Flash complete."
