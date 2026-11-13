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

## Priority 2: Audio, Storage, and Configuration (Score 28–33)

- [ ] Battery voltage calibration offset in config.ini
  - Rank: [Total: 32, Use: 6, Setup: 9, Harmony: 9, Safety: 8, Effort: S]
  - Location: `rlcd_panel/battery_monitor.h`, `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
  - Goal: Add the `config.ini` key `battery_offset_mv` (default `0`, range `-300` to `300` mV). `BatteryMonitor::decodeMillivolts` adds the offset to the cell voltage before each limit: the percentage, the low battery alert, the empty protection, the charge full latch, the USB voltage limit of 4.25 V, the runtime history and the cycle counter. The offset corrects the tolerance of the 3.0 divider and of the ADC. A malformed or out-of-range value is rejected and counts in `ConfigRejects`. A power-on reads the cell before the card mounts, so this first check uses the offset of the RTC copy, or `0` after a clean start. Store the offset in the config trailer of `PersistentState` (2 bytes) and increase the state version. Show the key in the `CONFIG` reply.
  - Acceptance test: Host tests cover the parser for the default, the edges `-300` and `300`, the values `-301` and `301`, a malformed value, the percentage with `+100` and `-100` mV, the low battery and the empty protection limits with an offset, the USB voltage limit with an offset, the `CONFIG` line and its parse back, and the RTC round trip with a corrupt field.

- [ ] Serial console command HISTORY to export the 24-hour histories
  - Rank: [Total: 32, Use: 5, Setup: 8, Harmony: 9, Safety: 10, Effort: S]
  - Location: `rlcd_panel/serial_console.h`, `rlcd_panel/sparkline.h`, `rlcd_panel/rlcd_panel.ino`, `scripts/dump-logs.sh`, `tests/test_firmware_logic.cpp`
  - Goal: Add the console command `HISTORY` without an argument. It sends the line `BEGIN HISTORY`, the CSV header `sensor,age_h,temp_c,rh_pct,noise_db`, and one row for each stored hour of each sensor (sensor `0` onboard, `1` to `3` the slots, `age_h` `0` is the newest sample). The noise column holds a value in the rows of sensor `0` only. A missing sample gives an empty field. End the reply with `CRC32 <8 hex digits>` over all bytes before this line. The command reads the RTC copy (`ClimateHistory`, `NoiseHistory`) only. It does not mount the card and does not stop the clap listener. It runs on USB power only. Add `./scripts/dump-logs.sh --history [port]`, which checks the CRC-32 and writes `sd_logs/history_<host time>.csv`. Thus the user gets the last 24 hours also without a card.
  - Acceptance test: Host tests cover the parser, the rejection of `HISTORY` with arguments, the header, a full history, an empty history, an invalid noise hour, negative temperatures, the row order, the CRC-32 line, the stop on a short write, no card access, and the USB power gate.

- [ ] Serial console command VERSION with the build identity
  - Rank: [Total: 31, Use: 4, Setup: 9, Harmony: 8, Safety: 10, Effort: S]
  - Location: `rlcd_panel/firmware_version.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `scripts/flash-firmware.sh`, `tests/test_firmware_logic.cpp`
  - Goal: Add `rlcd_panel/firmware_version.h` with the constant `FIRMWARE_VERSION` (semantic version, start at `1.0.0`) and the macro `RLCD_GIT_HASH` (default `unknown`). `scripts/flash-firmware.sh` passes the short Git hash with `--build-property` and adds `-dirty` for a changed working tree. Add the console command `VERSION` without an argument. It replies `VERSION fw=<version> git=<hash> core=3.3.12 state=<STATE_VERSION> built=<YYYY-MM-DDTHH:MM:SS>` and the `CRC32` line. The build time comes from `__DATE__` and `__TIME__` through `Pcf85063Codec::fromCompileStamp`. The command runs on USB power only and does not mount the card. A CI build without the property shows `git=unknown`. Thus the user knows which firmware runs on the panel.
  - Acceptance test: Host tests cover the parser, the rejection of `VERSION` with arguments, the reply format, the default hash, the build time text from a fixed compile stamp, the CRC-32 line, and the USB power gate. `arduino-cli compile rlcd_panel` passes with and without the build property.

- [ ] Commented config.ini template with a sync test
  - Rank: [Total: 31, Use: 4, Setup: 9, Harmony: 8, Safety: 10, Effort: S]
  - Location: `docs/config.example.ini`, `README.md`, `tests/test_firmware_logic.cpp`
  - Goal: Add `docs/config.example.ini`. It lists each key of `DeviceConfigParser` once, with its default value, its range and one comment line in ASD-STE100. Optional keys without a default value (names, fixed MACs) are comment lines only. The file has LF line ends and stays below 4096 bytes. `README.md` links to the file. Add a host test that reads the file. The test checks that the parse gives no rejected line, that the result is equal to the defaults of `DeviceConfig`, and that each key of the parser key table appears in the file. Thus the template stays in sync with the firmware when a new key comes.
  - Acceptance test: The new host test passes. It fails when a key of the parser is missing in the file, when a value in the file is not the default, and when the file is above 4096 bytes.

- [ ] Reset reason and abnormal reset counter in STATUS
  - Rank: [Total: 31, Use: 4, Setup: 9, Harmony: 9, Safety: 9, Effort: M]
  - Location: `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
  - Goal: Add `ResetReasonCodec`, which maps `esp_reset_reason()` to the text `POWERON`, `EXT`, `SW`, `PANIC`, `INT_WDT`, `TASK_WDT`, `WDT`, `DEEPSLEEP`, `BROWNOUT`, `SDIO` or `UNKNOWN`. A reset by `PANIC`, `INT_WDT`, `TASK_WDT`, `WDT` or `BROWNOUT` is abnormal. Count abnormal resets in NVS (`Preferences`, namespace `rlcd`, key `abn_resets`, saturates at 65535). Write NVS only in the wake cycle after an abnormal reset, so a deep sleep wakeup causes no flash write. The console command `STATUS` adds the line `RESET reason=<TEXT> abnormal=<N>` before the `POWER` line. `abnormal=--` shows when NVS is unreadable. Thus the user sees a brownout of a weak cell or a crash of the firmware.
  - Acceptance test: Host tests cover each reason text, the abnormal classification, the counter with a portable NVS stand-in (write count after 10 deep sleep wakeups is 0, after one panic is 1), the saturation, an unreadable NVS, and the `STATUS` line. `arduino-cli compile rlcd_panel` passes.

- [ ] Automatic release of a dead sensor slot
  - Rank: [Total: 31, Use: 7, Setup: 7, Harmony: 9, Safety: 8, Effort: M]
  - Location: `rlcd_panel/switchbot_ble.h`, `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/audio_alert.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
  - Goal: Add the `config.ini` key `slot_release_h` (default `0` is off, range `0` to `168` hours). A slot without a fixed MAC and without a packet for this time loses its reading, its 24-hour histories, its ventilation latch and its battery latch (`PersistentState::resetSlot`). The free slot then takes the next new sensor. A slot with a fixed MAC never releases. The release runs once per wake cycle before the BLE scan. A release can clear `ALERT_SENSOR_LOST` without a chime. A malformed or out-of-range value is rejected and counts in `ConfigRejects`. Store the value in the config trailer of `PersistentState` (1 byte) and increase the state version. Show the key in the `CONFIG` reply. Thus a removed sensor does not block a slot and the sensor lost alert until the next power-on.
  - Acceptance test: Host tests cover the parser for the default, the edges `0` and `168`, the value `169`, a malformed value, the release at the exact time and over the 32-bit clock wrap, no release before the time, no release with the key `0`, no release of a fixed slot, the cleared histories and latches, the cleared `ALERT_SENSOR_LOST` bit, a new sensor in the free slot, the `CONFIG` line, and the RTC round trip.

- [ ] Hours above the mold limit in the sensor stats view
  - Rank: [Total: 30, Use: 8, Setup: 3, Harmony: 9, Safety: 10, Effort: S]
  - Location: `rlcd_panel/sparkline.h`, `rlcd_panel/dashboard_view.h`, `tools/preview_scenes.h`, `tests/test_firmware_logic.cpp`
  - Goal: Add `HumidityHistory::countAbove(limit_tenths)`. It counts the stored hourly samples above the limit. Each sensor block of the stats view shows `MOLD <N>H` in scale-1 text, right-aligned to x = 392, on the MAC line (`y + 21`). The limit is `mold_alert_limit_rh`. A slot with fewer than 2 samples, an empty slot and a stale slot show `MOLD --`. The text stays right of the widest MAC text `MAC ..:EE:FF:00` and does not touch the comfort label. Calculate the count for each render. No RTC state changes. Thus the user sees how long a room was at risk of mold on the last day.
  - Acceptance test: Host tests cover 0, 1 and 24 hours above the limit, a sample at the exact limit (not counted), a limit change, fewer than 2 samples, an empty and a stale slot, and the text bounds in each block. `docs/display_preview_stats.png` shows at least one value above 0.

- [ ] Event log file on the MicroSD card
  - Rank: [Total: 30, Use: 6, Setup: 8, Harmony: 9, Safety: 7, Effort: M]
  - Location: `rlcd_panel/sd_logger.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `scripts/dump-logs.sh`, `tests/test_firmware_logic.cpp`
  - Goal: Add `EventRing`, a ring of 16 events in `PersistentState`. An event has the local RTC time, a code and a 16-bit detail. Record an event for each rising and falling edge of an alert bit, for each config load (detail: count of rejected lines), for each NTP sync result, for the set and the release of the low battery alert, and for the empty protection. The hourly write appends the events to `/sdcard/events_v1_YYYY.csv` (columns `time,event,detail`) while the card is mounted, and then clears the ring. A full ring drops the oldest event and adds the row `EVENTS_DROPPED <N>` at the next write. A failed write keeps the ring. `LIST`, `DUMP`, the retention rule (as for the daily file) and `scripts/dump-logs.sh` accept the new name. Increase the state version. Thus the user sees when an alert started and ended.
  - Acceptance test: Host tests cover the ring with 1, 16 and 17 events, the dropped row, each event code, the CSV header and rows, a failed write with a retry, an invalid RTC time, the file name check of the console, the retention rule, and the RTC round trip with a corrupt ring.

- [ ] Longer wake interval during the quiet hours on battery power
  - Rank: [Total: 29, Use: 6, Setup: 5, Harmony: 9, Safety: 9, Effort: M]
  - Location: `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/audio_alert.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
  - Goal: Add the `config.ini` key `night_wake_interval_s` (default `0` is off, range `0` or `60` to `1800`). On battery power and while `QuietHoursWindow::isActive()` is true, `PowerManager::powerConfig` uses this value as `sleep_interval_s`. USB power keeps 60 s. An invalid RTC time uses `wake_interval_s`. The hourly log, the histories and the NTP schedule use RTC stamps, so they stay correct. `DeviceConfig::staleTimeoutMs()` uses the larger of the two intervals for its clamp, so a slot does not turn stale during the night. A malformed or out-of-range value is rejected and counts in `ConfigRejects`. Store the value in the config trailer of `PersistentState` (2 bytes) and increase the state version. Show the key in the `CONFIG` reply. Thus the battery lasts longer, because the panel scans less often while the user sleeps.
  - Acceptance test: Host tests cover the parser for the default, the edges `60` and `1800`, the values `59` and `1801`, a malformed value, the interval inside and outside the quiet hours, a window across midnight, USB power, an invalid RTC time, the stale clamp with both intervals, the `CONFIG` line, and the RTC round trip.

- [ ] Fahrenheit display unit in config.ini
  - Rank: [Total: 29, Use: 7, Setup: 4, Harmony: 8, Safety: 10, Effort: M]
  - Location: `rlcd_panel/device_config.h`, `rlcd_panel/climate_math.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tools/preview_scenes.h`, `tests/test_firmware_logic.cpp`
  - Goal: Add the `config.ini` key `temp_unit` (default `C`, values `C` and `F`). With `F`, the tiles, the dew point line, the 24-hour record line and the console command `STATUS` show the temperature in Fahrenheit with the glyph `F`. Add `ClimateMath::toDisplay(temp_c, unit)`. The MicroSD files, the `config.ini` limits and offsets, the alerts, the trend limits and the comfort limits stay in Celsius. A malformed value is rejected and counts in `ConfigRejects`. Store the unit in the config trailer of `PersistentState` (1 byte) and increase the state version. Show the key in the `CONFIG` reply. Thus a user who reads Fahrenheit can use the panel.
  - Acceptance test: Host tests cover the parser for `C`, `F`, `f`, `K` and an empty value, the conversion at -40 C, 0 C, 37 C and 100 C, the text bounds of the widest Fahrenheit values in each tile and in the record line, the unchanged CSV row, the `CONFIG` line, and the RTC round trip. `docs/display_preview_dashboard.png` stays bit-exact for the default scenes.

- [ ] 24-hour climate chart view for one sensor
  - Rank: [Total: 28, Use: 8, Setup: 2, Harmony: 9, Safety: 9, Effort: M]
  - Location: `rlcd_panel/button_handler.h`, `rlcd_panel/dashboard_view.h`, `rlcd_panel/sparkline.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tools/render_display_preview.cpp`, `tools/preview_scenes.h`, `scripts/generate-display-preview.sh`, `tests/test_firmware_logic.cpp`
  - Goal: Add `DisplayMode::CLIMATE_CHART` (value 4) after `AUDIO_SPECTRUM` and set `DISPLAY_MODE_COUNT` to 5. The view shows the name of one sensor, a 360 x 90 px temperature curve and a 360 x 90 px humidity curve of the last 24 hours, each with its minimum and maximum. Add `Sparkline::renderTemperature` with a minimum span of 2.0 C. In this view a short `KEY` press selects the next sensor (`ButtonEvent::NEXT_SENSOR`) and a hold of 2 s changes the view, as in the spectrum view. Store the selected sensor in `PersistentState` (1 byte) and increase the state version. A sensor with fewer than 2 samples shows `NO DATA`. The view reads only the RTC histories, so it needs no sensor read and no microphone. Thus the user sees the course of one room in detail.
  - Acceptance test: Host tests cover the mode cycle with 5 modes, the rejection of a stored mode above 4, the `KEY` handling (short press, long press, wake press), the sensor selection with wrap, the curve bounds, the minimum span, `NO DATA`, and the RTC round trip. `./scripts/generate-display-preview.sh` writes the new file `docs/display_preview_chart.png`, and the other preview files stay bit-exact.

## Priority 3: Audio Expansions (Score 22–27)

- [ ] Serial console command SET to change one config.ini key
  - Rank: [Total: 27, Use: 5, Setup: 9, Harmony: 8, Safety: 5, Effort: M]
  - Location: `rlcd_panel/serial_console.h`, `rlcd_panel/device_config.h`, `rlcd_panel/sd_logger.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `scripts/dump-logs.sh`, `tests/test_firmware_logic.cpp`
  - Goal: Add the console command `SET <key>=<value>` (line limit 40 bytes). The command accepts only a key of `DeviceConfigParser` and checks the value with the parser rules. A bad key gives `ERR BAD_KEY`, a bad value gives `ERR BAD_VALUE`. The command reads `/sdcard/config.ini` (4096-byte limit), replaces the last line of the key or appends a new line, and keeps each other line and each comment. It writes the result to `/sdcard/config.tmp`, removes the old file, and renames the new file. A failed step keeps the old file when possible and gives `ERR WRITE_FAILED`. A result above 4096 bytes gives `ERR TOO_LARGE` and writes nothing. After a success the firmware applies the new config with `PersistentState::applyConfig`, as a `KEY` wakeup does, and replies `OK SET <key>`. The command runs on USB power only. Thus the user changes a setting without a card removal.
  - Acceptance test: Host tests cover the parser, an unknown key, a bad value, the replacement of the last line of a key, a new key, kept comments and CRLF lines, the size limit, each failure step of the file write, the applied config, and the USB power gate. `arduino-cli compile rlcd_panel` passes.

- [ ] Chime reminder for an alert that stays active
  - Rank: [Total: 26, Use: 6, Setup: 3, Harmony: 9, Safety: 8, Effort: M]
  - Location: `rlcd_panel/audio_alert.h`, `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
  - Goal: Add the `config.ini` key `chime_repeat_h` (default `0` is off, range `0` to `24` hours). When an alert bit stays active for this time after its last chime, the chime of the lowest active bit sounds again. `ALERT_CHARGE_FULL` never repeats. Mute, quiet hours and a cleared bit of `chime_alert_mask` suppress the repeat. A suppressed repeat is skipped and not delayed, so no chime plays at the end of the quiet hours. A new rising edge resets the timer. Store the clock stamp of the last chime in `PersistentState` (4 bytes) and the key in the config trailer (1 byte), and increase the state version. Show the key in the `CONFIG` reply. Thus the user does not forget an open window or a freezer that is too warm.
  - Acceptance test: Host tests cover the parser for the default, the edges `0` and `24`, the value `25`, a malformed value, a repeat at the exact time and over the 32-bit clock wrap, no repeat for `ALERT_CHARGE_FULL`, the lowest active bit, mute, quiet hours and a cleared mask bit, the reset by a new edge, and the RTC round trip.

- [ ] Feels-like temperature in the sensor stats view
  - Rank: [Total: 25, Use: 6, Setup: 1, Harmony: 8, Safety: 10, Effort: S]
  - Location: `rlcd_panel/climate_math.h`, `rlcd_panel/dashboard_view.h`, `tools/preview_scenes.h`, `tests/test_firmware_logic.cpp`
  - Goal: Add `ClimateMath::heatIndexC(temp_c, rh_pct)` with the NOAA Rothfusz regression and its two NOAA corrections. Below 26.7 C or below 40 %RH the function returns the air temperature. NaN, infinite and out-of-range input gives an invalid result. Each sensor block of the stats view shows `FEELS <T>C` in scale-1 text, right-aligned to x = 392, on the first line (`y + 3`). A stale or empty slot and an invalid result show `FEELS --`. With a 12-glyph name the text keeps a gap of at least 4 px to the `RSSI` text. Calculate the value for each render. No RTC state changes. Thus the user sees the heat stress in a warm room.
  - Acceptance test: Host tests cover reference values of the NOAA table (for example 32 C and 70 %RH), the limits 26.7 C and 40 %RH, both corrections, NaN, infinite and out-of-range input, the dashes for a stale and an empty slot, and the text bounds with a 12-glyph name. `docs/display_preview_stats.png` shows one value.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components and firmware modules does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
