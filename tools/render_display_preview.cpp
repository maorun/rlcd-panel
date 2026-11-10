#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../rlcd_panel/battery_monitor.h"
#include "../rlcd_panel/board_pins.h"
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

  // Sensor 2: Garden / Garten
  slots[1].valid = true;
  slots[1].temperature_c = 13.8f;
  slots[1].humidity_pct = 68;
  slots[1].battery_pct = 82;

  // Sensor 3: Cellar / Keller
  slots[2].valid = true;
  slots[2].temperature_c = 16.2f;
  slots[2].humidity_pct = 61;
  slots[2].battery_pct = 76;

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

  DashboardView::render(fb, onboard, battery, slots, MAX_SWITCHBOT_SENSORS, "14:32", now_ms);

  if (!exportFramebufferToBmp(fb, out_path)) {
    return 1;
  }

  printf("Successfully generated display preview: %s (400x300 24-bpp)\n", out_path);
  return 0;
}
