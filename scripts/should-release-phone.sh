#!/usr/bin/env bash
# Evaluates whether changes in a commit or file list warrant releasing the Android phone app.
# Exit 0 if android/ or config files changed; exit 1 otherwise.
set -euo pipefail

matches_trigger() {
  local file="$1"
  if [[ "${file}" =~ ^android/ || "${file}" =~ ^docs/config(\.example)?\.ini$ || "${file}" == "rlcd_panel/device_config.h" ]]; then
    return 0
  fi
  return 1
}

if [[ "${1:-}" == "--files" ]]; then
  shift
  for f in "$@"; do
    if matches_trigger "${f}"; then
      exit 0
    fi
  done
  exit 1
fi

BASE="${1:-HEAD~1}"
FILES=""

if [[ -z "${BASE}" || "${BASE}" =~ ^0+$ ]]; then
  FILES=$(git diff-tree --no-commit-id --name-only -r HEAD)
elif git rev-parse --verify "${BASE}^{commit}" >/dev/null 2>&1; then
  FILES=$(git diff --name-only "${BASE}" HEAD)
elif git rev-parse --verify HEAD~1 >/dev/null 2>&1; then
  FILES=$(git diff --name-only HEAD~1 HEAD)
else
  FILES=$(git diff-tree --no-commit-id --name-only -r HEAD)
fi

while IFS= read -r f; do
  [[ -z "${f}" ]] && continue
  if matches_trigger "${f}"; then
    exit 0
  fi
done <<< "${FILES}"

exit 1
