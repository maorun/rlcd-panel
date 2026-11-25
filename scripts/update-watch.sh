#!/usr/bin/env bash
set -euo pipefail

# Downloads the newest Wear OS debug APK from the CI runs on master and installs it on the watch.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_NAME="rlcd-watch-debug-apk"
APK_NAME="app-debug.apk"
RUN_LIMIT=10
GH_BIN="${RLCD_GH_BIN:-gh}"
INSTALL_SCRIPT="${RLCD_INSTALL_SCRIPT:-${REPO_ROOT}/scripts/install-watch-apk.sh}"

usage() {
  cat <<EOF
Usage: $(basename "$0") [PORT | HOST | HOST:PORT] [-p PORT] [--host HOST]
       $(basename "$0") --self-test
       $(basename "$0") -h | --help

Downloads the artifact ${ARTIFACT_NAME} of the newest CI run on master that has it,
then installs it on the watch with scripts/install-watch-apk.sh and starts the app.
The newest ${RUN_LIMIT} completed push runs on master are tried, newest first.
The target arguments go to install-watch-apk.sh unchanged (see its --help).
Set RLCD_REPO=OWNER/REPO to select another GitHub repository.

Examples:
  $(basename "$0")
  $(basename "$0") 41234
  $(basename "$0") -p 41234

Exit codes: 0 ok, 4 bad argument, 5 no gh, 6 no artifact found.
Other values come from install-watch-apk.sh.
EOF
}

# download_latest DIR -> downloads the APK into DIR, prints "RUN_ID SHA", returns 6 without an artifact.
download_latest() {
  local dir="$1" repo runs id sha
  repo="${RLCD_REPO:-}"
  if [[ -z "${repo}" ]]; then
    repo="$(cd "${REPO_ROOT}" && "${GH_BIN}" repo view --json nameWithOwner -q .nameWithOwner)"
  fi
  runs="$("${GH_BIN}" run list -R "${repo}" --workflow CI --branch master --event push \
    --status completed --limit "${RUN_LIMIT}" --json databaseId,headSha \
    -q '.[] | "\(.databaseId) \(.headSha)"')"
  while read -r id sha; do
    [[ -n "${id}" ]] || continue
    if "${GH_BIN}" run download "${id}" -R "${repo}" -n "${ARTIFACT_NAME}" -D "${dir}" >/dev/null 2>&1 \
      && [[ -f "${dir}/${APK_NAME}" ]]; then
      echo "${id} ${sha}"
      return 0
    fi
    rm -rf "${dir:?}"/*
  done <<< "${runs}"
  return 6
}

self_test() {
  local tmp failures=0 status
  SELF_TEST_DIR="$(mktemp -d)"
  trap 'rm -rf "${SELF_TEST_DIR}"' EXIT
  tmp="${SELF_TEST_DIR}"

  cat > "${tmp}/gh" <<'EOF'
#!/usr/bin/env bash
case "$1 $2" in
  "repo view") echo "owner/repo" ;;
  "run list") printf '%s\n' "${FAKE_RUNS}" ;;
  "run download")
    id="$3"; dir=""
    while [[ $# -gt 0 ]]; do [[ "$1" == "-D" ]] && dir="$2"; shift; done
    [[ " ${FAKE_GOOD_RUNS} " == *" ${id} "* ]] || exit 1
    echo apk > "${dir}/app-debug.apk" ;;
  *) exit 9 ;;
esac
EOF
  cat > "${tmp}/install" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$@" > "${FAKE_ARGS_FILE}"
[[ -f "${!#}" ]] || exit 7
EOF
  chmod +x "${tmp}/gh" "${tmp}/install"

  run_case() {
    status=0
    rm -f "${tmp}/args"
    FAKE_RUNS="$1" FAKE_GOOD_RUNS="$2" FAKE_ARGS_FILE="${tmp}/args" RLCD_REPO="" \
      RLCD_GH_BIN="${tmp}/gh" RLCD_INSTALL_SCRIPT="${tmp}/install" \
      "${BASH}" "${BASH_SOURCE[0]}" "${@:3}" > "${tmp}/out" 2>&1 || status=$?
  }
  fail() { echo "FAIL $*"; failures=$((failures + 1)); }

  # The newest run has no artifact, the second run has it.
  run_case $'111 aaaaaaaaaaaa\n222 bbbbbbbbbbbb' "222" -p 41234
  [[ ${status} -eq 0 ]] || fail "fallback run gave exit ${status}: $(cat "${tmp}/out")"
  if [[ "$(sed -n 1,2p "${tmp}/args" 2>/dev/null | tr '\n' ' ')" != "-p 41234 " ]] \
    || [[ "$(sed -n 3p "${tmp}/args" 2>/dev/null)" != */app-debug.apk ]] \
    || [[ "$(wc -l < "${tmp}/args" 2>/dev/null | tr -d ' ')" != "3" ]]; then
    fail "install args: $(tr '\n' ' ' < "${tmp}/args" 2>/dev/null)"
  fi
  grep -qF 'Downloaded run 222 (bbbbbbb).' "${tmp}/out" || fail "run line missing: $(cat "${tmp}/out")"

  # A port alone goes to install-watch-apk.sh as the first argument.
  run_case '333 cccccccccccc' "333" 41234
  [[ ${status} -eq 0 && "$(sed -n 1p "${tmp}/args" 2>/dev/null)" == "41234" ]] \
    || fail "port argument gave exit ${status}: $(tr '\n' ' ' < "${tmp}/args" 2>/dev/null)"

  # No run has the artifact: exit 6 and no install.
  run_case $'111 aaaaaaaaaaaa\n222 bbbbbbbbbbbb' "" 41234
  [[ ${status} -eq 6 && ! -f "${tmp}/args" ]] || fail "missing artifact gave exit ${status}"

  # No completed run at all: exit 6.
  run_case '' "" 
  [[ ${status} -eq 6 && ! -f "${tmp}/args" ]] || fail "empty run list gave exit ${status}"

  if [[ ${failures} -ne 0 ]]; then
    echo "Self-test failed: ${failures} case(s)."
    return 1
  fi
  echo "Self-test passed."
}

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  --self-test) self_test; exit $? ;;
esac

if ! command -v "${GH_BIN}" >/dev/null 2>&1; then
  echo "Error: 'gh' not found in PATH. Install via: brew install gh, then run: gh auth login" >&2
  exit 5
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

echo "Searching the newest CI artifact ${ARTIFACT_NAME} on master..."
status=0
RUN_INFO="$(download_latest "${WORK_DIR}")" || status=$?
if [[ ${status} -ne 0 ]]; then
  echo "Error: no ${ARTIFACT_NAME} artifact in the newest ${RUN_LIMIT} completed CI runs on master." >&2
  exit 6
fi
read -r RUN_ID RUN_SHA <<< "${RUN_INFO}"
echo "Downloaded run ${RUN_ID} (${RUN_SHA:0:7})."

"${BASH}" "${INSTALL_SCRIPT}" "$@" "${WORK_DIR}/${APK_NAME}"
