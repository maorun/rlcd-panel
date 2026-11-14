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

- [ ] Onboard SHTC3 read failure counter in STATUS and the diagnostics view
  - Rank: [Total: 35, Use: 7, Setup: 10, Harmony: 9, Safety: 9, Effort: S]
  - Location: `rlcd_panel/shtc3_sensor.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`, `tests/test_firmware_logic.cpp`
  - Goal: Add `Shtc3Health` to `shtc3_sensor.h`. It counts the failed reads of the onboard `SHTC3` by cause: `NACK` (an I2C command without an acknowledgement), `SHORT` (fewer than 6 bytes) and `CRC` (a CRC-8 mismatch in the frame). Each counter saturates at 65535. It also holds the count of consecutive failures, which a good read sets to 0. `readOnboardShtc3` returns the cause and does not change the bus sequence. Store the counters in `PersistentState` (7 bytes) and increase the state version. A power-on sets the counters to 0. Add the line `SHTC3 nack=<N> short=<N> crc=<N> run=<N>` to the `STATUS` reply before `POWER`. Show `SHTC3 ERR: <N>` (sum of the three counters) in the diagnostics view, inverted while `run` is 3 or more. Thus the user finds a loose sensor or a bad bus before the onboard tile shows wrong values.
  - Acceptance test: Host tests cover the classification of each cause from a fake I2C result, a valid frame, a frame with a bad temperature CRC and a bad humidity CRC, the saturation at 65535, the reset of `run` after a good read, the `STATUS` line format and its position, the inverted diagnostics text at `run` 3, the RTC round trip with a corrupt field and the old state version. `./scripts/generate-display-preview.sh` gives a diagnostics preview with the new line. `arduino-cli compile rlcd_panel` passes.

## Priority 2: Audio, Storage, and Configuration (Score 28–33)

- [ ] Rejected BLE packet counter in STATUS
  - Rank: [Total: 33, Use: 5, Setup: 10, Harmony: 9, Safety: 9, Effort: S]
  - Location: `rlcd_panel/switchbot_ble.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
  - Goal: Add a per-scan counter to `SwitchBotRegistry`. It counts the decoded packets of each format (`WOIO`, `METER`, `BTHOME`, `ATC`) that the registry rejected because no slot was free or because each free slot has a fixed MAC of a different sensor. It also records the MAC of the last rejected packet. Reset the counters at the start of each scan; do not store them in `PersistentState`. Add the line `BLE rejected=<N> last=<MAC|--> woio=<N> meter=<N> bthome=<N> atc=<N>` to the `STATUS` reply before `POWER`. Thus the user finds a wrong `slotN_mac` entry or a fourth sensor that takes no slot.
  - Acceptance test: Host tests cover a full registry with a new MAC, a fixed slot with a different MAC, a fixed slot with the correct MAC (no count), each format counter, the saturation of each counter at 65535, the reset at the scan start, the last MAC, the `STATUS` line format with and without a rejected packet, and the unchanged slot contents. `arduino-cli compile rlcd_panel` passes.

- [ ] Heat alert chime for a high feels-like temperature
  - Rank: [Total: 32, Use: 9, Setup: 6, Harmony: 9, Safety: 8, Effort: M]
  - Location: `rlcd_panel/audio_alert.h`, `rlcd_panel/climate_math.h`, `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/dashboard_view.h`, `docs/config.example.ini`, `tests/test_firmware_logic.cpp`
  - Goal: Add the alert bit `ALERT_HEAT` (bit 7, value 128) and the `config.ini` key `heat_alert_limit_c` (0 is off, else 27 to 45 C, default 0). `AlertEvaluator` sets the bit when `ClimateMath::heatIndexC` of a tile in `climate_alert_tiles` is at or above the limit. It releases the bit when each such tile is 2 C below the limit, or has no usable reading. Increase `MAX_CHIME_ALERT_MASK` to 255 and add bit 7 to `chime_alert_mask`; the default mask keeps bit 7 on. The chime is a sequence of two short high notes. The rising and the falling edge go to the event ring as `ALERT_RISE` and `ALERT_FALL`. `BEEP 7` plays the chime. Store the limit in the config trailer (1 byte) and increase the state version. Thus the user gets a warning for a hot room in summer.
  - Acceptance test: Host tests cover the parser for 0, 27, 45, 26, 46 and a malformed value, the trigger at the limit, the release with hysteresis, a tile outside `climate_alert_tiles`, a stale tile, the limit 0, the old mask value 127 and the new value 255, `BEEP 7`, the event ring entries, the `CONFIG` line, the template sync test with `docs/config.example.ini`, and the RTC round trip with a corrupt field and the old state version. `arduino-cli compile rlcd_panel` passes.

- [ ] Battery runtime line in the serial console command STATUS
  - Rank: [Total: 31, Use: 6, Setup: 7, Harmony: 8, Safety: 10, Effort: S]
  - Location: `rlcd_panel/serial_console.h`, `rlcd_panel/battery_monitor.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
  - Goal: Add the line `RUNTIME h=<N|--> samples=<N> cycles=<N|--> scan_saved_ms=<N>` to the `STATUS` reply after `POWER` and before `CRC32`. The values come from `BatteryHistory`, `BatteryRuntime` and `CycleTracker`, which the diagnostics view already uses. `h` is the remaining runtime in whole hours, or `--` when no estimate exists. The line reads the RTC copy and NVS only and does not change the state. Thus the user logs the battery state on the host without a look at the display.
  - Acceptance test: Host tests cover a valid runtime, too few samples, a flat slope, an unknown cycle count, the line position before `CRC32`, the new CRC-32 value, and an unchanged `BatteryHistory` after the command. `arduino-cli compile rlcd_panel` passes.

- [ ] Absolute humidity line in the sensor stats view
  - Rank: [Total: 30, Use: 7, Setup: 4, Harmony: 9, Safety: 10, Effort: S]
  - Location: `rlcd_panel/dashboard_view.h`, `rlcd_panel/climate_math.h`, `tools/preview_scenes.h`, `tests/test_firmware_logic.cpp`, `docs/display_preview_stats.png`
  - Goal: Add `DashboardView::formatAbsHumidity`. It gives `AH <N.N>G` with `ClimateMath::absoluteHumidityGm3` of the slot (1 decimal, g/m3). Show it at scale 1, right-aligned to x = 392, at `y + 54`, on the record line of each slot. An empty or stale slot or an invalid result gives `AH --`. The text is calculated on each render; nothing is stored. Make sure that the record text and the new text do not overlap at the widest record text. Thus the user compares the indoor and the outdoor air moisture without the ventilation advice.
  - Acceptance test: Host tests cover 20 C and 50 %RH (8.6 g/m3), 0 %RH, a NaN reading, a stale slot, an empty slot, the right edge at x = 392, and no overlap with the widest record text in Celsius and in Fahrenheit. `./scripts/generate-display-preview.sh` gives a stats preview with the new text.

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
