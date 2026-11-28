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

- [ ] Console command SLOT REPLACE for a new sensor in a slot
  - **Location:** `rlcd_panel/serial_console.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/device_config.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `tests/fixtures/slot_replace_reply_sample.txt`, `FEATURES.md`
  - **Goal:** Let the user replace a defective sensor and keep the room data. Add `SLOT REPLACE <N> <MAC>` (N 1 to 10, MAC in the `slotN_mac` format). The command writes `slotN_mac` to `config.ini` through `DeviceConfigEditor` and sets the offset and gain keys of the slot to the default in the same file write. It keeps the slot name, the 24-hour histories and the week history. It clears the reading, the reception history, the JumpGuard packet and the latch bits of the slot. It records one `SLOT_ASSIGN` event. Replies: `OK SLOT REPLACE <N>`, `ERR BAD_ARG`, `ERR DUPLICATE_MAC` (another slot holds the MAC), `ERR NO_CARD`, `ERR WRITE_FAILED`, `ERR READ_ONLY` on BLE with `ble_console` 1. A failed write changes no RTC state.
  - **Acceptance:** Host tests cover a good replace, a duplicate MAC, a bad MAC, a failed write without a state change and the kept histories. The host test command and `arduino-cli compile rlcd_panel` pass.
  - **Rank:** [Total: 34, Use: 7, Setup: 10, Harmony: 9, Safety: 8, Effort: M]

## Priority 2: Configuration and Control (Score 28–33)

- [ ] Calibration age of each sensor
  - **Location:** `rlcd_panel/serial_console.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `macos/RlcdCompanion/Sources/RlcdProtocol/Calibration.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/CalibrationView.swift`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/CalibrationReply.kt`, `tests/test_firmware_logic.cpp`, `tests/fixtures/calib_age_reply_sample.txt`, `FEATURES.md`
  - **Goal:** Show when the user calibrated each sensor last. After a successful `SET` of an offset or gain key of a sensor, write the RTC date to NVS (namespace `rlcd`, key `cal_d<sensor>`, one write for each `SET`). Add `CALIB AGE`: `BEGIN CALIB AGE`, header `sensor,name,date,days`, one row for each sensor, `CRC32` line. An unknown date gives empty fields. The apps show `Calibrated <N> days ago` and a warning above 365 days.
  - **Acceptance:** Host tests cover the key selection, the reply and an invalid RTC time without an NVS write. App unit tests parse the fixture. The host test command, `arduino-cli compile rlcd_panel` and `swift test` pass. The CI job of the phone passes.
  - **Rank:** [Total: 32, Use: 5, Setup: 9, Harmony: 9, Safety: 9, Effort: M]
- [ ] Slot replace in the macOS scanner and the Android slot screen
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/SlotAssignment.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/ScannerView.swift`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/SlotConfig.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/SlotConfigScreen.kt`, the matching test files, `FEATURES.md`
  - **Goal:** Let the user replace a sensor from a scan row. Needs the entry "Console command SLOT REPLACE for a new sensor in a slot". Add the menu item `Replace sensor in slot <N>` for a scanned MAC without a slot. Ask for a confirmation that names the offsets that go back to the default. Send `SLOT REPLACE`, then `STATUS`. Map each `ERR` reply to a text.
  - **Acceptance:** Unit tests cover the command text, the menu rules and the error texts. `swift test` passes. The CI job of the phone passes.
  - **Rank:** [Total: 31, Use: 6, Setup: 9, Harmony: 8, Safety: 8, Effort: M]

## Priority 3: Display and Logging (Score 22–27)

- [ ] Desktop widget for the macOS app
  - **Location:** `macos/RlcdCompanion/Package.swift`, `macos/RlcdCompanion/Sources/RlcdWidget/`, `macos/RlcdCompanion/Sources/RlcdProtocol/WidgetSnapshot.swift`, `scripts/build-app.sh`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/WidgetSnapshotTests.swift`, `FEATURES.md`
  - **Goal:** Show the rooms on the macOS desktop without the menu. Add a WidgetKit extension with a small and a medium widget. The app writes a `WidgetSnapshot` (rooms, temperatures, humidity, alert names, frame time) to the shared app group after each telemetry frame. The widget shows `--` for a snapshot older than 35 minutes. `scripts/build-app.sh` signs and embeds the extension.
  - **Acceptance:** Unit tests cover the snapshot coding and the age rule. `swift test` passes. `./scripts/build-app.sh --dest /tmp/rlcd-app` builds a bundle that `codesign --verify --deep` accepts.
  - **Rank:** [Total: 26, Use: 8, Setup: 1, Harmony: 7, Safety: 10, Effort: L]
- [ ] Year overview in the macOS daily charts
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/DailyLog.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DailyChartView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/DailyLogTests.swift`, `FEATURES.md`
  - **Goal:** Show one year of each room in one chart. Add `DailyLog.monthSummary(rows:sensor:)` with the minimum, the maximum and the mean of each month and the sum of the mold, dry and heat hours. Add the picker `Days` and `Months` to `DailyChartView`. A month without a row is a gap.
  - **Acceptance:** Unit tests cover a full year, a gap month and mixed `daily_v5` to `daily_v7` files. `swift test` and `swift build -c release` pass.
  - **Rank:** [Total: 25, Use: 6, Setup: 1, Harmony: 8, Safety: 10, Effort: M]
- [ ] Daily noise chart in the Android days screen
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/DaysScreen.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/DaysReply.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/DaysReplyTest.kt`, `FEATURES.md`
  - **Goal:** Show the noise of each day on the phone. Draw the bars from `noise_min_db` to `noise_max_db` and one mark at `noise_peak_db` for each day of the `DAYS` reply. Show the peak hour below the bar. An empty field is a gap.
  - **Acceptance:** Unit tests cover the bar model with full and empty fields from `tests/fixtures/days_reply_v7_sample.txt`. The CI job of the phone passes.
  - **Rank:** [Total: 24, Use: 5, Setup: 1, Harmony: 8, Safety: 10, Effort: S]
- [ ] Console command REBOOT
  - **Location:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `tests/test_firmware_logic.cpp`, `FEATURES.md`
  - **Goal:** Let the user restart the panel without a button press. Add `REBOOT` on USB only. Save the RTC state, reply `OK REBOOT`, flush the serial output and call `esp_restart()`. The next boot records the `RESET` event with the software reason. Reply `ERR NOT_OVER_BLE` on BLE and `ERR BAD_ARG` for extra text. Add a `Restart panel` button with a confirmation to `DiagnosticsView`.
  - **Acceptance:** Host tests cover the parser and the BLE refusal. `arduino-cli compile rlcd_panel` and `swift test` pass. A hardware test on the board shows `RESET reason=SW` in `STATUS` after the command.
  - **Rank:** [Total: 24, Use: 2, Setup: 8, Harmony: 6, Safety: 8, Effort: S]
- [ ] Feels-like temperature complication on the Wear OS watch
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/complication/ClimateComplicationModel.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/complication/FeelsComplicationService.kt`, `wearos/RlcdWatch/app/src/main/AndroidManifest.xml`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/complication/ClimateComplicationModelTest.kt`, `FEATURES.md`
  - **Goal:** Show the feels-like temperature of the focused room on the watch face. Use `ClimateMath.heatIndexC` of the cached frame. Show `--` for an old cache, an empty tile and an invalid result. Use the types `SHORT_TEXT` and `RANGED_VALUE` (range 20 to 45 C).
  - **Acceptance:** Unit tests cover the text, the range value and `--`. The CI job `Wear OS companion tests and APK` passes.
  - **Rank:** [Total: 24, Use: 6, Setup: 1, Harmony: 7, Safety: 10, Effort: S]
- [ ] Larger event ring in RTC memory
  - **Location:** `rlcd_panel/sd_logger.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `tests/test_firmware_logic.cpp`, `FEATURES.md`
  - **Goal:** Lose fewer events between two hourly writes. Increase `EventRing` from 16 to 32 events. Accept `EVENTS <N>` from 1 to 32. Set `STATE_VERSION` to the next value. Add a host test that the serialized state fits in the RTC slow memory budget.
  - **Acceptance:** Host tests cover a full ring, the drop count, `EVENTS 32`, `EVENTS 33` (`ERR BAD_ARG`) and the size check. The host test command and `arduino-cli compile rlcd_panel` pass.
  - **Rank:** [Total: 24, Use: 4, Setup: 5, Harmony: 8, Safety: 7, Effort: M]
- [ ] Seven-day telemetry history in the Android app
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/HistoryScreen.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/TelemetryHistory.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/TelemetryHistoryTest.kt`, `FEATURES.md`
  - **Goal:** Keep the telemetry of the phone for seven days. Store one mean sample for each room and 15 minutes after the first 24 hours. Remove samples older than 7 days. Add the range picker `24 h` and `7 d` to the history screen. Keep the CSV share for both ranges.
  - **Acceptance:** Unit tests cover the downsampling, the removal and a store that the old format wrote. The CI job of the phone passes.
  - **Rank:** [Total: 24, Use: 7, Setup: 1, Harmony: 7, Safety: 9, Effort: M]

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
