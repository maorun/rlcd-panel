#pragma once

#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "switchbot_ble.h"

namespace rlcd_panel {

// BTHome v2 Service Data UUID (0xFCD2)
constexpr uint16_t BTHOME_SERVICE_UUID = 0xFCD2;
// Plausible temperature range. Sentinel values of other sensors fall outside it and are rejected.
constexpr float BTHOME_MIN_TEMPERATURE_C = -50.0f;
constexpr float BTHOME_MAX_TEMPERATURE_C = 100.0f;

class BtHomeBleParser {
public:
  // Decode BTHome v2 Service Data (the bytes after the 2-byte UUID):
  //   Payload[0]:  Device info. Bit 0 = encrypted, bits 7..5 = version (must be 2).
  //   Payload[1..]: Objects, each one object ID byte followed by a fixed-length value (little-endian):
  //     0x01 battery,      uint8,  1 %
  //     0x02 temperature,  sint16, 0.01 C
  //     0x03 humidity,     uint16, 0.01 %
  //     0x2E humidity,     uint8,  1 %
  // Encrypted packets are rejected. Objects with a known length but no use here are skipped. An object ID of
  // unknown length ends the parse, because the following bytes cannot be located; values decoded up to that
  // point stay valid. The reading is valid only when it holds a temperature and a humidity.
  // The caller supplies the sensor MAC address (BTHome packets do not carry one).
  static bool parseServiceData(
      uint16_t service_uuid,
      const uint8_t* mac,
      const uint8_t* payload,
      size_t len,
      SwitchBotReading& out) {
    out = SwitchBotReading{};
    if (service_uuid != BTHOME_SERVICE_UUID || mac == nullptr || payload == nullptr || len < 1) {
      return false;
    }

    const uint8_t device_info = payload[0];
    if ((device_info & 0x01) != 0 || (device_info >> 5) != 2) {
      return false;
    }

    bool have_temperature = false;
    bool have_humidity = false;
    float temperature_c = 0.0f;
    float humidity_pct = 0.0f;
    int8_t battery_pct = -1;

    size_t pos = 1;
    while (pos < len) {
      const uint8_t object_id = payload[pos++];
      const size_t value_len = objectLength(object_id);
      if (value_len == 0 || pos + value_len > len) {
        break; // unknown object ID or truncated value
      }
      const uint8_t* v = payload + pos;
      pos += value_len;

      switch (object_id) {
        case 0x01:
          if (v[0] <= 100) {
            battery_pct = static_cast<int8_t>(v[0]);
          }
          break;
        case 0x02: {
          const int16_t raw = static_cast<int16_t>(static_cast<uint16_t>(v[0]) | (static_cast<uint16_t>(v[1]) << 8));
          const float t = static_cast<float>(raw) * 0.01f;
          if (t >= BTHOME_MIN_TEMPERATURE_C && t <= BTHOME_MAX_TEMPERATURE_C) {
            temperature_c = t;
            have_temperature = true;
          }
          break;
        }
        case 0x03: {
          const uint16_t raw = static_cast<uint16_t>(v[0]) | (static_cast<uint16_t>(v[1]) << 8);
          if (raw <= 10000) {
            humidity_pct = static_cast<float>(raw) * 0.01f;
            have_humidity = true;
          }
          break;
        }
        case 0x2E:
          if (v[0] <= 100) {
            humidity_pct = static_cast<float>(v[0]);
            have_humidity = true;
          }
          break;
        default:
          break; // known length, value not used
      }
    }

    if (!have_temperature || !have_humidity) {
      return false;
    }

    memcpy(out.mac, mac, 6);
    out.temperature_c = temperature_c;
    out.humidity_pct = static_cast<uint8_t>(lroundf(humidity_pct));
    out.battery_pct = battery_pct;
    out.valid = true;
    return true;
  }

private:
  // Value length in bytes of the BTHome v2 object IDs this parser can step over. 0 = unknown or variable.
  static size_t objectLength(uint8_t id) {
    switch (id) {
      case 0x04: case 0x05: case 0x0A: case 0x0B: case 0x42: case 0x4B:
        return 3;
      case 0x3E: case 0x4C: case 0x4D: case 0x4E: case 0x4F: case 0x50: case 0x55: case 0x5B: case 0x5C:
        return 4;
      default:
        break;
    }
    if (id <= 0x01 || id == 0x09 || (id >= 0x0F && id <= 0x11) || (id >= 0x15 && id <= 0x2F) ||
        id == 0x3A || id == 0x46 || id == 0x57 || id == 0x58 || id == 0x59 || id == 0x60) {
      return 1;
    }
    if ((id >= 0x02 && id <= 0x03) || (id >= 0x06 && id <= 0x08) || (id >= 0x0C && id <= 0x0E) ||
        (id >= 0x12 && id <= 0x14) || id == 0x3C || id == 0x3D || (id >= 0x3F && id <= 0x41) ||
        (id >= 0x43 && id <= 0x45) || (id >= 0x47 && id <= 0x4A) || id == 0x51 || id == 0x52 ||
        id == 0x56 || id == 0x5A || id == 0x5D || id == 0x5E || id == 0x5F) {
      return 2;
    }
    return 0;
  }
};

} // namespace rlcd_panel
