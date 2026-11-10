#pragma once

#include <stddef.h>
#include <stdint.h>

namespace rlcd_panel {

struct Shtc3Reading {
  bool valid = false;
  float temperature_c = 0.0f;
  float humidity_pct = 0.0f;
};

class Shtc3Codec {
public:
  // Calibration offset to compensate for ESP32-S3 PCB and SoC self-heating.
  static constexpr float DEFAULT_TEMP_OFFSET_C = -1.9f;

  // Sensirion CRC-8: polynomial 0x31, initialization 0xFF
  static uint8_t computeCrc8(const uint8_t* data, size_t len) {
    uint8_t crc = 0xFF;
    for (size_t i = 0; i < len; ++i) {
      crc ^= data[i];
      for (uint8_t bit = 0; bit < 8; ++bit) {
        if (crc & 0x80) {
          crc = static_cast<uint8_t>((crc << 1) ^ 0x31);
        } else {
          crc = static_cast<uint8_t>(crc << 1);
        }
      }
    }
    return crc;
  }

  // Decode 6-byte SHTC3 response: [T_MSB, T_LSB, T_CRC, RH_MSB, RH_LSB, RH_CRC]
  // Applies temperature calibration offset (default: DEFAULT_TEMP_OFFSET_C = -1.9 C).
  static bool decodeFrame(
      const uint8_t* frame,
      size_t len,
      Shtc3Reading& out,
      float temp_offset_c = DEFAULT_TEMP_OFFSET_C) {
    if (frame == nullptr || len < 6) {
      return false;
    }
    if (computeCrc8(frame, 2) != frame[2]) {
      return false;
    }
    if (computeCrc8(frame + 3, 2) != frame[5]) {
      return false;
    }

    const uint16_t raw_temp =
        (static_cast<uint16_t>(frame[0]) << 8) | static_cast<uint16_t>(frame[1]);
    const uint16_t raw_rh =
        (static_cast<uint16_t>(frame[3]) << 8) | static_cast<uint16_t>(frame[4]);

    const float uncalibrated_temp =
        -45.0f + (175.0f * static_cast<float>(raw_temp)) / 65536.0f;
    out.temperature_c = uncalibrated_temp + temp_offset_c;
    out.humidity_pct = (100.0f * static_cast<float>(raw_rh)) / 65536.0f;
    out.valid = true;
    return true;
  }
};

} // namespace rlcd_panel
