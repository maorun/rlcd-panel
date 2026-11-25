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



## Priority 3: Display and Logging (Score 22–27)

- [ ] Gap report in the Android tail screen
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/LogGaps.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/TailScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/LogGapsTest.kt`, `FEATURES.md`
  - **Goal:** Show missing hours of the climate log on the phone. Port the macOS `LogGaps.report` to Kotlin. Give one gap for a step above 90 minutes between two neighbour rows. Skip a row with an unreadable timestamp. Use the smaller of the real and the wall-clock difference, so the October clock change gives no false gap. Show the gap count and one line for each gap in `TailScreen`.
  - **Acceptance:** A unit test checks that a step of 90 minutes gives no gap. A unit test checks that a step of 3 hours gives one gap with 2 missing hours. A unit test checks that the repeated hour of the October change gives no gap. A unit test parses `tests/fixtures/tail_reply_sample.txt`. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` passes.
  - **Rank:** [Total: 26, Use: 4, Setup: 5, Harmony: 7, Safety: 10, Effort: S]

- [ ] Hour window for the console command HISTORY
  - **Location:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `tests/fixtures/history_reply_sample.txt`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Make the `HISTORY` reply short for one sensor over BLE. Accept the optional argument `HISTORY <N> <H>` (H 1 to 24, no leading zero, `SerialCommand::history_hours`). Send only the rows of sensor N with `age_h` below H. Calculate the `CRC32` line over the filtered bytes. Keep the `HISTORY` and `HISTORY <N>` replies. Give `ERR BAD_ARG` for H 0, H 25, a leading zero, a non-digit and extra text. Do not change the RTC layout or `STATE_VERSION`.
  - **Acceptance:** A host test checks that `HISTORY 2 6` sends the 6 newest rows of sensor 2. A host test checks the CRC-32 of this reply. A host test checks that `HISTORY 2` keeps its reply. A host test checks `ERR BAD_ARG` for `HISTORY 2 0` and `HISTORY 2 25`. A host test checks `ERR BAD_ARG` for `HISTORY 2 06` and `HISTORY 2 6 1`. The host unit tests pass. `arduino-cli compile rlcd_panel` passes.
  - **Rank:** [Total: 25, Use: 3, Setup: 5, Harmony: 7, Safety: 10, Effort: S]

- [ ] Short HISTORY reads for the macOS salt test watch
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/SaltProgressTests.swift`, `FEATURES.md`
  - **Goal:** Send fewer bytes over BLE during a salt test. This item needs the firmware item "Hour window for the console command HISTORY". Add `ConsoleCommand.historySensorHours(Int, Int)` with the line `HISTORY <N> <H>`. Send `HISTORY <N> 6` every 10 minutes in the salt test watch. `SaltProgress.requiredHours` is 6, so 6 rows give the full count. Send `HISTORY <N>` again after an `ERR BAD_ARG` reply from an older firmware.
  - **Acceptance:** A unit test checks the line `HISTORY 4 6`. A unit test checks that sensor 11, hours 0 and hours 25 give no line. A unit test checks that 6 stable rows give `Stable 6/6 h`. A unit test checks the fallback to `HISTORY 4` after `ERR BAD_ARG`. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.
  - **Rank:** [Total: 25, Use: 3, Setup: 5, Harmony: 7, Safety: 10, Effort: S]


## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
