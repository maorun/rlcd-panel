#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../rlcd_panel/battery_monitor.h"
#include "../rlcd_panel/board_pins.h"
#include "../rlcd_panel/button_handler.h"
#include "../rlcd_panel/dashboard_view.h"
#include "../rlcd_panel/shtc3_sensor.h"
#include "../rlcd_panel/st7305_framebuffer.h"
#include "../rlcd_panel/switchbot_ble.h"

using namespace rlcd_panel;

namespace {

#pragma pack(push, 1)
struct BmpFileHeader {
  uint16_t file_type{0x4D42}; // "BM"
  uint32_t file_size{0};
  uint16_t reserved1{0};
  uint16_t reserved2{0};
  uint32_t offset_data{54};
};

struct BmpInfoHeader {
  uint32_t size{40};
  int32_t  width{0};
  int32_t  height{0};
  uint16_t planes{1};
  uint16_t bit_count{24};
  uint32_t compression{0};
  uint32_t size_image{0};
  int32_t  x_pixels_per_meter{2835};
  int32_t  y_pixels_per_meter{2835};
  uint32_t colors_used{0};
  uint32_t colors_important{0};
};
#pragma pack(pop)

bool exportFramebufferToBmp(const St7305Framebuffer& fb, const char* filepath) {
  FILE* fp = fopen(filepath, "wb");
  if (!fp) {
    fprintf(stderr, "Error: Could not open output file '%s'\n", filepath);
    return false;
  }

  const uint32_t width = St7305Framebuffer::WIDTH;
  const uint32_t height = St7305Framebuffer::HEIGHT;
  // Row size in bytes must be multiple of 4: 400 * 3 = 1200 bytes (already multiple of 4)
  const uint32_t row_bytes = width * 3;
  const uint32_t image_size = row_bytes * height;

  BmpFileHeader bfh;
  bfh.file_size = 54 + image_size;
  bfh.offset_data = 54;

  BmpInfoHeader bih;
  bih.width = static_cast<int32_t>(width);
  bih.height = static_cast<int32_t>(height); // Positive height = bottom-up
  bih.size_image = image_size;

  fwrite(&bfh, sizeof(bfh), 1, fp);
  fwrite(&bih, sizeof(bih), 1, fp);

  // Reflective LCD palette colors:
  // Foreground (black/on): deep charcoal LCD pixel
  const uint8_t fg_r = 0x1A, fg_g = 0x1E, fg_b = 0x1A;
  // Background (off): pale greenish-silver ambient reflective panel surface
  const uint8_t bg_r = 0xDB, bg_g = 0xE2, bg_b = 0xD4;

  uint8_t row_buffer[row_bytes];

  // BMP bottom-up order: write from y = height - 1 down to y = 0
  for (int32_t y = static_cast<int32_t>(height) - 1; y >= 0; --y) {
    for (uint32_t x = 0; x < width; ++x) {
      const bool pixel_on = fb.getPixel(static_cast<uint16_t>(x), static_cast<uint16_t>(y));
      const size_t offset = x * 3;
      if (pixel_on) {
        row_buffer[offset + 0] = fg_b;
        row_buffer[offset + 1] = fg_g;
        row_buffer[offset + 2] = fg_r;
      } else {
        row_buffer[offset + 0] = bg_b;
        row_buffer[offset + 1] = bg_g;
        row_buffer[offset + 2] = bg_r;
      }
    }
    fwrite(row_buffer, 1, row_bytes, fp);
  }

  fclose(fp);
  return true;
}

} // namespace

int main(int argc, char** argv) {
  const char* out_path = (argc > 1) ? argv[1] : "docs/display_preview.bmp";
  // Optional second argument selects the view: 0 = dashboard (default), 1 = sensor stats,
  // 2 = system diagnostics. An optional third argument "low" renders the low battery banner.
  char* mode_end = nullptr;
  const long mode_arg = (argc > 2) ? strtol(argv[2], &mode_end, 10) : 0;
  if ((argc > 2 && (mode_end == argv[2] || *mode_end != '\0')) || mode_arg < 0 ||
      mode_arg >= DISPLAY_MODE_COUNT) {
    fprintf(stderr, "Error: mode must be 0..%d\n", DISPLAY_MODE_COUNT - 1);
    return 1;
  }

  const bool low_battery_preview = (argc > 3 && strcmp(argv[3], "low") == 0);

  St7305Framebuffer fb;

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
  if (low_battery_preview) {
    battery.voltage_v = 3.38f;
    battery.percentage = 12;
    battery.low_battery = BatteryMonitor::updateLowBattery(battery, false);
  }

  // Preview history: 24 hourly samples with a daily rhythm per sensor
  ClimateHistory history;
  const float base[ClimateHistory::SLOT_COUNT] = {21.8f, 19.4f, 13.8f, 16.2f};
  const float swing[ClimateHistory::SLOT_COUNT] = {1.2f, 2.5f, 5.0f, 0.2f};
  for (size_t s = 0; s < ClimateHistory::SLOT_COUNT; ++s) {
    for (int hour = 0; hour < 24; ++hour) {
      history.slots[s].pushCelsius(base[s] + swing[s] * sinf(static_cast<float>(hour) * 0.2618f));
    }
  }

  SystemInfo info;
  info.free_heap_bytes = 214 * 1024;
  info.uptime_ms = 93784000; // 1 d 02:03:04

  DashboardView::renderMode(
      fb,
      static_cast<DisplayMode>(mode_arg),
      onboard,
      battery,
      slots,
      MAX_SWITCHBOT_SENSORS,
      info,
      "14:32",
      now_ms,
      &history);

  if (!exportFramebufferToBmp(fb, out_path)) {
    return 1;
  }

  printf("Successfully generated display preview: %s (400x300 24-bpp)\n", out_path);
  return 0;
}
