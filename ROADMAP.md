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

## Priority 1: Sensor Setup and Calibration (Score 34–40)

## Priority 2: Configuration and Control (Score 28–33)

## Priority 3: Display and Logging (Score 22–27)

- [ ] Show the alert state and each tile in the macOS menu bar
  - Rank: [Total: 27, Use: 8, Setup: 3, Harmony: 6, Safety: 10, Effort: S]
  - Location: `macos/RlcdCompanion/Sources/RlcdCompanion/RlcdCompanionApp.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/MenuBarSummary.swift` (new), `macos/RlcdCompanion/Tests/RlcdProtocolTests/MenuBarSummaryTests.swift` (new)
  - Goal: The `MenuBarExtra` shows only the onboard text and the frame age. Add `MenuBarSummary` that builds one line for each valid tile of the last `TelemetryFrame` (name from `ConfigReply`, else `Tile <N>`; temperature in the `temp_unit` of the last `CONFIG`, humidity). Show an SF Symbol with an alert mark when the alert mask of the frame is not 0. Show a stale mark when the frame is older than 15 minutes. Read only the last frame. Send no console command.
  - Acceptance test: `MenuBarSummaryTests` assert one line per set bit of the valid tile mask, the `Tile 3` fallback name, the conversion to Fahrenheit with `temp_unit` F, the alert symbol for a mask of 0x04, and the stale state for a frame of 16 minutes. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.

- [ ] Log registry slots 4 and 5 in the climate CSV files
  - Rank: [Total: 24, Use: 6, Setup: 3, Harmony: 8, Safety: 7, Effort: M]
  - Location: `rlcd_panel/sd_logger.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `scripts/dump-logs.sh`, `tests/test_firmware_logic.cpp`, `macos/RlcdCompanion/Sources/RlcdProtocol/LogArchive.swift`, `AGENTS.md`
  - Goal: `LOG_SOURCE_COUNT` is 4. Thus the monthly and the daily CSV files have no data for slots 4 and 5. Set `LOG_SOURCE_COUNT` to 6. Add format version 5 with the file names `climate_v5_YYYYMM.csv` and `daily_v3_YYYY.csv`. Keep the old files unchanged. Make `LIST`, `DUMP`, the retention, `scripts/dump-logs.sh` and `LogArchive.isValidName` accept the new names. Give empty fields for an empty or stale slot. Keep the BLE telemetry frame unchanged.
  - Acceptance test: Host tests assert the column count of the v5 header and of one row, empty fields for an empty slot 5, that the retention deletes an old `climate_v5` file and never the current month, and that `DUMP climate_v5_202611.csv` parses. A Swift test asserts that `LogArchive.isValidName("climate_v5_202611.csv")` is true. The C++ host tests, `arduino-cli compile rlcd_panel`, `swift test` and `swift build -c release` pass.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components and firmware modules does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
