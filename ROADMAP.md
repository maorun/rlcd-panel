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

- [ ] Quiet hours in the macOS diagnostics view
  - Rank: [Total: 27, Use: 4, Setup: 6, Harmony: 7, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/Diagnostics.swift, macos/RlcdCompanion/Sources/RlcdProtocol/DiagnosticsReport.swift, macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/DiagnosticsTests.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/DiagnosticsReportTests.swift
  - Goal: Show the `QUIET` line of the entry "Quiet hours line in STATUS" in the macOS app. Add `QuietMetrics.parse(line:)` with `startHour`, `endHour`, `active` (`Bool?`) and `nightIntervalS`, and add `DiagnosticsReply.quiet`. Return `nil` for a missing line and `active` `nil` for `--`. Throw `ParseError.badLine` for an hour above 23, an `active` value other than `0`, `1` and `--`, and a bad number. Show the group `Quiet hours` in `DiagnosticsView`: `22:00 to 07:00`, `active now` or `not active`, and `Night interval <N> s` or `Night interval off`. Add the summary line `Quiet hours: 22:00 to 07:00, active` to `DiagnosticsReport`.
  - Acceptance test: In `DiagnosticsTests`, assert the values of `QUIET start_h=22 end_h=7 active=1 night_interval_s=900`, `active` `nil` for `active=--`, `nil` without the line, and `ParseError.badLine` for `start_h=24`, `active=2` and `night_interval_s=x`. In `DiagnosticsReportTests`, assert the summary line. Run `swift test` and `swift build -c release` in `macos/RlcdCompanion`.

- [ ] Acknowledge all button in the macOS diagnostics view
  - Rank: [Total: 25, Use: 5, Setup: 4, Harmony: 7, Safety: 9, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift, macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift, macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/ReplyTests.swift
  - Goal: Put this entry after "Console command ACK ALL". The diagnostics view shows one `Acknowledge` button for each active alert. Add `ConsoleCommand.ackAll` with the line `ACK ALL`. Extend `ConsoleCommand.ackMessage` with `OK ACK ALL <mask>` (`Acknowledged: <names>`) and keep the current texts for `ERR NOT_ACTIVE` and `ERR READ_ONLY`. Add `AppModel.acknowledgeAll()`: it queues `.ackAll` and `.status`, like `acknowledge(bit:)`. Show the button `Acknowledge all` only when two or more active bits are not acknowledged.
  - Acceptance test: In `ReplyTests`, assert the line `ACK ALL`, `Acknowledged: Humidity, Ventilate` for `OK ACK ALL 22`, the current text for `ERR NOT_ACTIVE`, and the button rule for 1 and 2 open bits with a pure helper. Run `swift test` and `swift build -c release` in `macos/RlcdCompanion`.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
