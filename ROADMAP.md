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

- [ ] Saturated salt humidity check with the console command SALT
  - Rank: [Total: 35, Use: 6, Setup: 10, Harmony: 9, Safety: 10, Effort: M]
  - Location: rlcd_panel/serial_console.h, rlcd_panel/climate_math.h, rlcd_panel/rlcd_panel.ino, macos/RlcdCompanion/Sources/RlcdProtocol/Calibration.swift, macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift, macos/RlcdCompanion/Sources/RlcdCompanion/CalibrationView.swift, tests/test_firmware_logic.cpp, macos/RlcdCompanion/Tests/RlcdProtocolTests/CalibrationTests.swift
  - Goal: Add the console command `SALT N` (`N` is 0 for the onboard sensor or 1 to 5 for a slot). The user puts one sensor in a closed box with saturated sodium chloride for at least 6 hours. `ClimateMath::saltReferenceRh(temp_c)` gives the NaCl equilibrium humidity from a fixed table (15 C 75.6 %RH to 30 C 75.1 %RH, linear between points, NaN outside 10 to 35 C). The command reads the last 6 hourly samples of the sensor from `ClimateHistory`. It replies `SALT <name> hours=<n> rh_mean=<x> rh_span=<x> temp_mean=<x> reference=<x>`, then the line `<key>=<value>` with the active offset plus the difference (reference minus mean), clamped to the key range, then the `CRC32` line. Fewer than 6 samples give `SKIP <name> hours=<n>`. A humidity span above 1.0 %RH gives `UNSTABLE <name> span=<x>`. A temperature outside the table gives `ERR BAD_TEMP`. A missing argument, `SALT 6` and extra text give `ERR BAD_ARG`. The command reads only the RTC copy, runs on USB power only, and does not write the card. `CalibrationView` gets a salt check section that sends `SALT N` and applies the proposal through `ConfigBatch`.
  - Acceptance test: Host tests assert the reference at 15, 20, 25 and 30 C, NaN at 9 C, a stable sensor at 73.0 %RH and 25 C that gives a proposal of +2.3 %RH plus the active offset, the clamp at the key limit, `SKIP` with 5 samples, `UNSTABLE` with a span of 1.2 %RH, the onboard key name `rh_offset_pct`, `ERR BAD_ARG` for `SALT 6`, the `CRC32` line and the USB power gate. Swift `CalibrationTests` parse each reply line type and throw `ParseError.badLine` for a bad number.

## Priority 2: Configuration and Control (Score 28–33)

- [ ] RTC frequency trim from the measured NTP drift
  - Rank: [Total: 32, Use: 5, Setup: 9, Harmony: 10, Safety: 8, Effort: M]
  - Location: rlcd_panel/pcf85063_rtc.h, rlcd_panel/ntp_sync.h, rlcd_panel/serial_console.h, rlcd_panel/rlcd_panel.ino, macos/RlcdCompanion/Sources/RlcdProtocol/Diagnostics.swift, macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift, tests/test_firmware_logic.cpp, macos/RlcdCompanion/Tests/RlcdProtocolTests/DiagnosticsTests.swift
  - Goal: Add `RtcTrim` to `pcf85063_rtc.h`. It calculates the `PCF85063A` offset register value (register `0x02`, mode 0, 4.34 ppm per step, -64 to 63) from `RtcDriftState::rate_ppm`: the correction is `rate_ppm / 4.34` steps, rounded, added to the active value with the sign of the datasheet table (a positive drift means a fast clock that needs a slower count), and clamped. Add the console command `RTC TRIM`. It needs a drift figure from an interval of 24 h or more, else it replies `ERR NO_DRIFT`. It writes the register, reads it back, clears the `RtcDrift` baseline, and replies `OK RTC TRIM <old> <new>`. A failed readback gives `ERR WRITE_FAILED` and keeps the baseline. The register keeps its value through deep sleep and a reset while the RTC has power, so the firmware stores no copy. `STATUS` shows `RTC trim=<N> drift_ppm=<N|-->` after `RESET`. The command runs on USB power only and gives `ERR READ_ONLY` on BLE with `ble_console` 1. `Diagnostics.swift` parses the line and `DiagnosticsView` shows a trim button when a figure exists.
  - Acceptance test: Host tests assert the step count 8 for +35 ppm and 2 for −10 ppm with opposite signs that match the datasheet table, the clamp at −64 and 63, the register encoding of negative values (7-bit two's complement, mode bit 0), `ERR NO_DRIFT` without a figure and with a 12 h interval, the cleared baseline after a write, `ERR WRITE_FAILED` for a readback mismatch with a fake bus, and the `STATUS` line. Swift tests parse `RTC trim=-8 drift_ppm=--` and throw `ParseError.badLine` for `trim=x`.

- [ ] Event code for each SET command in the event ring
  - Rank: [Total: 31, Use: 4, Setup: 8, Harmony: 9, Safety: 10, Effort: S]
  - Location: rlcd_panel/sd_logger.h, rlcd_panel/serial_console.h, rlcd_panel/rlcd_panel.ino, macos/RlcdCompanion/Sources/RlcdProtocol/EventText.swift, tests/test_firmware_logic.cpp, macos/RlcdCompanion/Tests/RlcdProtocolTests/EventTextTests.swift
  - Goal: Add `EventCode::CONFIG_SET = 11` (`EventRing::MAX_CODE` 11). After each successful `SET <key>=<value>` the firmware records one event with the index of the key in `DeviceConfigParser::KEYS` as detail, and the transport in the high byte (0 USB, 1 BLE). A rejected `SET` records no event. The hourly write puts the event into `events_v1_YYYY.csv` without a format change. `EventText` in the macOS app shows `Config set: <key> (USB|BLE)` and `Config set: key #<N>` for an unknown index. The RTC ring check accepts code 11 and rejects 12.
  - Acceptance test: Host tests assert one event after `OK SET wake_interval_s`, no event after `ERR BAD_VALUE`, the detail for key index 0 and index 47 over BLE, the ring check for code 11 and code 12, and the CSV row text. Swift `EventTextTests` assert the text for a known key, an unknown index and both transports.

- [ ] Dry air badge and dry limit in config.ini
  - Rank: [Total: 30, Use: 8, Setup: 5, Harmony: 8, Safety: 9, Effort: M]
  - Location: rlcd_panel/device_config.h, rlcd_panel/dashboard_view.h, rlcd_panel/ventilate_latch.h, rlcd_panel/power_manager.h, rlcd_panel/ble_telemetry.h, docs/config.example.ini, tools/preview_scenes.h, scripts/generate-display-preview.sh, macos/RlcdCompanion/Sources/RlcdProtocol/ConfigSchema.swift, macos/RlcdCompanion/Sources/RlcdProtocol/TelemetryFrame.swift, tests/test_firmware_logic.cpp
  - Goal: Add the key `dry_alert_limit_rh` (0 is off, else 20 to 45 %RH, default 0; 1 byte in the config trailer; increase `KEY_COUNT`). `DryLatch` (one bit for each of the 6 tiles) sets a tile bit when its humidity is at or below the limit and releases it at the limit plus 3 %RH. An empty or stale tile clears its bit. Only tiles in `climate_alert_tiles` count. A set bit draws the inverted badge `D` next to the `V` badge in the tile, in each tile style. The latch has no chime, because `chime_alert_mask` has no free bit. The telemetry frame keeps format version 2 and carries no dry flag. The latch uses 1 byte in `PersistentState` before the checksum; a bit for a tile above 5 is rejected. Increase `STATE_VERSION`. Add the scene `display_preview_dry.png`.
  - Acceptance test: Host tests assert the set at 30 %RH with limit 30, the hold at 32 %RH, the release at 33 %RH, the clear for a stale tile and for a tile outside `climate_alert_tiles`, the limit 0 that never sets, the rejected lines `19` and `46`, the RTC round trip and the rejected bit 6, and the badge pixels in the STANDARD, LARGE and COMPACT tiles. `testConfigExampleTemplateInSync` and `ConfigSchemaTests` pass. The preview script writes the new image.

## Priority 3: Display and Logging (Score 22–27)

- [ ] Seven-day minimum and maximum chart view
  - Rank: [Total: 27, Use: 8, Setup: 2, Harmony: 8, Safety: 9, Effort: M]
  - Location: rlcd_panel/sparkline.h, rlcd_panel/dashboard_view.h, rlcd_panel/button_handler.h, rlcd_panel/power_manager.h, rlcd_panel/rlcd_panel.ino, tools/preview_scenes.h, scripts/generate-display-preview.sh, tests/test_firmware_logic.cpp
  - Goal: Add `WeekHistory` to `sparkline.h`: for each of the 6 sensors, 7 days of temperature minimum and maximum (int8 in 0.5 C) and humidity minimum and maximum (uint8 in 0.5 %RH), 168 bytes plus one day stamp in `PersistentState`. At the first hourly write after local midnight the firmware shifts the ring and stores the previous day from the 24-hour history of each sensor. An invalid RTC time skips the shift. A day without samples stores the marker 0xFF and shows a gap. Add the view `WEEK_CHART` (`DisplayMode` 5, `DISPLAY_MODE_COUNT` 6) after `CLIMATE_CHART`. It uses the same `chart_sensor`: a short `KEY` press returns `ButtonEvent::NEXT_SENSOR`, and a hold of 2 s changes the view. The page draws 7 vertical bars from minimum to maximum for temperature and humidity, with weekday labels from the RTC. The view reads only the RTC copy and does not mount the card. Increase `STATE_VERSION`; a stored mode above 5 is rejected. Add `display_preview_chart_week.png`.
  - Acceptance test: Host tests assert the shift at midnight, the store of the previous day from a known 24-hour history, the gap marker, the skip for an invalid RTC time, the RTC round trip and the rejected mode 6, the view order with `DISPLAY_MODE_COUNT` 6, `NEXT_SENSOR` on a short press in the new view, and the bar pixels for a known day. The preview script writes the new image.

- [ ] Daily mold hours columns in the daily file
  - Rank: [Total: 26, Use: 7, Setup: 2, Harmony: 9, Safety: 8, Effort: M]
  - Location: rlcd_panel/sd_logger.h, rlcd_panel/sparkline.h, rlcd_panel/serial_console.h, scripts/dump-logs.sh, macos/RlcdCompanion/Sources/RlcdProtocol/DailyLog.swift, macos/RlcdCompanion/Sources/RlcdProtocol/LogArchive.swift, macos/RlcdCompanion/Sources/RlcdCompanion/DailyChartView.swift, tests/test_firmware_logic.cpp, macos/RlcdCompanion/Tests/RlcdProtocolTests/DailyLogTests.swift
  - Goal: Add the daily file format `daily_v4_YYYY.csv` with 48 columns: the 42 columns of `daily_v3` plus `mold_h_0` to `mold_h_5`, the hours of each sensor above `mold_alert_limit_rh` from `HumidityHistory::countAbove`. An empty or stale slot gives an empty field. The old `daily_v1`, `daily_v2` and `daily_v3` files stay unchanged. `LIST`, `DUMP`, the retention and `scripts/dump-logs.sh` accept the new name. `DailyLog` in the macOS app parses `daily_v4` and `DailyChartView` shows the mold hours as a bar chart for each sensor. Each file write keeps the current append path, so a power loss loses at most one row.
  - Acceptance test: Host tests assert the header with 48 columns, a row with 5 mold hours for the onboard sensor and empty fields for an empty slot, the file name, the `DUMP daily_v4_2026.csv` parser, and the retention month of December. Swift `DailyLogTests` parse a `daily_v4` sample, give `nil` for an empty field and throw `ParseError.badLine` for 47 columns. `LogArchiveTests` accept the new name.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components and firmware modules does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
