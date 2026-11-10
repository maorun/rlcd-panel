#pragma once

#include <stddef.h>
#include <stdint.h>
#include <math.h>

#include "st7305_framebuffer.h"
#include "switchbot_ble.h"

namespace rlcd_panel {

// Circular FIFO of the last 24 hourly temperature samples of one sensor. A sample is an int16_t
// in 0.1 C steps (50 bytes with the two counters), so four sensors fit the RTC memory easily.
class TemperatureHistory {
public:
  static constexpr size_t CAPACITY = 24;
  static constexpr int16_t MIN_VALUE = -1000; // -100.0 C
  static constexpr int16_t MAX_VALUE = 1000;  // +100.0 C

  // Converts degrees Celsius to 0.1 C steps. Rounds to nearest and clamps to the valid range.
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

  // Ignores NaN: a bad reading must not appear as a real 0.0 C sample.
  void pushCelsius(float temperature_c) {
    if (temperature_c != temperature_c) {
      return;
    }
    push(encode(temperature_c));
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

// Four histories (onboard SHTC3 + three SwitchBot slots) with one shared hourly sample gate.
struct ClimateHistory {
  static constexpr size_t ONBOARD = 0;
  static constexpr size_t SLOT_COUNT = 1 + MAX_SWITCHBOT_SENSORS;
  static constexpr uint32_t SAMPLE_INTERVAL_MS = 3600UL * 1000UL;

  TemperatureHistory slots[SLOT_COUNT];
  uint32_t last_sample_ms = 0;
  bool has_sampled = false;

  // True when no sample exists yet or a full interval has passed (wrap-safe subtraction).
  bool sampleDue(uint32_t now_ms) const {
    return !has_sampled || static_cast<uint32_t>(now_ms - last_sample_ms) >= SAMPLE_INTERVAL_MS;
  }

  // Records one hourly sample for every valid, fresh sensor when due. A stale slot keeps its last
  // value in the registry, so recording it would draw a false flat line. Returns true when it pushed at
  // least one sample. With no valid sensor the gate stays open, so the next wake cycle retries.
  bool record(
      uint32_t now_ms, bool onboard_valid, float onboard_temp_c, const SwitchBotRegistry& registry) {
    if (!sampleDue(now_ms)) {
      return false;
    }
    bool pushed = false;
    if (onboard_valid && onboard_temp_c == onboard_temp_c) {
      slots[ONBOARD].pushCelsius(onboard_temp_c);
      pushed = true;
    }
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      const SwitchBotReading& r = registry.slot(i);
      if (r.valid && !r.isStale(now_ms)) {
        slots[1 + i].pushCelsius(r.temperature_c);
        pushed = true;
      }
    }
    if (pushed) {
      last_sample_ms = now_ms;
      has_sampled = true;
    }
    return pushed;
  }
};

// Auto-scaling and 1-bpp line renderer for a compact trend curve.
class Sparkline {
public:
  // A flatter curve than 1.0 C would amplify sensor noise into full-height swings.
  static constexpr int16_t MIN_SPAN = 10;

  struct Scale {
    int16_t lo = 0;
    int16_t hi = 0; // hi - lo >= MIN_SPAN
  };

  // Computes the value range for a history. A flat line or a single sample gets a span of
  // MIN_SPAN centered on the value. Returns false for an empty history.
  static bool computeScale(const TemperatureHistory& history, Scale& out) {
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
      out.lo = static_cast<int16_t>(center - MIN_SPAN / 2);
      out.hi = static_cast<int16_t>(center + MIN_SPAN / 2);
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
    const size_t span = TemperatureHistory::CAPACITY - 1;
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
      const TemperatureHistory& history,
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
