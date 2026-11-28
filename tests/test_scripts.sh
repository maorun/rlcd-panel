#!/usr/bin/env bash
# Tests of scripts/check-config.sh: the reason of each rejected line and the duplicate MAC check.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d -t rlcd_test_scripts.XXXXXX)"
trap 'rm -rf "${TMP}"' EXIT
CHECK="${REPO_ROOT}/scripts/check-config.sh"
fail=0

expect() { # <name> <condition result>
  if [[ "$2" == "0" ]]; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi
}

run_check() { # <file> -> sets out and status
  status=0
  out="$("${CHECK}" "$1" 2>&1)" || status=$?
}

# A valid config gives exit 0 and 0 rejected lines.
printf 'chime_volume_pct=40\nslot1_mac=AA:BB:CC:DD:EE:01\nslot3_mac=\n' > "${TMP}/valid.ini"
run_check "${TMP}/valid.ini"
expect "valid config exit 0" "$([[ ${status} -eq 0 ]]; echo $?)"
expect "valid config 0 rejected" "$(grep -q '^REJECTED count=0$' <<<"${out}"; echo $?)"

# A duplicate MAC (slot 1 and slot 3, other case) prints the key and DUPLICATE_MAC.
printf 'slot1_mac=AA:BB:CC:DD:EE:01\nslot3_mac=aa:bb:cc:dd:ee:01\n' > "${TMP}/dup.ini"
run_check "${TMP}/dup.ini"
expect "duplicate MAC exit 1" "$([[ ${status} -eq 1 ]]; echo $?)"
expect "duplicate MAC line" "$(grep -q '^REJECTED line=2 key=slot3_mac reason=DUPLICATE_MAC$' <<<"${out}"; echo $?)"

# Two empty MACs do not collide.
printf 'slot1_mac=\nslot3_mac=\n' > "${TMP}/empty.ini"
run_check "${TMP}/empty.ini"
expect "empty MACs exit 0" "$([[ ${status} -eq 0 ]]; echo $?)"
expect "empty MACs 0 rejected" "$(grep -q '^REJECTED count=0$' <<<"${out}"; echo $?)"

# A bad value prints BAD_VALUE.
printf 'chime_volume_pct=abc\n' > "${TMP}/bad.ini"
run_check "${TMP}/bad.ini"
expect "bad value exit 1" "$([[ ${status} -eq 1 ]]; echo $?)"
expect "bad value reason" "$(grep -q '^REJECTED line=1 key=chime_volume_pct reason=BAD_VALUE$' <<<"${out}"; echo $?)"

# The sample file stays valid.
run_check "${REPO_ROOT}/tools/sample_config.ini"
expect "sample config exit 0" "$([[ ${status} -eq 0 ]]; echo $?)"

# The self-test of install-phone-apk.sh passes, and a broken target resolution fails it.
PHONE="${REPO_ROOT}/scripts/install-phone-apk.sh"
status=0
"${PHONE}" --self-test >/dev/null 2>&1 || status=$?
expect "install-phone-apk self-test exit 0" "$([[ ${status} -eq 0 ]]; echo $?)"
mkdir -p "${TMP}/broken/scripts"
sed 's/ADB=("${ADB_BIN}" -s "${TARGET}")/ADB=("${ADB_BIN}")/' "${PHONE}" > "${TMP}/broken/scripts/install-phone-apk.sh"
chmod +x "${TMP}/broken/scripts/install-phone-apk.sh"
status=0
"${TMP}/broken/scripts/install-phone-apk.sh" --self-test >/dev/null 2>&1 || status=$?
expect "broken install-phone-apk resolution exit 1" "$([[ ${status} -eq 1 ]]; echo $?)"
status=0
"${PHONE}" --help >/dev/null 2>&1 || status=$?
expect "install-phone-apk help exit 0" "$([[ ${status} -eq 0 ]]; echo $?)"

exit "${fail}"
