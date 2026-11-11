#pragma once

#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include "board_pins.h"

#ifdef ARDUINO
#include <Arduino.h>
#endif

namespace rlcd_panel {

struct BatteryReading {
  bool valid = false;
  float voltage_v = 0.0f;
  int8_t percentage = -1; // 0..100%, or -1 if invalid
  bool usb_powered = false;
  bool low_battery = false; // latched alert with hysteresis, see BatteryMonitor::updateLowBattery
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

  static BatteryReading decodeMillivolts(uint32_t adc_millivolts) {
    BatteryReading r;
    r.voltage_v = (static_cast<float>(adc_millivolts) * BAT_VOLTAGE_DIVIDER_RATIO) / 1000.0f;
    r.percentage = voltageToPercent(r.voltage_v);
    r.usb_powered = (r.voltage_v >= 4.25f);
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

#ifdef ARDUINO
  static BatteryReading read() {
    // analogReadMilliVolts utilizes ESP32-S3 eFuse factory calibration curve
    const uint32_t raw_mv = analogReadMilliVolts(PIN_BAT_ADC);
    return decodeMillivolts(raw_mv);
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
  BatteryRuntime estimate(const BatteryReading& reading) const {
    BatteryRuntime r;
    double slope_v_h = 0.0;
    if (!reading.valid || reading.usb_powered || !plausible(reading.voltage_v) ||
        !slope(slope_v_h) || !(slope_v_h <= -MIN_DISCHARGE_V_PER_H)) {
      return r;
    }
    const double left_v = static_cast<double>(reading.voltage_v) - BatteryMonitor::V_EMPTY;
    const double hours = left_v > 0.0 ? left_v / -slope_v_h : 0.0;
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

} // namespace rlcd_panel
