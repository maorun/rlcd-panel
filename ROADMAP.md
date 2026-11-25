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

- [ ] Config key `heat_alert_delay_h` for the heat alert
  - **Location:** `rlcd_panel/device_config.h`, `rlcd_panel/audio_alert.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `docs/config.example.ini`, `macos/RlcdCompanion/Sources/RlcdProtocol/ConfigSchema.swift`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ConfigSchema.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/ConfigSchema.kt`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Hold the heat alert back during a short warm peak, as `mold_alert_delay_h` and `dry_alert_delay_h` do for the other alerts. Add the key `heat_alert_delay_h` (0 is off, else 1 to 12 h, default 0; 1 byte at the end of the config trailer, after `drift_limit_rh`; a value above 12 is a rejected line). Add `AlertLimits::heat_delay_h` and use the `history` pointer that `mold_delay_h` uses. Let `AlertEvaluator::Tally::add` raise `ALERT_HEAT` for a tile only when the live feels-like value is at or above `heat_alert_limit_c` and the newest N hourly sample pairs of the tile give a `ClimateMath::heatIndexC` value at or above the limit. Take the temperature sample and the humidity sample of the same index, because each hourly push writes both. Give no rise for fewer than N pairs, for an invalid pair and for a delay without a history pointer. Keep the hold rule and the release 2 C below the limit. Set `KEY_COUNT` to 104 and `STATE_VERSION` to 82. Add the key to `docs/config.example.ini` and to the three `ConfigSchema` tables.
  - **Acceptance:** A host test raises the alert with delay 2 and two hourly pairs at or above the limit. A host test gives no rise with delay 2 and one pair. A host test gives no rise when the older pair is below the limit. A host test gives no rise for a delay without a history pointer. A host test keeps the release 2 C below the limit with an active delay. A host test rejects the line `heat_alert_delay_h=13` and accepts 12. A host test checks the RTC restore of the new byte. The template test and the `KEY_COUNT` test pass. The host test suite passes. `arduino-cli compile rlcd_panel` passes. `swift test` in `macos/RlcdCompanion` and `gradle testDebugUnitTest` in `android/RlcdPhone` and `wearos/RlcdWatch` pass.
  - **Rank:** [Total: 31, Use: 8, Setup: 6, Harmony: 8, Safety: 9, Effort: M]

- [ ] Dry hours in the Android week chart
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/WeekScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/WeekChartModelTest.kt`, `FEATURES.md`
  - **Goal:** Show the dry hours of each day beside the mold hours in the Android week chart. The `WEEK` reply holds the column `dry_h` and `WeekRow.dryHours` parses it, but the chart shows only the mold hours. Add `WeekChartModel.dry(rows, sensor)` with the rules of `WeekChartModel.mold`: seven values from day 6 (left) to day 0 (right), `null` for a gap day, for a missing row and for a reply without the column. Draw a second bar row below the mold bars with the label `0 bis 24, max. <N> h`. Draw no bar row when each value is `null`. Send no new console command.
  - **Acceptance:** A unit test gives the seven dry values of one sensor in day order. A unit test gives `null` for a gap day and for a missing row. A unit test gives seven `null` values for a reply with the 8-column header. A unit test checks the maximum in the label. A unit test checks that a sensor without a dry value draws no bar row. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` passes.
  - **Rank:** [Total: 30, Use: 7, Setup: 5, Harmony: 8, Safety: 10, Effort: S]

- [ ] Jump guard advice on the Wear OS watch
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/JumpAdvice.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/JumpAdviceTest.kt`, `FEATURES.md`
  - **Goal:** Tell the user on the watch that the jump guard drops the readings of one slot, and offer a higher limit. Port `JumpAdvice` of the Android app with the same rules: key `jump_limit_c`, event `JUMP_DROP`, `MIN_DROPS` 3, `WINDOW_MS` 24 h, `STEP_C` 2.0 and `MAX_C` 20.0. Build one advice for each slot with three or more `JUMP_DROP` rows of the `EVENTS` reply in the newest 24 hours, in slot order. Give no advice for `jump_limit_c` 0, for a limit at 20.0 C, for a missing or unreadable `jump_limit_c` in the cached `PanelConfig`, for a row without a valid time and for a detail outside slot 1 to 10. Show one short line for each advice in the sensor health screen, and an `Apply` chip that sends `SET jump_limit_c=<x>` and then `CONFIG`. Send no automatic command.
  - **Acceptance:** A unit test gives one advice for three drops of one slot. A unit test gives none for two drops. A unit test gives none for `jump_limit_c=0` and for `jump_limit_c=20.0`. A unit test gives none without a cached `jump_limit_c`. A unit test ignores a row with an empty time field and a detail of 11. A unit test checks the proposal `jump_limit_c=10.0` for a limit of 8.0. A unit test checks the line text of the screen. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 30, Use: 4, Setup: 9, Harmony: 7, Safety: 10, Effort: S]

- [ ] Reception check in the console command SELFTEST
  - **Location:** `rlcd_panel/serial_console.h`, `tests/test_firmware_logic.cpp`, `tests/fixtures/selftest_reply_sample.txt`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Report a weak BLE link of a sensor slot in the self-test, so one command shows each hardware problem. Add the check `reception` as the seventh row after `ble`. Read the RTC copy of `ReceptionHistory` and the key `reception_low_pct`. Give `SKIP OFF` for the limit 0, `SKIP NO_SLOT` without an occupied slot, and `SKIP FEW_SCANS` when no slot reaches `RECEPTION_LOW_MIN_SCANS` 12 counted scans. Give `FAIL LOW=<N>` with the number of the first slot below the limit, and `OK <N>%` with the lowest rate of the counted slots. Count the row in `END SELFTEST pass=<N> fail=<N>` like each other row. Do not read the card, do not start the microphones, and do not change the state. Add the line to `tests/fixtures/selftest_reply_sample.txt`. The macOS, Android and Wear OS parsers read each `CHECK` line by name, so the apps show the row without a change.
  - **Acceptance:** A host test gives `SKIP OFF` for `reception_low_pct` 0. A host test gives `SKIP NO_SLOT` for an empty registry. A host test gives `SKIP FEW_SCANS` for a slot with 6 counted scans. A host test gives `FAIL LOW=2` for slot 2 with 12 scans and 5 packets. A host test gives `OK 83%` for 12 scans and 10 packets. A host test checks the `pass` and `fail` counts with the new row. A host test compares the reply with the updated fixture. The host test suite passes. `arduino-cli compile rlcd_panel` passes.
  - **Rank:** [Total: 30, Use: 4, Setup: 9, Harmony: 7, Safety: 10, Effort: S]

- [ ] Alert limit lines in the Android history chart
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ChartLimits.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/HistoryScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/ChartLimitsTest.kt`, `FEATURES.md`
  - **Goal:** Show the active alert limits as dashed lines in the Android history charts, so the user sees how near a room is to an alert. Port `ChartLimits.from(config:)` of the macOS app with the same four definitions: `mold_alert_limit_rh` (humidity), `dry_alert_limit_rh` (humidity, 0 is off), `freeze_alert_limit_c` (temperature, 0 is a valid limit) and `heat_alert_limit_c` (temperature, 0 is off). Give no line for a value outside the `ConfigSchema` range, for a missing key and for a missing `CONFIG` reply. Add `humidity(limits)` and `temperature(limits)`. Draw each line with its label in the matching chart. Send no console command.
  - **Acceptance:** A unit test gives four lines for four active limits. A unit test gives no line for `dry_alert_limit_rh=0` and one line for `freeze_alert_limit_c=0`. A unit test gives no line for a value outside the range. A unit test gives an empty list without a `CONFIG` reply. A unit test checks the split into humidity and temperature lines. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` passes.
  - **Rank:** [Total: 29, Use: 7, Setup: 5, Harmony: 7, Safety: 10, Effort: S]

- [ ] Salt test progress on the Wear OS watch
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/SaltProgress.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/HistoryCommand.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SaltScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/SaltProgressTest.kt`, `FEATURES.md`
  - **Goal:** Show on the watch how many stable hours a salt test still needs, so the user leaves the sensor in the jar long enough. Port `SaltProgress` and `HistoryCommand` of the Android app with the same rules: `REQUIRED_HOURS` 6, the tick interval, the stop reasons `USER`, `VIEW_CLOSED`, `TIMEOUT`, `LINK_LOST`, `REPLY_FAILED` and `COMPLETED`, and the short read `HISTORY <N> 6` with the single fallback to `HISTORY <N>` after `ERR BAD_ARG`. Show the text `<N> von 6 Stunden stabil` and `Bereit` at six stable hours. Start the watch only on a tap, and stop it when the screen closes, when the link drops, after a failed reply and after the timeout.
  - **Acceptance:** A unit test counts the stable hours of a reply with six samples in a span of 1.0 %RH. A unit test keeps the count below six for an unstable span. A unit test checks the text `3 von 6 Stunden stabil`. A unit test checks the stop reason `COMPLETED` at six hours. A unit test checks the fallback to `HISTORY <N>` after `ERR BAD_ARG` and that it runs once. A unit test checks the stop reason `TIMEOUT`. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 28, Use: 2, Setup: 9, Harmony: 7, Safety: 10, Effort: M]

## Priority 3: Display and Logging (Score 22–27)

- [ ] Dry hours in the Wear OS week screen
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/WeekMoldText.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/WeekScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/WeekMoldTextTest.kt`, `FEATURES.md`
  - **Goal:** Show the dry hours of the week beside the mold hours on the watch. `WeekReply` parses the column `dry_h` into `WeekRow.dryHours`, but the week screen shows only the mold line. Add `WeekMoldText.dry(rows)`, which gives `Trocken <N> h` with the sum of `dryHours` over the days with a value and `null` when no day has one. Show the line below the mold line of the selected room. Send no new console command.
  - **Acceptance:** A unit test gives `Trocken 9 h` for three days with 2, 3 and 4 hours. A unit test gives `null` for seven gap days. A unit test gives `null` for a reply with the 8-column header. A unit test ignores a day without a value in the sum. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 27, Use: 6, Setup: 4, Harmony: 7, Safety: 10, Effort: S]

- [ ] Heat alert delay in the Android alert limits screen
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/AlertLimits.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/AlertLimitScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/AlertLimitsTest.kt`, `FEATURES.md`
  - **Goal:** Let the user set the heat alert delay on the phone. This item needs the firmware item "Config key `heat_alert_delay_h` for the heat alert". Add the field `heat_alert_delay_h` (0 is off, else 1 to 12 h) below the heat limit field, with the rules of the dry delay field. Show the unit `h` and the text `Aus` for 0. Reject a value above 12 and a non-numeric value before the `SET` batch starts. Keep the schema order of the `SET` commands, and send `CONFIG` after the batch.
  - **Acceptance:** A unit test accepts the values 0, 1 and 12. A unit test rejects 13 and a non-numeric value. A unit test checks the `SET heat_alert_delay_h=2` line of the batch. A unit test checks the schema order of a batch with the heat limit and the heat delay. A unit test checks the text `Aus` for 0. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` passes.
  - **Rank:** [Total: 26, Use: 7, Setup: 5, Harmony: 4, Safety: 10, Effort: S]

- [ ] Slot enable toggle in the macOS scanner view
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/SlotAssignment.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/ScannerView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/SlotAssignmentTests.swift`, `FEATURES.md`
  - **Goal:** Turn one sensor slot off and on in the macOS scanner view, as the Android slot configuration screen does. Read the value of `slot<N>_enabled` from the cached `CONFIG` reply, and show one toggle for each slot. Build the command `SET slot<N>_enabled=<0|1>` in `SlotAssignment`, and send `CONFIG` after it. Show the text `Off` for a disabled slot in the slot menu, and block the MAC assignment of a disabled slot. Give no command for a missing `CONFIG` reply and for a slot that already holds the value.
  - **Acceptance:** A unit test builds `SET slot2_enabled=0`. A unit test gives no command for a slot that already holds the value. A unit test gives no command without a `CONFIG` reply. A unit test reports the blocked MAC assignment of a disabled slot. A unit test checks the default value 1 for a missing key. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.
  - **Rank:** [Total: 26, Use: 4, Setup: 8, Harmony: 5, Safety: 9, Effort: S]

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
