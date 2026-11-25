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

- [ ] Dry hours columns in the daily log
  - **Location:** `rlcd_panel/sd_logger.h`, `rlcd_panel/sparkline.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `tests/fixtures/days_reply_sample.txt`, `scripts/dump-logs.sh`, `macos/RlcdCompanion/Sources/RlcdProtocol/LogArchive.swift`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Keep the dry hours of each day on the card and restore them after a power-on. Write the daily row to `/sdcard/daily_v6_YYYY.csv`. Append the columns `dry_h_0` to `dry_h_10` after `mold_h_10` (94 columns). Write `HumidityHistory::countAtOrBelow` with `dry_alert_limit_rh`, and an empty field for limit 0 and for an empty or stale slot. Keep the `daily_v5_YYYY.csv` files unchanged. Let `LIST`, `DUMP`, `DAYS`, the retention and `SdLogger::restoreWeek` accept `daily_v6`, with the `daily_v5` file of the same year as fallback. Add `dry_h` to `WeekHistory::RestoreRow`, so the restore fills the dry hours. Add `daily_v6` to the name pattern of `scripts/dump-logs.sh` and `LogArchive`.
  - **Acceptance:** A host test checks the 94-column header and a row with 5 dry hours. A host test checks empty dry fields for limit 0. A host test restores the dry hours of 3 days from `daily_v6` rows. A host test restores a `daily_v5` row with dry gaps. A host test checks the `DAYS 2` reply with the `daily_v6` header and its `CRC32` line. `scripts/dump-logs.sh --self-test` accepts `daily_v6_2026.csv`. The host unit tests, `arduino-cli compile rlcd_panel` and `swift test` in `macos/RlcdCompanion` pass.
  - **Rank:** [Total: 26, Use: 6, Setup: 3, Harmony: 9, Safety: 8, Effort: M]

- [ ] Dry hours in the macOS daily charts
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/DailyLog.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DailyChartView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/DailyLogTests.swift`, `FEATURES.md`
  - **Goal:** Show in the macOS daily charts how many hours each room was too dry on each day. This item needs the firmware item "Dry hours columns in the daily log". Let `DailyLog` parse `daily_v6` with 11 sensors. Add `DailyRow.dryHours` (one `Int?` for each sensor, `[]` for older files). Show the dry hours as a second bar series beside the mold hours in `DailyChartView`.
  - **Acceptance:** A unit test parses a `daily_v6` row with 5 dry hours for sensor 2. A unit test checks `nil` for an empty field. A unit test checks `[]` for a `daily_v5` file. A unit test checks `ParseError.badLine` for a `daily_v6` row with 93 columns. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.
  - **Rank:** [Total: 25, Use: 6, Setup: 2, Harmony: 7, Safety: 10, Effort: S]

- [ ] Dry hours in the Android days screen
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/DaysReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/DaysReply.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/DaysScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/DaysReplyTest.kt`, `FEATURES.md`
  - **Goal:** Show on the phone how many hours each room was too dry on each day. This item needs the firmware item "Dry hours columns in the daily log". Let `DaysReply.parse` accept the `daily_v6` header and the `daily_v5` header. Add `dryHours` (one `Int?` for each sensor, empty for `daily_v5`). Copy the changed file into the watch protocol package. Show `Trocken <N> h` beside the mold hours of each room in the days screen.
  - **Acceptance:** A unit test parses the updated `tests/fixtures/days_reply_sample.txt` with dry hours. A unit test parses a `daily_v5` reply with empty `dryHours`. A unit test checks `null` for an empty field. A unit test checks `ParseException` for a row with 93 columns. `scripts/check-protocol-sync.sh` exits with 0. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` and `gradle testDebugUnitTest` in `wearos/RlcdWatch` pass.
  - **Rank:** [Total: 24, Use: 5, Setup: 2, Harmony: 7, Safety: 10, Effort: S]




## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
