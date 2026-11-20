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
