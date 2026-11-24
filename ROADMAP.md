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

- [ ] Structured config editor in the Android app
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ConfigSchema.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/ConfigEditorScreen.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/ConfigSchemaTest.kt`, `FEATURES.md`
  - **Goal:** Let the user change each `config.ini` key from the phone, not only the alert limits and the slots. Add `ConfigSchema.kt` with one entry for each key of `DeviceConfigParser::KEYS` (type, range, default, unit, description), the same data as the macOS `ConfigSchema.entries`. Add `validate(key, value)`, `diff` and `batch` (fixed schema order, `ble_console` last). Add `ConfigEditorScreen` with one field for each key, grouped like the macOS `ConfigEditorView`. Read the values with `CONFIG`. Send only the changed keys with `SET` over the BLE console, stop at the first `ERR` line, then send `CONFIG` again. Show `ERR READ_ONLY` as a clear text for `ble_console` 1. Refuse a `SET` line above the 40-byte limit before the send.
  - **Acceptance:** `ConfigSchemaTest` reads `docs/config.example.ini` and finds each template key once in the schema, with the template default. A test compares the ranges with the macOS `ConfigSchema` values for 5 sample keys. A value outside the range gives an error text and no command. A test checks that `batch` puts `ble_console` last and keeps the schema order. A test checks that the first `ERR` reply stops the batch. `gradle testDebugUnitTest` passes in `android/RlcdPhone`.
  - **Rank:** [Total: 34, Use: 7, Setup: 10, Harmony: 9, Safety: 8, Effort: L]

## Priority 2: Configuration and Control (Score 28–33)

- [ ] Weak signal badge in the climate tiles
  - **Location:** `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`, `scripts/generate-display-preview.sh`, `tests/test_firmware_logic.cpp`, `docs/display_preview_weak_signal.png`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Show on the panel which sensor has a weak BLE signal, so the user can move the sensor or the panel. Draw the inverted `R` badge beside the `V`, `D`, `W` and `C` badges in each tile style when the bit of the slot is set in `PersistentState::reception_low`. Use the RTC copy only. Do not draw the badge on the onboard tile. Keep the tile text, the RTC layout and `STATE_VERSION`. Add one preview scene with one weak slot.
  - **Acceptance:** A host test renders a tile with the bit set and finds the badge pixels at the expected position in STANDARD, LARGE and COMPACT style. A host test with mask 0 gives the same framebuffer CRC-32 as before the change. A host test with a weak slot that is also stale keeps the `STALE` badge readable. `./scripts/generate-display-preview.sh` writes `docs/display_preview_weak_signal.png`. The host unit tests pass.
  - **Rank:** [Total: 31, Use: 7, Setup: 8, Harmony: 7, Safety: 9, Effort: M]
- [ ] Low reception mask in the macOS diagnostics view
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/Diagnostics.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/DiagnosticsReport.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/DiagnosticsTests.swift`, `FEATURES.md`
  - **Goal:** Show on the Mac which slots have a weak BLE signal. Parse the field `rxlow=` of the `STATUS` line `LATCH` into `LatchMetrics.receptionLow` (`Int?`, bits 0 to 9 for slots 1 to 10). Include it in `hasBadges`. Show a weak signal symbol beside each flagged slot in the `Sensor health` rows of `DiagnosticsView`. Add the mask to the diagnostics report.
  - **Acceptance:** `DiagnosticsTests` parse `tests/fixtures/status_reply_sample.txt` and give the `rxlow` value of the fixture. A line without the field gives `nil`. A bad hex value throws `ParseError.badLine`. A test checks that a mask with only `rxlow` bits sets `hasBadges`. `swift test` passes in `macos/RlcdCompanion`.
  - **Rank:** [Total: 30, Use: 5, Setup: 8, Harmony: 7, Safety: 10, Effort: S]
- [ ] Console command DAYS for the newest daily rows
  - **Location:** `rlcd_panel/serial_console.h`, `rlcd_panel/sd_logger.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `tests/fixtures/days_reply_sample.txt`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Give the companion apps a long-term history over BLE, where `DUMP` is not allowed. Add `DAYS <N>` with N 1 to 31, no leading zero. Read the newest N valid rows of the current `daily_v5_YYYY.csv` and, with fewer rows, of the previous year file, each from the last 16384 bytes through `Storage::readTail`. Reply `BEGIN DAYS <n>`, the `daily_v5` header, the rows oldest first and the `CRC32` line. Mount and unmount the card in the command. Reply `ERR NO_CARD`, `ERR NO_FILE`, `ERR READ_FAILED` and `ERR BAD_ARG` with the same rules as `TAIL`. Run on USB and BLE at each `ble_console` level. Keep the RTC layout and `STATE_VERSION`.
  - **Acceptance:** A host test with a fake storage of 5 rows and `DAYS 3` gives the 3 newest rows oldest first and a correct `CRC32` line. A host test with 2 rows in the current year and 3 rows in the previous year gives 5 rows across the year change. A partial first line and a bad row count as no row. `DAYS 0`, `DAYS 32`, `DAYS 03` and `DAYS 3 x` give `ERR BAD_ARG`. The new fixture parses with `SdLogCodec::parseDailyRow`. The host unit tests pass.
  - **Rank:** [Total: 29, Use: 7, Setup: 5, Harmony: 8, Safety: 9, Effort: M]
- [ ] Open window notification on the Wear OS watch
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/WindowWatch.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/WindowWatchTest.kt`, `scripts/check-protocol-sync.sh`, `FEATURES.md`
  - **Goal:** Tell the user on the wrist that a window is open, so the user can close it in time. Copy `WindowWatch.kt` from `android/RlcdPhone` without a change except the `package` line. Add it to `scripts/check-protocol-sync.sh`. Feed it the `window` mask after each `STATUS` read. Show one notification with a short vibration for each rising tile bit, with the tile name from `PanelConfig.tileName`.
  - **Acceptance:** `WindowWatchTest` checks that the first read gives no tile, that a rising bit gives its tile, and that a falling bit and a `null` mask give no tile. `scripts/check-protocol-sync.sh` exits 0, and `--self-test` passes. `gradle testDebugUnitTest assembleDebug` passes in `wearos/RlcdWatch`.
  - **Rank:** [Total: 28, Use: 7, Setup: 3, Harmony: 8, Safety: 10, Effort: S]
- [ ] Daily history screen over the BLE console in the Android app
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/DaysReply.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/DaysScreen.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/DaysReplyTest.kt`, `FEATURES.md`
  - **Goal:** Show up to 31 days of daily minimum, maximum and mold hours on the phone without a card reader. Add `DaysReply.parse` with the column layout of `SdLogCodec::parseDailyRow`. Add `DaysScreen` with one room selector and one bar for each day (temperature range, humidity range and mold hours). Send `DAYS <N>` over the BLE console only on a user action. Add a CSV share of the rows. Requires "Console command DAYS for the newest daily rows".
  - **Acceptance:** `DaysReplyTest` reads `tests/fixtures/days_reply_sample.txt` and gives the row count and the values of the fixture. An empty pair gives `null`. A wrong column count throws `ParseException`. `ERR NO_CARD` and `ERR NO_FILE` give a clear text. `gradle testDebugUnitTest` passes in `android/RlcdPhone`.
  - **Rank:** [Total: 28, Use: 7, Setup: 4, Harmony: 8, Safety: 9, Effort: M]

## Priority 3: Display and Logging (Score 22–27)

- [ ] Weak signal notification on the Wear OS watch
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/StatusReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/ReceptionWatch.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/ReceptionWatchTest.kt`, `FEATURES.md`
  - **Goal:** Tell the user on the wrist that a sensor has a weak BLE signal. Parse the field `rxlow=` of the `STATUS` line `LATCH` (`null` for older firmware). Add `ReceptionWatch` with the rules of `WindowWatch` for bits 0 to 9 (slots 1 to 10). Show one notification for each rising bit with the slot name. Show a weak signal mark beside each flagged slot in `SensorHealthScreen`.
  - **Acceptance:** `StatusReplyTest` reads `tests/fixtures/status_reply_sample.txt` and gives the `rxlow` value of the fixture. A missing field gives `null`, a bad hex value throws `ParseException`. `ReceptionWatchTest` checks that the first read gives no slot, that a rising bit gives its slot, and that a falling bit gives no slot. `gradle testDebugUnitTest assembleDebug` passes in `wearos/RlcdWatch`.
  - **Rank:** [Total: 27, Use: 5, Setup: 6, Harmony: 7, Safety: 9, Effort: S]
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
