# Roadmap

This file lists open work for `rlcd-panel`, the standalone Arduino CLI firmware for the **Waveshare ESP32-S3-RLCD-4.2** development board, and its companion apps (`macos/RlcdCompanion` for macOS, `wearos/RlcdWatch` for Wear OS / Galaxy Watch Ultra 2 and `android/RlcdPhone` for Android smartphones).
The system renders climate telemetry from onboard sensors and external SwitchBot thermo-hygrometers onto a 4.2-inch reflective LCD and broadcasts BLE telemetry to the companion apps.
The list uses priorities. Priority 1 is the highest priority.
Do the work in priority order, unless the maintainer gives a different order.

## Rules for this file

- Write in ASD-STE100 Simplified Technical English.
- Include work across all system targets: the panel firmware (`rlcd_panel/`), the macOS companion app (`macos/RlcdCompanion/`), the Wear OS companion app (`wearos/RlcdWatch/`), and the Android phone companion app (`android/RlcdPhone/`). Do not restrict roadmap planning to the panel firmware alone.
- Add one entry for each open item. Do not give an entry a status. Each entry in this file is open.
- Remove an entry in the pull request that completes it. Describe finished work in `FEATURES.md`.
- Each entry names the code location, the goal, and the acceptance test.
- Each entry has a `Rank` line with the model, the total score, the four single scores, and the effort size. See "Rules for ranking".
- Put each entry in the priority that matches its total score. Order the entries inside a priority by total score, highest first. For equal scores, put the smaller effort first. Put a dependency above the entry that needs it.
- Priority 1 holds the most critical user-facing enhancements and core hardware integrations.
- Do not record review findings in this file. Name them in the review report and in the pull request description.

## Priority 1: Sensor Setup and Calibration (Score 34–40)

## Priority 2: Configuration and Control (Score 28–33)

## Priority 3: Display and Logging (Score 22–27)

- [ ] Clock sync line in the panel diagnostics view
  - Rank: [Total: 26, Use: 4, Setup: 6, Harmony: 6, Safety: 10, Effort: S]
  - Location: rlcd_panel/dashboard_view.h, rlcd_panel/ntp_sync.h, rlcd_panel/rlcd_panel.ino, tools/preview_scenes.h, tests/test_firmware_logic.cpp
  - Goal: Add one line below the `UP:` line of the diagnostics view: `NTP: OK <N>H AGO` after a success, `NTP: FAIL <N>` after failures and `NTP: --` without credentials or without an attempt. Use the RTC copy of `NtpSyncState` through a new `SystemInfo` field. Show whole hours, so the text changes once each hour and `FrameRefresh` still skips. Do not start Wi-Fi.
  - Acceptance test: Host tests assert the three texts, `NTP: OK 0H AGO` directly after a sync, and the render bytes of the diagnostics scene against a fixed CRC-32. `./scripts/generate-display-preview.sh` updates `docs/display_preview_diagnostics.png` with the line. `c++ -std=c++17 -Wall -Wextra -Werror tests/test_firmware_logic.cpp -o test_runner && ./test_runner` and `arduino-cli compile rlcd_panel` pass.

- [ ] Quiet hours and wake lines in the Android sensor health screen
  - Rank: [Total: 26, Use: 4, Setup: 6, Harmony: 6, Safety: 10, Effort: S]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/StatusReply.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/StatusReply.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/SensorHealthScreen.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/StatusReplyTest.kt
  - Goal: Parse the `STATUS` lines `QUIET start_h=<H> end_h=<H> active=<0|1|--> night_interval_s=<N>` and `WAKE cause=<POWER_ON|TIMER|KEY|OTHER> boots=<N> battery_interval_s=<N>` in the shared `StatusReply.kt`. Show `Ruhezeit <H>–<H> Uhr` with `aktiv` while active, `Akku-Intervall <N> s`, `Nachtintervall <N> s` (0 is `aus`) and `Weckgrund <cause>` in `SensorHealthScreen`. A missing line gives `null`. A bad number throws `ParseException`.
  - Acceptance test: `StatusReplyTest` reads `tests/fixtures/status_reply_sample.txt` and asserts each field, `null` for `active=--`, `null` for a missing line, and a `ParseException` for `boots=x` and for `cause=SLEEP`. `./scripts/check-protocol-sync.sh` and `./scripts/check-protocol-sync.sh --self-test` exit 0. The CI jobs pass.

- [ ] BLE reject counters in the Wear OS sensor health screen
  - Rank: [Total: 26, Use: 3, Setup: 7, Harmony: 6, Safety: 10, Effort: S]
  - Location: wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/StatusReply.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/StatusReply.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/StatusReplyTest.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/SensorHealthTextTest.kt
  - Goal: Parse the `STATUS` line `BLE rejected=<N> last=<MAC|--> ... unassigned=<N> jumps=<N>` into `BleRejectState` in the shared `StatusReply.kt` (`unassigned` and `jumps` are `null` for older firmware). Show `BLE abgelehnt <N>, Sprünge <N>` in the panel section of the watch `SensorHealthScreen`. Show the line with `⚠` when `unassigned` is above 0.
  - Acceptance test: `StatusReplyTest` asserts the fields of the fixture line, `null` for `last=--`, `null` for a missing `jumps` field and a `ParseException` for `rejected=x`. `SensorHealthTextTest` asserts the text and the warning mark. `./scripts/check-protocol-sync.sh` and `./scripts/check-protocol-sync.sh --self-test` exit 0. The CI jobs pass.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
