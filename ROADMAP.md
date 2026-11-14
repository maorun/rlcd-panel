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

- [ ] Chime reminder for an alert that stays active
  - Rank: [Total: 26, Use: 6, Setup: 3, Harmony: 9, Safety: 8, Effort: M]
  - Location: `rlcd_panel/audio_alert.h`, `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
  - Goal: Add the `config.ini` key `chime_repeat_h` (default `0` is off, range `0` to `24` hours). When an alert bit stays active for this time after its last chime, the chime of the lowest active bit sounds again. `ALERT_CHARGE_FULL` never repeats. Mute, quiet hours and a cleared bit of `chime_alert_mask` suppress the repeat. A suppressed repeat is skipped and not delayed, so no chime plays at the end of the quiet hours. A new rising edge resets the timer. Store the clock stamp of the last chime in `PersistentState` (4 bytes) and the key in the config trailer (1 byte), and increase the state version. Show the key in the `CONFIG` reply. Thus the user does not forget an open window or a freezer that is too warm.
  - Acceptance test: Host tests cover the parser for the default, the edges `0` and `24`, the value `25`, a malformed value, a repeat at the exact time and over the 32-bit clock wrap, no repeat for `ALERT_CHARGE_FULL`, the lowest active bit, mute, quiet hours and a cleared mask bit, the reset by a new edge, and the RTC round trip.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components and firmware modules does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
