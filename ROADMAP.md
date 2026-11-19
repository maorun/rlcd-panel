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

- [ ] Free MicroSD space in STATUS
  - Rank: [Total: 28, Use: 4, Setup: 8, Harmony: 8, Safety: 8, Effort: M]
  - Location: rlcd_panel/serial_console.h, rlcd_panel/power_manager.h, rlcd_panel/rlcd_panel.ino, tests/test_firmware_logic.cpp, tests/fixtures/status_reply_sample.txt, AGENTS.md, macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift, macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/ReplyTests.swift, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/StatusReply.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/SensorHealthScreen.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/StatusReplyTest.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/StatusReply.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/StatusReplyTest.kt
  - Goal: The diagnostics view of the panel shows `SD FREE: <N> MB`, but `STATUS` shows only the card status. `g_sd_free_mb` lives for one wake cycle only, so a console session cannot see a full card before the hourly write fails. Add `PersistentState::sd_free_mb` (`int32_t`, -1 is unknown, 4 bytes just before the checksum). Set it after each card access that reads the free space. Reject a value below -1. A power-on sets it to -1. Set `STATE_VERSION` to 68. Append ` free_mb=<N|-->` to the `SD` line of `STATUS`. Parse the field in the macOS `StatusReply` and in the shared Kotlin `StatusReply` (`null` for `--` and for a missing field, an error for a non-numeric value). Show the value in `DiagnosticsView` and in the Android `SensorHealthScreen` with a warning below 100 MB.
  - Acceptance test: In `tests/test_firmware_logic.cpp`, assert the `SD` line with a value and with `--`, the RTC round trip, the rejection of -2 and the new `CRC32` line of the status sample. Add the field to `tests/fixtures/status_reply_sample.txt` and calculate the `CRC32` line again. In `ReplyTests` and in both Kotlin `StatusReplyTest` classes, assert the value, `null` for `--` and an error for `free_mb=x`. Run `./scripts/check-protocol-sync.sh`, the host unit tests, `arduino-cli compile rlcd_panel`, `swift test` in `macos/RlcdCompanion` and `gradle testDebugUnitTest` in `android/RlcdPhone` and `wearos/RlcdWatch`.


## Priority 3: Display and Logging (Score 22–27)

- [ ] Readable event texts in the macOS event CSV export
  - Rank: [Total: 26, Use: 4, Setup: 5, Harmony: 7, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/CsvExport.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/TelemetryLogTests.swift
  - Goal: `CsvExport.events` writes only the columns `time,event,detail`. A user who opens `events.csv` in a spreadsheet sees raw codes, for example `SLOT_STALE,259`. Add the fourth column `text` with `EventText.describe(row)`. Quote a text with a comma or a quote character by the CSV rules. Keep the first three columns unchanged, so a tool that reads them still works. Do not send a console command for the export.
  - Acceptance test: In the CSV tests, assert the header `time,event,detail,text`, the text `Slot 3 back` for `SLOT_STALE,259`, the quoted form of a text with a comma and the raw text for an unknown event. Run `swift test` in `macos/RlcdCompanion`.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
