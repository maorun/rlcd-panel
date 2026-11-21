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

- [ ] Battery runtime preview for the wake interval in the macOS config editor
  - Rank: [Total: 31, Use: 6, Setup: 8, Harmony: 7, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/RuntimePreview.swift, macos/RlcdCompanion/Sources/RlcdCompanion/ConfigEditorView.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/RuntimePreviewTests.swift
  - Goal: Calculate the expected battery hours for an edited `wake_interval_s` and `night_wake_interval_s` from the `AWAKE avg_ms` value and the `RUNTIME` line of the last `STATUS`, with the same model as `BatteryHistory::estimate`. Show `About <N> h on battery` below the two fields. Show `--` without `AWAKE` samples.
  - Acceptance test: `RuntimePreviewTests` assert that a doubled interval gives a longer runtime, that `avg_ms=--` gives `nil`, that quiet hours from 22 to 7 weigh the night interval with 9 of 24 hours, and that the value for the current interval equals the `RUNTIME h` value within 10 %. `swift test` passes.

- [ ] Acknowledge all button in the Android chime screen
  - Rank: [Total: 30, Use: 7, Setup: 5, Harmony: 8, Safety: 10, Effort: S]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/AckCommand.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/AckCommand.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/ChimeScreen.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/AckCommandTest.kt
  - Goal: Add `AckCommand.ALL` (`ACK ALL`), `AckCommand.canAcknowledgeAll(alertMask, ackedMask)` and the result text for `OK ACK ALL <2 hex digits>` to the shared file. Show the button `Alle bestätigen` in `ChimeScreen` when two or more repeatable active bits are not acknowledged and `PanelConfig.canWrite` is true. Send `ACK ALL`, then `STATUS`.
  - Acceptance test: `AckCommandTest` asserts that mask `0x32` with acked `0x00` gives true, that `CHARGE_FULL` does not count, that `OK ACK ALL 22` gives the names `Humidity` and `Ventilate`, and that `ERR NOT_ACTIVE` gives its text. `./scripts/check-protocol-sync.sh` exits 0. The CI jobs pass.

- [ ] Console command SLOT SWAP to change the order of two tiles
  - Rank: [Total: 30, Use: 6, Setup: 9, Harmony: 8, Safety: 7, Effort: M]
  - Location: rlcd_panel/serial_console.h, rlcd_panel/power_manager.h, rlcd_panel/switchbot_ble.h, rlcd_panel/sparkline.h, tests/test_firmware_logic.cpp
  - Goal: Add `SLOT SWAP <A> <B>` (A and B 1 to 10, different). Swap the reading, the 24-hour histories, the week days, the reception history and the badge latch bits of the two slots under the registry lock. Reply `OK SLOT SWAP <A> <B>`. Give `ERR FIXED_MAC` when one slot has a fixed MAC, `ERR BAD_ARG` for an equal or bad number and `ERR READ_ONLY` on BLE with `ble_console` 1. Record one `SLOT_ASSIGN` event for each occupied slot. The RTC layout and `STATE_VERSION` do not change.
  - Acceptance test: Host tests assert that a swap of slot 1 and slot 3 moves the MAC, the history samples, the week days and the `stale_slots` bit, that a second swap restores the RTC bytes exactly, that the state checksum stays valid, that a fixed MAC gives `ERR FIXED_MAC` without a change, and that `SLOT SWAP 2 2` gives `ERR BAD_ARG`. `arduino-cli compile rlcd_panel` passes.

- [ ] Runtime and awake time lines in the Android sensor health screen
  - Rank: [Total: 29, Use: 6, Setup: 6, Harmony: 7, Safety: 10, Effort: S]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/StatusReply.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/StatusReply.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/SensorHealthScreen.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/StatusReplyTest.kt
  - Goal: Parse the `STATUS` lines `RUNTIME h=<N|--> samples=<N> cycles=<N.N|--> scan_saved_ms=<N>` and `AWAKE last_ms=<N|--> avg_ms=<N|--> max_ms=<N|--> samples=<N>` in the shared `StatusReply.kt`. Show `Restlaufzeit <N> h`, `Ladezyklen <N.N>` and `Wachzeit Ø <N> ms` in `SensorHealthScreen`. A missing line gives `null`. A bad number throws `ParseException`.
  - Acceptance test: `StatusReplyTest` reads `tests/fixtures/status_reply_sample.txt` and asserts each field, `null` for `--`, `null` for a missing line and a `ParseException` for `h=x`. `./scripts/check-protocol-sync.sh` exits 0. The CI jobs pass.

- [ ] Open window detection with a tile badge and an event code
  - Rank: [Total: 29, Use: 8, Setup: 4, Harmony: 8, Safety: 9, Effort: M]
  - Location: rlcd_panel/window_latch.h, rlcd_panel/device_config.h, rlcd_panel/power_manager.h, rlcd_panel/sd_logger.h, rlcd_panel/dashboard_view.h, rlcd_panel/rlcd_panel.ino, docs/config.example.ini, macos/RlcdCompanion/Sources/RlcdProtocol/ConfigSchema.swift, tests/test_firmware_logic.cpp
  - Goal: Add the key `window_drop_c` (0 is off, else 1.0 to 5.0 C, default 0). Set a tile bit in `PersistentState::window_tiles` when the temperature of a tile in `climate_alert_tiles` falls by at least the limit within 15 minutes. Release the bit when the temperature rises again by half the limit or after 2 hours. Draw an inverted `W` badge beside the `D` badge. Record `EventCode::WINDOW_OPEN` (22, detail low byte tile, high byte 1 open, 0 closed). Raise `STATE_VERSION` to 71. Add the key to `KEYS`, the template and `ConfigSchema`.
  - Acceptance test: Host tests assert a set for a drop of 2.0 C in 10 minutes with limit 1.5, no set for the same drop in 20 minutes, a release after 2 hours, no change with limit 0, a rejected bit above bit 10, one event for each edge and the RTC round trip with `STATE_VERSION` 71. `testConfigExampleTemplateInSync` and `ConfigSchemaTests` pass. `./scripts/generate-display-preview.sh` gives a preview with the `W` badge.

- [ ] Open window event texts and notification in the macOS app
  - Rank: [Total: 28, Use: 7, Setup: 4, Harmony: 7, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/EventText.swift, macos/RlcdCompanion/Sources/RlcdProtocol/EventFilter.swift, macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/EventTextTests.swift
  - Goal: Show `WINDOW_OPEN` events as `Window open in <room>` and `Window closed in <room>`. Put the code in the `EventFilter` category `climate`. Post one macOS notification for each new open event of the event list. Do this after the firmware item "Open window detection with a tile badge and an event code".
  - Acceptance test: `EventTextTests` assert both texts with the room name from `DeviceConfig` names, the raw text for an unknown high byte, and the filter category. `swift test` passes.

## Priority 3: Display and Logging (Score 22–27)

- [ ] Acknowledge all action in the Wear OS alert notification
  - Rank: [Total: 27, Use: 6, Setup: 4, Harmony: 7, Safety: 10, Effort: S]
  - Location: wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/BleScanService.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/AckReceiver.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/AckCommandTest.kt
  - Goal: Add the action `Acknowledge all` to the watch alert notification when two or more repeatable bits rise together. `AckReceiver` sends `ACK ALL` once after a user tap and shows the result text. Do this after the item "Acknowledge all button in the Android chime screen", which adds `AckCommand.ALL` to the shared file.
  - Acceptance test: `AckCommandTest` on the watch asserts the action rule for `HUMIDITY | VENTILATE` (true) and `HUMIDITY | CHARGE_FULL` (false). `./scripts/check-protocol-sync.sh` exits 0. The CI job `Wear OS companion tests and APK` passes.

- [ ] Runtime line in the Wear OS sensor health screen
  - Rank: [Total: 27, Use: 5, Setup: 5, Harmony: 7, Safety: 10, Effort: S]
  - Location: wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/SensorHealthTextTest.kt
  - Goal: Show `Runtime <N> h` and `Cycles <N.N>` from the shared `StatusReply.runtime` in the panel section of `SensorHealthScreen`. Show no line for `null`. Do this after the item "Runtime and awake time lines in the Android sensor health screen".
  - Acceptance test: `SensorHealthTextTest` asserts the text for `h=412 cycles=3.5`, `Runtime --` for `h=--`, and no line for a missing `RUNTIME` line. The CI job `Wear OS companion tests and APK` passes.

- [ ] Console command CONFIG <key> to read one value
  - Rank: [Total: 27, Use: 3, Setup: 8, Harmony: 6, Safety: 10, Effort: S]
  - Location: rlcd_panel/serial_console.h, tests/test_firmware_logic.cpp
  - Goal: Accept `CONFIG <key>` for each key of `DeviceConfigParser::KEYS`. Reply one line `<key>=<value>` from the RTC copy and the `CRC32` line. Give `ERR BAD_KEY` for an unknown key. `CONFIG` without an argument stays unchanged. The command needs no card and runs on USB and BLE at each `ble_console` level.
  - Acceptance test: Host tests assert `CONFIG mold_alert_limit_rh` gives `mold_alert_limit_rh=70` and a correct CRC line, `CONFIG nokey` gives `ERR BAD_KEY`, `CONFIG mold_alert_limit_rh x` gives `ERR BAD_ARG`, and the full `CONFIG` reply is byte-equal to the current reply. `arduino-cli compile rlcd_panel` passes.

- [ ] Wall humidity badge on the climate tiles
  - Rank: [Total: 27, Use: 7, Setup: 3, Harmony: 8, Safety: 9, Effort: M]
  - Location: rlcd_panel/dashboard_view.h, rlcd_panel/climate_math.h, rlcd_panel/power_manager.h, rlcd_panel/serial_console.h, tools/preview_scenes.h, tests/test_firmware_logic.cpp
  - Goal: Set a tile bit in `PersistentState::wall_tiles` when `ClimateMath::wallSurfaceRh` of the tile is at or above `mold_alert_limit_rh`. Release it 3 %RH below the limit, for an empty or stale tile, for the outdoor tile and for `wall_frsi_pct` 0. Draw an inverted `C` badge beside the `D` badge. Append ` wall=<3 hex digits>` to the `STATUS` line `LATCH`. Raise `STATE_VERSION`.
  - Acceptance test: Host tests assert a set at the limit, the hysteresis, a clear for a stale outdoor slot, a rejected bit above bit 10, the `LATCH` line with `wall=` and the RTC round trip. `./scripts/generate-display-preview.sh` adds `docs/display_preview_wall.png` with the badge. `arduino-cli compile rlcd_panel` passes.

- [ ] Room comparison chart in the macOS app
  - Rank: [Total: 26, Use: 7, Setup: 2, Harmony: 7, Safety: 10, Effort: M]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/ClimateCompare.swift, macos/RlcdCompanion/Sources/RlcdCompanion/ClimateChartView.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/ClimateCompareTests.swift
  - Goal: Add the mode `Compare rooms` to `ClimateChartView`. Draw the temperature or humidity of two to six selected sensors of one `ClimateLog` month as lines in one chart with one color for each sensor. Break a line at an empty field. Use the sensor names of the active `DeviceConfig`.
  - Acceptance test: `ClimateCompareTests` load a fixture month and assert one series for each selected sensor, a gap for an empty field, the series order and the axis range over all series. `swift test` and `swift build -c release` pass.

- [ ] Feels-like and absolute humidity curves in the macOS climate chart
  - Rank: [Total: 25, Use: 6, Setup: 2, Harmony: 7, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/ClimateMath.swift, macos/RlcdCompanion/Sources/RlcdCompanion/ClimateChartView.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/ClimateMathTests.swift
  - Goal: Add the metric picker values `Feels like` and `Absolute humidity` to `ClimateChartView`. Calculate each point from the temperature and humidity of the `ClimateLog` row with `ClimateMath`. Skip a point without both values.
  - Acceptance test: `ClimateMathTests` read `tests/fixtures/climate_math_vectors.txt` and assert the heat index and absolute humidity points of a fixture row, and no point for a row with an empty humidity field. `swift test` passes.

- [ ] Room comparison chart in the Android history screen
  - Rank: [Total: 25, Use: 6, Setup: 2, Harmony: 7, Safety: 10, Effort: M]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/HistoryScreen.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/TelemetryHistory.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/TelemetryHistoryTest.kt
  - Goal: Add the card `Räume vergleichen` to `HistoryScreen`. Draw the humidity or temperature of each active tile of `TelemetryHistory.windowPoints` in one `Canvas` with one color and one legend entry for each room. Use the shared axis range of all rooms.
  - Acceptance test: `TelemetryHistoryTest` asserts a new function `comparePoints(nowMillis, metric)` that gives one list for each tile with points, no list for an empty tile, and the shared minimum and maximum. `gradle testDebugUnitTest` passes in CI.

- [ ] Vibration pattern for each alert on the Wear OS watch
  - Rank: [Total: 24, Use: 6, Setup: 1, Harmony: 7, Safety: 10, Effort: S]
  - Location: wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/AlertVibration.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/BleScanService.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/AlertVibrationTest.kt
  - Goal: Give each alert bit its own vibration pattern, so the user knows the alert without a look at the watch: freezer three short pulses, humidity two long pulses, heat four short pulses, and one pulse for each other bit. For more rising bits, use the pattern of the lowest bit. Use the same rule in `MainActivity` and `BleScanService`.
  - Acceptance test: `AlertVibrationTest` asserts the timing arrays of the freezer, humidity and heat bits, the lowest-bit rule for `HUMIDITY | HEAT` and an empty array for mask 0. The CI job `Wear OS companion tests and APK` passes.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
