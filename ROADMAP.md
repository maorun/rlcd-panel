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


- [ ] Console command DAYS for the newest daily rows
  - **Location:** `rlcd_panel/serial_console.h`, `rlcd_panel/sd_logger.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `tests/fixtures/days_reply_sample.txt`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Give the companion apps a long-term history over BLE, where `DUMP` is not allowed. Add `DAYS <N>` with N 1 to 31, no leading zero. Read the newest N valid rows of the current `daily_v5_YYYY.csv` and, with fewer rows, of the previous year file, each from the last 16384 bytes through `Storage::readTail`. Reply `BEGIN DAYS <n>`, the `daily_v5` header, the rows oldest first and the `CRC32` line. Mount and unmount the card in the command. Reply `ERR NO_CARD`, `ERR NO_FILE`, `ERR READ_FAILED` and `ERR BAD_ARG` with the same rules as `TAIL`. Run on USB and BLE at each `ble_console` level. Keep the RTC layout and `STATE_VERSION`.
  - **Acceptance:** A host test with a fake storage of 5 rows and `DAYS 3` gives the 3 newest rows oldest first and a correct `CRC32` line. A host test with 2 rows in the current year and 3 rows in the previous year gives 5 rows across the year change. A partial first line and a bad row count as no row. `DAYS 0`, `DAYS 32`, `DAYS 03` and `DAYS 3 x` give `ERR BAD_ARG`. The new fixture parses with `SdLogCodec::parseDailyRow`. The host unit tests pass.
  - **Rank:** [Total: 29, Use: 7, Setup: 5, Harmony: 8, Safety: 9, Effort: M]
- [ ] Daily history screen over the BLE console in the Android app
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/DaysReply.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/DaysScreen.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/DaysReplyTest.kt`, `FEATURES.md`
  - **Goal:** Show up to 31 days of daily minimum, maximum and mold hours on the phone without a card reader. Add `DaysReply.parse` with the column layout of `SdLogCodec::parseDailyRow`. Add `DaysScreen` with one room selector and one bar for each day (temperature range, humidity range and mold hours). Send `DAYS <N>` over the BLE console only on a user action. Add a CSV share of the rows. Requires "Console command DAYS for the newest daily rows".
  - **Acceptance:** `DaysReplyTest` reads `tests/fixtures/days_reply_sample.txt` and gives the row count and the values of the fixture. An empty pair gives `null`. A wrong column count throws `ParseException`. `ERR NO_CARD` and `ERR NO_FILE` give a clear text. `gradle testDebugUnitTest` passes in `android/RlcdPhone`.
  - **Rank:** [Total: 28, Use: 7, Setup: 4, Harmony: 8, Safety: 9, Effort: M]

## Priority 3: Display and Logging (Score 22–27)

- [ ] Log gap report in the macOS archive view
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/LogGaps.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/ArchiveView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/LogGapsTests.swift`, `FEATURES.md`
  - **Goal:** Show the user where the climate log has missing hours, for example after an empty battery or a card fault. Add `LogGaps.find(rows:minGapMinutes:)` over the `ClimateLog` rows of one month: each step above 90 minutes gives one gap with start, end and missing hours. Show the gap list and the total coverage in percent in `ArchiveView` for the selected month. Read only the downloaded files.
  - **Acceptance:** `LogGapsTests` check that hourly rows give no gap, that a 3-hour step gives one gap with 2 missing hours, and that a step of exactly 90 minutes gives no gap. A month without rows gives coverage 0 and no crash. Rows out of time order are sorted first. `swift test` passes in `macos/RlcdCompanion`.
  - **Rank:** [Total: 27, Use: 4, Setup: 7, Harmony: 6, Safety: 10, Effort: S]

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
