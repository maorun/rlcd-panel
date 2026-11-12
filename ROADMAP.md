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

- [ ] High-humidity ventilation indicator on sensor tiles
  - Rank: [Total: 36, Use: 10, Setup: 7, Harmony: 9, Safety: 10, Effort: M]
  - Location: `rlcd_panel/dashboard_view.h`, `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`, `tests/test_firmware_logic.cpp`
  - Goal: Add the `config.ini` key `ventilate_limit_rh` (default `60`, range `50` to `80` %RH). Keep one latch for each tile (onboard and the three slots). Set the latch when the calibrated humidity of the tile is above `ventilate_limit_rh`. Release the latch when the humidity is 2 %RH or more below the limit. Between the two limits, keep the previous state. A stale slot, an empty slot and an invalid reading clear the latch and show no indicator. Draw a small inverted badge (for example the glyph `V` in a 9 x 9 px box) right of the humidity line of the tile. Keep the badge inside the tile and outside the sparkline box. The indicator is independent of the `VENTILATE` header banner and of `mold_alert_limit_rh`, and it has no chime. Store `ventilate_limit_rh` in the config trailer after `outdoor_slot`, store the four latch bits in `PersistentState`, and increase the state version. Thus the user sees which room needs fresh air.
  - Acceptance test: Host tests cover the parser for the default `60`, the edges `50` and `80`, the out-of-range values `49` and `81`, and a malformed value. Tests cover the trigger above the limit, no trigger at the limit, the hold between the limits, the release at 2 %RH below the limit, a stale slot, an empty slot, an invalid onboard reading, the calibrated humidity as input, the badge bounds inside each tile without clipping and outside the sparkline box, and the RTC round trip with a corrupt record and the old state version. `docs/display_preview_dashboard.png` shows the indicator in at least one tile.

- [ ] Low battery warning for external SwitchBot and BTHome sensors
  - Rank: [Total: 35, Use: 8, Setup: 8, Harmony: 9, Safety: 10, Effort: M]
  - Location: `rlcd_panel/switchbot_ble.h`, `rlcd_panel/bthome_ble.h`, `rlcd_panel/device_config.h`, `rlcd_panel/audio_alert.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`, `tests/test_firmware_logic.cpp`
  - Goal: Add the `config.ini` key `sensor_battery_limit_pct` (default `10`, range `0` to `50`, `0` is off). Set a latch for each slot when its reported `battery_pct` is at or below the limit. Release the latch at the limit plus 5 %. Show the inverted text `BAT LOW` in the tile of the slot instead of `BAT: N %`. Add the alert `ALERT_SENSOR_BATTERY` (bit 6 of `chime_alert_mask`, new range `0` to `127`, default `127`) with one chime when the first latch switches on. Mute and quiet hours suppress the chime. A packet without a battery field and a stale slot do not change the latch. Store the three latch bits in `PersistentState` and increase the state version. Thus the user replaces a sensor battery before the sensor stops to send data.
  - Acceptance test: Host tests cover the parser for the default, the edges `0` and `50`, out-of-range and malformed values, the trigger at the limit, the release with the hysteresis, a SwitchBot packet and a BTHome packet, a packet without a battery field, a stale slot, `sensor_battery_limit_pct=0`, the mask bit and the new mask range, mute and quiet hours, the tile text bounds, and the RTC round trip. `docs/display_preview_dashboard.png` shows `BAT LOW` in one scene.

## Priority 2: Audio, Storage, and Configuration (Score 28–33)

- [ ] Trend arrows for temperature and humidity in the climate tiles
  - Rank: [Total: 29, Use: 8, Setup: 2, Harmony: 9, Safety: 10, Effort: S]
  - Location: `rlcd_panel/sparkline.h`, `rlcd_panel/dashboard_view.h`, `tools/preview_scenes.h`, `tests/test_firmware_logic.cpp`
  - Goal: Add `TrendClassifier` to `sparkline.h`. Compare the newest hourly sample of `TemperatureHistory` and `HumidityHistory` with the sample 3 hours earlier. Give `RISING` for a change of 0.5 °C or more (3.0 %RH for humidity), `FALLING` for the same negative change, and `STEADY` for a smaller change. Give `UNKNOWN` when one of the two samples is invalid. Draw a small up or down triangle with `fillRect` right of the temperature line and right of the humidity line of each tile. Draw no mark for `STEADY` and `UNKNOWN`. The 5x7 font has no arrow glyph, so the triangle is 7 px high, the same as one text row. The trend uses the hourly samples, so it can lag by up to one hour. Use no new RTC memory and no new sensor read.
  - Acceptance test: Host tests cover a rise, a fall and a steady value for each quantity, the exact thresholds, an invalid newest sample, an invalid sample 3 hours earlier, fewer than 4 samples, a wrap of the circular buffer, and the triangle bounds inside the tile and outside the sparkline box. `docs/display_preview_dashboard.png` shows one rising and one falling arrow.

## Priority 3: Audio Expansions (Score 22–27)

- [ ] Early BLE scan stop when each known sensor has reported
  - Rank: [Total: 24, Use: 6, Setup: 2, Harmony: 8, Safety: 8, Effort: M]
  - Location: `rlcd_panel/switchbot_ble.h`, `rlcd_panel/bthome_ble.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/battery_monitor.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
  - Goal: Add `ScanCompletion` to `power_manager.h`. Stop the BLE scan before the end of `scan_duration_s` when each valid slot received a packet with temperature, humidity and battery in this scan, and at least 1 s of the scan is done. Run the full scan after a power-on, after a `KEY` wakeup, when a slot is empty or stale, and at the first wake cycle of each hour, so a new sensor can still fill an empty slot. Count the saved scan time in the battery runtime estimate. Thus the radio stays on for a shorter time on battery power, and the battery runtime increases. On USB power, keep the full scan.
  - Acceptance test: Host tests cover the stop with all slots reported, one missing slot, one slot with readings but no battery field, the 1 s minimum, an empty slot, a stale slot, the power-on, the `KEY` wakeup, the first wake cycle of the hour, the USB power case, and the runtime estimate with a short scan. The firmware compile check passes.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components and firmware modules does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
