#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

namespace rlcd_panel {

// SwitchBot BLE Company ID (0x0969, little-endian bytes: 0x69, 0x09)
constexpr uint16_t SWITCHBOT_COMPANY_ID = 0x0969;
// SwitchBot BLE Service Data UUID (0xFD3D)
constexpr uint16_t SWITCHBOT_SERVICE_UUID = 0xFD3D;
// Device types in the first Service Data byte of the SwitchBot Meter and Meter Plus.
constexpr uint8_t SWITCHBOT_METER_TYPE = 0x54;
constexpr uint8_t SWITCHBOT_METER_PLUS_TYPE = 0x69;

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
  int8_t rssi = 0; // dBm of the last valid advertisement
  uint32_t updated_ms = 0;
  uint32_t packet_count = 0; // advertisements received from this sensor since power-on

  // Unsigned subtraction keeps the result correct when millis() wraps around.
  bool isStale(uint32_t now_ms, uint32_t timeout_ms = SENSOR_STALE_TIMEOUT_MS) const {
    return valid && (now_ms - updated_ms) > timeout_ms;
  }
};

// Release rule of a silent slot: true when a valid reading is at least `timeout_ms` old. `timeout_ms` 0 never
// releases. Unsigned subtraction keeps the result correct when the 32-bit clock wraps around.
inline bool slotExpired(const SwitchBotReading& r, uint32_t now_ms, uint32_t timeout_ms) {
  return timeout_ms != 0 && r.valid && (now_ms - r.updated_ms) >= timeout_ms;
}

class SwitchBotBleParser {
public:
  // Clamps a dBm value from the BLE stack to the int8_t range of the registry.
  static int8_t clampRssi(int dbm) {
    return static_cast<int8_t>(dbm < -128 ? -128 : (dbm > 127 ? 127 : dbm));
  }

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

  // Decode the 6-byte Service Data (UUID 0xFD3D) of the SwitchBot Meter (0x54) and Meter Plus (0x69):
  //   ServicePayload[0]: Device type
  //   ServicePayload[2]: Bits 6..0 = Battery percentage (0..100%)
  //   ServicePayload[3]: Bits 3..0 = tenths of the temperature
  //   ServicePayload[4]: Bit 7 = sign (1 = positive, 0 = negative), Bits 6..0 = whole degrees (C)
  //   ServicePayload[5]: Bit 7 = unit flag (0 = C, 1 = F), Bits 6..0 = relative humidity (0..100%)
  // The packet carries no MAC address, so the caller passes the BLE address (6 bytes).
  static bool parseServiceDataReading(
      uint16_t service_uuid,
      const uint8_t* service_payload,
      size_t len,
      const uint8_t* mac,
      SwitchBotReading& out) {
    out = SwitchBotReading{};
    if (service_uuid != SWITCHBOT_SERVICE_UUID || service_payload == nullptr || mac == nullptr ||
        len != 6) {
      return false;
    }
    if (service_payload[0] != SWITCHBOT_METER_TYPE &&
        service_payload[0] != SWITCHBOT_METER_PLUS_TYPE) {
      return false;
    }

    const uint8_t batt = service_payload[2] & 0x7F;
    const uint8_t frac_part = service_payload[3] & 0x0F;
    const bool is_positive = (service_payload[4] & 0x80) != 0;
    const uint8_t int_part = service_payload[4] & 0x7F;
    const uint8_t humidity = service_payload[5] & 0x7F;
    if (batt > 100 || frac_part > 9 || humidity > 100) {
      return false;
    }

    float temp = static_cast<float>(int_part) + (static_cast<float>(frac_part) * 0.1f);
    if (!is_positive && (int_part != 0 || frac_part != 0)) {
      temp = -temp;
    }

    memcpy(out.mac, mac, 6);
    out.temperature_c = temp;
    out.humidity_pct = humidity;
    out.battery_pct = static_cast<int8_t>(batt);
    out.fahrenheit_mode = (service_payload[5] & 0x80) != 0;
    out.valid = true;
    return true;
  }

  // Combine the Manufacturer Data and the Service Data of one advertisement. Valid Manufacturer Data
  // (WoIOSensor) wins for temperature and humidity; the Service Data then gives the battery only.
  // Without valid Manufacturer Data, the Service Data of a Meter or Meter Plus gives all values and
  // the BLE address gives the MAC. Pass nullptr or 0 for a missing part.
  static bool parseAdvertisement(
      const uint8_t* mfg_data,
      size_t mfg_len,
      const uint8_t* service_payload,
      size_t service_len,
      const uint8_t* ble_mac,
      SwitchBotReading& out) {
    if (parseManufacturerData(mfg_data, mfg_len, out)) {
      int8_t battery_pct = -1;
      if (parseServiceDataBattery(SWITCHBOT_SERVICE_UUID, service_payload, service_len, battery_pct)) {
        out.battery_pct = battery_pct;
      }
      return true;
    }
    return parseServiceDataReading(SWITCHBOT_SERVICE_UUID, service_payload, service_len, ble_mac, out);
  }
};

// Packet format of a decoded advertisement. WOIO is the Manufacturer Data of a WoIOSensor, METER the
// Service Data of a Meter or Meter Plus.
enum class BleFormat : uint8_t { WOIO = 0, METER = 1, BTHOME = 2, ATC = 3 };
constexpr size_t BLE_FORMAT_COUNT = 4;

// Packets the registry rejected during the current scan. The registry rejects a packet when no slot is
// free or when each free slot has a fixed MAC of another sensor. Each counter stops at 65535. This is
// no persistent state: resetRejects() clears it at the start of each scan.
struct BleRejectStats {
  uint16_t by_format[BLE_FORMAT_COUNT] = {0, 0, 0, 0};
  uint8_t last_mac[6] = {0, 0, 0, 0, 0, 0};
  bool has_last = false;

  uint32_t total() const {
    uint32_t sum = 0;
    for (size_t i = 0; i < BLE_FORMAT_COUNT; ++i) {
      sum += by_format[i];
    }
    return sum;
  }
};

class SwitchBotRegistry {
public:
  // Fixes a slot to one MAC (nullptr or an all-zero MAC removes the fix). A fixed slot accepts packets
  // of this MAC only; an unfixed slot never accepts a MAC that another slot has fixed. This does not
  // touch the stored reading: the caller clears a slot whose MAC changed (see clearSlot()).
  void setFixedMac(size_t index, const uint8_t* mac) {
    if (index >= MAX_SWITCHBOT_SENSORS) {
      return;
    }
    fixed_[index] = mac != nullptr && !isZeroMac(mac);
    if (fixed_[index]) {
      memcpy(fixed_mac_[index], mac, 6);
    } else {
      memset(fixed_mac_[index], 0, 6);
    }
  }

  bool isFixed(size_t index) const {
    return index < MAX_SWITCHBOT_SENSORS && fixed_[index];
  }

  const uint8_t* fixedMac(size_t index) const {
    return isFixed(index) ? fixed_mac_[index] : nullptr;
  }

  void clearSlot(size_t index) {
    if (index < MAX_SWITCHBOT_SENSORS) {
      slots_[index] = SwitchBotReading{};
    }
  }

  // True when every stored reading obeys the fixed MACs: a fixed slot holds its own MAC only, and
  // no unfixed slot holds a MAC that another slot has fixed.
  bool consistent() const {
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (slots_[i].valid && !accepts(i, slots_[i].mac)) {
        return false;
      }
    }
    return true;
  }

  // Stores one packet. `format` only feeds the reject counters of the current scan. A false result
  // for a valid reading counts as a reject and leaves the slots unchanged.
  bool upsert(const SwitchBotReading& reading, BleFormat format = BleFormat::WOIO) {
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

    // 2. A slot that is fixed to this MAC takes it, also when an earlier slot is free.
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (fixed_[i] && memcmp(fixed_mac_[i], reading.mac, 6) == 0) {
        slots_[i] = reading;
        slots_[i].packet_count = 1;
        return true;
      }
    }

    // 3. Allocate the first free unfixed slot, unless another slot has fixed this MAC.
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (!slots_[i].valid && accepts(i, reading.mac)) {
        slots_[i] = reading;
        slots_[i].packet_count = 1;
        return true;
      }
    }

    uint16_t& count = rejects_.by_format[static_cast<size_t>(format) < BLE_FORMAT_COUNT ? static_cast<size_t>(format) : 0];
    if (count < UINT16_MAX) {
      ++count;
    }
    memcpy(rejects_.last_mac, reading.mac, 6);
    rejects_.has_last = true;
    return false;
  }

  const BleRejectStats& rejects() const {
    return rejects_;
  }

  // Call at the start of each scan.
  void resetRejects() {
    rejects_ = BleRejectStats{};
  }

  const SwitchBotReading& slot(size_t index) const {
    static const SwitchBotReading kEmptyReading{};
    if (index >= MAX_SWITCHBOT_SENSORS) {
      return kEmptyReading;
    }
    return slots_[index];
  }

  // Restores one slot from persisted state. Rejects invalid readings, MACs held by another slot and MACs the fixed MACs forbid.
  bool restoreSlot(size_t index, const SwitchBotReading& reading) {
    if (index >= MAX_SWITCHBOT_SENSORS || !reading.valid || !accepts(index, reading.mac)) {
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

  // True when one valid slot of `slots` is stale. An empty slot never counts: a sensor that was
  // never seen cannot drop out.
  static bool anyStale(const SwitchBotReading* slots, size_t count, uint32_t now_ms,
                       uint32_t stale_timeout_ms = SENSOR_STALE_TIMEOUT_MS) {
    for (size_t i = 0; slots != nullptr && i < count; ++i) {
      if (slots[i].isStale(now_ms, stale_timeout_ms)) {
        return true;
      }
    }
    return false;
  }

  // Copy of the registry with a per-slot offset added to the decoded values. The stored registry keeps
  // the raw readings, so the RTC state never applies an offset twice. The humidity is rounded and
  // clamped to 0..100 %RH. A null array means no offset; an empty slot stays empty.
  SwitchBotRegistry calibrated(const float* temp_offset_c, const float* rh_offset_pct) const {
    SwitchBotRegistry out = *this;
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      SwitchBotReading& r = out.slots_[i];
      if (!r.valid) {
        continue;
      }
      if (temp_offset_c != nullptr) {
        r.temperature_c += temp_offset_c[i];
      }
      if (rh_offset_pct != nullptr) {
        const float rh = static_cast<float>(r.humidity_pct) + rh_offset_pct[i];
        r.humidity_pct = rh <= 0.0f ? 0 : (rh >= 100.0f ? 100 : static_cast<uint8_t>(rh + 0.5f));
      }
    }
    return out;
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
  static bool isZeroMac(const uint8_t* mac) {
    for (size_t i = 0; i < 6; ++i) {
      if (mac[i] != 0) {
        return false;
      }
    }
    return true;
  }

  // True when slot `index` may hold `mac`: its own fixed MAC only, or, without a fixed MAC, any MAC
  // that no other slot has fixed.
  bool accepts(size_t index, const uint8_t* mac) const {
    if (fixed_[index]) {
      return memcmp(fixed_mac_[index], mac, 6) == 0;
    }
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (i != index && fixed_[i] && memcmp(fixed_mac_[i], mac, 6) == 0) {
        return false;
      }
    }
    return true;
  }

  SwitchBotReading slots_[MAX_SWITCHBOT_SENSORS];
  bool fixed_[MAX_SWITCHBOT_SENSORS] = {false, false, false};
  uint8_t fixed_mac_[MAX_SWITCHBOT_SENSORS][6] = {{0}, {0}, {0}};
  BleRejectStats rejects_;
};

// Result list of the console command SCAN: one entry for each MAC that sent a decoded packet during
// one scan. A second packet of a known MAC replaces the entry only with a stronger RSSI (equal RSSI
// keeps the first). Packets of a 17th and further MAC find no entry: each one raises dropped() (stops
// at 65535). The collector reads and writes no registry and no persistent state.
class ScanCollector {
public:
  static constexpr size_t CAPACITY = 16;

  struct Entry {
    SwitchBotReading reading;
    BleFormat format = BleFormat::WOIO;
  };

  void add(const SwitchBotReading& reading, BleFormat format) {
    if (!reading.valid) {
      return;
    }
    for (size_t i = 0; i < count_; ++i) {
      if (memcmp(entries_[i].reading.mac, reading.mac, 6) == 0) {
        if (reading.rssi > entries_[i].reading.rssi) {
          entries_[i].reading = reading;
          entries_[i].format = format;
        }
        return;
      }
    }
    if (count_ >= CAPACITY) {
      if (dropped_ < UINT16_MAX) {
        ++dropped_;
      }
      return;
    }
    entries_[count_].reading = reading;
    entries_[count_].format = format;
    ++count_;
  }

  size_t size() const {
    return count_;
  }

  const Entry& at(size_t index) const {
    static const Entry kEmpty{};
    return index < count_ ? entries_[index] : kEmpty;
  }

  uint16_t dropped() const {
    return dropped_;
  }

  // Slot number 1 to 3 of `mac` in `registry` (a stored reading or a fixed MAC), 0 for a free MAC.
  static uint8_t slotOf(const SwitchBotRegistry& registry, const uint8_t* mac) {
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      if ((registry.slot(i).valid && memcmp(registry.slot(i).mac, mac, 6) == 0) ||
          (registry.isFixed(i) && memcmp(registry.fixedMac(i), mac, 6) == 0)) {
        return static_cast<uint8_t>(i + 1);
      }
    }
    return 0;
  }

  static const char* formatName(BleFormat format) {
    switch (format) {
      case BleFormat::WOIO: return "WOIO";
      case BleFormat::METER: return "METER";
      case BleFormat::BTHOME: return "BTHOME";
      case BleFormat::ATC: return "ATC";
      default: return "WOIO";
    }
  }

private:
  Entry entries_[CAPACITY];
  size_t count_ = 0;
  uint16_t dropped_ = 0;
};

// Per slot low battery latch of the external sensors (config.ini key sensor_battery_limit_pct).
// Bits 0 to 2 are the registry slots. A bit sets when the reported battery is at or below the
// limit. It releases at the limit plus RELEASE_MARGIN_PCT or more; between the two it holds. A
// stale slot and a slot without a reported battery (-1) keep their bit: no new information
// arrived. An empty slot clears its bit. A limit of 0 switches the latch off and clears all bits.
// SwitchBot and BTHome packets both fill SwitchBotReading::battery_pct.
class SensorBatteryLatch {
public:
  static constexpr uint8_t ALL_MASK = static_cast<uint8_t>((1u << MAX_SWITCHBOT_SENSORS) - 1u);
  static constexpr uint8_t LIMIT_OFF = 0;
  static constexpr uint8_t MAX_LIMIT_PCT = 50;
  static constexpr uint8_t RELEASE_MARGIN_PCT = 5;

  static uint8_t update(uint8_t previous, const SwitchBotReading* slots, size_t slot_count, uint32_t now_ms,
                        uint8_t limit_pct, uint32_t stale_timeout_ms = SENSOR_STALE_TIMEOUT_MS) {
    if (limit_pct == LIMIT_OFF || limit_pct > MAX_LIMIT_PCT || slots == nullptr) {
      return 0;
    }
    uint8_t bits = 0;
    for (size_t i = 0; i < slot_count && i < MAX_SWITCHBOT_SENSORS; ++i) {
      const uint8_t mask = static_cast<uint8_t>(1u << i);
      const SwitchBotReading& r = slots[i];
      if (!r.valid) {
        continue;
      }
      uint8_t held = static_cast<uint8_t>(previous & mask);
      if (!r.isStale(now_ms, stale_timeout_ms) && r.battery_pct >= 0) {
        if (r.battery_pct <= static_cast<int>(limit_pct)) {
          held = mask;
        } else if (r.battery_pct >= static_cast<int>(limit_pct) + RELEASE_MARGIN_PCT) {
          held = 0;
        }
      }
      bits |= held;
    }
    return bits;
  }

  static bool isSet(uint8_t bits, size_t slot) {
    return slot < MAX_SWITCHBOT_SENSORS && (bits & (1u << slot)) != 0;
  }
};

} // namespace rlcd_panel
