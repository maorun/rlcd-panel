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

### GitHub Actions CI for host tests and firmware compile

- **Rank:** Feature model. Total 34/40 (use 4, setup 10, harmony 10, safety 10). Effort S.
- **Area:** Tooling / CI.
- **Code:** `.github/workflows/ci.yml` (new), `README.md`.
- **Problem:** The repository has no CI. Each check runs only on the local machine of the maintainer. A pull request can break the host tests, the preview renderer, or the firmware build without a signal.
- **Goal:** Add one workflow that runs on each pull request and on each push to `master`. Job 1 builds and runs `tests/test_firmware_logic.cpp` with `-std=c++17 -Wall -Wextra -Werror`. Job 1 also compiles `tools/render_display_preview.cpp` and renders all scenes with `--all`. Job 2 installs `arduino-cli`, installs `esp32:esp32@3.3.12` from the index URL in `sketch.yaml`, and runs `arduino-cli compile --profile rlcd_panel_s3 rlcd_panel`. Cache the `arduino-cli` data directory with the core version in the cache key. Pin each action to a major version. Do not use secrets, because `wifi_config.h` is optional.
- **Acceptance test:** The workflow passes on a branch from `master`. A deliberately failing host assertion on a test branch makes job 1 fail. Report a run without a runner (billing, policy or quota) as "not run".

### Microphone calibration with stored noise floor and SPL offset

- **Rank:** Feature model. Total 34/40 (use 7, setup 10, harmony 8, safety 9). Effort M.
- **Area:** Sensors / Audio / Setup.
- **Code:** `rlcd_panel/es7210_mic.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/button_handler.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`.
- **Problem:** `NoiseClassifier` uses fixed, uncalibrated PCM RMS limits. The 30 dB microphone gain and the board tolerance are not verified on hardware. The diagnostics view shows dBFS, not a sound pressure level. Two boards can show different bands in the same room.
- **Goal:** Add a `MicCalibration` struct with an SPL offset (`int16_t`, the dB SPL level that matches 0 dBFS) and a measured noise floor (RMS). Add a calibration mode: a `KEY` long press (2 s or more) in the diagnostics view starts a 10 s measurement in a quiet room and stores the noise floor. A short press keeps the mode change. The SPL offset comes from a compile-time default (`MIC_SPL_OFFSET_DB`), measured once against a reference sound level meter. A later config file can override it (see "Configurable thresholds"). Store the calibration in NVS with `Preferences`, with a version and a CRC, so a flash erase or a corrupt record gives the default. Derive the band limits from the noise floor (for example floor + 10 dB and floor + 30 dB). Show `NOISE: <BAND> <dB> DBA` with the offset, or `DBFS` without a calibration. Show `MIC CAL: OK` or `MIC CAL: DEFAULT` in the diagnostics view.
- **Acceptance test:** Host tests cover the dBFS to dB SPL conversion with offset and clamp, the derived band limits with hysteresis, the long-press detection (2 s limit, release before the limit), the serialization with version and CRC rejection, and the line width in the diagnostics view.

## Priority 2: Audio, Storage, and Configuration (Score 28–33)

### Quiet hours for chime alerts

- **Rank:** Feature model. Total 30/40 (use 7, setup 6, harmony 7, safety 10). Effort S.
- **Area:** Audio / RTC.
- **Code:** `rlcd_panel/audio_alert.h`, `rlcd_panel/pcf85063_rtc.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`.
- **Problem:** A freezer or mold alert chimes at any time, also at night.
- **Goal:** Suppress the chime sound between 22:00 and 07:00 local RTC time. Keep the alert state tracking active, so the alert does not replay in the morning. Support a window that crosses midnight and an empty window (start equals end). Without a valid RTC time, do not suppress. Show a small `Q` marker in the header while quiet hours are active.
- **Acceptance test:** Host tests cover the window edges (21:59, 22:00, 06:59, 07:00), a window that does not cross midnight, the empty window, an invalid RTC time, and that an alert that starts in quiet hours does not chime after the window ends.

### Configurable thresholds and settings from the MicroSD card

- **Rank:** Feature model. Total 30/40 (use 6, setup 9, harmony 8, safety 7). Effort M.
- **Area:** Storage / Setup.
- **Code:** `rlcd_panel/device_config.h` (new), `rlcd_panel/sd_logger.h`, `rlcd_panel/audio_alert.h`, `rlcd_panel/shtc3_sensor.h`, `rlcd_panel/es7210_mic.h`, `rlcd_panel/rlcd_panel.ino`.
- **Problem:** The SHTC3 offset (`-1.9 °C`), the alert limits (0 °C, 70 %RH), the chime volume (30 %), the quiet hours, and the microphone SPL offset are compile-time constants. A change needs a new flash.
- **Goal:** Read an optional `/sdcard/config.ini` with `key=value` lines on power-on and on a `KEY` wakeup. Add a host-testable parser without Arduino dependency. Clamp each value to a safe range, and keep the default for a missing, malformed, or out-of-range value. Ignore unknown keys. Limit the file size to 4 KB. Do not read Wi-Fi credentials from the card. Copy the accepted values to RTC memory, so a timer wakeup does not mount the card. Show `CONFIG: SD` or `CONFIG: DEFAULT` in the diagnostics view.
- **Acceptance test:** Host tests cover valid values, comments, spaces, CRLF line ends, missing keys, out-of-range values, duplicate keys (last value wins), a file larger than 4 KB, and the RTC round trip with a corrupt record.

### MicroSD noise logging with hourly measurement

- **Rank:** Feature model. Total 30/40 (use 7, setup 6, harmony 10, safety 7). Effort M.
- **Area:** Storage / Audio.
- **Code:** `rlcd_panel/sd_logger.h`, `rlcd_panel/es7210_mic.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`.
- **Dependency:** "Microphone calibration" gives the dB SPL value.
- **Problem:** The board measures noise only when the diagnostics view is on screen after a `KEY` wakeup. The CSV log has no noise data, so the user cannot see noise patterns over the day.
- **Goal:** Run one 5 s `NoiseSampler` window in the wake cycle that writes the hourly CSV record, also after a timer wakeup. Add the columns `noise_rms`, `noise_db`, and `noise_band` to each row. An invalid reading gives empty fields. Change the file name to `/sdcard/climate_v2_YYYYMM.csv`, so the new header never mixes with rows of the old 15-column format. Keep the microphones off on all other timer wakeups. Skip the measurement with a low battery alert.
- **Acceptance test:** Host tests cover the 18-column header, rows with valid and invalid noise data, the new file name bounds, the gate that measures only when a log is due, the low battery skip, and that the sampler powers down the ADC on each exit path.

### Microphone self-test in the diagnostics view

- **Rank:** Feature model. Total 29/40 (use 3, setup 9, harmony 8, safety 9). Effort S.
- **Area:** Audio / Diagnostics.
- **Code:** `rlcd_panel/es7210_mic.h`, `rlcd_panel/dashboard_view.h`, `tests/test_firmware_logic.cpp`.
- **Problem:** `NoiseSampler` averages both microphones into one value. A dead microphone, a stuck channel, or a missing I2S clock gives a low reading, and the view shows `QUIET` as if the room is quiet.
- **Goal:** Accumulate RMS, DC mean, and the peak per channel during the existing window. Classify each channel as `OK`, `SILENT` (all samples zero), `STUCK` (peak to peak below a small limit), `DC` (mean offset above a limit), or `CLIP` (peak at full scale). Report a channel imbalance above 10 dB. Show `MIC1: OK MIC2: SILENT` in the diagnostics view. Do not show a noise band when both channels fail.
- **Acceptance test:** Host tests feed synthetic stereo buffers: two good channels, one zero channel, a constant channel, a DC offset, a clipped channel, and an imbalance. The tests also check the line width in the view.

### Speaker to microphone loopback test

- **Rank:** Feature model. Total 29/40 (use 2, setup 9, harmony 10, safety 8). Effort M.
- **Area:** Audio / Diagnostics.
- **Code:** `rlcd_panel/audio_loopback.h` (new), `rlcd_panel/audio_alert.h`, `rlcd_panel/es7210_mic.h`, `rlcd_panel/rlcd_panel.ino`.
- **Problem:** The user cannot verify the speaker path (`ES8311`, amplifier, speaker) and the microphone path (`ES7210`) together. A silent chime gives no signal.
- **Goal:** On a `BOOT` long press in the diagnostics view, play a 1 kHz tone for 500 ms at the configured volume and record it at the same time over the shared I2S bus. Detect the tone with a Goertzel filter. Report `AUDIO LOOP: PASS <dB>` or `AUDIO LOOP: FAIL`. Switch off the amplifier and power down both codecs on each exit path. Skip the test while muted or during quiet hours.
- **Acceptance test:** Host tests cover the Goertzel detector with a 1 kHz sine, a 2 kHz sine, silence and white noise, the pass limit, the amplifier off order on success and on each failure, and the mute skip.

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
