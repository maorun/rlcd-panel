# Features

This file describes finished work for `rlcd-panel`. Open work is in `ROADMAP.md`.

## Overview

- **Sensors & BLE:** Onboard `SHTC3` plus 5 BLE slots (`SwitchBot`, `BTHome v2`, `ATC/pvvx`), early scan stop, dead-slot release, reception rate, per-slot calibration offsets & gain slopes (`rh_gain`), USB temperature offset with battery/USB separation.
- **Display & Views:** 400×300 `ST7305` dashboard (1–6 dynamic tiles), 2-page stats view (comfort, dew point, VPD, mold, feels-like, AH, RSSI, records), 24 h climate & noise charts, 7-day min/max chart (`WEEK_CHART`), dry air badge (`D`), stale data age badge (`STALE <N>H`), audio spectrum, diagnostics, `KEY` button navigation, frame-skip refresh, display invert.
- **Power & Battery:** Deep sleep with CRC-checked RTC state, wake & night intervals, USB detection, low-battery banner, empty-battery protection, runtime & cycle tracking, awake-time stats.
- **Audio & Alerts:** `ES8311` chimes (freezer, mold, heat, noise, dropout, charge full) with repeat & quiet hours, `BOOT` mute, `ES7210` noise & transient monitor (L10/L90), mic self-test & speaker loopback.
- **Logging & Time:** MicroSD hourly CSV (`climate_v5`), daily summaries (`daily_v4` with mold hours), event log (`events_v1`), retention & free-space check, Wi-Fi NTP sync, `PCF85063A` RTC drift tracking & frequency trim (`RTC TRIM`).
- **Configuration & Console:** MicroSD `config.ini` up to 8 KB with host validator, USB serial & BLE console (`STATUS`, `CONFIG`, `SET`, `LIST`, `DUMP`, `SCAN`, `CALIB`, `SALT`, `RTC TRIM`, `MUTE`, `SLOT CLEAR`, `BEEP`, `PATTERN`, `SCREEN`, `TIME`, `HISTORY`, `WEEK`, `EVENTS`, `VERSION`), `CONFIG_SET` and `SD_FAULT` event recording.
- **macOS Companion App:** Signed `.app` bundle & menu bar summary, live BLE telemetry, panel pinning, `config.ini` editor & backup, BLE scanner with repeated scan mode, sensor health table, calibration assistant with single & two-point salt check, diagnostics & clipboard report, log archive & charts (including 7-day week chart), remote screen, CSV export, open at login.

## Deep sleep power management with periodic wakeup

- **Code:** `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`.
- The board runs one wake cycle, then enters deep sleep: restore state, scan BLE for 4 s, read sensors, render, save state, sleep.
- The sleep timer starts the next scan `wake_interval_s` seconds after the start of the last scan (default 60 s, range 30 s to 900 s, set in `config.ini`). `PowerManager::powerConfig` passes the value to `PowerConfig::sleep_interval_s` on battery power. USB power keeps 10 s. The hourly log, the histories and the NTP schedule use RTC stamps, so the interval does not move them. Only a power-on and a `KEY` wakeup read the card, so a new value takes effect after one of them. The runtime estimate fits the sample stamps, so wake times off the hour grid do not distort it. The timer subtracts the awake time and clamps to 1 s to 1 h.
- The registry, battery reading, view mode, and boot count stay in RTC memory (`RTC_DATA_ATTR`). A magic number, a version, and a CRC-32 protect them. Corrupt or blank data gives a clean start with an immediate scan.
- The `KEY` button (`GPIO18`) wakes the board through `ext0`. A press cycles the display mode without a new scan. The board stays awake for 3 s after a render, so more presses redraw at once.
- Timestamps use the RTC-backed system clock, because `millis()` restarts after deep sleep.
- Host tests cover the timer maths, the serialization round trip with corruption rejection, and the wakeup cause and state machine logic.

## Low battery alert and warning banner

- **Code:** `rlcd_panel/battery_monitor.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`.
- `BatteryMonitor::updateLowBattery()` sets the alert at 15 % or less, or below 3.40 V. It clears the alert only at 20 % or more and 3.50 V or more.
- USB power, an invalid reading, or a missing cell switches the alert off.
- The `low_battery` flag stays in RTC memory (flag bit 2 of the battery byte), so the latch survives deep sleep. A reset of the RTC state starts with the alert off.
- An active alert replaces the header with a white bar, a double black frame, and the text `! LOW BATTERY - PLEASE CHARGE !` in all four views. The text is 370 px wide and centered. The banner does not flash, because the display sleeps between wake cycles.
- The 5x7 font has a new `!` glyph.
- `tools/render_display_preview.cpp` renders the banner with a third argument `low`.
- Host tests cover the hysteresis sequence, USB suppression, USB re-arming, banner geometry in all views, and the RTC round trip of the flag.

## 24-hour humidity trend sparklines

- **Code:** `rlcd_panel/sparkline.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`.
- `HumidityHistory` is a circular FIFO of 24 `int16_t` samples in 0.1 %RH steps (0 to 1000). The oldest sample drops out first. `encode()` rounds and clamps to 0 to 100 %RH.
- `ClimateHistory` holds one history for the onboard sensor and for each of the 3 SwitchBot slots. It records one humidity sample per hour. It skips NaN, negative, above 100 %RH, invalid and stale readings. The hour gate uses wrap-safe 32-bit maths.
- `Sparkline` scales the curve to the minimum and maximum of the history. A flat line or a single sample gets a span of 5.0 %RH (`MIN_SPAN` = 50). The span shifts to stay inside 0 to 100 %RH. The renderer clips every pixel to its box.
- Each dashboard tile shows a 72 x 16 px humidity curve right of the humidity line. The box stays inside the tile border and below the title divider. A tile with fewer than 2 samples shows no curve.
- The width is 72 px, not 80 px, so the curve fits next to the humidity text in a 190 px tile.
- The history stays in RTC memory (state version 7, since then 8 and 9, see "24-hour noise history in the stats view"). The old format, with temperature samples, gives a clean start.
- `tools/preview_scenes.h` renders a sample 24 hour humidity history.
- Host tests cover the encoding and clamping, the FIFO over 24+ samples, the sample gate, invalid and stale samples, the scaling edge cases at 0 and 100 %RH, the pixel bounds, the tile bounds, and the RTC round trip with corrupt fields.

## MicroSD offline climate telemetry logger

- **Code:** `rlcd_panel/sd_logger.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`.
- `SdLogCodec` has no Arduino dependency. It writes the CSV header, one row per record, and the file name `/sdcard/climate_v5_YYYYMM.csv`.
- A row has the timestamp, battery (percent, volt, USB), the onboard `SHTC3` (T, RH), and 5 SwitchBot slots (T, RH, battery). A missing, invalid, stale or NaN source gives empty fields, so each row has 27 columns (see "MicroSD noise logging", "Hourly count of loud transient events" and "L10 and L90 noise levels in the hourly window").
- Every codec function returns 0 for a null pointer, an empty buffer, a small buffer or an invalid time.
- `SdLogger` (Arduino only) calls `SD_MMC.setPins(38, 21, 39)` before `SD_MMC.begin("/sdcard", true)`. It checks the card type, appends one record, and unmounts. A new file gets the header first. A failed header write removes the new file.
- The board logs once per hour, also after a deep sleep wake. The log runs after the render, so a slow or missing card never delays the display.
- A failed mount or write gives no crash. The next try follows after 10 min, not after 1 h.
- `last_log_ms` and `has_logged` stay in RTC memory (state version 3). The old format gives a clean start. The gate uses wrap-safe 32-bit maths.
- Host tests cover the header, valid and invalid rows, the file name bounds, null and empty input, the schedule with wrap and retry, and the RTC round trip with a corrupt flag.

## Audio chime alerts via ES8311 DAC and speaker

- **Code:** `rlcd_panel/audio_alert.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`.
- `AlertEvaluator` has no hardware dependency. It raises the freezer alert below 0 C and the mold risk alert above 70 %RH. It ignores invalid, stale and NaN readings. The alerts release at 1 C and at 67 %RH, so a reading at the limit does not chime on every wake cycle.
- A chime sounds only when an alert switches on. An alert that stays active does not repeat.
- `ChimeSynth` makes 16 kHz, 16-bit mono sine PCM with a 5 ms fade at the start and end of each note. The default volume is 30 %. The freezer chime is three beeps at 880 Hz. The humidity chime is two rising notes.
- `AudioSequencer` plays the chime through a hardware layer. It switches the amplifier on only while samples play. It switches the amplifier off on every exit path, also after a codec failure, so deep sleep draws no speaker current.
- `Es8311Hal` (Arduino only) writes the ES8311 register sequence over I2C (`0x18`), starts I2S with MCLK (`GPIO8`, `9`, `10`, `16`, `45`), and sets `PA_CTRL` (`GPIO46`) with `pinMode()` before `digitalWrite()`.
- A press of the `BOOT` button in the 3 s grace window toggles the mute flag. Unmuting plays a short confirmation beep. Mute suppresses the sound only; the alert state is still tracked, so unmuting does not replay an old alert.
- `muted` and `alert_mask` stay in RTC memory (state version 4, one byte; the layout changed in state versions 11 and 12, see "Sensor dropout chime"). The old format gives a clean start. The audio byte layout of state version 23 is: bit 0 muted, bits 1 to 5 alert mask, bits 6 and 7 noise band. No reserved bit is left; the band value 3 is rejected, even with a valid CRC.
- One alert bit covers all sensors of its kind. A second sensor that enters the alert while another one stays in it does not chime. With no usable reading an active alert holds, so a sensor dropout does not clear it.
- The mute toggle works only in the 3 s grace window, which follows a `KEY` wakeup or a power-on. A timer wakeup skips the window. A reset of the RTC state (corrupt data or a format change) clears the mute flag.
- The amplifier pin is held low through deep sleep with `gpio_hold_en`.
- Host tests cover the thresholds with edge values, the hysteresis, stale and NaN input, the mute suppression, the PCM frequency, duration, volume and sample bounds, the amplifier on and off order, and the RTC round trip.

## Quiet hours for chime alerts

- **Code:** `rlcd_panel/audio_alert.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`.
- `QuietHoursWindow` has no hardware dependency. The default window is 22:00 to 07:00 local RTC time. The start minute is in the window and the end minute is not. A window can cross midnight. A window with start equal to end is empty, so quiet hours never start. A bound or a time out of range never suppresses.
- `isActive()` needs a valid RTC time. With an invalid time or a stopped oscillator the chime sounds.
- Quiet hours suppress the sound only, as the mute flag does. The firmware still stores the alert mask, so an alert that starts in quiet hours does not chime when the window ends. A new alert after the window sounds.
- The header shows a small inverted `Q` badge between the title and the clock in every view while quiet hours are active. The low battery banner replaces it. The preview `docs/display_preview_quiet_hours.png` shows the badge.
- The window is a build constant (`QUIET_HOURS` in `rlcd_panel.ino`). It adds nothing to the RTC state.
- Host tests cover the edges (21:59, 22:00, 06:59, 07:00), a same-day window, the empty window, out-of-range values, an invalid RTC time, the chime suppression with state latching, and the badge without clipping.

## Wi-Fi NTP time synchronization with offline fallback

- **Code:** `rlcd_panel/ntp_sync.h`, `rlcd_panel/wifi_config.example.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`.
- `NtpTime` has no Arduino dependency. It converts UTC epoch seconds (2000..2099) to calendar fields, applies the time zone, and encodes the `PCF85063A` BCD registers with the existing `Pcf85063Codec`. The default zone is CET/CEST. DST runs from 01:00 UTC on the last Sunday of March to 01:00 UTC on the last Sunday of October.
- `NtpSchedule` decides when to sync: once per `ntp_sync_interval_h` hours after a success (default 24 h, range 6 h to 168 h). After a failure the retry delay doubles from 15 min up to 6 h. A `KEY` wakeup, a low battery, a missing RTC, and missing credentials never start the radio. These skips leave the state unchanged.
- `NtpClient` (Arduino only) connects to Wi-Fi (8 s limit) and waits for SNTP (6 s limit) with `0/1/2.pool.ntp.org`. It then calls `WiFi.disconnect(true)` and `WiFi.mode(WIFI_OFF)` before it writes the local time to the `PCF85063A`. It restores the course of the system clock, because the firmware uses that clock for its timestamps. A failure leaves the RTC unchanged.
- The sync runs after the first render, so a slow network never delays the display. A successful sync redraws the header clock. The wake cycle takes up to 14 s longer while the sync runs.
- Credentials come from the optional file `rlcd_panel/wifi_config.h`. Git ignores it. Copy `wifi_config.example.h` to create it. Without it, or with an empty SSID, the radio stays off. The password must have 8 to 63 characters, be a 64-digit hex key, or be empty (open network).
- The sync state (`last_attempt_ms`, flags, failure count) stays in RTC memory (state version 5, 6 bytes). The old format gives a clean start. Inconsistent sync state with a valid CRC is rejected.
- The RTC holds local time and syncs once per day. After a DST change, the clock can be wrong by 1 h until the next sync.
- Host tests cover the epoch conversion (2000, 2024, 2100 leap rules, month, year and hour edges), both DST transitions at the second, BCD registers, the credential rules, the schedule with backoff, wrap-around and clock reset, the system clock restore, and the RTC round trip with corrupt fields.

## Ambient noise monitor via ES7210 dual microphone ADC

- **Code:** `rlcd_panel/es7210_mic.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`.
- `RmsAccumulator` has no hardware dependency. It sums the squares of 16-bit PCM samples in 64 bits and gives the RMS of a whole window. Full scale gives 32767 without overflow.
- `NoiseClassifier` sorts the RMS into `QUIET`, `MODERATE` and `LOUD`. The bands rise at 300 and 3000 and fall back below 200 and 2000, so a level at a limit does not flip the band. The levels are not calibrated sound pressure levels. The dB value on the screen is relative to full scale (dBFS).
- `NoiseSampler` reads a 5 s window of stereo samples at 16 kHz after a 100 ms settling time, and measures the RMS of both microphones (see "Microphone self-test in the diagnostics view"). A failed or stopped read gives no reading, never a partial one.
- The sampler powers down the ADC and closes I2S on every exit path. `Es7210Hal` (Arduino only) writes the ES7210 register sequence over I2C (`0x40`) and starts I2S receive with MCLK (`GPIO9`, `10`, `16`, `45`). The shutdown sequence turns off the ADCs, the microphone bias and the analog supply. A failed write repeats the sequence once. The register values come from the Espressif reference driver and are not verified on hardware.
- The diagnostics view shows `NOISE: <BAND> <dB> DB`, or `NOISE: --` without a reading. The line is at most 264 px wide.
- The board measures only when the diagnostics view is on screen and the wakeup is not a timer wakeup. It draws the view first, measures, then redraws. The measurement runs once per wake cycle, also after a failure. The microphones stay off in the other views, so they cost no battery.
- The band stays in RTC memory (bits 5 and 6 of the audio byte since state version 12; bits 4 and 5 in version 11; bits 3 and 4 before). The state version stayed 5 when the band came: an old state decodes as `QUIET`, and a band value of 3 is rejected.
- Host tests cover the RMS of silence, sine, DC and full scale, the band limits and hysteresis, the sampler power-down paths, the diagnostics line (all states, no clipping), and the RTC round trip.

## MicroSD noise logging with hourly measurement

- **Code:** `rlcd_panel/sd_logger.h`, `rlcd_panel/es7210_mic.h`, `rlcd_panel/rlcd_panel.ino`.
- Format version 2 (since replaced by version 3, see "Hourly count of loud transient events"): the header had 18 columns: the 15 climate columns plus `noise_rms`, `noise_db` and `noise_band`. The file name is `/sdcard/climate_v2_YYYYMM.csv`, so the new header never mixed with rows of the old 15-column files. The old files stay unchanged on the card.
- `noise_rms` is the 16-bit PCM RMS of the window. `noise_db` is the dB SPL value from the active `MicCalibration` (stored calibration plus the `config.ini` offset). `noise_band` is `QUIET`, `MODERATE` or `LOUD`. An invalid reading gives three empty fields.
- `SdLogCodec::noiseMeasureWanted()` is the gate. The wake cycle runs one 5 s `NoiseSampler` window only when a record is due, the RTC time is valid, and no low battery alert is active. This also applies after a timer wakeup. Each other timer wakeup keeps the microphones off.
- The window runs before the record is written. After a low battery skip or a failed window, the row is still written with empty noise fields, and the record is not repeated. A missing card skips the window (a card that mounts but rejects the write still costs one window per 10 min retry), so the 10 min retry costs no microphone power. KEY presses during the window are not seen. A due record in the diagnostics view redraws the view with the new level.
- `NoiseSampler` powers down the ADC and closes I2S on every exit path (bus failure, ADC failure, short read, zero window).
- Host tests cover the 18-column header, rows with valid and invalid noise data, the calibration offset, all band labels, the file name bounds, the gate (due, not due, invalid time, low battery, retry schedule), and the ADC power-down on each exit path.

## GitHub Actions CI for host tests and firmware compile

- **Code:** `.github/workflows/ci.yml`, `README.md`.
- One workflow runs on each pull request and on each push to `master`. It uses no secrets and has read-only `contents` permission. A new push to the same ref cancels the older run.
- Job `host-tests` builds `tests/test_firmware_logic.cpp` with `-std=c++17 -Wall -Wextra -Werror` and runs it. It also builds `tools/render_display_preview.cpp` and renders all scenes with `--all` into a temporary directory. The job creates no PNG files.
- Job `firmware-compile` installs `arduino-cli` with `arduino/setup-arduino-cli@v2`. It pins `arduino-cli` to version 1.5.1. It runs `arduino-cli compile --profile rlcd_panel_s3 rlcd_panel`. The profile in `rlcd_panel/sketch.yaml` installs `esp32:esp32@3.3.12` from its index URL.
- The job caches `~/.arduino15`. The cache key holds the core version and the hash of `sketch.yaml`.
- The build does not need `rlcd_panel/wifi_config.h`, because the file is optional and ignored by Git.
- All actions use a major version tag (`actions/checkout@v4`, `actions/cache@v4`, `arduino/setup-arduino-cli@v2`).
- `README.md` shows the CI status badge.

## Microphone calibration with stored noise floor and SPL offset

- **Code:** `rlcd_panel/es7210_mic.h`, `rlcd_panel/button_handler.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`.
- `MicCalibration` holds the SPL offset (`int16_t`, the dB SPL level that matches 0 dBFS) and the measured noise floor (RMS). The offset comes from the compile-time default `MIC_SPL_OFFSET_DB` (90). Measure it once against a reference sound level meter and override it at build time. The default is an estimate and is not verified on hardware.
- `splDb()` adds the offset to the dBFS level and clamps the result to 0 to 140 dB. The value is not A-weighted, so the `DBA` label is an approximation.
- `NoiseClassifier::limits()` derives the band limits from the noise floor: moderate at floor + 10 dB, loud at floor + 30 dB. Each band releases 3 dB lower (hysteresis). Without a calibration, the fixed limits stay in force.
- `MicCalibrationCodec` has no Arduino dependency. A record has 12 bytes: magic, version, flag, offset, floor, and CRC-32. A wrong size, magic, version, CRC, or field gives the default record.
- `MicCalibrationStore` (Arduino only) keeps the record in NVS (`Preferences`, namespace `rlcd`, key `miccal`). A flash erase or a corrupt record gives the default.
- `LongPressDetector` and `DisplayModeController::updateWithLongPress()` tell a short press from a long press. In the diagnostics view, a release before 2 s changes the mode. A hold of 2 s or more starts the calibration and keeps the mode. The other views change the mode at once. The press that woke the board never starts a calibration.
- The calibration waits for the release of `KEY`, then measures 10 s in a quiet room. It accepts a floor of 1 to 1000 RMS. A failed window, a louder room, or a failed save keeps the old calibration. `applyCalibrationWindow()` holds this decision. A success uses the build default offset, resets the band latch, and stores the record.
- The calibration takes about 11 s. `KEY` presses during it are not seen. A pending press keeps the 3 s grace window open until the release or the 2 s limit.
- The diagnostics view shows `NOISE: <BAND> <dB> DBA` with a calibration, or `NOISE: <BAND> <dB> DBFS` without. The widest line is 288 px. The new line `MIC CAL: OK`, `DEFAULT`, `MEASURING`, or `FAILED` is at y = 244.
- Host tests cover the conversion and the clamp, the derived limits with hysteresis, the long press and short press edges (2 s limit, wrap-around), the serialization with every flipped bit, the apply decision, and the line widths.

## Microphone self-test in the diagnostics view

- **Code:** `rlcd_panel/es7210_mic.h`, `rlcd_panel/dashboard_view.h`, `tools/preview_scenes.h`, `tests/test_firmware_logic.cpp`.
- `NoiseSampler` splits the interleaved stereo samples of the existing 5 s window into `MIC1` and `MIC2` (`StereoAnalyzer`). `MicChannelStats` keeps the RMS, the DC mean, the minimum and maximum, and the number of full scale samples of each channel. The microphones need no extra window.
- Each channel gets one status (`MicStatus`): `SILENT` (all samples are zero), `STUCK` (peak to peak below 2), `CLIP` (at least 8 samples at full scale; one glitch does not count), `DC` (mean offset above 4096, which is 12.5 % of full scale), or else `OK`. The checks run in this order.
- The imbalance is the level difference of the two RMS values in whole dB. It is set only when both channels are `OK`, and it is reported above 10 dB (`MicSelfTest::imbalanced()`). A failed channel has no usable level to compare.
- The noise level uses the usable channels only. With one failed channel the level is that of the working microphone, so a dead microphone does not lower the reading by 3 dB. With two good channels it is the combined RMS.
- When both channels fail, `NoiseReading::valid` is false and the band stays unset. The reading keeps `mic` for the view. The diagnostics view shows `NOISE: --`, and the hourly CSV record gets empty noise fields. The latch of the band in RTC memory does not change.
- The diagnostics view shows `MIC1: <STATUS> MIC2: <STATUS>` at y = 224, for example `MIC1: OK MIC2: SILENT`. An imbalance adds ` IMB <n> DB`. Without a finished window the line shows `MIC: --`. The widest line (`MIC1: OK MIC2: OK IMB 255 DB`) is 28 glyphs, 336 px, and ends inside the 400 px width.
- Host tests cover synthetic stereo buffers (two good channels, a zero channel, a constant channel, a positive and a negative DC offset, a clipped channel, a single glitch, both channels failed), the limit edges, the imbalance at 6, 10 and 20 dB in both directions, odd chunk sizes, the sampler with the fake ADC, the suppression of the band when both channels fail, and the line width and the margins in the view. Mutation checks confirm that the tests fail without each limit and without the line.
- The limits and the assignment of `MIC1` to the left I2S slot and `MIC2` to the right slot are estimates and are not verified on hardware.

## Configurable thresholds and settings from the MicroSD card

- **Code:** `rlcd_panel/device_config.h`, `rlcd_panel/sd_logger.h`, `rlcd_panel/audio_alert.h`, `rlcd_panel/es7210_mic.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`.
- The firmware reads an optional `/sdcard/config.ini` with `key=value` lines on power-on and on a `KEY` wakeup. A timer wakeup does not mount the card. It uses the copy in RTC memory.
- `DeviceConfigParser` has no Arduino dependency. A line that starts with `#` or `;` is a comment. Spaces, tabs, CRLF line ends, and a UTF-8 byte order mark are accepted. Key names are case sensitive. The parser ignores unknown keys.
- A missing, malformed, or out-of-range value keeps the default. The last valid line of a key wins. An invalid later line does not replace an earlier valid value.
- The parser rejects a file above 8192 bytes as a whole. The card never supplies Wi-Fi credentials. Those keys are unknown keys.
- Keys, defaults, and ranges:

  | Key | Default | Range | Used by |
  | --- | --- | --- | --- |
  | `temp_offset_c` | `-1.9` | `-10.0` to `10.0` | `SHTC3` decoder |
  | `rh_offset_pct` | `0.0` | `-10.0` to `10.0` | `SHTC3` decoder, result clamped to 0 to 100 %RH |
  | `rh_gain`, `slot1_rh_gain` to `slot5_rh_gain` | `1.0` | `0.90` to `1.10` (steps of 0.002) | humidity = raw * gain + offset, clamped to 0 to 100 %RH |
  | `freeze_alert_limit_c` | `0.0` | `-20.0` to `15.0` | freezer alert trigger |
  | `mold_alert_limit_rh` | `70` | `40` to `95` | humidity alert trigger |
  | `chime_volume_pct` | `30` | `0` to `100` | chimes and the unmute beep |
  | `quiet_hours_start_hour` | `22` | `0` to `23` | quiet hours start |
  | `quiet_hours_end_hour` | `7` | `0` to `23` | quiet hours end |
  | `mic_spl_offset_db` | `0.0` | `-20.0` to `20.0` | added to the microphone SPL offset |
  | `log_retention_months` | `0` | `0` to `120` | delete log files older than this; `0` keeps all |
| `noise_alert_limit_db` | `0` | `0` (off) or `40` to `100` | loud noise alert trigger |
| `chime_alert_mask` | `255` | `0` to `255` | chime per alert (bit 0 freezer, 1 humidity, 2 noise, 3 sensor lost, 4 charge full, 5 ventilate, 6 sensor battery, 7 heat) |
| `sensor_battery_limit_pct` | `10` | `0` to `50` | an external sensor at or below this battery level shows `BAT LOW`; `0` is off |
  | `ntp_sync_interval_h` | `24` | `6` to `168` | hours between two successful NTP syncs |
  | `wake_interval_s` | `60` | `30` to `900` | seconds between two scans on battery power; USB power keeps `10` |
  | `sensor_stale_min` | `30` | `5` to `240` | minutes without a packet until a sensor is stale |
  | `display_invert` | `0` | `0` or `1` | `1` shows light text on a dark background |
  | `slot_release_h` | `0` | `0` to `168` | hours without a packet until a slot without a fixed MAC is released; `0` is off |
  | `temp_unit` | `C` | `C` or `F` | display unit of the temperature on the screen and in `STATUS`; everything else stays in Celsius |
  | `chime_repeat_h` | `0` | `0` to `24` | hours until the chime of an alert that stays active sounds again; `0` is off |
  | `night_wake_interval_s` | `0` | `0`, or `60` to `1800` | seconds between two scans on battery power in the quiet hours; `0` keeps `wake_interval_s` |
  | `climate_alert_tiles` | `15` | `0` to `15` | tiles of the freezer and mold alerts (bit 0 onboard, bits 1 to 3 slots 1 to 3); `0` switches both alerts off |
  | `outdoor_slot` | `0` | `0` (off) to `3` | registry slot of the outdoor sensor for the ventilation advice |

- Equal start and end hours switch the quiet hours off. A lone CR is no line end: use LF or CRLF. The SPL offset has whole dB steps, so a fraction below 0.5 dB has no effect.
- A card that does not mount keeps the previous settings on a `KEY` wakeup. A card without a usable file gives the defaults.
- The release limits keep a fixed hysteresis: `+1.0 C` for the freezer alert and `-3 %RH` for the humidity alert. The extra SPL offset applies in memory only. A new microphone calibration never stores it.
- The accepted values are part of `PersistentState` in RTC memory (state version 14). A corrupt or out-of-range record gives a clean start.
- The diagnostics view shows `CONFIG: SD` or `CONFIG: DEFAULT`.
- Host tests cover valid values, comments, spaces, CRLF, missing keys, out-of-range and malformed values, duplicate keys, a file above 8 KB, the wiring into each consumer, and the RTC round trip with corrupt records.

## Speaker to microphone loopback test

- **Code:** `rlcd_panel/audio_loopback.h`, `rlcd_panel/button_handler.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`.
- A `BOOT` long press (2 s) in the diagnostics view starts the test. A short press still toggles the mute flag; in this view the toggle waits for the release, so a long press never changes the mute flag. `BootButtonController` holds this logic. The press that woke the board starts no test.
- The test plays a 1 kHz tone for 500 ms at the configured chime volume (`ES8311`, amplifier) and records it at the same time over the shared I2S bus (`ES7210`, 16 kHz, stereo). The first 100 ms of the recording stay out of the analysis (ADC settling).
- `GoertzelFilter` measures the tone. `LoopbackDetector` passes when the stronger channel holds the tone at or above -60 dBFS and the tone makes at least 50 % of the window power. The power share rejects loud noise and other frequencies. The limits are estimates and are not verified on hardware. The limit does not follow the chime volume: a very low volume (below about 5 %) can fail the test on a working path. The DC offset of the microphone is removed from the power share. The reported level is the mean over the 400 ms analysis window, so the playback latency lowers it a little.
- The diagnostics view shows `AUDIO LOOP: PASS <dB>` (tone level in dBFS) or `AUDIO LOOP: FAIL` at y = 260. A failed bus, codec or transfer also gives `FAIL`. `AUDIO LOOP: SKIP MUTED`, `SKIP QUIET` and `SKIP VOLUME` show a skipped test. The result lives for the wake cycle only.
- The test is skipped while muted, in active quiet hours, and with chime volume 0. A skipped test leaves the hardware untouched.
- One silent chunk follows the tone, then the drain wait, so no tone stays in the DMA buffers when the amplifier stops. The amplifier is switched on only after both codecs are configured. On every exit path the order is: amplifier off, `ES8311` shutdown, `ES7210` shutdown, I2S bus end.
- The test takes about 1 s; `KEY` presses during it are not seen. The preview of the diagnostics view shows a passing result.
- Host tests cover the detector with 1 kHz, 2 kHz, 500 Hz and 1.1 kHz sines, silence, white noise and a tone in noise, the level limits and the dB text, the power-down order on success and on each failure (bus, codec, ADC, write, short read, dead path), the skip rules, the tone volume, the `BOOT` press logic, and the diagnostics line bounds.

## 24-hour noise history in the stats view

- **Code:** `rlcd_panel/sparkline.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`.
- `NoiseHistory` is a circular FIFO of 24 hourly noise levels. A sample is one byte in dB (0 to 140). The value `0xFF` (`INVALID`) marks an hour without a valid reading. The oldest sample drops out first. `encode()` clamps to 0 to 140 dB.
- The wake cycle that writes the hourly MicroSD record adds one sample, after the record is written. The level is the dB SPL value of the active calibration. A failed noise window adds an invalid hour. A skipped window (low battery alert, missing card) adds nothing.
- `Sparkline::renderNoise` scales the curve to the minimum and maximum of the valid samples. A flat curve gets a span of 10 dB (`NOISE_MIN_SPAN`). An invalid hour leaves a gap in the curve. The renderer clips every pixel to its box.
- The sensor stats view shows `NOISE <min>-<max> DB` and a 170 x 20 px curve below the third sensor block. Without a valid hour, the view shows neither the line nor the curve.
- The history stays in RTC memory (26 bytes). The state version is 8. The old format gives a clean start. A ring with a count or head out of range, or a sample above 140 dB that is not `0xFF`, gives a clean start.
- `tools/preview_scenes.h` renders a sample 24 hour noise history with two invalid hours. `docs/display_preview_stats.png` shows it.
- Host tests cover the FIFO over 24+ samples, invalid samples, the range, the clamping, the pixel bounds and gaps, the stats view, the RTC round trip with corrupt fields, the old state version, and the preview scene.

## Audio frequency spectrum visualizer

- **Code:** `rlcd_panel/audio_spectrum.h`, `rlcd_panel/button_handler.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`.
- `DisplayMode::AUDIO_SPECTRUM` (value 3) follows `SYSTEM_DIAGNOSTICS`, and `DISPLAY_MODE_COUNT` is 4. The RTC state check in `power_manager.h` uses `DISPLAY_MODE_COUNT`, so it rejects a stored view mode above 3 and gives a clean start. The file needs no edit.
- `SpectrumAnalyzer` mixes the interleaved stereo samples to mono. It cuts them into frames of 512 samples (16 kHz), applies a Hann window and runs a radix-2 FFT. It groups the bins into 32 logarithmic bands from 100 Hz to 8 kHz. A band that holds bins takes the highest bin magnitude. A band that holds no bin center uses a linear interpolation at the band center. The two methods give slightly different levels, so the bars of the narrow low bands read a little lower than the bars of the wide high bands for the same noise.
- A bar is the mean band level over the capture. A peak is the highest frame level of the band. The level runs from 0 (at or below -80 dBFS) to 100 (full scale). Silence gives all bars and peaks at 0.
- `SpectrumCapture::measure` records a 1 s window after 100 ms of ADC settling. It uses the same hardware layer as `NoiseSampler`. It powers down the ADC and ends the bus on every exit path. A failed bus, a failed ADC or a short read gives an invalid reading. The analyzer holds about 7 KB of buffers and lives in static memory.
- The capture runs on entry to the view and not on timer wakeups, so the microphones stay off there. The result is not kept in RTC memory, so a timer wakeup in this view shows `NO DATA`.
- `DisplayModeController::updateWithLongPress()` returns `ButtonEvent::RECAPTURE` for a short `KEY` press in the spectrum view. The mode stays and the view captures again. A hold of 2 s changes the mode. The press that wakes the board changes the mode and starts no extra capture. A capture takes about 1.1 s; `KEY` presses during it are not seen.
- The view draws 32 bars of 10 px (pitch 12 px, 2 px per level step, base line at y = 240) with a 2 px peak marker above each bar and the labels `100`, `1K` and `8K` at their log positions. All pixels stay inside the 400 x 300 framebuffer.
- `tools/preview_scenes.h` renders a fixed sample spectrum (fan hum near 120 Hz with harmonics). `docs/display_preview_spectrum.png` shows it.
- The microphone gain, the dBFS scale and the band layout are estimates and are not verified on hardware.
- Host tests cover the band edges, the FFT, sines at 250 Hz, 1 kHz and 4 kHz with band centers, silence, the level scale, the capture lifecycle on each failure path, the view bounds, the `KEY` handling, the mode cycle with 4 modes and the rejection of a stored mode above 3.

## Display mode switching with the KEY button only

- **Code:** `rlcd_panel/rlcd_panel.ino`, `rlcd_panel/button_handler.h`, `rlcd_panel/clap_detector.h`.
- The display mode changes only when the user presses the `KEY` button: a `KEY` wakeup (`g_mode_controller.next()`) or a press in the grace window (`ButtonEvent::MODE_CHANGED`). A sound never changes the mode.
- Acoustic double-clap mode switching is removed. `ClapListener`, `ClapListenMode` and `PowerManager::clapListenMode` no longer exist, so the microphones stay off in the awake window, except for the noise window, the spectrum capture and the calibration.
- `ClapDetector` and `TransientCounter` stay: the 5 s `NoiseSampler` window uses them for the `noise_events` CSV column.
- On USB power the board stays awake while the serial console or the BLE console runs (`PowerManager::continuousListenAllowed`); the 3 s grace window applies as before.
- A host test (`testModeChangesOnlyOnKeyButton`) checks that the sketch has no clap listener, that `next()` runs only in the `KEY` wakeup branch, and that the mode stays without a press.

## Battery runtime estimator in the diagnostics view

- **Code:** `rlcd_panel/battery_monitor.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/st7305_framebuffer.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`.
- The diagnostics view shows `RUNTIME: ~NN H` below 48 h and `RUNTIME: ~N D` from 48 h (at most 99 D). `RUNTIME: --` shows on USB power, without a battery, with fewer than 6 samples, after a charge, and with a flat or rising curve.
- `BatteryHistory` keeps up to 24 battery voltage samples (millivolts) with clock stamps. One sample per hour is added in the wake cycle after the hour gate opens. A NaN or implausible voltage (below 2.5 V or above 4.3 V) is rejected.
- The discharge rate is the least-squares slope of voltage over elapsed hours. The stamps keep the slope correct when the wake interval moves the samples off the exact hour grid. The runtime is the voltage above `BatteryMonitor::V_EMPTY` (3.20 V) divided by the slope.
- A voltage 0.10 V or more above the lowest stored sample clears the history, because this indicates a charge. A slow charge in small steps counts too. USB power clears the history and adds no sample. A load sag followed by a 0.10 V recovery also clears it; the estimate restarts after 6 samples.
- The straight line ignores the flat plateau and the knee of a Li-ion cell near empty. The estimate reads long on the plateau. The accuracy on the board (±25 % after one full discharge) is not verified yet.
- The history stays in RTC memory (state version 9, 151 bytes). The old format gives a clean start. A ring with a count or head out of range, a sample outside 2.5 V to 4.3 V, stamps that do not increase, or a sampled flag above 1 gives a clean start.
- The 5x7 font has a new `~` glyph.
- `tools/preview_scenes.h` has a new scene. `docs/display_preview_runtime.png` shows `RUNTIME: ~3 D`.
- Host tests cover a linear discharge, the day format, a flat and a rising curve, the charge step with 0.09 V and 0.10 V, a slow charge, USB power, fewer than 6 samples, NaN and invalid readings, irregular sample spacing with a clock wrap, the RTC round trip with corrupt fields and stamps, the old state version, the `~` glyph, the diagnostics line without clipping, and the preview scene.

## MicroSD log dump over USB serial

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/sd_logger.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `scripts/dump-logs.sh`.
- The board reads the monthly CSV logs over USB serial (115200 baud), so the user does not remove the MicroSD card. The commands are `LIST`, `INFO` and `DUMP climate_v5_YYYYMM.csv` (the old v4, v3 and v2 names also work). The parser (`SerialConsole::parse`) has no Arduino dependency. It accepts uppercase commands with single spaces and lines up to 40 bytes. `SerialLineAssembler` drops an overlong line whole, so its tail never runs as a command.
- `SdLogCodec::isLogFileName` accepts only a bare name `climate_v5_YYYYMM.csv` or the old `climate_v4_YYYYMM.csv`, `climate_v3_YYYYMM.csv` or `climate_v2_YYYYMM.csv` with a year from 2000 to 2099 and a month from 01 to 12. A path separator, `..`, `config.ini` or an old v1 name gives `ERR BAD_NAME`. Unknown commands give `ERR UNKNOWN`, long lines `ERR TOO_LONG`.
- `PowerManager::serialConsoleAllowed` is true on USB power only. On battery, or with an invalid battery reading, the board does not read the port and does not stay awake for it, so the awake time does not increase. The console has its own `UsbPowerWatch` (every 5 s, two low samples end it) and keeps the board awake: on USB power the board stays awake until the next scan is due. The first command can wait for the next wake cycle; press `KEY` to wake the board.
- `SerialConsoleRunner` mounts the card for one command and unmounts it after the command, on each path. A valid command runs while the board is awake; the I2S bus and the card share the awake window.
- Reply frames: `LIST` gives `FILE <name> <bytes>` per file (sorted by name, at most 48 files) and `END LIST <count>`. `INFO` gives `INFO total=<bytes> used=<bytes> files=<count>`. `DUMP` gives `BEGIN <name> <bytes>`, exactly that many raw bytes, and `CRC32 <8 hex digits>` (IEEE 802.3, the same value as `zlib.crc32`). Errors are `ERR NO_CARD`, `ERR NOT_FOUND` and `ERR READ_FAILED`.
- A read error after `BEGIN` pads the payload with zero bytes to the announced length and sends `ERR READ_FAILED` in place of the CRC line. A short serial write ends the `DUMP` at once, and the host sees no CRC line. The setup sets a TX timeout of 1 s on the USB port.
- `scripts/dump-logs.sh [port]` finds the port, sends `LIST` and one `DUMP` per file, checks the byte count and the CRC-32, and writes the files to the ignored directory `sd_logs/` (mode `0700`). It ignores boot log lines, rejects sizes above 16 MiB, drains the port after an error, and exits with 1 when a file fails.
- The first read after a long deep sleep waits for the board to wake. The throughput of the USB CDC port and a dump of a large file on hardware are not measured yet.
- Host tests cover the CRC-32 check value, the file name rules, valid, unknown, too long and path-traversal commands, the line assembler, the frame with byte count and CRC-32 over short reads, an empty file, a missing file, a read error, a missing card, short serial writes at every frame position, the sorted `LIST`, `INFO`, the mount count and the USB gate.

## MicroSD free space check and log retention

- **Code:** `rlcd_panel/sd_logger.h`, `rlcd_panel/device_config.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`.
- The diagnostics view shows `SD FREE: NNN MB` at y = 144. It shows `SD FREE: --` while the free space is unknown, for example when the card does not mount. The value is capped at 9999999 MB, so the line stays inside the screen.
- The free space comes from `SD_MMC.totalBytes()` and `SD_MMC.usedBytes()` while the card is mounted: after each hourly write, and when `config.ini` loads. The value lives for one wake cycle only. A timer wakeup without a write does not mount the card and shows `--`.
- The new key `log_retention_months` (default `0` = keep all files, range `0` to `120`) is part of `config.ini` and of the RTC state (state version 12, the old format gives a clean start). A value outside the range is rejected and the default stays.
- After each hourly write, also after a failed write, the logger deletes the files `climate_v5_YYYYMM.csv`, `climate_v4_YYYYMM.csv`, `climate_v3_YYYYMM.csv` and `climate_v2_YYYYMM.csv` that are more than `log_retention_months` months older than the current RTC month (`SdLogCodec::shouldDeleteLog`). The age counts whole calendar months, also across a year change. Retention `3` in January 2026 keeps October 2025 and deletes September 2025.
- The file of the current month, files from a future month, `config.ini`, the old v1 files and every other name stay. An invalid RTC time, a set oscillator-stop flag or a year before 2025 deletes nothing (`SdLogCodec::MIN_TRUSTED_YEAR`). A wrong clock that still reads a later valid year deletes by that clock; the NTP sync keeps the clock right.
- One pass deletes at most 24 files (`SdLogCodec::MAX_PRUNE_PER_PASS`); the next hourly pass deletes the rest. The logger collects the names first and deletes after the directory closes.
- A full card still fails the write of the record. The prune runs after that failed write, so the retry after 10 min finds free space. A card that is full of files inside the retention period needs manual cleanup; the diagnostics view then shows `SD FREE: 0 MB` after a successful mount.
- `tools/preview_scenes.h` shows `SD FREE: 7421 MB` in `docs/display_preview_diagnostics.png`.
- Host tests cover the month age across a year change, retention `0`, `1`, `3` and `120`, out-of-range values, the current and a future month, names that do not match the pattern, the `config.ini` key with valid, malformed and out-of-range values, the RTC round trip, the free-space maths, and the diagnostics line (unmounted, normal, zero and widest value without clipping). The directory walk and the free-space read on the real card are not tested on hardware yet.

## BTHome v2 sensor support

- `BtHomeBleParser` (`rlcd_panel/bthome_ble.h`) decodes BTHome v2 Service Data (`UUID 0xFCD2`) from third-party thermo-hygrometers. The sensor MAC address comes from the BLE address, because the packet carries none.
- Supported objects: battery (`0x01`, 1 %), temperature (`0x02`, signed 0.01 °C), humidity (`0x03`, 0.01 %, and `0x2E`, 1 %). The parser steps over other objects of known length.
- The parser rejects encrypted packets, other BTHome versions, and packets without temperature or humidity. An unknown object ID ends the parse, and the values decoded before it stay valid.
- BTHome and SwitchBot sensors share the 3 slots of `SwitchBotRegistry` and the 30 min stale rule. A packet without battery keeps the last battery value.
- The parser rejects temperatures outside -50 to 100 °C. The `packet_count` counts each advertisement, also repeats with the same packet ID, as for SwitchBot.
- The dashboard titles the three sensor tiles `SENSOR 1` to `SENSOR 3`.
- The registry assigns slots in first-seen order (FIFO). The first three distinct MAC addresses take the slots, and a fourth sensor gets no slot. The registry does not filter by sensor type. A SwitchBot or BTHome sender in range can take a slot.
- Risk: other BTHome or SwitchBot senders near the panel can fill the 3 slots before your own sensors appear. Pair the panel only in a place with few foreign senders. There is no config filter for MAC addresses yet.
- The firmware reads only the first Service Data entry of an advertisement. A BTHome entry behind another Service Data UUID is missed.
- A sensor with a rotating random BLE address changes its MAC address. Each new address takes a free slot, and the sensor can use up the 3 slots over time.

## Loud noise alert chime

- The new key `noise_alert_limit_db` (default `0` = off, range `40` to `100` dB SPL) is part of `config.ini`. A value from `1` to `39`, above `100`, or malformed is rejected, and the previous value stays.
- `NoiseAlertEvaluator` (`rlcd_panel/audio_alert.h`) sets the alert bit `ALERT_NOISE_HIGH` when the SPL of the hourly 5 s noise window is above the limit. The alert releases when the level is `5 dB` or more below the limit. Between the two values the previous state holds.
- A limit of `0`, or a limit outside the range, switches the alert off and clears the bit. A failed window (`NoiseReading::valid` is false) holds the latch, so it neither switches the alert on nor off and gives no second chime.
- The wake cycle uses only the existing hourly window of `logToSdCard()`. The microphones are not powered on more often, so the awake time per hour on battery power does not increase. The check runs after the window ended and before the SD write. The chime never plays while the microphones record. A skipped window (low battery alert, no card) leaves the latch as it is until the next window.
- The chime has its own pattern: two short 988 Hz beeps, then a 660 Hz note. It sounds once, when the bit switches on. Mute and quiet hours suppress the sound only. The mask is stored, so unmuting or the end of the quiet hours never replays the alert.
- `AlertEvaluator` now sets the climate bits only. `AlertEvaluator::withClimate()` keeps the noise bit of the last window, so the climate check does not clear it.
- The alert mask uses 3 bits in RTC memory (bits 1 to 3 of the audio byte). The noise band moves to bits 4 and 5 (see "Sensor dropout chime" for the later layout). The limit adds one byte to the config trailer. The state version is 11. The old format gives a clean start.
- Host tests cover the edges (`40`, `100`, `39`, `101`), the release at `-5 dB`, the default `0`, an invalid reading, mute and quiet hours suppression, the `config.ini` key, and the RTC round trip with corrupt records.

## Sensor dropout chime

- `ALERT_SENSOR_LOST` (`rlcd_panel/audio_alert.h`, value 8) is the fourth alert bit. It joins `ALERT_CLIMATE_MASK`, so `AlertEvaluator::withClimate()` keeps the noise bit of the last window. `AlertEvaluator::evaluate` sets it when one valid sensor slot is stale, that is, no packet arrived for more than 30 min (`SENSOR_STALE_TIMEOUT_MS`). `SwitchBotRegistry::anyStale` makes the check with wrap-safe 32-bit maths.
- A slot that was never valid does not set the bit. The bit clears when each valid slot sends again. It does not latch: a second dropout after a recovery chimes again.
- The chime is one low 440 Hz note of 400 ms. It sounds once, when the bit switches on. The bit stays on without a repeat. A second stale sensor does not chime again, as for the other alerts.
- Mute and quiet hours suppress the sound only. The wake cycle still stores the bit, so unmuting or the end of the quiet hours never replays an old alert.
- The audio byte in RTC memory is now: bit 0 muted, bits 1 to 4 alert mask, bits 5 and 6 noise band. The state version is 12. The old format gives a clean start. Bit 7 stays reserved. A set bit 7 is rejected, even with a valid CRC.
- Limit: a sensor that is removed for good keeps its slot, so the bit stays on until a power-on clears the RTC state. The chime does not repeat in this time.
- Host tests cover the change from fresh to stale (also at the exact timeout and over the clock wrap), a slot that was never valid, the recovery of one and of each slot, the mute and quiet hours suppression, the 440 Hz pattern and its playback, and the RTC round trip with the audio byte encoding.

## Dew point and VPD line in the climate tiles

- **Code:** `rlcd_panel/climate_math.h`, `rlcd_panel/dashboard_view.h`, `tests/test_firmware_logic.cpp`.
- `ClimateMath::compute` calculates the dew point with the Magnus formula (`a = 17.62`, `b = 243.12 C`). It calculates the vapor pressure deficit (VPD) in kPa as `0.61078 * exp(a * T / (T + b)) * (1 - RH / 100)`. At 20.0 C and 50 %RH the result is 9.3 C and 1.17 kPa.
- The result is invalid for NaN, infinite and out-of-range input (temperature outside -45 C to 60 C, humidity at or below 0 %RH or above 100 %RH). At 0 %RH the dew point does not exist.
- Each of the 4 tiles shows one scale-1 line, for example `DP 9.3C VPD 1.2K` (`K` is kPa). The line sits at `y + 86`, between the sparkline box and the battery line, and ends inside the 190 px tile. A stale or invalid tile shows `DP -- VPD --`.
- The values are calculated for each render. No RTC state changes and the state version stays the same.
- Host tests cover the reference point, 0 and 100 %RH, -10 C and -20 C, NaN, infinite and out-of-range input, the text format, the line position in all 4 tiles, and the dashes for a stale and a missing sensor.

## Comfort zone label for each sensor in the stats view

- **Code:** `rlcd_panel/climate_math.h`, `rlcd_panel/dashboard_view.h`, `tests/test_firmware_logic.cpp`.
- `ClimateMath::comfort(temp_c, rh_pct)` returns `ComfortZone`: `COLD` below 18.0 C, `WARM` above 26.0 C, `DRY` below 30 %RH, `HUMID` above 60 %RH, else `OK`. The temperature is checked first. The exact edges 18.0 C, 26.0 C, 30 %RH and 60 %RH belong to `OK`. NaN, infinite and out-of-range input (limits of `ClimateMath::compute`, humidity 0 to 100 %RH) gives `INVALID`.
- Each sensor block of the stats view shows `COMFORT <LABEL>` in scale-1 text, right-aligned to x = 392 (8 px right margin), on the packet line (`y + 39`). An empty slot shows `COMFORT --` in the `NO SIGNAL` line. A stale or invalid slot shows `COMFORT --`.
- The label is calculated for each render. No RTC state changes and the state version stays 29.
- `docs/display_preview_stats.png` shows `OK`, `COLD` and `--`.
- Host tests cover each label, the exact edges, the priority of the temperature, NaN, infinite and out-of-range input, the dashes for a stale and an empty slot, and the text bounds in each block.

## Deep sleep protection for an empty battery

- **Code:** `rlcd_panel/battery_monitor.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`.
- `BatteryMonitor::updateEmptyProtection` sets `BatteryReading::empty_protection` below 3.30 V on battery power. 3.30 V sets nothing. The latch clears at 3.50 V or more, or on USB power. An invalid reading never sets the latch and holds a set latch.
- `setup()` reads the cell before any module starts. While the latch is set, the wake cycle skips the BLE scan, the audio, the MicroSD card, Wi-Fi and the NTP sync. It only saves the RTC state and sleeps.
- `DashboardView::renderEmptyBattery` draws the full-screen warning `BATTERY EMPTY - CHARGE NOW` once, in the cycle that sets the latch. The reflective panel keeps the image without power, so later cycles leave the display alone.
- `PowerManager::planProtectedSleep` sleeps 3600 s (`PROTECTED_SLEEP_US`) with the `KEY` wakeup. The `KEY` wakeup stays off while the button is held. A `KEY` wakeup checks the voltage again.
- The latch uses bit 3 (value 8) of the battery flags byte in RTC memory. The state version is 13 (15 since `ntp_sync_interval_h`). The old format gives a clean start. A power-on clears the latch and checks the cell again.
- The preview scene `display_preview_battery_empty` shows the warning at 3.29 V.
- Host tests cover 3.29 V and 3.30 V, the hysteresis at 3.49 V and 3.50 V, USB power, an invalid reading, the module gate, the render-once rule, the 3600 s plan, the RTC round trip with a corrupt flag bit, and the preview scene.
- Not verified: the awake time per hour on the board with a lab supply.
- The new key `ntp_sync_interval_h` (default `24`, range `6` to `168` hours) is part of `config.ini` and of the RTC state (state version 16, the old format gives a clean start). A value outside the range or a malformed value is rejected, and the previous value stays.
- `NtpSchedule::syncIntervalMs` replaces the fixed `SYNC_INTERVAL_MS`. `NtpSchedule::due` and `NtpSchedule::decide` take the interval in hours. The retry backoff after a failure (15 min, doubling up to 6 h) does not depend on the interval.
- `syncNtpTime` passes `config().ntp_sync_interval_h`. A change in `config.ini` applies from the next power-on or `KEY` wakeup that reads the card.
- Host tests cover the default, the edges `6` and `168`, out-of-range and malformed values, the schedule decision with 6 h and 168 h, the unchanged backoff, the RTC round trip, corrupt fields, and the old state version.

## 24-hour minimum and maximum records in the stats view

- **Code:** `rlcd_panel/sparkline.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`.
- `TemperatureHistory` is a circular FIFO of 24 hourly temperature samples for each sensor slot. A sample is an `int16_t` in 0.1 C steps from -100.0 C to +100.0 C. `encode()` rounds and clamps. `pushCelsius()` ignores NaN and values outside the range.
- `ClimateHistory::record` adds the temperature next to the humidity, with the same hourly gate. An invalid onboard reading and a stale SwitchBot slot add no sample.
- `HumidityHistory::range` and `TemperatureHistory::range` return the lowest and highest sample. They return `false` for fewer than 2 samples.
- The stats view shows one line below each sensor block, for example `24H T-15.0 TO 5.0 H58-86`. The text gives the lowest and highest temperature in C, then the lowest and highest humidity in %RH. Each part shows `--` for fewer than 2 samples. The 5x7 font has no `/`, so the line uses `TO`. The longest text has 30 glyphs (360 px) and keeps the 8 px right margin.
- The sensor blocks in the stats view now have a pitch of 72 px (`SENSOR_BLOCK_PITCH`), so the new lines fit above the noise line. The onboard sensor has no block in this view, so it shows no record line.
- Each slot adds 50 bytes to the RTC state. The state version is 14 (16 since `ntp_sync_interval_h`). The old format gives a clean start. A ring with a count or head out of range, or a sample outside -100.0 C to +100.0 C, gives a clean start.
- Host tests cover the minimum and maximum over 24+ samples, the drop of the oldest sample, negative temperatures (-15 C to 5 C), the encoding limits, skipped and stale samples, fewer than 2 samples, the text bounds, the RTC round trip with corrupt fields, and the old state version. `docs/display_preview_stats.png` shows the records.

## Chime selection for each alert in config.ini

- The new key `chime_alert_mask` (default `31`, range `0` to `31`; `15` before the charge-full chime) is part of `config.ini`. Bit 0 is `ALERT_FREEZER`, bit 1 is `ALERT_HUMIDITY`, bit 2 is `ALERT_NOISE_HIGH`, bit 3 is `ALERT_SENSOR_LOST`, and bit 4 is `ALERT_CHARGE_FULL`. A value above `31` or a malformed value is rejected, and the previous value stays.
- A cleared bit suppresses the sound of this alert only. The alert state is still stored, so setting the bit again does not replay an old alert. Mute and quiet hours still suppress each chime.
- `AlertEvaluator::chimeBits`, `step`, `climateStep` and `NoiseAlertEvaluator::step` take the mask as the last argument. The default is all bits set.
- The mask adds one byte to the config trailer in RTC memory. The state version is 15 (16 since `ntp_sync_interval_h`). The old format gives a clean start. A mask above `15` is rejected, even with a valid CRC.
- Host tests cover the default, each single bit, `0`, a combination, out-of-range values, mute and quiet hours, no replay after the bit is set again, and the RTC round trip.

## USB power detection via USB controller status

- `BatteryMonitor::read()` and `BatteryMonitor::readAveraged()` pass `HWCDC::isPlugged()` to `BatteryMonitor::decodeMillivolts(adc_millivolts, usb_plugged)`.
- `BatteryReading::usb_powered` is true when the USB controller reports a host, or when the voltage is 4.25 V or more (`BatteryMonitor::USB_VOLTAGE_V`).
- An installed cell clamps the voltage to 4.20 V. Before this change, the diagnostics view then showed `POWER: BATTERY` on a connected host.
- The parameter `usb_plugged` defaults to false. Host tests use it as the injection point for the controller status.
- Host tests cover the plugged state at 3.3 V, 3.8 V, 4.15 V and 4.2 V, the unplugged state below 4.25 V, the unplugged state at 4.25 V and above, the 4.248 V and 4.251 V limit, and the effect on the low battery alert and the empty protection.
- `HWCDC::isPlugged()` calls `usb_serial_jtag_is_connected()`. The call reports SOF traffic of an active USB host, not the VBUS level. A charger without a host data connection with an installed cell still shows `POWER: BATTERY`, unless the voltage is 4.25 V or more.
- The SOF watchdog can lag the real state for a few milliseconds after a wake. A short false reading shows `POWER: BATTERY` for one wake cycle at most.
- The hardware check on the board connected to a Mac is not part of the host tests.

## Battery charge cycle counter in the diagnostics view

- **Code:** `rlcd_panel/battery_monitor.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`.
- The diagnostics view shows `CYCLES: N.N` (line at y = 64). `CYCLES: --` shows when NVS is unreadable. The widest text `CYCLES: 9999.9` has 14 glyphs = 168 px.
- `CycleTracker` adds each percentage drop between two wake cycles on battery power. 100 points of discharge are one cycle. The total clamps at 9999.9 cycles.
- The total is stored in NVS (`Preferences`, namespace `rlcd`, key `cycle_count`, percentage points), because RTC memory does not survive a cell removal. The unwritten points and the tracking state stay in RTC memory (`RTC_DATA_ATTR`, checksummed), separate from `PersistentState`.
- NVS is written once per charge event (USB power starts after a discharge) and at each whole-cycle milestone. A failed write keeps the points; each following wake cycle retries until a write succeeds. A rise of more than 3 points on battery power (a charger without a USB host) also counts as a charge event.
- An invalid reading changes nothing. A drop of more than 40 points between two readings (cell swap) only moves the baseline. A rise of up to 3 points on battery power is ADC noise and keeps the baseline. A rise on USB power resets the baseline. A charge event with unreadable NVS stays owed until NVS reads again.
- `tools/preview_scenes.h` shows `CYCLES: 12.3`. `docs/display_preview_diagnostics.png` and `docs/display_preview_runtime.png` are regenerated.
- Host tests use a portable NVS stand-in with a write counter. They cover partial discharges, the milestone write, the charge detection, USB power, invalid readings, jumps, failed and unreadable NVS, the write count over 10 cycles, the RTC round trip with corrupt data, and the text bounds of the line.
- The check on the board (NVS persistence over a power cycle) is not part of the host tests.

## Humidity calibration offset for the onboard SHTC3

- **Code:** `rlcd_panel/device_config.h`, `rlcd_panel/shtc3_sensor.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`.
- The new key `rh_offset_pct` (default `0.0`, range `-10.0` to `10.0`) is part of `config.ini`. A value outside the range or a malformed value is rejected, and the previous value stays.
- `Shtc3Codec::decodeFrame` takes the offset as the last argument (default `0.0`). It adds the offset to the decoded humidity and clamps the result to 0 to 100 %RH.
- The tile, the sparkline sample and the CSV row use the same onboard reading. They show the corrected value.
- The offset adds 4 bytes (float) to the config trailer in RTC memory. The state version is 17. The old format gives a clean start. A NaN value or a value outside the range is rejected, even with a valid CRC.
- Acceptance criteria: host tests cover the default, the edges `-10.0` and `10.0`, out-of-range and malformed values, the clamp at 0 and 100 %RH, the RTC round trip, and the rejection of the old state version.

## Calibration offsets for each SwitchBot and BTHome slot

- **Code:** `rlcd_panel/device_config.h`, `rlcd_panel/switchbot_ble.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`. `rlcd_panel/bthome_ble.h` needs no change: BTHome and SwitchBot packets use the same registry slots.
- The new `config.ini` keys `slot1_temp_offset_c` to `slot3_temp_offset_c` (default `0.0`, range `-5.0` to `5.0`) and `slot1_rh_offset_pct` to `slot3_rh_offset_pct` (default `0.0`, range `-10.0` to `10.0`) correct one registry slot each. A value outside the range or a malformed value is rejected, and the previous value stays. A slot without a key keeps its raw value.
- An offset belongs to the slot position, not to the sensor MAC. A new sensor takes the first free slot, so check the offsets after a registry reset or a sensor swap.
- `SwitchBotRegistry::calibrated()` returns a copy of the registry with the offsets added. The humidity is rounded and clamped to 0 to 100 %RH. An empty slot stays empty. The stored registry keeps the raw readings, so the RTC state never applies an offset twice.
- `calibratedRegistry()` in `rlcd_panel.ino` feeds the dashboard views, the freezer and mold alerts, the hourly history and the CSV log. Each of them shows the corrected value.
- The six floats add 24 bytes to the config trailer in RTC memory. The state version is 18. The old format gives a clean start. A NaN value or a value outside the range is rejected, even with a valid CRC.
- Acceptance criteria: host tests cover the defaults, the range edges, malformed values, the application to the correct slot only (SwitchBot and BTHome), the humidity clamp, the effect on the freezer and mold alerts, the history sample and the RTC round trip. A slot without an offset key keeps its raw value.

## BLE signal strength in the sensor stats view

- **Code:** `rlcd_panel/switchbot_ble.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`. `rlcd_panel/bthome_ble.h` needs no change: BTHome and SwitchBot packets use the same registry slots and the same scan callback path. `tools/preview_scenes.h` already holds sample RSSI values.
- `SwitchBotReading::rssi` is now `int8_t` (dBm). `SwitchBotBleParser::clampRssi()` limits the value of the BLE stack to -128 to 127 dBm. A valid packet updates the RSSI of its slot. A rejected packet leaves it unchanged.
- The sensor stats view shows `RSSI -NN DBM` in the first line of each sensor block. `DashboardView::formatRssi()` prints `RSSI --` for a stale slot (no packet for 30 minutes) and for an empty reading. A slot that never received a packet keeps the existing `S1 NO SIGNAL` line.
- The RSSI uses one byte in the RTC slot record instead of two. The state version is 19. The old format gives a clean start.
- Acceptance criteria: host tests cover the RSSI update on a valid packet, no update on a rejected packet, the text for -30 dBm and -100 dBm, the dashes for a stale slot, the line bounds in each block, and the RTC round trip. `docs/display_preview_stats.png` shows the line.

## Serial console command to show the active configuration

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/device_config.h`, `rlcd_panel/rlcd_panel.ino`, `scripts/dump-logs.sh`, `tests/test_firmware_logic.cpp`.
- The new console command `CONFIG` shows the active settings. It takes no argument: `CONFIG now` gives `ERR UNKNOWN`. The command runs on USB power only, like the other console commands (`PowerManager::serialConsoleAllowed`).
- The reply is one `key=value` line for each `DeviceConfig` setting (18 lines, key order of `DeviceConfigParser`), the line `SOURCE SD` or `SOURCE DEFAULT`, and `CRC32 <8 hex digits>`. The CRC-32 covers all bytes before the `CRC32` line. `DeviceConfigText::format` writes the lines. A float has one or two decimals, so `DeviceConfigParser` reads the text back to the same values.
- The command reads the RTC copy (`g_state.config`, `g_state.config_from_sd`) only. It does not mount the card . `SerialConsoleRunner::execute` ignores `CONFIG`; `SerialConsoleRunner::showConfig` sends the reply. A short serial write ends the reply, and the host sees no CRC line.
- `DeviceConfig` holds no Wi-Fi setting, so the reply sends no credential.
- `./scripts/dump-logs.sh --config [port]` sends `CONFIG`, checks the CRC-32 and prints the lines.
- Acceptance criteria: host tests cover the parser for `CONFIG`, the rejection of `CONFIG` with arguments, the line format of each key, the CRC-32 of the output, the source line, no card access, the stop on a short write, and the parse of the output by `DeviceConfigParser` back to the same values (edge values and defaults).

## Sensor names from config.ini in the tiles

- **Code:** `rlcd_panel/device_config.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`, `tests/test_firmware_logic.cpp`.
- The new `config.ini` keys `onboard_name` and `slot1_name` to `slot3_name` set a name for each sensor. A name has 1 to 12 characters: `A` to `Z`, `0` to `9`, space and `-`. All of them have a glyph in the 5x7 font. A lowercase letter, another symbol, an empty value and a name with more than 12 characters are rejected, and the previous name stays. A sensor without a name keeps the default title.
- `DashboardView::tileTitle()` gives the tile title: the name, else `ONBOARD SHTC3` or `SENSOR n`. The widest name (12 glyphs at scale 2) ends at x + 150 in its tile and stays left of the `STALE` badge, which starts at x + 151.
- `DashboardView::statsLabel()` gives the label in the sensor stats view: the name, else `S1` to `S3`. The longest line (`<12 glyphs> RSSI -100 DBM`) is 312 px wide.
- A name belongs to the slot position, not to the sensor MAC, like the calibration offsets. `DeviceConfigText::format` writes a name only when it is set, so the `CONFIG` console reply of a default config stays unchanged.
- The four names add 52 bytes (`PowerManager::NAMES_SIZE`) to the config trailer in RTC memory. The state version is 20. The old format gives a clean start. A stored name with an unsupported character or without a terminator is rejected, even with a valid CRC.
- Acceptance criteria: host tests cover the default titles, a 12-character name, an empty value, a name that is too long, an unsupported character, the title bounds in each tile (also with the `STALE` badge), the stats view label, the config text round trip and the RTC round trip. `docs/display_preview_dashboard.png` shows the sample names.

## RTC drift measurement at each NTP sync

- **Code:** `rlcd_panel/pcf85063_rtc.h`, `rlcd_panel/ntp_sync.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`, `tests/test_firmware_logic.cpp`.
- `NtpClient::sync` reads the `PCF85063A` time before the NTP write. The drift is the RTC time minus the NTP time in seconds. A positive value means the RTC runs ahead. The reference uses the UTC offset that held at the previous sync, so a DST change shows no false hour.
- `RtcDrift::record` calculates the rate in ppm (rounded) from the drift and the time since the previous successful sync. A failed sync changes nothing. The first sync, an unreadable RTC, a sync less than 1 h after the previous one (`RtcDrift::MIN_INTERVAL_S`) and a drift above 86400 s store the sync time but give no figure. `NtpTime::driftReference` builds the reference. A seed of the RTC from the compile stamp clears the baseline. `PowerManager::serialize` stores an out-of-range figure as "no figure".
- `RtcDriftState` (flags, last sync time, drift, rate) lives in `PersistentState::rtc_drift` and uses 13 bytes in the NTP trailer in RTC memory. The state version is 21. The old format gives a clean start. A record with a reserved flag, a figure without a sync, a value without a figure, a value above its limit, a rate with the wrong sign or a sync time outside 2000 to 2099 is rejected, even with a valid CRC.
- The diagnostics view shows `RTC DRIFT: +N S (+N PPM)` at y = 184, or `RTC DRIFT: --` without a figure. The widest line (32 glyphs) is 384 px wide. The two diagnostics tests for the noise line check the rows 179 to 184 and 198 to 204.
- `RtcDriftState::interval_s` (4 bytes) holds the time between the two syncs of the figure; it is 0 without a figure. It uses 4 more bytes in the NTP trailer in RTC memory. `STATE_VERSION` is 53. A record of version 52 gives a clean start.
- `RtcTrim` (`pcf85063_rtc.h`) calculates the offset register (`0x02`, mode 0, 4.34 ppm per step, -64 to 63) from `RtcDriftState::rate_ppm`: `rate_ppm / 4.34` steps, rounded, subtracted from the active value (a fast clock needs a slower count), clamped. `RtcTrim::encode` gives 7-bit two's complement with mode bit 0.
- The console command `RTC TRIM` needs a figure from an interval of 24 h or more, else it replies `ERR NO_DRIFT`. It reads the register, writes the new value, reads it back, clears the `RtcDrift` baseline, and replies `OK RTC TRIM <old> <new>`. An unreadable register, a failed write or a readback mismatch gives `ERR WRITE_FAILED` and keeps the baseline. The register keeps its value through deep sleep and a reset while the RTC has power, so the firmware stores no copy. No card access. On BLE the command gives `ERR READ_ONLY` with `ble_console` 1.
- `STATUS` shows `RTC trim=<N|--> drift_ppm=<N|-->` after `RESET`. `drift_ppm` shows only a figure from an interval of 24 h or more.
- The macOS companion parses the line (`RtcTrimMetrics`, `Diagnostics.swift`) and `DiagnosticsView` shows the `Trim RTC` button when a drift figure exists (`AppModel.trimRtc` sends `RTC TRIM`, then `STATUS`).
- Acceptance criteria: host tests cover a positive and a negative drift, a drift of 0, the ppm rate over 24 h and 168 h, the dashes without a previous sync, the rejection of a failed sync, a sync after less than 1 h, the reference across a DST change, the line bounds, and the RTC round trip. `docs/display_preview_diagnostics.png` shows the line.

## Daily climate summary file on the MicroSD card

- **Code:** `rlcd_panel/sd_logger.h`, `rlcd_panel/sparkline.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/ntp_sync.h`, `rlcd_panel/rlcd_panel.ino`, `scripts/dump-logs.sh`, `tests/test_firmware_logic.cpp`.
- `DailyPlanner::decide` runs after each hourly write. The RTC holds local time, so the first hourly write on a new calendar date is the first one after local midnight, in CET and in CEST. The first run and a history without a sample for 24 h (`ClimateHistory::freshWithin24h`) only stamp the day. An invalid, stopped or untrusted clock, an impossible date and a clock that runs back write nothing.
- `SdLogger::appendDaily` appended one row for the day that ended to `/sdcard/daily_v1_YYYY.csv` (later versions write `daily_v3_YYYY.csv`). The year is the year of the summarized day. A new file gets the header first. The row has 27 columns: the date, six values for each of the four sensors (temperature and humidity: minimum, maximum, mean, in 0.1 steps), then `noise_min_db` and `noise_max_db`. A sensor without samples and a noise history without a valid hour give empty fields. The source is the 24 hourly samples in the RTC memory (`summary()` of the three history classes), so the window is the last 24 samples and not the exact calendar day.
- A failed write leaves the day unstamped, and the next hourly write retries. A mount failure of the hourly write skips the second mount. The hourly file stays unchanged (later versions write `climate_v5_YYYYMM.csv`).
- The stamp (`PersistentState::daily`) uses 5 bytes in the SD log trailer in RTC memory. The state version is 22. The old format gives a clean start. A stamp with a reserved flag or an impossible date is rejected, even with a valid CRC.
- `LIST` and `DUMP daily_v1_YYYY.csv` accept the daily file (`SdLogCodec::isConsoleFileName`). `scripts/dump-logs.sh` copies it. The retention rule deletes a daily file when its whole year is older than `log_retention_months` (the file counts as December of its year); the current year never goes.
- Acceptance criteria: host tests cover the statistics, the row format and header, empty fields, the worst-case row, the file names, the retention rule, the midnight gate in CET and CEST (including both DST changes), one row per day, a failed write with a retry, the RTC round trip with corrupt stamps, and the console name check with `LIST` and `DUMP`.

## Charge complete chime on USB power

- **Code:** `rlcd_panel/audio_alert.h`, `rlcd_panel/battery_monitor.h`, `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`.
- `BatteryMonitor::updateChargeFull` sets the latch at 100 % on USB power and holds it down to 95 % (`CHARGE_FULL_CLEAR_PCT`), so a reading that swings between 99 % and 100 % never chimes again. Battery power clears the latch. An invalid reading holds it.
- `ALERT_CHARGE_FULL` is bit 4 (value 16) of the alert mask. `ALERT_MASK_ALL` is now 31. `AlertEvaluator::chargeStep` sets or clears this bit and keeps the other bits. The chime sounds on the rising edge only: two falling notes (988 Hz, 660 Hz). `chimeOnChargeFull` in `rlcd_panel.ino` runs after `chimeOnAlerts` in each wake cycle.
- Mute, quiet hours and a cleared bit 4 of `chime_alert_mask` suppress the sound only. The mask is stored, so no chime replays later. The default of `chime_alert_mask` is `31`; a card with `15` silences this chime.
- The audio byte in RTC memory holds the mask in bits 1 to 5 and the noise band in bits 6 and 7. The state version is 23. The old format gives a clean start.
- Acceptance criteria: host tests cover the trigger at 100 %, no replay for 99 % to 100 % noise, the release at 94 %, no trigger on battery power, an invalid reading, the mask bit, mute and quiet hours, the pattern and the playback, the new mask range `0` to `31` in `config.ini`, and the RTC round trip with the new layout.

## Hourly count of loud transient events

- `TransientCounter` (`clap_detector.h`) reduces the stereo samples of the noise window to the peak level of each millisecond and feeds a `ClapDetector`. `ClapDetector::transientCount()` counts each rise of 20 dB in under 5 ms that decays within 100 ms. The echo, lockout and double clap rules do not apply. The count stops at 65535.
- `NoiseSampler::measure` feeds the same samples to the counter that it feeds to the level analysis. The microphones stay on for the existing window only (100 ms settling plus 5 s). `NoiseReading::events` holds the count. A failed window has `valid == false` and `events == 0`.
- The CSV format is version 3. The header has 19 columns: the 18 columns of version 2 plus `noise_events`. The file name is `/sdcard/climate_v3_YYYYMM.csv`. A failed or skipped window gives an empty `noise_events` field. The old `climate_v2_YYYYMM.csv` files stay unchanged. The serial console and the retention accept both names. `scripts/dump-logs.sh` accepts both names.
- The diagnostics view shows `EVENTS: N` at the right of the `CONFIG` line (`EVENTS: --` for a failed window).
- Host tests cover 0, 1 and 10 synthetic transients, a slow rise, a steady tone, the counter limit, the read count of the window, the 19 column header and row, the empty field, and the line bounds.

## Fixed MAC address for each sensor slot

- The new `config.ini` keys `slot1_mac` to `slot3_mac` fix a registry slot to one sensor. The format is `AA:BB:CC:DD:EE:FF`. Upper and lower case hex digits are valid. An empty value, or no key, leaves the slot unfixed.
- The parser rejects a wrong separator, a short or long value, a non-hex digit, the all-zero MAC, and a MAC that another key already fixes. The previous value of the key stays.
- A fixed slot accepts packets of its MAC only, also when an earlier slot is free. A slot without a fixed MAC keeps the first-come rule, but it never accepts a MAC that another slot has fixed. This holds for SwitchBot and BTHome packets, because both use `SwitchBotRegistry::upsert`. A sensor of a neighbor cannot take a slot.
- `PersistentState::applyConfig` takes over a new config. A slot whose fixed MAC is set, changed or removed loses its reading and its 24-hour humidity and temperature histories. A slot that holds a MAC another slot has now fixed loses them too. A KEY wakeup with an unchanged file keeps all data. The call runs before the scan starts and inside the registry lock.
- The stats view shows `MAC ..:EE:FF` style lines with the last three MAC bytes in each sensor block. A fixed slot without signal shows the line too, so the user sees the configured sensor.
- `DeviceConfigText` writes the fixed MACs back. The config trailer of the RTC state holds the three MACs, and `STATE_VERSION` is 24. A stored state with a duplicate MAC, or with a reading that breaks a fixed MAC, gets a clean start.
- Host tests cover the parser (valid, lowercase, wrong separator, short, long, bad digit, zero, empty, duplicate), the registry (fixed slot, unfixed slot, first-come, SwitchBot, BTHome), the reset after a MAC change, the RTC round trip with corrupt records, and the stats line.

## Rejected config.ini lines in the diagnostics view and the serial console

- `DeviceConfigParser` records the 1-based line number of each rejected line. `ConfigParseResult::rejected` is the total count (it stops at 255). `rejected_lines` holds the first four line numbers. A line counts when it has no `=`, a malformed value, or a value outside the range. An unknown key is no error. LF and CRLF count one line each, and a byte order mark does not shift the numbers.
- `ConfigRejects` carries the count and the four line numbers. `PersistentState::config_rejects` stores them, and `applyConfig` takes them over with each new config. `SdLogger::loadConfig` returns them. A card without a usable file clears them. A card that does not mount keeps the old ones.
- The config trailer of the RTC state holds the count and four 16-bit line numbers (9 bytes). `STATE_VERSION` is 25. The old format gives a clean start. A record with a line number of 0 for a counted entry, or a line number for an uncounted entry, is rejected, even with a valid CRC.
- The diagnostics view shows `CONFIG: SD (N ERR)` when the count is above 0. The longest text `CONFIG: SD (255 ERR)` ends at x = 250. The events text shortens to `EV: N` at x = 264 while an error shows. The font has the new glyphs `(` and `)`.
- The console command `CONFIG` sends one `REJECTED line=N` line for each recorded line after the `SOURCE SD` line and before the `CRC32` line. The CRC-32 covers these lines. The defaults (`SOURCE DEFAULT`) list none.
- Host tests cover a file with no errors, one malformed value, one out-of-range value, a line without `=`, six rejected lines (four line numbers, count 6), CRLF, a byte order mark, an unknown key, the count limit of 255, the line bounds of `CONFIG: SD (255 ERR)`, the `CONFIG` output with the CRC-32, and the RTC round trip with corrupt records. `docs/display_preview_diagnostics.png` shows the error count.

## L10 and L90 noise levels in the hourly window

- `BlockLevels` (`es7210_mic.h`) splits the samples of the existing 5 s `NoiseSampler` window into 50 blocks of 100 ms. It keeps the sum of squares per block and channel (800 bytes). `NoiseSampler::measure` feeds it from the same read loop, so the microphones stay on for the existing window only.
- `NoiseReading::l10_rms` is the level that 10 % of the blocks exceed. `l90_rms` is the background level that 90 % of the blocks exceed. The levels use the same usable channels as `rms`. `levels_valid` is false for a failed window, for a window below one block, and for a window above 50 blocks. A rest below 100 ms is ignored.
- The CSV format is version 4 (superseded by version 5 with 27 columns; see "Log registry slots 4 and 5 in the climate CSV files"). The header has 21 columns: the 19 columns of version 3 plus `noise_l10` and `noise_l90` (dB, calibrated offset applied). The file name is `/sdcard/climate_v4_YYYYMM.csv`. A failed or skipped window gives empty fields. A window without percentiles gives empty L10 and L90 fields only. The old `climate_v3_YYYYMM.csv` and `climate_v2_YYYYMM.csv` files stay unchanged. `LIST`, `DUMP`, the retention rule and `scripts/dump-logs.sh` accept all three names.
- The diagnostics view shows `L10/L90: NN/NN DBA` (calibrated) or `L10/L90: -NN/-NN DBFS` (uncalibrated) in small text right of the `SD FREE` line, and `L10/L90: --` without percentiles. The 5x7 font gets the glyph `/`.
- Host tests cover a steady tone (L10 equals L90), a single loud block, five and six loud blocks, a ramp of 50 blocks, a 250 ms and a 6 s window, one usable channel, a failed window, the `DBA` and `DBFS` text, the 21 column header and row, the empty fields, the console file name check, the retention rule and the line bounds.

## Ventilation advice from indoor and outdoor absolute humidity

- **Code:** `rlcd_panel/climate_math.h`, `rlcd_panel/device_config.h`, `rlcd_panel/audio_alert.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`.
- **Preview:** `docs/display_preview_ventilate.png` shows the banner on the dashboard.
- `ClimateMath::absoluteHumidityGm3` calculates the water content in g/m³ from the Magnus vapor pressure. It returns `-1` for an invalid input.
- The new `config.ini` key `outdoor_slot` (default `0` is off, range `0` to `3`) selects the registry slot of the outdoor sensor. The onboard sensor is the indoor reference.
- `AlertEvaluator` sets `ALERT_VENTILATE` (bit 5) when the onboard humidity is above `mold_alert_limit_rh` and the outdoor air holds at least 2.0 g/m³ less water. The advice releases when the difference falls below 1.0 g/m³ or the onboard humidity is 3 %RH below the limit. Between the two limits the previous state holds.
- An off, empty, stale or invalid outdoor slot and an invalid onboard reading give no advice and clear it.
- The dashboard shows the banner `VENTILATE` in the header bar, in the same style as the low battery banner. The low battery banner wins. The banner hides the title, the clock and the battery indicator while the advice is active. The other views keep their header.
- The alert has its own chime (three rising notes) and sounds once for each trigger. Mute, quiet hours and a cleared bit 5 of `chime_alert_mask` suppress the sound only. The mask is stored, so no chime replays later.
- `chime_alert_mask` now has the range `0` to `63` (default `63`). A card with `31` silences the ventilation chime.
- RTC state version 27: a latch byte stores bit 5 before the audio byte, and the config trailer stores `outdoor_slot` after `wake_interval_s`. A corrupt or out-of-range record gives a clean start.

## Serial console command CALIB with suggested offsets

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`.
- The console command `CALIB N` (`N` is `0` for the onboard sensor or `1` to `5` for a slot) compares each other sensor with the reference. A missing argument, `CALIB 6` and extra text give `ERR BAD_ARG`.
- The command pairs the hourly samples of `ClimateHistory` by age. Only hours with a temperature and a humidity sample in both histories count.
- A sensor with at least 6 common hours gets one line `SENSOR <name> hours=<n> temp_offset=<x> rh_offset=<x> temp_diff=<x> rh_diff=<x>`. The offsets are the active `config.ini` values. The differences are the mean values (sensor minus reference). A sensor with fewer hours gets `SKIP <name> hours=<n>`.
- The suggested lines follow, for example `slot2_temp_offset_c=-0.4` and `slot2_rh_offset_pct=2.1`. Each value is the active offset minus the mean difference, clamped to the key range. The onboard sensor uses the keys `temp_offset_c` and `rh_offset_pct`.
- The answer comes from the RTC copy of the histories and the configuration. The command does not mount or write the MicroSD card, so the console keeps running. The command runs on USB power only. The output ends with the `CRC32` line over all lines before it.
- Host tests cover the parser, a constant difference, a gap, fewer than 6 hours, an empty reference, an active offset, the clamp, the onboard key names, the CRC-32 line and the USB power gate.
- `ClimateHistory::usb_mask` (24 bits, bit 0 is the newest onboard sample) records which hourly onboard samples came from USB power: each onboard push shifts the mask and sets bit 0 on USB power. The mask is 3 bytes little-endian in `PersistentState` just before the checksum (`STATE_VERSION` 56). A bit beyond the count of onboard samples is rejected, and a power-on sets it to 0.
- For `CALIB N` with N 1 to 5 the onboard `temp_offset_c` proposal uses the battery hours only when 3 or more battery hours exist; else it keeps the mean over all hours. The `temp_diff` value of the `SENSOR` line stays the mean over all hours.
- After the onboard `SENSOR` line the reply adds `USB hours=<n> bat_hours=<n> usb_temp_offset=<x> usb_temp_diff=<x|-->`. `usb_temp_diff` is the USB mean difference minus the battery mean difference. With 3 or more USB hours and 3 or more battery hours the reply adds the proposal `usb_temp_offset_c=<x>` (the active value minus `usb_temp_diff`, clamped to -5.0 to 0.0 C). Fewer hours give `--` and no proposal. The `CRC32` line covers the new lines. `CALIB 0` does not change.
- The macOS `CalibrationReply` parses the `USB` line into `UsbSplit` (`usbTempDiff` is `nil` for `--`; `usb_hours=x` throws `ParseError.badLine`). `CalibrationView` shows the hours and lists `usb_temp_offset_c` as one more selectable row.

## Serial console command SALT with a saturated salt humidity check

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/climate_math.h`, `rlcd_panel/rlcd_panel.ino`, `macos/RlcdCompanion/Sources/RlcdProtocol/Calibration.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/CalibrationView.swift`, `tests/test_firmware_logic.cpp`.
- The user puts one sensor in a closed box with saturated sodium chloride for 6 hours at least. The console command `SALT N` (`N` is `0` for the onboard sensor or `1` to `5` for a slot) then checks the humidity of this sensor. A missing argument, `SALT 6` and extra text give `ERR BAD_ARG`.
- `ClimateMath::saltReferenceRh(temp_c)` gives the equilibrium humidity of the salt from a fixed table (10 C 75.7 %RH, 15 C 75.6, 20 C 75.5, 25 C 75.3, 30 C 75.1, 35 C 74.9; linear between the points). It gives NaN outside 10 to 35 C.
- The command reads the last 6 hourly samples of the sensor from `ClimateHistory`. The reply is `SALT <name> hours=<n> rh_mean=<x> rh_span=<x> temp_mean=<x> reference=<x>`, then one line `<key>=<value>` (`rh_offset_pct` for the onboard sensor, `slotN_rh_offset_pct` for a slot), then the `CRC32` line. The value is the active offset plus the reference minus the mean humidity, clamped to the key range.
- Fewer than 6 samples give `SKIP <name> hours=<n>`. A humidity span above 1.0 %RH gives `UNSTABLE <name> span=<x>`. Both replies end with the `CRC32` line. A mean temperature outside the table gives `ERR BAD_TEMP`.
- The answer comes from the RTC copy of the histories and the configuration. The command does not mount or write the MicroSD card, so the console keeps running. The command runs on USB power only.
- macOS companion: `ConsoleCommand.salt(N)` sends `SALT N` (no line for `N` outside 0 to 5). `SaltReply.parse` (`Calibration.swift`) reads the three reply types and throws `ParseError.badLine` for a bad number. `CalibrationView` has a salt check section: the user selects a sensor and presses `Check`, then applies the proposal through `ConfigBatch` (`SET`, then `CONFIG`).
- Host tests cover the table at 15, 20, 25 and 30 C, NaN at 9 C, a stable sensor at 73.0 %RH and 25 C (proposal +2.3 %RH plus the active offset), the clamp, `SKIP` with 5 samples, `UNSTABLE` with a span of 1.2 %RH, `ERR BAD_TEMP`, the key names, `ERR BAD_ARG` for `SALT 6`, the CRC-32 line and the USB power gate. `SaltReplyTests` cover the Swift parser.

## High-humidity ventilation badge on the sensor tiles

- **Code:** `rlcd_panel/ventilate_latch.h`, `rlcd_panel/device_config.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`.
- **Preview:** `docs/display_preview_dashboard.png` shows the badge in the tile `GARTEN`.
- The new `config.ini` key `ventilate_limit_rh` (default `60`, range `50` to `80` %RH) sets the limit. A value outside the range is rejected.
- `VentilateLatch` keeps one bit for each tile (bit 0 onboard, bits 1 to 3 the slots). The bit sets when the calibrated humidity is above the limit. It releases when the humidity is 2 %RH or more below the limit. Between the two limits the bit keeps its state. A stale slot, an empty slot and an invalid reading clear the bit.
- The tile shows an inverted 9 x 9 px badge with the glyph `V` right of the humidity line, at x + 108 and y + 56. The badge stays inside the tile, above the sparkline box. A stale tile shows no badge.
- The badge is independent of the `VENTILATE` header banner, of `ALERT_VENTILATE` and of `mold_alert_limit_rh`. It has no chime.
- RTC state version 28: the audio block gets a third byte with the four latch bits, and the config trailer stores `ventilate_limit_rh` after `outdoor_slot`. A corrupt record or a latch bit above bit 3 gives a clean start.

## Trend arrows for temperature and humidity in the climate tiles

- **Code:** `rlcd_panel/sparkline.h`, `rlcd_panel/dashboard_view.h`, `tests/test_firmware_logic.cpp`.
- **Preview:** `docs/display_preview_dashboard.png` shows rising and falling arrows in the tiles.
- `TrendClassifier` compares the newest hourly sample with the sample 3 hours earlier. It gives `RISE` for a change of 0.5 °C or more (3.0 %RH for humidity), `FALL` for the same negative change and `STEADY` for a smaller change.
- It gives `UNKNOWN` for fewer than 4 samples and for a sample outside the valid range.
- `DashboardView::renderTile` draws a 7 x 7 px triangle with `fillRect` right of the temperature line and right of the humidity line. `STEADY` and `UNKNOWN` draw no mark.
- The humidity mark is skipped when it would touch the sparkline box. The trend compares sample positions, so a gap in the hourly samples widens the span. Each quantity uses its own buffer. The trend lags by up to one hour. It uses no new RTC memory and no new sensor read.

## Early BLE scan stop when each known sensor has reported

- **Code:** `rlcd_panel/power_manager.h`, `rlcd_panel/battery_monitor.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`.
- `ScanCompletion` ends the BLE scan before `scan_duration_s` when each valid slot received a packet with temperature, humidity and battery in this scan, and at least 1 s of the scan is done. A kept battery value from an earlier scan does not count.
- `ScanCompletion::fullScanRequired` keeps the full scan after a power-on, a `KEY` wakeup or any other non-timer wakeup, on USB power, in the first wake cycle of each hour (`hour_due`), and when a slot is empty or stale. Thus a new sensor can still fill an empty slot.
- `scanOnce` checks `canStop` every 10 ms inside the registry lock and records the saved time in `g_scan_saved_ms` (RTC memory).
- `BatteryHistory::estimate` takes the saved time. The awake time `AWAKE_MS` (10 s) shrinks by it, capped so that `MIN_AWAKE_MS` (2 s) remains, and the runtime grows by the same ratio.
- Host tests cover the stop with all slots reported, one missing slot, a packet without battery field, the 1 s minimum, an empty slot, a stale slot, power-on, `KEY` wakeup, the hourly cycle, USB power, unknown MACs and the runtime estimate with a short scan.
- The runtime estimate is a model: it scales the discharge slope by the saved time of the last scan. A cycle with a full scan (hourly, power-on) shows the unscaled value, so the value moves between cycles. A `KEY` wakeup runs no scan and keeps the saved time of the last scan. The battery reading for the USB check comes from the last wake cycle, so one cycle after a plug-in keeps the old power state.

## Low battery warning for external SwitchBot and BTHome sensors

- **Code:** `rlcd_panel/switchbot_ble.h`, `rlcd_panel/device_config.h`, `rlcd_panel/audio_alert.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`, `tests/test_firmware_logic.cpp`.
- **Preview:** `docs/display_preview_dashboard.png` shows `BAT LOW` in the tile `GARTEN`.
- The new `config.ini` key `sensor_battery_limit_pct` (default `10`, range `0` to `50`, `0` is off) sets the limit. A value outside the range is rejected, and the previous value stays.
- `SensorBatteryLatch::update` keeps one bit for each registry slot. A bit sets when the reported `battery_pct` is at or below the limit. It releases at the limit plus 5 %. Between the two values the bit holds. SwitchBot and BTHome packets fill the same `battery_pct` field, so one latch serves both sensor types.
- A packet without a battery field (`battery_pct` is `-1`) and a stale slot do not change the bit. An empty slot clears it. The limit `0` clears all bits.
- The tile shows the text `BAT LOW` in an inverted box instead of `BAT: N %`. The box is 86 x 18 px at y + 94 and does not touch the border of the tile.
- The new alert `ALERT_SENSOR_BATTERY` (bit 6, value 64) sounds one chime (two low falling notes, 523 Hz and 392 Hz) when the first latch switches on. A second latch does not chime again. The bit clears when all latches are off. Mute, quiet hours and a cleared bit 6 of `chime_alert_mask` suppress the sound only. The mask is stored, so no chime replays later.
- `chime_alert_mask` now has the range `0` to `127` (default `127`). A card with `63` silences the new chime.
- RTC state version 29: the audio block grows to 4 bytes. The first byte holds the ventilation bit and the sensor battery alert bit. A new byte holds the three latch bits. The config trailer stores `sensor_battery_limit_pct` after `ventilate_limit_rh`. A corrupt record, a latch bit above bit 2, a flag value above 3 or a limit above 50 gives a clean start.

## Serial console command STATUS with a live sensor snapshot

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `scripts/dump-logs.sh`, `tests/test_firmware_logic.cpp`
- The new console command `STATUS` shows all sensor values on the host without a view change. It takes no argument: `STATUS now` gives `ERR UNKNOWN`.
- The reply has one line `SENSOR <index> <name> temp=<C> rh=<%RH> bat=<%> rssi=<dBm> age=<s> state=<OK|STALE|EMPTY|BATLOW>` for index `0` (onboard) and for the slots `1` to `3`. A missing value gives `--`. The name is the configured name, or `onboard` and `slot1` to `slot3`; a space in a name becomes `_`.
- The slot lines show the calibrated readings. The onboard line shows the newest hourly sample of the RTC history, so its `age` is the time since that sample; `bat` and `rssi` are `--`. `state` is `EMPTY` without a reading, `STALE` after 30 minutes without a packet (the onboard line: 90 minutes, one hourly sample interval plus 30 minutes), `BATLOW` when the `SensorBatteryLatch` bit of the slot is set, else `OK`. `STALE` has priority over `BATLOW`. The onboard `age` uses the shared hourly sample time (`ClimateHistory::last_sample_ms`): when only a slot delivered a sample in that hour, an old onboard value shows a small `age`.
- Then one line `POWER src=<USB|BAT> v=<V> pct=<%> alerts=<hex mask> muted=<0|1>` and the line `CRC32 <8 hex digits>` over all bytes before it.
- The command reads the RTC copy (`g_state`) only. It does not mount the card and does not stop the console. It runs on USB power only (`PowerManager::serialConsoleAllowed`). A short serial write ends the reply without the `CRC32` line.
- `./scripts/dump-logs.sh --status [port]` sends `STATUS`, checks the CRC-32 and prints the lines.

## Serial console command BEEP to test each alert chime

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/audio_alert.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
- The new console command `BEEP N` plays the chime of one alert once. `N` is the alert bit number `0` to `6` (`ALERT_FREEZER`, `ALERT_HUMIDITY`, `ALERT_NOISE_HIGH`, `ALERT_SENSOR_LOST`, `ALERT_CHARGE_FULL`, `ALERT_VENTILATE`, `ALERT_SENSOR_BATTERY`). A missing argument, `BEEP 7`, a non-digit and extra text give `ERR BAD_ARG`.
- The chime is `ChimeSynth::pattern` of this bit at `chime_volume_pct`. `AudioSequencer::playTest` plays it with the same path as a real alert: the amplifier, the `ES8311` and the I2S bus power down on each path, in this order.
- Mute, quiet hours and volume `0` give `ERR MUTED`; the hardware stays untouched. A failed playback gives `ERR PLAY_FAILED`.
- A cleared bit in `chime_alert_mask` does not block the test. The command does not read or change the stored alert bits, so no alert is replayed or lost.
- Reply after the playback: `OK BEEP <N> <duration ms>` (the sum of the note and pause durations of the pattern).
- The command needs no card. It runs on USB power only (`PowerManager::serialConsoleAllowed`).

## Configurable sensor stale timeout in config.ini

- **Code:** `rlcd_panel/device_config.h`, `rlcd_panel/switchbot_ble.h`, `rlcd_panel/audio_alert.h`, `rlcd_panel/sparkline.h`, `rlcd_panel/sd_logger.h`, `rlcd_panel/ventilate_latch.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
- The `config.ini` key `sensor_stale_min` (default `30`, range `5` to `240` minutes) sets the time without a packet until a sensor is stale. A value outside the range or a malformed value is rejected and counted in `ConfigRejects`.
- `DeviceConfig::staleTimeoutMs()` returns the timeout in ms. A value below `wake_interval_s` + 4 s scan time is raised to this sum, so a sensor never turns stale between two scans.
- The timeout replaces the fixed `SENSOR_STALE_TIMEOUT_MS` (still the default argument) in the `STALE` badge and `RSSI --` (and the comfort label), `ALERT_SENSOR_LOST` and `SwitchBotRegistry::anyStale` (`AlertLimits::stale_timeout_ms`), the hourly history gate, the CSV columns, `VentilateLatch`, `SensorBatteryLatch`, `ScanCompletion::fullScanRequired` and the `STALE` state of the console command `STATUS`.
- The CONFIG reply and `DeviceConfigText` list the key `sensor_stale_min`.
- RTC state version 30: the config trailer stores `sensor_stale_min` (2 bytes) after `wake_interval_s`. A corrupt or out-of-range record gives a clean start.
- Host tests cover the parser edges, rejected values, the clamp, each call site with 30 min and 60 min, the CONFIG reply and the RTC round trip.

## Inverted display mode from config.ini

- **Code:** `rlcd_panel/device_config.h`, `rlcd_panel/st7305_framebuffer.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tools/render_display_preview.cpp`, `tools/preview_scenes.h`, `scripts/generate-display-preview.sh`, `tests/test_firmware_logic.cpp`
- The `config.ini` key `display_invert` (default `0`, values `0` and `1`) selects the contrast. With `1` the panel shows light text on a dark background. A value outside `0` and `1` or a malformed value is rejected and counted in `ConfigRejects`.
- `St7305Framebuffer::invert()` inverts each of the 15,000 bytes. The firmware calls it after the render and before `St7305Panel::flush`, also for the empty battery screen. No panel command is used, so the host preview stays bit-exact. Inverted badges and banners invert again and stay readable.
- The reflective LCD has no backlight, so the mode does not change the power draw.
- The CONFIG reply and `DeviceConfigText` list the key `display_invert`.
- RTC state version 31: the config trailer stores `display_invert` (1 byte) after `sensor_battery_limit_pct`. A corrupt or out-of-range record gives a clean start.
- The preview `docs/display_preview_inverted.png` shows the inverted dashboard.
- Host tests cover the parser (`0`, `1`, out-of-range, malformed), `invert()` on each byte and the double inversion, the inverted preview scene, the CONFIG line and its parse back, and the RTC round trip.

## Tile selection for the freezer and mold alerts in config.ini

- **Code:** `rlcd_panel/device_config.h`, `rlcd_panel/audio_alert.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
- The `config.ini` key `climate_alert_tiles` (default `15`, range `0` to `15`) selects the tiles of the freezer and mold alerts. Bit 0 is the onboard sensor. Bits 1 to 3 are the registry slots. A value outside the range or a malformed value is rejected and counted in `ConfigRejects`.
- `AlertEvaluator::evaluate` reads only the tiles of the mask for `ALERT_FREEZER` and `ALERT_HUMIDITY` (`AlertLimits::climate_tiles`). A tile outside the mask counts as a tile without a usable reading, so the existing hold rule applies.
- The mask `0` clears both bits. It gives no chime.
- `ALERT_VENTILATE`, `ALERT_SENSOR_LOST`, `ALERT_SENSOR_BATTERY`, the ventilation badges and the `BAT LOW` text do not use the mask. Thus an outdoor sensor below 0 C in winter does not start the freezer alert.
- The CONFIG reply and `DeviceConfigText` list the key `climate_alert_tiles`.
- RTC state version 32: the config trailer stores `climate_alert_tiles` (1 byte) after `display_invert`. A corrupt or out-of-range record and a record of version 31 give a clean start.
- Host tests cover the parser edges, rejected values, each single tile bit, an outdoor slot outside the mask, the mask `0` with an active alert, a mask change while an alert is active, the unchanged ventilation advice, the CONFIG line and its parse back, and the RTC round trip.

## Serial console command TIME to read and set the RTC

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `scripts/dump-logs.sh`, `tests/test_firmware_logic.cpp`
- The console command `TIME` replies `TIME <YYYY-MM-DDTHH:MM:SS> valid=<0|1> osf=<0|1>` with the local time of the `PCF85063A`. `valid=0` shows an invalid or unreadable time. `osf=1` shows the oscillator stop flag.
- The command `TIME SET <YYYY-MM-DDTHH:MM:SS>` (28 bytes) writes a local time to the RTC and replies `OK TIME`. The write clears the oscillator stop flag. The firmware then reads the clock again and redraws the dashboard.
- The parser accepts the years 2025 to 2099 and a real calendar date, with the leap year rule. A bad format, an impossible date, 24:00:00 and a year outside the range give `ERR BAD_ARG`. A failed I2C write gives `ERR RTC_FAILED`. In this case the firmware does not redraw.
- A successful set clears the baseline of `RtcDrift`, as the seed from the compile stamp does. Thus the next NTP sync gives no false drift. The NTP schedule does not change.
- The commands run on USB power only (`PowerManager::serialConsoleAllowed`). They do not mount the card, so the console keeps running.
- `./scripts/dump-logs.sh --set-time [port]` sends `TIME SET` with the local time of the host and prints the result. The accuracy is about 1 s. Thus a user without Wi-Fi sets the clock for the hourly log, the quiet hours and the retention rule.
- Host tests cover the parser, 29 February in a leap year and in a common year, 24:00:00, the years 2024 and 2100, a missing `T`, extra text, the reply format with a valid and an invalid time, the cleared drift baseline, the failed write and the USB power gate.

## Serial console command HISTORY to export the 24-hour histories

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `scripts/dump-logs.sh`, `tests/test_firmware_logic.cpp`
- The console command `HISTORY` (no argument) sends the 24-hour histories from the RTC copy (`ClimateHistory`, `NoiseHistory`). `HISTORY` with an argument gives `ERR UNKNOWN`.
- The reply starts with `BEGIN HISTORY` and the header `sensor,age_h,temp_c,rh_pct,noise_db`. Each stored hour of each sensor gives one row: sensor `0` is the onboard sensor, `1` to `3` are the slots. `age_h` `0` is the newest sample.
- The noise field holds a value in the rows of sensor `0` only. A missing sample or an invalid noise hour gives an empty field. Temperatures show one decimal and can be negative.
- The reply ends with `CRC32 <8 hex digits>` over all bytes before this line. A short write stops the reply, so the host sees no `CRC32` line.
- The command does not mount the card, so the console keeps running. It runs on USB power only (`PowerManager::serialConsoleAllowed`).
- `./scripts/dump-logs.sh --history [port]` sends `HISTORY`, checks the CRC-32 and writes `sd_logs/history_<host time>.csv`. Thus the user gets the last 24 hours also without a card.
- Host tests cover the parser, the rejection of arguments, the header, a full and an empty history, an invalid noise hour, negative temperatures, the row order, the CRC-32 line, each short write, no card access, the USB power gate and the script option.

## Serial console command EVENTS to read the event ring without a card

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `scripts/dump-logs.sh`, `tests/test_firmware_logic.cpp`
- The console command `EVENTS` (no argument) sends the event ring of `PersistentState::events` from the RTC copy. `EVENTS` with an argument gives `ERR BAD_ARG`.
- The reply starts with `BEGIN EVENTS` and the header `time,event,detail`. Each event gives one row, from the oldest to the newest. `SdLogCodec::formatEventRow` writes each row, so the rows are equal to the rows of `events_v1_YYYY.csv`. An event without a valid time has an empty first column.
- A ring with lost events starts with the row from `SdLogCodec::formatEventsDroppedRow`, with the current RTC time. An empty ring gives only the header. The firmware reads the RTC again for the command. If the RTC time is invalid, the reply has no `EVENTS_DROPPED` row, as in the file.
- The reply ends with `CRC32 <8 hex digits>` over all bytes before this line. A short write stops the reply, so the host sees no `CRC32` line.
- The command does not mount the card and does not clear the ring, so the console keeps running and the next hourly write still appends the events. It runs on USB power only (`PowerManager::serialConsoleAllowed`).
- `./scripts/dump-logs.sh --events [port]` sends `EVENTS`, checks the CRC-32 and prints the rows. Thus the user reads the recent alerts and resets also without a MicroSD card.
- Host tests cover the parser, the rejection of arguments, an empty ring, one event, a full ring, lost events, an event without time, the row equality with `formatEventRow`, the CRC-32 value, each short write, the unchanged ring, no card access, the USB power gate and the script option.

## Serial console command PATTERN to show a display test pattern

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/st7305_framebuffer.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
- The console command `PATTERN N` shows one test pattern on the panel. `N` is `0`, `1` or `2`. A missing digit, `PATTERN 3`, `PATTERN -1` and extra text give `ERR BAD_ARG`.
- `St7305Framebuffer::fillPattern` draws the pattern. Pattern `0` makes each pixel black. Pattern `1` is a checkerboard of 1-pixel squares; the pixel (0,0) is black. Pattern `2` is a 1-pixel border with a cross from corner to corner. A value above `2` leaves the buffer unchanged.
- The firmware flushes the pattern with `g_panel.flush` and not with `flushFramebuffer()`. Thus `display_invert` has no effect, and the user sees the raw packing of the `ST7305`.
- The reply is `OK PATTERN <N>`. The pattern stays on the panel until the next render. The next wake cycle renders the normal view again.
- The command does not mount the card, so the console keeps running. It runs on USB power only (`PowerManager::serialConsoleAllowed`). `execute()` ignores `PATTERN`.
- Thus the user finds dead pixels, a bad row or a wrong packing after a hardware change.
- Host tests cover the parser for each pattern, the values `3` and `-1`, extra text, the bit-exact framebuffer bytes of each pattern with the `ST7305` packing rule, the corner pixels, the unchanged buffer for an invalid value, the raw flush without `display_invert`, no card access and the USB power gate.

## SwitchBot Meter and Meter Plus support from Service Data

- **Code:** `rlcd_panel/switchbot_ble.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `README.md`
- `SwitchBotBleParser::parseServiceDataReading` decodes the 6-byte Service Data (`UUID 0xFD3D`) of the SwitchBot Meter (device type `0x54`) and the Meter Plus (device type `0x69`). Byte 2 holds the battery (bits 0 to 6). Byte 3 holds the tenths of the temperature (bits 0 to 3). Byte 4 holds the sign (bit 7, 1 is positive) and the whole degrees (bits 0 to 6). Byte 5 holds the humidity (bits 0 to 6) and the unit flag (bit 7).
- The packet has no MAC address. The caller passes the BLE address, and the parser copies it into the reading.
- The parser rejects a tenth above 9, a humidity above 100 %RH, a battery above 100 %, a length other than 6, a wrong UUID and other device types. A rejected packet leaves an invalid reading. The value 0.0 °C is always positive, with both sign values.
- Manufacturer Data has priority. When an advertisement holds valid Manufacturer Data (`WoIOSensor`), the firmware keeps this path and takes only the battery from the Service Data. The Service Data path runs only when the Manufacturer Data is missing or invalid.
- The new sensors use the same registry slots, fixed MAC rule, offsets, latches and stale rule as the other sensors.
- Host tests cover `parseAdvertisement` (Manufacturer Data wins, Service Data gives the battery, fallback to Service Data), a Meter packet at 21.5 °C and 48 %RH, a negative temperature, 0.0 °C with both signs, a Meter Plus packet, the battery value, a tenth of 10, a humidity of 101, a battery of 101 %, an unknown device type, a short and a long payload, a wrong UUID, a `WoIOSensor` packet with both data types, the MAC from the BLE address, and the registry upsert with a fixed MAC.

## Host tool to check a config.ini file

- **Code:** `tools/check_config.cpp`, `scripts/check-config.sh`, `tools/sample_config.ini`, `rlcd_panel/device_config.h`, `.github/workflows/ci.yml`, `README.md`, `tests/test_firmware_logic.cpp`
- `./scripts/check-config.sh <file>` builds `tools/check_config.cpp` with `-std=c++17 -Wall -Wextra -Werror` in a temporary directory and runs it. The tool needs no Arduino header.
- `DeviceConfigCheck::run` runs `DeviceConfigParser` with the firmware rules. It writes the accepted values with `DeviceConfigText::format`. Then it writes one line `REJECTED line=<N>` for each recorded line (the first four) and the line `REJECTED count=<N>`.
- The exit code is `0` for a file without rejected lines. It is `1` for rejected lines. It is `2` for a missing file or a file above 8192 bytes. In the case of exit code `2` the tool writes one `ERROR` line to stderr and no values.
- The CI job `host-tests` runs the script on `tools/sample_config.ini`. Thus the user finds a bad line on the host and not after a reboot of the panel.
- Host tests cover a valid file, a malformed value, an out-of-range value, more than four rejected lines, an empty file, a file at the limit and a file above 8192 bytes, a report buffer that is too small, and the exit codes 0, 1 and 2.

## Xiaomi thermometer support with ATC and pvvx firmware

- **Code:** `rlcd_panel/atc_ble.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `README.md`, `AGENTS.md`
- `AtcBleParser` (`rlcd_panel/atc_ble.h`) decodes Service Data (`UUID 0x181A`) of Xiaomi thermometers, for example the `LYWSD03MMC`, with ATC or pvvx custom firmware.
- The length of the payload selects the format. A payload of 13 bytes is ATC: MAC, temperature (`int16`, 0.1 °C), humidity (`uint8`, %), battery (%), battery in mV and counter, all big-endian. A payload of 15 bytes is pvvx: MAC, temperature (`int16`, 0.01 °C), humidity (`uint16`, 0.01 %), battery in mV, battery (%), counter and flags, all little-endian.
- The reading stores the MAC in big-endian order for both formats.
- The parser rejects other lengths, a temperature outside -50 to 100 °C, a humidity above 100 %RH, a battery above 100 %, a wrong UUID, a null payload and an all-zero MAC. A rejected packet leaves an invalid reading.
- The scan callback in `rlcd_panel.ino` processes each advertisement with `UUID 0x181A` like a BTHome advertisement. The firmware still reads only the first Service Data entry of an advertisement.
- The UUID `0x181A` is generic. Another device with a 13-byte or 15-byte payload in range can take a free slot. Fix a slot to a MAC in `config.ini` to prevent this.
- The new sensors use the same registry slots, fixed MAC rule, offsets, latches and stale rule as the other sensors.
- Host tests cover an ATC packet and a pvvx packet, negative temperatures in both formats, the byte order of the MAC, the lengths 12, 14 and 16, the limits of temperature, humidity and battery, and the registry upsert with a fixed MAC.

## MicroSD card status in the diagnostics view and in STATUS

- **Code:** `rlcd_panel/sd_logger.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
- `SdStatus` has the values `UNKNOWN`, `OK`, `NO_CARD`, `MOUNT_FAILED`, `WRITE_FAILED` and `FULL`. `SdStatusCodec` converts the logger results to a status.
- The wake cycle sets the status after each hourly write and after each config load. A write that fails with 0 MB free space gives `FULL`. A config load keeps a stored `WRITE_FAILED` or `FULL`, because a read does not prove a write. A short read of `config.ini` gives `MOUNT_FAILED`.
- `PersistentState` stores the status (1 byte) and the clock stamp of the last result (4 bytes). `STATE_VERSION` is 33. A wake cycle without card access keeps the stored status. A corrupt status byte rejects the state.
- The diagnostics view keeps `SD FREE: NNN MB` while the status is `OK` or `UNKNOWN`. For another status it shows `SD: NO CARD`, `SD: MOUNT ERR`, `SD: WRITE ERR` or `SD: FULL` in the same place, left of the L10/L90 text.
- The console command `STATUS` sends the line `SD status=<VALUE> age=<s>` before the `POWER` line. The age is `--` while the status is `UNKNOWN`.
- Thus the user sees a missing or full card before hours of log data are lost.
- Host tests cover each status from the logger results, the 0 MB case, the line text and bounds of each status, the unchanged `SD FREE` line for `OK` and `UNKNOWN`, the `STATUS` line for each value, the RTC round trip, a corrupt status byte and the old state version 32.

## Hours above the mold limit in the sensor stats view

- **Code:** `rlcd_panel/sparkline.h`, `rlcd_panel/dashboard_view.h`, `tests/test_firmware_logic.cpp`
- `HumidityHistory::countAbove(limit_tenths)` counts the stored hourly samples that are strictly above the limit. A sample at the limit does not count.
- Each sensor block of the stats view shows `MOLD <N>H` in scale-1 text, right-aligned to x = 392, on the `MAC` line (`y + 21`). The limit is `mold_alert_limit_rh`, converted to 0.1 %RH steps.
- A slot with fewer than 2 samples, an empty slot and a stale slot show `MOLD --`. A packet that the BLE task stamps up to 65 s after the render time counts as live.
- The text stays right of the widest `MAC` text and does not touch the `COMFORT` label.
- The count is calculated for each render. No RTC state changes and `STATE_VERSION` stays 33.
- Thus the user sees how long a room was at risk of mold in the last day.
- Host tests cover 0, 1 and 24 hours above the limit, a sample at the exact limit, a limit change, fewer than 2 samples, an empty slot, a stale slot, and the text bounds in each block.

## Battery voltage calibration offset in config.ini

- **Code:** `rlcd_panel/battery_monitor.h`, `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
- The `config.ini` key `battery_offset_mv` has the default `0` and the range `-300` to `300` mV. A malformed or out-of-range value is rejected and counts in `ConfigRejects`.
- `BatteryMonitor::decodeMillivolts` adds the offset to the cell voltage. Thus the percentage, the low battery alert, the empty protection, the charge full latch, the USB voltage limit of 4.25 V, the runtime history and the cycle counter use the corrected voltage.
- The offset corrects the tolerance of the 3.0 divider and of the ADC.
- `PersistentState` stores the offset in the config trailer (2 bytes, `int16_t`). `STATE_VERSION` is 34.
- A power-on reads the cell before the card mounts. This first check uses the offset of the RTC copy, or `0` after a clean start.
- The console command `CONFIG` shows the key `battery_offset_mv`.
- Thus the user can correct a battery reading that is wrong by a fixed voltage.
- Host tests cover the parser for the default, the edges, the values `-301` and `301` and a malformed value, the percentage with `+100` and `-100` mV, the low battery, empty protection, USB and charge full limits with an offset, the `CONFIG` line and its parse back, the RTC round trip, a corrupt field and the old state version 33.

## Commented config.ini template with a sync test

- **Code:** `docs/config.example.ini`, `rlcd_panel/device_config.h`, `tests/test_firmware_logic.cpp`.
- `docs/config.example.ini` lists each key of `DeviceConfigParser` once. Each key has one comment line with its range. Keys with a default value are active lines. The names (`onboard_name`, `slot1_name` to `slot3_name`) and the fixed MACs (`slot1_mac` to `slot3_mac`) have no default value, so they are comment lines only. The file has LF line ends and is below 8192 bytes. `README.md` links to it.
- `DeviceConfigParser::KEYS` is the table of all 35 keys. `KEY_COUNT` is its size.
- The host test `testConfigExampleTemplateInSync` reads the file. It checks that the parse gives no rejected line, that the result equals the defaults of `DeviceConfig`, and that each key of `KEYS` appears in the file. It also checks that the parser knows each key of `KEYS`.
- The test fails when a key is missing in the file, when a value is not the default, when the file has more than 8192 bytes, and when a line is rejected. A new key in the parser needs an entry in `KEYS` and in the template.

## Automatic release of a dead sensor slot

- **Code:** `rlcd_panel/switchbot_ble.h`, `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `docs/config.example.ini`, `tests/test_firmware_logic.cpp`
- The `config.ini` key `slot_release_h` has the default `0` (off) and the range `0` to `168` hours. A malformed or out-of-range value is rejected and counts in `ConfigRejects`.
- `PersistentState::releaseDeadSlots` runs once in each wake cycle, before the BLE scan. A slot without a fixed MAC and without a packet for `slot_release_h` hours or longer is released. The 32-bit clock wrap does not change the result.
- A released slot loses its reading, its two 24-hour histories, its ventilation latch bit and its battery latch bit (`PersistentState::resetSlot`). The free slot takes the next new sensor.
- A slot with a fixed MAC never releases. The key `0` never releases.
- A release time below `sensor_stale_min` releases the slot before it turns stale. Then `ALERT_SENSOR_LOST` does not fire for this slot.
- The release clears `ALERT_SENSOR_LOST` for this slot. No chime sounds, because the chime plays only for a new alert.
- `PersistentState` stores the key in the config trailer (1 byte, after `battery_offset_mv`). `STATE_VERSION` is 35. A corrupt field and a record of version 34 give a clean start.
- The console command `CONFIG` and `DeviceConfigText` list the key `slot_release_h`. `docs/config.example.ini` and `DeviceConfigParser::KEYS` list it too.
- Thus a removed sensor does not block a slot and does not keep the sensor lost alert until the next power-on.
- Host tests cover the parser for the default, the edges `0` and `168`, the value `169` and a malformed value, the release at the exact time and over the clock wrap, no release before the time, no release with the key `0`, no release of a fixed MAC slot, the cleared histories and latches, the cleared `ALERT_SENSOR_LOST` bit, a new sensor in the released slot, the `CONFIG` line and its parse back, and the RTC round trip with a corrupt field and the old state version 34.

## Serial console command VERSION with the build identity

- **Code:** `rlcd_panel/firmware_version.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `scripts/flash-firmware.sh`.
- `rlcd_panel/firmware_version.h` holds the constant `FIRMWARE_VERSION` (`1.0.0`), the constant `ARDUINO_CORE_VERSION` (`3.3.12`) and the macro `RLCD_GIT_HASH`. The macro has the default `unknown`.
- `scripts/flash-firmware.sh` passes the short Git hash to the compiler with `--build-property`. It adds `-dirty` when `git status --porcelain` shows a change. A build without the property, for example in CI, shows `git=unknown`.
- The console command `VERSION` takes no argument. A command with an argument gives `ERR UNKNOWN`.
- The reply is `VERSION fw=<version> git=<hash> core=3.3.12 state=<STATE_VERSION> built=<YYYY-MM-DDTHH:MM:SS>`, then `CRC32 <8 hex digits>` over the `VERSION` line.
- The build time comes from `__DATE__` and `__TIME__` through `Pcf85063Codec::fromCompileStamp`. An unreadable stamp gives `built=unknown`.
- The command runs on USB power only. It does not mount the card, so the console keeps running.
- Thus the user knows which firmware runs on the panel.
- Host tests cover the parser, the rejection of an argument, the reply format, the default hash, the build time from a fixed compile stamp, the CRC-32 line, a short write and the USB power gate.

## Longer wake interval during the quiet hours on battery power

- **Code:** `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `docs/config.example.ini`, `tests/test_firmware_logic.cpp`
- The `config.ini` key `night_wake_interval_s` has the default `0` (off). The range is `0` or `60` to `1800` seconds. A malformed or out-of-range value (for example `59`, `1801`, a negative value or text) is rejected and counts in `ConfigRejects`.
- `PowerManager::powerConfig` takes the quiet hours window and the RTC time as extra arguments. On battery power, while `QuietHoursWindow::isActive()` is true and the key is more than `0`, the key replaces `sleep_interval_s`. The window can cross midnight.
- USB power keeps `10` s. An invalid RTC time or a stopped oscillator counts as no quiet hours, so `wake_interval_s` applies.
- The interval moves the wakeup timer only. The hourly log, the histories and the NTP schedule use RTC stamps, so they stay correct.
- `DeviceConfig::staleTimeoutMs()` uses the larger of `wake_interval_s` and `night_wake_interval_s` plus the scan time for its lower bound. A sensor does not turn stale during the night.
- `PersistentState` stores the key in the config trailer (2 bytes, little endian, after `slot_release_h`). `STATE_VERSION` is 36. A corrupt field and a record of version 35 give a clean start.
- The console command `CONFIG` and `DeviceConfigText` list the key. `docs/config.example.ini` and `DeviceConfigParser::KEYS` list it too.
- Thus the battery lasts longer, because the panel scans less often while the user sleeps.
- Host tests cover the parser for the default, the edges `60` and `1800`, the values `59` and `1801` and a malformed value, the interval inside and outside the quiet hours, a window across midnight, the USB power gate, an invalid RTC time, the stale clamp with both intervals, the `CONFIG` line and its parse back, and the RTC round trip with a corrupt field and the old state version 35.

## Reset reason and abnormal reset counter in STATUS

- **Code:** `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`.
- `ResetReasonCodec` maps `esp_reset_reason()` to the text `POWERON`, `EXT`, `SW`, `PANIC`, `INT_WDT`, `TASK_WDT`, `WDT`, `DEEPSLEEP`, `BROWNOUT`, `SDIO` or `UNKNOWN`. Each other value, for example `USB` or `PWR_GLITCH`, gives `UNKNOWN` and counts as normal.
- A reset by `PANIC`, `INT_WDT`, `TASK_WDT`, `WDT` or `BROWNOUT` is abnormal.
- `AbnormalResetCounter::onBoot` counts abnormal resets in NVS (`Preferences`, namespace `rlcd`, key `abn_resets`, `uint16_t`). The count saturates at 65535 and then causes no write.
- `setup()` calls `onBoot` once. NVS is written only after an abnormal reset. A deep sleep wakeup or a power-on causes no flash write.
- The console command `STATUS` adds the line `RESET reason=<TEXT> abnormal=<N>` before the `POWER` line. The line shows `abnormal=--` when NVS is unreadable.
- Thus the user sees a brownout of a weak cell or a crash of the firmware.
- Host tests cover each reason text, the abnormal classification, the counter with a portable NVS stand-in (0 writes after 10 deep sleep wakeups, 1 write after one panic), the saturation, an unreadable NVS, a failed write and the `STATUS` line.

## Event log file on the MicroSD card

- **Code:** `rlcd_panel/sd_logger.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `scripts/dump-logs.sh`, `tests/test_firmware_logic.cpp`
- `EventRing` holds the last 16 events in `PersistentState`. An event has the local RTC time, a code (`EventCode`) and a 16-bit detail. An invalid RTC time or a stopped oscillator gives an event without a time, and the time column stays empty.
- The firmware records an event for: each rising edge (`ALERT_RISE`) and falling edge (`ALERT_FALL`) of an alert bit (detail: the alert bit), each config load from the card (`CONFIG_LOAD`, detail: count of rejected lines), each NTP sync result (`NTP_SYNC`, detail: `NtpResult`, `0` is OK), the set and the release of the low battery alert (`BATTERY_LOW_SET`, `BATTERY_LOW_CLEAR`, detail: cell percentage) and the set and the release of the empty protection (`EMPTY_PROTECTION`, detail `1` or `0`).
- The hourly write calls `SdLogger::appendEvents` while the card is mounted. The function appends the events to `/sdcard/events_v1_YYYY.csv` (year of the RTC time) and the firmware then clears the ring. A new file gets the header `time,event,detail` first. A row has the format `YYYY-MM-DD hh:mm:ss,NAME,detail`.
- A full ring drops the oldest event and counts the loss. The next write starts with the row `<time>,EVENTS_DROPPED,<N>`. The time of this summary row is the time of the write. The set of the empty protection has an empty time column, because the RTC is not read yet at that point.
- A failed write keeps the ring (`EventFlush::apply` clears it after `OK` only). The next hourly write repeats it. A short write to an existing file can leave part of the rows, so a retry can repeat rows; the `File` class cannot truncate. A card that does not mount for the hourly write skips the event write.
- `LIST` and `DUMP events_v1_YYYY.csv` accept the file (`SdLogCodec::isConsoleFileName`). `scripts/dump-logs.sh` copies it. The retention rule treats the file like the daily file: it counts as December of its year.
- `PersistentState` stores the ring before the checksum (3 bytes plus 16 entries of 11 bytes). `STATE_VERSION` is 37. A corrupt ring (bad count, code, date or time, dirty unused entry, dropped count without a full ring) and a record of version 36 give a clean start.
- Thus the user sees when an alert started and ended.
- Host tests cover the ring with 1, 16 and 17 events, the dropped row, each event code, the CSV header and rows, a failed write with a retry, an invalid RTC time, the file name check of the console, the retention rule and the RTC round trip with a corrupt ring.

## Fahrenheit display unit in config.ini

- **Code:** `rlcd_panel/device_config.h`, `rlcd_panel/climate_math.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `docs/config.example.ini`, `tests/test_firmware_logic.cpp`
- The `config.ini` key `temp_unit` has the default `C`. The values are `C` and `F`. Each other value (for example `f`, `K`, `FF` or an empty value) is rejected and counts in `ConfigRejects`. A rejected line keeps the earlier value.
- With `F`, these texts show the temperature in Fahrenheit with the glyph `F`: the temperature line of each tile (`T: 70.7 F` for 21.5 C), the dew point line (`DP 48.7F VPD 1.2K`), the 24-hour record line (`24H T5.0 TO 41.0F H41-63`) and the `temp=` fields of the console command `STATUS` (`temp=72.1F`).
- `ClimateMath::toDisplay(temp_c, unit)` converts a value. The unit `F` gives `temp_c * 9 / 5 + 32`. Each other unit keeps Celsius.
- The MicroSD files, the offsets and limits of `config.ini`, the alerts, the trend limits and the comfort limits stay in Celsius. The screen converts the value only when it draws the text.
- `PersistentState` stores the unit as 1 byte in the config trailer, after `night_wake_interval_s`. `STATE_VERSION` is 38. A stored unit other than `C` and `F` and a record of version 37 give a clean start.
- The `CONFIG` reply shows the line `temp_unit=C` or `temp_unit=F`. `docs/config.example.ini` lists the key.
- Thus a user who reads Fahrenheit can use the panel.
- Host tests cover the parser for `C`, `F`, `f`, `K`, an empty value and a longer text, the conversion at -40 C, 0 C, 37 C and 100 C, the text bounds of the widest Fahrenheit values in a tile, in the dew point line and in the record line, the unchanged CSV row, the `CONFIG` line, the `STATUS` temperatures, and the RTC round trip with corrupt unit bytes. The default preview scenes stay bit-exact.

## 24-hour climate chart view for one sensor

- **Code:** `rlcd_panel/button_handler.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/sparkline.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`, `tests/test_firmware_logic.cpp`
- `DisplayMode::CLIMATE_CHART` (value 4) follows `AUDIO_SPECTRUM`, and `DISPLAY_MODE_COUNT` is 5. A stored view mode above 4 gives a clean start.
- The view shows the name of one sensor (the configured name, else `ONBOARD SHTC3` or `SENSOR n`). It shows a 360 x 90 px temperature curve and a 360 x 90 px humidity curve of the last 24 hours. Each curve has a label with its minimum and maximum. The temperature follows `temp_unit`.
- `Sparkline::renderTemperature` draws the temperature curve. The minimum span is 2.0 °C (`TEMPERATURE_MIN_SPAN`), centered on the value and limited to -100 to +100 °C. This prevents a flat room from filling the box with sensor noise.
- A short `KEY` press returns `ButtonEvent::NEXT_SENSOR` and selects the next sensor: onboard, slot 1, slot 2, slot 3, then onboard again. The mode stays. A hold of 2 s changes the view, as in the spectrum view. The press that wakes the board starts no sensor change.
- `PersistentState::chart_sensor` stores the sensor as 1 byte after the mode byte. A sensor above 3 gives a clean start. `STATE_VERSION` is 39. A record of version 38 gives a clean start.
- A sensor with fewer than 2 samples shows `NO DATA` in the box of the curve.
- The view reads only the RTC histories. It needs no sensor read and no microphone, so a `KEY` press redraws the view at once.
- `tools/preview_scenes.h` adds the scene `display_preview_chart` (garden sensor). `./scripts/generate-display-preview.sh` writes `docs/display_preview_chart.png`. The other preview files stay bit-exact.
- Thus the user sees the course of one room in detail.
- Host tests cover the mode cycle with 5 modes, the rejection of a stored mode above 4 and of a stored sensor above 3, the `KEY` handling (short press, hold, wake press), the sensor wrap, the curve bounds, the minimum span, `NO DATA` and the RTC round trip with the new state version.

## Seven-day minimum and maximum chart view

- **Code:** `rlcd_panel/sparkline.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/button_handler.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`, `scripts/generate-display-preview.sh`, `tests/test_firmware_logic.cpp`
- `WeekHistory` (`sparkline.h`) keeps the daily minimum and maximum of the last 7 days for each of the 6 sensors (onboard and 5 slots). A day is 4 bytes: temperature minimum and maximum (`int8` in 0.5 C steps with bias 128) and humidity minimum and maximum (`uint8` in 0.5 %RH steps). The marker `0xFF` in both bytes of a pair is a day without samples and shows a gap. The block is 173 bytes (168 bytes plus the day stamp) in `PersistentState::week`.
- At the first hourly write after local midnight (`logWeekSummary` in `rlcd_panel.ino`) the firmware shifts the ring and stores the previous day from the 24-hour histories of each sensor. A missed day gives a gap. A stale history, a dead slot (no reading in the last 24 hours, `WeekHistory::liveSensors`) or a sensor without samples stores gaps. The first valid RTC time only stamps the date. An invalid RTC time (invalid, stopped oscillator, year before 2025, impossible date) skips the shift. A clock that ran back by up to 7 days changes nothing; a larger step back (a wrong date that a sync corrected) only takes the new stamp and keeps the bars. The render of the wake cycle runs before the step, so the view shows the new day one wake cycle later. The step needs no card.
- `DisplayMode::WEEK_CHART` (value 5) follows `CLIMATE_CHART`, and `DISPLAY_MODE_COUNT` is 6. A stored view mode above 5 gives a clean start.
- The view shows the name of the `chart_sensor` sensor, a temperature box and a humidity box (360 x 90 px each) with 7 vertical bars from the daily minimum to the daily maximum, the newest day at the right edge, and the weekday labels from the RTC below the bars. Each box has a label with the minimum and maximum of the week; the temperature follows `temp_unit`. A flat week uses the minimum span 2.0 C or 5.0 %RH. A box without a valid day shows `NO DATA`.
- The `KEY` handling equals the climate chart: a short press returns `ButtonEvent::NEXT_SENSOR` and selects the next sensor, a hold of 2 s changes the view (`MODE_CHANGED`).
- The view reads only the RTC copy. It needs no sensor read, no microphone and no card.
- `STATE_VERSION` was 56 here. A record of version 55 gives a clean start.
- `tools/preview_scenes.h` adds the scene `display_preview_chart_week` (sensor 1 with one day without samples). `./scripts/generate-display-preview.sh` writes `docs/display_preview_chart_week.png`.
- Thus the user sees the course of one room over a week.

## Serial console command SET to change one config.ini key

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/device_config.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
- The console command `SET <key>=<value>` changes one key of `/sdcard/config.ini`. The line limit is 40 bytes. The command runs on USB power only, as each console command does.
- The parser accepts only a key of `DeviceConfigParser::KEYS` (exact match, lowercase). `DeviceConfigParser::checkSetting` checks the value with the parser rules of `config.ini`. A bad key or a missing `=` gives `ERR BAD_KEY`. A bad value gives `ERR BAD_VALUE`. Both errors leave the card untouched.
- `DeviceConfigEditor::set` replaces the last line of the key. The line end of that line stays; its indent and its spaces around `=` do not. Without such a line, the editor appends `key=value` in the line end style of the file (LF for an empty file). Each other line, each comment and the byte order mark stay unchanged.
- A result above 8192 bytes, or an existing file above 8192 bytes, gives `ERR TOO_LARGE`. The command writes nothing in this case.
- The runner (`SerialConsoleRunner::setConfig`) writes the result to `/sdcard/config.tmp`, removes the old file and renames the new file. A failed write or a failed remove keeps the old file and removes `config.tmp`. A failed rename writes the old text back. If this restore also fails, `config.tmp` stays and holds the new text. Each failed step gives `ERR WRITE_FAILED`. No card gives `ERR NO_CARD`. A power loss between the remove and the rename can lose the file; `config.tmp` then holds the new text.
- After a success the firmware parses the new text and calls `PersistentState::applyConfig`, as a `KEY` wakeup does. It also sets the card status to `OK`, records the event `CONFIG_LOAD` and recalculates the power settings. Then the reply is `OK SET <key>`.
- The `RTC` state layout does not change.
- Thus the user changes a setting without a card removal.
- Host tests cover the parser (valid pairs, unknown key, bad value, the 40-byte limit), the editor (replace, append, comments, CRLF, byte order mark, 8192-byte limit), each failing step of the file write, the empty file and the absent file, the applied config and the reply, and the ignore in `execute`.

## Feels-like temperature in the sensor stats view

- **Code:** `rlcd_panel/climate_math.h`, `rlcd_panel/dashboard_view.h`, `tests/test_firmware_logic.cpp`.
- `ClimateMath::heatIndexC(temp_c, rh_pct)` returns the heat index in Celsius. It uses the NOAA Rothfusz regression in Fahrenheit and its two NOAA corrections: below 13 %RH with 80 to 112 F it subtracts, above 85 %RH with 80 to 87 F it adds. Below 26.7 C or below 40 %RH it returns the air temperature. NaN, infinite and out-of-range input (limits of `ClimateMath::compute`, humidity 0 to 100 %RH) gives NaN.
- Each sensor block of the stats view shows `FEELS <T>C` in scale-1 text, right-aligned to x = 392, on the first line (`y + 3`). The text follows `temp_unit` (`FEELS 105F`). An empty slot, a stale slot and an invalid result show `FEELS --`. With a 12-glyph name the text keeps a gap of at least 4 px to the `RSSI` text.
- The `COMFORT --` label of an empty slot moved from `y + 3` to `y + 11` (`COMFORT_EMPTY_Y_OFFSET`), so it does not touch the `FEELS` text.
- The value is calculated for each render. No RTC state changes and the state version stays 39.
- `docs/display_preview_stats.png` shows `FEELS 19C`, `FEELS 14C` and `FEELS --`.
- Thus the user sees the heat stress in a warm room.
- Host tests cover the NOAA table values (for example 32 C and 70 %RH), the limits 26.7 C and 40 %RH, both corrections, NaN, infinite and out-of-range input, the text in Celsius and Fahrenheit, the dashes for a stale and an empty slot, and the text bounds with a 12-glyph name.

## Chime reminder for an alert that stays active

- **Code:** `rlcd_panel/audio_alert.h`, `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `docs/config.example.ini`, `tests/test_firmware_logic.cpp`
- The `config.ini` key `chime_repeat_h` has the default `0` (off) and the range `0` to `24` hours. A malformed or out-of-range value (for example `25`) is rejected and counts in `ConfigRejects`.
- When an alert bit stays active for `chime_repeat_h` hours after its last chime, the chime of the lowest active bit sounds again. `AlertEvaluator::repeat` makes this decision once in each wake cycle. The unsigned clock difference is correct over the 32-bit clock wrap.
- `ALERT_CHARGE_FULL` never repeats.
- Mute, quiet hours and a cleared bit of `chime_alert_mask` suppress the repeat. A suppressed repeat is skipped and not delayed: the stamp moves to the current time, so no chime plays at the end of the quiet hours.
- A new rising edge of an alert bit resets the timer, also when the first chime is suppressed.
- `PersistentState::last_chime_ms` (4 bytes, before the audio flags byte) stores the clock stamp. The key uses 1 byte in the config trailer, after `temp_unit`. `STATE_VERSION` is 40. A corrupt field and a record of version 39 give a clean start.
- The `CONFIG` reply shows the line `chime_repeat_h=<N>`. `docs/config.example.ini` and `DeviceConfigParser::KEYS` list the key (`KEY_COUNT` is 37).
- Thus the user does not forget an open window or a freezer that is too warm.
- Host tests cover the parser for the default, the edges `0` and `24`, the value `25` and malformed values, a repeat at the exact time and over the clock wrap, no repeat for `ALERT_CHARGE_FULL`, the lowest active bit, the suppression by mute, quiet hours and a cleared mask bit (with the stamp advance), the reset by a rising edge, the `CONFIG` reply and the `RTC` round trip with the new `STATE_VERSION`.

## Onboard SHTC3 read failure counter in STATUS and the diagnostics view

- **Code:** `rlcd_panel/shtc3_sensor.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`, `tests/test_firmware_logic.cpp`
- `Shtc3Codec::classify` gives the cause of each read: `OK`, `NACK` (an I2C command without an acknowledgement), `SHORT` (fewer than 6 bytes) or `CRC` (a CRC-8 mismatch in the frame). `readOnboardShtc3` returns this result. The bus sequence does not change.
- `Shtc3Health` counts the failed reads by cause. Each counter stops at 65535. `run` counts the failures in a row (it stops at 255) and a good read sets it to 0.
- `PersistentState::shtc3` stores 7 bytes just before the checksum: three `uint16_t` counters and one `uint8_t` run. `STATE_VERSION` is 41. A power-on sets the counters to 0. A corrupt field, a run above the sum of the counters and a record of version 40 give a clean start.
- The console command `STATUS` shows the line `SHTC3 nack=<N> short=<N> crc=<N> run=<N>` before the `POWER` line.
- The diagnostics view shows `SHTC3 ERR: <N>` (the sum of the three counters) at x = 190, y = 44. The text is inverted while `run` is 3 or more. The widest text ends at x = 394.
- `docs/display_preview_diagnostics.png` shows `SHTC3 ERR: 3`.
- Thus the user finds a loose sensor or a bad bus before the onboard tile shows wrong values.
- Host tests cover the cause of each failed read (a missing acknowledgement, a short frame, a bad temperature CRC and a bad humidity CRC) and a valid frame, the saturation at 65535 and at run 255, the reset of `run` after a good read, the `STATUS` line and its position, the inverted text at `run` 3, and the RTC round trip with a corrupt field and the old state version.

## Retry of a failed onboard SHTC3 read with a soft reset

- A failed onboard `SHTC3` read (`NACK`, `SHORT` or `CRC`) no longer gives an empty tile for a full wake cycle. `readOnboardShtc3` in `rlcd_panel/rlcd_panel.ino` sends the soft reset command `0x805D`, waits 1 ms and reads one more time. The retry adds at most 17 ms of awake time on a responsive bus. A sensor that is absent for good adds one retry to each wake cycle and one `retried` count. The I2C order of the retry (soft reset after the first sleep command, then wake, measure and sleep again) has no host test; the hardware proves it.
- `Shtc3Codec::shouldRetry(result, attempt)` is true only for a failed first attempt (`attempt` 0), so there is a maximum of one retry. `readShtc3WithRetry` in `rlcd_panel/shtc3_sensor.h` holds the flow, so the host tests run it.
- `Shtc3Health::record` counts only the final result of the wake cycle. The new counter `retried` (`uint16_t`, saturates at 65535) counts each retry, also a retry that succeeds.
- `PersistentState::shtc3` stores 9 bytes just before the checksum: three `uint16_t` counters, one `uint8_t` run and the `uint16_t` `retried`. `STATE_VERSION` is 58. A power-on sets `retried` to 0. An RTC record of the old size is rejected.
- The console command `STATUS` shows `SHTC3 nack=<N> short=<N> crc=<N> run=<N> retried=<N>[ usb_offset=<x>]`.
- `Shtc3Stats` in `Diagnostics.swift` parses `retried`. A missing field gives `nil` (old firmware) and a non-numeric value throws `ParseError.badLine`. `DiagnosticsView` shows the count.
- Tests: `testShtc3RetryDecision`, `testShtc3RetryFlow`, `testShtc3RetriedRtcRoundTrip` and `testShtc3StatusLine` in `tests/test_firmware_logic.cpp`; `shtc3RetriedField` in `DiagnosticsTests.swift`.

## Absolute humidity line in the sensor stats view

- **Code:** `rlcd_panel/dashboard_view.h`, `tests/test_firmware_logic.cpp`.
- `DashboardView::formatAbsHumidity` gives `AH <N.N>G` with `ClimateMath::absoluteHumidityGm3` of the slot (1 decimal, g/m3).
- Each sensor block of the stats view shows the text in scale 1, right-aligned to x = 392, at `y + 54` (`ABS_HUMIDITY_Y_OFFSET`), on the record line. An empty slot, a stale slot and an invalid result show `AH --`.
- The widest record text ends at x = 370. The `AH` text starts at x = 338 or later. If the gap to the record text is less than 4 px (`AH_GAP_PX`), the firmware does not draw the `AH` text. The record text stays complete in Celsius and in Fahrenheit.
- The value is calculated for each render. No RTC state changes and the state version stays 41.
- `docs/display_preview_stats.png` shows `AH 9.0G`, `AH 8.1G` and `AH --`.
- Thus the user compares the indoor and the outdoor air moisture without the ventilation advice.
- Host tests cover 20 C and 50 %RH (8.6 g/m3), 0 %RH, a NaN reading, a stale slot, an empty slot, a future time stamp, the right edge at x = 392, and the record line in Celsius and in Fahrenheit at its widest text.

## Rejected BLE packet counter in STATUS

- **Code:** `rlcd_panel/switchbot_ble.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
- `SwitchBotRegistry::upsert(reading, BleFormat)` counts each valid packet that the registry rejects. The registry rejects a packet when no slot is free, or when each free slot has a fixed MAC of a different sensor. The format is `WOIO`, `METER`, `BTHOME` or `ATC`.
- A packet for a known MAC or for a slot that is fixed to this MAC is not a reject and is not counted. A reject does not change the slots.
- `BleRejectStats` holds one `uint16_t` counter for each format and the MAC of the last rejected packet. Each counter stops at 65535.
- `SwitchBotRegistry::resetRejects()` clears the counters at the start of each scan (`scanOnce()`). `PersistentState` does not store them, so `STATE_VERSION` stays 41.
- The console command `STATUS` shows the line `BLE rejected=<N> last=<MAC|--> woio=<N> meter=<N> bthome=<N> atc=<N>` before the `POWER` line. `<N>` after `rejected=` is the sum of the four counters. `last` has the format `AA:BB:CC:DD:EE:FF`, or `--` without a reject.
- Thus the user finds a wrong `slotN_mac` entry or a fourth sensor that takes no slot.
- Host tests cover a full registry with a new MAC, a fixed slot with a different MAC, a fixed slot with the correct MAC (no count), each format counter, the saturation of each counter at 65535, the reset at the scan start, the last MAC, the `STATUS` line with and without a reject, the longest line, and the unchanged slot contents.

## Battery runtime line in STATUS

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
- The console command `STATUS` shows the new line `RUNTIME h=<N|--> samples=<N> cycles=<N.N|--> scan_saved_ms=<N>` after the `POWER` line and before the `CRC32` line. The `CRC32` value covers the new line.
- `h` is the remaining runtime in whole hours from `BatteryHistory::estimate`, with the same scan time saving as the diagnostics view. `h` is `--` on USB power, without a battery, with fewer than 6 samples, and with a flat or rising curve.
- `samples` is `BatteryHistory::size()`. `cycles` is `CycleTracker::cycleTenths()` with one decimal. `cycles` is `--` when the NVS total is unknown. `scan_saved_ms` is the value of `g_scan_saved_ms`.
- The line reads the RTC copy and the NVS total only. It does not record a sample and does not change `BatteryHistory`. `PersistentState` does not change, so `STATE_VERSION` stays 41.
- The console runs on USB power only and the estimate is invalid on USB power, so `h` shows `--` in practice, as `RUNTIME: --` does in the diagnostics view. `samples`, `cycles` and `scan_saved_ms` stay valid.
- Thus the user logs the battery state on the host without a look at the display.
- Host tests cover a valid runtime, a runtime with a saved scan time, too few samples, a flat slope, USB power, a missing history, an unknown cycle count, the position of the line after `POWER` and before `CRC32`, the fixed CRC-32 value, and an unchanged `BatteryHistory` after the command.

## Heat alert chime for a high feels-like temperature

- **Code:** `rlcd_panel/audio_alert.h`, `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `docs/config.example.ini`, `tests/test_firmware_logic.cpp`.
- The alert bit `ALERT_HEAT` (bit 7, value 128) is new. The `config.ini` key `heat_alert_limit_c` (0 is off, else 27 to 45 C, default 0) sets the limit. The parser rejects 26, 46 and a malformed value.
- `AlertEvaluator` sets the bit when `ClimateMath::heatIndexC` of a tile in `climate_alert_tiles` is at or above the limit. It releases the bit when each such tile is 2 C below the limit, or has no usable reading. A stale tile and a tile outside the mask do not count.
- `MAX_CHIME_ALERT_MASK` and the default `chime_alert_mask` are 255. Bit 7 of the mask switches the chime on or off.
- The chime is two short high notes. `BEEP 7` plays it.
- The rising and the falling edge go to the event ring as `ALERT_RISE` and `ALERT_FALL`.
- The limit uses 1 byte in the config trailer after `chime_repeat_h`. The state version is 42. The console command `CONFIG` shows `heat_alert_limit_c=<N>`.
- Thus the user gets a warning for a hot room in summer.
- Host tests cover the parser, the trigger, the release with hysteresis, a tile outside the mask, a stale tile, the limit 0, the masks 127 and 255, `BEEP 7`, the event ring, the `CONFIG` line, the template sync and the RTC round trip with a corrupt field and the old state version.

## Daily noise peak summary in the daily file

- **Code:** `rlcd_panel/sd_logger.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `scripts/dump-logs.sh`, `tests/test_firmware_logic.cpp`.
- The daily row has 3 new columns: `noise_peak_db` (highest hourly noise level of the day), `noise_peak_hour` (local hour 0 to 23 of the peak) and `noise_events_sum` (sum of the hourly `noise_events`). The row has 30 columns.
- `SdLogger::appendDaily` wrote the rows to `/sdcard/daily_v2_YYYY.csv` (now `daily_v3_YYYY.csv`, see "Log registry slots 4 and 5 in the climate CSV files"). The old `daily_v1_YYYY.csv` files stay unchanged.
- `DailyNoiseStats` in `PersistentState::daily` collects the values at each hourly write. If two hours have the same peak, the first hour wins. The sum stops at 65535. `DailyLogState::markDone` starts a new summary after each daily row.
- A day without a valid noise reading gives empty fields.
- The summary uses 4 bytes in the SD log trailer in RTC memory. The state version is 43. A peak above 140 dB, an hour above 23 and an event sum without a peak are rejected, also with a valid CRC.
- `LIST` and `DUMP` accept `daily_v1_YYYY.csv` and `daily_v2_YYYY.csv`. `scripts/dump-logs.sh` copies both. The retention rule treats both files in the same way.
- Thus the user sees the loudest hour of each day without the hourly file.
- Host tests cover the header and the row format with 30 columns, a day with one peak, two equal peaks, a day without a noise reading, the sum saturation at 65535, the file name, `DUMP` and `LIST` with both daily files, the retention of both file names, and the RTC round trip with a corrupt field and the old state version.

## Serial console command SCAN to list nearby BLE sensors

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/switchbot_ble.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
- The console command `SCAN` (no argument) runs one 4 s BLE scan. `SCAN` with an argument gives `ERR BAD_ARG`. A scan that does not start gives `ERR SCAN_FAILED`.
- `ScanCollector` collects each decoded packet of the formats `WOIO`, `METER`, `BTHOME` and `ATC` for up to 16 MAC addresses. For one MAC, the packet with the strongest RSSI wins.
- The reply starts with `BEGIN SCAN` and the header `mac,format,rssi,slot,temp_c,rh_pct,battery_pct`. Each MAC gives one row. The `slot` column is `1` to `3` for a MAC in a registry slot or a fixed MAC, and `-` for a free MAC. The temperature is in Celsius. The `battery_pct` field is empty when the packet has no battery value.
- A 17th MAC and each further new MAC increase a counter. The reply then ends with the row `DROPPED,<N>` before the `CRC32` line.
- The reply ends with `CRC32 <8 hex digits>` over all bytes before this line. A short write stops the reply, so the host sees no `CRC32` line.
- During the command, the BLE callback fills the collector only. The registry, the fixed MAC slots, the reject counters and `PersistentState` stay unchanged. The command needs no card. It runs on USB power only (`PowerManager::serialConsoleAllowed`).
- Thus the user finds the MAC of a new sensor for a `slotN_mac` entry.
- Host tests cover the parser, the rejection of arguments, an empty scan, one packet of each format, two packets of one MAC with different RSSI, the slot column for an assigned, a fixed and a free MAC, 17 and 18 MAC addresses with the `DROPPED` row, the CRC-32 value, the unchanged registry, the short write, the failed scan start and the USB power gate.

## BLE telemetry broadcast of the climate tiles

- **Code:** `rlcd_panel/ble_telemetry.h`, `rlcd_panel/rlcd_panel.ino`, `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `docs/config.example.ini`, `tests/fixtures/ble_telemetry_vectors.txt`.
- The `config.ini` key `ble_broadcast_enabled` (0 or 1, default 0) switches the broadcast on. The parser rejects 2 and a malformed value.
- `BleTelemetryCodec` encodes one Manufacturer Data frame of 20 bytes: the company ID `0xFFFF` and an 18-byte payload. The flags field (3 bytes) and the AD header (2 bytes) bring the advertising data to 25 bytes. The limit is 31 bytes.
- The payload holds the format version (1), the sequence number, the cell percentage (255 is unknown), the alert mask, the valid tile mask, four tiles and a CRC-8 (`0x31`).
- Each tile holds the temperature (`int16`, 0.01 C, little-endian) and the relative humidity (`uint8`, 0.5 %RH). An empty or stale tile has its bit cleared in the valid mask and zero bytes. The temperature stays in Celsius.
- After the scan, the wake cycle advertises the frame as non-connectable for 100 ms (interval 20 ms). The advertising stops before the deep sleep.
- A low battery alert and the empty battery protection skip the broadcast.
- The key uses 1 byte in the config trailer after `heat_alert_limit_c`. The state version is 44. The console command `CONFIG` shows `ble_broadcast_enabled=<N>`.
- Thus a host reads all four tiles without a cable.
- Host tests cover four valid tiles, an empty and a stale slot, the extreme values, the rounding to 0.5 %RH, the unknown cell percentage, the sequence wrap, the CRC-8, the frame size, the skip rules, the parser, the `CONFIG` line, the template sync and the RTC round trip with a corrupt field and the old state version. A test compares each shared hex vector byte for byte.

## Event codes for resets and sensor slot changes

- The event ring has three more event codes: `RESET` (8), `SLOT_ASSIGN` (9) and `SLOT_RELEASE` (10).
- The firmware records `RESET` for each start that is not a deep sleep wakeup. The detail is the `ResetReason` value. A deep sleep wakeup records no event.
- The firmware records `SLOT_ASSIGN` when a new MAC takes a registry slot. The detail has the slot number (1 to 3) in the high byte and the `BleFormat` in the low byte. A known MAC records no event.
- The firmware records `SLOT_RELEASE` when `PersistentState::releaseDeadSlots` clears a slot. The detail is the slot number (1 to 3). A slot with a fixed MAC never releases and records no event.
- `SdLogCodec::formatEventRow` writes the names `RESET`, `SLOT_ASSIGN` and `SLOT_RELEASE`. The file `events_v1_YYYY.csv` and the console command `EVENTS` show them.
- The RTC validation of the ring accepts the codes 0 to 11 and rejects the code 12 and higher.
- The ring layout does not change. `STATE_VERSION` stays the same.

## Event code for each SET command

- The event ring has one more event code: `CONFIG_SET` (11). `EventRing::MAX_CODE` is 11.
- After each successful `SET <key>=<value>` the firmware records one `CONFIG_SET` event (`SerialConsoleRunner::setConfig`). The low byte of the detail is the index of the key in `DeviceConfigParser::KEYS`. The high byte is the transport: 0 for USB serial, 1 for the BLE console.
- A rejected `SET` (`ERR BAD_KEY`, `ERR BAD_VALUE`, `ERR NO_CARD`, `ERR WRITE_FAILED`, `ERR TOO_LARGE`) records no event.
- The hourly write puts the event into `events_v1_YYYY.csv`. The file format does not change. `STATE_VERSION` stays the same.

## BLE console bridge for the serial console commands

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/ble_telemetry.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/device_config.h`, `rlcd_panel/rlcd_panel.ino`, `docs/config.example.ini`, `tests/test_firmware_logic.cpp`
- The board gives a GATT service with the Nordic UART layout. The service UUID is `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`. The RX characteristic `6E400002-...` accepts writes. The TX characteristic `6E400003-...` sends notifications.
- The `config.ini` key `ble_console` (0 to 2, default 0) controls the service. 0 is off. 1 allows the read commands. 2 also allows `SET` and `TIME SET`. The parser rejects 3 and a malformed value.
- `PowerManager::bleConsoleAllowed` is true only on USB power with `ble_console` 1 or 2. On battery power the board never advertises the service. The service stops before the deep sleep.
- `BleConsoleBridge` feeds each RX byte to a `SerialLineAssembler`. A complete line goes through the same `SerialConsole` parser as on USB. A line above 40 bytes gives `ERR TOO_LONG`.
- The sketch runs each command with `SerialConsoleRunner`. `BleChunkSink` is the output sink. It sends the reply bytes in notifications of `max(1, mtu - 3)` bytes. The reply has the same bytes as on USB serial, with the same `BEGIN` and `CRC32` lines. A failed notification ends the reply, so the central sees no `CRC32` line.
- With `ble_console` 1, `SET` and `TIME SET` give `ERR READ_ONLY`. At each level, `DUMP` gives `ERR NOT_OVER_BLE`, because a monthly file is too large for BLE. `SCAN` gives `ERR NOT_OVER_BLE`, because the scan stops the BLE stack that carries the console. USB serial `SET`, `DUMP` and `SCAN` work as before.
- Only one central connects. The board disconnects a second central. A USB `SCAN` stops the BLE stack and drops the central. A disconnect clears the partial line. A quick reconnect keeps its connect event. A full receive ring drops the partial line and gives `ERR TOO_LONG`.
- The service has no pairing or bonding. Any device in range can connect while the board runs on USB power. A `SET` of `ble_console` or the loss of USB power stops the service at once.
- The key uses 1 byte in the config trailer after `ble_broadcast_enabled`. The state version is 45. The console command `CONFIG` shows `ble_console=<N>`.
- Thus the user reads the climate values and the diagnostics from a phone or a computer without a cable.
- Host tests cover the line assembly across several write packets, a line above 40 bytes, the reply split for MTU 23 (20-byte chunks) and MTU 185 (182-byte chunks) with byte equality to the USB reply, the CRC-32 line, `SET` with `ble_console` 1 and 2, `DUMP` over BLE, the gate on battery power and with `ble_console=0`, a disconnect in a partial line, the parser for 0, 1, 2, 3 and a malformed value, the `CONFIG` line, the template sync and the RTC round trip with a corrupt field and the old state version.

## Measured awake time of each wake cycle

- **Code:** `rlcd_panel/battery_monitor.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
- The wake cycle reads `millis()` just before the deep sleep. This value is the awake time of the cycle.
- `AwakeTimeStats` keeps the last value, the maximum and the mean of the last 24 samples. Each value saturates at 65535 ms.
- Only a timer wakeup on battery power adds a sample. A power-on, a `KEY` wakeup and a cycle on USB power add no sample (`AwakeTimeStats::wanted`).
- The stats use 6 bytes in `PersistentState`, just before the checksum: the last value, the maximum, and one 16-bit field. The field holds the mean in 32 ms steps (high 11 bits) and the sample count (low 5 bits). After 24 samples, each new sample moves the mean by 1/24 of the difference, so the oldest sample fades out.
- The RTC check rejects a count above 24, a last value above the maximum, and values without a sample. A power-on sets the stats to 0. The state version is 46.
- From 8 samples, `BatteryHistory::estimate` uses the measured mean as the awake time instead of the constant `BatteryRuntime::AWAKE_MS`. With fewer samples, the constant stays.
- The console command `STATUS` shows the new line `AWAKE last_ms=<N|--> avg_ms=<N|--> max_ms=<N|--> samples=<N>` after the `RUNTIME` line and before the `CRC32` line. The fields show `--` while `samples` is 0. The `CRC32` value covers the new line.
- Thus the user sees the real cost of each feature, for example the BLE broadcast or the noise window, and gets a more accurate runtime.
- Host tests cover the first sample, 24 samples and the 25th sample, the saturation at 65535 ms, the skip rules, the estimate with 7 and 8 samples, the `STATUS` line and its position after `RUNTIME`, and the RTC round trip with a corrupt field and the old state version.

## Skip of the display refresh for an unchanged frame

- **Code:** `rlcd_panel/st7305_framebuffer.h`, `rlcd_panel/frame_refresh.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
- `St7305Framebuffer::crc32()` gives the CRC-32 of the 15,000 frame bytes. The wake cycle calls it after `display_invert`, so the value describes the bytes that go to the panel.
- The reflective panel keeps its image without power. A timer wakeup with a frame equal to the last flushed frame skips the panel start and the SPI transfer. The board saves energy in each such cycle.
- `FrameRefresh` keeps the CRC-32 of the last flushed frame and a counter of the skipped cycles in a row. It uses 5 bytes in `PersistentState`, just before the checksum. The state version is 47.
- A power-on, a `KEY` wakeup, a view change (mode or chart sensor) and the empty battery screen always flush. The 61st cycle in a row flushes after 60 skips.
- The console command `PATTERN` starts the panel, shows the pattern and clears the stored CRC-32. Thus the next wake cycle flushes the normal view.
- The RTC check rejects a skip counter above 60 and skips without a known frame. A power-on sets both values to 0.
- Host tests cover the CRC-32 of a known value and of a pattern, one changed pixel, the skip for an equal frame, the flush for a power-on, a `KEY` wakeup and a view change, the forced flush after 60 skips, the `PATTERN` path, and the RTC round trip with a corrupt field and the old state version.

## Native macOS companion app for the BLE telemetry and the BLE console

- The Swift package `macos/RlcdCompanion` holds a SwiftUI menu bar app for macOS 14 and later. It has the library `RlcdProtocol`, the app target `RlcdCompanion` and the test target `RlcdProtocolTests`.
- The app scans passively with `CoreBluetooth` for the telemetry frame (company ID `0xFFFF`, 20 bytes). `TelemetryFrame.decode` rejects a frame with a wrong length, a wrong company ID, an unknown format version or a bad CRC-8 (`0x31`).
- The menu bar shows the onboard temperature (see `MenuBarSummary` below). The window shows the four tiles, the cell percentage, the active alerts and the age of the last frame.
- `MenuBarSummary` (`MenuBarSummary.swift`) builds the menu bar content from the last `TelemetryFrame` only and sends no console command: one line for each valid tile (`<name>: <T> °<unit>, <RH> %`; name from the last `ConfigReply`, else `Tile <N>`; `°F` with `temp_unit` F), the symbol `exclamationmark.triangle.fill` with the alert names when the alert mask is not 0, and a stale mark when the frame is older than 15 minutes. The label shows the onboard temperature in the `temp_unit`; it shows `-- <unit>` for a frame older than 1900 s (`MenuBarSummary.expiredSeconds`, above the longest wake interval of 1800 s). `MenuBarSummaryTests` cover it.
- The app connects to the BLE console bridge (Nordic UART service `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`) only after the scan finds the service. The panel offers the service only on USB power with `ble_console` not 0.
- The app sends `STATUS`, `HISTORY`, `EVENTS` and `CONFIG` only when the user presses the button. It never sends a command on its own.
- `ReplyAssembler` joins the notification chunks of one reply, also chunks of 1 byte. It checks the `CRC32` line of each reply and rejects a reply with a changed byte.
- The window shows two 24-hour charts (`Swift Charts`) from the `HISTORY` rows: temperature and relative humidity.
- With `ble_console=2` or on the USB serial link, the user changes one key with `SET <key>=<value>`. With `ble_console=1` the firmware replies `ERR READ_ONLY`, and the app disables the `SET` control.
- As a fallback, the app opens the first port `/dev/cu.usbmodem*` at 115200 baud and sends the same commands.
- The app stores no data on the panel.
- The tests read `tests/fixtures/ble_telemetry_vectors.txt` and decode each vector with the values of the C++ test. They also test the four rejection cases, the parsers of the four replies, the CRC-32, a changed byte and a reply split over several notifications.
- The CI job `macOS companion tests` runs `swift test` and `swift build -c release` on `macos-latest`.

## Panel pinning and deterministic connection timeouts in the macOS app

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/PanelPin.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/LinkWatchdog.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/Transports.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/Views.swift`.
- The window lists each peripheral that sends a valid telemetry frame or the console service. Each row shows the name, the RSSI and the age of the last packet. The strongest signal is first. A peripheral without a packet for 120 s leaves the list.
- The `Pin` button stores the `CBPeripheral.identifier` UUID through `PanelPin`. `PinStore` is a small key-value protocol: the app uses `UserDefaults`, the tests use `InMemoryPinStore`. A stored text that is not a UUID counts as no pin.
- With a pin, `BluetoothScanner` drops each packet of another UUID and connects the console only to the pinned UUID. Without a pin, the behavior does not change. The `Forget panel` button clears the pin.
- `LinkWatchdog` is a pure state machine with an injected clock. It fails a connection attempt after 10 s without `Console connected`, a BLE write after 5 s without `didWriteValueFor`, and a command after 10 s without a chunk. Each received chunk restarts the command timer. A cancel ends all timers without a failure.
- Each failure gives one fixed reason text, closes the link and drops the command queue.
- `BluetoothScanner.send` splits a command into chunks. It writes one chunk and waits for its write response before it writes the next chunk. A failed write closes the link.
- USB serial keeps the 10 s command timer of `AppModel`.
- The tests step `LinkWatchdog` with a fake clock: a connect timeout at 10 s, no timeout at 9.9 s, a write timeout at 5 s, a restart of the command timer by each chunk and no timeout after a cancel. They also store, read and clear a pin, reject a malformed UUID text and filter the frames of a second UUID.
## Structured `config.ini` editor in the macOS companion app

- `ConfigSchema` (`macos/RlcdCompanion/Sources/RlcdProtocol/ConfigSchema.swift`) holds one entry for each of the 44 keys of `DeviceConfigParser::KEYS`: category (climate, alerts, power, sensors, BLE), value type, range or enum values, default value, unit and description.
- `ConfigSchema.validate(key:value:)` applies the rules of `DeviceConfigParser`. Examples: `heat_alert_limit_c` accepts 0 or 27 to 45, `noise_alert_limit_db` accepts 0 or 40 to 100, `temp_unit` accepts `C` or `F`, a name has 1 to 12 characters of A-Z, 0-9, space and `-`, and `slotN_mac` accepts `AA:BB:CC:DD:EE:FF` or an empty value.
- `ConfigSchema.diff` compares two `ConfigReply` values. A key that the reply lacks has its default value. `ConfigSchema.batch` gives the `SET` commands in the fixed schema order. `ble_console` is the last key.
- `ConfigSchema.problems` also finds a MAC that two slots would hold. It replays the batch order, so a MAC move needs a clear of the old slot first.
- `ConsoleCommand.set` accepts an empty value for `slot1_mac` to `slot5_mac`. This clears the fixed MAC.
- `ConfigEditorView` shows the keys in five groups. A marker shows each changed key. A diff sheet lists each change as `key: old -> new` before the apply.
- `ConfigBatch` sends one `SET` for each changed key and then one `CONFIG`. The first `ERR` reply stops the remaining `SET` commands. The `CONFIG` refresh still runs.
- The apply is off when no `SET` is possible (BLE with `ble_console` below 2), when a value fails the validation, or when a command line has more than 40 bytes. The form warns when the batch lowers `ble_console` over BLE.
- A lost link, a timeout or a changed link ends the batch and clears the edits.
- The tests read `docs/config.example.ini` and compare the keys and the default values with the schema. They test the limits, the enum values, the diff, the batch order, the stop at `ERR BAD_VALUE` and the limits against the firmware constants.

## Hardware diagnostics panel in the macOS app

- `Diagnostics.swift` (`macos/RlcdCompanion/Sources/RlcdProtocol/Diagnostics.swift`) parses the lines that `StatusReply.otherLines` keeps: `SHTC3` (`Shtc3Stats`), `BLE` (`BleRejectMetrics`), `RESET` (`ResetMetrics`), `RUNTIME` (`RuntimeMetrics`) and `AWAKE` (`AwakeMetrics`). `DiagnosticsReply.parse(status:)` gives all five values.
- A `--` field gives `nil`. A line that the firmware does not send gives `nil` and no error, so an older firmware works. A non-numeric or negative counter, or a missing key in a present line, throws `ParseError.badLine`.
- `StatusReply` also keeps the full `RESET` line in `otherLines`, so the abnormal count is available. `resetReason` stays unchanged.
- `ResetMetrics.isAbnormal` is true for `PANIC`, `INT_WDT`, `TASK_WDT`, `WDT` and `BROWNOUT`. `Shtc3Stats.isAlert` is true when `run` is 3 or more, as in the diagnostics view of the panel.
- `ConsoleCommand.version` sends `VERSION`. `VersionReply.parse` reads `fw`, `git`, `core`, `state` and `built`. A missing field throws `ParseError.badLine`.
- `DiagnosticsView` shows five groups: SHTC3 sensor, BLE rejected packets, resets, battery runtime and awake time. It marks the SHTC3 `run` red at 3 or more and shows a red `ABNORMAL` badge for an abnormal reset reason. The refresh button sends `STATUS` and `VERSION` (`AppModel.refreshDiagnostics`). The app sends no command on its own.
- A new panel pin, a forget and a lost link clear the diagnostics and the version.
- `DiagnosticsTests.swift` covers a full reply, `--` fields, missing lines, non-numeric counters, the `VERSION` reply, each reset reason and the red mark at run 2 and run 3.

## Nearby BLE sensor scanner and slot assignment in the macOS app

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/SlotAssignment.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/ScannerView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/ScanReplyTests.swift`.
- `ConsoleCommand.scan` sends `SCAN`. `ScanReply` parses `BEGIN SCAN`, the header `mac,format,rssi,slot,temp_c,rh_pct,battery_pct`, one row for each MAC and the optional `DROPPED,<N>` row. The format is `WOIO`, `METER`, `BTHOME` or `ATC`. The slot is 1 to 5 or `-`. An empty battery field gives no value. A missing header, a bad MAC, an unknown format or a row with a wrong field count gives a parse error.
- The app sends `SCAN` only on the USB serial link, because the firmware replies `ERR NOT_OVER_BLE` on BLE. The reply timeout is 10 s, because the scan takes about 4 s.
- `ScannerView` lists the sensors with format, RSSI, temperature, humidity, battery and slot. The strongest RSSI is first.
- `SlotAssignment` builds `SET slot<N>_mac=<MAC>` for slot 1 to 5. Slot 6 gives no command. It reports a MAC that another slot holds as fixed MAC, and refuses the all-zero MAC. The `Clear slot` action builds `SET slot<N>_mac=` with an empty value. `ConsoleCommand.set` accepts an empty value only for `slot1_mac` to `slot5_mac`.
- After each `SET`, the app reads `CONFIG` and scans again. A scan reply that fails, times out or ends the link clears the list.
- Tests parse a recorded reply with each format, an empty battery field, a slot `-` and a `DROPPED,3` row. They reject bad replies, build each `SET` line and the clear line, refuse an empty value for other keys, refuse slot 6, parse slot 4 and 5 rows, report a duplicate MAC (also in slot 5) and refuse `SCAN` on BLE.

## Repeated scan mode for sensor placement in the macOS app

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/ScanSeries.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/ScannerView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/ScanSeriesTests.swift`
- One `SCAN` gives one RSSI value. The `Repeat (15 s)` toggle of `ScannerView` sends `SCAN` on the USB serial link every 15 s, so the user sees a series for each MAC.
- `ScanSeries` (pure `RlcdProtocol` code, injected clock) keeps the last 10 RSSI values of each MAC. It gives minimum, mean, maximum, the count of kept values and the count of scans without the MAC.
- `ScannerView` shows these values below each sensor row.
- The repeat stops when the user turns it off, closes the view, the link drops, a `SCAN` gives `ERR`, or after 10 minutes. The view shows the reason.
- The app never starts the repeat on a BLE link, because `SCAN` gives `ERR NOT_OVER_BLE`. The firmware does not change.
- `ScanSeriesTests` assert minimum, mean and maximum, the limit of 10 values, the missed-scan count, the stop after 10 minutes, the stop on an `ERR` reply and no start on a BLE link.

## SD card log archive over USB serial in the macOS app

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/ReplyAssembler.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/LogArchive.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/ArchiveView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/LogArchiveTests.swift`.
- `ConsoleCommand.list` sends `LIST`. `ConsoleCommand.dump(name)` sends `DUMP <name>` and gives no line for a name that is not a firmware pattern: `climate_v2` to `climate_v5` (`YYYYMM`), `daily_v1` to `daily_v4` and `events_v1` (`YYYY`), each with the extension `.csv`. A path, a space or a line end is never valid (`LogArchive.isValidName`).
- `ListReply` parses `FILE <name> <bytes>` rows and `END LIST <count>`. It rejects a count that differs from the rows, a missing `END LIST` line, a bad size and a name outside the patterns. A `LIST` reply ends at the `END LIST` line and has no `CRC32` line.
- `ReplyAssembler` has a binary mode. After `BEGIN <name> <bytes>` with a log file name it takes exactly that byte count without line splitting, also if the bytes hold LF or `CRC32 ` text. Then it checks the `CRC32` line over these bytes, as `scripts/dump-logs.sh` does, and returns `ConsoleReply.file(name:bytes:)`. A wrong CRC-32, a changed byte or a body that is too short or too long gives `ConsoleReply.corrupt`. An `ERR` line after the bytes ends the reply. A size above 16 MiB gives `ReplyError.tooLarge`. A body that stops early stays open until the 10 s reply timeout. The other `BEGIN` replies stay line based.
- `LogArchive.plan` skips a file whose local copy has the same name and size, except the file of the current month (`climate_*`) or the current year (`daily_*`, `events_*`), which still grows. `LogArchive.store` writes `.<name>.part` and renames it to the final name only after the CRC-32 check, so a failed download leaves no final file.
- `ArchiveView` lists the card files, lets the user choose a folder, selects the new files and downloads the selected files one after the other. The app sends `LIST` and `DUMP` only on the USB serial link, because the firmware replies `ERR NOT_OVER_BLE` on BLE. Thus the user keeps a full copy of the card logs on the Mac.
- Tests parse a recorded `LIST` reply, reject a wrong `END LIST` count, assemble a `DUMP` reply with LF and `CRC32 ` text in the bytes (also in chunks of 1 byte), reject a changed byte and a short body, refuse a path and an unknown pattern, and plan the downloads for an equal file, a shorter file and the current month.

## Dynamic 1-to-6 tile climate dashboard and 5 sensor slots

- **Code:** `rlcd_panel/dashboard_view.h`, `rlcd_panel/switchbot_ble.h`, `rlcd_panel/sparkline.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/device_config.h`, `rlcd_panel/audio_alert.h`.
- The registry holds 5 external slots (`MAX_SWITCHBOT_SENSORS`, `DeviceConfig::SLOT_COUNT`). `ClimateHistory` holds 6 histories: the onboard sensor and slot 1 to slot 5. The panel has up to 6 climate sources.
- The new `config.ini` keys are `slot4_name`, `slot5_name`, `slot4_mac` and `slot5_mac`. `DeviceConfigParser::KEYS` has 44 keys. `docs/config.example.ini` lists each key.
- `climate_alert_tiles` uses bits 0 to 5 (default 63). `outdoor_slot` accepts 0 to 5. The tile latches (`VentilateLatch`, `SensorBatteryLatch`), `AlertEvaluator` and the chart view cover slot 4 and slot 5.
- `PersistentState` stores 5 slots and 6 histories. `STATE_VERSION` is 48. A record of an older version starts clean.
- `DashboardView::activeTileCount` counts the tiles: the onboard tile and each slot that has a reading, a fixed MAC or a name. `DashboardView::layoutTiles` gives one rectangle for each tile in the area x = 6, y = 36, 388 x 256 px with a gap of 8 px.
- Layouts: 1 tile 388 x 256. 2 tiles 190 x 256 side by side. 3 tiles one 388 x 124 tile above two 190 x 124 tiles. 4 tiles a 2 x 2 grid of 190 x 124. 5 tiles two 190 x 124 tiles above three 124 x 124 tiles. 6 tiles a 2 x 3 grid of 190 x 80.
- `DashboardView::tileStyle` selects the draw style from the tile size. The standard style (190 x 124 and 388 x 124) is unchanged. The large style (height 200 px or more) shows a big temperature text and humidity text, each with a curve. The compact style (190 x 80 and 124 x 124) shows short text lines and one curve. A battery text that is too wide for a tile drops to scale 1.
- The sensor stats view keeps slots 1 to 3. The CSV logs (`climate_v5`, `daily_v3`) hold slots 1 to 5. The offset keys and the console command `CALIB` cover slots 1 to 5. The console commands `STATUS` and `HISTORY` list all 6 sources.
- The macOS companion app knows the new keys (`ConfigSchema`, 44 keys), accepts an empty value for `slot4_mac` and `slot5_mac`, finds a duplicate MAC across the 5 slots, and names 6 sensors in the history charts.
- The early scan stop (`ScanCompletion`) still keeps the full scan while a slot is empty, so a new sensor can fill it. With fewer than 5 external sensors the scan always runs the full 4 s. This costs battery runtime.
- `DeviceConfigText::MAX_SIZE` is 1184 bytes (1152 before the dry limit key). A host test fills every key with its longest value.
- Preview images: `docs/display_preview_tiles1.png`, `display_preview_tiles2.png` and `display_preview_tiles6.png`. The 4 tile layout is `display_preview_dashboard.png`.
- Host tests cover the geometry and the bounds of each layout from 1 to 6 tiles, the render for each count with the widest texts, the tile count, the registry and the fixed MACs for 5 slots, the parser and the checked `SET` for the new keys, the template sync, the RTC round trip with 5 slots, the alerts and latches for slot 5, and the unchanged log format.

## Dynamic 1-to-6 tile telemetry frame and companion app display

- **Code:** `rlcd_panel/ble_telemetry.h`, `macos/RlcdCompanion/Sources/RlcdProtocol/TelemetryFrame.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/Views.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `tests/fixtures/ble_telemetry_vectors.txt`, `tests/test_firmware_logic.cpp`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/TelemetryFrameTests.swift`.
- `BleTelemetryCodec` broadcasts up to 6 tiles: format version 2, sequence, cell percentage, alert mask, valid tile mask (bits 0 to 5) and 6 tiles (int16 0.01 C LE, uint8 0.5 %RH) protected by CRC-8 (`0x31`).
- The payload size is 24 bytes. The Manufacturer Data field uses 26 bytes. The advertising frame uses 31 of 31 bytes.
- The macOS companion app decodes 6 tiles (`TelemetryFrame.formatVersion = 2`, size 26 bytes).
- The companion app view adapts the tile grid dynamically for 1 to 6 active sensors using configured names and slot labels.
- Shared vectors in `tests/fixtures/ble_telemetry_vectors.txt` verify both C++ and Swift decoders across all tile combinations.

## Climate badges and macOS notifications for alerts in the companion app

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/ClimateMath.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/AlertTransitions.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/Views.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/ClimateMathTests.swift`, `tests/fixtures/climate_math_vectors.txt`, `tests/test_firmware_logic.cpp`.
- Ported `ClimateMath` calculations to Swift: dew point, absolute humidity, NOAA Rothfusz heat index regression with adjustments, and comfort zone classification matching C++ implementation.
- Added shared test vectors in `tests/fixtures/climate_math_vectors.txt` tested by both C++ firmware unit test harness and Swift test suite.
- Tile views in the companion app display Dew Point (`DP`), Heat Index (`HI`), Comfort zone tags, and Mold hours count from history samples.
- `AlertTransitions` detects rising alert mask bits across consecutive telemetry frames with distinct sequence numbers.
- `AppModel` dispatches macOS user notification banners via `UserNotifications` when rising alert bits occur and notifications are enabled by the user.

## Clock sync, chime test and display test actions in the macOS app

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/PanelClock.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/ReplyAssembler.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/PanelClockTests.swift`.
- Added `ConsoleCommand.time`, `.timeSet(Date)`, `.beep(bit)` and `.pattern(n)`.
- `TimeReply` parses `TIME <YYYY-MM-DDTHH:MM:SS> valid=<0|1> osf=<0|1>`.
- `PanelClock` converts and formats Mac time in `Europe/Berlin` (CET/CEST), validates years within 2025...2099, and computes seconds offset between panel and Mac.
- `DiagnosticsView` displays panel local time, offset, oscillator stop flag status, and maintenance controls.
- "Sync clock" action synchronizes the panel RTC via `TIME SET` followed by `TIME`, gated by USB serial or `ble_console=2`.
- Chime test menu triggers alert chimes `BEEP 0` to `BEEP 7` and shows duration or mute error.
- Display test menu sends `PATTERN 0`, `1` or `2` after user confirmation dialog.

## Standalone macOS desktop `.app` bundle and build pipeline

- **Code:** `scripts/build-app.sh`, `macos/RlcdCompanion/Info.plist`, `macos/RlcdCompanion/Sources/RlcdCompanion/RlcdCompanionApp.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/PanelClockTests.swift`.
- `scripts/build-app.sh` builds the release binary of `RlcdCompanion`, stages `RLCD Companion.app` with `Contents/Info.plist` (`com.maorun.rlcd-companion`, `NSBluetoothAlwaysUsageDescription`, `NSHighResolutionCapable`), validates the plist with `plutil -lint`, signs the bundle with ad-hoc `codesign --force --deep --sign -`, verifies the signature with `codesign --verify --deep --strict`, and installs it into `~/Applications/RLCD Companion.app` (or `--dest <dir>`).
- Passing `--rebuild` quits a running instance, replaces the bundle, and reopens `RLCD Companion.app`. Without `--rebuild`, the script refuses while `RlcdCompanion` runs.
- `RlcdCompanionApp` uses `WindowGroup` as the primary scene and sets `NSApplication.ActivationPolicy.regular` via `AppDelegate` so the main desktop window opens immediately on launch alongside the `MenuBarExtra`.
- `AppModel` guards `UNUserNotificationCenter.current()` when running without a bundle identifier.

## Local telemetry history and CSV export in the macOS app

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/TelemetryLog.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/CsvExport.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/Views.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/TelemetryLogTests.swift`.
- `TelemetryLog` stores each telemetry frame with a new sequence number: time, sequence, cell percentage, alert mask and all tiles. A frame with the sequence of the last row adds no row. The log keeps 7 days or 20,000 rows, oldest out first.
- `TelemetryLogStore` loads `~/Library/Application Support/RlcdCompanion/telemetry.json` at start and writes it at most once a minute, through `telemetry.json.tmp` and a rename. A missing or corrupt file gives an empty log.
- The window shows a 7-day temperature chart and a 7-day humidity chart for each tile from the log.
- `CsvExport` writes the log, the last `HISTORY` rows and the last `EVENTS` rows as CSV with a header line, Celsius values and empty fields for missing values. A save panel selects the target file.

## Calibration assistant in the macOS app

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/Calibration.swift`, `Replies.swift`, `Sources/RlcdCompanion/CalibrationView.swift`, `AppModel.swift`, `Tests/RlcdProtocolTests/CalibrationTests.swift`.
- `ConsoleCommand.calib(N)` sends `CALIB N`. It gives no line for `N` outside 0 to 5. The app sends it only after the user presses `Compare`.
- `CalibrationReply.parse(lines:)` reads `REFERENCE`, `SENSOR` and `SKIP` lines and the proposed `key=value` lines. A bad number (for example `nan`) throws `ParseError.badLine`. A reply without `REFERENCE` throws `ParseError.missingLine`.
- The parser keeps only proposed offset keys that `ConfigSchema.validate(key:value:)` accepts. A value outside the key range is dropped.
- `CalibrationView` shows the sensors, the common hours, the differences and the active offsets. The user selects the proposals. `Apply selected` sends them through `ConfigBatch` (`SET` commands, then `CONFIG`).
- On the BLE link with `ble_console` 1 the firmware answers `ERR READ_ONLY`. The batch stops and shows this reply.

## Serial console command MUTE and mute toggle in the macOS app

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`
- The console command `MUTE 0` and `MUTE 1` sets `PersistentState::muted` in `g_state` (`SerialConsoleRunner::setMute`) and replies `OK MUTE <0|1>`. Before, only a short `BOOT` press changed the flag.
- `MUTE` without a digit, with a digit above 1 or with extra text gives `ERR BAD_ARG`. The parser keeps `SerialCommandKind::MUTE` and the value in `SerialCommand::reference`.
- With `ble_console` 1 the command gives `ERR READ_ONLY` (`BleConsoleBridge::gate`). With `ble_console` 2 and on USB serial it runs.
- `MUTE 0` plays no confirmation beep, unlike the unmute by a short `BOOT` press.
- The command needs no card. The next save of the wake cycle writes the flag to RTC memory with the existing audio trailer. `STATE_VERSION` is unchanged.
- `ConsoleCommand.mute(Bool)` sends `MUTE 0` or `MUTE 1`. `DiagnosticsView` shows the toggle `Mute alerts` with the value of `muted=` in the `POWER` line of `STATUS`. `AppModel.setMute` sends `MUTE` and then `STATUS`, so the toggle follows the state of the panel. The app sends `MUTE` only after the user changes the toggle.

## Serial console command SLOT CLEAR

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/sd_logger.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/EventText.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/ScannerView.swift`
- A sensor that left keeps its registry slot until `slot_release_h` passes. The console command `SLOT CLEAR <N>` (N 1 to 5, `SerialCommandKind::SLOT_CLEAR`) frees slot N at once. `PersistentState::clearSlotByUser` calls `resetSlot(N - 1)`: reading, 24-hour histories, reception mask, week days and the battery, ventilation and dry air latches of this slot only.
- An occupied slot records the event `SLOT_RELEASE` with the slot number in the low byte and `1` in the high byte (manual release, detail `0x01<N>`). An empty slot gives `OK SLOT CLEAR <N>` and records no event. The reply is `OK SLOT CLEAR <N>`.
- A slot with a fixed MAC gives `ERR FIXED_MAC` and no change, because the next scan takes the same sensor again.
- A missing number, a number outside 1 to 5 or extra text gives `ERR BAD_ARG`. With `ble_console` 1 the command gives `ERR READ_ONLY` (`BleConsoleBridge::gate`); with `ble_console` 2 and on USB serial it runs.
- The command needs no card. The firmware changes the RTC state under the registry lock and replies after the lock. The next save of the wake cycle writes the state. `STATE_VERSION` is unchanged; `EventRing::MAX_CODE` is unchanged.
- macOS companion: `ConsoleCommand.slotClear(N)` sends `SLOT CLEAR <N>`. `EventText` shows `Slot <N> released by the user` for the high byte 1 and `slot <N> released` for the timer release. `ScannerView` shows a `Free slot <N>` button for each slot with a reading in `STATUS` (state not `EMPTY`) and without a fixed MAC in `CONFIG` (`AppModel.freeableSlots`). `AppModel.clearSlot` sends `SLOT CLEAR`, then `STATUS`.
- Host tests cover the parser, the cleared state of slot 2 only, the event detail `0x0102`, no event for an empty slot, `ERR FIXED_MAC` with an unchanged state, the `ERR BAD_ARG` cases and `ERR READ_ONLY` on BLE level 1. Swift tests cover the command text, the `OK` and `ERR FIXED_MAC` replies and the `EventText` of `0x0102` and `0x0002`.

## Back up and restore `config.ini` in the macOS app

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/ConfigBackup.swift`, `ConfigEditorView.swift`, `AppModel.swift`; tests in `ConfigBackupTests.swift`.
- `Save backup…` writes the values of the last `CONFIG` reply as a `config.ini` text file through `NSSavePanel`. `ConfigBackup.render` writes each `ConfigSchema` key once in schema order, one `key=value` line, LF line ends, no CR. A value that the schema rejects (an empty name) is a comment line `# key=`. The file holds no Wi-Fi credentials, because only schema keys are written.
- `Restore backup…` reads a file through `NSOpenPanel`. `ConfigBackup.parse` uses the rules of `docs/config.example.ini`: blank lines and lines with `#` or `;` are skipped, unknown keys are ignored, CR before LF is tolerated. Each value goes through `ConfigSchema.validate(key:value:)`. A file above 8192 bytes (`ConfigBackup.maxBytes`) or invalid UTF-8 is refused.
- The restore sheet lists each rejected line with its line number and the `ConfigSchema.diff` against the current values. `Restore` is disabled with a rejected line, a problem, no changes or a console that takes no `SET`. It sends only the changed keys through `ConfigBatch`. A backup equal to the current values gives an empty batch.

## Calibration offsets for slots 4 and 5

- **Code:** `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `docs/config.example.ini`, `macos/RlcdCompanion/Sources/RlcdProtocol/ConfigSchema.swift`, `Replies.swift`; tests in `tests/test_firmware_logic.cpp`, `ConfigSchemaTests.swift`, `CalibrationTests.swift`.
- `DeviceConfig::OFFSET_SLOT_COUNT` is 5. The keys `slot4_temp_offset_c`, `slot5_temp_offset_c` (-5 to 5 C), `slot4_rh_offset_pct` and `slot5_rh_offset_pct` (-10 to 10 %RH) ended `DeviceConfigParser::KEYS` at that time (`KEY_COUNT` 48). A value outside the range is a rejected line.
- `PersistentState` already stored five offset entries, so the config trailer keeps its size. `STATE_VERSION` was 49 here, and an RTC image of version 48 is rejected.
- `CALIB N` accepts 0 to 5. The macOS app sends it for slots 4 and 5 and proposes the new keys. The CSV logs and the BLE telemetry frame stay unchanged.

## Daily summary charts in the macOS app

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/DailyLog.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DailyChartView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/ArchiveView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/DailyLogTests.swift`
- `DailyLog.parse(text:)` reads `daily_v1_YYYY.csv` (27 columns) `daily_v2_YYYY.csv` (30 columns) and `daily_v3_YYYY.csv` (42 columns, six sensors). The header row selects the format; an unknown header throws `ParseError.missingLine`. An empty field gives `nil`. A row with a wrong column count or a non-numeric field throws `ParseError.badLine`.
- `DailyLog.load(from:)` reads each daily file of the folder, lists unreadable files in `failedFiles` and sorts the rows by date. A row of a newer format (`daily_v3` over `daily_v2` over `daily_v1`) replaces a row of the same date.
- The button `Show daily charts` in `ArchiveView` shows `DailyChartView`: for each sensor one Swift Charts view with the daily minimum, mean and maximum of temperature and humidity, and one chart with the daily noise peak of `daily_v2` rows. The view reads local files only and sends no console command.

## Log registry slots 4 and 5 in the climate CSV files

- `LOG_SOURCE_COUNT` is 6: the onboard sensor and the registry slots 1 to 5.
- The hourly file is `/sdcard/climate_v5_YYYYMM.csv` with 27 columns. The columns `s4_temp_c`, `s4_rh`, `s4_battery_pct`, `s5_temp_c`, `s5_rh` and `s5_battery_pct` come after the slot 3 columns and before the noise columns. An empty or stale slot gives empty fields.
- The daily file is `/sdcard/daily_v3_YYYY.csv` with 42 columns: six columns for each of six sensors (`onboard`, `s1` to `s5`) and the five noise columns.
- The files `climate_v4`, `climate_v3`, `climate_v2`, `daily_v2` and `daily_v1` stay unchanged. `LIST`, `DUMP`, the retention and `scripts/dump-logs.sh` accept all names.
- `SdLogCodec::MAX_ROW_SIZE` is 320 and `DAILY_HEADER_SIZE` is 768 for the wider rows.
- The BLE telemetry frame and the stats view keep slots 1 to 3.
- `LogArchive.isValidName` accepts `climate_v5_YYYYMM.csv` and `daily_v3_YYYY.csv`. `DailyLog` reads `daily_v3` (six sensors) and `DailyChartView` shows the sensors that a row holds.

## Console command SCREEN and panel view

- `SCREEN` sends `BEGIN screen.bin 15000`, the 15,000 raw bytes of the framebuffer as the last render left them (after `invert()` with `display_invert` 1) and a `CRC32` line (`St7305Framebuffer::crc32()`). `SerialConsoleRunner::showScreen` sends it.
- `SCREEN` with extra text gives `ERR BAD_ARG`. Before the first render of the session the reply is `ERR NO_FRAME`. On the BLE console the reply is `ERR NOT_OVER_BLE`.
- The command needs no card and does not flush the panel.
- `PanelImage.decode(_:)` in the macOS app reads the packing of the framebuffer into a 400 x 300 1-bit image. A body of another size throws `ParseError.badLine`. `ReplyAssembler` accepts `screen.bin` in binary mode only with 15,000 bytes.
- `PanelView` shows the image. The `Refresh` button sends `SCREEN` on a user action, on the USB link only.

## Readable event log texts in the macOS app

- `EventText.describe(_:)` (`EventText.swift`) turns one `EventRow` into a readable text. The macOS app sends no new console command.
- `ALERT_RISE` and `ALERT_FALL` use the bit mask of the firmware in the detail (1 `FREEZER`, 2 `HUMIDITY`, 4 `NOISE`, 8 `SENSOR_LOST`, 16 `CHARGE_FULL`, 32 `VENTILATE`, 64 `SENSOR_BATTERY`, 128 `HEAT`), for example `ALERT_RISE,128` gives `HEAT alert on`.
- `CONFIG_LOAD` gives the count of rejected lines. `NTP_SYNC` gives `OK`, `WIFI_FAILED`, `NTP_TIMEOUT` or `RTC_WRITE_FAILED`. `BATTERY_LOW_SET` and `BATTERY_LOW_CLEAR` give the cell percentage. `EMPTY_PROTECTION` gives `set` or `release`.
- `RESET` gives the `ResetReasonCodec` text. A reason that `ResetMetrics.isAbnormal` marks gets the text `(abnormal)`, and the events view shows the row in red (`EventText.isAbnormal`).
- `SLOT_ASSIGN` gives the slot (high byte) and the `BleFormat` name (low byte), for example `514` gives `slot 2 BTHOME`. `SLOT_RELEASE` gives the slot number; the high byte 1 gives `Slot <N> released by the user`.
- `CONFIG_SET` gives the key name from `ConfigSchema.keys` and the transport, for example `Config set: wake_interval_s (BLE)`. An index outside the schema gives `Config set: key #<N>`.
- An unknown event name (for example `EVENTS_DROPPED`) or a detail outside the range gives the raw text `<event>,<detail>`.
- The `Open events file` button of `ArchiveView` reads a downloaded `events_v1_YYYY.csv` with `EventsReply.parse(file:)` and shows it in the same events list. A wrong header or row gives a message in the archive summary.

## Diagnostics report for the clipboard in the macOS app

- The `Copy report` button of `DiagnosticsView` sends `VERSION`, `STATUS`, `CONFIG` and `EVENTS` one after the other (`AppModel.copyReport`). It runs only on a user action.
- `DiagnosticsReport.build` joins the verified reply lines in this order, with one `## <COMMAND>` heading each. The first lines give the app version and the link type (`Link: USB` or `Link: BLE`).
- A failed reply (`ERR` text, wrong CRC-32, timeout or lost link) gives the line `ERROR <reason>` below its heading. The other sections stay.
- The report has no `CRC32` line.
- The report replaces the value of each key that `ConfigSchema` marks as a name or a MAC with `<hidden>`, for example `slot1_mac=<hidden>` and `onboard_name=<hidden>`. The toggle `Include names and MACs` keeps the values.
- `AppModel` puts the text on `NSPasteboard.general`. The report holds no Wi-Fi data, because the firmware sends none.
- `DiagnosticsReportTests` cover the section order, the missing `CRC32` lines, the hidden and the kept values, and a failed `EVENTS` reply.

## BLE reception rate of each registry slot

- **Code:** `rlcd_panel/switchbot_ble.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `macos/RlcdCompanion/Sources/RlcdProtocol/Diagnostics.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `tests/test_firmware_logic.cpp`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/DiagnosticsTests.swift`
- `ReceptionHistory` keeps one 24-bit mask and one scan count for each of the 5 registry slots. Bit 0 is the newest scan. The count stops at 24.
- After each BLE scan, `ReceptionHistory::recordScan` shifts the mask of each occupied slot by one bit. It sets bit 0 when the `packet_count` of the slot grew in this scan. An empty slot keeps mask 0 and count 0.
- `PersistentState::resetSlot` clears the mask and the count of the slot.
- `PersistentState::reception` uses 16 bytes just before the checksum. `STATE_VERSION` was 50 then, and an RTC image of version 49 is rejected. A count above 24, a mask bit beyond the counted scans and a value for an empty slot are rejected too. A power-on sets all values to 0.
- The console command `STATUS` shows `RECEPTION s1=<N>/<M> s2=<N>/<M> s3=<N>/<M> s4=<N>/<M> s5=<N>/<M>` after `BLE`. `<N>` is the number of 1-bits in the mask and `<M>` is the scan count. An empty slot shows `--`.
- `ReceptionMetrics` parses the line. `--` gives `nil`, and a bad value such as `s1=x/24` throws `ParseError.badLine`.
- `SensorHealth` (`SensorHealth.swift`) joins the `SENSOR` and `RECEPTION` lines of one `STATUS` reply into one row for each slot 1 to 5. The verdict follows this order: `EMPTY`, `STALE`, `BATTERY` (state `BATLOW`, or a battery at or below `sensor_battery_limit_pct` plus 5 % when the config is known), `WEAK_SIGNAL` (RSSI below -85 dBm, or a rate below 50 % with 6 or more counted scans), else `GOOD`. Each verdict has one fixed advice text. A missing `RECEPTION` line uses the RSSI only. A non-numeric field throws `ParseError.badLine`. `DiagnosticsView` shows the table; the app sends no new command.
- `DiagnosticsView` shows the rate of each slot and a warning symbol (`exclamationmark.triangle.fill`) when the rate is below 50 %.
- Host tests cover 12/24 for each second scan, N/3 for a young slot, `resetSlot`, the old `STATE_VERSION` and a count above 24. `DiagnosticsTests` cover the parser.

## Hourly climate charts in the macOS app

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/ClimateLog.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/ClimateChartView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/ArchiveView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/ClimateLogTests.swift`.
- `ClimateLog.parse(text:)` reads `climate_v2` (18 columns), `climate_v3` (19), `climate_v4` (21) and `climate_v5` (27, six sensors). The header row selects the format. An empty field gives `nil`. A row with a wrong column count or a bad field throws `ParseError.badLine`. An unknown header throws `ParseError.missingLine`. Formats before v5 hold four sensors; fields that a format lacks (`noise_events`, `noise_l10`, `noise_l90`) give `nil`.
- `ClimateLog.isClimateFileName`, `ClimateLog.month(of:)` and `ClimateLog.availableMonths(in:)` use the name check of `LogArchive`. `ClimateLog.load(from:month:)` reads the files of one month (`YYYYMM`) from the folder and lists a file that fails to parse in `failedFiles`. For the same timestamp the newer format wins.
- The button `Show climate charts` in `ArchiveView` shows `ClimateChartView`: a month picker, for each sensor one Swift Charts view with the hourly temperature and humidity, and one chart with `noise_db`, `noise_l10` and `noise_l90`. The view reads local files only and sends no console command.
- `ClimateLogTests` cover all four formats, empty fields, a row with a wrong column count, an unknown header, the file names and the month load with the newer-format rule.

## Sensor battery forecast in the macOS app

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/BatteryForecast.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/ClimateChartView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/BatteryForecastTests.swift`.
- `BatteryForecast.estimate(rows:sensor:limitPct:)` reads the `batteryPct` of one slot (1 to 5) from the `ClimateLog` rows of the last 60 days before the newest row. It uses only the rows after the last rise of 10 % or more (a new battery). It calculates a least-squares line over the remaining rows and returns the date when the line reaches `limitPct`.
- It returns `nil` for fewer than 7 days of data, for a drop below 3 %, for a line that does not fall, and for the onboard sensor (no battery column). A line that already reached the limit gives the time of the newest row. A battery change within 7 days of the newest row leaves too little data, so the view shows `not enough data`.
- `BatteryForecast.limitPct(config:)` reads `sensor_battery_limit_pct` from the last `CONFIG` reply, else 10 %. The value 0 (`BAT LOW` off) also gives 10 %. The window counts back from the newest row, and timestamps parse in Europe/Berlin time (`PanelClock.timeZone`).
- `AppModel.climateForecastRows` holds the rows of the selected month and the two months before it (`ClimateLog.earlierMonth`), so the 60-day window spans month files.
- `ClimateChartView` shows `Battery change near <date>` or `Battery forecast: not enough data` below the charts of each slot. The app sends no new command and the firmware does not change.
- `ClimateChartView` also shows the forecast for a slot that has no climate values in the selected month, when an earlier month of the window holds battery data.
- `BatteryForecastTests` cover a line of 1 % per week from 80 %, the rows after a rise from 12 % to 100 %, a rise of 9 % and of 10 %, 6 days and exactly 7 days of data, the 60-day window, unsorted rows and gaps, a drop of 2 %, a flat and a rising line, the onboard sensor, a reached limit, a line across the DST change, `ClimateLog.earlierMonth`, and the limit from a config, from a value of 0 and the default.

## Second stats page for slots 4 and 5

- **Code:** `rlcd_panel/dashboard_view.h`, `rlcd_panel/button_handler.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`
- The stats view has room for three slot blocks (`STATS_SLOT_COUNT` 3). A second page shows slots 4 and 5 with the same lines (comfort, mold, heat index, absolute humidity, records).
- `DashboardView::statsHasSecondPage` is true when slot 4 or slot 5 is active (`activeTileCount` rules). Only then the title shows `SENSOR STATS 1/2` or `SENSOR STATS 2/2`. Without slot 4 and slot 5 the render is bit-exact against the old one, and a page request is ignored.
- In the stats view a short `KEY` press returns `ButtonEvent::NEXT_PAGE` and selects the other page while the second page exists. A hold of 2 s changes the view (`MODE_CHANGED`), the same as in the chart view. Without slot 4 and slot 5 a press changes the view as before. A view change returns to page 0.
- `PersistentState::stats_page` stores the page as 1 byte after `chart_sensor`. A value above 1 is rejected. `STATE_VERSION` is 53, and an RTC image of version 50 is rejected. `flushFramebuffer()` counts a page change as a view change, so the panel always flushes it.
- `tools/preview_scenes.h` adds the scene `display_preview_stats2`. `./scripts/generate-display-preview.sh` writes `docs/display_preview_stats2.png`.
- Host tests cover `NEXT_PAGE`, `MODE_CHANGED` without slot 4 and slot 5, the hold of 2 s, the bit-exact first page, a page byte of 2 and the old `STATE_VERSION`.

## Open the macOS app at login

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/LoginItem.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/Views.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/LoginItemTests.swift`
- The menu bar app stops at logout. The toggle `Open at login` registers the app as a login item on user request. The default is off.
- `LoginItem` holds the state logic behind the protocol `LoginItemService` (`status`, `register()`, `unregister()`, `openSystemSettings()`). `MainAppLoginItemService` uses `SMAppService.mainApp`. `LoginItemStatus` has the values `enabled`, `disabled`, `requiresApproval` and `notAvailable`.
- `LoginItem` stores no copy of the state. `AppModel.refreshLoginItem()` reads it from the service when the window opens and after each toggle.
- Toggle on calls `register()`, toggle off calls `unregister()`. A thrown error sets `errorText` and the service keeps the old state.
- `requiresApproval` shows the button `Open Login Items settings` (`SMAppService.openSystemSettingsLoginItems()`).
- A run without an app bundle (`swift run`) or an unknown app (`SMAppService.mainApp.status == .notFound`) gives `notAvailable`. The toggle is disabled then.
- `LoginItemTests` use a fake service. They cover register once, unregister, a failed register and unregister that keep the state, the approval state, `notAvailable` and a state read at each call.

## USB power temperature offset

- The key `usb_temp_offset_c` (-5.0 to 0.0 C, default 0.0, 0.1 C steps) was the last entry of `DeviceConfigParser::KEYS` (`KEY_COUNT` 49) until `dry_alert_limit_rh` followed. A value outside the range is a rejected line and keeps the default.
- `DeviceConfig::onboardTempOffsetC(usb_powered)` returns `temp_offset_c + usb_temp_offset_c` on USB power and `temp_offset_c` on battery power. `refreshSensors()` reads the `SHTC3` after the battery reading and passes the power source to `readOnboardShtc3`.
- The 24-hour history, the CSV logs, the telemetry frame and `CALIB` use the corrected value.
- The console command `STATUS` shows `SHTC3 nack=<N> short=<N> crc=<N> run=<N> retried=<N> usb_offset=<x>` (the offset only on USB power).
- The config trailer stores the key as 1 byte (`int8`, 0.1 C) after `ble_console`. `STATE_VERSION` is 53, and an RTC image of version 51 gives a clean start.
- Host test `testUsbTempOffset` asserts 21.9 C on USB power and 23.1 C on battery power with `temp_offset_c=-1.9` and `usb_temp_offset_c=-1.2`, the rejected lines `-5.1` and `0.1`, and the trailer round trip.

## Dry air badge and dry limit

- The key `dry_alert_limit_rh` (0 is off, else 20 to 45 %RH, default 0) is the last entry of `DeviceConfigParser::KEYS` (`KEY_COUNT` 50). A value outside the range, for example `19` or `46`, is a rejected line and keeps the earlier value. `DeviceConfigText::MAX_SIZE` is 1184 (worst case 1168 bytes).
- `DryLatch` (`dry_latch.h`) keeps one bit for each tile (bit 0 onboard, bits 1 to 5 the slots). The bit sets when the calibrated humidity is at or below the limit. It releases when the humidity is 3 %RH or more above the limit. Between the two limits the bit keeps its state. A stale slot, an empty slot, an invalid reading and a tile outside `climate_alert_tiles` clear the bit. The limit 0 never sets a bit.
- A set bit draws the inverted badge `D` beside the `V` badge in the STANDARD, LARGE and COMPACT tiles. A tile that shows both badges puts `D` 10 px beside `V`. The latch has no chime, because `chime_alert_mask` has no free bit. The telemetry frame keeps format version 2 and has no dry flag.
- The config trailer stores the limit as 1 byte after `heat_alert_limit_c`. `PersistentState::dry_tiles` is 1 byte just before the checksum (`PowerManager::DRY_SIZE`). A bit above bit 5 is rejected. `PersistentState::resetSlot` clears the bit of the slot. `STATE_VERSION` is 54, and an RTC image of version 53 gives a clean start.
- **Preview:** `docs/display_preview_dry.png` shows the badge on the onboard tile and on slot 1.
- The macOS `ConfigSchema` has the entry `dry_alert_limit_rh`, and `docs/config.example.ini` lists the key.
- Host tests `testDryLatchConfig`, `testDryLatchLogic`, `testDryLatchRtcRoundTrip`, `testDryBadgeBounds` and `testDryPreviewScene` assert the set at 30 %RH with limit 30, the hold at 32 %RH, the release at 33 %RH, the clear for a stale tile and for a tile outside `climate_alert_tiles`, the limit 0, the rejected values 19 and 46, the RTC round trip, the rejected bit 6 and the badge pixels in each tile style. `ConfigSchemaTests` cover the range.

## Daily mold hours in the daily file

- `SdLogger::appendDaily` writes `/sdcard/daily_v4_YYYY.csv` with 48 columns: the 42 columns of `daily_v3` plus `mold_h_0` to `mold_h_5`. Each column holds the hours of one sensor (onboard, then slots 1 to 5) above `mold_alert_limit_rh` (`HumidityHistory::countAbove`, strictly above the limit). A sensor without samples gives an empty field.
- The append path stays the same: one row for each write, so a power loss loses one row at most. The files `daily_v1`, `daily_v2` and `daily_v3` stay unchanged. `LIST`, `DUMP`, the retention (December of the year) and `scripts/dump-logs.sh` accept `daily_v4_YYYY.csv`.
- `DailyLog.parse(text:)` reads `daily_v4` (48 columns; 47 columns throw `ParseError.badLine`). `DailyRow.moldHours` holds one `Int?` for each sensor, `nil` for an empty field and `[]` for older formats. `LogArchive.isValidName` accepts the new name.
- `DailyChartView` shows the mold hours as a bar chart (0 to 24 h) for each sensor that has a value.
- Host tests `testDailyMoldHours`, `testDailySummaryRowFormat`, `testDailySummaryFileNames`, `testDailySummaryRetention`, `testDailySummaryConsole` and `testDailySlots4And5` assert the header, the row, the file name, the `DUMP` parser and the retention. `DailyLogTests` and `LogArchiveTests` assert the Swift parser and the name check.

## Data age in the STALE badge

- `DashboardView::formatStaleAge` gives the age of a stale slot: whole hours since `updated_ms`, `<N>H` from `1H` (also below 1 h) to `99H`, `>99H` above. A slot without a packet (`updated_ms` 0) or with a packet stamped after `now_ms` gives an empty text, so the badge shows `STALE`.
- `DashboardView::drawStaleBadge` draws the inverted badge in the title band of the STANDARD, LARGE and COMPACT tiles: `STALE <age>`, or the age only when the title would touch the badge (3 px gap). The title scale follows the badge width. The text changes once an hour, so `FrameRefresh` still skips most frames of a stale dashboard. The stats view keeps `RSSI --`.
- Acceptance criteria: host tests cover `1H`, `2H`, `99H`, `>99H`, the empty age, the badge pixels in each tile and the equal frame CRC-32 for two renders 10 min apart. `docs/display_preview.png` shows one stale tile.

## Event code for MicroSD card faults

- The event ring has one more event code: `SD_FAULT` (12). `EventRing::MAX_CODE` is 12. The RTC validation of the ring accepts the codes 1 to 12 and rejects the code 13.
- The detail is the `SdStatus` value: 1 `OK`, 2 `NO_CARD`, 3 `MOUNT_FAILED`, 4 `WRITE_FAILED`, 5 `FULL`.
- `SdStatusCodec::faultEventDetail(previous, next)` gives the detail, or -1 for no event. A change to a fault from a different status records the fault. A change from a fault back to `OK` records detail 1. The same status in a row, `UNKNOWN` to `OK` and a change to `UNKNOWN` record nothing.
- `setSdStatusLogged` in `rlcd_panel.ino` records the event after an hourly write and after a console `SET`. The config load runs before the RTC read, so it keeps the detail in `g_pending_sd_event` until `refreshSensors()` has the time.
- The event stays in the RTC ring until the next successful hourly write puts it into `events_v1_YYYY.csv`. The console command `EVENTS` shows it without a card. The ring layout does not change. `STATE_VERSION` stays the same.
- macOS `EventText` shows `SD card fault: <status>` and `SD card OK`. An unknown status number gives the raw text `SD_FAULT,<N>`.
- Acceptance criteria: host tests cover the status changes, the CSV row and the ring check. Swift `EventTextTests` cover each status text and an unknown number.
## Two-point humidity calibration with a second salt

- **Code:** `rlcd_panel/climate_math.h`, `rlcd_panel/device_config.h`, `rlcd_panel/shtc3_sensor.h`, `rlcd_panel/switchbot_ble.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `macos/RlcdCompanion/Sources/RlcdProtocol/Calibration.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/CalibrationView.swift`.
- The `SALT` check with sodium chloride corrects only an offset. A sensor with a slope error stays wrong far from 75 %RH. Six new keys, `rh_gain` and `slot1_rh_gain` to `slot5_rh_gain` (0.90 to 1.10, default 1.00), add the slope. `DeviceConfigParser::KEY_COUNT` is 56, and `rh_gain` and the five slot keys end `KEYS` (index 50 to 55). A value of `0.89` or `1.11` is a rejected line.
- The firmware calculates `rh = raw * gain + offset`, clamped to 0 to 100 %RH, before it stores a reading: `Shtc3Codec::decodeFrame` for the onboard sensor and `SwitchBotRegistry::calibrated` for the slots. The stored registry keeps the raw readings.
- `ClimateMath::magnesiumReferenceRh(temp_c)` gives the equilibrium humidity of saturated magnesium chloride from a fixed table (10 C 33.5 %RH, 15 C 33.3, 20 C 33.1, 25 C 32.8, 30 C 32.4, 35 C 32.1, linear; NaN outside 10 to 35 C).
- The console command `SALT N MGCL2` gives the same checks and the same reply as `SALT N` with the MgCl2 reference and no proposal line. Another salt name, `mgcl2` in lower case or extra text gives `ERR BAD_ARG`.
- The config trailer of the RTC state stores each gain as 1 byte (`int8`, steps of 0.002 around 1.00, -50 to 50) after the USB temperature offset byte: 6 bytes. A step count outside -50 to 50 is rejected. `STATE_VERSION` is 57, and an RTC image of version 56 gives a clean start.
- `DeviceConfigText::MAX_SIZE` is 1312 bytes (worst case 1282). The `CONFIG` reply shows the gain lines with 3 decimals.
- macOS companion: `ConsoleCommand.saltMagnesium(N)` sends `SALT N MGCL2`. `TwoPointCalibration.compute` (`Calibration.swift`) takes the two `SaltReply` results of one sensor and the active `CONFIG` values, calculates the raw means `(mean - offset) / gain`, the gain as the slope through the two references (rounded to 0.002) and the offset as the mean residual of the two points, and returns the `SET` values. A gain outside 0.90 to 1.10 (`Failure.gainOutOfRange`), results of two different sensors, an unknown config or two raw means that are too close give an error text and no `SET`. `CalibrationView` shows the values and `AppModel.applyTwoPoint` applies them through `ConfigBatch`. `ConfigSchema` has the six new entries.
- Host tests cover the MgCl2 reference at 10, 25 and 35 C and NaN at 9 C, the corrected value for gain 1.05 and offset -2.0 at raw 50.0 %RH, the clamp at 100 %RH, the rejected lines `0.89` and `1.11`, the config trailer round trip, `SALT 1 MGCL2` without a proposal line and `ERR BAD_ARG` for `SALT 1 LICL`. `testConfigExampleTemplateInSync` and `ConfigSchemaTests.rangesMatchFirmwareConstants` cover the new keys. `TwoPointCalibrationTests` cover the result for known raw means and the error for a gain of 1.2.

## Console command WEEK and seven-day chart in the macOS app

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/ClimateChartView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/Views.swift`, `tests/test_firmware_logic.cpp`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/ReplyTests.swift`
- The console command `WEEK` (`SerialConsoleRunner::showWeek`) sends `WeekHistory` from the RTC copy. The reply is `BEGIN WEEK`, the header `sensor,day,date,temp_min_c,temp_max_c,rh_min_pct,rh_max_pct`, 42 rows (7 days for each of the 6 sensors) and the `CRC32` line. Day 0 is the day before the stamp. The date is `YYYY-MM-DD` from the stamp.
- A gap day has empty value fields. Without a stamp the date field is empty. Values use 0.5 steps. `WEEK` with text gives `ERR BAD_ARG`.
- The command needs no card and changes no state. It runs on USB and on BLE, also with `ble_console` 1.
- macOS companion: `WeekReply.parse` (`Replies.swift`) gives one `WeekRow` for each row (`nil` for an empty field, `ParseError.badLine` for a wrong column count or an index out of range). `ConsoleCommand.week` sends `WEEK`. The read button of the console section sends `STATUS`, `HISTORY`, `WEEK`, `EVENTS` and `CONFIG`. `WeekChartView` shows the 7 bars (daily minimum to maximum, `Swift Charts`) of each sensor for temperature and humidity.
- Host tests cover the parser, the header, 42 rows, the dates of day 0 and day 6, the gap day, the missing stamp, the CRC-32, the short write, the BLE gate and an unchanged `PersistentState`. Swift `ReplyTests` parse a sample.

## Android phone companion app

- `android/RlcdPhone/`: Standalone Kotlin + Jetpack Compose Material 3 app for Android smartphones (`com.rlcdpanel.phone`, `minSdk 26`, `targetSdk 34`). It mirrors `wearos/RlcdWatch` and reuses its protocol layer: `TelemetryFrame.decode`/`decodePayload` (26-byte Manufacturer Data frame, company ID `0xFFFF`, version `2`, CRC-8 `0x31`), `Checksums`, `ClimateMath` and `AlertTransitions`.
- `BleTelemetryScanner` scans with `SCAN_MODE_LOW_LATENCY` and a wildcard filter. The manifest sets `BLUETOOTH_SCAN` with `neverForLocation` on SDK 31 and later, and `ACCESS_FINE_LOCATION` up to SDK 30. `POST_NOTIFICATIONS` is requested on SDK 33 and later.
- `PhoneDashboardScreen` shows the status header (cell percentage, sequence number, last packet age, RSSI, scan state), the alert badges, the outdoor climate card, the ventilation advice of the focused room and up to 6 climate cards (temperature, humidity, dew point, heat index, absolute humidity, comfort badge, `STALE` badge after 35 minutes without a packet, `BAT LOW` badge on the `SENSOR_BATTERY` alert).
- `VentilationGuide` compares the absolute humidity of room and outdoor air. The room timer state is persisted in `SharedPreferences`. `OutdoorWeatherService` reads Open-Meteo for fixed coordinates (Berlin, 52.52 N 13.41 E; no location setting); a sensor slot can replace it as outdoor source. The advice needs a frame younger than 35 minutes and an outdoor reading younger than 1 hour. The frame CRC-8 is no authentication: a nearby device can forge a frame.
- The app scans only while it is visible (foreground only, no background service). Each alert bit of the first frame counts as new. A rising alert bit starts a vibration pattern and posts a notification. A ventilation stop or abort advice posts a notification while the window is open.
- `TelemetryFrameTest` reads `tests/fixtures/ble_telemetry_vectors.txt`. `VentilationGuideTest` and `ClimateMathTest` cover the formulas. `OutdoorResolverTest` covers the freshness rules of `OutdoorResolver`. The stop button of an active ventilation stays visible when stale data hides the advice. CI job `Android phone companion tests and APK` runs `gradle testDebugUnitTest assembleDebug`. `scripts/install-phone-apk.sh [serial|host:port] [apk]` installs the APK with `adb install -r` and starts the app.

## Door-based ventilation dependency for windowless rooms

- **Code:** `rlcd_panel/ventilate_latch.h`, `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `docs/config.example.ini`, `macos/RlcdCompanion/Sources/RlcdProtocol/ConfigSchema.swift`, `tests/test_firmware_logic.cpp`.
- The new `config.ini` key `door_ref_slot` (default `0`, range `0` to `5`) selects the reference sensor slot for door-based ventilation of slot 1. Value `0` turns the comparison off and falls back to pure relative humidity.
- When `door_ref_slot` is active (2 to 5), slot 1 checks the absolute humidity difference against the reference slot: absolute humidity of slot 1 must exceed the reference slot by at least `1.0` g/m³ (`DOOR_TRIGGER_DIFF_GM3`) and relative humidity must exceed `ventilate_limit_rh` to set the `V` badge.
- The badge releases when the absolute humidity difference drops below `0.5` g/m³ (`DOOR_RELEASE_DIFF_GM3`) or the relative humidity drops 2 %RH below `ventilate_limit_rh`.
- A stale, empty or invalid reference slot clears the badge.
- RTC state version 59: stores `door_ref_slot` after `outdoor_slot` in the config trailer.
