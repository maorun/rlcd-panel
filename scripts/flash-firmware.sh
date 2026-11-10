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

echo "=== 1. Generating Display Preview Image ==="
"${REPO_ROOT}/scripts/generate-display-preview.sh"

echo "=== 2. Compiling Firmware ==="
arduino-cli compile "${REPO_ROOT}/rlcd_panel"

echo "=== 3. Uploading Firmware to ${PORT} ==="
arduino-cli upload -p "${PORT}" "${REPO_ROOT}/rlcd_panel"

echo "Flash complete."
