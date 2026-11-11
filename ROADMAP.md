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

- [ ] MicroSD log dump over USB serial
  - Rank: [Total: 35, Use: 9, Setup: 9, Harmony: 8, Safety: 9, Effort: M]
  - Location: new `rlcd_panel/serial_console.h`, `rlcd_panel/sd_logger.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, new `scripts/dump-logs.sh`.
  - Goal: Read the CSV logs without removing the MicroSD card. Add a line-based command parser without Arduino dependency (`LIST`, `DUMP <file>`, `INFO`). Accept commands only on USB power, while the board is awake. Reject file names that do not match `climate_v2_YYYYMM.csv`. Mount the card for one command and unmount it after the command. Send each file with a start line, the byte count, and a CRC-32 end line. Add `scripts/dump-logs.sh`, which sends the commands and writes the files into an ignored local directory.
  - Acceptance test: Host tests cover the parser with valid, unknown, too long and path-traversal commands, and the frame with the byte count and the CRC-32. On the board with USB power, `./scripts/dump-logs.sh` copies each monthly file, and the CRC-32 agrees with the file on the card. On battery power, the board ignores the commands and its awake time does not increase.

## Priority 2: Audio, Storage, and Configuration (Score 28–33)

- [ ] BTHome v2 sensor support
  - Rank: [Total: 30, Use: 8, Setup: 6, Harmony: 7, Safety: 9, Effort: M]
  - Location: new `rlcd_panel/bthome_ble.h`, `rlcd_panel/switchbot_ble.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`.
  - Goal: Show third-party thermo-hygrometers with the BTHome v2 protocol (Service Data `UUID 0xFCD2`) in the 3 sensor slots. Decode the unencrypted object IDs for temperature (`0x02`), humidity (`0x03`) and battery (`0x01`). Skip encrypted packets and unknown object IDs without a failure. Put BTHome and SwitchBot sensors into the same registry with the same 30 min stale rule.
  - Acceptance test: Host tests cover valid packets, negative temperatures, truncated packets, encrypted packets, unknown object IDs, and a registry with one BTHome sensor and two SwitchBot sensors. A board with a BTHome v2 sensor shows its values in one tile, and the existing SwitchBot tests stay green.

- [ ] MicroSD free space check and log retention
  - Rank: [Total: 29, Use: 7, Setup: 8, Harmony: 7, Safety: 7, Effort: S]
  - Location: `rlcd_panel/sd_logger.h`, `rlcd_panel/device_config.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`.
  - Goal: Prevent a full card from stopping the hourly log. Read the free space after each hourly write. Add the key `log_retention_months` (default `0` = keep all files, range `0` to `120`) to `config.ini`. Delete only `climate_v2_YYYYMM.csv` files that are older than the retention period. Never delete the file of the current month, and never delete other files. Show the free space in MB in the diagnostics view.
  - Acceptance test: Host tests cover the age calculation across a year change, the default `0`, the out-of-range values, and file names that do not match the pattern. On the board, a card with old test files keeps only the files inside the retention period, and the diagnostics view shows the free space.

## Priority 3: Audio Expansions (Score 22–27)

- [ ] Sensor dropout chime
  - Rank: [Total: 27, Use: 6, Setup: 6, Harmony: 7, Safety: 8, Effort: S]
  - Location: `rlcd_panel/audio_alert.h`, `rlcd_panel/switchbot_ble.h`, `rlcd_panel/rlcd_panel.ino`.
  - Goal: Tell the user when a known external sensor stops sending, for example because its battery is empty. Add the alert bit `ALERT_SENSOR_LOST`. Set it when a valid slot becomes stale. Play one low 440 Hz note when the alert switches on. Obey the mute flag and quiet hours. Clear the bit when each valid slot sends again.
  - Acceptance test: Host tests cover the change from fresh to stale, a slot that was never valid, the recovery, the mute and quiet hours suppression, and the RTC round trip of the new bit. On the board, the chime sounds once when a sensor gets its battery removed for more than 30 min.

- [ ] Loud noise alert chime
  - Rank: [Total: 24, Use: 5, Setup: 4, Harmony: 8, Safety: 7, Effort: M]
  - Location: `rlcd_panel/audio_alert.h`, `rlcd_panel/es7210_mic.h`, `rlcd_panel/device_config.h`, `rlcd_panel/rlcd_panel.ino`.
  - Goal: Tell the user when the hourly 5 s noise window is above a limit. Add the key `noise_alert_limit_db` (default `0` = off, range `40` to `100`) to `config.ini`. Use a release hysteresis of `-5 dB`. Use only the existing hourly noise window, so the microphones are not powered on more often. Do not play the chime while the noise window records.
  - Acceptance test: Host tests cover the limit edges, the hysteresis, the default `0`, an invalid noise reading, and the mute and quiet hours suppression. The awake time per hour on battery power does not increase.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components and firmware modules does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
