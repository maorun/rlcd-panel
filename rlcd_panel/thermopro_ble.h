#pragma once

#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "switchbot_ble.h"

namespace rlcd_panel {

// Header byte of ThermoPro Bluetooth Low Energy manufacturer data frames.
constexpr uint8_t THERMOPRO_HEADER_BYTE = 0xC2;
constexpr size_t THERMOPRO_MIN_PAYLOAD_LEN = 5;
// Plausible temperature range in degrees Celsius. Sentinel or out-of-bounds values are rejected.
constexpr float THERMOPRO_MIN_TEMPERATURE_C = -50.0f;
constexpr float THERMOPRO_MAX_TEMPERATURE_C = 100.0f;

class ThermoProBleParser {
public:
  // Decodes Manufacturer Specific Data of ThermoPro BLE sensors (such as TP357, TP357S, TP358, TP359, TP393).
  // The sensor broadcasts:
  //   Payload[0]:     Header marker 0xC2.
  //   Payload[1..2]:  Temperature as a signed 16-bit integer, little-endian, scaled by 10 (0.1 C steps).
  //   Payload[3]:     Relative humidity as uint8 percentage (0..100 %RH).
  //   Payload[4]:     Battery / status byte (bar level 0..2 or percentage 0..100).
  //   Payload[5..]:   Optional device counter / flags.
  //
  // The caller supplies the BLE address of the advertising device as `mac` (6 bytes, big-endian),
  // because ThermoPro manufacturer data frames do not carry the device MAC address.
  // `name` is optional: if non-null and not empty, it must start with "TP" / "tp" to be accepted.
  // Returns false if payload is null, too short, header mismatch, wrong name prefix, zero MAC,
  // or physical values out of range.
  static bool parseManufacturerData(
      const uint8_t* payload,
      size_t len,
      const uint8_t* mac,
      const char* name,
      SwitchBotReading& out) {
    out = SwitchBotReading{};
    if (payload == nullptr || len < THERMOPRO_MIN_PAYLOAD_LEN || mac == nullptr) {
      return false;
    }

    if (payload[0] != THERMOPRO_HEADER_BYTE) {
      return false;
    }

    if (!isMatchingName(name)) {
      return false;
    }

    static const uint8_t ZERO_MAC[6] = {0, 0, 0, 0, 0, 0};
    if (memcmp(mac, ZERO_MAC, 6) == 0) {
      return false;
    }

    const int16_t raw_temp = readInt16Le(payload[1], payload[2]);
    const float temperature_c = static_cast<float>(raw_temp) / 10.0f;
    if (temperature_c < THERMOPRO_MIN_TEMPERATURE_C || temperature_c > THERMOPRO_MAX_TEMPERATURE_C) {
      return false;
    }

    const uint8_t humidity_pct = payload[3];
    if (humidity_pct > 100) {
      return false;
    }

    const int8_t battery_pct = decodeBattery(payload[4]);

    out.valid = true;
    memcpy(out.mac, mac, 6);
    out.temperature_c = temperature_c;
    out.humidity_pct = humidity_pct;
    out.battery_pct = battery_pct;
    return true;
  }

  static bool isMatchingName(const char* name) {
    if (name == nullptr || name[0] == '\0') {
      return true; // No advertised name present, accept if payload matches signature
    }
    // Match "TP" or "tp" prefix (e.g. "TP357", "TP357S", "TP358", "TP359", "TP393")
    return (name[0] == 'T' || name[0] == 't') &&
           (name[1] == 'P' || name[1] == 'p');
  }

  static int8_t decodeBattery(uint8_t raw_byte) {
    // Check discrete bar level (0 = low / replace battery, 1 = medium, 2 = full).
    // Some firmware reports raw bars (0..2), others encode it in the lower nibble with high nibble 0x20.
    const uint8_t bar = raw_byte & 0x0F;
    if (raw_byte <= 2 || ((raw_byte & 0xF0) == 0x20 && bar <= 2)) {
      if (bar == 2) {
        return 100;
      }
      if (bar == 1) {
        return 50;
      }
      return 10; // Low battery indicator threshold
    }
    // If reported as a direct percentage 3..100 %
    if (raw_byte <= 100) {
      return static_cast<int8_t>(raw_byte);
    }
    return -1;
  }

private:
  static int16_t readInt16Le(uint8_t low, uint8_t high) {
    return static_cast<int16_t>(
        static_cast<uint16_t>(low) | (static_cast<uint16_t>(high) << 8));
  }
};

} // namespace rlcd_panel
