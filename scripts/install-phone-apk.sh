#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SELF="${REPO_ROOT}/scripts/$(basename "${BASH_SOURCE[0]}")"
DEFAULT_APK="${REPO_ROOT}/android/RlcdPhone/app/build/outputs/apk/debug/app-debug.apk"
ADB_BIN="${RLCD_ADB_BIN:-adb}"

usage() {
  cat <<EOF
Usage: $(basename "$0") [TARGET] [APK]
       $(basename "$0") --self-test
       $(basename "$0") -h | --help

Installs the RLCD Phone debug APK with adb and starts the app.

TARGET is an ADB serial or HOST:PORT. Without TARGET (or RLCD_PHONE_TARGET),
adb uses the only attached device. A TARGET with a colon runs adb connect first.
APK defaults to android/RlcdPhone/app/build/outputs/apk/debug/app-debug.apk.

Options:
      --self-test   Check the target resolution with a fake adb and exit
  -h, --help        Show this help and exit

  RLCD_ADB_BIN selects the adb program (default adb).

Examples:
  $(basename "$0")
  $(basename "$0") R58M123ABC
  $(basename "$0") 192.168.2.50:41234 path/to/app.apk

Exit codes: 0 ok, 1 no adb, 2 no APK, 3 connect failed, 4 bad argument.
A failed adb install or app start returns the exit code of adb.
EOF
}

self_test() {
  local failures=0 tmp fake apk log status
  tmp="$(mktemp -d)"
  fake="${tmp}/fake-adb"
  apk="${tmp}/app.apk"
  log="${tmp}/adb.log"
  : > "${apk}"
  # The fake adb logs its arguments. `adb connect` succeeds unless FAKE_ADB_CONNECT_FAIL is set.
  cat > "${fake}" <<'FAKE'
#!/usr/bin/env bash
echo "$*" >> "${FAKE_ADB_LOG}"
if [[ "${1:-}" == "connect" ]]; then
  if [[ -n "${FAKE_ADB_CONNECT_FAIL:-}" ]]; then
    echo "failed to connect to '$2'"
  else
    echo "connected to $2"
  fi
fi
exit 0
FAKE
  chmod +x "${fake}"

  # run_case ARGS... -> sets status; the log holds the adb calls.
  run_case() {
    : > "${log}"
    status=0
    env -u RLCD_PHONE_TARGET RLCD_ADB_BIN="${fake}" FAKE_ADB_LOG="${log}" "${SELF}" "$@" >/dev/null 2>&1 || status=$?
  }
  expect_status() { # <name> <expected>
    if [[ ${status} -ne $2 ]]; then
      echo "FAIL $1: exit ${status}, expected $2"; failures=$((failures + 1))
    fi
  }
  expect_log() { # <name> <expected log text>
    local actual
    actual="$(cat "${log}")"
    if [[ "${actual}" != "$2" ]]; then
      echo "FAIL $1: adb calls were:"$'\n'"${actual}"$'\n'"expected:"$'\n'"$2"; failures=$((failures + 1))
    fi
  }

  run_case "" "${apk}"
  expect_status "no argument" 0
  expect_log "no argument" "install -r ${apk}
shell am start -n com.rlcdpanel.phone/.MainActivity"

  run_case R58M123ABC "${apk}"
  expect_status "serial" 0
  expect_log "serial" "-s R58M123ABC install -r ${apk}
-s R58M123ABC shell am start -n com.rlcdpanel.phone/.MainActivity"

  run_case 10.0.0.5:41234 "${apk}"
  expect_status "host:port" 0
  expect_log "host:port" "connect 10.0.0.5:41234
-s 10.0.0.5:41234 install -r ${apk}
-s 10.0.0.5:41234 shell am start -n com.rlcdpanel.phone/.MainActivity"

  : > "${log}"
  status=0
  env RLCD_PHONE_TARGET=SERIAL9 RLCD_ADB_BIN="${fake}" FAKE_ADB_LOG="${log}" "${SELF}" "" "${apk}" >/dev/null 2>&1 || status=$?
  expect_status "RLCD_PHONE_TARGET" 0
  expect_log "RLCD_PHONE_TARGET" "-s SERIAL9 install -r ${apk}
-s SERIAL9 shell am start -n com.rlcdpanel.phone/.MainActivity"

  : > "${log}"
  status=0
  env -u RLCD_PHONE_TARGET FAKE_ADB_CONNECT_FAIL=1 RLCD_ADB_BIN="${fake}" FAKE_ADB_LOG="${log}" "${SELF}" 10.0.0.5:41234 "${apk}" >/dev/null 2>&1 || status=$?
  expect_status "failed connect" 3
  expect_log "failed connect" "connect 10.0.0.5:41234"

  run_case R58M123ABC "${tmp}/missing.apk"
  expect_status "missing APK" 2
  expect_log "missing APK" ""

  run_case -x
  expect_status "unknown option" 4

  run_case R58M123ABC "${apk}" extra
  expect_status "too many arguments" 4

  status=0
  env -u RLCD_PHONE_TARGET RLCD_ADB_BIN="${tmp}/no-such-adb" "${SELF}" R58M123ABC "${apk}" >/dev/null 2>&1 || status=$?
  expect_status "missing adb" 1

  run_case --help
  expect_status "help" 0
  expect_log "help" ""

  rm -rf "${tmp}"
  if [[ ${failures} -ne 0 ]]; then
    echo "Self-test failed: ${failures} case(s)."
    return 1
  fi
  echo "Self-test passed."
}

POSITIONAL=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --self-test) self_test; exit $? ;;
    -h|--help) usage; exit 0 ;;
    --) shift; POSITIONAL+=("$@"); break ;;
    -*) echo "Error: unknown option $1" >&2; usage >&2; exit 4 ;;
    *) POSITIONAL+=("$1"); shift ;;
  esac
done
if [[ ${#POSITIONAL[@]} -gt 2 ]]; then
  echo "Error: too many arguments." >&2; usage >&2; exit 4
fi

# Optional first argument: ADB serial or host:port. Without it, adb uses the only attached device.
TARGET="${POSITIONAL[0]:-${RLCD_PHONE_TARGET:-}}"
APK_PATH="${POSITIONAL[1]:-${DEFAULT_APK}}"
# An empty first argument keeps the default APK too.
[[ -n "${APK_PATH}" ]] || APK_PATH="${DEFAULT_APK}"

if ! command -v "${ADB_BIN}" >/dev/null 2>&1; then
  echo "Error: '${ADB_BIN}' not found in PATH. Install via: brew install --cask android-platform-tools" >&2
  exit 1
fi

if [[ ! -f "${APK_PATH}" ]]; then
  echo "Error: APK not found at ${APK_PATH}" >&2
  exit 2
fi

ADB=("${ADB_BIN}")
if [[ -n "${TARGET}" ]]; then
  if [[ "${TARGET}" == *:* ]]; then
    echo "Connecting to phone at ${TARGET}..."
    CONNECT_OUT="$("${ADB_BIN}" connect "${TARGET}" 2>&1)"
    echo "${CONNECT_OUT}"
    if [[ "${CONNECT_OUT}" != *"connected to ${TARGET}"* ]]; then
      echo "Error: Failed to connect to ${TARGET}. Verify Wireless Debugging is enabled." >&2
      exit 3
    fi
  fi
  ADB=("${ADB_BIN}" -s "${TARGET}")
fi

echo "Installing ${APK_PATH}..."
"${ADB[@]}" install -r "${APK_PATH}"

echo "Launching RLCD Phone..."
"${ADB[@]}" shell am start -n com.rlcdpanel.phone/.MainActivity
echo "Done."
