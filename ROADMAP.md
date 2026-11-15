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
