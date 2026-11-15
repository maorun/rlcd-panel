# Roadmap

This file lists open work for `rlcd-panel`, the standalone Arduino CLI firmware for the **Waveshare ESP32-S3-RLCD-4.2** development board.
The device renders climate telemetry from onboard sensors and external SwitchBot thermo-hygrometers onto a 4.2-inch reflective LCD.
The list uses priorities. Priority 1 is the highest priority.
Do the work in priority order, unless the maintainer gives a different order.

## Rules for this file

- Write in ASD-STE100 Simplified Technical English.
- Add one entry for each open item. Do not give an entry a status. Each entry in this file is open.
- Remove an entry in the pull request that completes it. Describe finished work in `FEATURES.md`.
- Each entry names the code location, the goal, and the acceptance test.
- Each entry has a `Rank` line with the model, the total score, the four single scores, and the effort size. See "Rules for ranking".
- Put each entry in the priority that matches its total score. Order the entries inside a priority by total score, highest first. For equal scores, put the smaller effort first. Put a dependency above the entry that needs it.
- Priority 1 holds the most critical user-facing enhancements and core hardware integrations.
- Do not record review findings in this file. Name them in the review report and in the pull request description.

## Priority 1: Sensor Setup and Calibration (Score 34–40)


## Priority 2: Configuration and Control (Score 28–33)

- [ ] Repeated scan mode for sensor placement in the macOS app
  - Rank: [Total: 28, Use: 3, Setup: 9, Harmony: 8, Safety: 8, Effort: M]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/ScanSeries.swift, macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift, macos/RlcdCompanion/Sources/RlcdCompanion/ScannerView.swift, macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/ScanSeriesTests.swift
  - Goal: One `SCAN` gives one RSSI value. To find a good place for a sensor, the user needs a series. Add a `Repeat` toggle to `ScannerView`. While it is on, the app sends `SCAN` on the USB link every 15 s. `ScanSeries` (pure `RlcdProtocol` code with an injected clock) keeps the last 10 RSSI values of each MAC and gives minimum, mean, maximum and the count of scans without the MAC. The view shows these values for each MAC. The repeat stops when the user turns it off, closes the view, the link drops, a `SCAN` gives `ERR`, or after 10 minutes. The app never repeats over BLE, because `SCAN` gives `ERR NOT_OVER_BLE`. The firmware does not change.
  - Acceptance test: Swift `ScanSeriesTests` assert minimum, mean and maximum for known replies, the limit of 10 values, the missing-scan count for a MAC that is absent from one reply, the stop after 10 minutes with the injected clock, the stop on `ERR SCAN_FAILED`, and no start on a BLE link. `swift test` and `swift build -c release` pass.

## Priority 3: Display and Logging (Score 22–27)

- [ ] Console command WEEK and seven-day chart in the macOS app
  - Rank: [Total: 27, Use: 6, Setup: 2, Harmony: 9, Safety: 10, Effort: M]
  - Location: rlcd_panel/serial_console.h, rlcd_panel/sparkline.h, rlcd_panel/rlcd_panel.ino, macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift, macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift, macos/RlcdCompanion/Sources/RlcdCompanion/ClimateChartView.swift, tests/test_firmware_logic.cpp, macos/RlcdCompanion/Tests/RlcdProtocolTests/ReplyTests.swift
  - Goal: The `WEEK_CHART` view shows `WeekHistory` on the panel only. Add the console command `WEEK` (`SerialConsoleRunner::showWeek`). It replies `BEGIN WEEK`, the header `sensor,day,date,temp_min_c,temp_max_c,rh_min_pct,rh_max_pct`, one row for each of the 7 days of each of the 6 sensors (day 0 is the day before the stamp, the date is `YYYY-MM-DD` from the stamp), and the `CRC32` line. A gap day gives empty value fields. Without a stamp the date fields are empty. `WEEK` with text gives `ERR BAD_ARG`. The command reads only the RTC copy, needs no card, and runs on USB and BLE. `WeekReply` in the macOS app parses the reply (`nil` for an empty field, `ParseError.badLine` for a wrong column count) and the climate chart window shows the 7 bars of each sensor with Swift Charts.
  - Acceptance test: Host tests assert the header, 42 rows, the date of day 0 and day 6 from a known stamp, empty fields for a gap day and for a missing stamp, the values in 0.5 steps, `ERR BAD_ARG` for `WEEK 1`, the `CRC32` line, and no change of `PersistentState`. Swift `ReplyTests` parse a `WEEK` sample, give `nil` for a gap and throw `ParseError.badLine` for 6 columns.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components and firmware modules does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
