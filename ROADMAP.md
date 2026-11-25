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

- [ ] Dry hours in the macOS sensor list
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/Views.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/StatusReplyTests.swift`, `FEATURES.md`
  - **Goal:** Show the dry hours of each room beside the mold hours. This item needs the firmware item "Dry hours field in the STATUS SENSOR lines". Add `StatusSensor.dryHours` (`Int?`). Give `nil` for `--` and for a missing field. Throw `ParseError.badLine` for a bad number. Show `Dry <N> h` in the menu bar status list and in the `Sensor health` rows. Show a warning symbol above 0 hours.
  - **Acceptance:** A unit test parses `dry_h=5` to 5. A unit test parses `dry_h=--` and a missing field to `nil`. A unit test checks that `dry_h=x` throws `ParseError.badLine`. A unit test parses `tests/fixtures/status_reply_sample.txt`. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.
  - **Rank:** [Total: 27, Use: 6, Setup: 4, Harmony: 7, Safety: 10, Effort: S]

- [ ] Slot swap from the Wear OS sensor health screen
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/SlotSwapCommand.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/SlotSwapCommandTest.kt`, `FEATURES.md`
  - **Goal:** Let the user swap two slots from the watch. Add `SlotSwapCommand` with the rules of `SlotClearCommand`. `line(a, b)` gives `SLOT SWAP <A> <B>` for two different slots 1 to 10. `canSwap` is false for a slot with a fixed MAC. Show a confirm step `Platz <A> und <B> tauschen?`. Map `OK SLOT SWAP`, `ERR FIXED_MAC`, `ERR SLOT_OFF` and `ERR READ_ONLY` to a result text. Send `STATUS` after a good reply.
  - **Acceptance:** A unit test checks `SLOT SWAP 1 3`. A unit test checks that equal slots, 0 and 11 give no line. A unit test checks that a fixed MAC slot gives `canSwap` false. A unit test checks the result text of each reply. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 26, Use: 3, Setup: 7, Harmony: 7, Safety: 9, Effort: S]

- [ ] Sensor argument for the console command TODAY
  - **Location:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `tests/fixtures/today_reply_sample.txt`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Make the `TODAY` reply short for one room over BLE. Accept the optional argument `TODAY <N>` (N 0 to 10, no leading zero, `SerialCommand::today_one`). Send the header and only the row of sensor N, then the `CRC32` line over the filtered bytes. Send the header and no row for a sensor without a sample. Keep the `TODAY` reply without an argument. Give `ERR BAD_ARG` for 11, a leading zero, a non-digit and extra text. Keep `ERR NO_TIME`. Do not change the RTC layout or `STATE_VERSION`.
  - **Acceptance:** A host test checks that `TODAY 2` sends only the row of sensor 2 with a correct CRC-32. A host test checks that `TODAY` keeps its reply. A host test checks `ERR BAD_ARG` for `TODAY 11`, `TODAY 01`, `TODAY x` and `TODAY 1 2`. The host unit tests pass. `arduino-cli compile rlcd_panel` passes.
  - **Rank:** [Total: 24, Use: 3, Setup: 4, Harmony: 7, Safety: 10, Effort: S]


## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
