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

- [ ] Badge latches in the Wear OS sensor health screen
  - Rank: [Total: 28, Use: 5, Setup: 5, Harmony: 8, Safety: 10, Effort: S]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/StatusReply.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/StatusReply.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/StatusReplyTest.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/SensorHealthTextTest.kt
  - Goal: The panel tiles show the badges `V`, `D`, `BAT LOW` and `STALE`. The `STATUS` line `LATCH vent=<3 hex> dry=<3 hex> batlow=<3 hex> stale=<3 hex>` gives the same state, but only the macOS app reads it. Add `LatchState(ventilate, dry, batteryLow, stale)`, `StatusReply.latch` and `LatchState.badges(sensor)` to the shared `StatusReply.kt`, with the rules of `LatchMetrics` in `Diagnostics.swift`: `vent` and `dry` up to `0x7ff`, `batlow` and `stale` up to `0x3ff`, the order `V`, `D`, `BAT LOW`, `STALE`, no `BAT LOW` and no `STALE` for sensor 0, no badge outside 0 to 10. Return `null` for a missing line. Throw `ParseException` for a value that is not 3 hex digits or above the limit. Show the badges in each sensor row of the watch `SensorHealthScreen`.
  - Acceptance test: In the watch `StatusReplyTest`, assert the masks of the fixture line `LATCH vent=002 dry=000 batlow=008 stale=004`, `["V"]` for sensor 1, `["BAT LOW"]` for sensor 4, `["STALE"]` for sensor 3, `[]` for sensor 11, `null` without the line, and `ParseException` for `vent=800` and `dry=12`. In `SensorHealthTextTest`, assert the row text. Run `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` and `android/RlcdPhone`, and `./scripts/check-protocol-sync.sh`.

- [ ] Room history on the Wear OS watch
  - Rank: [Total: 28, Use: 7, Setup: 2, Harmony: 9, Safety: 10, Effort: M]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/HistoryReply.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/HistoryReply.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/HistoryScreen.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/HistoryReplyTest.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/HistoryTextTest.kt, scripts/check-protocol-sync.sh
  - Goal: The watch shows the current reading and the seven-day chart, but not the trend of the last hours. Put this entry after "Console command HISTORY for one sensor". Copy `HistoryReply.kt` of the phone into the watch package and add it to `FILES` in `scripts/check-protocol-sync.sh`. Add the chip `Room history` to the watch dashboard. A tap sends `HISTORY <N>` once for the focused room (`TelemetryCache.focusedRoom`). For an older firmware that replies with an `ERR` line, send `HISTORY` and keep the rows of the room. Add `HistoryText`: the minimum and the maximum of the last 24 hours, and one row for each of the 6 newest hours (`-<age> h <T> °C <RH> %`). Show `Keine Daten` for no rows and `Panel not in range` for a timeout.
  - Acceptance test: Copy `HistoryReplyTest` of the phone into the watch tests and assert that it passes with `tests/fixtures/history_reply_sample.txt`. In `HistoryTextTest`, assert the minimum and maximum text, the 6 newest rows in age order, `Keine Daten` for no rows, and the fallback filter for a full reply. Run `./scripts/check-protocol-sync.sh` and assert exit 0 with the new `FILES` entry. Run `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` and `android/RlcdPhone`, and `./scripts/check-protocol-sync.sh --self-test`.

## Priority 3: Display and Logging (Score 22–27)

- [ ] Quiet hours in the macOS diagnostics view
  - Rank: [Total: 27, Use: 4, Setup: 6, Harmony: 7, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/Diagnostics.swift, macos/RlcdCompanion/Sources/RlcdProtocol/DiagnosticsReport.swift, macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/DiagnosticsTests.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/DiagnosticsReportTests.swift
  - Goal: Show the `QUIET` line of the entry "Quiet hours line in STATUS" in the macOS app. Add `QuietMetrics.parse(line:)` with `startHour`, `endHour`, `active` (`Bool?`) and `nightIntervalS`, and add `DiagnosticsReply.quiet`. Return `nil` for a missing line and `active` `nil` for `--`. Throw `ParseError.badLine` for an hour above 23, an `active` value other than `0`, `1` and `--`, and a bad number. Show the group `Quiet hours` in `DiagnosticsView`: `22:00 to 07:00`, `active now` or `not active`, and `Night interval <N> s` or `Night interval off`. Add the summary line `Quiet hours: 22:00 to 07:00, active` to `DiagnosticsReport`.
  - Acceptance test: In `DiagnosticsTests`, assert the values of `QUIET start_h=22 end_h=7 active=1 night_interval_s=900`, `active` `nil` for `active=--`, `nil` without the line, and `ParseError.badLine` for `start_h=24`, `active=2` and `night_interval_s=x`. In `DiagnosticsReportTests`, assert the summary line. Run `swift test` and `swift build -c release` in `macos/RlcdCompanion`.

- [ ] Event code for a failed BLE scan start
  - Rank: [Total: 26, Use: 3, Setup: 8, Harmony: 7, Safety: 8, Effort: S]
  - Location: rlcd_panel/sd_logger.h, rlcd_panel/power_manager.h, rlcd_panel/rlcd_panel.ino, tests/test_firmware_logic.cpp, AGENTS.md
  - Goal: When `g_ble_scan->start` fails in `scanOnce()`, the panel clears the reject copy and shows old readings. Nothing in the event log tells the user that the radio failed. Add `EventCode::SCAN_FAULT = 21` and set `EventRing::MAX_CODE` to 21. Add `PersistentState::scan_fault` (1 byte just before the checksum; a value above 1 is rejected; a power-on sets it to 0) and raise `STATE_VERSION` by 1. Add `PersistentState::recordScanResult(started, now)`: a change from 0 to 1 records one event with detail 1, a change from 1 to 0 records one event with detail 0, an equal state records nothing. So a radio that fails in each wake cycle does not fill the ring. Call it in `scanOnce()` after the start. When the RTC time is not read yet, keep the event in a pending flag, like `g_pending_sd_event`. The apps show the raw text until an `EventText` entry exists.
  - Acceptance test: In `tests/test_firmware_logic.cpp`, assert one event with detail 1 for the first failed start, no event for a second failed start, one event with detail 0 for the next good start, no event for a good start after a good start, the row `SCAN_FAULT` from `SdLogCodec::formatEventRow`, the rejection of a stored `scan_fault` of 2, and the RTC round trip with the new `STATE_VERSION`. Change each `EventRing::MAX_CODE == 20` assert to 21 (find them with `rg -n 'MAX_CODE == 20' tests/test_firmware_logic.cpp`). Change each rejection test `static_cast<EventCode>(21)` to 22 and add a restore test that accepts code 21. Run `c++ -std=c++17 -Wall -Wextra -Werror tests/test_firmware_logic.cpp -o test_runner && ./test_runner && rm -f test_runner` and `arduino-cli compile rlcd_panel`.

- [ ] Acknowledge all button in the macOS diagnostics view
  - Rank: [Total: 25, Use: 5, Setup: 4, Harmony: 7, Safety: 9, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift, macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift, macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/ReplyTests.swift
  - Goal: Put this entry after "Console command ACK ALL". The diagnostics view shows one `Acknowledge` button for each active alert. Add `ConsoleCommand.ackAll` with the line `ACK ALL`. Extend `ConsoleCommand.ackMessage` with `OK ACK ALL <mask>` (`Acknowledged: <names>`) and keep the current texts for `ERR NOT_ACTIVE` and `ERR READ_ONLY`. Add `AppModel.acknowledgeAll()`: it queues `.ackAll` and `.status`, like `acknowledge(bit:)`. Show the button `Acknowledge all` only when two or more active bits are not acknowledged.
  - Acceptance test: In `ReplyTests`, assert the line `ACK ALL`, `Acknowledged: Humidity, Ventilate` for `OK ACK ALL 22`, the current text for `ERR NOT_ACTIVE`, and the button rule for 1 and 2 open bits with a pure helper. Run `swift test` and `swift build -c release` in `macos/RlcdCompanion`.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
