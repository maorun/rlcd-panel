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

- [ ] Low battery warning for external SwitchBot and BTHome sensors
  - Rank: [Total: 35, Use: 8, Setup: 8, Harmony: 9, Safety: 10, Effort: M]
  - Location: `rlcd_panel/switchbot_ble.h`, `rlcd_panel/bthome_ble.h`, `rlcd_panel/device_config.h`, `rlcd_panel/audio_alert.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`, `tests/test_firmware_logic.cpp`
  - Goal: Add the `config.ini` key `sensor_battery_limit_pct` (default `10`, range `0` to `50`, `0` is off). Set a latch for each slot when its reported `battery_pct` is at or below the limit. Release the latch at the limit plus 5 %. Show the inverted text `BAT LOW` in the tile of the slot instead of `BAT: N %`. Add the alert `ALERT_SENSOR_BATTERY` (bit 6 of `chime_alert_mask`, new range `0` to `127`, default `127`) with one chime when the first latch switches on. Mute and quiet hours suppress the chime. A packet without a battery field and a stale slot do not change the latch. Store the three latch bits in `PersistentState` and increase the state version. Thus the user replaces a sensor battery before the sensor stops to send data.
  - Acceptance test: Host tests cover the parser for the default, the edges `0` and `50`, out-of-range and malformed values, the trigger at the limit, the release with the hysteresis, a SwitchBot packet and a BTHome packet, a packet without a battery field, a stale slot, `sensor_battery_limit_pct=0`, the mask bit and the new mask range, mute and quiet hours, the tile text bounds, and the RTC round trip. `docs/display_preview_dashboard.png` shows `BAT LOW` in one scene.

## Priority 2: Audio, Storage, and Configuration (Score 28–33)


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
