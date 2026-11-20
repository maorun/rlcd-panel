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

## Priority 3: Display and Logging (Score 22–27)

- [ ] Event filter in the Android event log
  - Rank: [Total: 27, Use: 3, Setup: 6, Harmony: 8, Safety: 10, Effort: M]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/EventFilter.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/EventLogScreen.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/EventFilterTest.kt, macos/RlcdCompanion/Sources/RlcdProtocol/EventFilter.swift, tests/fixtures/events_reply_sample.txt
  - Goal: The macOS event list has category filters (`EventFilter.swift`), but the Android event log shows each row of the ring. The user cannot find the sensor or power events quickly. Add `EventFilter` to the phone package `com.rlcdpanel.phone.protocol` with the categories `ALERTS`, `SENSORS`, `POWER`, `CONFIG` and `OTHER` and the same event name lists as `EventFilter.swift`. Each name outside the lists is `OTHER`. `apply(rows, categories)` keeps the order, and an empty set keeps each row. Show one `FilterChip` for each category above the rows of `EventLogScreen`. Keep the selection in the screen state. Apply the filter to the visible rows only; `Share CSV` keeps each row. Do not send a console command for a filter change.
  - Acceptance test: In `EventFilterTest`, parse `tests/fixtures/events_reply_sample.txt` and assert 18 rows (`EventsReply.parse` gives the `EVENTS_DROPPED` row as `dropped`, not as a row), 3 for `ALERTS`, 6 for `SENSORS`, 4 for `POWER`, 2 for `CONFIG`, 3 for `OTHER` and 7 for `ALERTS` plus `POWER`. Assert that `CLOCK_SET` and `FUTURE_EVENT` are `OTHER`, that an empty set keeps 18 rows and that the order stays. Assert that the Kotlin name lists are equal to the lists of `EventFilter.swift` (read the Swift file from the repository root and compare the sets). Run `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` and `./scripts/check-protocol-sync.sh`.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
