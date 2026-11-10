#pragma once

#include <Arduino.h>
#include <SPI.h>
#include "board_pins.h"
#include "st7305_framebuffer.h"

namespace rlcd_panel {

class St7305Panel {
public:
  explicit St7305Panel(SPIClass& spi = SPI) : spi_(spi) {}

  void begin() {
    // Note: pinMode() must precede digitalWrite() on ESP32 core 3.3.12
    pinMode(PIN_RLCD_CS, OUTPUT);
    digitalWrite(PIN_RLCD_CS, HIGH);

    pinMode(PIN_RLCD_DC, OUTPUT);
    digitalWrite(PIN_RLCD_DC, HIGH);

    pinMode(PIN_RLCD_RST, OUTPUT);
    digitalWrite(PIN_RLCD_RST, HIGH);

    spi_.begin(PIN_RLCD_SCK, -1, PIN_RLCD_MOSI, PIN_RLCD_CS);
    resetHardware();
    initRegisters();
  }

  void flush(const St7305Framebuffer& fb) {
    // Column Address Set (0x12..0x2A = 25 column words x 12 cols = 300)
    sendCommand(0x2A);
    sendData(0x12);
    sendData(0x2A);

    // Row Address Set (0x00..0xC7 = 200 row pairs x 2 rows = 400)
    sendCommand(0x2B);
    sendData(0x00);
    sendData(0xC7);

    // Memory Write
    sendCommand(0x2C);
    spi_.beginTransaction(SPISettings(10000000, MSBFIRST, SPI_MODE0));
    digitalWrite(PIN_RLCD_DC, HIGH);
    digitalWrite(PIN_RLCD_CS, LOW);
    spi_.writeBytes(fb.data(), St7305Framebuffer::BYTE_COUNT);
    digitalWrite(PIN_RLCD_CS, HIGH);
    spi_.endTransaction();
  }

  void setLowPowerMode(bool enable) {
    sendCommand(enable ? 0x39 : 0x38);
  }

private:
  SPIClass& spi_;

  void resetHardware() {
    digitalWrite(PIN_RLCD_RST, HIGH);
    delay(20);
    digitalWrite(PIN_RLCD_RST, LOW);
    delay(50);
    digitalWrite(PIN_RLCD_RST, HIGH);
    delay(100);
  }

  void sendCommand(uint8_t cmd) {
    spi_.beginTransaction(SPISettings(10000000, MSBFIRST, SPI_MODE0));
    digitalWrite(PIN_RLCD_DC, LOW);
    digitalWrite(PIN_RLCD_CS, LOW);
    spi_.transfer(cmd);
    digitalWrite(PIN_RLCD_CS, HIGH);
    spi_.endTransaction();
  }

  void sendData(uint8_t data) {
    spi_.beginTransaction(SPISettings(10000000, MSBFIRST, SPI_MODE0));
    digitalWrite(PIN_RLCD_DC, HIGH);
    digitalWrite(PIN_RLCD_CS, LOW);
    spi_.transfer(data);
    digitalWrite(PIN_RLCD_CS, HIGH);
    spi_.endTransaction();
  }

  void initRegisters() {
    // NVM Load Control
    sendCommand(0xD6);
    sendData(0x17);
    sendData(0x02);

    // Booster Enable
    sendCommand(0xD1);
    sendData(0x01);

    // Gate Voltage Control
    sendCommand(0xC0);
    sendData(0x0E);
    sendData(0x0A);

    // VSHP / VSLP / VSHN / VSLN Settings
    sendCommand(0xC1);
    sendData(0x41);
    sendData(0x41);
    sendData(0x41);
    sendData(0x41);

    sendCommand(0xC2);
    sendData(0x32);
    sendData(0x32);
    sendData(0x32);
    sendData(0x32);

    sendCommand(0xC4);
    sendData(0x46);
    sendData(0x46);
    sendData(0x46);
    sendData(0x46);

    sendCommand(0xC5);
    sendData(0x46);
    sendData(0x46);
    sendData(0x46);
    sendData(0x46);

    // Oscillator & Frame Rate Control
    sendCommand(0xD8);
    sendData(0xA6);
    sendData(0xE9);

    sendCommand(0xB2);
    sendData(0x12);

    // Update Period Gate EQ Control (HPM / LPM)
    sendCommand(0xB3);
    const uint8_t b3_params[10] = {0xE5, 0xF6, 0x05, 0x46, 0x77, 0x77, 0x77, 0x77, 0x76, 0x45};
    for (uint8_t b : b3_params) {
      sendData(b);
    }

    sendCommand(0xB4);
    const uint8_t b4_params[8] = {0x05, 0x46, 0x77, 0x77, 0x77, 0x77, 0x76, 0x45};
    for (uint8_t b : b4_params) {
      sendData(b);
    }

    // Gate Line Setting (0x64 = 100 gate lines -> 400 rows)
    sendCommand(0xB0);
    sendData(0x64);

    // Sleep Out
    sendCommand(0x11);
    delay(120);

    // Memory Data Access Control, Data Format (1-bpp), Gamma Mode, Panel Setting
    sendCommand(0x36);
    sendData(0x48);

    sendCommand(0x3A);
    sendData(0x11);

    sendCommand(0xB9);
    sendData(0x20);

    sendCommand(0xB8);
    sendData(0x29);

    // High Power Mode + Display ON
    sendCommand(0x38);
    sendCommand(0x29);
    delay(20);
  }
};

} // namespace rlcd_panel
