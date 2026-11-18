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

- [ ] Alert acknowledge buttons in the macOS diagnostics view
  - Rank: [Total: 29, Use: 7, Setup: 4, Harmony: 9, Safety: 9, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift, macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift, macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/ReplyTests.swift
  - Goal: The firmware has the console command `ACK <N>`, but the macOS app does not send it. The user must type it in a terminal or use `MUTE`, which silences each alert. Parse `acked=<hex>` of the `POWER` line into `StatusReply.ackedMask` (0 for a missing field, `ParseError.badLine` for a bad hex value). Add `ConsoleCommand.ack(bit)` for bits 0 to 7. In `DiagnosticsView`, show one row for each active alert bit of `alertMask` with the alert name of `AlertTransitions`. Show an `Acknowledge` button for an active bit that is not in `ackedMask`. Show `Acknowledged` for a bit in `ackedMask`. `AppModel.acknowledge(bit)` sends `ACK <N>`, then `STATUS`. Show `ERR NOT_ACTIVE` as `The alert is off` and `ERR READ_ONLY` as `Set ble_console=2 to apply`. Send each command only on a user action.
  - Acceptance test: In `ReplyTests`, parse `tests/fixtures/status_reply_sample.txt` and assert `ackedMask == 0`. Assert `ackedMask` for `acked=02` and for a missing field. Assert that `acked=zz` throws `ParseError.badLine`. Assert that `ConsoleCommand.ack(1).line` is `ACK 1`, and that bit 8 gives `nil`. Run `cd macos/RlcdCompanion && swift test && swift build -c release`.

- [ ] Acknowledge action in the Wear OS alert notification
  - Rank: [Total: 28, Use: 8, Setup: 3, Harmony: 9, Safety: 8, Effort: M]
  - Location: wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/BleScanService.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/AckReceiver.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/AckCommand.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/console/BleConsoleClient.kt, wearos/RlcdWatch/app/src/main/AndroidManifest.xml, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/AckCommandTest.kt
  - Goal: The watch vibrates on a rising alert bit, but the user cannot stop the chime reminder of the panel from the wrist. Add `AckCommand.line(bit)` for bits 0 to 7 and `AckCommand.resultText(reply)`. Add one `Acknowledge` action to the alert notification of `BleScanService` for each rising bit that `chime_repeat_h` can repeat. Do not add it for `CHARGE_FULL`, because it never repeats. The action starts `AckReceiver`, which sends `ACK <N>` through `BleConsoleClient.send`. Replace the notification with `Acknowledged` on `OK ACK <N>`. Show `ERR NOT_ACTIVE` as `The alert is off` and `ERR READ_ONLY` as `Set ble_console=2 to apply`. Show a timeout as `Panel not in range`. Do not send `ACK` without a tap. Do not retry a failed command.
  - Acceptance test: In `AckCommandTest`, assert `ACK 0` to `ACK 7`, and assert that bit 8 and bit -1 throw `IllegalArgumentException`. Assert the texts of `OK ACK 1`, `ERR NOT_ACTIVE`, `ERR READ_ONLY` and a timeout. Assert that a rising `CHARGE_FULL` bit gives no action and that a rising `HUMIDITY` bit gives one action with bit 1. Run `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch`.

## Priority 3: Display and Logging (Score 22–27)

- [ ] Home screen widget in the Android app
  - Rank: [Total: 26, Use: 8, Setup: 2, Harmony: 7, Safety: 9, Effort: M]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/widget/ClimateWidgetReceiver.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/widget/ClimateWidgetModel.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/BleScanService.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/TelemetryHistory.kt, android/RlcdPhone/app/src/main/res/xml/climate_widget_info.xml, android/RlcdPhone/app/src/main/AndroidManifest.xml, android/RlcdPhone/app/build.gradle.kts, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/widget/ClimateWidgetModelTest.kt
  - Goal: The phone user must open the app to see the climate. The watch has a tile, but the phone has no glanceable view. Add a Jetpack Glance home screen widget (4 x 2 cells). `ClimateWidgetModel.build(snapshot, nowMs, config)` gives up to four rows with the tile name of `PanelConfig.tileName`, the temperature, the humidity and the `STALE` or `BAT LOW` badge, plus the active alert names and the data age. Read the last frame of each page from the store that `BleScanService` writes. Show `--` and `Open the app` for an empty store, a failed decode or a frame older than 35 minutes. Make `BleScanService` request a widget update only for a new sequence number. The widget starts no BLE scan and no network request. A tap opens `MainActivity`.
  - Acceptance test: In `ClimateWidgetModelTest`, decode a frame of `tests/fixtures/ble_telemetry_vectors.txt` and assert the four rows, the names and the alert names. Assert `--` for an empty store and for a frame of 36 minutes. Assert the `STALE` badge for a tile without its valid bit. Assert that the same sequence number requests no update. Run `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone`.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
