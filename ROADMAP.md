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


## Priority 3: Audio Expansions (Score 22–27)

- [ ] Inverted display mode from config.ini
  - Rank: [Total: 23, Use: 4, Setup: 3, Harmony: 6, Safety: 10, Effort: S]
  - Location: `rlcd_panel/device_config.h`, `rlcd_panel/st7305_framebuffer.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tools/render_display_preview.cpp`, `tools/preview_scenes.h`, `scripts/generate-display-preview.sh`, `tests/test_firmware_logic.cpp`
  - Goal: Add the `config.ini` key `display_invert` (default `0`, values `0` and `1`). With `1`, invert each of the 15,000 framebuffer bytes after the render and before `St7305Panel::flush`, so the panel shows light text on a dark background. Do the inversion in `St7305Framebuffer::invert()`, not with a panel command, so the host preview stays bit-exact. Inverted badges and banners invert again and stay readable. A malformed value is rejected and counts in `ConfigRejects`. Store the flag in the config trailer of `PersistentState` and increase the state version. Show the key in the `CONFIG` reply. The reflective LCD has no backlight, so the mode does not change the power draw. Thus the user selects the better contrast for the light in the room.
  - Acceptance test: Host tests cover the parser for `0`, `1`, out-of-range and malformed values, `invert()` on each byte and a double inversion back to the original, the `CONFIG` line and its parse back, and the RTC round trip. `./scripts/generate-display-preview.sh` writes the new file `docs/display_preview_inverted.png`, and the other preview files stay bit-exact.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components and firmware modules does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
