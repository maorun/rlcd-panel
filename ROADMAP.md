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

- [ ] Panel clock read and sync in the Android app
  - Rank: [Total: 31, Use: 6, Setup: 8, Harmony: 9, Safety: 8, Effort: M]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/PanelClock.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/SensorHealthScreen.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/PanelClockTest.kt
  - Goal: Only the macOS app reads and sets the panel clock (`TIME`, `TIME SET`). A user without a Mac cannot see or correct a wrong panel time, for example after a battery change without Wi-Fi. A wrong time moves the hourly CSV rows, the daily file and the event times. Add `PanelClock` in the Android protocol package as a port of `PanelClock.swift`: time zone `Europe/Berlin`, `format(epochMillis)` gives `YYYY-MM-DDTHH:MM:SS` or null for a year outside 2025 to 2099, `parseTimeReply(lines)` reads `TIME <YYYY-MM-DDTHH:MM:SS> valid=<0|1> osf=<0|1>` and throws `ParseException` for a bad line, `offsetSeconds(panel, phone)` gives the difference. Add a clock row to `SensorHealthScreen` with the buttons `Read clock` and `Sync clock`. `Read clock` sends `TIME` through `BleConsoleClient.send` and shows the offset, `valid` and `osf`. `Sync clock` sends `TIME SET <phone time>`, then `TIME`. Show `ERR READ_ONLY` as `Set ble_console=2 to apply`. Send the commands only on a user tap. Do not retry a failed command.
  - Acceptance test: In `PanelClockTest`, assert the text of a winter time and a summer time instant, null for the year 2100, the parse of `TIME 2026-11-19T10:00:00 valid=1 osf=0`, `ParseException` for `TIME x valid=1 osf=0` and the offset sign. Run `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone`.

- [ ] Event code for a change of the mute flag
  - Rank: [Total: 30, Use: 5, Setup: 7, Harmony: 9, Safety: 9, Effort: S]
  - Location: rlcd_panel/sd_logger.h, rlcd_panel/serial_console.h, rlcd_panel/rlcd_panel.ino, tests/test_firmware_logic.cpp, AGENTS.md, macos/RlcdCompanion/Sources/RlcdProtocol/EventText.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/EventTextTests.swift, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/EventText.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/EventTextTest.kt
  - Goal: Three paths change `PersistentState::muted`: a short `BOOT` press, the console command `MUTE` over USB and `MUTE` over BLE. The event ring does not record the change. The user cannot find out why a chime did not play, or which device muted the panel. Add `EventCode::MUTE_CHANGE` 16 and set `EventRing::MAX_CODE` to 16. Set the detail low byte to 1 for muted and 0 for unmuted. Set the high byte to the source: 0 `BOOT` press, 1 USB console, 2 BLE console. Record one event only when the flag changed. `MUTE 1` on a muted panel records nothing. Use the RTC time of the command, as the `ACK` path does. Do not change the RTC layout or `STATE_VERSION`. Show the event as `Chime muted (<source>)` and `Chime on (<source>)` in the macOS and the Android `EventText`, with the source texts `BOOT`, `USB` and `BLE`.
  - Acceptance test: In `tests/test_firmware_logic.cpp`, assert one event with detail `0x0101` for `MUTE 1` over USB, one event with detail `0x0200` for `MUTE 0` over BLE and no event for an equal flag. Assert that the RTC ring check accepts code 16 and rejects code 17. In `EventTextTests` and `EventTextTest`, assert the texts of `0x0001`, `0x0101`, `0x0200` and the raw text of `0x0302`. Run the host unit tests, `swift test` in `macos/RlcdCompanion` and `gradle testDebugUnitTest` in `android/RlcdPhone`.

- [ ] Event log over the BLE console in the Wear OS app
  - Rank: [Total: 30, Use: 5, Setup: 6, Harmony: 10, Safety: 9, Effort: M]
  - Location: wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/EventsReply.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/EventText.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/EventLogScreen.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/EventTextTest.kt, scripts/check-protocol-sync.sh, tests/fixtures/events_reply_sample.txt
  - Goal: The watch shows alerts and the sensor health, but not the event ring. After a silence notification the user cannot see a reset, a lost slot or a power change on the wrist. Copy `EventsReply.kt` and `EventText.kt` of the Android app into the Wear OS protocol package. Add both names to `FILES` of `scripts/check-protocol-sync.sh`. Add `EventLogScreen` with a `ScalingLazyColumn` of the newest 8 rows, newest first, with the time as `HH:mm` and the text of `EventText.describe`. Open it from a button of `SensorHealthScreen`. Send `EVENTS` through `BleConsoleClient.send` only on a user tap. Show a failed CRC-32 or a timeout as one error text. Do not retry the command.
  - Acceptance test: Run `./scripts/check-protocol-sync.sh` and assert exit 0. In the Wear OS `EventTextTest`, read `tests/fixtures/events_reply_sample.txt` and assert the texts of `RESET` 4, `SLOT_STALE` 259 and `POWER_SOURCE` 1. Assert that the screen model keeps the newest 8 rows in the newest-first order. Run `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch`.

- [ ] Panel silence notification in the macOS app
  - Rank: [Total: 29, Use: 7, Setup: 4, Harmony: 8, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/SilenceWatch.swift, macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/SilenceWatchTests.swift
  - Goal: The macOS menu bar shows `--` for a frame older than the stale limit, but the app sends no notice when the panel stops to send, for example with an empty battery or a crash. The Wear OS app has `SilenceWatch` for this case. Port `SilenceWatch.kt` to `SilenceWatch` in `RlcdProtocol` as a pure state machine with an injected clock and the same 35 min limit. It reports `silent` once when the last frame is older than the limit and `back` once at the next frame. Without a frame since the app start it reports nothing. A clock that runs back gives no report. `AppModel` checks it at the existing 30 s tick and posts one `UserNotifications` notice `Panel silent since <HH:mm>` for `silent` and removes it for `back`. Post the notice only when the user enabled the notifications.
  - Acceptance test: In `SilenceWatchTests`, assert `silent` once at 35 min plus 1 s, no second `silent` at 60 min, `back` once at the next frame, no report without a first frame and no report for a clock that runs back. Run `swift test` and `swift build -c release` in `macos/RlcdCompanion`.

- [ ] Panel clock offset in the Wear OS app
  - Rank: [Total: 29, Use: 4, Setup: 6, Harmony: 9, Safety: 10, Effort: S]
  - Location: wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/PanelClock.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/PanelClockTest.kt, scripts/check-protocol-sync.sh
  - Goal: The watch cannot show if the panel clock is wrong. Copy `PanelClock.kt` of the Android app into the Wear OS protocol package and add `PanelClock.kt` to `FILES` of `scripts/check-protocol-sync.sh`. Add one `Read clock` button to `SensorHealthScreen`. A tap sends `TIME` through `BleConsoleClient.send` and shows the offset to the watch time in seconds. Show a warning symbol for an offset above 120 s, for `valid=0` and for `osf=1`. Do not send `TIME SET` from the watch. Do not retry a failed command.
  - Acceptance test: Run `./scripts/check-protocol-sync.sh` and assert exit 0. In the Wear OS `PanelClockTest`, assert the parse of a `TIME` line, the offset and the warning for 121 s, `valid=0` and `osf=1`, and no warning for 120 s. Run `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch`.

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
