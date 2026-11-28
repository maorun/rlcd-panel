#pragma once

#include <stddef.h>
#include <stdint.h>

#include "shtc3_sensor.h"
#include "sparkline.h"
#include "switchbot_ble.h"

namespace rlcd_panel {

// Per tile frozen reading latch (config.ini key stuck_alert_h, 0 = off, else 6 to 48 h). One bit for each tile:
// bit 0 is the onboard sensor, bits 1 to 10 are the registry slots. A bit sets when the newest N hourly temperature
// samples and the newest N hourly humidity samples of the tile are each equal (N = stuck_alert_h) and the slot got a
// packet in this wake cycle (`packet_mask`, bit i is slot i + 1; the onboard tile needs no packet). A set bit releases
// at the first changed sample: the newest sample differs from the one before it in either history. A stale tile, an
// empty tile, a tile outside `tile_mask` and limit 0 clear the bit. The 24-hour histories hold 24 samples, so N above 24
// counts as 24. The latch has no chime.
class StuckLatch {
public:
  static constexpr uint8_t TILE_COUNT = 1 + MAX_SWITCHBOT_SENSORS;
  static constexpr uint16_t ALL_MASK = static_cast<uint16_t>((1u << TILE_COUNT) - 1u);

  // Returns the new latch bits. `slots` must hold the calibrated readings. `packet_mask` has bit i set for each slot
  // that got a packet in this wake cycle.
  static uint16_t update(uint16_t previous, const Shtc3Reading& onboard, const SwitchBotReading* slots,
                         size_t slot_count, uint32_t now_ms, uint8_t limit_h, const ClimateHistory* history,
                         uint16_t packet_mask, uint16_t tile_mask = ALL_MASK,
                         uint32_t stale_timeout_ms = SENSOR_STALE_TIMEOUT_MS) {
    uint16_t bits = 0;
    if (limit_h == 0 || history == nullptr) {
      return 0;
    }
    if (onboard.valid && isSet(tile_mask, 0)) {
      bits |= step(previous, 0, true, limit_h, *history);
    }
    for (size_t i = 0; slots != nullptr && i < slot_count && i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (slots[i].valid && !slots[i].isStale(now_ms, stale_timeout_ms) && isSet(tile_mask, 1 + i)) {
        bits |= step(previous, static_cast<uint8_t>(1 + i), ((packet_mask >> i) & 1u) != 0, limit_h, *history);
      }
    }
    return bits;
  }

  static bool isSet(uint16_t bits, size_t tile) {
    return tile < TILE_COUNT && (bits & (1u << tile)) != 0;
  }

private:
  // True when the newest `n` samples of both histories exist and are equal to the newest sample.
  static bool newestEqual(const ClimateHistory& h, size_t tile, size_t n) {
    const TemperatureHistory& t = h.temperatures[tile];
    const HumidityHistory& r = h.slots[tile];
    if (n < 2 || t.size() < n || r.size() < n) {
      return false;
    }
    for (size_t k = 1; k < n; ++k) {
      if (t.at(t.size() - 1 - k) != t.at(t.size() - 1) || r.at(r.size() - 1 - k) != r.at(r.size() - 1)) {
        return false;
      }
    }
    return true;
  }

  static uint16_t step(uint16_t previous, uint8_t tile, bool got_packet, uint8_t limit_h, const ClimateHistory& h) {
    const uint16_t mask = static_cast<uint16_t>(1u << tile);
    const size_t n = limit_h > HumidityHistory::CAPACITY ? HumidityHistory::CAPACITY : limit_h;
    if ((previous & mask) != 0) {
      // Hold until the first changed sample (newest against the one before it).
      return newestEqual(h, tile, 2) ? mask : 0;
    }
    return got_packet && newestEqual(h, tile, n) ? mask : 0;
  }
};

} // namespace rlcd_panel
