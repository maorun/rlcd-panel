#pragma once

#include <math.h>
#include <stdint.h>
#include <string.h>

#include "../rlcd_panel/battery_monitor.h"
#include "../rlcd_panel/button_handler.h"
#include "../rlcd_panel/dashboard_view.h"
#include "../rlcd_panel/device_config.h"
#include "../rlcd_panel/shtc3_sensor.h"
#include "../rlcd_panel/st7305_framebuffer.h"
#include "../rlcd_panel/switchbot_ble.h"

namespace rlcd_panel {
namespace preview {

// One preview image: a display mode, optionally with the low battery alert banner or the quiet
// hours marker.
struct PreviewScene {
  const char* file_name; // file name inside docs/
  DisplayMode mode;
  bool low_battery;
  bool quiet_hours;
  bool long_runtime = false; // slow discharge: the runtime line shows days
  bool battery_empty = false; // empty battery protection: full-screen warning
};

constexpr uint8_t PREVIEW_SCENE_COUNT = 8;

constexpr PreviewScene PREVIEW_SCENES[PREVIEW_SCENE_COUNT] = {
    {"display_preview_dashboard", DisplayMode::DASHBOARD, false, false},
    {"display_preview_stats", DisplayMode::SENSOR_STATS, false, false},
    {"display_preview_diagnostics", DisplayMode::SYSTEM_DIAGNOSTICS, false, false},
    {"display_preview_runtime", DisplayMode::SYSTEM_DIAGNOSTICS, false, false, true},
    {"display_preview_spectrum", DisplayMode::AUDIO_SPECTRUM, false, false},
    {"display_preview_low_battery", DisplayMode::DASHBOARD, true, false},
    {"display_preview_quiet_hours", DisplayMode::DASHBOARD, false, true},
    {"display_preview_battery_empty", DisplayMode::DASHBOARD, false, false, false, true},
};

// Renders one mode with the fixed sample data used for all documentation previews.
inline void renderScene(
    St7305Framebuffer& fb, DisplayMode mode, bool low_battery, bool quiet_hours = false,
    bool long_runtime = false, bool battery_empty = false) {
  Shtc3Reading onboard;
  onboard.valid = true;
  onboard.temperature_c = 21.8f;
  onboard.humidity_pct = 48.0f;

  SwitchBotReading slots[MAX_SWITCHBOT_SENSORS];
  // Sensor 1: Living room / Balkon
  slots[0].valid = true;
  slots[0].temperature_c = 19.4f;
  slots[0].humidity_pct = 54;
  slots[0].battery_pct = 95;
  slots[0].rssi = -58;
  slots[0].packet_count = 412;
  slots[0].mac[0] = 0xB0; slots[0].mac[1] = 0xE9; slots[0].mac[2] = 0xFE;
  slots[0].mac[3] = 0x12; slots[0].mac[4] = 0x34; slots[0].mac[5] = 0x56;

  // Sensor 2: Garden / Garten
  slots[1].valid = true;
  slots[1].temperature_c = 13.8f;
  slots[1].humidity_pct = 68;
  slots[1].battery_pct = 82;
  slots[1].rssi = -71;
  slots[1].packet_count = 388;
  slots[1].mac[0] = 0xB0; slots[1].mac[1] = 0xE9; slots[1].mac[2] = 0xFE;
  slots[1].mac[3] = 0xAB; slots[1].mac[4] = 0xCD; slots[1].mac[5] = 0xEF;

  // Sensor 3: Cellar / Keller
  slots[2].valid = true;
  slots[2].temperature_c = 16.2f;
  slots[2].humidity_pct = 61;
  slots[2].battery_pct = 76;
  slots[2].rssi = -84;
  slots[2].packet_count = 97;
  slots[2].mac[0] = 0xB0; slots[2].mac[1] = 0xE9; slots[2].mac[2] = 0xFE;
  slots[2].mac[3] = 0x65; slots[2].mac[4] = 0x43; slots[2].mac[5] = 0x21;

  // Preview clock: slots 0 and 1 are fresh, slot 2 got its last packet 45 minutes ago (stale)
  const uint32_t now_ms = 10000000;
  slots[0].updated_ms = now_ms - 60000;
  slots[1].updated_ms = now_ms - 120000;
  slots[2].updated_ms = now_ms - 45 * 60 * 1000;

  BatteryReading battery;
  battery.valid = true;
  battery.voltage_v = 3.92f;
  battery.percentage = 72;
  battery.usb_powered = false;
  if (low_battery) {
    battery.voltage_v = 3.38f;
    battery.percentage = 12;
    battery.low_battery = BatteryMonitor::updateLowBattery(battery, false);
  }

  if (battery_empty) {
    battery.voltage_v = 3.29f;
    battery.percentage = BatteryMonitor::voltageToPercent(battery.voltage_v);
    battery.empty_protection = BatteryMonitor::updateEmptyProtection(battery, false);
  }

  // Preview history: 24 hourly samples with a daily rhythm per sensor
  ClimateHistory history;
  const float base[ClimateHistory::SLOT_COUNT] = {45.0f, 58.0f, 72.0f, 40.0f};
  const float swing[ClimateHistory::SLOT_COUNT] = {4.0f, 9.0f, 14.0f, 1.0f};
  // Outdoor sensors swing below freezing, so the stats view shows negative records
  const float tbase[ClimateHistory::SLOT_COUNT] = {21.0f, 4.0f, -5.0f, 6.0f};
  const float tswing[ClimateHistory::SLOT_COUNT] = {1.5f, 6.0f, 10.0f, 0.0f};
  for (size_t s = 0; s < ClimateHistory::SLOT_COUNT; ++s) {
    for (int hour = 0; hour < 24; ++hour) {
      history.slots[s].pushPercent(base[s] + swing[s] * sinf(static_cast<float>(hour) * 0.2618f));
      history.temperatures[s].pushCelsius(
          tbase[s] + tswing[s] * sinf(static_cast<float>(hour - 6) * 0.2618f));
    }
  }

  // Preview noise: 24 hourly levels, quiet at night and louder by day, with two hours without a reading
  NoiseHistory noise_history;
  for (int hour = 0; hour < 24; ++hour) {
    if (hour == 9 || hour == 10) {
      noise_history.pushInvalid();
      continue;
    }
    noise_history.push(NoiseHistory::encode(static_cast<int>(
        44.0f + 14.0f * sinf((static_cast<float>(hour) - 6.0f) * 0.2618f) + (hour % 3) * 2.0f)));
  }

  // Preview discharge: 12 hourly samples ending at the current voltage, 20 mV per hour
  // (36 H left) or 10 mV per hour (3 D left)
  BatteryHistory battery_history;
  const float step_v = long_runtime ? 0.01f : 0.02f;
  for (int hour = 11; hour >= 0; --hour) {
    battery_history.push(battery.voltage_v + step_v * static_cast<float>(hour));
  }

  SystemInfo info;
  info.runtime = battery_history.estimate(battery);
  info.free_heap_bytes = 214 * 1024;
  info.uptime_ms = 93784000; // 1 d 02:03:04
  // Calibrated microphone: floor RMS 100, offset 90 dB. RMS 650 is moderate, about 56 DBA.
  MicCalibration::fromFloor(100, MicCalibration::DEFAULT_SPL_OFFSET_DB, info.mic_cal);
  info.noise = NoiseClassifier::makeReading(NoiseBand::QUIET, 650, info.mic_cal);
  // Both microphones pass the self-test (MIC1: OK MIC2: OK)
  info.noise.mic.valid = true;
  info.quiet_hours = quiet_hours;
  info.config_from_sd = true;
  info.sd_free_mb = 7421;
  info.cycle_tenths = 123; // 12.3 cycles
  info.loopback.status = LoopbackStatus::PASS;
  info.loopback.level_db = -24;
  // Fixed sample spectrum: a fan hum near 120 Hz with harmonics and a soft broad high band
  info.spectrum.valid = true;
  for (size_t b = 0; b < SpectrumReading::BANDS; ++b) {
    const float x = static_cast<float>(b);
    const float hum = 80.0f * expf(-(x - 2.0f) * (x - 2.0f) / 4.0f);
    const float harmonic = 45.0f * expf(-(x - 9.0f) * (x - 9.0f) / 3.0f);
    const float hiss = 22.0f + 8.0f * sinf(x * 0.9f);
    float level = hum + harmonic + (x > 20.0f ? hiss : 12.0f);
    if (level > 100.0f) {
      level = 100.0f;
    }
    info.spectrum.bars[b] = static_cast<uint8_t>(level);
    const float peak = level * 1.15f + 4.0f;
    info.spectrum.peaks[b] = static_cast<uint8_t>(peak > 100.0f ? 100.0f : peak);
  }

  // Sample sensor names for the tile titles and the stats view
  DeviceConfig config;
  strcpy(config.onboard_name, "BUERO");
  strcpy(config.slot_name[0], "WOHNZIMMER");
  strcpy(config.slot_name[1], "GARTEN");
  strcpy(config.slot_name[2], "KELLER");

  DashboardView::renderMode(
      fb,
      mode,
      onboard,
      battery,
      slots,
      MAX_SWITCHBOT_SENSORS,
      info,
      "14:32",
      now_ms,
      &history,
      &noise_history,
      &config);
}

} // namespace preview
} // namespace rlcd_panel
