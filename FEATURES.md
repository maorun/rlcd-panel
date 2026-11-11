# Features

This file describes finished work for `rlcd-panel`. Open work is in `ROADMAP.md`.

## Deep sleep power management with periodic wakeup

- **Code:** `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`.
- The board runs one wake cycle, then enters deep sleep: restore state, scan BLE for 4 s, read sensors, render, save state, sleep.
- The sleep timer starts the next scan 60 s after the start of the last scan. The timer subtracts the awake time and clamps to 1 s to 1 h.
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
- `SdLogCodec` has no Arduino dependency. It writes the CSV header, one row per record, and the file name `/sdcard/climate_v2_YYYYMM.csv`.
- A row has the timestamp, battery (percent, volt, USB), the onboard `SHTC3` (T, RH), and 3 SwitchBot slots (T, RH, battery). A missing, invalid, stale or NaN source gives empty fields, so each row has 18 columns (see "MicroSD noise logging").
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
- `muted` and `alert_mask` stay in RTC memory (state version 4, one byte). The old format gives a clean start. A reserved bit with a valid CRC is rejected.
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
- `NtpSchedule` decides when to sync: once per 24 h after a success. After a failure the retry delay doubles from 15 min up to 6 h. A `KEY` wakeup, a low battery, a missing RTC, and missing credentials never start the radio. These skips leave the state unchanged.
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
- The band stays in RTC memory (bits 3 and 4 of the audio byte). The state version stays 5: an old state decodes as `QUIET`, and a band value of 3 is rejected.
- Host tests cover the RMS of silence, sine, DC and full scale, the band limits and hysteresis, the sampler power-down paths, the diagnostics line (all states, no clipping), and the RTC round trip.

## MicroSD noise logging with hourly measurement

- **Code:** `rlcd_panel/sd_logger.h`, `rlcd_panel/es7210_mic.h`, `rlcd_panel/rlcd_panel.ino`.
- The CSV format is version 2. The header has 18 columns: the 15 climate columns plus `noise_rms`, `noise_db` and `noise_band`. The file name is `/sdcard/climate_v2_YYYYMM.csv`, so the new header never mixes with rows of the old 15-column files. The old files stay unchanged on the card.
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
  | `freeze_alert_limit_c` | `0.0` | `-20.0` to `15.0` | freezer alert trigger |
  | `mold_alert_limit_rh` | `70` | `40` to `95` | humidity alert trigger |
  | `chime_volume_pct` | `30` | `0` to `100` | chimes and the unmute beep |
  | `quiet_hours_start_hour` | `22` | `0` to `23` | quiet hours start |
  | `quiet_hours_end_hour` | `7` | `0` to `23` | quiet hours end |
  | `mic_spl_offset_db` | `0.0` | `-20.0` to `20.0` | added to the microphone SPL offset |

- Equal start and end hours switch the quiet hours off. A lone CR is no line end: use LF or CRLF. The SPL offset has whole dB steps, so a fraction below 0.5 dB has no effect.
- A card that does not mount keeps the previous settings on a `KEY` wakeup. A card without a usable file gives the defaults.
- The release limits keep a fixed hysteresis: `+1.0 C` for the freezer alert and `-3 %RH` for the humidity alert. The extra SPL offset applies in memory only. A new microphone calibration never stores it.
- The accepted values are part of `PersistentState` in RTC memory (state version 6). A corrupt or out-of-range record gives a clean start.
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
