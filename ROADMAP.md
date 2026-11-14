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

- [ ] Heat alert chime for a high feels-like temperature
  - Rank: [Total: 32, Use: 9, Setup: 6, Harmony: 9, Safety: 8, Effort: M]
  - Location: `rlcd_panel/audio_alert.h`, `rlcd_panel/climate_math.h`, `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/dashboard_view.h`, `docs/config.example.ini`, `tests/test_firmware_logic.cpp`
  - Goal: Add the alert bit `ALERT_HEAT` (bit 7, value 128) and the `config.ini` key `heat_alert_limit_c` (0 is off, else 27 to 45 C, default 0). `AlertEvaluator` sets the bit when `ClimateMath::heatIndexC` of a tile in `climate_alert_tiles` is at or above the limit. It releases the bit when each such tile is 2 C below the limit, or has no usable reading. Increase `MAX_CHIME_ALERT_MASK` to 255 and add bit 7 to `chime_alert_mask`; the default mask keeps bit 7 on. The chime is a sequence of two short high notes. The rising and the falling edge go to the event ring as `ALERT_RISE` and `ALERT_FALL`. `BEEP 7` plays the chime. Store the limit in the config trailer (1 byte) and increase the state version. Thus the user gets a warning for a hot room in summer.
  - Acceptance test: Host tests cover the parser for 0, 27, 45, 26, 46 and a malformed value, the trigger at the limit, the release with hysteresis, a tile outside `climate_alert_tiles`, a stale tile, the limit 0, the old mask value 127 and the new value 255, `BEEP 7`, the event ring entries, the `CONFIG` line, the template sync test with `docs/config.example.ini`, and the RTC round trip with a corrupt field and the old state version. `arduino-cli compile rlcd_panel` passes.

## Priority 3: Audio Expansions (Score 22–27)

- [ ] Display test pattern from the serial console
  - Rank: [Total: 27, Use: 2, Setup: 9, Harmony: 7, Safety: 9, Effort: S]
  - Location: `rlcd_panel/serial_console.h`, `rlcd_panel/st7305_framebuffer.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
  - Goal: Add the console command `PATTERN <0|1|2>`. Pattern 0 fills each pixel black, pattern 1 is a 1-pixel checkerboard, pattern 2 is a 1-pixel border with a cross from corner to corner. Add `St7305Framebuffer::fillPattern(uint8_t)`. The command renders the pattern, flushes it without `display_invert`, and replies `OK PATTERN <N>`. The next wake cycle renders the normal view again. Another value gives `ERR BAD_ARG`. The command runs on USB power only and does not mount the card. Thus the user finds dead pixels, a bad row or a wrong packing after a hardware change.
  - Acceptance test: Host tests cover the parser for each pattern, the values 3 and -1, extra text, the bit-exact framebuffer bytes of each pattern with the packing rule of the `ST7305` (corner pixels at (0,0), (399,0), (0,299) and (399,299)), the ignored `display_invert`, and the USB power gate. `arduino-cli compile rlcd_panel` passes.

- [ ] Daily noise peak summary in the daily file
  - Rank: [Total: 25, Use: 6, Setup: 2, Harmony: 9, Safety: 8, Effort: M]
  - Location: `rlcd_panel/sd_logger.h`, `rlcd_panel/sparkline.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `scripts/dump-logs.sh`, `tests/test_firmware_logic.cpp`
  - Goal: Add three columns to the daily summary row: `noise_peak_db` (highest hourly `noise_db` of the day), `noise_peak_hour` (local hour 0 to 23 of the peak) and `noise_events_sum` (sum of `noise_events` of the day). Keep the values in `PersistentState::daily` and increase the state version. Write the rows to a new file `/sdcard/daily_v2_YYYY.csv` (30 columns); the old `daily_v1_YYYY.csv` files stay unchanged. `LIST`, `DUMP`, `scripts/dump-logs.sh` and the retention accept both file names. A day without a valid noise reading gives empty fields. Thus the user finds loud hours over many weeks without a read of each hourly file.
  - Acceptance test: Host tests cover the new header and row format, a day with one peak, two equal peaks (the first hour wins), a day without a noise reading, the sum saturation at 65535, the file name, `DUMP daily_v2_YYYY.csv`, the retention of both file names, and the RTC round trip with a corrupt field and the old state version. `arduino-cli compile rlcd_panel` passes.


## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components and firmware modules does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
