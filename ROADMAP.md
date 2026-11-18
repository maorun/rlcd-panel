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

- [ ] Panel configuration over the BLE console in the Android app
  - Rank: [Total: 35, Use: 8, Setup: 9, Harmony: 9, Safety: 9, Effort: M]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/console/BleConsoleClient.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/PanelConfig.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ReplyAssembler.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/SensorHealthScreen.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/PanelConfigTest.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/console/BleConsoleClientTest.kt, tests/fixtures/config_reply_sample.txt
  - Goal: `PanelConfig.load` reads only a bundled asset or a manual file, so names and MACs become stale after each `SET`. `BleConsoleClient` sends only the fixed text `STATUS`. Replace the fixed command with `send(command, callback)` and keep one GATT connection for each command. Add a `Read panel config` button that sends `CONFIG`. Parse the reply with `PanelConfig.parseConfigReply` and keep the `REJECTED line=<N>` lines apart. Store the text through `config.ini.tmp` and a rename in `filesDir`. Make `PanelConfig.load` prefer this cache to the external file and the asset. Show the cache age and the rejected lines in `SensorHealthScreen`. Keep the `ReplyAssembler` CRC-32 check; a bad CRC-32 keeps the old cache. The panel answers only on USB power with `ble_console` 1 or 2.
  - Acceptance test: Add `tests/fixtures/config_reply_sample.txt` and parse it in `PanelConfigTest.parsesConfigReply` (names, MACs, `outdoor_slot`, rejected lines). Assert in `PanelConfigTest.cachePreferredOverAsset` that the cache wins. Assert that a bad CRC-32 and an `ERR` reply keep the old cache. Assert in `BleConsoleClientTest` that `send` writes the command bytes plus `\n` in MTU chunks. Run `gradle testDebugUnitTest` in `android/RlcdPhone`.

- [ ] Calibration proposals in the Android app
  - Rank: [Total: 34, Use: 7, Setup: 10, Harmony: 9, Safety: 8, Effort: M]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/CalibrationReply.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/CalibrationScreen.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/console/BleConsoleClient.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/CalibrationReplyTest.kt, tests/fixtures/calib_reply_sample.txt, macos/RlcdCompanion/Tests/RlcdProtocolTests/CalibrationTests.swift
  - Goal: Only the macOS app shows the `CALIB N` and `SALT N` proposals, so a user without a Mac cannot calibrate. Depend on the generic `BleConsoleClient.send` of the entry above. Port `CalibrationReply` and `SaltReply` from `Calibration.swift` to Kotlin with the same strict number rules. Add a `CalibrationScreen` with a reference sensor picker (0 to 5), the `SENSOR`, `SKIP`, `USB` and proposal lines, and one checkbox for each proposal. Send the selected proposals as `SET` commands, then `CONFIG`. Stop at the first `ERR` line. Show `ERR READ_ONLY` as the text `Set ble_console=2 to apply`. Keep the proposals that the key range rejects out of the list.
  - Acceptance test: Add `tests/fixtures/calib_reply_sample.txt` with a `SENSOR`, a `SKIP`, a `USB` and two proposal lines. Parse it in `CalibrationReplyTest` and in the Swift `CalibrationTests.sharedFixture`, and assert equal values. Assert that `hours=x` throws an error. Assert that the batch stops after an `ERR READ_ONLY` reply and sends no `CONFIG`. Run `gradle testDebugUnitTest` in `android/RlcdPhone` and `swift test` in `macos/RlcdCompanion`.

## Priority 2: Configuration and Control (Score 28–33)

- [ ] NTP sync state in STATUS and in the companion diagnostics
  - Rank: [Total: 32, Use: 5, Setup: 9, Harmony: 8, Safety: 10, Effort: S]
  - Location: rlcd_panel/serial_console.h, rlcd_panel/ntp_sync.h, rlcd_panel/rlcd_panel.ino, macos/RlcdCompanion/Sources/RlcdProtocol/Diagnostics.swift, macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/StatusReply.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/SensorHealthScreen.kt, tests/fixtures/status_reply_sample.txt, tests/test_firmware_logic.cpp
  - Goal: `NtpSyncState` holds the last attempt, the result and the failures in a row, but no console command shows them. The user cannot see why the clock does not sync. Add the `STATUS` line `NTP ok=<0|1|--> failures=<N> age_s=<N|--> next_s=<N|-->` after the `RTC` line. Calculate `next_s` with `NtpSchedule::retryDelayMs` and `ntp_sync_interval_h`. Show `--` without an attempt and without Wi-Fi credentials. Read only the RTC copy; do not start Wi-Fi. Parse the line in `NtpMetrics` (Swift) and `StatusReply.ntp` (Kotlin), with `nil` for `--` and for a missing line. Show a warning symbol at 3 failures or more.
  - Acceptance test: Add `testStatusNtpLine` with no attempt, one success and four failures, and assert each field. Update the line index and the fixed CRC-32 in `testStatusRuntimeLinePositionCrcAndNoSideEffect`. Add the `NTP` line to `tests/fixtures/status_reply_sample.txt`. Assert the parse in `DiagnosticsTests.ntpLine` and `StatusReplyTest.ntpLine`, and assert `ParseError.badLine` for `failures=x`. Run the host unit tests, `swift test` and `gradle testDebugUnitTest`.

- [ ] Outdoor location setting in the Android app
  - Rank: [Total: 30, Use: 8, Setup: 7, Harmony: 6, Safety: 9, Effort: S]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/ventilation/OutdoorSource.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/ventilation/OutdoorWeatherService.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/ventilation/OutdoorLocationStore.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/PhoneDashboardScreen.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/ventilation/OutdoorLocationStoreTest.kt
  - Goal: `OutdoorSource.OpenMeteo` uses the fixed Berlin coordinates 52.52 N 13.41 E. A user in another city gets wrong ventilation advice. Add a settings dialog with latitude, longitude and a place name. Validate latitude -90 to 90 and longitude -180 to 180, with at most 4 decimals. Store the values in `SharedPreferences` through `OutdoorLocationStore`. Keep Berlin as the default. Do not request a location permission. Clear the cached Open-Meteo reading after a change, so the next advice uses the new place.
  - Acceptance test: Assert in `OutdoorLocationStoreTest` the default, a round trip, the rejection of latitude 91 and longitude -181, and the rejection of a non-numeric text. Assert that `OutdoorWeatherService.requestUrl` contains the stored coordinates with 4 decimals. Assert that a change clears the cached reading in `OutdoorResolverTest`. Run `gradle testDebugUnitTest` in `android/RlcdPhone`.

- [ ] Seven-day chart over the BLE console in the Android app
  - Rank: [Total: 28, Use: 7, Setup: 2, Harmony: 9, Safety: 10, Effort: S]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/WeekReply.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/WeekScreen.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/WeekReplyTest.kt, tests/fixtures/week_reply_sample.txt, macos/RlcdCompanion/Tests/RlcdProtocolTests/ReplyTests.swift
  - Goal: `TelemetryHistory` holds only the data that the phone received while it scanned. The panel keeps 7 days of minimum and maximum values in `WeekHistory`, and the `WEEK` command runs on BLE. Depend on the generic `BleConsoleClient.send`. Port `WeekReply.parse` from `Replies.swift` to Kotlin. Add a `WeekScreen` with 7 bars from minimum to maximum for temperature and humidity of each sensor. Show a gap day as an empty bar. Use the `PanelConfig` names for the sensor titles.
  - Acceptance test: Add `tests/fixtures/week_reply_sample.txt` with 42 rows and one gap day. Parse it in `WeekReplyTest` and in the Swift `ReplyTests.weekSharedFixture`, and assert equal values. Assert `null` for an empty field and an error for a wrong column count. Run `gradle testDebugUnitTest` in `android/RlcdPhone` and `swift test` in `macos/RlcdCompanion`.

- [ ] Protocol sync check for the Wear OS and Android sources
  - Rank: [Total: 28, Use: 3, Setup: 5, Harmony: 10, Safety: 10, Effort: S]
  - Location: scripts/check-protocol-sync.sh, .github/workflows/ci.yml, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/ClimateMathTest.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/ClimateMathTest.kt, tests/fixtures/climate_math_vectors.txt
  - Goal: `AGENTS.md` requires equal protocol code in `wearos/RlcdWatch` and `android/RlcdPhone`, but no check enforces it. The Kotlin tests also do not read `tests/fixtures/climate_math_vectors.txt`. Add `scripts/check-protocol-sync.sh`. Compare `AlertTransitions.kt`, `Checksums.kt`, `ClimateMath.kt`, `PanelConfig.kt` and `TelemetryFrame.kt` after the removal of the `package` line. Print each file that differs and exit 1. Add `--self-test` with a fixed good pair and a bad pair. Add a Wear OS `ClimateMathTest` and make both Kotlin tests read the shared vectors.
  - Acceptance test: Run `scripts/check-protocol-sync.sh` in the CI job `Host tests` and assert exit 0. Run `scripts/check-protocol-sync.sh --self-test` and assert that the bad pair gives exit 1. Assert in both `ClimateMathTest` classes that each vector of `climate_math_vectors.txt` matches within its tolerance. Run `gradle testDebugUnitTest` in `android/RlcdPhone` and `wearos/RlcdWatch`.

## Priority 3: Display and Logging (Score 22–27)

- [ ] Restore of the seven-day history from the daily file
  - Rank: [Total: 26, Use: 7, Setup: 3, Harmony: 8, Safety: 8, Effort: M]
  - Location: rlcd_panel/sparkline.h, rlcd_panel/sd_logger.h, rlcd_panel/rlcd_panel.ino, tests/test_firmware_logic.cpp
  - Goal: A power-on sets each `WeekHistory` day to a gap, so the `WEEK_CHART` view and the `WEEK` command stay empty for 7 days. `ClimateHistory::restore` refills only the 24-hour histories. Add `SdLogCodec::parseDailyRow` for the current `daily_v5_YYYY.csv` format. Add `WeekHistory::restore`, which takes the rows of the last 7 days before the RTC date and sets the stamp. Read the last 4096 bytes of the current year file, and of the previous year file in the first week of January. Run it in `restoreHistoriesFromCard` for a power-on only. An invalid RTC time, a week with data and a bad row change nothing. Do not change the RTC state layout.
  - Acceptance test: Add `testParseDailyRow` for a good row, an empty field, a wrong column count and a bad date. Add `testWeekRestoreFromDailyRows` with 5 rows and one missing day, and assert the gap and the stamp. Add `testWeekRestoreYearChange` and `testWeekRestoreNoChange` for an invalid RTC time and a non-empty week. Run `c++ -std=c++17 -Wall -Wextra -Werror tests/test_firmware_logic.cpp -o test_runner && ./test_runner && rm -f test_runner` and `arduino-cli compile rlcd_panel`.


## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
