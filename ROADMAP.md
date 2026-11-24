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

- [ ] Trend arrows in the macOS sensor list
  - Rank: [Total: 30, Use: 7, Setup: 4, Harmony: 9, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift, macos/RlcdCompanion/Sources/RlcdCompanion/Views.swift, macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/ReplyTests.swift, FEATURES.md
  - Goal: Add `StatusSensor.temperatureTrend` and `StatusSensor.humidityTrend` (`SensorTrend?` with `rise`, `fall`, `steady`) from the `temp_trend=` and `rh_trend=` fields of the `SENSOR` lines. Give `nil` for `--` and for a missing field. Throw `ParseError.badLine` for another text. Show an arrow up, an arrow down or a dash beside the temperature and the humidity of each sensor row in the menu bar list and in `DiagnosticsView`. Do not send a new command.
  - Acceptance test: `ReplyTests` read `tests/fixtures/status_reply_sample.txt` and assert each trend value of each sensor, `nil` for `--` and for a line without the fields, and `ParseError.badLine` for `rh_trend=UP`. Run `cd macos/RlcdCompanion && swift test && swift build -c release`.

- [ ] Today summary screen in the Android app
  - Rank: [Total: 29, Use: 7, Setup: 3, Harmony: 9, Safety: 10, Effort: M]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/TodayReply.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/TodayScreen.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/PhoneDashboardScreen.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/TodayReplyTest.kt, FEATURES.md
  - Goal: Add `TodayReply.parse` for the `TODAY` reply: one `TodayRow` (sensor, name, hours, minimum, maximum and mean of temperature and humidity; `null` for an empty field) for each row. Throw `ParseException` for a wrong header, a wrong column count and a bad number. Add `TodayScreen` with one card for each sensor. Open it from one entry on the main screen. Send `TODAY` over the BLE console only on a user action. Show `ERR NO_TIME` as the text `Panel clock not set`.
  - Acceptance test: `TodayReplyTest` reads `tests/fixtures/today_reply_sample.txt` and asserts each row, `null` for empty fields, `ParseException` for 8 columns, for `temp_min_c=x` and for a wrong header. Run `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone`.



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
