#pragma once

#include <math.h>
#include <stddef.h>
#include <stdint.h>

#include "climate_math.h"
#include "shtc3_sensor.h"
#include "switchbot_ble.h"

namespace rlcd_panel {

// Per tile wall humidity badge latch (config.ini keys wall_frsi_pct and mold_alert_limit_rh, wall_frsi_pct 0 = off).
// One bit for each tile: bit 0 is the onboard sensor, bits 1 to 10 are the registry slots. A bit sets when
// ClimateMath::wallSurfaceRh of the tile (room air at the temperature of the outer wall) is at or above the mold limit.
// It releases when the value is RELEASE_MARGIN_PCT or more below the limit. Between the two limits the bit keeps its
// previous state. An empty or stale tile, the outdoor tile, a stale or empty outdoor slot, an invalid result and
// frsi 0 clear the bit. The latch has no chime. Like DryLatch it uses SwitchBotReading::isStale, so a packet stamped
// after the clock counts as stale here, while DashboardView::formatWall shows it as live.
class WallLatch {
public:
  static constexpr uint8_t TILE_COUNT = 1 + MAX_SWITCHBOT_SENSORS;
  static constexpr uint16_t ALL_MASK = static_cast<uint16_t>((1u << TILE_COUNT) - 1u);
  static constexpr float RELEASE_MARGIN_PCT = 3.0f;

  // Returns the new latch bits. `slots` must hold the calibrated readings, and `onboard` the calibrated onboard
  // reading. `outdoor_slot` is 1 to MAX_SWITCHBOT_SENSORS or 0 for none.
  static uint16_t update(uint16_t previous, const Shtc3Reading& onboard, const SwitchBotReading* slots,
                         size_t slot_count, uint32_t now_ms, uint8_t limit_rh, uint8_t frsi_pct, uint8_t outdoor_slot,
                         uint32_t stale_timeout_ms = SENSOR_STALE_TIMEOUT_MS) {
    if (frsi_pct == 0 || slots == nullptr || outdoor_slot < 1 || outdoor_slot > slot_count ||
        outdoor_slot > MAX_SWITCHBOT_SENSORS) {
      return 0;
    }
    const SwitchBotReading& outdoor = slots[outdoor_slot - 1];
    if (!outdoor.valid || outdoor.isStale(now_ms, stale_timeout_ms)) {
      return 0;
    }
    const float frsi = static_cast<float>(frsi_pct) / 100.0f;
    uint16_t bits = 0;
    if (onboard.valid) {
      bits |= step(previous, 0, onboard.temperature_c, onboard.humidity_pct, outdoor.temperature_c, frsi, limit_rh);
    }
    for (size_t i = 0; i < slot_count && i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (i + 1 == outdoor_slot || !slots[i].valid || slots[i].isStale(now_ms, stale_timeout_ms)) {
        continue;
      }
      bits |= step(previous, static_cast<uint8_t>(1 + i), slots[i].temperature_c,
                   static_cast<float>(slots[i].humidity_pct), outdoor.temperature_c, frsi, limit_rh);
    }
    return bits;
  }

  static bool isSet(uint16_t bits, size_t tile) {
    return tile < TILE_COUNT && (bits & (1u << tile)) != 0;
  }

private:
  // Returns the bit of `tile` (0 or its mask) for a usable reading.
  static uint16_t step(uint16_t previous, uint8_t tile, float t_in_c, float rh_in_pct, float t_out_c, float frsi,
                       uint8_t limit_rh) {
    const uint16_t mask = static_cast<uint16_t>(1u << tile);
    const float rh = ClimateMath::wallSurfaceRh(t_in_c, rh_in_pct, t_out_c, frsi);
    if (!isfinite(rh)) {
      return 0;
    }
    const float limit = static_cast<float>(limit_rh);
    if (rh >= limit) {
      return mask;
    }
    if (rh > limit - RELEASE_MARGIN_PCT) {
      return static_cast<uint16_t>(previous & mask);
    }
    return 0;
  }
};

} // namespace rlcd_panel
