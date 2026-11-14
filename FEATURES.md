# Features

This file describes finished work for `rlcd-panel`. Open work is in `ROADMAP.md`.

## Deep sleep power management with periodic wakeup

- **Code:** `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`.
- The board runs one wake cycle, then enters deep sleep: restore state, scan BLE for 4 s, read sensors, render, save state, sleep.
- The sleep timer starts the next scan `wake_interval_s` seconds after the start of the last scan (default 60 s, range 30 s to 900 s, set in `config.ini`). `PowerManager::powerConfig` passes the value to `PowerConfig::sleep_interval_s` on battery power. USB power keeps the default 60 s. The hourly log, the histories and the NTP schedule use RTC stamps, so the interval does not move them. Only a power-on and a `KEY` wakeup read the card, so a new value takes effect after one of them. The runtime estimate fits the sample stamps, so wake times off the hour grid do not distort it. The timer subtracts the awake time and clamps to 1 s to 1 h.
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
- `SdLogCodec` has no Arduino dependency. It writes the CSV header, one row per record, and the file name `/sdcard/climate_v4_YYYYMM.csv`.
- A row has the timestamp, battery (percent, volt, USB), the onboard `SHTC3` (T, RH), and 3 SwitchBot slots (T, RH, battery). A missing, invalid, stale or NaN source gives empty fields, so each row has 21 columns (see "MicroSD noise logging", "Hourly count of loud transient events" and "L10 and L90 noise levels in the hourly window").
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
- `SdLogCodec::noiseMeasureWanted()` is the gate. The wake cycle runs one 5 s `NoiseSampler` window only when a record is due, the RTC time is valid, and no low battery alert is active. This also applies after a timer wakeup. Each other timer wakeup keeps the microphones off, except on USB power, where the clap listener runs.
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
- The parser rejects a file above 4096 bytes as a whole. The card never supplies Wi-Fi credentials. Those keys are unknown keys.
- Keys, defaults, and ranges:

  | Key | Default | Range | Used by |
  | --- | --- | --- | --- |
  | `temp_offset_c` | `-1.9` | `-10.0` to `10.0` | `SHTC3` decoder |
  | `rh_offset_pct` | `0.0` | `-10.0` to `10.0` | `SHTC3` decoder, result clamped to 0 to 100 %RH |
  | `freeze_alert_limit_c` | `0.0` | `-20.0` to `15.0` | freezer alert trigger |
  | `mold_alert_limit_rh` | `70` | `40` to `95` | humidity alert trigger |
  | `chime_volume_pct` | `30` | `0` to `100` | chimes and the unmute beep |
  | `quiet_hours_start_hour` | `22` | `0` to `23` | quiet hours start |
  | `quiet_hours_end_hour` | `7` | `0` to `23` | quiet hours end |
  | `mic_spl_offset_db` | `0.0` | `-20.0` to `20.0` | added to the microphone SPL offset |
  | `log_retention_months` | `0` | `0` to `120` | delete log files older than this; `0` keeps all |
| `noise_alert_limit_db` | `0` | `0` (off) or `40` to `100` | loud noise alert trigger |
| `chime_alert_mask` | `127` | `0` to `127` | chime per alert (bit 0 freezer, 1 humidity, 2 noise, 3 sensor lost, 4 charge full, 5 ventilate, 6 sensor battery) |
| `sensor_battery_limit_pct` | `10` | `0` to `50` | an external sensor at or below this battery level shows `BAT LOW`; `0` is off |
  | `ntp_sync_interval_h` | `24` | `6` to `168` | hours between two successful NTP syncs |
  | `wake_interval_s` | `60` | `30` to `900` | seconds between two scans on battery power; USB power keeps `60` |
  | `sensor_stale_min` | `30` | `5` to `240` | minutes without a packet until a sensor is stale |
  | `display_invert` | `0` | `0` or `1` | `1` shows light text on a dark background |
  | `slot_release_h` | `0` | `0` to `168` | hours without a packet until a slot without a fixed MAC is released; `0` is off |
  | `temp_unit` | `C` | `C` or `F` | display unit of the temperature on the screen and in `STATUS`; everything else stays in Celsius |
  | `night_wake_interval_s` | `0` | `0`, or `60` to `1800` | seconds between two scans on battery power in the quiet hours; `0` keeps `wake_interval_s` |
  | `climate_alert_tiles` | `15` | `0` to `15` | tiles of the freezer and mold alerts (bit 0 onboard, bits 1 to 3 slots 1 to 3); `0` switches both alerts off |
  | `outdoor_slot` | `0` | `0` (off) to `3` | registry slot of the outdoor sensor for the ventilation advice |

- Equal start and end hours switch the quiet hours off. A lone CR is no line end: use LF or CRLF. The SPL offset has whole dB steps, so a fraction below 0.5 dB has no effect.
- A card that does not mount keeps the previous settings on a `KEY` wakeup. A card without a usable file gives the defaults.
- The release limits keep a fixed hysteresis: `+1.0 C` for the freezer alert and `-3 %RH` for the humidity alert. The extra SPL offset applies in memory only. A new microphone calibration never stores it.
- The accepted values are part of `PersistentState` in RTC memory (state version 14). A corrupt or out-of-range record gives a clean start.
- The diagnostics view shows `CONFIG: SD` or `CONFIG: DEFAULT`.
- Host tests cover valid values, comments, spaces, CRLF, missing keys, out-of-range and malformed values, duplicate keys, a file above 4 KB, the wiring into each consumer, and the RTC round trip with corrupt records.

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

## Acoustic double-clap detection in the awake window

- **Code:** `rlcd_panel/clap_detector.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`.
- A double clap cycles the display mode, as a `KEY` press does. The board listens only while it is awake: the `ES7210` has no interrupt output and deep sleep cannot sample I2S, so a sound cannot wake the board.
- `PowerManager::clapListenMode` sets the gate. On USB power the board listens continuously, also after a timer wakeup, until the next scan is due (`continuousListenAllowed`). On battery the board listens only in the 3 s `KEY` grace window, which a `KEY` wakeup or a power-on opens. A timer wakeup on battery and a low battery alert on battery keep the microphones off.
- `ClapListener` reads one 20 ms audio frame at a time. The read blocks, so the CPU idles between frames. It reduces each millisecond to a peak level in dBFS and ignores the first 100 ms (ADC settling).
- `ClapDetector` finds a clap as a short transient: the level rises 20 dB above the noise floor in less than 5 ms, is at least -40 dBFS, and falls below floor + 10 dB in less than 100 ms. Speech (slow rise) and continuous noise (no decay) give no clap. The noise floor follows the level slowly and stands still during a transient.
- Two claps with onsets 150 ms to 600 ms apart give a double clap. A closer clap is an echo. A third clap within 400 ms of the second clap is applause: all claps are dropped. The detector reports the double clap 400 ms after the second clap and then ignores claps for 1 s.
- The microphone is powered down in the order `ES7210` shutdown, then I2S bus end. A render with a noise window releases it first, and the next frame opens it again. `stop()` runs before the state save on each path, and the destructor does it too.
- On battery, the 3 s grace window limits the detection: the settling (100 ms), the gap (up to 600 ms), the triple window (400 ms) and the decay must all fit, so claps late in the window can miss. A pending press still keeps the window open; a pending clap does not.
- Continuous listening checks the USB power every 5 s (`UsbPowerWatch`) and stops after two low samples in a row or when the listener fails. The listener then runs until the 3 s grace window ends. If the listener failed, it stays off.
- A blocked I2S read has a timeout of 1 s, so the board can stay awake up to 1 s longer than the 3 s grace window.
- The limits are estimates and are not verified on hardware. The USB idle current while the board listens is not measured yet.
- Host tests cover one clap, two claps at 100 ms, 150 ms, 300 ms, 600 ms and 800 ms, three claps, loud steady noise, speech-like bursts, a rustle in a silent room, time wrap-around, the listener on scripted audio, the power-down on each failure path, the USB and battery gate, and the mode cycle.

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
- The board reads the monthly CSV logs over USB serial (115200 baud), so the user does not remove the MicroSD card. The commands are `LIST`, `INFO` and `DUMP climate_v4_YYYYMM.csv` (the old v3 and v2 names also work). The parser (`SerialConsole::parse`) has no Arduino dependency. It accepts uppercase commands with single spaces and lines up to 40 bytes. `SerialLineAssembler` drops an overlong line whole, so its tail never runs as a command.
- `SdLogCodec::isLogFileName` accepts only a bare name `climate_v4_YYYYMM.csv` or the old `climate_v3_YYYYMM.csv` or `climate_v2_YYYYMM.csv` with a year from 2000 to 2099 and a month from 01 to 12. A path separator, `..`, `config.ini` or an old v1 name gives `ERR BAD_NAME`. Unknown commands give `ERR UNKNOWN`, long lines `ERR TOO_LONG`.
- `PowerManager::serialConsoleAllowed` is true on USB power only. On battery, or with an invalid battery reading, the board does not read the port and does not stay awake for it, so the awake time does not increase. The console has its own `UsbPowerWatch` (every 5 s, two low samples end it) and shares the awake window of the clap listener: on USB power the board stays awake until the next scan is due. The first command can wait for the next wake cycle; press `KEY` to wake the board.
- `SerialConsoleRunner` mounts the card for one command and unmounts it after the command, on each path. A valid command releases the clap listener first, because the I2S bus and the card share the awake window.
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
- After each hourly write, also after a failed write, the logger deletes the files `climate_v4_YYYYMM.csv`, `climate_v3_YYYYMM.csv` and `climate_v2_YYYYMM.csv` that are more than `log_retention_months` months older than the current RTC month (`SdLogCodec::shouldDeleteLog`). The age counts whole calendar months, also across a year change. Retention `3` in January 2026 keeps October 2025 and deletes September 2025.
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
- The command reads the RTC copy (`g_state.config`, `g_state.config_from_sd`) only. It does not mount the card and does not release the clap listener. `SerialConsoleRunner::execute` ignores `CONFIG`; `SerialConsoleRunner::showConfig` sends the reply. A short serial write ends the reply, and the host sees no CRC line.
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
- Acceptance criteria: host tests cover a positive and a negative drift, a drift of 0, the ppm rate over 24 h and 168 h, the dashes without a previous sync, the rejection of a failed sync, a sync after less than 1 h, the reference across a DST change, the line bounds, and the RTC round trip. `docs/display_preview_diagnostics.png` shows the line.

## Daily climate summary file on the MicroSD card

- **Code:** `rlcd_panel/sd_logger.h`, `rlcd_panel/sparkline.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/ntp_sync.h`, `rlcd_panel/rlcd_panel.ino`, `scripts/dump-logs.sh`, `tests/test_firmware_logic.cpp`.
- `DailyPlanner::decide` runs after each hourly write. The RTC holds local time, so the first hourly write on a new calendar date is the first one after local midnight, in CET and in CEST. The first run and a history without a sample for 24 h (`ClimateHistory::freshWithin24h`) only stamp the day. An invalid, stopped or untrusted clock, an impossible date and a clock that runs back write nothing.
- `SdLogger::appendDaily` appends one row for the day that ended to `/sdcard/daily_v1_YYYY.csv`. The year is the year of the summarized day. A new file gets the header first. The row has 27 columns: the date, six values for each of the four sensors (temperature and humidity: minimum, maximum, mean, in 0.1 steps), then `noise_min_db` and `noise_max_db`. A sensor without samples and a noise history without a valid hour give empty fields. The source is the 24 hourly samples in the RTC memory (`summary()` of the three history classes), so the window is the last 24 samples and not the exact calendar day.
- A failed write leaves the day unstamped, and the next hourly write retries. A mount failure of the hourly write skips the second mount. The hourly file `climate_v4_YYYYMM.csv` stays unchanged.
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
- The CSV format is version 4. The header has 21 columns: the 19 columns of version 3 plus `noise_l10` and `noise_l90` (dB, calibrated offset applied). The file name is `/sdcard/climate_v4_YYYYMM.csv`. A failed or skipped window gives empty fields. A window without percentiles gives empty L10 and L90 fields only. The old `climate_v3_YYYYMM.csv` and `climate_v2_YYYYMM.csv` files stay unchanged. `LIST`, `DUMP`, the retention rule and `scripts/dump-logs.sh` accept all three names.
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
- The console command `CALIB N` (`N` is `0` for the onboard sensor or `1` to `3` for a slot) compares each other sensor with the reference. A missing argument, `CALIB 4` and extra text give `ERR BAD_ARG`.
- The command pairs the hourly samples of `ClimateHistory` by age. Only hours with a temperature and a humidity sample in both histories count.
- A sensor with at least 6 common hours gets one line `SENSOR <name> hours=<n> temp_offset=<x> rh_offset=<x> temp_diff=<x> rh_diff=<x>`. The offsets are the active `config.ini` values. The differences are the mean values (sensor minus reference). A sensor with fewer hours gets `SKIP <name> hours=<n>`.
- The suggested lines follow, for example `slot2_temp_offset_c=-0.4` and `slot2_rh_offset_pct=2.1`. Each value is the active offset minus the mean difference, clamped to the key range. The onboard sensor uses the keys `temp_offset_c` and `rh_offset_pct`.
- The answer comes from the RTC copy of the histories and the configuration. The command does not mount or write the MicroSD card, so the clap listener keeps running. The command runs on USB power only. The output ends with the `CRC32` line over all lines before it.
- Host tests cover the parser, a constant difference, a gap, fewer than 6 hours, an empty reference, an active offset, the clamp, the onboard key names, the CRC-32 line and the USB power gate.

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
- The command reads the RTC copy (`g_state`) only. It does not mount the card and does not stop the clap listener. It runs on USB power only (`PowerManager::serialConsoleAllowed`). A short serial write ends the reply without the `CRC32` line.
- `./scripts/dump-logs.sh --status [port]` sends `STATUS`, checks the CRC-32 and prints the lines.

## Serial console command BEEP to test each alert chime

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/audio_alert.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
- The new console command `BEEP N` plays the chime of one alert once. `N` is the alert bit number `0` to `6` (`ALERT_FREEZER`, `ALERT_HUMIDITY`, `ALERT_NOISE_HIGH`, `ALERT_SENSOR_LOST`, `ALERT_CHARGE_FULL`, `ALERT_VENTILATE`, `ALERT_SENSOR_BATTERY`). A missing argument, `BEEP 7`, a non-digit and extra text give `ERR BAD_ARG`.
- The chime is `ChimeSynth::pattern` of this bit at `chime_volume_pct`. `AudioSequencer::playTest` plays it with the same path as a real alert: the amplifier, the `ES8311` and the I2S bus power down on each path, in this order.
- Mute, quiet hours and volume `0` give `ERR MUTED`; the hardware stays untouched. A failed playback gives `ERR PLAY_FAILED`.
- A cleared bit in `chime_alert_mask` does not block the test. The command does not read or change the stored alert bits, so no alert is replayed or lost.
- Reply after the playback: `OK BEEP <N> <duration ms>` (the sum of the note and pause durations of the pattern).
- The console stops the clap listener before the playback (`release()`); the next `poll()` reopens it. The command needs no card. It runs on USB power only (`PowerManager::serialConsoleAllowed`).

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
- The commands run on USB power only (`PowerManager::serialConsoleAllowed`). They do not mount the card, so the clap listener keeps running.
- `./scripts/dump-logs.sh --set-time [port]` sends `TIME SET` with the local time of the host and prints the result. The accuracy is about 1 s. Thus a user without Wi-Fi sets the clock for the hourly log, the quiet hours and the retention rule.
- Host tests cover the parser, 29 February in a leap year and in a common year, 24:00:00, the years 2024 and 2100, a missing `T`, extra text, the reply format with a valid and an invalid time, the cleared drift baseline, the failed write and the USB power gate.

## Serial console command HISTORY to export the 24-hour histories

- **Code:** `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `scripts/dump-logs.sh`, `tests/test_firmware_logic.cpp`
- The console command `HISTORY` (no argument) sends the 24-hour histories from the RTC copy (`ClimateHistory`, `NoiseHistory`). `HISTORY` with an argument gives `ERR UNKNOWN`.
- The reply starts with `BEGIN HISTORY` and the header `sensor,age_h,temp_c,rh_pct,noise_db`. Each stored hour of each sensor gives one row: sensor `0` is the onboard sensor, `1` to `3` are the slots. `age_h` `0` is the newest sample.
- The noise field holds a value in the rows of sensor `0` only. A missing sample or an invalid noise hour gives an empty field. Temperatures show one decimal and can be negative.
- The reply ends with `CRC32 <8 hex digits>` over all bytes before this line. A short write stops the reply, so the host sees no `CRC32` line.
- The command does not mount the card, so the clap listener keeps running. It runs on USB power only (`PowerManager::serialConsoleAllowed`).
- `./scripts/dump-logs.sh --history [port]` sends `HISTORY`, checks the CRC-32 and writes `sd_logs/history_<host time>.csv`. Thus the user gets the last 24 hours also without a card.
- Host tests cover the parser, the rejection of arguments, the header, a full and an empty history, an invalid noise hour, negative temperatures, the row order, the CRC-32 line, each short write, no card access, the USB power gate and the script option.

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
- The exit code is `0` for a file without rejected lines. It is `1` for rejected lines. It is `2` for a missing file or a file above 4096 bytes. In the case of exit code `2` the tool writes one `ERROR` line to stderr and no values.
- The CI job `host-tests` runs the script on `tools/sample_config.ini`. Thus the user finds a bad line on the host and not after a reboot of the panel.
- Host tests cover a valid file, a malformed value, an out-of-range value, more than four rejected lines, an empty file, a file at the limit and a file above 4096 bytes, a report buffer that is too small, and the exit codes 0, 1 and 2.

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
- `docs/config.example.ini` lists each key of `DeviceConfigParser` once. Each key has one comment line with its range. Keys with a default value are active lines. The names (`onboard_name`, `slot1_name` to `slot3_name`) and the fixed MACs (`slot1_mac` to `slot3_mac`) have no default value, so they are comment lines only. The file has LF line ends and is below 4096 bytes. `README.md` links to it.
- `DeviceConfigParser::KEYS` is the table of all 35 keys. `KEY_COUNT` is its size.
- The host test `testConfigExampleTemplateInSync` reads the file. It checks that the parse gives no rejected line, that the result equals the defaults of `DeviceConfig`, and that each key of `KEYS` appears in the file. It also checks that the parser knows each key of `KEYS`.
- The test fails when a key is missing in the file, when a value is not the default, when the file has more than 4096 bytes, and when a line is rejected. A new key in the parser needs an entry in `KEYS` and in the template.

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
- The command runs on USB power only. It does not mount the card, so the clap listener keeps running.
- Thus the user knows which firmware runs on the panel.
- Host tests cover the parser, the rejection of an argument, the reply format, the default hash, the build time from a fixed compile stamp, the CRC-32 line, a short write and the USB power gate.

## Longer wake interval during the quiet hours on battery power

- **Code:** `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `docs/config.example.ini`, `tests/test_firmware_logic.cpp`
- The `config.ini` key `night_wake_interval_s` has the default `0` (off). The range is `0` or `60` to `1800` seconds. A malformed or out-of-range value (for example `59`, `1801`, a negative value or text) is rejected and counts in `ConfigRejects`.
- `PowerManager::powerConfig` takes the quiet hours window and the RTC time as extra arguments. On battery power, while `QuietHoursWindow::isActive()` is true and the key is more than `0`, the key replaces `sleep_interval_s`. The window can cross midnight.
- USB power keeps `60` s. An invalid RTC time or a stopped oscillator counts as no quiet hours, so `wake_interval_s` applies.
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
