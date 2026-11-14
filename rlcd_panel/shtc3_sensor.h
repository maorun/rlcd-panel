#pragma once

#include <stddef.h>
#include <stdint.h>

namespace rlcd_panel {

struct Shtc3Reading {
  bool valid = false;
  float temperature_c = 0.0f;
  float humidity_pct = 0.0f;
};

// Result of one onboard SHTC3 read. NACK: an I2C command without an acknowledgement. SHORT: fewer
// than 6 bytes. CRC: a CRC-8 mismatch in the frame.
enum class Shtc3Result : uint8_t { OK = 0, NACK = 1, SHORT = 2, CRC = 3 };

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
  // Applies temperature calibration offset (default: DEFAULT_TEMP_OFFSET_C = -1.9 C) and a
  // humidity offset (default 0), with the humidity clamped to 0..100 %RH. The offset must be a
  // finite value; DeviceConfig::valid() and the config parser guarantee this.
  static bool decodeFrame(
      const uint8_t* frame,
      size_t len,
      Shtc3Reading& out,
      float temp_offset_c = DEFAULT_TEMP_OFFSET_C,
      float rh_offset_pct = 0.0f) {
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
    float rh = (100.0f * static_cast<float>(raw_rh)) / 65536.0f + rh_offset_pct;
    out.humidity_pct = rh < 0.0f ? 0.0f : (rh > 100.0f ? 100.0f : rh);
    out.valid = true;
    return true;
  }

  // Classifies one transfer. `acked` is false when a command got no acknowledgement; `received` is
  // the count of bytes read back. A good frame fills `out`; a failed read leaves it invalid.
  static Shtc3Result classify(
      bool acked,
      size_t received,
      const uint8_t* frame,
      Shtc3Reading& out,
      float temp_offset_c = DEFAULT_TEMP_OFFSET_C,
      float rh_offset_pct = 0.0f) {
    if (!acked) {
      return Shtc3Result::NACK;
    }
    if (received < 6 || frame == nullptr) {
      return Shtc3Result::SHORT;
    }
    return decodeFrame(frame, received, out, temp_offset_c, rh_offset_pct) ? Shtc3Result::OK
                                                                          : Shtc3Result::CRC;
  }
};

// Failed reads of the onboard SHTC3 by cause. Each counter saturates at 65535. `run` counts the
// failures in a row (saturates at 255); a good read sets it to 0.
struct Shtc3Health {
  static constexpr uint16_t COUNT_MAX = 65535;
  static constexpr uint8_t RUN_ALERT = 3; // the diagnostics text is inverted from this run on
  static constexpr size_t SERIALIZED_SIZE = 3 * 2 + 1;

  uint16_t nack = 0;
  uint16_t short_reads = 0;
  uint16_t crc = 0;
  uint8_t run = 0;

  void record(Shtc3Result result) {
    switch (result) {
      case Shtc3Result::OK:
        run = 0;
        return;
      case Shtc3Result::NACK:
        bump(nack);
        break;
      case Shtc3Result::SHORT:
        bump(short_reads);
        break;
      case Shtc3Result::CRC:
        bump(crc);
        break;
    }
    if (run < 255) {
      ++run;
    }
  }

  uint32_t total() const {
    return static_cast<uint32_t>(nack) + short_reads + crc;
  }

  bool alert() const {
    return run >= RUN_ALERT;
  }

  // Each failure in a row also counts in one counter, so `run` never exceeds the sum.
  bool consistent() const {
    return run <= total();
  }

private:
  static void bump(uint16_t& counter) {
    if (counter < COUNT_MAX) {
      ++counter;
    }
  }
};

} // namespace rlcd_panel
