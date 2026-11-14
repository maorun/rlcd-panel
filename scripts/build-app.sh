#!/usr/bin/env bash
# Builds and signs the standalone macOS companion bundle "RLCD Companion.app" into ~/Applications (or --dest <dir>).
# Pass --rebuild to quit a running instance, replace the bundle, and reopen the app.
set -euo pipefail

REBUILD=0
DEST_DIR="${RLCD_APP_DIR:-${HOME}/Applications}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --rebuild)
      REBUILD=1
      shift
      ;;
    --dest)
      if [[ $# -lt 2 || -z "${2:-}" ]]; then
        echo "usage: $0 [--rebuild] [--dest <dir>]" >&2
        exit 2
      fi
      DEST_DIR="$2"
      shift 2
      ;;
    *)
      echo "usage: $0 [--rebuild] [--dest <dir>]" >&2
      exit 2
      ;;
  esac
done

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG_DIR="${REPO_ROOT}/macos/RlcdCompanion"
PLIST_SRC="${PKG_DIR}/Info.plist"
APP_NAME="RLCD Companion.app"
APP_EXEC="RlcdCompanion"
FINAL_APP="${DEST_DIR}/${APP_NAME}"

if pgrep -x "${APP_EXEC}" >/dev/null 2>&1; then
  if [[ "${REBUILD}" -eq 0 ]]; then
    echo "ERROR ${APP_NAME} is running. Quit the app first or pass --rebuild." >&2
    exit 1
  fi
  osascript -e 'tell application "RLCD Companion" to quit' >/dev/null 2>&1 || true
  for _ in {1..30}; do
    if ! pgrep -x "${APP_EXEC}" >/dev/null 2>&1; then
      break
    fi
    sleep 0.1
  done
  if pgrep -x "${APP_EXEC}" >/dev/null 2>&1; then
    pkill -x "${APP_EXEC}" >/dev/null 2>&1 || true
    for _ in {1..20}; do
      if ! pgrep -x "${APP_EXEC}" >/dev/null 2>&1; then
        break
      fi
      sleep 0.1
    done
  fi
fi

(
  cd "${PKG_DIR}"
  swift build -c release --disable-sandbox
)

BIN_DIR="$(cd "${PKG_DIR}" && swift build -c release --disable-sandbox --show-bin-path)"
BIN_PATH="${BIN_DIR}/${APP_EXEC}"
if [[ ! -x "${BIN_PATH}" ]]; then
  echo "ERROR release binary not found at ${BIN_PATH}" >&2
  exit 1
fi

STAGE_DIR="$(mktemp -d -t rlcd_companion_app.XXXXXX)"
trap 'rm -rf "${STAGE_DIR}"' EXIT
STAGE_APP="${STAGE_DIR}/${APP_NAME}"

mkdir -p "${STAGE_APP}/Contents/MacOS" "${STAGE_APP}/Contents/Resources"
cp "${BIN_PATH}" "${STAGE_APP}/Contents/MacOS/${APP_EXEC}"
chmod 0755 "${STAGE_APP}/Contents/MacOS/${APP_EXEC}"
cp "${PLIST_SRC}" "${STAGE_APP}/Contents/Info.plist"
chmod 0644 "${STAGE_APP}/Contents/Info.plist"
printf 'APPL????' > "${STAGE_APP}/Contents/PkgInfo"

plutil -lint "${STAGE_APP}/Contents/Info.plist" >/dev/null
codesign --force --deep --sign - "${STAGE_APP}"
codesign --verify --deep --strict "${STAGE_APP}"

mkdir -p "${DEST_DIR}"
rm -rf "${FINAL_APP}"
mv "${STAGE_APP}" "${FINAL_APP}"

echo "Built and signed: ${FINAL_APP}"

if [[ "${REBUILD}" -eq 1 ]]; then
  open "${FINAL_APP}"
fi
