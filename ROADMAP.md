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

- [ ] Badge intervals in the macOS alert timeline
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/AlertTimeline.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/AlertTimelineTests.swift`, `FEATURES.md`
  - **Goal:** Show when each badge of a room started and ended. Add `BadgeInterval` and `AlertTimeline.badges(rows:now:)`. Pair each set row of `WINDOW_OPEN`, `WALL_DAMP`, `DRY_AIR` and `SENSOR_DRIFT` with the next clear row of the same tile. Read the tile from the detail low byte and the state from the high byte. Use the injected `now` as the end of an open interval. Mark an interval without a set row as `startUnknown`. Show the intervals in `DiagnosticsView` below the alert intervals. Show the badge, the room name, the start, the end and the duration.
  - **Acceptance:** A unit test pairs one `DRY_AIR` set row and one clear row to one interval. A unit test checks that a `WINDOW_OPEN` set row without a clear row ends at `now`. A unit test checks that a clear row without a set row gives `startUnknown`. A unit test checks that two tiles of one badge give two intervals. A unit test parses `tests/fixtures/events_reply_sample.txt`. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.
  - **Rank:** [Total: 28, Use: 6, Setup: 4, Harmony: 8, Safety: 10, Effort: M]

## Priority 3: Display and Logging (Score 22–27)

- [ ] Newest log rows with gap report on the Wear OS watch
  - **Location:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/TailReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/LogGaps.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/TailScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/TailReplyTest.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/LogGapsTest.kt`, `scripts/check-protocol-sync.sh`, `FEATURES.md`
  - **Goal:** Show on the watch if the card log has missing hours. Copy the Android `TailReply.kt` and `LogGaps.kt` into the watch protocol package. Add both names to the `FILES` list of `scripts/check-protocol-sync.sh`. Add `TailScreen`. Send `TAIL 6` only after a tap. Show the newest row of each room, the gap count and one line for each gap. Show `Keine Lücken` for a report without a gap.
  - **Acceptance:** A unit test parses `tests/fixtures/tail_reply_sample.txt`. A unit test checks that a step of 3 hours gives one gap with 2 missing hours. A unit test checks that hourly rows give no gap. `scripts/check-protocol-sync.sh` and `scripts/check-protocol-sync.sh --self-test` exit with 0. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 27, Use: 4, Setup: 5, Harmony: 8, Safety: 10, Effort: M]

- [ ] Room picker for the macOS today view
  - **Location:** `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/TodayReplyTests.swift`, `FEATURES.md`
  - **Goal:** Read the day range of one room with a short BLE reply. Add `ConsoleCommand.todaySensor(Int)` with the line `TODAY <N>` for 0 to 10. Give no line for another number. Add a room picker with `All` and the tile names to the `Today` group of `DiagnosticsView`. Send `TODAY` again after `ERR BAD_ARG` from an older firmware. Then keep only the row of the selected room.
  - **Acceptance:** A unit test checks the lines `TODAY 3` and `TODAY`. A unit test checks that 11 and -1 give no line. A unit test checks the fallback decision for `ERR BAD_ARG`. A unit test parses `tests/fixtures/today_one_reply_sample.txt`. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.
  - **Rank:** [Total: 25, Use: 5, Setup: 3, Harmony: 7, Safety: 10, Effort: S]

- [ ] Dry hours in the week chart
  - **Location:** `rlcd_panel/sparkline.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`, `tests/test_firmware_logic.cpp`, `docs/display_preview_chart_week.png`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Show on the panel how many hours of the week each room was too dry. Add one dry hour byte for each day and sensor to `WeekHistory`, after the mold bytes. Store the hours from `HumidityHistory::countAtOrBelow` with `dry_alert_limit_rh` in `WeekHistory::advance`. Store `0xFF` for a gap day and for limit 0. Shift, swap and clear the bytes with the mold bytes. Add `DashboardView::formatWeekDry`. Append `  DRY <N>H` after the `MOLD <N>H` text of the humidity label line. Keep the `WEEK` reply and the daily file unchanged. Raise `STATE_VERSION` by one.
  - **Acceptance:** A host test checks that `advance` stores 5 hours for 5 dry samples. A host test checks `0xFF` for limit 0 and for a gap day. A host test checks that the RTC restore rejects 25 and a value beside a gap humidity pair. A host test checks `DRY 12H` for a sum of 12 hours. A host test checks that `SLOT SWAP` swaps the dry bytes. The host unit tests pass. `./scripts/generate-display-preview.sh` updates `docs/display_preview_chart_week.png`. `arduino-cli compile rlcd_panel` passes.
  - **Rank:** [Total: 23, Use: 5, Setup: 2, Harmony: 8, Safety: 8, Effort: M]



## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
