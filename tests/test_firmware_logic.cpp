#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

#include "../rlcd_panel/battery_monitor.h"
#include "../rlcd_panel/board_pins.h"
#include "../rlcd_panel/dashboard_view.h"
#include "../rlcd_panel/pcf85063_rtc.h"
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
  printf("All 13 firmware logic test suites passed.\n");
  return 0;
}
