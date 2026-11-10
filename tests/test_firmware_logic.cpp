#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

#include "../rlcd_panel/battery_monitor.h"
#include "../rlcd_panel/board_pins.h"
#include "../rlcd_panel/button_handler.h"
#include "../rlcd_panel/dashboard_view.h"
#include "../rlcd_panel/pcf85063_rtc.h"
#include "../rlcd_panel/power_manager.h"
#include "../rlcd_panel/shtc3_sensor.h"
#include "../rlcd_panel/st7305_framebuffer.h"
#include "../rlcd_panel/switchbot_ble.h"

using namespace rlcd_panel;

namespace {

bool approxEqual(float a, float b, float eps = 0.05f) {
  return fabsf(a - b) <= eps;
}

void testSwitchBotManufacturerPositiveTemp() {
  // Company ID 0x0969 (0x69, 0x09) + MAC AA:BB:CC:DD:EE:01 + 2 status bytes +
  // byte[8]=0x04 (0.4 C), byte[9]=0x80 | 23 (+23 C -> +23.4 C), byte[10]=56 (56% RH, C mode)
  const uint8_t packet[13] = {
      0x69, 0x09,
      0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0x01,
      0x00, 0x00,
      0x04,
      static_cast<uint8_t>(0x80 | 23),
      56
  };

  SwitchBotReading reading;
  const bool ok = SwitchBotBleParser::parseManufacturerData(packet, sizeof(packet), reading);
  assert(ok);
  assert(reading.valid);
  assert(approxEqual(reading.temperature_c, 23.4f));
  assert(reading.humidity_pct == 56);
  assert(!reading.fahrenheit_mode);
  assert(reading.mac[5] == 0x01);
}

void testSwitchBotManufacturerNegativeTemp() {
  // -5.8 C, 82% RH, Fahrenheit display flag set on device
  const uint8_t packet[13] = {
      0x69, 0x09,
      0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0x02,
      0x00, 0x00,
      0x08,
      5, // Bit 7 = 0 -> negative
      static_cast<uint8_t>(0x80 | 82)
  };

  SwitchBotReading reading;
  const bool ok = SwitchBotBleParser::parseManufacturerData(packet, sizeof(packet), reading);
  assert(ok);
  assert(reading.valid);
  assert(approxEqual(reading.temperature_c, -5.8f));
  assert(reading.humidity_pct == 82);
  assert(reading.fahrenheit_mode);
}

void testSwitchBotInvalidPacketsRejected() {
  SwitchBotReading reading;
  assert(!SwitchBotBleParser::parseManufacturerData(nullptr, 13, reading));

  // Wrong company ID
  const uint8_t wrong_company[13] = {
      0x4C, 0x00, 0, 1, 2, 3, 4, 5, 0, 0, 0x02, 0x95, 50
  };
  assert(!SwitchBotBleParser::parseManufacturerData(wrong_company, sizeof(wrong_company), reading));

  // Too short
  const uint8_t short_pkt[12] = {
      0x69, 0x09, 0, 1, 2, 3, 4, 5, 0, 0, 0x02, 0x95
  };
  assert(!SwitchBotBleParser::parseManufacturerData(short_pkt, sizeof(short_pkt), reading));

  // Invalid decimal nibble (> 9)
  const uint8_t bad_frac[13] = {
      0x69, 0x09, 0, 1, 2, 3, 4, 5, 0, 0, 0x0A, 0x95, 50
  };
  assert(!SwitchBotBleParser::parseManufacturerData(bad_frac, sizeof(bad_frac), reading));

  // Invalid humidity (> 100)
  const uint8_t bad_rh[13] = {
      0x69, 0x09, 0, 1, 2, 3, 4, 5, 0, 0, 0x02, 0x95, 105
  };
  assert(!SwitchBotBleParser::parseManufacturerData(bad_rh, sizeof(bad_rh), reading));
}

void testSwitchBotServiceBatteryAndRegistry() {
  const uint8_t svc[3] = {0x77, 0x00, static_cast<uint8_t>(0x80 | 88)};
  int8_t battery = -1;
  assert(SwitchBotBleParser::parseServiceDataBattery(SWITCHBOT_SERVICE_UUID, svc, sizeof(svc), battery));
  assert(battery == 88);

  // Wrong service UUID returns false
  int8_t wrong_battery = -1;
  assert(!SwitchBotBleParser::parseServiceDataBattery(0x180F, svc, sizeof(svc), wrong_battery));

  SwitchBotRegistry reg;
  assert(reg.activeCount() == 0);

  for (uint8_t id = 1; id <= 3; ++id) {
    SwitchBotReading r;
    r.valid = true;
    r.mac[5] = id;
    r.temperature_c = 18.0f + id;
    r.humidity_pct = 40 + id;
    r.battery_pct = 90 - id;
    assert(reg.upsert(r));
  }
  assert(reg.activeCount() == 3);

  // Update sensor #2 without battery field -> preserves prior battery_pct
  SwitchBotReading update2;
  update2.valid = true;
  update2.mac[5] = 2;
  update2.temperature_c = 25.5f;
  update2.humidity_pct = 60;
  update2.battery_pct = -1;
  assert(reg.upsert(update2));
  assert(approxEqual(reg.slot(1).temperature_c, 25.5f));
  assert(reg.slot(1).battery_pct == 88);

  // 4th distinct sensor is rejected when all 3 slots are occupied
  SwitchBotReading fourth;
  fourth.valid = true;
  fourth.mac[5] = 4;
  assert(!reg.upsert(fourth));
}

void testSt7305FramebufferPixelMapping() {
  St7305Framebuffer fb;
  assert(St7305Framebuffer::BYTE_COUNT == 15000);
  assert(St7305Framebuffer::BLOCKS_PER_COL == 75);

  // Bottom-left block (x in 0..1, y in 296..299) maps to byte 0 (inv_y in 0..3)
  for (uint16_t local_y = 0; local_y < 4; ++local_y) {
    const uint16_t y = static_cast<uint16_t>((St7305Framebuffer::HEIGHT - 1) - local_y);
    for (uint16_t local_x = 0; local_x < 2; ++local_x) {
      fb.clear(false);
      fb.setPixel(local_x, y, true);
      assert(fb.getPixel(local_x, y));
      const uint8_t expected_mask = static_cast<uint8_t>(1U << (7 - (local_y * 2 + local_x)));
      assert(fb.data()[0] == expected_mask);
    }
  }

  // Top-left pixel (0, 0): inv_y = 299 -> block_y = 74, local_y = 3, byte_x = 0, local_x = 0
  // byte_index = 0 * 75 + 74 = 74, bit = 7 - (3*2 + 0) = 1 (mask 0x02)
  fb.clear(false);
  fb.setPixel(0, 0, true);
  assert(fb.getPixel(0, 0));
  assert(fb.data()[74] == 0x02);

  // Top-right pixel (399, 0): inv_y = 299 -> block_y = 74, local_y = 3, byte_x = 199, local_x = 1
  // byte_index = 199 * 75 + 74 = 14999, bit = 7 - (3*2 + 1) = 0 (mask 0x01)
  fb.clear(false);
  fb.setPixel(399, 0, true);
  assert(fb.getPixel(399, 0));
  assert(fb.data()[14999] == 0x01);

  // Bottom-right pixel (399, 299): inv_y = 0 -> block_y = 0, local_y = 0, byte_x = 199, local_x = 1
  // byte_index = 199 * 75 + 0 = 14925, bit = 7 - (0*2 + 1) = 6 (mask 0x40)
  fb.clear(false);
  fb.setPixel(399, 299, true);
  assert(fb.getPixel(399, 299));
  assert(fb.data()[14925] == 0x40);

  // Out-of-bounds writes are ignored safely
  fb.setPixel(400, 0, true);
  fb.setPixel(0, 300, true);
  assert(!fb.getPixel(400, 0));
  assert(!fb.getPixel(0, 300));
}

void testShtc3Codec() {
  // 0x6666 -> ~25.0 C uncalibrated, 0x8000 -> 50.0 %RH
  uint8_t frame[6] = {0x66, 0x66, 0x00, 0x80, 0x00, 0x00};
  frame[2] = Shtc3Codec::computeCrc8(frame, 2);
  frame[5] = Shtc3Codec::computeCrc8(frame + 3, 2);

  // Default calibration offset (-1.9 C): 25.0 - 1.9 = 23.1 C
  Shtc3Reading default_out;
  assert(Shtc3Codec::decodeFrame(frame, sizeof(frame), default_out));
  assert(default_out.valid);
  assert(approxEqual(default_out.temperature_c, 23.1f, 0.1f));
  assert(approxEqual(default_out.humidity_pct, 50.0f, 0.1f));

  // Explicit zero offset (raw uncalibrated): 25.0 C
  Shtc3Reading raw_out;
  assert(Shtc3Codec::decodeFrame(frame, sizeof(frame), raw_out, 0.0f));
  assert(raw_out.valid);
  assert(approxEqual(raw_out.temperature_c, 25.0f, 0.1f));
  assert(approxEqual(raw_out.humidity_pct, 50.0f, 0.1f));

  // Explicit custom offset (-2.5 C): 25.0 - 2.5 = 22.5 C
  Shtc3Reading custom_out;
  assert(Shtc3Codec::decodeFrame(frame, sizeof(frame), custom_out, -2.5f));
  assert(custom_out.valid);
  assert(approxEqual(custom_out.temperature_c, 22.5f, 0.1f));

  // Corrupt CRC must fail
  frame[2] ^= 0xFF;
  assert(!Shtc3Codec::decodeFrame(frame, sizeof(frame), default_out));
}

void testBatteryMonitorDecoding() {
  assert(BatteryMonitor::voltageToPercent(4.20f) == 100);
  assert(BatteryMonitor::voltageToPercent(4.30f) == 100);
  assert(BatteryMonitor::voltageToPercent(3.70f) == 50);
  assert(BatteryMonitor::voltageToPercent(3.20f) == 0);
  assert(BatteryMonitor::voltageToPercent(3.10f) == 0);
  assert(BatteryMonitor::voltageToPercent(0.20f) == -1);

  // ADC = 1400 mV * 3.0 = 4200 mV -> 4.20 V, 100%
  const BatteryReading full = BatteryMonitor::decodeMillivolts(1400);
  assert(full.valid);
  assert(approxEqual(full.voltage_v, 4.20f, 0.01f));
  assert(full.percentage == 100);
  assert(!full.usb_powered);

  // ADC = 1450 mV * 3.0 = 4350 mV -> 4.35 V, usb_powered = true
  const BatteryReading usb = BatteryMonitor::decodeMillivolts(1450);
  assert(usb.valid);
  assert(usb.usb_powered);

  // ADC = 100 mV * 3.0 = 300 mV -> invalid (< 0.5V)
  const BatteryReading empty = BatteryMonitor::decodeMillivolts(100);
  assert(!empty.valid);
}

void testDashboardViewRendering() {
  St7305Framebuffer fb;
  Shtc3Reading onboard;
  onboard.valid = true;
  onboard.temperature_c = 22.0f;
  onboard.humidity_pct = 50.0f;

  BatteryReading battery;
  battery.valid = true;
  battery.voltage_v = 3.85f;
  battery.percentage = 65;

  SwitchBotReading slots[MAX_SWITCHBOT_SENSORS];
  slots[0].valid = true;
  slots[0].temperature_c = 19.5f;
  slots[0].humidity_pct = 55;
  slots[0].battery_pct = 90;

  DashboardView::render(fb, onboard, battery, slots, MAX_SWITCHBOT_SENSORS, nullptr, 0);

  // Top header bar (y in 0..27) must contain active black pixels
  bool header_pixels_found = false;
  for (uint16_t x = 0; x < 50; ++x) {
    if (fb.getPixel(x, 10)) {
      header_pixels_found = true;
      break;
    }
  }
  assert(header_pixels_found);

  // Null slots gracefully handled without crash
  DashboardView::render(fb, onboard, battery, nullptr, 0, nullptr, 0);
  assert(header_pixels_found);
}

void testPcf85063BcdCodec() {
  uint8_t bin = 0;
  assert(Pcf85063Codec::bcdToBin(0x59, bin) && bin == 59);
  assert(Pcf85063Codec::bcdToBin(0x00, bin) && bin == 0);
  assert(!Pcf85063Codec::bcdToBin(0x5A, bin));
  assert(!Pcf85063Codec::bcdToBin(0xA0, bin));
  assert(Pcf85063Codec::binToBcd(59) == 0x59);
  assert(Pcf85063Codec::binToBcd(7) == 0x07);

  // 2026-11-10 (Tuesday) 14:32:45
  const uint8_t regs[7] = {0x45, 0x32, 0x14, 0x10, 0x02, 0x11, 0x26};
  RtcTime t;
  assert(Pcf85063Codec::decodeRegisters(regs, sizeof(regs), t));
  assert(t.valid && !t.oscillator_stopped);
  assert(t.hour == 14 && t.minute == 32 && t.second == 45);
  assert(t.day == 10 && t.weekday == 2 && t.month == 11 && t.year == 2026);
  assert(Pcf85063Codec::computeWeekday(2026, 11, 10) == 2);

  // Encode round trip
  uint8_t out[7] = {0};
  assert(Pcf85063Codec::encodeRegisters(t, out, sizeof(out)));
  assert(memcmp(out, regs, sizeof(regs)) == 0);

  // Control bits in the register high bits are masked off
  const uint8_t masked[7] = {0x45, 0xB2, 0x14 | 0x80, 0x10 | 0x40, 0x02 | 0xF8, 0x11 | 0xE0, 0x26};
  assert(Pcf85063Codec::decodeRegisters(masked, sizeof(masked), t));
  assert(t.hour == 14 && t.minute == 32 && t.month == 11);

  char text[8];
  Pcf85063Codec::formatClock(t, text, sizeof(text));
  assert(strcmp(text, "14:32") == 0);
  Pcf85063Codec::formatClock(RtcTime{}, text, sizeof(text));
  assert(strcmp(text, "--:--") == 0);
}

void testPcf85063InvalidValues() {
  RtcTime t;
  const uint8_t good[7] = {0x45, 0x32, 0x14, 0x10, 0x02, 0x11, 0x26};
  uint8_t regs[7];

  // Oscillator-stop flag (bit 7 of seconds) marks the time as invalid
  memcpy(regs, good, sizeof(regs));
  regs[0] |= 0x80;
  assert(!Pcf85063Codec::decodeRegisters(regs, sizeof(regs), t));
  assert(!t.valid && t.oscillator_stopped);

  // Invalid BCD digit
  memcpy(regs, good, sizeof(regs));
  regs[1] = 0x6A;
  assert(!Pcf85063Codec::decodeRegisters(regs, sizeof(regs), t));
  assert(!t.valid);

  // BCD valid but out of range: 24 h, 60 min, month 13, day 0
  memcpy(regs, good, sizeof(regs));
  regs[2] = 0x24;
  assert(!Pcf85063Codec::decodeRegisters(regs, sizeof(regs), t));
  memcpy(regs, good, sizeof(regs));
  regs[1] = 0x60;
  assert(!Pcf85063Codec::decodeRegisters(regs, sizeof(regs), t));
  memcpy(regs, good, sizeof(regs));
  regs[5] = 0x13;
  assert(!Pcf85063Codec::decodeRegisters(regs, sizeof(regs), t));
  memcpy(regs, good, sizeof(regs));
  regs[3] = 0x00;
  assert(!Pcf85063Codec::decodeRegisters(regs, sizeof(regs), t));

  // Impossible calendar dates: Feb 31, Apr 31, Feb 29 in a non-leap year
  memcpy(regs, good, sizeof(regs));
  regs[3] = 0x31;
  regs[5] = 0x02;
  assert(!Pcf85063Codec::decodeRegisters(regs, sizeof(regs), t));
  regs[5] = 0x04;
  assert(!Pcf85063Codec::decodeRegisters(regs, sizeof(regs), t));
  regs[3] = 0x29;
  regs[5] = 0x02;
  assert(!Pcf85063Codec::decodeRegisters(regs, sizeof(regs), t)); // 2026
  regs[6] = 0x24; // 2024 is a leap year
  assert(Pcf85063Codec::decodeRegisters(regs, sizeof(regs), t));
  assert(Pcf85063Codec::daysInMonth(2100, 2) == 28);
  assert(Pcf85063Codec::daysInMonth(2000, 2) == 29);

  // Null and short buffers
  assert(!Pcf85063Codec::decodeRegisters(nullptr, 7, t));
  assert(!Pcf85063Codec::decodeRegisters(good, 6, t));

  // Encode rejects out-of-range values and short buffers
  RtcTime bad;
  bad.valid = true;
  bad.hour = 24;
  assert(!Pcf85063Codec::encodeRegisters(bad, regs, sizeof(regs)));
  RtcTime ok;
  assert(!Pcf85063Codec::encodeRegisters(ok, regs, 6));
  assert(!Pcf85063Codec::encodeRegisters(ok, nullptr, 7));

  // Compile stamp parsing
  RtcTime stamp;
  assert(Pcf85063Codec::fromCompileStamp("Nov 10 2026", "14:03:47", stamp));
  assert(stamp.valid && stamp.year == 2026 && stamp.month == 11 && stamp.day == 10);
  assert(stamp.hour == 14 && stamp.minute == 3 && stamp.second == 47 && stamp.weekday == 2);
  assert(Pcf85063Codec::fromCompileStamp("Jan  5 2027", "00:00:00", stamp));
  assert(stamp.day == 5);
  assert(!Pcf85063Codec::fromCompileStamp("Xyz 10 2026", "14:03:47", stamp));
  assert(!Pcf85063Codec::fromCompileStamp("Nov 10 2026", "25:03:47", stamp));
}

void testDashboardHeaderClock() {
  Shtc3Reading onboard;
  BatteryReading battery;
  battery.valid = true;
  battery.voltage_v = 4.20f;
  battery.percentage = 100; // widest battery label "100%"

  St7305Framebuffer base;
  St7305Framebuffer with_clock;
  DashboardView::render(base, onboard, battery, nullptr, 0, nullptr, 0);
  DashboardView::render(with_clock, onboard, battery, nullptr, 0, "88:88", 0);

  // Title occupies x 10..224, battery label + icon start at x = 316.
  const uint16_t title_right = 10 + 18 * 12;
  const uint16_t battery_left = 364 - 4 * 12;

  uint16_t min_x = St7305Framebuffer::WIDTH, max_x = 0;
  uint16_t min_y = St7305Framebuffer::HEIGHT, max_y = 0;
  size_t changed = 0;
  for (uint16_t y = 0; y < St7305Framebuffer::HEIGHT; ++y) {
    for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
      if (base.getPixel(x, y) != with_clock.getPixel(x, y)) {
        ++changed;
        if (x < min_x) min_x = x;
        if (x > max_x) max_x = x;
        if (y < min_y) min_y = y;
        if (y > max_y) max_y = y;
      }
    }
  }

  assert(changed > 0);
  // Inside the header bar
  assert(max_y < DashboardView::HEADER_HEIGHT);
  assert(min_y >= 1);
  // No overlap with title or battery indicator, with a margin on both sides
  assert(min_x > title_right + 8);
  assert(max_x + 8 < battery_left);
  // Full 5 glyphs rendered without clipping: 4 * 12 + 10 = 58 px
  assert(max_x - min_x + 1 >= 50);

  // Title and battery pixels are unchanged by the clock
  for (uint16_t y = 0; y < DashboardView::HEADER_HEIGHT; ++y) {
    for (uint16_t x = 0; x <= title_right; ++x) {
      assert(base.getPixel(x, y) == with_clock.getPixel(x, y));
    }
    for (uint16_t x = battery_left; x < St7305Framebuffer::WIDTH; ++x) {
      assert(base.getPixel(x, y) == with_clock.getPixel(x, y));
    }
  }

  // Overlong clock text is rejected instead of clipping into the battery area
  St7305Framebuffer overlong;
  DashboardView::render(overlong, onboard, battery, nullptr, 0, "12:34:56:78", 0);
  assert(memcmp(overlong.data(), base.data(), St7305Framebuffer::BYTE_COUNT) == 0);
}

void testSwitchBotStaleDetection() {
  SwitchBotReading reading;
  reading.valid = true;
  reading.updated_ms = 1000;

  assert(SENSOR_STALE_TIMEOUT_MS == 1800000u);

  // Fresh reading (< 30 min) is not stale
  assert(!reading.isStale(1000));
  assert(!reading.isStale(1000 + 60000));
  assert(!reading.isStale(1000 + SENSOR_STALE_TIMEOUT_MS));
  // Older than 1800000 ms is stale
  assert(reading.isStale(1000 + SENSOR_STALE_TIMEOUT_MS + 1));
  // Custom timeout
  assert(reading.isStale(1000 + 5001, 5000));
  assert(!reading.isStale(1000 + 5000, 5000));

  // millis() wrap-around: packet 10 s before the wrap, now 10 s after it (20 s elapsed)
  SwitchBotReading wrapped;
  wrapped.valid = true;
  wrapped.updated_ms = 0xFFFFFFFFu - 9999u;
  assert(!wrapped.isStale(10000u));
  // Exactly 30 min after a wrapped packet is not stale, 30 min + 1 ms is
  assert(!wrapped.isStale(SENSOR_STALE_TIMEOUT_MS - 10000u));
  assert(wrapped.isStale(SENSOR_STALE_TIMEOUT_MS - 9999u));

  // Invalid (never seen) slots are not reported as stale
  SwitchBotReading empty;
  assert(!empty.isStale(0xFFFFFFFFu));
}

void testDashboardStaleIndicator() {
  Shtc3Reading onboard;
  onboard.valid = true;
  onboard.temperature_c = 22.0f;
  onboard.humidity_pct = 50.0f;
  BatteryReading battery;
  battery.valid = true;
  battery.voltage_v = 3.85f;
  battery.percentage = 65;

  SwitchBotReading slots[MAX_SWITCHBOT_SENSORS];
  for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
    slots[i].valid = true;
    slots[i].temperature_c = 18.0f + static_cast<float>(i);
    slots[i].humidity_pct = 55;
    slots[i].battery_pct = 90;
    slots[i].updated_ms = 100000;
  }
  slots[1].updated_ms = 100000 - SENSOR_STALE_TIMEOUT_MS - 1; // 1 ms past the timeout (stale)

  const uint32_t now_ms = 100000;
  SwitchBotReading fresh_slots[MAX_SWITCHBOT_SENSORS];
  for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
    fresh_slots[i] = slots[i];
  }
  fresh_slots[1].updated_ms = now_ms; // same data, but fresh

  St7305Framebuffer fresh;
  St7305Framebuffer mixed;
  // Only slot 1 is stale in "mixed"
  DashboardView::render(fresh, onboard, battery, fresh_slots, MAX_SWITCHBOT_SENSORS, nullptr, now_ms);
  DashboardView::render(mixed, onboard, battery, slots, MAX_SWITCHBOT_SENSORS, nullptr, now_ms);

  // Tile 1 is SwitchBot 2: col 0 / row 1 => x 6..195, y 168..291; tile area differs
  auto diffInRect = [&](uint16_t x0, uint16_t y0, uint16_t w, uint16_t h) {
    size_t diff = 0;
    for (uint16_t y = y0; y < y0 + h; ++y) {
      for (uint16_t x = x0; x < x0 + w; ++x) {
        if (fresh.getPixel(x, y) != mixed.getPixel(x, y)) {
          ++diff;
        }
      }
    }
    return diff;
  };

  // SwitchBot 2 tile (stale): pixels must differ
  assert(diffInRect(6, 36 + 132, 190, 124) > 0);
  // Onboard, SwitchBot 1 and SwitchBot 3 tiles and the header stay identical
  assert(diffInRect(6, 36, 190, 124) == 0);
  assert(diffInRect(204, 36, 190, 124) == 0);
  assert(diffInRect(204, 36 + 132, 190, 124) == 0);
  assert(diffInRect(0, 0, 400, 28) == 0);

  // The stale indicator stays inside the tile border, in the title band above the divider line
  size_t outside = 0;
  for (uint16_t y = 0; y < 300; ++y) {
    for (uint16_t x = 0; x < 400; ++x) {
      if (fresh.getPixel(x, y) != mixed.getPixel(x, y) &&
          (x <= 6 || x >= 195 || y <= 168 || y >= 168 + 24)) {
        ++outside;
      }
    }
  }
  assert(outside == 0);

  // Invalid slot never shows STALE
  SwitchBotReading invalid_slots[MAX_SWITCHBOT_SENSORS];
  St7305Framebuffer a;
  St7305Framebuffer b;
  DashboardView::render(a, onboard, battery, invalid_slots, MAX_SWITCHBOT_SENSORS, nullptr, 0);
  DashboardView::render(b, onboard, battery, invalid_slots, MAX_SWITCHBOT_SENSORS, nullptr, 0xFFFFFFFFu);
  assert(memcmp(a.data(), b.data(), St7305Framebuffer::BYTE_COUNT) == 0);
}

void testButtonDebounceAndModeTransitions() {
  // Bounce on press is ignored: no event before the 30 ms debounce time has passed.
  ButtonDebouncer button;
  assert(!button.update(false, 0));      // seed: released
  assert(!button.update(true, 100));     // contact bounce starts
  assert(!button.update(false, 110));    // bounce back
  assert(!button.update(true, 115));
  assert(!button.update(true, 140));     // 25 ms stable: too early
  assert(!button.pressed());
  assert(button.update(true, 145));      // 30 ms stable: one press event
  assert(button.pressed());
  assert(!button.update(true, 500));     // holding raises no further event
  assert(!button.update(false, 600));    // release raises no event
  assert(!button.update(false, 640));
  assert(!button.pressed());

  // Bounce on release raises no event; only the next stable press does.
  ButtonDebouncer chatter;
  assert(!chatter.update(false, 0));
  assert(!chatter.update(true, 10));
  assert(chatter.update(true, 40));       // press event
  assert(!chatter.update(false, 100));    // release bounce starts
  assert(!chatter.update(true, 110));
  assert(!chatter.update(false, 120));
  assert(!chatter.update(false, 160));    // stable release
  assert(!chatter.pressed());
  assert(!chatter.update(true, 200));
  assert(chatter.update(true, 230));      // second press: exactly one more event

  // Zero debounce time reports a press on the second identical sample, and a long hold stays silent.
  ButtonDebouncer instant(0);
  assert(!instant.update(false, 0));
  assert(!instant.update(true, 1));
  assert(instant.update(true, 2));
  for (uint32_t t = 2; t < 100000; t += 997) {
    assert(!instant.update(true, t));
  }
  assert(!instant.update(false, 100000));
  assert(!instant.update(false, 100001));
  assert(!instant.update(true, 100002));
  assert(instant.update(true, 100003));

  // A button held at boot seeds the state and does not count as a press.
  ButtonDebouncer held;
  assert(!held.update(true, 0));
  assert(!held.update(true, 1000));

  // Debounce survives millis() wrap-around.
  ButtonDebouncer wrapped;
  assert(!wrapped.update(false, 0xFFFFFFF0u));
  assert(!wrapped.update(true, 0xFFFFFFF8u));
  assert(!wrapped.update(true, 0x00000010u)); // 24 ms elapsed
  assert(wrapped.update(true, 0x00000016u));  // 30 ms elapsed

  // Each debounced press cycles DASHBOARD -> SENSOR_STATS -> SYSTEM_DIAGNOSTICS -> DASHBOARD.
  DisplayModeController controller;
  assert(controller.mode() == DisplayMode::DASHBOARD);
  uint32_t t = 0;
  const DisplayMode expected[4] = {
      DisplayMode::SENSOR_STATS,
      DisplayMode::SYSTEM_DIAGNOSTICS,
      DisplayMode::DASHBOARD,
      DisplayMode::SENSOR_STATS};
  controller.update(false, t);
  for (const DisplayMode want : expected) {
    t += 100;
    controller.update(true, t);
    t += 40;
    assert(controller.update(true, t));
    assert(controller.mode() == want);
    t += 100;
    controller.update(false, t);
    t += 40;
    assert(!controller.update(false, t));
    assert(controller.mode() == want);
  }
}

void testDisplayModesRenderWithoutClipping() {
  Shtc3Reading onboard;
  onboard.valid = true;
  onboard.temperature_c = -12.3f;
  onboard.humidity_pct = 100.0f;

  // Worst-case widths: low RSSI, big packet counter, long age, wide battery label.
  SwitchBotReading slots[MAX_SWITCHBOT_SENSORS];
  for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
    slots[i].valid = true;
    slots[i].rssi = -100;
    slots[i].packet_count = 4000000000u;
    slots[i].updated_ms = 0;
    for (size_t b = 0; b < 6; ++b) {
      slots[i].mac[b] = 0xDD;
    }
  }
  BatteryReading battery;
  battery.valid = true;
  battery.voltage_v = 4.20f;
  battery.percentage = 100;
  SystemInfo info;
  info.free_heap_bytes = 8u * 1024 * 1024;
  info.uptime_ms = 0xFFFFFFFFFFull * 1000; // widest uptime: thousands of days
  const uint32_t now_ms = 0xFFFFFFFFu;

  const DisplayMode modes[DISPLAY_MODE_COUNT] = {
      DisplayMode::DASHBOARD, DisplayMode::SENSOR_STATS, DisplayMode::SYSTEM_DIAGNOSTICS};
  St7305Framebuffer frames[DISPLAY_MODE_COUNT];
  for (uint8_t m = 0; m < DISPLAY_MODE_COUNT; ++m) {
    St7305Framebuffer& fb = frames[m];
    // Poison the buffer: a full redraw must overwrite stale content.
    fb.clear(true);
    DashboardView::renderMode(
        fb, modes[m], onboard, battery, slots, MAX_SWITCHBOT_SENSORS, info, "88:88", now_ms);

    // Header bar is drawn and the body has content.
    assert(fb.getPixel(0, 0));
    size_t body_ink = 0;
    for (uint16_t y = DashboardView::HEADER_HEIGHT; y < St7305Framebuffer::HEIGHT; ++y) {
      for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
        if (fb.getPixel(x, y)) {
          ++body_ink;
        }
      }
    }
    assert(body_ink > 500);

    if (modes[m] != DisplayMode::DASHBOARD) {
      // Text views leave a blank margin on the right and bottom edges: nothing is cut off.
      for (uint16_t y = DashboardView::HEADER_HEIGHT; y < St7305Framebuffer::HEIGHT; ++y) {
        for (uint16_t x = St7305Framebuffer::WIDTH - 8; x < St7305Framebuffer::WIDTH; ++x) {
          assert(!fb.getPixel(x, y));
        }
      }
      for (uint16_t y = St7305Framebuffer::HEIGHT - 8; y < St7305Framebuffer::HEIGHT; ++y) {
        for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
          assert(!fb.getPixel(x, y));
        }
      }
    }
  }

  // Sensor stats: every sensor block (3 text lines of 12 px height) has ink on each line.
  for (uint16_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
    for (uint16_t line = 0; line < 3; ++line) {
      const uint16_t y0 = static_cast<uint16_t>(34 + i * 78 + line * 18);
      size_t ink = 0;
      for (uint16_t y = y0; y < y0 + 14; ++y) {
        for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
          ink += frames[1].getPixel(x, y) ? 1 : 0;
        }
      }
      assert(ink > 20);
    }
  }

  // Diagnostics: power, battery, heap and uptime lines are all drawn.
  const uint16_t diag_rows[4] = {44, 84, 124, 164};
  for (const uint16_t y0 : diag_rows) {
    size_t ink = 0;
    for (uint16_t y = y0; y < y0 + 14; ++y) {
      for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
        ink += frames[2].getPixel(x, y) ? 1 : 0;
      }
    }
    assert(ink > 20);
  }

  // Invalid slot prints one NO SIGNAL line and leaves the MAC and packet lines blank.
  {
    SwitchBotReading one_invalid[MAX_SWITCHBOT_SENSORS] = {};
    St7305Framebuffer fb;
    DashboardView::renderMode(
        fb, DisplayMode::SENSOR_STATS, onboard, battery, one_invalid, MAX_SWITCHBOT_SENSORS,
        info, "88:88", 0);
    size_t first = 0, second = 0;
    for (uint16_t y = 34; y < 48; ++y) {
      for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
        first += fb.getPixel(x, y) ? 1 : 0;
      }
    }
    for (uint16_t y = 52; y < 84; ++y) {
      for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
        second += fb.getPixel(x, y) ? 1 : 0;
      }
    }
    assert(first > 20);
    assert(second == 0);
  }

  // A packet stamped after now_ms renders age 0 instead of a wrapped value: same pixels as age 0.
  {
    SwitchBotReading future[MAX_SWITCHBOT_SENSORS] = {};
    SwitchBotReading zero[MAX_SWITCHBOT_SENSORS] = {};
    future[0].valid = zero[0].valid = true;
    future[0].updated_ms = 5000;
    zero[0].updated_ms = 4000;
    St7305Framebuffer a, b;
    DashboardView::renderMode(
        a, DisplayMode::SENSOR_STATS, onboard, battery, future, MAX_SWITCHBOT_SENSORS, info, "88:88", 4000);
    DashboardView::renderMode(
        b, DisplayMode::SENSOR_STATS, onboard, battery, zero, MAX_SWITCHBOT_SENSORS, info, "88:88", 4000);
    assert(memcmp(a.data(), b.data(), St7305Framebuffer::BYTE_COUNT) == 0);
  }

  // Age text: seconds, minutes, hours and a 30-day silence each render differently; a packet
  // 65 s in the near future still renders as age 0.
  {
    const uint32_t ages_ms[5] = {0, 59000u, 61000u, 2u * 3600u * 1000u, 30u * 86400u * 1000u};
    St7305Framebuffer renders[5];
    for (size_t k = 0; k < 5; ++k) {
      SwitchBotReading one[MAX_SWITCHBOT_SENSORS] = {};
      one[0].valid = true;
      one[0].updated_ms = 1000;
      DashboardView::renderMode(
          renders[k], DisplayMode::SENSOR_STATS, onboard, battery, one, MAX_SWITCHBOT_SENSORS,
          info, "88:88", 1000 + ages_ms[k]);
    }
    for (size_t a = 0; a < 5; ++a) {
      for (size_t b = a + 1; b < 5; ++b) {
        assert(memcmp(renders[a].data(), renders[b].data(), St7305Framebuffer::BYTE_COUNT) != 0);
      }
    }
  }

  // Header titles of the text views stay clear of the clock and the battery indicator.
  for (uint8_t m = 1; m < DISPLAY_MODE_COUNT; ++m) {
    St7305Framebuffer with_clock;
    St7305Framebuffer without_clock;
    DashboardView::renderMode(
        with_clock, modes[m], onboard, battery, slots, MAX_SWITCHBOT_SENSORS, info, "88:88", now_ms);
    DashboardView::renderMode(
        without_clock, modes[m], onboard, battery, slots, MAX_SWITCHBOT_SENSORS, info, nullptr, now_ms);
    uint16_t min_x = St7305Framebuffer::WIDTH;
    for (uint16_t y = 0; y < DashboardView::HEADER_HEIGHT; ++y) {
      for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
        if (with_clock.getPixel(x, y) != without_clock.getPixel(x, y) && x < min_x) {
          min_x = x;
        }
      }
    }
    assert(min_x > 10 + 18 * 12 + 8); // longest title "SYSTEM DIAGNOSTICS" ends at x = 226
  }

  // The three views differ, and empty inputs render without crashing.
  assert(memcmp(frames[0].data(), frames[1].data(), St7305Framebuffer::BYTE_COUNT) != 0);
  assert(memcmp(frames[1].data(), frames[2].data(), St7305Framebuffer::BYTE_COUNT) != 0);
  BatteryReading no_battery;
  for (uint8_t m = 0; m < DISPLAY_MODE_COUNT; ++m) {
    St7305Framebuffer fb;
    DashboardView::renderMode(fb, modes[m], Shtc3Reading{}, no_battery, nullptr, 0, SystemInfo{}, nullptr, 0);
  }
}

void testSwitchBotPacketCounter() {
  SwitchBotRegistry registry;
  SwitchBotReading reading;
  reading.valid = true;
  reading.mac[5] = 0x07;
  assert(registry.upsert(reading));
  assert(registry.slot(0).packet_count == 1);
  assert(registry.upsert(reading));
  assert(registry.upsert(reading));
  assert(registry.slot(0).packet_count == 3);

  // A new MAC starts at 1 even when the incoming reading carries a stale counter.
  SwitchBotReading other;
  other.valid = true;
  other.mac[5] = 0x08;
  other.packet_count = 99;
  assert(registry.upsert(other));
  assert(registry.slot(1).packet_count == 1);
  assert(registry.slot(0).packet_count == 3);
  // A repeat of that MAC counts from the stored value, not from the incoming one.
  other.packet_count = 50;
  assert(registry.upsert(other));
  assert(registry.slot(1).packet_count == 2);
}

SwitchBotReading makeReading(uint8_t id, float temp, uint8_t hum, int8_t batt) {
  SwitchBotReading r;
  r.valid = true;
  for (int i = 0; i < 6; ++i) r.mac[i] = static_cast<uint8_t>(0xA0 + id);
  r.temperature_c = temp;
  r.humidity_pct = hum;
  r.battery_pct = batt;
  r.rssi = -71;
  r.updated_ms = 12345u * id;
  r.packet_count = 7u + id;
  return r;
}

void testPowerSleepTimerCalculation() {
  PowerConfig cfg;
  assert(cfg.sleep_interval_s == 60 && cfg.scan_duration_s == 4);
  // Awake for 6 s after the scan start: sleep the remaining 54 s.
  assert(PowerManager::sleepMicros(cfg, 16000, 10000) == 54000000ULL);
  // No time elapsed: full interval.
  assert(PowerManager::sleepMicros(cfg, 10000, 10000) == 60000000ULL);
  // Awake longer than the interval: clamp to the minimum.
  assert(PowerManager::sleepMicros(cfg, 90000, 10000) == PowerManager::MIN_SLEEP_US);
  // Awake almost the whole interval: clamp to the minimum.
  assert(PowerManager::sleepMicros(cfg, 69500, 10000) == PowerManager::MIN_SLEEP_US);
  // Clock wrap-around between scan start and now.
  assert(PowerManager::sleepMicros(cfg, 5000, 0xFFFFF000u) == (60000ULL - 9096ULL) * 1000ULL);
  // Boundary: 1 s left is the minimum; 1 ms either side stays at the minimum or one step above.
  assert(PowerManager::sleepMicros(cfg, 10000 + 59000, 10000) == 1000000ULL);
  assert(PowerManager::sleepMicros(cfg, 10000 + 58999, 10000) == 1001000ULL);
  assert(PowerManager::sleepMicros(cfg, 10000 + 59001, 10000) == PowerManager::MIN_SLEEP_US);
  assert(PowerManager::sleepMicros(cfg, 10000 + 60000, 10000) == PowerManager::MIN_SLEEP_US);
  // Interval beyond 71 minutes must not overflow 32 bit math.
  PowerConfig big;
  big.sleep_interval_s = 100000;
  assert(PowerManager::sleepMicros(big, 0, 0) == 3600ULL * 1000000ULL);

  // Sanitizing: limits for interval, scan at least 1 s and at most half the interval.
  PowerConfig low;
  low.sleep_interval_s = 1;
  low.scan_duration_s = 50;
  const PowerConfig fixed = low.sanitized();
  assert(fixed.sleep_interval_s == PowerConfig::MIN_SLEEP_INTERVAL_S);
  assert(fixed.scan_duration_s == PowerConfig::MIN_SLEEP_INTERVAL_S / 2);
  PowerConfig zero;
  zero.scan_duration_s = 0;
  assert(zero.sanitized().scan_duration_s == 1);

  // The plan skips the KEY wakeup while the button is held.
  assert(PowerManager::planSleep(cfg, 16000, 10000, false).enable_key_wakeup);
  assert(!PowerManager::planSleep(cfg, 16000, 10000, true).enable_key_wakeup);
  assert(PowerManager::planSleep(cfg, 16000, 10000, false).timer_us == 54000000ULL);
}

void testPowerStateSerialization() {
  PersistentState s;
  s.boot_count = 42;
  s.last_scan_ms = 0xDEADBEEFu;
  s.mode = DisplayMode::SENSOR_STATS;
  s.battery.valid = true;
  s.battery.voltage_v = 3.87f;
  s.battery.percentage = 67;
  s.battery.usb_powered = false;
  s.registry.upsert(makeReading(1, 21.4f, 55, 88));
  s.registry.upsert(makeReading(2, -7.3f, 90, -1));

  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  assert(!PowerManager::serialize(s, buf, sizeof(buf) - 1));

  PersistentState r;
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(r.boot_count == 42 && r.last_scan_ms == 0xDEADBEEFu);
  assert(r.mode == DisplayMode::SENSOR_STATS);
  assert(r.battery.valid && r.battery.percentage == 67 && approxEqual(r.battery.voltage_v, 3.87f));
  assert(r.registry.activeCount() == 2);
  const SwitchBotReading& a = r.registry.slot(0);
  assert(a.valid && approxEqual(a.temperature_c, 21.4f) && a.humidity_pct == 55 &&
         a.battery_pct == 88 && a.rssi == -71 && a.updated_ms == 12345u && a.packet_count == 1u);
  assert(memcmp(a.mac, s.registry.slot(0).mac, 6) == 0);
  const SwitchBotReading& b = r.registry.slot(1);
  assert(approxEqual(b.temperature_c, -7.3f) && b.battery_pct == -1);
  assert(!r.registry.slot(2).valid);

  // Corruption: every single flipped byte fails the checksum or a field check.
  for (size_t i = 0; i < sizeof(buf); ++i) {
    uint8_t bad[PowerManager::SERIALIZED_SIZE];
    memcpy(bad, buf, sizeof(buf));
    bad[i] ^= 0x40;
    PersistentState out;
    out.boot_count = 99;
    assert(!PowerManager::deserialize(bad, sizeof(bad), out));
    assert(out.boot_count == 0 && out.registry.activeCount() == 0);
  }
  // Wrong length, null pointer, blank RTC memory.
  PersistentState out;
  assert(!PowerManager::deserialize(buf, sizeof(buf) - 1, out));
  assert(!PowerManager::deserialize(nullptr, sizeof(buf), out));
  uint8_t blank[PowerManager::SERIALIZED_SIZE] = {0};
  assert(!PowerManager::deserialize(blank, sizeof(blank), out));

  // Wrong version with a valid checksum is rejected.
  uint8_t old[PowerManager::SERIALIZED_SIZE];
  memcpy(old, buf, sizeof(buf));
  old[4] = PowerManager::STATE_VERSION + 1;
  const uint32_t crc = PowerManager::crc32(old, sizeof(old) - 4);
  for (int i = 0; i < 4; ++i) old[sizeof(old) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  assert(!PowerManager::deserialize(old, sizeof(old), out));

  // Range checks behind a valid checksum: each out-of-range field makes deserialize fail.
  struct Patch { size_t offset; uint8_t value; };
  const size_t slot0_off = PowerManager::HEADER_SIZE;
  const Patch patches[] = {
      {5, 9},                       // mode
      {6, 4},                       // battery flags
      {7, 101},                     // battery percentage
      {11, 0x7F},                   // battery voltage exponent byte -> huge value
      {slot0_off, 2},               // slot valid flag
      {slot0_off + 11, 101},        // slot humidity
      {slot0_off + 12, 101},        // slot battery
      {slot0_off + 13, 2},          // slot fahrenheit flag
  };
  for (const Patch& p : patches) {
    uint8_t bad[PowerManager::SERIALIZED_SIZE];
    memcpy(bad, buf, sizeof(buf));
    bad[p.offset] = p.value;
    const uint32_t c = PowerManager::crc32(bad, sizeof(bad) - 4);
    for (int i = 0; i < 4; ++i) bad[sizeof(bad) - 4 + i] = static_cast<uint8_t>(c >> (8 * i));
    assert(!PowerManager::deserialize(bad, sizeof(bad), out));
  }

  // restoreSlot refuses a bad index, an invalid reading, and a MAC held by another slot.
  PersistentState dup;
  assert(dup.registry.restoreSlot(0, makeReading(1, 20.0f, 50, 50)));
  assert(!dup.registry.restoreSlot(1, makeReading(1, 21.0f, 51, 51)));
  assert(!dup.registry.restoreSlot(MAX_SWITCHBOT_SENSORS, makeReading(2, 21.0f, 51, 51)));
  assert(!dup.registry.restoreSlot(1, SwitchBotReading{}));
  assert(dup.registry.restoreSlot(0, makeReading(1, 22.0f, 52, 52))); // same slot may overwrite
  assert(dup.registry.activeCount() == 1);

  // A blob with two slots of one MAC and a valid checksum fails in deserialize.
  uint8_t dupbuf[PowerManager::SERIALIZED_SIZE];
  memcpy(dupbuf, buf, sizeof(buf));
  const size_t slot0 = PowerManager::HEADER_SIZE;
  memcpy(dupbuf + slot0 + PowerManager::SLOT_SIZE + 1, dupbuf + slot0 + 1, 6);
  const uint32_t dupcrc = PowerManager::crc32(dupbuf, sizeof(dupbuf) - 4);
  for (int i = 0; i < 4; ++i) dupbuf[sizeof(dupbuf) - 4 + i] = static_cast<uint8_t>(dupcrc >> (8 * i));
  assert(!PowerManager::deserialize(dupbuf, sizeof(dupbuf), out));

  // CRC-32 check value for "123456789".
  const uint8_t check[9] = {'1', '2', '3', '4', '5', '6', '7', '8', '9'};
  assert(PowerManager::crc32(check, 9) == 0xCBF43926u);
}

void testPowerWakeupAndStateMachine() {
  assert(PowerManager::decodeWakeupCause(0) == WakeupCause::POWER_ON);
  assert(PowerManager::decodeWakeupCause(4) == WakeupCause::TIMER);
  assert(PowerManager::decodeWakeupCause(2) == WakeupCause::KEY_BUTTON);
  assert(PowerManager::decodeWakeupCause(3) == WakeupCause::KEY_BUTTON);
  assert(PowerManager::decodeWakeupCause(7) == WakeupCause::OTHER);

  assert(!PowerManager::stateSurvives(WakeupCause::POWER_ON));
  assert(PowerManager::stateSurvives(WakeupCause::TIMER));
  assert(PowerManager::stateSurvives(WakeupCause::KEY_BUTTON));
  assert(!PowerManager::stateSurvives(WakeupCause::OTHER));
  assert(PowerManager::needsScan(WakeupCause::TIMER));
  assert(PowerManager::needsScan(WakeupCause::POWER_ON));
  assert(!PowerManager::needsScan(WakeupCause::KEY_BUTTON));

  // Restore ignores valid bytes after a power-on and accepts them after a timer wakeup.
  PersistentState s;
  s.boot_count = 5;
  s.registry.upsert(makeReading(3, 18.0f, 40, 70));
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  PersistentState r;
  assert(!PowerManager::restore(WakeupCause::POWER_ON, buf, sizeof(buf), r));
  assert(r.boot_count == 0 && r.registry.activeCount() == 0);
  assert(PowerManager::restore(WakeupCause::TIMER, buf, sizeof(buf), r));
  assert(r.boot_count == 5 && r.registry.activeCount() == 1);
  buf[10] ^= 1;
  assert(!PowerManager::restore(WakeupCause::KEY_BUTTON, buf, sizeof(buf), r));

  // Timer wakeup: RESTORE -> SCAN -> RENDER -> SAVE -> SLEEP, SLEEP is final.
  PowerCycle timer;
  assert(timer.phase() == PowerPhase::BOOT);
  timer.begin(WakeupCause::TIMER);
  assert(timer.phase() == PowerPhase::RESTORE);
  assert(timer.advance() == PowerPhase::SCAN);
  assert(timer.advance() == PowerPhase::RENDER);
  assert(timer.advance() == PowerPhase::SAVE);
  assert(timer.advance() == PowerPhase::SLEEP);
  assert(timer.advance() == PowerPhase::SLEEP);

  // KEY wakeup skips the scan.
  PowerCycle key;
  key.begin(WakeupCause::KEY_BUTTON);
  assert(key.advance() == PowerPhase::RENDER);

  // Timestamps from a reset clock (in the future of now) make restored state inconsistent.
  PersistentState ts;
  ts.last_scan_ms = 5000;
  ts.registry.upsert(makeReading(1, 20.0f, 50, 50)); // updated_ms = 12345
  assert(PowerManager::timestampsConsistent(ts, 20000));
  assert(PowerManager::timestampsConsistent(ts, 12345));
  assert(!PowerManager::timestampsConsistent(ts, 12344));
  ts.last_scan_ms = 30000;
  assert(!PowerManager::timestampsConsistent(ts, 20000));

  // The controller accepts a restored mode and ignores an out-of-range value.
  DisplayModeController ctrl;
  ctrl.setMode(DisplayMode::SYSTEM_DIAGNOSTICS);
  assert(ctrl.mode() == DisplayMode::SYSTEM_DIAGNOSTICS);
  ctrl.setMode(static_cast<DisplayMode>(9));
  assert(ctrl.mode() == DisplayMode::SYSTEM_DIAGNOSTICS);
}

} // namespace

int main() {
  testSwitchBotManufacturerPositiveTemp();
  testSwitchBotManufacturerNegativeTemp();
  testSwitchBotInvalidPacketsRejected();
  testSwitchBotServiceBatteryAndRegistry();
  testSt7305FramebufferPixelMapping();
  testShtc3Codec();
  testBatteryMonitorDecoding();
  testDashboardViewRendering();
  testPcf85063BcdCodec();
  testPcf85063InvalidValues();
  testDashboardHeaderClock();
  testSwitchBotStaleDetection();
  testDashboardStaleIndicator();
  testButtonDebounceAndModeTransitions();
  testDisplayModesRenderWithoutClipping();
  testSwitchBotPacketCounter();
  testPowerSleepTimerCalculation();
  testPowerStateSerialization();
  testPowerWakeupAndStateMachine();
  printf("All 19 firmware logic test suites passed.\n");
  return 0;
}
