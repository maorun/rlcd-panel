#pragma once

#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "switchbot_ble.h"

namespace rlcd_panel {

// Environmental Sensing Service Data UUID (0x181A) of the Xiaomi thermometers with custom firmware
constexpr uint16_t ATC_SERVICE_UUID = 0x181A;
constexpr size_t ATC_PAYLOAD_LEN = 13;
constexpr size_t PVVX_PAYLOAD_LEN = 15;
// Plausible temperature range. Sentinel values of other sensors fall outside it and are rejected.
constexpr float ATC_MIN_TEMPERATURE_C = -50.0f;
constexpr float ATC_MAX_TEMPERATURE_C = 100.0f;

class AtcBleParser {
public:
  // Decode Service Data (the bytes after the 2-byte UUID 0x181A) of a Xiaomi LYWSD03MMC or similar
  // sensor with custom firmware. The length selects the format.
  //   ATC, 13 bytes:  MAC (6, big-endian), temperature int16 big-endian (0.1 C), humidity uint8 (%),
  //                   battery uint8 (%), battery mV uint16 big-endian, counter uint8.
  //   pvvx, 15 bytes: MAC (6, little-endian), temperature int16 little-endian (0.01 C),
  //                   humidity uint16 little-endian (0.01 %), battery mV uint16 little-endian,
  //                   battery uint8 (%), counter uint8, flags uint8.
  // The reading holds the MAC in big-endian order, as the other parsers do. The parser rejects other
  // lengths, a temperature outside -50 C to 100 C, a humidity above 100 %RH and a battery above 100 %.
  static bool parseServiceData(
      uint16_t service_uuid,
      const uint8_t* payload,
      size_t len,
      SwitchBotReading& out) {
    out = SwitchBotReading{};
    if (service_uuid != ATC_SERVICE_UUID || payload == nullptr) {
      return false;
    }

    uint8_t mac[6];
    float temperature_c;
    float humidity_pct;
    uint8_t battery_pct;
    if (len == ATC_PAYLOAD_LEN) {
      memcpy(mac, payload, 6);
      temperature_c = static_cast<float>(readInt16(payload[6], payload[7])) / 10.0f;
      humidity_pct = static_cast<float>(payload[8]);
      battery_pct = payload[9];
    } else if (len == PVVX_PAYLOAD_LEN) {
      for (size_t i = 0; i < 6; ++i) {
        mac[i] = payload[5 - i];
      }
      temperature_c = static_cast<float>(readInt16(payload[7], payload[6])) / 100.0f;
      humidity_pct = static_cast<float>(static_cast<uint16_t>(payload[8] | (payload[9] << 8))) / 100.0f;
      battery_pct = payload[12];
    } else {
      return false;
    }

    if (temperature_c < ATC_MIN_TEMPERATURE_C || temperature_c > ATC_MAX_TEMPERATURE_C ||
        humidity_pct > 100.0f || battery_pct > 100) {
      return false;
    }

    memcpy(out.mac, mac, 6);
    out.temperature_c = temperature_c;
    out.humidity_pct = static_cast<uint8_t>(lroundf(humidity_pct));
    out.battery_pct = static_cast<int8_t>(battery_pct);
    out.valid = true;
    return true;
  }

private:
  static int16_t readInt16(uint8_t high, uint8_t low) {
    return static_cast<int16_t>((static_cast<uint16_t>(high) << 8) | low);
  }
};

} // namespace rlcd_panel
