#pragma once

#include <stddef.h>
#include <stdint.h>

namespace rlcd_panel {

// Skip of the display refresh for an unchanged frame. The reflective panel keeps its image without
// power, so a timer wakeup with the same frame as the last flush needs no panel start and no SPI
// transfer. `crc` is the CRC-32 of the last flushed frame (after display_invert); 0 means unknown.
// `skips` counts the skipped cycles in a row. The 61st cycle flushes again.
struct FrameRefresh {
  static constexpr uint8_t MAX_SKIPS = 60;
  static constexpr size_t SERIALIZED_SIZE = 5; // crc (uint32 little endian), skips (uint8)

  uint32_t crc = 0;
  uint8_t skips = 0;

  // True only for a timer wakeup without a view change, a known last frame, an equal CRC-32 and
  // fewer than MAX_SKIPS skips in a row. A power-on, a KEY wakeup and a view change always flush.
  bool shouldSkip(bool timer_wakeup, bool view_changed, uint32_t frame_crc) const {
    return timer_wakeup && !view_changed && crc != 0 && frame_crc == crc && skips < MAX_SKIPS;
  }

  void recordSkip() {
    if (skips < MAX_SKIPS) {
      ++skips;
    }
  }

  void recordFlush(uint32_t frame_crc) {
    crc = frame_crc;
    skips = 0;
  }

  // The panel shows something else than the last stored frame (console command PATTERN): the next
  // wake cycle flushes the normal view.
  void invalidate() {
    crc = 0;
    skips = 0;
  }

  bool consistent() const {
    return skips <= MAX_SKIPS && (crc != 0 || skips == 0);
  }

  void serialize(uint8_t* out) const {
    for (int i = 0; i < 4; ++i) {
      out[i] = static_cast<uint8_t>(crc >> (8 * i));
    }
    out[4] = skips;
  }

  // Returns false for a corrupt block; `*this` is then unchanged.
  bool restore(const uint8_t* in) {
    FrameRefresh r;
    r.crc = static_cast<uint32_t>(in[0]) | (static_cast<uint32_t>(in[1]) << 8) |
            (static_cast<uint32_t>(in[2]) << 16) | (static_cast<uint32_t>(in[3]) << 24);
    r.skips = in[4];
    if (!r.consistent()) {
      return false;
    }
    *this = r;
    return true;
  }
};

} // namespace rlcd_panel
