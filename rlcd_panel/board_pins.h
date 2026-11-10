#pragma once

#include <stdint.h>

namespace rlcd_panel {

// ============================================================================
// Waveshare ESP32-S3-RLCD-4.2 (Amazon ASIN: B0GF23YC3Y) Hardware Pinout
// MCU: ESP32-S3-WROOM-1-N16R8 (16 MB Flash, 8 MB Octal PSRAM)
// ============================================================================

// --- 4.2-inch Reflective LCD (ST7305, 400x300 1-bpp monochrome, SPI) ---
constexpr uint8_t PIN_RLCD_SCK  = 11;
constexpr uint8_t PIN_RLCD_MOSI = 12;
constexpr uint8_t PIN_RLCD_CS   = 40;
constexpr uint8_t PIN_RLCD_DC   = 5;
constexpr uint8_t PIN_RLCD_RST  = 41;
constexpr uint8_t PIN_RLCD_TE   = 6;

constexpr uint16_t RLCD_WIDTH  = 400;
constexpr uint16_t RLCD_HEIGHT = 300;
constexpr uint32_t RLCD_BUFFER_SIZE = (RLCD_WIDTH * RLCD_HEIGHT) / 8; // 15,000 bytes

// --- Shared Onboard I2C Bus (SHTC3, PCF85063A RTC, ES8311 DAC, ES7210 ADC) ---
constexpr uint8_t PIN_I2C_SDA = 13;
constexpr uint8_t PIN_I2C_SCL = 14;

constexpr uint8_t I2C_ADDR_SHTC3    = 0x70;
constexpr uint8_t I2C_ADDR_PCF85063 = 0x51;
constexpr uint8_t I2C_ADDR_ES8311   = 0x18;
constexpr uint8_t I2C_ADDR_ES7210   = 0x40;

// --- Onboard MicroSD Slot (SD_MMC 1-bit mode) ---
constexpr uint8_t PIN_SDMMC_CLK = 38;
constexpr uint8_t PIN_SDMMC_CMD = 21;
constexpr uint8_t PIN_SDMMC_D0  = 39;

// --- Onboard Audio I2S Bus (ES8311 Speaker DAC + ES7210 Dual Mic ADC) ---
constexpr uint8_t PIN_I2S_DOUT  = 8;
constexpr uint8_t PIN_I2S_BCLK  = 9;
constexpr uint8_t PIN_I2S_DIN   = 10;
constexpr uint8_t PIN_I2S_MCLK  = 16;
constexpr uint8_t PIN_I2S_LRCLK = 45;
constexpr uint8_t PIN_PA_CTRL   = 46;

// --- Onboard Buttons (Active Low) and 18650 Battery ADC ---
constexpr uint8_t PIN_BTN_BOOT = 0;
constexpr uint8_t PIN_BTN_KEY  = 18;
constexpr uint8_t PIN_BAT_ADC  = 4;
constexpr float   BAT_VOLTAGE_DIVIDER_RATIO = 3.0f;

} // namespace rlcd_panel
