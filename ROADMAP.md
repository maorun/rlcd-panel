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

- [ ] Leave stale slots and the outdoor slot out of CALIB
  - **Location:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `tests/fixtures/calib_reply_sample.txt`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Stop wrong offset proposals from `CALIB N`. `SPREAD` and `DriftLatch` ignore a stale slot and the `outdoor_slot`, but `SerialConsoleRunner::showCalib` compares each sensor with the reference. Give `showCalib` the registry, the time and the config. Do not send a `SENSOR` line or a proposal line for a stale slot (`SwitchBotRegistry::isStale` with `staleTimeoutMs()`) and for the `outdoor_slot`. Send `SKIP <name> stale` and `SKIP <name> outdoor` instead. A stale or outdoor reference gives `ERR BAD_REF`. Keep the `USB` line and the reply order. Do not change the RTC layout or `STATE_VERSION`.
  - **Acceptance:** A host test checks `SKIP slot3 outdoor` and no proposal line for `outdoor_slot` 3. A host test checks `SKIP slot2 stale` for a slot with an old `updated_ms`. A host test checks `ERR BAD_REF` for `CALIB 3` with `outdoor_slot` 3. A host test checks that `outdoor_slot` 0 and fresh slots keep the old reply bytes. The macOS `CalibrationReply` and the Kotlin `CalibrationReply` parse the two new `SKIP` forms. The host test suite passes. `arduino-cli compile rlcd_panel` passes. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass. `scripts/check-protocol-sync.sh` passes.
  - **Rank:** [Total: 36, Use: 7, Setup: 10, Harmony: 9, Safety: 10, Effort: S]

- [ ] Rejected line reasons in the console command CONFIG
  - **Location:** `rlcd_panel/device_config.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/power_manager.h`, `tests/test_firmware_logic.cpp`, `tests/fixtures/config_reply_sample.txt`, `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, the Kotlin `ConfigChangedReply.kt` and `PanelConfig.kt` of the phone and the watch, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Tell the user why the panel rejected a `config.ini` line. The host tool `DeviceConfigCheck` names the reason, but the console `CONFIG` reply shows only `REJECTED line=<N>`. Store the `CheckReason` of the first four rejected lines in `ConfigRejects` (4 bytes, `UNKNOWN_KEY` 0, `BAD_VALUE` 1, `DUPLICATE_MAC` 2) in `PersistentState`, just before the checksum. Reject a reason above 2 in the RTC check. Send `REJECTED line=<N> reason=<UNKNOWN_KEY|BAD_VALUE|DUPLICATE_MAC>`. A power-on sets the reasons from the parse. Increase `STATE_VERSION` by 1. The three app parsers accept the old and the new line form and keep the reason as an optional field.
  - **Acceptance:** A host test checks `REJECTED line=4 reason=DUPLICATE_MAC` for two slots with the same MAC. A host test checks the RTC reject of reason 3. A host test checks that `CONFIG` keeps the line count. A unit test in each app parses both line forms. The host test suite passes. `arduino-cli compile rlcd_panel` passes. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass. `scripts/check-protocol-sync.sh` passes.
  - **Rank:** [Total: 34, Use: 4, Setup: 10, Harmony: 10, Safety: 10, Effort: M]

- [ ] Heat badge latch in the climate tiles
  - **Location:** `rlcd_panel/heat_latch.h` (new), `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/sd_logger.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`, `tests/test_firmware_logic.cpp`, `tests/fixtures/status_reply_sample.txt`, `docs/display_preview_heat.png`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Show which room is too hot, not only that `ALERT_HEAT` is active. Add `HeatLatch` with one bit for each tile in `PersistentState::heat_tiles` (bit 0 onboard, bits 1 to 10 slots, 2 bytes just before the checksum). Set the bit when `ClimateMath::heatIndexC` of a tile in `climate_alert_tiles` is at or above `heat_alert_limit_c`. Release it 2 C below the limit. Clear it for limit 0, an empty or stale tile and an invalid reading. Draw the inverted `H` badge beside the other badges in each tile style. Record `EventCode::HEAT_TILE` 27 (detail low byte the tile, high byte 1 hot, 0 good); `EventRing::MAX_CODE` becomes 27. Append ` heat=<3 hex digits>` to the `STATUS` line `LATCH`. Reject a bit above bit 10 or of an empty slot. `resetSlot` clears and `swapSlotsByUser` swaps the bit. Increase `STATE_VERSION` by 1.
  - **Acceptance:** A host test checks the set at the limit and the release at the limit minus 2 C. A host test checks one event for each changed bit. A host test checks the clear for a stale tile. A host test checks the RTC reject of bit 11. A host test checks the `LATCH` field. `./scripts/generate-display-preview.sh` creates `docs/display_preview_heat.png`. The host test suite passes. `arduino-cli compile rlcd_panel` passes.
  - **Rank:** [Total: 34, Use: 9, Setup: 4, Harmony: 10, Safety: 9, Effort: M]

## Priority 2: Configuration and Control (Score 28–33)

- [ ] Wear OS alert causes from the console command ALERTS
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/AlertsReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/AlertCauses.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/AlertsReplyTest.kt`, `scripts/check-protocol-sync.sh`, `FEATURES.md`
  - **Goal:** Show on the watch why an alert is active. The phone has `AlertsReply.kt` and `AlertCauses.kt`, but the watch has neither file. Copy both files to the watch package and add them to `FILES` of `scripts/check-protocol-sync.sh`. Add an `Alert causes` chip in the sensor health screen that sends `ALERTS` only on a tap. Show one row for each active alert: alert name, the tile names of the `tiles` mask (names from `PanelConfig`) and the limit with its unit. Show `Acked` for an acknowledged alert. Show `No active alert` for a reply without an active row.
  - **Acceptance:** A unit test parses `tests/fixtures/alerts_reply_sample.txt` and checks the row texts. A unit test checks the tile names for the mask `006`. A unit test checks `No active alert` for a reply with all `active` fields 0. `scripts/check-protocol-sync.sh` passes. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 33, Use: 7, Setup: 7, Harmony: 9, Safety: 10, Effort: S]

- [ ] Wear OS battery change forecast in the tail screen
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/BatteryForecast.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/TailScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/BatteryForecastTest.kt`, `scripts/check-protocol-sync.sh`, `FEATURES.md`
  - **Goal:** Show on the watch when a sensor battery needs a change. The phone has `BatteryForecast.kt` from the `TAIL` rows, but the watch has no copy. Copy the file to the watch package and add it to `FILES` of the sync check. Show one line `<name>: change near <date>` for each slot 1 to 10 with a forecast below the `TailScreen` gap report. Show `Battery forecast: read more rows` when `BatteryForecast.needsLongerLog` is true. Use the limit of `BatteryForecast.limitPct(config)`.
  - **Acceptance:** A unit test reads `tests/fixtures/tail_reply_sample.txt` and checks the forecast text of the phone test. A unit test checks the hint text for rows of less than 7 days. `scripts/check-protocol-sync.sh` passes. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 32, Use: 7, Setup: 6, Harmony: 9, Safety: 10, Effort: S]

- [ ] Wear OS monthly alert statistics in the event log
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/EventStats.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/EventLogScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/EventStatsTest.kt`, `scripts/check-protocol-sync.sh`, `FEATURES.md`
  - **Goal:** Show on the watch how often each alert occurred. The phone has `EventStats.kt`, the watch has no copy. Copy the file, add it to `FILES` of the sync check and show a `Stats` chip in the event log. Show one row for each alert with a rise: name, rise count and the sum of the active hours of the `ALERT_RISE` and `ALERT_FALL` pairs. Show `--` for an alert that is still active.
  - **Acceptance:** A unit test parses `tests/fixtures/events_reply_sample.txt` and checks the counts of the phone test. A unit test checks `--` for an open pair. `scripts/check-protocol-sync.sh` passes. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 30, Use: 6, Setup: 5, Harmony: 9, Safety: 10, Effort: S]

- [ ] Heat hours in the three week charts
  - **Location:** `macos/RlcdCompanion/Sources/RlcdCompanion/ClimateChartView.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/WeekMold.swift`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/WeekScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/WeekScreen.kt`, the test files of the three apps, `FEATURES.md`
  - **Goal:** Show the weekly heat hours that the `WEEK` reply sends. The three `WeekReply` parsers read `heatHours`, but no week chart shows it. Add the label `HEAT <N> h` (sum over the days with a value, 0 to 168) beside the mold and dry labels of each sensor. Show no label when no day has a value.
  - **Acceptance:** A unit test in each app reads `tests/fixtures/week_heat_reply_sample.txt` and checks the sum label. A unit test checks no label for the 9-column fixture. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` passes. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 30, Use: 7, Setup: 3, Harmony: 10, Safety: 10, Effort: S]

- [ ] Room name in the Android alert notification
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/BleScanService.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/AlertNotice.kt` (new), `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/AlertNoticeTest.kt`, `FEATURES.md`
  - **Goal:** Tell the user which room caused an alert. `BleScanService` writes only the alert names (`AlertMask.names`) as the text of `RLCD Alarm`. Add `AlertNotice.text(bit, frame, config)`. For `HEAT` name the tile with the highest `ClimateMath.heatIndexC`: `HEAT: <room> feels like <T> °C`. For `FREEZER` name the coldest tile. For `HUMIDITY` name the most humid tile. Use only the tiles of `climate_alert_tiles` with a valid mask bit. Keep the old text for the other bits and for a frame without a usable tile.
  - **Acceptance:** A unit test checks the `HEAT` text for a frame with two hot tiles. A unit test checks the `FREEZER` and `HUMIDITY` texts. A unit test checks the old text for `NOISE`. A unit test checks the old text when `climate_alert_tiles` excludes each tile. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` passes.
  - **Rank:** [Total: 30, Use: 9, Setup: 1, Harmony: 10, Safety: 10, Effort: S]

- [ ] Heat badge masks in the three app sensor lists
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/Diagnostics.swift`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/StatusReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/StatusReply.kt`, the three sensor health views, the test files, `FEATURES.md`
  - **Goal:** Show the hot rooms in the apps. Depends on `Heat badge latch in the climate tiles`. Parse `heat=` of the `LATCH` line (`nil` or `null` for a missing field, `ParseError.badLine` or `ParseException` for bad hex). Show a heat symbol in the row of each tile with its bit set.
  - **Acceptance:** A unit test in each app parses the new fixture line. A unit test checks `nil` or `null` for an old line. `scripts/check-protocol-sync.sh` passes. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` passes. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 30, Use: 7, Setup: 3, Harmony: 10, Safety: 10, Effort: S]

- [ ] Heat hours column in the three week CSV exports
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/CsvExport.swift`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/WeekCsv.kt`, the test files, `FEATURES.md`
  - **Goal:** Keep the heat hours in the CSV export of the week. `WeekReply.header` stays the 9-column header, so the export drops `heat_h`. Write the 10-column header `WeekReply.heatHeader` and `heat_h` as the last column. Write an empty field for `nil` or `null`.
  - **Acceptance:** A unit test exports the rows of `tests/fixtures/week_heat_reply_sample.txt` and checks the header and one row. A unit test checks an empty field for a gap day. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` passes.
  - **Rank:** [Total: 29, Use: 5, Setup: 3, Harmony: 9, Safety: 10, Effort: S]

- [ ] Heat hours column in the console command TODAY
  - **Location:** `rlcd_panel/serial_console.h`, `rlcd_panel/sparkline.h`, `tests/test_firmware_logic.cpp`, `tests/fixtures/today_heat_reply_sample.txt`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Show the heat hours of the current day. `TODAY` has `mold_h` and `dry_h`, but no heat column. Add `ClimateHistory::heatHoursNewest(sensor, count, limit_c)` (pairs among the newest `count` samples with `ClimateMath::heatIndexC` at or above the limit, -1 for limit 0, unequal history sizes and no pair). Append the column `heat_h` (12 columns). Write an empty field for -1. Keep `today_hours_reply_sample.txt` for the app parsers and add a new 12-column fixture. Do not change the RTC layout or `STATE_VERSION`.
  - **Acceptance:** A host test checks `heat_h` 2 for two hot pairs since midnight. A host test checks an empty field for limit 0. A host test checks the 12-column header. A host test checks `TODAY 1` with the filtered row and the `CRC32` line. The host test suite passes. `arduino-cli compile rlcd_panel` passes.
  - **Rank:** [Total: 29, Use: 6, Setup: 3, Harmony: 10, Safety: 10, Effort: S]

- [ ] App parsers for the TODAY heat hours column
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/TodayReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/TodayReply.kt`, the three test files, `FEATURES.md`
  - **Goal:** Read the `heat_h` column of `TODAY`. Accept 9, 11 and 12 columns by the header. Add `TodayRow.heatHours` (`Int?`, `nil` or `null` for fewer columns and for an empty field). Show `Heat <N> h` in the three today views beside the mold and dry hours.
  - **Acceptance:** A unit test in each app reads `tests/fixtures/today_heat_reply_sample.txt` and checks `heatHours`. A unit test checks `nil` or `null` for the 11-column fixture. A unit test checks `ParseError.badLine` or `ParseException` for a negative value. `scripts/check-protocol-sync.sh` passes. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` passes. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 29, Use: 6, Setup: 3, Harmony: 10, Safety: 10, Effort: S]

- [ ] macOS weak signal advice in the reception strip
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/ReceptionWatch.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/ReceptionTests.swift`, `FEATURES.md`
  - **Goal:** Tell the macOS user what to do for a weak slot. The Android app has `ReceptionNotice.kt` with advice texts, the macOS strip shows only the rate. Add `ReceptionWatch.advice(rate:rssi:)`: rate below 50 % and RSSI below -85 dBm gives `Move the sensor nearer to the panel`, rate below 50 % with a better RSSI gives `Check the sensor battery`. Show the text below the strip row.
  - **Acceptance:** A unit test checks both texts at the limits -85 and -86 dBm. A unit test checks no text at 50 %. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.
  - **Rank:** [Total: 29, Use: 5, Setup: 8, Harmony: 6, Safety: 10, Effort: S]

- [ ] Wear OS door reference and outdoor slot proposals
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/DoorProposal.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/OutdoorProposal.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/DoorProposalTest.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/OutdoorProposalTest.kt`, `scripts/check-protocol-sync.sh`, `FEATURES.md`
  - **Goal:** Let the watch user set `outdoor_slot` and `door_ref_slot` from a proposal. The phone and macOS setup assistants have both proposals, the watch has none. Copy both files from the phone and add them to `FILES` of the sync check. Show a `Setup proposals` chip that sends `HISTORY` and `CONFIG` only on a tap. Show each proposal with an `Apply` chip that sends one `SET` and then `CONFIG`. Map `ERR READ_ONLY` to `Set ble_console=2`.
  - **Acceptance:** A unit test reads `tests/fixtures/history_reply_sample.txt` and checks the proposal of the phone test. A unit test checks the `SET` line of each proposal. `scripts/check-protocol-sync.sh` passes. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 29, Use: 4, Setup: 9, Harmony: 9, Safety: 7, Effort: M]

- [ ] Wear OS diagnostics report share
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/DiagnosticsReport.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/DiagnosticsReportTest.kt`, `scripts/check-protocol-sync.sh`, `FEATURES.md`
  - **Goal:** Let the watch user send one support report. The phone has `DiagnosticsReport.kt`, the watch has none. Copy the file and add it to `FILES` of the sync check. Build the report from the last `STATUS`, `VERSION`, `SELFTEST` and `RECEPTION` replies in memory. Send it with a share intent to the paired phone (`Intent.ACTION_SEND`, `text/plain`). Do not send a console command for the report.
  - **Acceptance:** A unit test builds the report from the fixtures and checks the section order of the phone test. A unit test checks the text `not read` for a missing reply. `scripts/check-protocol-sync.sh` passes. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 28, Use: 3, Setup: 8, Harmony: 8, Safety: 9, Effort: S]

## Priority 3: Display and Logging (Score 22–27)

- [ ] Heat hours in the week chart view of the panel
  - **Location:** `rlcd_panel/dashboard_view.h`, `rlcd_panel/sparkline.h`, `tests/test_firmware_logic.cpp`, `tools/preview_scenes.h`, `docs/display_preview_chart_week.png`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Show the weekly heat hours on the panel. `WeekHistory` stores heat hours, but the `WEEK_CHART` view shows only `MOLD` and `DRY`. Add `DashboardView::formatWeekHeat` (`HEAT <N>H`, sum over the days with `hasHeatHours`, empty without such a day). Append it to the humidity label line after the `DRY` text, separated by two spaces. Do not draw it when the line is wider than the box.
  - **Acceptance:** A host test checks `HEAT 7H` for 3 days with 2, 2 and 3 hours. A host test checks the empty text without a day. A host test checks that a full label line drops the heat text. `./scripts/generate-display-preview.sh` creates the chart image. The host test suite passes. `arduino-cli compile rlcd_panel` passes.
  - **Rank:** [Total: 27, Use: 6, Setup: 2, Harmony: 9, Safety: 10, Effort: S]

- [ ] Heat hours columns in the daily log (daily_v7)
  - **Location:** `rlcd_panel/sd_logger.h`, `rlcd_panel/sparkline.h`, `rlcd_panel/serial_console.h`, `scripts/dump-logs.sh`, `macos/RlcdCompanion/Sources/RlcdProtocol/DailyLog.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/LogArchive.swift`, the Kotlin `DaysReply.kt` of the phone and the watch, `tests/test_firmware_logic.cpp`, `tests/fixtures/days_reply_v7_sample.txt`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Keep the heat hours of each day on the card. Add `heat_h_0` to `heat_h_10` after the dry hours (105 columns, `DAILY_COLUMNS`) in `daily_v7_YYYY.csv`. Keep `daily_v6` files unchanged. `DAYS` sends the `daily_v7` header and fills 11 empty fields for each `daily_v6` row. Add `heat_h` to `WeekHistory::RestoreRow`. Add `daily_v7` to the name pattern of `LIST`, `DUMP`, the retention, `dump-logs.sh` and `LogArchive.swift`.
  - **Acceptance:** A host test checks the 105-column row. A host test checks the restore of the heat hours from `daily_v7` and gaps from `daily_v6`. A host test checks the `DAYS` reply across a `daily_v6` and a `daily_v7` file. `./scripts/dump-logs.sh --self-test` passes. A unit test in each app parses the new fixture. The host test suite passes. `arduino-cli compile rlcd_panel` passes. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass. `scripts/check-protocol-sync.sh` passes.
  - **Rank:** [Total: 27, Use: 5, Setup: 3, Harmony: 10, Safety: 9, Effort: L]

- [ ] Android heat hours in the days screen
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/DaysReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/DaysReply.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/DaysScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/DaysReplyTest.kt`, `FEATURES.md`
  - **Goal:** Show the heat hours of each day on the phone. Depends on `Heat hours columns in the daily log (daily_v7)`. Accept 83, 94 and 105 columns by the header. Add `DaysRow.heatHours` (`null` for fewer columns and an empty field). Show `Heat <N> h` beside mold and dry hours of each day.
  - **Acceptance:** A unit test parses `tests/fixtures/days_reply_v7_sample.txt`. A unit test checks `null` for `days_reply_v6_sample.txt`. A unit test checks `ParseException` for a value of 25. `scripts/check-protocol-sync.sh` passes. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` passes.
  - **Rank:** [Total: 27, Use: 6, Setup: 2, Harmony: 9, Safety: 10, Effort: S]

- [ ] macOS heat hours in the daily charts
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/DailyLog.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DailyChartView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/DailyLogTests.swift`, `FEATURES.md`
  - **Goal:** Prepare the macOS daily charts for the heat hours of `daily_v7`. Depends on `Heat hours columns in the daily log (daily_v7)`. Add `DailyRow.heatHours` (one `Int?` for each sensor, `[]` for older files). Show a heat bar chart for each sensor below the dry hours chart. Show no chart when all values are `nil`.
  - **Acceptance:** A unit test parses `tests/fixtures/days_reply_v7_sample.txt` and checks `heatHours`. A unit test checks `[]` for a `daily_v6` file. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.
  - **Rank:** [Total: 27, Use: 6, Setup: 1, Harmony: 10, Safety: 10, Effort: S]

- [ ] Wear OS sensor battery forecast in the tile
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/tile/ClimateTileModel.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/tile/ClimateTileService.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/ClimateTileModelTest.kt`, `FEATURES.md`
  - **Goal:** Show the next battery change in the watch tile. Keep the last forecast of the tail screen in `TelemetryCache` (slot, date). Show `Battery: <name> <date>` below the alert names when the date is within 30 days. Show nothing for an older or missing forecast.
  - **Acceptance:** A unit test checks the line for a date 10 days ahead. A unit test checks no line for a date 40 days ahead and for an empty cache. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 25, Use: 6, Setup: 2, Harmony: 7, Safety: 10, Effort: S]

- [ ] Wear OS heat hours of the day on the today screen
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/TodayScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/TodayScreenTextTest.kt`, `FEATURES.md`
  - **Goal:** Show the heat hours of the current day on the watch. Depends on `App parsers for the TODAY heat hours column`. Add `Heat <N> h` to the room row after the dry hours. Show no text for `null`. Show a warning color above 0.
  - **Acceptance:** A unit test checks the row text for `heatHours` 2. A unit test checks no heat text for `null`. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 25, Use: 6, Setup: 1, Harmony: 8, Safety: 10, Effort: S]

- [ ] Badges in the Android panel preview
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/PanelRenderer.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/PanelPreviewScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/PanelRendererTest.kt`, `FEATURES.md`
  - **Goal:** Show the `V`, `D`, `W`, `C`, `R` and `S` badges in the phone preview. The preview has no badges because the telemetry frame does not carry them. Read the badge masks from the last `STATUS` `LATCH` line in memory when it exists. Draw each badge with the positions of `dashboard_view.h`. Keep the label `Preview from telemetry, not the panel image`.
  - **Acceptance:** A unit test renders a frame with the `vent` bit of tile 1 and checks the inverted badge pixels against a reference from `tools/render_display_preview.cpp`. A unit test checks no badge without a `STATUS` reply. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` passes.
  - **Rank:** [Total: 25, Use: 4, Setup: 3, Harmony: 8, Safety: 10, Effort: M]

- [ ] Self-test script for install-phone-apk.sh
  - **Location:** `scripts/install-phone-apk.sh`, `.github/workflows/ci.yml`, `tests/test_scripts.sh`, `FEATURES.md`
  - **Goal:** Test the target resolution of the phone install script like `install-watch-apk.sh`. Add `--self-test` with fake `adb` through `RLCD_ADB_BIN`: a serial, `host:port` and no argument. Check the `adb -s` arguments. Add `-h`/`--help`. Run the self-test in the CI job `Host tests`.
  - **Acceptance:** `./scripts/install-phone-apk.sh --self-test` exits 0. A broken resolution exits 1. `tests/test_scripts.sh` passes.
  - **Rank:** [Total: 24, Use: 1, Setup: 5, Harmony: 8, Safety: 10, Effort: S]

- [ ] Self-test of check-protocol-sync.sh against an unlisted shared file
  - **Location:** `scripts/check-protocol-sync.sh`, `.github/workflows/ci.yml`, `FEATURES.md`
  - **Goal:** Find a protocol file that exists in both apps but is not in `FILES`. Add a check: list the `.kt` files with the same name in both `protocol` directories, remove the package line, and print `UNLISTED <name>` for an equal pair outside `FILES`. Exit 1 for an unlisted equal pair. Keep a different pair outside `FILES` as no error.
  - **Acceptance:** The `--self-test` checks one unlisted equal pair (exit 1) and one unlisted different pair (exit 0). `./scripts/check-protocol-sync.sh` passes for the current tree after the needed `FILES` entries.
  - **Rank:** [Total: 24, Use: 1, Setup: 4, Harmony: 9, Safety: 10, Effort: S]

- [ ] Parser fuzz test for the console reply assemblers
  - **Location:** `macos/RlcdCompanion/Tests/RlcdProtocolTests/ReplyAssemblerTests.swift`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/ReplyAssemblerTest.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/ReplyAssemblerTest.kt`, `FEATURES.md`
  - **Goal:** Make sure that a damaged BLE reply never crashes an app. Feed each fixture of `tests/fixtures` through the `ReplyAssembler` of each app with fixed seeds: drop one byte, flip one byte, cut the reply at each position of the first 200 bytes. The assembler must give a CRC error or an incomplete reply, never an exception other than the parse error types. Use a fixed seed list so the test is repeatable.
  - **Acceptance:** The new tests pass in the three apps. A test fails when you remove the CRC check of the assembler. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` passes. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 24, Use: 2, Setup: 3, Harmony: 9, Safety: 10, Effort: M]

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
