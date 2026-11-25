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

- [ ] Weak signal notification in the Android app
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ReceptionNotice.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/ReceptionNoticeTest.kt`, `FEATURES.md`
  - **Goal:** Tell the user on the phone when a sensor has a weak BLE signal, as on the watch. The shared `ReceptionWatch.kt` exists in `android/RlcdPhone`, but no screen uses it. Feed `ReceptionWatch.update` with `status.latch?.receptionLow` after each `STATUS` reply. Add `ReceptionNotice.plan(status, slots)`. It gives one notification for each rising slot with the sensor name, `ReceptionWatch.message` and `ReceptionWatch.notificationId`. Use the slot number as the name for an unknown sensor. Post nothing without the `POST_NOTIFICATIONS` permission. Do not change the shared `ReceptionWatch.kt`.
  - **Acceptance:** `ReceptionNoticeTest` reads `tests/fixtures/status_reply_sample.txt` and gives the name and the ID of each `rxlow` slot. A test checks that the first reply gives no notification. A test checks that a reply without `rxlow` gives no notification. A test checks that an unknown sensor gives its slot number. `./scripts/check-protocol-sync.sh` exits with 0. `gradle testDebugUnitTest` passes in `android/RlcdPhone`.
  - **Rank:** [Total: 31, Use: 6, Setup: 7, Harmony: 8, Safety: 10, Effort: S]
- [ ] Weak signal notification in the macOS app
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/ReceptionWatch.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/ReceptionWatchTests.swift`, `FEATURES.md`
  - **Goal:** Tell the user on the Mac when a sensor has a weak BLE signal. Add `ReceptionWatch` with the rules of the Kotlin `ReceptionWatch.kt`. The first reply only stores the mask. A rising bit of `LatchMetrics.receptionLow` gives its slot number. An equal mask, a falling bit and a `nil` mask give no slot. A `nil` mask keeps the stored mask. Feed it in `AppModel` with each `STATUS` reply, as `DriftWatch`. Post one notification `Weak signal: <name>` for each rising slot. Post nothing while notifications are off.
  - **Acceptance:** `ReceptionWatchTests` give the same slots as the Kotlin `ReceptionWatchTest` for the same masks. A test checks that the first mask gives no slot. A test checks that a `nil` mask keeps the stored mask. A test reads `tests/fixtures/status_reply_sample.txt` and feeds its `rxlow` mask. `swift test` passes in `macos/RlcdCompanion`.
  - **Rank:** [Total: 31, Use: 6, Setup: 7, Harmony: 8, Safety: 10, Effort: S]
- [ ] Hour window selection for SPREAD in the macOS calibration view
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/CalibrationView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/CalibrationTests.swift`, `FEATURES.md`
  - **Goal:** Let the user select the window of the co-location check on the Mac. This entry needs the firmware entry "Hour window argument for the console command SPREAD". Add `ConsoleCommand.spread(hours:)`. It sends `SPREAD` for 6 hours and `SPREAD <H>` for 7 to 24 hours. Show a picker with 6, 12 and 24 hours in `CalibrationView`. Send the selected window with each read and after each applied proposal. Show `Firmware update needed` for `ERR BAD_ARG` from an older firmware. Keep `SpreadReply.parse`.
  - **Acceptance:** `CalibrationTests` check that `spread(hours: 6)` gives `SPREAD`. A test checks that `spread(hours: 24)` gives `SPREAD 24`. A test checks that 5 and 25 give no command. A test reads `tests/fixtures/spread_reply_sample.txt` and gives the same result for each window. `swift test` passes in `macos/RlcdCompanion`.
  - **Rank:** [Total: 30, Use: 4, Setup: 9, Harmony: 8, Safety: 9, Effort: S]
- [ ] Config key reception_low_pct for the weak signal limit
  - **Location:** `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `docs/config.example.ini`, `tools/sample_config.ini`, `tests/test_firmware_logic.cpp`, `macos/RlcdCompanion/Sources/RlcdProtocol/ConfigSchema.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/ConfigSchemaTests.swift`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ConfigSchema.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/ConfigSchemaTest.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/ConfigSchema.kt`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Let the user set the reception rate that sets the `R` badge. A sensor far from the panel has a low rate and sets a false badge. Add the key `reception_low_pct` (0 is off, else 30 to 80 %, default 50). Add 1 byte at the end of the config trailer, after `mold_alert_delay_h`. `PersistentState::recordReceptionChanges` sets the bit below the limit and releases it at the limit plus 20 %. Value 0 clears each bit without an event. Raise `KEY_COUNT` to 98 and `STATE_VERSION` to 77. Add the key to the three `ConfigSchema` files. Keep the Kotlin files equal.
  - **Acceptance:** A host test checks that `reception_low_pct=30` sets the bit below 30 % and releases it at 50 %. A host test checks that the default gives the old 50 % and 70 % rules. A host test checks that 0 clears each bit without a `RECEPTION_LOW` event. A host test rejects 29, 81 and a non-digit. `testConfigExampleTemplateInSync` passes. `ConfigSchemaTests.rangesMatchFirmwareConstants` passes. `./scripts/check-protocol-sync.sh` exits with 0. The host unit tests, `swift test`, `gradle testDebugUnitTest` in both Kotlin apps and `arduino-cli compile rlcd_panel` pass.
  - **Rank:** [Total: 30, Use: 4, Setup: 8, Harmony: 9, Safety: 9, Effort: M]
- [ ] Salt test progress in the Android calibration screen
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/SaltProgress.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/CalibrationScreen.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/SaltProgressTest.kt`, `FEATURES.md`
  - **Goal:** Show the user on the phone when a salt test has enough stable hours. Add `SaltProgress` with the rules of the macOS `SaltProgress.swift` and an injected clock. Count the stable hours of one sensor from each `HISTORY N` reply. A stable hour has a humidity span of 1.0 %RH or less. Send `HISTORY N` every 10 minutes while `CalibrationScreen` is open. Stop after 8 hours, on a lost link, on an `ERR` reply and when the user leaves the screen. Show `<N> von 6 Stunden stabil`. Send `SALT N` at 6 stable hours.
  - **Acceptance:** `SaltProgressTest` reads `tests/fixtures/history_reply_sample.txt` and gives the same stable hours as the macOS `SaltProgressTests`. A test checks that a span above 1.0 %RH resets the count. A test checks the stop after 8 hours with the injected clock. A test checks that an `ERR` reply stops the progress. `gradle testDebugUnitTest` passes in `android/RlcdPhone`.
  - **Rank:** [Total: 29, Use: 3, Setup: 9, Harmony: 8, Safety: 9, Effort: S]
- [ ] Reset changed settings to the default on the Wear OS watch
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/ConfigChangedScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/ConfigChangedReplyTest.kt`, `FEATURES.md`
  - **Goal:** Let the user undo a wrong setting from the wrist. Show one `Zurücksetzen` chip for each row of `ConfigChangedScreen`. A tap shows a confirmation with the key and the default value. The confirmation sends the `SET` command of the shared `ConfigResetPlan`, then `CONFIG CHANGED`. Show the skip reason for a key without a sendable default. The first `ERR` stops and shows its text. Show `Nur lesen` and no chip for `ERR READ_ONLY`. Do not change the shared files.
  - **Acceptance:** `ConfigChangedReplyTest` reads `tests/fixtures/config_changed_reply_sample.txt` and gives one reset command for each key with the `ConfigSchema` default. A test checks that a key with an empty default lands in `skipped`. A test checks that `ERR READ_ONLY` stops the run after the first command. `./scripts/check-protocol-sync.sh` exits with 0. `gradle testDebugUnitTest` passes in `wearos/RlcdWatch`.
  - **Rank:** [Total: 28, Use: 3, Setup: 8, Harmony: 8, Safety: 9, Effort: S]

## Priority 3: Display and Logging (Score 22–27)

- [ ] Mold hours in the Wear OS week screen
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/WeekScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/WeekMoldText.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/WeekMoldTextTest.kt`, `FEATURES.md`
  - **Goal:** Show the weekly mold hours on the watch, as on the phone and on the panel. The shared `WeekReply` gives `moldHours` for each day. Add `WeekMoldText.summary(rows)`. It gives `Schimmel <N> h` with the sum over the days with a value. It gives `null` when no day has a value. Show the text below the week bars of `WeekScreen`. Mark a day with mold hours above 0 with `⚠` in its row.
  - **Acceptance:** `WeekMoldTextTest` reads `tests/fixtures/week_reply_sample.txt` and gives the sum of the fixture for one sensor. A test checks that gap days do not add to the sum. A test checks that a reply with 7 columns gives `null`. `gradle testDebugUnitTest` passes in `wearos/RlcdWatch`.
  - **Rank:** [Total: 26, Use: 5, Setup: 3, Harmony: 8, Safety: 10, Effort: S]

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
