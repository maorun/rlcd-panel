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

## Priority 1: High-Impact Core Features (Score 34–40)

### Key button display mode switcher

- **Rank:** Feature model. Total 34/40 (use 9, setup 8, harmony 9, safety 8). Effort S.
- **Area:** User Interface / Input.
- **Code:** `rlcd_panel/button_handler.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`.
- **Problem:** The physical user button `KEY` (GPIO 18, active low) has no software function in firmware. The 400x300 screen only shows one fixed dashboard view.
- **Goal:** Implement an active-low debounced button handler on GPIO 18. Each press cycles the display between:
  1. Main 4-tile climate dashboard.
  2. Detailed sensor statistics view (RSSI, packet counter, MAC address, raw voltages).
  3. Power and system diagnostics view (onboard battery curve, free heap, uptime).
- **Acceptance test:** Host unit test processes button press events and confirms state machine transitions across all three views.

## Priority 2: Power, Storage, and Visuals (Score 28–33)

### Deep sleep power management with periodic wakeup

- **Rank:** Feature model. Total 33/40 (use 9, setup 7, harmony 8, safety 9). Effort M.
- **Area:** Power Management.
- **Code:** `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`.
- **Problem:** The ESP32-S3 CPU and BLE radio run continuously at 240 MHz. An 18650 lithium battery empties in less than 24 hours. The reflective LCD needs zero power to maintain a static image.
- **Goal:** Implement light sleep or deep sleep between BLE scan cycles. Wake up every 60 seconds, scan for advertisements for 4 seconds, refresh the RLCD screen, and return to sleep.
- **Acceptance test:** State machine preserves registry and battery data across sleep cycles in RTC memory. Host test verifies timer calculations and state preservation logic.

### Low battery visual alert and critical warning banner

- **Rank:** Feature model. Total 32/40 (use 9, setup 7, harmony 8, safety 8). Effort S.
- **Area:** Power / UI.
- **Code:** `rlcd_panel/battery_monitor.h`, `rlcd_panel/dashboard_view.h`.
- **Problem:** The battery icon changes bar segments, but gives no prominent warning when the 18650 cell drops below 15% or 3.4 V. The board powers off unexpectedly during use.
- **Goal:** Detect low battery state (< 15% or < 3.40 V). Render an inverted warning banner across the screen header with flashing exclamation mark when critical. Add hysteresis to prevent oscillation around 15%.
- **Acceptance test:** Host unit test validates low-battery hysteresis thresholds (triggers at <= 15%, clears at >= 20%).

### 24-hour temperature trend sparkline mini-charts

- **Rank:** Feature model. Total 30/40 (use 8, setup 6, harmony 9, safety 7). Effort M.
- **Area:** Graphics / Framebuffer.
- **Code:** `rlcd_panel/sparkline.h`, `rlcd_panel/dashboard_view.h`.
- **Problem:** Tiles display only instantaneous temperature and humidity values. The user cannot see whether temperature is rising or falling over time.
- **Goal:** Store an hourly circular buffer of 24 temperature samples per sensor slot. Render a compact 1-bpp sparkline curve (width 80 px, height 16 px) at the bottom of each tile.
- **Acceptance test:** Host unit test records 24 consecutive values, validates min/max auto-scaling, and tests clipping within tile pixel boundaries.

### MicroSD offline climate telemetry logger

- **Rank:** Feature model. Total 29/40 (use 7, setup 7, harmony 8, safety 7). Effort M.
- **Area:** Storage / Logging.
- **Code:** `rlcd_panel/sd_logger.h`, `rlcd_panel/rlcd_panel.ino`.
- **Problem:** Historical sensor data disappears when the device restarts or loses power.
- **Goal:** Mount the onboard MicroSD card slot over 1-bit SD_MMC (GPIO 38, 21, 39). Append hourly climate records (timestamp, battery, SHTC3 T/RH, 3 SwitchBot slots) to `/sdcard/climate_YYYYMM.csv`.
- **Acceptance test:** Host unit test validates CSV formatting, header generation, and sensor serialization. Mock driver handles missing or removed card gracefully.

## Priority 3: Audio and Networking Expansions (Score 22–27)

### Audio chime alerts via ES8311 DAC and speaker

- **Rank:** Feature model. Total 27/40 (use 7, setup 6, harmony 8, safety 6). Effort M.
- **Area:** Audio / Alerts.
- **Code:** `rlcd_panel/audio_alert.h`, `rlcd_panel/board_pins.h`, `rlcd_panel/rlcd_panel.ino`.
- **Problem:** The device is silent. If a freezer sensor drops below a safe temperature or a room exceeds high humidity thresholds (> 70 %RH mold risk), the user must look at the display to notice.
- **Goal:** Drive the onboard ES8311 I2S DAC and power amplifier (GPIO 8, 9, 10, 16, 45, 46) to produce subtle audio alert beeps when configured climate thresholds are breached.
- **Acceptance test:** Host unit test validates PCM sample generation and threshold event triggers. Audio generation honors mute settings.

### Wi-Fi NTP time synchronization with offline fallback

- **Rank:** Feature model. Total 26/40 (use 6, setup 7, harmony 7, safety 6). Effort M.
- **Area:** Networking / RTC.
- **Code:** `rlcd_panel/ntp_sync.h`, `rlcd_panel/rlcd_panel.ino`.
- **Problem:** The PCF85063A real-time clock requires manual setting on initial power-up. If the backup coin cell runs out, clock time drifts.
- **Goal:** Provide optional background Wi-Fi connection to NTP pool servers once per day (or on startup). Synchronize the PCF85063A RTC hardware registers, then shut off the Wi-Fi radio immediately.
- **Acceptance test:** Host unit test verifies epoch timestamp conversion into PCF85063 BCD register format with timezone and daylight saving offset support.

### Ambient noise monitor via ES7210 dual microphone ADC

- **Rank:** Feature model. Total 23/40 (use 5, setup 5, harmony 7, safety 6). Effort L.
- **Area:** Sensors / Audio.
- **Code:** `rlcd_panel/es7210_mic.h`, `rlcd_panel/dashboard_view.h`.
- **Problem:** The onboard ES7210 ADC chip and dual microphone array are currently unused.
- **Goal:** Initialize the ES7210 over I2C (`0x40`) and I2S. Compute average RMS acoustic energy over 5-second sampling windows. Render an ambient noise level indicator (quiet / moderate / loud) on the diagnostics view.
- **Acceptance test:** Host unit test computes RMS amplitude on test sine waves and verifies categorization into noise bands.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components and firmware modules does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
