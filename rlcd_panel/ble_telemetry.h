#pragma once

#include <math.h>
#include <stddef.h>
#include <stdint.h>

#include "battery_monitor.h"
#include "shtc3_sensor.h"
#include "switchbot_ble.h"

namespace rlcd_panel {

// GATT layout of the BLE console (Nordic UART service): the central writes console lines to RX and
// receives the reply bytes as notifications on TX. serial_console.h holds the bridge.
struct BleConsoleUuids {
  static constexpr const char* SERVICE = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
  static constexpr const char* RX = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"; // write
  static constexpr const char* TX = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"; // notify
};

// One climate tile of the telemetry frame. An empty or stale tile has valid = false.
struct BleTelemetryTile {
  bool valid = false;
  float temperature_c = 0.0f; // Celsius, also when temp_unit is F
  float humidity_pct = 0.0f;
};

// Manufacturer Data frame of the BLE telemetry broadcast.
//
// Payload (after the company ID), 24 bytes:
//   0     format version (2)
//   1     sequence number (low byte of the wake cycle counter)
//   2     cell percentage (0..100, 255 = unknown)
//   3     active alert mask (ALERT_* bits)
//   4     valid tile mask (bit 0 onboard, bits 1..5 slots 1..5)
//   5..22 six tiles, each: temperature int16 little-endian in 0.01 C, humidity uint8 in 0.5 %RH
//   23    CRC-8 (0x31, Shtc3Codec::computeCrc8) over bytes 0..22
// Manufacturer Data field: company ID 0xFFFF (bytes FF FF) + payload = 26 bytes.
// Advertising data: flags (3 bytes) + length and type AD (2 bytes) + 26 bytes = 31 bytes of 31.
class BleTelemetryCodec {
public:
  static constexpr uint16_t COMPANY_ID = 0xFFFF;
  static constexpr uint8_t FORMAT_VERSION = 2;
  static constexpr uint8_t CELL_UNKNOWN = 255;
  static constexpr size_t TILE_COUNT = 6;
  static constexpr size_t PAYLOAD_SIZE = 5 + TILE_COUNT * 3 + 1;
  static constexpr size_t MANUFACTURER_DATA_SIZE = 2 + PAYLOAD_SIZE;
  static constexpr size_t FLAGS_AD_SIZE = 3;
  static constexpr size_t MANUFACTURER_AD_SIZE = 2 + MANUFACTURER_DATA_SIZE;
  static constexpr size_t ADVERTISING_SIZE = FLAGS_AD_SIZE + MANUFACTURER_AD_SIZE;
  static constexpr size_t ADVERTISING_LIMIT = 31;
  static constexpr uint32_t ADVERTISE_MS = 100;
  static constexpr uint16_t INTERVAL_UNITS = 32; // 20 ms in units of 0.625 ms

  static_assert(ADVERTISING_SIZE <= ADVERTISING_LIMIT, "telemetry frame must fit into one advertising packet");

  // The broadcast runs only when the key is 1 and neither the low battery alert nor the empty
  // battery protection is set.
  static bool shouldBroadcast(uint8_t ble_broadcast_enabled, const BatteryReading& battery) {
    return ble_broadcast_enabled != 0 && !battery.low_battery && !battery.empty_protection;
  }

  static uint8_t cellPercent(const BatteryReading& battery) {
    if (!battery.valid || battery.percentage < 0 || battery.percentage > 100) {
      return CELL_UNKNOWN;
    }
    return static_cast<uint8_t>(battery.percentage);
  }

  // The wake cycle counter wraps 255 to 0.
  static uint8_t sequenceFrom(uint32_t boot_count) { return static_cast<uint8_t>(boot_count & 0xFF); }

  // Writes the 18 payload bytes. `out` needs PAYLOAD_SIZE bytes.
  static void encodePayload(const BleTelemetryTile tiles[TILE_COUNT], uint8_t sequence, uint8_t cell_pct,
                            uint8_t alert_mask, uint8_t* out) {
    size_t pos = 0;
    out[pos++] = FORMAT_VERSION;
    out[pos++] = sequence;
    out[pos++] = cell_pct;
    out[pos++] = alert_mask;
    uint8_t valid_mask = 0;
    for (size_t i = 0; i < TILE_COUNT; ++i) {
      if (tileUsable(tiles[i])) {
        valid_mask = static_cast<uint8_t>(valid_mask | (1u << i));
      }
    }
    out[pos++] = valid_mask;
    for (size_t i = 0; i < TILE_COUNT; ++i) {
      int16_t t = 0;
      uint8_t rh = 0;
      if ((valid_mask & (1u << i)) != 0) {
        t = encodeTemperature(tiles[i].temperature_c);
        rh = encodeHumidity(tiles[i].humidity_pct);
      }
      const uint16_t u = static_cast<uint16_t>(t);
      out[pos++] = static_cast<uint8_t>(u & 0xFF);
      out[pos++] = static_cast<uint8_t>(u >> 8);
      out[pos++] = rh;
    }
    out[pos] = Shtc3Codec::computeCrc8(out, pos);
  }

  // Writes the 20 bytes of the Manufacturer Data field: company ID 0xFFFF, then the payload.
  static void encode(const BleTelemetryTile tiles[TILE_COUNT], uint8_t sequence, uint8_t cell_pct,
                     uint8_t alert_mask, uint8_t* out) {
    out[0] = static_cast<uint8_t>(COMPANY_ID & 0xFF);
    out[1] = static_cast<uint8_t>(COMPANY_ID >> 8);
    encodePayload(tiles, sequence, cell_pct, alert_mask, out + 2);
  }

  // Builds the six tiles: tile 0 is the onboard reading, tiles 1..5 are the registry slots. A slot
  // that is empty or stale (older than `stale_timeout_ms`) is no valid tile. Pass the calibrated
  // registry, so the frame shows the same values as the screen.
  static void buildTiles(const Shtc3Reading& onboard, const SwitchBotRegistry& registry, uint32_t now_ms,
                         uint32_t stale_timeout_ms, BleTelemetryTile out[TILE_COUNT]) {
    out[0].valid = onboard.valid;
    out[0].temperature_c = onboard.temperature_c;
    out[0].humidity_pct = onboard.humidity_pct;
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS && i + 1 < TILE_COUNT; ++i) {
      const SwitchBotReading r = registry.slot(i);
      out[i + 1].valid = r.valid && !r.isStale(now_ms, stale_timeout_ms);
      out[i + 1].temperature_c = r.temperature_c;
      out[i + 1].humidity_pct = static_cast<float>(r.humidity_pct);
    }
  }

  static int16_t encodeTemperature(float temperature_c) {
    const float v = roundf(temperature_c * 100.0f);
    if (v < -32768.0f) {
      return -32768;
    }
    if (v > 32767.0f) {
      return 32767;
    }
    return static_cast<int16_t>(v);
  }

  static uint8_t encodeHumidity(float humidity_pct) {
    const float v = roundf(humidity_pct * 2.0f);
    if (v < 0.0f) {
      return 0;
    }
    if (v > 200.0f) {
      return 200;
    }
    return static_cast<uint8_t>(v);
  }

private:
  static bool tileUsable(const BleTelemetryTile& t) {
    return t.valid && isfinite(t.temperature_c) && isfinite(t.humidity_pct);
  }
};

} // namespace rlcd_panel
