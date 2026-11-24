#!/usr/bin/env bash
# Checks that the shared protocol sources of the Wear OS app and the Android phone app stay equal.
# The package line is removed before the comparison. Exit codes: 0 equal, 1 differences, 2 usage error or missing file.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WATCH_DIR="wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol"
PHONE_DIR="android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol"
# The decoder lives in the finder package of the phone app and in the protocol package of the watch app.
PHONE_ADV="android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/finder/SensorAdvertisement.kt"
WATCH_ADV="${WATCH_DIR}/SensorAdvertisement.kt"
FILES=(AckCommand.kt AlertTransitions.kt CalibrationBatch.kt CalibrationReply.kt Checksums.kt ChimeCommand.kt ClimateMath.kt ConsoleWire.kt EventsReply.kt EventFilter.kt EventText.kt HistoryReply.kt PanelClock.kt PanelConfig.kt RejectsReply.kt ReplyAssembler.kt SaltCommand.kt SaltText.kt SelfTestReply.kt SensorHealth.kt SignalMeter.kt SilenceWatch.kt SpreadReply.kt StaleWatch.kt StatusReply.kt TelemetryFrame.kt TodayReply.kt VersionReply.kt WallText.kt WallWatch.kt WeekReply.kt WindowWatch.kt)

# compare_dirs <watch dir> <phone dir>: prints each differing file, returns 1 for a difference, 2 for a missing file.
compare_dirs() {
  local watch_dir="$1" phone_dir="$2" name status=0
  for name in "${FILES[@]}"; do
    if [[ ! -f "${watch_dir}/${name}" || ! -f "${phone_dir}/${name}" ]]; then
      echo "MISSING ${name}" >&2
      return 2
    fi
    if ! diff -q <(sed '/^package /d' "${watch_dir}/${name}") <(sed '/^package /d' "${phone_dir}/${name}") >/dev/null; then
      echo "DIFFERS ${name}"
      status=1
    fi
  done
  return "${status}"
}

# compare_files <watch file> <phone file>: same result codes as compare_dirs for one pair.
compare_files() {
  local watch_file="$1" phone_file="$2"
  if [[ ! -f "${watch_file}" || ! -f "${phone_file}" ]]; then
    echo "MISSING $(basename "${watch_file}")" >&2
    return 2
  fi
  if ! diff -q <(sed '/^package /d' "${watch_file}") <(sed '/^package /d' "${phone_file}") >/dev/null; then
    echo "DIFFERS $(basename "${watch_file}")"
    return 1
  fi
  return 0
}

self_test() {
  local tmp status
  tmp="$(mktemp -d -t rlcd_protocol_sync.XXXXXX)"
  trap 'rm -rf "${tmp}"' RETURN
  mkdir -p "${tmp}/good_watch" "${tmp}/good_phone" "${tmp}/bad_watch" "${tmp}/bad_phone"
  local name
  for name in "${FILES[@]}"; do
    printf 'package a.watch\n\nobject X { val n = "%s" }\n' "${name}" > "${tmp}/good_watch/${name}"
    printf 'package b.phone\n\nobject X { val n = "%s" }\n' "${name}" > "${tmp}/good_phone/${name}"
    cp "${tmp}/good_watch/${name}" "${tmp}/bad_watch/${name}"
    cp "${tmp}/good_phone/${name}" "${tmp}/bad_phone/${name}"
  done
  echo "extra" >> "${tmp}/bad_phone/ClimateMath.kt"

  status=0
  compare_dirs "${tmp}/good_watch" "${tmp}/good_phone" >/dev/null || status=$?
  if [[ "${status}" -ne 0 ]]; then
    echo "SELF-TEST FAILED: good pair gave exit ${status}" >&2
    return 1
  fi
  status=0
  compare_dirs "${tmp}/bad_watch" "${tmp}/bad_phone" >/dev/null || status=$?
  if [[ "${status}" -ne 1 ]]; then
    echo "SELF-TEST FAILED: bad pair gave exit ${status}" >&2
    return 1
  fi
  rm "${tmp}/good_phone/Checksums.kt"
  status=0
  compare_dirs "${tmp}/good_watch" "${tmp}/good_phone" >/dev/null 2>&1 || status=$?
  if [[ "${status}" -ne 2 ]]; then
    echo "SELF-TEST FAILED: missing file gave exit ${status}" >&2
    return 1
  fi
  printf 'package a\n\nobject Adv\n' > "${tmp}/adv_watch.kt"
  printf 'package b\n\nobject Adv\n' > "${tmp}/adv_phone.kt"
  compare_files "${tmp}/adv_watch.kt" "${tmp}/adv_phone.kt" >/dev/null || { echo "SELF-TEST FAILED: good file pair" >&2; return 1; }
  echo "extra" >> "${tmp}/adv_phone.kt"
  status=0
  compare_files "${tmp}/adv_watch.kt" "${tmp}/adv_phone.kt" >/dev/null || status=$?
  if [[ "${status}" -ne 1 ]]; then echo "SELF-TEST FAILED: bad file pair gave exit ${status}" >&2; return 1; fi
  status=0
  compare_files "${tmp}/adv_watch.kt" "${tmp}/none.kt" >/dev/null 2>&1 || status=$?
  if [[ "${status}" -ne 2 ]]; then echo "SELF-TEST FAILED: missing file pair gave exit ${status}" >&2; return 1; fi
  echo "SELF-TEST OK: good pair exit 0, bad pair exit 1, missing file exit 2"
}

case "${1:-}" in
  "")
    status=0
    compare_dirs "${REPO_ROOT}/${WATCH_DIR}" "${REPO_ROOT}/${PHONE_DIR}" || status=$?
    adv=0
    compare_files "${REPO_ROOT}/${WATCH_ADV}" "${REPO_ROOT}/${PHONE_ADV}" || adv=$?
    if [[ "${status}" -eq 2 || "${adv}" -eq 2 ]]; then exit 2; fi
    exit $(( status | adv ))
    ;;
  --self-test) self_test ;;
  *) echo "usage: $0 [--self-test]" >&2; exit 2 ;;
esac
