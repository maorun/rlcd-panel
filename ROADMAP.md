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

- [ ] Trend fields in the STATUS SENSOR lines
  - Rank: [Total: 31, Use: 6, Setup: 6, Harmony: 9, Safety: 10, Effort: S]
  - Location: rlcd_panel/serial_console.h, rlcd_panel/sparkline.h, rlcd_panel/rlcd_panel.ino, tests/test_firmware_logic.cpp, tests/fixtures/status_reply_sample.txt, AGENTS.md, FEATURES.md
  - Goal: Append ` temp_trend=<RISE|FALL|STEADY|-->` and ` rh_trend=<RISE|FALL|STEADY|-->` after `mold_h=` to each `SENSOR` line of `STATUS`. Use `TrendClassifier::temperature` and `TrendClassifier::humidity` of the 24-hour histories of the sensor, the same rules as the trend marks of the tiles. Give `--` for `Trend::UNKNOWN` and for an empty, disabled or stale slot. Calculate the fields on each `STATUS`. Do not add RTC state. Do not change the line count, the RTC layout or `STATE_VERSION`. Keep the macOS, Android and Wear OS `StatusReply` parsers unchanged, because they ignore unknown fields.
  - Acceptance test: Host tests in `tests/test_firmware_logic.cpp` assert `RISE`, `FALL` and `STEADY` at the exact thresholds (0.5 C, 3.0 %RH), `--` for 3 samples, for an empty slot and for a stale slot, and the full `SENSOR` line with fixed histories. Update `tests/fixtures/status_reply_sample.txt` and assert that `StatusReplyTest` of the phone and the watch and `ReplyTests` of macOS still pass. Run `c++ -std=c++17 -Wall -Wextra -Werror tests/test_firmware_logic.cpp -o test_runner && ./test_runner && rm -f test_runner` and `arduino-cli compile rlcd_panel`.

- [ ] Trend arrows in the macOS sensor list
  - Rank: [Total: 30, Use: 7, Setup: 4, Harmony: 9, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift, macos/RlcdCompanion/Sources/RlcdCompanion/Views.swift, macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/ReplyTests.swift, FEATURES.md
  - Goal: Add `StatusSensor.temperatureTrend` and `StatusSensor.humidityTrend` (`SensorTrend?` with `rise`, `fall`, `steady`) from the `temp_trend=` and `rh_trend=` fields of the `SENSOR` lines. Give `nil` for `--` and for a missing field. Throw `ParseError.badLine` for another text. Show an arrow up, an arrow down or a dash beside the temperature and the humidity of each sensor row in the menu bar list and in `DiagnosticsView`. Do not send a new command.
  - Acceptance test: `ReplyTests` read `tests/fixtures/status_reply_sample.txt` and assert each trend value of each sensor, `nil` for `--` and for a line without the fields, and `ParseError.badLine` for `rh_trend=UP`. Run `cd macos/RlcdCompanion && swift test && swift build -c release`.

- [ ] Recent events in the macOS menu bar
  - Rank: [Total: 29, Use: 6, Setup: 5, Harmony: 8, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift, macos/RlcdCompanion/Sources/RlcdProtocol/EventText.swift, macos/RlcdCompanion/Sources/RlcdCompanion/Views.swift, macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/ReplyTests.swift, FEATURES.md
  - Goal: Add `ConsoleCommand.eventsNewest(Int)` for `EVENTS <N>` (N 1 to 16). Add a section `Recent events` to the menu bar view with the newest 5 events as `EventText` lines, newest first. Read the events with `EVENTS 5` on a user action and after each `STATUS` refresh that the user starts. Show `Events lost` when the reply has the `EVENTS_DROPPED` row. Send the command on USB and on BLE.
  - Acceptance test: `ReplyTests` assert the command text `EVENTS 1`, `EVENTS 5` and `EVENTS 16`, a precondition failure or `nil` for 0 and 17, and the parse of a 5-row reply with and without the `EVENTS_DROPPED` row through `EventsReply`. Run `cd macos/RlcdCompanion && swift test && swift build -c release`.

- [ ] Console command TODAY for the minimum and maximum of the current day
  - Rank: [Total: 29, Use: 7, Setup: 4, Harmony: 8, Safety: 10, Effort: M]
  - Location: rlcd_panel/serial_console.h, rlcd_panel/sparkline.h, rlcd_panel/rlcd_panel.ino, tests/test_firmware_logic.cpp, tests/fixtures/today_reply_sample.txt, AGENTS.md, FEATURES.md
  - Goal: Add the console command `TODAY` (`SerialCommandKind::TODAY`). Use the hourly samples of the RTC `ClimateHistory` since the last local midnight of the RTC time (onboard and slots 1 to 10). Reply `BEGIN TODAY`, the header `sensor,name,hours,temp_min_c,temp_max_c,temp_mean_c,rh_min_pct,rh_max_pct,rh_mean_pct`, one row for each sensor with samples, and the `CRC32` line. Give empty values for a sensor without samples since midnight. Reply `ERR NO_TIME` for an invalid or stopped RTC time and `ERR BAD_ARG` for extra text. Do not access the card. Do not change the state or the RTC layout. Run the command on USB and on BLE at each `ble_console` level.
  - Acceptance test: Host tests assert the row of a sensor with 5 samples since midnight, the exclusion of samples before midnight, a sensor without samples, `ERR NO_TIME` and `ERR BAD_ARG`. Compare one reply with `tests/fixtures/today_reply_sample.txt`. Run the host test command from `AGENTS.md` and `arduino-cli compile rlcd_panel`. On the board, send `TODAY` over USB and verify the `CRC32` line.

- [ ] Today summary screen in the Android app
  - Rank: [Total: 29, Use: 7, Setup: 3, Harmony: 9, Safety: 10, Effort: M]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/TodayReply.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/TodayScreen.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/PhoneDashboardScreen.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/TodayReplyTest.kt, FEATURES.md
  - Goal: Add `TodayReply.parse` for the `TODAY` reply: one `TodayRow` (sensor, name, hours, minimum, maximum and mean of temperature and humidity; `null` for an empty field) for each row. Throw `ParseException` for a wrong header, a wrong column count and a bad number. Add `TodayScreen` with one card for each sensor. Open it from one entry on the main screen. Send `TODAY` over the BLE console only on a user action. Show `ERR NO_TIME` as the text `Panel clock not set`.
  - Acceptance test: `TodayReplyTest` reads `tests/fixtures/today_reply_sample.txt` and asserts each row, `null` for empty fields, `ParseException` for 8 columns, for `temp_min_c=x` and for a wrong header. Run `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone`.

- [ ] Recent events card on the Android dashboard
  - Rank: [Total: 28, Use: 6, Setup: 4, Harmony: 8, Safety: 10, Effort: S]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/PhoneDashboardScreen.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/EventsReply.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/EventsReplyTest.kt, FEATURES.md
  - Goal: Add `EventsReply.newestCommand(n)` for `EVENTS <N>` (N 1 to 16, `IllegalArgumentException` for another value). Add a card `Recent events` to `PhoneDashboardScreen` with the newest 3 events as `EventText` lines, newest first, and a button that opens `EventLogScreen`. Read the card with `EVENTS 3` over the BLE console only on a user action. Keep the full `EVENTS` read of `EventLogScreen`.
  - Acceptance test: `EventsReplyTest` asserts the command text `EVENTS 3`, `IllegalArgumentException` for 0 and 17, and the parse of a 3-row reply with and without the `EVENTS_DROPPED` row. Run `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone`.

- [ ] Short event log read on the Wear OS watch
  - Rank: [Total: 28, Use: 5, Setup: 5, Harmony: 8, Safety: 10, Effort: S]
  - Location: wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/EventsReply.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/EventLogScreen.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/EventsReplyTest.kt, FEATURES.md
  - Goal: Add `EventsReply.newestCommand(n)` for `EVENTS <N>` (N 1 to 16, `IllegalArgumentException` for another value). Make `EventLogScreen` read `EVENTS 8` first, because a short reply needs less BLE time on the watch. Add a chip `Show all` that reads `EVENTS` once. Keep the `EventFilterChip` on both lists.
  - Acceptance test: `EventsReplyTest` asserts the command text `EVENTS 8`, `IllegalArgumentException` for 0 and 17, and the parse of an 8-row reply with and without the `EVENTS_DROPPED` row. Run `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch`. Install the APK with `./scripts/install-watch-apk.sh` and verify the short list and the `Show all` chip.

## Priority 3: Display and Logging (Score 22–27)

- [ ] Week screen for one room on the Wear OS watch
  - Rank: [Total: 26, Use: 5, Setup: 3, Harmony: 8, Safety: 10, Effort: S]
  - Location: wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/WeekReply.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/WeekScreen.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/WeekReplyTest.kt, FEATURES.md
  - Goal: Add `WeekReply.sensorCommand(n)` for `WEEK <N>` (N 0 to 5, `IllegalArgumentException` for another value). Make `WeekScreen` read `WEEK <N>` for the focused room of `TelemetryCache` instead of the full `WEEK` reply. Add a chip that selects the next sensor and reads its 7 rows. Keep the parse of the 7 rows through `WeekReply.parse`.
  - Acceptance test: `WeekReplyTest` asserts the command text `WEEK 0` and `WEEK 5`, `IllegalArgumentException` for -1 and 6, and the parse of a 7-row reply of one sensor with and without the `mold_h` column. Run `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch`.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
