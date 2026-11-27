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
#include "device_config.h"
#include "pcf85063_rtc.h"
#include "sd_logger.h"
#include "es7210_mic.h"
#include "ntp_sync.h"
#include "shtc3_sensor.h"
#include "sparkline.h"
#include "st7305_framebuffer.h"
#include "switchbot_ble.h"
#include "drift_latch.h"
#include "heat_latch.h"
#include "dry_latch.h"
#include "window_latch.h"
#include "wall_latch.h"
#include "ventilate_latch.h"

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
  bool muted = false;       // the mute flag is set; the header shows "M" and wins over "Q"
  bool ventilate = false;   // ventilation advice is active; the dashboard header shows the banner
  uint16_t ventilate_tiles = 0; // VentilateLatch bits: the tiles that show the "V" badge
  uint16_t sensor_battery_latch = 0; // SensorBatteryLatch bits: the slot tiles that show "BAT LOW"
  uint16_t dry_tiles = 0;       // DryLatch bits: the tiles that show the "D" badge
  uint16_t window_tiles = 0;    // WindowLatch bits: the tiles that show the "W" badge
  uint16_t wall_tiles = 0;      // WallLatch bits: the tiles that show the "C" badge
  uint16_t reception_low = 0;   // PersistentState::reception_low: bit i is slot i + 1, shows the "R" badge
  uint16_t drift_tiles = 0;     // PersistentState::drift_tiles: bit 0 onboard, bits 1 to 10 slots, shows the "S" badge
  uint16_t heat_tiles = 0;      // PersistentState::heat_tiles: bit 0 onboard, bits 1 to 10 slots, shows the "H" badge
  bool config_from_sd = false; // true: settings come from /sdcard/config.ini
  uint8_t config_rejected = 0; // rejected config.ini lines; above 0 shows "(N ERR)"
  LoopbackResult loopback;     // last speaker to microphone test of this wake cycle
  SpectrumReading spectrum;    // last spectrum capture; invalid shows "NO DATA"
  uint8_t dashboard_page = 0;  // dashboard page (0: tiles 0-5, 1: tiles 6-10) when >6 active tiles exist
  uint8_t stats_page = 0;      // stats view page: 0 slots 1 to 3, 1 slots 4 and 5 (needs an active slot 4 or 5)
  uint8_t chart_sensor = 0;    // sensor of the climate chart view: 0 onboard, 1 to 3 registry slots
  BatteryRuntime runtime;      // remaining battery runtime; invalid shows "--"
  int32_t cycle_tenths = -1;   // battery charge cycles in tenths; negative (unknown) shows "--"
  RtcDriftState rtc_drift;     // RTC drift of the last NTP sync; no figure shows "--"
  int32_t sd_free_mb = -1;     // free MicroSD space in MB; negative (unmounted, unknown) shows "--"
  SdStatus sd_status = SdStatus::UNKNOWN; // card fault replaces the free space text (OK and UNKNOWN keep it)
  Shtc3Health shtc3;           // failed onboard SHTC3 reads; "SHTC3 ERR: N" is inverted from a run of 3
  NtpSyncState ntp;            // RTC copy of the NTP attempt state for the "NTP:" line
  bool ntp_credentials = false; // Wi-Fi credentials exist; without them the line shows "NTP: --"
  uint32_t now_ms = 0;         // RTC-backed clock (nowMs()) for the age of the last sync
  const ReceptionHistory* reception = nullptr; // BLE reception rate of each slot for the stats view; null shows "RX --"
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
  static constexpr const char* VENTILATE_TEXT = "VENTILATE";
  static constexpr const char* EMPTY_BATTERY_TEXT = "BATTERY EMPTY - CHARGE NOW";
  static constexpr uint16_t STALE_BADGE_W = 35;
  static constexpr uint16_t STALE_BADGE_H = 17;
  // Trend curve right of the humidity line ("H: 100 %" ends at x + 106). The box lies fully
  // inside the tile: 6 px from the right border, between the divider (y + 24) and the bottom.
  static constexpr uint16_t SPARKLINE_W = 72;
  static constexpr uint16_t SPARKLINE_H = 16;
  static constexpr uint16_t SPARKLINE_RIGHT_MARGIN = 6;
  static constexpr uint16_t WIDE_SPARKLINE_W = 200; // wide standard tile (388 x 124): curve beside the text lines
  static constexpr uint16_t WIDE_SPARKLINE_H = 46;
  static constexpr uint16_t SPARKLINE_Y_OFFSET = 66;
  // Ventilation badge: inverted 9x9 box with a "V" (scale 1). It sits right of the humidity line
  // ("H: 100 %" ends at x + 106), directly above the sparkline box (starts at y + 66) and below the
  // temperature line (ends at y + 53), so it touches neither text nor curve.
  static constexpr uint16_t VENT_BADGE_X_OFFSET = 108;
  static constexpr uint16_t VENT_BADGE_Y_OFFSET = 56;
  static constexpr uint16_t VENT_BADGE_SIZE = 9;
  // Dry air badge "D": same box as the "V" badge, one pixel gap beside it (right of it, except in the narrow
  // compact tile, where the tile border is on the right and the badge sits left of it).
  static constexpr uint16_t DRY_BADGE_STEP = VENT_BADGE_SIZE + 1;
  static constexpr uint16_t BAT_LOW_Y_OFFSET = 96;
  static constexpr uint16_t BAT_LOW_W = 86;
  static constexpr uint16_t BAT_LOW_H = 18;
  // Trend triangle (7 x 7 px, one text row high) 4 px right of the temperature or humidity line. It
  // sits 3 px below the line top, so it is centered on the 14 px text row. It is skipped when it
  // would touch the sparkline box ("H: 100 %" ends at x + 106, the box starts at x + 112).
  static constexpr uint16_t TREND_MARK_SIZE = 7;
  static constexpr uint16_t TREND_MARK_GAP = 4;
  static constexpr uint16_t TREND_MARK_Y_INSET = 3;
  static constexpr uint16_t TEMP_LINE_Y_OFFSET = 40;
  static constexpr uint16_t HUMIDITY_LINE_Y_OFFSET = 68;
  // Dew point and VPD line (scale 1, 7 px high) between the sparkline box (ends at y + 82) and the
  // battery line (starts at y + 96). The widest text has 19 glyphs = 113 px, inside the 190 px tile.
  static constexpr uint16_t CLIMATE_LINE_Y_OFFSET = 86;
  static constexpr uint16_t CLIMATE_LINE_H = 7;
  // Sensor stats: noise trend line "NOISE 140-140 DB" (16 glyphs, 192 px) and its curve box right of
  // it, above the "ONBOARD BAT" line at y = 270.
  static constexpr uint16_t SENSOR_BLOCK_PITCH = 72; // 4 text lines of 18 px
  // Comfort label in small text (6 px per glyph) right-aligned on the packet line (y + 36, 14 px high):
  // "COMFORT HUMID" is 13 glyphs = 78 px and ends at x = 392 (8 px right margin). The longest packet
  // text "PKT 4294967295 AGE 1193 H" (4-digit hours before the 49.7-day wrap) ends at x = 310, left of
  // the widest label start x = 314.
  static constexpr uint16_t COMFORT_RIGHT_X = 392;
  static constexpr uint16_t COMFORT_Y_OFFSET = 39;
  static constexpr uint16_t MOLD_Y_OFFSET = 21; // MAC line
  // Feels-like text on the first line, right-aligned to COMFORT_RIGHT_X. The widest text is
  // "FEELS -45C" or "FEELS 140F" = 10 glyphs = 60 px, so it starts at x = 332, 10 px right of the
  // widest RSSI line ("<12 glyphs> RSSI -100 DBM" ends at x = 322).
  static constexpr uint16_t FEELS_Y_OFFSET = 3;
  // The empty slot shows its comfort label between the FEELS text (y + 3 to y + 9) and the MOLD text (y + 21).
  static constexpr uint16_t COMFORT_EMPTY_Y_OFFSET = 11;
  // Absolute humidity text on the record line (y + 54, scale 1, 7 px high), right-aligned to COMFORT_RIGHT_X.
  // The record text (scale 2) starts at x = 10 and reaches x = 370 in the widest case, so the AH text
  // (up to "AH 130.4G" = 54 px, x = 338) is drawn only when a gap of AH_GAP_PX stays to the record text.
  static constexpr uint16_t ABS_HUMIDITY_Y_OFFSET = 54;
  static constexpr uint16_t AH_GAP_PX = 4;
  // Wall humidity text (scale 1, right-aligned to COMFORT_RIGHT_X) at y + 30, between the MOLD text (ends at
  // y + 28) and the comfort label (starts at y + 39). "WALL 100%" = 9 glyphs = 54 px, x = 338. The MAC line
  // (scale 2, y + 18 to y + 32) is the next text on the left; WALL is drawn only when WALL_GAP_PX stay free.
  static constexpr uint16_t WALL_Y_OFFSET = 30;
  static constexpr uint16_t WALL_GAP_PX = 4;
  // Dry hours text (scale 1) on the MOLD line (y + 21), right-aligned DRY_MOLD_GAP_PX left of the MOLD text.
  // "DRY 24H" = 7 glyphs = 42 px. The MAC line (scale 2) is the next text on the left; DRY is drawn only when
  // DRY_GAP_PX stay free.
  static constexpr uint16_t DRY_MOLD_GAP_PX = 6;
  static constexpr uint16_t DRY_GAP_PX = 4;
  // Reception text (scale 1, right-aligned to COMFORT_RIGHT_X) at y + 11 on the RSSI line, below the FEELS text
  // (y + 3 to y + 9) and above the MOLD text (y + 21). "RX 100%" = 7 glyphs = 42 px, x = 350. The inverted box
  // (2 px padding left and right, y + 10 to y + 18) marks a rate below 50 %. The RSSI line (scale 2, up to x = 322)
  // is the next text on the left; RX is drawn only when RX_GAP_PX stay free.
  static constexpr uint16_t RX_Y_OFFSET = 11;
  static constexpr uint16_t RX_GAP_PX = 4;
  static constexpr uint8_t RX_MIN_SCANS = 6;
  static constexpr uint16_t NOISE_TREND_Y = 248;
  static constexpr uint16_t NOISE_SPARKLINE_X = 216;
  static constexpr uint16_t NOISE_SPARKLINE_Y = 246;
  static constexpr uint16_t NOISE_SPARKLINE_W = 170;
  static constexpr uint16_t NOISE_SPARKLINE_H = 20;
  static constexpr uint16_t EVENTS_X = 232;   // diagnostics: "EVENTS: N" right of the config text
  static constexpr uint16_t EVENTS_Y = 276;   // same line as "CONFIG:"; 13 glyphs end at x = 388
  static constexpr uint16_t EVENTS_ERR_X = 264; // short "EV: N" right of "CONFIG: SD (255 ERR)"; 9 glyphs end at x = 372
  // Diagnostics: "SHTC3 ERR: N" right of the power line (ends at x = 178). The widest text is
  // "SHTC3 ERR: 196605" = 17 glyphs = 204 px, from x = 190 to x = 394. The inverted box adds 2 px.
  static constexpr uint16_t SHTC3_X = 190;
  static constexpr uint16_t SHTC3_Y = 44;
  static constexpr uint16_t LOOPBACK_Y = 260; // diagnostics: speaker to microphone test line
  static constexpr uint16_t MIC_TEST_Y = 224; // diagnostics: microphone self-test line
  static constexpr uint16_t PERCENTILE_X = 240; // diagnostics: "L10/L90" text (scale 1) right of "SD FREE"
  static constexpr uint16_t NTP_X = 300; // diagnostics: "NTP:" text (scale 1) right of the "UP:" line; 16 glyphs end at x = 396
  static constexpr uint16_t NTP_Y = 168; // same line as "UP:" (y = 164)
  static constexpr uint16_t PERCENTILE_Y = 148; // same line as "SD FREE:" (y = 144); 21 glyphs end at x = 366
  // Spectrum view: 32 bars of 10 px with a 2 px gap fill x = 8 to 391. A level of 100 gives a bar
  // of 200 px, from y = 40 to the baseline at y = 240. The labels sit under the baseline.
  static constexpr uint16_t SPECTRUM_X = 8;
  static constexpr uint16_t SPECTRUM_BAR_W = 10;
  static constexpr uint16_t SPECTRUM_BAR_PITCH = 12;
  static constexpr uint16_t SPECTRUM_TOP_Y = 40;
  static constexpr uint16_t SPECTRUM_BASE_Y = 240;
  static constexpr uint16_t SPECTRUM_LABEL_Y = 248;
  static constexpr uint16_t SPECTRUM_PEAK_H = 2;
  // Climate chart view: two curve boxes of 360 x 90 px with a 1 px frame. The sensor name sits at
  // y = 34, each curve has a label line (scale 1) above its frame.
  static constexpr uint16_t CHART_X = 20;
  static constexpr uint16_t CHART_W = 360;
  static constexpr uint16_t CHART_H = 90;
  static constexpr uint16_t CHART_NAME_Y = 34;
  static constexpr uint16_t CHART_TEMP_LABEL_Y = 52;
  static constexpr uint16_t CHART_TEMP_Y = 62;
  static constexpr uint16_t CHART_HUMIDITY_LABEL_Y = 160;
  static constexpr uint16_t CHART_HUMIDITY_Y = 170;
  static constexpr uint16_t CHART_FOOTER_Y = 272;
  // Week chart: the same boxes as the climate chart; the weekday labels sit below the humidity box.
  static constexpr uint16_t WEEK_LABEL_OFFSET_Y = CHART_H + 3;

  // Dynamic tile grid. The area below the header is x = 6, y = 36, 388 x 256 px with an 8 px gap.
  // Tile 0 is the onboard sensor. A registry slot gets a tile when it holds a reading, has a fixed
  // MAC or has a name. N = 1 + active slots (1 to 6):
  //   1: one tile 388 x 256            2: two tiles 190 x 256 side by side
  //   3: one tile 388 x 124 + two 190 x 124   4: 2 x 2 grid of 190 x 124
  //   5: two 190 x 124 + three 124 x 124      6: 2 x 3 grid of 190 x 80
  static constexpr uint16_t GRID_X = 6;
  static constexpr uint16_t GRID_Y = 36;
  static constexpr uint16_t GRID_W = 388;
  static constexpr uint16_t GRID_H = 256;
  static constexpr uint16_t GRID_GAP = 8;
  static constexpr size_t MAX_TILES = 1 + MAX_SWITCHBOT_SENSORS;
  // The stats view has room for three slot blocks (72 px pitch).
  static constexpr size_t STATS_SLOT_COUNT = 3;

  struct TileRect {
    uint16_t x = 0;
    uint16_t y = 0;
    uint16_t w = 0;
    uint16_t h = 0;
  };

  enum class TileStyle : uint8_t { STANDARD, COMPACT, LARGE };

  // Tile style by size: LARGE from 200 px height, COMPACT below 100 px height or 160 px width.
  static TileStyle tileStyle(uint16_t w, uint16_t h) {
    if (h >= 200) {
      return TileStyle::LARGE;
    }
    if (h < 100 || w < 160) {
      return TileStyle::COMPACT;
    }
    return TileStyle::STANDARD;
  }

  // Fills `out` with the rectangles of `count` tiles (clamped to 1..6). Returns the tile count.
  static size_t layoutTiles(size_t count, TileRect (&out)[MAX_TILES]) {
    const size_t n = count < 1 ? 1 : (count > 6 ? 6 : count);
    const uint16_t half_w = static_cast<uint16_t>((GRID_W - GRID_GAP) / 2);                  // 190
    const uint16_t third_w = static_cast<uint16_t>((GRID_W - 2 * GRID_GAP) / 3);             // 124
    const uint16_t half_h = static_cast<uint16_t>((GRID_H - GRID_GAP) / 2);                  // 124
    const uint16_t third_h = static_cast<uint16_t>((GRID_H - 2 * GRID_GAP) / 3);             // 80
    const uint16_t col2_x = static_cast<uint16_t>(GRID_X + half_w + GRID_GAP);
    const uint16_t row2_y = static_cast<uint16_t>(GRID_Y + half_h + GRID_GAP);
    auto set = [&out](size_t i, uint16_t x, uint16_t y, uint16_t w, uint16_t h) {
      out[i].x = x;
      out[i].y = y;
      out[i].w = w;
      out[i].h = h;
    };
    switch (n) {
      case 1:
        set(0, GRID_X, GRID_Y, GRID_W, GRID_H);
        break;
      case 2:
        set(0, GRID_X, GRID_Y, half_w, GRID_H);
        set(1, col2_x, GRID_Y, half_w, GRID_H);
        break;
      case 3:
        set(0, GRID_X, GRID_Y, GRID_W, half_h);
        set(1, GRID_X, row2_y, half_w, half_h);
        set(2, col2_x, row2_y, half_w, half_h);
        break;
      case 4:
        set(0, GRID_X, GRID_Y, half_w, half_h);
        set(1, col2_x, GRID_Y, half_w, half_h);
        set(2, GRID_X, row2_y, half_w, half_h);
        set(3, col2_x, row2_y, half_w, half_h);
        break;
      case 5:
        set(0, GRID_X, GRID_Y, half_w, half_h);
        set(1, col2_x, GRID_Y, half_w, half_h);
        for (size_t i = 0; i < 3; ++i) {
          set(2 + i, static_cast<uint16_t>(GRID_X + i * (third_w + GRID_GAP)), row2_y, third_w, half_h);
        }
        break;
      default:
        for (size_t i = 0; i < 6; ++i) {
          set(i, i % 2 == 0 ? GRID_X : col2_x,
              static_cast<uint16_t>(GRID_Y + (i / 2) * (third_h + GRID_GAP)), half_w, third_h);
        }
        break;
    }
    return n;
  }

  // True when registry slot `slot` gets a tile.
  static bool slotActive(const SwitchBotReading* slots, size_t slot_count, const DeviceConfig* config, size_t slot) {
    if (config != nullptr && !config->isSlotEnabled(slot)) {
      return false;
    }
    if (slots != nullptr && slot < slot_count && slots[slot].valid) {
      return true;
    }
    return config != nullptr && slot < DeviceConfig::SLOT_COUNT &&
           (config->hasSlotMac(slot) || config->slot_name[slot][0] != '\0');
  }

  // MAC address of the sensor in registry slot `slot`: the MAC of its reading, else the fixed MAC of
  // the slot, else nullptr. The display name follows this MAC, not the slot index.
  static const uint8_t* slotActiveMac(const SwitchBotReading* slots, size_t slot_count, const DeviceConfig* config,
                                      size_t slot) {
    if (config != nullptr && !config->isSlotEnabled(slot)) {
      return nullptr;
    }
    if (slots != nullptr && slot < slot_count && slots[slot].valid) {
      return slots[slot].mac;
    }
    if (config != nullptr && config->hasSlotMac(slot)) {
      return config->slot_mac[slot];
    }
    return nullptr;
  }

  // Number of tiles (1 to 6): the onboard tile plus each active slot. `slot_of_tile[t]` gets the
  // registry slot of tile t >= 1 (index 0 is unused).
  static size_t activeTileCount(const SwitchBotReading* slots, size_t slot_count, const DeviceConfig* config,
                                size_t (&slot_of_tile)[MAX_TILES]) {
    size_t n = 1;
    slot_of_tile[0] = 0;
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS && n < MAX_TILES; ++i) {
      if (slotActive(slots, slot_count, config, i)) {
        slot_of_tile[n++] = i;
      }
    }
    return n;
  }

  // Largest scale (1 to max_scale) at which `text` fits into max_w px.
  static uint8_t fitScale(const char* text, uint16_t max_w, uint8_t max_scale) {
    const size_t glyphs = strlen(text);
    for (uint8_t sc = max_scale; sc > 1; --sc) {
      if (glyphs * 6u * sc <= static_cast<size_t>(max_w) + sc) {
        return sc;
      }
    }
    return 1;
  }

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

  // Draws a 7 x 7 triangle for RISE (apex up) or FALL (apex down) at the top left (mx, my).
  // Row widths 1, 3, 3, 5, 5, 7, 7. STEADY and UNKNOWN draw nothing.
  static void renderTrendMark(St7305Framebuffer& fb, uint16_t mx, uint16_t my, Trend trend) {
    if (trend != Trend::RISE && trend != Trend::FALL) {
      return;
    }
    for (uint16_t row = 0; row < TREND_MARK_SIZE; ++row) {
      const uint16_t widest_row = trend == Trend::RISE ? row : static_cast<uint16_t>(TREND_MARK_SIZE - 1 - row);
      const uint16_t row_w = static_cast<uint16_t>(2 * ((widest_row + 1) / 2) + 1);
      fb.fillRect(
          static_cast<uint16_t>(mx + (TREND_MARK_SIZE - row_w) / 2), static_cast<uint16_t>(my + row), row_w, 1, true);
    }
  }

  // Trend mark right of a text line at scale 2 that starts at x + 12. `glyphs` is the line length.
  // `limit_x` is the first column the mark must not reach (0 for no limit).
  static void renderTrendAfterLine(
      St7305Framebuffer& fb, uint16_t x, uint16_t line_y, size_t glyphs, uint16_t limit_x, Trend trend) {
    const uint16_t mx = static_cast<uint16_t>(x + 12 + glyphs * 12 - 2 + TREND_MARK_GAP);
    if (limit_x != 0 && mx + TREND_MARK_SIZE >= limit_x) {
      return;
    }
    renderTrendMark(fb, mx, static_cast<uint16_t>(line_y + TREND_MARK_Y_INSET), trend);
  }

  // Dew point and VPD line; shows "DP -- VPD --" for a stale, invalid or NaN reading.
  static void renderClimateLine(
      St7305Framebuffer& fb, uint16_t x, uint16_t y, uint16_t h, bool usable, float temp_c,
      float humidity_pct, char unit = 'C') {
    if (h <= CLIMATE_LINE_Y_OFFSET + CLIMATE_LINE_H) {
      return;
    }
    char line[ClimateMath::LINE_MAX_CHARS + 1];
    ClimateMath::format(line, sizeof(line), usable, temp_c, humidity_pct, unit);
    fb.drawText(x + 12, y + CLIMATE_LINE_Y_OFFSET, line, 1, true);
  }

  // Tile title: the configured name, else "ONBOARD SHTC3" (tile 0) or "SENSOR n" (tile n = 1..5).
  // A name has at most 12 glyphs (142 px at scale 2), so it ends at x + 150 and stays left of the
  // STALE badge without age, which starts at x + 151. A badge with age falls back to the age only
  // when the full text leaves a gap below 3 px.
  // A slot tile takes the name that belongs to `mac` (the sensor in the slot); an unknown or missing MAC
  // gives "SENSOR n".
  static void tileTitle(char* out, size_t capacity, const DeviceConfig* config, size_t tile,
                        const uint8_t* mac = nullptr) {
    const char* name = nullptr;
    if (config != nullptr) {
      name = tile == 0 ? config->onboard_name : config->nameForMac(mac);
    }
    if (name != nullptr && name[0] != '\0') {
      snprintf(out, capacity, "%s", name);
    } else if (tile == 0) {
      snprintf(out, capacity, "ONBOARD SHTC3");
    } else {
      snprintf(out, capacity, "SENSOR %u", static_cast<unsigned>(tile));
    }
  }

  // Stats view label of registry slot `slot` (0..2): the configured name, else "S1" to "S3".
  // The longest line is "<12 glyphs> RSSI -100 DBM" = 26 glyphs = 312 px.
  // The name belongs to `mac`, the MAC of the sensor in the slot.
  static void statsLabel(char* out, size_t capacity, const DeviceConfig* config, size_t slot,
                         const uint8_t* mac = nullptr) {
    const char* name = config != nullptr ? config->nameForMac(mac) : nullptr;
    if (name != nullptr) {
      snprintf(out, capacity, "%s", name);
    } else {
      snprintf(out, capacity, "S%u", static_cast<unsigned>(slot + 1));
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
      const HumidityHistory* history = nullptr,
      bool ventilate_badge = false,
      const TemperatureHistory* temperature_history = nullptr,
      bool battery_low = false,
      char unit = 'C',
      bool dry_badge = false,
      const char* stale_age = nullptr,
      const char* format_badge = nullptr,
      bool window_badge = false,
      bool wall_badge = false, bool weak_badge = false, bool drift_badge = false, bool heat_badge = false) {
    const TileStyle style = tileStyle(w, h);
    if (style == TileStyle::COMPACT) {
      renderTileCompact(fb, x, y, w, h, title, valid, temp_c, humidity_pct, battery_pct, battery_voltage_v, is_stale,
                        history, ventilate_badge, temperature_history, battery_low, unit, dry_badge, stale_age, format_badge, window_badge, wall_badge, weak_badge, drift_badge, heat_badge);
      return;
    }
    if (style == TileStyle::LARGE) {
      renderTileLarge(fb, x, y, w, h, title, valid, temp_c, humidity_pct, battery_pct, battery_voltage_v, is_stale,
                      history, ventilate_badge, temperature_history, battery_low, unit, dry_badge, stale_age, format_badge, window_badge, wall_badge, weak_badge, drift_badge, heat_badge);
      return;
    }
    drawTileFrame(fb, x, y, w, h, title, is_stale, stale_age, format_badge);

    if (!valid) {
      fb.drawText(x + 12, y + 56, "NO SIGNAL", 2, true);
      renderClimateLine(fb, x, y, h, false, 0.0f, 0.0f, unit);
      return;
    }

    // Widest text: "T: -148.0 F" (-100 C) = 11 glyphs = 132 px, so it ends at x + 144, inside the 190 px tile.
    char line_temp[32];
    snprintf(line_temp, sizeof(line_temp), "T: %.1f %c", static_cast<double>(ClimateMath::toDisplay(temp_c, unit)),
             unit == 'F' ? 'F' : 'C');
    fb.drawText(x + 12, y + TEMP_LINE_Y_OFFSET, line_temp, 2, true);

    char line_rh[24];
    snprintf(line_rh, sizeof(line_rh), "H: %.0f %%", humidity_pct);
    fb.drawText(x + 12, y + HUMIDITY_LINE_Y_OFFSET, line_rh, 2, true);

    // A wide tile (388 px) draws a larger curve at the right, beside both text lines.
    const bool wide = w > 250;
    if (wide && history != nullptr && history->size() >= 2) {
      Sparkline::render(fb, static_cast<uint16_t>(x + w - WIDE_SPARKLINE_W - SPARKLINE_RIGHT_MARGIN),
                        static_cast<uint16_t>(y + 32), WIDE_SPARKLINE_W, WIDE_SPARKLINE_H, *history, true);
    }

    // Trend marks from the hourly samples; no history and a stale tile give no mark.
    if (temperature_history != nullptr && !is_stale) {
      renderTrendAfterLine(
          fb, x, static_cast<uint16_t>(y + TEMP_LINE_Y_OFFSET), strlen(line_temp), 0,
          TrendClassifier::temperature(*temperature_history));
    }
    if (history != nullptr && !is_stale) {
      const uint16_t box_x = wide ? static_cast<uint16_t>(x + w - WIDE_SPARKLINE_W - SPARKLINE_RIGHT_MARGIN)
                                  : static_cast<uint16_t>(x + w - SPARKLINE_W - SPARKLINE_RIGHT_MARGIN);
      renderTrendAfterLine(
          fb, x, static_cast<uint16_t>(y + HUMIDITY_LINE_Y_OFFSET), strlen(line_rh), box_x,
          TrendClassifier::humidity(*history));
    }

    renderClimateLine(fb, x, y, h, !is_stale, temp_c, humidity_pct, unit);

    if (ventilate_badge && !is_stale) {
      const uint16_t bx = static_cast<uint16_t>(x + VENT_BADGE_X_OFFSET);
      const uint16_t by = static_cast<uint16_t>(y + VENT_BADGE_Y_OFFSET);
      fb.fillRect(bx, by, VENT_BADGE_SIZE, VENT_BADGE_SIZE, true);
      fb.drawText(bx + 2, by + 1, "V", 1, false);
    }
    if (dry_badge && !is_stale) {
      const uint16_t bx = static_cast<uint16_t>(x + VENT_BADGE_X_OFFSET + (ventilate_badge ? DRY_BADGE_STEP : 0));
      const uint16_t by = static_cast<uint16_t>(y + VENT_BADGE_Y_OFFSET);
      fb.fillRect(bx, by, VENT_BADGE_SIZE, VENT_BADGE_SIZE, true);
      fb.drawText(bx + 2, by + 1, "D", 1, false);
    }
    if (window_badge && !is_stale) {
      const uint16_t bx = static_cast<uint16_t>(x + VENT_BADGE_X_OFFSET + (ventilate_badge ? DRY_BADGE_STEP : 0) +
                                                (dry_badge ? DRY_BADGE_STEP : 0));
      const uint16_t by = static_cast<uint16_t>(y + VENT_BADGE_Y_OFFSET);
      fb.fillRect(bx, by, VENT_BADGE_SIZE, VENT_BADGE_SIZE, true);
      fb.drawText(bx + 2, by + 1, "W", 1, false);
    }
    if (wall_badge && !is_stale) {
      const uint16_t bx = static_cast<uint16_t>(x + VENT_BADGE_X_OFFSET + (ventilate_badge ? DRY_BADGE_STEP : 0) +
                                                (dry_badge ? DRY_BADGE_STEP : 0) + (window_badge ? DRY_BADGE_STEP : 0));
      const uint16_t by = static_cast<uint16_t>(y + VENT_BADGE_Y_OFFSET);
      fb.fillRect(bx, by, VENT_BADGE_SIZE, VENT_BADGE_SIZE, true);
      fb.drawText(bx + 2, by + 1, "C", 1, false);
    }
    if (weak_badge && !is_stale) {
      const uint16_t bx = static_cast<uint16_t>(x + VENT_BADGE_X_OFFSET + (ventilate_badge ? DRY_BADGE_STEP : 0) +
                                                (dry_badge ? DRY_BADGE_STEP : 0) + (window_badge ? DRY_BADGE_STEP : 0) +
                                                (wall_badge ? DRY_BADGE_STEP : 0));
      const uint16_t by = static_cast<uint16_t>(y + VENT_BADGE_Y_OFFSET);
      fb.fillRect(bx, by, VENT_BADGE_SIZE, VENT_BADGE_SIZE, true);
      fb.drawText(bx + 2, by + 1, "R", 1, false);
    }
    if (drift_badge && !is_stale) {
      const uint16_t bx = static_cast<uint16_t>(x + VENT_BADGE_X_OFFSET + (ventilate_badge ? DRY_BADGE_STEP : 0) +
                                                (dry_badge ? DRY_BADGE_STEP : 0) + (window_badge ? DRY_BADGE_STEP : 0) +
                                                (wall_badge ? DRY_BADGE_STEP : 0) + (weak_badge ? DRY_BADGE_STEP : 0));
      const uint16_t by = static_cast<uint16_t>(y + VENT_BADGE_Y_OFFSET);
      fb.fillRect(bx, by, VENT_BADGE_SIZE, VENT_BADGE_SIZE, true);
      fb.drawText(bx + 2, by + 1, "S", 1, false);
    }
    if (heat_badge && !is_stale) {
      const uint16_t bx = static_cast<uint16_t>(x + VENT_BADGE_X_OFFSET + (ventilate_badge ? DRY_BADGE_STEP : 0) +
                                                (dry_badge ? DRY_BADGE_STEP : 0) + (window_badge ? DRY_BADGE_STEP : 0) +
                                                (wall_badge ? DRY_BADGE_STEP : 0) + (weak_badge ? DRY_BADGE_STEP : 0) +
                                                (drift_badge ? DRY_BADGE_STEP : 0));
      const uint16_t by = static_cast<uint16_t>(y + VENT_BADGE_Y_OFFSET);
      fb.fillRect(bx, by, VENT_BADGE_SIZE, VENT_BADGE_SIZE, true);
      fb.drawText(bx + 2, by + 1, "H", 1, false);
    }

    // A curve needs two samples. One sample would draw a single dot.
    if (!wide && history != nullptr && history->size() >= 2 && w > SPARKLINE_W + SPARKLINE_RIGHT_MARGIN &&
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

    if (battery_low) {
      // Inverted "BAT LOW" (7 glyphs at scale 2 = 82 px wide) in place of the battery line.
      fb.fillRect(x + 10, y + BAT_LOW_Y_OFFSET - 2, BAT_LOW_W, BAT_LOW_H, true);
      fb.drawText(x + 12, y + BAT_LOW_Y_OFFSET, "BAT LOW", 2, false);
    } else if (battery_pct >= 0) {
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
      // The widest text "BAT: 4.20V 100%" is 180 px at scale 2 and would reach x + 192 in a 190 px tile.
      // Such a text drops to scale 1; every shorter text keeps scale 2.
      const uint8_t bat_scale = fitScale(line_bat, static_cast<uint16_t>(w - 16), 2);
      fb.drawText(x + 12, y + (bat_scale == 2 ? 96 : 99), line_bat, bat_scale, true);
    }
  }

  // Width of an inverted badge with `text`: glyphs at scale 1 plus 3 px padding on each side.
  static uint16_t badgeWidth(const char* text) {
    return static_cast<uint16_t>(strlen(text) * 6 - 1 + 6);
  }

  // "STALE <age>" or "STALE" without age. The badge is inverted, 17 px high, 4 px below the tile top
  // and 4 px from its right border. `title_end` is the title end relative to x. The full text needs
  // 3 px of free space after the title, else the badge shows the age only (or "STALE" without age).
  static void drawStaleBadge(St7305Framebuffer& fb, uint16_t x, uint16_t y, uint16_t w, uint16_t title_end,
                             const char* age) {
    char text[16];
    if (age != nullptr && age[0] != '\0') {
      snprintf(text, sizeof(text), "STALE %s", age);
      if (x + w - badgeWidth(text) - 4 < x + title_end + 3) {
        snprintf(text, sizeof(text), "%s", age);
      }
    } else {
      snprintf(text, sizeof(text), "STALE");
    }
    const uint16_t bw = badgeWidth(text);
    const uint16_t bx = static_cast<uint16_t>(x + w - bw - 4);
    fb.fillRect(bx, y + 4, bw, STALE_BADGE_H, true);
    fb.drawText(bx + 3, y + 9, text, 1, false);
  }

  // Title in the band of a tile (scale 2, scale 1 when the tile is too narrow) and the STALE badge.
  static void drawTileFrame(St7305Framebuffer& fb, uint16_t x, uint16_t y, uint16_t w, uint16_t h,
                            const char* title, bool is_stale, const char* stale_age = nullptr,
                            const char* format_badge = nullptr) {
    fb.drawRect(x, y, w, h, true);
    fb.drawHLine(x, y + 24, w, true);
    const bool has_age = stale_age != nullptr && stale_age[0] != '\0';
    const uint16_t badge_w = !is_stale ? 0
                             : has_age ? static_cast<uint16_t>(badgeWidth(stale_age) + 8)
                                       : static_cast<uint16_t>(STALE_BADGE_W + 8);
    const uint16_t room = static_cast<uint16_t>(w - 16 - badge_w);
    const uint8_t sc = fitScale(title, room, 2);
    fb.drawText(x + 8, y + (sc == 2 ? 6 : 9), title, sc, true);
    if (is_stale) {
      drawStaleBadge(fb, x, y, w, static_cast<uint16_t>(8 + strlen(title) * 6 * sc - sc), stale_age);
    } else if (format_badge != nullptr && format_badge[0] != '\0') {
      char badge[12];
      snprintf(badge, sizeof(badge), "[%s]", format_badge);
      const uint16_t bw = static_cast<uint16_t>(strlen(badge) * 6 - 1);
      const uint16_t bx = static_cast<uint16_t>(x + w - bw - 6);
      const uint16_t title_end = static_cast<uint16_t>(8 + strlen(title) * 6 * sc - sc);
      if (bx > x + title_end + 4) {
        fb.drawText(bx, y + 9, badge, 1, true);
      }
    }
  }

  // Battery text of a tile: inverted "BAT LOW", else "BAT: 4.20V 100%" or "BAT: 80 %". A tile
  // without a battery figure draws nothing. `scale` is 1 or 2.
  static void drawTileBattery(St7305Framebuffer& fb, uint16_t x, uint16_t y, int8_t battery_pct, float voltage_v,
                              bool battery_low, uint8_t scale) {
    if (battery_low) {
      const uint16_t bw = static_cast<uint16_t>(7 * 6 * scale - scale + 4);
      fb.fillRect(x, y - 2, bw, static_cast<uint16_t>(7 * scale + 4), true);
      fb.drawText(x + 2, y, "BAT LOW", scale, false);
    } else if (battery_pct >= 0) {
      char line[24];
      if (voltage_v >= 0.0f) {
        snprintf(line, sizeof(line), "BAT: %.2fV %d%%", static_cast<double>(voltage_v), static_cast<int>(battery_pct));
      } else {
        snprintf(line, sizeof(line), "BAT: %d %%", static_cast<int>(battery_pct));
      }
      fb.drawText(x, y, line, scale, true);
    }
  }

  // Tile of a 190 x 80 or 124 x 124 cell: title band, short temperature and humidity lines (scale 2)
  // and a curve. A 124 px wide tile has the title at scale 1 and the curve below the text.
  static void renderTileCompact(
      St7305Framebuffer& fb, uint16_t x, uint16_t y, uint16_t w, uint16_t h, const char* title, bool valid,
      float temp_c, float humidity_pct, int8_t battery_pct, float battery_voltage_v, bool is_stale,
      const HumidityHistory* history, bool ventilate_badge, const TemperatureHistory* temperature_history,
      bool battery_low, char unit, bool dry_badge, const char* stale_age,
      const char* format_badge = nullptr, bool window_badge = false, bool wall_badge = false, bool weak_badge = false, bool drift_badge = false, bool heat_badge = false) {
    drawTileFrame(fb, x, y, w, h, title, is_stale, stale_age, format_badge);
    const bool narrow = w < 160;
    const uint16_t pad = narrow ? 8 : 12;
    if (!valid) {
      fb.drawText(x + pad, y + (h > 100 ? 56 : 36), "NO SIGNAL", 2, true);
      return;
    }
    char line_temp[24];
    snprintf(line_temp, sizeof(line_temp), "%.1f %c", static_cast<double>(ClimateMath::toDisplay(temp_c, unit)),
             unit == 'F' ? 'F' : 'C');
    char line_rh[16];
    snprintf(line_rh, sizeof(line_rh), "%.0f %%", static_cast<double>(humidity_pct));
    const uint16_t temp_y = static_cast<uint16_t>(y + 30);
    const uint16_t rh_y = static_cast<uint16_t>(y + 48);
    fb.drawText(x + pad, temp_y, line_temp, 2, true);
    fb.drawText(x + pad, rh_y, line_rh, 2, true);

    // Curve: right of the text lines (190 x 80) or below them (124 x 124).
    uint16_t box_x = 0;
    if (!narrow) {
      box_x = static_cast<uint16_t>(x + w - SPARKLINE_W - SPARKLINE_RIGHT_MARGIN);
      if (history != nullptr && history->size() >= 2) {
        Sparkline::render(fb, box_x, static_cast<uint16_t>(y + 32), SPARKLINE_W, 30, *history, true);
      }
    } else if (history != nullptr && history->size() >= 2) {
      Sparkline::render(fb, static_cast<uint16_t>(x + 8), static_cast<uint16_t>(y + 68),
                        static_cast<uint16_t>(w - 16), 18, *history, true);
    }
    if (!is_stale) {
      if (temperature_history != nullptr) {
        const uint16_t mx = static_cast<uint16_t>(x + pad + strlen(line_temp) * 12 - 2 + TREND_MARK_GAP);
        if (box_x == 0 || mx + TREND_MARK_SIZE < box_x) {
          renderTrendMark(fb, mx, static_cast<uint16_t>(temp_y + TREND_MARK_Y_INSET),
                          TrendClassifier::temperature(*temperature_history));
        }
      }
      if (history != nullptr) {
        const uint16_t mx = static_cast<uint16_t>(x + pad + strlen(line_rh) * 12 - 2 + TREND_MARK_GAP);
        if (box_x == 0 || mx + TREND_MARK_SIZE < box_x) {
          renderTrendMark(fb, mx, static_cast<uint16_t>(rh_y + TREND_MARK_Y_INSET),
                          TrendClassifier::humidity(*history));
        }
      }
    }
    if (ventilate_badge && !is_stale) {
      const uint16_t bx = static_cast<uint16_t>(narrow ? x + w - 22 : x + 92);
      const uint16_t by = static_cast<uint16_t>(rh_y + 2);
      fb.fillRect(bx, by, VENT_BADGE_SIZE, VENT_BADGE_SIZE, true);
      fb.drawText(bx + 2, by + 1, "V", 1, false);
    }
    if (dry_badge && !is_stale) {
      const uint16_t vx = static_cast<uint16_t>(narrow ? x + w - 22 : x + 92);
      const uint16_t bx = static_cast<uint16_t>(!ventilate_badge ? vx : (narrow ? vx - DRY_BADGE_STEP : vx + DRY_BADGE_STEP));
      const uint16_t by = static_cast<uint16_t>(rh_y + 2);
      fb.fillRect(bx, by, VENT_BADGE_SIZE, VENT_BADGE_SIZE, true);
      fb.drawText(bx + 2, by + 1, "D", 1, false);
    }
    if (window_badge && !is_stale) {
      const uint16_t vx = static_cast<uint16_t>(narrow ? x + w - 22 : x + 92);
      const unsigned before = (ventilate_badge ? 1u : 0u) + (dry_badge ? 1u : 0u);
      const uint16_t bx = static_cast<uint16_t>(narrow ? vx - before * DRY_BADGE_STEP : vx + before * DRY_BADGE_STEP);
      const uint16_t by = static_cast<uint16_t>(rh_y + 2);
      fb.fillRect(bx, by, VENT_BADGE_SIZE, VENT_BADGE_SIZE, true);
      fb.drawText(bx + 2, by + 1, "W", 1, false);
    }
    if (wall_badge && !is_stale) {
      const uint16_t vx = static_cast<uint16_t>(narrow ? x + w - 22 : x + 92);
      const unsigned before = (ventilate_badge ? 1u : 0u) + (dry_badge ? 1u : 0u) + (window_badge ? 1u : 0u);
      const uint16_t bx = static_cast<uint16_t>(narrow ? vx - before * DRY_BADGE_STEP : vx + before * DRY_BADGE_STEP);
      const uint16_t by = static_cast<uint16_t>(rh_y + 2);
      fb.fillRect(bx, by, VENT_BADGE_SIZE, VENT_BADGE_SIZE, true);
      fb.drawText(bx + 2, by + 1, "C", 1, false);
    }
    if (weak_badge && !is_stale) {
      const uint16_t vx = static_cast<uint16_t>(narrow ? x + w - 22 : x + 92);
      const unsigned before =
          (ventilate_badge ? 1u : 0u) + (dry_badge ? 1u : 0u) + (window_badge ? 1u : 0u) + (wall_badge ? 1u : 0u);
      const uint16_t bx = static_cast<uint16_t>(narrow ? vx - before * DRY_BADGE_STEP : vx + before * DRY_BADGE_STEP);
      const uint16_t by = static_cast<uint16_t>(rh_y + 2);
      fb.fillRect(bx, by, VENT_BADGE_SIZE, VENT_BADGE_SIZE, true);
      fb.drawText(bx + 2, by + 1, "R", 1, false);
    }
    if (drift_badge && !is_stale) {
      const uint16_t vx = static_cast<uint16_t>(narrow ? x + w - 22 : x + 92);
      const unsigned before = (ventilate_badge ? 1u : 0u) + (dry_badge ? 1u : 0u) + (window_badge ? 1u : 0u) +
                              (wall_badge ? 1u : 0u) + (weak_badge ? 1u : 0u);
      const uint16_t bx = static_cast<uint16_t>(narrow ? vx - before * DRY_BADGE_STEP : vx + before * DRY_BADGE_STEP);
      const uint16_t by = static_cast<uint16_t>(rh_y + 2);
      fb.fillRect(bx, by, VENT_BADGE_SIZE, VENT_BADGE_SIZE, true);
      fb.drawText(bx + 2, by + 1, "S", 1, false);
    }
    if (heat_badge && !is_stale) {
      const uint16_t vx = static_cast<uint16_t>(narrow ? x + w - 22 : x + 92);
      const unsigned before = (ventilate_badge ? 1u : 0u) + (dry_badge ? 1u : 0u) + (window_badge ? 1u : 0u) +
                              (wall_badge ? 1u : 0u) + (weak_badge ? 1u : 0u) + (drift_badge ? 1u : 0u);
      const uint16_t bx = static_cast<uint16_t>(narrow ? vx - before * DRY_BADGE_STEP : vx + before * DRY_BADGE_STEP);
      const uint16_t by = static_cast<uint16_t>(rh_y + 2);
      fb.fillRect(bx, by, VENT_BADGE_SIZE, VENT_BADGE_SIZE, true);
      fb.drawText(bx + 2, by + 1, "H", 1, false);
    }
    if (narrow) {
      // Dew point and VPD line (113 px at most) from x + 6 so it ends inside the 124 px tile.
      char climate[ClimateMath::LINE_MAX_CHARS + 1];
      ClimateMath::format(climate, sizeof(climate), !is_stale, temp_c, humidity_pct, unit);
      fb.drawText(x + 6, y + 106, climate, 1, true);
    }
    drawTileBattery(fb, static_cast<uint16_t>(x + pad), static_cast<uint16_t>(y + (narrow ? 92 : 66)), battery_pct,
                    battery_voltage_v, battery_low, 1);
  }

  // Tile of 190 x 256 or 388 x 256: big temperature and humidity text, each with its own curve.
  static void renderTileLarge(
      St7305Framebuffer& fb, uint16_t x, uint16_t y, uint16_t w, uint16_t h, const char* title, bool valid,
      float temp_c, float humidity_pct, int8_t battery_pct, float battery_voltage_v, bool is_stale,
      const HumidityHistory* history, bool ventilate_badge, const TemperatureHistory* temperature_history,
      bool battery_low, char unit, bool dry_badge, const char* stale_age,
      const char* format_badge = nullptr, bool window_badge = false, bool wall_badge = false, bool weak_badge = false, bool drift_badge = false, bool heat_badge = false) {
    drawTileFrame(fb, x, y, w, h, title, is_stale, stale_age, format_badge);
    if (!valid) {
      fb.drawText(x + 12, y + h / 2 - 7, "NO SIGNAL", 2, true);
      return;
    }
    const uint16_t pad = 12;
    const uint16_t inner_w = static_cast<uint16_t>(w - 2 * pad);
    char line_temp[24];
    snprintf(line_temp, sizeof(line_temp), "%.1f %c", static_cast<double>(ClimateMath::toDisplay(temp_c, unit)),
             unit == 'F' ? 'F' : 'C');
    char line_rh[16];
    snprintf(line_rh, sizeof(line_rh), "%.0f %%", static_cast<double>(humidity_pct));
    const uint8_t ts = w >= 300 ? 4 : 3;
    const uint16_t text_h = static_cast<uint16_t>(7 * ts);
    const uint16_t gap = 6;
    // 24 px title band, two text lines, climate line (7), battery line (14) and 7 gaps.
    const uint16_t fixed = static_cast<uint16_t>(24 + 2 * text_h + 7 + 14 + 7 * gap);
    uint16_t spark_h = static_cast<uint16_t>((h - fixed) / 2);
    if (spark_h > 60) {
      spark_h = 60;
    }
    uint16_t cy = static_cast<uint16_t>(y + 24 + gap);
    fb.drawText(x + pad, cy, line_temp, ts, true);
    if (!is_stale && temperature_history != nullptr) {
      renderTrendMark(fb, static_cast<uint16_t>(x + pad + strlen(line_temp) * 6 * ts + TREND_MARK_GAP),
                      static_cast<uint16_t>(cy + (text_h - TREND_MARK_SIZE) / 2),
                      TrendClassifier::temperature(*temperature_history));
    }
    cy = static_cast<uint16_t>(cy + text_h + gap);
    if (temperature_history != nullptr && temperature_history->size() >= 2) {
      fb.drawRect(x + pad, cy, inner_w, spark_h, true);
      Sparkline::renderTemperature(fb, static_cast<uint16_t>(x + pad + 1), static_cast<uint16_t>(cy + 1),
                                   static_cast<uint16_t>(inner_w - 2), static_cast<uint16_t>(spark_h - 2),
                                   *temperature_history);
    }
    cy = static_cast<uint16_t>(cy + spark_h + gap);
    fb.drawText(x + pad, cy, line_rh, ts, true);
    const uint16_t rh_end = static_cast<uint16_t>(x + pad + strlen(line_rh) * 6 * ts);
    if (!is_stale && history != nullptr) {
      renderTrendMark(fb, static_cast<uint16_t>(rh_end + TREND_MARK_GAP),
                      static_cast<uint16_t>(cy + (text_h - TREND_MARK_SIZE) / 2), TrendClassifier::humidity(*history));
    }
    if (ventilate_badge && !is_stale) {
      const uint16_t bx = static_cast<uint16_t>(rh_end + 4 * TREND_MARK_GAP + TREND_MARK_SIZE);
      const uint16_t by = static_cast<uint16_t>(cy + (text_h - VENT_BADGE_SIZE) / 2);
      fb.fillRect(bx, by, VENT_BADGE_SIZE, VENT_BADGE_SIZE, true);
      fb.drawText(bx + 2, by + 1, "V", 1, false);
    }
    if (dry_badge && !is_stale) {
      const uint16_t vx = static_cast<uint16_t>(rh_end + 4 * TREND_MARK_GAP + TREND_MARK_SIZE);
      const uint16_t bx = static_cast<uint16_t>(ventilate_badge ? vx + DRY_BADGE_STEP : vx);
      const uint16_t by = static_cast<uint16_t>(cy + (text_h - VENT_BADGE_SIZE) / 2);
      fb.fillRect(bx, by, VENT_BADGE_SIZE, VENT_BADGE_SIZE, true);
      fb.drawText(bx + 2, by + 1, "D", 1, false);
    }
    if (window_badge && !is_stale) {
      const uint16_t vx = static_cast<uint16_t>(rh_end + 4 * TREND_MARK_GAP + TREND_MARK_SIZE);
      const unsigned before = (ventilate_badge ? 1u : 0u) + (dry_badge ? 1u : 0u);
      const uint16_t bx = static_cast<uint16_t>(vx + before * DRY_BADGE_STEP);
      const uint16_t by = static_cast<uint16_t>(cy + (text_h - VENT_BADGE_SIZE) / 2);
      fb.fillRect(bx, by, VENT_BADGE_SIZE, VENT_BADGE_SIZE, true);
      fb.drawText(bx + 2, by + 1, "W", 1, false);
    }
    if (wall_badge && !is_stale) {
      const uint16_t vx = static_cast<uint16_t>(rh_end + 4 * TREND_MARK_GAP + TREND_MARK_SIZE);
      const unsigned before = (ventilate_badge ? 1u : 0u) + (dry_badge ? 1u : 0u) + (window_badge ? 1u : 0u);
      const uint16_t bx = static_cast<uint16_t>(vx + before * DRY_BADGE_STEP);
      const uint16_t by = static_cast<uint16_t>(cy + (text_h - VENT_BADGE_SIZE) / 2);
      fb.fillRect(bx, by, VENT_BADGE_SIZE, VENT_BADGE_SIZE, true);
      fb.drawText(bx + 2, by + 1, "C", 1, false);
    }
    if (weak_badge && !is_stale) {
      const uint16_t vx = static_cast<uint16_t>(rh_end + 4 * TREND_MARK_GAP + TREND_MARK_SIZE);
      const unsigned before =
          (ventilate_badge ? 1u : 0u) + (dry_badge ? 1u : 0u) + (window_badge ? 1u : 0u) + (wall_badge ? 1u : 0u);
      const uint16_t bx = static_cast<uint16_t>(vx + before * DRY_BADGE_STEP);
      const uint16_t by = static_cast<uint16_t>(cy + (text_h - VENT_BADGE_SIZE) / 2);
      fb.fillRect(bx, by, VENT_BADGE_SIZE, VENT_BADGE_SIZE, true);
      fb.drawText(bx + 2, by + 1, "R", 1, false);
    }
    if (drift_badge && !is_stale) {
      const uint16_t vx = static_cast<uint16_t>(rh_end + 4 * TREND_MARK_GAP + TREND_MARK_SIZE);
      const unsigned before = (ventilate_badge ? 1u : 0u) + (dry_badge ? 1u : 0u) + (window_badge ? 1u : 0u) +
                              (wall_badge ? 1u : 0u) + (weak_badge ? 1u : 0u);
      const uint16_t bx = static_cast<uint16_t>(vx + before * DRY_BADGE_STEP);
      const uint16_t by = static_cast<uint16_t>(cy + (text_h - VENT_BADGE_SIZE) / 2);
      fb.fillRect(bx, by, VENT_BADGE_SIZE, VENT_BADGE_SIZE, true);
      fb.drawText(bx + 2, by + 1, "S", 1, false);
    }
    if (heat_badge && !is_stale) {
      const uint16_t vx = static_cast<uint16_t>(rh_end + 4 * TREND_MARK_GAP + TREND_MARK_SIZE);
      const unsigned before = (ventilate_badge ? 1u : 0u) + (dry_badge ? 1u : 0u) + (window_badge ? 1u : 0u) +
                              (wall_badge ? 1u : 0u) + (weak_badge ? 1u : 0u) + (drift_badge ? 1u : 0u);
      const uint16_t bx = static_cast<uint16_t>(vx + before * DRY_BADGE_STEP);
      const uint16_t by = static_cast<uint16_t>(cy + (text_h - VENT_BADGE_SIZE) / 2);
      fb.fillRect(bx, by, VENT_BADGE_SIZE, VENT_BADGE_SIZE, true);
      fb.drawText(bx + 2, by + 1, "H", 1, false);
    }
    cy = static_cast<uint16_t>(cy + text_h + gap);
    if (history != nullptr && history->size() >= 2) {
      fb.drawRect(x + pad, cy, inner_w, spark_h, true);
      Sparkline::render(fb, static_cast<uint16_t>(x + pad + 1), static_cast<uint16_t>(cy + 1),
                        static_cast<uint16_t>(inner_w - 2), static_cast<uint16_t>(spark_h - 2), *history, true);
    }
    cy = static_cast<uint16_t>(cy + spark_h + gap);
    char climate[ClimateMath::LINE_MAX_CHARS + 1];
    ClimateMath::format(climate, sizeof(climate), !is_stale, temp_c, humidity_pct, unit);
    fb.drawText(x + pad, cy, climate, 1, true);
    cy = static_cast<uint16_t>(cy + 7 + gap);
    // Battery text at scale 2 when the longest text ("BAT: 4.20V 100%") fits the tile, else scale 1.
    const uint8_t bs = fitScale("BAT: 4.20V 100%", inner_w, 2);
    drawTileBattery(fb, static_cast<uint16_t>(x + pad), cy, battery_pct, battery_voltage_v, battery_low, bs);
  }

  // Clears the framebuffer and draws the header bar: title, optional clock and battery indicator.
  static void renderHeader(
      St7305Framebuffer& fb,
      const char* title,
      const BatteryReading& battery,
      const char* clock_text,
      bool quiet_hours = false,
      bool ventilate = false,
      bool muted = false) {
    fb.clear(false);

    // Inverted banner: white bar with a double black frame and centered text. The low battery
    // text is 31 glyphs at scale 2 = 370 px wide, inside the 400 px width. The low battery banner
    // wins over the ventilation advice.
    const char* banner = battery.low_battery ? LOW_BATTERY_TEXT : (ventilate ? VENTILATE_TEXT : nullptr);
    if (banner != nullptr) {
      fb.drawRect(0, 0, RLCD_WIDTH, HEADER_HEIGHT, true);
      fb.drawRect(1, 1, RLCD_WIDTH - 2, HEADER_HEIGHT - 2, true);
      const uint16_t text_w = static_cast<uint16_t>(strlen(banner) * 12 - 2);
      fb.drawText(static_cast<uint16_t>((RLCD_WIDTH - text_w) / 2), 7, banner, 2, true);
      return;
    }

    // Header bar (black filled rectangle)
    fb.fillRect(0, 0, RLCD_WIDTH, HEADER_HEIGHT, true);
    fb.drawText(10, 7, title, 2, false);

    // The mute badge takes the place of the quiet hours badge: mute silences all chimes.
    if (muted || quiet_hours) {
      fb.fillRect(HEADER_QUIET_X, HEADER_QUIET_Y, HEADER_QUIET_W, HEADER_QUIET_H, false);
      fb.drawText(HEADER_QUIET_X + 2, HEADER_QUIET_Y + 2, muted ? "M" : "Q", 1, true);
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
      bool quiet_hours = false,
      const DeviceConfig* config = nullptr,
      bool ventilate = false,
      uint16_t ventilate_tiles = 0,
      uint16_t sensor_battery_latch = 0,
      uint16_t dry_tiles = 0,
      uint8_t dashboard_page = 0,
      bool muted = false,
      uint16_t window_tiles = 0,
      uint16_t wall_tiles = 0,
      uint16_t reception_low = 0,
      uint16_t drift_tiles = 0,
      uint16_t heat_tiles = 0) {
    size_t slot_of_tile[MAX_TILES] = {};
    const size_t total_tiles = activeTileCount(switchbot_slots, switchbot_count, config, slot_of_tile);

    if (total_tiles <= 6) {
      renderHeader(fb, "RLCD CLIMATE PANEL", battery, clock_text, quiet_hours, ventilate, muted);
      TileRect rects[MAX_TILES];
      layoutTiles(total_tiles, rects);

      // Tile 0: Onboard SHTC3 and 18650 Battery
      char onboard_title[24];
      tileTitle(onboard_title, sizeof(onboard_title), config, 0);
      renderTile(
          fb, rects[0].x, rects[0].y, rects[0].w, rects[0].h, onboard_title,
          onboard.valid, onboard.temperature_c, onboard.humidity_pct, battery.percentage,
          battery.valid ? battery.voltage_v : -1.0f, false,
          history != nullptr ? &history->slots[ClimateHistory::ONBOARD] : nullptr,
          VentilateLatch::isSet(ventilate_tiles, 0),
          history != nullptr ? &history->temperatures[ClimateHistory::ONBOARD] : nullptr,
          false, tempUnit(config), DryLatch::isSet(dry_tiles, 0), nullptr, "I2C",
          WindowLatch::isSet(window_tiles, 0), WallLatch::isSet(wall_tiles, 0), false,
          DriftLatch::isSet(drift_tiles, 0), HeatLatch::isSet(heat_tiles, 0));

      for (size_t t = 1; t < total_tiles; ++t) {
        const size_t i = slot_of_tile[t];
        char title[24];
        tileTitle(title, sizeof(title), config, i + 1, slotActiveMac(switchbot_slots, switchbot_count, config, i));
        const bool have_slot = (switchbot_slots != nullptr && i < switchbot_count);
        const SwitchBotReading empty_reading{};
        const SwitchBotReading& sb = have_slot ? switchbot_slots[i] : empty_reading;
        const bool tile_stale = sb.isStale(now_ms, staleTimeoutMs(config));
        char age[8] = "";
        if (tile_stale) {
          formatStaleAge(age, sizeof(age), sb, now_ms);
        }
        renderTile(
            fb, rects[t].x, rects[t].y, rects[t].w, rects[t].h, title,
            sb.valid, sb.temperature_c, static_cast<float>(sb.humidity_pct), sb.battery_pct,
            -1.0f, tile_stale,
            history != nullptr ? &history->slots[1 + i] : nullptr,
            VentilateLatch::isSet(ventilate_tiles, 1 + i),
            history != nullptr ? &history->temperatures[1 + i] : nullptr,
            SensorBatteryLatch::isSet(sensor_battery_latch, i),
            tempUnit(config), DryLatch::isSet(dry_tiles, 1 + i),
            age, sb.valid ? formatBadge(sb.format) : nullptr,
            WindowLatch::isSet(window_tiles, 1 + i), WallLatch::isSet(wall_tiles, 1 + i), ((reception_low >> i) & 1u) != 0,
            DriftLatch::isSet(drift_tiles, 1 + i), HeatLatch::isSet(heat_tiles, 1 + i));
      }
    } else {
      // Paged dashboard for >6 tiles (2 pages)
      const uint8_t page = dashboard_page % 2;
      char header_title[32];
      snprintf(header_title, sizeof(header_title), "RLCD CLIMATE %u/2", static_cast<unsigned>(page + 1));
      renderHeader(fb, header_title, battery, clock_text, quiet_hours, ventilate, muted);

      if (page == 0) {
        TileRect rects[MAX_TILES];
        layoutTiles(6, rects);

        // Tile 0: Onboard SHTC3
        char onboard_title[24];
        tileTitle(onboard_title, sizeof(onboard_title), config, 0);
        renderTile(
            fb, rects[0].x, rects[0].y, rects[0].w, rects[0].h, onboard_title,
            onboard.valid, onboard.temperature_c, onboard.humidity_pct, battery.percentage,
            battery.valid ? battery.voltage_v : -1.0f, false,
            history != nullptr ? &history->slots[ClimateHistory::ONBOARD] : nullptr,
            VentilateLatch::isSet(ventilate_tiles, 0),
            history != nullptr ? &history->temperatures[ClimateHistory::ONBOARD] : nullptr,
            false, tempUnit(config), DryLatch::isSet(dry_tiles, 0), nullptr, "I2C",
          WindowLatch::isSet(window_tiles, 0), WallLatch::isSet(wall_tiles, 0), false,
          DriftLatch::isSet(drift_tiles, 0), HeatLatch::isSet(heat_tiles, 0));

        for (size_t t = 1; t < 6; ++t) {
          const size_t i = slot_of_tile[t];
          char title[24];
          tileTitle(title, sizeof(title), config, i + 1, slotActiveMac(switchbot_slots, switchbot_count, config, i));
          const bool have_slot = (switchbot_slots != nullptr && i < switchbot_count);
          const SwitchBotReading empty_reading{};
          const SwitchBotReading& sb = have_slot ? switchbot_slots[i] : empty_reading;
          const bool tile_stale = sb.isStale(now_ms, staleTimeoutMs(config));
          char age[8] = "";
          if (tile_stale) {
            formatStaleAge(age, sizeof(age), sb, now_ms);
          }
          renderTile(
              fb, rects[t].x, rects[t].y, rects[t].w, rects[t].h, title,
              sb.valid, sb.temperature_c, static_cast<float>(sb.humidity_pct), sb.battery_pct,
              -1.0f, tile_stale,
              history != nullptr ? &history->slots[1 + i] : nullptr,
              VentilateLatch::isSet(ventilate_tiles, 1 + i),
              history != nullptr ? &history->temperatures[1 + i] : nullptr,
              SensorBatteryLatch::isSet(sensor_battery_latch, i),
              tempUnit(config), DryLatch::isSet(dry_tiles, 1 + i),
              age, sb.valid ? formatBadge(sb.format) : nullptr,
            WindowLatch::isSet(window_tiles, 1 + i), WallLatch::isSet(wall_tiles, 1 + i), ((reception_low >> i) & 1u) != 0,
            DriftLatch::isSet(drift_tiles, 1 + i), HeatLatch::isSet(heat_tiles, 1 + i));
        }
      } else {
        const size_t page_tiles = total_tiles - 6;
        TileRect rects[MAX_TILES];
        layoutTiles(page_tiles, rects);

        for (size_t t = 0; t < page_tiles; ++t) {
          const size_t i = slot_of_tile[6 + t];
          char title[24];
          tileTitle(title, sizeof(title), config, i + 1, slotActiveMac(switchbot_slots, switchbot_count, config, i));
          const bool have_slot = (switchbot_slots != nullptr && i < switchbot_count);
          const SwitchBotReading empty_reading{};
          const SwitchBotReading& sb = have_slot ? switchbot_slots[i] : empty_reading;
          const bool tile_stale = sb.isStale(now_ms, staleTimeoutMs(config));
          char age[8] = "";
          if (tile_stale) {
            formatStaleAge(age, sizeof(age), sb, now_ms);
          }
          renderTile(
              fb, rects[t].x, rects[t].y, rects[t].w, rects[t].h, title,
              sb.valid, sb.temperature_c, static_cast<float>(sb.humidity_pct), sb.battery_pct,
              -1.0f, tile_stale,
              history != nullptr ? &history->slots[1 + i] : nullptr,
              VentilateLatch::isSet(ventilate_tiles, 1 + i),
              history != nullptr ? &history->temperatures[1 + i] : nullptr,
              SensorBatteryLatch::isSet(sensor_battery_latch, i),
              tempUnit(config), DryLatch::isSet(dry_tiles, 1 + i),
              age, sb.valid ? formatBadge(sb.format) : nullptr,
            WindowLatch::isSet(window_tiles, 1 + i), WallLatch::isSet(wall_tiles, 1 + i), ((reception_low >> i) & 1u) != 0,
            DriftLatch::isSet(drift_tiles, 1 + i), HeatLatch::isSet(heat_tiles, 1 + i));
        }
      }
    }
  }

  // "L10/L90: 60/40 DBA" (calibrated) or "L10/L90: -30/-50 DBFS", "L10/L90: --" without percentiles.
  // Widest: "L10/L90: -96/-96 DBFS" = 21 glyphs.
  static void formatNoisePercentiles(
      char* out, size_t capacity, const NoiseReading& noise, const MicCalibration& cal) {
    if (!noise.valid || !noise.levels_valid) {
      snprintf(out, capacity, "L10/L90: --");
    } else if (cal.calibrated) {
      snprintf(out, capacity, "L10/L90: %d/%d DBA", cal.splDb(noise.l10_rms), cal.splDb(noise.l90_rms));
    } else {
      snprintf(
          out, capacity, "L10/L90: %d/%d DBFS", NoiseClassifier::dbfs(noise.l10_rms),
          NoiseClassifier::dbfs(noise.l90_rms));
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
  // With unit 'F' both temperatures are in Fahrenheit and the highest one ends with 'F':
  // "24H T-148.0 TO 212.0F H100-100" is the longest text (30 glyphs, 360 px), as wide as in Celsius.
  static void formatRecords(char* out, size_t capacity, const ClimateHistory* history, size_t slot,
                            char unit = 'C') {
    char temp[40] = "--";
    char hum[12] = "--";
    if (history != nullptr && slot < ClimateHistory::SLOT_COUNT) {
      int16_t lo = 0;
      int16_t hi = 0;
      if (history->temperatures[slot].range(lo, hi)) {
        if (unit == 'F') {
          snprintf(temp, sizeof(temp), "%.1f TO %.1fF",
                   static_cast<double>(ClimateMath::toDisplay(lo / 10.0f, unit)),
                   static_cast<double>(ClimateMath::toDisplay(hi / 10.0f, unit)));
        } else {
          snprintf(temp, sizeof(temp), "%.1f TO %.1f", lo / 10.0, hi / 10.0);
        }
      }
      if (history->slots[slot].range(lo, hi)) {
        snprintf(hum, sizeof(hum), "%d-%d", (lo + 5) / 10, (hi + 5) / 10);
      }
    }
    snprintf(out, capacity, "24H T%s H%s", temp, hum);
  }

  // Display unit of the config, or Celsius without a config.
  static char tempUnit(const DeviceConfig* config) {
    return config != nullptr && config->temp_unit == 'F' ? 'F' : 'C';
  }

  // Stale timeout of the config, or the default timeout without a config.
  static uint32_t staleTimeoutMs(const DeviceConfig* config) {
    return config != nullptr ? config->staleTimeoutMs() : SENSOR_STALE_TIMEOUT_MS;
  }

  // "COMFORT OK" for a live slot, "COMFORT --" for an empty, stale or out-of-range slot. Computed on
  // each render; nothing is stored.
  static void formatComfort(char* out, size_t capacity, const SwitchBotReading& sb, uint32_t now_ms,
                            uint32_t stale_timeout_ms = SENSOR_STALE_TIMEOUT_MS) {
    const bool future = (now_ms - sb.updated_ms) >= 0xFFFF0000u;
    ComfortZone zone = ComfortZone::INVALID;
    if (sb.valid && (future || !sb.isStale(now_ms, stale_timeout_ms))) {
      zone = ClimateMath::comfort(sb.temperature_c, static_cast<float>(sb.humidity_pct));
    }
    snprintf(out, capacity, "COMFORT %s", ClimateMath::comfortLabel(zone));
  }

  static void drawComfort(
      St7305Framebuffer& fb, uint16_t y, uint16_t y_offset, const SwitchBotReading& sb, uint32_t now_ms,
      uint32_t stale_timeout_ms = SENSOR_STALE_TIMEOUT_MS) {
    char text[16];
    formatComfort(text, sizeof(text), sb, now_ms, stale_timeout_ms);
    const uint16_t x = static_cast<uint16_t>(COMFORT_RIGHT_X - strlen(text) * 6);
    fb.drawText(x, static_cast<uint16_t>(y + y_offset), text, 1, true);
  }

  // "FEELS 31C" (heat index in the display unit, rounded) for a live slot, "FEELS --" for an empty or
  // stale slot or an invalid result. Computed on each render; nothing is stored.
  static void formatFeels(char* out, size_t capacity, const SwitchBotReading& sb, uint32_t now_ms,
                          uint32_t stale_timeout_ms = SENSOR_STALE_TIMEOUT_MS, char unit = 'C') {
    const bool future = (now_ms - sb.updated_ms) >= 0xFFFF0000u;
    float hi = NAN;
    if (sb.valid && (future || !sb.isStale(now_ms, stale_timeout_ms))) {
      hi = ClimateMath::heatIndexC(sb.temperature_c, static_cast<float>(sb.humidity_pct));
    }
    if (!isfinite(hi)) {
      snprintf(out, capacity, "FEELS --");
      return;
    }
    float shown = roundf(ClimateMath::toDisplay(hi, unit));
    if (shown == 0.0f) {
      shown = 0.0f; // no "-0": a value from -0.5 to 0 rounds to zero
    }
    snprintf(out, capacity, "FEELS %.0f%c", static_cast<double>(shown), unit == 'F' ? 'F' : 'C');
  }

  static void drawFeels(
      St7305Framebuffer& fb, uint16_t y, const SwitchBotReading& sb, uint32_t now_ms,
      const DeviceConfig* config) {
    char text[24];
    formatFeels(text, sizeof(text), sb, now_ms, staleTimeoutMs(config), tempUnit(config));
    const uint16_t x = static_cast<uint16_t>(COMFORT_RIGHT_X - strlen(text) * 6);
    fb.drawText(x, static_cast<uint16_t>(y + FEELS_Y_OFFSET), text, 1, true);
  }

  // "AH 8.6G" (absolute humidity in g/m3, 1 decimal) for a live slot, "AH --" for an empty or stale slot
  // or an invalid result. Computed on each render; nothing is stored. Widest: "AH 130.4G" = 9 glyphs.
  static void formatAbsHumidity(char* out, size_t capacity, const SwitchBotReading& sb, uint32_t now_ms,
                                uint32_t stale_timeout_ms = SENSOR_STALE_TIMEOUT_MS) {
    const bool future = (now_ms - sb.updated_ms) >= 0xFFFF0000u;
    float ah = -1.0f;
    if (sb.valid && (future || !sb.isStale(now_ms, stale_timeout_ms))) {
      ah = ClimateMath::absoluteHumidityGm3(sb.temperature_c, static_cast<float>(sb.humidity_pct));
    }
    if (!isfinite(ah) || ah < 0.0f) {
      snprintf(out, capacity, "AH --");
      return;
    }
    snprintf(out, capacity, "AH %.1fG", static_cast<double>(ah));
  }

  // Draws the AH text unless it would come closer than AH_GAP_PX to the record text of
  // record_chars scale-2 glyphs (0 for a slot without a record line).
  static void drawAbsHumidity(
      St7305Framebuffer& fb, uint16_t y, const SwitchBotReading& sb, uint32_t now_ms,
      const DeviceConfig* config, size_t record_chars) {
    char text[24];
    formatAbsHumidity(text, sizeof(text), sb, now_ms, staleTimeoutMs(config));
    const uint16_t x = static_cast<uint16_t>(COMFORT_RIGHT_X - strlen(text) * 6);
    if (record_chars > 0 && x < 10 + record_chars * 12 + AH_GAP_PX) {
      return;
    }
    fb.drawText(x, static_cast<uint16_t>(y + ABS_HUMIDITY_Y_OFFSET), text, 1, true);
  }

  // "WALL 73%" (humidity of the room air at the temperature of the outer wall, whole percent) for a live
  // indoor slot with the key wall_frsi_pct set and a fresh outdoor reading. "WALL --" for an empty or stale slot,
  // the outdoor slot itself, a stale or empty outdoor slot or a NaN result. Computed on each render; nothing is stored.
  static void formatWall(char* out, size_t capacity, const SwitchBotReading& sb, const SwitchBotReading* outdoor,
                         bool is_outdoor_tile, uint32_t now_ms, uint32_t stale_timeout_ms, uint8_t frsi_pct) {
    const bool future = (now_ms - sb.updated_ms) >= 0xFFFF0000u;
    float rh = NAN;
    if (!is_outdoor_tile && outdoor != nullptr && sb.valid && (future || !sb.isStale(now_ms, stale_timeout_ms)) &&
        outdoor->valid) {
      const bool out_future = (now_ms - outdoor->updated_ms) >= 0xFFFF0000u;
      if (out_future || !outdoor->isStale(now_ms, stale_timeout_ms)) {
        rh = ClimateMath::wallSurfaceRh(sb.temperature_c, static_cast<float>(sb.humidity_pct),
                                        outdoor->temperature_c, static_cast<float>(frsi_pct) / 100.0f);
      }
    }
    if (!isfinite(rh)) {
      snprintf(out, capacity, "WALL --");
      return;
    }
    snprintf(out, capacity, "WALL %.0f%%", static_cast<double>(roundf(rh)));
  }

  // Draws the WALL text when wall_frsi_pct is not 0, unless it would come closer than WALL_GAP_PX to the
  // MAC line of left_chars scale-2 glyphs (0 for none).
  static void drawWall(
      St7305Framebuffer& fb, uint16_t y, const SwitchBotReading* slots, size_t slot_count, size_t slot,
      uint32_t now_ms, const DeviceConfig* config, size_t left_chars) {
    if (config == nullptr || config->wall_frsi_pct == 0) {
      return;
    }
    const size_t out_idx = config->outdoor_slot;
    const bool has_out = out_idx >= 1 && slots != nullptr && out_idx <= slot_count;
    char text[16];
    formatWall(text, sizeof(text), slot < slot_count && slots != nullptr ? slots[slot] : SwitchBotReading{},
               has_out ? &slots[out_idx - 1] : nullptr, has_out && out_idx - 1 == slot, now_ms,
               staleTimeoutMs(config), config->wall_frsi_pct);
    const uint16_t x = static_cast<uint16_t>(COMFORT_RIGHT_X - strlen(text) * 6);
    if (left_chars > 0 && x < 10 + left_chars * 12 + WALL_GAP_PX) {
      return;
    }
    fb.drawText(x, static_cast<uint16_t>(y + WALL_Y_OFFSET), text, 1, true);
  }

  // "MOLD 5H" (hourly samples above the mold limit in the 24 h history, one sample per hour) for a live slot with at least 2 samples,
  // "MOLD --" for an empty or stale slot or fewer samples. Computed on each render; nothing is stored.
  // Widest: "MOLD 24H" = 8 glyphs, 48 px.
  static void formatMold(char* out, size_t capacity, const SwitchBotReading& sb, uint32_t now_ms,
                         uint32_t stale_timeout_ms, const HumidityHistory* hist, uint8_t limit_rh) {
    const bool future = (now_ms - sb.updated_ms) >= 0xFFFF0000u;
    if (!sb.valid || (!future && sb.isStale(now_ms, stale_timeout_ms)) || hist == nullptr ||
        hist->size() < HumidityHistory::MIN_RANGE_SAMPLES) {
      snprintf(out, capacity, "MOLD --");
      return;
    }
    snprintf(out, capacity, "MOLD %uH",
             static_cast<unsigned>(hist->countAbove(static_cast<uint16_t>(limit_rh * 10u))));
  }

  // Draws the MOLD text and returns its width in px.
  static uint16_t drawMold(
      St7305Framebuffer& fb, uint16_t y, const SwitchBotReading& sb, uint32_t now_ms,
      const DeviceConfig* config, const ClimateHistory* history, size_t slot) {
    char text[12];
    formatMold(
        text, sizeof(text), sb, now_ms, staleTimeoutMs(config),
        history != nullptr ? &history->slots[1 + slot] : nullptr,
        config != nullptr ? config->mold_alert_limit_rh : DeviceConfig::DEFAULT_MOLD_ALERT_LIMIT_RH);
    const uint16_t w = static_cast<uint16_t>(strlen(text) * 6);
    fb.drawText(static_cast<uint16_t>(COMFORT_RIGHT_X - w), static_cast<uint16_t>(y + MOLD_Y_OFFSET), text, 1, true);
    return w;
  }

  // "DRY 5H" (hourly samples at or below the dry limit in the 24 h history, one sample per hour) for a live slot
  // with at least 2 samples, "DRY --" for an empty or stale slot or fewer samples. Empty text for limit_rh 0.
  // Computed on each render; nothing is stored. Widest: "DRY 24H" = 7 glyphs, 42 px.
  static void formatDry(char* out, size_t capacity, const SwitchBotReading& sb, uint32_t now_ms,
                        uint32_t stale_timeout_ms, const HumidityHistory* hist, uint8_t limit_rh) {
    if (limit_rh == 0) {
      snprintf(out, capacity, "%s", "");
      return;
    }
    const bool future = (now_ms - sb.updated_ms) >= 0xFFFF0000u;
    if (!sb.valid || (!future && sb.isStale(now_ms, stale_timeout_ms)) || hist == nullptr ||
        hist->size() < HumidityHistory::MIN_RANGE_SAMPLES) {
      snprintf(out, capacity, "DRY --");
      return;
    }
    snprintf(out, capacity, "DRY %uH",
             static_cast<unsigned>(hist->countAtOrBelow(static_cast<uint16_t>(limit_rh * 10u))));
  }

  // Draws the DRY text left of the MOLD text (mold_w px wide) unless dry_alert_limit_rh is 0 or it would come
  // closer than DRY_GAP_PX to the MAC line of left_chars scale-2 glyphs (0 for none). Returns the width in px taken
  // right of the next text: mold_w, or mold_w + DRY_MOLD_GAP_PX + the DRY width when the DRY text is drawn.
  static uint16_t drawDry(
      St7305Framebuffer& fb, uint16_t y, const SwitchBotReading& sb, uint32_t now_ms,
      const DeviceConfig* config, const ClimateHistory* history, size_t slot, uint16_t mold_w, size_t left_chars) {
    if (config == nullptr) {
      return mold_w;
    }
    char text[12];
    formatDry(text, sizeof(text), sb, now_ms, staleTimeoutMs(config),
              history != nullptr ? &history->slots[1 + slot] : nullptr, config->dry_alert_limit_rh);
    const size_t len = strlen(text);
    if (len == 0) {
      return mold_w;
    }
    const uint16_t x = static_cast<uint16_t>(COMFORT_RIGHT_X - mold_w - DRY_MOLD_GAP_PX - len * 6);
    if (left_chars > 0 && x < 10 + left_chars * 12 + DRY_GAP_PX) {
      return mold_w;
    }
    fb.drawText(x, static_cast<uint16_t>(y + MOLD_Y_OFFSET), text, 1, true);
    return static_cast<uint16_t>(mold_w + DRY_MOLD_GAP_PX + len * 6);
  }

  // "HEAT 5H" (hourly pairs of the 24 h histories with a feels-like value at or above heat_alert_limit_c) for a live slot,
  // "HEAT --" for an empty or stale slot or when ClimateHistory::heatHours is unknown. Empty text for limit_c 0.
  // `sensor` is the history index (1 + slot). Computed on each render; nothing is stored. Widest: "HEAT 24H" = 8 glyphs, 48 px.
  static void formatHeat(char* out, size_t capacity, const SwitchBotReading& sb, uint32_t now_ms,
                         uint32_t stale_timeout_ms, const ClimateHistory* history, size_t sensor, uint8_t limit_c) {
    if (limit_c == 0) {
      snprintf(out, capacity, "%s", "");
      return;
    }
    const bool future = (now_ms - sb.updated_ms) >= 0xFFFF0000u;
    const int hours = history != nullptr ? history->heatHours(sensor, limit_c) : -1;
    if (!sb.valid || (!future && sb.isStale(now_ms, stale_timeout_ms)) || hours < 0) {
      snprintf(out, capacity, "HEAT --");
      return;
    }
    snprintf(out, capacity, "HEAT %uH", static_cast<unsigned>(hours));
  }

  // Draws the HEAT text DRY_MOLD_GAP_PX left of the DRY text (or of the MOLD text when no DRY text is drawn), unless
  // heat_alert_limit_c is 0 or it would come closer than DRY_GAP_PX to the MAC line of left_chars scale-2 glyphs (0 for
  // none). `taken_w` is the width of the texts to its right, the return value of drawDry.
  static void drawHeat(
      St7305Framebuffer& fb, uint16_t y, const SwitchBotReading& sb, uint32_t now_ms,
      const DeviceConfig* config, const ClimateHistory* history, size_t slot, uint16_t taken_w, size_t left_chars) {
    if (config == nullptr) {
      return;
    }
    char text[20]; // "HEAT " + 10 digits + "H" + NUL for the full int range
    formatHeat(text, sizeof(text), sb, now_ms, staleTimeoutMs(config), history, 1 + slot, config->heat_alert_limit_c);
    const size_t len = strlen(text);
    if (len == 0) {
      return;
    }
    const uint16_t x = static_cast<uint16_t>(COMFORT_RIGHT_X - taken_w - DRY_MOLD_GAP_PX - len * 6);
    if (left_chars > 0 && x < 10 + left_chars * 12 + DRY_GAP_PX) {
      return;
    }
    fb.drawText(x, static_cast<uint16_t>(y + MOLD_Y_OFFSET), text, 1, true);
  }

  // "RX 92%" (share of the last counted BLE scans with a packet of the slot, rounded) for a slot with at least
  // RX_MIN_SCANS counted scans, "RX --" for an empty slot, fewer scans or no history. Computed on each render;
  // nothing is stored. Widest: "RX 100%" = 7 glyphs.
  static void formatReception(char* out, size_t capacity, const ReceptionHistory* reception, size_t slot) {
    const unsigned scans = reception != nullptr ? reception->scans(slot) : 0;
    if (scans < RX_MIN_SCANS) {
      snprintf(out, capacity, "RX --");
      return;
    }
    const unsigned got = reception->received(slot);
    snprintf(out, capacity, "RX %u%%", (got * 100u + scans / 2u) / scans);
  }

  // True when the slot has a rate and it is below 50 % (exact count compare, no rounding).
  static bool receptionLow(const ReceptionHistory* reception, size_t slot) {
    return reception != nullptr && reception->scans(slot) >= RX_MIN_SCANS &&
           static_cast<unsigned>(reception->received(slot)) * 2u < reception->scans(slot);
  }

  // Draws the RX text unless it would come closer than RX_GAP_PX to the RSSI line of left_chars scale-2 glyphs.
  // A rate below 50 % is drawn inverted.
  static void drawReception(
      St7305Framebuffer& fb, uint16_t y, const ReceptionHistory* reception, size_t slot, size_t left_chars) {
    char text[12];
    formatReception(text, sizeof(text), reception, slot);
    const uint16_t w = static_cast<uint16_t>(strlen(text) * 6);
    const uint16_t x = static_cast<uint16_t>(COMFORT_RIGHT_X - w);
    const bool low = receptionLow(reception, slot);
    const uint16_t pad = low ? 2 : 0; // the inverted box reaches 2 px left of the text
    if (left_chars > 0 && x < 10 + left_chars * 12 + RX_GAP_PX + pad) {
      return;
    }
    if (low) {
      fb.fillRect(static_cast<uint16_t>(x - 2), static_cast<uint16_t>(y + RX_Y_OFFSET - 1),
                  static_cast<uint16_t>(w + 3), 9, true);
      fb.drawText(x, static_cast<uint16_t>(y + RX_Y_OFFSET), text, 1, false);
    } else {
      fb.drawText(x, static_cast<uint16_t>(y + RX_Y_OFFSET), text, 1, true);
    }
  }

  // "MAC ..:EE:FF" style line: the last three MAC bytes. The full address stays in config.ini.
  static void formatMac(char* out, size_t capacity, const uint8_t* mac) {
    snprintf(out, capacity, "MAC ..:%02X:%02X:%02X", mac[3], mac[4], mac[5]);
  }

  // "RSSI -NN DBM" for a live slot, "RSSI --" for an empty or stale slot.
  static void formatRssi(char* out, size_t capacity, const SwitchBotReading& sb, uint32_t now_ms,
                         uint32_t stale_timeout_ms = SENSOR_STALE_TIMEOUT_MS) {
    // A packet stamped shortly after now_ms counts as fresh, like the age display.
    const bool future = (now_ms - sb.updated_ms) >= 0xFFFF0000u;
    if (!sb.valid || (!future && sb.isStale(now_ms, stale_timeout_ms))) {
      snprintf(out, capacity, "RSSI --");
      return;
    }
    snprintf(out, capacity, "RSSI %d DBM", static_cast<int>(sb.rssi));
  }

  // Data age of a stale slot for the tile badge: whole hours since the last packet, "<N>H" from 1H
  // (30 min gives "1H") to 99H, ">99H" above. An empty string for a slot without a packet (never
  // stamped) or a packet stamped up to 65 s after now_ms. A larger future stamp (a wrapped 32-bit
  // clock) gives ">99H", like the stale rule of isStale and formatRssi. The age is valid up to 49 days (32-bit milliseconds). The text changes once an hour.
  static void formatStaleAge(char* out, size_t capacity, const SwitchBotReading& sb, uint32_t now_ms) {
    const uint32_t age_ms = now_ms - sb.updated_ms;
    if (!sb.valid || sb.updated_ms == 0 || age_ms >= 0xFFFF0000u) {
      snprintf(out, capacity, "%s", "");
      return;
    }
    const uint32_t hours = age_ms / 3600000u;
    if (hours > 99) {
      snprintf(out, capacity, ">99H");
    } else {
      snprintf(out, capacity, "%uH", static_cast<unsigned>(hours < 1 ? 1 : hours));
    }
  }

  // Total pages needed for the active sensors in stats view (3 slots per page).
  static uint8_t statsTotalPages(const SwitchBotReading* slots, size_t slot_count, const DeviceConfig* config) {
    size_t last_active = 0;
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (slotActive(slots, slot_count, config, i)) {
        last_active = i + 1;
      }
    }
    if (last_active == 0) return 1;
    return static_cast<uint8_t>((last_active + STATS_SLOT_COUNT - 1) / STATS_SLOT_COUNT);
  }

  // True when the stats view has more than one page.
  static bool statsHasSecondPage(const SwitchBotReading* slots, size_t slot_count, const DeviceConfig* config) {
    return statsTotalPages(slots, slot_count, config) > 1;
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
      const ClimateHistory* history = nullptr,
      const DeviceConfig* config = nullptr,
      uint8_t page = 0,
      bool muted = false,
      const ReceptionHistory* reception = nullptr) {
    const uint8_t total_pages = statsTotalPages(switchbot_slots, switchbot_count, config);
    const uint8_t shown_page = (total_pages > 1 && page < total_pages) ? page : 0;
    char header_title[32];
    if (total_pages > 1) {
      snprintf(header_title, sizeof(header_title), "SENSOR STATS %u/%u",
               static_cast<unsigned>(shown_page + 1), static_cast<unsigned>(total_pages));
    } else {
      snprintf(header_title, sizeof(header_title), "SENSOR STATS");
    }
    renderHeader(fb, header_title, battery, clock_text, quiet_hours, false, muted);

    const size_t first_slot = shown_page * STATS_SLOT_COUNT;
    const size_t block_count = (first_slot + STATS_SLOT_COUNT <= MAX_SWITCHBOT_SENSORS)
                                   ? STATS_SLOT_COUNT
                                   : (MAX_SWITCHBOT_SENSORS - first_slot);
    for (size_t k = 0; k < block_count; ++k) {
      const size_t i = first_slot + k;
      const uint16_t y = static_cast<uint16_t>(34 + k * SENSOR_BLOCK_PITCH);
      const bool have_slot = (switchbot_slots != nullptr && i < switchbot_count);
      const SwitchBotReading empty_reading{};
      const SwitchBotReading& sb = have_slot ? switchbot_slots[i] : empty_reading;

      char label[DeviceConfig::NAME_MAX_CHARS + 1];
      statsLabel(label, sizeof(label), config, i, slotActiveMac(switchbot_slots, switchbot_count, config, i));
      char line[40];
      if (config != nullptr && !config->isSlotEnabled(i)) {
        snprintf(line, sizeof(line), "%s DISABLED", label);
        drawLine(fb, 10, y, line);
        continue;
      }
      if (!sb.valid) {
        snprintf(line, sizeof(line), "%s NO SIGNAL", label);
        drawLine(fb, 10, y, line);
        drawComfort(fb, y, COMFORT_EMPTY_Y_OFFSET, sb, now_ms, staleTimeoutMs(config)); // the empty slot has no packet line
        drawFeels(fb, y, sb, now_ms, config);
        const uint16_t mold_w = drawMold(fb, y, sb, now_ms, config, history, i);
        drawAbsHumidity(fb, y, sb, now_ms, config, 0);
        size_t mac_chars = 0;
        if (config != nullptr && config->hasSlotMac(i)) {
          formatMac(line, sizeof(line), config->slot_mac[i]);
          drawLine(fb, 10, static_cast<uint16_t>(y + 18), line);
          mac_chars = strlen(line);
        }
        const uint16_t taken_w = drawDry(fb, y, sb, now_ms, config, history, i, mold_w, mac_chars);
        drawHeat(fb, y, sb, now_ms, config, history, i, taken_w, mac_chars);
        drawWall(fb, y, switchbot_slots, switchbot_count, i, now_ms, config, mac_chars);
        continue;
      }

      char rssi[24];
      formatRssi(rssi, sizeof(rssi), sb, now_ms, staleTimeoutMs(config));
      snprintf(line, sizeof(line), "%s %s", label, rssi);
      drawLine(fb, 10, y, line);
      drawReception(fb, y, reception, i, strlen(line));

      char mac[20];
      formatMac(mac, sizeof(mac), sb.mac);
      char mac_src[32];
      snprintf(mac_src, sizeof(mac_src), "%s [%s]", mac, formatBadge(sb.format));
      drawLine(fb, 10, static_cast<uint16_t>(y + 18), mac_src);
      drawWall(fb, y, switchbot_slots, switchbot_count, i, now_ms, config, strlen(mac_src));

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
      drawComfort(fb, y, COMFORT_Y_OFFSET, sb, now_ms, staleTimeoutMs(config));
      drawFeels(fb, y, sb, now_ms, config);
      const uint16_t mold_w = drawMold(fb, y, sb, now_ms, config, history, i);
      const uint16_t taken_w = drawDry(fb, y, sb, now_ms, config, history, i, mold_w, strlen(mac_src));
      drawHeat(fb, y, sb, now_ms, config, history, i, taken_w, strlen(mac_src));

      formatRecords(line, sizeof(line), history, 1 + i, tempUnit(config));
      const bool record_drawn = drawLine(fb, 10, static_cast<uint16_t>(y + 54), line);
      drawAbsHumidity(fb, y, sb, now_ms, config, record_drawn ? strlen(line) : 0);
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
    renderHeader(fb, "SYSTEM DIAGNOSTICS", battery, clock_text, info.quiet_hours, false, info.muted);

    char line[40];
    const char* power = !battery.valid ? "NONE" : (battery.usb_powered ? "USB" : "BATTERY");
    snprintf(line, sizeof(line), "POWER: %s", power);
    drawLine(fb, 10, 44, line);

    snprintf(line, sizeof(line), "SHTC3 ERR: %lu", static_cast<unsigned long>(info.shtc3.total()));
    if (info.shtc3.alert()) {
      fb.fillRect(SHTC3_X - 2, SHTC3_Y - 2, static_cast<uint16_t>(strlen(line) * 12 + 2), 18, true);
      fb.drawText(SHTC3_X, SHTC3_Y, line, 2, false);
    } else {
      fb.drawText(SHTC3_X, SHTC3_Y, line, 2, true);
    }

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

    // Cycle counter at y = 64, between the power line (ends y = 58) and the battery line (y = 84).
    // Widest: "CYCLES: 9999.9" = 14 glyphs = 168 px.
    CycleTracker::describe(info.cycle_tenths, line, sizeof(line));
    drawLine(fb, 10, 64, line);

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
    // A card fault replaces the text: "SD: MOUNT ERR" and "SD: WRITE ERR" are 13 glyphs = 156 px.
    const char* sd_fault = SdStatusCodec::faultText(info.sd_status);
    if (sd_fault != nullptr) {
      snprintf(line, sizeof(line), "%s", sd_fault);
    } else if (info.sd_free_mb >= 0) {
      snprintf(line, sizeof(line), "SD FREE: %ld MB", static_cast<long>(info.sd_free_mb));
    } else {
      snprintf(line, sizeof(line), "SD FREE: --");
    }
    drawLine(fb, 10, 144, line);

    // Percentile levels of the window in small text (6 px per glyph, at most 126 px). "SD FREE: 9999999 MB"
    // ends at x = 238, so the text starts right of it.
    formatNoisePercentiles(line, sizeof(line), info.noise, info.mic_cal);
    fb.drawText(PERCENTILE_X, PERCENTILE_Y, line, 1, true);

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

    // Clock sync at scale 1 right of the uptime line; the text changes once each hour.
    NtpSchedule::describe(info.ntp, info.ntp_credentials, info.now_ms, line, sizeof(line));
    fb.drawText(NTP_X, NTP_Y, line, 1, true);

    // RTC drift at y = 184, between the uptime line (ends y = 178) and the noise line (y = 204).
    // Widest: "RTC DRIFT: -86400 S (-99999 PPM)" = 32 glyphs = 384 px.
    RtcDrift::describe(info.rtc_drift, line, sizeof(line));
    drawLine(fb, 10, 184, line);

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

    // "CONFIG: SD (255 ERR)" is 20 glyphs = 240 px, from x = 10 to x = 250: it ends right of
    // EVENTS_X, so the events text shortens to "EV: N" at EVENTS_ERR_X while an error shows.
    const bool config_err = info.config_from_sd && info.config_rejected > 0;
    if (config_err) {
      snprintf(line, sizeof(line), "CONFIG: SD (%u ERR)", static_cast<unsigned>(info.config_rejected));
    } else {
      snprintf(line, sizeof(line), "CONFIG: %s", info.config_from_sd ? "SD" : "DEFAULT");
    }
    drawLine(fb, 10, 276, line);

    // Loud transients of the last noise window. Widest: "EVENTS: 65535" = 13 glyphs = 156 px,
    // from x = 232 to x = 388. "CONFIG: DEFAULT" ends at x = 190. A failed window shows "--".
    const char* events_label = config_err ? "EV" : "EVENTS";
    if (info.noise.valid) {
      snprintf(line, sizeof(line), "%s: %u", events_label, static_cast<unsigned>(info.noise.events));
    } else {
      snprintf(line, sizeof(line), "%s: --", events_label);
    }
    drawLine(fb, config_err ? EVENTS_ERR_X : EVENTS_X, EVENTS_Y, line);
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

  // Frame and label (with the minimum and maximum) of one curve box of the chart view. The caller
  // draws the curve, or "NO DATA" for a history with fewer than 2 samples.
  static void drawChartFrame(
      St7305Framebuffer& fb, uint16_t label_y, uint16_t box_y, const char* label, bool has_range,
      const char* range_text) {
    char text[64];
    if (has_range) {
      snprintf(text, sizeof(text), "%s %s", label, range_text);
    } else {
      snprintf(text, sizeof(text), "%s", label);
    }
    fb.drawText(CHART_X, label_y, text, 1, true);
    fb.drawRect(CHART_X - 1, box_y - 1, CHART_W + 2, CHART_H + 2, true);
  }

  static void renderClimateChart(
      St7305Framebuffer& fb,
      const BatteryReading& battery,
      const SystemInfo& info,
      const char* clock_text,
      const ClimateHistory* history,
      const DeviceConfig* config,
      const SwitchBotReading* switchbot_slots = nullptr,
      size_t switchbot_count = 0) {
    renderHeader(fb, "CLIMATE CHART", battery, clock_text, info.quiet_hours, false, info.muted);
    const size_t sensor = info.chart_sensor < ClimateHistory::SLOT_COUNT ? info.chart_sensor : 0;
    char title[40];
    tileTitle(title, sizeof(title), config, sensor,
              sensor > 0 ? slotActiveMac(switchbot_slots, switchbot_count, config, sensor - 1) : nullptr);
    fb.drawText(CHART_X, CHART_NAME_Y, title, 2, true);

    const TemperatureHistory empty_temperature;
    const HumidityHistory empty_humidity;
    const TemperatureHistory& temperature =
        history != nullptr ? history->temperatures[sensor] : empty_temperature;
    const HumidityHistory& humidity = history != nullptr ? history->slots[sensor] : empty_humidity;
    const char unit = config != nullptr ? config->temp_unit : 'C';

    int16_t lo = 0;
    int16_t hi = 0;
    char range_text[40];
    const bool temp_ok = temperature.range(lo, hi);
    if (temp_ok) {
      snprintf(range_text, sizeof(range_text), "MIN %.1f MAX %.1f %c",
               static_cast<double>(ClimateMath::toDisplay(lo / 10.0f, unit)),
               static_cast<double>(ClimateMath::toDisplay(hi / 10.0f, unit)), unit == 'F' ? 'F' : 'C');
    }
    drawChartFrame(fb, CHART_TEMP_LABEL_Y, CHART_TEMP_Y, "TEMPERATURE", temp_ok, range_text);
    if (temp_ok) {
      Sparkline::renderTemperature(fb, CHART_X, CHART_TEMP_Y, CHART_W, CHART_H, temperature);
    } else {
      drawLine(fb, CHART_X + 120, CHART_TEMP_Y + 38, "NO DATA");
    }

    const bool rh_ok = humidity.range(lo, hi);
    if (rh_ok) {
      snprintf(range_text, sizeof(range_text), "MIN %.1f MAX %.1f %%",
               static_cast<double>(lo / 10.0f), static_cast<double>(hi / 10.0f));
    }
    drawChartFrame(fb, CHART_HUMIDITY_LABEL_Y, CHART_HUMIDITY_Y, "HUMIDITY", rh_ok, range_text);
    if (rh_ok) {
      Sparkline::render(fb, CHART_X, CHART_HUMIDITY_Y, CHART_W, CHART_H, humidity);
    } else {
      drawLine(fb, CHART_X + 120, CHART_HUMIDITY_Y + 38, "NO DATA");
    }
    fb.drawText(CHART_X, CHART_FOOTER_Y, "LAST 24 H  KEY: NEXT SENSOR  HOLD: VIEW", 1, true);
  }

  // Weekday text for the bars: two letters, from the RTC weekday (0 = Sunday).
  static const char* weekdayText(uint8_t weekday) {
    static const char* const NAMES[7] = {"SU", "MO", "TU", "WE", "TH", "FR", "SA"};
    return weekday < 7 ? NAMES[weekday] : "--";
  }

  static void drawWeekLabels(St7305Framebuffer& fb, uint16_t box_y, const WeekHistory& week) {
    for (size_t k = 0; k < WeekHistory::DAYS; ++k) {
      const size_t col = WeekHistory::DAYS - 1 - k;
      const uint16_t x = static_cast<uint16_t>(
          CHART_X + Sparkline::weekBarX(col, CHART_W) + (Sparkline::WEEK_BAR_W - 11) / 2);
      fb.drawText(x, static_cast<uint16_t>(box_y + WEEK_LABEL_OFFSET_Y), weekdayText(week.weekdayOf(k)), 1, true);
    }
  }

  // Lowest minimum or highest maximum over the valid days of one quantity; only for a week with a valid day.
  static int16_t weekExtreme(const WeekHistory& w, size_t sensor, bool humidity, bool want_max) {
    int16_t v = 0;
    bool found = false;
    for (size_t k = 0; k < WeekHistory::DAYS; ++k) {
      if (!(humidity ? w.hasHumidity(sensor, k) : w.hasTemperature(sensor, k))) {
        continue;
      }
      const int16_t d = want_max ? (humidity ? w.humidityMax(sensor, k) : w.temperatureMax(sensor, k))
                                 : (humidity ? w.humidityMin(sensor, k) : w.temperatureMin(sensor, k));
      v = (!found || (want_max ? d > v : d < v)) ? d : v;
      found = true;
    }
    return v;
  }

  // Sum of the mold hours over the days with a value: "MOLD <N>H" (0 to 168); empty text without such a day.
  static void formatWeekMold(const WeekHistory& week, size_t sensor, char* out, size_t capacity) {
    if (out == nullptr || capacity == 0) {
      return;
    }
    out[0] = '\0';
    uint16_t sum = 0;
    bool found = false;
    for (size_t k = 0; k < WeekHistory::DAYS; ++k) {
      if (week.hasMoldHours(sensor, k)) {
        sum = static_cast<uint16_t>(sum + week.moldHours(sensor, k));
        found = true;
      }
    }
    if (found) {
      snprintf(out, capacity, "MOLD %uH", static_cast<unsigned>(sum));
    }
  }

  // Sum of the dry hours over the days with a value: "DRY <N>H" (0 to 168); empty text without such a day.
  static void formatWeekDry(const WeekHistory& week, size_t sensor, char* out, size_t capacity) {
    if (out == nullptr || capacity == 0) {
      return;
    }
    out[0] = '\0';
    uint16_t sum = 0;
    bool found = false;
    for (size_t k = 0; k < WeekHistory::DAYS; ++k) {
      if (week.hasDryHours(sensor, k)) {
        sum = static_cast<uint16_t>(sum + week.dryHours(sensor, k));
        found = true;
      }
    }
    if (found) {
      snprintf(out, capacity, "DRY %uH", static_cast<unsigned>(sum));
    }
  }

  // Seven bars (daily minimum to maximum) of the temperature and of the humidity of one sensor. It
  // reads only the RTC copy. A day without samples leaves a gap; with no valid day the box shows NO DATA.
  static void renderWeekChart(
      St7305Framebuffer& fb,
      const BatteryReading& battery,
      const SystemInfo& info,
      const char* clock_text,
      const WeekHistory* week,
      const DeviceConfig* config,
      const SwitchBotReading* switchbot_slots = nullptr,
      size_t switchbot_count = 0) {
    renderHeader(fb, "WEEK CHART", battery, clock_text, info.quiet_hours, false, info.muted);
    const size_t sensor = info.chart_sensor < WeekHistory::SENSORS ? info.chart_sensor : 0;
    char title[40];
    tileTitle(title, sizeof(title), config, sensor,
              sensor > 0 ? slotActiveMac(switchbot_slots, switchbot_count, config, sensor - 1) : nullptr);
    fb.drawText(CHART_X, CHART_NAME_Y, title, 2, true);

    const WeekHistory empty;
    const WeekHistory& w = week != nullptr ? *week : empty;
    const char unit = config != nullptr ? config->temp_unit : 'C';

    for (int quantity = 0; quantity < 2; ++quantity) {
      const bool humidity = quantity == 1;
      const uint16_t box_y = humidity ? CHART_HUMIDITY_Y : CHART_TEMP_Y;
      const uint16_t label_y = humidity ? CHART_HUMIDITY_LABEL_Y : CHART_TEMP_LABEL_Y;
      Sparkline::Scale scale;
      const bool ok = Sparkline::computeWeekScale(w, sensor, humidity, scale);
      char range_text[48] = "";
      if (ok) {
        const float lo = weekExtreme(w, sensor, humidity, false) / 10.0f;
        const float hi = weekExtreme(w, sensor, humidity, true) / 10.0f;
        if (humidity) {
          snprintf(range_text, sizeof(range_text), "MIN %.1f MAX %.1f %%", static_cast<double>(lo),
                   static_cast<double>(hi));
          char mold[16];
          char dry[16];
          formatWeekMold(w, sensor, mold, sizeof(mold));
          formatWeekDry(w, sensor, dry, sizeof(dry));
          // Defensive bound: range_text holds at most 42 bytes (with drawChartFrame prefix 51 glyphs, inside CHART_W).
          size_t used = strlen(range_text);
          if (mold[0] != '\0' && used + 2 + strlen(mold) < sizeof(range_text)) {
            snprintf(range_text + used, sizeof(range_text) - used, "  %s", mold);
            used = strlen(range_text);
          }
          if (dry[0] != '\0' && used + 2 + strlen(dry) < sizeof(range_text)) {
            snprintf(range_text + used, sizeof(range_text) - used, "  %s", dry);
          }
        } else {
          snprintf(range_text, sizeof(range_text), "MIN %.1f MAX %.1f %c",
                   static_cast<double>(ClimateMath::toDisplay(lo, unit)),
                   static_cast<double>(ClimateMath::toDisplay(hi, unit)), unit == 'F' ? 'F' : 'C');
        }
      }
      drawChartFrame(fb, label_y, box_y, humidity ? "HUMIDITY" : "TEMPERATURE", ok, range_text);
      if (ok) {
        Sparkline::renderWeek(fb, CHART_X, box_y, CHART_W, CHART_H, w, sensor, humidity);
      } else {
        drawLine(fb, CHART_X + 120, box_y + 38, "NO DATA");
      }
    }
    if (w.hasDate()) {
      drawWeekLabels(fb, CHART_HUMIDITY_Y, w);
    }
    fb.drawText(CHART_X, CHART_FOOTER_Y, "LAST 7 D  KEY: NEXT SENSOR  HOLD: VIEW", 1, true);
  }

  static void renderSpectrum(
      St7305Framebuffer& fb,
      const BatteryReading& battery,
      const SystemInfo& info,
      const char* clock_text) {
    renderHeader(fb, "AUDIO SPECTRUM", battery, clock_text, info.quiet_hours, false, info.muted);

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

  // Identify screen of the console command IDENTIFY: inverted (white on black) "RLCD <XXXX>" at scale 6
  // (318 px), the firmware version and the transport ("USB" or "BLE") at scale 3. No state, no clock.
  static void renderIdentify(St7305Framebuffer& fb, uint16_t mac_tail, const char* version, const char* transport) {
    fb.clear(true);
    char title[16];
    snprintf(title, sizeof(title), "RLCD %04X", static_cast<unsigned>(mac_tail));
    drawCenteredText(fb, 70, title, 6);
    char info[32];
    snprintf(info, sizeof(info), "FW %s", version);
    drawCenteredText(fb, 150, info, 3);
    drawCenteredText(fb, 190, transport, 3);
  }

  static void drawCenteredText(St7305Framebuffer& fb, uint16_t y, const char* text, uint8_t scale) {
    const int width = static_cast<int>(strlen(text)) * 6 * scale - scale;
    const int x = (RLCD_WIDTH - width) / 2;
    fb.drawText(static_cast<uint16_t>(x < 0 ? 0 : x), y, text, scale, false);
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
      const NoiseHistory* noise_history = nullptr,
      const DeviceConfig* config = nullptr,
      const WeekHistory* week = nullptr) {
    if (battery.empty_protection) {
      renderEmptyBattery(fb, battery);
      return;
    }
    switch (mode) {
      case DisplayMode::SENSOR_STATS:
        renderSensorStats(fb, battery, switchbot_slots, switchbot_count, clock_text, now_ms, info.quiet_hours, noise_history, history, config, info.stats_page, info.muted, info.reception);
        break;
      case DisplayMode::SYSTEM_DIAGNOSTICS:
        renderDiagnostics(fb, battery, info, clock_text);
        break;
      case DisplayMode::AUDIO_SPECTRUM:
        renderSpectrum(fb, battery, info, clock_text);
        break;
      case DisplayMode::CLIMATE_CHART:
        renderClimateChart(fb, battery, info, clock_text, history, config, switchbot_slots, switchbot_count);
        break;
      case DisplayMode::WEEK_CHART:
        renderWeekChart(fb, battery, info, clock_text, week, config, switchbot_slots, switchbot_count);
        break;
      case DisplayMode::DASHBOARD:
      default:
        render(fb, onboard, battery, switchbot_slots, switchbot_count, clock_text, now_ms, history, info.quiet_hours, config, info.ventilate, info.ventilate_tiles, info.sensor_battery_latch, info.dry_tiles, info.dashboard_page, info.muted, info.window_tiles, info.wall_tiles, info.reception_low, info.drift_tiles, info.heat_tiles);
        break;
    }
  }
};

} // namespace rlcd_panel
