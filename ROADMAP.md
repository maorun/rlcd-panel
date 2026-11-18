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
