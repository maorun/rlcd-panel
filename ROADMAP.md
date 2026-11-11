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

- [ ] Sensor dropout chime
  - Rank: [Total: 27, Use: 6, Setup: 6, Harmony: 7, Safety: 8, Effort: S]
  - Location: `rlcd_panel/audio_alert.h`, `rlcd_panel/switchbot_ble.h`, `rlcd_panel/rlcd_panel.ino`.
  - Goal: Tell the user when a known external sensor stops sending, for example because its battery is empty. Add the alert bit `ALERT_SENSOR_LOST`. Set it when a valid slot becomes stale. Play one low 440 Hz note when the alert switches on. Obey the mute flag and quiet hours. Clear the bit when each valid slot sends again.
  - Acceptance test: Host tests cover the change from fresh to stale, a slot that was never valid, the recovery, the mute and quiet hours suppression, and the RTC round trip of the new bit. On the board, the chime sounds once when a sensor gets its battery removed for more than 30 min.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components and firmware modules does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
