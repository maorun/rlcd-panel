#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include <utility>

#include "sparkline.h"
#include "switchbot_ble.h"

namespace rlcd_panel {

// OUTLIER limits in tenths (keys drift_limit_c and drift_limit_rh). The defaults give 0.5 C and 3.0 %RH. DriftLatch
// releases 0.2 C and 1.0 %RH below the limits, so the defaults give the release limits 0.3 C and 2.0 %RH.
struct SpreadLimits {
  static constexpr int32_t DEFAULT_TEMP_TENTHS = 5;
  static constexpr int32_t DEFAULT_RH_TENTHS = 30;
  static constexpr int32_t RELEASE_TEMP_MARGIN_TENTHS = 2;
  static constexpr int32_t RELEASE_RH_MARGIN_TENTHS = 10;
  int32_t temp_tenths = DEFAULT_TEMP_TENTHS;
  int32_t rh_tenths = DEFAULT_RH_TENTHS;
  int32_t releaseTempTenths() const { return temp_tenths - RELEASE_TEMP_MARGIN_TENTHS; }
  int32_t releaseRhTenths() const { return rh_tenths - RELEASE_RH_MARGIN_TENTHS; }
  static SpreadLimits fromConfig(const DeviceConfig& config) {
    SpreadLimits l;
    l.temp_tenths = DeviceConfig::driftLimitTenths(config.drift_limit_c);
    l.rh_tenths = DeviceConfig::driftLimitTenths(config.drift_limit_rh);
    return l;
  }
};

// Co-location maths shared by the console command SPREAD and DriftLatch, so that both give the same result.
// All maths run in integers: the sum of the last HOURS tenth samples and its doubled median keep the limit checks exact.
struct SpreadMath {
  static constexpr size_t HOURS = 6;      // default window of SPREAD and the fixed window of DriftLatch
  static constexpr size_t MAX_HOURS = 24; // longest window of SPREAD <H>
  static constexpr size_t MIN_SENSORS = 3;
  using Limits = SpreadLimits;
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

  // Bit of the outdoor slot (`outdoor_slot` 1 to 10 is sensor bit `outdoor_slot`), 0 for outdoor_slot 0. The outdoor sensor never
  // sits beside the indoor sensors, so its deviation from the median is no calibration error: callers clear this bit.
  static uint16_t outdoorMask(uint8_t outdoor_slot) {
    return outdoor_slot >= 1 && outdoor_slot <= MAX_SWITCHBOT_SENSORS ? static_cast<uint16_t>(1u << outdoor_slot) : 0;
  }

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

  // True when entry `k` deviates above the SPREAD limits (default 0.5 C or 3.0 %RH, exact).
  static bool isOutlier(const Result& r, size_t k, const Limits& limits = Limits()) {
    return exceeds(r, k, limits.temp_tenths, limits.rh_tenths);
  }

  // True when entry `k` is at or below the release limits of the drift latch (the limits minus 0.2 C and 1.0 %RH,
  // exact; default 0.3 C and 2.0 %RH).
  static bool isSettled(const Result& r, size_t k, const Limits& limits = Limits()) {
    return !exceeds(r, k, limits.releaseTempTenths(), limits.releaseRhTenths());
  }

private:
  static bool exceeds(const Result& r, size_t k, int32_t temp_tenths, int32_t rh_tenths) {
    const int32_t t_abs = r.t_dev2[k] < 0 ? -r.t_dev2[k] : r.t_dev2[k];
    const int32_t rh_abs = r.rh_dev2[k] < 0 ? -r.rh_dev2[k] : r.rh_dev2[k];
    const int32_t scale = 2 * static_cast<int32_t>(r.hours);
    return t_abs > temp_tenths * scale || rh_abs > rh_tenths * scale;
  }
};

// Short samples for the console command SPREAD QUICK: on USB power only, one 5-minute mean of temperature and humidity for
// the onboard sensor and each slot, the newest 12 samples (one hour) for each sensor. The wake cycles of a 5-minute window
// add their readings to a running sum; when the window ends, each sensor with at least one reading pushes its rounded mean
// and a sensor without a reading loses its samples (a gap would break the one-hour span). Battery power clears everything.
// A power-on starts empty. `toHistory` copies the samples into a ClimateHistory, so SpreadMath::compute runs on them with 12 hours.
class QuickSpread {
public:
  static constexpr size_t SENSORS = ClimateHistory::SLOT_COUNT; // 0 onboard, 1 to 10 a slot
  static constexpr size_t SAMPLES = 12;
  static constexpr uint32_t WINDOW_MS = 5UL * 60UL * 1000UL;
  static constexpr uint16_t MAX_WINDOW_READINGS = 5000; // sanity bound of one window (at most 300 s at 10 s steps)
  // Serialized: has_window (1), window start (4), then for each sensor count (1), 12 temperature and 12 humidity samples
  // (int16 each), the window sums (int32 temperature, int32 humidity) and the window reading count (uint16).
  static constexpr size_t SENSOR_BYTES = 1 + SAMPLES * 2 * 2 + 4 + 4 + 2;
  static constexpr size_t SERIALIZED_SIZE = 1 + 4 + SENSORS * SENSOR_BYTES;

  uint8_t count[SENSORS] = {};
  int16_t temp[SENSORS][SAMPLES] = {};   // 0.1 C, index 0 is the oldest sample
  int16_t rh[SENSORS][SAMPLES] = {};     // 0.1 %RH
  int32_t sum_temp[SENSORS] = {};
  int32_t sum_rh[SENSORS] = {};
  uint16_t readings[SENSORS] = {};
  bool has_window = false;
  uint32_t window_start_ms = 0;

  void clear() { *this = QuickSpread(); }

  void clearSensor(size_t sensor) {
    if (sensor >= SENSORS) {
      return;
    }
    count[sensor] = 0;
    memset(temp[sensor], 0, sizeof(temp[sensor]));
    memset(rh[sensor], 0, sizeof(rh[sensor]));
    sum_temp[sensor] = 0;
    sum_rh[sensor] = 0;
    readings[sensor] = 0;
  }

  void swapSensors(size_t a, size_t b) {
    if (a >= SENSORS || b >= SENSORS || a == b) {
      return;
    }
    std::swap(count[a], count[b]);
    for (size_t i = 0; i < SAMPLES; ++i) {
      std::swap(temp[a][i], temp[b][i]);
      std::swap(rh[a][i], rh[b][i]);
    }
    std::swap(sum_temp[a], sum_temp[b]);
    std::swap(sum_rh[a], sum_rh[b]);
    std::swap(readings[a], readings[b]);
  }

  // Largest sample count of any sensor (0 to 12).
  size_t maxCount() const {
    size_t best = 0;
    for (size_t i = 0; i < SENSORS; ++i) {
      if (count[i] > best) {
        best = count[i];
      }
    }
    return best;
  }

  // One call for each wake cycle. USB power off (or an unknown power source) clears the buffer. Otherwise bit i of `valid_mask`
  // adds the reading `temp_tenths[i]` / `rh_tenths[i]` (0.1 steps) of sensor i to the window; the first call after a clear opens the
  // window. When the window is at least WINDOW_MS old, it closes and pushes the means. Returns true when it pushed samples.
  bool update(uint32_t now_ms, bool usb_powered, uint16_t valid_mask, const int16_t* temp_tenths, const int16_t* rh_tenths) {
    if (!usb_powered) {
      clear();
      return false;
    }
    if (has_window && static_cast<uint32_t>(now_ms - window_start_ms) >= 2 * WINDOW_MS) {
      // A window that stayed open for twice its length (a long gap between wake cycles) would give one sample that covers
      // far more than 5 minutes, and the samples before it no longer touch it: start again.
      clear();
    }
    if (!has_window) {
      has_window = true;
      window_start_ms = now_ms;
    }
    for (size_t i = 0; i < SENSORS; ++i) {
      if ((valid_mask & (1u << i)) != 0 && readings[i] < MAX_WINDOW_READINGS) {
        sum_temp[i] += temp_tenths[i];
        sum_rh[i] += rh_tenths[i];
        ++readings[i];
      }
    }
    if (static_cast<uint32_t>(now_ms - window_start_ms) < WINDOW_MS) {
      return false;
    }
    bool pushed = false;
    for (size_t i = 0; i < SENSORS; ++i) {
      if (readings[i] == 0) {
        clearSensor(i);
        continue;
      }
      push(i, static_cast<int16_t>(SpreadMath::divRound(sum_temp[i], readings[i])),
           static_cast<int16_t>(SpreadMath::divRound(sum_rh[i], readings[i])));
      sum_temp[i] = 0;
      sum_rh[i] = 0;
      readings[i] = 0;
      pushed = true;
    }
    window_start_ms = now_ms;
    return pushed;
  }

  // Builds the arguments of update() for one wake cycle: the onboard sensor when `onboard_valid`, and each slot of `slots`
  // (calibrated readings, `slot_count` at most MAX_SWITCHBOT_SENSORS) that got a packet in this wake cycle (bit i of
  // `packet_mask` is slot i + 1) and is not stale. A held reading must not enter the mean. Fills `temp` and `rh` (SENSORS
  // entries, 0.1 steps) and returns the mask of the sensors that have a value.
  static uint16_t collect(bool onboard_valid, float onboard_temperature_c, float onboard_humidity_pct,
                          const SwitchBotReading* slots, size_t slot_count, uint16_t packet_mask, uint32_t now_ms,
                          uint32_t stale_timeout_ms, int16_t* temp, int16_t* rh) {
    uint16_t mask = 0;
    if (onboard_valid) {
      mask = 1;
      temp[0] = TemperatureHistory::encode(onboard_temperature_c);
      rh[0] = HumidityHistory::encode(onboard_humidity_pct);
    }
    for (size_t i = 0; i < slot_count && i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (slots[i].valid && ((packet_mask >> i) & 1u) != 0 && !slots[i].isStale(now_ms, stale_timeout_ms)) {
        mask = static_cast<uint16_t>(mask | (2u << i));
        temp[1 + i] = TemperatureHistory::encode(slots[i].temperature_c);
        rh[1 + i] = HumidityHistory::encode(static_cast<float>(slots[i].humidity_pct));
      }
    }
    return mask;
  }

  // Copies the samples into `out` (a fresh ClimateHistory), so SpreadMath::compute can read them with 12 hours.
  void toHistory(ClimateHistory& out) const {
    out = ClimateHistory();
    for (size_t i = 0; i < SENSORS; ++i) {
      for (size_t k = 0; k < count[i]; ++k) {
        out.temperatures[i].push(temp[i][k]);
        out.slots[i].push(rh[i][k]);
      }
    }
  }

  void serialize(uint8_t* out) const {
    size_t pos = 0;
    out[pos++] = has_window ? 1 : 0;
    putU32(out, pos, window_start_ms);
    for (size_t i = 0; i < SENSORS; ++i) {
      out[pos++] = count[i];
      for (size_t k = 0; k < SAMPLES; ++k) {
        putU16(out, pos, static_cast<uint16_t>(temp[i][k]));
      }
      for (size_t k = 0; k < SAMPLES; ++k) {
        putU16(out, pos, static_cast<uint16_t>(rh[i][k]));
      }
      putU32(out, pos, static_cast<uint32_t>(sum_temp[i]));
      putU32(out, pos, static_cast<uint32_t>(sum_rh[i]));
      putU16(out, pos, readings[i]);
    }
  }

  // Reads SERIALIZED_SIZE bytes. Returns false and leaves *this untouched for corrupt content: a count above 12, a sample
  // outside the range (-100.0 to 100.0 C, 0 to 100 %RH), a nonzero sample beyond the count, window sums that no readings
  // explain, a reading count above the bound, a window state without `has_window` or readings without a window.
  bool restore(const uint8_t* data) {
    QuickSpread q;
    size_t pos = 0;
    const uint8_t flag = data[pos++];
    if (flag > 1) {
      return false;
    }
    q.has_window = flag == 1;
    q.window_start_ms = getU32(data, pos);
    if (!q.has_window && q.window_start_ms != 0) {
      return false;
    }
    // clear() zeroes everything together with the flag, so samples without a window show corruption.
    for (size_t i = 0; i < SENSORS; ++i) {
      q.count[i] = data[pos++];
      if (q.count[i] > SAMPLES) {
        return false;
      }
      for (size_t k = 0; k < SAMPLES; ++k) {
        q.temp[i][k] = static_cast<int16_t>(getU16(data, pos));
      }
      for (size_t k = 0; k < SAMPLES; ++k) {
        q.rh[i][k] = static_cast<int16_t>(getU16(data, pos));
      }
      for (size_t k = 0; k < SAMPLES; ++k) {
        if (k < q.count[i]) {
          if (q.temp[i][k] < TemperatureHistory::MIN_VALUE || q.temp[i][k] > TemperatureHistory::MAX_VALUE ||
              q.rh[i][k] < HumidityHistory::MIN_VALUE || q.rh[i][k] > HumidityHistory::MAX_VALUE) {
            return false;
          }
        } else if (q.temp[i][k] != 0 || q.rh[i][k] != 0) {
          return false;
        }
      }
      q.sum_temp[i] = static_cast<int32_t>(getU32(data, pos));
      q.sum_rh[i] = static_cast<int32_t>(getU32(data, pos));
      q.readings[i] = getU16(data, pos);
      const int32_t n = q.readings[i];
      if (q.readings[i] > MAX_WINDOW_READINGS || ((n > 0 || q.count[i] > 0) && !q.has_window) ||
          q.sum_temp[i] < n * TemperatureHistory::MIN_VALUE || q.sum_temp[i] > n * TemperatureHistory::MAX_VALUE ||
          q.sum_rh[i] < 0 || q.sum_rh[i] > n * HumidityHistory::MAX_VALUE) {
        return false;
      }
    }
    *this = q;
    return true;
  }

private:
  void push(size_t sensor, int16_t t, int16_t h) {
    if (count[sensor] == SAMPLES) {
      for (size_t k = 1; k < SAMPLES; ++k) {
        temp[sensor][k - 1] = temp[sensor][k];
        rh[sensor][k - 1] = rh[sensor][k];
      }
      --count[sensor];
    }
    temp[sensor][count[sensor]] = t;
    rh[sensor][count[sensor]] = h;
    ++count[sensor];
  }
  static void putU16(uint8_t* out, size_t& pos, uint16_t v) {
    out[pos++] = static_cast<uint8_t>(v & 0xFF);
    out[pos++] = static_cast<uint8_t>(v >> 8);
  }
  static void putU32(uint8_t* out, size_t& pos, uint32_t v) {
    for (int b = 0; b < 4; ++b) {
      out[pos++] = static_cast<uint8_t>((v >> (8 * b)) & 0xFF);
    }
  }
  static uint16_t getU16(const uint8_t* d, size_t& pos) {
    const uint16_t v = static_cast<uint16_t>(d[pos] | (d[pos + 1] << 8));
    pos += 2;
    return v;
  }
  static uint32_t getU32(const uint8_t* d, size_t& pos) {
    uint32_t v = 0;
    for (int b = 0; b < 4; ++b) {
      v |= static_cast<uint32_t>(d[pos + b]) << (8 * b);
    }
    pos += 4;
    return v;
  }
};

// Per tile sensor drift latch. One bit for each tile: bit 0 is the onboard sensor, bits 1 to 10 are the registry slots.
// A sensor that is OUTLIER by the SPREAD rules sets its bit. The bit releases when both deviations are at or below
// the limits minus 0.2 C and 1.0 %RH (default 0.3 C and 2.0 %RH); between the two limits the bit keeps its state. Fewer than 3 sensors, a tile outside `tile_mask`
// and a sensor without enough samples clear the bit. The latch has no chime and no badge.
class DriftLatch {
public:
  static constexpr uint8_t TILE_COUNT = 1 + MAX_SWITCHBOT_SENSORS;
  static constexpr uint16_t ALL_MASK = static_cast<uint16_t>((1u << TILE_COUNT) - 1u);

  // `tile_mask` holds the tiles that exist (bit 0 onboard, bits 1 to 10 the slots that hold a reading).
  static uint16_t update(uint16_t previous, const ClimateHistory& history, uint16_t tile_mask = ALL_MASK,
                         const SpreadMath::Limits& limits = SpreadMath::Limits()) {
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
      if (SpreadMath::isOutlier(r, k, limits) || ((previous & mask) != 0 && !SpreadMath::isSettled(r, k, limits))) {
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
