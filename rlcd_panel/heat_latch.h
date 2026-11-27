#pragma once

#include <stddef.h>
#include <stdint.h>

#include "climate_math.h"
#include "switchbot_ble.h"
#include "shtc3_sensor.h"

namespace rlcd_panel {

// Per tile heat badge latch (config.ini key heat_alert_limit_c, 0 = off). One bit for each tile:
// bit 0 is the onboard sensor, bits 1 to 10 are the registry slots. A bit sets when the feels-like value
// (ClimateMath::heatIndexC of the calibrated reading) is at or above the limit. It releases when the value is
// RELEASE_MARGIN_C or more below the limit (the same release as ALERT_HEAT). Between the two limits the bit keeps its
// previous state. A stale slot, an empty slot, an invalid reading, an invalid feels-like value and a tile outside
// `tile_mask` (climate_alert_tiles) clear the bit. The latch has no chime and ignores heat_alert_delay_h.
class HeatLatch {
public:
  static constexpr uint8_t TILE_COUNT = 1 + MAX_SWITCHBOT_SENSORS;
  static constexpr uint16_t ALL_MASK = static_cast<uint16_t>((1u << TILE_COUNT) - 1u);
  static constexpr float RELEASE_MARGIN_C = 2.0f;

  // Returns the new latch bits. `slots` must hold the calibrated readings, and `onboard` the calibrated onboard
  // reading. A limit of 0 switches the latch off.
  static uint16_t update(uint16_t previous, const Shtc3Reading& onboard, const SwitchBotReading* slots,
                         size_t slot_count, uint32_t now_ms, uint8_t limit_c, uint16_t tile_mask = ALL_MASK,
                         uint32_t stale_timeout_ms = SENSOR_STALE_TIMEOUT_MS) {
    uint16_t bits = 0;
    if (limit_c == 0) {
      return 0;
    }
    if (onboard.valid && isSet(tile_mask, 0)) {
      bits |= step(previous, 0, onboard.temperature_c, onboard.humidity_pct, limit_c);
    }
    for (size_t i = 0; slots != nullptr && i < slot_count && i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (slots[i].valid && !slots[i].isStale(now_ms, stale_timeout_ms) && isSet(tile_mask, 1 + i)) {
        bits |= step(previous, static_cast<uint8_t>(1 + i), slots[i].temperature_c,
                     static_cast<float>(slots[i].humidity_pct), limit_c);
      }
    }
    return bits;
  }

  static bool isSet(uint16_t bits, size_t tile) {
    return tile < TILE_COUNT && (bits & (1u << tile)) != 0;
  }

private:
  // Returns the bit of `tile` (0 or its mask) for a reading.
  static uint16_t step(uint16_t previous, uint8_t tile, float temperature_c, float humidity_pct, uint8_t limit_c) {
    const uint16_t mask = static_cast<uint16_t>(1u << tile);
    const float feels = ClimateMath::heatIndexC(temperature_c, humidity_pct);
    if (feels != feels) { // NaN: no usable reading
      return 0;
    }
    const float limit = static_cast<float>(limit_c);
    if (feels >= limit) {
      return mask;
    }
    if (feels > limit - RELEASE_MARGIN_C) {
      return static_cast<uint16_t>(previous & mask);
    }
    return 0;
  }
};

} // namespace rlcd_panel
