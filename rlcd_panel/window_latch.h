#pragma once

#include <math.h>
#include <stddef.h>
#include <stdint.h>

#include "switchbot_ble.h"
#include "shtc3_sensor.h"

namespace rlcd_panel {

// Per tile open window badge latch (config.ini key window_drop_c, 0 = off). One bit for each tile:
// bit 0 is the onboard sensor, bits 1 to 10 are the registry slots (like DryLatch). The latch keeps one anchor for each
// tile: two reference temperatures in 0.01 C and a clock stamp.
// - Bit clear: two buckets of half a window hold the peak. `ref` is the peak of the current bucket, `prev` the peak
//   of the bucket before it, `stamp_ms` is the start of the current bucket. A bucket that is `window_ms / 2` old
//   rotates at the next reading. The peak is the larger of the two. With readings at most one minute apart the
//   peak covers between half and the whole window, plus one reading interval. With a longer interval the peak can
//   reach back up to 1.5 windows, because a bucket ends at the first reading after its half window. The bit sets when the reading is at least the limit below the peak. Both buckets restart when no
//   reading arrived for `window_ms` (a long wake interval, a stale slot), so the window is never shorter than the
//   interval between two readings.
// - Bit set: `ref` is the lowest reading since the bit set, `stamp_ms` is the time of the set. The bit releases
//   when the reading is half the limit above the lowest reading, or HOLD_MS after the set.
// A stale slot, an empty slot, an invalid reading, a tile outside `tile_mask` (climate_alert_tiles) and a limit of 0
// clear the bit and the anchor. The latch has no chime.
class WindowLatch {
public:
  static constexpr uint8_t TILE_COUNT = 1 + MAX_SWITCHBOT_SENSORS;
  static constexpr uint16_t ALL_MASK = static_cast<uint16_t>((1u << TILE_COUNT) - 1u);
  static constexpr uint32_t DROP_WINDOW_MS = 15UL * 60UL * 1000UL;
  static constexpr uint32_t HOLD_MS = 2UL * 60UL * 60UL * 1000UL;
  static constexpr int16_t NO_REF = INT16_MIN; // the anchor of the tile is not set
  static constexpr int16_t MAX_REF = 12000;    // 120.00 C; a stored reference beyond +-MAX_REF is corrupt

  // Anchors of all tiles. A default value has no anchor.
  struct Anchors {
    int16_t ref[TILE_COUNT];
    int16_t prev[TILE_COUNT];
    uint32_t stamp_ms[TILE_COUNT];

    Anchors() { clear(); }
    void clear() {
      for (size_t i = 0; i < TILE_COUNT; ++i) {
        clearTile(i);
      }
    }
    void clearTile(size_t tile) {
      if (tile < TILE_COUNT) {
        ref[tile] = NO_REF;
        prev[tile] = NO_REF;
        stamp_ms[tile] = 0;
      }
    }
  };

  // Returns the new latch bits and updates `anchors`. `slots` must hold the calibrated readings, and `onboard` the
  // calibrated onboard reading. `limit_c` is the key window_drop_c; 0 switches the latch off. `window_ms` is
  // DeviceConfig::windowDropMs(): 15 minutes, or longer when the wake interval is longer.
  static uint16_t update(uint16_t previous, Anchors& anchors, const Shtc3Reading& onboard,
                         const SwitchBotReading* slots, size_t slot_count, uint32_t now_ms, float limit_c,
                         uint16_t tile_mask = ALL_MASK, uint32_t stale_timeout_ms = SENSOR_STALE_TIMEOUT_MS,
                         uint32_t window_ms = DROP_WINDOW_MS) {
    uint16_t bits = 0;
    const bool on = limit_c > 0.0f;
    const int16_t limit100 = static_cast<int16_t>(lroundf(limit_c * 100.0f));
    for (size_t tile = 0; tile < TILE_COUNT; ++tile) {
      bool usable = false;
      float temp_c = 0.0f;
      if (on && isSet(tile_mask, tile)) {
        if (tile == 0) {
          usable = onboard.valid;
          temp_c = onboard.temperature_c;
        } else if (slots != nullptr && tile - 1 < slot_count && tile - 1 < MAX_SWITCHBOT_SENSORS) {
          const SwitchBotReading& r = slots[tile - 1];
          usable = r.valid && !r.isStale(now_ms, stale_timeout_ms);
          temp_c = r.temperature_c;
        }
      }
      if (!usable || !isfinite(temp_c) || fabsf(temp_c) * 100.0f > MAX_REF) {
        anchors.clearTile(tile);
        continue;
      }
      const int16_t t100 = static_cast<int16_t>(lroundf(temp_c * 100.0f));
      if (step(isSet(previous, tile), anchors, tile, t100, limit100, now_ms, window_ms)) {
        bits = static_cast<uint16_t>(bits | (1u << tile));
      }
    }
    return bits;
  }

  static bool isSet(uint16_t bits, size_t tile) {
    return tile < TILE_COUNT && (bits & (1u << tile)) != 0;
  }

  // True for a stored anchor that is valid for a tile with the latch bit `open`: an open tile needs a reference, a
  // tile without a reference has no previous peak and stamp 0, and each reference stays inside +-MAX_REF.
  static bool anchorValid(bool open, int16_t ref, int16_t prev, uint32_t stamp_ms) {
    if (prev != NO_REF && (prev < -MAX_REF || prev > MAX_REF || open)) {
      return false;
    }
    if (ref == NO_REF) {
      return !open && prev == NO_REF && stamp_ms == 0;
    }
    return ref >= -MAX_REF && ref <= MAX_REF;
  }

private:
  // Returns the new bit of one tile for a usable reading `t100` (0.01 C).
  static bool step(bool open, Anchors& a, size_t tile, int16_t t100, int16_t limit100, uint32_t now_ms,
                   uint32_t window_ms) {
    if (open && a.ref[tile] != NO_REF) {
      if (t100 < a.ref[tile]) {
        a.ref[tile] = t100;
      }
      const bool rose = static_cast<int32_t>(t100) - a.ref[tile] >= (limit100 + 1) / 2;
      if (rose || now_ms - a.stamp_ms[tile] >= HOLD_MS) {
        a.ref[tile] = t100; // a new peak starts at the release
        a.prev[tile] = NO_REF;
        a.stamp_ms[tile] = now_ms;
        return false;
      }
      return true;
    }
    const uint32_t age = now_ms - a.stamp_ms[tile];
    if (a.ref[tile] == NO_REF || age > window_ms) {
      a.ref[tile] = t100;
      a.prev[tile] = NO_REF;
      a.stamp_ms[tile] = now_ms;
      return false;
    }
    if (age >= window_ms / 2) {
      a.prev[tile] = a.ref[tile]; // rotate: the old bucket becomes the previous one
      a.ref[tile] = t100;
      a.stamp_ms[tile] = now_ms;
    } else if (t100 > a.ref[tile]) {
      a.ref[tile] = t100;
    }
    const int16_t peak = (a.prev[tile] != NO_REF && a.prev[tile] > a.ref[tile]) ? a.prev[tile] : a.ref[tile];
    if (static_cast<int32_t>(peak) - t100 >= limit100) {
      a.ref[tile] = t100;
      a.prev[tile] = NO_REF;
      a.stamp_ms[tile] = now_ms;
      return true;
    }
    return false;
  }
};

} // namespace rlcd_panel
