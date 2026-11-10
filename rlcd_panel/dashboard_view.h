#pragma once

#include <stdio.h>
#include <string.h>
#include "battery_monitor.h"
#include "board_pins.h"
#include "button_handler.h"
#include "es7210_mic.h"
#include "shtc3_sensor.h"
#include "sparkline.h"
#include "st7305_framebuffer.h"
#include "switchbot_ble.h"

namespace rlcd_panel {

// Runtime figures for the system diagnostics view.
struct SystemInfo {
  uint32_t free_heap_bytes = 0;
  uint64_t uptime_ms = 0; // 64 bit: millis() wraps after 49.7 days
  NoiseReading noise;     // ambient noise of the last window; invalid shows "--"
  MicCalibration mic_cal; // calibrated: dB SPL (DBA) is shown, else dBFS
  bool mic_cal_busy = false; // the calibration window is running
  bool mic_cal_failed = false; // the last calibration of this wake cycle was rejected
};

class DashboardView {
public:
  // Header clock "HH:MM" (5 glyphs at scale 2 = 58 px wide). The title ends at x = 224 and the
  // widest battery label ("100%") starts at x = 316, so the clock fits between them.
  static constexpr uint16_t HEADER_HEIGHT = 28;
  static constexpr uint16_t HEADER_CLOCK_X = 244;
  static constexpr uint16_t HEADER_CLOCK_Y = 7;
  static constexpr size_t HEADER_CLOCK_MAX_CHARS = 5;
  static constexpr const char* LOW_BATTERY_TEXT = "! LOW BATTERY - PLEASE CHARGE !";
  static constexpr uint16_t STALE_BADGE_W = 35;
  static constexpr uint16_t STALE_BADGE_H = 17;
  // Trend curve right of the humidity line ("H: 100 %" ends at x + 106). The box lies fully
  // inside the tile: 6 px from the right border, between the divider (y + 24) and the bottom.
  static constexpr uint16_t SPARKLINE_W = 72;
  static constexpr uint16_t SPARKLINE_H = 16;
  static constexpr uint16_t SPARKLINE_RIGHT_MARGIN = 6;
  static constexpr uint16_t SPARKLINE_Y_OFFSET = 66;

  static void renderBatteryIcon(
      St7305Framebuffer& fb,
      uint16_t x,
      uint16_t y,
      int8_t percentage,
      bool white_pixels = false) {
    const bool pixel_color = !white_pixels;
    // Outer battery body (22 x 12 px)
    fb.drawRect(x, y, 22, 12, pixel_color);
    // Positive terminal nipple (2 x 4 px)
    fb.fillRect(x + 22, y + 4, 2, 4, pixel_color);

    if (percentage > 0) {
      uint16_t fill_w = static_cast<uint16_t>((percentage * 18) / 100);
      if (fill_w == 0) {
        fill_w = 1;
      }
      if (fill_w > 18) {
        fill_w = 18;
      }
      fb.fillRect(x + 2, y + 2, fill_w, 8, pixel_color);
    }
  }

  static void renderTile(
      St7305Framebuffer& fb,
      uint16_t x,
      uint16_t y,
      uint16_t w,
      uint16_t h,
      const char* title,
      bool valid,
      float temp_c,
      float humidity_pct,
      int8_t battery_pct,
      float battery_voltage_v = -1.0f,
      bool is_stale = false,
      const TemperatureHistory* history = nullptr) {
    fb.drawRect(x, y, w, h, true);
    fb.drawHLine(x, y + 24, w, true);
    fb.drawText(x + 8, y + 6, title, 2, true);

    if (!valid) {
      fb.drawText(x + 12, y + 56, "NO SIGNAL", 2, true);
      return;
    }

    char line_temp[24];
    snprintf(line_temp, sizeof(line_temp), "T: %.1f C", temp_c);
    fb.drawText(x + 12, y + 40, line_temp, 2, true);

    char line_rh[24];
    snprintf(line_rh, sizeof(line_rh), "H: %.0f %%", humidity_pct);
    fb.drawText(x + 12, y + 68, line_rh, 2, true);

    // A curve needs two samples. One sample would draw a single dot.
    if (history != nullptr && history->size() >= 2 && w > SPARKLINE_W + SPARKLINE_RIGHT_MARGIN &&
        h > SPARKLINE_Y_OFFSET + SPARKLINE_H) {
      Sparkline::render(
          fb,
          static_cast<uint16_t>(x + w - SPARKLINE_W - SPARKLINE_RIGHT_MARGIN),
          static_cast<uint16_t>(y + SPARKLINE_Y_OFFSET),
          SPARKLINE_W,
          SPARKLINE_H,
          *history,
          true);
    }

    if (battery_pct >= 0) {
      char line_bat[24];
      if (battery_voltage_v >= 0.0f) {
        snprintf(
            line_bat,
            sizeof(line_bat),
            "BAT: %.2fV %d%%",
            battery_voltage_v,
            static_cast<int>(battery_pct));
      } else {
        snprintf(line_bat, sizeof(line_bat), "BAT: %d %%", static_cast<int>(battery_pct));
      }
      fb.drawText(x + 12, y + 96, line_bat, 2, true);
    }

    if (is_stale) {
      // Inverted "STALE" badge (5 glyphs at scale 1 = 29 px wide) in the title band, right of the
      // title ("SWITCHBOT 3" ends at x + 138) and above the divider line at y + 24.
      const uint16_t bx = static_cast<uint16_t>(x + w - STALE_BADGE_W - 4);
      const uint16_t by = static_cast<uint16_t>(y + 4);
      fb.fillRect(bx, by, STALE_BADGE_W, STALE_BADGE_H, true);
      fb.drawText(bx + 3, by + 5, "STALE", 1, false);
    }
  }

  // Clears the framebuffer and draws the header bar: title, optional clock and battery indicator.
  static void renderHeader(
      St7305Framebuffer& fb,
      const char* title,
      const BatteryReading& battery,
      const char* clock_text) {
    fb.clear(false);

    if (battery.low_battery) {
      // Inverted banner: white bar with a double black frame and centered text. The text is
      // 31 glyphs at scale 2 = 370 px wide, inside the 400 px width.
      fb.drawRect(0, 0, RLCD_WIDTH, HEADER_HEIGHT, true);
      fb.drawRect(1, 1, RLCD_WIDTH - 2, HEADER_HEIGHT - 2, true);
      const uint16_t text_w = static_cast<uint16_t>(strlen(LOW_BATTERY_TEXT) * 12 - 2);
      fb.drawText(static_cast<uint16_t>((RLCD_WIDTH - text_w) / 2), 7, LOW_BATTERY_TEXT, 2, true);
      return;
    }

    // Header bar (black filled rectangle)
    fb.fillRect(0, 0, RLCD_WIDTH, HEADER_HEIGHT, true);
    fb.drawText(10, 7, title, 2, false);

    // Header clock between the title and the battery indicator
    if (clock_text != nullptr && strlen(clock_text) <= HEADER_CLOCK_MAX_CHARS) {
      fb.drawText(HEADER_CLOCK_X, HEADER_CLOCK_Y, clock_text, 2, false);
    }

    // Header battery indicator on the right side
    if (battery.valid) {
      char bat_header[12];
      if (battery.usb_powered) {
        snprintf(bat_header, sizeof(bat_header), "USB");
      } else if (battery.percentage >= 0) {
        snprintf(bat_header, sizeof(bat_header), "%d%%", static_cast<int>(battery.percentage));
      } else {
        snprintf(bat_header, sizeof(bat_header), "--%%");
      }

      // Draw text right-aligned before battery icon
      const size_t len = strlen(bat_header);
      const uint16_t text_x = static_cast<uint16_t>(364 - (len * 12));
      fb.drawText(text_x, 7, bat_header, 2, false);
      renderBatteryIcon(fb, 368, 8, battery.percentage, true);
    }
  }

  static void render(
      St7305Framebuffer& fb,
      const Shtc3Reading& onboard,
      const BatteryReading& battery,
      const SwitchBotReading* switchbot_slots,
      size_t switchbot_count,
      const char* clock_text,
      uint32_t now_ms,
      const ClimateHistory* history = nullptr) {
    renderHeader(fb, "RLCD CLIMATE PANEL", battery, clock_text);

    const uint16_t tile_w = 190;
    const uint16_t tile_h = 124;

    // Tile 0: Onboard SHTC3 and 18650 Battery
    renderTile(
        fb,
        6,
        36,
        tile_w,
        tile_h,
        "ONBOARD SHTC3",
        onboard.valid,
        onboard.temperature_c,
        onboard.humidity_pct,
        battery.percentage,
        battery.valid ? battery.voltage_v : -1.0f,
        false,
        history != nullptr ? &history->slots[ClimateHistory::ONBOARD] : nullptr);

    // Tiles 1..3: SwitchBot Outdoor Meters (WoIOSensor)
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      const uint16_t col = static_cast<uint16_t>((i + 1) % 2);
      const uint16_t row = static_cast<uint16_t>((i + 1) / 2);
      const uint16_t tx = static_cast<uint16_t>(6 + col * 198);
      const uint16_t ty = static_cast<uint16_t>(36 + row * 132);

      char title[24];
      snprintf(title, sizeof(title), "SWITCHBOT %u", static_cast<unsigned>(i + 1));

      const bool have_slot = (switchbot_slots != nullptr && i < switchbot_count);
      const SwitchBotReading empty_reading{};
      const SwitchBotReading& sb = have_slot ? switchbot_slots[i] : empty_reading;

      renderTile(
          fb,
          tx,
          ty,
          tile_w,
          tile_h,
          title,
          sb.valid,
          sb.temperature_c,
          static_cast<float>(sb.humidity_pct),
          sb.battery_pct,
          -1.0f,
          sb.isStale(now_ms),
          history != nullptr ? &history->slots[1 + i] : nullptr);
    }
  }

  // Draws one scale-2 text line; returns false (and draws nothing) when it would not fit the screen.
  static bool drawLine(St7305Framebuffer& fb, uint16_t x, uint16_t y, const char* text) {
    if (static_cast<uint32_t>(x) + strlen(text) * 12 > RLCD_WIDTH) {
      return false;
    }
    fb.drawText(x, y, text, 2, true);
    return true;
  }

  static void renderSensorStats(
      St7305Framebuffer& fb,
      const BatteryReading& battery,
      const SwitchBotReading* switchbot_slots,
      size_t switchbot_count,
      const char* clock_text,
      uint32_t now_ms) {
    renderHeader(fb, "SENSOR STATS", battery, clock_text);

    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      const uint16_t y = static_cast<uint16_t>(34 + i * 78);
      const bool have_slot = (switchbot_slots != nullptr && i < switchbot_count);
      const SwitchBotReading empty_reading{};
      const SwitchBotReading& sb = have_slot ? switchbot_slots[i] : empty_reading;

      char line[40];
      if (!sb.valid) {
        snprintf(line, sizeof(line), "S%u NO SIGNAL", static_cast<unsigned>(i + 1));
        drawLine(fb, 10, y, line);
        continue;
      }

      snprintf(line, sizeof(line), "S%u RSSI %d DBM", static_cast<unsigned>(i + 1), sb.rssi);
      drawLine(fb, 10, y, line);

      snprintf(
          line,
          sizeof(line),
          "MAC %02X:%02X:%02X:%02X:%02X:%02X",
          sb.mac[0], sb.mac[1], sb.mac[2], sb.mac[3], sb.mac[4], sb.mac[5]);
      drawLine(fb, 10, static_cast<uint16_t>(y + 18), line);

      // The BLE task can stamp a packet shortly after the caller read now_ms: show 0 s for such a
      // packet from the near future (under 65 s) instead of a wrapped age near 49.7 days.
      const uint32_t age_ms = now_ms - sb.updated_ms;
      const uint32_t age_s = age_ms >= 0xFFFF0000u ? 0 : age_ms / 1000;
      char age[12];
      if (age_s < 60) {
        snprintf(age, sizeof(age), "%lu S", static_cast<unsigned long>(age_s));
      } else if (age_s < 3600) {
        snprintf(age, sizeof(age), "%lu M", static_cast<unsigned long>(age_s / 60));
      } else {
        snprintf(age, sizeof(age), "%lu H", static_cast<unsigned long>(age_s / 3600));
      }
      snprintf(
          line,
          sizeof(line),
          "PKT %lu AGE %s",
          static_cast<unsigned long>(sb.packet_count),
          age);
      drawLine(fb, 10, static_cast<uint16_t>(y + 36), line);
    }

    // Raw voltage readings: only the onboard cell is measured; SwitchBot packets carry a percentage.
    char line[40];
    if (battery.valid) {
      snprintf(line, sizeof(line), "ONBOARD BAT %.2f V", battery.voltage_v);
    } else {
      snprintf(line, sizeof(line), "ONBOARD BAT --");
    }
    drawLine(fb, 10, 270, line);
  }

  static void renderDiagnostics(
      St7305Framebuffer& fb,
      const BatteryReading& battery,
      const SystemInfo& info,
      const char* clock_text) {
    renderHeader(fb, "SYSTEM DIAGNOSTICS", battery, clock_text);

    char line[40];
    const char* power = !battery.valid ? "NONE" : (battery.usb_powered ? "USB" : "BATTERY");
    snprintf(line, sizeof(line), "POWER: %s", power);
    drawLine(fb, 10, 44, line);

    if (battery.valid && battery.percentage >= 0) {
      snprintf(
          line,
          sizeof(line),
          "BAT: %.2f V %d %%",
          battery.voltage_v,
          static_cast<int>(battery.percentage));
    } else if (battery.valid) {
      snprintf(line, sizeof(line), "BAT: %.2f V --", battery.voltage_v);
    } else {
      snprintf(line, sizeof(line), "BAT: --");
    }
    drawLine(fb, 10, 84, line);

    snprintf(
        line,
        sizeof(line),
        "HEAP: %lu KB FREE",
        static_cast<unsigned long>(info.free_heap_bytes / 1024));
    drawLine(fb, 10, 124, line);

    const uint64_t total_s = info.uptime_ms / 1000;
    snprintf(
        line,
        sizeof(line),
        "UP: %llu D %02lu:%02lu:%02lu",
        static_cast<unsigned long long>(total_s / 86400),
        static_cast<unsigned long>((total_s / 3600) % 24),
        static_cast<unsigned long>((total_s / 60) % 60),
        static_cast<unsigned long>(total_s % 60));
    drawLine(fb, 10, 164, line);

    // Widest line: "NOISE: MODERATE 140 DBA" = 23 glyphs = 276 px; "... -96 DBFS" = 24 = 288 px.
    if (info.noise.valid && info.mic_cal.calibrated) {
      snprintf(
          line,
          sizeof(line),
          "NOISE: %s %d DBA",
          NoiseClassifier::label(info.noise.band),
          info.mic_cal.splDb(info.noise.rms));
    } else if (info.noise.valid) {
      snprintf(
          line,
          sizeof(line),
          "NOISE: %s %d DBFS",
          NoiseClassifier::label(info.noise.band),
          NoiseClassifier::dbfs(info.noise.rms));
    } else {
      snprintf(line, sizeof(line), "NOISE: --");
    }
    drawLine(fb, 10, 204, line);

    snprintf(
        line,
        sizeof(line),
        "MIC CAL: %s",
        info.mic_cal_busy ? "MEASURING"
                          : (info.mic_cal_failed ? "FAILED"
                                                 : (info.mic_cal.calibrated ? "OK" : "DEFAULT")));
    drawLine(fb, 10, 244, line);
  }

  static void renderMode(
      St7305Framebuffer& fb,
      DisplayMode mode,
      const Shtc3Reading& onboard,
      const BatteryReading& battery,
      const SwitchBotReading* switchbot_slots,
      size_t switchbot_count,
      const SystemInfo& info,
      const char* clock_text,
      uint32_t now_ms,
      const ClimateHistory* history = nullptr) {
    switch (mode) {
      case DisplayMode::SENSOR_STATS:
        renderSensorStats(fb, battery, switchbot_slots, switchbot_count, clock_text, now_ms);
        break;
      case DisplayMode::SYSTEM_DIAGNOSTICS:
        renderDiagnostics(fb, battery, info, clock_text);
        break;
      case DisplayMode::DASHBOARD:
      default:
        render(fb, onboard, battery, switchbot_slots, switchbot_count, clock_text, now_ms, history);
        break;
    }
  }
};

} // namespace rlcd_panel
