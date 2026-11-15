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

- [ ] Copy one diagnostics report to the clipboard in the macOS app
  - Rank: [Total: 32, Use: 4, Setup: 10, Harmony: 8, Safety: 10, Effort: S]
  - Location: `macos/RlcdCompanion/Sources/RlcdProtocol/DiagnosticsReport.swift` (new), `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/DiagnosticsReportTests.swift` (new)
  - Goal: For a fault report the user must copy the replies of `VERSION`, `STATUS`, `CONFIG` and `EVENTS` by hand. Add a `Copy report` button to `DiagnosticsView`. It sends `VERSION`, `STATUS`, `CONFIG` and `EVENTS` one after the other on a user action. `DiagnosticsReport.build` joins the verified reply lines in this order, with one `## <COMMAND>` heading each, the app version and the link type (`USB` or `BLE`) at the top. A failed reply gives the line `ERROR <reason>` below its heading and the other sections stay. Remove each `CRC32` line. Remove the values of each key that `ConfigSchema` marks as a name or a MAC, and write `<hidden>` instead, unless the user selects `Include names and MACs`. Put the text on `NSPasteboard`. The report holds no Wi-Fi data, because the firmware sends none.
  - Acceptance test: `DiagnosticsReportTests` build a report from four recorded replies and assert the section order, the absence of `CRC32` lines, and `slot1_mac=<hidden>` and `onboard_name=<hidden>` in the default mode. With `Include names and MACs` the MAC value stays. A failed `EVENTS` reply gives `ERROR` below `## EVENTS` and the three other sections stay. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.

- [ ] Count the BLE reception rate of each registry slot
  - Rank: [Total: 30, Use: 6, Setup: 9, Harmony: 8, Safety: 7, Effort: M]
  - Location: `rlcd_panel/switchbot_ble.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `macos/RlcdCompanion/Sources/RlcdProtocol/Diagnostics.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/DiagnosticsTests.swift`, `AGENTS.md`
  - Goal: The `STATUS` reply shows the RSSI and the age of each sensor, but not how often the scan misses it. A weak sensor at the edge of the range is thus hard to find. Add `ReceptionHistory` to `switchbot_ble.h`: one 24-bit mask for each of the 5 slots. After each BLE scan, shift the mask of each occupied slot by one bit and set bit 0 when the slot got a valid packet in this scan. A slot without a MAC keeps mask 0 and count 0. `resetSlot` clears the mask. Store the masks and a count of the scans (up to 24) in `PersistentState` just before the checksum and increase `STATE_VERSION`. A power-on clears them. Add the line `RECEPTION s1=<N>/<M> s2=<N>/<M> s3=<N>/<M> s4=<N>/<M> s5=<N>/<M>` (`--` for an empty slot) after `BLE` in the `STATUS` reply. Parse the line in `Diagnostics.swift` and show the rate per slot in `DiagnosticsView`. Mark a rate below 50 % with a warning symbol.
  - Acceptance test: Host tests assert that 24 scans with a packet in each second scan give `12/24`, that a slot with 3 scans gives `N/3`, that `resetSlot` gives `--`, that an RTC image of the old `STATE_VERSION` is rejected, and that a mask with a count above 24 is rejected. `DiagnosticsTests` parse `RECEPTION s1=12/24 s2=-- s3=24/24 s4=-- s5=1/3`, give `nil` for `--`, and throw `ParseError.badLine` for `s1=x/24`. The C++ host tests, `arduino-cli compile rlcd_panel`, `swift test` and `swift build -c release` pass.

- [ ] Show the hourly climate logs as charts in the macOS app
  - Rank: [Total: 29, Use: 8, Setup: 3, Harmony: 8, Safety: 10, Effort: M]
  - Location: `macos/RlcdCompanion/Sources/RlcdProtocol/ClimateLog.swift` (new), `macos/RlcdCompanion/Sources/RlcdCompanion/ClimateChartView.swift` (new), `macos/RlcdCompanion/Sources/RlcdCompanion/ArchiveView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/ClimateLogTests.swift` (new)
  - Goal: `ArchiveView` downloads the monthly `climate_v2` to `climate_v5` files, but the app shows only the daily files. Add `ClimateLog.parse(text:)` for the four formats. Use the header row to select the format (`climate_v5` has 27 columns and six sensors, the older formats have four sensors). Give `nil` for an empty field. Throw `ParseError.badLine` for a row with a wrong column count and `ParseError.missingLine` for an unknown header. Add `ClimateLog.load(from:month:)` that reads the files of one month from the selected folder. Show one Swift Charts view per sensor with the hourly temperature and humidity, and one chart with `noise_db`, `noise_l10` and `noise_l90` for `climate_v5` rows. Let the user select the month. Read only local files. Send no console command.
  - Acceptance test: `ClimateLogTests` parse one `climate_v5` sample from `SdLogCodec` output and one `climate_v4` sample, and assert the values of one row. An empty slot field gives `nil`. A `climate_v5` row with 26 columns throws `badLine`. A file with an unknown header throws `missingLine`. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass.

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
