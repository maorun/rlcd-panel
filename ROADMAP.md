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



- [ ] Console command REJECTS with the reason of each rejected BLE packet
  - Rank: [Total: 34, Use: 6, Setup: 10, Harmony: 9, Safety: 9, Effort: M]
  - Location: rlcd_panel/switchbot_ble.h, rlcd_panel/power_manager.h, rlcd_panel/serial_console.h, rlcd_panel/rlcd_panel.ino, tests/test_firmware_logic.cpp, tests/fixtures/rejects_reply_sample.txt
  - Goal: The `BLE` line of `STATUS` gives only counters and the MAC of the last reject. The user cannot see why a sensor does not take a slot. Add `RejectLog` to `switchbot_ble.h`: 8 entries with MAC, `BleFormat`, reason code and a count that saturates at 255. Use the reason codes `FULL`, `UNASSIGNED`, `DISABLED` and `JUMP`. Merge each packet of a known MAC into its entry. Count each further MAC in `dropped`, which saturates at 255. Clear the log with `resetRejects()` at the start of `scanOnce()`. Store the log in `PersistentState::reject_log` just before the checksum (73 bytes: 9 bytes for each entry plus `dropped`). Reject a count of 0 in a used entry and a reason code above `JUMP`. Increase `STATE_VERSION` by one. Add the console command `REJECTS`. Reply `BEGIN REJECTS` and the header `mac,format,reason,count`. Then send one row for each entry and a `DROPPED,<N>` row for lost MACs. End with the `CRC32` line. Give `ERR BAD_ARG` for extra text. Read only the RTC copy. Allow the command on USB and on BLE at each `ble_console` level.
  - Acceptance test: In `tests/test_firmware_logic.cpp`, fill all slots and send a packet of a new MAC. Assert one `FULL` row. Set `slot_auto_assign` to 0 and assert one `UNASSIGNED` row. Send a large jump and call `endScan()`. Assert one `JUMP` row. Send 9 MACs and assert the `DROPPED,1` row. Assert that `resetRejects()` clears the log. Assert a serialize and deserialize round trip, and the rejection of a bad reason code. Compare the reply with `tests/fixtures/rejects_reply_sample.txt` and its CRC-32. Run the host unit tests. Run `arduino-cli compile rlcd_panel`.

## Priority 2: Configuration and Control (Score 28–33)

- [ ] Mute toggle and chime test in the Android app
  - Rank: [Total: 31, Use: 6, Setup: 7, Harmony: 9, Safety: 9, Effort: S]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/StatusReply.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ChimeCommand.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/SensorHealthScreen.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/StatusReplyTest.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/ChimeCommandTest.kt
  - Goal: Only the macOS app sends `MUTE` and `BEEP`. Parse `muted=<0|1>` of the `POWER` line into `StatusReply.muted` (`null` for a missing field). Add `ChimeCommand.mute(on)` and `ChimeCommand.beep(bit)` for bits 0 to 7. Add a mute switch and a `Test chime` menu with the alert names of `AlertTransitions` to `SensorHealthScreen`. Send `MUTE <0|1>`, then `STATUS`, through `BleConsoleClient.send`. Send `BEEP <N>` and show the reply `OK BEEP <N> <ms>`, `ERR MUTED` or `ERR PLAY_FAILED`. Show `ERR READ_ONLY` as `Set ble_console=2 to apply`. Send each command only on a user action.
  - Acceptance test: In `StatusReplyTest`, assert `muted` for `muted=0`, `muted=1` and a missing field with `tests/fixtures/status_reply_sample.txt`. In `ChimeCommandTest`, assert the lines `MUTE 1`, `MUTE 0` and `BEEP 7`. Assert that bit 8 and bit -1 give `null`. Assert the texts of `ERR MUTED`, `ERR PLAY_FAILED` and `ERR READ_ONLY`. Run `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone`.

- [ ] Panel config over the BLE console in the Wear OS app
  - Rank: [Total: 31, Use: 6, Setup: 7, Harmony: 9, Safety: 9, Effort: M]
  - Location: wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/console/BleConsoleClient.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/ConsoleWire.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ConsoleWire.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/console/BleConsoleClient.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/WatchDashboardScreen.kt, wearos/RlcdWatch/app/src/main/AndroidManifest.xml, scripts/check-protocol-sync.sh, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/ConsoleWireTest.kt
  - Goal: The watch has `ConfigCache` in `PanelConfig.kt`, but no code fills it. The watch shows only the names of the bundled asset. Move `ConsoleWire` into the `protocol` package of both apps. Add `ConsoleWire.kt` to the file list of `scripts/check-protocol-sync.sh`. Port `BleConsoleClient` to the watch. Keep one GATT connection for each command and writes of `max(1, MTU - 3)` bytes. Keep the `ReplyAssembler` CRC-32 check and the timeout. Add a `Read panel config` chip to `WatchDashboardScreen` that sends `CONFIG`. Store a good reply through `ConfigCache`. Keep the old cache on a bad CRC-32 or a timeout. Make the tile and the complication use `PanelConfig.tileName`. Request `BLUETOOTH_CONNECT` on SDK 31 and later.
  - Acceptance test: Run `./scripts/check-protocol-sync.sh` and assert exit 0 with `ConsoleWire.kt` in the list. Run `./scripts/check-protocol-sync.sh --self-test`. In `ConsoleWireTest`, assert the chunks for MTU 23 and MTU 185. Feed `tests/fixtures/config_reply_sample.txt` to `PanelConfig.parseConfigReply` and assert the names. Assert that a bad CRC-32 keeps the old cache. Run `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch`.

- [ ] Console command ACK to stop the chime reminder of an active alert
  - Rank: [Total: 29, Use: 7, Setup: 4, Harmony: 9, Safety: 9, Effort: M]
  - Location: rlcd_panel/audio_alert.h, rlcd_panel/power_manager.h, rlcd_panel/serial_console.h, rlcd_panel/sd_logger.h, rlcd_panel/rlcd_panel.ino, macos/RlcdCompanion/Sources/RlcdProtocol/EventText.swift, tests/test_firmware_logic.cpp, tests/fixtures/status_reply_sample.txt
  - Goal: `chime_repeat_h` repeats the chime of an alert that stays on. The user can stop it only with `MUTE`, which silences every alert. Add the console command `ACK <N>` for alert bit 0 to 7. Store the acknowledged bits in `PersistentState::alert_ack` (1 byte before the checksum). Increase `STATE_VERSION` by one. Make `AlertEvaluator::repeat` skip each acknowledged bit. Clear a bit when its alert falls, so the next rise chimes again. Reply `OK ACK <N>`. Give `ERR NOT_ACTIVE` for an alert that is off. Give `ERR BAD_ARG` for a missing bit, a bit above 7 or extra text. Treat `ACK` as a write command: `ERR READ_ONLY` on BLE with `ble_console` 1. Record the new `EventCode` 13 `ALERT_ACK` with the bit as detail. Set `EventRing::MAX_CODE` to 13. Append ` acked=<hex>` to the `POWER` line of `STATUS`. Add the text `Alert <name> acknowledged` to `EventText`.
  - Acceptance test: In `tests/test_firmware_logic.cpp`, set `ALERT_HUMIDITY` and `chime_repeat_h` 1. Send `ACK 1`. Advance the clock by 2 hours and assert that `repeat` plays no chime. Let the alert fall and rise. Assert one chime and a cleared ack bit. Assert `ERR NOT_ACTIVE`, `ERR BAD_ARG` and `ERR READ_ONLY`. Assert a serialize and deserialize round trip and the `ALERT_ACK` event. Update `tests/fixtures/status_reply_sample.txt` and its CRC-32. Assert that `StatusReplyTest` and `SensorHealthTests` still pass. Run the host unit tests, `arduino-cli compile rlcd_panel` and `cd macos/RlcdCompanion && swift test`.

## Priority 3: Display and Logging (Score 22–27)

- [ ] Ventilation advice in the Wear OS tile
  - Rank: [Total: 27, Use: 7, Setup: 2, Harmony: 8, Safety: 10, Effort: S]
  - Location: wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/tile/ClimateTileService.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/TelemetryCache.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/ventilation/VentilationGuide.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/tile/ClimateTileModelTest.kt
  - Goal: Only `MainActivity` shows the ventilation advice. The user must open the app to see it. Store the outdoor source of `MainActivity` in `TelemetryCache`. Store the tile index of a sensor slot, or the last Open-Meteo reading and its time. Make `ClimateTileModel.build` calculate `VentilationGuide.analysiereLueftung` for the focused room. Show the `titel` of the advice as one line below the climate line. Show no advice for a stale frame or a stale outdoor slot. Show no advice when the outdoor tile is the focused room. Show no advice for an Open-Meteo reading older than 1 hour. Do not start a BLE scan or a network request in the tile.
  - Acceptance test: In `ClimateTileModelTest`, build the model from a fixture frame and a fresh outdoor tile. Assert the advice line. Assert no advice line for the same tile as outdoor source. Assert no advice line for an Open-Meteo reading of 61 minutes. Assert no advice line for a frame older than 35 minutes. Run `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch`.

- [ ] Monthly alert statistics from the event files in the macOS app
  - Rank: [Total: 27, Use: 6, Setup: 4, Harmony: 7, Safety: 10, Effort: M]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/AlertStats.swift, macos/RlcdCompanion/Sources/RlcdCompanion/AlertStatsView.swift, macos/RlcdCompanion/Sources/RlcdCompanion/ArchiveView.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/AlertStatsTests.swift
  - Goal: `AlertTimeline` shows only the 16 events of the RTC ring or one file. The user cannot see how often an alert comes back over months. Add `AlertStats.load(from:)`. Read each `events_v1_YYYY.csv` of the archive folder with `EventsReply.parse(file:)`. Build the intervals with `AlertTimeline.build(rows:now:)`. Split an interval at each month boundary. Give the count of rises and the total hours for each alert and each month. Count an interval with `startUnknown` apart. Skip a file with a bad name or a bad line, and list it in `skippedFiles`. Show the result as a Swift Charts bar chart from a `Show alert statistics` button in `ArchiveView`. Send no console command.
  - Acceptance test: In `AlertStatsTests`, write two event files to a temporary folder. Assert the counts and hours for each month. Assert that an interval from 31 January 23:00 to 1 February 01:00 gives 1 hour in each month. Assert the `startUnknown` count after an `EVENTS_DROPPED` row. Assert that a corrupt file goes to `skippedFiles` and the other file still counts. Run `cd macos/RlcdCompanion && swift test && swift build -c release`.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
