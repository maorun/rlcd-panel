# Roadmap

This file lists open work for `rlcd-panel`, the standalone Arduino CLI firmware for the **Waveshare ESP32-S3-RLCD-4.2** development board.
The device renders climate telemetry from onboard sensors and external SwitchBot thermo-hygrometers onto a 4.2-inch reflective LCD.
The list uses priorities. Priority 1 is the highest priority.
Do the work in priority order, unless the maintainer gives a different order.

## Rules for this file

- Write in ASD-STE100 Simplified Technical English.
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

- [ ] Show slots 4 and 5 on a second page of the stats view
  - Rank: [Total: 27, Use: 7, Setup: 3, Harmony: 8, Safety: 9, Effort: M]
  - Location: `rlcd_panel/dashboard_view.h`, `rlcd_panel/button_handler.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`, `scripts/generate-display-preview.sh`, `tests/test_firmware_logic.cpp`, `AGENTS.md`
  - Goal: The stats view has room for three slot blocks (`STATS_SLOT_COUNT` 3). Thus the comfort, mold, heat index and record lines of slots 4 and 5 do not show. Add a second stats page with slots 4 and 5. In the stats view a short `KEY` press returns `ButtonEvent::NEXT_PAGE` and selects the other page. A hold of 2 s changes the view, the same as in the chart view. Show the second page only when slot 4 or slot 5 is active (`activeTileCount` rules). Else a short press changes the view as before. Show `1/2` or `2/2` in the header. Store the page in one byte of `PersistentState` after `chart_sensor` (a value above 1 is rejected) and increase `STATE_VERSION`. Keep the first page bit-exact. Add the preview image `docs/display_preview_stats2.png`.
  - Acceptance test: Host tests assert that a short press in the stats view with an active slot 4 gives `NEXT_PAGE`, that a short press without slot 4 and slot 5 gives `MODE_CHANGED`, that a hold of 2 s gives `MODE_CHANGED`, that the first page is bit-exact against the old render, and that an RTC page byte of 2 is rejected. `./scripts/generate-display-preview.sh` writes `docs/display_preview_stats2.png`. The C++ host tests and `arduino-cli compile rlcd_panel` pass.

- [ ] Start the macOS app at login on user request
  - Rank: [Total: 23, Use: 6, Setup: 4, Harmony: 3, Safety: 10, Effort: S]
  - Location: `macos/RlcdCompanion/Sources/RlcdProtocol/LoginItem.swift` (new), `macos/RlcdCompanion/Sources/RlcdCompanion/Views.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/LoginItemTests.swift` (new)
  - Goal: The menu bar app stops at logout, and the user must start it again by hand. Add an `Open at login` toggle. Use `SMAppService.mainApp` to register and unregister the app. Put the state logic in `LoginItem` behind a protocol, so that the tests use a fake service. Read the current state from the service at each window open. Do not store a copy of the state. Show the `requiresApproval` state with a button that opens the Login Items pane of System Settings (`SMAppService.openSystemSettingsLoginItems()`). Show an error text when the registration fails. Keep the toggle off as the default. The `swift run` build without an app bundle shows the toggle as not available.
  - Acceptance test: `LoginItemTests` with a fake service assert that the toggle on calls `register()` once, that the toggle off calls `unregister()`, that a thrown error gives the error text and keeps the old state, and that `requiresApproval` gives the approval state. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass. `./scripts/build-app.sh` builds a signed bundle.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components and firmware modules does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
