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


- [ ] RTC trim in the Android sensor health screen
  - Rank: [Total: 29, Use: 4, Setup: 8, Harmony: 8, Safety: 9, Effort: S]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/StatusReply.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/StatusReply.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/SensorHealthScreen.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/StatusReplyTest.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/SensorHealthTextTest.kt, tests/fixtures/status_reply_sample.txt
  - Goal: The macOS app shows the `RTC trim=<N|--> drift_ppm=<N|-->` line of `STATUS` and sends `RTC TRIM`. The phone shows the clock offset, but not the drift rate, and it cannot trim the clock. Add `RtcState(trim: Int?, driftPpm: Int?)` and `StatusReply.rtc` to the shared `StatusReply.kt`. Return `null` for a missing line and `null` fields for `--`. Throw `ParseException` for a bad number and for a trim outside -64 to 63. Show the row `RTC Trim <N>, Drift <N> ppm` in `SensorHealthScreen`. Show the button `RTC trimmen` only when `panelConfig.canWrite` is true and the drift is 3 ppm or more in either direction. The button sends `RTC TRIM` after a tap, then `STATUS`. Show `OK RTC TRIM <old> <new>`, `ERR NO_DRIFT` and `ERR READ_ONLY` as short texts. Add the line `RTC trim=0 drift_ppm=5` after `RESET` to `tests/fixtures/status_reply_sample.txt` and calculate the `CRC32` line again.
  - Acceptance test: In `StatusReplyTest`, assert `RtcState(0, 5)` for the fixture, `RtcState(null, null)` for `RTC trim=-- drift_ppm=--`, `null` without the line, and `ParseException` for `trim=64` and `drift_ppm=x`. In `SensorHealthTextTest`, assert the button rule for 2 ppm, 3 ppm and -3 ppm and the result texts. Update each literal of the old fixture CRC (find them with `rg -n 18c23d06`). Run `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` and `wearos/RlcdWatch`, `swift test` in `macos/RlcdCompanion`, `c++ -std=c++17 -Wall -Wextra -Werror tests/test_firmware_logic.cpp -o test_runner && ./test_runner && rm -f test_runner` and `./scripts/check-protocol-sync.sh`.

- [ ] Event code for a dropped reading jump
  - Rank: [Total: 28, Use: 4, Setup: 8, Harmony: 7, Safety: 9, Effort: S]
  - Location: rlcd_panel/switchbot_ble.h, rlcd_panel/power_manager.h, rlcd_panel/sd_logger.h, rlcd_panel/rlcd_panel.ino, tests/test_firmware_logic.cpp, AGENTS.md
  - Goal: `JumpGuard` drops a packet that jumps far from the stored reading. The `jumps` counter of `STATUS` and the `REJECTS` list show only the last scan. The event log does not show which sensor sends bad readings, and when. Put this entry after "Event code for a low sensor battery", which takes code 19. Add `EventCode::JUMP_DROP = 20` and set `EventRing::MAX_CODE` to 20. Add `SwitchBotRegistry::takeJumped(index)` like `takeAssigned`: `countJump` sets a flag for the slot, and `takeJumped` returns and clears it. `PersistentState::recordSlotEvents` records one `JUMP_DROP` event for each flagged slot, with the slot number 1 to 10 as detail. Record at most one event for each slot and scan. Add the name `JUMP_DROP` to `SdLogCodec`. The apps show the raw text until a text entry exists. Do not change the RTC layout or `STATE_VERSION`.
  - Acceptance test: In `tests/test_firmware_logic.cpp`, assert one event with detail 3 after a dropped jump in slot 3 at `endScan`, one event with detail 3 for a jump dropped by a near packet, one event only for two drops of the same slot in one scan, no event for a jump that a second packet confirms, and the row `JUMP_DROP`. Change each `EventRing::MAX_CODE == 19` assert to 20 and each rejection test `static_cast<EventCode>(20)` to 21. Run `c++ -std=c++17 -Wall -Wextra -Werror tests/test_firmware_logic.cpp -o test_runner && ./test_runner && rm -f test_runner` and `arduino-cli compile rlcd_panel`.

## Priority 3: Display and Logging (Score 22–27)

- [ ] Chime reminder time in the macOS diagnostics view
  - Rank: [Total: 27, Use: 4, Setup: 6, Harmony: 7, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/Diagnostics.swift, macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/DiagnosticsTests.swift
  - Goal: Show the `CHIME` line of the entry "Chime reminder line in STATUS" beside the alert acknowledge buttons. Add `ChimeMetrics.parse(line:)` with `repeatHours`, `bits`, `lastS` and `nextS`, and add `DiagnosticsReply.chime`. Return `nil` for a missing line and `nil` fields for `--`. Throw `ParseError.badLine` for `repeat_h` above 24, `bits` that are not 2 hex digits and a bad number. Show `Next reminder in <H> h <M> min` when `nextS` exists, `Reminder off` for `repeatHours` 0 and `No reminder due` for `bits` 0.
  - Acceptance test: In `DiagnosticsTests`, assert the values of `CHIME repeat_h=2 bits=02 last_s=3600 next_s=3600`, `nil` for `next_s=--` and `last_s=--`, `nil` without the line, and `ParseError.badLine` for `repeat_h=25` and `bits=2`. Assert the three texts of the view helper. Run `swift test` and `swift build -c release` in `macos/RlcdCompanion`.

- [ ] Daily mold hours in the seven-day history
  - Rank: [Total: 25, Use: 6, Setup: 3, Harmony: 8, Safety: 8, Effort: M]
  - Location: rlcd_panel/sparkline.h, rlcd_panel/power_manager.h, rlcd_panel/serial_console.h, tests/test_firmware_logic.cpp, tests/fixtures/week_reply_sample.txt, macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/WeekReply.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/WeekReply.kt, AGENTS.md
  - Goal: The daily file holds the mold hours of each day, but the `WEEK` reply has no mold value. Without a card, the user cannot see on which days a room was humid for a long time. Add one byte for each day and sensor to `WeekHistory`: the hours above `mold_alert_limit_rh` from `HumidityHistory::countAbove`, 0 to 24, `0xFF` for a gap day. `WeekHistory::advance` stores it with the minimum and maximum. Add the bytes to `PersistentState::week` and raise `STATE_VERSION` by 1. Reject a value from 25 to 254 and a value beside a gap pair. `WeekHistory::restore` sets the hours from the `mold_h_<N>` columns of the daily file, or `0xFF` for an empty field. Add the column `mold_h` to the `WEEK` reply (empty for a gap day). The macOS and Kotlin `WeekReply` parsers accept 7 or 8 columns and give `moldHours` as `nil` or `null` for 7 columns and for an empty field.
  - Acceptance test: In `tests/test_firmware_logic.cpp`, assert the stored hours after `advance`, the gap marker for a sensor without samples, the rejection of a stored 25, the `restore` value from a daily row, the `WEEK` header with `mold_h`, and the RTC round trip with the new `STATE_VERSION`. Add the column to `tests/fixtures/week_reply_sample.txt` and calculate the `CRC32` line again. In the macOS and Kotlin `WeekReply` tests, assert `moldHours` for 8 columns and `nil` or `null` for 7 columns. Run `c++ -std=c++17 -Wall -Wextra -Werror tests/test_firmware_logic.cpp -o test_runner && ./test_runner && rm -f test_runner`, `arduino-cli compile rlcd_panel`, `swift test` in `macos/RlcdCompanion`, `gradle testDebugUnitTest` in `android/RlcdPhone` and `wearos/RlcdWatch`, and `./scripts/check-protocol-sync.sh`.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
