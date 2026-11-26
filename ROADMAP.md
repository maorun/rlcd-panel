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



- [ ] Add ReceptionReply.kt to the protocol sync check
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ReceptionReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/ReceptionReply.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/SensorHealthScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt`, the two `ReceptionReplyTest.kt` files, `scripts/check-protocol-sync.sh`, `FEATURES.md`
  - **Goal:** Remove the only protocol file that the phone and the watch hold in two different versions. The phone `ReceptionRow` has `cells: List<Boolean>` and `percent`; the watch has `mask: String` and `newestCells`. Make one file with `mask`, `cells`, `percent`, `isLow` and `newestCells(count)`. Use it in both apps without a change of the screen texts. Add `ReceptionReply.kt` to the `FILES` list of `scripts/check-protocol-sync.sh`.
  - **Acceptance:** `scripts/check-protocol-sync.sh` passes with `ReceptionReply.kt` in the list. Both `ReceptionReplyTest.kt` files hold the same test cases and parse `tests/fixtures/reception_reply_sample.txt`. A unit test checks `cells` and `newestCells(12)` of one 24-character mask. `gradle testDebugUnitTest assembleDebug` passes in `android/RlcdPhone` and in `wearos/RlcdWatch`.
  - **Rank:** [Total: 30, Use: 2, Setup: 8, Harmony: 10, Safety: 10, Effort: S]



- [ ] Panel screen image on the Android phone
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/PanelImage.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/PanelScreen.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/PanelImageTest.kt`, `FEATURES.md`
  - **Goal:** Let the phone user see the panel screen. `SCREEN` gives `ERR NOT_OVER_BLE`, so the phone cannot read the framebuffer. Render the four tiles from the newest telemetry frame instead: port `PanelImage.decode` of the macOS app (invariant 4 packing) for a later USB path, and add `PanelPreview.render(frame, config)`, which draws a 400 x 300 bitmap with the tile layout of `DashboardView::layoutTiles` for the valid tiles (names, temperature, humidity). Label the image `Preview from telemetry, not the panel image`. Do not send a console command.
  - **Acceptance:** A unit test decodes a 15,000-byte buffer with one set pixel at x 3, y 5 and checks the pixel. A unit test checks the exception for 14,999 bytes. A unit test checks the tile rectangles for 1, 4 and 6 valid tiles against the values of `layoutTiles` in `AGENTS.md`. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` passes.
  - **Rank:** [Total: 24, Use: 5, Setup: 3, Harmony: 6, Safety: 10, Effort: M]

- [ ] App parser tests for the new TODAY fixtures
  - **Location:** `macos/RlcdCompanion/Tests/RlcdProtocolTests/TodayReplyTests.swift`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/TodayReplyTest.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/TodayReplyTest.kt`, `tests/fixtures/today_hours_reply_sample.txt`, `tests/fixtures/today_hours_one_reply_sample.txt`
  - **Goal:** Close a test gap: the firmware writes the 11-column `TODAY` reply, but no app test reads `today_hours_reply_sample.txt` or `today_hours_one_reply_sample.txt`. A change of the firmware fixture can break the app parsers without a failed test. Add one test in each app that parses both fixtures and checks the row count, `moldHours` and `dryHours` of each row. Add the same check for `tests/fixtures/history_reply_sample.txt` to the macOS tests, which do not read it.
  - **Acceptance:** Each new test passes with the current fixtures. Each new test fails when a column of the fixture header is removed. `swift test` passes. `gradle testDebugUnitTest assembleDebug` passes in both Android projects.
  - **Rank:** [Total: 23, Use: 1, Setup: 2, Harmony: 10, Safety: 10, Effort: S]

- [ ] Weekly heat hours in the WEEK reply
  - **Location:** `rlcd_panel/sparkline.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `tests/fixtures/week_reply_sample.txt`, `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, the shared `WeekReply.kt`, the `WeekReply` tests, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Keep the heat hours of the last 7 days. This item needs the firmware item "Heat hours in the stats view and the STATUS SENSOR lines". Add one byte of heat hours for each day and sensor to `WeekHistory` (77 bytes after the dry bytes, 0 to 24, `0xFF` for a gap day and for `heat_alert_limit_c` 0). `WeekHistory::advance` stores `ClimateHistory::heatHours` with the mold and dry hours. Add the column `heat_h` after `dry_h` to the `WEEK` reply (10 columns). Let the Swift and Kotlin `WeekReply.parse` accept 7, 8, 9 or 10 columns, with the header as the selector. Merge the app parsers before the firmware change. Increase `STATE_VERSION` by 1.
  - **Acceptance:** A host test checks the stored hours of one day after `advance`. A host test checks `0xFF` for limit 0. A host test checks that the RTC restore rejects 25. A host test checks the 10-column header. App unit tests parse the old 9-column fixture and a new 10-column row. `scripts/check-protocol-sync.sh` passes. The host test suite passes. `arduino-cli compile rlcd_panel` passes. `swift test` passes. `gradle testDebugUnitTest assembleDebug` passes in both Android projects.
  - **Rank:** [Total: 22, Use: 5, Setup: 1, Harmony: 8, Safety: 8, Effort: L]

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
