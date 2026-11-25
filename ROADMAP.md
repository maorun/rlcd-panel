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

- [ ] Config key reception_low_pct for the weak signal limit
  - **Location:** `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `docs/config.example.ini`, `tools/sample_config.ini`, `tests/test_firmware_logic.cpp`, `macos/RlcdCompanion/Sources/RlcdProtocol/ConfigSchema.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/ConfigSchemaTests.swift`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ConfigSchema.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/ConfigSchemaTest.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/ConfigSchema.kt`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Let the user set the reception rate that sets the `R` badge. A sensor far from the panel has a low rate and sets a false badge. Add the key `reception_low_pct` (0 is off, else 30 to 80 %, default 50). Add 1 byte at the end of the config trailer, after `mold_alert_delay_h`. `PersistentState::recordReceptionChanges` sets the bit below the limit and releases it at the limit plus 20 %. Value 0 clears each bit without an event. Raise `KEY_COUNT` to 98 and `STATE_VERSION` to 77. Add the key to the three `ConfigSchema` files. Keep the Kotlin files equal.
  - **Acceptance:** A host test checks that `reception_low_pct=30` sets the bit below 30 % and releases it at 50 %. A host test checks that the default gives the old 50 % and 70 % rules. A host test checks that 0 clears each bit without a `RECEPTION_LOW` event. A host test rejects 29, 81 and a non-digit. `testConfigExampleTemplateInSync` passes. `ConfigSchemaTests.rangesMatchFirmwareConstants` passes. `./scripts/check-protocol-sync.sh` exits with 0. The host unit tests, `swift test`, `gradle testDebugUnitTest` in both Kotlin apps and `arduino-cli compile rlcd_panel` pass.
  - **Rank:** [Total: 30, Use: 4, Setup: 8, Harmony: 9, Safety: 9, Effort: M]
- [ ] Salt test progress in the Android calibration screen
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/SaltProgress.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/CalibrationScreen.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/SaltProgressTest.kt`, `FEATURES.md`
  - **Goal:** Show the user on the phone when a salt test has enough stable hours. Add `SaltProgress` with the rules of the macOS `SaltProgress.swift` and an injected clock. Count the stable hours of one sensor from each `HISTORY N` reply. A stable hour has a humidity span of 1.0 %RH or less. Send `HISTORY N` every 10 minutes while `CalibrationScreen` is open. Stop after 8 hours, on a lost link, on an `ERR` reply and when the user leaves the screen. Show `<N> von 6 Stunden stabil`. Send `SALT N` at 6 stable hours.
  - **Acceptance:** `SaltProgressTest` reads `tests/fixtures/history_reply_sample.txt` and gives the same stable hours as the macOS `SaltProgressTests`. A test checks that a span above 1.0 %RH resets the count. A test checks the stop after 8 hours with the injected clock. A test checks that an `ERR` reply stops the progress. `gradle testDebugUnitTest` passes in `android/RlcdPhone`.
  - **Rank:** [Total: 29, Use: 3, Setup: 9, Harmony: 8, Safety: 9, Effort: S]

## Priority 3: Display and Logging (Score 22–27)

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
