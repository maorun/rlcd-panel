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

- [ ] Two-point salt calibration on the Wear OS watch
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/TwoPointCalibration.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SaltScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/TwoPointCalibrationTest.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/TwoPointCalibration.kt`, `scripts/check-protocol-sync.sh`, `FEATURES.md`
  - **Goal:** Let the user calibrate the humidity gain and offset of one sensor on the watch. Copy the phone `TwoPointCalibration.kt` into the watch protocol package. Add `TwoPointCalibration.kt` to the `FILES` list of `scripts/check-protocol-sync.sh`. Add a `NaCl` button and a `MgCl2` button to the salt screen. Send `SALT N` and `SALT N MGCL2` through the shared `SaltCommand`. After both replies, show the gain and the offset. Apply them through `CalibrationBatch` with the `rh_gain` or `slotN_rh_gain` key and the offset key. Show an error text and send no `SET` for a gain outside 0.90 to 1.10.
  - **Acceptance:** A unit test calculates gain and offset from one NaCl reply and one MgCl2 reply. A unit test checks the `SET` lines for slot 3. A unit test checks that a gain of 1.15 gives the error text and no `SET` line. A unit test checks that one reply only gives no result. `scripts/check-protocol-sync.sh` and `scripts/check-protocol-sync.sh --self-test` exit with 0. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` and `gradle testDebugUnitTest` in `android/RlcdPhone` pass.
  - **Rank:** [Total: 34, Use: 5, Setup: 10, Harmony: 10, Safety: 9, Effort: M]


## Priority 2: Configuration and Control (Score 28–33)

- [ ] Jump guard advice in the macOS diagnostics view
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/JumpAdvice.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/JumpAdviceTests.swift`, `FEATURES.md`
  - **Goal:** Tell the user when the jump guard drops many good readings of a sensor in the sun or near a heater. Add `JumpAdvice.evaluate(events:config:now:)`. Count the `JUMP_DROP` rows of each slot in the newest 24 hours of the `EVENTS` reply. Give one advice for each slot with 3 or more drops. Propose `jump_limit_c=<active value + 2.0>`, clamped to 20.0. Give no advice for `jump_limit_c=0`. Show the advice with the slot name in `DiagnosticsView`. Apply the proposal through `ConfigBatch` after a user action only.
  - **Acceptance:** A unit test checks one advice for 3 drops of slot 2 within 24 hours. A unit test checks no advice for 2 drops and for drops older than 24 hours. A unit test checks the proposal `jump_limit_c=10.0` from 8.0 and the clamp at 20.0. A unit test checks no advice for `jump_limit_c=0`. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.
  - **Rank:** [Total: 31, Use: 5, Setup: 9, Harmony: 8, Safety: 9, Effort: S]

- [ ] RTC trim action on the Wear OS watch
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/RtcTrimReply.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/RtcTrimReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/SensorHealthScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/RtcTrimReplyTest.kt`, `scripts/check-protocol-sync.sh`, `FEATURES.md`
  - **Goal:** Let the user correct a fast or slow panel clock from the watch. The watch parses `RtcState.canTrim`, but it has no action. Move the reply text of the phone `RTC TRIM` action into a shared `RtcTrimReply.kt` in both protocol packages. Add `RtcTrimReply.kt` to the `FILES` list of `scripts/check-protocol-sync.sh`. Show a `RTC trimmen` chip in the watch sensor health screen while `canTrim` is true. Send `RTC TRIM` after a tap, then `STATUS` after an `OK`. Show the old and the new trim value, or the text of `ERR NO_DRIFT`, `ERR WRITE_FAILED` and `ERR READ_ONLY`. Do not repeat a failed command.
  - **Acceptance:** A unit test parses `OK RTC TRIM 2 -1` to the old and the new value. A unit test checks the text of `ERR NO_DRIFT` and `ERR READ_ONLY`. A unit test checks that a bad reply line gives no value. `scripts/check-protocol-sync.sh` exits with 0. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` and `gradle testDebugUnitTest` in `android/RlcdPhone` pass.
  - **Rank:** [Total: 29, Use: 3, Setup: 9, Harmony: 8, Safety: 9, Effort: S]


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
