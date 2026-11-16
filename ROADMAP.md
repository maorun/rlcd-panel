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

- [ ] Console command SLOT CLEAR to free one registry slot
  - Rank: [Total: 34, Use: 5, Setup: 10, Harmony: 10, Safety: 9, Effort: S]
  - Location: rlcd_panel/serial_console.h, rlcd_panel/power_manager.h, rlcd_panel/sd_logger.h, rlcd_panel/rlcd_panel.ino, macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift, macos/RlcdCompanion/Sources/RlcdProtocol/EventText.swift, macos/RlcdCompanion/Sources/RlcdCompanion/ScannerView.swift, macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift, tests/test_firmware_logic.cpp, macos/RlcdCompanion/Tests/RlcdProtocolTests/ReplyTests.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/EventTextTests.swift
  - Goal: A neighbor sensor or a removed sensor keeps a registry slot until `slot_release_h` passes. The user cannot free the slot at once. Add the console command `SLOT CLEAR <N>` (N 1 to 5, `SerialCommandKind::SLOT_CLEAR`). It calls `PersistentState::resetSlot(N - 1)`, records the event `SLOT_RELEASE` with the slot number in the low byte and `1` in the high byte (manual release), and replies `OK SLOT CLEAR <N>`. A slot with a fixed MAC gives `ERR FIXED_MAC` and no change, because the next scan takes the same sensor again. An empty slot gives `OK SLOT CLEAR <N>` and records no event. A missing number, a number outside 1 to 5 or extra text gives `ERR BAD_ARG`. The command needs no card. On BLE with `ble_console` 1 it gives `ERR READ_ONLY`. The next save writes the state. `EventText` shows `Slot <N> released by the user` for the high byte 1. `ConsoleCommand.slotClear(N)` sends it. `ScannerView` shows a `Free slot` button for each occupied slot without a fixed MAC; after `OK` the app sends `STATUS`.
  - Acceptance test: Host tests assert that `SLOT CLEAR 2` clears the reading, the histories, the reception mask, the week days and the latches of slot 2 only, the event with detail `0x0102`, no event for an empty slot, `ERR FIXED_MAC` with an unchanged state, `ERR BAD_ARG` for `SLOT CLEAR`, `SLOT CLEAR 0`, `SLOT CLEAR 6` and `SLOT CLEAR 2 X`, and `ERR READ_ONLY` on BLE level 1. Swift tests assert the command text, the `OK` reply, the `ERR FIXED_MAC` text and the `EventText` of detail `0x0102` and `0x0002`.

## Priority 2: Configuration and Control (Score 28–33)

- [ ] Wall surface humidity from the outdoor temperature
  - Rank: [Total: 32, Use: 8, Setup: 5, Harmony: 9, Safety: 10, Effort: M]
  - Location: rlcd_panel/climate_math.h, rlcd_panel/device_config.h, rlcd_panel/dashboard_view.h, rlcd_panel/rlcd_panel.ino, docs/config.example.ini, tools/preview_scenes.h, scripts/generate-display-preview.sh, macos/RlcdCompanion/Sources/RlcdProtocol/ConfigSchema.swift, tests/test_firmware_logic.cpp, macos/RlcdCompanion/Tests/RlcdProtocolTests/ConfigSchemaTests.swift
  - Goal: Mold grows on a cold outer wall, where the humidity is higher than in the room air. The panel shows only the room humidity. Add `ClimateMath::wallSurfaceRh(t_in_c, rh_in_pct, t_out_c, frsi)`: the wall temperature is `t_out + frsi * (t_in - t_out)`, and the result is the humidity of the room air at this temperature (same vapor pressure as the room air), clamped to 100 %RH. NaN or infinite input, `frsi` outside 0.50 to 0.95, or `t_out` above `t_in` gives NaN. Add the key `wall_frsi_pct` (0 is off, else 50 to 95; default 0; 1 byte in the config trailer; increase `KEY_COUNT` and `STATE_VERSION`). When the key is not 0 and `outdoor_slot` holds a fresh reading, the stats view shows `WALL <N>%` for each indoor tile (scale 1, right-aligned to x = 392, on a free line of the stats row; not drawn when the gap to the next text is below 4 px). An empty or stale tile, the outdoor tile itself, a stale outdoor slot or a NaN result shows `WALL --`. The value is calculated on each render and has no RTC state. The text uses whole percent, so `FrameRefresh` still skips most frames. Add the key to `docs/config.example.ini` and `ConfigSchema.entries`.
  - Acceptance test: Host tests assert the wall humidity for 20 C and 50 %RH inside, 0 C outside and `frsi` 0.70 (wall 14 C, about 73 %RH), the clamp at 100 %RH, NaN for `frsi` 0.49 and 0.96 and for `t_out` above `t_in`, the rejected lines `wall_frsi_pct=49` and `96`, the config trailer round trip, `WALL --` for a stale outdoor slot and for the outdoor tile, and the text pixels inside the stats row. `testConfigExampleTemplateInSync` and `ConfigSchemaTests.rangesMatchFirmwareConstants` pass. The preview script writes the updated stats image.

- [ ] Sensor battery forecast in the macOS app
  - Rank: [Total: 31, Use: 6, Setup: 7, Harmony: 8, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/BatteryForecast.swift, macos/RlcdCompanion/Sources/RlcdProtocol/ClimateLog.swift, macos/RlcdCompanion/Sources/RlcdCompanion/ClimateChartView.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/BatteryForecastTests.swift
  - Goal: The panel shows `BAT LOW` only when a sensor battery is already low. The user cannot plan the battery change. Add `BatteryForecast.estimate(rows:sensor:limitPct:)` to `RlcdProtocol`. It reads the `batteryPct` of one slot (1 to 5) from the `ClimateLog` rows of the last 60 days. It uses only the rows after the last rise of 10 % or more (a new battery). It calculates a least-squares line over the remaining rows and gives the date when the line reaches `limitPct` (`sensor_battery_limit_pct` from the last `CONFIG` reply, else 10 %). It gives `nil` for fewer than 7 days of data, for a drop below 3 %, or for a line that does not fall. The onboard sensor has no battery column and always gives `nil`. `ClimateChartView` shows `Battery change near <date>` or `Battery forecast: not enough data` for each slot. The app sends no new command and the firmware does not change.
  - Acceptance test: Swift `BatteryForecastTests` assert the date for a known line of 1 % per week from 80 %, the use of the rows after a rise from 12 % to 100 % only, `nil` for 6 days of data, for a drop of 2 %, for a flat or rising line and for the onboard sensor, the limit from a known config and the 10 % default. `swift test` and `swift build -c release` pass.

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
