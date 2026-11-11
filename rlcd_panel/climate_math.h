#pragma once

#include <math.h>
#include <stdio.h>
#include <stddef.h>

namespace rlcd_panel {

// Dew point (Magnus formula) and vapor pressure deficit from temperature and relative humidity.
struct ClimateDerived {
  bool valid = false;
  float dew_point_c = 0.0f;
  float vpd_kpa = 0.0f;
};

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

  // Saturation vapor pressure in kPa.
  static float saturationPressureKpa(float temp_c) {
    return SATURATION_PRESSURE_KPA_AT_0C * expf((MAGNUS_A * temp_c) / (temp_c + MAGNUS_B_C));
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

  // Formats "DP 9.3C VPD 1.2K" (VPD in kPa, one digit), or "DP -- VPD --" when not valid.
  static void format(char* buf, size_t size, bool usable, float temp_c, float humidity_pct) {
    if (buf == nullptr || size == 0) {
      return;
    }
    const ClimateDerived d = usable ? compute(temp_c, humidity_pct) : ClimateDerived{};
    if (!d.valid) {
      snprintf(buf, size, "DP -- VPD --");
      return;
    }
    snprintf(buf, size, "DP %.1fC VPD %.1fK", static_cast<double>(d.dew_point_c),
             static_cast<double>(d.vpd_kpa));
  }
};

} // namespace rlcd_panel
