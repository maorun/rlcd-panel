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
};

class BatteryMonitor {
public:
  // Standard single-cell Li-ion (18650) threshold voltages
  static constexpr float V_EMPTY = 3.20f;
  static constexpr float V_FULL  = 4.20f;

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

#ifdef ARDUINO
  static BatteryReading read() {
    // analogReadMilliVolts utilizes ESP32-S3 eFuse factory calibration curve
    const uint32_t raw_mv = analogReadMilliVolts(PIN_BAT_ADC);
    return decodeMillivolts(raw_mv);
  }
#endif
};

} // namespace rlcd_panel
