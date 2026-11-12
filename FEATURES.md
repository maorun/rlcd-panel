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
- `SdLogCodec` has no Arduino dependency. It writes the CSV header, one row per record, and the file name `/sdcard/climate_v3_YYYYMM.csv`.
- A row has the timestamp, battery (percent, volt, USB), the onboard `SHTC3` (T, RH), and 3 SwitchBot slots (T, RH, battery). A missing, invalid, stale or NaN source gives empty fields, so each row has 19 columns (see "MicroSD noise logging" and "Hourly count of loud transient events").
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
| `chime_alert_mask` | `31` | `0` to `31` | chime per alert (bit 0 freezer, 1 humidity, 2 noise, 3 sensor lost, 4 charge full) |
  | `ntp_sync_interval_h` | `24` | `6` to `168` | hours between two successful NTP syncs |
  | `wake_interval_s` | `60` | `30` to `900` | seconds between two scans on battery power; USB power keeps `60` |

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
- The board reads the monthly CSV logs over USB serial (115200 baud), so the user does not remove the MicroSD card. The commands are `LIST`, `INFO` and `DUMP climate_v3_YYYYMM.csv`. The parser (`SerialConsole::parse`) has no Arduino dependency. It accepts uppercase commands with single spaces and lines up to 40 bytes. `SerialLineAssembler` drops an overlong line whole, so its tail never runs as a command.
- `SdLogCodec::isLogFileName` accepts only a bare name `climate_v3_YYYYMM.csv` or the old `climate_v2_YYYYMM.csv` with a year from 2000 to 2099 and a month from 01 to 12. A path separator, `..`, `config.ini` or an old v1 name gives `ERR BAD_NAME`. Unknown commands give `ERR UNKNOWN`, long lines `ERR TOO_LONG`.
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
- After each hourly write, also after a failed write, the logger deletes the files `climate_v3_YYYYMM.csv` and `climate_v2_YYYYMM.csv` that are more than `log_retention_months` months older than the current RTC month (`SdLogCodec::shouldDeleteLog`). The age counts whole calendar months, also across a year change. Retention `3` in January 2026 keeps October 2025 and deletes September 2025.
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
- A failed write leaves the day unstamped, and the next hourly write retries. A mount failure of the hourly write skips the second mount. The hourly file `climate_v3_YYYYMM.csv` stays unchanged.
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
