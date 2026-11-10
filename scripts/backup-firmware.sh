#!/usr/bin/env bash
set -euo pipefail
umask 077

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="${REPO_ROOT}/firmware_backups"
ESPTOOL_BIN="/Users/mdriemel/Library/Arduino15/packages/esp32/tools/esptool_py/5.3.1/esptool"

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

if [[ ! -x "${ESPTOOL_BIN}" ]]; then
  echo "Error: esptool binary not found at ${ESPTOOL_BIN}" >&2
  exit 1
fi

mkdir -p "${BACKUP_DIR}"
chmod 0700 "${BACKUP_DIR}"

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
FULL_OUT="${BACKUP_DIR}/esp32s3-rlcd-full-${TIMESTAMP}.bin"
NVS_OUT="${BACKUP_DIR}/esp32s3-rlcd-nvs-${TIMESTAMP}.bin"

echo "Reading NVS partition (0x9000, 20 KB) from ${PORT} -> ${NVS_OUT}"
"${ESPTOOL_BIN}" --chip esp32s3 --port "${PORT}" --baud 921600 read-flash 0x9000 0x5000 "${NVS_OUT}"

echo "Reading full flash (0x0, 16 MB) from ${PORT} -> ${FULL_OUT}"
"${ESPTOOL_BIN}" --chip esp32s3 --port "${PORT}" --baud 921600 read-flash 0x0 0x1000000 "${FULL_OUT}"

echo "Backup complete:"
ls -lh "${NVS_OUT}" "${FULL_OUT}"
