#pragma once

#include <stdint.h>
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

} // namespace rlcd_panel
