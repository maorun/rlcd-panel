# AGENTS.md

## Project Overview

`rlcd-panel` contains the standalone `Arduino CLI` (`esp32:esp32@3.3.12`) firmware for the **Waveshare ESP32-S3-RLCD-4.2** (`B0GF23YC3Y`) reflective LCD panel. The device scans BLE advertisements from up to three **SwitchBot IP65 Indoor/Outdoor Thermo-Hygrometers** (`WoIOSensor`, `B0DXPHZMMP`), reads the onboard `SHTC3` sensor over I²C, and renders a 4-tile climate dashboard onto the 400×300 `ST7305` RLCD screen.

## Repository Structure

- `rlcd_panel/sketch.yaml`: Pinned `arduino-cli` build profile `rlcd_panel_s3` (`esp32:esp32:esp32s3:CDCOnBoot=cdc,FlashSize=16M,PSRAM=opi,PartitionScheme=app3M_fat9M_16MB` on `esp32:esp32@3.3.12`).
- `rlcd_panel/board_pins.h`: Hardware GPIO constants and I²C addresses for `ESP32-S3-RLCD-4.2`.
- `rlcd_panel/battery_monitor.h`: 18650 Li-ion battery voltage and percentage decoder (`GPIO4` ADC1_CH3 with 3.0× divider).
- `rlcd_panel/battery_monitor.h` also holds `CycleTracker` (charge cycle counter: 100 points of discharge are one cycle; NVS key `cycle_count`, one write per charge event or whole cycle) and `CycleStore`.
- `rlcd_panel/switchbot_ble.h`: Header-only BLE parser (`Company ID 0x0969` Manufacturer Data + `UUID 0xFD3D` Service Data) and 3-slot sensor registry. `SwitchBotRegistry::anyStale` feeds `ALERT_SENSOR_LOST` (one 440 Hz note when a valid slot is stale for 30 min).
- `rlcd_panel/sparkline.h`: Header-only 24-hour relative humidity trend: `HumidityHistory` (24 hourly samples, 0.1 %RH steps, 0 to 100 %RH), `ClimateHistory` (onboard + 3 SwitchBot slots, hourly gate) and the `Sparkline` scaler and renderer (`MIN_SPAN` 5.0 %RH).
- `rlcd_panel/st7305_framebuffer.h`: Header-only 400×300 1-bpp framebuffer (`15,000` bytes) with `ST7305` 2×4 block pixel packing and 5×7 text primitives.
- `rlcd_panel/st7305_panel.h`: ESP32-S3 hardware SPI driver for the `ST7305` RLCD controller.
- `rlcd_panel/shtc3_sensor.h`: Sensirion `SHTC3` CRC-8 (`0x31`) and raw frame decoder.
- `rlcd_panel/power_manager.h`: Header-only deep sleep manager: wakeup timer maths, wakeup cause decoding, wake cycle state machine, and checksummed RTC-memory state (registry, battery, view mode).
- `rlcd_panel/device_config.h`: Header-only `config.ini` parser (`DeviceConfigParser`, `DeviceConfig`) for the settings from the MicroSD card: ranges, defaults, 4096-byte limit, including the sensor names `onboard_name` and `slot1_name` to `slot3_name`, `log_retention_months`, `noise_alert_limit_db`, `chime_alert_mask` (0 to 127; bit 5 is `ALERT_VENTILATE`, bit 6 is `ALERT_SENSOR_BATTERY`), `sensor_battery_limit_pct` (0 to 50, default 10, 0 is off; `SensorBatteryLatch` in `switchbot_ble.h` sets a per-slot latch at or below the limit, releases at limit + 5 %, and drives the inverted `BAT LOW` text in the tile), `outdoor_slot` (0 is off, 1 to 3 selects the outdoor registry slot of the ventilation advice), `ventilate_limit_rh` (50 to 80 %RH, default 60; `VentilateLatch` in `ventilate_latch.h` drives the `V` badge on a tile) and `sensor_stale_min` (5 to 240 min, default 30; `DeviceConfig::staleTimeoutMs()` raises it to `wake_interval_s` + 4 s and feeds each `isStale` call) and `wake_interval_s` (30 to 900 s; `PowerManager::powerConfig` passes it to `PowerConfig::sleep_interval_s` on battery power, USB power keeps 60 s). `ConfigRejects` holds the count of rejected lines (saturates at 255) and the line numbers of the first four; unknown keys are no errors. The values and the rejects live in `PersistentState` (RTC memory); only a power-on and a `KEY` wakeup mount the card. Never read Wi-Fi credentials from the card.
- `rlcd_panel/sd_logger.h`: Header-only CSV logger (`SdLogCodec`, `SdLogger`). Format version 4: 21 columns (climate plus `noise_rms`, `noise_db`, `noise_band`, `noise_events`, `noise_l10`, `noise_l90`), file `/sdcard/climate_v4_YYYYMM.csv`; the old `climate_v3_YYYYMM.csv` and `climate_v2_YYYYMM.csv` files stay unchanged, and the console and the retention still handle them. `DailyPlanner` and `SdLogger::appendDaily` write one summary row per day (27 columns, min/max/mean of the 24-hour histories) to `/sdcard/daily_v1_YYYY.csv` at the first hourly write after local midnight; the stamp lives in `PersistentState::daily`. After each hourly write the logger deletes only matching files older than `log_retention_months` (never the current month; a daily file counts as December of its year) and reads the free space for the diagnostics view. The wake cycle that writes the hourly record runs one 5 s `NoiseSampler` window (50 blocks of 100 ms give `noise_l10` and `noise_l90`), also after a timer wakeup; a low battery alert skips it. Other timer wakeups keep the microphones off, except on USB power, where the clap listener runs.
- `rlcd_panel/audio_loopback.h`: Header-only speaker to microphone loopback test: `GoertzelFilter`, `LoopbackDetector` (1 kHz, pass at -60 dBFS and 50 % tone share), `AudioLoopback::run` (skips while muted, in quiet hours and with volume 0; powers down in the order amplifier, `ES8311`, `ES7210`, bus on every path) and the ESP32 `LoopbackHal`. A `BOOT` long press in the diagnostics view starts it (`BootButtonController`).
- `rlcd_panel/clap_detector.h`: Header-only double-clap detection: `TransientCounter` (counts the transients of the 5 s `NoiseSampler` window with the same detector front end, so `NoiseReading::events` needs no longer microphone time), `ClapDetector` (transient rise 20 dB in under 5 ms, decay under 100 ms, 150 ms to 600 ms between two claps, third clap ignored) and `ClapListener` (20 ms audio frames over the `ES7210` HAL). Only an awake board listens: continuously on USB power, in the 3 s `KEY` grace window on battery. `PowerManager::clapListenMode` sets the gate.
- `rlcd_panel/serial_console.h`: Header-only USB serial console: `SerialConsole` parser (`LIST`, `INFO`, `STATUS` (live sensor snapshot from `g_state`, `SerialStatusSnapshot`), `CONFIG` with `REJECTED line=N` lines, `DUMP climate_v4_YYYYMM.csv` (or `climate_v3_YYYYMM.csv` or `climate_v2_YYYYMM.csv`) or `DUMP daily_v1_YYYY.csv`), `SerialLineAssembler`, `SerialCrc32` and `SerialConsoleRunner` (mounts the card for one command; `BEGIN` line, raw bytes, `CRC32` line). Commands run on USB power only (`PowerManager::serialConsoleAllowed`).
- `rlcd_panel/ntp_sync.h`: Wi-Fi NTP sync: epoch-to-calendar and CET/CEST converter, `PCF85063A` register encoding, sync schedule with backoff, and the ESP32 `NtpClient`. Optional credentials come from the ignored `rlcd_panel/wifi_config.h` (template: `wifi_config.example.h`).
- `rlcd_panel/es7210_mic.h`: Header-only ambient noise monitor (also used for the hourly CSV noise columns): RMS accumulator, per-channel `MicChannelStats` and `StereoAnalyzer` (microphone self-test: `OK`, `SILENT`, `STUCK`, `DC`, `CLIP`, imbalance above 10 dB; both channels failed gives an invalid reading with no band), noise band classifier with hysteresis, 5 s `NoiseSampler`, and the ESP32 `Es7210Hal` (`ES7210` over I²C `0x40`, I²S receive).
- `rlcd_panel/audio_spectrum.h`: Header-only audio spectrum: `SpectrumAnalyzer` (512-point radix-2 FFT with Hann window on the mono mix, 32 logarithmic bands from 100 Hz to 8 kHz, bars 0 to 100 with peak markers) and `SpectrumCapture` (1 s window; ADC powered down on every path). The `AUDIO_SPECTRUM` view captures on entry; a short `KEY` press returns `ButtonEvent::RECAPTURE` and captures again, and a hold of 2 s changes the view; timer wakeups keep the microphones off.
- `rlcd_panel/dashboard_view.h`: Decoupled 4-tile climate dashboard renderer shared between firmware and preview generator.
- `rlcd_panel/rlcd_panel.ino`: Main firmware entry point (`setup()` / `loop()`).
- `scripts/backup-firmware.sh`: Backs up NVS (`0x9000`, `20 KB`) and full flash (`0x0`, `16 MB`) via `esptool` into `firmware_backups/` (mode `0700`).
- `scripts/generate-display-preview.sh`: Compiles and executes the bit-exact framebuffer renderer, generating `docs/display_preview.png` (dashboard), `display_preview_dashboard.png`, `display_preview_stats.png`, `display_preview_diagnostics.png`, `display_preview_spectrum.png`, `display_preview_low_battery.png`, `display_preview_quiet_hours.png`, and `display_preview_ventilate.png`.
- `scripts/dump-logs.sh`: Copies the monthly CSV logs over USB serial into the ignored directory `sd_logs/` and checks the CRC-32.
- `scripts/flash-firmware.sh`: Mandatory flash script that generates the display preview image first, compiles, and uploads to the board.
- `tools/render_display_preview.cpp`: Host C++ tool that renders one view (`<out.bmp> [mode 0..3] [low]`) or every scene (`--all <dir>`) and exports 400×300 24-bpp BMP.
- `tools/preview_scenes.h`: Shared preview scene table (`PREVIEW_SCENES`) and fixed sample data, used by the tool and the host tests.
- `tests/test_firmware_logic.cpp`: Host C++17 unit test suite covering BLE packet parsing, registry management, framebuffer bit mapping, `SHTC3` CRC-8 decoding, and dashboard rendering.

## Hardware & Firmware Invariants

1. **Pre-Flash Backup:** Run `./scripts/backup-firmware.sh` to dump NVS and the full 16 MB flash before the first flash upload.
2. **Pre-Flash Preview Mandatory:** Always generate and inspect `docs/display_preview.png` via `./scripts/generate-display-preview.sh` before flashing firmware to the board.
3. **GPIO Output Ordering on ESP32 Core 3.3.12:** Always call `pinMode(pin, OUTPUT)` before `digitalWrite(pin, level)`.
4. **ST7305 Framebuffer Packing:** The 400×300 display uses `15,000` bytes with column-major block packing and inverted Y (`byte_index = (x / 2) * 75 + ((299 - y) / 4)`, `bit = 7 - (((299 - y) % 4) * 2 + (x % 2))`).
5. **MicroSD Card (`SD_MMC`):** Always call `SD_MMC.setPins(38, 21, 39)` before `SD_MMC.begin("/sdcard", true)`.
6. **Build Cleanliness:** Do not commit `build/`, `rlcd_panel/build/`, `firmware_backups/`, or `test_runner` binaries.
7. **Deep Sleep Cycle:** `setup()` runs one wake cycle (restore, scan, render, save, `esp_deep_sleep_start()`); `loop()` never runs. Use the RTC-backed `nowMs()` for timestamps, not `millis()`, which restarts after sleep. Call `PowerPlatform::releasePinsAfterSleep()` first in `setup()`: it frees the KEY pin and the held display pins (`CS`, `DC`, `RST`) after they were driven high.
8. **SHTC3 Calibration Offset:** The onboard SHTC3 temperature sensor is subject to PCB self-heating and applies a default calibration offset of `-1.9 °C` (`Shtc3Codec::DEFAULT_TEMP_OFFSET_C`).

## Verification Commands

- **Display Preview Generation:**
  ```bash
  ./scripts/generate-display-preview.sh
  ```
- **Host Unit Tests:**
  ```bash
  c++ -std=c++17 -Wall -Wextra -Werror tests/test_firmware_logic.cpp -o test_runner && ./test_runner && rm -f test_runner
  ```
- **Firmware Compile Check:**
  ```bash
  arduino-cli compile rlcd_panel
  ```
- **Firmware Flash (with Preview):**
  ```bash
  ./scripts/flash-firmware.sh
  ```
