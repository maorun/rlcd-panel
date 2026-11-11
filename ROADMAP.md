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

No open item.

## Priority 2: Audio, Storage, and Configuration (Score 28–33)

No open item.

## Priority 3: Audio Expansions (Score 22–27)

### Acoustic double-clap detection in the awake window

- **Rank:** Feature model. Total 22/40 (use 5, setup 3, harmony 8, safety 6). Effort L.
- **Area:** Audio / Input.
- **Code:** `rlcd_panel/clap_detector.h` (new), `rlcd_panel/es7210_mic.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`.
- **Problem:** The user must touch the board to change the view. The ES7210 has no interrupt output, and deep sleep cannot sample I2S. Thus a sound cannot wake the board from deep sleep.
- **Goal:** Detect a double clap only while the board is awake. On USB power, keep the board awake with light sleep between 20 ms audio frames, and listen continuously. On battery, listen only in the existing 3 s grace window after a `KEY` wakeup. Detect a clap as a short energy transient (rise above the noise floor + 20 dB in less than 5 ms, decay in less than 100 ms). Accept two claps with 150 ms to 600 ms between them. Ignore a third clap in a short window. A double clap cycles the display mode, as a `KEY` press does. Switch off the microphones before deep sleep on each path.
- **Acceptance test:** Host tests feed synthetic envelopes: one clap, two claps at 100 ms, 300 ms and 800 ms, three claps, continuous loud noise, and speech-like bursts. Only the 300 ms case triggers. Further tests cover the USB and battery gate and the microphone power-down before sleep. Measure the USB idle current on hardware and record it in `FEATURES.md`.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components and firmware modules does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
