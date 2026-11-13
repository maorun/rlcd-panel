#!/usr/bin/env bash
# Checks a config.ini file with the firmware parser. Exit codes: 0 ok, 1 rejected lines, 2 unusable file, 3 build failure.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <config.ini>" >&2
  exit 2
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_TMP="$(mktemp -d -t rlcd_check_config.XXXXXX)"
trap 'rm -rf "${BUILD_TMP}"' EXIT

# Exit code 3 marks a build failure, so it differs from the tool codes 0, 1 and 2.
if ! c++ -std=c++17 -Wall -Wextra -Werror "${REPO_ROOT}/tools/check_config.cpp" -o "${BUILD_TMP}/check_config"; then
  echo "ERROR cannot build tools/check_config.cpp" >&2
  exit 3
fi

# Do not stop on exit codes 1 and 2: pass them to the caller.
status=0
"${BUILD_TMP}/check_config" "$1" || status=$?
exit "${status}"
