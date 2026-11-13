#pragma once

#include <stddef.h>
#include <stdint.h>
#include <math.h>

#include "st7305_framebuffer.h"
#include "switchbot_ble.h"

namespace rlcd_panel {

// Circular FIFO of the last 24 hourly relative humidity samples of one sensor. A sample is an
// int16_t in 0.1 %RH steps (50 bytes with the two counters), so four sensors fit the RTC memory easily.
// `range` gives the 24 h minimum and maximum for the stats view.
class HumidityHistory {
public:
  static constexpr size_t CAPACITY = 24;
  static constexpr int16_t MIN_VALUE = 0;    // 0.0 %RH
  static constexpr int16_t MAX_VALUE = 1000; // 100.0 %RH
  static constexpr size_t MIN_RANGE_SAMPLES = 2;

  // Converts %RH to 0.1 %RH steps. Rounds to nearest and clamps to the valid range.
  static int16_t encode(float humidity_pct) {
    if (!(humidity_pct == humidity_pct)) {
      return 0; // NaN
    }
    const float scaled = humidity_pct * 10.0f;
    if (scaled <= MIN_VALUE) {
      return MIN_VALUE;
    }
    if (scaled >= MAX_VALUE) {
      return MAX_VALUE;
    }
    return static_cast<int16_t>(lroundf(scaled));
  }

  // Appends a sample. The oldest sample drops out when the buffer is full.
  void push(int16_t value) {
    samples_[head_] = value;
    head_ = static_cast<uint8_t>((head_ + 1) % CAPACITY);
    if (count_ < CAPACITY) {
      ++count_;
    }
  }

  // Ignores NaN, negative and above 100 %RH: a bad reading must not appear as a real sample.
  // Returns true when it stored the sample.
  bool pushPercent(float humidity_pct) {
    if (!(humidity_pct >= 0.0f && humidity_pct <= 100.0f)) {
      return false;
    }
    push(encode(humidity_pct));
    return true;
  }

  size_t size() const {
    return count_;
  }

  // Index 0 is the oldest sample, size() - 1 the newest. Returns 0 for an index out of range.
  int16_t at(size_t index) const {
    if (index >= count_) {
      return 0;
    }
    return samples_[(head_ + CAPACITY - count_ + index) % CAPACITY];
  }

  // Lowest and highest sample of the buffer. Returns false for fewer than MIN_RANGE_SAMPLES samples:
  // one value is no range.
  bool range(int16_t& lo, int16_t& hi) const {
    if (count_ < MIN_RANGE_SAMPLES) {
      return false;
    }
    lo = hi = at(0);
    for (size_t i = 1; i < count_; ++i) {
      const int16_t v = at(i);
      lo = v < lo ? v : lo;
      hi = v > hi ? v : hi;
    }
    return true;
  }

  // Lowest, highest and mean sample, with the mean rounded to the nearest step. Returns false for an
  // empty buffer. Unlike range(), one sample is enough: a day with one reading still has a summary.
  bool summary(int16_t& lo, int16_t& hi, int16_t& mean) const {
    if (count_ == 0) {
      return false;
    }
    lo = hi = at(0);
    int32_t sum = at(0);
    for (size_t i = 1; i < count_; ++i) {
      const int16_t v = at(i);
      lo = v < lo ? v : lo;
      hi = v > hi ? v : hi;
      sum += v;
    }
    const int32_t n = count_;
    mean = static_cast<int16_t>((sum + (sum >= 0 ? n / 2 : -(n / 2))) / n);
    return true;
  }

  void clear() {
    *this = HumidityHistory{};
  }

  uint8_t head() const {
    return head_;
  }

  // Restores the raw ring from persisted bytes. Returns false for an inconsistent ring.
  bool restore(uint8_t count, uint8_t head, const int16_t* samples) {
    if (count > CAPACITY || head >= CAPACITY || samples == nullptr) {
      return false;
    }
    for (size_t i = 0; i < CAPACITY; ++i) {
      if (samples[i] < MIN_VALUE || samples[i] > MAX_VALUE) {
        return false;
      }
    }
    count_ = count;
    head_ = head;
    for (size_t i = 0; i < CAPACITY; ++i) {
      samples_[i] = samples[i];
    }
    return true;
  }

  // Raw ring slot, for serialization only.
  int16_t rawSample(size_t slot) const {
    return slot < CAPACITY ? samples_[slot] : 0;
  }

private:
  int16_t samples_[CAPACITY] = {};
  uint8_t head_ = 0;  // next write position
  uint8_t count_ = 0; // valid samples, at most CAPACITY
};

// Circular FIFO of the last 24 hourly temperature samples of one sensor, in 0.1 degC steps from
// -100.0 to +100.0 degC (the range the RTC restore accepts for a registry slot). It follows
// HumidityHistory: 50 bytes with the two counters.
class TemperatureHistory {
public:
  static constexpr size_t CAPACITY = 24;
  static constexpr int16_t MIN_VALUE = -1000; // -100.0 degC
  static constexpr int16_t MAX_VALUE = 1000;  // +100.0 degC
  static constexpr size_t MIN_RANGE_SAMPLES = 2;

  // Converts degC to 0.1 degC steps. Rounds to nearest (away from zero) and clamps to the valid range.
  static int16_t encode(float temperature_c) {
    if (!(temperature_c == temperature_c)) {
      return 0; // NaN
    }
    const float scaled = temperature_c * 10.0f;
    if (scaled <= MIN_VALUE) {
      return MIN_VALUE;
    }
    if (scaled >= MAX_VALUE) {
      return MAX_VALUE;
    }
    return static_cast<int16_t>(lroundf(scaled));
  }

  // Appends a sample. The oldest sample drops out when the buffer is full.
  void push(int16_t value) {
    samples_[head_] = value;
    head_ = static_cast<uint8_t>((head_ + 1) % CAPACITY);
    if (count_ < CAPACITY) {
      ++count_;
    }
  }

  // Ignores NaN and values outside -100..+100 degC: a bad reading must not appear as a real sample.
  // Returns true when it stored the sample.
  bool pushCelsius(float temperature_c) {
    if (!(temperature_c >= -100.0f && temperature_c <= 100.0f)) {
      return false;
    }
    push(encode(temperature_c));
    return true;
  }

  size_t size() const {
    return count_;
  }

  // Index 0 is the oldest sample, size() - 1 the newest. Returns 0 for an index out of range.
  int16_t at(size_t index) const {
    if (index >= count_) {
      return 0;
    }
    return samples_[(head_ + CAPACITY - count_ + index) % CAPACITY];
  }

  // Lowest and highest sample. Returns false for fewer than MIN_RANGE_SAMPLES samples.
  bool range(int16_t& lo, int16_t& hi) const {
    if (count_ < MIN_RANGE_SAMPLES) {
      return false;
    }
    lo = hi = at(0);
    for (size_t i = 1; i < count_; ++i) {
      const int16_t v = at(i);
      lo = v < lo ? v : lo;
      hi = v > hi ? v : hi;
    }
    return true;
  }

  // Lowest, highest and mean sample, with the mean rounded to the nearest step. Returns false for an
  // empty buffer. Unlike range(), one sample is enough: a day with one reading still has a summary.
  bool summary(int16_t& lo, int16_t& hi, int16_t& mean) const {
    if (count_ == 0) {
      return false;
    }
    lo = hi = at(0);
    int32_t sum = at(0);
    for (size_t i = 1; i < count_; ++i) {
      const int16_t v = at(i);
      lo = v < lo ? v : lo;
      hi = v > hi ? v : hi;
      sum += v;
    }
    const int32_t n = count_;
    mean = static_cast<int16_t>((sum + (sum >= 0 ? n / 2 : -(n / 2))) / n);
    return true;
  }

  void clear() {
    *this = TemperatureHistory{};
  }

  uint8_t head() const {
    return head_;
  }

  // Restores the raw ring from persisted bytes. Returns false for an inconsistent ring.
  bool restore(uint8_t count, uint8_t head, const int16_t* samples) {
    if (count > CAPACITY || head >= CAPACITY || samples == nullptr) {
      return false;
    }
    for (size_t i = 0; i < CAPACITY; ++i) {
      if (samples[i] < MIN_VALUE || samples[i] > MAX_VALUE) {
        return false;
      }
    }
    count_ = count;
    head_ = head;
    for (size_t i = 0; i < CAPACITY; ++i) {
      samples_[i] = samples[i];
    }
    return true;
  }

  // Raw ring slot, for serialization only.
  int16_t rawSample(size_t slot) const {
    return slot < CAPACITY ? samples_[slot] : 0;
  }

private:
  int16_t samples_[CAPACITY] = {};
  uint8_t head_ = 0;  // next write position
  uint8_t count_ = 0; // valid samples, at most CAPACITY
};

// Direction of a quantity over the last 3 hours, from the hourly samples of one sensor.
enum class Trend : uint8_t { UNKNOWN, STEADY, RISE, FALL };

// Compares the newest hourly sample with the sample 3 hours earlier. The trend lags by up to one hour.
// A change of at least the threshold gives RISE or FALL, a smaller one STEADY. A sample outside the
// valid range, or fewer than 4 samples, gives UNKNOWN.
class TrendClassifier {
public:
  static constexpr size_t LOOKBACK_HOURS = 3;
  static constexpr int16_t TEMPERATURE_THRESHOLD = 5; // 0.5 degC in 0.1 degC steps
  static constexpr int16_t HUMIDITY_THRESHOLD = 30;   // 3.0 %RH in 0.1 %RH steps

  static Trend classifyDelta(int16_t newest, int16_t older, int16_t threshold, int16_t min_value, int16_t max_value) {
    if (newest < min_value || newest > max_value || older < min_value || older > max_value) {
      return Trend::UNKNOWN;
    }
    const int32_t delta = static_cast<int32_t>(newest) - older;
    if (delta >= threshold) {
      return Trend::RISE;
    }
    if (delta <= -threshold) {
      return Trend::FALL;
    }
    return Trend::STEADY;
  }

  static Trend temperature(const TemperatureHistory& history) {
    const size_t n = history.size();
    if (n <= LOOKBACK_HOURS) {
      return Trend::UNKNOWN;
    }
    return classifyDelta(
        history.at(n - 1), history.at(n - 1 - LOOKBACK_HOURS), TEMPERATURE_THRESHOLD,
        TemperatureHistory::MIN_VALUE, TemperatureHistory::MAX_VALUE);
  }

  static Trend humidity(const HumidityHistory& history) {
    const size_t n = history.size();
    if (n <= LOOKBACK_HOURS) {
      return Trend::UNKNOWN;
    }
    return classifyDelta(
        history.at(n - 1), history.at(n - 1 - LOOKBACK_HOURS), HUMIDITY_THRESHOLD,
        HumidityHistory::MIN_VALUE, HumidityHistory::MAX_VALUE);
  }
};

// Circular FIFO of the last 24 hourly noise levels. A sample is one byte in dB (0 to 140). The value
// 0xFF marks an hour without a valid reading, so the time axis keeps its spacing. It needs 26 bytes
// with the two counters.
class NoiseHistory {
public:
  static constexpr size_t CAPACITY = 24;
  static constexpr uint8_t MAX_DB = 140;
  static constexpr uint8_t INVALID = 0xFF;

  // Clamps a dB value to 0..MAX_DB.
  static uint8_t encode(int db) {
    return static_cast<uint8_t>(db < 0 ? 0 : (db > MAX_DB ? MAX_DB : db));
  }

  // Appends a sample. The oldest sample drops out when the buffer is full. A value above MAX_DB,
  // which is not INVALID, is clamped.
  void push(uint8_t value) {
    samples_[head_] = (value == INVALID) ? INVALID : encode(value);
    head_ = static_cast<uint8_t>((head_ + 1) % CAPACITY);
    if (count_ < CAPACITY) {
      ++count_;
    }
  }

  // Appends an hour without a valid reading.
  void pushInvalid() {
    push(INVALID);
  }

  size_t size() const {
    return count_;
  }

  // Index 0 is the oldest sample, size() - 1 the newest. Returns INVALID for an index out of range.
  uint8_t at(size_t index) const {
    if (index >= count_) {
      return INVALID;
    }
    return samples_[(head_ + CAPACITY - count_ + index) % CAPACITY];
  }

  static bool isValid(uint8_t sample) {
    return sample <= MAX_DB;
  }

  // Number of valid samples.
  size_t validCount() const {
    size_t n = 0;
    for (size_t i = 0; i < count_; ++i) {
      if (isValid(at(i))) {
        ++n;
      }
    }
    return n;
  }

  // Minimum and maximum of the valid samples. Returns false when there is none.
  bool range(uint8_t& lo, uint8_t& hi) const {
    bool found = false;
    for (size_t i = 0; i < count_; ++i) {
      const uint8_t v = at(i);
      if (!isValid(v)) {
        continue;
      }
      if (!found || v < lo) lo = v;
      if (!found || v > hi) hi = v;
      found = true;
    }
    return found;
  }

  void clear() {
    *this = NoiseHistory{};
  }

  uint8_t head() const {
    return head_;
  }

  // Restores the raw ring from persisted bytes. Returns false for an inconsistent ring.
  bool restore(uint8_t count, uint8_t head, const uint8_t* samples) {
    if (count > CAPACITY || head >= CAPACITY || samples == nullptr) {
      return false;
    }
    for (size_t i = 0; i < CAPACITY; ++i) {
      if (!isValid(samples[i]) && samples[i] != INVALID) {
        return false;
      }
    }
    count_ = count;
    head_ = head;
    for (size_t i = 0; i < CAPACITY; ++i) {
      samples_[i] = samples[i];
    }
    return true;
  }

  // Raw ring slot, for serialization only.
  uint8_t rawSample(size_t slot) const {
    return slot < CAPACITY ? samples_[slot] : INVALID;
  }

private:
  uint8_t samples_[CAPACITY] = {};
  uint8_t head_ = 0;  // next write position
  uint8_t count_ = 0; // valid ring entries, at most CAPACITY
};

// Four histories (onboard SHTC3 + three SwitchBot slots) with one shared hourly sample gate.
struct ClimateHistory {
  static constexpr size_t ONBOARD = 0;
  static constexpr size_t SLOT_COUNT = 1 + MAX_SWITCHBOT_SENSORS;
  static constexpr uint32_t SAMPLE_INTERVAL_MS = 3600UL * 1000UL;

  HumidityHistory slots[SLOT_COUNT];
  TemperatureHistory temperatures[SLOT_COUNT];
  uint32_t last_sample_ms = 0;
  bool has_sampled = false;

  // True when no sample exists yet or a full interval has passed (wrap-safe subtraction).
  bool sampleDue(uint32_t now_ms) const {
    return !has_sampled || static_cast<uint32_t>(now_ms - last_sample_ms) >= SAMPLE_INTERVAL_MS;
  }

  // Records one hourly sample for every valid, fresh sensor when due. A stale slot keeps its last
  // value in the registry, so recording it would draw a false flat line. Returns true when it pushed at
  // least one sample. With no valid sensor the gate stays open, so the next wake cycle retries. The
  // default NAN temperature skips the onboard temperature sample.
  bool record(
      uint32_t now_ms,
      bool onboard_valid,
      float onboard_humidity_pct,
      const SwitchBotRegistry& registry,
      float onboard_temperature_c = NAN,
      uint32_t stale_timeout_ms = SENSOR_STALE_TIMEOUT_MS) {
    if (!sampleDue(now_ms)) {
      return false;
    }
    bool pushed = false;
    if (onboard_valid && slots[ONBOARD].pushPercent(onboard_humidity_pct)) {
      pushed = true;
    }
    if (onboard_valid && temperatures[ONBOARD].pushCelsius(onboard_temperature_c)) {
      pushed = true;
    }
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      const SwitchBotReading& r = registry.slot(i);
      if (r.valid && !r.isStale(now_ms, stale_timeout_ms)) {
        if (slots[1 + i].pushPercent(static_cast<float>(r.humidity_pct))) {
          pushed = true;
        }
        if (temperatures[1 + i].pushCelsius(r.temperature_c)) {
          pushed = true;
        }
      }
    }
    if (pushed) {
      last_sample_ms = now_ms;
      has_sampled = true;
    }
    return pushed;
  }

  // True when the last hourly sample is younger than 24 h, so the histories describe the last day.
  bool freshWithin24h(uint32_t now_ms) const {
    return has_sampled && static_cast<uint32_t>(now_ms - last_sample_ms) < 24UL * SAMPLE_INTERVAL_MS;
  }
};

// Auto-scaling and 1-bpp line renderer for a compact trend curve.
class Sparkline {
public:
  // A flatter curve than 5.0 %RH would amplify sensor noise into full-height swings.
  static constexpr int16_t MIN_SPAN = 50;

  struct Scale {
    int16_t lo = 0;
    int16_t hi = 0; // hi - lo >= MIN_SPAN
  };

  // Computes the value range for a history. A flat line or a single sample gets a span of
  // MIN_SPAN centered on the value, shifted to stay inside 0..100 %RH. Returns false for an empty history.
  static bool computeScale(const HumidityHistory& history, Scale& out) {
    if (history.size() == 0) {
      return false;
    }
    int16_t lo = history.at(0);
    int16_t hi = lo;
    for (size_t i = 1; i < history.size(); ++i) {
      const int16_t v = history.at(i);
      if (v < lo) lo = v;
      if (v > hi) hi = v;
    }
    if (hi - lo < MIN_SPAN) {
      const int center = (static_cast<int>(lo) + hi) / 2;
      int lo_v = center - MIN_SPAN / 2;
      if (lo_v < HumidityHistory::MIN_VALUE) lo_v = HumidityHistory::MIN_VALUE;
      if (lo_v > HumidityHistory::MAX_VALUE - MIN_SPAN) lo_v = HumidityHistory::MAX_VALUE - MIN_SPAN;
      out.lo = static_cast<int16_t>(lo_v);
      out.hi = static_cast<int16_t>(lo_v + MIN_SPAN);
    } else {
      out.lo = lo;
      out.hi = hi;
    }
    return true;
  }

  // Row inside the box for a value: 0 is the top (hi), h - 1 the bottom (lo). Clamps to the box.
  static uint16_t valueToRow(int16_t value, const Scale& scale, uint16_t h) {
    if (h <= 1) {
      return 0;
    }
    int v = value;
    if (v < scale.lo) v = scale.lo;
    if (v > scale.hi) v = scale.hi;
    const int span = scale.hi - scale.lo;
    const int up = ((v - scale.lo) * (h - 1) + span / 2) / span;
    return static_cast<uint16_t>((h - 1) - up);
  }

  // Column of sample `index` out of `count`. The newest sample sits at the right edge and the
  // spacing is fixed for a full 24-sample window, so the curve grows in from the right.
  static uint16_t sampleColumn(size_t index, size_t count, uint16_t w) {
    if (w <= 1 || count == 0 || index >= count) {
      return 0;
    }
    const size_t back = count - 1 - index;
    const size_t span = HumidityHistory::CAPACITY - 1;
    const size_t offset = (back * (w - 1) + span / 2) / span;
    return static_cast<uint16_t>((w - 1) - offset);
  }

  // Draws the curve into the box (x, y, w, h). Every pixel stays inside the box. Returns false
  // and draws nothing for an empty history or an empty box.
  static bool render(
      St7305Framebuffer& fb,
      uint16_t x,
      uint16_t y,
      uint16_t w,
      uint16_t h,
      const HumidityHistory& history,
      bool black = true) {
    Scale scale;
    if (w == 0 || h == 0 || !computeScale(history, scale)) {
      return false;
    }
    const size_t n = history.size();
    int px = sampleColumn(0, n, w);
    int py = valueToRow(history.at(0), scale, h);
    plot(fb, x, y, w, h, px, py, black);
    for (size_t i = 1; i < n; ++i) {
      const int cx = sampleColumn(i, n, w);
      const int cy = valueToRow(history.at(i), scale, h);
      line(fb, x, y, w, h, px, py, cx, cy, black);
      px = cx;
      py = cy;
    }
    return true;
  }

  // A flatter noise curve than 10 dB would amplify the measurement noise into full-height swings.
  static constexpr int16_t NOISE_MIN_SPAN = 10;

  // Value range of the valid noise samples. A flat curve gets a span of NOISE_MIN_SPAN centered on the
  // value, shifted to stay inside 0..MAX_DB. Returns false when there is no valid sample.
  static bool computeNoiseScale(const NoiseHistory& history, Scale& out) {
    uint8_t lo = 0;
    uint8_t hi = 0;
    if (!history.range(lo, hi)) {
      return false;
    }
    if (hi - lo < NOISE_MIN_SPAN) {
      const int center = (static_cast<int>(lo) + hi) / 2;
      int lo_v = center - NOISE_MIN_SPAN / 2;
      if (lo_v < 0) lo_v = 0;
      if (lo_v > NoiseHistory::MAX_DB - NOISE_MIN_SPAN) lo_v = NoiseHistory::MAX_DB - NOISE_MIN_SPAN;
      out.lo = static_cast<int16_t>(lo_v);
      out.hi = static_cast<int16_t>(lo_v + NOISE_MIN_SPAN);
    } else {
      out.lo = lo;
      out.hi = hi;
    }
    return true;
  }

  // Draws the noise curve into the box (x, y, w, h). Every pixel stays inside the box. An invalid
  // hour leaves a gap: the curve does not join the samples on both sides, and a lone sample is one
  // pixel. Returns false and draws nothing without a valid sample or for an empty box.
  static bool renderNoise(
      St7305Framebuffer& fb,
      uint16_t x,
      uint16_t y,
      uint16_t w,
      uint16_t h,
      const NoiseHistory& history,
      bool black = true) {
    Scale scale;
    if (w == 0 || h == 0 || !computeNoiseScale(history, scale)) {
      return false;
    }
    const size_t n = history.size();
    bool have_prev = false;
    int px = 0;
    int py = 0;
    for (size_t i = 0; i < n; ++i) {
      const uint8_t v = history.at(i);
      if (!NoiseHistory::isValid(v)) {
        have_prev = false;
        continue;
      }
      const int cx = sampleColumn(i, n, w);
      const int cy = valueToRow(v, scale, h);
      if (have_prev) {
        line(fb, x, y, w, h, px, py, cx, cy, black);
      } else {
        plot(fb, x, y, w, h, cx, cy, black);
      }
      px = cx;
      py = cy;
      have_prev = true;
    }
    return true;
  }

private:
  static void plot(
      St7305Framebuffer& fb, uint16_t x, uint16_t y, uint16_t w, uint16_t h, int cx, int cy,
      bool black) {
    if (cx < 0 || cy < 0 || cx >= w || cy >= h) {
      return;
    }
    fb.setPixel(static_cast<uint16_t>(x + cx), static_cast<uint16_t>(y + cy), black);
  }

  // Bresenham line in box coordinates; plot() clips to the box.
  static void line(
      St7305Framebuffer& fb, uint16_t x, uint16_t y, uint16_t w, uint16_t h, int x0, int y0,
      int x1, int y1, bool black) {
    const int dx = x1 > x0 ? x1 - x0 : x0 - x1;
    const int dy = y1 > y0 ? y0 - y1 : y1 - y0; // negative
    const int sx = x0 < x1 ? 1 : -1;
    const int sy = y0 < y1 ? 1 : -1;
    int err = dx + dy;
    for (;;) {
      plot(fb, x, y, w, h, x0, y0, black);
      if (x0 == x1 && y0 == y1) {
        break;
      }
      const int e2 = 2 * err;
      if (e2 >= dy) {
        err += dy;
        x0 += sx;
      }
      if (e2 <= dx) {
        err += dx;
        y0 += sy;
      }
    }
  }
};

} // namespace rlcd_panel
