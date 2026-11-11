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

- [ ] Calibration offsets for each SwitchBot and BTHome slot
  - Rank: [Total: 35, Use: 8, Setup: 9, Harmony: 8, Safety: 10, Effort: M]
  - Location: `rlcd_panel/device_config.h`, `rlcd_panel/switchbot_ble.h`, `rlcd_panel/bthome_ble.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
  - Goal: Add the `config.ini` keys `slot1_temp_offset_c` to `slot3_temp_offset_c` (range `-5.0` to `5.0`) and `slot1_rh_offset_pct` to `slot3_rh_offset_pct` (range `-10.0` to `10.0`). Apply each offset to the decoded value of its registry slot before the alerts, the history and the CSV log use it. Store the six values in the config trailer and increase the state version.
  - Acceptance test: Host tests cover the defaults, the range edges, malformed values, the application to the correct slot only, the humidity clamp, the effect on the freezer and mold alerts, and the RTC round trip. A slot without an offset key keeps its raw value.

- [ ] BLE signal strength in the sensor stats view
  - Rank: [Total: 34, Use: 6, Setup: 10, Harmony: 8, Safety: 10, Effort: S]
  - Location: `rlcd_panel/switchbot_ble.h`, `rlcd_panel/bthome_ble.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`
  - Goal: Store the RSSI of the last valid advertisement for each registry slot (`int8_t`, dBm). Show `RSSI -NN DBM` in each sensor block of the stats view. Show `RSSI --` for a stale or empty slot. The user can then find a good position for the panel and for each sensor.
  - Acceptance test: Host tests cover the RSSI update on a valid packet, no update on a rejected packet, the text for -30 dBm and -100 dBm, the dashes for a stale slot, the line bounds in each block, and the RTC round trip. `docs/display_preview_stats.png` shows the line.

## Priority 2: Audio, Storage, and Configuration (Score 28–33)

- [ ] Serial console command to show the active configuration
  - Rank: [Total: 31, Use: 4, Setup: 9, Harmony: 8, Safety: 10, Effort: S]
  - Location: `rlcd_panel/serial_console.h`, `rlcd_panel/device_config.h`, `rlcd_panel/rlcd_panel.ino`, `scripts/dump-logs.sh`, `tests/test_firmware_logic.cpp`
  - Goal: Add the command `CONFIG` to the USB serial console. Send one `key=value` line for each setting in `DeviceConfig`, in the format of `config.ini`, then the source (`SD` or `DEFAULT`) and a `CRC32` line. The command reads the RTC copy only and does not mount the card. Send no Wi-Fi credentials.
  - Acceptance test: Host tests cover the parser for `CONFIG`, the rejection of `CONFIG` with arguments, the line format for each key, the CRC-32 of the output, and the parse of the output by `DeviceConfigParser` back to the same values. The command runs on USB power only.

- [ ] Sensor names from config.ini in the tiles
  - Rank: [Total: 31, Use: 7, Setup: 7, Harmony: 7, Safety: 10, Effort: M]
  - Location: `rlcd_panel/device_config.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`
  - Goal: Add the `config.ini` keys `onboard_name` and `slot1_name` to `slot3_name` (1 to 12 characters, uppercase letters, digits, space and `-`). Show the name in the tile title and in the stats view instead of the default title. Store the names in the config trailer and increase the state version. Reject a name with a character that the 5x7 font does not have.
  - Acceptance test: Host tests cover the default titles, a 12-character name, an empty value, a name that is too long, an unsupported character, the title bounds in each tile, and the RTC round trip. `docs/display_preview_dashboard.png` shows sample names.

- [ ] RTC drift measurement at each NTP sync
  - Rank: [Total: 29, Use: 3, Setup: 8, Harmony: 8, Safety: 10, Effort: S]
  - Location: `rlcd_panel/ntp_sync.h`, `rlcd_panel/pcf85063_rtc.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`
  - Goal: Read the `PCF85063A` time before each NTP write. Calculate the drift in seconds and the rate in ppm since the previous sync. Store the last drift and rate in RTC memory. Show `RTC DRIFT: +N S (+N PPM)` in the diagnostics view, or `RTC DRIFT: --` before the second sync.
  - Acceptance test: Host tests cover a positive and a negative drift, a drift of 0, the ppm rate over 24 h and 168 h, the dashes without a previous sync, the rejection of a failed sync, the line bounds, and the RTC round trip.

## Priority 3: Audio Expansions (Score 22–27)

- [ ] Daily climate summary file on the MicroSD card
  - Rank: [Total: 26, Use: 6, Setup: 4, Harmony: 8, Safety: 8, Effort: M]
  - Location: `rlcd_panel/sd_logger.h`, `rlcd_panel/sparkline.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
  - Goal: Write one row per day to `/sdcard/daily_v1_YYYY.csv` at the first hourly write after local midnight. The row holds the date, and the minimum, maximum and mean temperature and humidity for each sensor, plus the minimum and maximum noise level. Use the 24-hour histories in RTC memory as the source. Include the file in `LIST` and `DUMP`, and in the retention rule.
  - Acceptance test: Host tests cover the row format, empty fields for a sensor without samples, the midnight gate with CET and CEST, one row per day only, a failed write with a retry, and the console file name check. The hourly file stays unchanged.

- [ ] Charge complete chime on USB power
  - Rank: [Total: 25, Use: 5, Setup: 3, Harmony: 8, Safety: 9, Effort: S]
  - Location: `rlcd_panel/audio_alert.h`, `rlcd_panel/battery_monitor.h`, `rlcd_panel/device_config.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
  - Goal: Add the alert `ALERT_CHARGE_FULL` (bit 4 of `chime_alert_mask`, new range `0` to `31`). Play two falling notes once when the cell reaches 100 % on USB power. Release the alert below 95 %. Mute and quiet hours suppress the chime.
  - Acceptance test: Host tests cover the trigger at 100 %, no replay at 99 % to 100 % noise, the release at 94 %, no trigger on battery power, the mask bit, mute and quiet hours, and the new mask range in `config.ini` and in the RTC trailer.

- [ ] Hourly count of loud transient events
  - Rank: [Total: 22, Use: 4, Setup: 2, Harmony: 8, Safety: 8, Effort: M]
  - Location: `rlcd_panel/clap_detector.h`, `rlcd_panel/es7210_mic.h`, `rlcd_panel/sd_logger.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`
  - Goal: Count the transients (rise of 20 dB in under 5 ms) in the hourly 5 s noise window with the `ClapDetector` front end. Add the column `noise_events` to the CSV (format version 3, new file name `climate_v3_YYYYMM.csv`). Show `EVENTS: N` in the diagnostics view. Do not keep the microphones on longer than the existing window.
  - Acceptance test: Host tests cover 0, 1 and 10 synthetic transients, a steady tone with no events, the CSV header and row with 19 columns, an empty field for a failed window, and the line bounds. The old CSV files stay unchanged.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components and firmware modules does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
