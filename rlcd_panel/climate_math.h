#pragma once

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stddef.h>

namespace rlcd_panel {

// Dew point (Magnus formula) and vapor pressure deficit from temperature and relative humidity.
struct ClimateDerived {
  bool valid = false;
  float dew_point_c = 0.0f;
  float vpd_kpa = 0.0f;
};

// Comfort zone of one room. INVALID stands for NaN, infinite or out-of-range input.
enum class ComfortZone : uint8_t { INVALID, COLD, WARM, DRY, HUMID, OK };

class ClimateMath {
public:
  static constexpr float MAGNUS_A = 17.62f;
  static constexpr float MAGNUS_B_C = 243.12f;
  static constexpr float SATURATION_PRESSURE_KPA_AT_0C = 0.61078f;
  // Sensor range: below -45 C the Magnus formula leaves its fitted range.
  static constexpr float MIN_TEMP_C = -45.0f;
  static constexpr float MAX_TEMP_C = 60.0f;
  // Longest text from format(): "DP -45.0C VPD 9.99K" is 19 glyphs.
  static constexpr size_t LINE_MAX_CHARS = 20;

  // Temperature in the display unit: 'F' gives Fahrenheit, any other unit keeps Celsius.
  static float toDisplay(float temp_c, char unit) {
    return unit == 'F' ? temp_c * 9.0f / 5.0f + 32.0f : temp_c;
  }

  // Saturation vapor pressure in kPa.
  static float saturationPressureKpa(float temp_c) {
    return SATURATION_PRESSURE_KPA_AT_0C * expf((MAGNUS_A * temp_c) / (temp_c + MAGNUS_B_C));
  }

  // Absolute humidity in g/m3 from the Magnus vapor pressure: 216.7 * e[hPa] / (T[K]). Returns -1
  // for NaN, infinite or out-of-range input (0 %RH gives 0 g/m3).
  static float absoluteHumidityGm3(float temp_c, float humidity_pct) {
    if (!isfinite(temp_c) || !isfinite(humidity_pct) || temp_c < MIN_TEMP_C || temp_c > MAX_TEMP_C ||
        humidity_pct < 0.0f || humidity_pct > 100.0f) {
      return -1.0f;
    }
    const float vapor_hpa = saturationPressureKpa(temp_c) * 10.0f * (humidity_pct / 100.0f);
    return 216.7f * vapor_hpa / (temp_c + 273.15f);
  }

  // Returns an invalid result for NaN, infinite or out-of-range input. 0 %RH gives no dew point
  // (the logarithm diverges), so it is invalid as well.
  static ClimateDerived compute(float temp_c, float humidity_pct) {
    ClimateDerived out;
    if (!isfinite(temp_c) || !isfinite(humidity_pct) || temp_c < MIN_TEMP_C ||
        temp_c > MAX_TEMP_C || humidity_pct <= 0.0f || humidity_pct > 100.0f) {
      return out;
    }
    const float gamma = logf(humidity_pct / 100.0f) + (MAGNUS_A * temp_c) / (MAGNUS_B_C + temp_c);
    out.dew_point_c = (MAGNUS_B_C * gamma) / (MAGNUS_A - gamma);
    out.vpd_kpa = saturationPressureKpa(temp_c) * (1.0f - humidity_pct / 100.0f);
    out.valid = isfinite(out.dew_point_c) && isfinite(out.vpd_kpa);
    return out;
  }

  // Comfort zone: COLD below 18.0 C, WARM above 26.0 C, DRY below 30 %RH, HUMID above 60 %RH, else OK.
  // The temperature wins over the humidity. The input limits are the ones of compute() (0 %RH is
  // valid here: the air is DRY).
  static constexpr float COMFORT_MIN_TEMP_C = 18.0f;
  static constexpr float COMFORT_MAX_TEMP_C = 26.0f;
  static constexpr float COMFORT_MIN_RH = 30.0f;
  static constexpr float COMFORT_MAX_RH = 60.0f;
  static ComfortZone comfort(float temp_c, float rh_pct) {
    if (!isfinite(temp_c) || !isfinite(rh_pct) || temp_c < MIN_TEMP_C || temp_c > MAX_TEMP_C ||
        rh_pct < 0.0f || rh_pct > 100.0f) {
      return ComfortZone::INVALID;
    }
    if (temp_c < COMFORT_MIN_TEMP_C) return ComfortZone::COLD;
    if (temp_c > COMFORT_MAX_TEMP_C) return ComfortZone::WARM;
    if (rh_pct < COMFORT_MIN_RH) return ComfortZone::DRY;
    if (rh_pct > COMFORT_MAX_RH) return ComfortZone::HUMID;
    return ComfortZone::OK;
  }

  static const char* comfortLabel(ComfortZone zone) {
    switch (zone) {
      case ComfortZone::COLD: return "COLD";
      case ComfortZone::WARM: return "WARM";
      case ComfortZone::DRY: return "DRY";
      case ComfortZone::HUMID: return "HUMID";
      case ComfortZone::OK: return "OK";
      default: return "--";
    }
  }

  // Formats "DP 9.3C VPD 1.2K" (VPD in kPa, one digit), or "DP -- VPD --" when not valid.
  static void format(char* buf, size_t size, bool usable, float temp_c, float humidity_pct, char unit = 'C') {
    if (buf == nullptr || size == 0) {
      return;
    }
    const ClimateDerived d = usable ? compute(temp_c, humidity_pct) : ClimateDerived{};
    if (!d.valid) {
      snprintf(buf, size, "DP -- VPD --");
      return;
    }
    snprintf(buf, size, "DP %.1f%c VPD %.1fK", static_cast<double>(toDisplay(d.dew_point_c, unit)),
             unit == 'F' ? 'F' : 'C', static_cast<double>(d.vpd_kpa));
  }
};

} // namespace rlcd_panel
