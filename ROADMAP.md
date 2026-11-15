# Roadmap

This file lists open work for `rlcd-panel`, the standalone Arduino CLI firmware for the **Waveshare ESP32-S3-RLCD-4.2** development board.
The device renders climate telemetry from onboard sensors and external SwitchBot thermo-hygrometers onto a 4.2-inch reflective LCD.
The list uses priorities. Priority 1 is the highest priority.
Do the work in priority order, unless the maintainer gives a different order.

## Rules for this file

- Write in ASD-STE100 Simplified Technical English.
- Add one entry for each open item. Do not give an entry a status. Each entry in this file is open.
- Remove an entry in the pull request that completes it. Describe finished work in `FEATURES.md`.
- Each entry names the code location, the goal, and the acceptance test.
- Each entry has a `Rank` line with the model, the total score, the four single scores, and the effort size. See "Rules for ranking".
- Put each entry in the priority that matches its total score. Order the entries inside a priority by total score, highest first. For equal scores, put the smaller effort first. Put a dependency above the entry that needs it.
- Priority 1 holds the most critical user-facing enhancements and core hardware integrations.
- Do not record review findings in this file. Name them in the review report and in the pull request description.

## Priority 2: Storage, and Configuration (Score 28–33)

## Priority 3: Score 22–27

- [ ] Local telemetry history and CSV export in the macOS app
  - Rank: [Total: 27, Use: 7, Setup: 5, Harmony: 6, Safety: 9, Effort: S]
  - Location: `macos/RlcdCompanion/Sources/RlcdProtocol/TelemetryLog.swift` (new), `macos/RlcdCompanion/Sources/RlcdProtocol/CsvExport.swift` (new), `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/Views.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/TelemetryLogTests.swift` (new)
  - Goal: Store each telemetry frame with a new sequence number in `TelemetryLog`: time, sequence, cell percentage, alert mask and the four tiles. The log keeps 7 days or a maximum of 20,000 rows, oldest out first. It writes a JSON file in `~/Library/Application Support/RlcdCompanion/` at most once a minute, through a temporary file and a rename. A corrupt file gives an empty log and no crash. The window shows a 7-day chart for each tile from the log. `CsvExport` writes the log, the last `HISTORY` rows and the last `EVENTS` rows as CSV with a header line, Celsius values and empty fields for missing values. The user selects the target file in a save panel. Thus the user keeps a longer history than the 24 hours of the panel.
  - Acceptance test: `swift test` in `macos/RlcdCompanion` passes. New tests add frames with a repeated sequence (one row), drop rows older than 7 days with a fake clock, cap the log at 20,000 rows, round-trip the JSON file, load a corrupt file as an empty log, and compare the CSV output of a fixed log, `HISTORY` rows and `EVENTS` rows with expected text. `swift build -c release` passes. The C++ host tests stay unchanged and pass.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components and firmware modules does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
