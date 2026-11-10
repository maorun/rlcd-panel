# AGENTS.md

## Project Overview

`rlcd-panel` contains the standalone `Arduino CLI` (`esp32:esp32@3.3.12`) firmware for the **Waveshare ESP32-S3-RLCD-4.2** (`B0GF23YC3Y`) reflective LCD panel. The device scans BLE advertisements from up to three **SwitchBot IP65 Indoor/Outdoor Thermo-Hygrometers** (`WoIOSensor`, `B0DXPHZMMP`), reads the onboard `SHTC3` sensor over I²C, and renders a 4-tile climate dashboard onto the 400×300 `ST7305` RLCD screen.

## Repository Structure

- `rlcd_panel/sketch.yaml`: Pinned `arduino-cli` build profile `rlcd_panel_s3` (`esp32:esp32:esp32s3:CDCOnBoot=cdc,FlashSize=16M,PSRAM=opi,PartitionScheme=app3M_fat9M_16MB` on `esp32:esp32@3.3.12`).
- `rlcd_panel/board_pins.h`: Hardware GPIO constants and I²C addresses for `ESP32-S3-RLCD-4.2`.
- `rlcd_panel/switchbot_ble.h`: Header-only BLE parser (`Company ID 0x0969` Manufacturer Data + `UUID 0xFD3D` Service Data) and 3-slot sensor registry.
- `rlcd_panel/st7305_framebuffer.h`: Header-only 400×300 1-bpp framebuffer (`15,000` bytes) with `ST7305` 2×4 block pixel packing and 5×7 text primitives.
- `rlcd_panel/st7305_panel.h`: ESP32-S3 hardware SPI driver for the `ST7305` RLCD controller.
- `rlcd_panel/shtc3_sensor.h`: Sensirion `SHTC3` CRC-8 (`0x31`) and raw frame decoder.
- `rlcd_panel/rlcd_panel.ino`: Main firmware entry point (`setup()` / `loop()`).
- `scripts/backup-firmware.sh`: Backs up NVS (`0x9000`, `20 KB`) and full flash (`0x0`, `16 MB`) via `esptool` into `firmware_backups/` (mode `0700`).
- `tests/test_firmware_logic.cpp`: Host C++17 unit test suite covering BLE packet parsing, registry management, framebuffer bit mapping, and `SHTC3` CRC-8 decoding.

## Hardware & Firmware Invariants

1. **Pre-Flash Backup:** Run `./scripts/backup-firmware.sh` to dump NVS and the full 16 MB flash before the first flash upload.
2. **GPIO Output Ordering on ESP32 Core 3.3.12:** Always call `pinMode(pin, OUTPUT)` before `digitalWrite(pin, level)`.
3. **ST7305 Framebuffer Packing:** The 400×300 display uses `15,000` bytes where each byte represents a 2-column × 4-row block (`byte_index = (y / 4) * 200 + (x / 2)`, `bit = 7 - ((y % 4) * 2 + (x % 2))`).
4. **MicroSD Card (`SD_MMC`):** Always call `SD_MMC.setPins(38, 21, 39)` before `SD_MMC.begin("/sdcard", true)`.
5. **Build Cleanliness:** Do not commit `build/`, `rlcd_panel/build/`, `firmware_backups/`, or `test_runner` binaries.

## Verification Commands

- **Host Unit Tests:**
  ```bash
  c++ -std=c++17 -Wall -Wextra -Werror tests/test_firmware_logic.cpp -o test_runner && ./test_runner && rm -f test_runner
  ```
- **Firmware Compile Check:**
  ```bash
  arduino-cli compile rlcd_panel
  ```
