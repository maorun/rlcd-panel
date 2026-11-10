#pragma once

#include <stdio.h>
#include "board_pins.h"
#include "shtc3_sensor.h"
#include "st7305_framebuffer.h"
#include "switchbot_ble.h"

namespace rlcd_panel {

class DashboardView {
public:
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
      int8_t battery_pct) {
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
      snprintf(line_bat, sizeof(line_bat), "BAT: %d %%", static_cast<int>(battery_pct));
      fb.drawText(x + 12, y + 96, line_bat, 2, true);
    }
  }

  static void render(
      St7305Framebuffer& fb,
      const Shtc3Reading& onboard,
      const SwitchBotReading* switchbot_slots,
      size_t switchbot_count) {
    fb.clear(false);

    // Header bar
    fb.fillRect(0, 0, RLCD_WIDTH, 28, true);
    fb.drawText(10, 7, "RLCD CLIMATE PANEL", 2, false);

    const uint16_t tile_w = 190;
    const uint16_t tile_h = 124;

    // Tile 0: Onboard SHTC3
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
        -1);

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
