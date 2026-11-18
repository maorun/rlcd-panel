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

## Priority 3: Display and Logging (Score 22–27)


## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
