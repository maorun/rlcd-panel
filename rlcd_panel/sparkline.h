#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <math.h>

#include "device_config.h"
#include "ntp_sync.h"
#include "pcf85063_rtc.h"
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

  // Number of stored samples strictly above the limit (0.1 %RH steps). A sample at the limit does not count.
  size_t countAbove(uint16_t limit_tenths) const {
    size_t n = 0;
    for (size_t i = 0; i < count_; ++i) {
      if (at(i) > static_cast<int32_t>(limit_tenths)) {
        ++n;
      }
    }
    return n;
  }

  // True when the newest `n` samples exist and each one is strictly above the limit (0.1 %RH steps).
  // Fewer than `n` samples give false. `n` 0 gives true.
  bool newestAbove(size_t n, uint16_t limit_tenths) const {
    if (n > count_) {
      return false;
    }
    for (size_t i = 0; i < n; ++i) {
      if (at(count_ - 1 - i) <= static_cast<int32_t>(limit_tenths)) {
        return false;
      }
    }
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

  // Text of the console command STATUS: RISE, FALL, STEADY or -- for UNKNOWN.
  static const char* statusText(Trend trend) {
    switch (trend) {
      case Trend::RISE: return "RISE";
      case Trend::FALL: return "FALL";
      case Trend::STEADY: return "STEADY";
      case Trend::UNKNOWN: break;
    }
    return "--";
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

  // Sums of the newest `hours` temperature samples (0.1 C) and humidity samples (0.1 %RH) of one sensor. False when the sensor
  // has fewer than `hours` samples in either history. Read only.
  bool recentSums(size_t sensor, size_t hours, int32_t& temp_sum, int32_t& rh_sum) const {
    if (sensor >= SLOT_COUNT || hours == 0 || temperatures[sensor].size() < hours || slots[sensor].size() < hours) {
      return false;
    }
    temp_sum = 0;
    rh_sum = 0;
    for (size_t k = 0; k < hours; ++k) {
      temp_sum += temperatures[sensor].at(temperatures[sensor].size() - 1 - k);
      rh_sum += slots[sensor].at(slots[sensor].size() - 1 - k);
    }
    return true;
  }
  uint32_t last_sample_ms = 0;
  bool has_sampled = false;
  // One bit for each hourly onboard temperature sample: bit 0 is the newest, a set bit means a USB powered sample
  // (the PCB is warmer there). 24 bits, 3 bytes in the RTC state.
  static constexpr uint32_t USB_MASK_ALL = 0xFFFFFFu;
  uint32_t usb_mask = 0;

  // True when the mask holds no bit beyond the stored onboard temperature samples.
  bool restoreUsbMask(uint32_t mask) {
    const size_t n = temperatures[ONBOARD].size();
    if ((mask & ~USB_MASK_ALL) != 0 || (n < 24 && (mask >> n) != 0)) {
      return false;
    }
    usb_mask = mask;
    return true;
  }

  // True when the onboard sample of this age (0 is the newest) was taken on USB power.
  bool onboardOnUsb(size_t age) const {
    return age < 24 && ((usb_mask >> age) & 1u) != 0;
  }

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
      uint32_t stale_timeout_ms = SENSOR_STALE_TIMEOUT_MS,
      bool onboard_usb_powered = false) {
    if (!sampleDue(now_ms)) {
      return false;
    }
    bool pushed = false;
    if (onboard_valid && slots[ONBOARD].pushPercent(onboard_humidity_pct)) {
      pushed = true;
    }
    if (onboard_valid && temperatures[ONBOARD].pushCelsius(onboard_temperature_c)) {
      usb_mask = ((usb_mask << 1) | (onboard_usb_powered ? 1u : 0u)) & USB_MASK_ALL;
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

  // One hourly CSV row for the restore after a power-on. `minutes` is the local RTC time of the row in
  // minutes since 1970-01-01. Bit i of `temp_mask` and `rh_mask` is set when source i (0 onboard, 1 to 10
  // a slot) has a value; `temp` and `rh` hold it in 0.1 steps.
  struct RestoreRow {
    int64_t minutes = 0;
    bool usb = false;
    uint16_t temp_mask = 0;
    uint16_t rh_mask = 0;
    int16_t temp[SLOT_COUNT] = {};
    int16_t rh[SLOT_COUNT] = {};
  };
  static constexpr uint16_t RESTORE_MIN_TRUSTED_YEAR = 2025;
  static constexpr int64_t RESTORE_MAX_AGE_MIN = 24 * 60;
  // Largest step between two rows that still counts as one hour: the hourly write drifts by up to one
  // wake interval, so a step of 61 to 90 minutes is no gap.
  static constexpr int64_t RESTORE_MAX_STEP_MIN = 90;

  // True when the row holds both onboard values. A row without them ends the restore like a gap, so the
  // onboard humidity history, temperature history and `usb_mask` keep the same length.
  static bool hasOnboard(const RestoreRow& row) {
    return (row.temp_mask & 1u) != 0 && (row.rh_mask & 1u) != 0;
  }

  // Refills the histories from the hourly rows of the card after a power-on. `rows` runs from the oldest
  // to the newest row. The restore takes the newest run of rows that is at most 24 hours old, has no step
  // above RESTORE_MAX_STEP_MIN and holds at most 24 rows, and pushes it oldest first into the onboard
  // history (with `usb_mask`) and into each slot that `fixed_slots` marks (bit k is registry slot k + 1
  // = history 1 + k; the registry order after a power-on is unknown for a slot without a fixed MAC, so
  // that slot stays empty). `last_sample_ms` follows the newest row, so the hourly gate does not push the
  // same hour twice. It changes nothing for an invalid or untrusted RTC time, for histories that already
  // hold a sample and for rows that are too old, too new or out of order. Returns the restored rows.
  size_t restore(
      const RestoreRow* rows, size_t count, const RtcTime& now, uint32_t now_ms, uint16_t fixed_slots) {
    if (rows == nullptr || count == 0 || has_sampled || !now.valid || now.oscillator_stopped ||
        now.year < RESTORE_MIN_TRUSTED_YEAR || now.year > 2099 ||
        !NtpTime::isValidDate(now.year, now.month, now.day) || now.hour > 23 || now.minute > 59) {
      return 0;
    }
    for (size_t i = 0; i < SLOT_COUNT; ++i) {
      if (slots[i].size() != 0 || temperatures[i].size() != 0) {
        return 0;
      }
    }
    const int64_t now_min = NtpTime::daysFromCivil(now.year, now.month, now.day) * 1440 +
                            static_cast<int64_t>(now.hour) * 60 + now.minute;
    const size_t end = count;
    const int64_t newest_age = now_min - rows[end - 1].minutes;
    if (newest_age < 0 || newest_age > RESTORE_MAX_AGE_MIN || !hasOnboard(rows[end - 1])) {
      return 0;
    }
    size_t first = end - 1;
    while (first > 0 && end - first < 24) {
      const int64_t step = rows[first].minutes - rows[first - 1].minutes;
      if (step <= 0 || step > RESTORE_MAX_STEP_MIN || !hasOnboard(rows[first - 1]) || now_min - rows[first - 1].minutes > RESTORE_MAX_AGE_MIN) {
        break;
      }
      --first;
    }
    bool pushed = false;
    for (size_t r = first; r < end; ++r) {
      const RestoreRow& row = rows[r];
      for (size_t i = 0; i < SLOT_COUNT; ++i) {
        if (i != ONBOARD && ((fixed_slots >> (i - 1)) & 1u) == 0) {
          continue;
        }
        if (((row.rh_mask >> i) & 1u) != 0 && slots[i].pushPercent(static_cast<float>(row.rh[i]) / 10.0f)) {
          pushed = true;
        }
        if (((row.temp_mask >> i) & 1u) != 0 &&
            temperatures[i].pushCelsius(static_cast<float>(row.temp[i]) / 10.0f)) {
          if (i == ONBOARD) {
            usb_mask = ((usb_mask << 1) | (row.usb ? 1u : 0u)) & USB_MASK_ALL;
          }
          pushed = true;
        }
      }
    }
    if (pushed) {
      last_sample_ms = now_ms - static_cast<uint32_t>(newest_age * 60000);
      has_sampled = true;
    }
    return pushed ? end - first : 0;
  }
};

// Daily minimum and maximum of the last 7 days for each of the 6 sensors (onboard + 5 slots). A day is
// 4 bytes: temperature minimum and maximum (0.5 degC steps, int8 with a bias of 128, so -64.0 to
// +63.0 degC) and humidity minimum and maximum (0.5 %RH steps, 0 to 100 %RH). The value 0xFF in both bytes
// of a pair marks a day without samples, which the chart shows as a gap. Day 0 is the newest day: the
// day before the stamped date. A fifth byte per day and sensor holds the hours above mold_alert_limit_rh
// (0 to 24, 0xFF for a day without humidity samples). With the 5-byte day stamp the block needs 390 bytes
// in the RTC memory.
class WeekHistory {
public:
  static constexpr size_t DAYS = 7;
  static constexpr size_t SENSORS = ClimateHistory::SLOT_COUNT;
  static constexpr size_t DAY_SIZE = 4;
  static constexpr size_t STAMP_SIZE = 1 + 2 + 1 + 1; // flag, year (uint16), month, day
  static constexpr size_t SERIALIZED_SIZE = SENSORS * DAYS * (DAY_SIZE + 1) + STAMP_SIZE;
  static constexpr uint8_t GAP = 0xFF;
  static constexpr uint8_t MAX_MOLD_HOURS = 24;
  static constexpr int TEMPERATURE_BIAS = 128;
  static constexpr uint8_t MAX_HUMIDITY = 200; // 100 %RH in 0.5 %RH steps
  static constexpr uint16_t MIN_TRUSTED_YEAR = 2025;

  WeekHistory() {
    memset(days_, GAP, sizeof(days_));
    memset(mold_, GAP, sizeof(mold_));
  }

  // Rounds a value in 0.1 steps to 0.5 steps, half away from zero.
  static int roundToHalf(int16_t tenths) {
    return (tenths + (tenths >= 0 ? 2 : -2)) / 5;
  }

  // 0.1 degC to the stored byte. Clamps to -64.0 .. +63.0 degC, so the byte never equals GAP.
  static uint8_t encodeTemperature(int16_t tenths) {
    int half = roundToHalf(tenths);
    half = half < -128 ? -128 : (half > 126 ? 126 : half);
    return static_cast<uint8_t>(half + TEMPERATURE_BIAS);
  }

  static int16_t decodeTemperature(uint8_t stored) {
    return static_cast<int16_t>((static_cast<int>(stored) - TEMPERATURE_BIAS) * 5);
  }

  // 0.1 %RH to the stored byte. Clamps to 0 .. 100 %RH.
  static uint8_t encodeHumidity(int16_t tenths) {
    const int half = roundToHalf(tenths);
    return static_cast<uint8_t>(half < 0 ? 0 : (half > MAX_HUMIDITY ? MAX_HUMIDITY : half));
  }

  static int16_t decodeHumidity(uint8_t stored) {
    return static_cast<int16_t>(static_cast<int>(stored) * 5);
  }

  bool hasDate() const {
    return has_date_;
  }

  // Calendar date of day 0 is the day before this stamp.
  uint16_t stampYear() const {
    return year_;
  }
  uint8_t stampMonth() const {
    return month_;
  }
  uint8_t stampDay() const {
    return day_;
  }

  bool hasTemperature(size_t sensor, size_t day) const {
    return sensor < SENSORS && day < DAYS && days_[sensor][day][0] != GAP;
  }

  bool hasHumidity(size_t sensor, size_t day) const {
    return sensor < SENSORS && day < DAYS && days_[sensor][day][2] != GAP;
  }

  // Values in 0.1 steps; 0 for a gap or an index out of range (check hasTemperature first).
  int16_t temperatureMin(size_t sensor, size_t day) const {
    return hasTemperature(sensor, day) ? decodeTemperature(days_[sensor][day][0]) : 0;
  }
  int16_t temperatureMax(size_t sensor, size_t day) const {
    return hasTemperature(sensor, day) ? decodeTemperature(days_[sensor][day][1]) : 0;
  }
  int16_t humidityMin(size_t sensor, size_t day) const {
    return hasHumidity(sensor, day) ? decodeHumidity(days_[sensor][day][2]) : 0;
  }
  int16_t humidityMax(size_t sensor, size_t day) const {
    return hasHumidity(sensor, day) ? decodeHumidity(days_[sensor][day][3]) : 0;
  }

  // True when the day holds a mold hour count (a day with a humidity pair and a count).
  bool hasMoldHours(size_t sensor, size_t day) const {
    return sensor < SENSORS && day < DAYS && mold_[sensor][day] != GAP;
  }

  // Hours above mold_alert_limit_rh, 0 to 24; 0 for a gap or an index out of range (check hasMoldHours first).
  uint8_t moldHours(size_t sensor, size_t day) const {
    return hasMoldHours(sensor, day) ? mold_[sensor][day] : 0;
  }

  // Stores one day from values in 0.1 steps. Swaps a reversed pair. `mold_hours` above 24 or GAP, and a day
  // without a humidity pair, store the gap marker for the mold hours.
  void setDay(size_t sensor, size_t day, bool has_temperature, int16_t t_min, int16_t t_max, bool has_humidity,
              int16_t h_min, int16_t h_max, uint8_t mold_hours = GAP) {
    if (sensor >= SENSORS || day >= DAYS) {
      return;
    }
    uint8_t* d = days_[sensor][day];
    if (has_temperature) {
      d[0] = encodeTemperature(t_min < t_max ? t_min : t_max);
      d[1] = encodeTemperature(t_min < t_max ? t_max : t_min);
    } else {
      d[0] = d[1] = GAP;
    }
    if (has_humidity) {
      d[2] = encodeHumidity(h_min < h_max ? h_min : h_max);
      d[3] = encodeHumidity(h_min < h_max ? h_max : h_min);
    } else {
      d[2] = d[3] = GAP;
    }
    mold_[sensor][day] = (has_humidity && mold_hours <= MAX_MOLD_HOURS) ? mold_hours : GAP;
  }

  // Weekday (0 = Sunday) of day `day` counted back from the stamp, or 0xFF without a stamp.
  uint8_t weekdayOf(size_t day) const {
    if (!has_date_ || day >= DAYS) {
      return 0xFF;
    }
    const int64_t days = NtpTime::daysFromCivil(year_, month_, day_) - 1 - static_cast<int64_t>(day);
    return static_cast<uint8_t>(((days + 4) % 7 + 7) % 7); // 1970-01-01 was a Thursday
  }

  // Clears all days of one sensor to gaps (a slot that lost its sensor).
  void clear(size_t sensor) {
    if (sensor < SENSORS) {
      memset(days_[sensor], GAP, sizeof(days_[sensor]));
      memset(mold_[sensor], GAP, sizeof(mold_[sensor]));
    }
  }

  // Swaps all days and mold hours of two sensors (console SLOT SWAP). An equal or bad index changes nothing.
  void swapSensors(size_t a, size_t b) {
    if (a >= SENSORS || b >= SENSORS || a == b) {
      return;
    }
    uint8_t day[sizeof(days_[0])];
    memcpy(day, days_[a], sizeof(day));
    memcpy(days_[a], days_[b], sizeof(day));
    memcpy(days_[b], day, sizeof(day));
    uint8_t mold[sizeof(mold_[0])];
    memcpy(mold, mold_[a], sizeof(mold));
    memcpy(mold_[a], mold_[b], sizeof(mold));
    memcpy(mold_[b], mold, sizeof(mold));
  }

  static constexpr uint16_t ALL_SENSORS = static_cast<uint16_t>((1u << SENSORS) - 1u);

  // Bit mask of the sensors that took part in the last 24 hours: bit 0 is the onboard sensor with a valid
  // reading, bits 1 to 10 are the registry slots with a valid reading that is at most 24 h old. A dead slot
  // keeps its old samples in the 24-hour history, so only this mask tells that they are not from today.
  static uint16_t liveSensors(bool onboard_valid, const SwitchBotRegistry& registry, uint32_t now_ms) {
    uint16_t mask = onboard_valid ? 1u : 0u;
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      const SwitchBotReading& r = registry.slot(i);
      if (r.valid && !r.isStale(now_ms, 24UL * ClimateHistory::SAMPLE_INTERVAL_MS)) {
        mask = static_cast<uint16_t>(mask | (1u << (1 + i)));
      }
    }
    return mask;
  }

  // Midnight step, called at the hourly write. An invalid RTC time changes nothing. The first valid
  // call only stamps the date. A later date shifts the ring by the days that passed (at most 7) and
  // stores the previous day from the 24-hour histories of the sensors in `live_mask` when
  // `history_fresh`; the days in between, a stale history and a sensor outside the mask give gaps. The
  // same day, or a clock that ran back by up to 7 days, changes nothing. A clock that ran back by more
  // than 7 days (a wrong date that a sync corrected) only takes the new stamp. Returns true when the
  // state changed.
  bool advance(const RtcTime& now, const ClimateHistory& history, bool history_fresh,
               uint16_t live_mask = ALL_SENSORS, uint8_t mold_limit_rh = DeviceConfig::DEFAULT_MOLD_ALERT_LIMIT_RH) {
    if (!now.valid || now.oscillator_stopped || now.year < MIN_TRUSTED_YEAR ||
        !NtpTime::isValidDate(now.year, now.month, now.day)) {
      return false;
    }
    if (!has_date_) {
      stamp(now);
      return true;
    }
    const int64_t today = NtpTime::daysFromCivil(now.year, now.month, now.day);
    const int64_t last = NtpTime::daysFromCivil(year_, month_, day_);
    if (today <= last) {
      if (last - today > static_cast<int64_t>(DAYS)) {
        stamp(now); // the stored date was wrong: the bars keep their data, new days follow the right date
        return true;
      }
      return false;
    }
    const size_t shift = (today - last) > static_cast<int64_t>(DAYS) ? DAYS : static_cast<size_t>(today - last);
    for (size_t s = 0; s < SENSORS; ++s) {
      for (size_t k = DAYS; k-- > 0;) {
        if (k >= shift) {
          memcpy(days_[s][k], days_[s][k - shift], DAY_SIZE);
          mold_[s][k] = mold_[s][k - shift];
        } else {
          memset(days_[s][k], GAP, DAY_SIZE);
          mold_[s][k] = GAP;
        }
      }
    }
    if (history_fresh) {
      for (size_t s = 0; s < SENSORS; ++s) {
        if ((live_mask & (1u << s)) == 0) {
          continue; // the shift already left a gap
        }
        int16_t t_lo = 0, t_hi = 0, t_mean = 0, h_lo = 0, h_hi = 0, h_mean = 0;
        const bool has_t = history.temperatures[s].summary(t_lo, t_hi, t_mean);
        const bool has_h = history.slots[s].summary(h_lo, h_hi, h_mean);
        const size_t hours = history.slots[s].countAbove(static_cast<uint16_t>(mold_limit_rh * 10u));
        setDay(s, 0, has_t, t_lo, t_hi, has_h, h_lo, h_hi,
               static_cast<uint8_t>(hours > MAX_MOLD_HOURS ? MAX_MOLD_HOURS : hours));
      }
    }
    stamp(now);
    return true;
  }

  // One row of the daily file for the restore. `day` is the local date in days since 1970-01-01. Bit s of
  // `temp_mask` and `rh_mask` is set when sensor s (0 onboard, 1 to 10 a slot) has both its minimum and
  // its maximum; the values are in 0.1 steps.
  struct RestoreRow {
    int64_t day = 0;
    uint16_t temp_mask = 0;
    uint16_t rh_mask = 0;
    int16_t temp_min[SENSORS] = {};
    int16_t temp_max[SENSORS] = {};
    int16_t rh_min[SENSORS] = {};
    int16_t rh_max[SENSORS] = {};
    uint8_t mold_h[SENSORS]; // hours above the limit, GAP for an empty field
    RestoreRow() {
      memset(mold_h, GAP, sizeof(mold_h));
    }
  };

  // Refills the days from the rows of the daily file after a power-on. `rows` run from the oldest to the
  // newest row; a later row for the same day replaces an earlier one. Only the rows of the 7 days before the RTC date count.
  // It changes nothing for an invalid or untrusted RTC time, for a week that holds a stamp or a value and
  // for rows without a day in that range. Else it stores the days (a missing day stays a gap) and takes
  // the RTC date as stamp. Returns the number of days stored.
  size_t restore(const RestoreRow* rows, size_t count, const RtcTime& now) {
    if (rows == nullptr || count == 0 || has_date_ || !now.valid || now.oscillator_stopped ||
        now.year < MIN_TRUSTED_YEAR || now.year > 2099 || !NtpTime::isValidDate(now.year, now.month, now.day)) {
      return 0;
    }
    for (size_t s = 0; s < SENSORS; ++s) {
      for (size_t k = 0; k < DAYS; ++k) {
        for (size_t b = 0; b < DAY_SIZE; ++b) {
          if (days_[s][k][b] != GAP) {
            return 0;
          }
        }
        if (mold_[s][k] != GAP) {
          return 0;
        }
      }
    }
    const int64_t today = NtpTime::daysFromCivil(now.year, now.month, now.day);
    bool filled[DAYS] = {};
    size_t stored = 0;
    for (size_t i = 0; i < count; ++i) {
      const int64_t age = today - 1 - rows[i].day;
      if (age < 0 || age >= static_cast<int64_t>(DAYS)) {
        continue;
      }
      const size_t k = static_cast<size_t>(age);
      if (rows[i].temp_mask == 0 && rows[i].rh_mask == 0) {
        continue; // a day without any pair is no data
      }
      for (size_t s = 0; s < SENSORS; ++s) {
        const bool has_t = (rows[i].temp_mask & (1u << s)) != 0;
        const bool has_h = (rows[i].rh_mask & (1u << s)) != 0;
        setDay(s, k, has_t, rows[i].temp_min[s], rows[i].temp_max[s], has_h, rows[i].rh_min[s],
               rows[i].rh_max[s], rows[i].mold_h[s]);
      }
      if (!filled[k]) {
        filled[k] = true;
        ++stored;
      }
    }
    if (stored > 0) {
      stamp(now);
    }
    return stored;
  }

  void serialize(uint8_t* out) const {
    size_t pos = 0;
    for (size_t s = 0; s < SENSORS; ++s) {
      for (size_t k = 0; k < DAYS; ++k) {
        memcpy(out + pos, days_[s][k], DAY_SIZE);
        pos += DAY_SIZE;
      }
    }
    for (size_t s = 0; s < SENSORS; ++s) {
      memcpy(out + pos, mold_[s], DAYS);
      pos += DAYS;
    }
    out[pos++] = has_date_ ? 1 : 0;
    out[pos++] = static_cast<uint8_t>(year_ & 0xFF);
    out[pos++] = static_cast<uint8_t>(year_ >> 8);
    out[pos++] = month_;
    out[pos++] = day_;
  }

  // Returns false for a corrupt block; `*this` is then unchanged. A day needs both bytes of a pair
  // as GAP or both in range with minimum <= maximum. A mold count is GAP or 0 to 24 and needs a humidity
  // pair. Without a stamp every day is a gap.
  bool restore(const uint8_t* in) {
    WeekHistory w;
    size_t pos = 0;
    for (size_t s = 0; s < SENSORS; ++s) {
      for (size_t k = 0; k < DAYS; ++k) {
        const uint8_t* d = in + pos;
        const bool t_gap = d[0] == GAP && d[1] == GAP;
        const bool t_ok = d[0] != GAP && d[1] != GAP && d[0] <= d[1];
        const bool h_gap = d[2] == GAP && d[3] == GAP;
        const bool h_ok = d[2] <= d[3] && d[3] <= MAX_HUMIDITY;
        if (!(t_gap || t_ok) || !(h_gap || h_ok)) {
          return false;
        }
        memcpy(w.days_[s][k], d, DAY_SIZE);
        pos += DAY_SIZE;
      }
    }
    for (size_t s = 0; s < SENSORS; ++s) {
      for (size_t k = 0; k < DAYS; ++k) {
        const uint8_t m = in[pos++];
        if (m != GAP && (m > MAX_MOLD_HOURS || w.days_[s][k][2] == GAP)) {
          return false;
        }
        w.mold_[s][k] = m;
      }
    }
    const uint8_t flag = in[pos++];
    const uint16_t year = static_cast<uint16_t>(in[pos] | (in[pos + 1] << 8));
    pos += 2;
    const uint8_t month = in[pos++];
    const uint8_t day = in[pos++];
    if (flag > 1) {
      return false;
    }
    if (flag == 1) {
      if (year < 2000 || year > 2099 || !NtpTime::isValidDate(year, month, day)) {
        return false;
      }
      w.has_date_ = true;
      w.year_ = year;
      w.month_ = month;
      w.day_ = day;
    } else {
      if (year != 0 || month != 0 || day != 0) {
        return false;
      }
      for (size_t s = 0; s < SENSORS; ++s) {
        for (size_t k = 0; k < DAYS; ++k) {
          for (size_t b = 0; b < DAY_SIZE; ++b) {
            if (w.days_[s][k][b] != GAP) {
              return false;
            }
          }
          if (w.mold_[s][k] != GAP) {
            return false;
          }
        }
      }
    }
    *this = w;
    return true;
  }

private:
  void stamp(const RtcTime& now) {
    has_date_ = true;
    year_ = now.year;
    month_ = now.month;
    day_ = now.day;
  }

  uint8_t days_[SENSORS][DAYS][DAY_SIZE];
  uint8_t mold_[SENSORS][DAYS];
  bool has_date_ = false;
  uint16_t year_ = 0;
  uint8_t month_ = 0;
  uint8_t day_ = 0;
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

  // A flatter temperature curve than 2.0 degC would amplify sensor noise into full-height swings.
  static constexpr int16_t TEMPERATURE_MIN_SPAN = 20; // 0.1 degC steps

  // Value range of a temperature history. A flat curve gets a span of TEMPERATURE_MIN_SPAN centered
  // on the value, shifted to stay inside -100..+100 degC. Returns false for an empty history.
  static bool computeTemperatureScale(const TemperatureHistory& history, Scale& out) {
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
    if (hi - lo < TEMPERATURE_MIN_SPAN) {
      const int center = (static_cast<int>(lo) + hi) / 2;
      int lo_v = center - TEMPERATURE_MIN_SPAN / 2;
      if (lo_v < TemperatureHistory::MIN_VALUE) lo_v = TemperatureHistory::MIN_VALUE;
      if (lo_v > TemperatureHistory::MAX_VALUE - TEMPERATURE_MIN_SPAN) {
        lo_v = TemperatureHistory::MAX_VALUE - TEMPERATURE_MIN_SPAN;
      }
      out.lo = static_cast<int16_t>(lo_v);
      out.hi = static_cast<int16_t>(lo_v + TEMPERATURE_MIN_SPAN);
    } else {
      out.lo = lo;
      out.hi = hi;
    }
    return true;
  }

  // Draws the temperature curve into the box (x, y, w, h). Every pixel stays inside the box.
  // Returns false and draws nothing for an empty history or an empty box.
  static bool renderTemperature(
      St7305Framebuffer& fb,
      uint16_t x,
      uint16_t y,
      uint16_t w,
      uint16_t h,
      const TemperatureHistory& history,
      bool black = true) {
    Scale scale;
    if (w == 0 || h == 0 || !computeTemperatureScale(history, scale)) {
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

  // Bars of the week chart: 7 slots of w / 7 px, the newest day at the right edge. A bar is WEEK_BAR_W px
  // wide, centered in its slot.
  static constexpr uint16_t WEEK_BAR_W = 24;

  // Left edge (offset inside the box) of the bar in column `col` (0 is the oldest day).
  static uint16_t weekBarX(size_t col, uint16_t w) {
    const uint16_t slot = static_cast<uint16_t>(w / WeekHistory::DAYS);
    return static_cast<uint16_t>(col * slot + (slot > WEEK_BAR_W ? (slot - WEEK_BAR_W) / 2 : 0));
  }

  // Scale over the valid days of one sensor. `humidity` selects the quantity. A flat week gets the
  // minimum span (2.0 degC or 5.0 %RH), shifted to stay in range. Returns false without a valid day.
  static bool computeWeekScale(const WeekHistory& week, size_t sensor, bool humidity, Scale& out) {
    bool found = false;
    int lo = 0;
    int hi = 0;
    for (size_t k = 0; k < WeekHistory::DAYS; ++k) {
      const bool has = humidity ? week.hasHumidity(sensor, k) : week.hasTemperature(sensor, k);
      if (!has) {
        continue;
      }
      const int d_lo = humidity ? week.humidityMin(sensor, k) : week.temperatureMin(sensor, k);
      const int d_hi = humidity ? week.humidityMax(sensor, k) : week.temperatureMax(sensor, k);
      lo = found && lo < d_lo ? lo : d_lo;
      hi = found && hi > d_hi ? hi : d_hi;
      found = true;
    }
    if (!found) {
      return false;
    }
    const int min_span = humidity ? MIN_SPAN : TEMPERATURE_MIN_SPAN;
    const int min_value = humidity ? HumidityHistory::MIN_VALUE : -640;
    const int max_value = humidity ? HumidityHistory::MAX_VALUE : 630;
    if (hi - lo < min_span) {
      int lo_v = (lo + hi) / 2 - min_span / 2;
      lo_v = lo_v < min_value ? min_value : lo_v;
      lo_v = lo_v > max_value - min_span ? max_value - min_span : lo_v;
      lo = lo_v;
      hi = lo_v + min_span;
    }
    out.lo = static_cast<int16_t>(lo);
    out.hi = static_cast<int16_t>(hi);
    return true;
  }

  // Draws one bar from the minimum to the maximum for each valid day into the box (x, y, w, h). A day
  // without samples leaves its slot empty. Every pixel stays inside the box. Returns false and draws
  // nothing without a valid day or for an empty box.
  static bool renderWeek(
      St7305Framebuffer& fb,
      uint16_t x,
      uint16_t y,
      uint16_t w,
      uint16_t h,
      const WeekHistory& week,
      size_t sensor,
      bool humidity,
      bool black = true) {
    Scale scale;
    if (w < WeekHistory::DAYS || h == 0 || !computeWeekScale(week, sensor, humidity, scale)) {
      return false;
    }
    for (size_t k = 0; k < WeekHistory::DAYS; ++k) {
      const bool has = humidity ? week.hasHumidity(sensor, k) : week.hasTemperature(sensor, k);
      if (!has) {
        continue;
      }
      const int16_t v_lo = humidity ? week.humidityMin(sensor, k) : week.temperatureMin(sensor, k);
      const int16_t v_hi = humidity ? week.humidityMax(sensor, k) : week.temperatureMax(sensor, k);
      const uint16_t top = valueToRow(v_hi, scale, h);
      const uint16_t bottom = valueToRow(v_lo, scale, h);
      const uint16_t bx = weekBarX(WeekHistory::DAYS - 1 - k, w);
      fb.fillRect(static_cast<uint16_t>(x + bx), static_cast<uint16_t>(y + top), WEEK_BAR_W,
                  static_cast<uint16_t>(bottom - top + 1), black);
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
