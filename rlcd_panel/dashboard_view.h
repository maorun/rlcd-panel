#pragma once

#include <stdio.h>
#include <string.h>
#include "battery_monitor.h"
#include "board_pins.h"
#include "shtc3_sensor.h"
#include "st7305_framebuffer.h"
#include "switchbot_ble.h"

namespace rlcd_panel {

class DashboardView {
public:
  // Header clock "HH:MM" (5 glyphs at scale 2 = 58 px wide). The title ends at x = 224 and the
  // widest battery label ("100%") starts at x = 316, so the clock fits between them.
  static constexpr uint16_t HEADER_HEIGHT = 28;
  static constexpr uint16_t HEADER_CLOCK_X = 244;
  static constexpr uint16_t HEADER_CLOCK_Y = 7;
  static constexpr size_t HEADER_CLOCK_MAX_CHARS = 5;

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
      float battery_voltage_v = -1.0f) {
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
  }

  static void render(
      St7305Framebuffer& fb,
      const Shtc3Reading& onboard,
      const BatteryReading& battery,
      const SwitchBotReading* switchbot_slots,
      size_t switchbot_count,
      const char* clock_text = nullptr) {
    fb.clear(false);

    // Header bar (black filled rectangle)
    fb.fillRect(0, 0, RLCD_WIDTH, HEADER_HEIGHT, true);
    fb.drawText(10, 7, "RLCD CLIMATE PANEL", 2, false);

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
        battery.valid ? battery.voltage_v : -1.0f);

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
          sb.battery_pct);
    }
  }
};

} // namespace rlcd_panel
