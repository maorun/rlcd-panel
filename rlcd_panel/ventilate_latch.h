#pragma once

#include <stddef.h>
#include <stdint.h>

#include "switchbot_ble.h"
#include "shtc3_sensor.h"
#include "climate_math.h"

namespace rlcd_panel {

// Per tile ventilation badge latch (config.ini key ventilate_limit_rh). One bit for each tile:
// bit 0 is the onboard sensor, bits 1 to 5 are the registry slots. A bit sets when the calibrated
// humidity of the tile is above the limit. It releases when the humidity is RELEASE_MARGIN_PCT or
// more below the limit. Between the two limits the bit keeps its previous state. A stale slot,
// an empty slot and an invalid reading clear the bit. The latch is independent of ALERT_VENTILATE
// and of mold_alert_limit_rh, and it has no chime.
// If door_ref_slot is 2..5, slot 1 checks door-based ventilation against that reference slot:
// absolute humidity of slot 1 must exceed the reference slot by DOOR_TRIGGER_DIFF_GM3 (1.0 g/m3)
// and releases at DOOR_RELEASE_DIFF_GM3 (0.5 g/m3).
class VentilateLatch {
public:
  static constexpr uint8_t TILE_COUNT = 1 + MAX_SWITCHBOT_SENSORS;
  static constexpr uint16_t ALL_MASK = static_cast<uint16_t>((1u << TILE_COUNT) - 1u);
  static constexpr float RELEASE_MARGIN_PCT = 2.0f;
  static constexpr float DOOR_TRIGGER_DIFF_GM3 = 1.0f;
  static constexpr float DOOR_RELEASE_DIFF_GM3 = 0.5f;

  // Returns the new latch bits. `slots` must hold the calibrated readings, and `onboard` the
  // calibrated onboard reading.
  static uint16_t update(uint16_t previous, const Shtc3Reading& onboard, const SwitchBotReading* slots,
                         size_t slot_count, uint32_t now_ms, uint8_t limit_rh,
                         uint32_t stale_timeout_ms = SENSOR_STALE_TIMEOUT_MS,
                         uint8_t door_ref_slot = 0) {
    uint16_t bits = 0;
    if (onboard.valid) {
      bits |= step(previous, 0, onboard.humidity_pct, limit_rh);
    }
    for (size_t i = 0; slots != nullptr && i < slot_count && i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (slots[i].valid && !slots[i].isStale(now_ms, stale_timeout_ms)) {
        if (i == 0 && door_ref_slot >= 2 && door_ref_slot <= MAX_SWITCHBOT_SENSORS) {
          if (door_ref_slot <= slot_count) {
            bits |= stepSlot1WithDoor(previous, slots[0], slots[door_ref_slot - 1], limit_rh, now_ms, stale_timeout_ms);
          }
        } else {
          bits |= step(previous, static_cast<uint8_t>(1 + i), static_cast<float>(slots[i].humidity_pct), limit_rh);
        }
      }
    }
    return bits;
  }

  static bool isSet(uint16_t bits, size_t tile) {
    return tile < TILE_COUNT && (bits & (1u << tile)) != 0;
  }

private:
  static uint16_t stepSlot1WithDoor(uint16_t previous, const SwitchBotReading& slot1,
                                    const SwitchBotReading& ref, uint8_t limit_rh,
                                    uint32_t now_ms, uint32_t stale_timeout_ms) {
    if (!ref.valid || ref.isStale(now_ms, stale_timeout_ms)) {
      return 0;
    }
    const float ah_slot1 = ClimateMath::absoluteHumidityGm3(slot1.temperature_c, static_cast<float>(slot1.humidity_pct));
    const float ah_ref = ClimateMath::absoluteHumidityGm3(ref.temperature_c, static_cast<float>(ref.humidity_pct));
    if (ah_slot1 < 0.0f || ah_ref < 0.0f) {
      return 0;
    }
    const float diff_gm3 = ah_slot1 - ah_ref;
    const float rh_limit = static_cast<float>(limit_rh);
    const float rh1 = static_cast<float>(slot1.humidity_pct);
    const uint16_t mask = static_cast<uint16_t>(1u << 1);

    if (rh1 > rh_limit && diff_gm3 >= DOOR_TRIGGER_DIFF_GM3) {
      return mask;
    }
    if ((previous & mask) != 0 && rh1 > rh_limit - RELEASE_MARGIN_PCT && diff_gm3 >= DOOR_RELEASE_DIFF_GM3) {
      return mask;
    }
    return 0;
  }

  // Returns the bit of `tile` (0 or its mask) for a usable humidity value.
  static uint16_t step(uint16_t previous, uint8_t tile, float humidity_pct, uint8_t limit_rh) {
    const uint16_t mask = static_cast<uint16_t>(1u << tile);
    const float limit = static_cast<float>(limit_rh);
    if (humidity_pct > limit) {
      return mask;
    }
    if (humidity_pct > limit - RELEASE_MARGIN_PCT) {
      return static_cast<uint16_t>(previous & mask);
    }
    return 0;
  }
};

} // namespace rlcd_panel
