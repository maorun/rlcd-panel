#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_HOST="192.168.2.91"
DEFAULT_PORT="46089"
DEFAULT_APK="${REPO_ROOT}/wearos/RlcdWatch/app/build/outputs/apk/debug/app-debug.apk"

usage() {
  cat <<EOF
Usage: $(basename "$0") [-p PORT] [--host HOST] [TARGET] [APK]
       $(basename "$0") --self-test
       $(basename "$0") -h | --help

Installs the RLCD Watch debug APK over ADB Wi-Fi and starts the app.

TARGET is HOST:PORT, HOST or PORT. A missing part takes the default.
APK defaults to wearos/RlcdWatch/app/build/outputs/apk/debug/app-debug.apk.

Options:
  -p, --port PORT   ADB Wi-Fi port of the watch (default ${DEFAULT_PORT}, env RLCD_WATCH_PORT)
      --host HOST   IP address or host name of the watch (default ${DEFAULT_HOST}, env RLCD_WATCH_HOST)
      --self-test   Check the target resolution without adb and exit
  -h, --help        Show this help and exit

  RLCD_WATCH_TARGET sets HOST:PORT when TARGET is missing.
  -p and --host override the matching part of TARGET.
  A single argument that ends in .apk is the APK path.
  Before adb connect, a TCP check stops after RLCD_WATCH_PROBE_TIMEOUT seconds
  (default 3, 0 is off, at most 60) and names a VPN route to the watch.

Examples:
  $(basename "$0") 41234
  $(basename "$0") -p 41234 path/to/app.apk
  $(basename "$0") 192.168.2.50:41234

Exit codes: 0 ok, 1 no adb, 2 no APK, 3 watch not reachable or connect failed, 4 bad argument.
A failed adb install or app start returns the exit code of adb.
EOF
}

is_port() {
  [[ "$1" =~ ^[0-9]+$ ]] && (( 10#$1 >= 1 && 10#$1 <= 65535 ))
}

# resolve_target TARGET HOST_OPT PORT_OPT -> prints HOST:PORT, returns 1 for a bad port.
resolve_target() {
  local target="$1" host_opt="$2" port_opt="$3"
  local host="${RLCD_WATCH_HOST:-${DEFAULT_HOST}}"
  local port="${RLCD_WATCH_PORT:-${DEFAULT_PORT}}"

  if [[ -z "${target}" && -n "${RLCD_WATCH_TARGET:-}" ]]; then
    target="${RLCD_WATCH_TARGET}"
  fi
  if [[ -n "${target}" ]]; then
    if [[ "${target}" == *:* ]]; then
      host="${target%:*}"
      port="${target##*:}"
    elif [[ "${target}" =~ ^[0-9]+$ ]]; then
      port="${target}"
    else
      host="${target}"
    fi
  fi
  [[ -n "${host_opt}" ]] && host="${host_opt}"
  [[ -n "${port_opt}" ]] && port="${port_opt}"

  if [[ -z "${host}" ]] || ! is_port "${port}"; then
    return 1
  fi
  echo "${host}:$((10#${port}))"
}

# probe_tcp HOST PORT TIMEOUT_S -> returns 0 when HOST:PORT accepts a TCP connection within the timeout.
probe_tcp() {
  local host="$1" port="$2" timeout_s="$3" pid ticks=0
  ( exec 3<>"/dev/tcp/${host}/${port}" ) >/dev/null 2>&1 &
  pid=$!
  while kill -0 "${pid}" 2>/dev/null; do
    if (( ticks >= timeout_s * 10 )); then
      kill "${pid}" 2>/dev/null || true
      wait "${pid}" 2>/dev/null || true
      return 1
    fi
    sleep 0.1
    ticks=$((ticks + 1))
  done
  wait "${pid}"
}

# route_interface HOST -> prints the route interface of HOST on macOS. It prints nothing and returns 0
# when `route` is absent or does not support `-n get` (for example on Linux).
route_interface() {
  command -v route >/dev/null 2>&1 || return 0
  route -n get "$1" 2>/dev/null | awk '/interface:/ { print $2 }' || true
}

# route_hint HOST IFACE -> prints one hint line about the route interface, or nothing for an empty IFACE.
route_hint() {
  local host="$1" iface="$2"
  case "${iface}" in
    "") ;;
    utun*|ppp*|ipsec*|tun*|wg*)
      echo "  The route to ${host} uses ${iface} (VPN). Disconnect the VPN or allow local network access." ;;
    *) echo "  The route to ${host} uses ${iface}." ;;
  esac
}

self_test_probe() {
  local failures=0 port_file listener start hint
  hint="$(route_hint 10.0.0.5 utun4)"
  if [[ "${hint}" != "  The route to 10.0.0.5 uses utun4 (VPN). Disconnect the VPN or allow local network access." ]]; then
    echo "FAIL route_hint utun4 gave '${hint}'"; failures=$((failures + 1))
  fi
  hint="$(route_hint 10.0.0.5 en0)"
  if [[ "${hint}" != "  The route to 10.0.0.5 uses en0." ]]; then
    echo "FAIL route_hint en0 gave '${hint}'"; failures=$((failures + 1))
  fi
  hint="$(route_hint 10.0.0.5 "")"
  if [[ -n "${hint}" ]]; then
    echo "FAIL route_hint without interface gave '${hint}'"; failures=$((failures + 1))
  fi
  if probe_tcp 127.0.0.1 1 2; then
    echo "FAIL probe_tcp accepted the closed port 127.0.0.1:1"; failures=$((failures + 1))
  fi
  # 192.0.2.1 is TEST-NET-1: it drops or rejects the connection, so the probe must end within the timeout.
  start=${SECONDS}
  if probe_tcp 192.0.2.1 9 1; then
    echo "FAIL probe_tcp accepted 192.0.2.1:9"; failures=$((failures + 1))
  fi
  if (( SECONDS - start > 3 )); then
    echo "FAIL probe_tcp ignored the timeout ($((SECONDS - start)) s)"; failures=$((failures + 1))
  fi
  if command -v python3 >/dev/null 2>&1; then
    port_file="$(mktemp)"
    python3 -c 'import socket,sys
s=socket.socket(); s.bind(("127.0.0.1",0)); s.listen(1)
open(sys.argv[1],"w").write(str(s.getsockname()[1])); s.settimeout(10)
try: s.accept()
except Exception: pass' "${port_file}" &
    listener=$!
    while [[ ! -s "${port_file}" ]] && kill -0 "${listener}" 2>/dev/null; do sleep 0.1; done
    if ! probe_tcp 127.0.0.1 "$(cat "${port_file}")" 3; then
      echo "FAIL probe_tcp rejected an open port"; failures=$((failures + 1))
    fi
    kill "${listener}" 2>/dev/null || true
    wait "${listener}" 2>/dev/null || true
    rm -f "${port_file}"
  else
    echo "SKIP open-port probe: python3 not found"
  fi
  return ${failures}
}

self_test() {
  local failures=0
  self_test_probe || failures=$((failures + $?))
  check() {
    local expected="$1"; shift
    local actual status=0
    actual="$(RLCD_WATCH_TARGET="" RLCD_WATCH_HOST="" RLCD_WATCH_PORT="" resolve_target "$@")" || status=$?
    if [[ "${expected}" == "ERR" ]]; then
      if [[ ${status} -eq 0 ]]; then
        echo "FAIL resolve_target $* gave ${actual}, expected error"; failures=$((failures + 1))
      fi
    elif [[ ${status} -ne 0 || "${actual}" != "${expected}" ]]; then
      echo "FAIL resolve_target $* gave '${actual}', expected ${expected}"; failures=$((failures + 1))
    fi
  }
  check "${DEFAULT_HOST}:${DEFAULT_PORT}" "" "" ""
  check "${DEFAULT_HOST}:41234" "41234" "" ""
  check "${DEFAULT_HOST}:41234" "" "" "41234"
  check "10.0.0.5:${DEFAULT_PORT}" "10.0.0.5" "" ""
  check "10.0.0.5:5555" "10.0.0.5:5555" "" ""
  check "10.0.0.5:41234" "10.0.0.5:5555" "" "41234"
  check "10.0.0.7:5555" "10.0.0.5:5555" "10.0.0.7" ""
  check "${DEFAULT_HOST}:80" "" "" "080"
  check ERR "" "" "0"
  check ERR "" "" "65536"
  check ERR "" "" "abc"
  check ERR "10.0.0.5:" "" ""
  check ERR ":5555" "" ""

  local env_out
  env_out="$(RLCD_WATCH_TARGET="" RLCD_WATCH_HOST="" RLCD_WATCH_PORT="41000" resolve_target "" "" "")"
  if [[ "${env_out}" != "${DEFAULT_HOST}:41000" ]]; then
    echo "FAIL RLCD_WATCH_PORT gave ${env_out}"; failures=$((failures + 1))
  fi

  if [[ ${failures} -ne 0 ]]; then
    echo "Self-test failed: ${failures} case(s)."
    return 1
  fi
  echo "Self-test passed."
}

TARGET_ARG=""
APK_ARG=""
HOST_OPT=""
PORT_OPT=""
POSITIONAL=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --self-test) self_test; exit $? ;;
    -h|--help) usage; exit 0 ;;
    -p|--port)
      [[ $# -ge 2 ]] || { echo "Error: $1 needs a value." >&2; usage >&2; exit 4; }
      PORT_OPT="$2"; shift 2 ;;
    --port=*) PORT_OPT="${1#--port=}"; shift ;;
    --host)
      [[ $# -ge 2 ]] || { echo "Error: $1 needs a value." >&2; usage >&2; exit 4; }
      HOST_OPT="$2"; shift 2 ;;
    --host=*) HOST_OPT="${1#--host=}"; shift ;;
    --) shift; POSITIONAL+=("$@"); break ;;
    -*) echo "Error: unknown option $1" >&2; usage >&2; exit 4 ;;
    *) POSITIONAL+=("$1"); shift ;;
  esac
done
if [[ ${#POSITIONAL[@]} -gt 2 ]]; then
  echo "Error: too many arguments." >&2; usage >&2; exit 4
fi
TARGET_ARG="${POSITIONAL[0]:-}"
APK_ARG="${POSITIONAL[1]:-}"
# A single argument that ends in .apk is the APK path, for example "-p 41234 app.apk".
if [[ ${#POSITIONAL[@]} -eq 1 && "${TARGET_ARG}" == *.apk ]]; then
  APK_ARG="${TARGET_ARG}"
  TARGET_ARG=""
fi

if ! TARGET="$(resolve_target "${TARGET_ARG}" "${HOST_OPT}" "${PORT_OPT}")"; then
  echo "Error: invalid target. Use HOST:PORT, HOST or PORT; the port must be 1 to 65535." >&2
  exit 4
fi
APK_PATH="${APK_ARG:-${DEFAULT_APK}}"
PROBE_TIMEOUT="${RLCD_WATCH_PROBE_TIMEOUT:-3}"
if ! [[ "${PROBE_TIMEOUT}" =~ ^[0-9]+$ ]] || (( 10#${PROBE_TIMEOUT} > 60 )); then
  echo "Error: RLCD_WATCH_PROBE_TIMEOUT must be 0 to 60 seconds." >&2
  exit 4
fi
PROBE_TIMEOUT=$((10#${PROBE_TIMEOUT}))

if ! command -v adb >/dev/null 2>&1; then
  echo "Error: 'adb' not found in PATH. Install via: brew install --cask android-platform-tools" >&2
  exit 1
fi

if [[ ! -f "${APK_PATH}" ]]; then
  echo "Error: APK not found at ${APK_PATH}" >&2
  exit 2
fi

TARGET_HOST="${TARGET%:*}"
TARGET_PORT="${TARGET##*:}"
if (( PROBE_TIMEOUT > 0 )); then
  echo "Checking ${TARGET} (timeout ${PROBE_TIMEOUT} s)..."
  if ! probe_tcp "${TARGET_HOST}" "${TARGET_PORT}" "${PROBE_TIMEOUT}"; then
    echo "Error: ${TARGET} does not accept a TCP connection." >&2
    echo "  Check on the watch: Wi-Fi is connected and Wireless debugging is on." >&2
    echo "  Read IP and port again under Developer options > Wireless debugging; both change often." >&2
    route_hint "${TARGET_HOST}" "$(route_interface "${TARGET_HOST}")" >&2
    exit 3
  fi
fi

echo "Connecting to Galaxy Watch at ${TARGET}..."
CONNECT_OUT="$(adb connect "${TARGET}" 2>&1)"
echo "${CONNECT_OUT}"
if [[ "${CONNECT_OUT}" != *"connected to ${TARGET}"* ]]; then
  echo "Error: Failed to connect to ${TARGET}. Verify Wireless Debugging is enabled on the watch." >&2
  exit 3
fi

echo "Installing ${APK_PATH} onto ${TARGET}..."
adb -s "${TARGET}" install -r "${APK_PATH}"

echo "Launching RLCD Watch on ${TARGET}..."
adb -s "${TARGET}" shell am start -n com.rlcdpanel.watch/.MainActivity
echo "Done."
