#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCS_DIR="${REPO_ROOT}/docs"
mkdir -p "${DOCS_DIR}"

BUILD_TMP="$(mktemp -d -t rlcd_preview_build.XXXXXX)"
trap 'rm -rf "${BUILD_TMP}"' EXIT

EXE="${BUILD_TMP}/render_display_preview"

c++ -std=c++17 -Wall -Wextra -Werror \
  "${REPO_ROOT}/tools/render_display_preview.cpp" \
  -o "${EXE}"

# The scene list (file names, modes, low battery flag) lives in tools/preview_scenes.h.
"${EXE}" --all "${BUILD_TMP}" >/dev/null

convert_preview() {
  local name="$1"
  local bmp="${BUILD_TMP}/${name}.bmp"
  if command -v sips >/dev/null 2>&1; then
    sips -s format png "${bmp}" --out "${DOCS_DIR}/${name}.png" >/dev/null 2>&1
    echo "Display preview generated: ${DOCS_DIR}/${name}.png"
  else
    cp "${bmp}" "${DOCS_DIR}/${name}.bmp"
    echo "Display preview generated: ${DOCS_DIR}/${name}.bmp"
  fi
}

for bmp in "${BUILD_TMP}"/display_preview_*.bmp; do
  name="$(basename "${bmp}" .bmp)"
  convert_preview "${name}"
  # Legacy name: docs/display_preview.png stays the dashboard.
  if [[ "${name}" == "display_preview_dashboard" ]]; then
    cp "${BUILD_TMP}/${name}.bmp" "${BUILD_TMP}/display_preview.bmp"
    convert_preview display_preview
  fi
done
