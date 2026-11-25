#pragma once

#include <stddef.h>
#include <stdint.h>

#include "switchbot_ble.h"
#include "shtc3_sensor.h"
#include "sparkline.h"

namespace rlcd_panel {

// Per tile dry air badge latch (config.ini key dry_alert_limit_rh, 0 = off). One bit for each tile:
// bit 0 is the onboard sensor, bits 1 to 5 are the registry slots. A bit sets when the calibrated
// humidity of the tile is at or below the limit. It releases when the humidity is RELEASE_MARGIN_PCT
// or more above the limit. Between the two limits the bit keeps its previous state. A stale slot, an empty slot, an invalid reading
// and a tile outside `tile_mask` (climate_alert_tiles) clear the bit. The latch has no chime.
// With `delay_h` (dry_alert_delay_h, 0 = off) a bit sets only when the live reading and the newest
// `delay_h` hourly samples of the tile are all at or below the limit; fewer samples give no set.
// A bit that is already set keeps its state by the hold and release rules.
class DryLatch {
public:
  static constexpr uint8_t TILE_COUNT = 1 + MAX_SWITCHBOT_SENSORS;
  static constexpr uint16_t ALL_MASK = static_cast<uint16_t>((1u << TILE_COUNT) - 1u);
  static constexpr float RELEASE_MARGIN_PCT = 3.0f;

  // Returns the new latch bits. `slots` must hold the calibrated readings, and `onboard` the
  // calibrated onboard reading. A limit of 0 switches the latch off.
  static uint16_t update(uint16_t previous, const Shtc3Reading& onboard, const SwitchBotReading* slots,
                         size_t slot_count, uint32_t now_ms, uint8_t limit_rh, uint16_t tile_mask = ALL_MASK,
                         uint32_t stale_timeout_ms = SENSOR_STALE_TIMEOUT_MS,
                         const ClimateHistory* history = nullptr, uint8_t delay_h = 0) {
    uint16_t bits = 0;
    if (limit_rh == 0) {
      return 0;
    }
    if (onboard.valid && isSet(tile_mask, 0)) {
      bits |= step(previous, 0, onboard.humidity_pct, limit_rh, delayPassed(history, 0, limit_rh, delay_h));
    }
    for (size_t i = 0; slots != nullptr && i < slot_count && i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (slots[i].valid && !slots[i].isStale(now_ms, stale_timeout_ms) && isSet(tile_mask, 1 + i)) {
        bits |= step(previous, static_cast<uint8_t>(1 + i), static_cast<float>(slots[i].humidity_pct), limit_rh,
                    delayPassed(history, 1 + i, limit_rh, delay_h));
      }
    }
    return bits;
  }

  static bool isSet(uint16_t bits, size_t tile) {
    return tile < TILE_COUNT && (bits & (1u << tile)) != 0;
  }

private:
  // True when the delay allows a set of the tile: delay 0, or the newest `delay_h` samples are at or below the limit.
  static bool delayPassed(const ClimateHistory* history, size_t tile, uint8_t limit_rh, uint8_t delay_h) {
    if (delay_h == 0) {
      return true;
    }
    if (history == nullptr || tile >= ClimateHistory::SLOT_COUNT) {
      return false;
    }
    return history->slots[tile].newestAtOrBelow(delay_h, static_cast<uint16_t>(limit_rh * 10u));
  }

  // Returns the bit of `tile` (0 or its mask) for a usable humidity value.
  static uint16_t step(uint16_t previous, uint8_t tile, float humidity_pct, uint8_t limit_rh, bool delay_ok) {
    const uint16_t mask = static_cast<uint16_t>(1u << tile);
    const float limit = static_cast<float>(limit_rh);
    if (humidity_pct <= limit) {
      return delay_ok ? mask : static_cast<uint16_t>(previous & mask);
    }
    if (humidity_pct < limit + RELEASE_MARGIN_PCT) {
      return static_cast<uint16_t>(previous & mask);
    }
    return 0;
  }
};

} // namespace rlcd_panel
