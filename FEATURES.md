# Features

This file describes finished work for `rlcd-panel`. Open work is in `ROADMAP.md`.

## Overview

- **Sensors & BLE:** Onboard `SHTC3` plus 5 BLE slots (`SwitchBot`, `BTHome v2`, `ATC/pvvx`), early scan stop, dead-slot release, reception rate, per-slot calibration offsets & gain slopes (`rh_gain`), USB temperature offset with battery/USB separation.
- **Display & Views:** 400×300 `ST7305` dashboard (1–6 dynamic tiles), 2-page stats view (comfort, dew point, VPD, mold, feels-like, AH, RSSI, records), 24 h climate & noise charts, 7-day min/max chart (`WEEK_CHART`), dry air badge (`D`), open window badge (`W`), wall humidity badge (`C`), stale data age badge (`STALE <N>H`), audio spectrum, diagnostics, `KEY` button navigation, frame-skip refresh, display invert.
- **Power & Battery:** Deep sleep with CRC-checked RTC state, wake & night intervals, USB detection, low-battery banner, empty-battery protection, runtime & cycle tracking, awake-time stats.
- **Audio & Alerts:** `ES8311` chimes (freezer, mold, heat, noise, dropout, charge full) with repeat & quiet hours, `BOOT` mute, `ES7210` noise & transient monitor (L10/L90), mic self-test & speaker loopback.
- **Logging & Time:** MicroSD hourly CSV (`climate_v5`), daily summaries (`daily_v4` with mold hours), event log (`events_v1`), retention & free-space check, Wi-Fi NTP sync, `PCF85063A` RTC drift tracking & frequency trim (`RTC TRIM`).
- **Configuration & Console:** MicroSD `config.ini` up to 8 KB with host validator, USB serial & BLE console (`STATUS`, `CONFIG`, `SET`, `LIST`, `DUMP`, `SCAN`, `CALIB`, `SALT`, `RTC TRIM`, `MUTE`, `SLOT CLEAR`, `SLOT SWAP`, `BEEP`, `PATTERN`, `SCREEN`, `TIME`, `HISTORY`, `WEEK`, `TODAY`, `EVENTS`, `VERSION`), `CONFIG_SET` and `SD_FAULT` event recording.
- **macOS Companion App:** Signed `.app` bundle & menu bar summary, live BLE telemetry, panel pinning, `config.ini` editor & backup, BLE scanner with repeated scan mode, guided sensor setup assistant, sensor health table, calibration assistant with single & two-point salt check, diagnostics & clipboard report, log archive & charts (including 7-day week chart), remote screen, CSV export, open at login.

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

## Mold hours in the Wear OS sensor health screen

- The watch parsed `mold_h` of the `SENSOR` lines into `StatusSensor.moldHours`, but the sensor health screen did not show it.
- `SensorHealthText.mold(sensor)` gives `Schimmel <N> h`, with the `WARNING` mark before it above 0 hours, and `Schimmel --` for `null`. `SensorHealthText.isMold(sensor)` is true above 0 hours.
- Each sensor card of `SensorHealthScreen.kt` shows the text below the reception rate. Above 0 hours, the text uses the warning color.
- The text matches `SensorHealthText.mold` of the phone. The shared protocol sources do not change.
- Tests: `SensorHealthTextTest.moldText` asserts `Schimmel 0 h`, `⚠ Schimmel 5 h` and `Schimmel --`.
- Limit: no Android SDK exists on the development host, so `gradle testDebugUnitTest assembleDebug` runs only in CI.

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
- The new console command `CONFIG` shows the active settings. It takes no argument here; `CONFIG <key>` (section "Console command CONFIG <key>") reads one value. The command runs on USB power only, like the other console commands (`PowerManager::serialConsoleAllowed`).
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
- The console command `CALIB N` (`N` is `0` for the onboard sensor or `1` to `5` for a slot) compares each other sensor with the reference. A missing argument, `CALIB 11` and extra text give `ERR BAD_ARG` (range 0 to 10 since the slot 6 to 10 item).
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

## Serial console command FRSI for the wall surface factor

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/climate_math.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `tests/fixtures/frsi_reply_sample.txt`.
- The console command `FRSI <R> <W>` measures the temperature factor of a wall. `R` is the room sensor (`0` onboard, `1` to `10` a slot). `W` is a slot that the user attaches to the inner wall surface. The two numbers must differ, and `W` 0 gives `ERR BAD_ARG`. The samples pair by age: a skipped hourly push of one history shifts its age. A missing number, a number above 10, a leading zero, equal numbers and extra text give `ERR BAD_ARG`.
- `ClimateMath::wallFactor(t_room, t_wall, t_out)` gives `(t_wall - t_out) / (t_room - t_out)`. It gives NaN for a non-finite or out-of-range input and for `t_out` at or above `t_room`.
- The command reads the last 6 hourly samples of `R`, `W` and the `outdoor_slot` from the RTC `ClimateHistory`. It calculates the factor for each hour and takes the mean. The reply is `FRSI room=<name> wall=<name> hours=<n> frsi=<x.xx> span=<x.xx> delta_c=<x>`, the proposal line `wall_frsi_pct=<N>` (the mean in percent, clamped to 50 to 95) and the `CRC32` line. `delta_c` is the mean difference of room and outdoor temperature.
- Fewer than 6 samples give `SKIP hours=<n>`. A mean difference below 10 C gives `ERR LOW_DELTA`. A factor span above 0.10 gives `UNSTABLE span=<x>`; an hour without a usable factor gives `UNSTABLE span=--`. `SKIP` and `UNSTABLE` end with the `CRC32` line. The errors have no `CRC32` line.
- `ERR NO_OUTDOOR` means that `outdoor_slot` is 0 or that the outdoor slot has no valid, fresh reading (`DeviceConfig::staleTimeoutMs()`). `ERR BAD_ARG` also covers the outdoor slot as `R` or `W`.
- The command uses the RTC copy and the registry only. It does not access the card and does not change the state. It runs on USB and on BLE at each `ble_console` level, also at level 1 (read command). The RTC layout and `STATE_VERSION` do not change.
- Host tests cover `wallFactor` for fixed values and NaN, the parser, each reply and error of `showFrsi` with a fixed `ClimateHistory`, the clamp to 50 and 95, the BLE gate, `execute()` and one reply against `tests/fixtures/frsi_reply_sample.txt`.
- Validation on the board: send `FRSI 0 2` over USB and check the reply and the `CRC32` line.

## Serial console command TAIL for the newest climate rows

- **Code:** `rlcd_panel/serial_console.h` (`SerialCommandKind::TAIL`, `SerialConsoleRunner::showTail`, `SdLogStorage::readTail`), `rlcd_panel/sd_logger.h` (`SdLogCodec::collectTailRows`, `TAIL_MAX_ROWS`, `TAIL_BYTES`), `rlcd_panel/rlcd_panel.ino`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/TailReply.kt`.
- The console command `TAIL <N>` (N 1 to 72) sends the newest N rows of the hourly climate log. It reads the last 16384 bytes of the current `climate_v6_YYYYMM.csv`. With fewer than N rows it reads the last 16384 bytes of the previous month file too (also across a year change).
- Only complete rows with the 42 columns count. A partial first line, a last line without a newline and a bad line are skipped, like in the history restore.
- Reply: `BEGIN TAIL <n>` (`n` is the row count sent, at most N), the `climate_v6` header, the rows oldest first and the `CRC32` line. Errors: `ERR NO_CARD`, `ERR NO_FILE` (no valid RTC time or no file), `ERR READ_FAILED`, and `ERR BAD_ARG` for 0, 73, a non-digit, a leading zero, a missing number or extra text.
- The command mounts and unmounts the card itself. It changes no state and runs on USB and on BLE at each `ble_console` level. The `BleConsoleBridge` gate does not block it.
- The Android `TailReply.parse` gives `TailRow` values (timestamp, panel battery, USB flag, onboard sensor and 10 slots). It checks the header, the row count, the date and every value range, and throws `ParseException` for a wrong column count.
- Validation: the host tests (235 suites) cover the parser, the gate, the row selection over the month limit and the year change, the 16384 byte limit, every error reply, a short write and the shared fixture `tests/fixtures/tail_reply_sample.txt`. `arduino-cli compile rlcd_panel` passed. `TailReplyTest` runs in CI only (no Android SDK on the local host).
- Validation on the board: send `TAIL 3` over BLE and verify the `CRC32` line.

## Serial console command DAYS for the newest daily rows

- **Code:** `rlcd_panel/serial_console.h` (`SerialCommandKind::DAYS`, `SerialConsoleRunner::showDays`), `rlcd_panel/sd_logger.h` (`SdLogCodec::collectDailyTailRows`, `DAYS_MAX_ROWS`, `DAYS_BYTES`), `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `tests/fixtures/days_reply_sample.txt`
- The console command `DAYS <N>` (N 1 to 31) sends the newest N rows of the daily summary log. It reads the last 16384 bytes of the current `daily_v5_YYYY.csv` through `Storage::readTail`. With fewer than N rows it reads the last 16384 bytes of the previous year file too.
- Only rows that `SdLogCodec::parseDailyRow` accepts count (83 columns, valid date). A partial first line, a last line without a newline and a bad line are skipped. A full row has about 450 to 580 bytes, so the 16384 byte window can hold fewer than 31 rows; then `DAYS 31` returns fewer rows (the reply shows the count `n`).
- Reply: `BEGIN DAYS <n>` (`n` is the row count sent, at most N), the `daily_v5` header, the rows oldest first and the `CRC32` line. Errors: `ERR NO_CARD`, `ERR NO_FILE` (no valid RTC time or no file), `ERR READ_FAILED`, and `ERR BAD_ARG` for 0, 32, a non-digit, a leading zero, a missing number or extra text.
- The command mounts and unmounts the card itself. It changes no state and runs on USB and on BLE at each `ble_console` level, so the companion apps read a long-term history where `DUMP` is not allowed. The RTC layout and `STATE_VERSION` do not change.
- Validation: the host tests (235 suites) cover the parser, the gate, 5 rows with `DAYS 3`, the year change (2 + 3 rows), bad rows, the 16384 byte limit, every error reply, a short write and the shared fixture `tests/fixtures/days_reply_sample.txt` (each row parses with `SdLogCodec::parseDailyRow`). `scripts/check-protocol-sync.sh` and `arduino-cli compile rlcd_panel` passed.
- Validation on the board: send `DAYS 3` over BLE and verify the `CRC32` line.

## Android newest climate rows screen

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/TailScreen.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/TailCommand.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/PhoneDashboardScreen.kt`.
- The main screen has the button `Neueste Logzeilen`. It opens `TailScreen`. The user selects 6, 24 or 72 rows (`TailCommand.CHOICES`) and taps `Neueste Zeilen lesen`. The app sends `TAIL <N>` over the BLE console on this tap only.
- `TailCommand.text(rows)` is pure. It gives `TAIL <N>` for N 1 to 72 and null for each other value; the app sends nothing for null.
- The screen shows one card for each hour, newest first: the local panel time (`MM-DD hh:mm`), the onboard values and the values of each slot with a name (`slotN_name` or the name bound to the fixed MAC `slotN_mac`). `TailCommand.valueText` shows `<T> °C  <H> %` and `--` for an empty field.
- `ERR NO_CARD`, `ERR NO_FILE` and `ERR READ_FAILED` show as clear texts (`TailCommand.errorText`). Other errors use `BleConsoleClient.errorText`. The panel needs USB power and `ble_console` 1 or 2.
- The tests `TailCommandTest` (command, error texts, value and time text) and `TailReplyTest` (row count and empty fields of `tests/fixtures/tail_reply_sample.txt`) run in CI only (no Android SDK on the local host).

## Serial console command SPREAD for a co-location check of all sensors

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/sparkline.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `tests/fixtures/spread_reply_sample.txt`
- The console command `SPREAD` checks that all sensors read the same values. Put all sensors at one location for 6 hours, then send `SPREAD`.
- `ClimateHistory::recentSums` adds the newest 6 hourly temperature and humidity samples of one sensor (onboard and slots 1 to 10). A sensor with fewer than 6 samples in one of the two histories does not count. A slot that `SwitchBotRegistry::isStale` reports with `DeviceConfig::staleTimeoutMs()` does not count, because it keeps its old hours: it has no `SENSOR` line and is not in `sensors=` and in the median (`SpreadMath::freshMask`). `SerialConsoleRunner::showSpread` takes the registry and the time.
- The command calculates the median of the means of the counted sensors for temperature and humidity (the mean of the two middle means for an even count). Integer maths keep the limit checks exact.
- Reply: `SPREAD sensors=<n> temp_median=<x> rh_median=<x>`, one line `SENSOR <name> temp_dev=<x> rh_dev=<x> <OK|OUTLIER>` for each counted sensor, and the `CRC32` line. `OUTLIER` means a deviation above 0.5 C or above 3.0 %RH; the exact limit is `OK`. The check uses the exact deviation and the printed value rounds to 0.1, so a line with `0.5` can show `OUTLIER`.
- Each `OUTLIER` line is followed by one proposal line `<temp_key>=<x> <rh_key>=<x>`. The keys are `temp_offset_c` and `rh_offset_pct` for the onboard sensor and `slotN_temp_offset_c` and `slotN_rh_offset_pct` for slots 1 to 10. Each value is the active offset plus the median minus the sensor mean, rounded to 0.1 and clamped to the key range. `OK` sensors have no proposal line, so a reply with only `OK` sensors stays byte-equal to the old reply. The Android and Wear OS parsers read the proposal lines into `SpreadReply.Outcome.Measured.proposals` (see "Apply the SPREAD offset proposals in the Android calibration screen").
- Fewer than 3 counted sensors give `SKIP sensors=<n>` and the `CRC32` line. Extra text gives `ERR BAD_ARG`.
- The command reads the RTC copy only. It does not access the card and does not change the state. It runs on USB and on BLE at each `ble_console` level. The RTC layout and `STATE_VERSION` do not change.
- Host tests cover the parser, the median for an odd and an even count, the limits at the exact value and above it for both quantities, the `SKIP` reply, a sensor with 5 samples, and one reply against `tests/fixtures/spread_reply_sample.txt` (one proposal), the proposal with an active offset, the clamped limits for the onboard and a slot sensor, and the old bytes and `CRC32` line for only `OK` sensors.
- Host tests also cover four sensors with one stale slot 2.0 C above the others (`sensors=3`, no `SENSOR` line, median unchanged) and a fresh slot with the old reply.
- Validation on the board: put the sensors together for 6 hours, send `SPREAD` over USB and check the reply and the `CRC32` line.

## Skip stale slots in the console command SPREAD and in the drift latch

- **Code:** `rlcd_panel/drift_latch.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
- `SpreadMath::compute` takes one sensor mask (bit 0 onboard, bits 1 to 10 the slots). A sensor outside the mask does not count in `count`, in the medians and in the entries. `SpreadMath::freshMask(registry, now_ms, stale_timeout_ms)` leaves out each slot that `isStale` reports.
- `SerialConsoleRunner::showSpread` uses the mask: a stale slot has no `SENSOR` line and is not in `sensors=` and in the median. The reply format does not change.
- `SPREAD <H>` runs the check over a longer window: H from 6 to 24, no leading zero (`SerialCommand::reference` holds H, `SPREAD` alone gives 6). `SpreadMath::compute(history, out, sensor_mask, hours)` passes H to `ClimateHistory::recentSums` and stores it in `SpreadMath::Result::hours`; the deviation checks and the printed values use `2 * hours` as the integer scale. A sensor with fewer than H samples in one of the two histories does not count. `SerialConsoleRunner::showSpread` takes H as last argument (default 6), so `SPREAD` and `SPREAD 6` give the same bytes. `ERR BAD_ARG` for 5, 25, a leading zero (`SPREAD 06`), a non-digit and extra text. `DriftLatch` keeps 6 hours. The reply format, the stale slot rule, the RTC layout and `STATE_VERSION` do not change. Host tests cover the 24 hour medians, the equal bytes of `SPREAD` and `SPREAD 6`, a sensor with 12 samples, the bad arguments and the 6 hours of `DriftLatch`.
- `rlcd_panel.ino` removes the stale slots from `drift_tile_mask` before `PersistentState::updateDrift`. `DriftLatch::update` and `updateDrift` count only the tiles in the mask; the bit of a stale tile clears. `updateDrift` records one `SENSOR_DRIFT` event with high byte 0 and the tile in the low byte (detail `0x00<tile>`) when that tile held the bit, so the event pair closes; a stale tile without a bit records nothing. Fewer than 3 sensors, `resetSlot` and a power-on still clear without an event.
- The RTC layout and `STATE_VERSION` do not change.
- Host tests cover four sensors with one stale slot 2.0 C above the others, an unchanged median, a drift bit that clears without an event and a fresh slot with the old behavior.

## Co-location check in the macOS calibration view

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/Calibration.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/CalibrationView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/CalibrationTests.swift`
- `SpreadReply.parse` reads the `SPREAD` reply: the header `SPREAD sensors=<n> temp_median=<x> rh_median=<x>`, one `SensorSpread` (name, temperature deviation, humidity deviation, outlier flag) for each `SENSOR` line, and the reply `SKIP sensors=<n>`. `SensorSpread.slot` gives the slot number of a name `slot<N>`.
- `ParseError.badLine` covers a bad number, a flag other than `OK` and `OUTLIER`, a header without `rh_median`, and a sensor count that differs from the `SENSOR` lines.
- `ConsoleCommand.spread` sends `SPREAD`. `AppModel.spreadCheck` sends it only on a user action, on USB and on BLE.
- `CalibrationView` has the section `Co-location check` with the button `Read spread`, a table of the sensors and a warning symbol for each `OUTLIER` row. An outlier slot shows the button `Salt check`, which selects this slot in the salt section.
- Host tests read `tests/fixtures/spread_reply_sample.txt` and cover the `SKIP` reply, the outlier flag and the bad lines.
- Validation: `swift test` and `swift build -c release` in `macos/RlcdCompanion`. The view needs a check on the real panel.

## Apply the SPREAD offset proposals in the macOS calibration view

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/Calibration.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/CalibrationView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/CalibrationTests.swift`
- `SpreadReply.proposals` holds one `SpreadProposal` (sensor, temperature key and value, humidity key and value) for each proposal line `<temp_key>=<x> <rh_key>=<x>` after an `OUTLIER` row. A reply without proposal lines gives an empty list. `ParseError.badLine` covers a bad number, a key with a wrong suffix, keys of another sensor and a proposal line without an `OUTLIER` row.
- `SpreadReply.changes(selecting:current:)` gives the `ConfigChange` list of the selected sensors against the last `CONFIG` reply; a value that equals the active one gives no change.
- `CalibrationView` shows one checkbox for each proposal in the section `Co-location check` and the button `Apply selected offsets`. `AppModel.applySpread` sends the selected values through `ConfigBatch` (`SET` commands in schema order, then `CONFIG`) and then sends `SPREAD` again. The batch result stays visible.
- Host tests cover the shared fixture (one proposal with two keys), the empty list, the bad number and the `SET` order of `ConfigBatch`.
- Validation: `swift build` in `macos/RlcdCompanion`. `swift test` did not run here: the Command Line Tools have no `Testing` macro plugin. The view needs a check on the real panel.

## Co-location check in the Android calibration screen

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/SpreadReply.kt`, `CalibrationScreen.kt`, `MainActivity.kt`, `SpreadReplyTest.kt`
- The pure Kotlin object `SpreadReply.parse` reads the `SPREAD` reply: the header `SPREAD sensors=<n> temp_median=<x> rh_median=<x>`, one `SensorSpread` (name, temperature deviation, humidity deviation, outlier flag) for each `SENSOR` line, or `SKIP sensors=<n>`. It throws `ParseException` for a bad number, a flag other than `OK` and `OUTLIER`, a wrong word count, a missing header field, or a `SENSOR` line count that differs from `sensors`.
- `CalibrationScreen` has a section `Co-location check` with the button `Read SPREAD`. It shows the medians, one row for each sensor with both deviations and a `⚠` mark for each `OUTLIER` row. `SKIP` shows `Put 3 or more sensors at one place for 6 hours`.
- The app sends `SPREAD` over the BLE console only after a tap. A lost link or a timeout shows the text of `BleConsoleClient.errorText`.
- Validation: `SpreadReplyTest` reads `tests/fixtures/spread_reply_sample.txt`. It runs in CI only (no Android SDK on the local host).

## Serial console command SELFTEST for the onboard hardware

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `tests/fixtures/selftest_reply_sample.txt`.
- The console command `SELFTEST` (`SerialCommandKind::SELFTEST`) runs each onboard check once and replies `BEGIN SELFTEST`, one line `CHECK <name> <OK|FAIL|SKIP> <detail>` for each check, `END SELFTEST pass=<N> fail=<N>` and the `CRC32` line. `SKIP` counts as neither pass nor fail. Extra text gives `ERR BAD_ARG`.
- The fixed order is `shtc3`, `rtc`, `sd`, `battery`, `mic`, `ble`. `SelfTestChecks` holds the verdicts; the sketch reads the hardware and passes the result in.
  - `shtc3`: one read with the retry of `readShtc3WithRetry` on a local `Shtc3Health` copy. Detail `READ`, `RETRIED`, `NACK`, `SHORT` or `CRC`.
  - `rtc`: `PCF85063A` read and oscillator stop flag. Detail `RUNNING`, `NO_RTC`, `OSC_STOPPED` or `READ_FAILED`. The check only reads.
  - `sd`: the card mounts and unmounts. Detail `free_mb=<N>`, `NO_CARD` or `SIZE_UNKNOWN`.
  - `battery`: ADC voltage from 2500 to 4400 mV (inclusive). Detail `mv=<N>`.
  - `mic`: one 1 s `StereoAnalyzer` window through `NoiseSampler::measure`, which powers the ADC and the I2S bus down on each path. Detail `L=<status>,R=<status>` or `IMBALANCE=<N>dB` above 10 dB. While muted (snooze included) the check gives `SKIP MUTED`, in quiet hours `SKIP QUIET`.
  - `ble`: one 1 s BLE scan start and stop. Detail `SCAN` or `START_FAILED`. On BLE the check gives `SKIP CONSOLE`, because the BLE console holds the radio.
- On USB the BLE console stops for the command and starts again. The command runs on USB and on BLE at each `ble_console` level, also at level 1 (read command). The RTC state, the RTC layout and `STATE_VERSION` do not change.
- Host tests cover the parser, the BLE gate, `execute()`, each verdict and limit (2499, 2500, 4400, 4401 mV), the reply for all checks `OK`, each single `FAIL`, the `SKIP` paths (muted, quiet hours, BLE), a short write without `CRC32` and one reply against `tests/fixtures/selftest_reply_sample.txt`.
- Validation on the board: send `SELFTEST` over USB with and without a MicroSD card and verify `CHECK sd FAIL NO_CARD` without a card. Send `SELFTEST` over BLE and verify `CHECK ble SKIP CONSOLE`. These board checks did not run in this delivery.

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
- Then one line `POWER src=<USB|BAT> v=<V> pct=<%> alerts=<hex mask> muted=<0|1> acked=<hex mask>` and the line `CRC32 <8 hex digits>` over all bytes before it.
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
- The console command `HISTORY` (no argument) sends the 24-hour histories from the RTC copy (`ClimateHistory`, `NoiseHistory`). `HISTORY <N>` (N 0 to 10) sends only the rows of sensor `N`; see the section on `HISTORY <N>` below.
- The reply starts with `BEGIN HISTORY` and the header `sensor,age_h,temp_c,rh_pct,noise_db`. Each stored hour of each sensor gives one row: sensor `0` is the onboard sensor, `1` to `3` are the slots. `age_h` `0` is the newest sample.
- The noise field holds a value in the rows of sensor `0` only. A missing sample or an invalid noise hour gives an empty field. Temperatures show one decimal and can be negative.
- The reply ends with `CRC32 <8 hex digits>` over all bytes before this line. A short write stops the reply, so the host sees no `CRC32` line.
- The command does not mount the card, so the console keeps running. It runs on USB power only (`PowerManager::serialConsoleAllowed`).
- `./scripts/dump-logs.sh --history [port]` sends `HISTORY`, checks the CRC-32 and writes `sd_logs/history_<host time>.csv`. Thus the user gets the last 24 hours also without a card.
- Host tests cover the parser, the rejection of arguments, the header, a full and an empty history, an invalid noise hour, negative temperatures, the row order, the CRC-32 line, each short write, no card access, the USB power gate and the script option.

## Serial console command HISTORY <N> for one sensor

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
- The console command `HISTORY <N>` (N 0 to 10, one digit or `10`) sends only the rows of sensor `N` (0 onboard, 1 to 10 slots). The reply has the same `BEGIN HISTORY` line, header and `CRC32` line as `HISTORY`. Thus a watch or a phone reads one room in a few BLE notifications.
- `HISTORY` without an argument keeps the full reply (`tests/fixtures/history_reply_sample.txt`). `SerialCommand::history_one` and `SerialCommand::reference` carry the sensor; `SerialConsoleRunner::showHistory(sink, history, noise, only_sensor)` takes `-1` for all sensors.
- A sensor without samples gives the header and no rows. `HISTORY 11`, `HISTORY 1x`, `HISTORY `, `HISTORY 010` and extra text give `ERR BAD_ARG`.
- No card access, no state change, no RTC layout change. The command runs on USB and on BLE at each `ble_console` level.
- Host tests cover the parser, the rows of sensors 3, 10 and 0, an empty sensor, the full reply, the fixture, the CRC-32 line of the one-sensor replies, the BLE gate at each `ble_console` level and no card mount.

## Signal meter for one sensor on the Wear OS watch

The Wear OS app shows the radio signal of one sensor. The sensor health screen shows a `Signal <name>` chip below each slot sensor whose `STATUS` `SENSOR` line holds a `mac=` field. A tap opens `SignalMeterScreen`.

- `SignalMeterScanner` runs a foreground BLE scan with a `ScanFilter` on this address (`SCAN_MODE_LOW_LATENCY`). It counts only packets that `SensorAdvertisement.decode` accepts.
- The screen shows in large text the newest RSSI, the mean of the last 10 packets and the age of the last packet.
- The watch vibrates once when the mean moves 10 dB or more from the first mean or from the last vibration (`SignalMeter.onPacket`).
- The scan stops when the screen closes, in `onStop` and after 5 minutes (`SignalMeter.check`). The button `Scan neu starten` starts a new run.
- `SignalMeter.kt` (pure, injected clock) is in `scripts/check-protocol-sync.sh` `FILES`. The watch copy `protocol/SensorAdvertisement.kt` equals `finder/SensorAdvertisement.kt` of the phone without the `package` line; the script checks the pair.
- The filter uses the BLE address from `mac=`. A sensor with a random BLE address (some ATC or pvvx slots) shows `--` until a packet matches. The vibration is one pulse of 200 ms; the 5 minute limit uses `SystemClock.elapsedRealtime()`.
- `StatusSensor.mac` (upper case `AA:BB:CC:DD:EE:FF`; `null` for `--`, a missing field and a bad text) is new in both apps.
- Tests: `SignalMeterTest` (mean, vibration edge, stop after 5 minutes with a fake clock) and `StatusSensorMacTest`. Gradle runs in the CI job `Wear OS companion tests and APK`; the host has no Android SDK.

## Room history on the Wear OS watch

- **Code:** `wearos/RlcdWatch/.../protocol/HistoryReply.kt`, `HistoryScreen.kt`, `MainActivity.kt`, `WatchDashboardScreen.kt`, `scripts/check-protocol-sync.sh`
- `HistoryReply.kt` is a copy of the phone file with another package line. `check-protocol-sync.sh` lists it in `FILES`.
- The watch dashboard has the chip `Room history`. A tap in the history screen sends `HISTORY <N>` once for the focused room (`TelemetryCache.focusedRoom`). The app sends no command on its own.
- An older firmware replies with an `ERR` line. The watch then sends `HISTORY` once and keeps only the rows of the room (`HistoryText.rowsOf`). `ERR BLUETOOTH_OFF` and `ERR PERMISSION` give no fallback.
- `HistoryText` shows the minimum and the maximum of the last 24 hours and one row for each of the 6 newest hours (`-<age> h <T> °C <RH> %`). It shows `Keine Daten` for no rows and `Panel not in range` for a timeout.
- Tests: `HistoryReplyTest` (shared fixture `tests/fixtures/history_reply_sample.txt`) and `HistoryTextTest`.

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
- The label line of the humidity box ends with `MOLD <N>H` after two spaces (`DashboardView::formatWeekMold`): the sum of `WeekHistory::moldHours` over the days with `hasMoldHours`, 0 to 168. A week without such a day shows no mold text. The line stays within `CHART_W`. The text is calculated on each render and needs no RTC state. The boxes, the weekday labels and the footer do not change.
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
- After the scan, the wake cycle advertises the frame as non-connectable for 1500 ms on battery power (750 ms for each of the 2 pages) and 2000 ms on USB power (interval 20 ms; `BleTelemetryCodec::advertiseMs`). The longer time raises the reception chance of the companion devices. The advertising stops before the deep sleep.
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

## Serial console command SLOT SWAP

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/switchbot_ble.h`, `rlcd_panel/sparkline.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
- The console command `SLOT SWAP <A> <B>` (A and B 1 to 10, different, `SerialCommandKind::SLOT_SWAP`, `SerialCommand::reference` and `reference_b`) changes the order of two tiles. `PersistentState::swapSlotsByUser` swaps the reading (`SwitchBotRegistry::swapSlots`, with the jump flag and the JumpGuard packet), both 24-hour histories, the week days and mold hours (`WeekHistory::swapSensors`), the reception history (`ReceptionHistory::swap`) and the battery, ventilation, dry air and stale latch bits of the two slots.
- The firmware swaps under the registry lock and replies after the lock. The reply is `OK SLOT SWAP <A> <B>`. Both assign flags drop, so the scan records no second `SLOT_ASSIGN` event.
- Each occupied slot after the swap records one `SLOT_ASSIGN` event (slot number in the high byte, `BleFormat` in the low byte, lower slot number first). An empty slot records nothing.
- A slot with a fixed MAC (`slotN_mac` or `SwitchBotRegistry::isFixed`) gives `ERR FIXED_MAC`. A disabled slot gives `ERR SLOT_OFF`. Both leave the state unchanged. An equal number, a missing number, a number outside 1 to 10 or extra text gives `ERR BAD_ARG`. With `ble_console` 1 the command gives `ERR READ_ONLY` (`BleConsoleBridge::gate`); with `ble_console` 2 and on USB serial it runs.
- The command needs no card. The next save of the wake cycle writes the state. The RTC layout, `STATE_VERSION` and `EventRing::MAX_CODE` are unchanged.
- Host tests cover the parser, the swap of slot 1 and slot 3 (MAC, format, history samples, week days, mold hours, reception, `stale_slots` and the other latches, events), the exact RTC bytes after a second swap with a valid checksum, `ERR FIXED_MAC` for both orders without a change, `ERR BAD_ARG` for `SLOT SWAP 2 2`, and `ERR READ_ONLY` on BLE level 1.

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

## BLE reception rate in the sensor stats view

- `DashboardView::formatReception(out, capacity, reception, slot)` gives `RX <N>%` for a slot with 6 or more counted scans in `ReceptionHistory`. `<N>` is the number of received scans divided by the counted scans, rounded. It gives `RX --` for an empty slot, for fewer than 6 counted scans and for no history.
- `DashboardView::drawReception` draws the text at scale 1, right-aligned to x = 392, at `y + 11` on the RSSI line, below the `FEELS` text and above the `MOLD` text. It draws the text for each live slot. An empty slot has no RSSI line and shows no `RX` text.
- A rate below 50 % (exact compare of the counts, `DashboardView::receptionLow`) shows the text inverted. A rate of exactly 50 % stays normal.
- `drawReception` does not draw the text when the gap to the RSSI line is below `RX_GAP_PX` (4 px). The widest RSSI line ends at x = 322 and the widest `RX` text starts at x = 350, so the text fits in each normal case.
- `SystemInfo::reception` points to `PersistentState::reception`. The view calculates the text on each render. There is no new RTC state and `STATE_VERSION` does not change.
- `tools/preview_scenes.h` fills sample rates (24 of 24, 11 of 24, 5 scans). `docs/display_preview_stats.png` shows `RX 100%`, the inverted `RX 46%` and `RX --`.
- Host tests (`testReceptionStats`) cover 24 of 24, 11 of 24, 12 of 24, 5 scans, an empty slot, a missing history, the inverted pixels below 50 %, the gap rule and the render of the stats view.

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

## Panel cell battery chart in the macOS climate charts
- `PanelBatterySeries` (`ClimateLog.swift`) reads the `battery_v` and `usb` columns of the `ClimateRow` list of one month (`climate_v2` to `climate_v6`). `points` holds each row with a voltage; an empty field gives no point. `usbRanges` holds each run of consecutive rows with `usb` 1 (first and last timestamp); a row with `usb` 0 or an empty field ends a run.
- `ClimateChartView` shows the section `Panel cell` in the `Sensors` mode: one line chart of the cell voltage with a gray band for each USB run (the last USB row covers the following hour). It shows `No battery data` when no row has a voltage. The app sends no console command and the firmware does not change.
- `ClimateLogTests` cover the battery fields of each log format, `nil` for an empty field, the points, the USB ranges (run of two rows, single rows, an empty `usb` field) and the empty series.

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

## Open window detection badge

- The key `window_drop_c` (0 is off, else 1.0 to 5.0 C, default 0) is the last entry of `DeviceConfigParser::KEYS` (`KEY_COUNT` 96). A value outside the range, for example `0.9` or `5.1`, is a rejected line and keeps the earlier value. The parser rounds to 0.1 C steps. `docs/config.example.ini` and the macOS `ConfigSchema` (`.decimal(min: 1, max: 5, off: 0)`) list the key.
- `WindowLatch` (`window_latch.h`) keeps one bit for each tile (bit 0 onboard, bits 1 to 10 the slots, only tiles in `climate_alert_tiles`) and one anchor for each tile: the peak of the current half window (`ref`), the peak of the half window before it (`prev`) in 0.01 C and a clock stamp. The bit sets when the tile is at least the limit below the larger peak. The window is 15 minutes, or `DeviceConfig::windowDropMs()` (the longest wake interval plus the scan time) when that is longer, so two readings always fall into one window. A rise only moves the peak. With the bit set, `ref` is the lowest reading. The bit releases when the tile is half the limit above it, or 2 hours after the set. A stale slot, an empty slot, an invalid or NaN reading, a tile outside the mask and a limit of 0 clear the bit and the anchor.
- A set bit draws the inverted badge `W` in the STANDARD, LARGE and COMPACT tiles, beside the `V` and `D` badges (10 px steps, same order). The latch has no chime and does not touch the telemetry frame.
- `EventCode::WINDOW_OPEN` (22, `EventRing::MAX_CODE` 22): `PersistentState::recordWindowChanges(previous, current, limit_c, now)` records one event for each changed bit. The low byte of the detail is the tile (0 onboard, 1 to 10 slots), the high byte is 1 for open and 0 for closed. A limit of 0 records nothing. `refreshSensors()` in `rlcd_panel.ino` runs the latch after `DryLatch`.
- `STATE_VERSION` is 71. The trailer `PowerManager::WINDOW_SIZE` (1 + 2 + 11 x 8 = 91 bytes) sits just before the checksum: the limit in 0.1 C steps, `PersistentState::window_tiles` and the 11 anchors (two peaks and a stamp). A bit above bit 10, a limit outside the range, a peak beyond 120 C, a previous peak on an open tile and an open tile without a reference are rejected. `resetSlot` clears the bit and the anchor of the slot; `swapSlotsByUser` swaps both.
- A change of the power source moves the onboard reading by `usb_temp_offset_c` at once. `PersistentState::restartWindowOnPowerChange` clears the onboard anchor then (not for an open tile), so the step is no fall. `refreshSensors()` calls it after `recordPowerChange`. `PersistentState::applyConfig` does the same for the tile of a changed `temp_offset_c`, `usb_temp_offset_c` or `slotN_temp_offset_c`. An open tile keeps its anchor in both cases. An invalid battery read in one cycle hides a power change (accepted).
- The detail of `WINDOW_OPEN` counts tiles from 0 (0 onboard, 1 to 10 slots), unlike `SENSOR_BATTERY` and `SLOT_STALE`, which count slots from 1. The app texts must map the number to the room accordingly.
- The peak of a tile covers between half and the whole window, plus one reading interval, when the readings come at least once a minute. With a longer interval it can reach back up to 1.5 windows.
- **Preview:** `docs/display_preview_window.png` shows the badge on the onboard tile, slot 1 and slot 2.
- Host tests `testWindowLatchConfig`, `testWindowLatchLogic`, `testWindowLatchEvents`, `testWindowLatchRtcRoundTrip` and `testWindowBadgeAndPreviewScene` assert the set for a drop of 2.0 C in 10 minutes with limit 1.5, no set for the same drop in 20 minutes, the release by a rise and after 2 hours, no change with limit 0, the rejected bit 11, one event for each edge (also the close events when the limit changes to 0), a step-wise drop, the widened window and the round trip.
- Validation: host tests (231 suites), `scripts/check-protocol-sync.sh` with `--self-test` and `swift test` in `macos/RlcdCompanion` (543 tests) pass.

## Wall humidity badge

- `WallLatch` (`wall_latch.h`) keeps one bit for each tile in `PersistentState::wall_tiles` (bit 0 onboard, bits 1 to 10 the slots). The bit sets when `ClimateMath::wallSurfaceRh` of the tile (room air at the temperature of the outer wall, factor `wall_frsi_pct`, outdoor reading of `outdoor_slot`) is at or above `mold_alert_limit_rh`. It releases when the value is more than 3 %RH below the limit; between both limits the bit keeps its state.
- An empty or stale tile, the outdoor tile itself, a stale or empty outdoor slot, an invalid result (for example an outdoor reading above the indoor one) and `wall_frsi_pct` 0 clear the bit. The latch needs no new key, has no chime and does not touch the telemetry frame. Unlike `DryLatch` and `WindowLatch`, it ignores `climate_alert_tiles`; like `VentilateLatch` it covers each tile.
- A set bit draws the inverted badge `C` in the STANDARD, LARGE and COMPACT tiles, beside the `V`, `D` and `W` badges (same 10 px steps and order). The `WALL <N>%` text of the stats view stays.
- The console command `STATUS` appends ` wall=<3 hex digits>` to the `LATCH` line (bit 0 onboard, bits 1 to 10 slots). The apps read fields by name and ignore it.
- `STATE_VERSION` is 72. `PowerManager::WALL_SIZE` (2 bytes) sits just before the checksum. A bit above bit 10 is rejected. `resetSlot` clears the bit of a slot; `swapSlotsByUser` swaps it.
- **Preview:** `docs/display_preview_wall.png` shows the badge on the onboard tile and slot 1 (slot 2 is the outdoor tile).
- Host tests `testWallLatchLogic` and `testWallTilesRtcAndPreview` assert the set at the limit, the hold and the release (hysteresis), the clear for a stale outdoor slot, a stale indoor slot, an empty outdoor slot, frsi 0 and no outdoor slot, the rejected bit 11, the RTC round trip and the preview scene. `testStatusLatchLine` asserts the `wall=` field.

## Window latch in the STATUS line LATCH

- The console command `STATUS` appends ` window=<3 hex digits>` after `wall=` to the `LATCH` line (RTC copy of `PersistentState::window_tiles`; bit 0 onboard, bits 1 to 10 slots). `SerialStatusSnapshot::window_tiles` holds it; `rlcd_panel.ino` fills it. The RTC layout and `STATE_VERSION` do not change. The apps read fields by name and ignore it.
- `tests/fixtures/status_reply_sample.txt` holds the field and a recalculated `CRC32` line.
- Host test `testStatusLatchLine` asserts `window=000` and `window=402` (tiles 1 and 10); the fixture check asserts the sample line and the CRC-32.

## Low reception mask in the STATUS line LATCH

- The console command `STATUS` appends ` rxlow=<3 hex digits>` after `window=` to the `LATCH` line (RTC copy of `PersistentState::reception_low`; bits 0 to 9 are slots 1 to 10). `SerialStatusSnapshot::reception_low` holds it; `rlcd_panel.ino` fills it. The line count, the RTC layout and `STATE_VERSION` do not change. The apps read fields by name and ignore it.
- `tests/fixtures/status_reply_sample.txt` holds the field and a recalculated `CRC32` line.
- Host test `testStatusLatchLine` asserts `rxlow=000` and `rxlow=202` (slots 2 and 10).

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
- The macOS `WeekChartView` shows the dry hours of each day in the week chart. `WeekMoldPoint.dryHours` holds the value of `WeekRow.dryHours`. The second bar series (orange) stands beside the mold hours (blue) in one chart.
- `WeekReply.drySum(rows:sensor:)` adds the dry hours of the days with a value. It gives `nil` without such a day, also for a reply with 7 or 8 columns. The text `Dry <N> h` stands below the charts of the room.
- Tests: `WeekMoldTests` check a sum of 12 hours over three days, `nil` for a reply without a dry value and `nil` for a reply with 8 columns.

## Android phone companion app

- `android/RlcdPhone/`: Standalone Kotlin + Jetpack Compose Material 3 app for Android smartphones (`com.rlcdpanel.phone`, `minSdk 26`, `targetSdk 34`). It mirrors `wearos/RlcdWatch` and reuses its protocol layer: `TelemetryFrame.decode`/`decodePayload` (26-byte Manufacturer Data frame, company ID `0xFFFF`, version `2`, CRC-8 `0x31`), `Checksums`, `ClimateMath` and `AlertTransitions`.
- `BleTelemetryScanner` scans with `SCAN_MODE_LOW_LATENCY` and the filter `BleTelemetryScanner.buildFilter()`: Manufacturer Data company ID `TelemetryFrame.COMPANY_ID` (`0xFFFF`) with empty data and an empty mask, so each payload of this ID matches and Android hardware filtering delivers results in the background with the screen off. `BleTelemetryScannerFilterTest` checks the filter constants. The manifest sets `BLUETOOTH_SCAN` with `neverForLocation` on SDK 31 and later, `ACCESS_FINE_LOCATION` up to SDK 30, and foreground service permissions (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`). `POST_NOTIFICATIONS` is requested on SDK 33 and later.
- `PhoneDashboardScreen` shows the status header (cell percentage, sequence number, last packet age, RSSI, scan state), the alert badges, the outdoor climate card, the ventilation advice of the focused room and up to 6 climate cards (temperature, humidity, dew point, heat index, absolute humidity, comfort badge, `STALE` badge after 35 minutes without a packet, `BAT LOW` badge on the `SENSOR_BATTERY` alert).
- `VentilationGuide` compares the absolute humidity of room and outdoor air. The room timer state is persisted in `SharedPreferences`. `OutdoorWeatherService` reads Open-Meteo for the stored outdoor location (`OutdoorLocationStore`, `SharedPreferences` `rlcd_outdoor_location`: latitude -90 to 90, longitude -180 to 180, 4 decimals, place name; default Berlin 52.52 N 13.41 E; the `Ort ändern` dialog of the outdoor card edits it, rejects non-numeric and out-of-range text, and asks for no location permission; `OutdoorResolver.cacheFor` drops the cached reading after a change; `OutdoorWeatherService.requestUrl` formats 4 decimals); a sensor slot can replace it as outdoor source. The advice needs a frame younger than 35 minutes and an outdoor reading younger than 1 hour. The frame CRC-8 is no authentication: a nearby device can forge a frame.
- `BleScanService` runs as a foreground service (`connectedDevice` type) with a low-importance persistent notification on Android and Wear OS, so BLE telemetry scanning continues while the app is in the background. Each alert bit of the first frame counts as new. A rising alert bit starts a vibration pattern and posts a notification. A ventilation stop or abort advice posts a notification while the window is open.
- The Android phone app reads the panel config over the BLE console. `BleConsoleClient.send(command, callback)` replaces the fixed `STATUS` read: one GATT connection for each command, writes of `max(1, MTU - 3)` bytes (`ConsoleWire.frame`/`chunks`, one chunk per write confirmation), callbacks of an older connection are ignored. The `Read panel config` button of `SensorHealthScreen` sends `CONFIG` after a tap only. `PanelConfig.parseConfigReply` keeps the `key=value` lines (8192 bytes at most, key and value shape checked), the `SOURCE` word and the `REJECTED line=<N>` numbers apart. `ConfigCache` stores a `SOURCE SD` reply in `filesDir/config.ini` through `config.ini.tmp` and an atomic rename (read time as `# READ_AT=<ms>`, rejected lines as `# REJECTED line=<N>` comments, written off the main thread). A bad CRC-32, an `ERR` reply, a timeout and a `SOURCE DEFAULT` reply keep the old cache. `PanelConfig.load` reads the cache first, then the external `config.ini`, then the bundled asset; an empty cache falls through. The screen shows the cache age and the rejected lines. The panel answers on USB power with `ble_console` 1 or 2 only. The cache has no clear action: delete the app data to return to the external file. `tests/fixtures/config_reply_sample.txt` holds a sample reply; `PanelConfigTest` and `BleConsoleClientTest` cover parsing, cache priority, the kept cache and the chunking.
- `TelemetryFrameTest` reads `tests/fixtures/ble_telemetry_vectors.txt`. `VentilationGuideTest` and `ClimateMathTest` cover the formulas. `OutdoorResolverTest` covers the freshness rules of `OutdoorResolver`. `OutdoorLocationStoreTest` covers the default, the round trip, the rejects, the URL and the cache clear. The stop button of an active ventilation stays visible when stale data hides the advice. CI job `Android phone companion tests and APK` runs `gradle testDebugUnitTest assembleDebug`. `scripts/install-phone-apk.sh [serial|host:port] [apk]` installs the APK with `adb install -r` and starts the app.
- `SensorHealthScreen` (button `Read sensor health` in `PhoneDashboardScreen`) shows one card for each registry slot with name (`PanelConfig.tileName`), RSSI, reception rate, battery and the verdict text of `SensorHealth` (`EMPTY`, `STALE`, `BATTERY`, `WEAK_SIGNAL`, `GOOD`, first match wins, same thresholds as `SensorHealth.swift`). `BleConsoleClient` (`console/`, `BLUETOOTH_CONNECT`, one GATT connection, MTU request 185, Nordic UART notifications, one write of `STATUS\n`, 10 s timeout, disconnect after the reply) sends `STATUS` only on a user action and no write command. It needs USB power and `ble_console` 1 or 2 on the panel. `ReplyAssembler` joins the chunks and checks the `CRC32` line; `StatusReply` parses the `SENSOR` lines and the `RECEPTION` line (`--` gives `null`, `s1=x/24` throws `ParseError`).
- `ReplyAssemblerTest`, `StatusReplyTest` and `SensorHealthTest` cover the parsers; `StatusReplyTest` and the Swift `SensorHealthTests` read the shared fixture `tests/fixtures/status_reply_sample.txt`.
- The Wear OS app mutes the panel chime from the wrist. `ChimeCommand.kt` is a copy of the Android phone file (package line apart) and is in `FILES` of `scripts/check-protocol-sync.sh`. `SensorHealthScreen` shows the `Panel stumm` toggle chip when the `STATUS` reply holds `muted=<0|1>`. A tap sends `MUTE 1` or `MUTE 0` through `BleConsoleClient.send`, then `STATUS`, and the toggle follows the `STATUS` reply only. A failed command keeps the old state and has no retry. `ERR READ_ONLY` shows `Set ble_console=2 to apply`, a timeout shows `Panel not in range`. `ChimeCommandTest` covers the command lines and the reply texts.

## Door-based ventilation dependency for windowless rooms

- **Code:** `rlcd_panel/ventilate_latch.h`, `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `docs/config.example.ini`, `macos/RlcdCompanion/Sources/RlcdProtocol/ConfigSchema.swift`, `tests/test_firmware_logic.cpp`.
- The new `config.ini` key `door_ref_slot` (default `0`, range `0` to `5`) selects the reference sensor slot for door-based ventilation of slot 1. Value `0` turns the comparison off and falls back to pure relative humidity.
- When `door_ref_slot` is active (2 to 5), slot 1 checks the absolute humidity difference against the reference slot: absolute humidity of slot 1 must exceed the reference slot by at least `1.0` g/m³ (`DOOR_TRIGGER_DIFF_GM3`) and relative humidity must exceed `ventilate_limit_rh` to set the `V` badge.
- The badge releases when the absolute humidity difference drops below `0.5` g/m³ (`DOOR_RELEASE_DIFF_GM3`) or the relative humidity drops 2 %RH below `ventilate_limit_rh`.
- A stale, empty or invalid reference slot clears the badge.
- RTC state version 59: stores `door_ref_slot` after `outdoor_slot` in the config trailer.

## Wall surface humidity in the stats view

- **Code:** `rlcd_panel/climate_math.h`, `rlcd_panel/device_config.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `docs/config.example.ini`, `tools/preview_scenes.h`, `macos/RlcdCompanion/Sources/RlcdProtocol/ConfigSchema.swift`, `tests/test_firmware_logic.cpp`.
- `ClimateMath::wallSurfaceRh(t_in_c, rh_in_pct, t_out_c, frsi)` calculates the wall temperature `t_out + frsi * (t_in - t_out)` and gives the humidity of the room air at this temperature (same vapor pressure), clamped to 100 %RH. NaN or infinite input, `frsi` outside 0.50 to 0.95 and `t_out` above `t_in` give NaN.
- The new `config.ini` key `wall_frsi_pct` (default `0`, 0 is off, else 50 to 95) sets `frsi`. The value uses 1 byte in the config trailer after `dry_alert_limit_rh`. `KEY_COUNT` is 83. RTC state version 61.
- With the key set and a fresh reading in `outdoor_slot`, the stats view shows `WALL <N>%` (scale 1, right-aligned to x = 392, line `y + 30`) for each indoor tile. The text is not drawn when the gap to the MAC line is below 4 px. An empty or stale tile, the outdoor tile itself, a stale or empty outdoor slot and a NaN result show `WALL --`. The value is calculated on each render, has no RTC state and uses whole percent, so `FrameRefresh` still skips most frames.
- The preview scene `display_preview_stats` sets `outdoor_slot` 2 and `wall_frsi_pct` 70.

## Mute marker in the panel header

- `SystemInfo::muted` (from `PersistentState::muted`) and the new `muted` argument of `DashboardView::renderHeader`, `render` and `renderSensorStats` drive the marker in every view.
- While `muted` is true, the header shows a white 9x11 badge with a black `M` at `HEADER_QUIET_X` and `HEADER_QUIET_Y`. The mute marker wins over the `Q` marker, because mute silences all chimes. Without mute the `Q` rule does not change.
- The low battery banner and the empty battery screen replace the marker.
- A change of the mute state changes the frame CRC-32, so `FrameRefresh` flushes the next frame.
- The preview `docs/display_preview_muted.png` shows the badge. `PREVIEW_SCENE_COUNT` is 18.
- A `MUTE` change in the console and a `BOOT` toggle in the grace window call `renderDashboard()`, so the panel shows the new state at once.

## Alert limit lines in the macOS climate charts

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/ChartLimits.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/ClimateChartView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DailyChartView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/ArchiveView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/ChartLimitsTests.swift`
- `ChartLimits.from(config:)` reads the last `ConfigReply` and gives one `ChartLimit` for each active limit: `mold_alert_limit_rh` (`Mold`) and `dry_alert_limit_rh` (`Dry`) for the humidity charts, `freeze_alert_limit_c` (`Freeze`) and `heat_alert_limit_c` (`Heat (feels-like)`) for the temperature charts.
- A value of 0 for `dry_alert_limit_rh` and `heat_alert_limit_c` (off), a missing key and a value outside the `ConfigSchema` range give no line. `freeze_alert_limit_c` has no off value, so 0.0 gives a line. Without a `CONFIG` reply the list is empty.
- `ClimateChartView` and `DailyChartView` draw each line as a dashed red `RuleMark` with its name. The app sends no new command.
- `ChartLimitsTests` cover the known sample, the value 0, the out-of-range value, the missing reply and the label texts.
- The charts draw each line for every sensor. They ignore `climate_alert_tiles`, which limits the firmware alerts to some tiles. A line widens the automatic Y range, so the user sees the distance of the curve to the limit.

## Alert timeline in the macOS diagnostics view

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/AlertTimeline.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/AlertTimelineTests.swift`
- `AlertTimeline.build(rows:now:)` pairs each `ALERT_RISE` with the next `ALERT_FALL` of the same alert bit into one `AlertInterval`. An alert without a fall stays open until the injected `now` (`isOpen`).
- A fall without a rise starts at the first row with a valid time and has `startUnknown`. An `EVENTS_DROPPED` row sets `startUnknown` on each interval that is open at that row. Other event codes and rows without a valid time are ignored.
- `DiagnosticsView` shows the intervals in the group `Alert timeline` as horizontal Swift Charts bars, one row for each alert, from `model.events` (the existing `EVENTS` read action or an opened events file). Red is an open alert, a dimmed bar has an unknown start. The app sends no new command.

## Restore of the 24-hour histories from the MicroSD card

- A power-on clears the RTC memory, so the sparklines, the records, the mold hours and the charts were empty for 24 hours. After the config load and the first RTC read, `restoreHistoriesFromCard` in `rlcd_panel.ino` calls `SdLogger::restoreHistories`. It runs only for `WakeupCause::POWER_ON` and not after a failed mount.
- `SdLogger::restoreHistories` reads the last 8192 bytes (`SdLogCodec::RESTORE_TAIL_BYTES`) of the current `climate_v6_YYYYMM.csv` file. With fewer than 24 rows it reads the previous month file too (`SdLogCodec::previousMonth`, also across a year change). A missing file, a short read or a bad row changes nothing and never blocks the wake cycle.
- `SdLogCodec::parseClimateRow` parses one row of the current format (42 columns, the roadmap named the old 27-column `climate_v5`). An empty field is a missing value. A wrong column count, a bad timestamp, a bad number or an out-of-range value rejects the row, so the header line is skipped too. `SdLogCodec::parseClimateTail` skips the partial first line of a tail and a last line without a newline. `ClimateRowBuffer` keeps the newest 48 rows.
- `ClimateHistory::restore` takes the newest run of rows that is at most 24 hours old, has at most 90 minutes between two rows (a larger step is a gap and ends the run) and holds at most 24 rows. A row without onboard temperature or humidity ends the run too, so the onboard histories and `usb_mask` keep one length. It pushes them oldest first into the onboard histories and into the slots with a fixed MAC (`slotN_mac`). A slot without a fixed MAC stays empty, because the registry order after a power-on is not known. The `usb` column feeds `usb_mask`. `last_sample_ms` follows the newest row, so the hourly gate does not push the same hour twice.
- `restore` changes nothing for an invalid RTC time, a stopped oscillator, a year before 2025, a row newer than the RTC time, histories that already hold samples and rows older than 24 hours.
- No new RTC state and no new `config.ini` key. `STATE_VERSION` stays 61.
- Host tests: `testParseClimateRow`, `testClimateRestoreFixedSlotAndUsbMask`, `testClimateRestoreGapAndAge`, `testClimateRestoreMonthChange`, `testClimateRestoreNoChange` and `testClimateRestoreTailParsing`.

## Sensor display names bound to MAC addresses

- A display name belongs to the sensor MAC, not to the slot index. `DeviceConfig::nameForMac(mac)` returns the `slotN_name` of the slot whose fixed MAC (`slotN_mac`) equals `mac`. A missing, zero or unknown MAC and a fixed MAC without a name give `nullptr`.
- A slot tile shows the name of the MAC of its sensor: the MAC of the live reading, else the fixed MAC of the slot (`DashboardView::slotActiveMac`). A sensor that moves to another slot keeps its name. A slot with an unknown MAC shows the default title (`SENSOR N` in the tile, `S<N>` in the stats view, `slot<N>` in `STATUS`) even when `slotN_name` is set. A `slotN_name` without `slotN_mac` matches no sensor. The `onboard_name` key is unchanged.
- `DashboardView::tileTitle` and `DashboardView::statsLabel` take the MAC as last argument. The tile headers, the stats view, the chart views and the console command `STATUS` use it.
- The macOS app (`ConfigReply.name(forSlot:activeMac:)`, `ConfigReply.name(forMac:)`), the Wear OS app and the Android phone app (`PanelConfig.nameForMac`, `PanelConfig.tileName(index, activeMac)`) look up the name by MAC; the mobile apps fall back to `slotName(index)` when no MAC matches. The telemetry frame carries no MAC, so a tile uses the fixed MAC of its slot.
- Migration: a `config.ini` with `slotN_name` but without `slotN_mac` shows the default titles until the user sets `slotN_mac` (the console command `SCAN` lists the MACs). `docs/config.ini` and `docs/config.example.ini` state this rule.
- No new `config.ini` key and no new RTC state. `STATE_VERSION` stays 61.
- Host tests: `testSensorNamesFollowMac`, `testSensorNames` and `testSerialStatusStates`. App tests: `MenuBarSummaryTests.nameFollowsTheSensorMac`, `PanelConfigTest` (Wear OS and Android).

## Name and MAC assignment in one step in the macOS scanner

- `SlotAssignment.batch(slot:mac:name:)` gives `SET slotN_mac=<MAC>` and, for a name that is not empty, `SET slotN_name=<name>`, in this order. The name is upper-cased. It has at most 12 characters (`SlotAssignment.maxNameChars`, `DeviceConfig::NAME_MAX_CHARS`) and no space and no `=`; `ConfigSchema.validate` checks the rest. A bad slot, MAC or name and a line above 40 bytes give `nil`.
- Each row of `ScannerView` has a name field beside the `Assign` menu. `AppModel.assign(slot:mac:name:)` sends the commands through `ConfigBatch(commands:)`: the first `ERR` stops the batch, then `CONFIG` runs. A MAC that another slot holds gives the existing duplicate warning and sends nothing.
- `ConfigSchema.unboundNames(config:)` lists the slots with a `slotN_name` and without a usable `slotN_mac`; an empty list without a `CONFIG` reply. `ConfigEditorView` shows `Name of slot <N> has no MAC. Assign the sensor in the scanner.` for each of them.
- No firmware change and no new `config.ini` key.
- App tests: `SlotAssignmentTests` and `ConfigSchemaTests.unboundNames*`.

## Config key slot_auto_assign to block unknown sensors

- The new `config.ini` key `slot_auto_assign` (0 or 1, default 1) controls the third step of `SwitchBotRegistry::upsert`. With 1 the registry works as before: a new MAC takes the first free slot. With 0 only a slot with a fixed MAC (`slotN_mac`) takes a new MAC; a packet of another MAC goes to the reject path.
- `BleRejectStats::unassigned` counts these packets (saturates at 65535; `resetRejects()` clears it). The packet also counts in its format counter and sets `last`. A slot that already holds a MAC keeps its updates. A change from 1 to 0 does not clear an occupied slot; `slot_release_h` and `SLOT CLEAR` free it.
- The console command `STATUS` appends ` unassigned=<N>` to the `BLE` line. The console command `SCAN` does not use the registry rules and still lists each MAC with slot `-`.
- The value uses 1 byte after `ble_console` in the config trailer (`PersistentState` restores it with `applyConfig` and `deserialize`). `STATE_VERSION` is 62 and `KEY_COUNT` is 84.
- The macOS app has the schema entry `slot_auto_assign` and `BleRejectMetrics.unassigned` (`nil` for an older firmware); `DiagnosticsView` shows it.
- Host tests: `testSlotAutoAssign`, `testSlotAutoAssignStatus`, `testSlotAutoAssignScan`, `testSlotAutoAssignConfigKey`. App tests: `ConfigSchemaTests`, `DiagnosticsTests.bleRejectUnassignedField`.

## Sensor protocol source badges in companion apps

- The console command `STATUS` reports `format=<SHTC3|WOIO|METER|BTHOME|ATC|THERMOPRO>` on each `SENSOR` line (`--` for an empty slot). The macOS app and the Android phone app now parse it and show the badge of the panel tiles beside the sensor name.
- Badge map: `SHTC3` is `[I2C]`, `WOIO` and `METER` are `[SB]`, `ATC` is `[ATC]`, `BTHOME` is `[BTH]`, `THERMOPRO` is `[TP]`. A missing field, `--` and an unknown format give `nil` (no badge).
- macOS: `StatusSensor.format`/`badge` and `SensorHealth.format`/`badge`/`badge(forFormat:)` (`Replies.swift`, `SensorHealth.swift`). `Views.swift` shows the badge in the status text and in the telemetry tile titles (from the last `STATUS`); `DiagnosticsView` shows it in the sensor health rows.
- Android: `StatusSensor.format`/`badge` and `SensorHealth.format`/`badge`/`badgeFor` (`StatusReply.kt`, `SensorHealth.kt`). `SensorHealthScreen` shows the badge in the slot title. `PhoneDashboardScreen` shows it in the climate card title (tile 0 is always `[I2C]`; the other tiles use the last sensor health read, kept in `MainActivity.sourceBadges`).
- The Wear OS app has no `STATUS` console and reads only the telemetry frame, so it has no change.
- No firmware change and no new `config.ini` key.
- Tests: `SensorHealthTests.formatGivesBadge`, `missingFormatGivesNil`, `emptyAndUnknownFormatGiveNoBadge`, `statusSensorCarriesFormat` and the same four tests in `SensorHealthTest.kt`.

## Sensor MAC addresses in the macOS companion app

- The firmware console command `STATUS` reports the live MAC address `mac=<AA:BB:CC:DD:EE:FF|-->` on each `SENSOR` line.
- The macOS companion app parses the live MAC address and displays it across all main sensor views, falling back to `slotN_mac` from `config.ini` when available.
- Telemetry tiles (`Views.swift`): each active sensor tile shows its MAC address below the sensor title in monospaced font.
- Status view (`Views.swift`): each sensor line includes the MAC address in parentheses after the name and format badge.
- Sensor health (`DiagnosticsView.swift`, `SensorHealth.swift`): `SensorHealth` carries the live `mac` string from `StatusSensor.mac` (or `ConfigReply.mac(forSlot:)`); `DiagnosticsView` displays the MAC address in an aligned column.
- Diagnostics report (`DiagnosticsReport.swift`): redacts `mac=` in sensor lines when `includeNames` is false.
- No new `config.ini` key and no new RTC state.
- Host tests: `testSerialStatusEmptyAndMissingValues`, `testSerialStatusStates` in `test_firmware_logic.cpp`. Companion tests: `SensorHealthTests.statusSensorCarriesFormatAndMac`, `rowsPopulatesLiveMacFromStatusLine`, `rowsPopulatesMacFromConfig`.

## Current log file names in `dump-logs.sh`

- `scripts/dump-logs.sh` uses one shared name pattern for the `NAME`, `FILE_LINE` and `BEGIN_LINE` checks. It accepts `climate_v2` to `climate_v6`, `daily_v1` to `daily_v5` and `events_v1`, the same set as `LogArchive.swift`. The script now copies the current `climate_v6` and `daily_v5` files.
- A `FILE` line with an unknown name prints the warning `SKIP <name>` on stderr, so a later format change is visible.
- `scripts/dump-logs.sh --self-test` checks a fixed list of good and bad names without a serial port and exits 0 or 1. The CI job `Host tests` runs it.
- No firmware change and no new `config.ini` key.

## Confirmation of a large reading jump

- `JumpGuard` in `switchbot_ble.h`: `SwitchBotRegistry::upsert` holds a packet as pending when a slot reading is younger than 10 minutes and the packet of the same MAC differs by more than 8.0 C or more than 25 %RH. The slot keeps its value and `upsert` returns false without a reject count.
- A second packet of the same MAC within 1.0 C and 5 %RH of the pending packet confirms the change, and the slot takes the newest packet. A packet near the stored reading drops the pending packet.
- `SwitchBotRegistry::endScan()` drops each pending packet at the end of `scanOnce()`. The pending packet is not in RTC memory. An empty slot, a stale slot and a reading of 10 minutes or older take the first packet.
- `BleRejectStats::jumps` counts each dropped pending packet (saturates at 65535, reset by `resetRejects()`). A held packet does not reach `ScanCompletion::onPacket`.
- The console command `STATUS` appends ` jumps=<N>` to the `BLE` line. `BleRejectMetrics.jumps` (`Diagnostics.swift`) parses the optional field and `DiagnosticsView` shows it.
- No new `config.ini` key and no new RTC state.
- Host tests: `testJumpGuard`, `testJumpGuardStatusLine` in `test_firmware_logic.cpp`. Companion test: `DiagnosticsTests.bleRejectJumpsField`.

## Wear OS tile

- The watch app has a tile `ClimateTileService` (`androidx.wear.tiles`, `androidx.wear.protolayout`) with the name, temperature and humidity of the focused room, the panel cell percentage, the active alert names and the age of the data.
- The tile does not scan BLE. `TelemetryCache` stores the 24-byte payload of the last valid frame of each page (hex text) and its receive time in `SharedPreferences`. `BleScanService` writes it for each new sequence number. `MainActivity` stores the focused room.
- The tile shows `--` and `Open the app` for an empty cache, a failed decode (length, version, CRC-8), a frame older than 35 minutes or a focused room without a reading.
- `BleScanService` writes the cache, so the tile stays current while the app is closed. A stale frame shows `--` at the next refresh, up to 15 minutes after the 35 minute limit.
- A tap opens `MainActivity`. The tile asks for a refresh every 15 minutes; a new cache entry requests an update at once.
- `TelemetryCacheTest` covers the round trip of a fixture vector, an empty cache, bad hex, bad CRC-8, the 35 minute limit and the alert names. No firmware change.

## 24-hour telemetry history and CSV share in the Android app

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/TelemetryHistory.kt`, `HistoryScreen.kt`, `BleScanService.kt`, `MainActivity.kt`, `PhoneDashboardScreen.kt`.
- `TelemetryHistory` adds one `HistoryRow` for each frame with a new sequence number: receive time, sequence, cell percentage, alert mask and 6 tiles in Celsius. A frame with the sequence of the last row adds no row. The history keeps 7 days (measured from the newest row) or 20,000 rows, oldest out first.
- `BleScanService` records only page 0 frames (`shouldRecord`), because page 1 holds the slots 6 to 10. The history records only while the app scans.
- `TelemetryHistoryStore` writes `telemetry.json` in `filesDir` through `telemetry.json.tmp` and a rename, at most once a minute (`saveThrottled`). A single writer thread runs the load, the merge with live rows and each save (`saveLatest` reads the value when the task runs). `onDestroy` saves at once. A missing or corrupt file, or a wrong version, gives an empty history. A load drops rows older than 7 days and sorts the rows.
- `HistoryScreen` (button `Verlauf 24 h` on the dashboard, `BackHandler` in `MainActivity`) draws a 24-hour temperature chart and humidity chart for each tile with Compose `Canvas`. A missing value or a pause above 30 minutes breaks the line. A flat curve sits in the middle of the box. The points are computed once a minute.
- `Share CSV` sends the text with `ACTION_SEND` (`text/plain`). The columns match `CsvExport.telemetry` of the macOS app (`time,sequence,cell_pct,alerts,t0_c,rh0_pct` to `t5_c,rh5_pct`; ISO-8601 UTC time; 2 and 1 decimals; empty field for a missing value). The text holds the newest 1,500 rows (`HistoryScreenText.SHARE_MAX_ROWS`), and the screen states this cap.
- No `config.ini` key and no firmware change.
- Tests: `TelemetryHistoryTest` (duplicate sequence, age limit, row limit, JSON round trip, corrupt JSON, CSV header and row, 24-hour window, chart gaps, store throttle, page filter, load merge). CI job `Android phone companion tests and APK` runs them.

## Wear OS watch face complication

- `ClimateComplicationService` (`complication` package, extends `ComplicationDataSourceService`) shows the focused room on the watch face. It supports `SHORT_TEXT` (`<T>°` with the title `<RH>%`) and `RANGED_VALUE` (humidity 0 to 100). A tap opens `MainActivity`.
- `ClimateComplicationModel.build` reads the focused room from `TelemetryCache`. It does not scan BLE. An empty cache, a failed decode (length, version or CRC-8), a frame older than 35 minutes and a room without a valid reading give `--` and the ranged value 0.
- `BleScanService` calls `ClimateComplicationService.requestUpdate` for each new sequence number, next to the tile update.
- The service is registered in `AndroidManifest.xml` with the types `SHORT_TEXT,RANGED_VALUE`. The dependency is `watchface-complications-data-source-ktx` 1.2.1.
- `ClimateComplicationModel.hasAlert` is true for a fresh frame with an alert bit in `TelemetryFrame.alerts`. The `SHORT_TEXT` text then shows one decimal with a `!` in front (`!21.4°`, at most 7 characters for -50.0 to 100.0) and `alertNames` lists the active alerts; the content description of both types appends `, Alert <names>`. An empty cache, a stale frame and a room without a reading give no mark.
- No `config.ini` key and no firmware change.
- Tests: `ClimateComplicationModelTest` (text of the `two_tiles` vector for two rooms, empty cache, frame of 36 minutes, bad CRC-8, room without reading, ranged value 0 to 100, alert mark `!21.4°` for mask 0x02, no mark for mask 0, a stale frame and an empty cache, text length of 7 or less for `!-10.5°`). The local machine has no Android SDK, so CI runs `gradle testDebugUnitTest assembleDebug`.

## Ventilation advice complication on the Wear OS watch face

- `VentilationComplicationService` (`complication` package, extends `ComplicationDataSourceService`) is a second complication data source next to `ClimateComplicationService`. It supports only `SHORT_TEXT`: the text is one word (`Lüften`, `Zu` or `--`), the title is the absolute humidity difference of the focused room minus the outdoor air in g/m3 (`+2.3`, `-1.4`, `--`). A tap opens `MainActivity`.
- `VentilationComplicationModel.build` reads the focused room and the outdoor source from `TelemetryCache`. It does not scan BLE. `Lüften` is `LUEFTUNG_STARTEN`; every other advice without an open window (`NICHT_LUEFTEN`, `NEUTRAL`) gives `Zu`. An empty cache, a failed decode, a frame older than 35 minutes, a room without a valid reading, no outdoor source, the outdoor tile as focused room, a stale outdoor frame and an Open-Meteo reading older than 1 hour give `--` for text and title.
- `VentilationGuide.advise` holds the shared outdoor resolution; `ClimateTileModel` uses it too, so tile and complication agree. `VentilationGuide.shortWord` and `shortDifference` format the text.
- `BleScanService` refreshes it through `ClimateComplicationService.requestUpdate` for each new sequence number. The manifest uses `UPDATE_PERIOD_SECONDS` 0 like the climate complication.
- No `config.ini` key and no firmware change.
- Tests: `ClimateComplicationModelTest` (`Lüften`, `Zu` for a wetter outdoor, equal air and a dry room, difference text, frame of 35 and 36 minutes, empty cache, bad CRC-8, missing outdoor source and outdoor sensor slot). The local machine has no Android SDK, so CI runs `gradle testDebugUnitTest assembleDebug`.

## Restore of the seven-day history from the daily file

- **Code:** `rlcd_panel/sd_logger.h`, `rlcd_panel/sparkline.h`, `rlcd_panel/rlcd_panel.ino`.
- A power-on set each `WeekHistory` day to a gap, so the `WEEK_CHART` view and the `WEEK` command stayed empty for 7 days. The firmware now refills the days from the daily file.
- `SdLogCodec::parseDailyRow` parses one row of `daily_v5_YYYY.csv` (83 columns). An empty pair is a missing value. A half pair, a wrong column count, a bad number and a bad date reject the row. The header line is rejected too.
- `WeekHistory::restore` takes the rows of the 7 days before the RTC date, stores the min and max of each sensor, leaves a missing day as a gap and sets the stamp to the RTC date. A later row of the same day wins. A day without any pair counts as no data.
- `SdLogger::restoreWeek` reads the last 4096 bytes of the file of the current year. From 1 to 7 January it also reads the file of the previous year. `restoreHistoriesFromCard` runs it for a power-on only.
- An invalid, stopped or untrusted RTC time, a week with a stamp or a value, a missing card or file, and corrupt rows change nothing.
- No `config.ini` key. The RTC `PersistentState` layout and `STATE_VERSION` stay unchanged.
- Tests: `testParseDailyRow`, `testWeekRestoreFromDailyRows`, `testWeekRestoreYearChange`, `testWeekRestoreNoChange`, `testWeekRestoreTailAndBuffer`.

### Protocol sync check for the Wear OS and Android sources

- `scripts/check-protocol-sync.sh` compares `AlertTransitions.kt`, `Checksums.kt`, `ClimateMath.kt`, `PanelConfig.kt`, `ReplyAssembler.kt` and `TelemetryFrame.kt` of `wearos/RlcdWatch` and `android/RlcdPhone` after it removes the `package` line. It prints `DIFFERS <file>` for each difference and exits 1. A missing file exits 2.
- `--self-test` builds a good pair (exit 0), a bad pair (exit 1) and a pair with a missing file (exit 2) in a temporary directory.
- The CI job `Host tests` runs the check and the self-test.
- The Wear OS `PanelConfig.kt` and the new `ReplyAssembler.kt` now equal the Android phone sources. The Wear OS app does not use the `ConfigCache` yet.
- A new Wear OS `ClimateMathTest` and the Android `ClimateMathTest` check each row of `tests/fixtures/climate_math_vectors.txt` with a tolerance of 0.05.

## Calibration proposals in the Android app

- **Code:** `android/RlcdPhone/.../protocol/CalibrationReply.kt`, `protocol/CalibrationBatch.kt`, `CalibrationScreen.kt`, `MainActivity.kt`, `SensorHealthScreen.kt`.
- A user without a Mac could not read the `CALIB N` proposals. The phone app now reads them over the BLE console. The `Calibration proposals` button of the sensor health screen opens the `CalibrationScreen`.
- `CalibrationReply` and `SaltReply` follow the parsing rules of `Calibration.swift`: digit-only hours, plain decimals, the last line of a key wins. A bad line throws `ParseException`, for example `hours=x`. `CalibrationKeys` drops each proposal outside the key range.
- The screen has a reference picker (0 to 5), the `SENSOR`, `SKIP` and `USB` lines and one checkbox for each proposal. A change of the reference clears the reply.
- `CalibrationBatch` sends each selected proposal as `SET`, then `CONFIG`, through `BleConsoleClient.send`. The first `ERR` stops the batch and sends no `CONFIG`. `ERR READ_ONLY` shows as `Set ble_console=2 to apply`. A timeout or a corrupt reply shows that the panel state is unknown. A good `CONFIG` reply refreshes the config cache.
- The macOS `ConfigBatch` still sends `CONFIG` after a failed `SET`. The phone batch skips it by design.
- `tests/fixtures/calib_reply_sample.txt` is shared. `CalibrationReplyTest` and `CalibrationTests.sharedFixture` assert the same values.
- The salt screen and the two-point calibration stay macOS only. The phone ports only the `SaltReply` parser.
- The local machine has no Android SDK, so CI runs `gradle testDebugUnitTest assembleDebug`.

## NTP sync state in STATUS and in the companion diagnostics

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `macos/RlcdCompanion/.../Diagnostics.swift`, `DiagnosticsView.swift`, `android/RlcdPhone/.../protocol/StatusReply.kt`, `SensorHealthScreen.kt`.
- The user could not see why the clock does not sync. `STATUS` now shows `NTP ok=<0|1|--> failures=<N> age_s=<N|--> next_s=<N|-->` right after the `RTC` line.
- The line reads only the RTC copy of `NtpSyncState`. It does not start Wi-Fi and does not change state. `age_s` is the time since the last attempt.
- `next_s` is the time to the next due attempt: `ntp_sync_interval_h` after a success, `NtpSchedule::retryDelayMs(failures)` after a failure. An overdue attempt shows 0.
- Without an attempt or without Wi-Fi credentials the line shows `ok=-- failures=0 age_s=-- next_s=--`.
- The macOS `NtpMetrics.parse` (`Diagnostics.swift`) returns `nil` for `ok=--` and a missing line. A bad field throws `ParseError.badLine`. `DiagnosticsView` shows a warning symbol from 3 failures.
- The Android `StatusReply.ntp` returns `null` for `ok=--` and a missing line. A bad field throws `ParseException`. `SensorHealthScreen` shows a warning from 3 failures.
- The new line shifts each following `STATUS` line index by one. `tests/fixtures/status_reply_sample.txt` holds the line and the new CRC-32.
- The local machine has no Android SDK and no Xcode, so CI runs `swift test` and `gradle testDebugUnitTest assembleDebug`.

## Seven-day chart over the BLE console in the Android app

- **Code:** `android/RlcdPhone/.../protocol/WeekReply.kt`, `WeekScreen.kt`, `MainActivity.kt`, `PhoneDashboardScreen.kt`, `tests/fixtures/week_reply_sample.txt`.
- The phone kept only the data from its own scan. The `Wochenverlauf 7 Tage` button now reads `WEEK` over the BLE console and shows the 7 days of `WeekHistory` from the panel.
- `WeekReply.parse` follows `WeekReply.parse` of `Replies.swift`: header, 7 columns, sensor 0 to 10, day 0 to 6, date `YYYY-MM-DD` or empty, no duplicate sensor and day, minimum not above maximum. A bad line throws `ParseException`.
- The Kotlin parser is stricter for a number. It accepts only a plain decimal, `--` or an empty field. Padding, `NaN` and `Infinity` throw `ParseException`.
- `WeekScreen` draws 7 bars from the daily minimum to the daily maximum for temperature and humidity of each sensor, oldest day left. The scale is shared by the 7 bars. The minimum span is 2.0 C and 5.0 %RH. The humidity scale stays inside 0 to 100 %RH. A gap day is an empty bar outline. The titles come from `PanelConfig.tileName`.
- A read that `onStop` aborts shows `Abgebrochen. Erneut lesen.`
- `tests/fixtures/week_reply_sample.txt` holds 42 rows, one gap day (sensor 2, day 3) and the `CRC32` line. `WeekReplyTest` and `ReplyTests.weekSharedFixture` assert the same values.
- The local machine has no Android SDK. CI runs `gradle testDebugUnitTest assembleDebug`. The Swift parser rules were compiled and checked against the fixture with `swiftc`, because `swift test` fails on this machine (`TestingMacros` plugin not found).

## Guided sensor setup assistant in the macOS app

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/SetupAssistant.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/SetupAssistantView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/ScannerView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/SetupAssistantTests.swift`.
- `SetupAssistant` is a pure state machine with an injected clock. The steps are `scan`, `choose`, `assign`, `verify` and `calibrate`. The view starts it with `Set up a new sensor` in the scanner box. The button needs the USB link.
- Step `scan` sends `SCAN` on the USB link only. Step `choose` lists only rows with slot `-`, sorted by `ScanReply.sortedByRssi`. The optional name follows the rules of `SlotAssignment.batch`.
- Step `assign` picks the first slot without a fixed MAC, without a reading and not disabled by `slotN_enabled=0`. It sends `SlotAssignment.batch(slot:mac:name:)` through `ConfigBatch`, then `CONFIG`. It needs a known `STATUS` and `CONFIG` and `canSet`. A MAC that another slot holds gives a hint and no command.
- Step `verify` sends `STATUS` on each `Check reception` click. It passes when the `SENSOR` line of the slot has the chosen `mac=` and `state=OK`. Another MAC or another state keeps the step and shows the line values.
- Step `calibrate` sends `CALIB <N>` (not the new slot). The reply selects only the keys of the new slot. The user may add the other proposals. `Apply selected` sends them through `ConfigBatch`, then `CONFIG`. `Finish without calibration` ends the assistant.
- The assistant sends one command for each user click. The next command of a running batch belongs to the same click. The first `ERR` reply, a reply that the parser rejects, a lost link, a timeout of a pending command or a transport switch stops the assistant. The view shows the reply text. A `SET` that the panel already applied stays.
- The `Cancel` button waits while a batch runs, so the panel does not keep a half assignment. The idle limit is 1800 s since the last click.
- The assistant keeps its `CALIB` reply to itself. The calibration box of the app does not show it. A finished batch clears the stored calibration and the typed name of the MAC.
- `SetupAssistantTests` feed recorded `SCAN`, `CONFIG`, `STATUS` and `CALIB` replies. They assert the command order, the free slot, the choose list without slot rows, `ERR FIXED_MAC` before `CONFIG`, a `STATUS` line with another MAC, a lost link and the idle clock.
- `swift test` fails on this machine (`TestingMacros` plugin not found, no Xcode). `swift build` passes. The 21 tests ran in a `swiftc` harness over the real sources. CI runs `swift test`.

## Nearby sensor finder and slot assignment in the Android app

The console command `SCAN` gives `ERR NOT_OVER_BLE` on BLE, so a user without a Mac could not find the MAC of a new sensor. The Android phone app now listens to the sensors with the phone radio and assigns a slot over the BLE console.

- `SensorAdvertisement.decode` (`finder/SensorAdvertisement.kt`) decodes `WOIO` (company ID `0x0969`), `METER` (service `0xFD3D`), `BTHOME` (service `0xFCD2`), `ATC` and pvvx (service `0x181A`) and `THERMOPRO` (header `0xC2`). It follows the dispatch order of the firmware and uses the same ranges: temperature -50 to 100 C for `BTHOME`, `ATC` and `THERMOPRO`, humidity 0 to 100 %RH, battery 0 to 100 %, a zero MAC rejected, only the lengths that the firmware accepts. A rejected packet gives `null`.
- `SensorFinderList` keeps each MAC once with the strongest RSSI (at most 32 rows). `SensorFinderScanner` runs the unfiltered scan, because the formats use different company IDs and service UUIDs. `SensorFinderScreen` shows the MAC, the format badge (`SB`, `BTH`, `ATC`, `TP`), the reading, the RSSI and the slot from the `ConfigCache` (`Slot - (nicht fest zugewiesen)` for a MAC without a fixed slot).
- `SlotAssignment.batch(slot, mac, name)` follows `SlotAssignment.swift`: slot 1 to 10, name of 12 characters or fewer without a space and without `=`, upper-cased, only `A-Z`, `0-9` and `-` (the firmware rule `DeviceConfig::validName`), at most 40 bytes for each line. It returns `SET slot<N>_mac=<MAC>` and `SET slot<N>_name=<NAME>`. `SlotAssignment.conflicts` and `warning` reject a MAC that another slot holds.
- `SlotAssignment.run` sends the commands and then `CONFIG` through `BleConsoleClient.send`. The first `ERR` stops the batch before `CONFIG`. `ERR READ_ONLY` shows `Set ble_console=2 to apply`. A good `CONFIG` reply refreshes the `ConfigCache`.
- `SensorHealthScreen` has the button `Find nearby sensors`. `MainActivity` stops the finder scan in `onStop`, before a console command and when the screen closes.
- `tests/fixtures/sensor_adv_vectors.txt` holds 47 lines (22 good and 25 rejected, each format has both). `testSensorAdvertisementFixtureVectors` in `tests/test_firmware_logic.cpp` decodes each line with the firmware parsers. `SensorAdvertisementTest` decodes the same lines and asserts the same values. `SlotAssignmentTest` asserts the command order, the upper-case name, the 13-character name, the duplicate MAC, the stop at the first `ERR` and the `READ_ONLY` text.
- The local machine has no Android SDK. CI runs `gradle testDebugUnitTest assembleDebug`.

## Signal series in the Android sensor finder

A user who moves a sensor needs a stable signal, not one RSSI value. The finder of the phone app now keeps a short series for each MAC. It ports `ScanSeries.swift`.

- `SignalSeries` (`finder/SignalSeries.kt`) takes an injected clock (`() -> Long`, milliseconds). The phone scan runs without a break, so the series cuts it into windows of 15 s (`WINDOW_MILLIS`). Each window keeps the strongest RSSI of each MAC. A closed window adds one value; the series keeps the last 10 values (`MAX_VALUES`) and gives minimum, mean and maximum (`Stats`).
- A MAC without a packet in a closed window gets one missed window. `Stats.missed` counts over all closed windows (`Stats.windows`), the RSSI values only over the last 10. The open window is not counted.
- `advance()` closes the elapsed windows, also after a silent gap. The series stops with `StopReason.TIMEOUT` after 40 windows (10 minutes, `MAX_DURATION_MILLIS`). `stop(reason)` keeps the values and the first reason. `start()` drops the old values.
- `MainActivity` starts the series with the finder scan and records each packet. A ticker (1 s) calls `advance()`, publishes the statistics and checks the scan permission. The series stops with `USER` (button, slot assignment), `SCREEN_STOPPED` (`onStop`, close of the screen), `TIMEOUT`, `PERMISSION_LOST` (permission check or scan error) or `SCAN_FAILED`. The values stay on the screen after the stop.
- `SensorFinderScreen` shows `Serie (<n>): min, Ø, max dBm, <missed> von <windows> Fenstern ohne Paket` in each row (`SensorFinderText.series`) and the stop text of a timeout, a lost permission and a failed scan. The scan itself keeps running after the timeout.
- No firmware, RTC or protocol change.
- `SignalSeriesTest` uses a fake clock and asserts the statistics, the strongest packet of a window, the limit of 10 values, the missed windows, the silent gap, the stop after 10 minutes, the first stop reason, a MAC that appears late (earlier windows count as missed, as in `ScanSeries.swift`) and the reset by `start()`. `SensorFinderTextTest` asserts the row text and the stop texts.
- The local machine has no Android SDK. CI runs `gradle testDebugUnitTest assembleDebug`.

## Console command REJECTS with the reason of each rejected BLE packet

The `BLE` line of `STATUS` gives only counters and the MAC of the last reject. The user could not see why a sensor does not take a slot. The new console command `REJECTS` lists each rejected MAC with its reason.

- `RejectLog` (`switchbot_ble.h`) holds 8 entries: MAC, `BleFormat`, `RejectReason` and a count that saturates at 255. The reasons are `FULL` (no free slot), `UNASSIGNED` (`slot_auto_assign=0` and no fixed MAC), `DISABLED`, `WEAK` (a new MAC below `auto_assign_min_rssi`) and `JUMP` (a pending packet that `JumpGuard` dropped). The enum value for `DISABLED` is `SLOT_OFF`, because the Arduino core defines the macro `DISABLED`.
- Each MAC has one entry. A later packet of the MAC raises the count and keeps the first reason. A ninth MAC raises `dropped`, which saturates at 255. `SwitchBotRegistry::resetRejects()` clears the log at the start of `scanOnce()`.
- `PersistentState::reject_log` stores the copy of the last scan, 73 bytes just before the checksum. `scanOnce()` copies it after `endScan()`. A scan that does not start clears it. A reason above `WEAK`, a count of 0, an unknown format, a repeated MAC, a gap and `dropped` with a free entry make the state invalid. `STATE_VERSION` is 64.
- `REJECTS` replies `BEGIN REJECTS`, the header `mac,format,reason,count`, one row for each entry, `DROPPED,<N>` when MACs were lost and the `CRC32` line. Extra text gives `ERR BAD_ARG`. The command reads only the RTC copy, mounts no card and runs on USB and on BLE at each `ble_console` level.
- `tests/fixtures/rejects_reply_sample.txt` holds the reply sample. The tests cover each reason, the `DROPPED` row, `resetRejects()`, saturation, the state round trip, each invalid block, the parser, the BLE gate and the fixture with its CRC-32.
- Validation: the host tests and `arduino-cli compile rlcd_panel` pass. The companion apps do not parse `REJECTS` yet.

## Mute toggle and chime test in the Android app

- `StatusReply.muted` parses `muted=<0|1>` of the `POWER` line (`null` for a missing or bad field).
- `ChimeCommand` builds `MUTE <0|1>` and `BEEP <N>` for bits 0 to 7 (`IllegalArgumentException` for another bit). `ALERT_NAMES` maps the bits: Freezer, Humidity, Noise, Charge full, Battery low, Ventilate, Sensor battery, Heat. `failureText` shows `ERR READ_ONLY` as `Set ble_console=2 to apply`, and words `ERR MUTED` and `ERR PLAY_FAILED`. `okText` shows `OK BEEP <N> <ms>`.
- `ChimeScreen` has a mute switch, a `Read mute state` button and one `Test` button for each alert bit. `SensorHealthScreen` has the button `Chime and alerts`. `MainActivity` sends `MUTE`, then `STATUS`, through `BleConsoleClient.send` only after a tap, and cancels the command in `onStop`.
- `StatusReplyTest` asserts `muted=0`, `muted=1` and a missing field. `ChimeCommandTest` asserts `MUTE 1`, `MUTE 0`, `BEEP 0` to `BEEP 7`, the failure of bit 8 and bit -1, and the reply texts.
- The local machine has no Android SDK. CI runs `gradle testDebugUnitTest assembleDebug`.

## Slot configuration screen in the Android app

- `SlotConfigScreen` lists slots 1 to 10 with the name, the fixed MAC address and the sensor format (badge of the last `Read sensor health`, `-` before a read). `SensorHealthScreen` has the button `Slot configuration`.
- The editor dialog defines the name and the fixed MAC address of a slot. `SlotConfig.define` checks the name (at most 12 characters from `A-Z`, `0-9` and `-`, upper-cased), the MAC format and a duplicate MAC of another slot, and returns `SET slotN_mac=<MAC>` then `SET slotN_name=<NAME>` (no name command for an empty name, because the firmware rejects an empty `slotN_name`).
- `Leeren` sends `SET slotN_mac=` (an empty value clears the fixed MAC). `SLOT CLEAR <N>` is not used: the firmware answers `ERR FIXED_MAC` for a slot with a fixed MAC.
- A switch toggles `slot_auto_assign` with `SET slot_auto_assign=<0|1>`; `PanelConfig.slotAutoAssign` reads it (default on). `PanelConfig.slotName` reads `slotN_name`; the Wear OS copy stays equal (`scripts/check-protocol-sync.sh`).
- `MainActivity` sends the commands through `SlotAssignment.run` (`BleConsoleClient.send`), reads `CONFIG` after the batch and refreshes `PanelConfig` and the cache. The first `ERR` stops the batch. `ERR READ_ONLY` sets `SlotAssignment.Result.readOnly` and shows the hint `Set ble_console=2 to apply`.
- `SlotConfigTest` asserts the commands for name and MAC, the clear command, the duplicate MAC conflict, the rejected names, and the `slot_auto_assign` commands. `SlotAssignmentTest` asserts the clear batch and the `ERR READ_ONLY` handling.
- The local machine has no Android SDK. CI runs `gradle testDebugUnitTest assembleDebug`.

## Console command ACK for the chime reminder

- `ACK <N>` (N is the bit number 0 to 7, like `BEEP <N>`) stops the reminder of `chime_repeat_h` for one active alert. `MUTE` silences each alert; `ACK` silences one. The alert stays on, the tiles and the `STATUS` mask do not change.
- `PersistentState::acknowledgeAlert` stores the bit in `PersistentState::alert_ack` (1 byte just before the checksum; a bit that is off in `alert_mask` is rejected as corrupt). It replies `OK ACK <N>`. An alert that is off gives `ERR NOT_ACTIVE` and no change. A repeated `ACK` of the same bit replies `OK ACK <N>` and records no second event. `ERR BAD_ARG` covers a missing bit, a bit above 7 and extra text. `STATE_VERSION` is 65.
- `AlertEvaluator::repeat` takes the acknowledged bits as last argument and skips them. `AlertEvaluator::ackAfter` clears a bit when its alert falls (`applyAlertStep`), so the next rise chimes again. A rising edge chimes as before.
- `ACK` is a write command: `ERR READ_ONLY` on BLE with `ble_console` 1. It needs no card, and `execute()` ignores it.
- `EventCode::ALERT_ACK` (13) records the alert mask as detail; `EventRing::MAX_CODE` is 13. `EventText` shows `Alert <name> acknowledged`.
- `STATUS` appends ` acked=<hex>` to the `POWER` line. `tests/fixtures/status_reply_sample.txt` and its CRC-32 follow; the companion parsers read the `POWER` line by field name, so they stay unchanged.
- Validation: the host tests, `arduino-cli compile rlcd_panel` and `swift test` pass. The Android tests run in CI only.

## Panel config over the BLE console in the Wear OS app

- **Code:** `wearos/RlcdWatch/.../console/BleConsoleClient.kt`, `.../protocol/ConsoleWire.kt` (both apps), `.../WatchDashboardScreen.kt`, `.../MainActivity.kt`, `.../complication/ClimateComplicationModel.kt`, `scripts/check-protocol-sync.sh`, `wearos/RlcdWatch/app/src/test/.../protocol/ConsoleWireTest.kt`.
- `ConsoleWire` moved from the `console` package to the `protocol` package of `android/RlcdPhone` and `wearos/RlcdWatch`. `scripts/check-protocol-sync.sh` compares `ConsoleWire.kt` with the other shared files.
- The watch has its own `BleConsoleClient` (port of the phone client): one GATT connection for each command, MTU 185, writes of `max(1, MTU - 3)` bytes, the `ReplyAssembler` CRC-32 check and a 10 s timeout.
- `WatchDashboardScreen` shows a `Read panel config` chip. A tap sends `CONFIG` and `MainActivity` stores a good reply through `ConfigCache`. A bad CRC-32, a timeout and an `ERR` reply keep the old cache and show the reason. After a good read the tile and the complication refresh.
- The tile, the dashboard and the complication use `PanelConfig.tileName`. `ClimateComplicationModel.roomName` holds the name for the content description.
- The manifest requests `BLUETOOTH_CONNECT`. `MainActivity` asks for it with `BLUETOOTH_SCAN` on SDK 31 and later before the first read.
- Validation: `./scripts/check-protocol-sync.sh` and `--self-test` pass. `ConsoleWireTest` covers the chunks for MTU 23 and 185, the names of `tests/fixtures/config_reply_sample.txt` and a bad CRC-32 that keeps the old cache.
- The local machine has no Android SDK. CI runs `gradle testDebugUnitTest assembleDebug`.

## Monthly alert statistics in the macOS app

- `AlertStats.load(from:)` (`AlertStats.swift`, `RlcdProtocol`) reads each `events_*` file of the archive folder (local files only, no console command). It parses a file with `EventsReply.parse(file:)` and joins the rows of all good files in name order. `AlertTimeline.build(rows:now:)` then builds the intervals.
- `AlertStats.build(intervals:)` splits an interval at each month boundary (Mac time zone). For each alert and each month `AlertMonthStat` holds `rises` (intervals with a known start that begin in the month), `hours` (the part of those intervals in the month) and `unknownStarts`. An interval with `startUnknown` counts only in `unknownStarts`, so a guessed start does not add to the hours. An interval from 31 January 23:00 to 1 February 01:00 gives 1 hour in each month and 1 rise in January.
- A file with a bad name (`LogArchive.isValidName`), a size above `ReplyAssembler.maxFileBytes`, an unreadable text or a bad line goes to `AlertStatsResult.skippedFiles`. The other files still count.
- `AlertStatsView` shows a Swift Charts bar chart of the active hours for each month, grouped by alert, with the rises as bar labels. The `Show alert statistics` button of `ArchiveView` fills `AppModel.alertStats`; a new archive folder clears it.
- `AlertStatsTests` write event files to a temporary folder and assert the counts and hours for each month, the month split, the `startUnknown` count after an `EVENTS_DROPPED` row and the skipped corrupt file.
- Validation: `swift build -c release` passes. `swift test` cannot run on the local Command Line Tools (no Testing macros); a `swiftc` harness with the same assertions passed, and the CI job `macOS companion tests` runs the suite.

## Ventilation advice in the Wear OS tile

- **Code:** `wearos/RlcdWatch/.../tile/ClimateTileService.kt`, `.../protocol/TelemetryCache.kt`, `.../MainActivity.kt`, `wearos/RlcdWatch/app/src/test/.../tile/ClimateTileModelTest.kt`, `.../protocol/TelemetryCacheTest.kt`.
- `MainActivity` stores the outdoor source in `TelemetryCache`: the tile index of a sensor slot (`saveOutdoorSlot`), or Open-Meteo (`saveOutdoorOpenMeteo`) with the last reading and its time (`saveOpenMeteoReading`). `readOutdoor` returns `CachedOutdoor`. A cycle of the source and a new reading refresh the tile.
- `ClimateTileModel.build` calls `VentilationGuide.analysiereLueftung` for the focused room (window closed) and stores the `titel` in `ClimateTileModel.advice`. The tile shows it as one line below the climate line.
- No advice for a stale or empty frame, a stale outdoor frame, an outdoor slot on another page than its frame, an outdoor tile without a reading, the outdoor tile as focused room, and an Open-Meteo reading older than 1 hour (`CachedMeteo.isStale`).
- The tile starts no BLE scan and no network request. It shows advice only after the user opened the app and chose an outdoor source. The messages for an open window stay in `MainActivity`.
- Validation: `./scripts/check-protocol-sync.sh` passes. The local machine has no Android SDK. CI runs `gradle testDebugUnitTest assembleDebug`.

### Android slot name without a fixed MAC and write lock

- `SlotConfig.define` accepts an empty MAC. A valid name with an empty MAC gives `SET slotN_name=<NAME>` (upper-cased), preceded by `SET slotN_mac=` when the slot holds a fixed MAC. A name and a MAC give `SET slotN_mac=<MAC>` first, then the name command. An empty name and an empty MAC give `SET slotN_mac=`. `nameError` rejects an invalid name in each case.
- `PanelConfig` of the phone app and the Wear OS app has `bleConsole` (`ble_console`, default 0) and `canWrite` (true only for `ble_console=2`). Both files stay equal except the `package` line.
- `SlotConfigScreen` disables the auto-assign switch, the `Bearbeiten` button, the `Leeren` button and the dialog confirm button while `canWrite` is false. It shows `Set ble_console=2 to apply` in that state.
- `SlotConfigTest` covers the name-only command, the name and MAC commands, a rejected name without MAC, the clear command and `canWrite`.
- Validation: `./scripts/check-protocol-sync.sh` passes. The local machine has no Android SDK, so `gradle testDebugUnitTest assembleDebug` did not run locally. CI runs it.

### Android slot rename fix

- `PanelConfig.tileName(index, activeMac)` of the phone app and the Wear OS app falls back in this order: `nameForMac(activeMac)`, `slotName(index)`, then `TelemetryFrame.DEFAULT_TILE_NAMES`. A `slotN_name` without a fixed MAC now shows in the tile title at once. Before, the tile showed `Slot N` until the user set a fixed MAC.
- `SlotConfigScreen` has one `Switch` for each slot. It sends `SET slotN_enabled=<0|1>` (`SlotConfig.setEnabled`) and reads the state with `PanelConfig.slotEnabled` (true unless `slotN_enabled=0`; the Wear OS copy stays equal). A disabled slot shows `Deaktiviert` and its `Bearbeiten` button is off. The switch needs `canWrite` and an idle screen. `PanelConfigTest` and `SlotConfigTest` assert the state and the commands.
- `SlotConfigScreen` no longer shows the text `(Name bleibt gespeichert)` after the name of a slot without a fixed MAC.
- `PanelConfigTest` of both apps asserts the slot name fallback for an unknown MAC and for a slot without a fixed MAC. Both `PanelConfig.kt` files stay equal except the `package` line.
- Validation: `./scripts/check-protocol-sync.sh` passes. The local machine has no Android SDK, so `gradle testDebugUnitTest assembleDebug` did not run locally. CI runs it.

## Rejected packet list in the macOS app

- `ConsoleCommand.rejects` sends `REJECTS`. The app sends it only on the `Read rejects` button, on USB and on BLE.
- `RejectsReply.parse` (`RejectsReply.swift`) reads the header `mac,format,reason,count`, one row for each MAC and the optional last row `DROPPED,<N>`. It throws `ParseError.badLine` for a wrong column count, an unknown format, an unknown reason, a count outside 1 to 255, an invalid or repeated MAC, and a misplaced `DROPPED` row.
- `RejectReason` (`FULL`, `UNASSIGNED`, `DISABLED`, `JUMP`, `WEAK`) gives the fix hint: `Free a slot`, `Assign to slot`, `Set slot<N>_enabled=1`, `Check the sensor position`, `Move the sensor nearer or lower auto_assign_min_rssi`.
- `ScannerView` shows the box `Rejected sensors` with MAC, format badge, reason, count and hint. A `FULL` row shows the `Free slot <N>` buttons of `AppModel.freeableSlots`. An `UNASSIGNED` row shows the `Assign to slot` menu of `SlotAssignment`.
- `RejectsReplyTests` cover the fixture `tests/fixtures/rejects_reply_sample.txt`, an empty log, the bad rows, the hints and the command line.
- Validation: `swift build` and `swift build -c release` pass. `swift test` does not run locally (Command Line Tools have no Testing macros); a `swiftc` harness checked the parser cases. CI runs `swift test`.

## Event codes for a lost and a recovered sensor slot

- `EventCode::SLOT_STALE` (14, `EventRing::MAX_CODE` 14) records each change of the stale state of a registry slot. The low byte of the detail is the slot number (1 to 10). The high byte is 0 for a lost slot (newly stale) and 1 for a recovered slot.
- `PersistentState::stale_slots` (`uint16_t`, 2 bytes just before the checksum, `PowerManager::STALE_SIZE`) holds one bit for each slot. `deserialize` rejects a bit above bit 9 and a bit of an empty slot. A power-on sets it to 0. `STATE_VERSION` is 66.
- `PersistentState::recordStaleChanges` runs after each BLE scan in `setup()` (`rlcd_panel.ino`). It compares `isStale` (with `DeviceConfig::staleTimeoutMs()`) of each valid slot with its bit and records one event for each change. A second scan without a change records no event.
- Ten slots that go stale in one scan record ten events. The ring holds 16 events, so a full ring drops the oldest event and `EVENTS_DROPPED` reports the loss.
- `PersistentState::resetSlot` (also `SLOT CLEAR`, `applyConfig` and `releaseDeadSlots`) clears the bit without an event.
- `EventText.describe` shows `Slot <N> lost` and `Slot <N> back`. A slot outside 1 to 10 or a high byte above 1 gives the raw text.
- `testSlotStaleEvents` covers the lost and back events with detail `0x0002` and `0x0102`, the quiet second scan, `resetSlot`, the round trip and the rejection of bit 10 and of a bit of an empty slot. `EventTextTests.slotStaleGivesLostAndBack` covers the texts.
- Validation: host tests (231 suites), `arduino-cli compile --profile rlcd_panel_s3 rlcd_panel` and `swift test` (437 tests) pass.

### Android salt check and two-point calibration

- `TwoPointCalibration.kt` (package `protocol`) ports `TwoPointCalibration` of `Calibration.swift`. `compute(naCl, mgCl2, current)` calculates gain and offset from one NaCl and one MgCl2 `SaltReply` of the same sensor. The raw means use the active gain and offset. The gain has steps of 0.002.
- `Result` holds `gainKey`, `offsetKey`, `gain`, `offset`, `rawNaCl` and `rawMgCl2`. `Failure` has the eight texts of the macOS app. A gain outside 0.90 to 1.10 and an offset outside -10.0 to 10.0 give an error and no command.
- `CalibrationScreen` shows the reference chips 0 to 10 and the section `Salt check` with the buttons `NaCl (75 %)` and `MgCl2 (33 %)`. A tap sends `SALT N` or `SALT N MGCL2` through `BleConsoleClient.send`. `SKIP`, `UNSTABLE` and `ERR BAD_TEMP` have readable texts.
- With both checks of one sensor and a read config, `MainActivity` runs `compute`. The button `Apply gain and offset` sends `SET` for the gain, `SET` for the offset, then `CONFIG` through `CalibrationBatch`. The first `ERR` stops the batch. `ERR READ_ONLY` shows `Set ble_console=2 to apply`. Each command needs a user tap.
- A changed value clears the stored checks. A new reference chip clears them too.
- `TwoPointCalibrationTest` uses the input values of the macOS `TwoPointCalibrationTests`. `CalibrationReplyTest` covers the `SALT 7` and `SALT 10 MGCL2` replies and the batch order for slot 7.
- Validation: `./scripts/check-protocol-sync.sh` passes. The local machine has no Android SDK and no `kotlinc`, so `gradle testDebugUnitTest assembleDebug` did not run locally. CI runs it.

## Wear OS sensor health screen

- `wearos/RlcdWatch/.../protocol/StatusReply.kt` and `SensorHealth.kt` are copies of the phone files (only the `package` line differs). `StatusReply.parseReply(reply)` parses a `ConsoleReply.Frame` and throws `ParseException` for a bad CRC-32, a timeout and an `ERR` reply (new in both apps).
- `scripts/check-protocol-sync.sh` lists `SensorHealth.kt` and `StatusReply.kt` in `FILES`.
- `WatchDashboardScreen` has the `Sensor health` chip. The chip opens `SensorHealthScreen`; it starts no command. The `Read sensor health` chip in the screen sends `STATUS` through `BleConsoleClient`. The system back gesture returns to the dashboard.
- `SensorHealthScreen` shows one `ScalingLazyColumn` row for each `SENSOR` line: name, state (`OK`, `STALE`, `BATLOW`, `EMPTY`), battery, RSSI and the rate of the `RECEPTION` line. A rate below 50 % and 3 or more NTP failures show a warning symbol.
- A bad CRC-32, a timeout or an `ERR` reply shows the reason and keeps the last good reply.
- Tests: `StatusReplyTest` (watch) parses `tests/fixtures/status_reply_sample.txt` (six `SENSOR` lines, states, `RECEPTION s3=0/12`, `muted`, bad CRC-32).
- Validation: `check-protocol-sync.sh` and `--self-test` pass. The local machine has no Android SDK, so `gradle testDebugUnitTest assembleDebug` did not run locally. CI runs it.

## Minimum signal strength for the automatic slot assignment

- `config.ini` key `auto_assign_min_rssi` (0 is off, else -100 to -40 dBm, default 0). `DeviceConfig::auto_assign_min_rssi` is an `int8_t`; `DeviceConfigParser::parseAutoAssignRssi` rejects -101, -39 and 5. `KEY_COUNT` is 95 (parser index 94).
- `SwitchBotRegistry::setMinAssignRssi` holds the limit. In step 3 of `upsert` a new MAC with an RSSI below the limit gets no free slot. The packet counts in `BleRejectStats` and `RejectLog` records it with `RejectReason::WEAK` (5, text `WEAK`). A full registry keeps the reason `FULL`.
- A slot that already holds the MAC keeps its updates at each RSSI. A slot with a fixed MAC (`slotN_mac`) takes its packet without a limit.
- `PersistentState` stores the limit as 1 `int8` byte at the end of the config trailer (after the rejected line numbers). `RejectLog::restore` accepts the reason 5 and rejects a higher one. `applyConfig` and `deserialize` pass the limit to the registry. `STATE_VERSION` is 67.
- The console command `REJECTS` shows `WEAK` in the reason column.
- `docs/config.example.ini` lists `auto_assign_min_rssi=0`. The macOS `ConfigSchema.entries` holds the key (range -100 to -40, 0 is off). `RejectReason.weak` in `RejectsReply.swift` gives the hint `Move the sensor nearer or lower auto_assign_min_rssi`.
- Tests: `testAutoAssignMinRssi` in `tests/test_firmware_logic.cpp` (limit -70: RSSI -80 gives no slot and one `WEAK` row, -60 gives a slot, a held slot updates at -90, a fixed MAC slot takes -95, -101, -39 and 5 are rejected lines, trailer round trip, bad trailer byte). `testConfigExampleTemplateInSync` passes. `ConfigSchemaTests` cover the range.

## Alert acknowledge buttons in the macOS diagnostics view

- `StatusReply.ackedMask` (`Replies.swift`) holds the `acked=<hex>` field of the `POWER` line. A missing field gives 0 (older firmware). A bad hex value throws `ParseError.badLine`.
- `ConsoleCommand.ack(bit)` sends `ACK <N>` for the bits 0 to 7. Another bit gives no line.
- `AppModel.acknowledge(bit:)` sends `ACK <N>`, then `STATUS`, only on a user action. `ERR NOT_ACTIVE` shows as `The alert is off`. `ERR READ_ONLY` shows as `Set ble_console=2 to apply`.
- `DiagnosticsView` shows one row for each active bit of `alertMask` with the alert name of `AlertMask`. A bit outside `ackedMask` has an `Acknowledge` button. A bit inside it shows `Acknowledged`.
- Tests: `ReplyTests` cover the shared status fixture, `acked=02`, a missing field, `acked=zz` and the `ACK` lines.

## Wear OS alert acknowledge action

- `wearos/RlcdWatch/.../protocol/AckCommand.kt`: `AckCommand.line(bit)` gives `ACK <bit>` for the bits 0 to 7 and throws `IllegalArgumentException` for another bit. `AckCommand.actionBits(rising)` lists the rising bits that get an action. `REPEATABLE` holds each bit except `CHARGE_FULL` (bit 4, value 16), like `AlertEvaluator::repeatableBits` of the firmware.
- `AckCommand.resultText(reply)` maps `OK ACK <N>` to `Acknowledged`, `ERR NOT_ACTIVE` to `The alert is off`, `ERR READ_ONLY` to `Set ble_console=2 to apply`, and a timeout or no reply to `Panel not in range`.
- `BleScanService.postAlertNotification` posts one notification for each rising repeatable bit (id `910 + bit`) with one `Acknowledge` action. The action starts `AckReceiver` with the bit as extra. Other rising bits (`CHARGE_FULL`) share the old notification id 901 without an action. An ACK result and a later alert keep the other actions.
- `AckReceiver` (registered with `android:exported="false"`) sends `ACK <N>` once through `BleConsoleClient.send` after a tap and replaces the notification of the bit with the result text. It runs one command at a time (a second tap is ignored), ends after 8 s because of the broadcast limit, checks `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` before it starts (text `Bluetooth permission missing`), and checks `POST_NOTIFICATIONS`. It sends nothing without a tap and does not retry.
- Tests: `AckCommandTest` covers `ACK 0` to `ACK 7`, bits -1 and 8, the four result texts, no action for `CHARGE_FULL`, and one action with bit 1 for `HUMIDITY`.
- Acknowledge all: when `AckCommand.canAcknowledgeAll(rising.rawValue, 0)` is true (two or more rising repeatable bits, `CHARGE_FULL` does not count), `BleScanService.postAlertNotification` adds the action `Acknowledge all` to each per-bit notification. The action starts `AckReceiver` with `EXTRA_ALL`. `AckReceiver` sends `ACK ALL` (`AckCommand.ALL`) once after the tap and shows `AckCommand.resultText` (`Acknowledged: <names>`) in the notification with id `925` (`ACK_ALL_NOTIFICATION_ID`). After `OK ACK ALL` the receiver cancels the per-bit notifications (ids `910` to `917`), because their actions are obsolete. The action appears only when two or more repeatable bits rise in the same frame; one later rising bit keeps its own `Acknowledge` action. The one-command, 8 s watchdog and permission rules stay the same. The protocol files did not change.
- Tests: `AckCommandTest` also asserts the rule for `HUMIDITY | VENTILATE` (true), `HUMIDITY | CHARGE_FULL` (false) and a single bit (false), and the text of `OK ACK ALL 22`.
- Validation: `check-protocol-sync.sh` passes (the protocol files did not change). The local machine has no Android SDK, so `gradle testDebugUnitTest assembleDebug` did not run locally. CI runs it.

### Android home screen widget

- `android/RlcdPhone/.../widget/ClimateWidgetModel.kt`: `ClimateWidgetModel.build(snapshot, nowMs, config)` gives a `WidgetState` with up to four rows (`PanelConfig.tileName`, temperature, humidity, badges `STALE` and `BAT LOW`), the active alert names and the data age. A tile without its valid bit that has a fixed MAC or a name shows `--` and `STALE`; a slot with the `SENSOR_BATTERY` alert shows `BAT LOW`. An empty store or a frame older than 35 minutes gives `--` and `Open the app`.
- `ClimateWidgetReceiver.kt`: `ClimateWidget` (Jetpack Glance `glance-appwidget:1.1.1`, 4 x 2 cells, `climate_widget_info.xml`) and `ClimateWidgetReceiver` render the rows. A tap opens `MainActivity`. The widget starts no BLE scan and no network request. It reads the last snapshot from `WidgetSnapshotStore` (`SharedPreferences`).
- `BleScanService` stores the snapshot and updates the widget only when the sequence number changes (`ClimateWidgetModel.shouldRequestUpdate`). The file writes run on a separate single thread.
- Limit: the widget redraws on a new frame and every 30 minutes (`updatePeriodMillis`, Android minimum). Without a scan the age text and the 35-minute cutoff can lag by up to 30 minutes.
- A page 1 frame merges into the stored snapshot and sets the receive time, so the age text then describes the newest page (same limit as the dashboard).
- Tests: `ClimateWidgetModelTest` reads `tests/fixtures/ble_telemetry_vectors.txt` and covers the four rows, the names, the alert names, `--` for an empty store and for a frame of 36 minutes, the `STALE` and `BAT LOW` badges, the same sequence number and the store round trip.
- Validation: `check-protocol-sync.sh` passes (the protocol files did not change). The local machine has no Android SDK, so `gradle testDebugUnitTest assembleDebug` did not run locally. CI runs it.

## Event log screen over the BLE console in the Android app

- **Code:** `android/RlcdPhone/.../protocol/EventsReply.kt`, `EventText.kt`, `EventLogScreen.kt`, `MainActivity.kt`, `PhoneDashboardScreen.kt`, `tests/fixtures/events_reply_sample.txt`.
- The phone could not read the event ring of the panel. The `Ereignisse` button now sends `EVENTS` over the BLE console after a tap and shows the rows newest first.
- `EventsReply.parse` follows `EventsReply.parse` of `Replies.swift`: `BEGIN EVENTS`, header `time,event,detail`, rows with 3 columns and an integer detail. A bad line, a missing header or a row with another column count throws `ParseException`. An `EVENTS_DROPPED` row gives `EventsLog.dropped` and is no row.
- `EventText.describe` is a port of the macOS `EventText`. It gives the same texts for the event codes 1 to 15 and the raw text `<event>,<detail>` for an unknown event or a detail outside the range. `EventText.isAbnormal` marks an abnormal `RESET`; the screen shows it in the error colour.
- A failed CRC-32, a timeout or an `ERR` reply gives one error text through `BleConsoleClient.errorText`. The command is not repeated. A read that `onStop` aborts shows `Abgebrochen. Erneut lesen.`
- `tests/fixtures/events_reply_sample.txt` holds 15 rows and the `CRC32` line. `EventsReplyTest`, `EventTextTest` and the macOS `EventTextTests.sharedEventsFixtureGivesTheTexts` read it.
- The local machine has no Android SDK. CI runs `gradle testDebugUnitTest assembleDebug`. `swift test` and `scripts/check-protocol-sync.sh` pass locally.

## Recent events card on the Android dashboard

- **Code:** `android/RlcdPhone/.../protocol/EventsReply.kt`, `PhoneDashboardScreen.kt`, `MainActivity.kt`, `EventsReplyTest.kt`.
- The dashboard has the card `Letzte Ereignisse`. It shows the newest 3 events as `EventText` lines, newest first, with `Zeit unbekannt` for an empty time and the error colour for an abnormal event.
- The button `Lesen` sends `EVENTS 3` over the BLE console. The app sends the command only after a tap, never on its own. The card shows `Ereignisse gingen verloren.` when the reply has the `EVENTS_DROPPED` row.
- The button `Alle Ereignisse` opens `EventLogScreen`. `EventLogScreen` keeps its full `EVENTS` read.
- `EventsReply.newestCommand(n)` returns `EVENTS <n>` for n 1 to 16. It throws `IllegalArgumentException` for another value.
- The BLE scan stops during the read. It starts again after the reply when it ran before and no console screen is open. A cancelled read (`onStop` or closing the event log) shows `Abgebrochen. Erneut lesen.`
- Tests: `EventsReplyTest` asserts `EVENTS 3`, `IllegalArgumentException` for 0, 17 and -1, and the parse of a 3-row reply with and without the `EVENTS_DROPPED` row. `EventLogStateTest` asserts the newest-first order and the limit of the card rows.
- The local machine has no Android SDK. CI runs `gradle testDebugUnitTest assembleDebug`.

## Rejected packet list over the BLE console in the Android app

- **Code:** `android/RlcdPhone/.../protocol/RejectsReply.kt`, `RejectsScreen.kt`, `SensorHealthScreen.kt`, `MainActivity.kt`, `tests/fixtures/rejects_reply_sample.txt`.
- The phone could not show which sensor the panel blocks. The `Rejected packets` button of the sensor health screen opens `RejectsScreen`. A tap on `Read rejected packets` sends `REJECTS` over the BLE console. The app sends nothing else on its own.
- `RejectsReply.parse` reads `BEGIN REJECTS`, the header `mac,format,reason,count`, rows and one optional `DROPPED,<N>` row at the end. A wrong column count, an invalid MAC, an unknown format, an unknown reason, a count of 0 or above 255, a repeated or early `DROPPED` row and a missing header throw `ParseException`.
- Each row shows the MAC, the format, the reason, the count and one fix text: `FULL` gives `Free a slot`, `UNASSIGNED` gives `Assign the MAC to a slot`, `DISABLED` gives `Enable the slot in slot_enabled_mask`, `JUMP` gives `Check the sensor position`, `WEAK` gives `Move the sensor nearer or lower auto_assign_min_rssi`. The screen shows the count of lost MACs when `DROPPED` is above 0.
- A failed CRC-32, a timeout or an `ERR` reply gives one error text through `BleConsoleClient.errorText`. The command is not repeated. A read that `onStop` aborts shows `Abgebrochen. Erneut lesen.`
- `RejectsReplyTest` reads `tests/fixtures/rejects_reply_sample.txt` and covers each row, the reasons, the counts, the dropped count, the CRC-32 frame, the fix texts and the error cases.
- The local machine has no Android SDK. CI runs `gradle testDebugUnitTest assembleDebug`.

## Ventilation advice in the macOS app

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/VentilationAdvice.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/Views.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/VentilationAdviceTests.swift`, `tests/fixtures/ventilation_vectors.txt`
- `VentilationAdvice.make(indoor:outdoor:)` ports `VentilationGuide.analysiereLueftung` with the same limits (48 and 53 %RH, hysteresis 0.1 and 0.5 g/m³, cold limit 5 °C, minimum indoor temperature 17.5 °C) and the same `VentilationAction` values. It gives the absolute humidity of both tiles and the difference in g/m³. It gives `nil` for a tile without a reading, a NaN value and a value out of range.
- `VentilationAdvice.outdoorTile` takes the outdoor tile from `outdoor_slot` of the last `CONFIG` reply (1 to 5, slot N is tile N). It gives `nil` for a missing or stale frame (older than 35 min, as in the Android app), a missing `CONFIG` reply, `outdoor_slot=0` and an invalid outdoor tile.
- The tile list shows the advice title and the difference (`AH +4.3 g/m³`) for each valid indoor tile. The outdoor tile shows no advice. The app sends no console command for the advice.
- `tests/fixtures/ventilation_vectors.txt` holds the shared rows (indoor and outdoor values, window state, minutes, action, difference). `VentilationAdviceTests`, and both Kotlin `VentilationGuideTest` classes read it. The Swift test also checks the difference and the `nil` cases.
- The Kotlin tests run in CI only. The local machine has no Android SDK.

## Power source event

- `EventCode::POWER_SOURCE` 15 records a change of the power source in the event ring. Detail 1 is USB power, detail 0 is battery power. `EventRing::MAX_CODE` is 15.
- `PersistentState::recordPowerChange(previous, current, now)` compares the `usb_powered` flag of the previous wake cycle (RTC copy) with the new reading in `refreshSensors()`. It records an event only when both readings are valid and the flag changed. A power-on records nothing. The RTC layout and `STATE_VERSION` do not change.
- The macOS `EventText` shows `USB power connected` and `Battery power`. Another detail shows the raw text.

## Memory line in STATUS

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `macos/RlcdCompanion/Sources/RlcdProtocol/Diagnostics.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `tests/fixtures/status_reply_sample.txt`, `tests/test_firmware_logic.cpp`
- The console command `STATUS` shows the new line `MEMORY free=<N> min=<N> largest=<N> psram=<N>` after the `AWAKE` line and before the `CRC32` line. The `CRC32` value covers the new line.
- The fields are bytes: `ESP.getFreeHeap()`, `ESP.getMinFreeHeap()`, `ESP.getMaxAllocHeap()` and `ESP.getFreePsram()`. `SerialStatusSnapshot` holds them in `free_heap`, `min_free_heap`, `largest_heap` and `free_psram`. The sketch fills them for each `STATUS` command.
- The values do not go to RTC memory. `PersistentState` and `STATE_VERSION` do not change.
- The macOS `MemoryMetrics.parse` reads the line. A missing line gives `nil`. A non-numeric or missing field throws `ParseError.badLine`.
- `DiagnosticsView` shows the four values and a warning symbol when `largest` is below 16384 bytes.
- Thus the user sees a memory shortage, for example after a long USB console session, without a look at the display.
- Host tests cover the line text, its position after `AWAKE` and the new CRC-32. `DiagnosticsTests` cover the four values, the missing line and `free=x`. The fixture `tests/fixtures/status_reply_sample.txt` holds the line, and the Android and Wear OS `StatusReplyTest` classes read it.

## PNG export of the panel image in the macOS app

- `PanelImage.pngData()` (`PanelImage.swift`) encodes the 400 x 300 pixels as a 1-bit grayscale PNG with `CoreGraphics` and `ImageIO`. A set pixel is black. The method gives `nil` when the encoder fails.
- `PanelView` has a `Save PNG` button next to `Refresh`. The button stays disabled until the first image exists.
- A click opens an `NSSavePanel` with the default name `panel_<YYYYMMDD_HHMMSS>.png`. The app writes `pngData()` to the chosen URL with an atomic write (`AppModel.exportPanelPng`) and shows `PNG saved: <name>` or `PNG export failed: <reason>` in `message`. An encoder failure gives a message and no save panel.
- The export sends no console command. It works on each transport, because it uses the last image only.
- `PanelImageTests.pngRoundTripKeepsCornersAndCheckerboard` reads the PNG back with `CGImageSource` and checks the size, the four corner pixels and a checkerboard pixel.

## Wear OS panel silence notification

- `SilenceWatch` (`protocol/SilenceWatch.kt`) is a pure state machine with an injected clock (`clock: () -> Long`, default `System.currentTimeMillis()`). `check()` returns `SILENT` once when the last frame is older than 35 min (`SILENCE_MS`). `onFrame()` returns `BACK` once for the first frame after `SILENT`. Without a frame since the service start it reports nothing. A clock that runs back gives no `SILENT` and moves the last frame time to the new time.
- `BleScanService` creates one `SilenceWatch` when the scan starts and calls `check()` once each minute on the main looper. No scan setting changes.
- `SILENT` posts the notification `Panel silent since <HH:mm>` (time of the last frame, id 902) on the channel `rlcd_silence_channel` without vibration. `BACK` cancels it. A stop of the scan cancels it too.
- `SilenceWatchTest` covers `SILENT` at 35 min plus 1 s, no second `SILENT` at 60 min, `BACK` once, a new `SILENT` after `BACK`, no report without a frame and a clock that runs back.

## Power source event texts in the Android app

- `EventText.describe` of the Android app has the case `POWER_SOURCE` (`EventCode` 15). Detail 1 gives `USB power connected`, detail 0 gives `Battery power`, the same texts as the macOS `EventText`. Each other detail gives the raw text `POWER_SOURCE,<detail>`.
- `tests/fixtures/events_reply_sample.txt` holds two `POWER_SOURCE` rows (detail 1 and detail 0) before the `FUTURE_EVENT` row and a new `CRC32` line.
- `EventTextTest` asserts the three texts. `EventsReplyTest` asserts 14 rows and the valid fixture CRC-32. The macOS `EventTextTests.sharedEventsFixtureGivesTheTexts` asserts the two texts.

## Panel clock in the Android app

- `PanelClock` (`protocol/PanelClock.kt`) is a port of `PanelClock.swift`. The time zone is `Europe/Berlin`. `format(epochMillis)` gives `YYYY-MM-DDTHH:MM:SS` or null for a year outside 2025 to 2099. `parse(text)` gives epoch milliseconds or null. `parseTimeReply(lines)` reads `TIME <time> valid=<0|1> osf=<0|1>` into `TimeReply` and throws `ParseException` for a bad line. `offsetSeconds(panel, phone)` is positive when the panel is ahead. `isTimeSetAllowed(level)` is true from `ble_console` 2.
- `SensorHealthScreen` has a clock card with the buttons `Read clock` and `Sync clock`. `Read clock` sends `TIME` and shows the offset in seconds, `valid` and `osf` (warning symbol for `valid=0` or `osf=1`). `Sync clock` sends `TIME SET <phone time>`, then `TIME`. `ERR READ_ONLY` shows `Set ble_console=2 to apply`.
- The app sends the commands only on a tap. It does not retry a failed command.
- `PanelClockTest` asserts the winter and summer text, null for the year 2100, the `TIME` parse, the `ParseException`, the offset sign and `isTimeSetAllowed`.

## Panel clock offset in the Wear OS app

- `protocol/PanelClock.kt` is a copy of the phone file and stays equal. `scripts/check-protocol-sync.sh` compares it (the `FILES` list has 13 entries).
- `SensorHealthScreen` has the chip `Read clock`. A tap sends `TIME` through `BleConsoleClient.send` once and shows `Clock offset: +5 s` (panel ahead of the watch) or `Clock offset: -3 s`, plus `valid` and `osf`.
- `SensorHealthText.isClockWarning` shows the warning symbol for an offset above 120 s in either direction, for `valid=0` and for `osf=1`. An offset of 120 s with `valid=1` and `osf=0` shows no warning.
- The watch never sends `TIME SET`. The app sends `TIME` only on a tap and does not retry a failed command. A failed CRC-32, a timeout or an `ERR` reply gives one error text.
- Tests: `PanelClockTest` asserts the `TIME` parse, the offset sign, the offset text and the warning for 121 s, `valid=0` and `osf=1` and no warning for 120 s.

## Mute change event

- `EventCode::MUTE_CHANGE` 16 records each change of the mute flag. `EventRing::MAX_CODE` is 16. The detail low byte is 1 for muted and 0 for unmuted. The detail high byte is the source: 0 `BOOT` press, 1 USB console, 2 BLE console.
- `SerialConsoleRunner::setMute(sink, muted, value, events, now, source)` records one event only when the flag changed. `MUTE 1` on a muted panel records nothing. `run_console` reads the RTC time again, as the `ACK` path does. The `BOOT` press records source 0.
- The RTC layout and `STATE_VERSION` do not change.
- The macOS and the Android `EventText` show `Chime muted (<source>)` and `Chime on (<source>)` with the sources `BOOT`, `USB` and `BLE`. An invalid state or source gives the raw text.
- Tests: the host test asserts detail `0x0101` (USB), `0x0200` (BLE), no event for an equal flag, code 16 accepted and code 17 rejected. `EventTextTests` and `EventTextTest` assert `0x0001`, `0x0101`, `0x0200` and the raw text of `0x0302`.

## Event log over the BLE console in the Wear OS app

- `protocol/EventsReply.kt` and `protocol/EventText.kt` are copies of the phone files and stay equal. `scripts/check-protocol-sync.sh` compares them (the `FILES` list has 12 entries).
- `SensorHealthScreen` has the chip `Event log`. It opens `EventLogScreen`. The system back gesture returns to the sensor health screen.
- `EventLogScreen` has the chip `Read events`. A tap sends `EVENTS` through `BleConsoleClient.send` once. The app sends no command without a tap.
- `EventLogState(isReading, rows, dropped, loaded, message)` keeps the rows oldest first. `visibleRows` gives the newest 8 rows, newest first. The card shows `HH:mm` and `EventText.describe(row)`. An abnormal event has the error color. A `dropped` count above 0 shows one text line.
- A failed CRC-32, a timeout or an `ERR` reply gives one error text. The app does not retry the command.
- Tests: `EventTextTest` asserts the texts for `RESET` 4, `SLOT_STALE` 259 and `POWER_SOURCE` 1, and reads `tests/fixtures/events_reply_sample.txt` to assert the newest 8 rows in newest-first order.

## Panel silence notification in the macOS app

- `SilenceWatch` (`RlcdProtocol`) is a pure state machine with an injected clock. It ports `SilenceWatch.kt` of the Wear OS app with the same limit of 35 min (`silenceSeconds`).
- `check()` returns `.silent` once when the last frame is older than the limit. `onFrame()` returns `.back` once for the first frame after `.silent`. Without a frame nothing is reported. A clock that runs back gives no report and anchors the last frame to the new time.
- `AppModel` calls `onFrame()` for each frame and `check()` at the 30 s tick. For `.silent` with enabled notifications and an app bundle it posts the notice `Panel silent since <HH:mm>` (identifier `rlcd_panel_silence`). A `.silent` event while notifications are off or without an app bundle posts nothing and is not repeated later. For `.back` it removes the delivered notice.
- Tests: `SilenceWatchTests` assert `silent` once at 35 min plus 1 s, no second `silent` at 60 min, `back` once at the next frame, `silent` again after `back`, no report without a first frame and no report for a clock that runs back.

## Wall humidity notification in the macOS app

- `VentilationAdvice.wallRh(tile:index:outdoor:config:)` returns the wall humidity as `Double?` with the rules of `wallText` (`nil` for `wall_frsi_pct` 0, the outdoor tile, an invalid tile and a bad result). `wallText` uses it and still shows `Wall <N>%`.
- `WallWatch` (`RlcdProtocol`) is a pure state machine with an injected clock and one state for each tile index 0 to 5. A tile rises at `riseRh` 80.0 %RH or more and releases below `releaseRh` 77.0 %RH. A rise gives one `.notify(tile:rh:)`. A second rise of the same tile within `repeatInterval` (21600 s) gives no result. A `nil` value releases the tile without a result.
- `AppModel` feeds `WallWatch` with each new telemetry frame (fresh outdoor tile only). With `notificationsEnabled` and an app bundle it posts one notice `<name>: wall <N>%` with the identifier `rlcd_panel_wall_<tile>`. It sends no console command.
- Tests: `WallWatchTests` cover the rise at 80.0, no second result at 81, the release at 76.9, no result 5 hours and one result 6 hours after a notice, the release by `nil` and separate tile states. `VentilationAdviceTests` cover `wallRh` (72.5 to 74.0, `nil` for `wall_frsi_pct=0`) and `Wall 73%`.

## Lost sensor notification in the macOS app

- `StaleWatch` (`RlcdProtocol`) is a pure state machine with an injected clock and one state for each tile index 0 to 10. It has the same rules as `StaleWatch.kt` of the Android and Wear OS apps. A tile that was valid once and stays invalid for `lostAfter` (35 min) gives `.lost(tile)` once. The next valid frame gives `.back(tile)`. A tile that was never valid, a tile outside 0 to 10 and a clock that ran back give no result.
- `AppModel` feeds `StaleWatch` with the tiles of each incoming frame page (page 0 gives tiles 0 to 5, page 1 gives tiles 6 to 10), because the merged frame keeps the tiles of the other page. With `notificationsEnabled` and an app bundle it posts one notice `<name>: no data for 35 min` with the identifier `rlcd_panel_stale_<tile>` for `.lost`. The name comes from `ConfigReply.name(forSlot:)`. For `.back` it removes the delivered notice. Without enabled notifications `StaleWatch` stays untouched.
- Tests: `StaleWatchTests` assert no result for a tile that was never valid, no result after 34 min, `.lost(2)` after 35 min, no second `.lost(2)`, `.back(2)` for the next valid frame, no result for tile 11 and for a clock that ran back.

## Free MicroSD space in STATUS

- `PersistentState::sd_free_mb` (`int32_t`, -1 is unknown) keeps the free card space in MB across the deep sleep. It uses 4 bytes just before the checksum. `rlcd_panel.ino` sets it after each card access that reads the free space (hourly write and config load). A power-on sets it to -1. A value below -1 is rejected. `STATE_VERSION` is 68.
- The console command `STATUS` appends ` free_mb=<N|-->` to the `SD` line. A console session can now see a full card before the hourly write fails.
- The macOS `StatusReply.sdFreeMb` and the shared Kotlin `StatusReply.sdFreeMb` and `sdStatus` (phone and watch) parse the field. `--` and a missing field give `nil` or `null`. A non-numeric value throws `ParseError.badLine` or `ParseException`.
- `DiagnosticsView` (macOS) and `SensorHealthScreen` (Android) show the free space and a warning below 100 MB.
- Tests: `tests/test_firmware_logic.cpp` asserts the `SD` line with a value and with `--`, the RTC round trip, the rejection of -2 and the `CRC32` line of `tests/fixtures/status_reply_sample.txt`. `ReplyTests`, and both `StatusReplyTest` classes assert the value, `null` for `--` and an error for `free_mb=x`.

## Readable event texts in the macOS event CSV export

- `CsvExport.events` writes the header `time,event,detail,text`. The fourth column holds `EventText.describe(row)`, for example `Slot 3 back` for `SLOT_STALE,259`. The first three columns stay unchanged.
- A text with a comma, a quote character or a line break is quoted by the CSV rules, and each inner quote is doubled. An unknown event gives the raw text `<event>,<detail>`, which the export quotes.
- The export sends no console command.
- Tests: `TelemetryLogTests.csvOfHistoryAndEvents` asserts the header, `Slot 3 back`, a quoted text with a comma and the quoted raw text of an unknown event.

## Clock set event

- `EventCode::CLOCK_SET` 17 records a change of the panel clock. `EventRing::MAX_CODE` was 17 then. The detail low byte is 0 for `TIME SET` and 1 for `RTC TRIM`. The detail high byte is the transport: 0 USB console, 1 BLE console.
- `SerialConsoleRunner::setTime(sink, rtc, drift, t, events, transport)` records one event with the written time after a successful RTC write. A bad date and a failed RTC write record nothing.
- `SerialConsoleRunner::trimRtc(sink, rtc, drift, events, now, transport)` records one event after a successful register write and readback. `ERR NO_DRIFT` and `ERR WRITE_FAILED` record nothing. `run_console` reads the RTC time again for the trim event.
- The RTC layout and `STATE_VERSION` do not change.
- The macOS, Android and Wear OS `EventText` show `Clock set (<transport>)` and `RTC trimmed (<transport>)` with the transports `USB` and `BLE`. A transport or action above 1 gives the raw text. The Android and Wear OS sources stay equal.
- `tests/fixtures/events_reply_sample.txt` holds two `CLOCK_SET` rows (detail 0 and 257) before the `FUTURE_EVENT` row and a new `CRC32` line.
- Tests: the host test asserts detail `0x0000` (USB `TIME SET`), `0x0101` (BLE `RTC TRIM`), no event for a bad date, a failed write and a failed trim, code 17 accepted and code 18 rejected. `EventTextTests` and both `EventTextTest` classes assert `0x0000`, `0x0101` and the raw text of `0x0002`.

## Onboard sensor fault event

- `EventCode::SHTC3_FAULT` 18 records the start and the end of an onboard `SHTC3` fault. `EventRing::MAX_CODE` is 18.
- `Shtc3Health::faultEdge(previous_run, result)` gives the detail of an edge, or -1 for none. A fault edge is `run` going from 2 to 3: low byte 1, high byte the `Shtc3Result` of the third failed read (1 `NACK`, 2 `SHORT`, 3 `CRC`). A recovery edge is `run` going from 3 or more to 0: detail 0.
- A run of 1 or 2, a fourth failure in a row and a power-on record nothing.
- `refreshSensors()` reads `run` before the `SHTC3` read and records one event for each edge after the RTC time is known.
- The RTC layout and `STATE_VERSION` do not change.
- The macOS, Android and Wear OS `EventText` show `Onboard sensor fault (<NACK|SHORT|CRC>)` and `Onboard sensor back`. Another detail gives the raw text `<event>,<detail>`. The Android and Wear OS sources stay equal. `EventFilter` sorts `SHTC3_FAULT` into `sensors`.
- `tests/fixtures/events_reply_sample.txt` holds two `SHTC3_FAULT` rows (detail 257 and 0) before the `FUTURE_EVENT` row and a new `CRC32` line.
- Tests: the host test asserts detail `0x0101` for three `NACK` reads, `0x0000` for the next `OK` read, no edge for two failures and an `OK` read, no second fault edge for a fourth failure, code 18 accepted and code 19 rejected. The three `EventText` tests assert both texts and the raw text for `0x0401`; `EventFilterTests` asserts the `sensors` category.

## Panel silence notification in the Android app

- `protocol/SilenceWatch.kt` of the Android app is a copy of the Wear OS file and differs only in the `package` line. `scripts/check-protocol-sync.sh` compares it (the `FILES` list has 14 entries).
- `BleScanService` creates one `SilenceWatch` when the scan starts. It calls `onFrame()` for each decoded frame and `check()` once each minute on the main looper (`SILENCE_CHECK_MS` 60000). The frame callback and the timer run on the main thread.
- `SILENT` posts the notification `Panel silent since <HH:mm>` (time of the last frame, id 901 `SILENCE_NOTIFICATION_ID`) on the alert channel `rlcd_ventilation_channel` without vibration. `BACK` cancels it. A stop of the scan, `onDestroy` and a new scan start cancel it too. Nothing posts before the first frame.
- `BleScanService.isSilenceTimerActive` is true only while the timer is queued. `BleScanServiceTest.stoppedServiceHasNoSilenceTimer` asserts it is false for a stopped service. `SilenceWatchTest` is a copy of the Wear OS test.

## Rejected packet list in the macOS diagnostics report

- `DiagnosticsReport.commands` holds `.rejects` after `.events`. The report ends with the section `## REJECTS`. The `Copy report` button sends `REJECTS` after `EVENTS`.
- With `includeNames` false, `DiagnosticsReport` replaces the MAC of each `REJECTS` row with `<hidden>`. The header, the format, the reason, the count and the `DROPPED,<N>` row stay. With `includeNames` true, the rows stay unchanged.
- A failed `REJECTS` reply gives the line `ERROR <reason>`. The other sections stay. The report holds no `CRC32` line.
- Tests: `DiagnosticsReportTests` build a report from `tests/fixtures/rejects_reply_sample.txt`. They assert the command order, the section order, the hidden and the kept MACs, the missing `CRC32` line and the `ERROR` line.

## Free MicroSD space in the Wear OS sensor health screen

- `SensorHealthText.sdFree(mb)` returns `SD frei <N> MB`, or `SD frei --` for null. `SensorHealthText.isSdLow(mb)` is true for a known value below `SD_LOW_MB` (100). The values equal the Android `SensorHealthText`.
- `SensorHealthScreen` shows the line below the NTP line when a `STATUS` reply exists. Below the limit, the line has the prefix `⚠ ` and the warning color.
- Tests: `SensorHealthTextTest` asserts `SD frei 14820 MB`, `SD frei --`, `isSdLow(99)` true, and `isSdLow(100)` and `isSdLow(null)` false.

## Event filter in the macOS event list

- `EventFilter` (`EventFilter.swift`) sorts each event name into one `Category`: `alerts` (`ALERT_RISE`, `ALERT_FALL`, `ALERT_ACK`, `BATTERY_LOW_SET`, `BATTERY_LOW_CLEAR`, `EMPTY_PROTECTION`), `sensors` (`SLOT_ASSIGN`, `SLOT_RELEASE`, `SLOT_STALE`, `SHTC3_FAULT`, `SENSOR_BATTERY`), `power` (`RESET`, `POWER_SOURCE`, `SD_FAULT`), `config` (`CONFIG_LOAD`, `CONFIG_SET`, `NTP_SYNC`, `MUTE_CHANGE`) and `other` (each other name, for example `EVENTS_DROPPED`, `CLOCK_SET` and an unknown name).
- `EventFilter.apply(rows, categories)` keeps the row order. An empty set returns all rows.
- `AppModel.eventCategory` holds the selection (`nil` shows all events). `AppModel.filteredEvents` applies it. The `Events` box shows a segmented `Picker` above the list. The `Export EVENTS (CSV)` button writes the filtered rows.
- Tests: `EventFilterTests` read `tests/fixtures/events_reply_sample.txt` and assert 3 alerts, 4 sensors, 4 power, 2 config and 4 other rows, `FUTURE_EVENT` in `other`, all rows in unchanged order for an empty set, and the order for a mixed set.

## Android event log CSV share

- `EventCsv.format(rows)` (`protocol/EventCsv.kt`) returns the header `time,event,detail,text` and one line for each `EventRow`, oldest first. The `text` column holds `EventText.describe(row)`. A field with a comma, a quote or a line break gets quotes, and each inner quote is doubled. An empty list gives only the header.
- `EventLogScreen` shows a `Share CSV` button while `state.rows` is not empty. `MainActivity.shareEventsCsv` sends the text with `Intent.ACTION_SEND`, `text/plain` and `Intent.createChooser`. The app sends no console command for it.
- Tests: `EventCsvTest` reads `tests/fixtures/events_reply_sample.txt` and asserts the header, 17 lines for 16 rows, the text `Slot 3 lost` of one `SLOT_STALE` row, a quoted field with a comma, a quote and a newline, and only the header for an empty list.

## Android seven-day CSV share

- `WeekCsv.format(rows, panelConfig)` (`protocol/WeekCsv.kt`) returns the header `WeekReply.HEADER` plus `,name` and one line for each `WeekRow` in the order of the reply. The temperature and humidity values have one decimal place; a `null` value gives an empty field. The `name` column holds `PanelConfig.tileName(sensor)`. A field with a comma, a quote or a line break gets quotes (`EventCsv.quote`, now `internal`). An empty list gives only the header.
- `WeekScreen` shows a `CSV teilen` button beside the read button. It is disabled while `state.rows` is empty. `MainActivity.shareWeekCsv` sends the text with `Intent.ACTION_SEND`, `text/csv` and `Intent.createChooser`. The app sends no console command for it.
- Tests: `WeekCsvTest` reads `tests/fixtures/week_reply_sample.txt` and asserts the header, the first row `0,0,2026-11-17,15.0,19.5,40.0,55.0,Office`, the empty fields of the gap day with the quoted name `"Keller, unten"`, one line for each row, and only the header for an empty list.

## Display refresh skip state in STATUS

- The console command `STATUS` shows the line `FRAME crc=<8 hex digits|--> skips=<N>` after `MEMORY` and before `CRC32`. It comes from the RTC copy of `FrameRefresh` (`SerialStatusSnapshot::frame`, set from `g_state.frame` in `rlcd_panel.ino`). `--` means CRC 0 (unknown); else 8 lowercase hex digits. The user can now see if the firmware skipped the panel flush. The RTC layout and `STATE_VERSION` do not change.
- macOS: `FrameMetrics.parse` (`Diagnostics.swift`) gives `nil` for a missing line or `crc=--` and `ParseError.badLine` for a bad field. `DiagnosticsView` shows the group `Display refresh`.
- Tests: `testStatusFrameLine` asserts `FRAME crc=-- skips=0`, `FRAME crc=1a2b3c4d skips=12` and the position before `CRC32`. `DiagnosticsTests` cover the parser. The fixture `status_reply_sample.txt` holds the line with a new `CRC32`.

## Wear OS chime test

- `SensorHealthScreen` shows an `Alert: <name>` chip and a `Test chime` chip below the mute chip. The first chip steps through the 8 names of `ChimeCommand.ALERT_NAMES` (bits 0 to 7). The second chip sends `BEEP <bit>` through `BleConsoleClient.send` (`MainActivity.testChime`).
- The watch shows `ChimeCommand.okText` for `OK BEEP` and `ChimeCommand.failureText` for an `ERR` reply (`ERR MUTED`, `ERR READ_ONLY`, `ERR PLAY_FAILED`). The command runs only on a tap and is not retried. `ChimeTestState.isTesting` blocks a second tap and the other console chips while a command runs. `onStop` clears the flag.
- Tests: `ChimeCommandTest` asserts `BEEP 0` and `BEEP 7`, the exception for bit 8, the text of `OK BEEP 7 420` and the texts of `ERR MUTED` and `ERR READ_ONLY`. The UI wiring has no host test.

## Wear OS week chart

- `SensorHealthScreen` shows a `Week chart` chip below the `Event log` chip. It opens `WeekScreen` (`ScalingLazyColumn`). The `Read week` chip sends `WEEK` through `BleConsoleClient.send` (`MainActivity.readWeek`). The command runs only on a tap and is not retried.
- `WeekScreen` shows one card for each day of the focused room (`WeekText.daysOf`, day 0 first): weekday and date (`Mo 17.11.`), the temperature range and the humidity range. A gap day shows `--` for both ranges. A failed reply shows `BleConsoleClient.errorText` (for example `Panel meldet: ERR READ_ONLY`). `onStop` and the back action cancel the client and clear the state.
- `protocol/WeekReply.kt` of the watch is a copy of the Android file and differs only in the `package` line. `scripts/check-protocol-sync.sh` compares it (the `FILES` list has 15 entries). `BleScanService` stays unchanged.
- Tests: `WeekReplyTest` parses `tests/fixtures/week_reply_sample.txt` (42 rows, date of day 0, null values for the gap day of sensor 2, `ParseException` for a wrong column count and a bad header). `WeekTextTest` covers the texts, the gap day and the seven weekday labels. The UI wiring has no host test.

## Wall surface humidity in the macOS app

- `ClimateMath.wallSurfaceRh(tInC:rhInPct:tOutC:frsi:)` (`ClimateMath.swift`) follows `ClimateMath::wallSurfaceRh` in `climate_math.h`. The wall temperature is `t_out + frsi * (t_in - t_out)`. The vapor pressure stays equal. The result clamps to 100 %RH. It gives `nil` for bad input, `frsi` outside 0.50 to 0.95 and `t_out` above `t_in`.
- `VentilationAdvice.wallText(tile:index:outdoor:config:)` returns `Wall <N>%` or `nil`. It reads `wall_frsi_pct` (divided by 100) and `outdoor_slot` from the `CONFIG` reply. It gives `nil` for key value 0, a missing config, a missing outdoor tile, the outdoor tile and an invalid tile.
- `AppModel.wallText(forTile:)` adds the freshness rules of `VentilationAdvice.outdoorTile`. `Views.swift` shows the text below the heat index of each fresh indoor tile. The panel shows `WALL --` for a bad result; the app hides the line.
- Tests: `tests/fixtures/wall_rh_vectors.txt` holds the shared vectors. `testWallRhFixtureVectors` (C++) and `WallRhTests` (Swift) assert them within 0.1 %RH. `WallRhTests` also asserts `Wall 73%` for 20 C, 50 %RH, 0 C and `wall_frsi_pct=70` and the `nil` cases. The UI wiring and `AppModel.wallText` have no host test.

## Wear OS rejected packet list

- `SensorHealthScreen` shows a `Rejected packets` chip below the `Week chart` chip. It opens `RejectsScreen` (`ScalingLazyColumn`). The `Read rejects` chip sends `REJECTS` through `BleConsoleClient.send` (`MainActivity.readRejects`). Only a tap sends it.
- `RejectsScreen` shows one card for each entry: the last 5 characters of the MAC (`RejectsText.shortMac`), and the format, the reason and the count (`RejectsText.detail`, for example `METER FULL x2`). It shows `DROPPED <N>` when MACs were lost and `No rejected packets` for an empty list. A failed reply shows `BleConsoleClient.errorText`. `onStop` and the back action cancel the client and clear the state.
- `protocol/RejectsReply.kt` of the watch is a copy of the Android file and differs only in the `package` line. `scripts/check-protocol-sync.sh` compares it (the `FILES` list has 16 entries).
- Tests: `RejectsReplyTest` parses `tests/fixtures/rejects_reply_sample.txt` (rows, reasons, `DROPPED` count), an empty list for the header only and `ParseException` for the reason `LOST`. `RejectsTextTest` covers the row and dropped texts. The UI wiring has no host test.

## Wake cycle line in STATUS

The console command `STATUS` shows the line `WAKE cause=<POWER_ON|TIMER|KEY|OTHER> boots=<N> battery_interval_s=<N>` after `FRAME` and before `CRC32`.

- `cause` is the wakeup cause of this wake cycle (`WakeupCauseCodec::text`). A failed RTC restore shows `POWER_ON`.
- `boots` is the RTC copy of `PersistentState::boot_count`.
- `battery_interval_s` is the interval that `PowerManager::powerConfig` gives on battery power at the current RTC time. It shows `night_wake_interval_s` in the quiet hours and `wake_interval_s` at other times and for an invalid RTC time. The line shows it also on USB power.
- The RTC layout and `STATE_VERSION` do not change.

## Wall surface humidity in the Android app

- `ClimateMath.wallSurfaceRh(tInC, rhInPct, tOutC, frsi)` follows `ClimateMath::wallSurfaceRh` in `climate_math.h`. It gives `null` for bad input, `frsi` outside 0.50 to 0.95 and `tOutC` above `tInC`. The result clamps to 100 %RH. The phone and the watch `ClimateMath.kt` are equal, so `scripts/check-protocol-sync.sh` passes.
- `WallText` (`protocol/WallText.kt`) reads `wall_frsi_pct` (divided by 100) and `outdoor_slot` from `PanelConfig.values`. `WallText.outdoorTile` takes the outdoor reading only from the panel tile of `outdoor_slot` (slots 1 to 5), not from Open-Meteo. `WallText.format(tile, index, outdoor, config)` returns `Wall <N>%` and a warning flag, or `null` for key value 0, a missing key, the outdoor card, an invalid tile and a missing outdoor tile.
- `WallText.isWarning(rhPct)` is true from 80.0 %RH. The flag uses the unrounded value. `PhoneDashboardScreen` shows the text below the detail line of each fresh indoor card and hides it on a stale frame. The warning shows in bold orange.
- Tests: both `ClimateMathTest` classes read `tests/fixtures/wall_rh_vectors.txt` and assert each row within 0.1 %RH, and assert `null` for `frsi` 0.49, `t_out` above `t_in` and NaN input. `WallTextTest` (phone) asserts `Wall 73%` for 20 C, 50 %RH, 0 C and `wall_frsi_pct=70`, the `null` cases and the warning limit. Gradle tests run only in CI (no Android SDK on the dev host).

## Wall surface humidity in the Wear OS app

- `WallText` moved from `PhoneDashboardScreen.kt` into `protocol/WallText.kt` of the phone. The watch holds a copy in the package `com.rlcdpanel.watch.protocol`. `scripts/check-protocol-sync.sh` lists `WallText.kt` in `FILES` (alphabetical order), so the two copies stay equal.
- The rules do not change: `Wall <N>%`, a warning from 80.0 %RH (`WARNING_RH_PCT`), `null` for `wall_frsi_pct=0`, the outdoor tile, a stale tile and a missing outdoor tile. The outdoor tile comes only from `WallText.outdoorTile` of the panel frame, not from Open-Meteo.
- `WatchDashboardScreen` shows the text on each fresh indoor tile below the detail line with the absolute humidity. The warning shows in bold orange. The line is hidden for a stale frame (`TelemetryCache.MAX_AGE_MILLIS`) and without a panel config. The BLE scan and the tile service do not change.
- Tests: `WallTextTest` (watch) asserts `Wall 73%` for 20 C, 50 %RH, 0 C and `wall_frsi_pct=70`, the `null` cases, `isWarning(79.9)` false and `isWarning(80.0)` true. The phone `WallTextTest` only gets the new import. Gradle tests run only in CI (no Android SDK on the dev host).

## Wake cycle metrics in the macOS diagnostics view

- `WakeMetrics.parse(line:)` (`Diagnostics.swift`) reads the `STATUS` line `WAKE cause=<POWER_ON|TIMER|KEY|OTHER> boots=<N> battery_interval_s=<N>`. An unknown cause, a missing field or a bad number throws `ParseError.badLine`. `WakeMetrics.parse(lines:)` and `DiagnosticsReply.wake` give `nil` for a missing line.
- `WakeMetrics.isNightInterval(config:)` is true when `night_wake_interval_s` is not 0 and `battery_interval_s` differs from `wake_interval_s`. A missing config or a missing key gives false.
- `DiagnosticsView` shows the group `Wake cycle` with the cause, the wake count and the battery interval in seconds. The `Night interval` badge appears while `isNightInterval` is true. The badge uses the last config read of the app.
- `DiagnosticsReport` keeps the raw `WAKE` line and adds the line `Wake cycle: <cause>, boots <N>, battery interval <N> s` after it. The line holds no name and no MAC.
- `DiagnosticsTests` and `DiagnosticsReportTests` cover the parser, the night interval and the report line.

## Panel diagnostics lines in the Android sensor health screen

- The shared Kotlin `StatusReply` (phone and watch, equal except the `package` line) parses the `STATUS` lines `RESET`, `SHTC3` and `MEMORY` into `StatusReply.reset` (`ResetState(reason, abnormalCount)`), `StatusReply.shtc3` (`Shtc3State(nack, shortReads, crc, run, retried)`) and `StatusReply.memory` (`MemoryState(free, min, largest, psram)`). A missing line gives `null`.
- `ResetState.isAbnormal` is true for `PANIC`, `INT_WDT`, `TASK_WDT`, `WDT` and `BROWNOUT`. `abnormal=--` gives a `null` count and keeps the reason. `Shtc3State.isAlert` is true from `run` 3. `MemoryState.isLow` is true when `largest` is below 16384 bytes. The limits equal `ResetMetrics`, `Shtc3Stats` and `MemoryMetrics` in `Diagnostics.swift`.
- A bad number throws `ParseException`.
- `SensorHealthScreen` (phone) shows the card `Panel diagnostics` with one line for each group and a warning symbol for each alert state.
- Tests: both `StatusReplyTest` classes assert the fixture values, the abnormal reset, the `null` count, the alert and low states, `null` for missing lines and `ParseException` for `SHTC3 nack=x`.

## Badge latch line in STATUS

- The console command `STATUS` shows `LATCH vent=<3 hex digits> dry=<3 hex digits> batlow=<3 hex digits> stale=<3 hex digits>` after `WAKE` and before `CRC32`. Each mask has 3 lowercase hex digits.
- `vent` (`VentilateLatch`) and `dry` (`DryLatch`): bit 0 is the onboard tile, bits 1 to 10 are the registry slots. `batlow` (`SensorBatteryLatch`) and `stale` (`PersistentState::stale_slots`): bits 0 to 9 are the registry slots.
- `SerialStatusSnapshot` has the new fields `ventilate_tiles`, `dry_tiles` and `stale_slots` (`uint16_t`). `sensor_battery_latch` is now `uint16_t`, so slots 9 and 10 keep their `BATLOW` state in the `SENSOR` line.
- The sketch fills the fields from the RTC copy of `g_state`. The RTC layout and `STATE_VERSION` do not change. The Kotlin parsers ignore the line.
- Tests: `testStatusLatchLine` covers the values, the empty line, the `SENSOR 10` state `BATLOW` and the line order. `tests/fixtures/status_reply_sample.txt` holds the line.

## Badge latch states in the macOS diagnostics view

- `LatchMetrics.parse(line:)` (`Diagnostics.swift`) reads the `STATUS` line `LATCH` into the `Int` masks `ventilate`, `dry`, `batteryLow` and `stale`. `DiagnosticsReply.latch` holds it; a missing line gives `nil`.
- A field that is not 3 hex digits, a `vent` or `dry` value above `0x7ff` and a `batlow` or `stale` value above `0x3ff` throw `ParseError.badLine`.
- `LatchMetrics.badges(forSensor:)`: sensor 0 is the onboard tile, sensors 1 to 10 are the slots. The result holds `V`, `D`, `BAT LOW` and `STALE` in this order. The onboard sensor never gets `BAT LOW` or `STALE`.
- `DiagnosticsView` shows the group `Panel badges` with one row for each sensor that has a badge (name from `StatusReply.sensors`) and `No badges` when each mask is 0.
- `DiagnosticsReport` adds the line `Panel badges: <sensor> <badges>; ...` (or `Panel badges: none`) after the raw `LATCH` line.
- Tests: `DiagnosticsTests` (values, badges, `nil`, bad fields) and `DiagnosticsReportTests` (report line).

## Noise metrics in the macOS diagnostics view

- `NoiseMetrics.parse(line:)` (`Diagnostics.swift`) reads the `STATUS` line `NOISE` into `db`, `band`, `peakDb`, `peakHour` and `eventsSum`. `DiagnosticsReply.noise` holds it; a missing line gives `nil`, `--` gives `nil` fields.
- A `band` other than `QUIET`, `MODERATE` and `LOUD`, a `db` or `peak_db` above 140, a `peak_hour` above 23 and a bad number throw `ParseError.badLine`.
- `DiagnosticsView` shows the group `Noise`: level, band, the peak of the day with its hour, and the event sum.
- `DiagnosticsReport` adds the line `Noise: <db> dB <band>, peak <N> dB at <H>:00` after the raw `NOISE` line (`peak --` without a peak).
- Tests: `DiagnosticsTests` (values, `nil` fields, missing line, bad fields) and `DiagnosticsReportTests` (report line).

## Mold hours in the STATUS sensor lines

- Each `SENSOR` line of the console command `STATUS` ends with ` mold_h=<N|-->`: the count of hourly samples above `mold_alert_limit_rh` in the 24-hour humidity history. The rules equal `DashboardView::formatMold`: `--` for fewer than 2 samples and for an empty, disabled or stale slot.
- The line count, the RTC layout and `STATE_VERSION` do not change.
- `StatusSensor.moldHours` (`Int?`) in the macOS, phone and watch parsers is `nil` or `null` for `--` and a missing field. A bad number gives `ParseError.badLine` or `ParseException`.
- The phone `SensorHealthScreen` shows `Schimmel <N> h` on each sensor row, with a warning symbol and the error colour above 0.
- Tests: `testSerialStatusMoldHours`, `ReplyTests`, and both `StatusReplyTest` classes. `tests/fixtures/status_reply_sample.txt` holds the field; the CRC literal is `7108cbc2`.

## Panel diagnostics lines in the Wear OS sensor health screen

- `SensorHealthScreen` of the Wear OS app shows the three lines `reset`, `shtc3` and `memory` after the free card space line.
- `SensorHealthText.reset`, `shtc3` and `memory` format the texts with the same templates as the phone app: `Reset <reason>, abnormal <N|-->`, `SHTC3 nack <N>, short <N>, crc <N>, run <N>, retried <N>` and `Heap frei <N> B, min <N> B, größter Block <N> B, PSRAM <N> B`.
- A missing line gives `Reset --`, `SHTC3 --` and `Heap --`.
- An alert state (`isAbnormal`, `isAlert`, `isLow`) prefixes the text with `⚠ ` and displays warning color.
- Tests in `SensorHealthTextTest` (watch and phone) assert the fixture texts, missing line fallbacks and warning conditions.

## Wall humidity notification in the Android app

- Pure state machine `WallWatch` (`com.rlcdpanel.phone.ventilation`) with an injected clock detects mold risk for tiles 0 to 5.
- A tile rises at 80.0 %RH or more and releases below 77.0 %RH. It emits at most one notification every 6 hours; a null or non-finite reading releases the tile without a result.
- `WallText.value(tile, index, outdoor, config)` returns the calculated wall humidity as `Double?`, and `WallText.format` uses it.
- `MainActivity` feeds `WallWatch` on each new telemetry frame in foreground and posts `<name>: Wand <N> %` through `rlcd_ventilation_channel` with notification ID `2000 + tile`.
- Tests: `WallWatchTest` asserts thresholds, suppression within 6 hours, re-trigger after 6 hours, null release, and per-tile isolation. `WallTextTest` asserts value range and format output.

## Event filter in the Android event log

- Pure object `EventFilter` (`com.rlcdpanel.phone.protocol`) groups events into `ALERTS`, `SENSORS`, `POWER`, `CONFIG` and `OTHER`.
- Name lists equal `EventFilter.swift`; any unlisted event falls into `OTHER`.
- `EventFilter.apply(rows, categories)` filters rows while keeping their order; an empty selection keeps all rows.
- `EventLogScreen` shows scrollable `FilterChip` items for the categories above the event cards and filters the displayed items in screen state.
- `Share CSV` retains all rows unfiltered; no console command is sent on filter changes.
- Tests in `EventFilterTest` assert the 18 rows of `events_reply_sample.txt`, exact counts per category, fallback to `OTHER` for unlisted codes, and set equality with `EventFilter.swift`.

## Lost sensor notification in the Android app

- `StaleWatch` (`protocol/StaleWatch.kt`) is a pure state machine with an injected clock. `update(tile, valid)` takes the tile index 0 to 10 and the valid bit of the frame. A tile that was valid once and stays invalid for 35 min (`LOST_AFTER_MS`) gives `Result.Lost(tile)` once. The next valid frame gives `Result.Back(tile)`. A tile that was never valid and a tile outside 0 to 10 give no result. A clock that runs back gives no `Lost` and moves the last valid time to the new time.
- `protocol/StaleWatch.kt` of the Wear OS app is a copy and differs only in the `package` line. `scripts/check-protocol-sync.sh` compares it (the `FILES` list has 18 entries).
- `BleScanService` creates one `StaleWatch` when the scan starts. It feeds each decoded frame through `StaleWatch.updateFrame` (page 0: tiles 0 to 5, page 1: tiles 6 to 10; a tile is valid when it holds a temperature). The BLE scan does not change.
- `Lost` posts the notification `<tile name>: keine Daten seit 35 min` (id 910 plus the tile index, `STALE_NOTIFICATION_BASE_ID`) on the alert channel `rlcd_ventilation_channel` without vibration. The name comes from `PanelConfig.tileName`. `Back`, a stop of the scan and a new scan start cancel it.
- `StaleWatchTest` covers the page mapping of `updateFrame`, a tile that was never valid, no result at 34 min, `Lost(2)` at 35 min, no second `Lost(2)`, `Back(2)`, independent tiles, a tile outside 0 to 10 and a clock that runs back.


## Sensor battery event

- `EventCode::SENSOR_BATTERY` 19 records the low battery state of each registry slot. `EventRing::MAX_CODE` is 19.
- `PersistentState::recordBatteryChanges(previous, current, limit_pct, now)` runs in `refreshSensors()` directly after `SensorBatteryLatch::update`. It records one event for each changed bit of `sensor_battery_latch`.
- The detail low byte is the slot number 1 to 10. The high byte is 1 for a low battery and 0 for a battery that is OK again.
- A limit of 0 (`sensor_battery_limit_pct`) and an equal latch record nothing. `resetSlot` clears the bit without an event.
- `SdLogCodec` names the event `SENSOR_BATTERY`. The RTC layout and `STATE_VERSION` do not change.
- The macOS, Android and Wear OS `EventText` show `Slot <N> battery low` for the high byte 1 and `Slot <N> battery OK` for the high byte 0. A slot outside 1 to 10 or a high byte above 1 gives the raw text `<event>,<detail>`. The Android and Wear OS sources stay equal. `EventFilter` (Swift and Kotlin) sorts `SENSOR_BATTERY` into `sensors`.
- `tests/fixtures/events_reply_sample.txt` holds the rows `SENSOR_BATTERY,260` and `SENSOR_BATTERY,4` before the `FUTURE_EVENT` row and a new `CRC32` line (21 rows with `EVENTS_DROPPED` in the macOS reply, 20 in the Kotlin parsers, 8 `sensors` rows).
- Tests: `EventTextTests` and both `EventTextTest` classes assert `Slot 4 battery low` (260), `Slot 4 battery OK` (4) and the raw text of detail 11 and 516.
- Tests: the host test asserts detail `0x0104` (0x000 to 0x008), `0x0004` (back), two events for 0x000 to 0x201, no event for an equal latch and for limit 0, the row `SENSOR_BATTERY` and the restore of code 19 and the rejection of code 20.

## Dropped jump event

- `EventCode::JUMP_DROP` 20 records each slot for which `JumpGuard` dropped a packet. `EventRing::MAX_CODE` is 20.
- `SwitchBotRegistry::countJump` sets a flag for the slot. `SwitchBotRegistry::takeJumped(index)` returns and clears the flag, like `takeAssigned`. `clearSlot` and a disabled slot clear the flag.
- `PersistentState::recordSlotEvents` records one `JUMP_DROP` event for each flagged slot after the `SLOT_ASSIGN` events. The detail is the slot number 1 to 10.
- Two drops of one slot in one scan give one event. A jump that a second packet confirms gives no event.
- `SdLogCodec` names the event `JUMP_DROP`. The RTC layout and `STATE_VERSION` do not change. The apps show the raw text `<event>,<detail>` until an `EventText` entry exists.
- Tests: the host test asserts one event with detail 3 for a jump dropped at `endScan`, one for a jump dropped by a near packet, one event for two drops in one scan, no event for a confirmed jump and after `clearSlot`, the row `JUMP_DROP` and the restore of code 20 and the rejection of code 21.

## Lost sensor notification in the Wear OS app

- `BleScanService` of the watch uses the shared `protocol/StaleWatch.kt` (same code as the phone, compared by `scripts/check-protocol-sync.sh`). It creates one `StaleWatch` when the scan starts and feeds each decoded frame through `StaleWatch.updateFrame`. The BLE scan mode and the tile refresh do not change.
- `Lost` posts the notification `<tile name>: no data for 35 min` (id 940 plus the tile index, `STALE_NOTIFICATION_BASE_ID`) on the channel `rlcd_silence_channel` and vibrates once for 120 ms (`vibrateNotice`). The name comes from `PanelConfig.load(context).tileName`. Without the notification permission, no notification and no vibration occur.
- `Back`, a stop of the scan and a new scan start remove the notification.
- `StaleWatchTest` of the watch is a copy of the phone test (package line only) and covers the same cases.

## Wall humidity notification in the Wear OS app

- `WallWatch.kt` moved from the phone package `ventilation` into the shared package `protocol`. The watch holds a copy (package line only), and `scripts/check-protocol-sync.sh` compares both files (entry in `FILES`).
- The rules stay: a tile rises at 80.0 %RH, releases below 77.0 %RH, and gives one `Notify` for each tile within a repeat window of 6 hours. A `null` value releases the tile without a result.
- The watch `MainActivity` feeds `WallText.value` of each tile (index 0 to 5) on each new frame, like `checkWallNotifications` of the phone. Without the notification permission (Android 13 and later), it does not feed `WallWatch`.
- For each `Notify`, it posts the notification `<name>: Wand <N> %` (id 2000 plus the tile index) and vibrates once with `vibrateNotice`.
- Tests: `WallWatchTest` of the phone moved into the package `protocol` without a change. The watch test is a copy with another package line.
- Limit: the check runs in the `MainActivity` while the app shows a frame, like the phone. The state lives in the activity, so a restart of the activity clears the repeat window.

## Firmware version in the Android sensor health screen

- `protocol/VersionReply.kt` (phone and watch, same code, compared by `scripts/check-protocol-sync.sh`; `VersionReply.kt` is in `FILES`) parses the `VERSION` line into `firmware`, `git`, `core`, `state` and `built` (`built=unknown` is valid). A missing `VERSION` line, a missing field or an empty field throws `ParseException`.
- `SensorHealthScreen` of the phone has the button `Firmware-Version lesen`. A tap sends `VERSION` through the BLE console (`MainActivity.readVersion`, same permission and busy rules as `TIME`). The row shows `Firmware <fw> (<git>), Zustand <state>, gebaut <built>`; an error shows the console error text. Closing the screen clears the result.
- Tests: `VersionReplyTest` asserts the fields of `VERSION fw=1.0.0 git=abc1234-dirty core=3.3.12 state=68 built=2026-11-03T04:05:06`, `built=unknown`, and `ParseException` without the line, without `git=` and for an empty `git=`.

## Firmware version in the Wear OS sensor health screen

- `SensorHealthScreen` of the watch has the chip `Firmware version`. A tap sends `VERSION` once through the BLE console (`MainActivity.readVersion`, same permission and busy rules as `TIME`). It uses the shared `protocol/VersionReply.kt`.
- The screen shows `FW <fw> (<git>)` in one line (`SensorHealthText.firmware`). A timeout shows `Panel not in range`; other failures show the console error text, an unreadable reply shows `Antwort nicht lesbar`. Stopping the activity clears the result.
- Tests: `SensorHealthTextTest.firmwareLineAndTimeoutText` asserts `FW 1.0.0 (abc1234)` and the timeout text.

## Noise line in STATUS

- The console command `STATUS` shows `NOISE db=<N|--> band=<QUIET|MODERATE|LOUD> peak_db=<N|--> peak_hour=<N|--> events_sum=<N>` after `LATCH` and before `CRC32`.
- `db` is the newest sample of `PersistentState::noise_history`; `--` for an empty history and for `NoiseHistory::INVALID`. `band` is `PersistentState::noise_band`. `peak_db`, `peak_hour` and `events_sum` come from `PersistentState::daily.noise`; `peak_db` and `peak_hour` are `--` for `DailyNoiseStats::NO_PEAK`.
- `SerialStatusSnapshot` has the new fields `noise_history`, `noise_band` and `daily_noise`. The sketch fills them from the RTC copy. The command does not start the microphones. The RTC layout and `STATE_VERSION` do not change. The Kotlin parsers ignore the line.
- Tests: the host test asserts the filled line, the empty line, the order `LATCH`, `NOISE`, `CRC32` and the `--` level for an invalid newest sample. Each full `STATUS` assert counts one more line. `tests/fixtures/status_reply_sample.txt` holds the line with a new `CRC32`.

## Event filter in the Wear OS event log

- `protocol/EventFilter.kt` is a copy of the phone file and stays equal. `scripts/check-protocol-sync.sh` compares it (`EventFilter.kt` is in `FILES`).
- `EventLogScreen` has one filter chip above the `Read events` chip. Each tap selects the next category: all, alerts, sensors, power, config, other, then all again (`EventFilterChip.next`, pure helper in `EventFilterChip.kt`). The chip text shows the title and the visible row count (`EventFilterChip.label`, for example `Alerts (3)`).
- `EventLogState.visibleRows(category)` filters with `EventFilter.apply` and keeps the newest 8 rows, newest first. The order of the rows does not change. The chip selection is UI state and resets when the screen closes. A filter without a match shows `Keine Ereignisse in dieser Kategorie.`.
- Tests: `EventFilterTest` (copy of the phone test: fixture counts, `OTHER` for unlisted names, order, Swift name sync), `EventFilterChipTest` (chip order, label, filtered visible rows).

## Seven-day CSV export in the macOS app

- `CsvExport.week(_ rows: [WeekRow])` (`CsvExport.swift`) returns `WeekReply.header` and one line for each row: `sensor,day,date`, then the four values with one decimal place. A gap day has empty fields for `nil`; an empty date stays empty.
- The button `Export week CSV` in `Views.swift` sits beside the other export buttons. It calls `model.exportCsv(name: "week.csv", ...)` and is disabled while `model.week` is empty.
- Tests: `CsvExportTests` parse `tests/fixtures/week_reply_sample.txt` with `WeekReply.parse`, export it with `CsvExport.week` and compare the result with the header and the rows of the fixture, including the empty fields of the gap day.

## Chime reminder line in STATUS

- The console command `STATUS` shows `CHIME repeat_h=<N> bits=<2 hex digits> last_s=<N|--> next_s=<N|-->` after `NOISE` and before `CRC32`.
- `repeat_h` is `chime_repeat_h`. `bits` is `AlertEvaluator::repeatableBits(alert_mask)` without the `alert_ack` bits. `last_s` is the time since `PersistentState::last_chime_ms`; `--` for stamp 0. `next_s` is `repeat_h * 3600 - last_s`, at least 0; `--` for `repeat_h` 0 and for `bits` 00. `next_s` is the nominal due time: mute, quiet hours and `chime_alert_mask` still skip the sound and restart the timer. Stamp 0 counts from clock zero. A stamp ahead of the clock wraps like `AlertEvaluator::repeat`.
- `SerialStatusSnapshot` has the new field `last_chime_ms`. The sketch fills it from the RTC copy. The RTC layout and `STATE_VERSION` do not change. The Kotlin parsers ignore the line.
- Tests: `testStatusChimeLine` asserts the filled line, an acknowledged alert, `ALERT_CHARGE_FULL`, an overdue reminder, stamp 0 and the order `NOISE`, `CHIME`, `CRC32`. Each full `STATUS` assert counts one more line. `tests/fixtures/status_reply_sample.txt` holds the line; the CRC literal is `7108cbc2`.

## Panel 24-hour history over the BLE console in the Android app

- `HistoryReply.parse(lines)` (`protocol/HistoryReply.kt`) is a port of `HistoryReply.parse` of `Replies.swift`. It reads `BEGIN HISTORY`, the header `sensor,age_h,temp_c,rh_pct,noise_db` and the rows as `PanelHistoryRow(sensor, ageHours, temperatureC, humidityPct, noiseDb)`. An empty field and `--` give `null`. A wrong first line, a wrong header, a wrong column count, a sensor outside 0 to 10, an age outside 0 to 23 and a non-decimal value throw `ParseException`.
- `HistoryScreen` has the button `Panel-Verlauf lesen`. `MainActivity.readPanelHistory` sends `HISTORY` through `BleConsoleClient` only after a tap (Bluetooth permission check, background scan stops, `closePanelHistory` restarts it). After a good reply the screen shows the panel curves of each sensor with a temperature value in place of the local curves, with age 0 at the read time (`PanelHistoryChart`, line break above 90 min). The button `Lokal` returns to the local curves. A failed read shows one message and keeps the local curves. The command runs at each `ble_console` level.
- `tests/fixtures/history_reply_sample.txt` holds a valid `CRC32` line. `HistoryReplyTest` reads it through `ReplyAssembler` and asserts the 6 rows, the first row, `null` for an empty `noise_db` field, and `ParseException` for a wrong header, 4 columns, sensor 11 and age 24.

## RTC trim in the Android sensor health screen

- The shared `StatusReply.kt` of the phone and the watch parses the `STATUS` line `RTC trim=<N|--> drift_ppm=<N|-->` into `RtcState(trim, driftPpm)` (`StatusReply.rtc`). `--` gives a `null` field and a missing line gives `null`. A bad number and a trim outside -64 to 63 throw `ParseException`. `RtcState.canTrim` is true for a drift of 3 ppm or more in either direction.
- `SensorHealthScreen` shows the row `RTC Trim <N>, Drift <N> ppm` and the button `RTC trimmen` only when `panelConfig.canWrite` is true and `canTrim` is true (`SensorHealthText.canTrim`). `MainActivity.trimRtc` sends `RTC TRIM` after a tap, then `STATUS` after an `OK`. `SensorHealthText.trimResult` shows `OK RTC TRIM <old> <new>`, `ERR NO_DRIFT` and `ERR READ_ONLY` as short texts.
- Tests: `StatusReplyTest` (phone and watch) and `SensorHealthTextTest`. `tests/fixtures/status_reply_sample.txt` holds the line `RTC trim=0 drift_ppm=5` after `RESET` with a new `CRC32` (`7108cbc2`).

## Chime reminder time in the macOS diagnostics view

- `ChimeMetrics.parse(line:)` (`Diagnostics.swift`) parses `CHIME repeat_h=<N> bits=<2 hex digits> last_s=<N|--> next_s=<N|-->` into `repeatHours`, `bits`, `lastS` and `nextS`. `--` gives `nil`. `repeat_h` above 24, `bits` that are not 2 hex digits, a bad number and a missing field throw `ParseError.badLine`. `DiagnosticsReply.chime` is `nil` for a missing line.
- `ChimeMetrics.reminderText` gives `Reminder off` for `repeatHours` 0, `No reminder due` for `bits` 0 or without `nextS`, else `Next reminder in <H> h <M> min`.
- `DiagnosticsView` shows the text below the alert acknowledge rows.
- Tests: `DiagnosticsTests` (`parsesChimeLine`, `chimeRejectsBadFields`, `chimeReminderTexts`).

- Tests: `testStatusChimeLine` asserts the filled line, an acknowledged alert, `ALERT_CHARGE_FULL`, an overdue reminder, stamp 0 and the order `NOISE`, `CHIME`, `CRC32`. Each full `STATUS` assert counts one more line. `tests/fixtures/status_reply_sample.txt` holds the line; the CRC literal is `b42a8075`.

## Daily mold hours in the seven-day history

- `WeekHistory` (`sparkline.h`) stores one more byte for each day and sensor: the hours above `mold_alert_limit_rh` from `HumidityHistory::countAbove`, 0 to 24. The value `0xFF` marks a gap day. `WeekHistory::advance` stores it with the minimum and maximum, and the midnight shift moves it with the day. A sensor without samples stores `0xFF`.
- The block in `PersistentState::week` grows from 173 to 390 bytes (77 mold bytes before the stamp). `STATE_VERSION` is 69. A stored value from 25 to 254 and a value beside a gap humidity pair are corrupt, so the RTC copy is rejected.
- `WeekHistory::restore` takes the hours from the `mold_h_<N>` columns of the daily file (`SdLogCodec::parseDailyRow`, `WeekHistory::RestoreRow::mold_h`). An empty field gives `0xFF`. A count above 24 rejects the row.
- The `WEEK` reply has the new last column `mold_h`: header `sensor,day,date,temp_min_c,temp_max_c,rh_min_pct,rh_max_pct,mold_h`, empty for a gap day. Without a card the user sees on which days a room was humid for a long time.
- Companion apps: the macOS `WeekReply.parse` and the Kotlin `WeekReply.parse` (phone and watch) accept 7 or 8 columns. `WeekRow.moldHours` is `nil` or `null` for 7 columns and for an empty field. A value outside 0 to 24 or a non-numeric value is a bad line. The Android `WeekCsv` writes the column `mold_h` before `name`.
- Tests: the host tests assert the stored hours, the gap marker, the rejection of 25, 254 and a count beside a gap pair, the restore value from a daily row, the `WEEK` header and the RTC round trip with version 69. `tests/fixtures/week_reply_sample.txt` holds the column and a new `CRC32` line.

## Dropped jump event texts in the companion apps

- `EventCode::JUMP_DROP` (20) has a text in the macOS, Android phone and Wear OS `EventText`: `Slot <N> reading jump dropped` for the detail 1 to 10. Each other detail (0, 11, 259) shows the raw text, for example `JUMP_DROP,3`.
- `EventFilter` (`EventFilter.swift` and both `EventFilter.kt`) puts `JUMP_DROP` into the category `sensors`. `EventFilterTest.kotlinNamesMatchSwiftNames` compares both lists.
- `tests/fixtures/events_reply_sample.txt` has the new row `JUMP_DROP,3` before `FUTURE_EVENT` (22 lines with `EVENTS_DROPPED`, 21 rows in the Kotlin tests) and the new CRC-32 `97608910`.
- Tests: `EventTextTests` and both `EventTextTest` classes assert `Slot 3 reading jump dropped`, `Slot 10 reading jump dropped` and the raw text for the details 0, 11 and 259. The filter and reply tests count one more row and one more `sensors` row.

## Noise line in the Android sensor health screen

- The `STATUS` line `NOISE db=<N|--> band=<QUIET|MODERATE|LOUD> peak_db=<N|--> peak_hour=<N|--> events_sum=<N>` gives the room noise without a card. Only the macOS app showed it.
- The shared `StatusReply.kt` (phone and watch, same source) holds `NoiseState(db, band, peakDb, peakHour, eventsSum)` and `StatusReply.noise`. A missing line gives `null`; `--` gives a `null` field. A band other than `QUIET`, `MODERATE` and `LOUD`, a `db` or `peak_db` above 140, a `peak_hour` above 23 and a bad number throw `ParseException`, like `NoiseMetrics` of `Diagnostics.swift`.
- `SensorHealthText.noise(state)` (phone) gives `Lärm <db> dB (<band>), Spitze <N> dB um <H> Uhr` and `Lärm --` for `null`. The peak part is left out when `peak_db` or `peak_hour` is `--`. `SensorHealthScreen` shows the row below the memory row in the card `Panel diagnostics`. `SensorHealthState.noise` carries it from `MainActivity`.
- Tests: `StatusReplyTest.noiseFromFixtureAndMissingValues` and `badNoiseLineThrows`, `SensorHealthTextTest.noiseText`.
- Limit: no Android SDK exists on the development host, so `gradle testDebugUnitTest assembleDebug` runs only in CI.

## Noise line in the Wear OS sensor health screen

- The watch uses `StatusReply.noise` (shared `StatusReply.kt`, same source as the phone). `SensorHealthText.noise(state)` gives `Lärm <db> dB <band>` in one line, `Lärm -- dB <band>` for a `null` `db` and `Lärm --` for `null`. The peak stays out, because the watch row has room for one short line.
- `SensorHealthScreen` shows the row below the memory row. The row has no warning mark.
- Tests: `SensorHealthTextTest.noiseText`.
- Limit: no Android SDK exists on the development host, so `gradle testDebugUnitTest assembleDebug` runs only in CI.

## Chime reminder time in the Android chime screen

- The `STATUS` line `CHIME repeat_h=<N> bits=<2 hex digits> last_s=<N|--> next_s=<N|-->` tells when the next chime reminder sounds. The phone chime screen ignored it.
- The shared `StatusReply.kt` (phone and watch, same source) holds `ChimeReminder(repeatHours, bits, lastS, nextS)` and `StatusReply.chime`. A missing line gives `null`; `--` gives a `null` field. `repeat_h` above 24, `bits` that are not 2 hex digits and a bad number throw `ParseException`, like `ChimeMetrics` of `Diagnostics.swift`.
- `ChimeText.reminder(reminder)` (phone) gives `Erinnerung aus` for `repeatHours` 0, `Keine Erinnerung fällig` for `bits` 0 or without `nextS`, else `Nächste Erinnerung in <H> h <M> min`. `ChimeScreen` shows it below the mute state after each `STATUS` read (`ChimeState.reminder`).
- Tests: `StatusReplyTest.parsesChimeLine` and `badChimeLineThrows`, `ChimeTextTest`.

## Mold hours in the Android week chart

- `WeekChartModel.mold(rows, sensor)` gives seven values of `WeekRow.moldHours` from day 6 (left) to day 0 (right). A gap day, a missing row, an empty field and a reply with the 7-column header give `null`.
- `WeekScreen` shows a third `Canvas` `Schimmelstunden (h)` below the humidity chart: one bar for each day with hours, a fixed scale of 0 to 24 h (`MOLD_MAX_H`), an outline for a `null` day and the label `0 bis 24, max. <N> h`. The chart stays hidden when each value is `null`.
- Tests: `WeekChartModelTest` (fixture `tests/fixtures/week_reply_sample.txt`).
- Limit: no Android SDK exists on the development host, so `gradle testDebugUnitTest assembleDebug` runs only in CI.

## Quiet hours line in STATUS

- The console command `STATUS` shows `QUIET start_h=<H> end_h=<H> active=<0|1|--> night_interval_s=<N>` after `CHIME` and before `CRC32`.
- `start_h` and `end_h` are `quiet_hours_start_hour` and `quiet_hours_end_hour`. `active` is `QuietHoursWindow::isActive` at the RTC time of the command; `--` for an invalid, unreadable or stopped RTC time. `night_interval_s` is `night_wake_interval_s` (0 is off). An equal start and end hour gives `active=0`.
- `SerialStatusSnapshot::quiet_active` is `int8_t` (-1 unknown); `SerialStatusSnapshot::quietFlag` derives it. The sketch reads the RTC for each `STATUS`. The RTC layout and `STATE_VERSION` do not change. The Kotlin parsers ignore the line.
- Tests: `testStatusQuietLine` asserts 23:30 (`active=1`), 12:00 (`active=0`), a stopped oscillator (`active=--`), an equal start and end hour and the order `CHIME`, `QUIET`, `CRC32`. Each full `STATUS` assert counts 29 lines now. `tests/fixtures/status_reply_sample.txt` holds the line; the CRC literal is `7108cbc2`.

## Mold hours in the Wear OS week screen

- `WeekText.mold(row)` gives `Schimmel <N> h` from `WeekRow.moldHours`, with the `SensorHealthText.WARNING` mark above 0 hours, and an empty text for `null` (7-column reply, gap day).
- `WeekScreen` shows the text below the humidity of each day row in a small grey line. An empty text draws no line, so a reply with 7 columns keeps the current rows.
- Tests: `WeekTextTest.moldText`.

## Console command ACK ALL

- `ACK ALL` stops the chime reminder of each active alert at once. Before, an app sent one `ACK <N>` for each bit and waited for the BLE cooldown each time.
- `PersistentState::acknowledgeAll(now)` calls `acknowledgeAlert` for each bit of `AlertEvaluator::repeatableBits(alert_mask)` that is not in `alert_ack`, lowest bit first. It returns the mask of the new bits. Each new bit records one `ALERT_ACK` event. A bit that is acknowledged already records nothing. `ALERT_CHARGE_FULL` is not in the mask.
- Reply `OK ACK ALL <2 hex digits>` with the new mask, or `ERR NOT_ACTIVE` when no bit is new (`SerialConsoleRunner::ackAllReply`). `ACK ALLX`, `ACK ALL 1` and other extra text give `ERR BAD_ARG`. `ACK <N>` is unchanged (`SerialCommand::ack_all` is false).
- `ACK ALL` is a write command: `ERR READ_ONLY` on BLE with `ble_console` 1. It needs no card. The RTC layout and `STATE_VERSION` do not change.
- Tests: the host tests assert `OK ACK ALL 22` with two events for mask 0x32, `OK ACK ALL 20` with one event, `ERR NOT_ACTIVE` for a second command and for mask 0x10, `ERR READ_ONLY`, and the `BAD_ARG` cases.

## Acknowledge action in the Android alert notification

- `AckCommand.kt` is a copy of the watch file in the phone package `protocol` and is in `FILES` of `scripts/check-protocol-sync.sh`.
- `BleScanService.postAlertNotification(rising)` posts one notification for each bit of `AckCommand.actionBits(rising)` with the action `Acknowledge` (notification id `930` + bit, `ackNotificationId`; the ids 910 to 920 stay with the stale notifications). Other rising bits keep the old notification (id 900). The action exists only when `PanelConfig.canWrite` is true (`ble_console=2`); else the old single notification stays.
- `AckReceiver` (not exported) runs after a user tap only. It checks `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`, sends `ACK <N>` once through `BleConsoleClient` (one command in flight, 8 s watchdog) and shows `AckCommand.resultText` in a short notification.
- Tests: `AckCommandTest` (phone) asserts that `HUMIDITY | CHARGE_FULL` gives `[1]`. `./scripts/check-protocol-sync.sh` and `--self-test` pass. The Gradle tasks did not run on this host (no Android SDK); the CI jobs run them.

## Panel history of one sensor in the Android app

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/HistoryCommand.kt`, `HistoryScreen.kt`, `MainActivity.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/HistoryCommandTest.kt`
- `HistoryCommand.line(sensor)` gives `HISTORY <N>` for 0 to 10 and `HISTORY` for `null`. Another number throws `IllegalArgumentException`.
- Each sensor card of `HistoryScreen` has the button `Nur diesen Raum lesen`. It sends `HISTORY <N>` and reads only the rows of this sensor. The button `Panel-Verlauf lesen` still reads all sensors.
- An older firmware answers `HISTORY <N>` with `ERR UNKNOWN` (the old parser takes no argument; `HistoryCommand.shouldFallBack`; other `ERR` lines do not retry). `MainActivity.readPanelHistory` then sends `HISTORY` once and keeps the rows of the sensor with `HistoryCommand.filter`.
- A one-sensor read shows only the card of this sensor. The button `Lokal` and a new read of all sensors bring the other rooms back.
- A one-sensor read with no usable values for the sensor shows `Keine Werte für diesen Raum` and keeps the local curves.
- Tests: `HistoryCommandTest` (command lines, the exception for 11 and -1, the fallback rule, the filter on `tests/fixtures/history_reply_sample.txt`).
- Limit: no Android SDK exists on the development host, so `gradle testDebugUnitTest assembleDebug` runs only in CI.

## Mold hours in the macOS week chart

- `WeekMold.points(rows:sensor:)` (`WeekMold.swift`, `RlcdProtocol`) gives 7 `WeekMoldPoint` values for the days 6 to 0 of one sensor, oldest first, with the axis label of `WeekChartView` (`MM-DD`, or `day <N>` without a stamp) and `moldHours`. A gap day, an empty field, a missing row and a reply with 7 columns give `nil` hours.
- `WeekChartView` shows a third chart `Mold hours` beside temperature and humidity: one `BarMark` for each day with hours and a fixed scale of 0 to 24 h. The chart stays hidden when each point is `nil`.
- Tests: `WeekMoldTests` (day order and hours of `tests/fixtures/week_reply_sample.txt`, gap day, 7-column reply).

## Event code for a failed BLE scan start

- `EventCode::SCAN_FAULT` (21, `EventRing::MAX_CODE` 21, `SdLogCodec::eventCodeName` `"SCAN_FAULT"`) records when `g_ble_scan->start` fails or recovers in `scanOnce()`.
- `PersistentState::scan_fault` (1 byte just before the checksum; a value above 1 is rejected; a power-on sets it to 0) holds the fault latch. `STATE_VERSION` is 70.
- `PersistentState::recordScanResult(started, now)` records one `SCAN_FAULT` event on an edge: detail 1 when the scan start changes from OK to failed, detail 0 when it changes from failed back to OK, and nothing when the state stays equal. Before `g_rtc_time.valid` is known, `recordScanStart` stores the result in `g_pending_scan_fault` and `refreshSensors()` flushes it once the RTC time is read.
- Tests: `testScanFaultEvents` covers the rising fault edge, repeated failures without filling the ring, the recovery edge, repeated good starts, `SdLogCodec::formatEventRow`, the rejection of `scan_fault` = 2, and the RTC round trip with `STATE_VERSION` 70.

## Badge latches in the Wear OS sensor health screen

- The panel tiles show the badges `V`, `D`, `BAT LOW` and `STALE`. The `STATUS` line `LATCH vent=<3 hex> dry=<3 hex> batlow=<3 hex> stale=<3 hex>` gives the same state. Only the macOS app read it.
- The shared `StatusReply.kt` (phone and watch, same source) holds `LatchState(ventilate, dry, batteryLow, stale)`, `StatusReply.latch` and `LatchState.badges(sensor)`, with the rules of `LatchMetrics` in `Diagnostics.swift`: `vent` and `dry` up to `0x7ff`, `batlow` and `stale` up to `0x3ff`, the order `V`, `D`, `BAT LOW`, `STALE`, no `BAT LOW` and no `STALE` for sensor 0, no badge outside 0 to 10. A missing line gives `null`. A value that is not 3 hex digits or is above its limit throws `ParseException`.
- `SensorHealthText.badges(latch, sensor)` gives the text `V · STALE`; the `SensorHealthScreen` row of each sensor shows it as `Badges <text>` and shows no line for a sensor without a badge.
- Tests: `StatusReplyTest.parsesLatchLine`, `latchBadgeOrderAndOnboardRules` and `badLatchLineThrows`, `SensorHealthTextTest.badgeText`.
- Limit: no Android SDK exists on the development host, so `gradle testDebugUnitTest assembleDebug` runs only in CI.

## Quiet hours in the macOS diagnostics view

- `QuietMetrics.parse(line:)` (`Diagnostics.swift`) parses `QUIET start_h=<H> end_h=<H> active=<0|1|--> night_interval_s=<N>` into `startHour`, `endHour`, `active` (`Bool?`, `nil` for `--`) and `nightIntervalS`. An hour above 23, an `active` value other than `0`, `1` and `--`, a missing field and a bad number throw `ParseError.badLine`. `DiagnosticsReply.quiet` is `nil` for a missing line.
- `DiagnosticsView` shows the group `Quiet hours` (`22:00 to 07:00`, `active now` or `not active` or `--`, and `Night interval <N> s` or `Night interval off`).
- `DiagnosticsReport` adds the summary line `Quiet hours: 22:00 to 07:00, active` (or `not active` / `--`) after a valid `QUIET` line.
- Tests: `DiagnosticsTests.parsesQuietLine` and `quietRejectsBadFields`, `DiagnosticsReportTests.quietSummaryIsInTheReport`.

## Acknowledge all button in the macOS diagnostics view

- `ConsoleCommand.ackAll` (`Replies.swift`) sends `ACK ALL`. `ConsoleCommand.ackMessage` formats `OK ACK ALL <2 hex digits>` as `Acknowledged: <names>` (lowest bit first) and keeps the texts for `ERR NOT_ACTIVE` and `ERR READ_ONLY`.
- `ConsoleCommand.canAcknowledgeAll(alertMask:ackedMask:)` is true when two or more repeatable active alert bits (excluding `CHARGE_FULL`) are not acknowledged yet.
- `DiagnosticsView` shows the button `Acknowledge all` above the alert rows when `canAcknowledgeAll` is true. `AppModel.acknowledgeAll()` queues `.ackAll` and `.status`.
- Tests: `ReplyAssemblerTests.ackCommandLines`, `ackErrorTexts` and `canAcknowledgeAllRule`.

## Console commands CALIB and SALT for slots 6 to 10

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/sparkline.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`.
- `CALIB N`, `SALT N` and `SALT N MGCL2` accept `N` from 0 to 10 (0 is the onboard sensor, 1 to 10 are the registry slots, `MAX_SWITCHBOT_SENSORS`). `CALIB 11`, `SALT 11` and `SALT 11 MGCL2` give `ERR BAD_ARG`. This replaces the older range 0 to 5 in the sections above.
- The commands read the 24-hour histories of `ClimateHistory` (6 to 10 included). The proposal lines use the keys `slot6_temp_offset_c` to `slot10_temp_offset_c` and `slot6_rh_offset_pct` to `slot10_rh_offset_pct` with the active offsets and the key limits of the slot keys.
- `CALIB N` lists `SKIP slot6 hours=<n>` to `SKIP slot10 hours=<n>` for a slot without 6 common hours. `SALT 7` with fewer than 6 samples gives `SKIP slot7 hours=<n>`.
- The reply format, the RTC layout and `STATE_VERSION` do not change. The replies for 0 to 5 stay byte-equal.
- Host tests (`testSerialCalibSalt6To10`, `testSerialCalibSaltRepliesUnchangedFor0To5`) cover `CALIB 10` and `SALT 10` with the key `slot10_rh_offset_pct`, `SALT 10 MGCL2`, the `SKIP` reply of `SALT 7`, the `ERR BAD_ARG` of reference 11 and golden replies of `CALIB 0`, `SALT 3` and each reference 0 to 5 for both commands.

## Calibration of slots 6 to 10 in the macOS app

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/Calibration.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/CalibrationView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/CalibrationTests.swift`.
- `ConsoleCommand.calib`, `.salt` and `.saltMagnesium` accept 0 to 10 and give `nil` outside this range. The error texts of `AppModel.calibrate`, `saltCheck` and `saltMagnesiumCheck` say "0 to 10".
- `CalibrationSensors.selectable(status:)` lists the sensors of the pickers in `CalibrationView` (reference and salt sensor): 0 to 5 always, and each slot 6 to 10 only when its `STATUS` state is `OK`, `STALE` or `BATLOW` (`EMPTY` and `DISABLED` show no reading). A selected slot that leaves the list resets the picker to the onboard sensor. Without a `STATUS` reply only 0 to 5 show. `AppModel.calibrationSensors` feeds both pickers.
- `SaltReply.parse` keeps the proposal key `slotN_rh_offset_pct` of slots 6 to 10 (checked by `ConfigSchema.validate`). Proposals of `CALIB` and `SALT` go through `ConfigBatch` (`SET` commands, then `CONFIG`) as before.
- Tests (`calibAndSaltLinesForSlots6To10`, `saltReplyWithSlot8Key`, `pickerShowsSlots6To10OnlyWithReading`) cover `CALIB 10`, `SALT 6`, `nil` for 11, a `SALT slot8` reply with `slot8_rh_offset_pct` and the picker filter.
- Do not record review findings in this file. Name them in the review report and in the pull request description.

## Alert limit editor in the Android app

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/AlertLimits.kt`, `AlertLimitScreen.kt`, `MainActivity.kt`, `SensorHealthScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/AlertLimitsTest.kt`.
- The button `Alert limits` of the sensor health screen opens `AlertLimitScreen`. It edits `mold_alert_limit_rh` (40 to 95), `mold_alert_delay_h` (whole hours, 0 to 12, 0 is off; label `Verzögerung Schimmelalarm (h)`, shown below the mold limit; same `SET` batch and stop rules), `dry_alert_limit_rh` (0 or 20 to 45), `dry_alert_delay_h` (whole hours, 0 to 12, 0 is off; label `Verzögerung Trockenalarm (h)`, shown below the dry limit; same `SET` batch and stop rules), `freeze_alert_limit_c` (-20.0 to 15.0), `heat_alert_limit_c` (0 or 27 to 45), `ventilate_limit_rh` (50 to 80) and `sensor_battery_limit_pct` (0 to 50). The ranges equal the firmware limits in `device_config.h`.
- `AlertLimits.error` checks one input against the range and the number form (a comma counts as decimal point; only `freeze_alert_limit_c` takes one decimal). `AlertLimits.plan` gives one `SET <key>=<value>` for each changed key in the fixed order of `AlertLimits.LIMITS`, no command for an unchanged value, and refuses the whole list when one value is invalid.
- `AlertLimits.run` sends the commands one by one, then `CONFIG` (same batch as `SlotAssignment.run`). The first `ERR` line stops the batch before `CONFIG`; the summary names the failed reply and the count of applied keys.
- The edit fields and the `Anwenden` button show only when `PanelConfig.canWrite` is true (`ble_console=2`). Otherwise the screen shows the cached values read-only with the `ble_console=2` hint. The button `CONFIG lesen` refreshes the cache.
- No firmware change, no RTC layout change. Tests: `AlertLimitsTest` (ranges, mold delay range and wire text, fixed order, unchanged value, invalid value, stop after `ERR BAD_VALUE`, `CONFIG` last).

## Calibration of slots 6 to 10 in the Android app

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/SaltCommand.kt`, `CalibrationScreen.kt`, `MainActivity.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/SaltCommandTest.kt`, `CalibrationReplyTest.kt`.
- `SaltCommand.line(sensor, magnesium)` accepts 0 to 10 and throws `IllegalArgumentException` for another value. `MainActivity.readSalt` skips a sensor outside this range. `CALIB N` uses the same reference numbers.
- `CalibrationSensors.selectable(rows, keep)` lists the sensors of the picker in `CalibrationScreen`: 0 to 5 always, and each slot 6 to 10 only when its `STATUS` state is `OK`, `STALE` or `BATLOW`. Without a `STATUS` read (`healthState.rows` empty) only 0 to 5 and the picked reference (`keep`) show. `CalibrationScreen` shows a hint while no slot 6 to 10 is listed. `MainActivity.readCalibration` and `readSalt` skip a reference outside 0 to 10.
- `CalibrationReply.parse` and `SaltReply.parse` keep the proposal keys `slot6_*` to `slot10_*` (`CalibrationKeys` already accepted them).
- No firmware change, no RTC layout change. Tests: `SaltCommandTest` (`SALT 10`, exception for 11 and -1), `CalibrationSensorsTest` (picker filter, kept reference), `CalibrationReplyTest.regressionGuardProposalKeysOfSlots6To10` (`slot9_temp_offset_c`; a guard, the parser already accepted the keys).

## Diagnostics report share in the Android app

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/DiagnosticsReport.kt`, `SensorHealthScreen.kt`, `MainActivity.kt`, `DiagnosticsReportTest.kt`
- `DiagnosticsReport.build(version, status, rejects, includeNames)` builds one plain text report from the last `VERSION`, `STATUS` and `REJECTS` replies. It has the line `Firmware: <fw> (<git>), state <N>, built <time>`, one `Sensor <N> <name>:` line for each `SENSOR` line, the line `SD: status <S>, free <N> MB`, the `Reset`, `SHTC3`, `Panel badges` (from the `LATCH` line) and `Noise` lines, and the `## REJECTS` section with the header, the rows and `DROPPED,<N>`.
- The `Wake cycle` and `Quiet hours` lines of the macOS report need parsers that the Android `StatusReply` does not have yet. The Android report leaves them out.
- A missing `VERSION` reply gives `Firmware: --`. A missing `STATUS` or `REJECTS` reply gives `--` lines.
- The report hides the sensor names and the foreign MACs as `<hidden>`. It has no `NTP` line and no Wi-Fi data.
- The button `Bericht teilen` in `SensorHealthScreen` sends the text with an `ACTION_SEND` intent (`MainActivity.shareDiagnosticsReport`). It sends no console command. It uses the replies that the app read before. The button is off while a read runs. Closing the health screen clears the stored `REJECTS` log.
- `DiagnosticsReportTest` reads `tests/fixtures/status_reply_sample.txt` and `tests/fixtures/rejects_reply_sample.txt`.

## Wear OS salt check screen

- **Code:** `wearos/RlcdWatch/.../protocol/SaltCommand.kt`, `SaltText.kt`, `CalibrationReply.kt` (holds `SaltReply`), `CalibrationBatch.kt`, `SaltScreen.kt`, `SensorHealthScreen.kt`, `MainActivity.kt`, `scripts/check-protocol-sync.sh`
- The four protocol files are copies of the phone files (only the `package` line differs). `SaltText` needs `SaltReply` and `CalibrationBatch.failureText`, so `CalibrationReply.kt` and `CalibrationBatch.kt` come with them. The script lists all four in `FILES`.
- `SensorHealthScreen` shows a `Salt check` chip below the `Week chart` chip. It opens `SaltScreen` (`ScalingLazyColumn`) for the focused room. The `Run NaCl check` chip sends `SALT <N>` through `BleConsoleClient.send` (`MainActivity.readSalt`). Only a tap sends it. The command is not retried.
- The screen shows `SaltText.outcome` (`SKIP`, `UNSTABLE`, or the mean and the reference) and, with a proposal, the line `Vorschlag: <key>=<value>`. `ERR BAD_TEMP` and `ERR READ_ONLY` have readable texts.
- The screen shows the proposal only. The watch sends no `SET`. The user applies the value in the phone app or the macOS app.
- No firmware change, no RTC layout change. Tests: `SaltCommandTest` and `SaltTextTest` (phone test data), `SaltScreenTextTest`.
- Validation: `./scripts/check-protocol-sync.sh` and `--self-test` exit 0. The local machine has no Android SDK, so `gradle testDebugUnitTest assembleDebug` did not run locally. CI runs it.

## Battery runtime preview in the macOS config editor

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/RuntimePreview.swift`, `Sources/RlcdCompanion/ConfigEditorView.swift`, `Tests/RlcdProtocolTests/RuntimePreviewTests.swift`
- `RuntimePreview.hours(...)` scales the `RUNTIME h` value of the last `STATUS` with the ratio of the drain rates of the current and the edited schedule. The drain rate is the wake cycles per hour weighted by the awake time: `(day_h / (wake_interval_s + awake) + night_h / (night_wake_interval_s + awake)) / 24`. The sleep current is not modelled, so the value is an estimate.
- The awake time follows `BatteryHistory::estimate`: the `AWAKE avg_ms` mean counts from 8 samples and above 2000 ms, else the constant 10 s applies. A `RUNTIME h` value at the cap of 2388 h gives no preview; a result stops at 2388 h. A quiet hour outside 0 to 23 gives `--`.
- `RuntimePreview.nightHours` gives the hours of the quiet window (22 to 7 is 9 of 24 hours; equal start and end is 0). A `night_wake_interval_s` of 0 uses `wake_interval_s` for all 24 hours. The quiet window comes from the draft or the `CONFIG` values, else from the `QUIET` line.
- `RuntimePreview.hours(config:draft:runtime:awake:quiet:)` builds the current schedule from the `CONFIG` values and the edited schedule from the draft. A draft value that is no integer, or an interval outside the key range (`wake_interval_s` 30 to 900, `night_wake_interval_s` 0 or 60 to 1800), gives `--`.
- `ConfigEditorView` shows `About <N> h on battery` below the `night_wake_interval_s` field. It shows `--` without an `AWAKE` mean or a `RUNTIME h` value. It shows nothing before the first diagnostics read. It sends no console command.
- The firmware does not change; the RTC layout and `STATE_VERSION` do not change.
- Validation: `swift test` (543 tests), `swift build -c release`, `./scripts/check-protocol-sync.sh` and `--self-test` pass locally.

## Acknowledge all button in the Android chime screen

- **Code:** `android/RlcdPhone/.../protocol/AckCommand.kt`, `StatusReply.kt`, `ChimeScreen.kt`, `MainActivity.kt`, `wearos/RlcdWatch/.../protocol/AckCommand.kt`, `StatusReply.kt`, `AckCommandTest.kt`, `StatusReplyTest.kt`
- `AckCommand.ALL` is `ACK ALL`. `AckCommand.canAcknowledgeAll(alertMask, ackedMask)` is true when two or more repeatable active bits (without `CHARGE_FULL`) are not acknowledged. `AckCommand.resultText` turns `OK ACK ALL <2 hex digits>` into `Acknowledged: <names>` (lowest bit first, for example `Humidity, Ventilate` for `22`). The other replies keep their texts, and a malformed hex field gives `Acknowledged`.
- `StatusReply.alertMask` and `StatusReply.ackedMask` parse `alerts=` and `acked=` of the `POWER` line (`null` for a missing or bad field). The phone and watch copies stay equal except for the `package` line (`scripts/check-protocol-sync.sh`).
- `ChimeScreen` shows the button `Alle bestätigen` when `ChimeState.canAcknowledgeAll` and `PanelConfig.canWrite` are true. `MainActivity.acknowledgeAll` sends `ACK ALL`, then `STATUS`, only after a tap. The result text stays below the buttons. `ERR NOT_ACTIVE` shows `The alert is off`, `ERR READ_ONLY` shows `Set ble_console=2 to apply`.
- The firmware does not change; the RTC layout and `STATE_VERSION` do not change. The button needs one `STATUS` read first (`Read mute state`).
- Tests: `AckCommandTest` (rule for mask `0x32`, `CHARGE_FULL`, `OK ACK ALL 22`, `ERR NOT_ACTIVE`) `StatusReplyTest.parsesAlertAndAckedMasks` and `ChimeStateTest` (button rule with null masks).
- Validation: `./scripts/check-protocol-sync.sh` and `--self-test` exit 0. The local machine has no Android SDK, so `gradle testDebugUnitTest assembleDebug` did not run locally. CI runs it.

## Runtime and awake time lines in the Android sensor health screen

- **Code:** `android/RlcdPhone/.../protocol/StatusReply.kt`, `wearos/RlcdWatch/.../protocol/StatusReply.kt`, `android/RlcdPhone/.../SensorHealthScreen.kt`, `MainActivity.kt`, `StatusReplyTest.kt` (phone and watch), `SensorHealthTextTest.kt`.
- The shared `StatusReply.kt` parses the `STATUS` line `RUNTIME h=<N|--> samples=<N> cycles=<N.N|--> scan_saved_ms=<N>` into `RuntimeState(hours, samples, cycles, scanSavedMs)` (`StatusReply.runtime`) and the line `AWAKE last_ms=<N|--> avg_ms=<N|--> max_ms=<N|--> samples=<N>` into `AwakeState(lastMs, avgMs, maxMs, samples)` (`StatusReply.awake`). `--` gives a `null` field. A missing line gives `null`. A bad number (`h=x`, a negative value, `cycles` without one decimal digit or a missing field) throws `ParseException`. The phone and watch copies stay equal except for the `package` line.
- `SensorHealthScreen` shows `Restlaufzeit <N> h`, `Ladezyklen <N.N>` and `Wachzeit Ø <N> ms` (`avg_ms`) in the card `Panel diagnostics`; `--` replaces a missing value (`SensorHealthText.runtimeHours`, `cycles`, `awake`). The lines never show a warning. `h` is `--` on USB power, because the estimate is invalid there.
- The firmware does not change; the RTC layout and `STATE_VERSION` do not change.
- Tests: `StatusReplyTest.parsesRuntimeAndAwakeLines` and `badRuntimeAndAwakeLinesThrow` (phone and watch) and `SensorHealthTextTest.runtimeAndAwakeTexts`. `tests/fixtures/status_reply_sample.txt` holds the two lines after `POWER`; the CRC literal is `62a64947`.
- Validation: `./scripts/check-protocol-sync.sh` and `--self-test` exit 0. The local machine has no Android SDK, so `gradle testDebugUnitTest assembleDebug` did not run locally. CI runs it.

## Open window event texts and notification in the macOS app

- `EventText.describe(_:config:)` shows `WINDOW_OPEN` (code 22) as `Window open in <room>` (high byte 1) and `Window closed in <room>` (high byte 0). The low byte is the tile 0 to 10.
- `EventText.roomName(tile:config:)` takes the name from the `ConfigReply` (`onboard_name`, `slotN_name` by fixed MAC). Without a name it shows `Onboard` or `Slot <N>`.
- A high byte above 1, a tile above 10 or a detail outside 0 to 65535 gives the raw text `WINDOW_OPEN,<detail>`. `describe(_:)` without `config` keeps the old signature.
- `EventFilter.Category.climate` is new (title `Climate`); it holds `WINDOW_OPEN`. The event list picker shows it. `EventTextTests.windowOpenIsClimate` covers it.
- `WindowWatch` (`EventText.swift`) keeps the rows of earlier event lists. The first list only sets the baseline. A later list gives the tile of each new open row, oldest first. A row counts by time, detail and its place among equal rows, so an open row that a later row of the same list closed gives nothing. Rows with an empty time (invalid RTC) can match after the ring dropped its oldest row; the limit stays.
- `AppModel.checkWindowNotifications` posts one notification `Window open in <room>` (`rlcd_panel_window_<tile>`) for each new open row, when notifications are on and the app runs as a bundle. A closed row posts nothing. Without a `CONFIG` read the notification shows `Slot <N>`; `clearPanelData` resets the watch for a new panel. The watch also runs while notifications are off, so a later switch-on does not replay old rows.
- The event list view uses `model.lastConfig` for the room names.
- The firmware does not change; the RTC layout and `STATE_VERSION` do not change.
- Tests: `EventTextTests.windowOpenAndClosedUseTheRoomName`, `windowOpenWithUnknownHighByteOrTileGivesRawText`, `windowOpenIsClimate`, `windowWatchReportsOnlyNewOpenEvents`, `windowWatchSkipsAnOpenRowThatALaterRowClosed`, `windowWatchCountsEqualRowsWithEmptyTimeOnce`, `openedWindowTileRejectsTileAboveTenAndOtherRows`.

## Runtime line in the Wear OS sensor health screen

- **Code:** `wearos/RlcdWatch/.../SensorHealthScreen.kt`, `SensorHealthTextTest.kt`.
- `SensorHealthText.runtimeHours` shows `Runtime <N> h` and `SensorHealthText.cycles` shows `Cycles <N.N>` from the shared `StatusReply.runtime` (`RuntimeState`). `h=--` gives `Runtime --` and `cycles=--` gives `Cycles --`. A missing `RUNTIME` line gives `null`, so the screen shows no row.
- `SensorHealthScreen` shows both rows below the SD line and above the reset line. The rows never show a warning.
- The firmware, the shared `StatusReply.kt`, the RTC layout and `STATE_VERSION` do not change.
- Tests: `SensorHealthTextTest.runtimeTexts` and `runtimeTextsFromFixtureAndMissingLine`.
- Validation: `./scripts/check-protocol-sync.sh` and `--self-test` exit 0. The local machine has no Android SDK, so `gradle testDebugUnitTest assembleDebug` did not run locally. CI runs it.

## Console command CONFIG <key>

- **Code:** `rlcd_panel/serial_console.h` (`SerialConsole::parse`, `SerialConsoleRunner::showConfigKey`), `rlcd_panel/rlcd_panel.ino` (`run_console`), `tests/test_firmware_logic.cpp`.
- `CONFIG <key>` accepts each key of `DeviceConfigParser::KEYS` (exact match). The reply is the one line `<key>=<value>` and the `CRC32` line over it, for example `mold_alert_limit_rh=70`.
- The value comes from `DeviceConfigText::format` of the RTC copy (`g_state.config`). A name or MAC that is not set gives `<key>=` with an empty value.
- `ERR BAD_KEY` for a key outside `KEYS` (also a wrong case or `key=value`). `ERR BAD_ARG` for extra text after the key (`CONFIG mold_alert_limit_rh x`). `CONFIG` without an argument keeps its reply byte for byte; `CONFIG ` with a trailing space still gives `ERR UNKNOWN`.
- The command needs no card access (`SerialConsoleRunner::execute` ignores `CONFIG`). It is a read command, so it runs on USB and on BLE at each `ble_console` level.
- The parser stores the key in `SerialCommand::set_key`. RTC layout, `STATE_VERSION` and `KEY_COUNT` do not change.
- Tests: `testSerialConfigKeyCommand` (parser, each key, no card, BLE gate) and `testSerialConfigKeyReply` (`mold_alert_limit_rh=70` with CRC, float, text and empty keys, each key of `KEYS`, the full reply byte-equal to name lines plus `SOURCE` plus CRC).
- Validation: host tests, `arduino-cli compile rlcd_panel`, `./scripts/check-protocol-sync.sh` and `--self-test` pass locally.

## Room comparison chart in the macOS app

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/ClimateCompare.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/ClimateChartView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/ClimateCompareTests.swift`.
- `ClimateChartView` has the modes `Sensors` (the old view) and `Compare rooms`. The mode `Compare rooms` draws the temperature or the humidity of 2 to 6 selected sensors of the selected `ClimateLog` month as lines in one chart, one color for each sensor. The user selects the sensors with toggle buttons (default onboard and slot 1) and the value with a segmented picker. The view reads local files only and sends no console command.
- `ClimateCompare.build(rows:sensors:metric:config:)` gives one `Series` for each selected sensor in the selection order, with one sample for each row. An empty field (or a row without the sensor) is a gap: `Series.segments` ends a run there, so the line breaks. It returns `nil` for fewer than 2 or more than 6 sensors, a repeated sensor and an index outside `ClimateLog.sensorNames`.
- `Chart.axisRange` is the smallest to the largest value over all series, `nil` without a value; a flat range widens by 0.5 on each side.
- `ClimateCompare.name(sensor:config:)` uses `ConfigReply.name(forSlot:)` of the active config (`onboard_name`, `slotN_name` of the fixed MAC) and falls back to `ClimateLog.sensorNames`.
- `ClimateCompareTests` cover the series order, the gap, the axis range, the empty case, the flat range, the bad selections and the config names.

## Feels-like and absolute humidity curves in the macOS climate chart

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/ClimateMath.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/ClimateCompare.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/ClimateChartView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/ClimateMathTests.swift`.
- The value picker of the `Compare rooms` mode has two new values: `Feels like` (heat index in the unit of the chart) and `Absolute humidity` (g/m³).
- `ClimateMath.feelsLikePoint(tempC:humidityPct:)` and `ClimateMath.absoluteHumidityPoint(tempC:humidityPct:)` call `heatIndexC` and `absoluteHumidityGm3` for one `ClimateLog` reading. They return `nil` when the temperature or the humidity is empty or out of range, so the line breaks at that point.
- `ClimateCompare.Metric` has the cases `feelsLike` and `absoluteHumidity`. `ClimateCompare.value(of:_:)` selects the value of a reading. The view reads local files only and sends no console command.
- Tests: `curvePointsMatchFixtureRow` (row `32.0 | 70.0` of `tests/fixtures/climate_math_vectors.txt`: heat index 40.41, absolute humidity 23.57) and `curvePointsSkipMissingValues` (no point for an empty humidity or temperature field).
- Validation: `swift test` (560 tests), `swift build -c release`, `./scripts/check-protocol-sync.sh` and `--self-test` pass locally.

## Room comparison chart in the Android history screen

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/HistoryScreen.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/TelemetryHistory.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/TelemetryHistoryTest.kt`.
- `HistoryScreen` shows the card `Räume vergleichen` in the local view (not in the panel history view) when at least one tile has a value. One `Canvas` draws the temperature or the humidity of each active tile of the last 24 hours, one color and one legend entry (room name from `PanelConfig.tileName`) for each tile. Two buttons select the value. All curves use one axis range, shown as `Achse <min>–<max> <unit>`.
- `TelemetryHistory.comparePoints(nowMillis, metric)` returns `ComparePoints`: `series` has the `windowPoints` of each tile with at least one value in the window (ascending by tile index, no entry for an empty tile), `min` and `max` span the values of all listed tiles (`null` without a value). A missing value in a list stays a gap.
- Tests: `comparePointsListsTilesWithValuesAndSharesTheRange` and `comparePointsGivesNoEntryForEmptyTilesAndNoRangeWithoutValues`.
- Validation: `gradle testDebugUnitTest assembleDebug` runs in CI only (no local Android SDK).

## Vibration pattern for each alert on the Wear OS watch

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/AlertVibration.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/BleScanService.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/AlertVibrationTest.kt`.
- `AlertVibration.pattern(alerts)` returns the timing array for `VibrationEffect.createWaveform` (delay, on, off, on, ...). The lowest set bit selects the pattern: `FREEZER` three short pulses (120 ms), `HUMIDITY` two long pulses (400 ms), `HEAT` four short pulses, each other bit one long pulse. The gap is 120 ms. Mask 0 gives an empty array and no vibration.
- `BleScanService.vibrateAlert(alerts)` uses the rising alert bits of a frame, so several new bits give the pattern of the lowest bit. `MainActivity.vibrateAlert(alerts)` uses the same rule for the ventilation stop and abort advice with `AlertMask.VENTILATE`.
- Tests: `AlertVibrationTest` covers the freezer, humidity and heat arrays, one pulse for other bits, the lowest-bit rule for `HUMIDITY | HEAT` and the empty array for mask 0.
- Validation: `gradle testDebugUnitTest assembleDebug` runs in CI only (no local Android SDK).

## Serial console command IDENTIFY to find the connected panel

- **Code:** `rlcd_panel/serial_console.h` (`SerialCommandKind::IDENTIFY`, `SerialConsoleRunner::identify`), `rlcd_panel/dashboard_view.h` (`DashboardView::renderIdentify`), `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`, `tools/render_display_preview.cpp`, `tests/test_firmware_logic.cpp`, `docs/display_preview_identify.png`.
- `IDENTIFY` renders the inverted text `RLCD <last 4 hex digits of the BLE MAC>`, the firmware version and the transport (`USB` or `BLE`), flushes it with `g_panel.flush` (no `display_invert`, like `PATTERN`) and plays one short note under the `BEEP` rules (mute, quiet hours and volume 0 keep it silent and the reply stays `OK IDENTIFY`). The reply is `OK IDENTIFY <4 hex digits>`; extra text gives `ERR BAD_ARG`.
- The sketch reads the BLE address with `esp_read_mac(ESP_MAC_BT)`. The command calls `FrameRefresh::invalidate()`, so the next wake cycle flushes the normal view. `SCREEN` gives `ERR NO_FRAME` until then.
- The command runs on USB and on BLE at each `ble_console` level. It does not mount the card. The RTC layout and `STATE_VERSION` do not change.
- Tests: parser, `execute()` gate, BLE bridge with `ble_console` 1, reply `OK IDENTIFY 3F2A`, `ERR BAD_ARG` for `IDENTIFY X`, the render CRC-32 for `USB` and `BLE`, and the silent paths.
- Validation: host tests, `./scripts/generate-display-preview.sh` and `arduino-cli compile rlcd_panel` pass locally. Hardware behavior (panel flush, note) is not tested.

## Slot swap in the macOS scanner view

- `ConsoleCommand.slotSwap(a, b)` writes `SLOT SWAP <A> <B>` for two different slots from 1 to 10. Equal slots, slot 0 and slot 11 give no line.
- `ConsoleCommand.slotSwapMessage` shows readable texts for `ERR FIXED_MAC`, `ERR SLOT_OFF` and `ERR READ_ONLY`; other replies stay as they are.
- `ScannerView` shows a menu `Slot <N>` with the entries `Swap with slot <M>` for each slot of `AppModel.freeableSlots` (occupied, no fixed MAC). `AppModel.swapSlots` sends the command, then `STATUS`, and clears the old scan and reject results.
- Tests: `ReplyTests.slotSwapCommandAndMessages`.
- Validation: `swift test` and `swift build -c release` pass locally. The menu is not tested with hardware.

## Identify button in the macOS app

- `ConsoleCommand.identify` writes `IDENTIFY`. `IdentifyReply.parse` reads `OK IDENTIFY <4 hex digits>` and gives the code in upper case; a reply with 3 or 5 digits, a non-hex digit or another prefix throws `ParseError.badLine`.
- `ConsoleCommand.identifyMessage` shows `Panel shows RLCD <XXXX>` for a good reply and `The panel rejected the command` for `ERR BAD_ARG`; other text stays as it is.
- `DiagnosticsView` (maintenance group) and each row of the panel picker in `Views.swift` show the button `Identify panel`. `AppModel.identifyPanel` sends one command for each press and keeps the text in `identifyResult`; a new connection or a forgotten panel clears it. The user matches the code with the `PanelPin` and the physical panel.
- Tests: `ReplyTests.identifyCommandAndReply`.
- Validation: `swift test` (564 tests) and `swift build -c release` pass locally. The button is not tested with hardware.

## Delay for the humidity alert in config.ini

- The key `mold_alert_delay_h` (0 is off, else 1 to 12 h, default 0) sits in `DeviceConfig`, `DeviceConfigParser::KEYS` (`KEY_COUNT` 97, index 96), `DeviceConfigText::format` and `docs/config.example.ini`. A value above 12, a negative value and text are rejected lines. `PersistentState` stores it as 1 byte at the end of the config trailer, after `auto_assign_min_rssi`; a value above 12 is rejected. `STATE_VERSION` is 73.
- `HumidityHistory::newestAbove(n, limit_tenths)` is true when the newest `n` samples exist and each one is strictly above the limit; fewer than `n` samples give false.
- `AlertLimits::mold_delay_h` and `AlertLimits::history` (the `ClimateHistory`, set in `alertLimits()` of `rlcd_panel.ino`) feed `AlertEvaluator::Tally::add`: a tile raises `ALERT_HUMIDITY` only when the live reading is above `mold_alert_limit_rh` and the newest N samples of its `HumidityHistory` are also above it. With delay 0 the behavior does not change. With a delay and no history there is no rise. The release rule and the hold rule do not change, so a short shower gives no chime.
- The macOS `ConfigSchema` has the entry `mold_alert_delay_h` (integer 1 to 12, off 0) and `ConfigSchemaTests.rangesMatchFirmwareConstants` checks the range.
- Tests: `testMoldAlertDelay` (delay 0, two and three samples with delay 3, a sample at the limit, a live reading at the limit, no history, release, slot tile), `testMoldAlertDelayConfig` (parser, rejected 13 and -1, text round trip, `SET` check, RTC round trip and rejected trailer byte). The test without the change fails in `testMoldAlertDelay`.
- Validation: the host tests (231 suites), `swift test` (564 tests) and `arduino-cli compile rlcd_panel` pass locally. The delay is not tested with hardware.

## Slot swap in the Android slot configuration screen

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/SlotConfig.kt`, `SlotConfigScreen.kt`, `MainActivity.kt`
- `SlotConfig.swap(a, b, config)` gives `Plan.Commands(listOf("SLOT SWAP <A> <B>"))` for two different slots from 1 to 10. It gives `Plan.Invalid` for a slot with a fixed MAC, for a disabled slot (`slotN_enabled=0`), for equal slots and for a slot outside 1 to 10.
- `SlotConfig.swapSucceeded` and `SlotConfig.swapResultText` word the reply (`OK SLOT SWAP`, `ERR FIXED_MAC`, `ERR SLOT_OFF`, `ERR READ_ONLY`).
- `SlotConfigScreen` shows the button `Tauschen mit …` for each slot when `PanelConfig.canWrite` is true. The dialog lists the other slots and disables each slot that `swap` refuses. `MainActivity.swapSlots` sends the command and sends `STATUS` after an `OK SLOT SWAP` reply.
- Tests: `SlotConfigTest` (`swapSendsOneCommandForTwoFreeSlots`, `swapRefusesFixedMacDisabledEqualAndUnknownSlots`, `swapReplyTexts`).
- Validation: `check-protocol-sync.sh` and its `--self-test` pass locally. `gradle testDebugUnitTest` did not run locally (no Android SDK); CI runs it.

## Outdoor sensor selection in the Android slot configuration screen

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/SlotConfig.kt`, `SlotConfigScreen.kt`
- `SlotConfig.setOutdoorSlot(slot, config)` gives `SET outdoor_slot=<N>` for N 0 to 10 (0 is off). It gives an empty list when N equals the cached `outdoor_slot`, and throws `IllegalArgumentException` for 11 or -1. `SlotConfig.outdoorSlot(config)` reads the stored value (0 when missing or malformed).
- `SlotConfigScreen` shows the selector `Außensensor` (`Keiner` or `Slot 1` to `Slot 10` with the stored names) when `PanelConfig.canWrite` is true. Each slot card shows the text `Außensensor` for the active slot.
- Tests: `SlotConfigTest` (`setOutdoorSlotSendsChangedValue`, `setOutdoorSlotSendsNothingForUnchangedValue`, `setOutdoorSlotRejectsSlotOutsideRange`).
- Validation: `check-protocol-sync.sh` and its `--self-test` pass locally. `gradle testDebugUnitTest` did not run locally (no Android SDK); CI runs it.

## Identify action in the Wear OS sensor health screen

- `ChimeCommand.IDENTIFY` (`IDENTIFY`) and `ChimeCommand.identifyText(line)` sit in the shared `ChimeCommand.kt` of the watch and the phone (same code). `identifyText` gives `Panel zeigt RLCD <XXXX>` (upper case) for `OK IDENTIFY <4 hex digits>` and `null` for 3 or 5 digits, a non-hex digit, extra words or another line. `failureText` treats a good `OK IDENTIFY` reply as no failure.
- `SensorHealthScreen` shows the chip `Panel finden`. `MainActivity.identifyPanel` sends `IDENTIFY` once for each tap, with the same guards and permission check as `testChime`; `SensorHealthText.identifyResult` shows the text under the chime result or the error. No retry.
- Tests: `ChimeCommandTest` on the watch and the phone (`identifyCommandAndTexts`), `SensorHealthTextTest.identifyResultTexts`.
- Validation: `check-protocol-sync.sh` and its `--self-test` pass locally. `gradle testDebugUnitTest` did not run locally (no Android SDK); CI runs it. The chip is not tested with hardware.

## Console command SNOOZE for a timed mute

- `SNOOZE <M>` (M 0 to 720 minutes) mutes the chime for a time. `Snooze` (`audio_alert.h`) holds the start stamp (`uint32_t`, `nowMs()`) and the minutes (`uint16_t`). `active(now)` uses the unsigned clock difference, so a 32-bit clock wrap keeps the end correct. `AlertEvaluator::silenced(muted, snooze, now)` joins the mute flag and the snooze; the sketch passes it as `muted` to `chimeBits`, `repeat`, the noise step and the loopback test (`effectiveMuted()`). The header shows the mute marker `M` while the snooze runs.
- `PersistentState::snooze` takes 6 bytes just before the checksum (`PowerManager::SNOOZE_SIZE`): stamp (4 bytes) and minutes (2 bytes), little-endian. The RTC check rejects minutes above 720 and minutes 0 with a stamp. A power-on sets both to 0. `setup()` clears an expired snooze at each wake cycle, so a clock wrap never revives it. `STATE_VERSION` is 74.
- `SNOOZE 0`, `MUTE 0`, `MUTE 1` and a `BOOT` press end the snooze. The reply is `OK SNOOZE <M>`. `ERR BAD_ARG` for 721, a non-digit, a sign and extra text. `ERR READ_ONLY` on BLE with `ble_console` 1. No card access.
- `MUTE_CHANGE` records a snooze start with detail low byte 2 and the source in the high byte (`0x0102` for USB, `0x0202` for BLE), and the end of a running snooze with low byte 0.
- The `STATUS` line `CHIME` ends with ` snooze_s=<N|-->` (remaining seconds, `--` without an active snooze). The line count does not change. `tests/fixtures/status_reply_sample.txt` holds `snooze_s=3540`.
- Tests: `testSerialSnoozeCommand`, `testSnoozeMuteLogic` (59 and 61 minutes, clock wrap, repeat), `testSnoozeMuteEnds`, `testStatusChimeSnoozeLine`, `testSnoozeRtcRoundTrip`.
- Validation: host tests, `arduino-cli compile rlcd_panel` and `check-protocol-sync.sh` with `--self-test` pass locally. The behavior is not tested with hardware.
- The console command `NTP SYNC` runs one NTP sync on request (USB only). `SerialConsoleRunner::ntpSync` takes an injected function; the sketch passes `NtpClient::sync` and re-reads the RTC after a success. `NtpSchedule::record` stores the attempt, and one `EventCode::NTP_SYNC` event records the `NtpResult` (the enum moved out of the `ARDUINO` block of `ntp_sync.h` for the host tests).
- Replies: `OK NTP SYNC`; `ERR NTP_FAILED <WIFI_FAILED|NTP_TIMEOUT|RTC_WRITE_FAILED>`; `ERR NO_WIFI` without credentials (`NtpSchedule::hasCredentials`; no sync, no state change, no event); `ERR BAD_ARG` for extra text; `ERR NOT_OVER_BLE` on BLE at each `ble_console` level. The call blocks the console for about 14 s, and the sketch stops the BLE console around it. No card access. The RTC layout and `STATE_VERSION` do not change. The `STATUS` line `NTP` shows `ok=1 failures=0` after a success.
- Tests: `testNtpSyncCommand` (parser, gate, `BleConsoleBridge`, each `NtpResult`, event, `STATUS` line, `ERR NO_WIFI`).
- Validation: host tests, `arduino-cli compile rlcd_panel` and `check-protocol-sync.sh` with `--self-test` pass locally. The behavior is not tested with hardware.
- `EventText` of the macOS app and of the Android and Wear OS apps shows `SCAN_FAULT` (code 21) as `BLE scan start failed` (detail 1) and `BLE scan works again` (detail 0). Another detail gives the raw text `SCAN_FAULT,<detail>`. `EventFilter` puts `SCAN_FAULT` in the category `sensors`.
- The Kotlin `EventText.kt` of the phone and the watch shows `WINDOW_OPEN` (code 22) as `Window open (<room>)` and `Window closed (<room>)`. `<room>` is `Onboard` for tile 0 and `tile <N>` for tile 1 to 10. A high byte above 1, a tile above 10 or a detail outside 0 to 65535 gives the raw text (`WINDOW_OPEN,523`). The Kotlin apps have no room names, so the text uses the tile number. The Kotlin `EventFilter` already held `WINDOW_OPEN` in `climate`.
- `tests/fixtures/events_reply_sample.txt` has two new rows (`SCAN_FAULT,1`, `WINDOW_OPEN,257`) and a new CRC-32. Swift counts 24 rows with `EVENTS_DROPPED`, Kotlin counts 23 rows; the `sensors` category holds 10 rows.
- Tests: `EventTextTests.scanFaultGivesFailedAndBack`, `EventFilterTests.scanFaultIsSensorAndWindowOpenIsClimate`, Kotlin `EventTextTest.scanFaultGivesFailedAndBack` and `windowOpenGivesTileText`, `EventFilterTest.scanFaultIsSensorAndWindowOpenIsClimate`, `EventsReplyTest.sharedFixtureParses`.
- Validation: `swift test` (566 tests), `swift build -c release`, `check-protocol-sync.sh` and `--self-test` pass locally. The Swift tests fail without the source change. The Kotlin tests did not run locally (no Android SDK); the CI jobs run them.

## Snooze menu in the macOS menu bar

- `ConsoleCommand.snooze(Int)` sends `SNOOZE <M>` (M 0 to 720); another value gives no line. `AppModel.snooze(minutes:)` sends the command, then `STATUS`, only on a user action.
- The `MenuBarExtra` shows `Mute for 1 hour`, `Mute for 8 hours` and `End snooze` (`SNOOZE 60`, `SNOOZE 480`, `SNOOZE 0`), disabled without an idle console link.
- `ChimeMetrics.snoozeS` parses ` snooze_s=<N|-->` of the `CHIME` line (`nil` for `--` and for a missing field, `ParseError.badLine` for a bad number). `ChimeMetrics.snoozeText` gives `Muted for <H:MM>` (minutes rounded up); the menu and `DiagnosticsView` show it.
- `EventText` shows `MUTE_CHANGE` with low byte 2 as `Chime snoozed (<source>)`.
- Tests: `DiagnosticsTests.parsesChimeLine`, `ReplyParserTests.buildSnoozeLine`, `EventTextTests.muteChangeGivesStateAndSource`.
- Validation: `swift test` (567 tests), `swift build -c release`, `check-protocol-sync.sh` and `--self-test` pass locally. The tests use the new API, so they do not compile without the change. CI not run yet.

## Snooze buttons and notification action in the Android app

- `ChimeCommand.snooze(minutes)` (shared file of phone and watch) builds `SNOOZE <M>` for 0 to 720 and throws `IllegalArgumentException` otherwise. `ChimeCommand.snoozeText` and `okText` word `OK SNOOZE <M>` as `Stumm für <M> min` or `Stumm beendet`; `failureText` treats the reply as success.
- `ChimeReminder.snoozeSeconds` (`Long?`) parses ` snooze_s=<N|-->` of the `CHIME` line in the shared `StatusReply.kt` (`null` for `--` and for a missing field, `ParseException` for a bad number). `EventText` shows `MUTE_CHANGE` with low byte 2 as `Chime snoozed (<source>)`.
- `ChimeScreen` shows `Stumm noch <H> h <M> min` and, with `PanelConfig.canWrite`, the buttons `1 h stumm`, `8 h stumm` and `Stumm beenden` (`SNOOZE 60`, `SNOOZE 480`, `SNOOZE 0`, then `STATUS`).
- The alert notification has the action `1 h stumm` when `PanelConfig.canWrite` is true. `AckReceiver` (`ACTION_SNOOZE`) sends `SNOOZE 60` once through `BleConsoleClient` and shows the result text.
- Tests: `ChimeCommandTest.snoozeLines`, `snoozeResultText`, `StatusReplyTest.parsesChimeLine`, `badChimeLineThrows`, `EventTextTest.muteChangeGivesStateAndSource`.
- Validation: `check-protocol-sync.sh` and `--self-test` pass locally. The Gradle tests did not run locally (no Android SDK); CI runs them.

## Clock sync button in the macOS diagnostics view

- `ConsoleCommand.ntpSync` sends `NTP SYNC`. `ConsoleCommand.ntpSyncMessage` shows readable texts: `Clock synced`, `No Wi-Fi credentials on the panel` (`ERR NO_WIFI`), one text for each reason of `ERR NTP_FAILED <WIFI_FAILED|NTP_TIMEOUT|RTC_WRITE_FAILED>` and `Clock sync needs the USB serial link` (`ERR NOT_OVER_BLE`). Other text and an unknown reason stay raw.
- `DiagnosticsView` shows the button `Sync clock now (Wi-Fi)` in the group `Clock sync (NTP)`. It needs an idle USB link. `AppModel.syncClockOverWifi` sends `NTP SYNC`, then `STATUS`, on a user action only. The existing `AppModel.syncClock` (`TIME SET`) is unchanged.
- `SlotAssignment.timeoutSeconds(for: .ntpSync)` is 30 s (`ntpSyncTimeoutSeconds`), because the panel blocks for about 14 s. `SlotAssignment.ntpSyncAllowed(onUsb:)` allows USB only.
- Test: `ReplyTests.ntpSyncCommandAndTexts`.
- Validation: `swift test` (568 tests), `swift build -c release`, `check-protocol-sync.sh` and `--self-test` pass locally.

## Snooze action in the Wear OS alert notification

- The alert notification of the watch has the action `1 h stumm` (`BleScanService.postAlertNotification`, request code 121). Each per-bit notification and the notification of the other alerts carry it.
- `AckReceiver` (`ACTION_SNOOZE`) sends `ChimeCommand.snooze(60)` (`SNOOZE 60`) once through `BleConsoleClient`, with the same permission check, one command in flight and 8 s watchdog as the ACK actions. It shows the result under `SNOOZE_NOTIFICATION_ID` 926 with `ChimeCommand.failureText` and `okText` (`Stumm für 60 min`, `Set ble_console=2 to apply` for `ERR READ_ONLY`, `Keine Antwort vom Panel` without a reply).
- Tests: `ChimeCommandTest.snoozeLines` and `snoozeResultText` on the watch.
- Validation: `check-protocol-sync.sh` and `--self-test` pass locally. `gradle testDebugUnitTest` did not run locally (no Android SDK); CI runs it. The action is not tested on the watch.

- The macOS diagnostics view shows the wall and window latches. `LatchMetrics.wall` and `LatchMetrics.window` (`Int?`, `Diagnostics.swift`) parse `wall=` and `window=` of the `LATCH` line (3 hex digits, at most `7ff`; `nil` for a missing field of older firmware; `ParseError.badLine` for a bad field such as `wall=0x2`). `hasBadges` includes both. `badges(forSensor:)` returns `V`, `D`, `Wall`, `Window`, `BAT LOW`, `STALE` in this order; the latch group of `DiagnosticsView` and the report line `Panel badges` show them without a view change.
- Tests: `DiagnosticsTests.parsesWallAndWindowMasks`, `parsesWallAndWindowFromSharedFixture`, `wallAndWindowAreNilForOlderFirmware` and `wallAndWindowRejectBadFields`.

- The Wear OS sensor health screen shows the wall and window latches. The shared `StatusReply.kt` (phone and watch, same source) holds `LatchState.wall` and `LatchState.window` (`Int?`): they parse `wall=` and `window=` of the `LATCH` line (3 hex digits, at most `7ff`; `null` for a missing field of older firmware; `ParseException` for a bad field or a mask above `7ff`). `LatchState.badges(sensor)` adds `Wand` and `Fenster` after `D` and before `BAT LOW`, so `SensorHealthText.badges` gives for example `V · Wand · Fenster`.
- Tests: `StatusReplyTest.parsesLatchLine`, `latchBadgeOrderAndOnboardRules`, `latchWithoutWallAndWindowGivesNull` and `badLatchLineThrows`, `SensorHealthTextTest.badgeText`; phone `StatusReplyTest.parsesWallAndWindowMasks` and `badWallAndWindowThrow`. The phone `DiagnosticsReport` line `Panel badges` now lists `Wand` and `Fenster` too (`DiagnosticsReportTest.badgeLineListsLatchedSensors`).
- Validation: `check-protocol-sync.sh` and `--self-test` pass locally. `gradle testDebugUnitTest` did not run locally (no Android SDK); CI runs it.

## Clock sync line in the panel diagnostics view

- The diagnostics view shows one `NTP:` line at scale 1 (`DashboardView::NTP_X` 300, `NTP_Y` 168), right of the `UP:` line. The gap below `UP:` is only 6 px, so the line sits beside it.
- `NtpSchedule::describe(state, credentials, now_ms, out, capacity)` (`ntp_sync.h`) builds the text: `NTP: OK <N>H AGO` after a success (whole hours, `>99H` above 99), `NTP: FAIL <N>` after failures, `NTP: --` without credentials or without an attempt.
- `SystemInfo` has the new fields `ntp` (RTC copy of `NtpSyncState`), `ntp_credentials` and `now_ms`. `rlcd_panel.ino` fills them from `g_state.ntp`, `NtpSchedule::hasCredentials` and `nowMs()`. The view does not start Wi-Fi.
- The text changes once each hour, so `FrameRefresh` still skips. The RTC layout and `STATE_VERSION` do not change.
- Test: `testDiagnosticsNtpLine` (three texts, `NTP: OK 0H AGO`, no change inside one hour, only the NTP rows differ, fixed CRC-32 `0x02727b14` of the diagnostics scene).
- Validation: host tests (231 suites), `generate-display-preview.sh`, `arduino-cli compile rlcd_panel`, `check-protocol-sync.sh` and `--self-test` pass locally.

## Quiet hours and wake lines in the Android sensor health screen

- `StatusReply.kt` (phone and watch, same code) parses the `STATUS` lines `QUIET start_h=<H> end_h=<H> active=<0|1|--> night_interval_s=<N>` into `QuietHoursState` and `WAKE cause=<POWER_ON|TIMER|KEY|OTHER> boots=<N> battery_interval_s=<N>` into `WakeState`. `active=--` gives `null`. A missing line gives `null`. A bad number, an hour above 23, a bad `active` or `cause=SLEEP` throws `ParseException`.
- `SensorHealthText` (`SensorHealthScreen.kt`) shows `Ruhezeit <H>–<H> Uhr` (with `, aktiv` while active), `Akku-Intervall <N> s`, `Nachtintervall <N> s` (0 is `aus`) and `Weckgrund <cause>` in the card `Panel diagnostics`; `--` for a missing line.
- Tests: `StatusReplyTest` (`parsesQuietLine`, `badQuietLineThrows`, `parsesWakeLine`, `badWakeLineThrows`, read `tests/fixtures/status_reply_sample.txt`) and `SensorHealthTextTest.quietAndWakeTexts`.
- Validation: `check-protocol-sync.sh` and `--self-test` pass locally. The Gradle tests did not run locally (no Android SDK on this host); the CI jobs run them.

## BLE reject counters in the Wear OS sensor health screen

- `StatusReply.kt` (phone and watch, same code) parses the `STATUS` line `BLE rejected=<N> last=<MAC|--> ... unassigned=<N> jumps=<N>` into `BleRejectState`. `last=--` gives `null`. A missing `unassigned` or `jumps` field gives `null` (older firmware). A missing line gives `null`. A bad number, a missing `rejected` field or a value above 65535 throws `ParseException`. `BleRejectState.isWarning` is true when `unassigned` is above 0.
- `SensorHealthText.bleRejects` (`SensorHealthScreen.kt`) shows `BLE abgelehnt <N>, Sprünge <N>` (`--` for a missing `jumps` field) in the panel section. The screen adds `⚠` and the warning colour when `unassigned` is above 0.
- Tests: `StatusReplyTest` (`parsesBleRejectLine`, `badBleRejectLineThrows`) and `SensorHealthTextTest.bleRejectText`.
- Validation: `check-protocol-sync.sh` and `--self-test` pass locally. The Gradle tests did not run locally (no Android SDK on this host); the CI jobs run them.

## Guided sensor setup assistant in the Android app

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/SetupAssistant.kt`, `SetupAssistantScreen.kt`, `MainActivity.kt`, `PhoneDashboardScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/SetupAssistantTest.kt`.
- `SetupAssistant` is a pure Kotlin state machine with the step order of `SetupAssistant.swift`: `SCAN`, `CHOOSE`, `ASSIGN`, `VERIFY`, `CALIBRATE`, `DONE`. The phone scan (`SensorFinderScanner`) replaces the `SCAN` console command, so the step needs no command.
- Commands: `SET slotN_mac=<MAC>`, `SET slotN_name=<NAME>` and `CONFIG` (from `SlotAssignment.batch`), `STATUS` for each check of the verify step, `SALT <N>` and `SET <key>=<value>` plus `CONFIG` for the calibrate step. The app sends each command only after a user tap, over the BLE console. The assign tap reads `STATUS` and `CONFIG` first, then the assistant picks the first free slot.
- The assistant stops with a text for `ERR READ_ONLY` (`Set ble_console=2 to apply`, also before the first `SET` when `ble_console` is not 2), `ERR FIXED_MAC`, each other `ERR` reply, a lost link or timeout, and a sensor that stays absent in `STATUS` after 3 checks (`Stop.Absent`). A stopped assistant sends no command. `restart()` and the button `Neu starten` begin again at `SCAN`.
- The main screen has the button `Sensor einrichten`.
- Tests: `SetupAssistantTest` (step order, command texts, each stop reason, restart after a stop and after `DONE`).
- Validation: the host has no Java and no Android SDK, so Gradle did not run locally. The CI job `Android phone companion tests and APK` runs the tests.

## Wall factor proposal in the Android calibration screen

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/FrsiReply.kt`, `CalibrationScreen.kt`, `MainActivity.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/FrsiReplyTest.kt`, `tests/fixtures/frsi_reply_sample.txt`.
- `FrsiCommand.line(room, wall)` builds `FRSI <R> <W>` (R 0 to 10, W 1 to 10, different numbers; else `IllegalArgumentException`). `FrsiReply.parse` reads the three reply types: the measurement (`FRSI room=… wall=… hours=… frsi=… span=… delta_c=…` plus the line `wall_frsi_pct=<N>`), `SKIP hours=<n>` and `UNSTABLE span=<x|-->`. A bad number throws `ParseException`. A proposal outside 50 to 95 gives no proposal.
- `ERR LOW_DELTA`, `ERR NO_OUTDOOR` and `ERR BAD_ARG` arrive as status lines. `FrsiText.status` shows a clear text for each. `ERR READ_ONLY` (`ble_console=1`) shows `Set ble_console=2 to apply`, as for the other calibration commands.
- `CalibrationScreen` has the section `Wall factor`: two drop-down menus (room and wall), the button `FRSI`, the result text and the button `Apply wall factor`. The button sends `SET wall_frsi_pct=<N>`, then `CONFIG`, through `CalibrationBatch`.
- Tests: `FrsiReplyTest` (fixture, `SKIP`, `UNSTABLE`, proposal range, bad numbers, command line, status texts).
- Validation: the host has no Java and no Android SDK, so Gradle did not run locally. The CI job `Android phone companion tests and APK` runs `gradle testDebugUnitTest assembleDebug`.

## Hour window picker for the Android FRSI check

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/FrsiReply.kt`, `CalibrationScreen.kt`, `MainActivity.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/FrsiReplyTest.kt`.
- `FrsiCommand.line(room, wall, hours)` takes the sample window (default 6). 6 hours sends `FRSI <R> <W>`, other values send `FRSI <R> <W> <H>`. A value outside 3 to 24 throws `IllegalArgumentException`. `FrsiCommand.HOUR_CHOICES` lists 3, 6, 12 and 24.
- `CalibrationScreen` shows the chips `3 h`, `6 h`, `12 h` and `24 h` in the section `Wall factor` (`CalibrationState.frsiHours`). A new choice clears the last result. `frsiReplyHours` keeps the window of the request, so `FrsiText.outcome(reply, requestedHours)` shows `SKIP: nur <n> h, mindestens <H> h nötig`. The measurement text shows the hour count of the reply beside the factor.
- `FrsiText.status(line, hours)` adds a firmware update hint to `ERR BAD_ARG` for a window other than 6 hours.
- Tests: `FrsiReplyTest` (`commandHours`, `skipForLongerWindow`, `badArgHintOnlyForOtherWindows`).
- Validation: the host has no Android SDK, so Gradle did not run locally. The CI job `Android phone companion tests and APK` runs `gradle testDebugUnitTest assembleDebug`.

## Outdoor slot proposal in the macOS setup assistant

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/OutdoorProposal.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/SetupAssistant.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/SetupAssistantView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/OutdoorProposalTests.swift`.
- `OutdoorProposal.propose(week:config:)` reads the rows of the last `WEEK` reply (`AppModel.week`). A sensor counts only with at least 3 days that hold a temperature pair (`minDays`). The range of a day is maximum minus minimum; the range of a sensor is the mean over its days.
- The slot (1 to 10) with the largest range is the candidate. The proposal exists only when this range is at least twice the range of the next slot (`rangeFactor`; a single slot has next range 0) and the mean daily minimum of the candidate is below the mean daily minimum of the onboard sensor. The onboard sensor needs 3 days as well.
- No proposal when `outdoor_slot` of the active `CONFIG` already holds the slot.
- The `calibrate` step of the setup assistant shows the text `Outdoor slot <N> (<name>): range <x> C, next <x> C, minimum <x> C, onboard minimum <x> C` and the button `Use slot <N> as outdoor slot`. `SetupAssistant.beginApplyOutdoor` sends `SET outdoor_slot=<N>` through `ConfigBatch`, then `CONFIG`. The assistant sends nothing without a click and does not read `WEEK` itself; the app reads it with the refresh of `STATUS`, `CONFIG` and `WEEK` (button `Read STATUS and CONFIG`). Without `WEEK` data the step shows the button `Read week data`. After the outdoor batch the assistant stays in step `calibrate`, so the user can still compare and calibrate.
- `OutdoorProposalTests` assert a clear proposal, no proposal for two equal ranges, for fewer than 3 days, for a warm slot and for the active value, and the apply batch of the assistant. They also assert the step guard and the boundary of exactly twice the next range. A stub of `propose` that returns `nil` failed the proposal tests.
- Validation: `swift test -Xswiftc -plugin-path -Xswiftc /Library/Developer/CommandLineTools/usr/lib/swift/host/plugins/testing` (583 tests) and `swift build -c release` passed in `macos/RlcdCompanion`. Plain `swift test` fails on this machine in some runs with `TestingMacros` plugin not found; the explicit plugin path fixes it.

## Wall factor proposal in the macOS calibration view

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/Calibration.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/CalibrationView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/CalibrationTests.swift`.
- `ConsoleCommand.frsi(room:wall:)` sends `FRSI <R> <W>`. `room` is 0 to 10, `wall` is 1 to 10, and the two numbers must differ. Another input gives no line.
- `FrsiReply.parse` reads the reply. A `FRSI` frame gives `.measured` with room, wall, hours, factor, span and `delta_c`, and the proposal `wall_frsi_pct=<N>` only when `ConfigSchema.validate` accepts it. `SKIP hours=<n>`, `UNSTABLE span=<x>` (`span=--` gives `nil`), `ERR LOW_DELTA` and `ERR NO_OUTDOOR` give their own outcome. A bad number throws `ParseError.badLine`.
- `CalibrationView` has the section `Wall factor` with two pickers (room and wall slot), the button `Check wall`, the result text and the button `Apply wall factor`. `AppModel.frsiCheck` sends `FRSI` only on a click. `AppModel.applyFrsi` sends `SET wall_frsi_pct=<N>` and then `CONFIG` through `ConfigBatch`. An equal active value gives no change.
- The one-line errors `ERR LOW_DELTA` and `ERR NO_OUTDOOR` show as text in the section. `ERR BAD_ARG` shows as a message.
- Tests: `CalibrationTests` assert the shared fixture `tests/fixtures/frsi_reply_sample.txt`, each other outcome, `ParseError.badLine` for `frsi=x`, the out-of-range proposal, the command line and the batch text `SET wall_frsi_pct=70`, `CONFIG`.
- Validation: `swift test -Xswiftc -plugin-path -Xswiftc /Library/Developer/CommandLineTools/usr/lib/swift/host/plugins/testing` (589 tests) and `swift build -c release` in `macos/RlcdCompanion`.

## Salt test progress in the macOS calibration view

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/SaltProgress.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/CalibrationView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/SaltProgressTests.swift`
- `ConsoleCommand.historySensor(N)` sends `HISTORY N` (N 0 to 10, another number gives no line). The reply parses with `HistoryReply.parse`.
- `SaltProgress.stableHours(rows:sensor:)` counts the newest hours of one sensor, from age 0 on, whose humidity span stays at or below 1.0 %RH (same limit as `SALT N`). A missing hour or an hour that raises the span above the limit ends the count, so an unstable newest hour resets it to 1.
- `SaltProgress` is a pure state machine with an injected clock. `intervalSeconds` is 600 and `maxDurationSeconds` is 28,800. `text` gives `Stable <n>/6 h` and `hoursRemaining` gives the hours until 6 stable hours. `StopReason` is `user`, `viewClosed`, `timeout`, `linkLost` or `replyFailed`; the first reason stays.
- `CalibrationView` has the toggle `Watch salt test` in the salt section. `AppModel.setSaltWatch` sends `HISTORY N` for the selected sensor at once and then every 10 minutes while the console is idle. The watch stops on the toggle, on a closed view, on a lost link, on a transport change, on `ERR`, on a missing reply, on a corrupt reply and after 8 hours. The watch reads its own reply and leaves `AppModel.history` unchanged.
- While the watch runs, `Check NaCl` stays disabled until 6 stable hours. The `SALT N` check itself stays a user action.
- No firmware change, no RTC layout change, no `STATE_VERSION` change.
- Tests: `SaltProgressTests` assert the stable hour count for a fixed `HistoryReply`, the reset after an unstable hour, a gap, the inclusive 1.0 %RH limit, the text, the remaining hours, the 8-hour stop with a fake clock, the first stop reason and the command line.
- Validation: `swift test` (601 tests) and `swift build -c release` passed in `macos/RlcdCompanion`. With `maxSpanPct` set to 100 the new tests fail.

## Self-test buttons in the macOS and Android apps

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/Diagnostics.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/DiagnosticsReport.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/SlotAssignment.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/SelfTestReply.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/DiagnosticsReport.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/SensorHealthScreen.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/DiagnosticsTests.swift`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/SelfTestReplyTest.kt`
- `SelfTestReply.parse` (Swift and Kotlin) reads the `SELFTEST` reply without the `CRC32` line: `BEGIN SELFTEST`, one `CHECK <name> <OK|FAIL|SKIP> <detail>` line for each check and `END SELFTEST pass=<N> fail=<N>`. An unknown state, a bad line, a missing `END` line or counts that differ from the checks give `ParseError.badLine`, `ParseError.missingLine` or `ParseException`. `passed` is true for no failed check and at least one passed check.
- `ConsoleCommand.selfTest` sends `SELFTEST` with a 20 s reply timeout (`selfTestTimeoutSeconds`), because the command runs a 1 s microphone window, a 1 s BLE scan and a card mount.
- The macOS `DiagnosticsView` has the button `Run self-test` and the group `Self-test (<N> ok, <M> failed)` with a pass, fail or skip symbol for each check. `AppModel.runSelfTest` sends the command only on a click, on USB and on BLE. A new run, a failed parse and a link change clear the old result. The diagnostics report has a `SELFTEST` section with the line `Self-test: passed, <N> ok, <M> failed`.
- The Android `SensorHealthScreen` has the button `Run self-test` and a card with a mark for each check (`✔`, `✘`, `–`). The command goes over the BLE console, and the screen shows the failed checks in the error colour. The report of the phone includes the result.
- Tests: `DiagnosticsTests` read `tests/fixtures/selftest_reply_sample.txt` and assert the checks, the details, the counts, fail and skip counting, `badLine` for an unknown state and for wrong counts, `missingLine` without `END`, and the report summary. `SelfTestReplyTest` asserts the same for Kotlin.
- Validation: `swift test` (597 tests) and the build passed in `macos/RlcdCompanion`. Without the production change in `Diagnostics.swift` and `DiagnosticsReport.swift`, the build fails with `cannot find type 'SelfTestReply' in scope`. The host has no Android SDK, so the CI job `Android phone companion tests and APK` runs the Gradle tests.

## Config backup and restore in the Android app

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ConfigBackup.kt` (`ConfigKeys`, `ConfigBackup`, `ConfigRestorePlan`), `ConfigBackupScreen.kt`, `MainActivity.kt`, `SensorHealthScreen.kt`; tests in `ConfigBackupTest.kt` and `ConfigBackupSharedFixtureTests.swift`.
- The sensor health screen has the button `Config backup and restore`. `Save backup` sends `CONFIG` over the BLE console, refreshes the config cache, and shares the values as a `config.ini` text through the Android share sheet. `ConfigBackup.render` has the same format as the macOS `ConfigBackup.render`: each schema key once, in schema order, `key=value`, LF line ends, an invalid value (an empty name) as comment line `# key=`, no Wi-Fi keys. `tests/fixtures/config_backup_sample.ini` holds the export of `tests/fixtures/config_reply_sample.txt`; `ConfigBackupTest` and the macOS `ConfigBackupSharedFixtureTests` compare their export with it.
- `Restore backup` picks a file through `ActivityResultContracts.OpenDocument` and needs a cached `CONFIG` read. `ConfigBackup.parse` uses the macOS rules: blank, `#` and `;` lines are skipped, unknown keys are ignored, CR and a UTF-8 BOM are tolerated, a later line of a key wins. A file above 8192 bytes (`ConfigBackup.MAX_BYTES`) or invalid UTF-8 is refused; the app reads at most 8193 bytes of a file.
- `ConfigKeys` ports the key list, the ranges and the defaults of the macOS `ConfigSchema`. `ConfigKeys.validate` gives a rejected line with its line number. The review lists the changes (`key: old -> new`) against the cached values and the problems (a space in a value, a line above 40 bytes, a duplicate fixed MAC). `Restore` is disabled with a rejected line, a problem, no change or a cached `ble_console` below 2.
- `Restore` sends only the changed keys through `CalibrationBatch` in the macOS batch order (`ConfigKeys.entries`, `ble_console` last), then `CONFIG`. The first `ERR` reply stops the batch, skips `CONFIG`, and the screen shows the key and the reply with the number of applied keys. A timeout shows that the panel state is unknown.
- Validation: `swift test` (610 tests) passed in `macos/RlcdCompanion`. `ConfigBackupTest` (28 tests) compiled with Kotlin 2.4.21 outside Gradle and passed. Four mutants of `ConfigBackup.kt` (reversed batch order, no validation, changed default, removed key) made 3 to 7 tests fail. The host has no Android SDK, so `MainActivity.kt` and the screens are not compiled locally and the CI job `Android phone companion tests and APK` runs the Gradle tests.

### Open window notification in the Android app

- `WindowWatch` (`protocol/WindowWatch.kt`) is a pure state machine like `WallWatch`. `update(mask)` takes the `window` field of the `STATUS` line `LATCH` (bit 0 onboard tile, bits 1 to 10 slots) and returns the tiles whose bit rose since the previous read.
- The first read after the app start only stores the mask. An equal mask and a falling bit give no result. A missing field (`LatchState.window` is null on an older firmware) gives no result and keeps the stored mask.
- `MainActivity.checkWindowNotifications` runs at each parsed `STATUS` reply (`applyStatusReply` and the chime status read). It posts one notification `Window open: <name>` for each rising tile (name from `PanelConfig.tileName`, notification id `WindowWatch.notificationId` 2100 plus the tile). Without the notification permission (Android 13 and later), nothing is posted.
- The app has no periodic `STATUS` poll. A notification appears only when the user reads `STATUS`.
- Validation: `WindowWatchTest` (8 tests) covers the first read, a rising bit, an equal mask, a falling bit, a missing field, a missing first field, a bit above tile 10 and the message and notification id. The local host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs only in CI.

## Event code for a low BLE reception rate

- `EventCode::RECEPTION_LOW` (23, `EventRing::MAX_CODE` 23, `SdLogCodec::eventName` `"RECEPTION_LOW"`) records when the BLE reception rate of a slot falls low or recovers.
- `PersistentState::recordReceptionChanges(now)` runs once after each `ReceptionHistory::recordScan` in `setup()`. A slot with 12 or more counted scans sets its latch bit below 50 %. The bit holds between 50 % and 70 % and releases at 70 % or more. A slot with fewer than 12 counted scans keeps its bit.
- One event for each changed bit: detail low byte the slot number 1 to 10, high byte 1 for low, 0 for good again. An equal state records nothing. A slot without a reading loses its bit without an event.
- `PersistentState::reception_low` (`uint16_t`, 2 bytes `RECEPTION_LOW_SIZE` just before the checksum) stores the bits. `deserialize` rejects a bit above bit 9 and a bit of an empty slot. `resetSlot` clears the bit without an event. `swapSlotsByUser` swaps the bit. `STATE_VERSION` is 75.
- `EventText` of the macOS app and of the Android and Wear OS apps shows `Slot <N> weak signal` (high byte 1) and `Slot <N> signal good` (high byte 0). Another detail gives the raw text `RECEPTION_LOW,<detail>`. `EventFilter` puts `RECEPTION_LOW` in the category `sensors`.
- Tests: `testReceptionLowEvents` asserts the set, the hold between 50 % and 70 %, the release, the 12-scan minimum, the RTC round trip and checks, and `resetSlot`. `EventTextTests`, both `EventTextTest.kt` files assert the texts.
- `EventCode::WALL_DAMP` (24) and `EventCode::DRY_AIR` (25, `EventRing::MAX_CODE` 25, `SdLogCodec::eventName` `"WALL_DAMP"` and `"DRY_AIR"`) record when the wall humidity badge (`wall_tiles`) or the dry air badge (`dry_tiles`) of a tile changes.
- `PersistentState::recordWallChanges(previous, current, now)` and `recordDryChanges(previous, current, now)` call `recordTileChanges`. `refreshSensors()` runs them directly after `DryLatch::update` and `WallLatch::update`. One event for each changed bit: detail low byte the tile 0 to 10, high byte 1 set, 0 clear. An equal mask records nothing. `resetSlot` and a power-on record nothing. The RTC layout and `STATE_VERSION` do not change.
- `EventText` of the macOS app and of the Android and Wear OS apps shows `Wall damp (<tile>)`, `Wall dry again (<tile>)`, `Dry air (<tile>)` and `Air humidity good (<tile>)`. A tile above 10 or a high byte above 1 gives the raw text. `EventFilter` lists both names in the category `climate`.
- Tests: `testWallDryAirEvents` asserts one event for each changed bit, no event for an equal mask, the detail bytes and `resetSlot`. The event ring checks accept code 25 and reject code 26. `EventTextTests` and both `EventTextTest` classes assert the texts.

## Feels-like and absolute humidity curves in the Android history screen

- `HistoryCurve` (`HistoryReply.kt`: `Temperature`, `Humidity`, `Feels like`, `Absolute humidity`) and `PanelHistoryRow.value(curve)` derive the two new values for each hour with `ClimateMath.heatIndexC` and `ClimateMath.absoluteHumidityGm3`.
- An hour without a temperature and humidity pair, and an invalid input, gives `null`. The chart shows a gap for it.
- `HistoryScreen` shows a curve selector in the panel history view. It draws one chart for the selected curve. The local history keeps its temperature and humidity charts.
- Tests: `HistoryReplyTest` asserts the derived values against `tests/fixtures/climate_math_vectors.txt`, the sample fixture and the gap.

## Self-test screen on the Wear OS watch

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SelfTestScreen.kt`, `protocol/SelfTestReply.kt`, `SensorHealthScreen.kt`, `MainActivity.kt`, `scripts/check-protocol-sync.sh`.
- `SelfTestReply.kt` is a copy of the phone file. The `FILES` list of `scripts/check-protocol-sync.sh` holds it, so the check fails when the two copies differ.
- The sensor health screen has the chip `Self-test`. The screen shows one chip for each `CHECK` line (mark, name, `OK`, `FAIL` or `SKIP`, detail) and the counts `Bestanden <N>, fehlgeschlagen <N>`.
- The watch sends `SELFTEST` over the BLE console only after the user taps `Start self-test` and then `Test jetzt starten`. The warning text says that the command mounts the card and runs the microphones for 1 s.
- `CHECK ble SKIP CONSOLE` is an expected result over BLE. The chip adds the text `erwartet über BLE`.
- A timeout gives the text `Verbindung zum Panel verloren`. A reply without an `END SELFTEST` line gives `Antwort unvollständig: END SELFTEST fehlt`. Another bad reply gives `Antwort nicht lesbar`. The command is not repeated.
- Tests: `SelfTestReplyTest` reads `tests/fixtures/selftest_reply_sample.txt` and asserts each check, the counts and the `ParseException` for a bad verdict. `SelfTestTextTest` covers the texts.
- Validation: `./scripts/check-protocol-sync.sh` and `--self-test` exit 0. The local host has no Android SDK, so the Gradle tests run in the CI job `Wear OS companion tests and APK`.

## Console command WEEK <N> for one sensor

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `tests/fixtures/week_reply_sample.txt`
- The console command `WEEK <N>` (N 0 to 5, `SerialCommand::week_one`, sensor in `SerialCommand::reference`) sends only the 7 rows of sensor N. The header, the row format and the `CRC32` line are the same as for `WEEK`. The `CRC32` covers the filtered bytes.
- `WEEK 6`, `WEEK 1x`, `WEEK ` and extra text give `ERR BAD_ARG`. `WEEK` without an argument keeps its reply of 42 rows.
- The command uses only the RTC copy. It needs no card and changes no state. It runs on USB and on BLE at each `ble_console` level, so the watch and the phone read one sensor with fewer BLE notifications.
- Host tests cover the parse of `WEEK 0` and `WEEK 5`, each bad form, the 7 rows of sensor 2, the `CRC32` line, the short write, an unchanged state and the reply of `WEEK` against `tests/fixtures/week_reply_sample.txt`.

## Console command EVENTS <N> for the newest events

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `tests/fixtures/events_reply_sample.txt`
- The console command `EVENTS <N>` (N 1 to 16, no leading zero, `SerialCommand::events_newest`, count in `SerialCommand::reference`) sends the header and only the newest N rows of the `EventRing`, oldest first, then the `CRC32` line.
- The `EVENTS_DROPPED` row appears only when the ring lost events and N covers all stored events.
- `EVENTS 0`, `EVENTS 17`, a leading zero, a non-digit and extra text give `ERR BAD_ARG`. `EVENTS` without an argument keeps its reply.
- The command uses only the RTC copy. It does not clear the ring and needs no card. It runs on USB and on BLE at each `ble_console` level, so the apps read a few events with fewer BLE notifications.
- Host tests cover each parse form, `EVENTS 1`, `EVENTS 16` against `EVENTS` for a full ring, the `EVENTS_DROPPED` rule, the `CRC32` line, the unchanged ring and the format of `tests/fixtures/events_reply_sample.txt`.

## Co-location check screen on the Wear OS watch

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/SpreadReply.kt`, `SpreadScreen.kt`, `SensorHealthScreen.kt`, `MainActivity.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/SpreadReplyTest.kt`, `scripts/check-protocol-sync.sh`
- `SpreadReply.kt` is a copy of the phone file. `scripts/check-protocol-sync.sh` now lists it in `FILES`, so a difference between the two copies fails the check.
- `SpreadScreen` (`ScalingLazyColumn`) shows the temperature median, the humidity median and one chip for each sensor with both deviations. A `⚠` mark shows each `OUTLIER` row. `SKIP` shows `Put 3 or more sensors at one place for 6 hours`.
- `SensorHealthScreen` has the chip `Co-location check`, which opens the screen. The watch sends `SPREAD` over the BLE console only after a tap on `Read SPREAD`. A lost link or a timeout shows the text of `BleConsoleClient.errorText`.
- Validation: `SpreadReplyTest` reads `tests/fixtures/spread_reply_sample.txt` and asserts the same values as the phone test. It runs in CI only (no Android SDK on the local host).

## Newest climate rows in the macOS diagnostics view

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/TailReply.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/TailReplyTests.swift`, `tests/fixtures/tail_reply_sample.txt`
- `ConsoleCommand.tail(_ rows: Int)` sends `TAIL <N>` for N 1 to 72. For 0, 73 and negative values `line` is `nil`, so the app sends nothing.
- `TailReply.parse(lines:)` reads `BEGIN TAIL <n>`, the `climate_v6` header and the rows through `ClimateLog.parse`. It returns `ClimateRow` values and `nil` for each empty field. It throws `ParseError.badLine` for `BEGIN TAIL x`, a count above 72, a row count that differs from `n`, a wrong column count and a bad value. It throws `ParseError.missingLine` for a wrong header.
- `TailReply.message` turns `ERR NO_CARD`, `ERR NO_FILE` and `ERR READ_FAILED` into clear texts. Other text stays as it is.
- `DiagnosticsView` shows the group `Newest log rows` above `Maintenance and tests`: a picker for 6, 24 or 72 rows, the button `Read log rows` and a table (time, panel cell, USB, onboard values and each slot with a reading, newest row first). `AppModel.readTail(rows:)` sends `TAIL` only after the button, on USB and on BLE. A lost link, a timeout, a rejected reply and an `ERR` reply clear the old rows.
- Tests: `TailReplyTests` read `tests/fixtures/tail_reply_sample.txt` and assert the row count, the first and the last time stamp, each empty field, the bad forms and `ConsoleCommand.tail`.
- Validation: `swift build -c release` passed. `swift test` did not run on the local host (no `TestingMacros` plugin without Xcode), so the CI job `macOS companion tests` runs it. A standalone harness of `Sources/RlcdProtocol/*.swift` checked the same assertions against the fixture and passed.

## Short event log read on the Wear OS watch

- `EventsReply.newestCommand(n)` gives `EVENTS <N>` for N 1 to 16. Another value throws `IllegalArgumentException`. The phone copy of `protocol/EventsReply.kt` has the same function, so `scripts/check-protocol-sync.sh` stays green.
- The chip `Read events` of `EventLogScreen` sends `EVENTS 8` (`EventLogState.SHORT_COUNT`), because a short reply needs less BLE time on the watch. The app sends no command without a tap.
- The chip `Show all` sends `EVENTS` once and sets `EventLogState.all`. The list then keeps every row (up to 16) instead of the newest 8. The chip disappears after this read.
- The `EventFilterChip` works on both lists.
- `EVENTS <N>` needs the panel firmware with this command (PR #380). An older firmware answers `ERR BAD_ARG`; the app shows the error text and `Show all` still reads the full ring.
- A failed read keeps the rows of the last good read and shows only the error text.
- Tests: `EventsReplyTest` asserts `EVENTS 8`, `IllegalArgumentException` for 0 and 17, the parse of an 8-row reply with and without the `EVENTS_DROPPED` row and the row count of the short and the full list.

## Recent events in the macOS menu bar

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/EventText.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/RlcdCompanionApp.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/ReplyTests.swift`
- `ConsoleCommand.eventsNewest(_ n: Int)` sends `EVENTS <N>` for N 1 to 16. For 0, 17 and negative values `line` is `nil`, so the app sends nothing.
- `RecentEvents.make(rows:config:limit:)` takes the rows of an `EventsReply` and returns the newest 5 events as `EventText` lines, newest first. The `EVENTS_DROPPED` row gives no line and sets `lost`.
- The menu bar has the section `Recent events`: the lines, the text `No events read` for an empty list, the text `Events lost` when the reply has the `EVENTS_DROPPED` row, and the button `Read events`.
- `AppModel.readRecentEvents()` sends `EVENTS 5` only after the button. `AppModel.refresh()` and `AppModel.refreshDiagnostics()` (both start with a user action) add `EVENTS 5` after their reads. The command runs on USB and on BLE at each `ble_console` level. A lost link, a timeout and a bad reply clear the old lines.
- The firmware keeps the `EVENTS_DROPPED` row for `EVENTS <N>` only when N covers all stored events. With `EVENTS 5` the text `Events lost` shows only while the ring holds 5 or fewer events.
- Tests: `ReplyTests` assert `EVENTS 1`, `EVENTS 5` and `EVENTS 16`, `nil` for 0 and 17, and the parse of a 5-row reply with and without the `EVENTS_DROPPED` row, and the cut of a 7-row reply to the newest 5.
- Validation: `swift build -c release` passed. `swift test` did not run on the local host (no `TestingMacros` plugin without Xcode), so the CI job `macOS companion tests` runs it. A standalone harness of `Sources/RlcdProtocol/*.swift` checked the same assertions and passed.

## Trend fields in the STATUS SENSOR lines

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/sparkline.h`, `tests/test_firmware_logic.cpp`, `tests/fixtures/status_reply_sample.txt`
- Each `SENSOR` line of the console command `STATUS` now ends with ` temp_trend=<RISE|FALL|STEADY|-->` and ` rh_trend=<RISE|FALL|STEADY|-->` after `mold_h=`.
- The fields use `TrendClassifier::temperature` and `TrendClassifier::humidity` of the 24-hour histories. These are the same rules as the trend marks of the tiles. The new helper `TrendClassifier::statusText` gives the text.
- The fields show `--` for `Trend::UNKNOWN`, for example with 3 samples, and for each slot that is empty, disabled or stale.
- The firmware calculates the fields on each `STATUS`. It adds no RTC state. The line count, the RTC layout and `STATE_VERSION` do not change. The Android and Wear OS `StatusReply` parsers ignore the new fields. The macOS parser reads them (see the next section).
- Tests: host tests assert `RISE`, `FALL` and `STEADY` at the exact thresholds (0.5 C, 3.0 %RH), `--` for 3 samples, an empty slot and a stale slot, and one full `SENSOR` line. `tests/fixtures/status_reply_sample.txt` holds the fields with a new CRC-32.

## Serial console command TODAY for the minimum and maximum of the current day

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `tests/fixtures/today_reply_sample.txt`
- The console command `TODAY` (`SerialCommandKind::TODAY`, `SerialConsoleRunner::showToday`) shows the minimum, maximum and mean of the hourly samples since the last local midnight for the onboard sensor and slots 1 to 10.
- The RTC time gives the minutes since midnight. The newest sample is as old as the time since `ClimateHistory::last_sample_ms`; each older sample is one hour older. A sample counts when its age does not exceed the minutes since midnight.
- Reply: `BEGIN TODAY`, the header `sensor,name,hours,temp_min_c,temp_max_c,temp_mean_c,rh_min_pct,rh_max_pct,rh_mean_pct`, one row for each sensor that holds a sample, and the `CRC32` line. `hours` is the larger sample count of the two histories. A sensor without a sample since midnight has hours 0 and empty value fields. Values use 0.1 steps.
- An invalid, stopped or untrusted RTC time (year before 2025 or after 2099) gives `ERR NO_TIME`; a failed clock read gives it too. Extra text gives `ERR BAD_ARG`.
- The age is an approximation: it assumes 60 minutes between pushes, but the real step is 60 minutes plus up to one wake interval, so a sample near midnight can fall on the wrong side. A slot without new samples (stale or lost) keeps its old samples and shows them as today's values, like `SPREAD`; the reply has no `STALE` marker. The day boundary is the local wall clock of the RTC (a plain 24-hour day, also on a DST change day).
- The command reads the RTC copy only. It does not access the card and does not change the state. It runs on USB and on BLE at each `ble_console` level. The RTC layout and `STATE_VERSION` do not change.
- Host tests cover the parser, a sensor with 5 samples since midnight, the exclusion of samples before midnight (also the exact limit), a sensor without a sample, a half-empty pair, `ERR NO_TIME`, `ERR BAD_ARG`, the BLE gate, and one reply against `tests/fixtures/today_reply_sample.txt`.
- Validation on the board: send `TODAY` over USB and check the reply and the `CRC32` line.

## Mold and dry hours of the day in the macOS today view

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/TodayReplyTests.swift`
- `TodayReply.parse` accepts the old header with 9 columns and the new header `TodayReply.headerWithHours` (`...,rh_mean_pct,mold_h,dry_h`) with 11 columns. The header selects the column count.
- `TodayRow.moldHours` and `TodayRow.dryHours` are `Int?`: `nil` for an empty field and for the old header. A value above 24 or above `hours` throws `ParseError.badLine`.
- The today view of `DiagnosticsView` shows `Mold <N> h` and `Dry <N> h` with a warning symbol above 0; the old header shows nothing.
- The firmware `TODAY` command does not send the new columns yet; the firmware item follows this one.

## Today summary screen in the Android app

- `TodayReply.parse` (`protocol/TodayReply.kt`) parses the reply of the console command `TODAY`: `BEGIN TODAY`, the header `sensor,name,hours,temp_min_c,temp_max_c,temp_mean_c,rh_min_pct,rh_max_pct,rh_mean_pct` and one `TodayRow` for each row (sensor 0 to 10, name, hours 0 to 24, minimum, maximum and mean of temperature and humidity). An empty field gives `null`.
- `ParseException` covers a wrong or missing `BEGIN` line, a wrong header, a column count other than 9, a bad sensor number, bad hours, a duplicate sensor, a bad number (only plain decimals count; `NaN`, `Infinity` and `temp_min_c=x` are bad) and a minimum above the maximum.
- `TodayReply.statusText` maps the status line `ERR NO_TIME` to the text `Panel clock not set`. Other status lines keep the text of `BleConsoleClient.errorText`.
- `TodayScreen` shows one card for each row: the name, the hours and the minimum, maximum and mean of temperature and humidity (`--` for an empty value). A sensor with 0 hours shows `Keine Daten seit Mitternacht`.
- The main screen has the entry `Heute`. `MainActivity.readToday` sends `TODAY` over the BLE console only after a tap on `Read today` (with the Bluetooth permission check). It stops the scan during the read, and `closeToday` restarts it. The panel needs USB power and `ble_console` 1 or 2.
- `TodayReplyTest` reads `tests/fixtures/today_reply_sample.txt` and covers each row, `null` for empty fields, `ParseException` for 8 columns, for `temp_min_c=x` and for a wrong header, and the `ERR NO_TIME` text. The shared protocol files stay unchanged, so `scripts/check-protocol-sync.sh` passes.
- Validation: the local host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs in CI only.

## Trend arrows in the macOS sensor list

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/Views.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/ReplyTests.swift`
- The new enum `SensorTrend` (`rise`, `fall`, `steady`) reads `RISE`, `FALL` and `STEADY`. `SensorTrend.symbol` gives an arrow up, an arrow down or a dash.
- `StatusSensor.temperatureTrend` and `StatusSensor.humidityTrend` (`SensorTrend?`) come from the `temp_trend=` and `rh_trend=` fields of the `SENSOR` lines. They are `nil` for `--` and for a missing field (older firmware). Another text throws `ParseError.badLine`.
- The status list of the menu bar shows the symbol directly after the temperature and after the humidity of each sensor. The `Sensor health` rows of `DiagnosticsView` show the temperature and the humidity of the slot, each with its symbol and a tooltip.
- The app sends no new command. It uses the `STATUS` reply that it already reads.
- Tests: `ReplyTests` read `tests/fixtures/status_reply_sample.txt` and assert each trend value of the 6 sensors, `nil` for `--` and for a line without the fields, the symbols, and `ParseError.badLine` for `rh_trend=UP`.
- Validation: `swift build -c release` and `swift test` (637 tests) passed on the local host. `./scripts/check-protocol-sync.sh` exited with 0.

## Week screen for one room on the Wear OS watch

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/WeekReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/WeekScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/WeekReply.kt`
- `WeekReply.sensorCommand(n)` returns `WEEK <N>` for N 0 to 5 (`WeekReply.MAX_SENSOR`). Another number throws `IllegalArgumentException`. The phone copy of `WeekReply.kt` has the same code, so `scripts/check-protocol-sync.sh` stays at exit 0.
- The week screen reads only the 7 rows of one sensor with `WEEK <N>` instead of the 42 rows of `WEEK`. The sensor starts as the focused room of `TelemetryCache` when the user opens the screen. The parse stays `WeekReply.parse`.
- The chip `Next sensor` selects the next sensor (wrap 5 to 0), clears the old rows and reads its 7 rows. The title shows the name of the selected sensor. The chip is off while a read runs. The command runs only after a user tap.
- Tests: `WeekReplyTest` (watch and phone) asserts `WEEK 0` and `WEEK 5`, `IllegalArgumentException` for -1 and 6, and the parse of a 7-row reply of one sensor with and without the `mold_h` column.

## Today summary screen on the Wear OS watch

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/TodayReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/TodayScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/TodayReplyTest.kt`, `scripts/check-protocol-sync.sh`
- `TodayReply.kt` is a copy of the phone file; only the `package` line differs. `scripts/check-protocol-sync.sh` now compares it.
- `TodayScreen` shows one card for each row of the `TODAY` reply: the name, the hours, and the minimum, maximum and mean of temperature and humidity. A sensor with 0 hours shows `--`; an empty value shows `--`.
- The sensor health screen has the chip `Today`. `MainActivity.readToday` sends `TODAY` over the BLE console only after a tap on `Read today` (with the Bluetooth permission check). `ERR NO_TIME` shows `Panel clock not set`. The panel needs USB power and `ble_console` 1 or 2.
- `TodayReplyTest` reads `tests/fixtures/today_reply_sample.txt` and covers the rows, `null` for empty fields, `ParseException` cases, the `--` texts for a sensor with 0 hours and the `ERR NO_TIME` text.
- Validation: `./scripts/check-protocol-sync.sh` and `--self-test` pass. The local host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs in CI only.

## Room picker for the Wear OS today screen

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/TodayCommand.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/TodayScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/TodayCommandTest.kt`
- `TodayCommand.line(sensor: Int?)` gives `TODAY <N>` for 0 to 10 and `TODAY` for `null`; another number throws `IllegalArgumentException`. `TodayCommand` is watch-only and not in `scripts/check-protocol-sync.sh`.
- `TodayScreen` has a scrollable chip row with `Alle` and the names of `PanelConfig.tileNames`. The selected chip uses the primary colors. A tap on `Read today` sends the command for the selected room.
- After `ERR BAD_ARG` from an older firmware, `MainActivity.startTodayRead` sends `TODAY` once (`TodayCommand.needsFallback`) and `TodayCommand.select` keeps only the row of the selected room. A room without a row shows `Keine Daten für den Raum`.
- `TodayCommandTest` covers `TODAY 3` and `TODAY`, the rejected numbers 11 and -1, the fallback decision, the row filter and `tests/fixtures/today_one_reply_sample.txt`.
- Validation: `./scripts/check-protocol-sync.sh` passes. The local host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs in CI only.

## Monthly mold summary screen on the Wear OS watch

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/DaysReply.kt`, `protocol/EventCsv.kt`, `protocol/MoldSummary.kt`, `MoldSummaryScreen.kt`, `MainActivity.kt`, `SensorHealthScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/DaysReplyTest.kt`, `scripts/check-protocol-sync.sh`
- `DaysReply.kt` and `EventCsv.kt` (needed by `DaysReply.csv`) are copies of the phone files; only the `package` line differs. `scripts/check-protocol-sync.sh` now compares both.
- `MoldSummary.build` sums the `mold_h` values of the `DAYS 31` rows for each room that holds a value: the sum of the hours, the number of days with at least 1 mold hour, and the worst day (most hours; on a tie the newest day) with its `MM-DD` date. The rows are sorted by the sum, highest first; equal sums keep the sensor order. A room without any mold value shows `--` for the sum, the days and the worst day and comes last. A room with values of 0 shows `0 h`, `0 Tage` and `--` for the worst day.
- `MoldSummaryScreen` shows one card for each room. The sensor health screen has the chip `Mold (31 days)`. `MainActivity.readMold` sends `DAYS 31` over the BLE console only after a tap on `Read mold days` (with the Bluetooth permission check). `ERR NO_CARD`, `ERR NO_FILE` and `ERR READ_FAILED` show the clear texts of `DaysReply.errorText`. The panel needs USB power and `ble_console` 1 or 2.
- `DaysReplyTest` reads `tests/fixtures/days_reply_sample.txt` (3 rows) and covers the parser and the summary model: sum, day count, worst day, sort order, the `--` texts and the fixture (its mold fields are empty, so the onboard room shows `--`).
- Validation: `./scripts/check-protocol-sync.sh` and `--self-test` pass. The local host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs in CI only.

## Trend arrows in the Android sensor health screen

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/StatusReply.kt`, `protocol/SensorHealth.kt`, `SensorHealthScreen.kt`, `StatusReplyTest.kt`.
- `StatusReply.parse` reads `temp_trend` and `rh_trend` of each `SENSOR` line into `StatusSensor.temperatureTrend` and `StatusSensor.humidityTrend` (`SensorTrend`: `RISE`, `FALL`, `STEADY`). `--` and a missing field give `null`. Another text throws `ParseException`.
- `SensorHealth` carries both trends. `SensorHealthScreen` shows a line `Temp <value> <arrow>  Feuchte <value> <arrow>` for each slot: `↑` for `RISE`, `↓` for `FALL`, `–` for `STEADY` and for `null`.
- Tests: `StatusReplyTest.trendsOfFixtureAndBadFields` reads `tests/fixtures/status_reply_sample.txt`. It runs in CI only when the host has no Android SDK.

## Trend arrows in the Wear OS sensor health screen

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/StatusReply.kt`, `SensorHealthScreen.kt`, `StatusReplyTest.kt`.
- `StatusReply.parse` reads `temp_trend` and `rh_trend` of each `SENSOR` line into `StatusSensor.temperatureTrend` and `StatusSensor.humidityTrend` (`SensorTrend`: `RISE`, `FALL`, `STEADY`), with the same rules as the Android parser. `--` and a missing field give `null`. Another text throws `ParseException`.
- `SensorHealthScreen` shows a line `T <value> <arrow>  RH <value> <arrow>` in each sensor card: `↑` for `RISE`, `↓` for `FALL`, `–` for `STEADY` and for `null` (`SensorHealthText.trend`).
- Tests: `StatusReplyTest.trendsOfFixtureAndBadFields` reads `tests/fixtures/status_reply_sample.txt`.

## Today summary in the macOS diagnostics view

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/ReplyTests.swift`
- `TodayReply.parse` parses the reply of the console command `TODAY`: `BEGIN TODAY`, the header `sensor,name,hours,temp_min_c,temp_max_c,temp_mean_c,rh_min_pct,rh_max_pct,rh_mean_pct` and one `TodayRow` for each row (sensor 0 to 10, name, hours 0 to 24, minimum, maximum and mean of temperature and humidity). An empty field gives `nil`. A wrong column count, a bad number, a sensor above 10, hours above 24 or a repeated sensor throws `ParseError.badLine`. `ConsoleCommand.today` sends `TODAY`.
- `TodayReply.message` maps `ERR NO_TIME` to the text `Panel clock not set`. Other error lines keep their text.
- `DiagnosticsView` has the group `Today` with the button `Read today` and one row for each sensor: name, hours and the minimum, maximum and mean of temperature and humidity (`--` for a sensor with 0 hours or an empty value). `AppModel.readToday` sends `TODAY` only on this button, on USB and on BLE. A lost link, a rejected reply or an error reply clears the rows.
- Tests: `TodayReplyTests` parse `tests/fixtures/today_reply_sample.txt` (2 rows, same as the Android `TodayReplyTest`), empty fields, bad rows, bad headers, the `ERR NO_TIME` text and the command line.

## Apply the SPREAD offset proposals in the Android calibration screen

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/SpreadReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/SpreadReply.kt`, `CalibrationScreen.kt`, `MainActivity.kt`, `SpreadReplyTest.kt`
- `SpreadReply.parse` reads each proposal line `<temp_key>=<x> <rh_key>=<x>` into `SpreadReply.Proposal` (temperature key and value, humidity key and value) in `Outcome.Measured.proposals`. A reply without proposal lines gives an empty list. A bad number throws `ParseException`. The Wear OS copy of `SpreadReply.kt` differs only in the `package` line; `scripts/check-protocol-sync.sh` checks it.
- The co-location card of `CalibrationScreen` shows one card with both keys and an `Apply` button for each proposal. A tap sends `SET <temp_key>=<x>` and `SET <rh_key>=<x>` over the BLE console, then `SPREAD` again. The first `ERR` reply stops the chain. `ERR READ_ONLY` shows `Set ble_console=2 to apply`.
- Host tests read `tests/fixtures/spread_reply_sample.txt` (one proposal with two keys) and cover a reply without proposal lines and bad numbers. They run in CI only when no Android SDK exists on the host.

## Low reception mask in the macOS diagnostics view

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/Diagnostics.swift`, `DiagnosticsReport.swift`, `Sources/RlcdCompanion/DiagnosticsView.swift`, `Tests/RlcdProtocolTests/DiagnosticsTests.swift`, `DiagnosticsReportTests.swift`
- `LatchMetrics.receptionLow` (`Int?`) parses `rxlow=` of the `STATUS` line `LATCH` (3 hex digits, at most `3ff`; bits 0 to 9 are slots 1 to 10). A missing field of older firmware gives `nil`. A bad field such as `rxlow=400` or `rxlow=0x1` throws `ParseError.badLine`. `hasBadges` includes it. `LatchMetrics.hasWeakSignal(slot:)` tests one slot; `badges(forSensor:)` adds `RX LOW` after `STALE` for slots 1 to 10.
- `DiagnosticsView` shows a slashed antenna symbol beside each flagged slot in the `Sensor health` rows and `RX LOW` in the `Panel badges` group. `DiagnosticsReport` lists `RX LOW` in the line `Panel badges`.
- Tests: `DiagnosticsTests.parsesReceptionLowFromSharedFixture` (fixture value `202`), `receptionLowIsNilForOlderFirmware`, `receptionLowAloneSetsHasBadges`, `receptionLowRejectsBadFields`; `DiagnosticsReportTests.weakSignalSlotsAreInTheReport`.
- Validation: `swift build` and host unit tests pass locally; `swift test` did not run locally (no TestingMacros in the Command Line Tools); CI `macOS companion tests` runs it.

## Open window notification on the Wear OS watch

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/WindowWatch.kt`, `MainActivity.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/WindowWatchTest.kt`, `scripts/check-protocol-sync.sh`
- `WindowWatch.kt` is a copy of the phone file; only the `package` line differs. `scripts/check-protocol-sync.sh` checks it. `update(mask)` takes the `window` field of the `STATUS` line `LATCH` (bit 0 onboard tile, bits 1 to 10 slots) and returns the tiles whose bit rose since the previous read. The first read, an equal mask, a falling bit and a `null` mask give no tile.
- `MainActivity.parseStatusReply` parses each `STATUS` reply of the sensor health read and of the mute read, then calls `checkWindowNotifications`. It posts one notification `Window open: <name>` for each rising tile (name from `PanelConfig.tileName`, id `WindowWatch.notificationId`, 2100 plus the tile) and one short vibration (`vibrateNotice`) for each read. Without the notification permission (Android 13 and later), nothing is posted. A reply that does not parse changes nothing.
- A window that opens while the notification permission is missing is not replayed after a grant (same rule as the phone and `checkWallNotifications`). The badge `Fenster` in `SensorHealthScreen` still shows the open tiles.
- Validation: `WindowWatchTest` (8 tests) covers the first read, a rising bit, an equal mask, a falling bit, a missing field, a bit above tile 10 and the message and notification id. The local host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs in CI only.

## Weak signal badge in the climate tiles

- A slot tile draws the inverted badge `R` while its bit is set in `PersistentState::reception_low` (bit 0 is slot 1, bit 9 is slot 10). The user can then move the sensor or the panel.
- The badge sits in the STANDARD, LARGE and COMPACT tile styles, beside the `V`, `D`, `W` and `C` badges (same 10 px steps, order `V`, `D`, `W`, `C`, `R`).
- The onboard tile never shows it. A stale tile draws no `R` badge, so the `STALE` badge stays readable. The tile text does not change.
- `SystemInfo::reception_low` carries the RTC copy to `DashboardView::render`; `rlcd_panel.ino` fills it from `g_state.reception_low`. The RTC layout and `STATE_VERSION` do not change. A mask of 0 gives the same frames as before.
- **Preview:** `docs/display_preview_weak_signal.png` shows the badge on slot 1.
- Host test `testWeakSignalBadge` asserts the badge pixels in each tile style, the shift beside the `C` badge, the stale tile, the CRC-32 of the sample scenes with mask 0 and the preview scene.

## Structured config editor in the Android app

- **Code:** `android/RlcdPhone/.../protocol/ConfigSchema.kt`, `ConfigEditorScreen.kt`, `MainActivity.kt`, `SensorHealthScreen.kt`, `ConfigSchemaTest.kt`.
- `ConfigSchema.entries` has one `SchemaEntry` for each key of `DeviceConfigParser::KEYS` (category, value rule, default, unit, description). The texts equal the macOS `ConfigSchema.entries`. The value rules, the order and the checks come from `ConfigKeys` (`ConfigBackup.kt`), so backup and editor share one rule set.
- `validate(key, value)` gives an error text or `null`. `diff(old, new)` lists the changes in schema order. `batch(changes)` gives the `SET key=value` lines in schema order; `ble_console` comes last. `problems` adds the duplicate MAC check and the 40-byte limit of a `SET` line.
- `plan(edits, current)` refuses the whole input when one value has a problem, so no `SET` reaches the panel. An edit that equals the current value gives no command.
- `ConfigSchema.run` sends the `SET` lines one by one over the BLE console. The first `ERR` reply drops the remaining `SET` lines; `CONFIG` is still sent, so the form shows the real panel state. A line above 40 bytes is refused before the send. `ERR READ_ONLY` shows a clear text for `ble_console=1`.
- `ConfigEditorScreen` shows one field for each key in the groups Climate, Alerts, Power, Sensors and BLE, with the problem text or the description under the field. The fields need `ble_console=2`. The `Config editor` button sits in the sensor health screen. A `CONFIG` read refreshes the config cache and clears the input.
- Tests: `ConfigSchemaTest` reads `docs/config.example.ini` (each template key once, template default), compares the ranges with the macOS values for 5 sample keys, checks the range error without a command, the batch order with `ble_console` last, the first `ERR` stop, `ERR READ_ONLY` and the 40-byte refusal.
- Limit: no Android SDK exists on the development host, so `gradle testDebugUnitTest assembleDebug` runs only in CI.

## Weak signal notification on the Wear OS watch

- **Code:** `wearos/RlcdWatch/.../protocol/StatusReply.kt`, `ReceptionWatch.kt`, `MainActivity.kt`, `SensorHealthScreen.kt`; tests `ReceptionWatchTest`, `StatusReplyTest`, `SensorHealthTextTest`.
- `LatchState.receptionLow` (`Int?`) parses `rxlow=` of the `STATUS` line `LATCH` (3 hex digits, at most `3ff`; bits 0 to 9 are slots 1 to 10). A missing field of older firmware gives `null`. A bad field such as `rxlow=400` or `rxlow=00g` throws `ParseException`. `LatchState.hasWeakSignal(sensor)` tests one slot; `badges()` does not change.
- `ReceptionWatch` follows the rules of `WindowWatch` for slots 1 to 10: the first read only stores the mask, a rising bit gives its slot number, a falling bit and an equal mask give nothing, a `null` mask keeps the stored mask.
- `MainActivity.parseStatusReply` feeds `rxlow` to `ReceptionWatch` after each `STATUS` read. It posts one notification `Weak signal: <slot name>` for each rising bit (id 2200 + slot) and one short vibration for each check. A read that raises a window bit and a weak signal bit vibrates twice. A rise is lost without the `POST_NOTIFICATIONS` permission, as for `WindowWatch`. The notifications appear only on `STATUS` reads.
- `SensorHealthScreen` shows `⚠ Weak signal` under each flagged slot row (`SensorHealthText.weakSignal`).
- `StatusReply.kt` and `ReceptionWatch.kt` also exist in `android/RlcdPhone` with the same code; `scripts/check-protocol-sync.sh` lists `ReceptionWatch.kt`.
- Validation: `ReceptionWatchTest` (9 tests) covers the first read, a rising bit, an equal mask, a falling bit, a missing field, slot 10, a bit above slot 10 and the message and notification id. The local host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs in CI only.

## Log gap report in the macOS archive view

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/LogGaps.swift`, `Sources/RlcdCompanion/ArchiveView.swift`, `Tests/RlcdProtocolTests/LogGapsTests.swift`.
- `LogGaps.find(rows:minGapMinutes:)` sorts the `ClimateRow` times first (a row with an unreadable timestamp is skipped). Each step above `minGapMinutes` (default 90) gives one `Gap` with `start` (last row before), `end` (first row after) and `missingHours` (step in hours, rounded, minus 1, at least 1). A step of exactly 90 minutes gives no gap. The step is the smaller of the real and the wall-clock difference, so the repeated hour of the October change and the lost hour of the March change give no false gap. A limit below 1 minute counts as 1 minute. A real gap across the October change counts one hour less. The days before the first row and after the last row are not counted.
- `LogGaps.report` adds `rowCount`, the sum of `missingHours` and `coveragePct` (rows divided by rows plus missing hours). A month without rows gives coverage 0 and no gap.
- `ArchiveView` shows the coverage and the gap list of the selected climate month above the climate charts. It reads only the rows of the downloaded files and sends no console command.
- Tests: `LogGapsTests` (11 tests) cover hourly rows, a 3-hour step, the exact 90-minute step, 91 minutes, an empty month, unsorted rows, an unreadable timestamp both daylight saving change nights the rounding of the missing hours and the limit clamp.
- Validation: `swift build` passes locally; `swift test` does not run locally (no TestingMacros in the Command Line Tools); CI `macOS companion tests` runs it.

## Android daily history screen

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/DaysReply.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/DaysScreen.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/PhoneDashboardScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/DaysReplyTest.kt`
- The dashboard button `Tagesverlauf` opens `DaysScreen`. The button `Tage lesen` sends `DAYS <N>` over the BLE console (7, 14 or 31 days) only after a tap. The panel needs USB power, `ble_console` 1 or 2 and a MicroSD card.
- `DaysReply.parse` reads the reply with the column layout of `SdLogCodec::parseDailyRow` (83 columns: the date, six values for each of 11 sensors, five noise columns, 11 mold hour columns). An empty field gives `null`. A half pair, a bad date, a value outside its range, mold hours above 24, a wrong header, a wrong row count and a wrong column count throw `ParseException`. The mean and noise columns are not read.
- The room selector shows the sensors that hold a value. One card shows one bar for each day: the temperature range, the humidity range and the mold hours (0 to 24). A day without a value is an empty outline.
- `ERR NO_CARD`, `ERR NO_FILE` and `ERR READ_FAILED` give a clear German text. The button `CSV teilen` shares one line for each day and sensor with data (`date,sensor,name,temp_min_c,temp_max_c,rh_min_pct,rh_max_pct,mold_h`).
- `DaysReply` exists in the phone app and in the Wear OS app (see the section on the monthly mold summary screen); `scripts/check-protocol-sync.sh` passes.
- Validation: `DaysReplyTest` reads `tests/fixtures/days_reply_sample.txt` (3 rows) and covers the values, an empty pair, a wrong column count, bad values and framing, the error texts, the CSV and the `CRC32` line. The local host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs in CI only.

## macOS daily history over the BLE console

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/DaysReply.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift` (`ConsoleCommand.days`), `macos/RlcdCompanion/Sources/RlcdProtocol/DailyLog.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DailyChartView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/ArchiveView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/DaysReplyTests.swift`.
- `ConsoleCommand.days(N)` sends `DAYS <N>` for N 1 to 31; 0 and 32 give no command. `DaysReply.parse(lines:)` reads `BEGIN DAYS <n>`, the `daily_v5` header and `n` rows through `DailyLog.parse`, so each row becomes a `DailyRow` equal to the same row of a `daily_v5` file. An empty pair gives `nil`. A wrong column count or a row count that differs from `n` throws `ParseError.badLine`; another header throws `ParseError.missingLine`.
- `DailyChartView` has a source selector: `Card files` (the folder rows as before) and `Panel (BLE)`. The panel source shows a day picker (`DaysReply.rowChoices`: 7, 14, 31) and the button `Read daily rows`. The app sends `DAYS <N>` only on this button, on USB and on BLE, and shows the rows in the existing charts (temperature, humidity, mold hours).
- `ERR NO_CARD`, `ERR NO_FILE`, `ERR READ_FAILED` and `ERR BAD_ARG` give a clear text (`DaysReply.message`). A lost link, a missing reply or a bad reply clears the rows (`AppModel.daysResult`).
- Validation: `DaysReplyTests` read `tests/fixtures/days_reply_sample.txt` (3 rows) and cover the values, an empty pair, a wrong column count, a bad count and header, the command limits, the error texts and the equality with the rows of a `daily_v5` file. `swift build` and `scripts/check-protocol-sync.sh` passed locally; `swift test` runs in CI (the local host lacks the Swift Testing macros).

## Sensor drift latch and event from the co-location median

- **Code:** `rlcd_panel/drift_latch.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/sd_logger.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
- The panel tells the user when one sensor drifts away from the other sensors, so the user can calibrate it before the alerts become wrong. The latch has no chime and no badge.
- `SpreadMath` (`drift_latch.h`) holds the median and deviation rules of the console command `SPREAD` (last 6 hourly samples of each sensor, at least 3 sensors, `OUTLIER` above 0.5 C or 3.0 %RH). `SerialConsoleRunner::showSpread` and `DriftLatch` use it, so both give the same result.
- `DriftLatch::update` runs in each wake cycle after the `ClimateHistory` push. A sensor that is `OUTLIER` sets its bit. The bit releases when both deviations are 0.3 C and 2.0 %RH or less; between the limits it keeps its state. Fewer than 3 sensors, a sensor with fewer than 6 samples and an empty slot clear the bit.
- `PersistentState::drift_tiles` (2 bytes just before the checksum; bit 0 onboard, bits 1 to 10 slots) holds the bits. A bit above bit 10 and a bit of an empty slot are rejected. `resetSlot` clears the bit and `swapSlotsByUser` swaps it. `STATE_VERSION` is 76.
- `EventCode::SENSOR_DRIFT` 26 records each changed bit (detail low byte the tile 0 to 10, high byte 1 drift, 0 good again). `EventRing::MAX_CODE` is 26.
- The `STATUS` line `LATCH` ends with ` drift=<3 hex digits>`. `tests/fixtures/status_reply_sample.txt` holds the field.
- Validation: host tests (`testSensorDriftLatch`) pass; `arduino-cli compile rlcd_panel` passes; `scripts/check-protocol-sync.sh` passes. The behavior on the board is not tested.

## Console command CONFIG CHANGED

- **Code:** `rlcd_panel/serial_console.h` (`SerialConsole::parse`, `SerialCommand::config_changed`, `SerialConsoleRunner::showConfigChanged`), `rlcd_panel/rlcd_panel.ino` (`run_console`), `tests/test_firmware_logic.cpp`, `tests/fixtures/config_changed_reply_sample.txt`.
- `CONFIG CHANGED` shows only the settings that differ from the default, so the user finds a wrong setting fast. The reply holds one line `<key>=<value>` for each key of `DeviceConfigParser::KEYS` whose value differs from a default `DeviceConfig`, in `KEYS` order, then `END CHANGED <N>` and the `CRC32` line over all lines before it.
- The line text equals the `CONFIG` and `CONFIG <key>` text (`DeviceConfigText::format`). An empty slot name and an empty MAC count as default and give no line. The default config holds no name and no MAC and gives `END CHANGED 0`. A change below the print precision of a key (two decimals; a gain step is 0.002) gives the same text and no line.
- `ERR BAD_ARG` for extra text (`CONFIG CHANGEDX`, `CONFIG CHANGED 1`, `CONFIG CHANGED `). The `CONFIG` and `CONFIG <key>` replies do not change.
- The command uses the RTC copy (`g_state.config`) and needs no card access (`SerialConsoleRunner::execute` ignores `CONFIG`). It runs on USB and on BLE at each `ble_console` level. RTC layout and `STATE_VERSION` do not change.
- Tests: `testSerialConfigChanged` (parser, errors, no card, BLE gate, default config, three changed keys in `KEYS` order, restored default) and `testSerialConfigChangedFixture` (byte-equal fixture and CRC-32 check).
- Validation: `c++ -std=c++17 -Wall -Wextra -Werror tests/test_firmware_logic.cpp` and the runner pass (236 suites, local macOS host); `arduino-cli compile rlcd_panel` and `scripts/check-protocol-sync.sh` pass. The behavior on the board is not tested.

## Sensor drift notification and calibration shortcut in the Android app

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/StatusReply.kt`, `DriftWatch.kt`, `EventText.kt`, `EventFilter.kt`, `MainActivity.kt`, `SensorHealthScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/DriftWatchTest.kt`
- `LatchState.drift` (`Int?`) holds the field `drift=<3 hex digits>` of the `STATUS` line `LATCH` (bit 0 onboard tile, bits 1 to 10 slots). A missing field gives `null`; a value that is not 3 hex digits or above `0x7FF` throws `ParseException`. `LatchState.badges` does not change.
- `DriftWatch.update(mask)` follows the rules of `WindowWatch`: the first read only stores the mask, a rising bit gives its tile, an equal mask, a falling bit and a `null` mask give no tile. `DriftWatch.notificationId(tile)` is 2300 plus the tile (free of `WindowWatch` 2100 and `ReceptionWatch` 2200).
- `MainActivity.checkDriftNotifications` runs after each `STATUS` parse (next to `checkWindowNotifications`) and posts one notification `Sensor drift: <name>` for each rising tile (name from `PanelConfig.tileName`). The notification tap starts `MainActivity` with the extra `open_calibration`; `handleCalibrationIntent` (`onCreate` and `onNewIntent`) opens `CalibrationScreen` and sends `SPREAD` through `withConsolePermission { readSpread() }`.
- `SensorHealthScreen` shows `⚠ Sensor drift` in the card of each flagged slot and in the panel diagnostics card for the onboard tile.
- `EventText` shows `Sensor drift (<tile>)` and `Sensor drift gone (<tile>)` for `SENSOR_DRIFT` 26 (high byte 1 or 0; a tile above 10 or a high byte above 1 gives the raw text). `EventFilter` sorts `SENSOR_DRIFT` into `sensors` in the Android, Wear OS and macOS lists, so `kotlinNamesMatchSwiftNames` stays green. The Wear OS copies of `StatusReply.kt`, `EventText.kt` and `EventFilter.kt` follow the phone files (`scripts/check-protocol-sync.sh`); the watch also has a drift notification (see below).
- The activity is exported, so any app can send the extra `open_calibration`; the effect is one read-only `SPREAD` read, an accepted risk. A tap while another console command runs opens the calibration without a `SPREAD` read; the user reads again with the screen button. The macOS `EventText.swift` has no `SENSOR_DRIFT` text yet (macOS roadmap item). A drift that rises while the notification permission is missing is not replayed after a grant (same rule as `WindowWatch`). Notifications appear only on `STATUS` reads, because the app has no periodic `STATUS` poll.
- Validation: `scripts/check-protocol-sync.sh` passes. `DriftWatchTest`, `StatusReplyTest.parsesDriftMask` and `EventTextTest.sensorDriftGivesTexts` are new. The local host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs in CI only; it did not run locally.

## Sensor drift notification and calibration shortcut in the macOS app

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/Diagnostics.swift`, `DriftWatch.swift`, `EventText.swift`, `EventFilter.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `DiagnosticsView.swift`, `Views.swift`
- The Mac app tells the user which sensor drifts and leads the user to the calibration.
- `LatchMetrics.drift` (`Int?`) parses the field `drift=` of the `STATUS` line `LATCH` (bit 0 onboard, bits 1 to 10 slots; `nil` for older firmware; a bad hex value or a value above `7FF` throws `ParseError.badLine`). `LatchMetrics.hasDrift(sensor:)` reads one bit. `badges(forSensor:)` stays unchanged.
- `DriftWatch` is a pure state machine: the first read sets the baseline and gives no tile, a later read gives each rising bit, a falling bit and a `nil` mask give no tile. `AppModel` feeds it with each `STATUS` reply (also while notifications are off) and posts one `UserNotifications` notification for each rising bit with the sensor name (`rlcd_panel_drift_<tile>`). A panel change resets the baseline.
- `DiagnosticsView` shows a drift button beside each flagged slot in the `Sensor health` rows (slots 1 to 5). The button calls `AppModel.openCalibrationForDrift()`: the main view scrolls to `CalibrationView` and `SPREAD` runs.
- `EventText` shows `Sensor drift (<tile>)` and `Sensor drift gone (<tile>)` for `EventCode::SENSOR_DRIFT` 26. `EventFilter` lists it in the category `sensors`.
- Validation: `DriftWatchTests`, `DiagnosticsTests` and `EventTextTests` cover the parser, the watch and the texts. `swift build` passes locally; `swift test` runs in CI only, because the local host lacks TestingMacros. `scripts/check-protocol-sync.sh` passes. The notification and the button are not tested on a panel.

## Weak signal notification in the macOS app

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/ReceptionWatch.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/ReceptionWatchTests.swift`
- The Mac app tells the user which sensor has a weak BLE signal.
- `ReceptionWatch` is a pure state machine with the rules of the Kotlin `ReceptionWatch.kt`: the first read only stores the mask, a rising bit of `LatchMetrics.receptionLow` gives its slot (1 to 10), an equal mask, a falling bit and a `nil` mask give no slot, a `nil` mask keeps the stored mask. Bits above slot 10 are ignored.
- `AppModel.checkReceptionNotifications` feeds it with each `STATUS` reply (also while notifications are off, so a rise does not repeat later) and posts one `UserNotifications` notification `Weak signal: <name>` for each rising slot (`rlcd_panel_reception_<slot>`; name from the `ConfigReply`, else `Slot <N>`). A panel change resets the baseline. Notifications appear only on `STATUS` reads.
- Validation: `ReceptionWatchTests` (11 tests, including a read of `tests/fixtures/status_reply_sample.txt` with `rxlow=202`) cover the rules. `swift build` passes locally; `swift test` runs in CI only, because the local host lacks TestingMacros. The notification is not tested on a panel.

## Sensor drift notification on the Wear OS watch

- **Code:** `wearos/RlcdWatch/.../protocol/StatusReply.kt`, `DriftWatch.kt`, `MainActivity.kt`, `SensorHealthScreen.kt`; tests `DriftWatchTest`, `StatusReplyTest`, `SensorHealthTextTest`.
- `LatchState.drift` (`Int?`) already parses `drift=<3 hex digits>` of the `STATUS` line `LATCH` (bit 0 onboard, bits 1 to 10 slots). A missing field of older firmware gives `null`; a bad field throws `ParseException`.
- `DriftWatch.kt` is a copy of the file in `android/RlcdPhone` with a different `package` line. The first read only stores the mask, a rising bit gives its tile, an equal mask, a falling bit and a `null` mask give nothing. `scripts/check-protocol-sync.sh` lists `DriftWatch.kt`.
- `MainActivity.parseStatusReply` feeds `drift` to `DriftWatch` after each `STATUS` read. It posts one notification `Sensor drift: <name>` for each rising tile (id 2300 + tile, free of `WindowWatch` 2100 and `ReceptionWatch` 2200) and one short vibration for each check. A rise is lost without the `POST_NOTIFICATIONS` permission, as for `WindowWatch`. Notifications appear only on `STATUS` reads.
- `SensorHealthScreen` shows `⚠ Sensor drift` in the row of each flagged sensor, onboard tile included (`SensorHealthText.driftMark`). The watch has no calibration shortcut.
- Validation: `scripts/check-protocol-sync.sh` and `--self-test` pass. `DriftWatchTest` (9 tests) and `SensorHealthTextTest.driftMark` are new. The local host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs in CI only; it did not run locally.

## Changed settings view and reset to default in the Android config editor

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ConfigChangedReply.kt`, `ConfigSchema.kt`, `ConfigEditorScreen.kt`, `MainActivity.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/ConfigChangedReplyTest.kt`
- The user sees the settings that differ from the default and resets them to the default on the phone.
- `ConfigChangedReply.parse` reads the reply of `CONFIG CHANGED` (lines before `CRC32`) into `ConfigChangedEntry` rows. A count in `END CHANGED` that differs from the line count, a missing `END` line, a line without `=`, an unknown key and a repeated key throw `ParseException`.
- `ConfigEditorScreen` has the button `CONFIG CHANGED lesen` and the switch `Changed only`, which shows only the keys of the reply. Each shown key has a `Reset` button; `Reset all` resets every changed key.
- `ConfigSchema.resetPlan` builds one `SET <key>=<default>` for each changed key through `batch` (fixed schema order, `ble_console` last). A key whose default the console cannot send (an empty name) goes to `skipped` with the reason; the other keys still reset.
- A dialog lists the commands and asks for a confirmation before the send. `applyConfigEditor` sends `CONFIG` after the batch, then `CONFIG CHANGED` again, so the list is current. A batch that sets `ble_console` or whose closing `CONFIG` fails after a `SET` was sent drops the list instead (the console can end); the user reads it again.
- Validation: `scripts/check-protocol-sync.sh` passes. The Gradle tests run in GitHub Actions CI (no Android SDK on the local host). The behavior on the phone is not tested.

## Assign and free slots from the Android rejects screen

- `RejectRow.action` gives `RejectAction.ASSIGN` for the reason `UNASSIGNED`, `RejectAction.FREE` for `FULL` and null for `DISABLED`, `JUMP` and `WEAK`. The Wear OS copy of `RejectsReply.kt` has the same code.
- `SlotConfig.assignRejected(slot, mac, config)` builds `SET slotN_mac=<MAC>` through `SlotConfig.define`. A slot with a fixed MAC, a disabled slot and a MAC that another slot holds give `Plan.Invalid`. `SlotConfig.clearRejected(slot, config)` builds `SLOT CLEAR <N>` and refuses a slot with a fixed MAC. `SlotConfig.freeableSlots(config)` lists the slots without a fixed MAC.
- `RejectsScreen` shows the menu `Slot zuweisen` (slots 1 to 10; a slot with a fixed MAC shows `feste MAC <MAC>` and a disabled slot shows `deaktiviert`; both cannot be picked; a MAC that a slot holds already switches the menu off) for an `UNASSIGNED` row, and one button `Slot <N> freigeben` for each slot without a fixed MAC for a `FULL` row.
- `MainActivity.fixReject` sends the commands, then `CONFIG` (the cached config is renewed) and `REJECTS` again. The controls stay off while a command or a read runs and when the cached `ble_console` is not 2.
- Validation: `scripts/check-protocol-sync.sh` passes. The Gradle tests run in GitHub Actions CI (no Android SDK on the local host). The behavior on the phone is not tested.

## Changed settings list on the Wear OS watch

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/ConfigChangedReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/ConfigSchema.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/ConfigBackup.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/ConfigChangedScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `scripts/check-protocol-sync.sh`.
- The user checks the panel settings on the wrist during troubleshooting. The chip `Changed settings` in the sensor health screen opens `ConfigChangedScreen`.
- `ConfigChangedReply.kt`, `ConfigSchema.kt` and `ConfigBackup.kt` (the types `ConfigKind`, `ConfigChange` and `ConfigKeys` that `ConfigSchema.kt` needs) are copies of the phone files with another `package` line. All three are in the `FILES` list of `scripts/check-protocol-sync.sh`. The parser needs `ConfigSchema.entry` to reject unknown keys.
- The button `Read settings` sends `CONFIG CHANGED` once over the BLE console. The screen shows one card for each changed key with key and value. A count of 0 shows `Alle Werte Standard`. A bad reply shows `Antwort nicht lesbar`.
- The screen sends no `SET`.
- Tests: `ConfigChangedReplyTest` reads `tests/fixtures/config_changed_reply_sample.txt` and checks keys and values, the CRC-32, a count of 0, a bad count and bad lines.
- Validation: `scripts/check-protocol-sync.sh` passes. The Gradle tests run in GitHub Actions CI (no Android SDK on the local host). The behavior on the watch is not tested.

## Changed settings view and reset to default in the macOS config editor

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/ConfigChangedReply.swift`, `Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `ConfigEditorView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/ConfigChangedReplyTests.swift`
- The user sees the settings that differ from the default and resets each one to the default on the Mac.
- `ConsoleCommand.configChanged` sends `CONFIG CHANGED`. `ConfigChangedReply.parse` reads the lines before `CRC32` into `ConfigChangedEntry` rows. A count in `END CHANGED` that differs from the line count, a missing or malformed `END` line, a line without `=`, an unknown key and a repeated key throw `ParseError`.
- `ConfigEditorView` has the toggle `Changed only` and the button `Read changed` (`AppModel.readConfigChanged`). The toggle shows only the keys of the reply. Each changed key has a `Reset` button.
- `Reset` (`AppModel.resetToDefault`) builds one `ConfigChange` to the `ConfigSchema` default (`ConfigSchema.resetChanges`) and sends `SET <key>=<default>` through `ConfigBatch`, then `CONFIG`, then `CONFIG CHANGED`. A key whose default the console cannot send (an empty name) has no reset: `ConfigSchema.resetPlan` reports it in `problems`. The button is off for `ble_console` 1, without a console that takes `SET`, during a running command and while the form holds unsaved edits (the `CONFIG` reply of the batch would drop them). Resetting `ble_console` skips the `CONFIG CHANGED` read (the console can end). Any `SET`, another batch, a failed `CONFIG`, a failed read and a link change drop the list, so no `Reset` button works on an old list.
- Validation: `ConfigChangedReplyTests` (9 tests, read `tests/fixtures/config_changed_reply_sample.txt`) are new. `swift build` passes. `swift test` does not run on the local host (no `TestingMacros`); it runs in CI. A scratch executable checked the fixture parse, the count and key errors and the reset batch locally. The behavior on a panel is not tested.

## Mold summary in the Android daily history screen

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/MoldSummary.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/DaysScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/MoldSummaryTest.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/DaysMoldCardsTest.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/MoldSummaryTest.kt`, `scripts/check-protocol-sync.sh`
- The user sees on the phone how many mold hours each room had in the days of the `DAYS` reply.
- `MoldSummary.kt` is a copy of the Wear OS file without the `package` line. `scripts/check-protocol-sync.sh` lists it in `FILES`.
- `DaysScreen` shows one card for each room above the charts (`DaysMoldCards.cards`, most mold hours first). The card shows the total mold hours, the mold days and the worst day (the newest day on a tie). A room with 0 mold hours shows `Keine Schimmelstunden`. A room without a mold value has no card.
- Validation: `MoldSummaryTest` (6 tests in each app) reads `tests/fixtures/days_reply_sample.txt`. The fixture holds no mold value, so the tests set the onboard mold column of its rows. `DaysMoldCardsTest` (4 tests) is new. `./scripts/check-protocol-sync.sh` exits 0. `gradle testDebugUnitTest` does not run on the local host (no Android SDK); it runs in CI.

## Monthly mold summary in the macOS daily history

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/MoldSummary.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DailyChartView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/MoldSummaryTests.swift`
- The user sees one mold line for each room above the daily charts on the Mac.
- `MoldSummary.summarize(rows:sensor:)` uses the rules of the Wear OS `MoldSummary.kt` on `DailyRow.moldHours`: the total mold hours, the days with at least one mold hour and the worst day. A tie selects the newest day (the rows run oldest first). A row without a value for the sensor does not count; a sensor without any value gives `totalHours` `nil`.
- `DailyChartView` shows the line `<room>: Mold <N> h, <D> days, worst <MM-DD> <H> h` for each room with a value, and `<room>: No mold hours` for a room whose total is 0. A room without a mold value (rows of a `daily_v1` to `daily_v3` file, empty or stale sensor) has no line. The source is the archive folder or the `DAYS` reply, whichever the chart shows.
- Tests: `MoldSummaryTests` read `tests/fixtures/days_reply_sample.txt` (no mold values, so each total is `nil`), and check the sum, the day count, the worst day, the tie rule, the `No mold hours` text and the rows of a `daily_v3` file without mold columns.
- Validation: `swift build` passes. `swift test` does not run on the local host (no `TestingMacros`); it runs in CI. The view is not checked on a screen.

## Apply the SPREAD offset proposals on the Wear OS watch

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SpreadScreen.kt`, `MainActivity.kt`, `protocol/SpreadApply.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/SpreadReplyTest.kt`
- The user corrects a drifting sensor from the watch. `SpreadScreen` shows one `Übernehmen` chip below each `OUTLIER` row that has a proposal. An `OK` row has no chip.
- `SpreadApply.proposalFor` pairs the n-th `OUTLIER` row with the n-th proposal line. A tap on the chip shows both offsets (`Temp <x> °C, Feuchte <y> %RH`) with the chips `Senden` and `Abbrechen`.
- `SpreadApply.proposalFor` accepts only a temperature offset key and a humidity offset key with values in the firmware range (`CalibrationKeys.validate`). `Senden` runs `SpreadApply.batch` (`CalibrationBatch`): the `SET` commands of `SpreadReply.Proposal`, temperature first, then `CONFIG`. After success `SPREAD` runs again. The first `ERR` stops the batch and shows its text.
- `ERR READ_ONLY` (`ble_console` 1) shows `Nur lesen` and hides the chips. The shared files `SpreadReply.kt` and `CalibrationBatch.kt` stay equal to `android/RlcdPhone`; `SpreadApply.kt` exists only on the watch.
- Validation: `SpreadReplyTest` has 9 new tests (the `SET` commands of the fixture proposal, no commands for an `OK` row, pairing of two outliers, the full batch, the stop after `ERR READ_ONLY`, the stop after another `ERR`, the key and range check of a proposal). They run in CI only (no Android SDK on the local host). `./scripts/check-protocol-sync.sh` exits with 0.

## Sensor drift badge in the climate tiles

- A tile draws the inverted badge `S` while its bit is set in `PersistentState::drift_tiles` (bit 0 is the onboard tile, bits 1 to 10 are the slots). The user sees a drifting sensor on the panel without an app.
- The badge sits in the STANDARD, LARGE and COMPACT tile styles, beside the `V`, `D`, `W`, `C` and `R` badges (same 10 px steps, order `V`, `D`, `W`, `C`, `R`, `S`). A stale tile draws no `S` badge, so the `STALE` badge stays readable. The tile text does not change.
- `SystemInfo::drift_tiles` carries the RTC copy to `DashboardView::render` (new last parameter `drift_tiles`); `rlcd_panel.ino` fills it from `g_state.drift_tiles`. The RTC layout and `STATE_VERSION` do not change. A mask of 0 gives the same frames as before.
- **Preview:** `docs/display_preview_drift.png` shows the badge on the onboard tile and on slot 1 (scene field `PreviewScene::drift`, `PREVIEW_SCENE_COUNT` 23).
- Host test `testDriftBadge` asserts the badge pixels in each tile style, the shift beside the `V` and `R` badges, the stale tile, a clear bit, the tile of each bit in the 4-tile layout and the preview scene. The test fails without the badge code.

## Weak signal notification in the Android app

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt`, `protocol/ReceptionNotice.kt`; test `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/ReceptionNoticeTest.kt`
- `ReceptionNotice.plan(status, names)` feeds `status.latch?.receptionLow` (`rxlow=` of the `STATUS` line `LATCH`) to the shared `ReceptionWatch` and gives one `Notice` (slot, message, notification ID) for each slot whose bit rose since the previous read. The message is `ReceptionWatch.message(name)` and the ID is `ReceptionWatch.notificationId(slot)` (2200 plus the slot). A slot without a name (index outside the list or a blank text) uses its slot number as the name. `MainActivity` passes `PanelConfig.tileNames`, which already holds the default title `Slot <N>` for an unnamed slot, so the notification reads `Weak signal: Slot <N>` there.
- The first reply only stores the mask. A reply without `rxlow` (older firmware), an equal mask and a falling bit give no notice. The shared `ReceptionWatch.kt` did not change.
- `MainActivity.checkReceptionNotifications` runs after each `STATUS` parse (next to `checkWindowNotifications` and `checkDriftNotifications`) with `panelConfig.tileNames`. It posts through `postVentilationNotification`, which posts nothing without the `POST_NOTIFICATIONS` permission (SDK 33 and later).
- A bit that rises while the permission is missing gives no notification later, because the watch has already stored the mask.
- Validation: `scripts/check-protocol-sync.sh` passes. `ReceptionNoticeTest` (7 tests) covers the fixture mask `202` (slots 2 and 10, IDs 2202 and 2210), the first reply, a missing `rxlow` that keeps the stored mask, an unknown sensor, the default titles of `PanelConfig.tileNames`, an equal mask and a falling bit. The local host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs in CI only; it did not run locally.

## Reset changed settings to the default on the Wear OS watch

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/ConfigReset.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/ConfigChangedScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/ConfigChangedReplyTest.kt`
- The user undoes a wrong setting from the wrist. Each row of `ConfigChangedScreen` has a `Zurücksetzen` chip. A tap shows the key and the default value (`<key> auf <default> setzen?`; an empty default reads `leer`) with the chips `Senden` and `Abbrechen`.
- `Senden` sends the `SET` command of the shared `ConfigSchema.resetPlan` for this key (`MainActivity.resetConfigKey`), then `CONFIG CHANGED`, so the list is current. The first `ERR` stops the run and shows its text. `ERR READ_ONLY` shows `Nur lesen` and hides all chips.
- A reply that is no `ERR` status (timeout, bad frame) leaves the panel state unknown: the list clears and only the error text stays.
- A key without a sendable default (an empty name) has no chip. The screen shows the skip reason of `ConfigResetPlan.skipped`.
- `ConfigReset.kt` is watch-only (not in the `FILES` list). The shared files do not change.
- Tests: `ConfigChangedReplyTest` gives one reset command for each fixture key with the `ConfigSchema` default, puts a key with an empty default in `skipped`, and checks that `ERR READ_ONLY` and another `ERR` stop the run after the first command.
- Validation: `scripts/check-protocol-sync.sh` passes. The Gradle tests run in GitHub Actions CI (no Android SDK on the local host). The behavior on the watch is not tested.

## Mold hours in the Wear OS week screen

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/WeekMoldText.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/WeekScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/WeekMoldTextTest.kt`
- The user sees the mold hours of one room for the week on the wrist, as on the phone and on the panel. `WeekScreen` shows `Schimmel <N> h` below the day rows. `N` is the sum of `WeekRow.moldHours` over the days with a value (`WeekMoldText.summary`).
- A gap day and a day with an empty field do not add to the sum. `summary` gives `null` when no day has a value (reply with 7 columns, older firmware), and the screen shows no line.
- Each day row keeps its own line `⚠ Schimmel <N> h` for more than 0 hours (`WeekText.mold`, unchanged).
- `WeekMoldText.kt` is watch-only (not in the `FILES` list). The shared files do not change.
- Tests: `WeekMoldTextTest` reads `tests/fixtures/week_reply_sample.txt` (sensor 0 gives 17 h, sensor 1 gives 10 h with one empty day), checks that gap days add nothing, and checks that a 7-column reply gives `null`.
- Validation: `scripts/check-protocol-sync.sh` passes. The Gradle tests run in GitHub Actions CI (no Android SDK on the local host). The behavior on the watch is not tested.

## Dry hours in the Wear OS week screen

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/WeekMoldText.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/WeekScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/WeekMoldTextTest.kt`
- The user sees the dry hours of one room for the week on the wrist. `WeekScreen` shows `Trocken <N> h` below the `Schimmel <N> h` line. `N` is the sum of `WeekRow.dryHours` over the days with a value (`WeekMoldText.dry`).
- A gap day and a day with an empty field do not add to the sum. `dry` gives `null` when no day has a value (reply with 7 or 8 columns, limit 0, older firmware), and the screen shows no line.
- No new console command. The `WEEK <N>` reply already holds the column `dry_h`. `WeekMoldText.kt` is watch-only (not in the `FILES` list).
- Tests: `WeekMoldTextTest` gives `Trocken 9 h` for three days with 2, 3 and 4 hours, `null` for seven gap days, `null` for a reply with the 8-column header, and ignores a day without a value in the sum.
- Validation: The Gradle tests run in GitHub Actions CI (no Android SDK on the local host). The behavior on the watch is not tested.

## Hour window selection for SPREAD in the macOS calibration view

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/CalibrationView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/CalibrationTests.swift`
- `ConsoleCommand.spread(hours:)` sends `SPREAD` for 6 hours and `SPREAD <H>` for 7 to 24 hours. Another number gives no command (`line` is `nil`).
- `CalibrationView` shows a picker with 6, 12 and 24 hours (`ConsoleCommand.spreadWindows`). `AppModel.spreadHours` holds the window and `spreadReplyHours` the window of the last read (the skip text shows it). `spreadCheck()` sends it with each read, and the read after `applySpread()` sends it again. The hint shows the selected window.
- `ConsoleCommand.spreadErrorMessage(hours:text:)`: `ERR BAD_ARG` for a window above 6 hours shows `Firmware update needed` (an older firmware has no hour argument). The 6 hour window sends plain `SPREAD`, so it works with each firmware.
- `SpreadReply.parse` does not change. The reply has the same format for each window.
- Tests: `CalibrationTests` check the command for 6, 7, 12 and 24 hours, no command for 0, 5 and 25, the picker windows, and the reply of `tests/fixtures/spread_reply_sample.txt` for each window. They run in CI (the local host lacks TestingMacros).

## Salt test progress in the Android calibration screen

- `CalibrationScreen` of the phone app shows when a salt test has enough stable hours. The buttons `NaCl beobachten` and `MgCl2 beobachten` start a watch for the picked reference sensor.
- `SaltProgress` (`protocol/SaltProgress.kt`, phone only) follows the rules of the macOS `SaltProgress.swift` with an injected clock in milliseconds. A stable hour has a humidity span of 1.0 %RH or less. The run starts at age 0 and ends at the first gap or at the first span above the limit, so a jump resets the count. The text is `<N> von 6 Stunden stabil`; the count stops at 6.
- `MainActivity` sends `HISTORY N` every 10 minutes (`SaltProgress.INTERVAL_SECONDS`) while the screen is open and no other console command runs. At 6 stable hours the watch ends and `SALT N` (or `SALT N MGCL2`) follows through `readSalt`.
- The watch stops after 8 hours (`StopReason.TIMEOUT`, checked at each tick), on a lost link or a reply timeout, on an `ERR` reply or an unreadable reply, when the user taps `Beobachtung beenden` or picks another sensor, when the app goes to the background and when the user leaves the screen. The first stop reason stays; a stopped watch ignores later replies.
- Tests: `SaltProgressTest` reads `tests/fixtures/history_reply_sample.txt` and gives the stable hours of the macOS `SaltProgressTests` rules (sensor 0: 1, sensor 1: 2, sensor 3: 1). It also covers the reset by a span above 1.0 %RH, the inclusive limit, a gap, the stop after 8 hours with the injected clock and the stop by a reply error.
- Validation: `scripts/check-protocol-sync.sh` passes (`SaltProgress.kt` is phone only). The Gradle tests run in GitHub Actions CI (no Android SDK on the local host). The behavior on a device is not tested.

## Config key reception_low_pct for the weak signal limit

- **Code:** `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `docs/config.example.ini`, `tests/test_firmware_logic.cpp`, the three `ConfigSchema` files
- The user sets the reception rate that sets the `R` badge. A far sensor has a low rate and sets a false badge, so the user lowers the limit.
- The key `reception_low_pct` is 0 (off) or 30 to 80 %, default 50. It is 1 byte at the end of the config trailer, after `mold_alert_delay_h`. `KEY_COUNT` is 98 and `STATE_VERSION` is 77.
- `PersistentState::recordReceptionChanges(now, limit_pct)` sets the bit of a slot below the limit and releases it at the limit plus 20 %. The default gives the old 50 % and 70 % rules. Value 0 clears each bit without a `RECEPTION_LOW` event.
- Tests: `testReceptionLowPctConfig` (default, 30, 80, 0, reject of 29, 81 and `x`, RTC round trip), `testReceptionLowPctLatch` (limit 30, default rules, limit 0), `ConfigSchemaTests.rangesMatchFirmwareConstants` and `ConfigSchemaTest.receptionLowPctAcceptsOffAndThirtyToEighty`.

## Battery runtime preview in the Android config editor

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/RuntimePreview.kt`, `ConfigEditorScreen.kt`, `MainActivity.kt`, `SensorHealthScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/RuntimePreviewTest.kt`
- `RuntimePreview` (phone only) ports the macOS `RuntimePreview.swift` ratio model: the `RUNTIME h` value of the last `STATUS` scales with the ratio of the drain rates of the current and the edited schedule. The drain rate is `(day_h / (wake_interval_s + awake) + night_h / (night_wake_interval_s + awake)) / 24`. The sleep current is not modelled, so the value is an estimate.
- The awake time follows `BatteryHistory::estimate`: the `AWAKE avg_ms` mean counts from 8 samples and above 2000 ms, else the constant 10 s applies. A `RUNTIME h` value at the cap of 2388 h gives no preview; a result stops at 2388 h.
- `night_wake_interval_s` counts only for the quiet hours (22 to 7 is 9 of 24 hours; equal start and end is 0). A night interval of 0 uses `wake_interval_s` for all 24 hours. The quiet window comes from the draft or the `CONFIG` values, else from the `QUIET` line.
- `ConfigEditorScreen` shows `Laufzeit ca. <N> h` below the `night_wake_interval_s` field and `Laufzeit --` without a `RUNTIME` value, without an `AWAKE` mean, for a draft value that is no integer and for an interval outside the key range. It shows nothing before the first `STATUS` read. The preview sends no command.
- The current schedule is the cached `CONFIG` of the moment of the `STATUS` read (`SensorHealthState.measuredConfig`); a later `CONFIG` read does not move it.
- The firmware does not change; the RTC layout and `STATE_VERSION` do not change.
- Tests: `RuntimePreviewTest` (doubled `wake_interval_s` gives the macOS value 764, night interval only for the quiet hours, missing runtime and zero awake samples give null, invalid draft gives null, cap and few samples). `scripts/check-protocol-sync.sh` passes. The Gradle tests run in GitHub Actions CI (no Android SDK on the local host). The behavior on a device is not tested.

## Hour window argument for the console command FRSI

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
- A short cold night gives fewer than 6 stable hours. The user selects the sample window of the wall factor check.
- The optional argument `FRSI <R> <W> <H>` takes H 3 to 24 without a leading zero (`SerialCommand::frsi_hours`). `FRSI <R> <W>` stays `FRSI <R> <W> 6` and gives the same bytes.
- `showFrsi` uses the newest H samples of each history. Fewer than H samples give `SKIP hours=<n>`. The `UNSTABLE` limit 0.10 and the `ERR LOW_DELTA` limit 10 C stay. The reply `hours=` field holds H.
- H 2, H 25, a leading zero, a non-digit and extra text give `ERR BAD_ARG`. The RTC layout and `STATE_VERSION` do not change.
- Tests: `testSerialFrsiParser` (default, H 3, 12, 24, bad H), `testSerialFrsiWindow` (3 samples with H 3, `SKIP hours=6` with H 12, equal bytes for the default and H 6, newest samples only, `ERR LOW_DELTA`).
- Validation on the board: send `FRSI 0 2 3` over USB and check the reply and the `CRC32` line.

## Open window latch notification in the macOS app

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/WindowWatch.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/EventText.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/WindowWatchTests.swift`
- `WindowWatch.update(mask:)` follows the rules of the Kotlin `WindowWatch`: the first read only stores the mask, a rising bit gives its tile (0 onboard, 1 to 10 slots), an equal mask and a falling bit give no tile, and a `nil` mask (older firmware) keeps the stored mask. `WindowWatch.notificationBody` gives `Window open: <name>` with `EventText.roomName`.
- `AppModel.checkWindowLatchNotifications` feeds it with `LatchMetrics.window` after each `STATUS` reply and posts one notification for each rising tile (identifier `rlcd_panel_window_<tile>`). The watch runs also while notifications are off, so a later switch-on does not replay a rise. `clearPanelData` resets it for a new panel.
- The old event list watch `WindowWatch` in `EventText.swift` is now `WindowEventWatch` (same rules, no change). Both paths use the identifier `rlcd_panel_window_<tile>`, so a window that both a `STATUS` reply and an event list report shows one notification (the later text replaces the earlier text). Each post can still play the sound, so one window can sound twice; this is accepted.
- Tests: `WindowWatchTests` (7 tests) cover the first mask, a rising bit once, an equal mask, a falling bit, a `nil` mask, a bit above tile 10 and the body text. They run in CI (the local host lacks TestingMacros). `swift build -c release` passes locally.

## Wear OS slot release

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/SlotClearCommand.kt`, `SensorHealthScreen.kt`, `MainActivity.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/SlotClearCommandTest.kt`
- The user frees a dead sensor slot from the watch. `SensorHealthScreen` shows a `Platz freigeben` chip below each sensor row with the state `STALE` (slot 1 to 10). The onboard sensor and a slot without the state `STALE` have no chip.
- `SlotClearCommand.canFree` also needs a `CONFIG` reply in the cache and no fixed MAC (`PanelConfig.slotMac`) for the slot. Without a `CONFIG` read the chip stays hidden, because a fixed MAC is unknown.
- A tap on the chip asks `Platz <N> freigeben?` with the chips `Senden` and `Abbrechen`. `Senden` calls `MainActivity.clearSlot`: it sends `SLOT CLEAR <N>` (`SlotClearCommand.line`, 1 to 10, other numbers throw `IllegalArgumentException`) and after `OK SLOT CLEAR <N>` it sends `STATUS`. An `ERR` reply sends no `STATUS`. A timeout or a corrupt reply still sends `STATUS`, because the command can have run. No command is retried. The result text and the confirmation reset when the user reads again or leaves the screen.
- `SlotClearCommand.result` words the reply: `Platz <N> freigegeben`, `Platz hat eine feste MAC` for `ERR FIXED_MAC`, `Nur lesen. Setze ble_console=2, um Plätze freizugeben.` for `ERR READ_ONLY`, and `Antwort nicht lesbar` for each other reply. `SlotClearCommand.kt` exists only on the watch.
- Tests: `SlotClearCommandTest` (slot 3 gives `SLOT CLEAR 3`, 0 and 11 throw, the three reply texts, an unknown reply, the chip rules including a slot with a fixed MAC).

## Wear OS slot swap

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/SlotSwapCommand.kt`, `SensorHealthScreen.kt`, `MainActivity.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/SlotSwapCommandTest.kt`
- The user swaps two sensor slots from the watch. `SensorHealthScreen` shows a `Platz tauschen` chip below each slot row (1 to 10) without a fixed MAC. A tap picks the first slot; the other rows then show `Mit Platz <A> tauschen`, and the first row shows `Tausch abbrechen`.
- `SlotSwapCommand.canSwap` needs a `CONFIG` reply in the cache and no fixed MAC (`PanelConfig.slotMac`) for the slot. The onboard sensor never swaps.
- The second pick asks `Platz <A> und <B> tauschen?` with the chips `Senden` and `Abbrechen`. `Senden` calls `MainActivity.swapSlots`: it sends `SLOT SWAP <A> <B>` (`SlotSwapCommand.line`, two different slots 1 to 10, else `null`) and then `STATUS`. An `ERR` reply sends no `STATUS`; a timeout or a corrupt reply still sends it. No command is retried.
- `SlotSwapCommand.result` words the reply: `Plätze <A> und <B> getauscht`, `Platz hat eine feste MAC` for `ERR FIXED_MAC`, `Platz ist abgeschaltet` for `ERR SLOT_OFF`, `Nur lesen. Setze ble_console=2, um Plätze zu tauschen.` for `ERR READ_ONLY`, `Panel meldet: <line>` for another `ERR` line and `Antwort nicht lesbar` for each other reply. `SlotSwapCommand.kt` exists only on the watch.
- Tests: `SlotSwapCommandTest` (`SLOT SWAP 1 3`, equal slots, 0 and 11 give no line, a fixed MAC slot gives `canSwap` false, the confirm text, the result text of each reply, an unknown reply).

## Clearing drift event for a stale slot

- **Code:** `rlcd_panel/power_manager.h`, `tests/test_firmware_logic.cpp`
- `PersistentState::updateDrift` adds the tiles that held a bit before the call to the event mask. A drifting slot that goes stale loses its bit and records one `SENSOR_DRIFT` event with detail `0x00<tile>`. A stale slot without a bit records nothing.
- Fewer than 3 sensors, `resetSlot` and a power-on keep clearing each bit without an event.
- The RTC layout and `STATE_VERSION` do not change.
- Host tests cover a drifting slot that goes stale, a stale slot without a bit, `resetSlot` and fewer than 3 sensors.

## Wall factor check on the Wear OS watch

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/FrsiReply.kt`, `FrsiScreen.kt`, `SensorHealthScreen.kt`, `MainActivity.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/FrsiReplyTest.kt`, `tests/fixtures/frsi_reply_sample.txt`.
- `FrsiReply.kt` is a port of the phone file (`FrsiCommand`, `FrsiReply`, `FrsiText`) with the watch package. `FrsiReply.parseBytes` runs the `ReplyAssembler` and throws `ParseException` for a bad or missing `CRC32` line and for a status line such as `ERR LOW_DELTA`. `FrsiApply` builds the batch `SET wall_frsi_pct=<N>`, then `CONFIG`, and words the result.
- `FrsiScreen` (`ScalingLazyColumn`) has a room chip (onboard to slot 10) and a wall chip (slot 1 to 10); each tap selects the next entry and clears the old result. `FrsiCommand.line` throws `IllegalArgumentException` for an equal pair, so the screen shows `Raum und Wand müssen verschieden sein` and sends nothing.
- The watch sends `FRSI <R> <W>` over the BLE console only after a tap on `Messen`. The screen shows the factor, the span, the hours, the difference and the line `Vorschlag: wall_frsi_pct=<N>`. `SKIP`, `UNSTABLE`, `ERR LOW_DELTA`, `ERR NO_OUTDOOR` and `ERR BAD_ARG` have their own texts.
- The chip `Übernehmen` asks for a confirmation. `Senden` sends `SET wall_frsi_pct=<N>`, then `CONFIG`. `ERR READ_ONLY` shows `Set ble_console=2 to apply` and hides the chip. No command is retried. `SensorHealthScreen` has the chip `Wall factor`.
- Validation: `FrsiReplyTest` reads `tests/fixtures/frsi_reply_sample.txt` (factor 0.70, proposal 70) and covers `SKIP`, `UNSTABLE`, the error texts, an equal pair, a bad CRC-32 line and the apply batch. It runs in CI only (no Android SDK on the local host).

## Hour window picker for the macOS FRSI check

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `Sources/RlcdCompanion/AppModel.swift`, `Sources/RlcdCompanion/CalibrationView.swift`, `Tests/RlcdProtocolTests/CalibrationTests.swift`
- The user selects the sample window of the wall factor check in the `Wall factor` section of `CalibrationView`. A segmented picker offers 3, 6, 12 and 24 hours (`ConsoleCommand.frsiWindows`, `AppModel.frsiHours`, default 6).
- `ConsoleCommand.frsi(room:wall:hours:)` replaces `frsi(room:wall:)`. The window of 6 hours (`frsiDefaultHours`) sends `FRSI <R> <W>`; the other values send `FRSI <R> <W> <H>`. A window below 3 or above 24 hours, equal sensors and a wall outside 1 to 10 give no command (`line` is `nil`).
- The measured reply keeps its `<N> h` text beside the factor. A `SKIP hours=<n>` reply shows `<n> of <H> h, too few samples (<H> needed)` with the window of the request (`AppModel.frsiReplyHours`). The firmware needs the item `FRSI <R> <W> <H>`; an older firmware answers `ERR BAD_ARG` for a window other than 6 hours, and the app shows `Firmware update needed` (`FrsiReply.statusResult(_:hours:)`). `FrsiReply.skipText` words the `SKIP` text.
- Tests: `CalibrationTests.frsiHourWindow` (6 gives `FRSI 0 2`, 12 gives `FRSI 0 2 12`, 3 and 24, 2 and 25 give `nil`) and `frsiSkipForLongerRequest` (`SKIP hours=6` for a 12 hour request) and `frsiSkipTextAndFirmwareHint` (`6 of 12 h, too few samples (12 needed)`, `Firmware update needed` for `ERR BAD_ARG` at 12 hours but not at 6).

## Dry air notification in the Android app

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/DryWatch.kt`, `MainActivity.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/DryWatchTest.kt`
- `DryWatch.update(mask)` follows the rules of `WindowWatch`: the first read only stores the mask, a rising bit gives its tile (bit 0 onboard tile, bits 1 to 10 slots), an equal mask, a falling bit and a `null` mask give no tile. A `null` mask keeps the stored mask. `DryWatch.message(name)` gives `Dry air: <name>`. `DryWatch.notificationId(tile)` is 2400 plus the tile (free of `WindowWatch` 2100, `ReceptionWatch` 2200 and `DriftWatch` 2300).
- `MainActivity.checkDryNotifications` runs after each `STATUS` parse (next to `checkDriftNotifications`). It feeds `LatchState.dry` (the field `dry=<3 hex digits>` of the `STATUS` line `LATCH`) to `DryWatch` and posts one notification for each rising tile (name from `PanelConfig.tileName`). No `STATUS` line `LATCH` gives a `null` mask and keeps the state.
- `DryWatch.kt` is in the `check-protocol-sync.sh` list; the Wear OS watch holds a copy.
- Validation: `scripts/check-protocol-sync.sh` passes. `DryWatchTest` (9 tests) is new. The local host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs in CI only; it did not run locally.

## Dry air vibration on the Wear OS watch

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/DryWatch.kt`, `MainActivity.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/DryWatchTest.kt`
- `DryWatch` is a pure state machine with the rules of `WindowWatch`. `update(mask)` takes the `dry` field of the `STATUS` line `LATCH` (bit 0 onboard tile, bits 1 to 10 slots) and returns the tiles whose bit rose since the previous read. The first read, an equal mask, a falling bit and a `null` mask give no tile. A `null` mask keeps the stored mask.
- `MainActivity.parseStatusReply` feeds the `dry` mask of each parsed `STATUS` reply to `checkDryNotifications`. It posts one notification `Dry air: <name>` for each rising tile (name from `PanelConfig.tileName`, id `DryWatch.notificationId`, 2400 plus the tile) and one short vibration (`vibrateNotice`) for each read. Without the notification permission (Android 13 and later), nothing is posted.
- `DryWatch.kt` is a copy of the phone file; only the `package` line differs. It is in `FILES` of `scripts/check-protocol-sync.sh`.
- Validation: `DryWatchTest` (9 tests, same as the phone) covers the first read, a rising bit, an equal mask, a falling bit, a missing field, a missing first field, a bit above tile 10 and the message and notification id. The local host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs only in CI.

## Dry air notification in the macOS app

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/DryWatch.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/DryWatchTests.swift`
- `DryWatch.update(mask:)` follows the rules of `WindowWatch`: the first read only stores the mask, a rising bit gives its tile (0 onboard, 1 to 10 slots), an equal mask and a falling bit give no tile, and a `nil` mask keeps the stored mask. `DryWatch.notificationBody` gives `Dry air: <name>` with `EventText.roomName`.
- `AppModel.checkDryNotifications` feeds it with `LatchMetrics.dry` after each `STATUS` reply and posts one notification for each rising tile (identifier `rlcd_panel_dry_<tile>`). The watch runs also while notifications are off, so a later switch-on does not replay a rise. `clearPanelData` resets it for a new panel.
- Tests: `DryWatchTests` (7 tests) cover the first mask, a rising bit once, an equal mask, a falling bit, a `nil` mask, a bit above tile 10 and the body text. They run in CI (the local host lacks TestingMacros). `swift build -c release` passes locally.

## Dry hours field in the STATUS SENSOR lines

- **Code:** `rlcd_panel/sparkline.h`, `rlcd_panel/serial_console.h`, `tests/test_firmware_logic.cpp`, `tests/fixtures/status_reply_sample.txt`
- Each `SENSOR` line of the console command `STATUS` now ends with ` dry_h=<N|-->` after `rh_trend=`. The field shows how many hourly samples of the 24-hour humidity history were too dry.
- `HumidityHistory::countAtOrBelow(limit_tenths)` counts the samples at or below `dry_alert_limit_rh` (0.1 %RH steps). A sample at the limit counts; a sample above it does not.
- The field shows `--` for `dry_alert_limit_rh` 0, for fewer than `HumidityHistory::MIN_RANGE_SAMPLES` samples and for an empty, disabled or stale slot.
- The firmware calculates the field on each `STATUS`. It adds no RTC state. The RTC layout and `STATE_VERSION` do not change. The static `STATUS` text buffer grows from 2560 to 3072 bytes for the longer lines. The Android and Wear OS `StatusReply` parsers read the field (see "Dry hours in the Android sensor health screen").
- Tests: host tests assert the count at the limit and above it, `dry_h=--` for limit 0, a stale slot, an empty slot and too few samples, and one full `SENSOR` line. `tests/fixtures/status_reply_sample.txt` holds the field (new `CRC32` 94168035).

## Dry hours in the Android sensor health screen

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/StatusReply.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/SensorHealth.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/SensorHealthScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/StatusReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/SensorHealth.kt`
- `StatusSensor.dryHours` (`Int?`) reads `dry_h=` of each `SENSOR` line. It gives `null` for `--` and for a missing field (older firmware). A bad number or a negative number throws `ParseException`. The Wear OS copy of `StatusReply.kt` and `SensorHealth.kt` stays equal to the phone copy (`scripts/check-protocol-sync.sh`), so the watch parses the field too but does not show it.
- `SensorHealth.dryHours` follows the slot. `SensorHealthText.dry` gives `Trocken <N> h` (`Trocken --` for `null`) with the prefix `⚠ ` above 0 hours. `SensorHealthScreen` shows the line after `Schimmel <N> h`, in the error colour above 0 hours (`SensorHealthText.isDry`).
- Tests: `StatusReplyTest.dryHoursOfFixtureAndBadFields` (fixture value 2, `dry_h=5`, `--`, missing field, `x` and `-1`), `SensorHealthTest.dryHoursFollowTheSlot` and `SensorHealthTextTest.dryTextAndWarning`. They run in CI (the local host has no Android SDK).

## Sensor argument for the console command TODAY

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `tests/fixtures/today_one_reply_sample.txt`
- The console command `TODAY <N>` (N 0 to 10, no leading zero, `SerialCommand::today_one`) sends the header and only the row of sensor N, then the `CRC32` line over the filtered bytes. The reply is short for one room over BLE.
- A sensor without a sample gets the header and no row. `TODAY` without an argument keeps its reply. `ERR NO_TIME` stays.
- `ERR BAD_ARG` for `TODAY 11`, `TODAY 01`, `TODAY x`, `TODAY 1 2` and other extra text. `TODAY 1` was a `BAD_ARG` before and now selects slot 1.
- The RTC layout and `STATE_VERSION` do not change. `showToday` takes the new parameter `only_sensor` (-1 sends every row).
- Tests: host tests cover the parser, the row of sensor 2 or 1 with a correct CRC-32 (and the new fixture), a sensor without a sample, `ERR NO_TIME`, and the unchanged full reply.

## Dry hours in the macOS sensor list

- The user sees the dry hours of each room beside the other sensor data. The macOS app reads the `dry_h=<N|-->` field of each `SENSOR` line of `STATUS`.
- `StatusSensor.dryHours` (`Int?`) is `nil` for `--` and for a missing field. A bad number or a negative number throws `ParseError.badLine`.
- The status list of the menu bar window shows ` Dry <N> h` after the state. It adds ` ⚠` above 0 hours.
- The `Sensor health` rows of `DiagnosticsView` show `Dry <N> h` and an orange warning symbol above 0 hours.
- Tests: `ReplyTests.dryHoursOfSharedFixtureAndBadFields` parses `tests/fixtures/status_reply_sample.txt`, `dry_h=5`, a missing field and `dry_h=x`.
- Validation: `swift build -c release` passes locally; `swift test` does not run locally (no TestingMacros in the Command Line Tools) and runs on GitHub Actions CI.

## Dry hours in the sensor stats view

- **Code:** `rlcd_panel/dashboard_view.h`, `tools/preview_scenes.h`, `tests/test_firmware_logic.cpp`
- Each sensor block of the stats view shows `DRY <N>H` in scale-1 text on the `MOLD` line (`y + 21`). The text ends `DRY_MOLD_GAP_PX` (6 px) left of the `MOLD <N>H` text.
- `DashboardView::formatDry` counts the hourly samples at or below `dry_alert_limit_rh` with `HumidityHistory::countAtOrBelow`. A sample at the limit counts.
- The key value 0 draws no text. An empty slot, a stale slot and a slot with fewer than 2 samples show `DRY --`.
- The text is drawn only when `DRY_GAP_PX` (4 px) stay free to the `MAC` line.
- The count is calculated for each render. No RTC state changes and `STATE_VERSION` stays 77.
- Thus the user sees how long a room was too dry in the last day.
- Host tests cover 5 samples at the limit, limit 0, a stale slot, an empty slot, fewer than 2 samples, and the text bounds to the `MAC` line and the `MOLD` text. `docs/display_preview_stats.png` and `docs/display_preview_stats2.png` show the text with a limit of 45 %RH.

## Dry hours in the Wear OS sensor health screen

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/SensorHealthTextTest.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/StatusReplyTest.kt`
- The user sees the dry hours of each room on the watch. `StatusSensor.dryHours` already holds the `dry_h=` field.
- `SensorHealthText.dry(sensor)` gives `Trocken <N> h`, with the `WARNING` mark before it above 0 hours, and `Trocken --` for `null`. `SensorHealthText.isDry(sensor)` is true above 0 hours. The names follow `mold` and `isMold`.
- `SensorHealthScreen` shows the line after `Schimmel <N> h`, in the warning colour above 0 hours.
- Tests: `SensorHealthTextTest.dryTextAndWarning` asserts `Trocken 0 h`, `⚠ Trocken 3 h` and `Trocken --`. `StatusReplyTest.dryHoursOfFixtureAndMissingField` parses `tests/fixtures/status_reply_sample.txt` (`dryHours` 2 for sensor 0) and a reply without the field. They run in CI (the local host has no Android SDK).

## Alert timeline in the Android event log

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/AlertTimeline.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/EventLogScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/AlertTimelineTest.kt`
- The user sees on the phone when each alert started and ended. `AlertTimeline.build(rows, nowMs)` ports the macOS `AlertTimeline.build`: each `ALERT_RISE` pairs with the next `ALERT_FALL` of the same alert bit.
- An alert without a fall stays open and ends at the injected `nowMs`. A fall without a rise starts at the first row with a valid time and has `startUnknown`. An `EVENTS_DROPPED` row marks each open interval `startUnknown`. A second rise of an open alert keeps the first start.
- `EventLogScreen` shows the section `Alarm-Verlauf` (newest first): alert name, start (`~` before a guessed start), end or `läuft`, and the duration (`AlertTimeline.lineText`, `durationText`). The panel time is read in the phone time zone.
- Tests: `AlertTimelineTest` covers one pair, an open interval, a fall without a rise, two alert bits, a repeated rise, a dropped row, bad times, the duration text and `tests/fixtures/events_reply_sample.txt`.
- Validation: `./scripts/check-protocol-sync.sh` exits 0. The host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs on GitHub Actions CI.

## Badge intervals in the Android event log

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/AlertTimeline.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/AlertTimeline.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/EventLogScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/AlertTimelineTest.kt`
- The user sees on the phone when each badge of a room started and ended. `AlertTimeline.badges(rows, nowMs, zone)` ports the macOS `AlertTimeline.badges`. It pairs each set row of `WINDOW_OPEN`, `WALL_DAMP`, `DRY_AIR` and `SENSOR_DRIFT` with the next clear row of the same badge and tile.
- A badge without a clear row stays open and ends at `nowMs`. A clear row without a set row starts at the first row with a valid time and has `startUnknown`. An `EVENTS_DROPPED` row marks each open interval as `startUnknown`. A row with an invalid detail is ignored.
- `EventLogScreen` shows the section `Badge-Verlauf` (newest first) below `Alarm-Verlauf`: badge letter, room (`Onboard` or `Slot <N>`), start (`~` before a guessed start), end or `läuft`, and the duration (`AlertTimeline.badgeLineText`).
- The watch protocol package holds the same file. Only the `package` line differs. `scripts/check-protocol-sync.sh` checks it.
- Tests: `AlertTimelineTest` covers one `DRY_AIR` pair, an open `WINDOW_OPEN` interval that ends at `nowMs`, a clear row without a set row, two tiles of one badge, and invalid details.
- Validation: `./scripts/check-protocol-sync.sh` exits 0. The host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs on GitHub Actions CI.

## macOS gap report for the newest log rows

- `DiagnosticsView` shows a gap report in the `Newest log rows` group after a `TAIL` read. It feeds `TailReply.rows` to `LogGaps.report(rows:)`.
- The report shows the count of missing hours and one line for each gap with its start and end time. It shows `No gaps` for a report without a gap.
- Tests: `TailReplyTests.gapReportOfFixtureRows` (rows of `tests/fixtures/tail_reply_sample.txt`: 1 gap, 666 missing hours), `TailReplyTests.threeHourStepOfTailRowsGivesOneGap` and `TailReplyTests.hourlyTailRowsGiveNoGap`.
- Validation: `swift build -c release` passes locally; `swift test` does not run locally (no TestingMacros in the Command Line Tools) and runs on GitHub Actions CI.

## Android gap report for the newest log rows

- `LogGaps.report(rows)` (`protocol/LogGaps.kt`, port of the macOS `LogGaps.swift`) finds missing hours in the `TAIL` rows. A step above 90 minutes between two neighbour rows gives one gap; the rows are sorted first and a row with an unreadable time stamp is skipped.
- The step is the smaller of the real (`Europe/Berlin`) and the wall-clock difference, so the repeated hour of the October change gives no false gap.
- `TailScreen` shows `Keine Lücken` or the count of missing hours and gaps, then one line for each gap with start, end and missing hours.
- Tests: `LogGapsTest` covers 90 minutes (no gap), 3 hours (1 gap, 2 missing hours), the October and March changes, bad time stamps, order, texts and `tests/fixtures/tail_reply_sample.txt` (1 gap, 666 missing hours).
- Validation: `./scripts/check-protocol-sync.sh` exits 0. The host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs on GitHub Actions CI.

## Hour window for the console command HISTORY

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
- The console command `HISTORY <N> <H>` (N 0 to 10, H 1 to 24, no leading zero) sends only the rows of sensor `N` with `age_h` below `H`. The reply has the same `BEGIN HISTORY` line and header as `HISTORY`; the `CRC32` line covers the filtered bytes. Thus a salt test reads 6 rows in a few BLE notifications.
- `SerialCommand::history_hours` holds H (0 without the argument). `SerialConsoleRunner::showHistory(sink, history, noise, only_sensor, max_hours)` takes 0 for all hours. `HISTORY` and `HISTORY <N>` keep their replies.
- `HISTORY <N> 0`, `HISTORY <N> 25`, `HISTORY <N> 06`, `HISTORY <N> 6 1`, a non-digit and extra text give `ERR BAD_ARG`. A window above the stored hours sends all rows.
- No card access, no state change, no RTC layout change, `STATE_VERSION` does not change. The command runs on USB and on BLE at each `ble_console` level.
- Host tests cover the parser, the 6 newest rows of sensor 2, the CRC-32 line, the unchanged `HISTORY 2` reply and the rejected arguments.

## Short HISTORY reads for the macOS salt test watch

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/SaltProgress.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/SaltProgressTests.swift`
- `ConsoleCommand.historySensorHours(N, H)` sends `HISTORY <N> <H>` (N 0 to 10, H 1 to 24; no line for another value). It needs the firmware hour window.
- The salt test watch sends `HISTORY <N> 6` every 10 minutes (`SaltProgress.historyCommand(sensor:)`). `SaltProgress.requiredHours` is 6, so 6 rows give the full count and the BLE reply is shorter.
- After `ERR BAD_ARG` for the short read (older firmware), `SaltProgress.fallBackToFullHistory()` switches once to `HISTORY <N>`, `AppModel` repeats the tick at once and the watch goes on. A new watch tries the short read again. Another error still stops the watch with `replyFailed`.
- Unit tests cover the lines `HISTORY 4 6`, sensor 11, hours 0 and hours 25 without a line, `Stable 6/6 h` for 6 stable rows and the fallback to `HISTORY 4`.

## Short HISTORY reads for the Android salt test watch

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/HistoryCommand.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/SaltProgress.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/CalibrationScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/HistoryCommandTest.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/SaltProgressTest.kt`
- `HistoryCommand.lineHours(sensor, hours)` gives the line `HISTORY <N> <H>`. It throws `IllegalArgumentException` for a sensor outside 0 to 10 and for hours outside 1 to 24. The command needs the firmware hour window.
- The salt test watch sends `HISTORY <N> 6` every 10 minutes (`SaltProgress.historyLine`). `SaltProgress.REQUIRED_HOURS` is 6, so 6 rows give the full count and the BLE reply is shorter.
- After `ERR BAD_ARG` for the short read (older firmware), `SaltProgress.fallBackToFullHistory` switches once to `HISTORY <N>`. `MainActivity` repeats the tick at once and the watch goes on. A new watch tries the short read again. Another error still stops the watch with `REPLY_FAILED`.
- Unit tests cover the line `HISTORY 4 6`, sensor 11, hours 0 and hours 25 with `IllegalArgumentException`, the full count for 6 stable rows and the fallback decision for `ERR BAD_ARG`.

## Alert timeline in the Wear OS event log

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/AlertTimeline.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/EventLogScreen.kt`, `scripts/check-protocol-sync.sh`
- `AlertTimeline.build(rows, nowMs)` is a copy of the Android `AlertTimeline.kt`. It pairs each `ALERT_RISE` row with the next `ALERT_FALL` row of the same alert bit.
- A rise without a fall ends at `now` and is open. A fall without a rise gives `startUnknown`.
- `EventLogScreen` shows the intervals at the top of the list, newest first, at most 8 (`EventLogState.MAX_INTERVALS`). Each line shows the alert name, the start, the end or `läuft` and the duration. A guessed start has a leading `~`.
- The first read holds only the newest events. The screen then shows the hint `Nur die letzten Ereignisse` (only for 8 or more rows) and a start of an older alert shows with `~`. `Show all` loads each event.
- `scripts/check-protocol-sync.sh` compares `AlertTimeline.kt` with the phone copy.
- Tests: `AlertTimelineTest` covers one pair, an open rise, a fall without a rise, a leading `EVENTS_DROPPED` row, the text and `tests/fixtures/events_reply_sample.txt`.
- Validation: `./scripts/check-protocol-sync.sh` and `--self-test` exit 0. The host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs on GitHub Actions CI.

## Config key dry_alert_delay_h for the dry air badge

- **Code:** `rlcd_panel/device_config.h`, `rlcd_panel/dry_latch.h`, `rlcd_panel/sparkline.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `docs/config.example.ini`, `macos/RlcdCompanion/Sources/RlcdProtocol/ConfigSchema.swift`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ConfigSchema.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/ConfigSchema.kt`
- The new `config.ini` key `dry_alert_delay_h` stops a short dry spell from setting the `D` badge. The value 0 is off (default). Else the value is 1 to 12 h. Another value gives a rejected line.
- With a delay of N hours, `DryLatch::update` sets the bit of a tile only when the live reading and the newest N hourly samples of the tile are all at or below `dry_alert_limit_rh`. Fewer than N samples give no set. A delay without a history gives no set.
- `HumidityHistory::newestAtOrBelow(n, limit_tenths)` checks the newest N samples. The limit itself counts as dry.
- The hold and release rules do not change. A bit that is set holds up to the limit plus 3 %RH. The delay never clears a set bit.
- The firmware stores the key as 1 byte at the end of the config trailer, after `reception_low_pct`. `KEY_COUNT` is 99. `STATE_VERSION` is 78.
- The three `ConfigSchema` files (macOS, Android, Wear OS) hold the new key. The Android and Wear OS `ConfigBackup` and `EventText` lists hold it too.
- Host tests cover the default 0, the values 1 and 12, the rejected values 13, -1 and `x`, the RTC round trip of the trailer byte, a delay of 3 with 2 and with 3 dry samples, and the unchanged rules for delay 0.

## Dry air delay in the Android alert limits screen

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/AlertLimits.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/AlertLimitsTest.kt`.
- `AlertLimits.LIMITS` holds the new `Limit` for `dry_alert_delay_h`. The value is a whole number of hours from 0 to 12. The value 0 is off.
- The label is `Verzögerung Trockenalarm (h)`. `AlertLimitScreen` shows the field below `dry_alert_limit_rh`.
- The `SET` batch and the stop rules equal those of `mold_alert_delay_h`. The first `ERR` line stops the batch before `CONFIG`.
- Unit tests check the valid values 0, 1 and 12, the invalid values 13, -1 and 1.5, the line `SET dry_alert_delay_h=4` and the range against the `ConfigSchema` entry.

## Dry hours in the week chart

- **Code:** `rlcd_panel/sparkline.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`, `docs/display_preview_chart_week.png`
- The week chart now shows how many hours of the week a room was too dry. The humidity label line shows `DRY <N>H` after the `MOLD <N>H` text, separated by two spaces.
- `WeekHistory` stores one dry hour byte for each day and sensor, after the mold bytes. `WeekHistory::advance` counts the hours with `HumidityHistory::countAtOrBelow` and the key `dry_alert_limit_rh`. A sample at the limit counts as dry.
- A gap day and the limit 0 store `0xFF`. The shift, `SLOT SWAP` and `SLOT CLEAR` move, swap and clear the dry bytes with the mold bytes.
- `DashboardView::formatWeekDry` adds the hours of the days with a value (0 to 168 h). It gives an empty text when no day has a value.
- The RTC restore rejects a value above 24 and a value beside a gap humidity pair. The restore from the `daily_v5` file leaves the dry hours as gaps, because the file has no dry column.
- Each day keeps the count of the limit at the time of its write. After a change of `dry_alert_limit_rh`, the sum mixes limits for up to 7 days.
- The `WEEK` reply and the daily file do not change. The week block grows from 390 to 467 bytes. `STATE_VERSION` is 79.
- Host tests cover 5 dry hours for 5 dry samples, `0xFF` for limit 0 and for a gap day, the RTC restore checks for 25 and for a gap humidity pair, the text `DRY 12H`, the rendered label line, and the swap of the dry bytes by `SLOT SWAP`.

## Badge intervals in the macOS alert timeline

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/AlertTimeline.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/BadgeIntervalTests.swift`
- `AlertTimeline.badges(rows:now:)` pairs each set row of `WINDOW_OPEN`, `WALL_DAMP`, `DRY_AIR` and `SENSOR_DRIFT` with the next clear row of the same badge and tile. The low byte of the detail is the tile (0 to 10). The high byte is the state (1 set, 0 clear). A row with another value is ignored.
- A `BadgeInterval` holds the event, the tile, the start, the end, `isOpen` and `startUnknown`. A badge without a clear row stays open and ends at the injected `now`. A clear row without a set row gives `startUnknown`. An `EVENTS_DROPPED` row gives `startUnknown` to each interval that is open at that row. Two tiles of one badge give two intervals. `AlertTimeline.build` and `AlertTimeline.badges` use one pairing helper. The tile and state rules are in `EventText.tileState`.
- A clear row without a set row takes the first valid row time as start. This start has no link to the badge, so the line shows it with `~`.
- `DiagnosticsView` shows the group `Badge intervals` below the alert timeline. Each line shows the badge letter (`W`, `C`, `D`, `S`), the room name, the start, the end (`now` for an open interval) and the duration. A `~` before the start marks an unknown start. The newest interval is first.
- Tests cover one `DRY_AIR` pair, two cycles of one tile, bad times, tile 10, the order of equal starts, an open `WINDOW_OPEN`, a clear row without a set row, two tiles of one badge, the same tile of two badges, invalid details, the dropped row, the line text, the `EventText` fallbacks and `tests/fixtures/events_reply_sample.txt`.
- Validation: `swift test` (758 tests) and `swift build -c release` pass in `macos/RlcdCompanion`.

## Log tail and gap report on the Wear OS watch

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/TailReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/LogGaps.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/TailScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `scripts/check-protocol-sync.sh`
- The watch shows if the log on the MicroSD card of the panel has missing hours. `TailReply.kt` and `LogGaps.kt` are copies of the Android files.
- The screen opens from the sensor health screen with the chip `Log tail and gaps`. A tap on `Read log tail` sends `TAIL 6`. The watch sends no command before this tap.
- `TailScreen` shows the summary of `LogGaps.summaryText`: `Keine Lücken` for a report without a gap, else the missing hours and the gap count. One line for each gap follows, newest gap first.
- `TailScreenText.coverageText` shows `Letzte <N> Zeilen`, because the report covers only these rows. `TailScreenText.roomLines` gives one card for each room. The card shows the room name, the time and the temperature and humidity of the newest row that holds a value of this room. A room without any value has no card.
- An error line (`ERR NO_CARD`, `ERR NO_FILE`, `ERR READ_FAILED`) shows a German text. A reply that does not parse shows `Antwort nicht lesbar`.
- `scripts/check-protocol-sync.sh` compares both files with the phone copies.
- Tests: `TailReplyTest` parses `tests/fixtures/tail_reply_sample.txt`. `LogGapsTest` checks that a step of 3 hours gives one gap with 2 missing hours and that hourly rows give no gap. `TailScreenTextTest` covers the room lines and the error texts.
- Validation: `./scripts/check-protocol-sync.sh` and `--self-test` exit 0. The host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs on GitHub Actions CI.

## Room picker for the macOS today view

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/TodayReplyTests.swift`
- `ConsoleCommand.todaySensor(N)` sends `TODAY <N>` for N 0 to 10. Another number gives no line. The short reply holds only the row of one room and saves BLE bytes.
- The group `Today` of `DiagnosticsView` has a picker `Room` with `All` and the 11 tile names (`config.ini` names, else `Onboard` and `Slot <N>`). `All` sends `TODAY`. A room sends `TODAY <N>`. The command runs only after the button `Read today`.
- An older firmware answers `ERR BAD_ARG` for `TODAY <N>`. `TodayReply.retryAfterError` then gives `TODAY`. `AppModel` sends it once and shows `Older firmware: reading all rooms`. `TodayReply.keepOnly` keeps only the row of the selected room.
- A room without a sample in the fallback reply gives an empty list and the text `No sensor holds a sample.`
- Tests cover the lines `TODAY 3` and `TODAY`, the numbers 11 and -1, the fallback decision for `ERR BAD_ARG`, `keepOnly` and `tests/fixtures/today_one_reply_sample.txt`.
- Validation: `swift test` (764 tests) and `swift build -c release` pass in `macos/RlcdCompanion`.
- The shared fixture `tests/fixtures/badge_events_sample.txt` is a full `EVENTS` reply with a correct `CRC32` line. It holds 13 rows with one set row and one clear row of `WINDOW_OPEN`, `WALL_DAMP`, `DRY_AIR` and `SENSOR_DRIFT`. The first row is an `SD_FAULT` row. It gives the start of the clear row without a set row.
- The fixture also holds one open interval (`SENSOR_DRIFT`, tile 5), one clear row without a set row (`SENSOR_DRIFT`, tile 0) and two tiles of one badge (`DRY_AIR`, tiles 3 and 4). The file `events_reply_sample.txt` does not change.
- `BadgeIntervalTests` read the fixture through `ReplyAssembler`. The tests check the `CRC32` line, the 7 intervals with each tile and each `startUnknown` flag, and the end of the open interval at the injected `now`.
- Validation: `swift test` does not run on the local host, because the Command Line Tools lack the Swift Testing macros. The CI job `macOS companion tests` runs it.

## Room picker for the Android today screen

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/TodayCommand.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/TodayScreen.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/MainActivity.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/TodayCommandTest.kt`, `scripts/check-protocol-sync.sh`
- `TodayCommand.kt` is a copy of the watch file. `TodayCommand.line(N)` gives `TODAY <N>` for N 0 to 10 and `TODAY` for no room. Another number throws `IllegalArgumentException`. The short reply holds only the row of one room and saves BLE bytes.
- The today screen has a chip row with `Alle` and the 11 tile names (`PanelConfig.tileNames`). `Alle` sends `TODAY`. A room sends `TODAY <N>`. The command runs only after the button `Read today`.
- An older firmware answers `ERR BAD_ARG` for `TODAY <N>`. `TodayCommand.needsFallback` then returns true. The app sends `TODAY` once. `TodayCommand.select` keeps only the row of the selected room.
- A read with a room and no row shows `Keine Daten für den Raum`. The selected room stays after an error, a cancel and a permission denial.
- `scripts/check-protocol-sync.sh` compares `TodayCommand.kt` with the watch copy.
- Tests: `TodayCommandTest` covers the lines `TODAY 3` and `TODAY`, the numbers 11 and -1, the fallback decision, `select` and `tests/fixtures/today_one_reply_sample.txt`.
- Validation: `./scripts/check-protocol-sync.sh` and `--self-test` exit 0. The host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs on GitHub Actions CI.

## Hour window picker for the Wear OS wall factor screen

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/FrsiReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/FrsiApply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/FrsiScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/FrsiReplyTest.kt`, `scripts/check-protocol-sync.sh`
- The user selects the sample window of the wall factor check on the watch. The chip `Fenster` of `FrsiScreen` changes the window in the order 3 h, 6 h, 12 h, 24 h and then 3 h again. The start value is 6 h.
- `FrsiReply.kt` is now a copy of the Android file. It holds `FrsiCommand.DEFAULT_HOURS` (6), `MIN_HOURS` (3), `MAX_HOURS` (24) and `HOUR_CHOICES`. `FrsiCommand.line(room, wall, hours)` gives `FRSI <R> <W>` for 6 hours and `FRSI <R> <W> <H>` for each other window. A number below 3 or above 24 throws `IllegalArgumentException` and sends no line.
- `FrsiReply.kt` is in the `FILES` list of `scripts/check-protocol-sync.sh`. The watch-only parts `FrsiApply` and `FrsiApply.parseBytes` moved to `FrsiApply.kt`, because the sync check needs equal files.
- `FrsiText.outcome(reply, hours)` shows the needed hours in the `SKIP` text. `FrsiText.status(line, hours)` adds a firmware update hint to `ERR BAD_ARG` for a window other than 6 h, because an older firmware does not know the argument.
- A change of the window clears the last reply and the confirmation. The `Senden` chip cannot apply a reply of another window.
- Tests: `FrsiScreenTextTest` checks the picker order. `FrsiReplyTest` checks the lines `FRSI 0 2` and `FRSI 0 2 12`, the hours 2 and 25 (no line), the `SKIP` and `ERR BAD_ARG` texts and `tests/fixtures/frsi_reply_sample.txt`.
- Validation: `scripts/check-protocol-sync.sh` and `scripts/check-protocol-sync.sh --self-test` exit 0. The host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs on GitHub Actions CI.


## Config keys for the jump guard limits

- **Code:** `rlcd_panel/device_config.h`, `rlcd_panel/switchbot_ble.h`, `rlcd_panel/power_manager.h`, `docs/config.example.ini`, `macos/RlcdCompanion/Sources/RlcdProtocol/ConfigSchema.swift`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ConfigSchema.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/ConfigSchema.kt`
- The user can set the limits of the jump guard. This helps for a sensor in the sun or near a heater, because the reading of such a sensor changes fast.
- `jump_limit_c` sets the temperature limit. The value 0 is off. Else the range is 3.0 to 20.0 C in 0.5 C steps. The default is 8.0 C. A value such as 2.5 or 3.2 gives a rejected line.
- `jump_limit_rh` sets the humidity limit. The value 0 is off. Else the range is 10 to 50 %RH. The default is 25 %RH.
- A limit of 0 turns only its own check off. When both keys are 0, the jump guard is off and no packet stays pending.
- `JumpGuard::isJump` takes both limits. `SwitchBotRegistry::setJumpLimits` stores them. `PersistentState::applyConfig` and the RTC restore pass them to the registry. The defaults give the old behavior.
- The RTC state holds two bytes at the end of the config trailer: the temperature limit in tenths of a degree and the humidity limit. The RTC restore rejects a byte outside the range. `KEY_COUNT` is 101 and `STATE_VERSION` is 80.
- The three `ConfigSchema` copies, the Android and Wear OS `ConfigBackup` and `EventText` lists, and `docs/config.example.ini` hold both keys.
- Tests: host tests check that a jump of 9.0 C passes at once with `jump_limit_c=10.0`, that a jump of 4.0 C stays pending with `jump_limit_c=3.0`, and that both keys 0 give no pending packet. They reject `jump_limit_c=2.5`, `jump_limit_c=3.2` and `jump_limit_rh=51`, and a trailer byte outside the range in the RTC restore. `testConfigExampleTemplateInSync` passes. The schema tests of the three apps cover both keys.

## Badge intervals in the Wear OS event log

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/EventLogScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/AlertTimelineTest.kt`
- The user sees on the watch when each badge of a room started and ended. The event log screen shows the section `Badge-Zeitleiste` below the alert intervals.
- The section uses `AlertTimeline.badges`. It shows one chip for each interval, newest first, up to 6 chips (`EventLogState.MAX_BADGE_CHIPS`). Badges are `W` (window open), `C` (wall damp), `D` (dry air) and `S` (sensor drift).
- `BadgeChips.text` gives two lines. The label line holds the badge letter and the room, for example `C Slot 3`. The second line holds the start and the duration, for example `01.01. 08:00 · 45 min`.
- An open interval shows `offen` in place of the duration. An interval with `startUnknown` shows `?` in place of the start.
- `AlertTimeline.kt` stays equal to the Android copy. The chip text is a watch-only part in `EventLogScreen.kt`.
- Tests: `AlertTimelineTest` checks the chip text of a closed `WALL_DAMP` interval, `offen` for an open `DRY_AIR` interval and `?` for an interval with `startUnknown`.
- Validation: `scripts/check-protocol-sync.sh` and `scripts/check-protocol-sync.sh --self-test` exit 0. The host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs on GitHub Actions CI.

## Dry hours column in the WEEK reply

- **Code:** `rlcd_panel/serial_console.h`, `tests/test_firmware_logic.cpp`, `tests/fixtures/week_reply_sample.txt`, `macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/CsvExport.swift`, `android/RlcdPhone/.../protocol/WeekReply.kt`, `android/RlcdPhone/.../protocol/WeekCsv.kt`, `wearos/RlcdWatch/.../protocol/WeekReply.kt`, `AGENTS.md`.
- The panel sends the stored dry hours of each day to the companion apps. The `WEEK` and `WEEK <N>` replies have the new last column `dry_h` after `mold_h`.
- `SerialConsoleRunner::showWeek` reads `WeekHistory::dryHours`. The field is empty without `hasDryHours`. A gap day and the limit 0 of `dry_alert_limit_rh` give an empty field. The `CRC32` line covers the new column.
- The macOS and Kotlin `WeekReply.parse` accept 7, 8 or 9 columns. The header selects the format: `WeekReply.header` has 9 columns, `moldHeader` (`MOLD_HEADER` in Kotlin) has 8 and `legacyHeader` has 7. `WeekRow.dryHours` is `nil` or `null` for 7 or 8 columns and for an empty field. A value outside 0 to 24, a sign or a non-digit is a bad line.
- `CsvExport.week` and the Android `WeekCsv` write the new column.
- Tests: host tests check the field `5` for a day with 5 dry hours, an empty field for a gap day and for limit 0, and the `CRC32` line of `WEEK 2`. A unit test in each app parses `tests/fixtures/week_reply_sample.txt` with the new column. A unit test in each app parses a 7-column and an 8-column reply with `dryHours` empty.

## Jump guard advice in the Android sensor health screen

- **Code:** `android/RlcdPhone/.../protocol/JumpAdvice.kt`, `android/RlcdPhone/.../SensorHealthScreen.kt`, `android/RlcdPhone/.../MainActivity.kt`, `android/RlcdPhone/.../protocol/JumpAdviceTest.kt`.
- The phone tells the user when the jump guard drops many good readings of one sensor. The advice appears in the sensor health screen.
- `JumpAdvice.evaluate(rows, config, nowMs)` counts the `JUMP_DROP` rows of each slot in the newest 24 hours of the `EVENTS` reply. A slot with 3 or more drops gives one advice. The detail of the row is the slot number 1 to 10. A row without a valid time does not count.
- The proposal is `jump_limit_c=<active value + 2.0>`, clamped to 20.0. The active value comes from the cached `CONFIG`. Without a cached value, no advice appears.
- The panel event ring holds 16 events, and the hourly card write clears it. Only drops since the last write count, so the 24-hour window is an upper bound.
- No advice appears for `jump_limit_c=0`, for a value that is not a number and for a limit that already is 20.0.
- After each good `STATUS` read, the screen sends `EVENTS` and shows one card for each advice with the room name and the button `Anwenden`.
- The app sends `SET jump_limit_c=<value>` and then `CONFIG` only after the tap. The first `ERR` stops the batch. The button is off without `ble_console` 2. The card shows `Set ble_console=2 to apply`.
- A failed `EVENTS` read gives no advice and no error text.
- Tests: unit tests check one advice for 3 drops of slot 4, no advice for 2 drops and for drops older than 24 hours, the proposal and the clamp at 20.0, no advice for `jump_limit_c=0`, the 24-hour boundary, slot details 0 and 11, a missing limit, and no advice for `tests/fixtures/events_reply_sample.txt`.
- Validation: `scripts/check-protocol-sync.sh` and `scripts/check-protocol-sync.sh --self-test` exit 0. The host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs on GitHub Actions CI.


## Jump guard advice on the Wear OS watch

- **Code:** `wearos/RlcdWatch/.../protocol/JumpAdvice.kt`, `wearos/RlcdWatch/.../SensorHealthScreen.kt`, `wearos/RlcdWatch/.../MainActivity.kt`, `wearos/RlcdWatch/.../protocol/JumpAdviceTest.kt`, `scripts/check-protocol-sync.sh`.
- The watch tells the user when the jump guard drops the readings of one slot. `JumpAdvice` is a port of the Android class with the same rules: key `jump_limit_c`, event `JUMP_DROP`, `MIN_DROPS` 3, `WINDOW_MS` 24 h, `STEP_C` 2.0 and `MAX_C` 20.0. The file is in the shared list of `scripts/check-protocol-sync.sh`.
- `JumpAdvice.evaluate(rows, config, nowMs)` gives one advice for each slot with 3 or more `JUMP_DROP` rows of the `EVENTS` reply in the newest 24 hours, in slot order. The proposal is `jump_limit_c=<active value + 2.0>`, clamped to 20.0.
- No advice appears for `jump_limit_c=0`, for a limit at 20.0 C, for a missing or unreadable `jump_limit_c` in the cached `PanelConfig`, for a row without a valid time and for a detail outside slot 1 to 10.
- After each good `STATUS` read, the sensor health screen sends `EVENTS` and shows one short line for each advice (`advice.text(room)`) and the chip `Anwenden`. The chip is off without `ble_console` 2 and the screen shows `Set ble_console=2 to apply`.
- The watch sends `SET jump_limit_c=<value>` and then `CONFIG` only after the tap. The first `ERR` stops the batch. The watch sends no automatic command.
- Tests: unit tests check one advice for 3 drops, none for 2 drops, none for `jump_limit_c=0` and `20.0`, none without a cached limit, an empty time field and detail 11, the proposal `10.0` for a limit of 8.0, and the line text.
- Validation: `scripts/check-protocol-sync.sh` and `--self-test` exit 0. The host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs on GitHub Actions CI.

## Config keys for the drift check limits

- **Code:** `rlcd_panel/device_config.h`, `rlcd_panel/drift_latch.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `docs/config.example.ini`, `macos/RlcdCompanion/Sources/RlcdProtocol/ConfigSchema.swift`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ConfigSchema.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ConfigBackup.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/ConfigSchema.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/ConfigBackup.kt`
- The user can set the limits of the `SPREAD` check and of the `S` drift badge. This helps for sensors of a lower class, because their readings differ more from the median.
- `drift_limit_c` sets the temperature limit. The range is 0.3 to 2.0 C in 0.1 C steps. The default is 0.5 C. There is no off value. A value such as 0.2, 2.1 or 0.55 gives a rejected line.
- `drift_limit_rh` sets the humidity limit. The range is 2.0 to 10.0 %RH in 0.5 %RH steps. The default is 3.0 %RH. There is no off value. A value such as 2.3 gives a rejected line.
- A sensor is an `OUTLIER` in `SPREAD`, and its `S` badge sets, when its deviation is above one of the two limits. `SpreadMath::Limits` holds both limits in tenths. `SpreadMath::isOutlier` and `SpreadMath::isSettled` take it.
- The badge releases when both deviations are at or below the limits minus 0.2 C and minus 1.0 %RH. The defaults keep the old release limits 0.3 C and 2.0 %RH. Between the two limits the badge keeps its state.
- The RTC state holds two bytes at the end of the config trailer, after `jump_limit_rh`: the temperature limit in tenths of a degree (3 to 20) and the humidity limit in tenths of a percent (20 to 100 in steps of 5). The RTC restore rejects any other byte. `KEY_COUNT` is 103 and `STATE_VERSION` is 81.
- The three `ConfigSchema` copies and the two Kotlin `ConfigBackup` copies list both keys. The Kotlin `EventText` shows both names for `CONFIG_SET`.
- Tests: host tests check that a deviation of 0.8 C gives `OK` with `drift_limit_c=1.0` and `OUTLIER` with the default. They check that a deviation of 4.0 %RH sets the drift bit with `drift_limit_rh=3.5` and that 2.5 %RH releases it. They check that the defaults give the old set and release results. They reject `drift_limit_c=0.2`, `drift_limit_c=2.1` and `drift_limit_rh=2.3`, and a trailer byte outside the range in the RTC restore. `testConfigExampleTemplateInSync` passes. The schema tests of the three apps cover both keys.

## Jump guard advice in the macOS diagnostics view

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/JumpAdvice.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/JumpAdviceTests.swift`
- The macOS app tells the user when the jump guard drops many good readings of one sensor. A sensor in the sun or near a heater causes this.
- `JumpAdvice.evaluate(events:config:now:)` counts the `JUMP_DROP` rows of each slot in the 24 hours before `now`. It gives one advice for each slot with 3 or more drops. A row with a bad time or a bad slot does not count.
- The proposal is `jump_limit_c` plus 2.0, with a maximum of 20.0. There is no advice without a config, for `jump_limit_c=0` (check off) and for `jump_limit_c=20.0`. A config without the key uses the default 8.0.
- Drops at or before the newest `CONFIG_SET` row of `jump_limit_c` do not count, so a second `Apply` does not stack on old drops. The app hides the advice after a batch that changed `jump_limit_c` until the next `EVENTS` read.
- The panel event ring holds 16 events, and the hourly card write clears it. Only drops since the last write count, so the 24-hour window is an upper bound.
- `DiagnosticsView` shows the advice with the slot name in the group `Jump guard advice`. The `Apply` button sends the proposal through `ConfigBatch` (`AppModel.applyJumpAdvice`). The app sends no command without a user action.
- Tests: `JumpAdviceTests` check one advice for 3 drops of slot 2, no advice for 2 drops and for drops older than 24 hours, the proposal `10.0` from 8.0, the clamp at 20.0 and no advice for `jump_limit_c=0`.

## Two-point salt calibration on the Wear OS watch

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/TwoPointCalibration.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SaltScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `scripts/check-protocol-sync.sh`.
- The user calibrates the humidity gain and offset of one sensor on the watch. The salt screen has a `NaCl` chip and a `MgCl2` chip. The `NaCl` chip sends `SALT N`. The `MgCl2` chip sends `SALT N MGCL2`. Both commands come from the shared `SaltCommand`.
- `TwoPointCalibration.kt` is a copy of the phone file. `scripts/check-protocol-sync.sh` compares both files. The watch keeps the last `NaCl` result and the last `MgCl2` result of the room. A failed read removes only the result of its own salt.
- After both replies, `TwoPointCalibration.evaluate` calculates the result from the replies and the cached `CONFIG` values. The screen shows `Gain <x>, Offset <y>` and the `Übernehmen` chip. With only one reply the screen shows no result and no error.
- A gain outside 0.90 to 1.10 gives the error text `Gain <x> is outside 0.90 to 1.10, no SET sent`. The screen shows no `Übernehmen` chip, so the watch sends no `SET`. The other failures of `TwoPointCalibration.Failure` show their text in the same way.
- The `Übernehmen` chip runs `CalibrationBatch`: `SET rh_gain=<x>` or `SET slotN_rh_gain=<x>`, then the offset key, then `CONFIG`. The first `ERR` stops the batch. `ERR READ_ONLY` shows the hint `Set ble_console=2 to apply`. No command is repeated.
- After a `SET` has run (also after a reply that is not valid), the watch removes both salt results and reads `CONFIG` again, because the raw means depend on the active gain and offset.
- Tests: `TwoPointCalibrationTest` calculates gain and offset from one `NaCl` reply and one `MgCl2` reply, checks the `SET` lines for slot 3, checks that a gain of 1.15 gives the error text and no result, and checks that one reply gives no result. `SaltScreenTextTest` checks the result text, the salt results of `SaltState` and the apply text.

## RTC trim action on the Wear OS watch

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/RtcTrimReply.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/RtcTrimReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/SensorHealthScreen.kt`, `scripts/check-protocol-sync.sh`
- The user corrects a fast or slow panel clock from the watch. The sensor health screen shows the `RTC trimmen` chip while `RtcState.canTrim` is true (drift of 3 ppm or more) and `ble_console` is 2.
- A tap sends `RTC TRIM`. After an `OK` reply the watch sends `STATUS` and shows the new trim value. The watch does not repeat a failed command.
- `RtcTrimReply.kt` holds the reply text. `RtcTrimReply.parse` gives the old and the new trim value of `OK RTC TRIM <old> <new>`. A bad line, a bad number or a value outside -64 to 63 gives no value. `RtcTrimReply.text` gives `RTC getrimmt: <old> → <new>`, the text of `ERR NO_DRIFT` and `ERR READ_ONLY`, or `Panel meldet: <line>` (for example `ERR WRITE_FAILED`).
- The phone `SensorHealthText.trimResult` uses the same function. `RtcTrimReply.kt` is in the `FILES` list of `scripts/check-protocol-sync.sh`, so both copies stay equal.
- Tests: `RtcTrimReplyTest` parses `OK RTC TRIM 2 -1`, checks the error texts and checks that a bad line gives no value. The test also checks the rule of the chip.

## Dry hours columns in the daily log (daily_v6)

- **Code:** `rlcd_panel/sd_logger.h` (`SdLogCodec::formatDailyRow`, `formatDailyHeader`, `formatDailyFilename`, `parseDailyRow`, `SdLogger::restoreWeek`), `rlcd_panel/sparkline.h` (`WeekHistory::RestoreRow::dry_h`), `rlcd_panel/serial_console.h` (`showDays`), `scripts/dump-logs.sh`, `macos/RlcdCompanion/Sources/RlcdProtocol/LogArchive.swift`
- The daily log keeps the dry hours of each day. The firmware writes the daily row to `/sdcard/daily_v6_YYYY.csv`. The row has 94 columns: the 83 columns of `daily_v5` and then `dry_h_0` to `dry_h_10`.
- Each `dry_h_<N>` value is `HumidityHistory::countAtOrBelow` with `dry_alert_limit_rh`. The field is empty for limit 0 and for a sensor without samples.
- The firmware does not change the `daily_v5_YYYY.csv` files. `LIST`, `DUMP`, the log retention, `SdLogCodec::parseDailyFileName` and `scripts/dump-logs.sh` accept `daily_v6` names. The macOS `LogArchive` accepts them too.
- `SdLogCodec::parseDailyRow` accepts 94 columns and 83 columns. A `daily_v5` row leaves the dry hours as gaps. A count above 24 or a bad character in a `dry_h` field rejects the row.
- `WeekHistory::RestoreRow` has the field `dry_h`. After a power-on `SdLogger::restoreWeek` reads the `daily_v5` file and then the `daily_v6` file of the current year (and of the previous year in the first week of January). A later row of a day wins, so the dry hours fill the week history.
- The console command `DAYS <N>` sends the header of `daily_v6`. It reads the sources from the newest to the oldest: `daily_v6_YYYY.csv` and `daily_v5_YYYY.csv` of the current year, then `daily_v6` and `daily_v5` of the previous year. It reads the next source only while it has fewer than N rows and the window of the last file reaches its start. A missing file is skipped. Each `daily_v5` row gets 11 empty fields. A read error of any file gives `ERR READ_FAILED`.
- The macOS, Android and Wear OS apps read the `daily_v6` header of `DAYS`. The fixture `tests/fixtures/days_reply_sample.txt` keeps the `daily_v5` format for the app parsers. The fixture `tests/fixtures/days_reply_v6_sample.txt` holds the `daily_v6` reply. The macOS `DailyLog` reads `daily_v6` files (see the section "Dry hours in the macOS daily charts").
- Tests: host tests check the 94-column header, a row with 5 dry hours, empty dry fields for limit 0, the restore of 3 days from `daily_v6` rows, the restore of a `daily_v5` row with dry gaps, and the `DAYS 2` reply with the `daily_v6` header and its `CRC32` line. `scripts/dump-logs.sh --self-test` accepts `daily_v6_2026.csv`.

## Dry hours in the Android days screen

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/DaysReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/DaysReply.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/DaysScreen.kt`, `DaysReplyTest.kt`, `DaysMoldCardsTest.kt`.
- `DaysReply.parse` accepts the `daily_v6` header (`DaysReply.HEADER`, 94 columns, `COLUMNS`) and the `daily_v5` header (`DaysReply.HEADER_V5`, 83 columns, `COLUMNS_V5`). The header selects the column count. A row with another column count throws `ParseException`, for example a row with 93 columns under the `daily_v6` header.
- `DaysSensor.dryHours` (`Int?`) holds the columns `dry_h_0` to `dry_h_10` (hours at or below `dry_alert_limit_rh`, 0 to 24). An empty field gives `null`. A `daily_v5` reply gives `null` for each sensor. A value above 24 or a bad character throws `ParseException`. `DaysSensor.isEmpty` is false when a dry value exists.
- `DaysReply.csv` has the new last column `dry_h`.
- The phone file `DaysReply.kt` is copied into the watch protocol package; only the `package` line differs. `scripts/check-protocol-sync.sh` compares both files.
- `DaysMoldCards.dryLine(rows, sensor)` gives `Trocken <N> h` (the sum of the dry hours of the room over the rows), or `null` without a dry value. `DaysScreen` shows the line under the mold lines of each room card. A room without a mold value has no card, so it shows no dry line.
- Tests: `DaysReplyTest` (phone and watch) covers a row with dry hours, an empty field, a `daily_v5` reply, a row with 93 columns, bad dry values, the CSV column and `tests/fixtures/days_reply_v6_sample.txt` with its CRC-32. `DaysMoldCardsTest` covers `dryLine`.
- Validation: `./scripts/check-protocol-sync.sh` and `--self-test` pass. The local host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs in CI only.

## Dry hours in the macOS daily charts

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/DailyLog.swift`, `macos/RlcdCompanion/Sources/RlcdProtocol/DaysReply.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/DailyChartView.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/DailyLogTests.swift`, `macos/RlcdCompanion/Tests/RlcdProtocolTests/DaysReplyTests.swift`
- `DailyLog.parse(text:)` reads `daily_v6` (94 columns, 11 sensors). A row with 93 columns throws `ParseError.badLine`. `DailyLog.isDailyFileName` and `DailyLog.load(from:)` accept `daily_v6_YYYY.csv`. A row of a newer format replaces a row of the same date.
- `DailyRow.dryHours` holds one `Int?` for each sensor. An empty field gives `nil`. A `daily_v1` to `daily_v5` row gives `[]`.
- `DaysReply.parse` accepts the `daily_v6` header and the `daily_v5` header of the `DAYS <N>` reply. Another header throws `ParseError.missingLine`.
- `DailyChartView` shows the dry hours (0 to 24 h) as a second bar series beside the mold hours of each sensor. Each series has its own color. The chart appears when a sensor has a mold value or a dry value.
- Tests: `DailyLogTests` parse a `daily_v6` row with 5 dry hours for sensor 2, check `nil` for an empty field, check `[]` for a `daily_v5` file and check `ParseError.badLine` for a row with 93 columns. `DaysReplyTests` parse the `daily_v6` fixture.

## Mold and dry hours of the day on the Wear OS today screen

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/TodayReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/TodayScreen.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/TodayReplyTest.kt`
- `TodayReply.parse` accepts the old header with 9 columns and the new header with `mold_h,dry_h` (11 columns, `HEADER_V2`). The header selects the column count. `TodayRow.moldHours` and `TodayRow.dryHours` (`Int?`) are null for an empty field and for the old header. `ParseException` for a wrong column count, a bad number and a value above `hours`.
- `TodayScreen` shows the line `Mold <N> h · Dry <N> h` (`TodayScreenText.moldDry`, `--` for null) below the humidity line of each result card.
- Tests: `TodayReplyTest` parses the old fixture with null values, a row with `mold_h` 3 and `dry_h` 0, an empty field, 10 columns, a value above `hours` and the line text `Mold 3 h · Dry -- h`.

## Mold and dry hours of the day in the Android today screen

- `TodayReply.parse` (`protocol/TodayReply.kt`) accepts the old header (9 columns) and the new header `HEADER_V2` with `mold_h` and `dry_h` (11 columns). The header selects the column count.
- `TodayRow.moldHours` and `TodayRow.dryHours` (`Int?`) are `null` for an empty field and for the old header.
- `ParseException` covers a count with the wrong column count for the header, a non-digit or signed value, a value above 24 and a value above `hours` (for example `dry_h` 7 with hours 5).
- `TodayScreen` shows `Schimmel <N> h` and `Trocken <N> h` on each card with data, with `⚠` above 0 and `--` for `null` (`TodayText.mold`, `TodayText.dry`).
- `TodayReplyTest` parses `tests/fixtures/today_reply_sample.txt` (old header) with `null` hours, a new row with `mold_h` 3 and `dry_h` 0, `null` for empty fields, and `ParseException` for 10 columns and for `dry_h` 7 with hours 5.
- Validation: the local host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs in CI only.

## Console command RECEPTION with the scan mask of each slot

The `RX <N>%` value and the `RECEPTION` line of `STATUS` show only a rate. The user could not see which scans failed. The new console command `RECEPTION` shows the scan mask of each slot, so the user finds a sensor with a gap pattern.

- `SerialConsoleRunner::showReception` reads the RTC copy of `ReceptionHistory` and the registry. It replies `BEGIN RECEPTION`, the header `slot,mac,name,scans,received,mask`, one row for each occupied registry slot and the `CRC32` line. An empty slot has no row.
- `mask` has `scans` characters (at most 24), oldest scan first: `1` for a scan with a packet, `0` for a missed scan. `received` is the count of `1` characters. `name` comes from `DeviceConfig::nameForMac` and is empty for an unknown MAC.
- Extra text, for example `RECEPTION 1` and `RECEPTIONX`, gives `ERR BAD_ARG`. The command mounts no card, changes no state and does not change the RTC layout or `STATE_VERSION`. It runs on USB and on BLE at each `ble_console` level.
- `tests/fixtures/reception_reply_sample.txt` holds the reply sample. The tests cover slot 2 with 6 scans and the mask `101101`, an empty slot, a slot with 24 scans, `ERR BAD_ARG`, the BLE gate at `ble_console` 1, a short write, the CRC-32, an unchanged state and the fixture.
- Validation: the host tests and `arduino-cli compile rlcd_panel` pass. The companion apps do not parse `RECEPTION` yet.

## Reception strip on the Wear OS watch

- **Code:** `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/ReceptionReply.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/ReceptionScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/SensorHealthScreen.kt`, `wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/ReceptionReplyTest.kt`
- `ReceptionReply.parse` reads the `RECEPTION` reply (`BEGIN RECEPTION`, the header `slot,mac,name,scans,received,mask`, one row for each slot). `ParseException` covers a missing `BEGIN` or header, a wrong column count, a slot outside 1 to 10, a bad MAC, `scans` above 24, `received` above `scans`, a mask with another length than `scans`, a mask character other than `0` and `1`, a `received` value that differs from the count of `1` characters, and a repeated slot.
- `ReceptionRow.newestCells()` gives the newest 12 scans (`STRIP_CELLS`) of the mask, oldest first. `ReceptionRow.isLow` is true below 50 % of the counted scans; a slot without a counted scan is not low.
- The `Reception` chip in the sensor health screen opens `ReceptionScreen`. The command `RECEPTION` goes out only after a tap on `Read reception`, with the same permission, busy and cancel rules as `REJECTS`. The screen shows the name (`Slot <N>` for an empty name), `<received>/<scans>` with `⚠` below 50 %, and a strip of the newest 12 cells (`█` packet, `·` gap).
- Tests: `ReceptionReplyTest` parses `tests/fixtures/reception_reply_sample.txt`, checks the CRC-32, the newest 12 cells of a 24 character mask, the warning below 50 %, and `ParseException` for the mask `10a1`, for a wrong mask length, for a `received` mismatch, for a repeated slot and for a missing header.
- The Android parser `android/RlcdPhone/.../protocol/ReceptionReply.kt` follows the same rules. The file is not in the protocol sync list, so a change needs both copies.
- Validation: the local host has no Android SDK, so `gradle testDebugUnitTest assembleDebug` runs in CI only. `scripts/check-protocol-sync.sh` passes.

## Reception strip in the Android sensor health screen

- **Code:** `android/RlcdPhone/.../protocol/ReceptionReply.kt`, `SensorHealthScreen.kt`, `MainActivity.kt`, `android/RlcdPhone/app/src/test/.../protocol/ReceptionReplyTest.kt`, `tests/fixtures/reception_reply_sample.txt`
- The phone showed only the reception rate of each slot. The `Read reception` button of the sensor health screen sends `RECEPTION` over the BLE console, only on a tap. The panel needs USB power and `ble_console` 1 or 2.
- `ReceptionReply.parse` reads `BEGIN RECEPTION`, the header `slot,mac,name,scans,received,mask` and one `ReceptionRow` for each slot with a reading. A wrong column count, a slot outside 1 to 10, a repeated slot, a bad MAC, a scan count above 24, a mask character other than `0` or `1`, a mask length other than `scans` or a `received` value that does not match the mask throws `ParseException`. An empty reply gives no rows.
- `SensorHealthScreen` shows one strip below the card text of each slot: up to 24 cells, oldest scan left, a filled cell for a scan with a packet. `ReceptionRow.isLow` (below 50 %, `received * 100 < 50 * scans`) draws the strip in the error colour and puts `⚠` before the text `Scan-Muster <received>/<scans> (<N> %)`. A slot without a counted scan shows no cells and no rate.
- A failed CRC-32, a timeout or an `ERR` reply gives one error text through `BleConsoleClient.errorText`. The command is not repeated. A read that `onStop` aborts gives `Abgebrochen. Erneut lesen.`
- `ReceptionReplyTest` parses `tests/fixtures/reception_reply_sample.txt` and its CRC-32, checks the cells of the mask `101101`, the low-rate flag, an empty reply and `ParseException` for the mask `10a1`, a wrong mask length, a wrong `received` value, a repeated slot, a wrong column count and a scan count above 24.
- The local machine has no Android SDK. CI runs `gradle testDebugUnitTest assembleDebug`.

## Mold and dry hours columns in the console command TODAY

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/sparkline.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `tests/fixtures/today_hours_reply_sample.txt`, `tests/fixtures/today_hours_one_reply_sample.txt`
- `TODAY` and `TODAY <N>` send the header `...,rh_mean_pct,mold_h,dry_h` (11 columns). `mold_h` counts the humidity samples since midnight strictly above `mold_alert_limit_rh`; `dry_h` counts those at or below `dry_alert_limit_rh`. Both use the sample age rule of the humidity fields.
- `HumidityHistory::countAboveNewest` and `countAtOrBelowNewest` apply the rules of `countAbove` and `countAtOrBelow` to the newest N samples. A sample at the mold limit does not count; a sample at the dry limit counts.
- Empty fields: both for a sensor without a humidity sample since midnight; `dry_h` also for `dry_alert_limit_rh` 0.
- The old samples `today_reply_sample.txt` and `today_one_reply_sample.txt` stay unchanged for the app parsers. The RTC layout and `STATE_VERSION` do not change.
- Host tests cover 5 samples with `mold_h` 2 and `dry_h` 1, the mold limit, a sample before midnight, limit 0, a sensor without a sample, `TODAY 2` and both new fixtures. Validation: host tests and `arduino-cli compile rlcd_panel` pass.

## Reception strip in the macOS diagnostics view

The `BLE reception` group of the diagnostics view showed only a rate for each slot. The new strip shows the scan pattern, so the user sees a gap pattern at once. It uses the console command `RECEPTION`.

- `ConsoleCommand.reception` sends `RECEPTION`. `ReceptionReply.parse` (`ReceptionReply.swift`) reads `BEGIN RECEPTION`, the header `slot,mac,name,scans,received,mask` and the rows into `ReceptionRow` (slot, MAC, name, scans, received and `[Bool]` cells, oldest scan first, `true` for a received packet). `percent` and `isWeak` use the exact compare of `ReceptionMetrics.Rate` (weak below 50 %).
- `ParseError.badLine` for a wrong column count, a slot outside 1 to 10, a bad MAC, scans above 24, a mask character other than `0` or `1`, a mask length other than `scans`, a `received` value that differs from the count of `1` characters and a repeated slot. A wrong first line or header gives `ParseError.missingLine`. An empty reply gives zero rows.
- `DiagnosticsView` shows the button `Read scan pattern` in the group `BLE reception (last 24 scans)` and one strip of 24 cells for each row (filled cell for a received packet, light cell for a missed scan, no cell beyond `scans`), the count `N/M` and a warning symbol below 50 %. `AppModel.readReception` sends `RECEPTION` only on this button, on USB and on BLE, and `AppModel.receptionResult` is cleared on a link change, an error reply, a parse failure and a missing reply.
- `tests/fixtures/reception_reply_sample.txt` is parsed by `ReceptionReplyTests`. The tests also cover the cells of the mask `101101`, the weak rule, an empty reply, `ParseError.badLine` for the mask `10a1`, a mask of 5 characters with `scans` 6, `received` 3 with the mask `101101` and a repeated slot.
- Validation: `swift build -c release` passes locally. The local Command Line Tools lack TestingMacros, so `swift test` runs in CI.

## Outdoor slot proposal in the Android setup assistant

- **Code:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/OutdoorProposal.kt`, `.../protocol/SetupAssistant.kt`, `.../SetupAssistantScreen.kt`, `.../MainActivity.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/OutdoorProposalTest.kt`.
- `OutdoorProposal.propose(week, config)` is a port of the macOS `OutdoorProposal.propose(week:config:)`. A sensor counts only with at least 3 days that hold a temperature pair (`MIN_DAYS`); the range of a sensor is the mean daily maximum minus minimum. The slot (1 to 10) with the widest range is the candidate when its range is at least `RANGE_FACTOR` 2.0 times the range of the next slot (next range 0 for a single slot) and its mean daily minimum is below that of the onboard sensor.
- The result is `null` without a `CONFIG` reply, without an onboard range, without a slot range, below the factor, for a slot that is not colder, and when `outdoor_slot` already holds the slot. The text is `Outdoor slot <N> (<name>): range <x> C, next <x> C, minimum <x> C, onboard minimum <x> C`; the name comes from `PanelConfig.tileName`.
- In the `CALIBRATE` step the setup assistant shows the button `Wochendaten lesen (Außensensor)`; `SetupAssistant.beginReadWeek` sends `WEEK` only on this tap. With a proposal the screen shows the text and the button `Als Außensensor übernehmen`. `SetupAssistant.beginApplyOutdoor` sends `SET outdoor_slot=<N>`, then `CONFIG`, and the assistant stays in `CALIBRATE`.
- Tests: `OutdoorProposalTest` covers the clear proposal (slot 2), the range factor (also the exact boundary), a warm slot, a slot with two days, no onboard range, the active value, no `CONFIG`, the `WEEK` read on demand and the `SET` line with the following `CONFIG`.
- Validation: the host has no Android SDK, so Gradle did not run locally. The CI job `Android phone companion tests and APK` runs `gradle testDebugUnitTest assembleDebug`.

## Slot enable toggle in the macOS scanner view

- **Code:** `macos/RlcdCompanion/Sources/RlcdProtocol/SlotAssignment.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/ScannerView.swift`, `macos/RlcdCompanion/Sources/RlcdCompanion/AppModel.swift`
- `SlotAssignment.enableCommand(slot:enabled:config:)` builds `SET slot<N>_enabled=<0|1>`. It gives `nil` for a slot outside 1 to 10, without a cached `CONFIG` reply and for a slot that already holds the value. `SlotAssignment.isEnabled` is true unless `slot<N>_enabled=0` (a missing key is the default 1).
- `AppModel.setSlotEnabled(slot:enabled:)` sends the command, then `CONFIG`; without a `CONFIG` reply or for a held value it sends nothing and shows a message. `ScannerView` shows the menu `Slot on/off` with one toggle for each slot; it needs `canSet`, an idle link and a `CONFIG` reply.
- `SlotAssignment.menuTitle` adds ` (Off)` to the slot menus (`Assign`, `Assign to slot`). `SlotAssignment.disabledWarning` blocks `AppModel.assign` (MAC and MAC with name) for a disabled slot; clearing a slot stays possible.
- `SlotAssignmentTests` cover the command, a slot that holds the value, a missing `CONFIG`, the default 1 for a missing key and the blocked assignment.
- Validation: `swift build -c release` passes locally. The local Command Line Tools lack TestingMacros, so `swift test` runs in CI.

## Config key heat_alert_delay_h for the heat alert

- The new `config.ini` key `heat_alert_delay_h` stops a short warm peak from raising `ALERT_HEAT`. The value 0 is off (default). Else the value is 1 to 12 h. Another value, for example 13, gives a rejected line.
- `AlertLimits::heat_delay_h` holds the delay. `AlertEvaluator::Tally::add` raises `ALERT_HEAT` for a tile only when the live feels-like value is at or above `heat_alert_limit_c` and `Tally::heatDelayPassed` is true. The newest N hourly pairs of the tile must each give a `ClimateMath::heatIndexC` value at or above the limit. The pair takes the temperature sample and the humidity sample of the same index. The two histories must have the same size, because each push is independent and a skipped push shifts the pairs.
- No rise for fewer than N pairs, for histories of unequal size, for an invalid pair (NaN heat index) and for a delay without a history pointer. `alertLimits()` in `rlcd_panel.ino` sets `heat_delay_h` and uses the same `history` pointer as `mold_delay_h`.
- The hold rule and the release 2 C below the limit do not change. A delay never clears an active alert.
- The key is the byte after `drift_limit_rh` at the end of the config trailer. `DeviceConfigParser::KEY_COUNT` is 104 (index 103) and `PowerManager::STATE_VERSION` is 82. `docs/config.example.ini` and `tests/fixtures/config_backup_sample.ini` list the key.
- The three `ConfigSchema` tables (macOS, Android, Wear OS), `ConfigBackup.kt` and `EventText.kt` list the key.
- Host tests cover the rise with delay 2 and two pairs, no rise with one pair, no rise with an older pair below the limit, no rise for a short humidity history and for a missing history pointer, the release 2 C below the limit, the values 12 and 13, the text round trip and the RTC byte with a corrupt value.
- Validation: host tests (242 suites) and `arduino-cli compile rlcd_panel` pass locally. The local Command Line Tools lack TestingMacros and the Android SDK, so `swift test` and `gradle testDebugUnitTest` run in CI.
