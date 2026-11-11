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

### 24-hour noise history in the stats view

- **Rank:** Feature model. Total 28/40 (use 6, setup 4, harmony 9, safety 9). Effort S.
- **Area:** Visuals / Audio.
- **Code:** `rlcd_panel/sparkline.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `tools/preview_scenes.h`.
- **Dependency:** "MicroSD noise logging" gives one hourly noise value.
- **Problem:** The display shows only the last noise reading. The noise trend over the day is visible only in the CSV file.
- **Goal:** Store 24 hourly noise values (one byte each, dB) in RTC memory and increase the state version. Draw a noise sparkline with the minimum and maximum of the last 24 h in the sensor stats view. Skip invalid hours. Add the curve to the preview scenes.
- **Acceptance test:** Host tests cover the FIFO over 24+ samples, invalid samples, the pixel bounds, the RTC round trip with a corrupt field, and the old state version that gives a clean start. The preview generator renders the stats view with the curve.

## Priority 3: Audio Expansions (Score 22–27)

### Audio frequency spectrum visualizer

- **Rank:** Feature model. Total 27/40 (use 4, setup 7, harmony 8, safety 8). Effort M.
- **Area:** Audio / Visuals.
- **Code:** `rlcd_panel/audio_spectrum.h` (new), `rlcd_panel/button_handler.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `tools/preview_scenes.h`.
- **Problem:** The noise band shows the level only. The user cannot see the source type, for example a fan hum at 100 Hz or a high whine.
- **Goal:** Add a fourth display mode `AUDIO_SPECTRUM` after `SYSTEM_DIAGNOSTICS` and set `DISPLAY_MODE_COUNT` to 4. Reject a stored view mode above 3 in the RTC state. Capture 1 s of audio on entry to the view. Run a 512-point radix-2 FFT with a Hann window on the mono mix. Group the bins into 32 logarithmic bands from 100 Hz to 8 kHz. Draw 32 bars with a peak marker and frequency labels (`100`, `1K`, `8K`). Each `KEY` press in this view captures again, before the next mode change. Keep the microphones off on timer wakeups.
- **Acceptance test:** Host tests feed sines at 250 Hz, 1 kHz and 4 kHz and check that the highest bar is in the correct band. The tests also cover silence (all bars at zero), the band edges, the pixel bounds, and the mode cycle with 4 modes. The preview generator renders the view with a fixed sample spectrum.

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
