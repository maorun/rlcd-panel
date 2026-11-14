#pragma once

#include <stddef.h>
#include <stdint.h>

#include "switchbot_ble.h"
#include "shtc3_sensor.h"

namespace rlcd_panel {

// Per tile ventilation badge latch (config.ini key ventilate_limit_rh). One bit for each tile:
// bit 0 is the onboard sensor, bits 1 to 5 are the registry slots. A bit sets when the calibrated
// humidity of the tile is above the limit. It releases when the humidity is RELEASE_MARGIN_PCT or
// more below the limit. Between the two limits the bit keeps its previous state. A stale slot,
// an empty slot and an invalid reading clear the bit. The latch is independent of ALERT_VENTILATE
// and of mold_alert_limit_rh, and it has no chime.
class VentilateLatch {
public:
  static constexpr uint8_t TILE_COUNT = 1 + MAX_SWITCHBOT_SENSORS;
  static constexpr uint8_t ALL_MASK = static_cast<uint8_t>((1u << TILE_COUNT) - 1u);
  static constexpr float RELEASE_MARGIN_PCT = 2.0f;

  // Returns the new latch bits. `slots` must hold the calibrated readings, and `onboard` the
  // calibrated onboard reading.
  static uint8_t update(uint8_t previous, const Shtc3Reading& onboard, const SwitchBotReading* slots,
                        size_t slot_count, uint32_t now_ms, uint8_t limit_rh,
                        uint32_t stale_timeout_ms = SENSOR_STALE_TIMEOUT_MS) {
    uint8_t bits = 0;
    if (onboard.valid) {
      bits |= step(previous, 0, onboard.humidity_pct, limit_rh);
    }
    for (size_t i = 0; slots != nullptr && i < slot_count && i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (slots[i].valid && !slots[i].isStale(now_ms, stale_timeout_ms)) {
        bits |= step(previous, static_cast<uint8_t>(1 + i), static_cast<float>(slots[i].humidity_pct), limit_rh);
      }
    }
    return bits;
  }

  static bool isSet(uint8_t bits, size_t tile) {
    return tile < TILE_COUNT && (bits & (1u << tile)) != 0;
  }

private:
  // Returns the bit of `tile` (0 or its mask) for a usable humidity value.
  static uint8_t step(uint8_t previous, uint8_t tile, float humidity_pct, uint8_t limit_rh) {
    const uint8_t mask = static_cast<uint8_t>(1u << tile);
    const float limit = static_cast<float>(limit_rh);
    if (humidity_pct > limit) {
      return mask;
    }
    if (humidity_pct > limit - RELEASE_MARGIN_PCT) {
      return static_cast<uint8_t>(previous & mask);
    }
    return 0;
  }
};

} // namespace rlcd_panel
