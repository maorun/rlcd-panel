#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

namespace rlcd_panel {

// SwitchBot BLE Company ID (0x0969, little-endian bytes: 0x69, 0x09)
constexpr uint16_t SWITCHBOT_COMPANY_ID = 0x0969;
// SwitchBot BLE Service Data UUID (0xFD3D)
constexpr uint16_t SWITCHBOT_SERVICE_UUID = 0xFD3D;

constexpr size_t MAX_SWITCHBOT_SENSORS = 3;
// A sensor is stale when no packet arrived for 30 minutes.
constexpr uint32_t SENSOR_STALE_TIMEOUT_MS = 1800000;

struct SwitchBotReading {
  bool valid = false;
  uint8_t mac[6] = {0, 0, 0, 0, 0, 0};
  float temperature_c = 0.0f;
  uint8_t humidity_pct = 0;
  int8_t battery_pct = -1; // -1 if not present in the packet
  bool fahrenheit_mode = false;
  int rssi = 0;
  uint32_t updated_ms = 0;
  uint32_t packet_count = 0; // advertisements received from this sensor since power-on

  // Unsigned subtraction keeps the result correct when millis() wraps around.
  bool isStale(uint32_t now_ms, uint32_t timeout_ms = SENSOR_STALE_TIMEOUT_MS) const {
    return valid && (now_ms - updated_ms) > timeout_ms;
  }
};

class SwitchBotBleParser {
public:
  // Decode Manufacturer Specific Data buffer starting with the 2-byte little-endian Company ID (0x69, 0x09)
  // followed by the 11+ byte WoIOSensor payload:
  //   Payload[0..5]: Device MAC (6 bytes)
  //   Payload[8]:    Decimal temperature in low nibble (bits 3..0, 0.1 C steps)
  //   Payload[9]:    Bit 7 = sign (1 = positive, 0 = negative), Bits 6..0 = integer temperature (C)
  //   Payload[10]:   Bit 7 = unit flag (0 = C, 1 = F), Bits 6..0 = relative humidity (0..100%)
  static bool parseManufacturerData(
      const uint8_t* data,
      size_t len,
      SwitchBotReading& out) {
    out = SwitchBotReading{};
    if (data == nullptr || len < 13) {
      return false;
    }

    const uint16_t company_id =
        static_cast<uint16_t>(data[0]) | (static_cast<uint16_t>(data[1]) << 8);
    if (company_id != SWITCHBOT_COMPANY_ID) {
      return false;
    }

    const uint8_t* payload = data + 2;
    const uint8_t frac_part = payload[8] & 0x0F;
    if (frac_part > 9) {
      return false;
    }

    const bool is_positive = (payload[9] & 0x80) != 0;
    const uint8_t int_part = payload[9] & 0x7F;
    const uint8_t humidity = payload[10] & 0x7F;
    if (humidity > 100) {
      return false;
    }

    float temp = static_cast<float>(int_part) + (static_cast<float>(frac_part) * 0.1f);
    if (!is_positive && (int_part != 0 || frac_part != 0)) {
      temp = -temp;
    }

    memcpy(out.mac, payload, 6);
    out.temperature_c = temp;
    out.humidity_pct = humidity;
    out.fahrenheit_mode = (payload[10] & 0x80) != 0;
    out.valid = true;
    return true;
  }

  // Decode optional SwitchBot Service Data payload (UUID 0xFD3D, after UUID bytes):
  //   ServicePayload[0]: Device type (e.g. 0x77 / 'w' for WoIOSensor or 0x69 / 'i')
  //   ServicePayload[2]: Bits 6..0 = Battery percentage (0..100%)
  static bool parseServiceDataBattery(
      uint16_t service_uuid,
      const uint8_t* service_payload,
      size_t len,
      int8_t& out_battery_pct) {
    if (service_uuid != SWITCHBOT_SERVICE_UUID || service_payload == nullptr || len < 3) {
      return false;
    }
    const uint8_t batt = service_payload[2] & 0x7F;
    if (batt > 100) {
      return false;
    }
    out_battery_pct = static_cast<int8_t>(batt);
    return true;
  }
};

class SwitchBotRegistry {
public:
  bool upsert(const SwitchBotReading& reading) {
    if (!reading.valid) {
      return false;
    }

    // 1. Update existing sensor slot with matching MAC
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (slots_[i].valid && memcmp(slots_[i].mac, reading.mac, 6) == 0) {
        int8_t prev_battery = slots_[i].battery_pct;
        const uint32_t prev_count = slots_[i].packet_count;
        slots_[i] = reading;
        slots_[i].packet_count = prev_count + 1;
        if (slots_[i].battery_pct < 0 && prev_battery >= 0) {
          slots_[i].battery_pct = prev_battery;
        }
        return true;
      }
    }

    // 2. Allocate first free sensor slot
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (!slots_[i].valid) {
        slots_[i] = reading;
        slots_[i].packet_count = 1;
        return true;
      }
    }

    return false;
  }

  const SwitchBotReading& slot(size_t index) const {
    static const SwitchBotReading kEmptyReading{};
    if (index >= MAX_SWITCHBOT_SENSORS) {
      return kEmptyReading;
    }
    return slots_[index];
  }

  // Restores one slot from persisted state. Rejects invalid readings and MACs held by another slot.
  bool restoreSlot(size_t index, const SwitchBotReading& reading) {
    if (index >= MAX_SWITCHBOT_SENSORS || !reading.valid) {
      return false;
    }
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (i != index && slots_[i].valid && memcmp(slots_[i].mac, reading.mac, 6) == 0) {
        return false;
      }
    }
    slots_[index] = reading;
    return true;
  }

  size_t activeCount() const {
    size_t count = 0;
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (slots_[i].valid) {
        ++count;
      }
    }
    return count;
  }

private:
  SwitchBotReading slots_[MAX_SWITCHBOT_SENSORS];
};

} // namespace rlcd_panel
