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

- [ ] Apply the SPREAD offset proposals in the macOS calibration view
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/Calibration.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/CalibrationView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/CalibrationTests.swift`, `FEATURES.md`
  - **Goal:** Let the user correct an outlier sensor from the co-location check without manual typing. Parse the proposal lines of the `SPREAD` reply into `SpreadReply.proposals`. Show one checkbox for each proposal in `CalibrationView`. Send the selected values through `ConfigBatch`, then send `SPREAD` again. Requires "Offset proposals in the console command SPREAD".
  - **Acceptance:** `CalibrationTests` parse the updated `tests/fixtures/spread_reply_sample.txt` into one proposal with two keys. A reply without proposal lines gives an empty list. A bad number throws `ParseError.badLine`. A test checks that `ConfigBatch` holds the selected `SET` commands in schema order. `swift test` passes.
  - **Rank:** [Total: 34, Use: 8, Setup: 10, Harmony: 8, Safety: 8, Effort: M]

## Priority 2: Configuration and Control (Score 28–33)

- [ ] Apply the SPREAD offset proposals in the Android calibration screen
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/SpreadReply.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/CalibrationScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/SpreadReplyTest.kt`, `FEATURES.md`
  - **Goal:** Let the user correct an outlier sensor from the phone. Parse the proposal lines of the `SPREAD` reply. Show an `Apply` button for each proposal in the co-location card of `CalibrationScreen`. Send the `SET` commands over the BLE console, then send `SPREAD` again. Show `ERR READ_ONLY` as a clear text for `ble_console` 1. Requires "Offset proposals in the console command SPREAD".
  - **Acceptance:** `SpreadReplyTest` reads `tests/fixtures/spread_reply_sample.txt` and gives one proposal with two keys. A reply without proposal lines gives an empty list. A bad number throws `ParseException`. `gradle testDebugUnitTest` passes in `android/RlcdPhone`.
  - **Rank:** [Total: 33, Use: 8, Setup: 9, Harmony: 8, Safety: 8, Effort: M]


## Priority 3: Display and Logging (Score 22–27)

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
