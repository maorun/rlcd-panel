#pragma once

#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include "board_pins.h"

#ifdef ARDUINO
#include <Arduino.h>
#include <Preferences.h>
#endif

namespace rlcd_panel {

struct BatteryReading {
  bool valid = false;
  float voltage_v = 0.0f;
  int8_t percentage = -1; // 0..100%, or -1 if invalid
  bool usb_powered = false;
  bool low_battery = false; // latched alert with hysteresis, see BatteryMonitor::updateLowBattery
  bool empty_protection = false; // latched deep sleep protection, see BatteryMonitor::updateEmptyProtection
};

class BatteryMonitor {
public:
  // Standard single-cell Li-ion (18650) threshold voltages
  static constexpr float V_EMPTY = 3.20f;
  static constexpr float V_FULL  = 4.20f;

  // Low battery alert with hysteresis: set at <= 15 % or < 3.40 V, cleared only at >= 20 % and
  // >= 3.50 V. The gap prevents flicker when the load makes the voltage sag around the limit.
  // On the linear 3.20..4.20 V curve, 3.40 V equals 20 % and 15 % equals 3.35 V, so the voltage
  // rule sets the alert first on a real cell. The percentage rule covers a skewed reading.
  static constexpr int8_t LOW_PCT_SET    = 15;
  static constexpr int8_t LOW_PCT_CLEAR  = 20;
  static constexpr float  LOW_V_SET      = 3.40f;
  static constexpr float  LOW_V_CLEAR    = 3.50f;

  // Deep sleep protection against a deep discharge of the cell: set below 3.30 V on battery power,
  // cleared at 3.50 V or more or on USB power. The gap prevents flicker while the unloaded cell
  // recovers its voltage in sleep.
  static constexpr float PROTECT_V_SET   = 3.30f;
  static constexpr float PROTECT_V_CLEAR = 3.50f;

  static int8_t voltageToPercent(float voltage_v) {
    if (voltage_v <= 0.5f) {
      return -1; // No battery connected or reading invalid
    }
    if (voltage_v <= V_EMPTY) {
      return 0;
    }
    if (voltage_v >= V_FULL) {
      return 100;
    }
    const float pct = ((voltage_v - V_EMPTY) / (V_FULL - V_EMPTY)) * 100.0f;
    return static_cast<int8_t>(pct + 0.5f);
  }

  // A charger or a floating pin lifts the cell voltage to 4.25 V or more. An installed cell clamps
  // it to 4.20 V, so the USB controller status `usb_plugged` also marks USB power. Host tests
  // inject `usb_plugged`; the firmware passes HWCDC::isPlugged().
  static constexpr float USB_VOLTAGE_V = 4.25f;

  // `offset_mv` (config.ini key battery_offset_mv) corrects the tolerance of the divider and of the
  // ADC. It is added to the cell voltage, so every limit and the history see the corrected value.
  static BatteryReading decodeMillivolts(uint32_t adc_millivolts, bool usb_plugged = false, int16_t offset_mv = 0) {
    BatteryReading r;
    r.voltage_v = (static_cast<float>(adc_millivolts) * BAT_VOLTAGE_DIVIDER_RATIO + static_cast<float>(offset_mv)) / 1000.0f;
    r.percentage = voltageToPercent(r.voltage_v);
    r.usb_powered = usb_plugged || (r.voltage_v >= USB_VOLTAGE_V);
    r.valid = (r.voltage_v > 0.5f);
    return r;
  }

  // Returns the new alert state for `reading`. `was_low` is the state of the previous wake cycle.
  // Without a battery or while USB powered, the alert stays off.
  static bool updateLowBattery(const BatteryReading& reading, bool was_low) {
    if (!reading.valid || reading.usb_powered || reading.percentage < 0) {
      return false;
    }
    const bool below_set = reading.percentage <= LOW_PCT_SET || reading.voltage_v < LOW_V_SET;
    if (below_set) {
      return true;
    }
    const bool above_clear = reading.percentage >= LOW_PCT_CLEAR && reading.voltage_v >= LOW_V_CLEAR;
    return was_low && !above_clear;
  }

  // Charge-complete latch. It sets at 100 % on USB power and holds down to CHARGE_FULL_CLEAR_PCT,
  // so a reading that swings between 99 % and 100 % never chimes again. Battery power clears it.
  // An invalid reading holds the latch.
  static constexpr int CHARGE_FULL_SET_PCT = 100;
  static constexpr int CHARGE_FULL_CLEAR_PCT = 95;
  static bool updateChargeFull(const BatteryReading& reading, bool was_full) {
    if (!reading.valid || reading.percentage < 0) {
      return was_full;
    }
    if (!reading.usb_powered) {
      return false;
    }
    if (reading.percentage >= CHARGE_FULL_SET_PCT) {
      return true;
    }
    return was_full && reading.percentage >= CHARGE_FULL_CLEAR_PCT;
  }

  // Returns the new protection state for `reading`. `was_latched` is the state of the previous
  // wake cycle. USB power ends the protection. An invalid reading (no battery or a failed ADC
  // read) never sets it and holds a set latch, because a bad sample must not wake the modules.
  static bool updateEmptyProtection(const BatteryReading& reading, bool was_latched) {
    if (!reading.valid) {
      return was_latched;
    }
    if (reading.usb_powered) {
      return false;
    }
    if (reading.voltage_v < PROTECT_V_SET) {
      return true;
    }
    return was_latched && reading.voltage_v < PROTECT_V_CLEAR;
  }

#ifdef ARDUINO
  static BatteryReading read(int16_t offset_mv = 0) {
    // analogReadMilliVolts utilizes ESP32-S3 eFuse factory calibration curve
    const uint32_t raw_mv = analogReadMilliVolts(PIN_BAT_ADC);
    return decodeMillivolts(raw_mv, HWCDC::isPlugged(), offset_mv);
  }

  // Mean of four ADC samples 5 ms apart. The protection check uses it, so one load dip cannot
  // latch the warning.
  static BatteryReading readAveraged(int16_t offset_mv = 0) {
    uint32_t sum_mv = 0;
    for (int i = 0; i < 4; ++i) {
      sum_mv += analogReadMilliVolts(PIN_BAT_ADC);
      delay(5);
    }
    return decodeMillivolts(sum_mv / 4, HWCDC::isPlugged(), offset_mv);
  }
#endif
};

// Remaining runtime derived from the discharge curve. `valid` is false when no estimate exists.
struct BatteryRuntime {
  bool valid = false;
  uint32_t hours = 0; // hours until the cell reaches BatteryMonitor::V_EMPTY

  // "RUNTIME: ~NN H" below 48 h, "RUNTIME: ~N D" from 48 h (rounded days, at most 99), else
  // "RUNTIME: --". Widest line: "RUNTIME: ~47 H" = 14 glyphs = 168 px at scale 2.
  void describe(char* out, size_t capacity) const {
    if (!valid) {
      snprintf(out, capacity, "RUNTIME: --");
    } else if (hours < 48) {
      snprintf(out, capacity, "RUNTIME: ~%lu H", static_cast<unsigned long>(hours));
    } else {
      const unsigned long days = static_cast<unsigned long>((hours + 12) / 24);
      snprintf(out, capacity, "RUNTIME: ~%lu D", days > 99 ? 99UL : days);
    }
  }
};

// Measured awake time of the timer wakeups (ms from wake to just before deep sleep). Keeps the last
// value, the maximum and a running mean over the last 24 samples. Every value saturates at 65535 ms.
// The state is 6 bytes: last (uint16), max (uint16) and one uint16 that packs the sample count
// (low 5 bits, 0 to 24) and the mean in steps of 32 ms (high 11 bits). Once 24 samples exist, each
// new sample moves the mean by 1/24 of the difference, so the oldest sample fades out.
// A power-on, a KEY wakeup and a cycle on USB power add no sample (`wanted`).
class AwakeTimeStats {
public:
  static constexpr uint8_t WINDOW = 24;
  static constexpr uint8_t MIN_SAMPLES = 8; // fewer samples keep the constant BatteryRuntime AWAKE_MS
  static constexpr uint32_t MEAN_STEP_MS = 32;
  static constexpr uint16_t MAX_MEAN_STEPS = 2047;
  static constexpr size_t SERIALIZED_SIZE = 6;

  // True only for a timer wakeup on battery power.
  static bool wanted(bool timer_wakeup, bool usb_powered) { return timer_wakeup && !usb_powered; }

  void clear() { *this = AwakeTimeStats{}; }

  void record(uint32_t awake_ms) {
    const uint16_t v = awake_ms > 65535u ? 65535u : static_cast<uint16_t>(awake_ms);
    last_ = v;
    if (count_ == 0 || v > max_) {
      max_ = v;
    }
    if (count_ < WINDOW) {
      ++count_;
    }
    // Running mean with the weight 1/count (1/24 at a full window), rounded to the nearest step. A
    // difference below half of count * 32 ms (at most 384 ms) gives no step: the mean has this dead zone.
    const int32_t target = static_cast<int32_t>(v);
    const int32_t current = static_cast<int32_t>(mean_steps_) * static_cast<int32_t>(MEAN_STEP_MS);
    const int32_t delta = target - current;
    const int32_t half = static_cast<int32_t>(count_) * static_cast<int32_t>(MEAN_STEP_MS) / 2;
    const int32_t divisor = static_cast<int32_t>(count_) * static_cast<int32_t>(MEAN_STEP_MS);
    const int32_t change = (delta >= 0 ? delta + half : delta - half) / divisor;
    int32_t next = static_cast<int32_t>(mean_steps_) + change;
    next = next < 0 ? 0 : (next > MAX_MEAN_STEPS ? MAX_MEAN_STEPS : next);
    mean_steps_ = static_cast<uint16_t>(next);
  }

  uint8_t samples() const { return count_; }
  uint16_t lastMs() const { return last_; }
  uint16_t maxMs() const { return max_; }
  uint32_t meanMs() const { return static_cast<uint32_t>(mean_steps_) * MEAN_STEP_MS; }
  bool enough() const { return count_ >= MIN_SAMPLES; }

  // Packs the state for the RTC copy.
  void serialize(uint8_t* out) const {
    out[0] = static_cast<uint8_t>(last_ & 0xFF);
    out[1] = static_cast<uint8_t>(last_ >> 8);
    out[2] = static_cast<uint8_t>(max_ & 0xFF);
    out[3] = static_cast<uint8_t>(max_ >> 8);
    const uint16_t packed = static_cast<uint16_t>((mean_steps_ << 5) | count_);
    out[4] = static_cast<uint8_t>(packed & 0xFF);
    out[5] = static_cast<uint8_t>(packed >> 8);
  }

  // Returns false and keeps the state when the bytes are corrupt: a count above WINDOW, a last
  // value above the maximum, a mean more than one step above the maximum, or values with no sample.
  bool restore(const uint8_t* in) {
    const uint16_t last = static_cast<uint16_t>(in[0] | (in[1] << 8));
    const uint16_t max = static_cast<uint16_t>(in[2] | (in[3] << 8));
    const uint16_t packed = static_cast<uint16_t>(in[4] | (in[5] << 8));
    const uint8_t count = static_cast<uint8_t>(packed & 0x1F);
    const uint16_t steps = static_cast<uint16_t>(packed >> 5);
    if (count > WINDOW || last > max || static_cast<uint32_t>(steps) * MEAN_STEP_MS > static_cast<uint32_t>(max) + MEAN_STEP_MS || (count == 0 && (last != 0 || max != 0 || steps != 0))) {
      return false;
    }
    last_ = last;
    max_ = max;
    count_ = count;
    mean_steps_ = steps;
    return true;
  }

private:
  uint16_t last_ = 0;
  uint16_t max_ = 0;
  uint16_t mean_steps_ = 0;
  uint8_t count_ = 0;
};

// Ring of 24 battery voltage samples (millivolts) with their clock stamps for the runtime
// estimate. Samples are one hour apart plus the wake interval, so the fit uses the stamps.
class BatteryHistory {
public:
  static constexpr size_t CAPACITY = 24;
  static constexpr size_t MIN_SAMPLES = 6;
  static constexpr uint32_t SAMPLE_INTERVAL_MS = 3600UL * 1000UL;
  static constexpr uint16_t MIN_VALID_MV = 2500;
  static constexpr uint16_t MAX_VALID_MV = 4300;
  // A rise of at least 0.10 V between two samples means the cell was charged.
  static constexpr uint16_t CHARGE_STEP_MV = 100;
  // Slopes flatter than 0.01 mV per hour give no usable runtime.
  static constexpr double MIN_DISCHARGE_V_PER_H = 1e-5;
  // Awake time of one wake cycle with the default 4 s scan (scan, sensors, render), in ms.
  static constexpr uint32_t AWAKE_MS = 10000;
  static constexpr uint32_t MIN_AWAKE_MS = 2000;

  static bool plausible(float voltage_v) {
    return voltage_v >= MIN_VALID_MV / 1000.0f && voltage_v <= MAX_VALID_MV / 1000.0f;
  }

  size_t size() const { return count_; }
  uint8_t head() const { return head_; }
  uint16_t rawSample(size_t k) const { return k < CAPACITY ? samples_[k] : 0; }
  uint16_t at(size_t index) const {
    return index < count_ ? samples_[(head_ + CAPACITY - count_ + index) % CAPACITY] : 0;
  }
  uint32_t rawTime(size_t k) const { return k < CAPACITY ? times_[k] : 0; }
  uint32_t timeAt(size_t index) const {
    return index < count_ ? times_[(head_ + CAPACITY - count_ + index) % CAPACITY] : 0;
  }

  void clear() {
    count_ = 0;
    head_ = 0;
    for (size_t k = 0; k < CAPACITY; ++k) {
      samples_[k] = 0;
      times_[k] = 0;
    }
  }

  bool sampleDue(uint32_t now_ms) const {
    return !has_sampled || static_cast<uint32_t>(now_ms - last_sample_ms) >= SAMPLE_INTERVAL_MS;
  }

  // Appends a sample one interval after the newest sample (stamp 0 for the first one).
  bool push(float voltage_v) {
    return push(voltage_v, count_ > 0 ? timeAt(count_ - 1) + SAMPLE_INTERVAL_MS : 0);
  }

  // Appends a sample taken at `now_ms`. A NaN or implausible voltage is rejected and changes
  // nothing. A rise of CHARGE_STEP_MV or more over the lowest stored sample clears the older
  // samples first, so a slow charge counts too. A short load sag followed by a recovery of
  // 0.10 V also clears the history; the estimate then restarts after MIN_SAMPLES hours.
  bool push(float voltage_v, uint32_t now_ms) {
    if (!(voltage_v == voltage_v) || !plausible(voltage_v)) {
      return false;
    }
    const uint16_t mv = static_cast<uint16_t>(voltage_v * 1000.0f + 0.5f);
    if (count_ > 0) {
      uint16_t lowest = at(0);
      for (size_t i = 1; i < count_; ++i) {
        lowest = at(i) < lowest ? at(i) : lowest;
      }
      if (mv >= lowest + CHARGE_STEP_MV) {
        clear();
      }
    }
    samples_[head_] = mv;
    times_[head_] = now_ms;
    head_ = static_cast<uint8_t>((head_ + 1) % CAPACITY);
    if (count_ < CAPACITY) {
      ++count_;
    }
    return true;
  }

  // Hourly gate for the wake cycle. USB power clears the history: a charging cell has no
  // discharge curve. Returns true when a sample was added.
  bool record(uint32_t now_ms, const BatteryReading& reading) {
    if (reading.valid && reading.usb_powered) {
      clear();
      return false;
    }
    if (!sampleDue(now_ms) || !reading.valid || !push(reading.voltage_v, now_ms)) {
      return false;
    }
    last_sample_ms = now_ms;
    has_sampled = true;
    return true;
  }

  // Rebuilds the ring from stored bytes. Returns false when count, head or a live sample is out
  // of range or the live stamps do not increase; the ring stays unchanged then.
  bool restore(uint8_t count, uint8_t head, const uint16_t* raw, const uint32_t* stamps) {
    if (count > CAPACITY || head >= CAPACITY) {
      return false;
    }
    for (size_t k = 0; k < count; ++k) {
      const uint16_t mv = raw[(head + CAPACITY - count + k) % CAPACITY];
      if (mv < MIN_VALID_MV || mv > MAX_VALID_MV) {
        return false;
      }
      if (k > 0) {
        const uint32_t prev = stamps[(head + CAPACITY - count + k - 1) % CAPACITY];
        const uint32_t step = static_cast<uint32_t>(stamps[(head + CAPACITY - count + k) % CAPACITY] - prev);
        if (step == 0 || step >= 0x80000000u) {
          return false;
        }
      }
    }
    count_ = count;
    head_ = head;
    for (size_t k = 0; k < CAPACITY; ++k) {
      samples_[k] = raw[k];
      times_[k] = stamps[k];
    }
    return true;
  }

  // Least-squares slope (V per hour) over the samples against their elapsed hours. Needs
  // MIN_SAMPLES.
  bool slope(double& volts_per_hour) const {
    if (count_ < MIN_SAMPLES) {
      return false;
    }
    double sx = 0, sy = 0, sxy = 0, sxx = 0;
    const double n = static_cast<double>(count_);
    for (size_t i = 0; i < count_; ++i) {
      const double x = static_cast<double>(static_cast<uint32_t>(timeAt(i) - timeAt(0))) /
                       static_cast<double>(SAMPLE_INTERVAL_MS);
      const double y = at(i) / 1000.0;
      sx += x;
      sy += y;
      sxy += x * y;
      sxx += x * x;
    }
    const double denom = n * sxx - sx * sx;
    if (denom <= 0.0) {
      return false;
    }
    volts_per_hour = (n * sxy - sx * sy) / denom;
    return true;
  }

  // Runtime until V_EMPTY at the fitted discharge rate. The straight line ignores the flat
  // plateau and the knee of a Li-ion cell near empty, so the value is an estimate that reads
  // long on the plateau. Invalid on USB power, without a battery,
  // with fewer than MIN_SAMPLES, with a flat or rising curve, and with an implausible voltage.
  // `scan_saved_ms` is the scan time that an early stop saved in the last scan. The awake time of
  // one wake cycle (AWAKE_MS) shrinks by that time and the runtime grows by the same ratio. The
  // saved time is capped, so the awake time never falls below MIN_AWAKE_MS.
  // With `awake` holding MIN_SAMPLES or more (and a mean above MIN_AWAKE_MS), `scan_saved_ms` is
  // ignored: the mean already includes the early scan stops.
  BatteryRuntime estimate(const BatteryReading& reading, uint32_t scan_saved_ms = 0,
                          const AwakeTimeStats* awake = nullptr) const {
    BatteryRuntime r;
    double slope_v_h = 0.0;
    if (!reading.valid || reading.usb_powered || !plausible(reading.voltage_v) ||
        !slope(slope_v_h) || !(slope_v_h <= -MIN_DISCHARGE_V_PER_H)) {
      return r;
    }
    const double left_v = static_cast<double>(reading.voltage_v) - BatteryMonitor::V_EMPTY;
    // The measured mean already contains the saving of an early scan stop. With the mean in use,
    // `scan_saved_ms` has no effect: subtracting it again would count it twice, so the scale is 1.
    // A mean at or below MIN_AWAKE_MS is no plausible measurement: the constant and the saved time apply.
    const bool measured = awake != nullptr && awake->enough() && awake->meanMs() > MIN_AWAKE_MS;
    const uint32_t awake_ms = measured ? awake->meanMs() : AWAKE_MS;
    const uint32_t saved_ms = measured ? 0 : (scan_saved_ms > AWAKE_MS - MIN_AWAKE_MS ? AWAKE_MS - MIN_AWAKE_MS : scan_saved_ms);
    const double scale = static_cast<double>(awake_ms) / static_cast<double>(awake_ms - saved_ms);
    const double hours = left_v > 0.0 ? left_v / -slope_v_h * scale : 0.0;
    r.valid = true;
    r.hours = hours > 99.0 * 24.0 + 12.0 ? 99u * 24u + 12u : static_cast<uint32_t>(hours + 0.5);
    return r;
  }

  uint32_t last_sample_ms = 0;
  bool has_sampled = false;

private:
  uint16_t samples_[CAPACITY] = {};
  uint32_t times_[CAPACITY] = {};
  uint8_t head_ = 0;
  uint8_t count_ = 0;
};

// Wear counter of the cell. Every percentage point of discharge adds to `pending_pct`; 100 points
// are one full cycle. The total lives in NVS (it survives a cell removal, RTC memory does not) and
// is written once per charge event: when USB power starts after a discharge, or when the total
// crosses a whole cycle. The unwritten part and the tracking state sit in RTC memory.
// A drop of more than MAX_STEP_PCT between two readings (cell swap, bad sample) only moves the
// baseline. A rise of up to RISE_HYSTERESIS_PCT on battery power is ADC noise and keeps the
// baseline, so a jittering reading is not counted twice.
class CycleTracker {
public:
  static constexpr int8_t MAX_STEP_PCT = 40;
  static constexpr int8_t RISE_HYSTERESIS_PCT = 3;
  static constexpr uint32_t MAX_TOTAL_PCT = 999990; // 9999.9 cycles
  static constexpr uint32_t STATE_MAGIC = 0x43594331; // "CYC1"

  // Fixed-layout copy for RTC memory. `check` guards against blank or damaged memory.
  struct RtcState {
    uint32_t magic = 0;
    uint32_t pending_pct = 0;
    int8_t last_pct = -1;
    uint8_t flags = 0; // bit 0: charging, bit 1: write retry
    uint16_t reserved = 0;
    uint32_t check = 0;
  };

  uint32_t storedPct() const { return stored_pct_; }
  uint32_t pendingPct() const { return pending_pct_; }
  uint32_t totalPct() const {
    const uint32_t t = stored_pct_ + pending_pct_;
    return t > MAX_TOTAL_PCT ? MAX_TOTAL_PCT : t;
  }
  bool storeKnown() const { return store_known_; }

  // Cycles in tenths (0.1 cycle = 10 percentage points); negative when the NVS total is unknown.
  int32_t cycleTenths() const {
    return store_known_ ? static_cast<int32_t>(totalPct() / 10) : -1;
  }

  // "CYCLES: N.N", or "CYCLES: --" for a negative value. Widest: "CYCLES: 9999.9" = 14 glyphs
  // = 168 px at scale 2.
  static void describe(int32_t tenths, char* out, size_t capacity) {
    if (tenths < 0) {
      snprintf(out, capacity, "CYCLES: --");
    } else {
      snprintf(out, capacity, "CYCLES: %ld.%ld", static_cast<long>(tenths / 10),
               static_cast<long>(tenths % 10));
    }
  }

  // Sets the NVS total; `known` is false when NVS could not be read.
  void setStored(uint32_t pct, bool known) {
    stored_pct_ = pct > MAX_TOTAL_PCT ? MAX_TOTAL_PCT : pct;
    store_known_ = known;
  }

  // Adds the reading. Returns true when the caller must write totalPct() to NVS now, then call
  // commit(). An invalid reading changes nothing.
  bool update(const BatteryReading& reading) {
    if (!reading.valid || reading.percentage < 0 || reading.percentage > 100) {
      return false;
    }
    if (reading.usb_powered) {
      const bool event = pending_pct_ > 0 && (!charging_ || retry_);
      charging_ = true;
      last_pct_ = -1; // a charging cell has no discharge baseline
      if (event && !store_known_) {
        retry_ = true; // the write is owed: do it as soon as NVS reads again
      }
      return event && store_known_;
    }
    charging_ = false;
    if (last_pct_ < 0) {
      last_pct_ = reading.percentage;
      return retry_ && pending_pct_ > 0 && store_known_;
    }
    const int drop = last_pct_ - reading.percentage;
    if (drop > 0) {
      if (drop <= MAX_STEP_PCT) {
        pending_pct_ += static_cast<uint32_t>(drop);
      }
      last_pct_ = reading.percentage;
    } else if (-drop > RISE_HYSTERESIS_PCT) {
      // A charger without a USB host reads as battery power: the rise is a charge event too.
      last_pct_ = reading.percentage;
      retry_ = retry_ || pending_pct_ > 0;
    }
    if (!store_known_) {
      return false;
    }
    const bool milestone = (stored_pct_ + pending_pct_) / 100 > stored_pct_ / 100;
    return milestone || (retry_ && pending_pct_ > 0);
  }

  // Result of the NVS write that update() requested. On failure the pending points stay and each
  // following wake cycle retries until a write succeeds (a failed write costs no flash wear).
  void commit(bool saved) {
    if (saved) {
      stored_pct_ = totalPct();
      pending_pct_ = 0;
      retry_ = false;
    } else {
      retry_ = true;
    }
  }

  // One wake cycle: reads the NVS total, adds `reading` and writes once when update() asks for it.
  // `Store` provides `bool load(uint32_t&)` and `bool save(uint32_t)` (CycleStore on the board).
  template <typename Store>
  void step(const BatteryReading& reading, Store& store) {
    uint32_t stored = 0;
    const bool known = store.load(stored);
    setStored(stored, known);
    if (update(reading)) {
      commit(store.save(totalPct()));
    }
  }

  RtcState toRtc() const {
    RtcState r;
    r.magic = STATE_MAGIC;
    r.pending_pct = pending_pct_;
    r.last_pct = last_pct_;
    r.flags = static_cast<uint8_t>((charging_ ? 1 : 0) | (retry_ ? 2 : 0));
    r.check = checkOf(r);
    return r;
  }

  // Restores the tracking state; false (state unchanged) for blank or damaged memory.
  bool fromRtc(const RtcState& r) {
    if (r.magic != STATE_MAGIC || r.check != checkOf(r) || (r.flags & ~3u) != 0 ||
        r.last_pct < -1 || r.last_pct > 100 || r.pending_pct > MAX_TOTAL_PCT) {
      return false;
    }
    pending_pct_ = r.pending_pct;
    last_pct_ = r.last_pct;
    charging_ = (r.flags & 1) != 0;
    retry_ = (r.flags & 2) != 0;
    return true;
  }

private:
  static uint32_t checkOf(const RtcState& r) {
    return (r.magic ^ 0xA5C3F00Fu) + r.pending_pct * 31u + static_cast<uint8_t>(r.last_pct) * 7u +
           r.flags * 131u;
  }

  uint32_t stored_pct_ = 0;
  uint32_t pending_pct_ = 0;
  int8_t last_pct_ = -1;
  bool charging_ = false;
  bool retry_ = false;
  bool store_known_ = false;
};

#ifdef ARDUINO
// NVS storage of the discharge total in percentage points. A missing key reads as 0 (new cell);
// an unreadable namespace reports unknown.
class CycleStore {
public:
  bool load(uint32_t& pct) const {
    Preferences prefs;
    if (!prefs.begin(NAMESPACE, false)) { // read-write open creates the namespace on first use
      return false;
    }
    pct = prefs.getUInt(KEY, 0);
    prefs.end();
    return true;
  }

  bool save(uint32_t pct) const {
    Preferences prefs;
    if (!prefs.begin(NAMESPACE, false)) {
      return false;
    }
    const bool ok = prefs.putUInt(KEY, pct) == sizeof(uint32_t);
    prefs.end();
    return ok;
  }

private:
  static constexpr const char* NAMESPACE = "rlcd";
  static constexpr const char* KEY = "cycle_count";
};
#endif

} // namespace rlcd_panel
