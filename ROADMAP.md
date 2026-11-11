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

- [ ] Deep sleep protection for an empty battery
  - Rank: [Total: 34, Use: 8, Setup: 7, Harmony: 9, Safety: 10, Effort: M]
  - Location: `rlcd_panel/battery_monitor.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`.
  - Goal: Prevent a deep discharge of the 18650 cell. Below 3.30 V on battery power, draw a full-screen warning `BATTERY EMPTY - CHARGE NOW` once. Then skip the BLE scan, the audio, the MicroSD card and Wi-Fi. Sleep with a 3600 s timer and the `KEY` wakeup. The reflective panel keeps the warning without power. Leave this mode at 3.50 V or more, or on USB power. Keep the latch in RTC memory.
  - Acceptance test: Host tests cover the entry at 3.29 V, no entry at 3.30 V, the exit hysteresis, USB power, an invalid reading, the skipped modules, the sleep time, and the RTC round trip of the latch. A preview scene shows the warning. On the board with a lab supply, the awake time per hour in this mode is below 1 s.

## Priority 2: Audio, Storage, and Configuration (Score 28–33)

- [ ] 24-hour minimum and maximum records in the stats view
  - Rank: [Total: 30, Use: 8, Setup: 5, Harmony: 8, Safety: 9, Effort: M]
  - Location: `rlcd_panel/sparkline.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/dashboard_view.h`, `tools/preview_scenes.h`.
  - Goal: Show the lowest and highest temperature and humidity of the last 24 h for each sensor in the `SENSOR STATS` view. Add a 24-sample hourly temperature history next to `HumidityHistory`, with the same hour gate and the same skip rules for invalid and stale readings. Read the humidity range from the existing history. Show `--` for fewer than 2 samples. Increase the RTC state version; the old format gives a clean start.
  - Acceptance test: Host tests cover the minimum and maximum over 24+ samples, negative temperatures, the encoding limits, skipped samples, fewer than 2 samples, the text bounds in the stats view, and the RTC round trip with corrupt fields. The regenerated `docs/display_preview_stats.png` shows the records without clipping.

- [ ] Configurable NTP sync interval from the MicroSD card
  - Rank: [Total: 29, Use: 4, Setup: 8, Harmony: 8, Safety: 9, Effort: S]
  - Location: `rlcd_panel/device_config.h`, `rlcd_panel/ntp_sync.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`.
  - Goal: Let the user set the time between two successful NTP syncs. Add the key `ntp_sync_interval_h` (default `24`, range `6` to `168`) to `config.ini`. Replace the fixed `NtpSchedule::SYNC_INTERVAL_MS` with this value. Keep the retry backoff after a failure unchanged. Store the value in `PersistentState` and increase the RTC state version.
  - Acceptance test: Host tests cover the default, the range edges `6` and `168`, out-of-range and malformed values, the schedule decision with 6 h and 168 h, the unchanged backoff, and the RTC round trip. The `FEATURES.md` key table lists the new key.

## Priority 3: Audio Expansions (Score 22–27)

- [ ] Chime selection for each alert in config.ini
  - Rank: [Total: 27, Use: 5, Setup: 6, Harmony: 7, Safety: 9, Effort: S]
  - Location: `rlcd_panel/device_config.h`, `rlcd_panel/audio_alert.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`.
  - Goal: Let the user switch off the chime of one alert and keep the others. Add the key `chime_alert_mask` (default `15`, range `0` to `15`) to `config.ini`. Bit 0 is `ALERT_FREEZER`, bit 1 is `ALERT_HUMIDITY`, bit 2 is `ALERT_NOISE_HIGH`, and bit 3 is `ALERT_SENSOR_LOST`. A cleared bit suppresses the sound only. The alert state is still tracked, so setting the bit again does not replay an old alert.
  - Acceptance test: Host tests cover the default, each single bit, `0`, out-of-range values, the combination with mute and quiet hours, no replay after the bit is set again, and the RTC round trip.

- [ ] Battery charge cycle counter in the diagnostics view
  - Rank: [Total: 26, Use: 4, Setup: 7, Harmony: 7, Safety: 8, Effort: M]
  - Location: `rlcd_panel/battery_monitor.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`.
  - Goal: Show the wear of the 18650 cell. Add the battery percentage drops of each discharge. Count one full cycle for each 100 % of discharge. Show `CYCLES: N.N` in the diagnostics view. Store the counter in NVS (`Preferences`), because RTC memory does not survive a cell removal. Write NVS only once at each charge event, so the flash wear stays low.
  - Acceptance test: Host tests cover partial discharges that add to one cycle, the charge detection, USB power, invalid readings, the NVS write count per charge event, and the text bounds in the diagnostics view. The regenerated `docs/display_preview_diagnostics.png` shows the line without clipping.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components and firmware modules does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
