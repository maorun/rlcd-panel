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

- [ ] Humidity alert delay in the Android alert limit editor
  - Rank: [Total: 33, Use: 7, Setup: 9, Harmony: 7, Safety: 10, Effort: S]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/AlertLimits.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/AlertLimitScreen.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/AlertLimitsTest.kt
  - Goal: Add `mold_alert_delay_h` (whole hours, 0 to 12, 0 is off) to the limits of `AlertLimits`. Show the field `Verzögerung Schimmelalarm (h)` below the mold limit in `AlertLimitScreen`. Send the value with the same `SET` batch and stop rules as the other limits. Do this after the firmware item "Delay for the humidity alert in config.ini".
  - Acceptance test: `AlertLimitsTest` asserts that 0 and 12 are accepted, that 13, -1 and `2.5` are rejected, the wire text `SET mold_alert_delay_h=3`, and no command for an unchanged value. `gradle testDebugUnitTest assembleDebug` passes in the CI job `Android phone companion tests and APK`.

- [ ] Slot swap in the Android slot configuration screen
  - Rank: [Total: 33, Use: 6, Setup: 10, Harmony: 8, Safety: 9, Effort: S]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/SlotConfig.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/SlotConfigScreen.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/SlotConfigTest.kt
  - Goal: Add `SlotConfig.swap(a, b, config): Plan`. Give `Plan.Commands(listOf("SLOT SWAP <A> <B>"))` for two different slots from 1 to 10. Give `Plan.Invalid` for a slot with a fixed MAC, for a disabled slot (`slotN_enabled=0`) and for equal slots. Add the button `Tauschen mit …` to the slot editor when `PanelConfig.canWrite` is true. Send `STATUS` after an `OK SLOT SWAP` reply.
  - Acceptance test: `SlotConfigTest` asserts the command for slots 1 and 4, `Plan.Invalid` for a fixed MAC in slot 4, for `slot2_enabled=0` and for slots 3 and 3, and the result text of `ERR FIXED_MAC`. `gradle testDebugUnitTest` passes in CI.

- [ ] Identify action in the Wear OS sensor health screen
  - Rank: [Total: 32, Use: 5, Setup: 9, Harmony: 8, Safety: 10, Effort: S]
  - Location: wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/ChimeCommand.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ChimeCommand.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/ChimeCommandTest.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/ChimeCommandTest.kt
  - Goal: Add `ChimeCommand.IDENTIFY` (`IDENTIFY`) and `ChimeCommand.identifyText(line)` for `OK IDENTIFY <4 hex digits>` to the shared file. Add the chip `Panel finden` to the watch `SensorHealthScreen`. Send the command once for each tap and show `Panel zeigt RLCD <XXXX>`. Do this after the firmware item "Console command IDENTIFY to find the connected panel".
  - Acceptance test: `ChimeCommandTest` on the watch and on the phone asserts the command text, the text for `OK IDENTIFY 3F2A`, and `null` for `OK IDENTIFY 3F`. `./scripts/check-protocol-sync.sh` and `./scripts/check-protocol-sync.sh --self-test` exit 0. The CI jobs `Wear OS companion tests and APK` and `Android phone companion tests and APK` pass.

- [ ] Console command SNOOZE for a timed mute
  - Rank: [Total: 32, Use: 9, Setup: 5, Harmony: 9, Safety: 9, Effort: M]
  - Location: rlcd_panel/serial_console.h, rlcd_panel/power_manager.h, rlcd_panel/audio_alert.h, rlcd_panel/dashboard_view.h, rlcd_panel/rlcd_panel.ino, tests/fixtures/status_reply_sample.txt, tests/test_firmware_logic.cpp
  - Goal: Add `SNOOZE <M>` (M 0 to 720 minutes). Store the start stamp (`uint32_t`, `nowMs()`) and the minutes (`uint16_t`) in `PersistentState::snooze`, 6 bytes just before the checksum. While the snooze is active, `AlertEvaluator::chimeBits` and `AlertEvaluator::repeat` work as for `muted`, and the header shows the mute marker. `SNOOZE 0`, `MUTE 0`, `MUTE 1` and a `BOOT` press end the snooze. Reply `OK SNOOZE <M>`. Give `ERR BAD_ARG` for 721, a non-digit and extra text, and `ERR READ_ONLY` on BLE with `ble_console` 1. Record `MUTE_CHANGE` with detail low byte 2 and the source in the high byte. Append ` snooze_s=<N|-->` (remaining seconds) to the `STATUS` line `CHIME`. Reject minutes above 720 and minutes 0 with a stamp in the RTC check. A power-on sets both to 0. Raise `STATE_VERSION` by one.
  - Acceptance test: Host tests assert that `SNOOZE 60` mutes a rising bit at 59 minutes and not at 61 minutes, that a 32-bit clock wrap keeps the end correct, that `MUTE 0` ends the snooze, the event detail `0x0102` for USB, the `CHIME` line with `snooze_s=3540` and `snooze_s=--`, the RTC round trip and the rejected values. Update `tests/fixtures/status_reply_sample.txt` and the `STATUS` line count asserts. `c++ -std=c++17 -Wall -Wextra -Werror tests/test_firmware_logic.cpp -o test_runner && ./test_runner` and `arduino-cli compile rlcd_panel` pass.

- [ ] Console command NTP SYNC to sync the clock on request
  - Rank: [Total: 32, Use: 6, Setup: 9, Harmony: 9, Safety: 8, Effort: M]
  - Location: rlcd_panel/serial_console.h, rlcd_panel/ntp_sync.h, rlcd_panel/rlcd_panel.ino, tests/test_firmware_logic.cpp
  - Goal: Add `NTP SYNC` on USB serial. Run `NtpClient::sync` once through an injected function of the sketch, as `SCAN` does with `consoleScanOnce`. Call `NtpSchedule::record` and record `EventCode::NTP_SYNC` with the `NtpResult`. Reply `OK NTP SYNC` or `ERR NTP_FAILED <WIFI_FAILED|NTP_TIMEOUT|RTC_WRITE_FAILED>`. Give `ERR NO_WIFI` without credentials (`NtpSchedule::hasCredentials`), `ERR NOT_OVER_BLE` on BLE and `ERR BAD_ARG` for extra text. Do not mount the card. Do not change the RTC layout or `STATE_VERSION`.
  - Acceptance test: Host tests with a fake sync function assert the reply for each `NtpResult`, the event row, the updated `NTP` line of `STATUS` (`ok=1 failures=0`), `ERR NO_WIFI` with empty credentials, `ERR NOT_OVER_BLE` through `BleConsoleBridge` and `ERR BAD_ARG` for `NTP SYNC 1`. `c++ -std=c++17 -Wall -Wextra -Werror tests/test_firmware_logic.cpp -o test_runner && ./test_runner` and `arduino-cli compile rlcd_panel` pass.

- [ ] Event texts for SCAN_FAULT and WINDOW_OPEN in the companion apps
  - Rank: [Total: 31, Use: 5, Setup: 7, Harmony: 9, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/EventText.swift, macos/RlcdCompanion/Sources/RlcdProtocol/EventFilter.swift, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/EventText.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/EventFilter.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/EventText.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/EventFilter.kt, tests/fixtures/events_reply_sample.txt, macos/RlcdCompanion/Tests/RlcdProtocolTests/EventTextTests.swift, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/EventTextTest.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/EventTextTest.kt
  - Goal: Show `SCAN_FAULT` events as `BLE scan start failed` (detail 1) and `BLE scan works again` (detail 0) in the three apps. Put `SCAN_FAULT` in the filter category `sensors`. Show `WINDOW_OPEN` events in the shared Kotlin `EventText.kt` as `Window open (tile <N>)` and `Window closed (tile <N>)`, with `Onboard` for tile 0. Keep the raw text for another detail. Add one row of each code to the shared events fixture.
  - Acceptance test: `EventTextTests`, `EventFilterTests` and the Kotlin `EventTextTest`, `EventFilterTest` and `EventsReplyTest` assert both texts of each code, the raw text for `SCAN_FAULT,2` and `WINDOW_OPEN,523`, the category, and the new fixture row counts (Swift counts the `EVENTS_DROPPED` row, Kotlin does not). `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass. `./scripts/check-protocol-sync.sh` and `./scripts/check-protocol-sync.sh --self-test` exit 0. The Android and Wear OS CI jobs pass.

- [ ] Snooze menu in the macOS menu bar
  - Rank: [Total: 30, Use: 8, Setup: 4, Harmony: 8, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift, macos/RlcdCompanion/Sources/RlcdProtocol/Diagnostics.swift, macos/RlcdCompanion/Sources/RlcdProtocol/EventText.swift, macos/RlcdCompanion/Sources/RlcdCompanion/RlcdCompanionApp.swift, macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/DiagnosticsTests.swift
  - Goal: Add `ConsoleCommand.snooze(Int)` (`SNOOZE <M>`, 0 to 720). Add the menu items `Mute for 1 hour`, `Mute for 8 hours` and `End snooze` to the `MenuBarExtra`. Send the command, then `STATUS`. Parse ` snooze_s=<N|-->` of the `CHIME` line and show `Muted for <H:MM>`. Show `MUTE_CHANGE` with low byte 2 as `Chime snoozed (<source>)`. Do this after the firmware item "Console command SNOOZE for a timed mute".
  - Acceptance test: `DiagnosticsTests` assert `snooze_s=3540` as 3540, `nil` for `--` and for a missing field, and `ParseError.badLine` for `snooze_s=x`. `ReplyTests` assert `SNOOZE 60` and a precondition failure for 721. `EventTextTests` assert the snooze text. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.

- [ ] Snooze buttons and notification action in the Android app
  - Rank: [Total: 30, Use: 8, Setup: 4, Harmony: 8, Safety: 10, Effort: S]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ChimeCommand.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/ChimeCommand.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/StatusReply.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/StatusReply.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/EventText.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/EventText.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/ChimeScreen.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/AckReceiver.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/BleScanService.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/ChimeCommandTest.kt
  - Goal: Add `ChimeCommand.snooze(minutes)` and the result text of `OK SNOOZE <M>` to the shared file. Parse ` snooze_s=` into `ChimeState.snoozeSeconds` in the shared `StatusReply.kt`. Show `MUTE_CHANGE` with low byte 2 as `Chime snoozed (<source>)`. Add the buttons `1 h stumm`, `8 h stumm` and `Stumm beenden` to `ChimeScreen` when `PanelConfig.canWrite` is true. Add the action `1 h stumm` to the alert notification; `AckReceiver` sends `SNOOZE 60` once after the tap. Do this after the firmware item "Console command SNOOZE for a timed mute".
  - Acceptance test: `ChimeCommandTest` asserts `SNOOZE 60`, an `IllegalArgumentException` for 721, and the result text. `StatusReplyTest` asserts `snoozeSeconds` for `3540`, `null` for `--` and a `ParseException` for `x`. `EventTextTest` asserts the snooze text. `./scripts/check-protocol-sync.sh` and `./scripts/check-protocol-sync.sh --self-test` exit 0. The CI jobs pass.

- [ ] Clock sync button in the macOS diagnostics view
  - Rank: [Total: 30, Use: 5, Setup: 8, Harmony: 8, Safety: 9, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift, macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift, macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/ReplyTests.swift
  - Goal: Add `ConsoleCommand.ntpSync` (`NTP SYNC`). Add the button `Sync clock now (Wi-Fi)` to the group `Clock sync (NTP)`. Send the command on the USB link only, with a 30 s reply timeout, then `STATUS`. Show readable texts for `ERR NO_WIFI`, `ERR NTP_FAILED <reason>` and `ERR NOT_OVER_BLE`. Do this after the firmware item "Console command NTP SYNC to sync the clock on request".
  - Acceptance test: `ReplyTests` assert the command line, the texts of `ERR NO_WIFI` and `ERR NTP_FAILED NTP_TIMEOUT`, and the raw text for an unknown reason. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.

- [ ] Snooze action in the Wear OS alert notification
  - Rank: [Total: 28, Use: 7, Setup: 3, Harmony: 8, Safety: 10, Effort: S]
  - Location: wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/BleScanService.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/AckReceiver.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/ChimeCommandTest.kt
  - Goal: Add the action `1 h stumm` to the watch alert notification. `AckReceiver` sends `SNOOZE 60` once after the tap and shows the result text from the shared `ChimeCommand`. Do this after the item "Snooze buttons and notification action in the Android app", which adds `ChimeCommand.snooze` to the shared file.
  - Acceptance test: `ChimeCommandTest` on the watch asserts `SNOOZE 60` and the result text of `OK SNOOZE 60` and `ERR READ_ONLY`. `./scripts/check-protocol-sync.sh` and `./scripts/check-protocol-sync.sh --self-test` exit 0. The CI job `Wear OS companion tests and APK` passes.

## Priority 3: Display and Logging (Score 22–27)

- [ ] Open window latch in the STATUS line LATCH
  - Rank: [Total: 27, Use: 3, Setup: 7, Harmony: 7, Safety: 10, Effort: S]
  - Location: rlcd_panel/serial_console.h, rlcd_panel/rlcd_panel.ino, tests/fixtures/status_reply_sample.txt, tests/test_firmware_logic.cpp
  - Goal: Append ` window=<3 hex digits>` after `wall=` to the `STATUS` line `LATCH` (RTC copy of `PersistentState::window_tiles`; bit 0 onboard, bits 1 to 10 slots). Add `SerialStatusSnapshot::window_tiles`. Do not change the RTC layout or `STATE_VERSION`. Update the shared fixture.
  - Acceptance test: Host tests assert `window=000` for no open tile, `window=402` for tiles 1 and 10, and the correct `CRC32` line. The macOS and Kotlin parser tests pass with the new fixture line. `c++ -std=c++17 -Wall -Wextra -Werror tests/test_firmware_logic.cpp -o test_runner && ./test_runner` and `arduino-cli compile rlcd_panel` pass.

- [ ] Wall and window latches in the macOS diagnostics view
  - Rank: [Total: 27, Use: 4, Setup: 6, Harmony: 7, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/Diagnostics.swift, macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/DiagnosticsTests.swift
  - Goal: Parse `wall=` and `window=` of the `LATCH` line into `LatchMetrics.wall` and `LatchMetrics.window` (`Int?`, `nil` for a missing field of older firmware). Include both in `hasBadges`. Show the badge names `Wall` and `Window` for each tile in the latch group of `DiagnosticsView`. Do this after the firmware item "Open window latch in the STATUS line LATCH".
  - Acceptance test: `DiagnosticsTests` assert `wall=002 window=402` from the fixture, `nil` for a line without both fields, and `ParseError.badLine` for `wall=0x2` and for a mask above `0x7ff`. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.

- [ ] Wall and window latches in the Wear OS sensor health screen
  - Rank: [Total: 27, Use: 4, Setup: 6, Harmony: 7, Safety: 10, Effort: S]
  - Location: wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/StatusReply.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/StatusReply.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/StatusReplyTest.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/SensorHealthTextTest.kt
  - Goal: Parse `wall=` and `window=` of the `LATCH` line into `LatchState.wall` and `LatchState.window` (`Int?`) in the shared `StatusReply.kt`. Show `Wand` and `Fenster` beside the other badge names of each room in the watch `SensorHealthScreen`. Do this after the firmware item "Open window latch in the STATUS line LATCH".
  - Acceptance test: `StatusReplyTest` asserts both masks from the fixture, `null` for a missing field and a `ParseException` for a mask above `7ff`. `SensorHealthTextTest` asserts the badge text of tile 1. `./scripts/check-protocol-sync.sh` and `./scripts/check-protocol-sync.sh --self-test` exit 0. The CI jobs pass.

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
