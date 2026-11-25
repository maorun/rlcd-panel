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

- [ ] Battery runtime preview in the Android config editor
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/RuntimePreview.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/RuntimePreviewTest.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/ConfigEditorScreen.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt`, `FEATURES.md`
  - **Goal:** Show the battery runtime for an edited wake schedule before the user sends `SET`. Port the macOS `RuntimePreview` ratio model to Kotlin. Use the `RUNTIME h` and `AWAKE avg_ms` values of the last `STATUS` reply. Use the draft values of `wake_interval_s`, `night_wake_interval_s`, `quiet_hours_start_hour` and `quiet_hours_end_hour`. Show `Laufzeit ca. <N> h` below the wake interval fields. Show `Laufzeit --` without a `RUNTIME` line, without a runtime value or without awake samples. The preview sends no command.
  - **Acceptance:** A unit test checks that a doubled `wake_interval_s` gives the macOS value for the same input. A unit test checks that a night interval counts only for the quiet hours. A unit test checks that a missing `RUNTIME` value and zero awake samples give null. A unit test checks that an invalid draft value gives null. `gradle testDebugUnitTest` and `gradle assembleDebug` in `android/RlcdPhone` pass.
  - **Rank:** [Total: 32, Use: 5, Setup: 9, Harmony: 8, Safety: 10, Effort: M]
- [ ] Hour window argument for the console command FRSI
  - **Location:** `rlcd_panel/serial_console.h`, `rlcd_panel/climate_math.h`, `tests/test_firmware_logic.cpp`, `tests/fixtures/frsi_reply_sample.txt`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Let the user select the sample window of the wall factor check. A short cold night gives fewer than 6 stable hours. Add the optional argument `FRSI <R> <W> <H>` (H 3 to 24, no leading zero). `FRSI <R> <W>` stays `FRSI <R> <W> 6`. Use the newest H samples of each history. Give `SKIP hours=<n>` below H samples. Keep the `UNSTABLE` limit 0.10 and the `ERR LOW_DELTA` limit 10 C. Give `ERR BAD_ARG` for 2, 25, a leading zero, a non-digit and extra text. Do not change the RTC layout or `STATE_VERSION`.
  - **Acceptance:** A host test checks that `FRSI 0 2 3` gives a result from 3 samples. A host test checks that `FRSI 0 2 12` gives `SKIP hours=6` with 6 samples. A host test checks that `FRSI 0 2` gives the same bytes as `FRSI 0 2 6`. A host test rejects `FRSI 0 2 2`, `FRSI 0 2 25` and `FRSI 0 2 06`. A host test rejects `FRSI 0 2 x` and `FRSI 0 2 6 1`. The host unit tests and `arduino-cli compile rlcd_panel` pass.
  - **Rank:** [Total: 29, Use: 3, Setup: 8, Harmony: 8, Safety: 10, Effort: S]
- [ ] Open window notification in the macOS app
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/WindowWatch.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/WindowWatchTests.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `FEATURES.md`
  - **Goal:** Tell the user when the panel detects an open window. The Android and Wear OS apps have this notification. The macOS app does not. Add `WindowWatch` with the rules of the Kotlin `WindowWatch`. Feed it with the `window` mask of the `STATUS` line `LATCH` (`LatchMetrics`). Post one `UserNotifications` notification `Window open: <name>` for each rising bit. Use the room name of `EventText`. The first read only stores the mask. A missing `window` field keeps the stored mask. Without enabled notifications the watch stays untouched.
  - **Acceptance:** A unit test checks that the first mask gives no tile. A unit test checks that a rising bit gives its tile once. A unit test checks that a falling bit and an equal mask give no tile. A unit test checks that a nil mask keeps the stored mask. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.
  - **Rank:** [Total: 29, Use: 8, Setup: 2, Harmony: 9, Safety: 10, Effort: S]
- [ ] Hour window picker for the macOS FRSI check
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/Calibration.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/CalibrationView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/CalibrationTests.swift`, `FEATURES.md`
  - **Goal:** Let the user select the FRSI sample window in the calibration view. This item needs the firmware item "Hour window argument for the console command FRSI". Change `ConsoleCommand.frsi(room:wall:)` to `frsi(room:wall:hours:)`. Send `FRSI <R> <W>` for 6 hours and `FRSI <R> <W> <H>` for other values. Add a picker with 3, 6, 12 and 24 hours. Show the hour count of the reply beside the factor.
  - **Acceptance:** A unit test checks that 6 hours gives `FRSI 0 2`. A unit test checks that 12 hours gives `FRSI 0 2 12`. A unit test checks that 2 and 25 hours give no command. A unit test parses a `SKIP hours=6` reply for a 12 hour request. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.
  - **Rank:** [Total: 29, Use: 3, Setup: 8, Harmony: 8, Safety: 10, Effort: S]
- [ ] Hour window picker for the Android FRSI check
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/FrsiReply.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/FrsiReplyTest.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/CalibrationScreen.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt`, `FEATURES.md`
  - **Goal:** Let the user select the FRSI sample window on the phone. This item needs the firmware item "Hour window argument for the console command FRSI". Add a `hours` parameter to the `FRSI` command builder. Send `FRSI <R> <W>` for 6 hours and `FRSI <R> <W> <H>` for other values. Add chips for 3, 6, 12 and 24 hours to the calibration screen. Show the hour count of the reply beside the factor.
  - **Acceptance:** A unit test checks that 6 hours gives `FRSI 0 2`. A unit test checks that 12 hours gives `FRSI 0 2 12`. A unit test checks that 2 and 25 hours throw `IllegalArgumentException`. A unit test parses a `SKIP hours=6` reply for a 12 hour request. `gradle testDebugUnitTest` and `gradle assembleDebug` in `android/RlcdPhone` pass.
  - **Rank:** [Total: 29, Use: 3, Setup: 8, Harmony: 8, Safety: 10, Effort: S]
- [ ] Slot release from the Wear OS sensor health screen
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/SlotClearCommand.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/SlotClearCommandTest.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `FEATURES.md`
  - **Goal:** Let the user free a dead sensor slot from the watch. Show a `Platz freigeben` button on each `STALE` row of the sensor health screen. Show the button only for a slot without a fixed MAC in the `CONFIG` reply. Ask for a confirmation before the send. Send `SLOT CLEAR <N>`, then `STATUS`. Show the text for `OK SLOT CLEAR <N>`, `ERR FIXED_MAC`, `ERR READ_ONLY` and an unknown reply.
  - **Acceptance:** A unit test checks that slot 3 gives `SLOT CLEAR 3`. A unit test checks that 0 and 11 throw `IllegalArgumentException`. A unit test checks the reply texts for `OK SLOT CLEAR 3`, `ERR FIXED_MAC` and `ERR READ_ONLY`. A unit test checks that a slot with a fixed MAC gets no button. `gradle testDebugUnitTest` and `gradle assembleDebug` in `wearos/RlcdWatch` pass.
  - **Rank:** [Total: 28, Use: 4, Setup: 8, Harmony: 8, Safety: 8, Effort: S]
- [ ] Wall factor check on the Wear OS watch
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/FrsiReply.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/FrsiReplyTest.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/FrsiScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `FEATURES.md`
  - **Goal:** Let the user measure `wall_frsi_pct` with the watch near the wall sensor. Port the Android `FrsiReply.kt`. Add `FrsiScreen` with a room picker and a wall slot picker. Send `FRSI <R> <W>`. Show the factor, the span, the hours and the proposal `wall_frsi_pct=<N>`. Show the texts for `SKIP`, `UNSTABLE`, `ERR LOW_DELTA`, `ERR NO_OUTDOOR` and `ERR BAD_ARG`. An `Übernehmen` button sends `SET wall_frsi_pct=<N>`, then `CONFIG`. Show the text for `ERR READ_ONLY`.
  - **Acceptance:** `FrsiReplyTest` reads `tests/fixtures/frsi_reply_sample.txt` and checks the factor 0.70 and the proposal 70. A unit test checks the `SKIP`, `UNSTABLE` and `ERR` replies. A unit test checks that an equal room and wall slot throw `IllegalArgumentException`. A unit test checks that a bad CRC-32 line throws `ParseException`. `gradle testDebugUnitTest` and `gradle assembleDebug` in `wearos/RlcdWatch` pass.
  - **Rank:** [Total: 28, Use: 3, Setup: 8, Harmony: 8, Safety: 9, Effort: M]

## Priority 3: Display and Logging (Score 22–27)

- [ ] Clearing drift event for a stale slot
  - **Location:** `rlcd_panel/power_manager.h`, `rlcd_panel/drift_latch.h`, `tests/test_firmware_logic.cpp`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Close each `SENSOR_DRIFT` event pair in the event log. Now a drifting slot that goes stale loses its bit without an event. The log then shows an open drift without an end. Change `PersistentState::updateDrift` to record one `SENSOR_DRIFT` event with high byte 0 for this case. Record nothing for a stale slot without a drift bit. Keep the rules for `resetSlot`, a power-on and fewer than 3 sensors. Do not change the RTC layout or `STATE_VERSION`.
  - **Acceptance:** A host test checks that a drifting slot that goes stale records one event with detail `0x00<slot>`. A host test checks that a stale slot without a drift bit records nothing. A host test checks that `resetSlot` still records nothing. A host test checks that fewer than 3 sensors still clear each bit without an event. The host unit tests and `arduino-cli compile rlcd_panel` pass.
  - **Rank:** [Total: 27, Use: 4, Setup: 6, Harmony: 8, Safety: 9, Effort: S]

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
