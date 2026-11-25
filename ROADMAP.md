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

- [ ] Sensor drift badge in the climate tiles
  - **Location:** `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`, `tools/render_display_preview.cpp`, `scripts/generate-display-preview.sh`, `docs/display_preview_drift.png`, `tests/test_firmware_logic.cpp`, `AGENTS.md`, `FEATURES.md`
  - **Goal:** Show the drift state on the panel, so the user sees it without an app. Add `SystemInfo::drift_tiles` and copy `PersistentState::drift_tiles` in `rlcd_panel.ino`. Bit 0 is the onboard tile, bits 1 to 10 are the slots. Draw the inverted `S` badge for each set bit. Put it beside `V`, `D`, `W`, `C` and `R` in each tile style. Do not draw the badge on a stale tile. Add the scene field `PreviewScene::drift` and the image `docs/display_preview_drift.png`. Do not change the RTC layout or `STATE_VERSION`.
  - **Acceptance:** A host test renders a tile with its drift bit set and finds the inverted `S` badge pixels. A host test checks that a clear bit and a stale tile give no badge. A host test checks the badge position beside the other badges in the STANDARD, LARGE and COMPACT styles. `./scripts/generate-display-preview.sh` creates `docs/display_preview_drift.png`. The host unit tests pass and `arduino-cli compile rlcd_panel` passes.
  - **Rank:** [Total: 33, Use: 7, Setup: 8, Harmony: 8, Safety: 10, Effort: S]


## Priority 3: Display and Logging (Score 22–27)

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
