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

- [ ] Badge intervals in the Wear OS event log
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/EventLogScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/AlertTimelineTest.kt`, `FEATURES.md`
  - **Goal:** Show on the watch when each badge of a room started and ended. This item needs the Android item "Badge intervals in the Android alert timeline". Use the shared `AlertTimeline.badges`. Show one chip for each interval below the alert intervals: badge letter, room name, start and duration. Show `offen` for an open interval and `?` for `startUnknown`.
  - **Acceptance:** A unit test checks the chip text of a closed `WALL_DAMP` interval. A unit test checks `offen` for an open `DRY_AIR` interval. A unit test checks `?` for an interval with `startUnknown`. `scripts/check-protocol-sync.sh` exits with 0. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 27, Use: 5, Setup: 4, Harmony: 8, Safety: 10, Effort: M]

- [ ] Dry hours column in the WEEK reply
  - **Location:** `rlcd_panel/serial_console.h`, `tests/test_firmware_logic.cpp`, `tests/fixtures/week_reply_sample.txt`, `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/WeekMoldTests.swift`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/WeekReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/WeekReply.kt`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Send the stored dry hours of each day to the companion apps. Append the column `dry_h` after `mold_h` to the `WEEK` and `WEEK <N>` replies. Read `WeekHistory::dryHours` and send an empty field without `hasDryHours`. Let the macOS and Kotlin `WeekReply.parse` accept 7, 8 or 9 columns. Give `dryHours` as `nil` or `null` for 7 or 8 columns and for an empty field.
  - **Acceptance:** A host test checks the field `5` for a day with 5 dry hours. A host test checks an empty field for a gap day and for limit 0. A host test checks the `CRC32` line of `WEEK 2`. A unit test in each app parses the updated `tests/fixtures/week_reply_sample.txt`. A unit test in each app parses an 8-column reply with `dryHours` empty. The host unit tests, `arduino-cli compile rlcd_panel`, `swift test`, `scripts/check-protocol-sync.sh` and `gradle testDebugUnitTest` in both Android projects pass.
  - **Rank:** [Total: 26, Use: 5, Setup: 3, Harmony: 9, Safety: 9, Effort: S]

- [ ] Dry hours in the macOS week chart
  - **Location:** `macos/RlcdCompanion/Sources/RlcdCompanion/ClimateChartView.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/WeekMoldTests.swift`, `FEATURES.md`
  - **Goal:** Show in the macOS week chart how many hours each room was too dry. This item needs the firmware item "Dry hours column in the WEEK reply". Add `WeekReply.drySum(sensor:)` with the sum over the days with a value. Give `nil` without such a day. Show the daily dry hours as a second bar series beside the mold hours in `WeekChartView`. Show the text `Dry <N> h` below the chart.
  - **Acceptance:** A unit test checks a sum of 12 hours over three days. A unit test checks `nil` for a reply without a dry value. A unit test checks that an 8-column reply gives `nil`. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.
  - **Rank:** [Total: 25, Use: 6, Setup: 2, Harmony: 7, Safety: 10, Effort: S]



## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
