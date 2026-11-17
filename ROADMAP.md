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

- [ ] Sensor protocol source badges in companion apps
  - Rank: [Total: 33, Use: 8, Setup: 7, Harmony: 9, Safety: 9, Effort: M]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/SensorHealth.swift, macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift, macos/RlcdCompanion/Sources/RlcdCompanion/Views.swift, macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/StatusReply.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/PhoneDashboardScreen.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/WatchDashboardScreen.kt, macos/RlcdCompanion/Tests/RlcdProtocolTests/SensorHealthTests.swift, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/StatusReplyTest.kt
  - Goal: The panel firmware renders sensor protocol source badges (`[TP]`, `[SB]`, `[ATC]`, `[BTH]`, `[I2C]`) on dashboard tiles and stats rows. The console command `STATUS` reports `format=<SHTC3|WOIO|METER|BTHOME|ATC|THERMOPRO>` for each sensor, but companion apps do not show the source badge. Add source badge parsing and rendering to companion apps. In `macos/RlcdCompanion`, add `format: String?` to `SensorHealth`. Parse `format=<format>` from each `SENSOR` line in `StatusReply`. In `Views.swift` and `DiagnosticsView.swift`, render the source badge string beside each sensor name: `[I2C]` for SHTC3, `[SB]` for WOIO and METER, `[ATC]` for ATC, `[BTH]` for BTHOME, and `[TP]` for THERMOPRO. In `android/RlcdPhone` and `wearos/RlcdWatch`, add the protocol format to `StatusReply` and sensor models. Display the corresponding source badge (`[I2C]`, `[SB]`, `[ATC]`, `[BTH]`, `[TP]`) on tile headers and sensor detail cards.
  - Acceptance test: `SensorHealthTests` in macOS assert the parsed `format` for each known protocol string and `nil` for a missing field. Swift tests assert the badge strings `[I2C]`, `[SB]`, `[ATC]`, `[BTH]`, and `[TP]`. Android and Wear OS unit tests assert the format parsing and badge string mapping for all supported protocols. `swift test`, `swift build -c release`, and Android/Wear OS unit tests pass.

- [ ] Confirmation of a large reading jump before the registry takes it
  - Rank: [Total: 32, Use: 8, Setup: 7, Harmony: 8, Safety: 9, Effort: M]
  - Location: rlcd_panel/switchbot_ble.h, rlcd_panel/serial_console.h, rlcd_panel/rlcd_panel.ino, macos/RlcdCompanion/Sources/RlcdProtocol/Diagnostics.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/DiagnosticsTests.swift, tests/test_firmware_logic.cpp
  - Goal: One bad packet (a parser edge case, a forged frame of a nearby device or a sensor with a weak cell) writes a wrong value into the tile, the 24-hour history, the CSV log and the alerts. A sensor sends several packets in one 4 s scan. Add `JumpGuard` in `switchbot_ble.h`: when a slot has a reading younger than 10 minutes and a new packet of the same MAC differs by more than 8.0 C or more than 25 %RH, `upsert` keeps the packet as pending and does not change the slot. A second packet of the same MAC in the same scan that is within 1.0 C and 5 %RH of the pending packet confirms it, and the slot takes the newest packet. A packet near the stored reading drops the pending packet. The pending packet is not in RTC memory, so a scan end drops it. An empty slot, a stale slot and the first packet after a power-on are never held. Count each dropped pending packet in `BleRejectStats::jumps` (saturates at 65535, reset by `resetRejects()`). The console command `STATUS` appends ` jumps=<N>` to the `BLE` line. `Diagnostics.swift` parses the optional field.
  - Acceptance test: Host tests assert that a jump of 9.0 C is held and the slot keeps its value, that a confirming second packet sets the new value, that a packet near the old value drops the pending packet and increments `jumps`, that a jump of 7.9 C and of 24 %RH pass at once, that an empty slot and a reading older than 10 minutes take the first packet, that a scan end drops the pending packet, the counter saturation and the `STATUS` text. `DiagnosticsTests` assert `jumps` and `nil` for a missing field. `test_firmware_logic` and `swift test` pass.

- [ ] Current log file names in `scripts/dump-logs.sh`
  - Rank: [Total: 30, Use: 6, Setup: 7, Harmony: 8, Safety: 9, Effort: S]
  - Location: scripts/dump-logs.sh, scripts/test-dump-logs-names.sh, .github/workflows/ci.yml, AGENTS.md
  - Goal: The firmware writes `climate_v6_YYYYMM.csv` and `daily_v5_YYYY.csv`, and `LIST` reports them. The patterns `NAME`, `FILE_LINE` and `BEGIN_LINE` of `scripts/dump-logs.sh` accept only `climate_v2` to `climate_v5` and `daily_v1` to `daily_v4`, so the script skips the current files without an error. Put the three patterns into one shared name pattern that accepts `climate_v2` to `climate_v6`, `daily_v1` to `daily_v5` and `events_v1`, the same set as `LogArchive.swift`. Print one warning line `SKIP <name>` for each `FILE` line with an unknown name, so a later format change is visible. Add `--self-test`: the script checks a fixed list of good and bad names against the pattern without a serial port and exits 0 or 1. Add the self-test to the `Host tests` CI job. Update the `dump-logs.sh` text in `AGENTS.md`.
  - Acceptance test: `scripts/dump-logs.sh --self-test` exits 0 and accepts `climate_v6_202611.csv`, `daily_v5_2026.csv`, `climate_v2_202501.csv` and `events_v1_2026.csv`. It rejects `climate_v7_202611.csv`, `climate_v6_202613.csv`, `daily_v6_2026.csv`, `../climate_v6_202611.csv` and `climate_v6_202611.csv.tmp`. A changed pattern that drops `v6` makes the self-test exit 1. The CI job `Host tests` runs the self-test.

- [ ] Wear OS tile with the focused room and the active alerts
  - Rank: [Total: 28, Use: 9, Setup: 2, Harmony: 8, Safety: 9, Effort: M]
  - Location: wearos/RlcdWatch/app/build.gradle.kts, wearos/RlcdWatch/app/src/main/AndroidManifest.xml, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/tile/ClimateTileService.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/TelemetryCache.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/TelemetryCacheTest.kt
  - Goal: The watch shows the climate only while the app is open. Add a Wear OS tile (`androidx.wear.tiles` and `androidx.wear.protolayout`) that shows the name, the temperature and the humidity of the focused room, the panel cell percentage, the names of the active alerts and the age of the data. The tile does not scan BLE. `TelemetryCache` stores the payload bytes of the last valid frame (hex text) and its receive time in `SharedPreferences`; `MainActivity` writes it for each new sequence number. The tile reads the cache, decodes it with `TelemetryFrame.decodePayload`, and shows `--` and the text `Open the app` when the frame is older than 35 minutes (the freshness rule of `OutdoorResolver`), when the cache is empty, or when the decode fails. A tap on the tile opens `MainActivity`. The tile asks for a refresh every 15 minutes (`freshnessIntervalMillis`).
  - Acceptance test: `TelemetryCacheTest` asserts the round trip of a vector from `tests/fixtures/ble_telemetry_vectors.txt`, `null` for an empty cache, `null` for a bad hex text and for a bad CRC-8, the stale state at 35 minutes plus 1 s and the fresh state at 34 minutes, and the alert names of a frame with two alert bits. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.

## Priority 3: Display and Logging (Score 22–27)

- [ ] 24-hour telemetry history and CSV share in the Android app
  - Rank: [Total: 26, Use: 8, Setup: 2, Harmony: 7, Safety: 9, Effort: M]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/TelemetryHistory.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/HistoryScreen.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt, android/RlcdPhone/app/src/main/AndroidManifest.xml, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/TelemetryHistoryTest.kt
  - Goal: The phone app shows only the last frame. The macOS app has `TelemetryLog` and `CsvExport`. Add `TelemetryHistory`: one row for each frame with a new sequence number (receive time, sequence, cell percentage, alert mask, the tiles in Celsius); a frame with the sequence of the last row adds no row; the history keeps 7 days or 20,000 rows, oldest out first. `TelemetryHistoryStore` writes `telemetry.json` in `filesDir` at most once a minute through `telemetry.json.tmp` and a rename, and a missing or corrupt file gives an empty history. `HistoryScreen` draws a 24-hour temperature chart and humidity chart for each tile with Compose `Canvas` (gaps for missing values) and has a `Share CSV` button. The button sends the CSV text (header line, Celsius values, empty fields for missing values, the same columns as `CsvExport.swift`) with an `ACTION_SEND` intent. The history records only while the app scans, as the scanner does now.
  - Acceptance test: `TelemetryHistoryTest` asserts one row for two frames with the same sequence, the age limit of 7 days, the row limit of 20,000, a round trip through the JSON text, an empty history for a corrupt text, the CSV header and a row with an empty field for a stale tile, and the points of the 24-hour window. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` passes.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
