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

- [x] Console command FRSI for the wall surface factor
  - Rank: [Total: 35, Use: 6, Setup: 10, Harmony: 9, Safety: 10, Effort: M]
  - Location: rlcd_panel/serial_console.h, rlcd_panel/climate_math.h, rlcd_panel/rlcd_panel.ino, tests/test_firmware_logic.cpp, tests/fixtures/frsi_reply_sample.txt, AGENTS.md, FEATURES.md
  - Goal: Add the console command `FRSI <R> <W>` (`SerialCommandKind::FRSI`). R is the room sensor (0 onboard, 1 to 10 a slot). W is a slot that the user attaches to the inner wall surface. Use the last 6 hourly samples of R, W and `outdoor_slot` from the RTC `ClimateHistory`. Calculate `frsi = (t_wall - t_out) / (t_room - t_out)` for each hour and the mean (`ClimateMath::wallFactor`). Reply `FRSI room=<name> wall=<name> hours=<n> frsi=<x.xx> span=<x.xx> delta_c=<x>`, the proposal line `wall_frsi_pct=<N>` (clamped to 50 to 95) and the `CRC32` line. Reply `SKIP hours=<n>` below 6 samples, `ERR LOW_DELTA` when the mean difference of room and outdoor temperature is below 10 C, `UNSTABLE span=<x>` above a span of 0.10, `ERR NO_OUTDOOR` without a fresh `outdoor_slot` reading and `ERR BAD_ARG` for equal sensors, the outdoor slot, a number above 10 or extra text. Do not access the card. Do not change the state. Run the command on USB and on BLE at each `ble_console` level.
  - Acceptance test: Host tests in `tests/test_firmware_logic.cpp` assert `ClimateMath::wallFactor` for fixed values, NaN for `t_out` above `t_room`, and each reply and error of `showFrsi` with a fixed `ClimateHistory`. Compare one reply with `tests/fixtures/frsi_reply_sample.txt`. Run `c++ -std=c++17 -Wall -Wextra -Werror tests/test_firmware_logic.cpp -o test_runner && ./test_runner && rm -f test_runner` and `arduino-cli compile rlcd_panel`. On the board, send `FRSI 0 2` over USB and verify the reply and the `CRC32` line.

- [x] Guided sensor setup assistant in the Android app
  - Rank: [Total: 35, Use: 6, Setup: 10, Harmony: 9, Safety: 10, Effort: L]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/SetupAssistantScreen.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/SetupAssistant.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/finder/SlotAssignment.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/SetupAssistantTest.kt, macos/RlcdCompanion/Sources/RlcdProtocol/SetupAssistant.swift, FEATURES.md
  - Goal: Port the step machine of the macOS `SetupAssistant` (`scan`, `choose`, `assign`, `verify`, `calibrate`, `done`) to a pure Kotlin class `SetupAssistant`. Use the `SensorFinder` scan, `SlotAssignment` (`SET slot<N>_mac=` and `SET slot<N>_name=`), `STATUS` for the verify step and the `SALT N` proposal for the calibrate step. Send each command only on a user action over the BLE console. Stop the assistant with a clear text for `ERR READ_ONLY`, `ERR FIXED_MAC`, a lost link and a sensor that stays absent in `STATUS` after 3 tries. Add one entry on the main screen.
  - Acceptance test: `SetupAssistantTest` asserts each step change, the command text of each step, each stop reason and the restart after a stop. Compare the step order with `SetupAssistantTests.swift`. Run `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone`. Install the APK with `./scripts/install-phone-apk.sh` and assign one sensor to a free slot on a USB-powered panel with `ble_console=2`.

- [x] Console command SELFTEST for the onboard hardware
  - Rank: [Total: 34, Use: 6, Setup: 10, Harmony: 9, Safety: 9, Effort: M]
  - Location: rlcd_panel/serial_console.h, rlcd_panel/rlcd_panel.ino, rlcd_panel/shtc3_sensor.h, rlcd_panel/pcf85063_rtc.h, rlcd_panel/es7210_mic.h, rlcd_panel/battery_monitor.h, tests/test_firmware_logic.cpp, tests/fixtures/selftest_reply_sample.txt, AGENTS.md, FEATURES.md
  - Goal: Add the console command `SELFTEST` (`SerialCommandKind::SELFTEST`, USB power only). Run each check once through injected functions: `SHTC3` read with retry, `PCF85063A` read and oscillator stop flag, card mount and free space, battery ADC range (2500 to 4400 mV), one 1 s `StereoAnalyzer` window and one BLE scan start and stop. Reply `BEGIN SELFTEST`, one line `CHECK <name> <OK|FAIL|SKIP> <detail>` for each check in a fixed order, `END SELFTEST pass=<N> fail=<N>` and the `CRC32` line. Skip the microphone check while muted or in quiet hours. Power down the microphones on each path. On BLE, skip the BLE scan check with `CHECK ble SKIP CONSOLE`, because the BLE console holds the radio. Run the command on BLE at each `ble_console` level. Reply `ERR BAD_ARG` for extra text. Do not change the RTC state.
  - Acceptance test: Host tests assert the reply for all checks `OK`, for each single `FAIL`, for the `SKIP` paths and the `CRC32` line with fake check functions. Compare one reply with `tests/fixtures/selftest_reply_sample.txt`. Run the host test command from `AGENTS.md` and `arduino-cli compile rlcd_panel`. On the board, send `SELFTEST` over USB with and without a MicroSD card and verify `CHECK sd FAIL NO_CARD` without a card. Send `SELFTEST` over BLE and verify `CHECK ble SKIP CONSOLE`.

- [x] Console command SPREAD for a co-location check of all sensors
  - Rank: [Total: 34, Use: 5, Setup: 10, Harmony: 9, Safety: 10, Effort: M]
  - Location: rlcd_panel/serial_console.h, rlcd_panel/sparkline.h, tests/test_firmware_logic.cpp, tests/fixtures/spread_reply_sample.txt, AGENTS.md, FEATURES.md
  - Goal: Add the console command `SPREAD` (`SerialCommandKind::SPREAD`). The user puts all sensors at one location for 6 hours. Use the last 6 hourly samples of each sensor with 6 samples from the RTC `ClimateHistory` (onboard and slots 1 to 10). Calculate the median of the group means for temperature and humidity. Reply `SPREAD sensors=<n> temp_median=<x> rh_median=<x>`, one line `SENSOR <name> temp_dev=<x> rh_dev=<x> <OK|OUTLIER>` for each sensor (`OUTLIER` above 0.5 C or 3.0 %RH), and the `CRC32` line. Reply `SKIP sensors=<n>` for fewer than 3 sensors and `ERR BAD_ARG` for extra text. Do not access the card. Do not change the state. Run the command on USB and on BLE at each `ble_console` level.
  - Acceptance test: Host tests assert the median for an odd and an even count, the `OUTLIER` limits at the exact limit and above it, the `SKIP` reply and a sensor with fewer than 6 samples. Compare one reply with `tests/fixtures/spread_reply_sample.txt`. Run the host test command from `AGENTS.md` and `arduino-cli compile rlcd_panel`.

## Priority 2: Configuration and Control (Score 28–33)

- [x] BLE reception rate in the panel stats view
  - Rank: [Total: 33, Use: 6, Setup: 9, Harmony: 8, Safety: 10, Effort: S]
  - Location: rlcd_panel/dashboard_view.h, rlcd_panel/switchbot_ble.h, tools/preview_scenes.h, tools/render_display_preview.cpp, scripts/generate-display-preview.sh, tests/test_firmware_logic.cpp, docs/display_preview_stats.png, FEATURES.md
  - Goal: Show the `ReceptionHistory` rate of each slot as `RX <N>%` in the stats view (`DashboardView::formatReception`, scale 1, right-aligned to x = 392, on the RSSI line). Show `RX --` for an empty slot and for fewer than 6 counted scans. Invert the text below 50 %. Do not draw the text when the gap to the next text is below 4 px. Calculate it on each render. Do not add RTC state.
  - Acceptance test: Host tests assert `formatReception` for 24 of 24, 11 of 24, 5 scans and an empty slot, and the inverted pixels below 50 %. Run `./scripts/generate-display-preview.sh` and inspect `docs/display_preview_stats.png`. Run the host test command from `AGENTS.md` and `arduino-cli compile rlcd_panel`.

- [x] Outdoor slot proposal in the macOS setup assistant
  - Rank: [Total: 33, Use: 6, Setup: 9, Harmony: 8, Safety: 10, Effort: M]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/OutdoorProposal.swift, macos/RlcdCompanion/Sources/RlcdProtocol/SetupAssistant.swift, macos/RlcdCompanion/Sources/RlcdCompanion/SetupAssistantView.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/OutdoorProposalTests.swift, FEATURES.md
  - Goal: Propose a value for `outdoor_slot` from the `WEEK` reply. Select the slot with the largest mean daily temperature range over at least 3 days. Propose it only when its range is at least twice the range of the next slot and its daily minimum is below the onboard minimum. Show the proposal with the slot name and the ranges in the setup assistant. Apply it on a user action through `ConfigBatch`. Show no proposal when `outdoor_slot` already has this value.
  - Acceptance test: `OutdoorProposalTests` assert a clear proposal, no proposal for two equal ranges, for fewer than 3 days, for a warm slot and for the active value. Run `swift test` and `swift build -c release` in `macos/RlcdCompanion`.

- [x] Wall factor proposal in the macOS calibration view
  - Rank: [Total: 32, Use: 5, Setup: 9, Harmony: 8, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/Calibration.swift, macos/RlcdCompanion/Sources/RlcdProtocol/ConsoleCommand.swift, macos/RlcdCompanion/Sources/RlcdCompanion/CalibrationView.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/CalibrationTests.swift, FEATURES.md
  - Goal: Add `FrsiReply.parse` for the `FRSI <R> <W>` reply (`SKIP`, `UNSTABLE`, `ERR LOW_DELTA`, `ERR NO_OUTDOOR`). Add `ConsoleCommand.frsi(room:wall:)`. Add a section to `CalibrationView` with two sensor pickers, the result and an apply button that sends `SET wall_frsi_pct=<N>` through `ConfigBatch`. This entry needs the console command `FRSI`.
  - Acceptance test: `CalibrationTests` assert each reply type of `tests/fixtures/frsi_reply_sample.txt`, `ParseError.badLine` for `frsi=x` and the batch text. Run `swift test` and `swift build -c release` in `macos/RlcdCompanion`.

- [x] Wall factor proposal in the Android calibration screen
  - Rank: [Total: 32, Use: 5, Setup: 9, Harmony: 8, Safety: 10, Effort: S]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/FrsiReply.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/CalibrationScreen.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/CalibrationBatch.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/FrsiReplyTest.kt, FEATURES.md
  - Goal: Parse the `FRSI <R> <W>` reply in `FrsiReply.parse`. Add a wall factor section to `CalibrationScreen` with two sensor menus, the result text and an apply button that sends `SET wall_frsi_pct=<N>` over the BLE console. Show `ERR READ_ONLY` as a clear text for `ble_console=1`. This entry needs the console command `FRSI`.
  - Acceptance test: `FrsiReplyTest` reads `tests/fixtures/frsi_reply_sample.txt` and asserts each reply type and a `ParseException` for a bad number. Run `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone`.

- [ ] Self-test buttons in the macOS and Android apps
  - Rank: [Total: 32, Use: 5, Setup: 9, Harmony: 9, Safety: 9, Effort: M]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/Diagnostics.swift, macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift, macos/RlcdCompanion/Sources/RlcdProtocol/DiagnosticsReport.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/DiagnosticsTests.swift, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/SelfTestReply.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/SensorHealthScreen.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/SelfTestReplyTest.kt, FEATURES.md
  - Goal: Parse the `SELFTEST` reply in `SelfTestReply` (Swift and Kotlin). Add a `Run self-test` button to `DiagnosticsView` that sends `SELFTEST` over USB or BLE. Show each check with a pass or fail symbol. Add the result to the diagnostics report. Add the same button to the Android `SensorHealthScreen`. It sends `SELFTEST` over the BLE console and shows each check with a pass or fail mark. This entry needs the console command `SELFTEST`.
  - Acceptance test: `DiagnosticsTests` and `SelfTestReplyTest` read `tests/fixtures/selftest_reply_sample.txt` and assert each check, the counts and `badLine` or `ParseException` for an unknown state. Run `swift test` and `swift build -c release` in `macos/RlcdCompanion` and `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone`.

- [x] Signal series in the Android sensor finder
  - Rank: [Total: 31, Use: 5, Setup: 9, Harmony: 7, Safety: 10, Effort: S]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/finder/SignalSeries.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/finder/SensorFinder.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/SensorFinderScreen.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/finder/SignalSeriesTest.kt, macos/RlcdCompanion/Sources/RlcdProtocol/ScanSeries.swift, FEATURES.md
  - Goal: Port `ScanSeries` to Kotlin `SignalSeries` with an injected clock. Keep the last 10 RSSI values of each MAC in 15 s windows: minimum, mean, maximum and missed windows. Show the values in each row of `SensorFinderScreen`, so the user can move a sensor to a place with a stable signal. Stop the series when the screen stops, after 10 minutes and on a lost scan permission.
  - Acceptance test: `SignalSeriesTest` asserts the statistics, the window limit of 10, the missed window count and the stop after 10 minutes with a fake clock. Run `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone`.

- [ ] Event code for a low BLE reception rate
  - Rank: [Total: 30, Use: 5, Setup: 8, Harmony: 8, Safety: 9, Effort: S]
  - Location: rlcd_panel/switchbot_ble.h, rlcd_panel/power_manager.h, rlcd_panel/sd_logger.h, rlcd_panel/rlcd_panel.ino, tests/test_firmware_logic.cpp, macos/RlcdCompanion/Sources/RlcdProtocol/EventText.swift, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/EventText.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/EventText.kt, AGENTS.md, FEATURES.md
  - Goal: Add the next free `EventCode` value `RECEPTION_LOW`. After each `ReceptionHistory::recordScan`, set a latch bit for a slot with 12 or more counted scans and a rate below 50 %. Release it at 70 % or more. Record one event for each changed bit: detail low byte the slot number 1 to 10, high byte 1 low, 0 good again. Store the latch bits in `PersistentState` (2 bytes just before the checksum) and raise `STATE_VERSION`. Reject a bit above bit 9 or of an empty slot. `resetSlot` clears the bit without an event. Add `Slot <N> weak signal` and `Slot <N> signal good` to `EventText` in the three apps and the category `sensors` to `EventFilter`.
  - Acceptance test: Host tests assert the set, the hold between 50 % and 70 %, the release, the 12-scan minimum, the RTC check and `resetSlot`. `EventTextTests`, `EventTextTest.kt` of both Kotlin apps assert the new texts. Run the host test command, `arduino-cli compile rlcd_panel`, `swift test` and `gradle testDebugUnitTest` in both Kotlin apps.

- [ ] Config backup and restore in the Android app
  - Rank: [Total: 30, Use: 4, Setup: 9, Harmony: 8, Safety: 9, Effort: M]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ConfigBackup.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/ConfigBackupScreen.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/ConfigBackupTest.kt, macos/RlcdCompanion/Sources/RlcdProtocol/ConfigBackup.swift, FEATURES.md
  - Goal: Read the `CONFIG` reply over the BLE console and save it as a `config.ini` text through the Android share sheet. Restore a selected file: parse it with the `PanelConfig` key list, show the diff against the active values, and send only the changed keys as `SET` commands in the macOS batch order (`ble_console` last). Stop at the first `ERR` reply and show the key. Use the same file format as the macOS `ConfigBackup`.
  - Acceptance test: `ConfigBackupTest` asserts the export text, the diff, the batch order, an unknown key, a bad value and the stop at the first `ERR`. Compare the export of `tests/fixtures/config_reply_sample.txt` with the macOS `ConfigBackupTests`. Run `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone`.

- [x] Signal meter for one sensor on the Wear OS watch
  - Rank: [Total: 29, Use: 4, Setup: 8, Harmony: 7, Safety: 10, Effort: M]
  - Location: wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SignalMeterScreen.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/SignalMeter.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/SignalMeterTest.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/finder/SensorAdvertisement.kt, FEATURES.md
  - Goal: Add a signal meter screen to the watch. The user selects one slot MAC from the `STATUS` reply. A foreground BLE scan with a `ScanFilter` on this MAC shows the newest RSSI, the mean of the last 10 packets and the age of the last packet in large text. The watch vibrates once when the mean changes by 10 dB or more. Stop the scan when the screen closes and after 5 minutes. Use the `SensorAdvertisement` decoder of the phone with a protocol sync check.
  - Acceptance test: `SignalMeterTest` asserts the mean, the vibration edge and the stop after 5 minutes with a fake clock. `./scripts/check-protocol-sync.sh` exits 0 with the new shared file. Run `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch`.

## Priority 3: Display and Logging (Score 22–27)

- [ ] Event codes for the wall humidity and dry air badges
  - Rank: [Total: 27, Use: 6, Setup: 3, Harmony: 8, Safety: 10, Effort: S]
  - Location: rlcd_panel/power_manager.h, rlcd_panel/sd_logger.h, rlcd_panel/rlcd_panel.ino, tests/test_firmware_logic.cpp, macos/RlcdCompanion/Sources/RlcdProtocol/EventText.swift, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/EventText.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/EventText.kt, AGENTS.md, FEATURES.md
  - Goal: Add the next two free `EventCode` values `WALL_DAMP` and `DRY_AIR`. Record one event for each changed bit of `wall_tiles` and `dry_tiles` with the same detail layout as `WINDOW_OPEN` (low byte tile 0 to 10, high byte 1 set, 0 clear). Use the pattern of `PersistentState::recordWindowChanges`. `resetSlot` and a power-on record nothing. Do not change the RTC layout and `STATE_VERSION`. Add the texts `Wall damp (<tile>)`, `Wall dry again (<tile>)`, `Dry air (<tile>)` and `Air humidity good (<tile>)` to `EventText` in the three apps.
  - Acceptance test: Host tests assert one event for each changed bit, no event for an equal mask and the detail bytes. The event text tests of the three apps assert the texts. Run the host test command, `arduino-cli compile rlcd_panel`, `swift test` and `gradle testDebugUnitTest` in both Kotlin apps.

- [ ] Open window notification in the Android app
  - Rank: [Total: 26, Use: 7, Setup: 1, Harmony: 8, Safety: 10, Effort: S]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/WindowWatch.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/StatusReply.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/WindowWatchTest.kt, FEATURES.md
  - Goal: Add `WindowWatch` with the same pattern as `WallWatch`. Read the window bits from the `STATUS` line `LATCH` at each `STATUS` read of the app. Post one notification for each rising bit with the tile name: `Window open: <name>`. Post nothing for an equal mask, for a falling bit and for the first read after the app start.
  - Acceptance test: `WindowWatchTest` asserts a rising bit, a falling bit, the first read and a missing field of an older firmware. Run `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone`.

- [ ] Panel cell battery chart in the macOS climate charts
  - Rank: [Total: 26, Use: 5, Setup: 5, Harmony: 6, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/ClimateLog.swift, macos/RlcdCompanion/Sources/RlcdCompanion/ClimateChartView.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/ClimateLogTests.swift, FEATURES.md
  - Goal: Read the `battery_pct`, `battery_v` and `usb` columns of the climate log rows. Show one chart of the panel cell voltage for the selected month. Mark the hours on USB power with a shaded band. Show `No battery data` when no row has a voltage.
  - Acceptance test: `ClimateLogTests` assert the parsed battery fields for each log format, `nil` for an empty field and the USB hour ranges. Run `swift test` and `swift build -c release` in `macos/RlcdCompanion`.

- [ ] Feels-like and absolute humidity curves in the Android history screen
  - Rank: [Total: 25, Use: 6, Setup: 1, Harmony: 8, Safety: 10, Effort: S]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/HistoryScreen.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/HistoryReply.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ClimateMath.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/HistoryReplyTest.kt, FEATURES.md
  - Goal: Calculate the feels-like temperature (`ClimateMath.heatIndexC`) and the absolute humidity (`ClimateMath.absoluteHumidityGm3`) for each hour of the panel history. Add a curve selector with `Temperature`, `Humidity`, `Feels like` and `Absolute humidity` to `HistoryScreen`. Skip an hour without a pair of values.
  - Acceptance test: `HistoryReplyTest` asserts the derived values of `tests/fixtures/history_reply_sample.txt` against `tests/fixtures/climate_math_vectors.txt` and the gap for a missing value. Run `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone`.

- [ ] Console command TAIL for the newest climate rows
  - Rank: [Total: 25, Use: 5, Setup: 4, Harmony: 8, Safety: 8, Effort: M]
  - Location: rlcd_panel/serial_console.h, rlcd_panel/sd_logger.h, tests/test_firmware_logic.cpp, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/TailReply.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/TailReplyTest.kt, AGENTS.md, FEATURES.md
  - Goal: Add the console command `TAIL <N>` (N 1 to 72). Read the newest N rows of the current `climate_v6_YYYYMM.csv` file and, if necessary, of the previous month file. Read at most 16384 bytes from the file end. Reply `BEGIN TAIL <n>`, the header, the rows oldest first and the `CRC32` line. Reply `ERR NO_CARD`, `ERR NO_FILE` and `ERR BAD_ARG` for 0, 73, a non-digit or extra text. Run the command on USB and on BLE at each `ble_console` level. Add `TailReply.parse` to the Android app with the column layout of `SdLogCodec::parseClimateRow`.
  - Acceptance test: Host tests assert the row selection over the month limit, the byte limit and each error reply with an in-memory file system. `TailReplyTest` asserts the parsed rows and `ParseException` for a wrong column count. Run the host test command, `arduino-cli compile rlcd_panel` and `gradle testDebugUnitTest` in `android/RlcdPhone`. On the board, send `TAIL 3` over BLE and verify the `CRC32` line.

- [ ] Ventilation advice complication on the Wear OS watch face
  - Rank: [Total: 22, Use: 5, Setup: 0, Harmony: 7, Safety: 10, Effort: S]
  - Location: wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/complication/ClimateComplicationService.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/complication/ClimateComplicationModel.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/ventilation/VentilationGuide.kt, wearos/RlcdWatch/app/src/main/AndroidManifest.xml, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/complication/ClimateComplicationModelTest.kt, FEATURES.md
  - Goal: Add a second complication data source with the `SHORT_TEXT` type. Show the `VentilationGuide` advice of the focused room from the `TelemetryCache` frame as one short word (`Lüften`, `Zu`, `--`) and the absolute humidity difference as the title. Show `--` for an empty cache, a failed decode and a frame older than 35 minutes. Refresh with the update period of the climate complication.
  - Acceptance test: `ClimateComplicationModelTest` asserts the text for each advice, for an old frame and for an empty cache. Run `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch`. Install the APK with `./scripts/install-watch-apk.sh` and add the complication to a watch face.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
