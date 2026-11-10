# rlcd-panel

Standalone `Arduino CLI` (`ESP32-S3`) firmware for the **Waveshare ESP32-S3-RLCD-4.2** reflective LCD panel (`B0GF23YC3Y`) that directly receives BLE advertisements from up to three **SwitchBot IP65 Indoor/Outdoor Thermo-Hygrometers** (`WoIOSensor`, `B0DXPHZMMP`) and reads the onboard `SHTC3` climate sensor.

## Target Hardware

| Component | Model / ASIN | Details |
| :--- | :--- | :--- |
| **Display & MCU Board** | Waveshare `ESP32-S3-RLCD-4.2` (`B0GF23YC3Y`) | `ESP32-S3-WROOM-1-N16R8` (16 MB Flash, 8 MB Octal PSRAM), 4.2" `ST7305` RLCD (400×300, 1-bpp monochrome, no backlight), onboard `SHTC3`, `PCF85063A` RTC, `ES8311` + `ES7210` audio, `MicroSD`, 18650 charger |
| **External BLE Sensors (3×)** | SwitchBot IP65 Indoor/Outdoor Thermo-Hygrometer (`WoIOSensor`, `B0DXPHZMMP`) | Unencrypted BLE advertisements (`Company ID 0x0969` Manufacturer Data + `UUID 0xFD3D` Service Data) |

## Internal Onboard GPIO Mapping (`ESP32-S3-RLCD-4.2`)

| Bus / Peripheral | Chip | Signal | GPIO | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **RLCD Display (SPI)** | `ST7305` | `SCK` / `MOSI` / `CS` / `DC` / `RST` / `TE` | `GPIO11` / `GPIO12` / `GPIO40` / `GPIO5` / `GPIO41` / `GPIO6` | 400×300 monochrome (15,000-byte framebuffer, 2×4 block byte packing) |
| **Shared I²C Bus** | `SHTC3` (`0x70`), `PCF85063A` (`0x51`), `ES8311` (`0x18`), `ES7210` (`0x40`) | `SDA` / `SCL` | `GPIO13` / `GPIO14` | Shared internal and rear-header I²C bus |
| **MicroSD Card** | `SD_MMC` (1-bit mode) | `CLK` / `CMD` / `D0` | `GPIO38` / `GPIO21` / `GPIO39` | Call `SD_MMC.setPins(38, 21, 39)` before `SD_MMC.begin("/sdcard", true)` |
| **Audio I²S** | `ES8311` / `ES7210` | `DOUT` / `BCLK` / `DIN` / `MCLK` / `LRCLK` / `PA_CTRL` | `GPIO8` / `GPIO9` / `GPIO10` / `GPIO16` / `GPIO45` / `GPIO46` | Speaker DAC and dual-microphone array |
| **Buttons & Battery** | Onboard | `BOOT` / `KEY` / `BAT_ADC` | `GPIO0` / `GPIO18` / `GPIO4` | Buttons active-low; battery voltage divider `3.0×` |

## Rear 2×8 Expansion Header Layout

### Left Header Side (Pins 1–8, Row 1)

| Pin | Label | ESP32-S3 Function |
| :--- | :--- | :--- |
| **1** | `VBUS` | 5 V USB Supply |
| **2** | `GND` | Ground |
| **3** | `GPIO19` | `USB_D-` |
| **4** | `GPIO20` | `USB_D+` |
| **5** | `GPIO43` | `U0TXD` |
| **6** | `GPIO44` | `U0RXD` |
| **7** | `GPIO13` | `I2C_SDA` |
| **8** | `GPIO14` | `I2C_SCL` |

### Right Header Side (Pins 1–8, Row 2)

| Pin | Label | ESP32-S3 Function |
| :--- | :--- | :--- |
| **1** | `3V3` | 3.3 V Regulated Supply |
| **2** | `GND` | Ground |
| **3** | `GPIO0` | `BOOT` (Strapping Pin, Active Low) |
| **4** | `GPIO1` | Free GPIO / `ADC1_CH0` |
| **5** | `GPIO2` | Free GPIO / `ADC1_CH1` |
| **6** | `GPIO3` | Free GPIO / `ADC1_CH2` |
| **7** | `GPIO17` | Free GPIO |
| **8** | `GPIO18` | `KEY` Button (Active Low) |

## Build, Test, Preview, and Flash Commands

### 1. Generate Display Preview Image

```bash
./scripts/generate-display-preview.sh
```
Renders a bit-exact 400×300 preview of the monochrome RLCD screen into `docs/display_preview.png`.

### 2. Run Host Unit Tests

```bash
c++ -std=c++17 -Wall -Wextra -Werror tests/test_firmware_logic.cpp -o test_runner && ./test_runner && rm -f test_runner
```

### 3. Compile Firmware with `arduino-cli`

```bash
arduino-cli compile rlcd_panel
```

### 4. Back Up Current / Factory Flash Before Flashing

```bash
./scripts/backup-firmware.sh
```

### 5. Flash Firmware to Board (with Pre-Flash Preview)

```bash
./scripts/flash-firmware.sh
```
