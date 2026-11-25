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


- [x] Reception strip in the macOS diagnostics view
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/ReceptionReply.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/ReceptionReplyTests.swift`, `FEATURES.md`
  - **Goal:** Show the scan pattern of each slot as a strip of 24 cells in the macOS diagnostics view. This item needs the firmware item "Console command RECEPTION with the scan mask of each slot". Add `ConsoleCommand.reception` and `ReceptionReply.parse` (`ReceptionRow` with slot, MAC, name, scans, received and `[Bool]` cells). Throw `ParseError.badLine` for a wrong column count, a mask character other than `0` or `1`, a mask length other than `scans`, a `received` value that does not match the mask, or a repeated slot. Show one strip for each row in the group `BLE reception`, oldest scan left, a filled cell for a received packet. Show a warning symbol below 50 %. Send `RECEPTION` only on a user action.
  - **Acceptance:** A unit test parses `tests/fixtures/reception_reply_sample.txt`. A unit test checks the cells of the mask `101101`. Unit tests check `ParseError.badLine` for the mask `10a1`, for a mask of 5 characters with `scans` 6, for `received` 3 with the mask `101101` and for a repeated slot. A unit test checks an empty reply with zero rows. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.
  - **Rank:** [Total: 31, Use: 5, Setup: 8, Harmony: 8, Safety: 10, Effort: S]

- [x] Reception strip in the Android sensor health screen
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ReceptionReply.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/SensorHealthScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/ReceptionReplyTest.kt`, `FEATURES.md`
  - **Goal:** Show the scan pattern of each slot as a strip of 24 cells in the Android sensor health screen. This item needs the firmware item "Console command RECEPTION with the scan mask of each slot". Add `ReceptionReply.parse` with the same rules as the macOS parser (`ParseException` for each bad line). Add a `Read reception` button that sends `RECEPTION` only on a user action. Show one strip below the row of each slot, oldest scan left. Show `⚠` below 50 %.
  - **Acceptance:** A unit test parses `tests/fixtures/reception_reply_sample.txt`. A unit test checks the cells of the mask `101101`. Unit tests check `ParseException` for the mask `10a1`, for a wrong mask length, for a wrong `received` value and for a repeated slot. A unit test checks an empty reply with zero rows. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` passes.
  - **Rank:** [Total: 31, Use: 5, Setup: 8, Harmony: 8, Safety: 10, Effort: S]

- [x] Reception strip on the Wear OS watch
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/ReceptionReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/ReceptionReplyTest.kt`, `FEATURES.md`
  - **Goal:** Show the scan pattern of each slot on the watch, so the user can walk to a sensor and see the gaps. This item needs the firmware item "Console command RECEPTION with the scan mask of each slot". Add `ReceptionReply.parse` with the same rules as the Android parser. Add a `Reception` chip in the sensor health screen that sends `RECEPTION` only on a tap. Show one row for each slot: the name, `<received>/<scans>` and a strip of the newest 12 cells that fits the round screen. Show `⚠` below 50 %.
  - **Acceptance:** A unit test parses `tests/fixtures/reception_reply_sample.txt`. A unit test checks that a mask of 24 characters gives the newest 12 cells. Unit tests check `ParseException` for the mask `10a1`, for a wrong mask length and for a repeated slot. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 30, Use: 5, Setup: 7, Harmony: 8, Safety: 10, Effort: S]

## Priority 3: Display and Logging (Score 22–27)

- [x] Mold and dry hours of the day in the macOS today view
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/TodayReplyTests.swift`, `FEATURES.md`
  - **Goal:** Show for each room how many hours of the current day were too damp or too dry. Let `TodayReply.parse` accept the old header with 9 columns and the new header `sensor,name,hours,temp_min_c,temp_max_c,temp_mean_c,rh_min_pct,rh_max_pct,rh_mean_pct,mold_h,dry_h` with 11 columns. The header selects the column count. Add `TodayRow.moldHours` and `TodayRow.dryHours` (`Int?`, `nil` for an empty field and for the old header). Throw `ParseError.badLine` for a value above 24 or above `hours`. Show `Mold <N> h` and `Dry <N> h` in the today view with a warning symbol above 0. Merge this item before the firmware item "Mold and dry hours columns in the console command TODAY".
  - **Acceptance:** A unit test parses `tests/fixtures/today_reply_sample.txt` (old header) with `nil` values. A unit test parses a new row with `mold_h` 3 and `dry_h` 0. A unit test checks `nil` for an empty `dry_h` field. Unit tests check `ParseError.badLine` for a new row with 10 columns and for `mold_h` 7 with `hours` 5. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.
  - **Rank:** [Total: 27, Use: 7, Setup: 2, Harmony: 8, Safety: 10, Effort: S]

- [x] Mold and dry hours of the day on the Wear OS today screen
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/TodayReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/TodayScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/TodayReplyTest.kt`, `FEATURES.md`
  - **Goal:** Show on the watch how many hours of the current day the selected room was too damp or too dry. Let `TodayReply.parse` accept the old header with 9 columns and the new header with `mold_h` and `dry_h` (11 columns), with the same rules as the Android parser. Add `TodayRow.moldHours` and `TodayRow.dryHours` (`Int?`). Show one line `Mold <N> h · Dry <N> h` below the humidity line of the room picker result; show `--` for `null`. Merge this item before the firmware item "Mold and dry hours columns in the console command TODAY".
  - **Acceptance:** A unit test parses `tests/fixtures/today_reply_sample.txt` (old header) with `null` values. A unit test parses a new row with `mold_h` 3 and `dry_h` 0. Unit tests check `ParseException` for a new row with 10 columns and for a value above `hours`. A unit test checks the line text `Mold 3 h · Dry -- h`. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 27, Use: 7, Setup: 2, Harmony: 8, Safety: 10, Effort: S]

- [ ] Mold and dry hours columns in the console command TODAY
  - **Location:** `rlcd_panel/serial_console.h`, `rlcd_panel/sparkline.h`, `tests/test_firmware_logic.cpp`, `tests/fixtures/today_hours_reply_sample.txt`, `tests/fixtures/today_hours_one_reply_sample.txt`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Count for each sensor the hourly samples since the last local midnight that are above `mold_alert_limit_rh` and at or below `dry_alert_limit_rh`. This item needs the three app items "Mold and dry hours of the day" above, because the app parsers accept only a known header. Append the columns `mold_h` and `dry_h` to the `TODAY` header and to each row (11 columns). Use the same sample age rules as the existing `TODAY` values. Count `mold_h` with the strict rule of `HumidityHistory::countAbove`, and `dry_h` with the rule of `HumidityHistory::countAtOrBelow`. Give an empty field for a sensor without a humidity sample since midnight; give an empty `dry_h` field for `dry_alert_limit_rh` 0. Apply the same columns to `TODAY <N>`. Do not change the RTC layout or `STATE_VERSION`.
  - **Acceptance:** A host test checks a sensor with 5 samples since midnight, 2 above the mold limit and 1 at the dry limit: `mold_h` 2 and `dry_h` 1. A host test checks that a sample at the mold limit does not count. A host test checks that a sample before midnight does not count. A host test checks an empty `dry_h` field for limit 0. A host test checks empty fields for a sensor without a sample. A host test checks `TODAY 2` with the new header. Host tests compare the replies with the new `tests/fixtures/today_hours_reply_sample.txt` and `today_hours_one_reply_sample.txt`. The old `today_reply_sample.txt` and `today_one_reply_sample.txt` stay unchanged as samples of the old header for the app parsers. The host test suite passes. `arduino-cli compile rlcd_panel` passes.
  - **Rank:** [Total: 26, Use: 7, Setup: 2, Harmony: 7, Safety: 10, Effort: S]

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
