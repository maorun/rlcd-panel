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

- [ ] Rejected packet list in the macOS diagnostics report
  - Rank: [Total: 30, Use: 4, Setup: 8, Harmony: 8, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/DiagnosticsReport.swift, macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/DiagnosticsReportTests.swift, tests/fixtures/rejects_reply_sample.txt
  - Goal: The diagnostics report on the clipboard holds `VERSION`, `STATUS`, `CONFIG` and `EVENTS`. It does not hold the `REJECTS` list, so a fault report about a missing sensor has no reason for the rejected packets. Add `.rejects` to `DiagnosticsReport.commands` after `.events`. In the `REJECTS` section, replace the MAC of each row with `<hidden>` unless `includeNames` is true. Keep the format, the reason, the count and the `DROPPED,<N>` row. A failed `REJECTS` reply gives the `ERROR <reason>` line and the other sections stay.
  - Acceptance test: In `DiagnosticsReportTests`, build a report from `tests/fixtures/rejects_reply_sample.txt`. Assert the `## REJECTS` header after `## EVENTS`, no MAC text with `includeNames` false, the MAC text with `includeNames` true, no `CRC32` line, and an `ERROR` line for a failed section. Run `swift test` and `swift build -c release` in `macos/RlcdCompanion`.

- [ ] Free MicroSD space in the Wear OS sensor health screen
  - Rank: [Total: 30, Use: 4, Setup: 7, Harmony: 9, Safety: 10, Effort: S]
  - Location: wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/SensorHealthTextTest.kt
  - Goal: The Wear OS `StatusReply` parses `sdFreeMb`, but `SensorHealthScreen` does not show it. The Android app and the macOS app show the free space and a warning below 100 MB. A full card stops the hourly log, and the watch user does not see it. Add `SensorHealthText.sdFree(mb)` with the text `SD frei <N> MB` or `SD frei --`, and `SensorHealthText.isSdLow(mb)` with the limit `SD_LOW_MB` 100, the same values as the Android `SensorHealthText`. Show the line below the NTP line, with the prefix `⚠ ` below the limit.
  - Acceptance test: In the new `SensorHealthTextTest`, assert `SD frei 14820 MB`, `SD frei --` for null, `isSdLow(99)` true, `isSdLow(100)` false and `isSdLow(null)` false. Run `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch`.

- [ ] Event filter in the macOS event list
  - Rank: [Total: 29, Use: 6, Setup: 6, Harmony: 7, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/EventFilter.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/EventFilterTests.swift, macos/RlcdCompanion/Sources/RlcdCompanion/Views.swift, macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift
  - Goal: The event list of the macOS app shows all events of the ring and of the `events_v1_YYYY.csv` files in one list. With 16 events in the ring and many events in a year file, the user cannot find the slot or power events quickly. Add `EventFilter` with the categories `alerts` (`ALERT_RISE`, `ALERT_FALL`, `ALERT_ACK`, `BATTERY_LOW_SET`, `BATTERY_LOW_CLEAR`, `EMPTY_PROTECTION`), `sensors` (`SLOT_ASSIGN`, `SLOT_RELEASE`, `SLOT_STALE`), `power` (`RESET`, `POWER_SOURCE`, `SD_FAULT`), `config` (`CONFIG_LOAD`, `CONFIG_SET`, `NTP_SYNC`, `MUTE_CHANGE`) and `other` for each other event name. `EventFilter.apply(rows, categories)` keeps the order. An empty set gives all rows. Add a `Picker` above the event list in `Views.swift`. Keep `CsvExport.events` on the filtered rows.
  - Acceptance test: In `EventFilterTests`, read `tests/fixtures/events_reply_sample.txt` and assert the row count of each category, `other` for `FUTURE_EVENT`, all rows for an empty set and the unchanged order. Run `swift test` and `swift build -c release` in `macos/RlcdCompanion`.

- [ ] Event log CSV share in the Android app
  - Rank: [Total: 28, Use: 5, Setup: 6, Harmony: 8, Safety: 9, Effort: S]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/EventCsv.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/EventCsvTest.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/EventLogScreen.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt
  - Goal: The Android event log screen shows the event ring, but the user cannot save it or send it with a fault report. The macOS app exports the events as CSV with a readable text column. Add `EventCsv.format(rows)` with the header `time,event,detail,text` and one row for each `EventRow`, oldest first. The `text` column holds `EventText.describe(row)`. Quote a field with a comma, a quote or a line break, and double each inner quote, the same rules as `CsvExport.swift`. Add a `Share CSV` button to `EventLogScreen` while rows exist. Send the text with `Intent.ACTION_SEND` and `text/plain`, the same way as `shareHistoryCsv`.
  - Acceptance test: In `EventCsvTest`, read `tests/fixtures/events_reply_sample.txt` and assert the header, the row count, the text of one `SLOT_STALE` row, a quoted field for a text with a comma and only a header for an empty list. Run `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone`.

## Priority 3: Display and Logging (Score 22–27)

- [ ] Display refresh skip state in STATUS
  - Rank: [Total: 27, Use: 3, Setup: 7, Harmony: 7, Safety: 10, Effort: S]
  - Location: rlcd_panel/serial_console.h, rlcd_panel/rlcd_panel.ino, tests/test_firmware_logic.cpp, tests/fixtures/status_reply_sample.txt, macos/RlcdCompanion/Sources/RlcdProtocol/Diagnostics.swift, macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/DiagnosticsTests.swift, AGENTS.md
  - Goal: `FrameRefresh` skips the panel flush for an unchanged frame, but no console reply shows the skip state. When the panel shows an old image, the user cannot see if the firmware skipped the flush or if the panel failed. Add the `STATUS` line `FRAME crc=<8 hex digits|--> skips=<N>` after the `MEMORY` line and before `CRC32` (`SerialStatusSnapshot::frame`, RTC copy of `PersistentState::frame`; `--` for CRC 0). Do not change the RTC layout or `STATE_VERSION`. Add `FrameMetrics.parse` to `Diagnostics.swift` (`nil` for a missing line or `--`, `ParseError.badLine` for a bad field). Show the line in `DiagnosticsView`.
  - Acceptance test: In `tests/test_firmware_logic.cpp`, assert `FRAME crc=-- skips=0` for a new state and `FRAME crc=1a2b3c4d skips=12` for a set state, and that the line comes before `CRC32`. Add the line to `tests/fixtures/status_reply_sample.txt` and calculate the `CRC32` line again. In `DiagnosticsTests`, assert the parsed values, `nil` for a missing line and `ParseError.badLine` for `skips=x`. In the Android and Wear OS `StatusReplyTest`, assert that the fixture still parses. Run the host unit tests, `arduino-cli compile rlcd_panel`, `swift test` in `macos/RlcdCompanion` and `gradle testDebugUnitTest` in `android/RlcdPhone` and `wearos/RlcdWatch`.

- [ ] Chime test in the Wear OS sensor health screen
  - Rank: [Total: 26, Use: 4, Setup: 6, Harmony: 8, Safety: 8, Effort: S]
  - Location: wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/ChimeCommand.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/ChimeCommandTest.kt
  - Goal: The Wear OS `ChimeCommand` has `beep(bit)` and the reply texts, but no screen sends `BEEP`. The user cannot test the panel speaker from the watch, for example after a change of `chime_volume`. Add a `Test chime` chip below the mute chip of `SensorHealthScreen`. Add a selector with the 8 names of `ChimeCommand.ALERT_NAMES`. A tap sends `BEEP <bit>` through `BleConsoleClient.send`. Show `ChimeCommand.okText` for `OK BEEP` and `ChimeCommand.failureText` for an `ERR` reply. Send the command only on a user tap. Do not retry a failed command. Block a second tap while a command runs.
  - Acceptance test: In `ChimeCommandTest`, assert `BEEP 0` and `BEEP 7`, an exception for bit 8, the text of `OK BEEP 7 420` and the texts of `ERR MUTED` and `ERR READ_ONLY`. Run `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` and `./scripts/check-protocol-sync.sh`.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
