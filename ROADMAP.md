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

- [ ] USB power detection via USB controller status
  - Rank: [Total: 36, Use: 8, Setup: 9, Harmony: 9, Safety: 10, Effort: S]
  - Location: `rlcd_panel/battery_monitor.h`, `tests/test_firmware_logic.cpp`.
  - Goal: Detect USB host power correctly when a battery is installed. In `BatteryMonitor::read()`, use `HWCDC::isPlugged()` alongside `voltage_v >= 4.25f` to determine `usb_powered`. On the host test platform where Arduino HWCDC is absent, allow injecting or mocking the plugged status. Set `battery.usb_powered` to true when plugged into a USB host, so `POWER: USB` displays reliably even when a battery clamps the voltage below 4.25 V.
  - Acceptance test: Host tests cover plugged and unplugged states across battery voltage levels (3.3 V to 4.2 V), verifying `usb_powered` is true whenever plugged. On the board connected to a Mac, the diagnostics view shows `POWER: USB` instead of `POWER: BATTERY`.

## Priority 2: Audio, Storage, and Configuration (Score 28–33)

- [ ] Configurable NTP sync interval from the MicroSD card
  - Rank: [Total: 29, Use: 4, Setup: 8, Harmony: 8, Safety: 9, Effort: S]
  - Location: `rlcd_panel/device_config.h`, `rlcd_panel/ntp_sync.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`.
  - Goal: Let the user set the time between two successful NTP syncs. Add the key `ntp_sync_interval_h` (default `24`, range `6` to `168`) to `config.ini`. Replace the fixed `NtpSchedule::SYNC_INTERVAL_MS` with this value. Keep the retry backoff after a failure unchanged. Store the value in `PersistentState` and increase the RTC state version.
  - Acceptance test: Host tests cover the default, the range edges `6` and `168`, out-of-range and malformed values, the schedule decision with 6 h and 168 h, the unchanged backoff, and the RTC round trip. The `FEATURES.md` key table lists the new key.

## Priority 3: Audio Expansions (Score 22–27)

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
