#pragma once

#include <stddef.h>
#include <stdint.h>

#include "sparkline.h"
#include "switchbot_ble.h"

namespace rlcd_panel {

// Co-location maths shared by the console command SPREAD and DriftLatch, so that both give the same result.
// All maths run in integers: the sum of the last HOURS tenth samples and its doubled median keep the limit checks exact.
struct SpreadMath {
  static constexpr size_t HOURS = 6;      // default window of SPREAD and the fixed window of DriftLatch
  static constexpr size_t MAX_HOURS = 24; // longest window of SPREAD <H>
  static constexpr size_t MIN_SENSORS = 3;
  static constexpr int32_t MAX_TEMP_TENTHS = 5;       // OUTLIER above 0.5 C
  static constexpr int32_t MAX_RH_TENTHS = 30;        // OUTLIER above 3.0 %RH
  static constexpr int32_t RELEASE_TEMP_TENTHS = 3;   // DriftLatch releases at 0.3 C or less
  static constexpr int32_t RELEASE_RH_TENTHS = 20;    // DriftLatch releases at 2.0 %RH or less
  static constexpr size_t MAX_COUNT = ClimateHistory::SLOT_COUNT;

  struct Result {
    size_t hours = HOURS;             // window of this result (6 to 24)
    size_t count = 0;                 // sensors with `hours` temperature and `hours` humidity samples
    size_t sensor_of[MAX_COUNT] = {}; // sensor (0 onboard, 1 to 10 a slot) of each entry
    int32_t t_sum[MAX_COUNT] = {};
    int32_t rh_sum[MAX_COUNT] = {};
    int32_t t_med2 = 0;               // twice the median of t_sum
    int32_t rh_med2 = 0;
    int32_t t_dev2[MAX_COUNT] = {};   // 2 * sum - median2, in units of 0.1 / (2 * hours) (divide by 2 * hours)
    int32_t rh_dev2[MAX_COUNT] = {};
    bool enough() const { return count >= MIN_SENSORS; }
  };

  // Rounds a / b half away from zero (b > 0).
  static int32_t divRound(int32_t a, int32_t b) {
    return a >= 0 ? (a + b / 2) / b : -((-a + b / 2) / b);
  }

  // Twice the median of `values` (count 1 to MAX_COUNT): the middle value doubled, or the sum of the two middle values.
  static int32_t medianTwice(const int32_t* values, size_t count) {
    int32_t sorted[MAX_COUNT];
    for (size_t i = 0; i < count; ++i) {
      size_t j = i;
      while (j > 0 && sorted[j - 1] > values[i]) {
        sorted[j] = sorted[j - 1];
        --j;
      }
      sorted[j] = values[i];
    }
    return count % 2 == 1 ? 2 * sorted[count / 2] : sorted[count / 2 - 1] + sorted[count / 2];
  }

  // Bit i of the sensor mask is sensor i (0 onboard, 1 to 10 a slot). Every sensor counts by default.
  static constexpr uint16_t ALL_SENSORS = static_cast<uint16_t>((1u << ClimateHistory::SLOT_COUNT) - 1u);

  // Mask of the sensors that count: the onboard sensor and each slot of `registry` that is not stale. A stale slot keeps
  // its old hourly samples, so it would show as a false OUTLIER.
  static uint16_t freshMask(const SwitchBotRegistry& registry, uint32_t now_ms, uint32_t stale_timeout_ms) {
    uint16_t mask = 1;
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (!registry.slot(i).isStale(now_ms, stale_timeout_ms)) {
        mask = static_cast<uint16_t>(mask | (2u << i));
      }
    }
    return mask;
  }

  // Fills `out` from the RTC histories over the newest `hours` samples (6 to 24, else 6). The medians and deviations exist
  // only with enough() sensors. A sensor outside `sensor_mask` or with fewer than `hours` samples in one history does not
  // count: not in `count`, not in the medians, no entry.
  static void compute(const ClimateHistory& history, Result& out, uint16_t sensor_mask = ALL_SENSORS,
                      size_t hours = HOURS) {
    out = Result();
    if (hours < HOURS || hours > MAX_HOURS) {
      hours = HOURS;
    }
    out.hours = hours;
    for (size_t i = 0; i < ClimateHistory::SLOT_COUNT; ++i) {
      if ((sensor_mask & (1u << i)) == 0) {
        continue;
      }
      if (history.recentSums(i, hours, out.t_sum[out.count], out.rh_sum[out.count])) {
        out.sensor_of[out.count++] = i;
      }
    }
    if (!out.enough()) {
      return;
    }
    out.t_med2 = medianTwice(out.t_sum, out.count);
    out.rh_med2 = medianTwice(out.rh_sum, out.count);
    for (size_t k = 0; k < out.count; ++k) {
      out.t_dev2[k] = 2 * out.t_sum[k] - out.t_med2;
      out.rh_dev2[k] = 2 * out.rh_sum[k] - out.rh_med2;
    }
  }

  // True when entry `k` deviates above the SPREAD limits (0.5 C or 3.0 %RH, exact).
  static bool isOutlier(const Result& r, size_t k) {
    return exceeds(r, k, MAX_TEMP_TENTHS, MAX_RH_TENTHS);
  }

  // True when entry `k` is at or below the release limits of the drift latch (0.3 C and 2.0 %RH, exact).
  static bool isSettled(const Result& r, size_t k) {
    return !exceeds(r, k, RELEASE_TEMP_TENTHS, RELEASE_RH_TENTHS);
  }

private:
  static bool exceeds(const Result& r, size_t k, int32_t temp_tenths, int32_t rh_tenths) {
    const int32_t t_abs = r.t_dev2[k] < 0 ? -r.t_dev2[k] : r.t_dev2[k];
    const int32_t rh_abs = r.rh_dev2[k] < 0 ? -r.rh_dev2[k] : r.rh_dev2[k];
    const int32_t scale = 2 * static_cast<int32_t>(r.hours);
    return t_abs > temp_tenths * scale || rh_abs > rh_tenths * scale;
  }
};

// Per tile sensor drift latch. One bit for each tile: bit 0 is the onboard sensor, bits 1 to 10 are the registry slots.
// A sensor that is OUTLIER by the SPREAD rules sets its bit. The bit releases when both deviations are at or below
// 0.3 C and 2.0 %RH; between the two limits the bit keeps its state. Fewer than 3 sensors, a tile outside `tile_mask`
// and a sensor without enough samples clear the bit. The latch has no chime and no badge.
class DriftLatch {
public:
  static constexpr uint8_t TILE_COUNT = 1 + MAX_SWITCHBOT_SENSORS;
  static constexpr uint16_t ALL_MASK = static_cast<uint16_t>((1u << TILE_COUNT) - 1u);

  // `tile_mask` holds the tiles that exist (bit 0 onboard, bits 1 to 10 the slots that hold a reading).
  static uint16_t update(uint16_t previous, const ClimateHistory& history, uint16_t tile_mask = ALL_MASK) {
    SpreadMath::Result r;
    SpreadMath::compute(history, r, tile_mask);
    uint16_t bits = 0;
    if (!r.enough()) {
      return 0;
    }
    for (size_t k = 0; k < r.count; ++k) {
      const size_t tile = r.sensor_of[k];
      const uint16_t mask = static_cast<uint16_t>(1u << tile);
      // SpreadMath::compute already left out each tile outside `tile_mask`, so every entry is inside it.
      if (SpreadMath::isOutlier(r, k) || ((previous & mask) != 0 && !SpreadMath::isSettled(r, k))) {
        bits |= mask;
      }
    }
    return bits;
  }

  static bool isSet(uint16_t bits, size_t tile) {
    return tile < TILE_COUNT && (bits & (1u << tile)) != 0;
  }
};

} // namespace rlcd_panel
