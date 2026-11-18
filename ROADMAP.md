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

- [ ] Mute toggle in the Wear OS app
  - Rank: [Total: 29, Use: 7, Setup: 4, Harmony: 9, Safety: 9, Effort: M]
  - Location: wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/ChimeCommand.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/ChimeCommandTest.kt, scripts/check-protocol-sync.sh
  - Goal: The watch shows the `muted=<0|1>` field of `StatusReply`, but the user cannot mute the panel chime from the wrist, for example at night. Copy `ChimeCommand.kt` of the Android app into the Wear OS protocol package and add `ChimeCommand.kt` to `FILES` of `scripts/check-protocol-sync.sh`. Add one toggle to `SensorHealthScreen`. A tap sends `MUTE 1` or `MUTE 0` through `BleConsoleClient.send`, then `STATUS`, and shows the new state from the reply. Show `ERR READ_ONLY` as `Set ble_console=2 to apply` and a timeout as `Panel not in range`. Do not change the toggle before the panel replies. Do not retry a failed command.
  - Acceptance test: Run `./scripts/check-protocol-sync.sh` and assert exit 0. In `ChimeCommandTest`, assert `MUTE 1`, `MUTE 0` and the texts of `OK MUTE 1`, `ERR READ_ONLY` and a timeout. Run `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch`.

## Priority 3: Display and Logging (Score 22–27)

- [ ] PNG export of the panel image in the macOS app
  - Rank: [Total: 27, Use: 4, Setup: 6, Harmony: 7, Safety: 10, Effort: S]
  - Location: macos/RlcdCompanion/Sources/RlcdProtocol/PanelImage.swift, macos/RlcdCompanion/Sources/RlcdCompanion/PanelView.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/PanelImageTests.swift
  - Goal: `PanelView` shows the framebuffer of the `SCREEN` reply, but the user cannot save it. A bug report or a layout check needs a file. Add `PanelImage.pngData()` in `RlcdProtocol`. It encodes the 400 x 300 pixels as a 1-bit grayscale PNG with `CoreGraphics` and `ImageIO`, black for a set pixel. Add one `Save PNG` button to `PanelView` with an `NSSavePanel` and the default name `panel_<YYYYMMDD_HHMMSS>.png`. Disable the button before the first image. Do not send a console command for the export.
  - Acceptance test: In `PanelImageTests`, decode a known 15,000-byte frame, call `pngData()`, read the PNG back with `CGImageSource` and assert the size 400 x 300 and the value of the four corner pixels and one checkerboard pixel. Run `swift test` and `swift build -c release` in `macos/RlcdCompanion`.

- [ ] Panel silence notification in the Wear OS app
  - Rank: [Total: 27, Use: 7, Setup: 5, Harmony: 7, Safety: 8, Effort: M]
  - Location: wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/SilenceWatch.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/BleScanService.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/SilenceWatchTest.kt
  - Goal: The watch tile shows `--` for a frame older than 35 min, but the user gets no notice when the panel stops to send, for example with an empty battery or a crash. Add `SilenceWatch` as a pure state machine with an injected clock. It reports `SILENT` once when the last frame is older than 35 min and `BACK` once at the next frame. Without any frame since the service start it reports nothing. `BleScanService` posts one notification `Panel silent since <HH:mm>` for `SILENT` and removes it for `BACK`. Do not vibrate for this notification. Do not raise the scan rate.
  - Acceptance test: In `SilenceWatchTest`, assert `SILENT` once at 35 min plus 1 s, no second `SILENT` at 60 min, `BACK` once at the next frame and no report without a first frame. Assert that a clock that runs back gives no `SILENT`. Run `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch`.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
