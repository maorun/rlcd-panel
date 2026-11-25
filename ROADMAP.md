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
