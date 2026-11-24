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

- [ ] Newest climate rows screen in the Android app
  - Rank: [Total: 31, Use: 6, Setup: 7, Harmony: 8, Safety: 10, Effort: S]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/TailScreen.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/TailReply.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/TailCommand.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/TailCommandTest.kt, FEATURES.md
  - Goal: Use the existing `TailReply.parse` in a new `TailScreen`. Add a pure `TailCommand.text(rows)` that gives `TAIL <N>` for N 1 to 72 and null for other values. Let the user select 6, 24 or 72 rows. Send `TAIL <N>` over the BLE console only on a user action. Show one row for each hour with the local time, the onboard values and the values of each slot with a name. Show an empty field as `--`. Show `ERR NO_CARD`, `ERR NO_FILE` and `ERR READ_FAILED` as clear texts. Add one entry on the main screen.
  - Acceptance test: `TailCommandTest` asserts `TAIL 6`, `TAIL 72`, null for 0, 73 and -1. `TailReplyTest` asserts the row count and the empty fields of `tests/fixtures/tail_reply_sample.txt`. Run `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone`.

- [ ] Newest climate rows in the macOS diagnostics view
  - Rank: [Total: 31, Use: 6, Setup: 7, Harmony: 8, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/TailReply.swift, macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift, macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift, macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/TailReplyTests.swift, FEATURES.md
  - Goal: Add `TailReply.parse` for the `TAIL <N>` reply with the column layout of the `climate_v6` header (`ClimateLog` row type, `nil` for an empty field). Add `ConsoleCommand.tail(_ rows: Int)`, which accepts only 1 to 72. Add a group `Newest log rows` to `DiagnosticsView` with a row count picker (6, 24, 72), a read button and a table. Show `ERR NO_CARD`, `ERR NO_FILE` and `ERR READ_FAILED` as clear texts. Send `TAIL` only on a user action, on USB and on BLE.
  - Acceptance test: `TailReplyTests` read `tests/fixtures/tail_reply_sample.txt` and assert the row count, the first and the last time stamp and each empty field. Assert `ParseError.badLine` for a wrong column count and for `BEGIN TAIL x`, and `nil` from `ConsoleCommand.tail(0)` and `ConsoleCommand.tail(73)`. Run `cd macos/RlcdCompanion && swift test && swift build -c release`.

## Priority 3: Display and Logging (Score 22–27)

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
