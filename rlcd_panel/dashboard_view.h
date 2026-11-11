#pragma once

#include <math.h>
#include <stdio.h>
#include <string.h>
#include "battery_monitor.h"
#include "audio_loopback.h"
#include "audio_spectrum.h"
#include "board_pins.h"
#include "button_handler.h"
#include "climate_math.h"
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
  bool quiet_hours = false; // chimes are suppressed now; the header shows "Q"
  bool config_from_sd = false; // true: settings come from /sdcard/config.ini
  LoopbackResult loopback;     // last speaker to microphone test of this wake cycle
  SpectrumReading spectrum;    // last spectrum capture; invalid shows "NO DATA"
  BatteryRuntime runtime;      // remaining battery runtime; invalid shows "--"
  int32_t sd_free_mb = -1;     // free MicroSD space in MB; negative (unmounted, unknown) shows "--"
};

class DashboardView {
public:
  // Header clock "HH:MM" (5 glyphs at scale 2 = 58 px wide). The title ends at x = 224 and the
  // widest battery label ("100%") starts at x = 316, so the clock fits between them.
  static constexpr uint16_t HEADER_HEIGHT = 28;
  static constexpr uint16_t HEADER_CLOCK_X = 244;
  static constexpr uint16_t HEADER_CLOCK_Y = 7;
  static constexpr size_t HEADER_CLOCK_MAX_CHARS = 5;
  // Quiet hours marker: a white 9x11 badge with a black "Q" (scale 1) between the title (ends at
  // x = 224) and the clock (starts at x = 244), so it reads apart from both.
  static constexpr uint16_t HEADER_QUIET_X = 230;
  static constexpr uint16_t HEADER_QUIET_Y = 8;
  static constexpr uint16_t HEADER_QUIET_W = 9;
  static constexpr uint16_t HEADER_QUIET_H = 11;
  static constexpr const char* LOW_BATTERY_TEXT = "! LOW BATTERY - PLEASE CHARGE !";
  static constexpr const char* EMPTY_BATTERY_TEXT = "BATTERY EMPTY - CHARGE NOW";
  static constexpr uint16_t STALE_BADGE_W = 35;
  static constexpr uint16_t STALE_BADGE_H = 17;
  // Trend curve right of the humidity line ("H: 100 %" ends at x + 106). The box lies fully
  // inside the tile: 6 px from the right border, between the divider (y + 24) and the bottom.
  static constexpr uint16_t SPARKLINE_W = 72;
  static constexpr uint16_t SPARKLINE_H = 16;
  static constexpr uint16_t SPARKLINE_RIGHT_MARGIN = 6;
  static constexpr uint16_t SPARKLINE_Y_OFFSET = 66;
  // Dew point and VPD line (scale 1, 7 px high) between the sparkline box (ends at y + 82) and the
  // battery line (starts at y + 96). The widest text has 19 glyphs = 113 px, inside the 190 px tile.
  static constexpr uint16_t CLIMATE_LINE_Y_OFFSET = 86;
  static constexpr uint16_t CLIMATE_LINE_H = 7;
  // Sensor stats: noise trend line "NOISE 140-140 DB" (16 glyphs, 192 px) and its curve box right of
  // it, above the "ONBOARD BAT" line at y = 270.
  static constexpr uint16_t SENSOR_BLOCK_PITCH = 72; // 4 text lines of 18 px
  static constexpr uint16_t NOISE_TREND_Y = 248;
  static constexpr uint16_t NOISE_SPARKLINE_X = 216;
  static constexpr uint16_t NOISE_SPARKLINE_Y = 246;
  static constexpr uint16_t NOISE_SPARKLINE_W = 170;
  static constexpr uint16_t NOISE_SPARKLINE_H = 20;
  static constexpr uint16_t LOOPBACK_Y = 260; // diagnostics: speaker to microphone test line
  static constexpr uint16_t MIC_TEST_Y = 224; // diagnostics: microphone self-test line
  // Spectrum view: 32 bars of 10 px with a 2 px gap fill x = 8 to 391. A level of 100 gives a bar
  // of 200 px, from y = 40 to the baseline at y = 240. The labels sit under the baseline.
  static constexpr uint16_t SPECTRUM_X = 8;
  static constexpr uint16_t SPECTRUM_BAR_W = 10;
  static constexpr uint16_t SPECTRUM_BAR_PITCH = 12;
  static constexpr uint16_t SPECTRUM_TOP_Y = 40;
  static constexpr uint16_t SPECTRUM_BASE_Y = 240;
  static constexpr uint16_t SPECTRUM_LABEL_Y = 248;
  static constexpr uint16_t SPECTRUM_PEAK_H = 2;

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

  // Dew point and VPD line; shows "DP -- VPD --" for a stale, invalid or NaN reading.
  static void renderClimateLine(
      St7305Framebuffer& fb, uint16_t x, uint16_t y, uint16_t h, bool usable, float temp_c,
      float humidity_pct) {
    if (h <= CLIMATE_LINE_Y_OFFSET + CLIMATE_LINE_H) {
      return;
    }
    char line[ClimateMath::LINE_MAX_CHARS + 1];
    ClimateMath::format(line, sizeof(line), usable, temp_c, humidity_pct);
    fb.drawText(x + 12, y + CLIMATE_LINE_Y_OFFSET, line, 1, true);
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
      const HumidityHistory* history = nullptr) {
    fb.drawRect(x, y, w, h, true);
    fb.drawHLine(x, y + 24, w, true);
    fb.drawText(x + 8, y + 6, title, 2, true);

    if (!valid) {
      fb.drawText(x + 12, y + 56, "NO SIGNAL", 2, true);
      renderClimateLine(fb, x, y, h, false, 0.0f, 0.0f);
      return;
    }

    char line_temp[24];
    snprintf(line_temp, sizeof(line_temp), "T: %.1f C", temp_c);
    fb.drawText(x + 12, y + 40, line_temp, 2, true);

    char line_rh[24];
    snprintf(line_rh, sizeof(line_rh), "H: %.0f %%", humidity_pct);
    fb.drawText(x + 12, y + 68, line_rh, 2, true);

    renderClimateLine(fb, x, y, h, !is_stale, temp_c, humidity_pct);

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
      // title ("SENSOR 3" ends at x + 102) and above the divider line at y + 24.
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
      const char* clock_text,
      bool quiet_hours = false) {
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

    if (quiet_hours) {
      fb.fillRect(HEADER_QUIET_X, HEADER_QUIET_Y, HEADER_QUIET_W, HEADER_QUIET_H, false);
      fb.drawText(HEADER_QUIET_X + 2, HEADER_QUIET_Y + 2, "Q", 1, true);
    }

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
      const ClimateHistory* history = nullptr,
      bool quiet_hours = false) {
    renderHeader(fb, "RLCD CLIMATE PANEL", battery, clock_text, quiet_hours);

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

    // Tiles 1..3: SwitchBot Outdoor Meters (WoIOSensor) and BTHome v2 sensors
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      const uint16_t col = static_cast<uint16_t>((i + 1) % 2);
      const uint16_t row = static_cast<uint16_t>((i + 1) / 2);
      const uint16_t tx = static_cast<uint16_t>(6 + col * 198);
      const uint16_t ty = static_cast<uint16_t>(36 + row * 132);

      char title[24];
      snprintf(title, sizeof(title), "SENSOR %u", static_cast<unsigned>(i + 1));

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

  // Formats the 24 h records of one history slot: "24H T-15.0 TO 5.0 H41-63" (lowest, highest degC; lowest-
  // highest %RH; the 5x7 font has no slash, and "TO" keeps a negative highest value unambiguous). Each range shows "--" for fewer than 2 samples. The longest text is
  // "24H T-100.0 TO -100.0 H100-100" (30 glyphs, 360 px), so it always fits the 400 px width.
  static void formatRecords(char* out, size_t capacity, const ClimateHistory* history, size_t slot) {
    char temp[28] = "--";
    char hum[12] = "--";
    if (history != nullptr && slot < ClimateHistory::SLOT_COUNT) {
      int16_t lo = 0;
      int16_t hi = 0;
      if (history->temperatures[slot].range(lo, hi)) {
        snprintf(temp, sizeof(temp), "%.1f TO %.1f", lo / 10.0, hi / 10.0);
      }
      if (history->slots[slot].range(lo, hi)) {
        snprintf(hum, sizeof(hum), "%d-%d", (lo + 5) / 10, (hi + 5) / 10);
      }
    }
    snprintf(out, capacity, "24H T%s H%s", temp, hum);
  }

  static void renderSensorStats(
      St7305Framebuffer& fb,
      const BatteryReading& battery,
      const SwitchBotReading* switchbot_slots,
      size_t switchbot_count,
      const char* clock_text,
      uint32_t now_ms,
      bool quiet_hours = false,
      const NoiseHistory* noise_history = nullptr,
      const ClimateHistory* history = nullptr) {
    renderHeader(fb, "SENSOR STATS", battery, clock_text, quiet_hours);

    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      const uint16_t y = static_cast<uint16_t>(34 + i * SENSOR_BLOCK_PITCH);
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

      formatRecords(line, sizeof(line), history, 1 + i);
      drawLine(fb, 10, static_cast<uint16_t>(y + 54), line);
    }

    // Noise trend of the last 24 h with the minimum and maximum. Hours without a reading are skipped;
    // with no valid hour the line stays off.
    uint8_t noise_lo = 0;
    uint8_t noise_hi = 0;
    if (noise_history != nullptr && noise_history->range(noise_lo, noise_hi)) {
      char noise_line[24];
      snprintf(
          noise_line,
          sizeof(noise_line),
          "NOISE %u-%u DB",
          static_cast<unsigned>(noise_lo),
          static_cast<unsigned>(noise_hi));
      drawLine(fb, 10, NOISE_TREND_Y, noise_line);
      Sparkline::renderNoise(
          fb, NOISE_SPARKLINE_X, NOISE_SPARKLINE_Y, NOISE_SPARKLINE_W, NOISE_SPARKLINE_H, *noise_history);
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
    renderHeader(fb, "SYSTEM DIAGNOSTICS", battery, clock_text, info.quiet_hours);

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

    // Runtime at y = 104, between the battery line (ends y = 98) and the heap line (y = 124).
    info.runtime.describe(line, sizeof(line));
    drawLine(fb, 10, 104, line);

    snprintf(
        line,
        sizeof(line),
        "HEAP: %lu KB FREE",
        static_cast<unsigned long>(info.free_heap_bytes / 1024));
    drawLine(fb, 10, 124, line);

    // SD space at y = 144, between the heap line (ends y = 138) and the uptime line (y = 164).
    // Widest: "SD FREE: 9999999 MB" = 19 glyphs = 226 px.
    if (info.sd_free_mb >= 0) {
      snprintf(line, sizeof(line), "SD FREE: %ld MB", static_cast<long>(info.sd_free_mb));
    } else {
      snprintf(line, sizeof(line), "SD FREE: --");
    }
    drawLine(fb, 10, 144, line);

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
    // Without a usable channel the reading is invalid, so no band is shown.
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

    // Microphone self-test at y = 224, between the noise line (ends y = 218) and the status line.
    // Widest: "MIC1: SILENT MIC2: SILENT" = 25 glyphs = 300 px; with an imbalance the OK line
    // "MIC1: OK MIC2: OK IMB 255 DB" = 28 glyphs = 336 px. Both end inside the 400 px width.
    if (info.noise.mic.valid) {
      const int n = snprintf(
          line,
          sizeof(line),
          "MIC1: %s MIC2: %s",
          MicSelfTest::label(info.noise.mic.channel[0]),
          MicSelfTest::label(info.noise.mic.channel[1]));
      if (info.noise.mic.imbalanced() && n > 0 && static_cast<size_t>(n) < sizeof(line)) {
        snprintf(
            line + n,
            sizeof(line) - static_cast<size_t>(n),
            " IMB %u DB",
            static_cast<unsigned>(info.noise.mic.imbalance_db));
      }
    } else {
      snprintf(line, sizeof(line), "MIC: --");
    }
    drawLine(fb, 10, MIC_TEST_Y, line);

    snprintf(
        line,
        sizeof(line),
        "MIC CAL: %s",
        info.mic_cal_busy ? "MEASURING"
                          : (info.mic_cal_failed ? "FAILED"
                                                 : (info.mic_cal.calibrated ? "OK" : "DEFAULT")));
    drawLine(fb, 10, 244, line);

    // Loopback line at y = 260, between the calibration line (ends y = 258) and the config line.
    info.loopback.describe(line, sizeof(line));
    drawLine(fb, 10, LOOPBACK_Y, line);

    snprintf(line, sizeof(line), "CONFIG: %s", info.config_from_sd ? "SD" : "DEFAULT");
    drawLine(fb, 10, 276, line);
  }

  // Pixel height of a bar level (0 to 100): 2 px per level step.
  static uint16_t spectrumBarHeight(uint8_t level) {
    const uint8_t clamped = level > SpectrumReading::LEVEL_MAX ? SpectrumReading::LEVEL_MAX : level;
    return static_cast<uint16_t>(clamped * 2);
  }

  // Draws a label so that its middle sits at center_x, kept inside the screen.
  static void drawCenteredLabel(St7305Framebuffer& fb, uint16_t center_x, uint16_t y, const char* text) {
    const int width = static_cast<int>(strlen(text)) * 12 - 2;
    int x = static_cast<int>(center_x) - width / 2;
    if (x < 0) {
      x = 0;
    }
    if (x + width > RLCD_WIDTH) {
      x = RLCD_WIDTH - width;
    }
    fb.drawText(static_cast<uint16_t>(x), y, text, 2, true);
  }

  static void renderSpectrum(
      St7305Framebuffer& fb,
      const BatteryReading& battery,
      const SystemInfo& info,
      const char* clock_text) {
    renderHeader(fb, "AUDIO SPECTRUM", battery, clock_text, info.quiet_hours);

    fb.drawHLine(SPECTRUM_X, SPECTRUM_BASE_Y, SpectrumReading::BANDS * SPECTRUM_BAR_PITCH - 2, true);
    if (!info.spectrum.valid) {
      drawLine(fb, 10, 120, "NO DATA");
    } else {
      for (size_t b = 0; b < SpectrumReading::BANDS; ++b) {
        const uint16_t x = static_cast<uint16_t>(SPECTRUM_X + b * SPECTRUM_BAR_PITCH);
        const uint16_t h = spectrumBarHeight(info.spectrum.bars[b]);
        if (h > 0) {
          fb.fillRect(x, static_cast<uint16_t>(SPECTRUM_BASE_Y - h), SPECTRUM_BAR_W, h, true);
        }
        // The peak marker is a short line above the bar. A peak at the bar top stays visible.
        const uint16_t ph = spectrumBarHeight(info.spectrum.peaks[b]);
        if (ph > 0) {
          const uint16_t top = static_cast<uint16_t>(SPECTRUM_BASE_Y - ph);
          const uint16_t marker_y = top >= SPECTRUM_TOP_Y + SPECTRUM_PEAK_H + 1
                                        ? static_cast<uint16_t>(top - SPECTRUM_PEAK_H - 1)
                                        : SPECTRUM_TOP_Y;
          fb.fillRect(x, marker_y, SPECTRUM_BAR_W, SPECTRUM_PEAK_H, true);
        }
      }
    }

    // Frequency labels at their log positions: 100 Hz at the left edge, 8 kHz at the right edge.
    const uint16_t span = static_cast<uint16_t>(SpectrumReading::BANDS * SPECTRUM_BAR_PITCH - 2);
    const float one_k = logf(1000.0f / SpectrumAnalyzer::MIN_HZ) /
                        logf(SpectrumAnalyzer::MAX_HZ / SpectrumAnalyzer::MIN_HZ);
    fb.drawText(SPECTRUM_X, SPECTRUM_LABEL_Y, "100", 2, true);
    drawCenteredLabel(
        fb, static_cast<uint16_t>(SPECTRUM_X + lroundf(one_k * span)), SPECTRUM_LABEL_Y, "1K");
    drawCenteredLabel(fb, static_cast<uint16_t>(SPECTRUM_X + span - 11), SPECTRUM_LABEL_Y, "8K");
    drawLine(fb, 10, 272, "LOW  FREQUENCY  HIGH");
  }

  // Full-screen warning of the empty battery protection. Two frames, "BATTERY EMPTY" and
  // "CHARGE NOW" at scale 4 (308 px and 236 px wide), the voltage and the complete warning text at
  // scale 2 (310 px). The reflective panel keeps the image without power.
  static void renderEmptyBattery(St7305Framebuffer& fb, const BatteryReading& battery) {
    fb.clear(false);
    fb.drawRect(0, 0, RLCD_WIDTH, RLCD_HEIGHT, true);
    fb.drawRect(1, 1, RLCD_WIDTH - 2, RLCD_HEIGHT - 2, true);
    fb.drawRect(6, 6, RLCD_WIDTH - 12, RLCD_HEIGHT - 12, true);
    drawCenteredLabel4(fb, 70, "BATTERY EMPTY");
    drawCenteredLabel4(fb, 120, "CHARGE NOW");
    char volt[16];
    snprintf(volt, sizeof(volt), "%.2f V", static_cast<double>(battery.voltage_v));
    drawCenteredLabel(fb, RLCD_WIDTH / 2, 190, volt);
    drawCenteredLabel(fb, RLCD_WIDTH / 2, 230, EMPTY_BATTERY_TEXT);
  }

  static void drawCenteredLabel4(St7305Framebuffer& fb, uint16_t y, const char* text) {
    const int width = static_cast<int>(strlen(text)) * 24 - 4;
    fb.drawText(static_cast<uint16_t>((RLCD_WIDTH - width) / 2), y, text, 4, true);
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
      const ClimateHistory* history = nullptr,
      const NoiseHistory* noise_history = nullptr) {
    if (battery.empty_protection) {
      renderEmptyBattery(fb, battery);
      return;
    }
    switch (mode) {
      case DisplayMode::SENSOR_STATS:
        renderSensorStats(fb, battery, switchbot_slots, switchbot_count, clock_text, now_ms, info.quiet_hours, noise_history, history);
        break;
      case DisplayMode::SYSTEM_DIAGNOSTICS:
        renderDiagnostics(fb, battery, info, clock_text);
        break;
      case DisplayMode::AUDIO_SPECTRUM:
        renderSpectrum(fb, battery, info, clock_text);
        break;
      case DisplayMode::DASHBOARD:
      default:
        render(fb, onboard, battery, switchbot_slots, switchbot_count, clock_text, now_ms, history, info.quiet_hours);
        break;
    }
  }
};

} // namespace rlcd_panel
