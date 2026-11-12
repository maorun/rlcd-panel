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

## Priority 1: Core Tooling and Calibration (Score 34–40)

## Priority 2: Audio, Storage, and Configuration (Score 28–33)

- [ ] Ventilation advice from indoor and outdoor absolute humidity
  - Rank: [Total: 30, Use: 8, Setup: 4, Harmony: 9, Safety: 9, Effort: M]
  - Location: `rlcd_panel/climate_math.h`, `rlcd_panel/device_config.h`, `rlcd_panel/audio_alert.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`, `tests/test_firmware_logic.cpp`
  - Goal: Add the `config.ini` key `outdoor_slot` (default `0` is off, range `0` to `3`). Calculate the absolute humidity in g/m³ for the onboard sensor and for the outdoor slot with `ClimateMath`. Show the banner `VENTILATE` when the indoor humidity is above `mold_alert_limit_rh` and the outdoor air holds at least 2.0 g/m³ less water. Release the banner when the difference is below 1.0 g/m³ or the indoor humidity is 3 %RH below the limit. Add the alert `ALERT_VENTILATE` (bit 5 of `chime_alert_mask`, new range `0` to `63`) with one chime per trigger. Mute and quiet hours suppress the chime. A stale outdoor slot gives no advice.
  - Acceptance test: Host tests cover the absolute humidity at 0 °C, 20 °C and 30 °C against reference values, the trigger and the release with the hysteresis, a stale or empty outdoor slot, `outdoor_slot=0`, the mask bit, mute and quiet hours, the new mask range, the banner bounds, and the RTC round trip. `docs/display_preview_dashboard.png` shows the banner in one scene.

## Priority 3: Audio Expansions (Score 22–27)

- [ ] L10 and L90 noise levels in the hourly window
  - Rank: [Total: 23, Use: 4, Setup: 3, Harmony: 8, Safety: 8, Effort: M]
  - Location: `rlcd_panel/es7210_mic.h`, `rlcd_panel/sd_logger.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
  - Goal: Split the 5 s `NoiseSampler` window into 50 blocks of 100 ms. Calculate the calibrated level of each block. Report L10 (the level that 10 % of the blocks exceed) and L90 (the background level that 90 % of the blocks exceed). Show `L10/L90: NN/NN DBA` in the diagnostics view. Add the columns `noise_l10` and `noise_l90` to the CSV (format version 4, new file name `climate_v4_YYYYMM.csv`). Include the new file in `LIST`, `DUMP` and the retention rule. Do not keep the microphones on longer than the existing window.
  - Acceptance test: Host tests cover a steady tone (L10 equals L90), a single loud block, a ramp of 50 blocks, a failed window with empty fields, the uncalibrated `DBFS` text, the CSV header and row with 21 columns, the console file name check, and the line bounds. The old CSV files stay unchanged.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components and firmware modules does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
