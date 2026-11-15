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

- [ ] Add a calibration assistant with the `CALIB` command to the macOS app
  - Rank: [Total: 35, Use: 7, Setup: 10, Harmony: 9, Safety: 9, Effort: M]
  - Location: `macos/RlcdCompanion/Sources/RlcdProtocol/Calibration.swift` (new), `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/CalibrationView.swift` (new), `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/CalibrationTests.swift` (new)
  - Goal: The firmware command `CALIB N` (0 onboard, 1 to 3 a slot) compares the 24-hour RTC histories with one reference sensor. It replies `REFERENCE <name>`, one `SENSOR` or `SKIP` line per other sensor, the `key=value` lines for `config.ini`, and a `CRC32` line. The app has no `CALIB` command. Add `ConsoleCommand.calib(Int)` (`nil` outside 0 to 3) and `CalibrationReply.parse(lines:)`. Parse `hours`, `temp_offset`, `rh_offset`, `temp_diff` and `rh_diff` as numbers. Throw `ParseError.badLine` for a bad number and `ParseError.missingLine` without the `REFERENCE` line. Accept only proposed keys that `ConfigSchema.validate(key:value:)` accepts. Show the sensors, the hours and the proposed offsets in `CalibrationView`. Let the user select the proposals. Send them through `ConfigBatch` (`SET` commands, then `CONFIG`). Send `CALIB` only on a user action. On the BLE link with `ble_console` 1, show the firmware error `ERR READ_ONLY` of the `SET` commands.
  - Acceptance test: `CalibrationTests` parse a recorded `CALIB 0` reply with two `SENSOR` lines and one `SKIP` line and assert each value. A reply without `REFERENCE` throws `missingLine`. A `temp_offset=nan` line throws `badLine`. A proposed key `slot1_temp_offset_c=9.0` (outside -5 to 5) is not in the batch. `ConsoleCommand.calib(4).line` is `nil` and `ConsoleCommand.calib(2).line` is `"CALIB 2"`. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass. The C++ host tests stay unchanged and pass.

## Priority 2: Configuration and Control (Score 28–33)

- [ ] Add the console command `MUTE` and a mute toggle in the macOS app
  - Rank: [Total: 32, Use: 8, Setup: 7, Harmony: 8, Safety: 9, Effort: S]
  - Location: `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`
  - Goal: Only a short `BOOT` press toggles `PersistentState::muted` now. Add `SerialCommandKind::MUTE`. `MUTE 0` and `MUTE 1` set the flag in `g_state` and reply `OK MUTE <0|1>`. `MUTE` without a digit, with a digit above 1 or with extra text gives `ERR BAD_ARG`. With `ble_console` 1 the command gives `ERR READ_ONLY`. The command needs no card. The next save of the wake cycle writes the flag to RTC memory with the existing audio trailer. Keep `STATE_VERSION` unchanged. Add `ConsoleCommand.mute(Bool)`. Show a toggle in `DiagnosticsView` that reads `muted=` from the `POWER` line of `STATUS`, sends `MUTE`, and then sends `STATUS` again.
  - Acceptance test: Host tests assert that `SerialConsole::parse("MUTE 1")` gives `MUTE` with value 1, that `"MUTE 2"`, `"MUTE"` and `"MUTE 1 x"` give `BAD_ARG`, and that the BLE bridge with level 1 replies `ERR READ_ONLY`. A Swift test asserts `ConsoleCommand.mute(true).line == "MUTE 1"`. `c++ -std=c++17 -Wall -Wextra -Werror tests/test_firmware_logic.cpp -o test_runner && ./test_runner`, `arduino-cli compile rlcd_panel`, `swift test` and `swift build -c release` pass.

- [ ] Back up and restore `config.ini` in the macOS app
  - Rank: [Total: 31, Use: 5, Setup: 10, Harmony: 8, Safety: 8, Effort: S]
  - Location: `macos/RlcdCompanion/Sources/RlcdProtocol/ConfigBackup.swift` (new), `macos/RlcdCompanion/Sources/RlcdCompanion/ConfigEditorView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/ConfigBackupTests.swift` (new)
  - Goal: Save the values of the last `ConfigReply` as a `config.ini` text file through `NSSavePanel`. Write the keys in `ConfigSchema` order, one `key=value` line each, with LF line ends and without CR characters. Load a backup file through `NSOpenPanel`. Parse it with the same rules as `docs/config.example.ini` (comment lines with `#` or `;`, blank lines, unknown keys ignored). Validate each value with `ConfigSchema.validate(key:value:)`. Show the rejected lines and the line numbers. Show the `ConfigSchema.diff` against the current values before the restore. Send only the changed keys through `ConfigBatch`. Refuse a file above 4096 bytes. Do not write Wi-Fi credentials.
  - Acceptance test: `ConfigBackupTests` assert that the output of a backup parses back to the same values, that the output has no CR character, and that a file above 4096 bytes is refused. A line `wake_interval_s=10` is a rejected line with its line number. A backup equal to the current values gives an empty batch. `docs/config.example.ini` parses without a rejected line. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.

- [ ] Add temperature and humidity offsets for slots 4 and 5
  - Rank: [Total: 30, Use: 6, Setup: 9, Harmony: 8, Safety: 7, Effort: M]
  - Location: `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `docs/config.example.ini`, `tests/test_firmware_logic.cpp`, `macos/RlcdCompanion/Sources/RlcdProtocol/ConfigSchema.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/ConfigSchemaTests.swift`, `AGENTS.md`
  - Goal: `DeviceConfig::OFFSET_SLOT_COUNT` is 3. Thus the sensors in slots 4 and 5 have no calibration offset. Add the keys `slot4_temp_offset_c`, `slot5_temp_offset_c` (-5 to 5 C) and `slot4_rh_offset_pct`, `slot5_rh_offset_pct` (-10 to 10 %RH). Append them to `DeviceConfigParser::KEYS` and increase `KEY_COUNT`. Set `OFFSET_SLOT_COUNT` to 5. Store the new values at the end of the config trailer of `PersistentState` and increase `STATE_VERSION`. Make `CALIB N` accept 1 to 5. Keep the CSV logs and the BLE telemetry frame unchanged. Add the keys to `docs/config.example.ini` and to `ConfigSchema.entries`.
  - Acceptance test: Host tests assert that `slot5_temp_offset_c=-1.5` gives -1.5 C on a slot 5 reading, that `slot4_rh_offset_pct=11` is a rejected line, that an RTC image of the old `STATE_VERSION` is rejected, and that `CALIB 5` parses. `testConfigExampleTemplateInSync` passes. `ConfigSchemaTests.rangesMatchFirmwareConstants` covers the four keys. `./scripts/check-config.sh tools/sample_config.ini`, the C++ host tests, `arduino-cli compile rlcd_panel`, `swift test` and `swift build -c release` pass.

- [ ] Show daily summary charts from the downloaded logs in the macOS app
  - Rank: [Total: 29, Use: 8, Setup: 3, Harmony: 8, Safety: 10, Effort: M]
  - Location: `macos/RlcdCompanion/Sources/RlcdProtocol/DailyLog.swift` (new), `macos/RlcdCompanion/Sources/RlcdCompanion/DailyChartView.swift` (new), `macos/RlcdCompanion/Sources/RlcdCompanion/ArchiveView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/DailyLogTests.swift` (new)
  - Goal: `ArchiveView` downloads `daily_v1_YYYY.csv` (27 columns) and `daily_v2_YYYY.csv` (30 columns) into a folder, but the app does not show them. Add `DailyLog.parse(text:)` for both formats. Use the header row to select the format. Give `nil` for an empty field. Throw `ParseError.badLine` for a row with a wrong column count. Read all daily files of the selected folder. Show one Swift Charts view per sensor with the daily minimum, mean and maximum of temperature and humidity. Show the daily noise peak for `daily_v2` rows. Read only local files. Send no console command.
  - Acceptance test: `DailyLogTests` parse one `daily_v1` and one `daily_v2` sample from `SdLogCodec` output and assert the values of one row. An empty field gives `nil`. A row with 29 columns throws `badLine`. A file with an unknown header throws `missingLine`. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.

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
