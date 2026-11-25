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

- [ ] Sensor drift notification and calibration shortcut in the macOS app
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/Diagnostics.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/DriftWatch.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/EventText.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/DriftWatchTests.swift`, `FEATURES.md`
  - **Goal:** Tell the user on the Mac which sensor drifts, and lead the user to the fix. Parse the field `drift=` of the `STATUS` line `LATCH` into `LatchMetrics.drift` (`Int?`, bit 0 onboard, bits 1 to 10 slots). Add `DriftWatch` with the rules of the other latch watches: the first read gives no tile, a rising bit gives its tile. Show one `UserNotifications` notification for each rising bit with the sensor name. Show a drift symbol beside each flagged sensor in the `Sensor health` rows and a button that opens `CalibrationView` and sends `SPREAD`. Add `EventText` entries `Sensor drift (<tile>)` and `Sensor drift gone (<tile>)` for `EventCode::SENSOR_DRIFT` 26. Requires "Sensor drift latch and event from the co-location median".
  - **Acceptance:** `DiagnosticsTests` read `tests/fixtures/status_reply_sample.txt` and give the `drift` value of the fixture. A line without the field gives `nil`, and a bad hex value throws `ParseError.badLine`. `DriftWatchTests` check that the first read gives no tile, that a rising bit gives its tile, and that a falling bit and a `nil` mask give no tile. `EventTextTests` check both texts. `swift test` passes in `macos/RlcdCompanion`.
  - **Rank:** [Total: 32, Use: 6, Setup: 9, Harmony: 8, Safety: 9, Effort: S]
- [ ] Sensor drift notification and calibration shortcut in the Android app
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/StatusReply.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/DriftWatch.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/EventText.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/SensorHealthScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/DriftWatchTest.kt`, `FEATURES.md`
  - **Goal:** Tell the user on the phone which sensor drifts, and open the calibration with one tap. Parse the field `drift=` of the `STATUS` line `LATCH` (`null` for older firmware). Add `DriftWatch` with the rules of `WindowWatch` for bits 0 to 10. Show one notification for each rising bit with the sensor name from `PanelConfig.tileName`. The notification tap opens `CalibrationScreen` and sends `SPREAD` over the BLE console. Show a drift mark beside each flagged sensor in `SensorHealthScreen`. Add the `EventText` entries for `EventCode::SENSOR_DRIFT` 26 with the category `sensors` in `EventFilter`. Requires "Sensor drift latch and event from the co-location median".
  - **Acceptance:** `StatusReplyTest` reads `tests/fixtures/status_reply_sample.txt` and gives the `drift` value of the fixture. A missing field gives `null`, and a bad hex value throws `ParseException`. `DriftWatchTest` checks that the first read gives no tile, that a rising bit gives its tile, and that a falling bit gives no tile. `EventTextTest` checks both texts and the filter category. `gradle testDebugUnitTest` passes in `android/RlcdPhone`.
  - **Rank:** [Total: 32, Use: 6, Setup: 9, Harmony: 8, Safety: 9, Effort: S]
- [ ] Console command CONFIG CHANGED for the keys that differ from the default
  - **Location:** `rlcd_panel/serial_console.h`, `rlcd_panel/device_config.h`, `tests/test_firmware_logic.cpp`, `tests/fixtures/config_changed_reply_sample.txt`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Show the user in one short reply which settings are not at the default, so a wrong setting is easy to find. Add `CONFIG CHANGED` (`SerialCommand::config_changed`). Compare each key of `DeviceConfigParser::KEYS` in the active `DeviceConfig` with its default and send one line `<key>=<value>` in the `CONFIG` format for each key that differs, in `KEYS` order. Then send `END CHANGED <N>` and the `CRC32` line. Use the RTC copy only, with no card access. Reply `ERR BAD_ARG` for extra text. Keep the `CONFIG` and `CONFIG <key>` replies. Run on USB and BLE at each `ble_console` level. Do not change the RTC layout or `STATE_VERSION`.
  - **Acceptance:** A host test with the default `DeviceConfig` gives `END CHANGED 0` and a correct `CRC32` line. A host test with three changed keys gives the three lines in `KEYS` order with the same text as `CONFIG`. An empty slot name and an empty MAC count as default. `CONFIG CHANGEDX` and `CONFIG CHANGED 1` give `ERR BAD_ARG`. The new fixture passes the CRC-32 check of the host test. The host unit tests pass.
  - **Rank:** [Total: 29, Use: 3, Setup: 9, Harmony: 7, Safety: 10, Effort: S]
- [ ] Changed settings view and reset to default in the Android config editor
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ConfigChangedReply.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ConfigSchema.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/ConfigEditorScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/ConfigChangedReplyTest.kt`, `FEATURES.md`
  - **Goal:** Let the user see and undo the changed settings on the phone. Add `ConfigChangedReply.parse` for the `CONFIG CHANGED` reply. Add a filter `Changed only` to `ConfigEditorScreen` that shows only the keys of the reply. Add one `Reset` action for each changed key and one `Reset all` action. Build the `SET` commands with the `ConfigSchema` default through `batch` (fixed schema order, `ble_console` last). Ask for a confirmation before the send. Send `CONFIG CHANGED` again after the batch. Requires "Console command CONFIG CHANGED for the keys that differ from the default".
  - **Acceptance:** `ConfigChangedReplyTest` reads `tests/fixtures/config_changed_reply_sample.txt` and gives the keys and values of the fixture. A count in `END CHANGED` that differs from the line count throws `ParseException`. An unknown key throws `ParseException`. A test checks that `Reset all` gives one `SET` command for each changed key with the schema default and puts `ble_console` last. `gradle testDebugUnitTest` passes in `android/RlcdPhone`.
  - **Rank:** [Total: 28, Use: 3, Setup: 9, Harmony: 8, Safety: 8, Effort: S]

## Priority 3: Display and Logging (Score 22–27)

- [ ] Sensor drift notification on the Wear OS watch
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/StatusReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/DriftWatch.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/DriftWatchTest.kt`, `scripts/check-protocol-sync.sh`, `FEATURES.md`
  - **Goal:** Tell the user on the wrist which sensor drifts. Copy `DriftWatch.kt` from `android/RlcdPhone` without a change except the `package` line, and add it to `scripts/check-protocol-sync.sh`. Parse the field `drift=` of the `STATUS` line `LATCH` (`null` for older firmware). Show one notification with a short vibration for each rising bit with the sensor name. Show a drift mark beside each flagged sensor in `SensorHealthScreen`. Requires "Sensor drift notification and calibration shortcut in the Android app".
  - **Acceptance:** `StatusReplyTest` reads `tests/fixtures/status_reply_sample.txt` and gives the `drift` value of the fixture. A missing field gives `null`, and a bad hex value throws `ParseException`. `DriftWatchTest` checks the first read, a rising bit and a falling bit. `scripts/check-protocol-sync.sh` exits 0, and `--self-test` passes. `gradle testDebugUnitTest assembleDebug` passes in `wearos/RlcdWatch`.
  - **Rank:** [Total: 27, Use: 5, Setup: 6, Harmony: 7, Safety: 9, Effort: S]

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
