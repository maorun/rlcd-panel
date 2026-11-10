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
- An active alert replaces the header with a white bar, a double black frame, and the text `! LOW BATTERY - PLEASE CHARGE !` in all three views. The text is 370 px wide and centered. The banner does not flash, because the display sleeps between wake cycles.
- The 5x7 font has a new `!` glyph.
- `tools/render_display_preview.cpp` renders the banner with a third argument `low`.
- Host tests cover the hysteresis sequence, USB suppression, USB re-arming, banner geometry in all views, and the RTC round trip of the flag.

## 24-hour temperature trend sparklines

- **Code:** `rlcd_panel/sparkline.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`.
- `TemperatureHistory` is a circular FIFO of 24 `int16_t` samples in 0.1 C steps. The oldest sample drops out first.
- `ClimateHistory` holds one history for the onboard sensor and for each of the 3 SwitchBot slots. It records one sample per hour. It skips invalid, stale and NaN readings. The hour gate uses wrap-safe 32-bit maths.
- `Sparkline` scales the curve to the minimum and maximum of the history. A flat line or a single sample gets a span of 1.0 C. The renderer clips every pixel to its box.
- Each dashboard tile shows a 72 x 16 px curve right of the humidity line. The box stays inside the tile border and below the title divider. A tile with fewer than 2 samples shows no curve.
- The width is 72 px, not 80 px, so the curve fits next to the humidity text in a 190 px tile.
- The history stays in RTC memory (state version 2). The old format gives a clean start.
- `tools/render_display_preview.cpp` renders a sample 24 hour history.
- Host tests cover the FIFO over 24+ samples, the sample gate, the scaling edge cases, the pixel bounds, the tile bounds, and the RTC round trip with corrupt fields.

## MicroSD offline climate telemetry logger

- **Code:** `rlcd_panel/sd_logger.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`.
- `SdLogCodec` has no Arduino dependency. It writes the CSV header, one row per record, and the file name `/sdcard/climate_YYYYMM.csv`.
- A row has the timestamp, battery (percent, volt, USB), the onboard `SHTC3` (T, RH), and 3 SwitchBot slots (T, RH, battery). A missing, invalid, stale or NaN source gives empty fields, so each row has 15 columns.
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
- `NoiseSampler` reads a 5 s window of stereo samples at 16 kHz after a 100 ms settling time, and averages both microphones into one RMS value. A failed or stopped read gives no reading, never a partial one.
- The sampler powers down the ADC and closes I2S on every exit path. `Es7210Hal` (Arduino only) writes the ES7210 register sequence over I2C (`0x40`) and starts I2S receive with MCLK (`GPIO9`, `10`, `16`, `45`). The shutdown sequence turns off the ADCs, the microphone bias and the analog supply. A failed write repeats the sequence once. The register values come from the Espressif reference driver and are not verified on hardware.
- The diagnostics view shows `NOISE: <BAND> <dB> DB`, or `NOISE: --` without a reading. The line is at most 264 px wide.
- The board measures only when the diagnostics view is on screen and the wakeup is not a timer wakeup. It draws the view first, measures, then redraws. The measurement runs once per wake cycle, also after a failure. The microphones stay off in the other views, so they cost no battery.
- The band stays in RTC memory (bits 3 and 4 of the audio byte). The state version stays 5: an old state decodes as `QUIET`, and a band value of 3 is rejected.
- Host tests cover the RMS of silence, sine, DC and full scale, the band limits and hysteresis, the sampler power-down paths, the diagnostics line (all states, no clipping), and the RTC round trip.

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
