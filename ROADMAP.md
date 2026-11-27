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


- [ ] Heat badge masks in the three app sensor lists
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/Diagnostics.swift`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/StatusReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/StatusReply.kt`, the three sensor health views, the test files, `FEATURES.md`
  - **Goal:** Show the hot rooms in the apps. Depends on `Heat badge latch in the climate tiles`. Parse `heat=` of the `LATCH` line (`nil` or `null` for a missing field, `ParseError.badLine` or `ParseException` for bad hex). Show a heat symbol in the row of each tile with its bit set.
  - **Acceptance:** A unit test in each app parses the new fixture line. A unit test checks `nil` or `null` for an old line. `scripts/check-protocol-sync.sh` passes. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` passes. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 30, Use: 7, Setup: 3, Harmony: 10, Safety: 10, Effort: S]

- [ ] Heat hours column in the console command TODAY
  - **Location:** `rlcd_panel/serial_console.h`, `rlcd_panel/sparkline.h`, `tests/test_firmware_logic.cpp`, `tests/fixtures/today_heat_reply_sample.txt`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Show the heat hours of the current day. `TODAY` has `mold_h` and `dry_h`, but no heat column. Add `ClimateHistory::heatHoursNewest(sensor, count, limit_c)` (pairs among the newest `count` samples with `ClimateMath::heatIndexC` at or above the limit, -1 for limit 0, unequal history sizes and no pair). Append the column `heat_h` (12 columns). Write an empty field for -1. Keep `today_hours_reply_sample.txt` for the app parsers and add a new 12-column fixture. Do not change the RTC layout or `STATE_VERSION`.
  - **Acceptance:** A host test checks `heat_h` 2 for two hot pairs since midnight. A host test checks an empty field for limit 0. A host test checks the 12-column header. A host test checks `TODAY 1` with the filtered row and the `CRC32` line. The host test suite passes. `arduino-cli compile rlcd_panel` passes.
  - **Rank:** [Total: 29, Use: 6, Setup: 3, Harmony: 10, Safety: 10, Effort: S]

- [ ] Wear OS door reference and outdoor slot proposals
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/DoorProposal.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/OutdoorProposal.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/DoorProposalTest.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/OutdoorProposalTest.kt`, `scripts/check-protocol-sync.sh`, `FEATURES.md`
  - **Goal:** Let the watch user set `outdoor_slot` and `door_ref_slot` from a proposal. The phone and macOS setup assistants have both proposals, the watch has none. Copy both files from the phone and add them to `FILES` of the sync check. Show a `Setup proposals` chip that sends `HISTORY` and `CONFIG` only on a tap. Show each proposal with an `Apply` chip that sends one `SET` and then `CONFIG`. Map `ERR READ_ONLY` to `Set ble_console=2`.
  - **Acceptance:** A unit test reads `tests/fixtures/history_reply_sample.txt` and checks the proposal of the phone test. A unit test checks the `SET` line of each proposal. `scripts/check-protocol-sync.sh` passes. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 29, Use: 4, Setup: 9, Harmony: 9, Safety: 7, Effort: M]

## Priority 3: Display and Logging (Score 22–27)

- [ ] Android heat hours in the days screen
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/DaysReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/DaysReply.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/DaysScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/DaysReplyTest.kt`, `FEATURES.md`
  - **Goal:** Show the heat hours of each day on the phone. Depends on `Heat hours columns in the daily log (daily_v7)`. Accept 83, 94 and 105 columns by the header. Add `DaysRow.heatHours` (`null` for fewer columns and an empty field). Show `Heat <N> h` beside mold and dry hours of each day.
  - **Acceptance:** A unit test parses `tests/fixtures/days_reply_v7_sample.txt`. A unit test checks `null` for `days_reply_v6_sample.txt`. A unit test checks `ParseException` for a value of 25. `scripts/check-protocol-sync.sh` passes. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` passes.
  - **Rank:** [Total: 27, Use: 6, Setup: 2, Harmony: 9, Safety: 10, Effort: S]

- [ ] macOS heat hours in the daily charts
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/DailyLog.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DailyChartView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/DailyLogTests.swift`, `FEATURES.md`
  - **Goal:** Prepare the macOS daily charts for the heat hours of `daily_v7`. Depends on `Heat hours columns in the daily log (daily_v7)`. Add `DailyRow.heatHours` (one `Int?` for each sensor, `[]` for older files). Show a heat bar chart for each sensor below the dry hours chart. Show no chart when all values are `nil`.
  - **Acceptance:** A unit test parses `tests/fixtures/days_reply_v7_sample.txt` and checks `heatHours`. A unit test checks `[]` for a `daily_v6` file. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.
  - **Rank:** [Total: 27, Use: 6, Setup: 1, Harmony: 10, Safety: 10, Effort: S]

- [ ] Wear OS sensor battery forecast in the tile
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/tile/ClimateTileModel.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/tile/ClimateTileService.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/ClimateTileModelTest.kt`, `FEATURES.md`
  - **Goal:** Show the next battery change in the watch tile. Keep the last forecast of the tail screen in `TelemetryCache` (slot, date). Show `Battery: <name> <date>` below the alert names when the date is within 30 days. Show nothing for an older or missing forecast.
  - **Acceptance:** A unit test checks the line for a date 10 days ahead. A unit test checks no line for a date 40 days ahead and for an empty cache. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 25, Use: 6, Setup: 2, Harmony: 7, Safety: 10, Effort: S]

- [ ] Wear OS heat hours of the day on the today screen
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/TodayScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/TodayScreenTextTest.kt`, `FEATURES.md`
  - **Goal:** Show the heat hours of the current day on the watch. Depends on `App parsers for the TODAY heat hours column`. Add `Heat <N> h` to the room row after the dry hours. Show no text for `null`. Show a warning color above 0.
  - **Acceptance:** A unit test checks the row text for `heatHours` 2. A unit test checks no heat text for `null`. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 25, Use: 6, Setup: 1, Harmony: 8, Safety: 10, Effort: S]

- [ ] Badges in the Android panel preview
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/PanelRenderer.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/PanelPreviewScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/PanelRendererTest.kt`, `FEATURES.md`
  - **Goal:** Show the `V`, `D`, `W`, `C`, `R` and `S` badges in the phone preview. The preview has no badges because the telemetry frame does not carry them. Read the badge masks from the last `STATUS` `LATCH` line in memory when it exists. Draw each badge with the positions of `dashboard_view.h`. Keep the label `Preview from telemetry, not the panel image`.
  - **Acceptance:** A unit test renders a frame with the `vent` bit of tile 1 and checks the inverted badge pixels against a reference from `tools/render_display_preview.cpp`. A unit test checks no badge without a `STATUS` reply. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` passes.
  - **Rank:** [Total: 25, Use: 4, Setup: 3, Harmony: 8, Safety: 10, Effort: M]

- [ ] Self-test script for install-phone-apk.sh
  - **Location:** `scripts/install-phone-apk.sh`, `.github/workflows/ci.yml`, `tests/test_scripts.sh`, `FEATURES.md`
  - **Goal:** Test the target resolution of the phone install script like `install-watch-apk.sh`. Add `--self-test` with fake `adb` through `RLCD_ADB_BIN`: a serial, `host:port` and no argument. Check the `adb -s` arguments. Add `-h`/`--help`. Run the self-test in the CI job `Host tests`.
  - **Acceptance:** `./scripts/install-phone-apk.sh --self-test` exits 0. A broken resolution exits 1. `tests/test_scripts.sh` passes.
  - **Rank:** [Total: 24, Use: 1, Setup: 5, Harmony: 8, Safety: 10, Effort: S]

- [ ] Self-test of check-protocol-sync.sh against an unlisted shared file
  - **Location:** `scripts/check-protocol-sync.sh`, `.github/workflows/ci.yml`, `FEATURES.md`
  - **Goal:** Find a protocol file that exists in both apps but is not in `FILES`. Add a check: list the `.kt` files with the same name in both `protocol` directories, remove the package line, and print `UNLISTED <name>` for an equal pair outside `FILES`. Exit 1 for an unlisted equal pair. Keep a different pair outside `FILES` as no error.
  - **Acceptance:** The `--self-test` checks one unlisted equal pair (exit 1) and one unlisted different pair (exit 0). `./scripts/check-protocol-sync.sh` passes for the current tree after the needed `FILES` entries.
  - **Rank:** [Total: 24, Use: 1, Setup: 4, Harmony: 9, Safety: 10, Effort: S]

- [ ] Parser fuzz test for the console reply assemblers
  - **Location:** `macos/RlcdCompanion/Tests/RlcdProtocolTests/ReplyAssemblerTests.swift`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/ReplyAssemblerTest.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/ReplyAssemblerTest.kt`, `FEATURES.md`
  - **Goal:** Make sure that a damaged BLE reply never crashes an app. Feed each fixture of `tests/fixtures` through the `ReplyAssembler` of each app with fixed seeds: drop one byte, flip one byte, cut the reply at each position of the first 200 bytes. The assembler must give a CRC error or an incomplete reply, never an exception other than the parse error types. Use a fixed seed list so the test is repeatable.
  - **Acceptance:** The new tests pass in the three apps. A test fails when you remove the CRC check of the assembler. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` passes. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 24, Use: 2, Setup: 3, Harmony: 9, Safety: 10, Effort: M]

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
