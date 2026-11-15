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

- [ ] Separate USB and battery hours in the CALIB proposal for the onboard sensor
  - Rank: [Total: 35, Use: 6, Setup: 10, Harmony: 10, Safety: 9, Effort: M]
  - Location: rlcd_panel/sparkline.h, rlcd_panel/power_manager.h, rlcd_panel/serial_console.h, rlcd_panel/rlcd_panel.ino, macos/RlcdCompanion/Sources/RlcdProtocol/Calibration.swift, macos/RlcdCompanion/Sources/RlcdCompanion/CalibrationView.swift, tests/test_firmware_logic.cpp, macos/RlcdCompanion/Tests/RlcdProtocolTests/CalibrationTests.swift
  - Goal: The `CALIB` proposal for `temp_offset_c` mixes hours on USB power and hours on battery power. The PCB is warmer on USB power, so the proposal is wrong for both cases. Add `ClimateHistory::usb_mask` (24 bits, one bit for each hourly sample of the onboard sensor; bit 0 is the newest sample). Each onboard push shifts the mask and sets bit 0 when the sample was taken on USB power. Store the mask as 3 bytes little-endian in `PersistentState` just before the checksum. A bit beyond the count of onboard samples is rejected. A power-on sets the mask to 0. Increase `STATE_VERSION`. `CALIB N` with N 1 to 5 uses the battery hours only for the `temp_offset_c` proposal of the onboard sensor when 3 or more battery hours exist; else it keeps the old calculation over all hours. After the onboard `SENSOR` line the reply adds the line `USB hours=<n> bat_hours=<n> usb_temp_offset=<x> usb_temp_diff=<x|-->`. With 3 or more USB hours and 3 or more battery hours it adds the proposal `usb_temp_offset_c=<x>`: the active value plus the USB difference minus the battery difference, clamped to -5.0 to 0.0 C. Fewer hours give `usb_temp_diff=--` and no proposal. The `CRC32` line covers the new lines. `CALIB 0` (onboard as reference) does not change. `CalibrationReply` parses the `USB` line and shows the proposal as one more selectable row in `CalibrationView`.
  - Acceptance test: Host tests assert the mask shift and bit 0 on USB power, the RTC round trip, the rejected bit beyond the sample count, a history with 6 battery hours at a difference of -1.0 C and 6 USB hours at -2.2 C that gives `temp_offset_c` from the battery hours and `usb_temp_offset_c` -1.2 C plus the active value, the clamp at -5.0 and 0.0 C, `usb_temp_diff=--` with 2 USB hours, the unchanged `CALIB 0` reply and the new `CRC32` value. Swift `CalibrationTests` parse the `USB` line, give `nil` for `--` and throw `ParseError.badLine` for `usb_hours=x`.

## Priority 2: Configuration and Control (Score 28–33)

- [ ] Raise the config.ini size limit to 8192 bytes
  - Rank: [Total: 33, Use: 4, Setup: 10, Harmony: 10, Safety: 9, Effort: S]
  - Location: rlcd_panel/device_config.h, rlcd_panel/sd_logger.h, rlcd_panel/serial_console.h, tools/check_config.cpp, docs/config.example.ini, macos/RlcdCompanion/Sources/RlcdProtocol/ConfigBackup.swift, tests/test_firmware_logic.cpp, macos/RlcdCompanion/Tests/RlcdProtocolTests/ConfigBackupTests.swift
  - Goal: `docs/config.example.ini` has 4071 of 4096 bytes. The next key with its comment lines breaks `testConfigExampleTemplateInSync`. Increase `DeviceConfigParser::MAX_FILE_SIZE` to 8192. The three static buffers in `SdLogger` (config read) and `SerialConsoleRunner::setConfig` (`current`, `next`) grow with the constant; they stay static, so the 8 KB loop task stack does not change. `DeviceConfigText::MAX_SIZE` (RTC copy) does not change, because the RTC copy holds values, not the file text. `DeviceConfigEditor::set` gives `ERR TOO_LARGE` above 8192 bytes. `ConfigBackup.maxBytes` becomes 8192. The host tool `check_config` uses the same constant. Add a comment in `docs/config.example.ini` that names the limit. The firmware reads an old 4096-byte file without a change.
  - Acceptance test: Host tests assert that a file of 8192 bytes parses, that 8193 bytes are rejected as a whole, that `DeviceConfigEditor::set` gives `ERR TOO_LARGE` for a result of 8193 bytes and succeeds for 8192 bytes, and that `testConfigExampleTemplateInSync` uses the new limit. Swift `ConfigBackupTests` accept 8192 bytes and reject 8193 bytes. `arduino-cli compile rlcd_panel` passes, and its DRAM report is part of the pull request description.

- [ ] Two-point humidity calibration with a second salt
  - Rank: [Total: 32, Use: 5, Setup: 10, Harmony: 8, Safety: 9, Effort: M]
  - Location: rlcd_panel/climate_math.h, rlcd_panel/device_config.h, rlcd_panel/serial_console.h, rlcd_panel/rlcd_panel.ino, docs/config.example.ini, macos/RlcdCompanion/Sources/RlcdProtocol/ConfigSchema.swift, macos/RlcdCompanion/Sources/RlcdProtocol/Calibration.swift, macos/RlcdCompanion/Sources/RlcdCompanion/CalibrationView.swift, tests/test_firmware_logic.cpp, macos/RlcdCompanion/Tests/RlcdProtocolTests/CalibrationTests.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/ConfigSchemaTests.swift
  - Goal: Needs "Raise the config.ini size limit to 8192 bytes". The `SALT` check corrects only an offset. A sensor with a slope error stays wrong far from 75 %RH. Add the keys `rh_gain` and `slot1_rh_gain` to `slot5_rh_gain` (0.90 to 1.10, default 1.00, 1 byte each in 0.002 steps around 1.00 in the config trailer; increase `KEY_COUNT`). The firmware calculates `rh = raw * gain + offset`, clamped to 0 to 100 %RH, before it stores a reading. Add `ClimateMath::magnesiumReferenceRh(temp_c)` from a fixed MgCl2 table (10 C 33.5 %RH to 35 C 32.1 %RH, linear; NaN outside). Add the argument `SALT N MGCL2`: the same checks and reply as `SALT N`, with the MgCl2 reference and no proposal line. The macOS `CalibrationView` stores the two results (NaCl and MgCl2) of one sensor. It calculates the raw means from the active gain and offset, then the new gain and offset from the two points. It shows the values and applies them through `ConfigBatch`. A gain outside the key range gives an error text and no `SET`. Increase `STATE_VERSION`.
  - Acceptance test: Host tests assert the MgCl2 reference at 10, 25 and 35 C and NaN at 9 C, the corrected value for gain 1.05 and offset -2.0 at a raw 50.0 %RH, the clamp at 100 %RH, the rejected lines `0.89` and `1.11`, the config trailer round trip, `SALT 1 MGCL2` without a proposal line and `ERR BAD_ARG` for `SALT 1 LICL`. `testConfigExampleTemplateInSync` and `ConfigSchemaTests.rangesMatchFirmwareConstants` pass with the new keys. Swift `CalibrationTests` assert the two-point result for known raw means, and the error for a gain of 1.2.

- [ ] Data age of a stale tile in the STALE badge
  - Rank: [Total: 31, Use: 7, Setup: 5, Harmony: 9, Safety: 10, Effort: S]
  - Location: rlcd_panel/dashboard_view.h, rlcd_panel/rlcd_panel.ino, tools/preview_scenes.h, scripts/generate-display-preview.sh, tests/test_firmware_logic.cpp
  - Goal: A stale tile shows `STALE`, but the user does not see if the last packet is 40 minutes or 3 days old. Add `DashboardView::formatStaleAge`: whole hours since the last packet of the slot, `<N>H` from 1 to 99 h (minimum `1H`) and `>99H` above. The badge shows `STALE <age>` when the tile is wide enough, else the age only. The text changes at most once an hour, so `FrameRefresh` still skips most frames of a stale dashboard. Each tile style (STANDARD, LARGE, COMPACT) keeps the badge inside the tile. The stats view keeps its text. The age uses `nowMs()` and the `updated_ms` of the slot reading in the RTC copy; a slot without a packet shows `STALE` without age. Update the preview scene with one stale tile.
  - Acceptance test: Host tests assert `1H` for 30 min and for 1 h 59 min, `2H` for 2 h, `99H` for 99 h, `>99H` for 100 h, `STALE` without age for a slot without a packet, the badge pixels in the STANDARD, LARGE and COMPACT tiles inside the tile rectangle, and the same frame CRC-32 for two renders 10 min apart within one hour. The preview script writes the updated image.

- [ ] Event code for MicroSD card faults
  - Rank: [Total: 30, Use: 4, Setup: 8, Harmony: 8, Safety: 10, Effort: S]
  - Location: rlcd_panel/sd_logger.h, rlcd_panel/rlcd_panel.ino, macos/RlcdCompanion/Sources/RlcdProtocol/EventText.swift, tests/test_firmware_logic.cpp, macos/RlcdCompanion/Tests/RlcdProtocolTests/EventTextTests.swift
  - Goal: `STATUS` shows only the last card result. The user does not see when a card fault started or how often it occurred. Add `EventCode::SD_FAULT = 12` (`EventRing::MAX_CODE` 12) with the new `SdStatus` as detail. The firmware records one event when `SdStatusCodec::next` changes the status to `NO_CARD`, `MOUNT_FAILED`, `WRITE_FAILED` or `FULL` from a different status. The same fault in a row records no event. A change back to `OK` records the event with detail 1 (`OK`). The event stays in the RTC ring until the next successful hourly write; the console command `EVENTS` shows it without a card. `EventText` shows `SD card fault: <status>` and `SD card OK`.
  - Acceptance test: Host tests assert one event for `OK` to `WRITE_FAILED`, no event for `WRITE_FAILED` to `WRITE_FAILED`, one event for `WRITE_FAILED` to `FULL`, the recovery event to `OK`, no event for `UNKNOWN` to `OK`, the ring check for code 12 and 13, and the CSV row text. Swift `EventTextTests` assert each status text and an unknown status number.

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
