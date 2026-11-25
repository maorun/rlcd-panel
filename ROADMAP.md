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

- [ ] Skip stale slots in the console command SPREAD and in the drift latch
  - **Location:** `rlcd_panel/drift_latch.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `tests/fixtures/spread_reply_sample.txt`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Stop false drift reports from a lost sensor. A stale slot keeps its old hourly samples. Thus `SPREAD` and `DriftLatch` mark it as `OUTLIER`. Give `SpreadMath` one sensor mask. Exclude each slot that `SwitchBotRegistry::isStale` reports with `DeviceConfig::staleTimeoutMs()`. Pass the registry and the time to `SerialConsoleRunner::showSpread`. In `rlcd_panel.ino`, remove the stale slots from `drift_tile_mask` before `PersistentState::updateDrift`. A stale slot clears its drift bit without an event. The excluded slot does not count in `sensors=` and in the median. Keep the reply format. Do not change the RTC layout or `STATE_VERSION`.
  - **Acceptance:** A host test uses four sensors and one stale slot 2.0 C above the others. The reply has `sensors=3` and no `SENSOR` line for the stale slot. A host test checks that the stale slot does not move the median. A host test makes a slot with a drift bit stale. The bit clears without a `SENSOR_DRIFT` event. A host test checks that a fresh slot keeps the old behavior. The host unit tests pass and `arduino-cli compile rlcd_panel` passes.
  - **Rank:** [Total: 34, Use: 6, Setup: 10, Harmony: 8, Safety: 10, Effort: M]

## Priority 2: Configuration and Control (Score 28–33)

- [ ] Sensor drift badge in the climate tiles
  - **Location:** `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`, `tools/render_display_preview.cpp`, `scripts/generate-display-preview.sh`, `docs/display_preview_drift.png`, `tests/test_firmware_logic.cpp`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Show the drift state on the panel, so the user sees it without an app. Add `SystemInfo::drift_tiles` and copy `PersistentState::drift_tiles` in `rlcd_panel.ino`. Bit 0 is the onboard tile, bits 1 to 10 are the slots. Draw the inverted `S` badge for each set bit. Put it beside `V`, `D`, `W`, `C` and `R` in each tile style. Do not draw the badge on a stale tile. Add the scene field `PreviewScene::drift` and the image `docs/display_preview_drift.png`. Do not change the RTC layout or `STATE_VERSION`.
  - **Acceptance:** A host test renders a tile with its drift bit set and finds the inverted `S` badge pixels. A host test checks that a clear bit and a stale tile give no badge. A host test checks the badge position beside the other badges in the STANDARD, LARGE and COMPACT styles. `./scripts/generate-display-preview.sh` creates `docs/display_preview_drift.png`. The host unit tests pass and `arduino-cli compile rlcd_panel` passes.
  - **Rank:** [Total: 33, Use: 7, Setup: 8, Harmony: 8, Safety: 10, Effort: S]
- [ ] Changed settings view and reset to default in the macOS config editor
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/ConfigChangedReply.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/ConfigSchema.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/ConfigEditorView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/ConfigChangedReplyTests.swift`, `FEATURES.md`
  - **Goal:** Show the user on the Mac the keys that differ from the default. Use the rules of the Android config editor. Add `ConsoleCommand.configChanged` for `CONFIG CHANGED`. Add `ConfigChangedReply.parse` for the `<key>=<value>` lines, the line `END CHANGED <N>` and the `CRC32` line. Add the filter `Changed only` to `ConfigEditorView`. Add one `Reset` button for each changed key. The button sends `SET <key>=<default>` from `ConfigSchema` through `ConfigBatch`, then `CONFIG CHANGED`. Disable the button for `ble_console` 1.
  - **Acceptance:** `ConfigChangedReplyTests` read `tests/fixtures/config_changed_reply_sample.txt` and give the keys and values of the fixture. A count in `END CHANGED` that does not match the lines throws `ParseError.badLine`. An unknown key throws `ParseError.badLine`. A test checks that the reset batch of a changed key holds the `ConfigSchema` default. `swift test` passes in `macos/RlcdCompanion`.
  - **Rank:** [Total: 30, Use: 4, Setup: 9, Harmony: 8, Safety: 9, Effort: S]
- [ ] Apply the SPREAD offset proposals on the Wear OS watch
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SpreadScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/SpreadReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/CalibrationBatch.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/SpreadReplyTest.kt`, `FEATURES.md`
  - **Goal:** Let the user correct a drifting sensor from the watch. Show one `Übernehmen` chip below each `OUTLIER` row of `SpreadScreen` that has a proposal. A tap shows a confirmation with both offsets. The confirmation sends the `SET` commands of `SpreadReply.Proposal` through `CalibrationBatch`, then `SPREAD` again. The first `ERR` stops the batch and shows its text. Show `Nur lesen` and no chip for `ble_console` 1 (`ERR READ_ONLY`). Keep the shared files equal to `android/RlcdPhone`.
  - **Acceptance:** `SpreadReplyTest` reads `tests/fixtures/spread_reply_sample.txt` and gives the `SET` commands of each proposal, temperature first. A test checks that an `OK` sensor gives no commands. A test checks that `ERR READ_ONLY` stops the batch after the first command. `./scripts/check-protocol-sync.sh` exits with 0. `gradle testDebugUnitTest` passes in `wearos/RlcdWatch`.
  - **Rank:** [Total: 29, Use: 4, Setup: 9, Harmony: 8, Safety: 8, Effort: S]
- [ ] Monthly mold summary in the Android daily history screen
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/MoldSummary.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/DaysScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/MoldSummaryTest.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/MoldSummaryTest.kt`, `scripts/check-protocol-sync.sh`, `FEATURES.md`
  - **Goal:** Show the user on the phone how many mold hours each room had in the last days. Copy `MoldSummary.kt` from `wearos/RlcdWatch` without the `package` line change. Add `MoldSummary.kt` to the `FILES` list of `scripts/check-protocol-sync.sh`. Show one card for each room above the rows of `DaysScreen`. The card shows the total mold hours, the mold days and the worst day. Show `Keine Schimmelstunden` for a room without mold hours. Show no card for a room without a mold value.
  - **Acceptance:** `MoldSummaryTest` in both apps reads `tests/fixtures/days_reply_sample.txt`. It gives the total, the day count and the worst day of the fixture. A test checks that a tie selects the newest day. A test checks that a room without a mold value gives `null`. `./scripts/check-protocol-sync.sh` exits with 0. `gradle testDebugUnitTest` passes in `android/RlcdPhone` and in `wearos/RlcdWatch`.
  - **Rank:** [Total: 28, Use: 6, Setup: 3, Harmony: 9, Safety: 10, Effort: S]


## Priority 3: Display and Logging (Score 22–27)

- [ ] Monthly mold summary in the macOS daily history
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/MoldSummary.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DailyChartView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/MoldSummaryTests.swift`, `FEATURES.md`
  - **Goal:** Show the user on the Mac a short mold figure for each room above the daily charts. Add `MoldSummary.summarize` with the rules of the Wear OS `MoldSummary.kt`. It gives the total mold hours, the mold days and the worst day. A tie selects the newest day. Use the `DailyRow.moldHours` of the archive folder and of the `DAYS` reply. Show one line for each room in `DailyChartView`. Show `No mold hours` for a room without mold hours. Show no line for a room without a mold value.
  - **Acceptance:** `MoldSummaryTests` read `tests/fixtures/days_reply_sample.txt` and give the same total, day count and worst day as the Kotlin `MoldSummaryTest`. A test checks the tie rule. A test checks that rows of a `daily_v3` file without mold columns give `nil`. `swift test` passes in `macos/RlcdCompanion`.
  - **Rank:** [Total: 26, Use: 5, Setup: 3, Harmony: 8, Safety: 10, Effort: S]

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
