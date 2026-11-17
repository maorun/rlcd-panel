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

- [ ] Mute marker in the panel header
  - Rank: [Total: 30, Use: 6, Setup: 5, Harmony: 9, Safety: 10, Effort: S]
  - Location: rlcd_panel/dashboard_view.h, rlcd_panel/rlcd_panel.ino, tools/preview_scenes.h, tools/render_display_preview.cpp, scripts/generate-display-preview.sh, tests/test_firmware_logic.cpp
  - Goal: `MUTE 1` or a `BOOT` press silences each chime, but the panel does not show this state. The user can forget that the alerts are silent. Add the field `muted` to the header input of each view. When `muted` is true, the header shows a white 9x11 badge with a black `M` at the position of the quiet hours badge (`HEADER_QUIET_X`, `HEADER_QUIET_Y`). The mute marker wins over the `Q` marker, because mute silences all chimes. Without mute the `Q` rule does not change. The empty battery screen does not show the marker. A change of the mute state changes the frame CRC-32, so `FrameRefresh` flushes the next frame. Add one preview scene with the mute marker.
  - Acceptance test: Host tests assert the `M` badge pixels for `muted` true, the `Q` badge for quiet hours without mute, the `M` badge for mute and quiet hours together, no badge for both false, a bit-exact header for both false against the old render, and different frame CRC-32 values for mute on and off. The preview script writes the new scene image.

- [ ] Restore of the 24-hour histories from the MicroSD card after a power-on
  - Rank: [Total: 28, Use: 7, Setup: 5, Harmony: 9, Safety: 7, Effort: M]
  - Location: rlcd_panel/sd_logger.h, rlcd_panel/sparkline.h, rlcd_panel/rlcd_panel.ino, tests/test_firmware_logic.cpp
  - Goal: A power-on (battery change, reset) clears the RTC memory. The sparklines, the records, the mold hours and the charts stay empty for 24 hours, but the card holds the hourly rows. At a power-on with a valid RTC time, the firmware mounts the card for the config load and then reads the end of the current `climate_v5_YYYYMM.csv` file (and the previous month file when the current file has fewer than 24 rows). Add `SdLogCodec::parseClimateRow` (27 columns; an empty field is a missing value; a wrong column count rejects the row) and `ClimateHistory::restore`. The restore pushes the newest contiguous hours, at most 24 and not older than 24 hours, into the onboard history, the onboard `usb_mask` (from the `usb` column) and the history of each slot with a fixed MAC (`slotN_mac`). Slots without a fixed MAC stay empty, because the registry order after a power-on is not known. A gap of more than one hour ends the restore at the gap. The read is limited to the last 8192 bytes of each file. A missing file, a read failure or a bad row changes nothing and never blocks the wake cycle. `last_sample_ms` follows the newest restored row, so the hourly gate does not push the same hour two times.
  - Acceptance test: Host tests assert the parse of a known row, the rejected row with 26 columns, empty fields as missing values, a restore of 24 rows into the onboard history and a fixed MAC slot, no restore for a slot without a fixed MAC, the stop at a 2-hour gap, the skip of rows older than 24 hours, the month change with 10 rows in the current file and 30 rows in the previous file, the `usb_mask` bits, and no change for an invalid RTC time and for a missing file.

## Priority 3: Display and Logging (Score 22–27)

- [ ] Alert limit lines in the macOS climate charts
  - Rank: [Total: 27, Use: 6, Setup: 3, Harmony: 8, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/ChartLimits.swift, macos/RlcdCompanion/Sources/RlcdCompanion/ClimateChartView.swift, macos/RlcdCompanion/Sources/RlcdCompanion/DailyChartView.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/ChartLimitsTests.swift
  - Goal: The charts show the values, but not the alert limits. The user cannot see how often a sensor was near a limit. Add `ChartLimits.from(config:)` to `RlcdProtocol`. It reads the last `ConfigReply` and gives one line for each active limit: `mold_alert_limit_rh` and `dry_alert_limit_rh` (only when not 0) for the humidity charts, `freeze_alert_limit_c` and `heat_alert_limit_c` (only when not 0) for the temperature charts. A limit outside the `ConfigSchema` range gives no line. Without a `CONFIG` reply the list is empty. `ClimateChartView` and `DailyChartView` draw each line as a dashed `RuleMark` with its name. The heat limit is a feels-like limit, so its label says `Heat (feels-like)`. The app sends no new command.
  - Acceptance test: Swift `ChartLimitsTests` assert the lines for a known `CONFIG` sample, no dry and no heat line for value 0, no line for a value outside the schema range, an empty list without a reply, and the label texts. `swift test` and `swift build -c release` pass.

- [ ] Alert timeline from the event ring in the macOS app
  - Rank: [Total: 26, Use: 5, Setup: 4, Harmony: 7, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/AlertTimeline.swift, macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift, macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/AlertTimelineTests.swift
  - Goal: The `EVENTS` reply lists `ALERT_RISE` and `ALERT_FALL` rows, but the user must pair them by hand. Add `AlertTimeline.build(rows:now:)` to `RlcdProtocol`. It pairs each `ALERT_RISE` with the next `ALERT_FALL` of the same alert bit (the alert bit as `EventText` reads the detail) into one interval. An alert without a fall stays open until `now`. A fall without a rise gives an interval from the first row of the reply with the flag `startUnknown`. An `EVENTS_DROPPED` row sets `startUnknown` on each interval that starts before the next rise. Rows with other codes are ignored. `DiagnosticsView` shows the intervals as one horizontal bar row for each alert with Swift Charts, from the `EVENTS` reply that the user requests with the existing read action.
  - Acceptance test: Swift `AlertTimelineTests` assert one interval for a rise and a fall, an open interval until the injected `now`, `startUnknown` for a fall without a rise and after an `EVENTS_DROPPED` row, two separate intervals for two alert bits in one reply, and no interval for `CONFIG_SET` rows. `swift test` and `swift build -c release` pass.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
