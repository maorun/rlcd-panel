#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCS_DIR="${REPO_ROOT}/docs"
mkdir -p "${DOCS_DIR}"

BUILD_TMP="$(mktemp -d -t rlcd_preview_build.XXXXXX)"
trap 'rm -rf "${BUILD_TMP}"' EXIT

EXE="${BUILD_TMP}/render_display_preview"
BMP_OUT="${DOCS_DIR}/display_preview.bmp"
PNG_OUT="${DOCS_DIR}/display_preview.png"

c++ -std=c++17 -Wall -Wextra -Werror \
  "${REPO_ROOT}/tools/render_display_preview.cpp" \
  -o "${EXE}"

"${EXE}" "${BMP_OUT}"

if command -v sips >/dev/null 2>&1; then
  sips -s format png "${BMP_OUT}" --out "${PNG_OUT}" >/dev/null 2>&1
  echo "Display preview generated: ${PNG_OUT}"
else
  echo "Display preview generated: ${BMP_OUT}"
fi
