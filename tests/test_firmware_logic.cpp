#include <assert.h>
#include <algorithm>
#include <initializer_list>
#include <math.h>
#include <stdio.h>
#include <string.h>
#include <string>
#include <vector>

#include "../rlcd_panel/audio_alert.h"
#include "../rlcd_panel/audio_loopback.h"
#include "../rlcd_panel/audio_spectrum.h"
#include "../rlcd_panel/battery_monitor.h"
#include "../rlcd_panel/board_pins.h"
#include "../rlcd_panel/bthome_ble.h"
#include "../rlcd_panel/button_handler.h"
#include "../rlcd_panel/clap_detector.h"
#include "../rlcd_panel/climate_math.h"
#include "../rlcd_panel/dashboard_view.h"
#include "../rlcd_panel/device_config.h"
#include "../rlcd_panel/es7210_mic.h"
#include "../rlcd_panel/ntp_sync.h"
#include "../rlcd_panel/pcf85063_rtc.h"
#include "../rlcd_panel/power_manager.h"
#include "../rlcd_panel/sd_logger.h"
#include "../rlcd_panel/serial_console.h"
#include "../rlcd_panel/shtc3_sensor.h"
#include "../rlcd_panel/sparkline.h"
#include "../rlcd_panel/st7305_framebuffer.h"
#include "../rlcd_panel/switchbot_ble.h"
#include "../tools/preview_scenes.h"

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

const uint8_t BTHOME_MAC[6] = {0xA4, 0xC1, 0x38, 0x11, 0x22, 0x33};

void testBtHomeValidPackets() {
  // Device info 0x40 (v2, unencrypted), battery 87 %, temperature 21.55 C, humidity 45.67 %.
  const uint8_t pkt[] = {0x40, 0x01, 87, 0x02, 0x6B, 0x08, 0x03, 0xD7, 0x11};
  SwitchBotReading r;
  assert(BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, BTHOME_MAC, pkt, sizeof(pkt), r));
  assert(r.valid);
  assert(approxEqual(r.temperature_c, 21.55f, 0.001f));
  assert(r.humidity_pct == 46);
  assert(r.battery_pct == 87);
  assert(memcmp(r.mac, BTHOME_MAC, 6) == 0);

  // Without battery: -1 so the registry keeps the previous value.
  const uint8_t no_batt[] = {0x40, 0x02, 0x4B, 0x08, 0x03, 0x97, 0x11};
  assert(BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, BTHOME_MAC, no_batt, sizeof(no_batt), r));
  assert(r.battery_pct == -1);

  // 1 % humidity object 0x2E.
  const uint8_t h8[] = {0x40, 0x02, 0xD0, 0x07, 0x2E, 55};
  assert(BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, BTHOME_MAC, h8, sizeof(h8), r));
  assert(approxEqual(r.temperature_c, 20.0f, 0.001f));
  assert(r.humidity_pct == 55);

  // Known object (packet id 0x00) before the values is skipped.
  const uint8_t with_id[] = {0x40, 0x00, 0x09, 0x02, 0xD0, 0x07, 0x03, 0x10, 0x27};
  assert(BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, BTHOME_MAC, with_id, sizeof(with_id), r));
  assert(r.humidity_pct == 100);
}

void testBtHomeNegativeTemperature() {
  // -5.25 C = -525 = 0xFDF3, humidity 80.00 %.
  const uint8_t pkt[] = {0x40, 0x02, 0xF3, 0xFD, 0x03, 0x40, 0x1F};
  SwitchBotReading r;
  assert(BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, BTHOME_MAC, pkt, sizeof(pkt), r));
  assert(approxEqual(r.temperature_c, -5.25f, 0.001f));
  assert(r.humidity_pct == 80);
}

void testBtHomeInvalidPackets() {
  SwitchBotReading r;
  const uint8_t good[] = {0x40, 0x02, 0xD0, 0x07, 0x03, 0x10, 0x27};
  assert(!BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, BTHOME_MAC, nullptr, 7, r));
  assert(!BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, nullptr, good, sizeof(good), r));
  assert(!BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, BTHOME_MAC, good, 0, r));
  assert(!BtHomeBleParser::parseServiceData(0xFD3D, BTHOME_MAC, good, sizeof(good), r));
  assert(!r.valid);

  // Truncated at every length below the full packet.
  for (size_t len = 0; len < sizeof(good); ++len) {
    assert(!BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, BTHOME_MAC, good, len, r));
  }

  // Encrypted flag (bit 0), also with otherwise valid objects.
  const uint8_t encrypted[] = {0x41, 0x02, 0xD0, 0x07, 0x03, 0x10, 0x27};
  assert(!BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, BTHOME_MAC, encrypted, sizeof(encrypted), r));
  assert(!r.valid);

  // Wrong BTHome version (v1 = 0x20, v3 = 0x60).
  const uint8_t v1[] = {0x20, 0x02, 0xD0, 0x07, 0x03, 0x10, 0x27};
  const uint8_t v3[] = {0x60, 0x02, 0xD0, 0x07, 0x03, 0x10, 0x27};
  assert(!BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, BTHOME_MAC, v1, sizeof(v1), r));
  assert(!BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, BTHOME_MAC, v3, sizeof(v3), r));

  // Humidity above 100 % is rejected, so the reading lacks humidity.
  const uint8_t bad_rh[] = {0x40, 0x02, 0xD0, 0x07, 0x03, 0x11, 0x27};
  assert(!BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, BTHOME_MAC, bad_rh, sizeof(bad_rh), r));

  // Temperature outside -50 to 100 C (0x7FFF = 327.67 C) is rejected.
  const uint8_t hot[] = {0x40, 0x02, 0xFF, 0x7F, 0x03, 0x10, 0x27};
  assert(!BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, BTHOME_MAC, hot, sizeof(hot), r));

  // Battery above 100 % is ignored. Humidity object 0x2E above 100 % is rejected.
  const uint8_t bad_batt[] = {0x40, 0x01, 101, 0x02, 0xD0, 0x07, 0x03, 0x10, 0x27};
  assert(BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, BTHOME_MAC, bad_batt, sizeof(bad_batt), r));
  assert(r.battery_pct == -1);
  const uint8_t bad_rh8[] = {0x40, 0x02, 0xD0, 0x07, 0x2E, 101};
  assert(!BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, BTHOME_MAC, bad_rh8, sizeof(bad_rh8), r));

  // Temperature only.
  const uint8_t temp_only[] = {0x40, 0x02, 0xD0, 0x07};
  assert(!BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, BTHOME_MAC, temp_only, sizeof(temp_only), r));
}

void testBtHomeUnknownObjectIds() {
  SwitchBotReading r;
  // Known values first, then an unknown object ID 0xFE: the parse stops and keeps the decoded values.
  const uint8_t tail_unknown[] = {0x40, 0x02, 0xD0, 0x07, 0x03, 0x10, 0x27, 0xFE, 0x01, 0x02};
  assert(BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, BTHOME_MAC, tail_unknown, sizeof(tail_unknown), r));
  assert(approxEqual(r.temperature_c, 20.0f, 0.001f));
  assert(r.humidity_pct == 100);

  // Unknown object ID before the values: the following bytes cannot be located, so the packet is skipped.
  const uint8_t head_unknown[] = {0x40, 0xFE, 0x01, 0x02, 0xD0, 0x07, 0x03, 0x10, 0x27};
  assert(!BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, BTHOME_MAC, head_unknown, sizeof(head_unknown), r));

  // Known object without use here (illuminance 0x05, 3 bytes) is stepped over.
  const uint8_t lux[] = {0x40, 0x05, 0x01, 0x02, 0x03, 0x02, 0xD0, 0x07, 0x03, 0x10, 0x27};
  assert(BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, BTHOME_MAC, lux, sizeof(lux), r));
  assert(approxEqual(r.temperature_c, 20.0f, 0.001f));
}

void testBtHomeCombinedRegistry() {
  SwitchBotRegistry reg;

  SwitchBotReading sb1;
  sb1.valid = true;
  sb1.mac[5] = 1;
  sb1.temperature_c = 18.0f;
  sb1.humidity_pct = 40;
  sb1.updated_ms = 1000;
  assert(reg.upsert(sb1));

  const uint8_t pkt[] = {0x40, 0x01, 77, 0x02, 0x4B, 0x08, 0x03, 0x97, 0x11};
  SwitchBotReading bt;
  assert(BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, BTHOME_MAC, pkt, sizeof(pkt), bt));
  bt.updated_ms = 2000;
  assert(reg.upsert(bt));

  SwitchBotReading sb2;
  sb2.valid = true;
  sb2.mac[5] = 2;
  sb2.temperature_c = 19.0f;
  sb2.humidity_pct = 41;
  sb2.updated_ms = 3000;
  assert(reg.upsert(sb2));
  assert(reg.activeCount() == 3);
  assert(memcmp(reg.slot(1).mac, BTHOME_MAC, 6) == 0);
  assert(reg.slot(1).battery_pct == 77);

  // A new BTHome packet without battery updates the same slot and keeps the battery.
  const uint8_t next[] = {0x40, 0x02, 0x10, 0x09, 0x03, 0x10, 0x0E};
  assert(BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, BTHOME_MAC, next, sizeof(next), bt));
  bt.updated_ms = 4000;
  assert(reg.upsert(bt));
  assert(reg.activeCount() == 3);
  assert(approxEqual(reg.slot(1).temperature_c, 23.2f, 0.001f));
  assert(reg.slot(1).battery_pct == 77);
  assert(reg.slot(1).packet_count == 2);

  // Same 30 min stale rule for both sensor types.
  const uint32_t timeout = SENSOR_STALE_TIMEOUT_MS;
  assert(!reg.slot(1).isStale(4000 + timeout));
  assert(reg.slot(1).isStale(4000 + timeout + 1));
  assert(!reg.slot(0).isStale(1000 + timeout));
  assert(reg.slot(0).isStale(1000 + timeout + 1));
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

void testBatteryMonitorUsbPlugged() {
  // ADC mV for 3.3 V, 3.8 V, 4.15 V, 4.2 V: 1100, 1267, 1383, 1400
  const uint32_t adc_mv[] = {1100, 1267, 1383, 1400};
  for (uint32_t mv : adc_mv) {
    // Plugged to a host: USB power at each cell voltage, the voltage stays unchanged.
    const BatteryReading plugged = BatteryMonitor::decodeMillivolts(mv, true);
    assert(plugged.valid);
    assert(plugged.usb_powered);
    assert(plugged.voltage_v < 4.25f);
    assert(plugged.percentage >= 0);
    // Unplugged: the cell voltage below 4.25 V means battery power.
    assert(!BatteryMonitor::decodeMillivolts(mv, false).usb_powered);
    assert(!BatteryMonitor::decodeMillivolts(mv).usb_powered);
  }

  // Unplugged with 4.25 V or more (charger, floating pin): USB power.
  assert(BatteryMonitor::decodeMillivolts(1417, false).usb_powered); // 4.251 V
  assert(BatteryMonitor::decodeMillivolts(1450, false).usb_powered); // 4.35 V
  assert(!BatteryMonitor::decodeMillivolts(1416, false).usb_powered); // 4.248 V
  // Plugged with 4.25 V or more stays USB power.
  assert(BatteryMonitor::decodeMillivolts(1450, true).usb_powered);

  // The plugged flag ends the low battery alert and the empty protection of the cell.
  const BatteryReading low_plugged = BatteryMonitor::decodeMillivolts(1050, true); // 3.15 V
  assert(low_plugged.usb_powered);
  assert(!BatteryMonitor::updateLowBattery(low_plugged, true));
  assert(!BatteryMonitor::updateEmptyProtection(low_plugged, true));
  const BatteryReading low_unplugged = BatteryMonitor::decodeMillivolts(1050, false);
  assert(BatteryMonitor::updateEmptyProtection(low_unplugged, false));
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

void testClimateMathReferencePoints() {
  const ClimateDerived ref = ClimateMath::compute(20.0f, 50.0f);
  assert(ref.valid);
  assert(approxEqual(ref.dew_point_c, 9.3f, 0.1f));
  assert(approxEqual(ref.vpd_kpa, 1.17f, 0.01f));

  // 100 %RH: dew point equals the temperature, no deficit
  const ClimateDerived sat = ClimateMath::compute(20.0f, 100.0f);
  assert(sat.valid);
  assert(approxEqual(sat.dew_point_c, 20.0f, 0.05f));
  assert(approxEqual(sat.vpd_kpa, 0.0f, 0.001f));

  // 0 %RH: the dew point does not exist
  assert(!ClimateMath::compute(20.0f, 0.0f).valid);

  const ClimateDerived cold = ClimateMath::compute(-10.0f, 50.0f);
  assert(cold.valid);
  assert(approxEqual(cold.dew_point_c, -18.5f, 0.2f));
  assert(approxEqual(cold.vpd_kpa, 0.14f, 0.01f));
  const ClimateDerived colder = ClimateMath::compute(-20.0f, 50.0f);
  assert(colder.valid);
  assert(approxEqual(colder.dew_point_c, -27.8f, 0.2f));
  assert(approxEqual(colder.vpd_kpa, 0.063f, 0.005f));
  assert(colder.dew_point_c < -20.0f);

  // The dew point never exceeds the temperature
  assert(ClimateMath::compute(30.0f, 80.0f).dew_point_c < 30.0f);
}

void testClimateMathInvalidInput() {
  const float nan_v = NAN;
  assert(!ClimateMath::compute(nan_v, 50.0f).valid);
  assert(!ClimateMath::compute(20.0f, nan_v).valid);
  assert(!ClimateMath::compute(INFINITY, 50.0f).valid);
  assert(!ClimateMath::compute(20.0f, 100.1f).valid);
  assert(!ClimateMath::compute(20.0f, -1.0f).valid);
  assert(!ClimateMath::compute(-60.0f, 50.0f).valid);
  assert(!ClimateMath::compute(80.0f, 50.0f).valid);

  char buf[ClimateMath::LINE_MAX_CHARS + 1];
  ClimateMath::format(buf, sizeof(buf), true, 20.0f, 50.0f);
  assert(strcmp(buf, "DP 9.3C VPD 1.2K") == 0);
  ClimateMath::format(buf, sizeof(buf), false, 20.0f, 50.0f); // invalid or stale
  assert(strcmp(buf, "DP -- VPD --") == 0);
  ClimateMath::format(buf, sizeof(buf), true, nan_v, 50.0f);
  assert(strcmp(buf, "DP -- VPD --") == 0);
  ClimateMath::format(buf, sizeof(buf), true, -45.0f, 100.0f); // longest text
  assert(strlen(buf) <= ClimateMath::LINE_MAX_CHARS);
  ClimateMath::format(nullptr, 0, true, 20.0f, 50.0f); // no crash
}

void testDashboardClimateLine() {
  Shtc3Reading onboard;
  onboard.valid = true;
  onboard.temperature_c = 20.0f;
  onboard.humidity_pct = 50.0f;
  BatteryReading battery;
  battery.valid = true;
  battery.voltage_v = 3.85f;
  battery.percentage = 65;
  const uint32_t now_ms = 100000;
  SwitchBotReading slots[MAX_SWITCHBOT_SENSORS];
  for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
    slots[i].valid = true;
    slots[i].temperature_c = 20.0f;
    slots[i].humidity_pct = 50;
    slots[i].battery_pct = 90;
    slots[i].updated_ms = now_ms;
  }
  ClimateHistory history;
  for (size_t s = 0; s < ClimateHistory::SLOT_COUNT; ++s) {
    for (int h = 0; h < 24; ++h) {
      history.slots[s].pushPercent(40.0f + static_cast<float>(h));
    }
  }

  St7305Framebuffer fb;
  DashboardView::render(fb, onboard, battery, slots, MAX_SWITCHBOT_SENSORS, nullptr, now_ms, &history);

  const uint16_t tile_x[4] = {6, 204, 6, 204};
  const uint16_t tile_y[4] = {36, 36, 168, 168};
  const uint16_t line_h = 7;
  for (size_t t = 0; t < 4; ++t) {
    const uint16_t ly = tile_y[t] + DashboardView::CLIMATE_LINE_Y_OFFSET;
    // The line sits below the sparkline box and above the battery line, inside the tile
    assert(ly >= tile_y[t] + DashboardView::SPARKLINE_Y_OFFSET + DashboardView::SPARKLINE_H);
    assert(ly + line_h <= tile_y[t] + 96);
    size_t set = 0;
    for (uint16_t y = ly; y < ly + line_h; ++y) {
      for (uint16_t x = tile_x[t] + 1; x < tile_x[t] + 189; ++x) {
        if (fb.getPixel(x, y)) {
          ++set;
          // The text starts at x + 12 and ends within the tile border
          assert(x >= tile_x[t] + 12);
        }
      }
    }
    assert(set > 0);
    // The border column stays clear of text
    for (uint16_t y = ly; y < ly + line_h; ++y) {
      assert(fb.getPixel(tile_x[t] + 189, y)); // right border is a line, not text overflow
    }
  }

  // The text matches the helper: a render with the line text drawn alone gives the same pixels
  St7305Framebuffer ref;
  ref.clear(false);
  char line[ClimateMath::LINE_MAX_CHARS + 1];
  ClimateMath::format(line, sizeof(line), true, 20.0f, 50.0f);
  ref.drawText(6 + 12, 36 + DashboardView::CLIMATE_LINE_Y_OFFSET, line, 1, true);
  for (uint16_t y = 0; y < line_h; ++y) {
    for (uint16_t x = 0; x < 120; ++x) {
      assert(
          fb.getPixel(18 + x, 36 + DashboardView::CLIMATE_LINE_Y_OFFSET + y) ==
          ref.getPixel(18 + x, 36 + DashboardView::CLIMATE_LINE_Y_OFFSET + y));
    }
  }

  // Stale sensor 1 and NaN humidity on sensor 2 show "DP -- VPD --"; the onboard tile is unchanged
  slots[0].updated_ms = now_ms - SENSOR_STALE_TIMEOUT_MS - 1;
  St7305Framebuffer dash;
  DashboardView::render(dash, onboard, battery, slots, MAX_SWITCHBOT_SENSORS, nullptr, now_ms, &history);
  St7305Framebuffer dashes;
  dashes.clear(false);
  dashes.drawText(204 + 12, 36 + DashboardView::CLIMATE_LINE_Y_OFFSET, "DP -- VPD --", 1, true);
  for (uint16_t y = 0; y < line_h; ++y) {
    for (uint16_t x = 0; x < 100; ++x) {
      const uint16_t py = 36 + DashboardView::CLIMATE_LINE_Y_OFFSET + y;
      assert(dash.getPixel(216 + x, py) == dashes.getPixel(216 + x, py));
      assert(dash.getPixel(18 + x, py) == fb.getPixel(18 + x, py));
    }
  }

  // An invalid tile ("NO SIGNAL") shows the dashes, too
  St7305Framebuffer none;
  DashboardView::render(none, onboard, battery, nullptr, 0, nullptr, now_ms);
  for (uint16_t y = 0; y < line_h; ++y) {
    for (uint16_t x = 0; x < 100; ++x) {
      const uint16_t py = 36 + DashboardView::CLIMATE_LINE_Y_OFFSET + y;
      assert(none.getPixel(216 + x, py) == dashes.getPixel(216 + x, py));
    }
  }
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

  // The stale indicator stays inside the tile border, in the title band above the divider line.
  // The dew point line below the sparkline also changes ("DP --" for stale data).
  const uint16_t dp_y0 = 168 + DashboardView::CLIMATE_LINE_Y_OFFSET;
  size_t outside = 0;
  for (uint16_t y = 0; y < 300; ++y) {
    for (uint16_t x = 0; x < 400; ++x) {
      if (fresh.getPixel(x, y) != mixed.getPixel(x, y) &&
          (x <= 6 || x >= 195 || y <= 168 || y >= 168 + 24) &&
          !(y >= dp_y0 && y < dp_y0 + 7U && x > 6 && x < 195)) {
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

  // Each debounced press cycles DASHBOARD -> SENSOR_STATS -> SYSTEM_DIAGNOSTICS -> AUDIO_SPECTRUM
  // -> DASHBOARD.
  DisplayModeController controller;
  assert(controller.mode() == DisplayMode::DASHBOARD);
  uint32_t t = 0;
  const DisplayMode expected[5] = {
      DisplayMode::SENSOR_STATS,
      DisplayMode::SYSTEM_DIAGNOSTICS,
      DisplayMode::AUDIO_SPECTRUM,
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

SwitchBotReading makeReading(uint8_t id, float temp, uint8_t hum, int8_t batt);

void testBleSignalStrength() {
  // clampRssi keeps dBm in the int8_t range.
  assert(SwitchBotBleParser::clampRssi(-30) == -30);
  assert(SwitchBotBleParser::clampRssi(-100) == -100);
  assert(SwitchBotBleParser::clampRssi(-500) == -128);
  assert(SwitchBotBleParser::clampRssi(500) == 127);

  // A valid packet updates the RSSI of its slot; a rejected packet leaves it.
  SwitchBotRegistry registry;
  SwitchBotReading a = makeReading(1, 20.0f, 50, 80);
  a.rssi = -60;
  assert(registry.upsert(a));
  assert(registry.slot(0).rssi == -60);
  a.rssi = -75;
  assert(registry.upsert(a));
  assert(registry.slot(0).rssi == -75);
  SwitchBotReading rejected = a;
  rejected.valid = false;
  rejected.rssi = -10;
  assert(!registry.upsert(rejected));
  assert(registry.slot(0).rssi == -75);

  // Text: dBm value, dashes for a stale or empty slot.
  char text[24];
  SwitchBotReading r;
  r.valid = true;
  r.updated_ms = 1000;
  r.rssi = -30;
  DashboardView::formatRssi(text, sizeof(text), r, 2000);
  assert(strcmp(text, "RSSI -30 DBM") == 0);
  r.rssi = -100;
  DashboardView::formatRssi(text, sizeof(text), r, 2000);
  assert(strcmp(text, "RSSI -100 DBM") == 0);
  DashboardView::formatRssi(text, sizeof(text), r, 1000 + SENSOR_STALE_TIMEOUT_MS + 1);
  assert(strcmp(text, "RSSI --") == 0);
  DashboardView::formatRssi(text, sizeof(text), SwitchBotReading{}, 2000);
  assert(strcmp(text, "RSSI --") == 0);

  // Rendered line: ink in its rows, none between the first and the second line of a block.
  Shtc3Reading onboard;
  BatteryReading battery;
  SystemInfo info;
  SwitchBotReading slots[MAX_SWITCHBOT_SENSORS];
  for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
    slots[i] = makeReading(static_cast<uint8_t>(i + 1), 20.0f, 50, 80);
    slots[i].rssi = -100;
    slots[i].updated_ms = 1000;
  }
  slots[2].updated_ms = 0; // stale at now_ms below
  const uint32_t now_ms = SENSOR_STALE_TIMEOUT_MS + 5;
  slots[0].updated_ms = slots[1].updated_ms = now_ms - 1000;
  St7305Framebuffer fb;
  DashboardView::renderMode(
      fb, DisplayMode::SENSOR_STATS, onboard, battery, slots, MAX_SWITCHBOT_SENSORS, info, "88:88", now_ms);
  size_t width_fresh = 0;
  for (uint16_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
    const uint16_t y0 = static_cast<uint16_t>(34 + i * DashboardView::SENSOR_BLOCK_PITCH);
    size_t ink = 0;
    uint16_t max_x = 0;
    for (uint16_t y = y0; y < y0 + 14; ++y) {
      for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
        if (fb.getPixel(x, y)) {
          ++ink;
          max_x = x > max_x ? x : max_x;
        }
      }
    }
    assert(ink > 20);
    assert(max_x < St7305Framebuffer::WIDTH - 1);
    if (i == 0) {
      width_fresh = max_x;
    }
    if (i == 2) {
      assert(max_x < width_fresh); // "RSSI --" is shorter than "RSSI -100 DBM"
    }
    for (uint16_t y = y0 + 14; y < y0 + 18; ++y) {
      for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
        assert(!fb.getPixel(x, y));
      }
    }
  }

  // RTC round trip keeps the RSSI and gives the previous state version a clean start.
  PersistentState s;
  s.registry.upsert(a);
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  PersistentState back;
  assert(PowerManager::deserialize(buf, sizeof(buf), back));
  assert(back.registry.slot(0).rssi == -75);
  assert(PowerManager::STATE_VERSION == 26);
  uint8_t old[PowerManager::SERIALIZED_SIZE];
  memcpy(old, buf, sizeof(buf));
  old[4] = 18;
  const uint32_t crc = PowerManager::crc32(old, sizeof(old) - 4);
  for (int k = 0; k < 4; ++k) old[sizeof(old) - 4 + k] = static_cast<uint8_t>(crc >> (8 * k));
  PersistentState clean;
  assert(!PowerManager::deserialize(old, sizeof(old), clean));
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
      DisplayMode::DASHBOARD,
      DisplayMode::SENSOR_STATS,
      DisplayMode::SYSTEM_DIAGNOSTICS,
      DisplayMode::AUDIO_SPECTRUM};
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

  // The four views differ, and empty inputs render without crashing.
  assert(memcmp(frames[0].data(), frames[1].data(), St7305Framebuffer::BYTE_COUNT) != 0);
  assert(memcmp(frames[1].data(), frames[2].data(), St7305Framebuffer::BYTE_COUNT) != 0);
  assert(memcmp(frames[2].data(), frames[3].data(), St7305Framebuffer::BYTE_COUNT) != 0);
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
      {6, 16},                      // battery flags (unknown bit)
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
  ctrl.setMode(DisplayMode::AUDIO_SPECTRUM);
  assert(ctrl.mode() == DisplayMode::AUDIO_SPECTRUM);
  ctrl.setMode(static_cast<DisplayMode>(4));
  assert(ctrl.mode() == DisplayMode::AUDIO_SPECTRUM);
  ctrl.setMode(static_cast<DisplayMode>(9));
  assert(ctrl.mode() == DisplayMode::AUDIO_SPECTRUM);
}

} // namespace

void testLowBatteryHysteresis() {
  auto reading = [](int8_t pct, float v, bool usb = false) {
    BatteryReading r;
    r.valid = true;
    r.percentage = pct;
    r.voltage_v = v;
    r.usb_powered = usb;
    return r;
  };

  // Triggers at <= 15 % or < 3.40 V
  assert(!BatteryMonitor::updateLowBattery(reading(16, 3.60f), false));
  assert(BatteryMonitor::updateLowBattery(reading(15, 3.60f), false));
  assert(BatteryMonitor::updateLowBattery(reading(40, 3.39f), false));
  assert(!BatteryMonitor::updateLowBattery(reading(40, 3.40f), false));

  // Stays latched between the thresholds, clears only at >= 20 % and >= 3.50 V
  assert(BatteryMonitor::updateLowBattery(reading(18, 3.45f), true));
  assert(BatteryMonitor::updateLowBattery(reading(19, 3.60f), true));
  assert(BatteryMonitor::updateLowBattery(reading(25, 3.49f), true));
  assert(!BatteryMonitor::updateLowBattery(reading(20, 3.50f), true));

  // Full sequence without oscillation around 15 %
  bool low = false;
  const int8_t pcts[] = {30, 16, 15, 17, 19, 16, 20, 17};
  const bool expected[] = {false, false, true, true, true, true, false, false};
  for (size_t i = 0; i < sizeof(pcts); ++i) {
    low = BatteryMonitor::updateLowBattery(reading(pcts[i], 3.55f), low);
    assert(low == expected[i]);
  }

  // USB power and invalid readings suppress the alert, also when latched
  assert(!BatteryMonitor::updateLowBattery(reading(5, 3.30f, true), true));
  assert(!BatteryMonitor::updateLowBattery(reading(-1, 3.30f), true));
  BatteryReading invalid;
  assert(!BatteryMonitor::updateLowBattery(invalid, true));

  // The alert re-arms at the first battery reading after USB power ends
  low = BatteryMonitor::updateLowBattery(reading(10, 3.30f, true), low);
  assert(!low);
  low = BatteryMonitor::updateLowBattery(reading(10, 3.30f), low);
  assert(low);
}

void testLowBatteryBanner() {
  Shtc3Reading onboard;
  BatteryReading battery;
  battery.valid = true;
  battery.voltage_v = 3.30f;
  battery.percentage = 8;

  St7305Framebuffer normal;
  St7305Framebuffer banner;
  DashboardView::render(normal, onboard, battery, nullptr, 0, "12:34", 0);
  battery.low_battery = true;
  DashboardView::render(banner, onboard, battery, nullptr, 0, "12:34", 0);

  // Banner is the inverse of the normal header: top-left pixel white instead of black
  assert(normal.getPixel(5, 5));
  assert(!banner.getPixel(5, 5));

  // Text is centered and fully inside the width: margins left and right, no clipping
  const uint16_t text_w = static_cast<uint16_t>(strlen(DashboardView::LOW_BATTERY_TEXT) * 12 - 2);
  const uint16_t left = (RLCD_WIDTH - text_w) / 2;
  assert(left >= 4 && left + text_w + 4 <= RLCD_WIDTH);
  uint16_t min_x = St7305Framebuffer::WIDTH, max_x = 0;
  size_t text_pixels = 0;
  for (uint16_t y = 4; y < DashboardView::HEADER_HEIGHT - 4; ++y) {
    for (uint16_t x = 4; x < St7305Framebuffer::WIDTH - 4; ++x) {
      if (banner.getPixel(x, y)) {
        ++text_pixels;
        if (x < min_x) min_x = x;
        if (x > max_x) max_x = x;
      }
    }
  }
  assert(text_pixels > 100);
  assert(min_x >= left && max_x < left + text_w);
  assert(max_x - min_x + 1 >= text_w - 12);

  // Frame lines cover the full header edge
  for (uint16_t x = 0; x < RLCD_WIDTH; ++x) {
    assert(banner.getPixel(x, 0));
    assert(banner.getPixel(x, DashboardView::HEADER_HEIGHT - 1));
  }

  // Tiles below the header are unchanged
  for (uint16_t y = DashboardView::HEADER_HEIGHT; y < St7305Framebuffer::HEIGHT; ++y) {
    for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
      assert(normal.getPixel(x, y) == banner.getPixel(x, y));
    }
  }

  // All views show the banner without writing outside the framebuffer
  SystemInfo info;
  SwitchBotReading slots[MAX_SWITCHBOT_SENSORS];
  for (int m = 0; m < DISPLAY_MODE_COUNT; ++m) {
    St7305Framebuffer fb;
    DashboardView::renderMode(
        fb, static_cast<DisplayMode>(m), onboard, battery, slots, MAX_SWITCHBOT_SENSORS, info,
        "88:88", 0);
    assert(!fb.getPixel(5, 5));
  }

  // The flag survives the RTC state round trip
  PersistentState s;
  s.battery = battery;
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  PersistentState r;
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(r.battery.low_battery);
}

void testHumidityHistoryFifo() {
  HumidityHistory h;
  assert(h.size() == 0);
  assert(h.at(0) == 0);
  for (int i = 0; i < 24; ++i) {
    h.push(static_cast<int16_t>(i));
    assert(h.size() == static_cast<size_t>(i + 1));
  }
  assert(h.at(0) == 0 && h.at(23) == 23);

  // Samples 24..29 push out 0..5: the oldest leaves first, order stays chronological
  for (int i = 24; i < 30; ++i) {
    h.push(static_cast<int16_t>(i));
  }
  assert(h.size() == 24);
  assert(h.at(0) == 6);
  assert(h.at(23) == 29);
  for (size_t i = 1; i < 24; ++i) {
    assert(h.at(i) == h.at(i - 1) + 1);
  }
  assert(h.at(24) == 0); // out of range

  // Many wraps keep the last 24 samples
  for (int i = 30; i < 1000; ++i) {
    h.push(static_cast<int16_t>(i));
  }
  assert(h.at(0) == 976 && h.at(23) == 999);

  // Encoding: 0.1 %RH steps, rounding, clamping to 0..100 %RH
  assert(HumidityHistory::encode(0.0f) == 0);
  assert(HumidityHistory::encode(45.54f) == 455);
  assert(HumidityHistory::encode(100.0f) == 1000);
  assert(HumidityHistory::encode(100.0f) == HumidityHistory::MAX_VALUE);
  assert(HumidityHistory::encode(250.0f) == HumidityHistory::MAX_VALUE);
  assert(HumidityHistory::encode(-5.06f) == HumidityHistory::MIN_VALUE);
  assert(HumidityHistory::encode(NAN) == 0);

  // pushPercent skips NaN, negative and above 100 %RH, and keeps the borders 0 and 100
  HumidityHistory p;
  assert(!p.pushPercent(NAN));
  assert(!p.pushPercent(-0.1f));
  assert(!p.pushPercent(100.1f));
  assert(!p.pushPercent(INFINITY));
  assert(!p.pushPercent(-INFINITY));
  assert(p.size() == 0);
  assert(p.pushPercent(0.0f) && p.pushPercent(100.0f) && p.pushPercent(55.5f));
  assert(p.size() == 3 && p.at(0) == 0 && p.at(1) == 1000 && p.at(2) == 555);
}

void testClimateHistorySampleGate() {
  ClimateHistory c;
  SwitchBotRegistry reg;
  const uint32_t hour = ClimateHistory::SAMPLE_INTERVAL_MS;

  // No valid sensor: nothing recorded, gate stays open
  assert(!c.record(1000, false, 50.0f, reg));
  assert(c.sampleDue(1000));

  assert(c.record(1000, true, 45.0f, reg));
  assert(c.slots[0].size() == 1 && c.slots[0].at(0) == 450);
  assert(c.slots[1].size() == 0);
  assert(!c.record(1000 + hour - 1, true, 46.0f, reg)); // not due
  assert(c.slots[0].size() == 1);
  assert(c.record(1000 + hour, true, 46.0f, reg));
  assert(c.slots[0].size() == 2);

  // NaN, negative and above 100 %RH are ignored; the gate stays open
  ClimateHistory q;
  assert(!q.record(5000, true, NAN, reg));
  assert(!q.record(5000, true, -3.0f, reg));
  assert(!q.record(5000, true, 140.0f, reg));
  assert(q.slots[0].size() == 0 && q.sampleDue(5000));
  // A stale SwitchBot slot is not recorded; a fresh one is recorded without the onboard sensor
  SwitchBotRegistry stale_reg;
  SwitchBotReading sb;
  sb.valid = true;
  sb.temperature_c = 18.0f;
  sb.humidity_pct = 62;
  sb.mac[5] = 1;
  sb.updated_ms = 1000;
  stale_reg.upsert(sb);
  const uint32_t late = 1000 + 3600UL * 1000UL;
  assert(!q.record(late, false, 50.0f, stale_reg)); // slot is stale at `late`
  assert(q.slots[1].size() == 0);
  assert(q.record(1000 + 1000, false, 50.0f, stale_reg)); // fresh slot is recorded
  assert(q.slots[1].size() == 1 && q.slots[1].at(0) == 620);
  assert(q.slots[0].size() == 0); // the onboard sensor was not valid
  // The SwitchBot temperature goes to its own history, not to the humidity ring
  assert(q.slots[1].at(0) != 180);
  assert(q.temperatures[1].size() == 1 && q.temperatures[1].at(0) == 180);

  // A wrapped 32-bit clock still counts the interval
  ClimateHistory w;
  assert(w.record(0xFFFFFFFFu - 10, true, 10.0f, reg));
  assert(!w.sampleDue(0xFFFFFFFFu - 10 + 5));
  assert(w.sampleDue(static_cast<uint32_t>(0xFFFFFFFFu - 10 + hour)));
}

void testTemperatureHistoryEncoding() {
  assert(TemperatureHistory::encode(0.0f) == 0);
  assert(TemperatureHistory::encode(21.54f) == 215);
  assert(TemperatureHistory::encode(-15.0f) == -150);
  assert(TemperatureHistory::encode(-0.04f) == 0);
  assert(TemperatureHistory::encode(-0.06f) == -1);
  assert(TemperatureHistory::encode(100.0f) == TemperatureHistory::MAX_VALUE);
  assert(TemperatureHistory::encode(-100.0f) == TemperatureHistory::MIN_VALUE);
  assert(TemperatureHistory::encode(500.0f) == TemperatureHistory::MAX_VALUE);
  assert(TemperatureHistory::encode(-500.0f) == TemperatureHistory::MIN_VALUE);
  assert(TemperatureHistory::encode(NAN) == 0);
  TemperatureHistory t;
  assert(!t.pushCelsius(NAN));
  assert(!t.pushCelsius(100.5f));
  assert(!t.pushCelsius(-100.5f));
  assert(t.size() == 0);
  assert(t.pushCelsius(-100.0f) && t.pushCelsius(100.0f));
  assert(t.size() == 2 && t.at(0) == -1000 && t.at(1) == 1000);
}

void testTemperatureHistoryRange() {
  TemperatureHistory t;
  int16_t lo = 7;
  int16_t hi = 7;
  // Fewer than 2 samples: no range, outputs untouched
  assert(!t.range(lo, hi) && lo == 7 && hi == 7);
  t.pushCelsius(12.3f);
  assert(!t.range(lo, hi) && lo == 7 && hi == 7);
  t.pushCelsius(12.3f);
  assert(t.range(lo, hi) && lo == 123 && hi == 123);

  // 30 samples from -15 degC up to +5 degC: the first 6 drop out of the 24-hour window
  TemperatureHistory w;
  for (int i = 0; i < 30; ++i) {
    w.pushCelsius(-15.0f + 20.0f * static_cast<float>(i) / 29.0f);
  }
  assert(w.size() == 24);
  assert(w.range(lo, hi));
  assert(lo == w.at(0) && hi == w.at(23));
  assert(lo > -150 && hi == 50); // the coldest samples are gone, the warmest stays

  // A cold sample inside the window is the minimum; once it drops out the minimum rises
  TemperatureHistory c;
  c.pushCelsius(-15.0f);
  for (int i = 0; i < 23; ++i) c.pushCelsius(3.0f);
  assert(c.range(lo, hi) && lo == -150 && hi == 30);
  c.pushCelsius(5.0f);
  assert(c.range(lo, hi) && lo == 30 && hi == 50);

  // Humidity range: same rules
  HumidityHistory h;
  int16_t hlo = 0;
  int16_t hhi = 0;
  assert(!h.range(hlo, hhi));
  h.pushPercent(41.0f);
  assert(!h.range(hlo, hhi));
  h.pushPercent(63.4f);
  h.pushPercent(0.0f);
  h.pushPercent(100.0f);
  assert(h.range(hlo, hhi) && hlo == 0 && hhi == 1000);
}

void testTemperatureHistoryRecordGate() {
  SwitchBotRegistry reg;
  const uint32_t hour = ClimateHistory::SAMPLE_INTERVAL_MS;
  ClimateHistory c;
  assert(c.record(1000, true, 45.0f, reg, -4.5f));
  assert(c.temperatures[0].size() == 1 && c.temperatures[0].at(0) == -45);
  assert(!c.record(1000 + hour - 1, true, 46.0f, reg, -4.0f)); // same hourly gate
  assert(c.temperatures[0].size() == 1);
  assert(c.record(1000 + hour, true, 46.0f, reg, -4.0f));
  assert(c.temperatures[0].size() == 2);

  // Invalid onboard reading or NaN temperature: skipped
  ClimateHistory q;
  assert(!q.record(5000, false, 50.0f, reg, 20.0f));
  assert(q.temperatures[0].size() == 0);
  assert(q.record(5000, true, 50.0f, reg, NAN)); // humidity alone still counts
  assert(q.slots[0].size() == 1 && q.temperatures[0].size() == 0);
  assert(!q.record(5000 + hour, true, NAN, reg, 150.0f)); // both out of range
  assert(q.temperatures[0].size() == 0 && q.sampleDue(5000 + hour));

  // Stale SwitchBot slot: skipped; fresh slot: recorded with its temperature
  SwitchBotRegistry stale_reg;
  SwitchBotReading sb;
  sb.valid = true;
  sb.temperature_c = -12.5f;
  sb.humidity_pct = 70;
  sb.mac[5] = 2;
  sb.updated_ms = 1000;
  stale_reg.upsert(sb);
  ClimateHistory s;
  assert(!s.record(1000 + hour, false, 50.0f, stale_reg));
  assert(s.temperatures[1].size() == 0);
  assert(s.record(2000, false, 50.0f, stale_reg));
  assert(s.temperatures[1].size() == 1 && s.temperatures[1].at(0) == -125);
  assert(s.temperatures[0].size() == 0);
}

void testRecordsStatsView() {
  char text[48];
  ClimateHistory h;
  // No history, empty history, one sample: both ranges show "--"
  DashboardView::formatRecords(text, sizeof(text), nullptr, 1);
  assert(strcmp(text, "24H T-- H--") == 0);
  DashboardView::formatRecords(text, sizeof(text), &h, 1);
  assert(strcmp(text, "24H T-- H--") == 0);
  h.temperatures[1].pushCelsius(-15.0f);
  h.slots[1].pushPercent(41.0f);
  DashboardView::formatRecords(text, sizeof(text), &h, 1);
  assert(strcmp(text, "24H T-- H--") == 0);
  // Slot out of range
  DashboardView::formatRecords(text, sizeof(text), &h, ClimateHistory::SLOT_COUNT);
  assert(strcmp(text, "24H T-- H--") == 0);

  // Temperature has 2 samples, humidity only one: the ranges decide independently
  h.temperatures[1].pushCelsius(5.0f);
  DashboardView::formatRecords(text, sizeof(text), &h, 1);
  assert(strcmp(text, "24H T-15.0 TO 5.0 H--") == 0);
  h.slots[1].pushPercent(63.4f);
  DashboardView::formatRecords(text, sizeof(text), &h, 1);
  assert(strcmp(text, "24H T-15.0 TO 5.0 H41-63") == 0);

  // Widest possible text fits the width with the left margin and the 8 px right margin
  ClimateHistory wide;
  wide.temperatures[3].pushCelsius(-100.0f);
  wide.temperatures[3].pushCelsius(-100.0f);
  wide.slots[3].pushPercent(100.0f);
  wide.slots[3].pushPercent(100.0f);
  DashboardView::formatRecords(text, sizeof(text), &wide, 3);
  assert(strcmp(text, "24H T-100.0 TO -100.0 H100-100") == 0);
  assert(10 + strlen(text) * 12 <= RLCD_WIDTH - 8);
  ClimateHistory widest;
  widest.temperatures[3].pushCelsius(-100.0f);
  widest.temperatures[3].pushCelsius(100.0f);
  widest.slots[3].pushPercent(0.0f);
  widest.slots[3].pushPercent(100.0f);
  DashboardView::formatRecords(text, sizeof(text), &widest, 3);
  assert(strcmp(text, "24H T-100.0 TO 100.0 H0-100") == 0);
  assert(10 + strlen(text) * 12 <= RLCD_WIDTH - 8);

  // Rendering: the records line draws pixels and stays inside the screen
  SwitchBotReading slots[MAX_SWITCHBOT_SENSORS];
  for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
    slots[i].valid = true;
    slots[i].mac[5] = static_cast<uint8_t>(i + 1);
  }
  Shtc3Reading onboard;
  BatteryReading battery;
  SystemInfo info;
  St7305Framebuffer with, without;
  DashboardView::renderMode(
      with, DisplayMode::SENSOR_STATS, onboard, battery, slots, MAX_SWITCHBOT_SENSORS, info, "12:34", 0,
      &widest);
  DashboardView::renderMode(
      without, DisplayMode::SENSOR_STATS, onboard, battery, slots, MAX_SWITCHBOT_SENSORS, info, "12:34", 0);
  assert(memcmp(with.data(), without.data(), St7305Framebuffer::BYTE_COUNT) != 0);
  // The records line of S3 (slot 3) sits at y 34 + 2 * 72 + 54 = 232 and is 7 px per glyph row at
  // scale 2 (14 px high). It has ink inside its text width, a blank left margin and a blank tail.
  const uint16_t line_y = 34 + 2 * DashboardView::SENSOR_BLOCK_PITCH + 54;
  const uint16_t text_end = static_cast<uint16_t>(10 + strlen(text) * 12);
  bool ink = false;
  for (uint16_t y = line_y; y < line_y + 14; ++y) {
    for (uint16_t x = 0; x < 10; ++x) {
      assert(!with.getPixel(x, y));
    }
    for (uint16_t x = 10; x < text_end; ++x) {
      ink = ink || with.getPixel(x, y);
    }
    for (uint16_t x = text_end; x < RLCD_WIDTH; ++x) {
      assert(!with.getPixel(x, y));
    }
  }
  assert(ink);
  // The line stays clear of the noise line below it (NOISE_TREND_Y)
  assert(line_y + 14 <= DashboardView::NOISE_TREND_Y);
}

void testRecordsPersistence() {
  PersistentState s;
  for (int i = 0; i < 30; ++i) {
    s.history.temperatures[0].pushCelsius(-15.0f + static_cast<float>(i) * 0.7f);
  }
  s.history.temperatures[2].push(TemperatureHistory::MIN_VALUE);
  s.history.temperatures[2].push(TemperatureHistory::MAX_VALUE);
  s.history.temperatures[3].push(-1);
  s.history.slots[2].push(500);
  s.history.last_sample_ms = 7000;
  s.history.has_sampled = true;

  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  PersistentState r;
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(r.history.temperatures[0].size() == 24);
  assert(r.history.temperatures[0].head() == s.history.temperatures[0].head());
  for (size_t i = 0; i < 24; ++i) {
    assert(r.history.temperatures[0].at(i) == s.history.temperatures[0].at(i));
  }
  assert(r.history.temperatures[1].size() == 0);
  assert(r.history.temperatures[2].size() == 2 && r.history.temperatures[2].at(0) == -1000 &&
         r.history.temperatures[2].at(1) == 1000);
  assert(r.history.temperatures[3].size() == 1 && r.history.temperatures[3].at(0) == -1);
  assert(r.history.slots[2].size() == 1 && r.history.slots[2].at(0) == 500);
  assert(r.history.last_sample_ms == 7000 && r.history.has_sampled);

  // Ring layout per slot: humidity ring, then temperature ring
  const size_t ring0 = PowerManager::HEADER_SIZE + MAX_SWITCHBOT_SENSORS * PowerManager::SLOT_SIZE;
  const size_t temp0 = ring0 + PowerManager::HISTORY_RING_SIZE;
  auto reseal = [](uint8_t* b) {
    const uint32_t crc = PowerManager::crc32(b, PowerManager::SERIALIZED_SIZE - 4);
    for (int i = 0; i < 4; ++i) {
      b[PowerManager::SERIALIZED_SIZE - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
    }
  };
  // Corrupt temperature count, head and sample (above +100 degC, below -100 degC) are rejected
  const size_t cases[4][2] = {{temp0, 25}, {temp0 + 1, 24}, {temp0 + 3, 0x7F}, {temp0 + 3, 0x80}};
  for (const auto& c : cases) {
    uint8_t b[PowerManager::SERIALIZED_SIZE];
    memcpy(b, buf, sizeof(b));
    b[c[0]] = static_cast<uint8_t>(c[1]);
    reseal(b);
    PersistentState out;
    out.boot_count = 99;
    assert(!PowerManager::deserialize(b, sizeof(b), out));
    assert(out.boot_count == 0 && out.history.temperatures[0].size() == 0);
  }
  // Without the checksum fix, a changed byte is rejected as well
  uint8_t flipped[PowerManager::SERIALIZED_SIZE];
  memcpy(flipped, buf, sizeof(flipped));
  flipped[temp0 + 2] ^= 0x01;
  PersistentState out;
  assert(!PowerManager::deserialize(flipped, sizeof(flipped), out));

  // The previous state version starts clean
  uint8_t old[PowerManager::SERIALIZED_SIZE];
  memcpy(old, buf, sizeof(old));
  old[4] = 13;
  reseal(old);
  assert(PowerManager::STATE_VERSION == 26);
  assert(!PowerManager::deserialize(old, sizeof(old), out));
}

void testSparklineAutoScale() {
  HumidityHistory h;
  Sparkline::Scale s;
  assert(!Sparkline::computeScale(h, s)); // empty

  // Single sample: span of MIN_SPAN centered on the value
  h.push(500);
  assert(Sparkline::computeScale(h, s));
  assert(s.hi - s.lo == Sparkline::MIN_SPAN);
  assert(s.lo < 500 && s.hi > 500);
  assert(Sparkline::valueToRow(500, s, 16) == 7 || Sparkline::valueToRow(500, s, 16) == 8);

  // Flat line: same span, every sample on the same row (no divide by zero)
  for (int i = 0; i < 23; ++i) {
    h.push(500);
  }
  assert(Sparkline::computeScale(h, s));
  assert(s.hi - s.lo == Sparkline::MIN_SPAN);
  assert(Sparkline::MIN_SPAN == 50); // 5.0 %RH

  // Small range below MIN_SPAN is widened, not stretched to full height
  HumidityHistory small;
  small.push(500);
  small.push(520);
  assert(Sparkline::computeScale(small, s));
  assert(s.hi - s.lo == Sparkline::MIN_SPAN);
  assert(Sparkline::valueToRow(520, s, 16) < Sparkline::valueToRow(500, s, 16));

  // Full range: min on the bottom row, max on the top row
  HumidityHistory full;
  full.push(HumidityHistory::MIN_VALUE);
  full.push(HumidityHistory::MAX_VALUE);
  assert(Sparkline::computeScale(full, s));
  assert(s.lo == HumidityHistory::MIN_VALUE && s.hi == HumidityHistory::MAX_VALUE);
  assert(Sparkline::valueToRow(s.lo, s, 16) == 15);
  assert(Sparkline::valueToRow(s.hi, s, 16) == 0);
  assert(s.lo == 0 && s.hi == 1000);
  assert(Sparkline::valueToRow(s.hi + 100, s, 16) == 0); // clamp
  assert(Sparkline::valueToRow(0, s, 1) == 0);

  // A flat curve at the dry border stays inside 0..100 %RH
  HumidityHistory dry;
  dry.push(0);
  dry.push(10);
  assert(Sparkline::computeScale(dry, s));
  assert(s.lo == 0 && s.hi == Sparkline::MIN_SPAN);
  // A flat curve at the wet border stays inside 0..100 %RH
  HumidityHistory wet;
  wet.push(1000);
  wet.push(990);
  assert(Sparkline::computeScale(wet, s));
  assert(s.hi == 1000 && s.lo == 1000 - Sparkline::MIN_SPAN);
  assert(Sparkline::valueToRow(1000, s, 16) == 0 && Sparkline::valueToRow(990, s, 16) > 0);

  // Columns: newest sample at the right edge, oldest of a full window at column 0
  assert(Sparkline::sampleColumn(23, 24, 80) == 79);
  assert(Sparkline::sampleColumn(0, 24, 80) == 0);
  assert(Sparkline::sampleColumn(0, 1, 80) == 79);
  assert(Sparkline::sampleColumn(23, 24, 72) == 71);
  assert(Sparkline::sampleColumn(0, 24, 72) == 0);
  assert(Sparkline::sampleColumn(0, 2, 80) < Sparkline::sampleColumn(1, 2, 80));
}

void testSparklineBoundsAndClipping() {
  // Rising ramp over the full range, with a frame of set pixels around the box to detect leaks
  HumidityHistory h;
  for (int i = 0; i < 24; ++i) {
    h.push(static_cast<int16_t>(i * 40));
  }
  const uint16_t bx = 100, by = 100, bw = 80, bh = 16;
  St7305Framebuffer fb;
  fb.clear(false);
  assert(!Sparkline::render(fb, bx, by, 0, bh, h));
  assert(!Sparkline::render(fb, bx, by, bw, 0, h));
  assert(!Sparkline::render(fb, bx, by, bw, bh, HumidityHistory{}));
  assert(Sparkline::render(fb, bx, by, bw, bh, h));

  int inside = 0;
  for (uint16_t y = 0; y < RLCD_HEIGHT; ++y) {
    for (uint16_t x = 0; x < RLCD_WIDTH; ++x) {
      const bool in_box = x >= bx && x < bx + bw && y >= by && y < by + bh;
      if (fb.getPixel(x, y)) {
        assert(in_box);
        ++inside;
      }
    }
  }
  assert(inside >= bw); // a connected curve touches every column
  assert(fb.getPixel(bx, by + bh - 1));   // oldest (lowest) at bottom left
  assert(fb.getPixel(bx + bw - 1, by));   // newest (highest) at top right
  for (uint16_t x = bx; x < bx + bw; ++x) {
    bool column_hit = false;
    for (uint16_t y = by; y < by + bh; ++y) {
      column_hit = column_hit || fb.getPixel(x, y);
    }
    assert(column_hit);
  }

  // Box at the screen corner: no write outside the framebuffer
  St7305Framebuffer corner;
  assert(Sparkline::render(corner, RLCD_WIDTH - bw, RLCD_HEIGHT - bh, bw, bh, h));
  assert(corner.getPixel(RLCD_WIDTH - 1, RLCD_HEIGHT - bh));
}

void testDashboardSparklineInsideTiles() {
  Shtc3Reading onboard;
  onboard.valid = true;
  onboard.temperature_c = 21.0f;
  onboard.humidity_pct = 100.0f;
  BatteryReading battery;
  battery.valid = true;
  battery.voltage_v = 3.9f;
  battery.percentage = 70;
  SwitchBotReading slots[MAX_SWITCHBOT_SENSORS];
  for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
    slots[i].valid = true;
    slots[i].temperature_c = 15.0f;
    slots[i].humidity_pct = 100;
    slots[i].battery_pct = 80;
    slots[i].updated_ms = 100;
  }
  ClimateHistory history;
  for (size_t s = 0; s < ClimateHistory::SLOT_COUNT; ++s) {
    for (int i = 0; i < 24; ++i) {
      history.slots[s].push(static_cast<int16_t>(i % 2 == 0 ? 100 : 900));
    }
  }

  St7305Framebuffer with, without;
  DashboardView::render(with, onboard, battery, slots, MAX_SWITCHBOT_SENSORS, nullptr, 100, &history);
  DashboardView::render(without, onboard, battery, slots, MAX_SWITCHBOT_SENSORS, nullptr, 100);

  const uint16_t tile_w = 190, tile_h = 124;
  // Tile origins as in DashboardView::render: slot 0 at (6,36), slots 1..3 on a 2-column grid
  const uint16_t ox[4] = {6, 6 + 198, 6, 6 + 198};
  const uint16_t oy[4] = {36, 36, 36 + 132, 36 + 132};
  // The sparkline layer = pixels that differ between both renders. They must lie inside the
  // sparkline box, which lies inside the tile without touching the border.
  int differing = 0;
  for (uint16_t y = 0; y < RLCD_HEIGHT; ++y) {
    for (uint16_t x = 0; x < RLCD_WIDTH; ++x) {
      if (with.getPixel(x, y) == without.getPixel(x, y)) {
        continue;
      }
      ++differing;
      bool in_box = false;
      for (int t = 0; t < 4; ++t) {
        const uint16_t bx = ox[t] + tile_w - DashboardView::SPARKLINE_W - DashboardView::SPARKLINE_RIGHT_MARGIN;
        const uint16_t by = oy[t] + DashboardView::SPARKLINE_Y_OFFSET;
        if (x >= bx && x < bx + DashboardView::SPARKLINE_W && y >= by &&
            y < by + DashboardView::SPARKLINE_H) {
          in_box = true;
          // Strictly inside the tile border and below the divider line
          assert(x > ox[t] && x < ox[t] + tile_w - 1);
          assert(y > oy[t] + 24 && y < oy[t] + tile_h - 1);
        }
      }
      assert(in_box);
    }
  }
  assert(differing > 4 * 20);

  // A single sample draws no curve
  ClimateHistory one;
  for (size_t s = 0; s < ClimateHistory::SLOT_COUNT; ++s) {
    one.slots[s].push(500);
  }
  St7305Framebuffer single;
  DashboardView::render(single, onboard, battery, slots, MAX_SWITCHBOT_SENSORS, nullptr, 100, &one);
  for (uint16_t y = 0; y < RLCD_HEIGHT; ++y) {
    for (uint16_t x = 0; x < RLCD_WIDTH; ++x) {
      assert(single.getPixel(x, y) == without.getPixel(x, y));
    }
  }
}

void testHistoryPersistence() {
  PersistentState s;
  for (int i = 0; i < 30; ++i) {
    s.history.slots[0].push(static_cast<int16_t>(100 + i));
  }
  s.history.slots[3].push(455);
  s.history.slots[2].push(0);    // dry border survives the round trip
  s.history.slots[2].push(1000); // wet border survives the round trip
  s.history.last_sample_ms = 5000;
  s.history.has_sampled = true;

  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  PersistentState r;
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(r.history.slots[0].size() == 24);
  for (size_t i = 0; i < 24; ++i) {
    assert(r.history.slots[0].at(i) == s.history.slots[0].at(i));
  }
  assert(r.history.slots[3].size() == 1 && r.history.slots[3].at(0) == 455);
  assert(r.history.slots[1].size() == 0);
  assert(r.history.slots[2].size() == 2 && r.history.slots[2].at(0) == 0 && r.history.slots[2].at(1) == 1000);
  assert(r.history.last_sample_ms == 5000 && r.history.has_sampled);
  assert(PowerManager::timestampsConsistent(r, 5000));
  // The history timestamp alone never forces a clean start; the sample gate handles a clock reset
  assert(PowerManager::timestampsConsistent(r, 4999));
  assert(r.history.sampleDue(4999));

  // Out-of-range ring data with a valid CRC is rejected
  const size_t ring_pos = PowerManager::HEADER_SIZE + MAX_SWITCHBOT_SENSORS * PowerManager::SLOT_SIZE;
  uint8_t bad[PowerManager::SERIALIZED_SIZE];
  memcpy(bad, buf, sizeof(bad));
  bad[ring_pos] = 25; // count > capacity
  const uint32_t crc = PowerManager::crc32(bad, sizeof(bad) - 4);
  for (int i = 0; i < 4; ++i) {
    bad[sizeof(bad) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  }
  PersistentState rejected;
  assert(!PowerManager::deserialize(bad, sizeof(bad), rejected));
  assert(rejected.history.slots[0].size() == 0);

  // Each corrupt field with a valid CRC is rejected: head, sample range, sampled flag
  const size_t sampled_pos = PowerManager::SERIALIZED_SIZE - 4 - PowerManager::CONFIG_SIZE - PowerManager::NTP_SIZE - PowerManager::AUDIO_SIZE - PowerManager::SD_LOG_SIZE - PowerManager::BATTERY_HISTORY_SIZE - PowerManager::NOISE_HISTORY_SIZE - 1;
  // Sample bytes: low byte at ring_pos + 2, high byte at ring_pos + 3 (little endian)
  const size_t cases[5][2] = {
      {ring_pos + 1, 24}, {ring_pos + 3, 0x7F}, {sampled_pos, 2}, {ring_pos + 3, 0xFF}, {ring_pos + 3, 0x04}};
  for (const auto& c : cases) {
    uint8_t b[PowerManager::SERIALIZED_SIZE];
    memcpy(b, buf, sizeof(b));
    b[c[0]] = static_cast<uint8_t>(c[1]);
    const uint32_t cc = PowerManager::crc32(b, sizeof(b) - 4);
    for (int i = 0; i < 4; ++i) {
      b[sizeof(b) - 4 + i] = static_cast<uint8_t>(cc >> (8 * i));
    }
    PersistentState x;
    assert(!PowerManager::deserialize(b, sizeof(b), x));
  }
}

void testNoiseHistoryFifo() {
  NoiseHistory h;
  assert(h.size() == 0 && h.validCount() == 0);
  uint8_t lo = 7;
  uint8_t hi = 7;
  assert(!h.range(lo, hi));
  assert(h.at(0) == NoiseHistory::INVALID);

  // 30 samples: the 6 oldest drop out and the order stays oldest first
  for (int i = 0; i < 30; ++i) {
    h.push(static_cast<uint8_t>(30 + i));
  }
  assert(h.size() == NoiseHistory::CAPACITY);
  for (size_t i = 0; i < NoiseHistory::CAPACITY; ++i) {
    assert(h.at(i) == 36 + i);
  }
  assert(h.at(NoiseHistory::CAPACITY) == NoiseHistory::INVALID);
  assert(h.range(lo, hi) && lo == 36 && hi == 59);

  // Invalid hours keep their place but stay out of the range
  NoiseHistory g;
  g.push(50);
  g.pushInvalid();
  g.push(40);
  g.pushInvalid();
  assert(g.size() == 4 && g.validCount() == 2);
  assert(g.range(lo, hi) && lo == 40 && hi == 50);
  assert(g.at(1) == NoiseHistory::INVALID && !NoiseHistory::isValid(g.at(3)));

  // Only invalid hours give no range
  NoiseHistory bad;
  bad.pushInvalid();
  bad.pushInvalid();
  assert(!bad.range(lo, hi) && bad.validCount() == 0);

  // Encoding clamps; a raw value above 140 that is not the sentinel is clamped
  assert(NoiseHistory::encode(-5) == 0);
  assert(NoiseHistory::encode(75) == 75);
  assert(NoiseHistory::encode(300) == NoiseHistory::MAX_DB);
  NoiseHistory c;
  c.push(200);
  assert(c.at(0) == NoiseHistory::MAX_DB);

  // Restore rejects a bad ring
  uint8_t raw[NoiseHistory::CAPACITY] = {};
  NoiseHistory r;
  assert(r.restore(24, 23, raw));
  assert(!r.restore(25, 0, raw));
  assert(!r.restore(0, 24, raw));
  assert(!r.restore(0, 0, nullptr));
  raw[5] = 141;
  assert(!r.restore(3, 3, raw));
  raw[5] = NoiseHistory::INVALID;
  assert(r.restore(3, 3, raw));
  r.clear();
  assert(r.size() == 0);
}

void testNoiseSparklineBounds() {
  // Every pixel stays inside the box, also with invalid hours and full range values
  NoiseHistory h;
  for (int i = 0; i < 24; ++i) {
    if (i % 5 == 2) {
      h.pushInvalid();
    } else {
      h.push(i % 2 == 0 ? 0 : NoiseHistory::MAX_DB);
    }
  }
  const uint16_t bx = 20, by = 30, bw = 60, bh = 14;
  St7305Framebuffer fb;
  fb.clear(false);
  assert(Sparkline::renderNoise(fb, bx, by, bw, bh, h));
  size_t ink = 0;
  for (uint16_t y = 0; y < St7305Framebuffer::HEIGHT; ++y) {
    for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
      if (fb.getPixel(x, y)) {
        ++ink;
        assert(x >= bx && x < bx + bw && y >= by && y < by + bh);
      }
    }
  }
  assert(ink > 20);

  // No valid sample or an empty box draws nothing
  NoiseHistory none;
  none.pushInvalid();
  St7305Framebuffer blank;
  blank.clear(false);
  assert(!Sparkline::renderNoise(blank, bx, by, bw, bh, none));
  assert(!Sparkline::renderNoise(blank, bx, by, 0, bh, h));
  assert(!Sparkline::renderNoise(blank, bx, by, bw, 0, h));
  for (uint16_t y = 0; y < St7305Framebuffer::HEIGHT; ++y) {
    for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
      assert(!blank.getPixel(x, y));
    }
  }

  // A flat curve gets the minimum span, also at the ends of the range
  NoiseHistory flat;
  flat.push(0);
  flat.push(0);
  Sparkline::Scale scale;
  assert(Sparkline::computeNoiseScale(flat, scale));
  assert(scale.lo == 0 && scale.hi == Sparkline::NOISE_MIN_SPAN);
  NoiseHistory loud;
  loud.push(NoiseHistory::MAX_DB);
  assert(Sparkline::computeNoiseScale(loud, scale));
  assert(scale.hi == NoiseHistory::MAX_DB && scale.hi - scale.lo == Sparkline::NOISE_MIN_SPAN);

  // A gap breaks the line: the invalid hour between two samples leaves its column empty
  NoiseHistory gap;
  gap.push(30);
  gap.pushInvalid();
  gap.push(60);
  St7305Framebuffer gf;
  gf.clear(false);
  assert(Sparkline::renderNoise(gf, 0, 0, 100, 20, gap));
  const uint16_t col_mid = Sparkline::sampleColumn(1, 3, 100);
  for (uint16_t y = 0; y < 20; ++y) {
    assert(!gf.getPixel(col_mid, y));
  }
}

void testNoiseHistoryStatsView() {
  SwitchBotReading slots[MAX_SWITCHBOT_SENSORS];
  Shtc3Reading onboard;
  BatteryReading battery;
  SystemInfo info;
  NoiseHistory h;
  for (int i = 0; i < 24; ++i) {
    h.push(static_cast<uint8_t>(40 + i));
  }
  St7305Framebuffer with, without, invalid_only;
  DashboardView::renderMode(
      with, DisplayMode::SENSOR_STATS, onboard, battery, slots, MAX_SWITCHBOT_SENSORS, info, "12:34", 0,
      nullptr, &h);
  DashboardView::renderMode(
      without, DisplayMode::SENSOR_STATS, onboard, battery, slots, MAX_SWITCHBOT_SENSORS, info, "12:34", 0);
  NoiseHistory inv;
  inv.pushInvalid();
  DashboardView::renderMode(
      invalid_only, DisplayMode::SENSOR_STATS, onboard, battery, slots, MAX_SWITCHBOT_SENSORS, info, "12:34",
      0, nullptr, &inv);
  // No valid hour: the view equals the view without a history
  assert(memcmp(invalid_only.data(), without.data(), St7305Framebuffer::BYTE_COUNT) == 0);
  assert(memcmp(with.data(), without.data(), St7305Framebuffer::BYTE_COUNT) != 0);
  // The trend only changes pixels in the noise band, between the sensor blocks and the battery line
  for (uint16_t y = 0; y < St7305Framebuffer::HEIGHT; ++y) {
    for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
      if (with.getPixel(x, y) != without.getPixel(x, y)) {
        assert(y >= DashboardView::NOISE_SPARKLINE_Y - 2 && y < 268);
        assert(x < DashboardView::NOISE_SPARKLINE_X + DashboardView::NOISE_SPARKLINE_W);
      }
    }
  }
}

void testNoiseHistoryPersistence() {
  PersistentState s;
  for (int i = 0; i < 30; ++i) {
    if (i % 7 == 3) {
      s.noise_history.pushInvalid();
    } else {
      s.noise_history.push(static_cast<uint8_t>(35 + i));
    }
  }
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  PersistentState r;
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(r.noise_history.size() == 24);
  for (size_t i = 0; i < 24; ++i) {
    assert(r.noise_history.at(i) == s.noise_history.at(i));
  }
  assert(r.noise_history.head() == s.noise_history.head());

  // Corrupt fields behind a valid checksum are rejected
  const size_t pos = PowerManager::HEADER_SIZE + MAX_SWITCHBOT_SENSORS * PowerManager::SLOT_SIZE +
      PowerManager::HISTORY_SIZE;
  const struct { size_t offset; uint8_t value; } patches[] = {
      {0, 25},  // count above capacity
      {1, 24},  // head outside the ring
      {2, 141}, // sample above 140 dB that is not the invalid marker
  };
  for (const auto& p : patches) {
    uint8_t bad[PowerManager::SERIALIZED_SIZE];
    memcpy(bad, buf, sizeof(bad));
    bad[pos + p.offset] = p.value;
    const uint32_t crc = PowerManager::crc32(bad, sizeof(bad) - 4);
    for (int i = 0; i < 4; ++i) bad[sizeof(bad) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
    PersistentState rejected;
    assert(!PowerManager::deserialize(bad, sizeof(bad), rejected));
    assert(rejected.noise_history.size() == 0);
  }

  // The previous state version gives a clean start
  assert(PowerManager::STATE_VERSION == 26);
  uint8_t old[PowerManager::SERIALIZED_SIZE];
  memcpy(old, buf, sizeof(old));
  old[4] = 8;
  const uint32_t crc = PowerManager::crc32(old, sizeof(old) - 4);
  for (int i = 0; i < 4; ++i) old[sizeof(old) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  PersistentState clean;
  assert(!PowerManager::deserialize(old, sizeof(old), clean));
  assert(clean.noise_history.size() == 0 && clean.boot_count == 0);
}

void testPreviewStatsShowNoiseCurve() {
  using namespace rlcd_panel::preview;
  St7305Framebuffer stats;
  stats.clear(true);
  renderScene(stats, DisplayMode::SENSOR_STATS, false, false);
  size_t ink = 0;
  for (uint16_t y = DashboardView::NOISE_SPARKLINE_Y; y < DashboardView::NOISE_SPARKLINE_Y + DashboardView::NOISE_SPARKLINE_H; ++y) {
    for (uint16_t x = DashboardView::NOISE_SPARKLINE_X; x < DashboardView::NOISE_SPARKLINE_X + DashboardView::NOISE_SPARKLINE_W; ++x) {
      ink += stats.getPixel(x, y) ? 1 : 0;
    }
  }
  assert(ink > 20);
  // The trend text line has ink
  size_t text_ink = 0;
  for (uint16_t y = DashboardView::NOISE_TREND_Y; y < DashboardView::NOISE_TREND_Y + 12; ++y) {
    for (uint16_t x = 10; x < 200; ++x) {
      text_ink += stats.getPixel(x, y) ? 1 : 0;
    }
  }
  assert(text_ink > 20);
}

void testPreviewScenesCoverAllDisplayModes() {
  using namespace rlcd_panel::preview;
  // Every display mode has at least one scene, and every scene has a unique file name.
  bool mode_seen[DISPLAY_MODE_COUNT] = {};
  for (uint8_t i = 0; i < PREVIEW_SCENE_COUNT; ++i) {
    const PreviewScene& scene = PREVIEW_SCENES[i];
    assert(static_cast<uint8_t>(scene.mode) < DISPLAY_MODE_COUNT);
    mode_seen[static_cast<uint8_t>(scene.mode)] = true;
    for (uint8_t j = 0; j < i; ++j) {
      assert(strcmp(scene.file_name, PREVIEW_SCENES[j].file_name) != 0);
    }
  }
  for (uint8_t m = 0; m < DISPLAY_MODE_COUNT; ++m) {
    assert(mode_seen[m]);
  }

  // Every scene renders content, and no two scenes render the same image.
  St7305Framebuffer frames[PREVIEW_SCENE_COUNT];
  for (uint8_t i = 0; i < PREVIEW_SCENE_COUNT; ++i) {
    frames[i].clear(true); // poison: a full redraw must overwrite it
    renderScene(
        frames[i], PREVIEW_SCENES[i].mode, PREVIEW_SCENES[i].low_battery, PREVIEW_SCENES[i].quiet_hours,
        PREVIEW_SCENES[i].long_runtime, PREVIEW_SCENES[i].battery_empty);
    size_t ink = 0;
    for (uint16_t y = 0; y < St7305Framebuffer::HEIGHT; ++y) {
      for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
        ink += frames[i].getPixel(x, y) ? 1 : 0;
      }
    }
    assert(ink > 500);
    assert(ink < static_cast<size_t>(St7305Framebuffer::WIDTH) * St7305Framebuffer::HEIGHT - 500);
    for (uint8_t j = 0; j < i; ++j) {
      bool differs = false;
      for (uint16_t y = 0; y < St7305Framebuffer::HEIGHT && !differs; ++y) {
        for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
          if (frames[i].getPixel(x, y) != frames[j].getPixel(x, y)) {
            differs = true;
            break;
          }
        }
      }
      assert(differs);
    }
  }

  // The low battery scene replaces the dark header bar with the inverted banner.
  uint8_t low_index = PREVIEW_SCENE_COUNT;
  uint8_t dash_index = PREVIEW_SCENE_COUNT;
  for (uint8_t i = 0; i < PREVIEW_SCENE_COUNT; ++i) {
    if (PREVIEW_SCENES[i].mode == DisplayMode::DASHBOARD && !PREVIEW_SCENES[i].quiet_hours &&
        !PREVIEW_SCENES[i].battery_empty) {
      (PREVIEW_SCENES[i].low_battery ? low_index : dash_index) = i;
    }
  }
  assert(low_index < PREVIEW_SCENE_COUNT && dash_index < PREVIEW_SCENE_COUNT);
  assert(frames[dash_index].getPixel(5, 5));  // filled header
  assert(!frames[low_index].getPixel(5, 5)); // banner interior is white
  assert(frames[low_index].getPixel(0, 0));  // banner frame
}

void testSdLogCodec() {
  char buf[SdLogCodec::MAX_ROW_SIZE];

  // Header
  const size_t hl = SdLogCodec::formatHeader(buf, sizeof(buf));
  assert(hl > 0 && hl == strlen(buf));
  assert(strncmp(buf, "timestamp,battery_pct,battery_v,usb,onboard_temp_c,onboard_rh,", 62) == 0);
  assert(buf[hl - 1] == '\n');
  size_t header_commas = 0;
  for (size_t i = 0; i < hl; ++i) header_commas += buf[i] == ',';
  assert(header_commas == 20);
  assert(SdLogCodec::formatHeader(nullptr, 100) == 0);
  assert(SdLogCodec::formatHeader(buf, 0) == 0);
  assert(SdLogCodec::formatHeader(buf, hl) == 0); // no room for the terminator

  // Filename
  RtcTime t;
  t.valid = true; t.year = 2026; t.month = 3; t.day = 9; t.hour = 7; t.minute = 5; t.second = 1;
  char path[SdLogCodec::MAX_PATH_SIZE];
  assert(SdLogCodec::formatFilename(&t, path, sizeof(path)) > 0);
  assert(strcmp(path, "/sdcard/climate_v4_202603.csv") == 0);
  t.month = 12;
  SdLogCodec::formatFilename(&t, path, sizeof(path));
  assert(strcmp(path, "/sdcard/climate_v4_202612.csv") == 0);
  t.month = 3;
  assert(SdLogCodec::formatFilename(nullptr, path, sizeof(path)) == 0);
  assert(SdLogCodec::formatFilename(&t, nullptr, 32) == 0);
  assert(SdLogCodec::formatFilename(&t, path, 0) == 0);
  assert(SdLogCodec::formatFilename(&t, path, 10) == 0 && path[0] == '\0');
  RtcTime bad = t;
  bad.valid = false;
  assert(SdLogCodec::formatFilename(&bad, path, sizeof(path)) == 0);
  bad = t; bad.month = 13;
  assert(SdLogCodec::formatFilename(&bad, path, sizeof(path)) == 0);
  bad = t; bad.month = 0;
  assert(SdLogCodec::formatFilename(&bad, path, sizeof(path)) == 0);
  bad = t; bad.year = 1999;
  assert(SdLogCodec::formatFilename(&bad, path, sizeof(path)) == 0);
  bad = t; bad.year = 2100;
  assert(SdLogCodec::formatFilename(&bad, path, sizeof(path)) == 0);

  // Row with valid battery, onboard sensor, and two valid SwitchBot slots
  BatteryReading bat;
  bat.valid = true; bat.percentage = 87; bat.voltage_v = 4.0f; bat.usb_powered = false;
  Shtc3Reading onboard;
  onboard.valid = true; onboard.temperature_c = 21.54f; onboard.humidity_pct = 45.0f;
  SwitchBotReading slots[MAX_SWITCHBOT_SENSORS];
  slots[0].valid = true; slots[0].temperature_c = -3.5f; slots[0].humidity_pct = 80;
  slots[0].battery_pct = 90; slots[0].updated_ms = 1000;
  slots[1].valid = true; slots[1].temperature_c = 19.0f; slots[1].humidity_pct = 50;
  slots[1].battery_pct = -1; slots[1].updated_ms = 1000;
  const size_t rl = SdLogCodec::formatRow(&t, &bat, &onboard, slots, 3, 2000, nullptr, nullptr, buf, sizeof(buf));
  assert(rl == strlen(buf));
  assert(strcmp(buf, "2026-03-09 07:05:01,87,4.00,0,21.5,45.0,-3.5,80,90,19.0,50,,,,,,,,,,\n") == 0);

  // Row with invalid battery, invalid onboard, a stale slot, a NaN slot, and a missing slot
  BatteryReading nobat;
  Shtc3Reading noonboard;
  slots[0].updated_ms = 0;
  slots[1].temperature_c = NAN;
  const uint32_t late = SENSOR_STALE_TIMEOUT_MS + 5000;
  SdLogCodec::formatRow(&t, &nobat, &noonboard, slots, 3, late, nullptr, nullptr, buf, sizeof(buf));
  assert(strcmp(buf, "2026-03-09 07:05:01,,,,,,,,,,,,,,,,,,,,\n") == 0);
  SdLogCodec::formatRow(&t, &nobat, &noonboard, nullptr, 0, 0, nullptr, nullptr, buf, sizeof(buf));
  assert(strcmp(buf, "2026-03-09 07:05:01,,,,,,,,,,,,,,,,,,,,\n") == 0);

  // Every row has as many columns as the header
  size_t row_commas = 0;
  for (size_t i = 0; buf[i] != '\0'; ++i) row_commas += buf[i] == ',';
  assert(row_commas == header_commas);

  // Null, empty and invalid input
  assert(SdLogCodec::formatRow(nullptr, &bat, &onboard, slots, 3, 0, nullptr, nullptr, buf, sizeof(buf)) == 0);
  assert(buf[0] == '\0');
  RtcTime invalid_time;
  assert(SdLogCodec::formatRow(&invalid_time, &bat, &onboard, slots, 3, 0, nullptr, nullptr, buf, sizeof(buf)) == 0);
  assert(SdLogCodec::formatRow(&t, &bat, &onboard, slots, 3, 0, nullptr, nullptr, nullptr, 256) == 0);
  assert(SdLogCodec::formatRow(&t, &bat, &onboard, slots, 3, 0, nullptr, nullptr, buf, 0) == 0);
  assert(SdLogCodec::formatRow(&t, &bat, &onboard, slots, 3, 0, nullptr, nullptr, buf, 16) == 0);
  assert(SdLogCodec::formatRow(&t, &bat, &onboard, slots, 3, 0, nullptr, nullptr, buf, SdLogCodec::MAX_ROW_SIZE - 1) == 0);
  assert(SdLogCodec::formatRow(&t, &bat, &onboard, slots, 3, 0, nullptr, nullptr, buf, SdLogCodec::MAX_ROW_SIZE) > 0);
  assert(SdLogCodec::formatRow(&t, nullptr, nullptr, nullptr, 0, 0, nullptr, nullptr, buf, sizeof(buf)) > 0);
  assert(strcmp(buf, "2026-03-09 07:05:01,,,,,,,,,,,,,,,,,,,,\n") == 0);
}

void testSdLogSchedule() {
  const uint32_t H = SdLogCodec::LOG_INTERVAL_MS;
  assert(SdLogCodec::logDue(false, 0, 0));
  assert(!SdLogCodec::logDue(true, 1000, 1000 + H - 1));
  assert(SdLogCodec::logDue(true, 1000, 1000 + H));
  // wrap-safe
  assert(!SdLogCodec::logDue(true, 0xFFFFFF00u, 100));
  assert(SdLogCodec::logDue(true, 0xFFFFFF00u, 0xFFFFFF00u + H));
  // a failed write retries after the retry interval, not after an hour
  const uint32_t stamp = SdLogCodec::retryStamp(5000000);
  assert(!SdLogCodec::logDue(true, stamp, 5000000 + SdLogCodec::RETRY_INTERVAL_MS - 1));
  assert(SdLogCodec::logDue(true, stamp, 5000000 + SdLogCodec::RETRY_INTERVAL_MS));

  // The log gate survives the RTC round trip and rejects a corrupt flag
  PersistentState s;
  s.last_log_ms = 123456;
  s.has_logged = true;
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  PersistentState r;
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(r.last_log_ms == 123456 && r.has_logged);
  buf[PowerManager::SERIALIZED_SIZE - 4 - PowerManager::CONFIG_SIZE - PowerManager::NTP_SIZE - PowerManager::AUDIO_SIZE - 5 - 1] = 2; // has_logged precedes the 5-byte daily stamp
  const uint32_t crc = PowerManager::crc32(buf, sizeof(buf) - 4);
  for (int i = 0; i < 4; ++i) buf[sizeof(buf) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  assert(!PowerManager::deserialize(buf, sizeof(buf), r));
}

Shtc3Reading makeOnboard(float temp, float hum) {
  Shtc3Reading r;
  r.valid = true;
  r.temperature_c = temp;
  r.humidity_pct = hum;
  return r;
}

void testAlertThresholds() {
  const uint32_t now = 1000;
  SwitchBotReading slots[MAX_SWITCHBOT_SENSORS];
  const Shtc3Reading none;
  // Normal climate: no alert
  assert(AlertEvaluator::evaluate(ALERT_NONE, makeOnboard(21.0f, 50.0f), slots, 3, now) == ALERT_NONE);
  // Freezer: below 0 C triggers, exactly 0 C does not
  assert(AlertEvaluator::evaluate(ALERT_NONE, makeOnboard(-0.1f, 50.0f), slots, 3, now) == ALERT_FREEZER);
  assert(AlertEvaluator::evaluate(ALERT_NONE, makeOnboard(0.0f, 50.0f), slots, 3, now) == ALERT_NONE);
  // Humidity: above 70 % triggers, exactly 70 % does not
  assert(AlertEvaluator::evaluate(ALERT_NONE, makeOnboard(21.0f, 70.5f), slots, 3, now) == ALERT_HUMIDITY);
  assert(AlertEvaluator::evaluate(ALERT_NONE, makeOnboard(21.0f, 70.0f), slots, 3, now) == ALERT_NONE);
  // Both at once
  assert(AlertEvaluator::evaluate(ALERT_NONE, makeOnboard(-5.0f, 90.0f), slots, 3, now) == (ALERT_FREEZER | ALERT_HUMIDITY));
  // A SwitchBot slot triggers too (freezer sensor)
  slots[1] = makeReading(1, -18.0f, 40, 80);
  slots[1].updated_ms = now;
  assert(AlertEvaluator::evaluate(ALERT_NONE, none, slots, 3, now) == ALERT_FREEZER);
  // A stale slot is ignored
  assert((AlertEvaluator::evaluate(ALERT_NONE, none, slots, 3, now + SENSOR_STALE_TIMEOUT_MS + 1) &
          (ALERT_FREEZER | ALERT_HUMIDITY)) == ALERT_NONE);
  // Invalid onboard and NaN readings are ignored
  assert(AlertEvaluator::evaluate(ALERT_NONE, none, nullptr, 0, now) == ALERT_NONE);
  assert(AlertEvaluator::evaluate(ALERT_NONE, makeOnboard(NAN, NAN), nullptr, 0, now) == ALERT_NONE);
  // Hysteresis: an active alert holds in the band, clears above the release limit
  assert(AlertEvaluator::evaluate(ALERT_FREEZER, makeOnboard(0.5f, 50.0f), nullptr, 0, now) == ALERT_FREEZER);
  assert(AlertEvaluator::evaluate(ALERT_FREEZER, makeOnboard(1.0f, 50.0f), nullptr, 0, now) == ALERT_NONE);
  assert(AlertEvaluator::evaluate(ALERT_NONE, makeOnboard(0.5f, 50.0f), nullptr, 0, now) == ALERT_NONE);
  assert(AlertEvaluator::evaluate(ALERT_HUMIDITY, makeOnboard(21.0f, 68.0f), nullptr, 0, now) == ALERT_HUMIDITY);
  assert(AlertEvaluator::evaluate(ALERT_HUMIDITY, makeOnboard(21.0f, 67.0f), nullptr, 0, now) == ALERT_NONE);
  // Latch: with no usable reading an active alert holds, so a dropout does not clear and re-chime
  assert(AlertEvaluator::evaluate(ALERT_FREEZER | ALERT_HUMIDITY, none, nullptr, 0, now) == (ALERT_FREEZER | ALERT_HUMIDITY));
  assert(AlertEvaluator::evaluate(ALERT_FREEZER, none, slots, 3, now + SENSOR_STALE_TIMEOUT_MS + 1) ==
         (ALERT_FREEZER | ALERT_SENSOR_LOST));
  assert(AlertEvaluator::evaluate(ALERT_NONE, none, nullptr, 0, now) == ALERT_NONE);
}

void testSensorLostAlert() {
  const uint32_t t0 = 5000;
  const uint32_t stale = t0 + SENSOR_STALE_TIMEOUT_MS + 1;
  const Shtc3Reading none;
  SwitchBotReading slots[MAX_SWITCHBOT_SENSORS];
  slots[0] = makeReading(1, 21.0f, 50, 80);
  slots[0].updated_ms = t0;
  slots[1] = makeReading(2, 20.0f, 50, 80);
  slots[1].updated_ms = t0;
  // Fresh slots: no alert, exactly at the timeout the slot is still fresh
  assert(AlertEvaluator::evaluate(ALERT_NONE, none, slots, 3, t0 + 1000) == ALERT_NONE);
  assert(AlertEvaluator::evaluate(ALERT_NONE, none, slots, 3, t0 + SENSOR_STALE_TIMEOUT_MS) == ALERT_NONE);
  // Fresh to stale: the bit switches on and sounds once
  uint8_t mask = AlertEvaluator::evaluate(ALERT_NONE, none, slots, 3, stale);
  assert(mask == ALERT_SENSOR_LOST);
  assert(AlertEvaluator::chimeBits(false, ALERT_NONE, mask, false) == ALERT_SENSOR_LOST);
  // Stays on without a repeated chime
  const uint8_t again = AlertEvaluator::evaluate(mask, none, slots, 3, stale + 60000);
  assert(again == ALERT_SENSOR_LOST);
  assert(AlertEvaluator::chimeBits(false, mask, again, false) == ALERT_NONE);
  // One slot recovers, the other stays stale: the bit stays on
  slots[0].updated_ms = stale;
  assert(AlertEvaluator::evaluate(mask, none, slots, 3, stale + 1000) == ALERT_SENSOR_LOST);
  // Each valid slot sends again: the bit clears; a later dropout chimes again
  slots[1].updated_ms = stale;
  mask = AlertEvaluator::evaluate(mask, none, slots, 3, stale + 1000);
  assert(mask == ALERT_NONE);
  const uint32_t stale2 = stale + SENSOR_STALE_TIMEOUT_MS + 2000;
  const uint8_t next = AlertEvaluator::evaluate(mask, none, slots, 3, stale2);
  assert(AlertEvaluator::chimeBits(false, mask, next, false) == ALERT_SENSOR_LOST);
  // Wrap-around of the 32-bit clock
  SwitchBotReading wrap[1];
  wrap[0] = makeReading(1, 21.0f, 50, 80);
  wrap[0].updated_ms = 0xFFFFFF00u;
  assert(AlertEvaluator::evaluate(ALERT_NONE, none, wrap, 1, 1000) == ALERT_NONE);
  assert(AlertEvaluator::evaluate(ALERT_NONE, none, wrap, 1, 0xFFFFFF00u + SENSOR_STALE_TIMEOUT_MS + 1) ==
         ALERT_SENSOR_LOST);
  // A slot that was never valid does not trigger, however old the clock is
  SwitchBotReading empty[MAX_SWITCHBOT_SENSORS];
  assert(AlertEvaluator::evaluate(ALERT_NONE, none, empty, 3, 0xF0000000u) == ALERT_NONE);
  assert(AlertEvaluator::evaluate(ALERT_NONE, none, nullptr, 0, stale) == ALERT_NONE);
  // A dropout sets the bit next to a climate alert; a climate alert alone keeps its bit
  const uint8_t both = AlertEvaluator::evaluate(ALERT_NONE, makeOnboard(-3.0f, 50.0f), slots, 3, stale2);
  assert(both == (ALERT_FREEZER | ALERT_SENSOR_LOST));
  assert(AlertEvaluator::chimeBits(false, ALERT_FREEZER, both, false) == ALERT_SENSOR_LOST);
  // The noise bit of the last window stays; a recovery clears only the dropout bit
  const AlertEvaluator::Step keep = AlertEvaluator::climateStep(false, ALERT_NOISE_HIGH | ALERT_SENSOR_LOST,
                                                                ALERT_NONE, false);
  assert(keep.mask == ALERT_NOISE_HIGH && keep.chime == ALERT_NONE);
  // A noise window keeps the dropout bit
  const AlertEvaluator::Step noise = NoiseAlertEvaluator::step(false, ALERT_SENSOR_LOST, true, 90, 80, false);
  assert(noise.mask == (ALERT_NOISE_HIGH | ALERT_SENSOR_LOST) && noise.chime == ALERT_NOISE_HIGH);
  // The dropout chime differs from the noise chime and sounds after it when both switch on together
  assert(ChimeSynth::pattern(ALERT_SENSOR_LOST).notes != ChimeSynth::pattern(ALERT_NOISE_HIGH).notes);
  // Mute and quiet hours suppress the sound; the mask is still stored by the caller
  assert(AlertEvaluator::chimeBits(true, ALERT_NONE, ALERT_SENSOR_LOST, false) == ALERT_NONE);
  assert(AlertEvaluator::chimeBits(false, ALERT_NONE, ALERT_SENSOR_LOST, true) == ALERT_NONE);
  // No replay after unmute: the stored bit is not new any more
  assert(AlertEvaluator::chimeBits(false, ALERT_SENSOR_LOST, ALERT_SENSOR_LOST, false) == ALERT_NONE);

  // The chime is one low 440 Hz note
  const ChimePattern p = ChimeSynth::pattern(ALERT_SENSOR_LOST);
  assert(p.notes != nullptr && p.count == 1 && p.notes[0].freq_hz == 440);
  assert(p.notes[0].freq_hz < ChimeSynth::pattern(ALERT_FREEZER).notes[0].freq_hz);
  struct Hal {
    int samples = 0, amp_on = 0, amp_off = 0, ends = 0;
    bool beginBus() { return true; }
    bool configureCodec() { return true; }
    void setAmplifier(bool on) { on ? ++amp_on : ++amp_off; }
    bool write(const int16_t*, size_t n) { samples += static_cast<int>(n); return true; }
    void drain() {}
    void shutdownCodec() {}
    void endBus() { ++ends; }
  };
  Hal hal;
  assert(AudioSequencer::play(hal, ALERT_SENSOR_LOST, 30));
  assert(static_cast<uint32_t>(hal.samples) == ChimeSynth::totalSamples(p));
  assert(hal.amp_on == 1 && hal.amp_off == 1 && hal.ends == 1);
  Hal silent;
  assert(!AudioSequencer::play(silent, ALERT_SENSOR_LOST, 0) && silent.amp_on == 0);

  // RTC round trip of the bit, alone and with every other audio field
  PersistentState s;
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  PersistentState r;
  const uint8_t masks[] = {ALERT_SENSOR_LOST, ALERT_MASK_ALL, ALERT_FREEZER | ALERT_SENSOR_LOST,
                           ALERT_NOISE_HIGH | ALERT_SENSOR_LOST};
  const NoiseBand bands[] = {NoiseBand::QUIET, NoiseBand::MODERATE, NoiseBand::LOUD};
  for (uint8_t m : masks) {
    for (NoiseBand band : bands) {
      s.alert_mask = m;
      s.noise_band = band;
      s.muted = true;
      assert(PowerManager::serialize(s, buf, sizeof(buf)));
      assert(PowerManager::deserialize(buf, sizeof(buf), r));
      assert(r.alert_mask == m && r.noise_band == band && r.muted);
    }
  }
  // State encoding: audio byte = muted | mask << 1 | band << 6
  const size_t audio_pos = PowerManager::SERIALIZED_SIZE - 4 - PowerManager::CONFIG_SIZE - PowerManager::NTP_SIZE - 1;
  s.muted = false;
  s.alert_mask = ALERT_SENSOR_LOST;
  s.noise_band = NoiseBand::MODERATE;
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  assert(ALERT_SENSOR_LOST == 8 && buf[audio_pos] == ((ALERT_SENSOR_LOST << 1) | (1 << 6)));
  s.alert_mask = ALERT_CHARGE_FULL;
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  assert(ALERT_CHARGE_FULL == 16 && buf[audio_pos] == ((ALERT_CHARGE_FULL << 1) | (1 << 6)));
  // Band value 3 does not exist
  buf[audio_pos] = static_cast<uint8_t>(3 << 6);
  const uint32_t crc = PowerManager::crc32(buf, sizeof(buf) - 4);
  for (int i = 0; i < 4; ++i) buf[sizeof(buf) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  assert(!PowerManager::deserialize(buf, sizeof(buf), r));
}

void testChimeMuteAndEdge() {
  // New alerts sound; an alert that stays active does not repeat
  assert(AlertEvaluator::chimeBits(false, ALERT_NONE, ALERT_FREEZER, false) == ALERT_FREEZER);
  assert(AlertEvaluator::chimeBits(false, ALERT_FREEZER, ALERT_FREEZER, false) == ALERT_NONE);
  assert(AlertEvaluator::chimeBits(false, ALERT_FREEZER, ALERT_FREEZER | ALERT_HUMIDITY, false) == ALERT_HUMIDITY);
  assert(AlertEvaluator::chimeBits(false, ALERT_MASK_ALL, ALERT_NONE, false) == ALERT_NONE);
  // Mute suppresses every chime
  assert(AlertEvaluator::chimeBits(true, ALERT_NONE, ALERT_CLIMATE_MASK, false) == ALERT_NONE);

  // The sequencer plays nothing without alert bits or at volume 0, and never touches the hardware
  struct Hal {
    int drains = 0, begins = 0, amp_on = 0, amp_off = 0, samples = 0, shutdowns = 0, ends = 0;
    bool amp_state = false, amp_during_write = true, fail_codec = false, fail_write = false, fail_bus = false;
    bool beginBus() { ++begins; return !fail_bus; }
    bool configureCodec() { return !fail_codec; }
    void setAmplifier(bool on) { on ? ++amp_on : ++amp_off; amp_state = on; }
    bool write(const int16_t*, size_t n) {
      if (fail_write) return false;
      samples += static_cast<int>(n);
      amp_during_write = amp_during_write && amp_state;
      return true;
    }
    void drain() { ++drains; }
    void shutdownCodec() { ++shutdowns; }
    void endBus() { ++ends; }
  };
  Hal idle;
  assert(!AudioSequencer::play(idle, ALERT_NONE, 30));
  assert(!AudioSequencer::play(idle, ALERT_FREEZER, 0));
  assert(idle.begins == 0 && idle.amp_on == 0 && idle.samples == 0);

  // Playback: amplifier on only while samples flow, off afterwards, bus closed
  Hal hal;
  assert(AudioSequencer::play(hal, ALERT_FREEZER | ALERT_HUMIDITY, 30));
  const uint32_t expected = ChimeSynth::totalSamples(ChimeSynth::pattern(ALERT_FREEZER)) +
                            ChimeSynth::totalSamples(ChimeSynth::pattern(ALERT_HUMIDITY));
  assert(static_cast<uint32_t>(hal.samples) == expected);
  assert(hal.amp_on == 1 && hal.amp_off == 1 && !hal.amp_state && hal.amp_during_write);
  assert(hal.shutdowns == 1 && hal.ends == 1);

  // A failed codec setup still switches the amplifier off and closes the bus
  Hal broken;
  broken.fail_codec = true;
  assert(!AudioSequencer::play(broken, ALERT_FREEZER, 30));
  assert(broken.amp_on == 0 && broken.amp_off == 1 && broken.samples == 0 && broken.ends == 1);

  // A failed bus start and a failed write also end with the amplifier off and the bus closed
  Hal nobus;
  nobus.fail_bus = true;
  assert(!AudioSequencer::play(nobus, ALERT_FREEZER, 30));
  assert(nobus.amp_on == 0 && nobus.amp_off == 1 && nobus.shutdowns == 1 && nobus.ends == 1);
  Hal badwrite;
  badwrite.fail_write = true;
  assert(!AudioSequencer::play(badwrite, ALERT_CLIMATE_MASK, 30));
  assert(badwrite.amp_on == 1 && badwrite.amp_off == 1 && !badwrite.amp_state && badwrite.ends == 1);
  assert(hal.drains == 1);

  // The unmute confirmation plays one short tone of its own
  Hal confirm;
  assert(AudioSequencer::playConfirm(confirm, 30));
  assert(static_cast<uint32_t>(confirm.samples) == ChimeSynth::totalSamples(ChimeSynth::confirmPattern()));
  assert(ChimeSynth::confirmPattern().notes[0].freq_hz != ChimeSynth::pattern(ALERT_FREEZER).notes[0].freq_hz);
  assert(ChimeSynth::confirmPattern().notes[0].freq_hz != ChimeSynth::pattern(ALERT_HUMIDITY).notes[1].freq_hz);
  Hal confirm_mute;
  assert(!AudioSequencer::playConfirm(confirm_mute, 0) && confirm_mute.begins == 0);
}

RtcTime makeRtc(uint8_t hour, uint8_t minute, bool valid = true) {
  RtcTime t;
  t.valid = valid;
  t.hour = hour;
  t.minute = minute;
  return t;
}

void testQuietHoursWindowEdges() {
  const QuietHoursWindow w; // default 22:00..07:00
  assert(w.start_hour == 22 && w.start_minute == 0 && w.end_hour == 7 && w.end_minute == 0);
  assert(!w.isInQuietHours(21, 59));
  assert(w.isInQuietHours(22, 0));
  assert(w.isInQuietHours(23, 59));
  assert(w.isInQuietHours(0, 0));
  assert(w.isInQuietHours(6, 59));
  assert(!w.isInQuietHours(7, 0)); // the end minute is excluded
  assert(!w.isInQuietHours(12, 0));
  // Out-of-range times never suppress
  assert(!w.isInQuietHours(24, 0));
  assert(!w.isInQuietHours(23, 60));
  assert(!w.isInQuietHours(255, 255));
}

void testQuietHoursSameDayAndEmptyWindow() {
  QuietHoursWindow day;
  day.start_hour = 13; day.start_minute = 30; day.end_hour = 15; day.end_minute = 15;
  assert(!day.isInQuietHours(13, 29));
  assert(day.isInQuietHours(13, 30));
  assert(day.isInQuietHours(14, 0));
  assert(day.isInQuietHours(15, 14));
  assert(!day.isInQuietHours(15, 15));
  assert(!day.isInQuietHours(23, 0));
  assert(!day.isInQuietHours(0, 0));

  QuietHoursWindow empty;
  empty.start_hour = 8; empty.start_minute = 0; empty.end_hour = 8; empty.end_minute = 0;
  for (uint8_t h = 0; h < 24; ++h) {
    assert(!empty.isInQuietHours(h, 0));
    assert(!empty.isInQuietHours(h, 59));
  }
  assert(!empty.isActive(makeRtc(8, 0)));

  // An out-of-range window bound never suppresses
  QuietHoursWindow bad;
  bad.start_hour = 24;
  assert(!bad.isInQuietHours(23, 0) && !bad.isInQuietHours(3, 0));
  bad = QuietHoursWindow();
  bad.end_minute = 60;
  assert(!bad.isInQuietHours(23, 0) && !bad.isInQuietHours(3, 0));
  bad = QuietHoursWindow();
  bad.start_minute = 200;
  assert(!bad.isInQuietHours(23, 0));
  bad = QuietHoursWindow();
  bad.end_hour = 99;
  assert(!bad.isInQuietHours(3, 0));
}

void testQuietHoursNeedsValidRtc() {
  const QuietHoursWindow w;
  assert(w.isActive(makeRtc(23, 0)));
  assert(w.isActive(makeRtc(3, 0)));
  assert(!w.isActive(makeRtc(12, 0)));
  // Invalid time: no suppression, also inside the nominal window
  assert(!w.isActive(makeRtc(23, 0, false)));
  RtcTime stopped = makeRtc(23, 0);
  stopped.oscillator_stopped = true;
  assert(!w.isActive(stopped));
  assert(!w.isActive(RtcTime()));
}

void testQuietHoursSuppressChimeButLatchState() {
  const QuietHoursWindow w;
  const RtcTime night = makeRtc(23, 30);
  const RtcTime morning = makeRtc(7, 0);
  // A new alert in quiet hours stays silent
  uint8_t mask = ALERT_NONE;
  uint8_t current = ALERT_FREEZER;
  assert(AlertEvaluator::chimeBits(false, mask, current, w.isActive(night)) == ALERT_NONE);
  mask = current; // the caller stores the mask while quiet
  // The window ends and the alert stays active: no replay
  assert(!w.isActive(morning));
  assert(AlertEvaluator::chimeBits(false, mask, current, w.isActive(morning)) == ALERT_NONE);
  // A new second alert after the window sounds, the latched one does not
  current = ALERT_FREEZER | ALERT_HUMIDITY;
  assert(AlertEvaluator::chimeBits(false, mask, current, false) == ALERT_HUMIDITY);
  // Without a quiet flag the default behaviour stays
  assert(AlertEvaluator::chimeBits(false, ALERT_NONE, ALERT_FREEZER, false) == ALERT_FREEZER);
  assert(AlertEvaluator::chimeBits(true, ALERT_NONE, ALERT_FREEZER, false) == ALERT_NONE);
  // Invalid RTC: the chime sounds
  assert(AlertEvaluator::chimeBits(false, ALERT_NONE, ALERT_FREEZER, w.isActive(makeRtc(23, 0, false))) ==
         ALERT_FREEZER);
}

void testQuietHoursHeaderMarker() {
  Shtc3Reading onboard;
  BatteryReading battery;
  battery.valid = true;
  battery.voltage_v = 3.9f;
  battery.percentage = 100;
  St7305Framebuffer off, on;
  DashboardView::render(off, onboard, battery, nullptr, 0, "12:34", 0, nullptr, false);
  DashboardView::render(on, onboard, battery, nullptr, 0, "12:34", 0, nullptr, true);

  // Only the marker area differs, and the marker has ink
  const uint16_t x0 = DashboardView::HEADER_QUIET_X;
  const uint16_t x1 = x0 + DashboardView::HEADER_QUIET_W;
  size_t ink = 0;
  for (uint16_t y = 0; y < St7305Framebuffer::HEIGHT; ++y) {
    for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
      if (off.getPixel(x, y) != on.getPixel(x, y)) {
        assert(x >= x0 && x < x1 && y >= DashboardView::HEADER_QUIET_Y &&
               y < DashboardView::HEADER_QUIET_Y + DashboardView::HEADER_QUIET_H);
        ++ink;
      }
    }
  }
  assert(ink > 10);
  // The badge has black ink (the Q) inside a white box, so both colours occur in the marker area
  bool white = false, black = false;
  for (uint16_t y = DashboardView::HEADER_QUIET_Y; y < DashboardView::HEADER_QUIET_Y + DashboardView::HEADER_QUIET_H; ++y) {
    for (uint16_t x = x0; x < x1; ++x) {
      (on.getPixel(x, y) ? black : white) = true;
    }
  }
  assert(white && black);
  // No clipping: the marker lies between the title end (224) and the clock start (244), inside
  // the header bar, with blank pixels (bar colour) on both sides.
  assert(x0 > 224 && x1 < DashboardView::HEADER_CLOCK_X);
  assert(DashboardView::HEADER_QUIET_Y + DashboardView::HEADER_QUIET_H + 2 <= DashboardView::HEADER_HEIGHT);
  for (uint16_t y = 0; y < DashboardView::HEADER_HEIGHT - 2; ++y) {
    assert(on.getPixel(x0 - 1, y) == off.getPixel(x0 - 1, y));
    assert(on.getPixel(x1, y) == off.getPixel(x1, y));
  }
  // The same marker shows in the other views through SystemInfo
  SystemInfo info;
  info.quiet_hours = true;
  St7305Framebuffer diag_on, diag_off;
  DashboardView::renderMode(diag_on, DisplayMode::SYSTEM_DIAGNOSTICS, onboard, battery, nullptr, 0, info, "12:34", 0);
  info.quiet_hours = false;
  DashboardView::renderMode(diag_off, DisplayMode::SYSTEM_DIAGNOSTICS, onboard, battery, nullptr, 0, info, "12:34", 0);
  St7305Framebuffer stats_on, stats_off;
  info.quiet_hours = true;
  DashboardView::renderMode(stats_on, DisplayMode::SENSOR_STATS, onboard, battery, nullptr, 0, info, "12:34", 0);
  info.quiet_hours = false;
  DashboardView::renderMode(stats_off, DisplayMode::SENSOR_STATS, onboard, battery, nullptr, 0, info, "12:34", 0);
  assert(diag_on.getPixel(x0 + 2, 10) != diag_off.getPixel(x0 + 2, 10) ||
         diag_on.getPixel(x0 + 4, 7) != diag_off.getPixel(x0 + 4, 7) ||
         memcmp(diag_on.data(), diag_off.data(), St7305Framebuffer::BYTE_COUNT) != 0);
  assert(memcmp(stats_on.data(), stats_off.data(), St7305Framebuffer::BYTE_COUNT) != 0);
  // The low battery banner replaces the header and shows no marker
  battery.low_battery = true;
  St7305Framebuffer low_on, low_off;
  DashboardView::render(low_on, onboard, battery, nullptr, 0, "12:34", 0, nullptr, true);
  DashboardView::render(low_off, onboard, battery, nullptr, 0, "12:34", 0, nullptr, false);
  assert(memcmp(low_on.data(), low_off.data(), St7305Framebuffer::BYTE_COUNT) == 0);
}

void testChimePcmSynthesis() {
  const ChimePattern freezer = ChimeSynth::pattern(ALERT_FREEZER);
  const ChimePattern humidity = ChimeSynth::pattern(ALERT_HUMIDITY);
  assert(ChimeSynth::pattern(ALERT_NONE).notes == nullptr);
  // Duration: 3 x 120 ms + 2 x 80 ms = 520 ms; 160 + 40 + 240 = 440 ms
  assert(ChimeSynth::totalSamples(freezer) == 520u * 16);
  assert(ChimeSynth::totalSamples(humidity) == 440u * 16);

  static int16_t pcm[16 * 520];
  assert(ChimeSynth::render(freezer, 100, 0, pcm, 16 * 520) == 16u * 520);
  // Nothing after the end; a short tail returns the remainder
  int16_t tail[8];
  assert(ChimeSynth::render(freezer, 100, 16 * 520, tail, 8) == 0);
  assert(ChimeSynth::render(freezer, 100, 16 * 520 - 3, tail, 8) == 3);
  // Chunked rendering equals one-shot rendering
  static int16_t chunked[16 * 520];
  size_t pos = 0;
  while (pos < 16u * 520) {
    const size_t n = ChimeSynth::render(freezer, 100, pos, chunked + pos, 100);
    assert(n > 0);
    pos += n;
  }
  assert(memcmp(pcm, chunked, sizeof(pcm)) == 0);

  // Frequency: count rising zero crossings inside the first note (880 Hz, 120 ms ~ 105 cycles)
  int crossings = 0;
  for (size_t i = 1; i < 16 * 120; ++i) {
    if (pcm[i - 1] <= 0 && pcm[i] > 0) ++crossings;
  }
  assert(crossings >= 104 && crossings <= 106);
  // Pause between notes is silent
  for (size_t i = 16 * 120; i < 16 * 200; ++i) assert(pcm[i] == 0);

  // Volume: the peak scales with the percentage and stays inside the sample range
  int peak100 = 0;
  for (size_t i = 0; i < 16 * 520; ++i) peak100 = abs(pcm[i]) > peak100 ? abs(pcm[i]) : peak100;
  assert(peak100 > 32000 && peak100 <= 32767);
  static int16_t quiet[16 * 520];
  assert(ChimeSynth::render(freezer, 30, 0, quiet, 16 * 520) == 16u * 520);
  int peak30 = 0;
  for (size_t i = 0; i < 16 * 520; ++i) peak30 = abs(quiet[i]) > peak30 ? abs(quiet[i]) : peak30;
  assert(peak30 > 9500 && peak30 < 10000);
  // Volume 0 is silent; volume above 100 clamps to full scale
  static int16_t mute[16 * 520];
  assert(ChimeSynth::render(freezer, 0, 0, mute, 16 * 520) == 16u * 520);
  for (size_t i = 0; i < 16 * 520; ++i) assert(mute[i] == 0);
  assert(ChimeSynth::render(freezer, 250, 0, mute, 16 * 520) == 16u * 520);
  assert(memcmp(mute, pcm, sizeof(pcm)) == 0);
  // Fade in and out: first and last sample of a note start and end at 0
  assert(pcm[0] == 0 && pcm[16 * 120 - 1] == 0);
  // Null handling
  assert(ChimeSynth::render({nullptr, 0}, 30, 0, pcm, 4) == 0);
  assert(ChimeSynth::render(freezer, 30, 0, nullptr, 4) == 0);
}

void testAudioStatePersistence() {
  PersistentState s;
  s.muted = true;
  s.alert_mask = ALERT_CLIMATE_MASK;
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  PersistentState r;
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(r.muted && r.alert_mask == ALERT_CLIMATE_MASK);
  s.muted = false;
  s.alert_mask = ALERT_HUMIDITY;
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(!r.muted && r.alert_mask == ALERT_HUMIDITY);
  // No reserved bit is left: the band value 3 with a valid CRC is rejected
  buf[PowerManager::SERIALIZED_SIZE - 4 - PowerManager::CONFIG_SIZE - PowerManager::NTP_SIZE - 1] = 0xC0;
  const uint32_t crc = PowerManager::crc32(buf, sizeof(buf) - 4);
  for (int i = 0; i < 4; ++i) buf[sizeof(buf) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  assert(!PowerManager::deserialize(buf, sizeof(buf), r));
  // The old format (version 3) is rejected, even with a valid CRC
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  buf[4] = 3;
  const uint32_t crc3 = PowerManager::crc32(buf, sizeof(buf) - 4);
  for (int i = 0; i < 4; ++i) buf[sizeof(buf) - 4 + i] = static_cast<uint8_t>(crc3 >> (8 * i));
  assert(!PowerManager::deserialize(buf, sizeof(buf), r));
  // The defaults are unmuted and quiet
  PersistentState d;
  assert(!d.muted && d.alert_mask == ALERT_NONE);
}

// Fake ADC for the sampler: serves a constant-amplitude square wave on both channels, counts the power calls.
struct FakeMicHal {
  bool bus_ok = true;
  bool adc_ok = true;
  size_t reads_before_fail = static_cast<size_t>(-1);
  int16_t amplitude = 1000;
  size_t reads = 0;
  int begin_calls = 0, shutdown_calls = 0, end_calls = 0;

  bool beginBus() { ++begin_calls; return bus_ok; }
  bool configureAdc() { return adc_ok; }
  size_t read(int16_t* samples, size_t count) {
    if (reads++ >= reads_before_fail) {
      return 0;
    }
    for (size_t i = 0; i < count; ++i) {
      // Square wave per channel: the sign alternates per stereo frame, so the mean is zero
      samples[i] = ((i / 2) & 1) ? static_cast<int16_t>(-amplitude) : amplitude;
    }
    return count;
  }
  void shutdownAdc() { ++shutdown_calls; }
  void endBus() { ++end_calls; }
};

void testNoiseRmsCalculation() {
  RmsAccumulator acc;
  // No samples and null input give 0
  assert(acc.rms() == 0 && acc.sampleCount() == 0);
  acc.add(nullptr, 10);
  assert(acc.sampleCount() == 0 && acc.rms() == 0);

  // Silence
  int16_t buf[1600] = {0};
  acc.add(buf, 1600);
  assert(acc.rms() == 0 && acc.sampleCount() == 1600);

  // Sine of amplitude A has RMS A / sqrt(2); 100 full periods of 160 samples
  const float amp = 10000.0f;
  for (size_t i = 0; i < 1600; ++i) {
    buf[i] = static_cast<int16_t>(lrintf(amp * sinf(6.28318530718f * static_cast<float>(i % 160) / 160.0f)));
  }
  acc.reset();
  assert(acc.sampleCount() == 0);
  acc.add(buf, 1600);
  assert(abs(static_cast<int>(acc.rms()) - 7071) <= 2);

  // Chunked input gives the same result as one block
  RmsAccumulator chunked;
  chunked.add(buf, 100);
  chunked.add(buf + 100, 1500);
  assert(chunked.rms() == acc.rms());

  // DC offset counts as energy
  int16_t dc[64];
  for (int16_t& v : dc) v = -500;
  RmsAccumulator dc_acc;
  dc_acc.add(dc, 64);
  assert(dc_acc.rms() == 500);

  // Full scale: +32767 and the most negative value both clamp to 32767 without overflow
  int16_t full[64];
  for (size_t i = 0; i < 64; ++i) full[i] = (i & 1) ? INT16_MIN : INT16_MAX;
  RmsAccumulator clip;
  clip.add(full, 64);
  assert(clip.rms() == 32767);
  // Many windows of full scale do not overflow the 64-bit sum
  for (int i = 0; i < 100000; ++i) clip.add(full, 64);
  assert(clip.rms() == 32767);
}

void testNoiseBandThresholds() {
  using NC = NoiseClassifier;
  // From quiet: bands switch at the trigger levels
  assert(NC::classify(NoiseBand::QUIET, 0) == NoiseBand::QUIET);
  assert(NC::classify(NoiseBand::QUIET, NC::MODERATE_TRIGGER_RMS - 1) == NoiseBand::QUIET);
  assert(NC::classify(NoiseBand::QUIET, NC::MODERATE_TRIGGER_RMS) == NoiseBand::MODERATE);
  assert(NC::classify(NoiseBand::QUIET, NC::LOUD_TRIGGER_RMS - 1) == NoiseBand::MODERATE);
  assert(NC::classify(NoiseBand::QUIET, NC::LOUD_TRIGGER_RMS) == NoiseBand::LOUD);
  assert(NC::classify(NoiseBand::QUIET, 32767) == NoiseBand::LOUD);

  // Hysteresis: moderate holds down to the release level
  assert(NC::classify(NoiseBand::MODERATE, NC::MODERATE_RELEASE_RMS) == NoiseBand::MODERATE);
  assert(NC::classify(NoiseBand::MODERATE, NC::MODERATE_TRIGGER_RMS - 1) == NoiseBand::MODERATE);
  assert(NC::classify(NoiseBand::MODERATE, NC::MODERATE_RELEASE_RMS - 1) == NoiseBand::QUIET);
  // Loud holds down to its release level, then falls to moderate or quiet
  assert(NC::classify(NoiseBand::LOUD, NC::LOUD_TRIGGER_RMS - 1) == NoiseBand::LOUD);
  assert(NC::classify(NoiseBand::LOUD, NC::LOUD_RELEASE_RMS) == NoiseBand::LOUD);
  assert(NC::classify(NoiseBand::LOUD, NC::LOUD_RELEASE_RMS - 1) == NoiseBand::MODERATE);
  assert(NC::classify(NoiseBand::LOUD, 0) == NoiseBand::QUIET);

  // A level that jitters around the trigger does not flip the band on every window
  NoiseBand band = NoiseBand::QUIET;
  const uint16_t jitter[] = {290, 310, 290, 250, 310, 210, 190, 290};
  const NoiseBand expected[] = {
      NoiseBand::QUIET, NoiseBand::MODERATE, NoiseBand::MODERATE, NoiseBand::MODERATE,
      NoiseBand::MODERATE, NoiseBand::MODERATE, NoiseBand::QUIET, NoiseBand::QUIET};
  for (size_t i = 0; i < sizeof(jitter) / sizeof(jitter[0]); ++i) {
    band = NC::classify(band, jitter[i]);
    assert(band == expected[i]);
  }

  // Hysteresis gaps are real
  assert(NC::MODERATE_RELEASE_RMS < NC::MODERATE_TRIGGER_RMS);
  assert(NC::LOUD_RELEASE_RMS < NC::LOUD_TRIGGER_RMS);
  assert(NC::MODERATE_TRIGGER_RMS < NC::LOUD_RELEASE_RMS);

  // Labels and levels
  assert(strcmp(NC::label(NoiseBand::QUIET), "QUIET") == 0);
  assert(strcmp(NC::label(NoiseBand::MODERATE), "MODERATE") == 0);
  assert(strcmp(NC::label(NoiseBand::LOUD), "LOUD") == 0);
  assert(NC::dbfs(0) == NC::DBFS_FLOOR);
  assert(NC::dbfs(32767) == 0);
  assert(NC::dbfs(16384) == -6);
  assert(NC::dbfs(1) == -90);
  const NoiseReading r = NC::makeReading(NoiseBand::QUIET, 3500);
  assert(r.valid && r.rms == 3500 && r.band == NoiseBand::LOUD);
  assert(!NoiseReading{}.valid);
}

void testNoiseSamplerPowersDownOnEveryPath() {
  // Normal window: constant 1000 amplitude gives RMS 1000 and a moderate band
  FakeMicHal ok;
  NoiseReading r = NoiseSampler::measure(ok, NoiseBand::QUIET);
  assert(r.valid && r.rms == 1000 && r.band == NoiseBand::MODERATE);
  assert(ok.begin_calls == 1 && ok.shutdown_calls == 1 && ok.end_calls == 1);
  // The window reads exactly settle + 5 s of stereo samples (rounded up to whole chunks)
  const size_t total = (NoiseSampler::SETTLE_FRAMES + NoiseSampler::SAMPLE_RATE_HZ * 5) * 2;
  assert(ok.reads == (total + NoiseSampler::CHUNK_SAMPLES - 1) / NoiseSampler::CHUNK_SAMPLES);

  // Bus failure and ADC failure: no reading, ADC still powered down
  FakeMicHal bus;
  bus.bus_ok = false;
  assert(!NoiseSampler::measure(bus, NoiseBand::QUIET).valid);
  assert(bus.shutdown_calls == 1 && bus.end_calls == 1);
  FakeMicHal adc;
  adc.adc_ok = false;
  assert(!NoiseSampler::measure(adc, NoiseBand::QUIET).valid);
  assert(adc.shutdown_calls == 1 && adc.end_calls == 1);

  // A read that stops mid-window discards the window instead of reporting a partial one
  FakeMicHal cut;
  cut.reads_before_fail = 10;
  assert(!NoiseSampler::measure(cut, NoiseBand::QUIET).valid);
  assert(cut.shutdown_calls == 1 && cut.end_calls == 1);

  // A zero length window is rejected
  FakeMicHal zero;
  assert(!NoiseSampler::measure(zero, NoiseBand::QUIET, 0).valid);
  assert(zero.shutdown_calls == 1);

  // Settling samples do not count: a loud start followed by silence measures silence
  struct SettleHal : FakeMicHal {
    size_t served = 0;
    size_t read(int16_t* samples, size_t count) {
      for (size_t i = 0; i < count; ++i) {
        samples[i] = served < NoiseSampler::SETTLE_FRAMES * 2 ? INT16_MAX : 0;
        ++served;
      }
      return count;
    }
  } settle;
  r = NoiseSampler::measure(settle, NoiseBand::QUIET, 1000);
  // The window after the settling time is silent: both channels are SILENT, so there is no band
  assert(!r.valid && r.mic.valid && r.mic.bothFailed());
  assert(r.mic.channel[0] == MicStatus::SILENT && r.mic.channel[1] == MicStatus::SILENT);
}

// Serves a square wave whose amplitude follows the 100 ms block of the window (after the settling).
struct BlockMicHal : FakeMicHal {
  int16_t (*amplitude_of_block)(size_t block) = nullptr;
  size_t served = 0;
  size_t read(int16_t* samples, size_t count) {
    for (size_t i = 0; i < count; ++i, ++served) {
      const size_t settle = NoiseSampler::SETTLE_FRAMES * 2;
      const size_t block = served < settle ? 0 : (served - settle) / (NoiseSampler::BLOCK_FRAMES * 2);
      const int16_t a = amplitude_of_block(block);
      samples[i] = ((served / 2) & 1) ? static_cast<int16_t>(-a) : a;
    }
    return count;
  }
};

void testNoisePercentilesSteadyTone() {
  FakeMicHal hal; // amplitude 1000 in every block
  const NoiseReading r = NoiseSampler::measure(hal, NoiseBand::QUIET);
  assert(r.valid && r.levels_valid);
  assert(r.l10_rms == 1000 && r.l90_rms == 1000);
  assert(NoiseSampler::BLOCK_FRAMES * 50 == NoiseSampler::SAMPLE_RATE_HZ * 5);
  // The microphones stay on for the existing window only
  const size_t total = (NoiseSampler::SETTLE_FRAMES + NoiseSampler::SAMPLE_RATE_HZ * 5) * 2;
  assert(hal.reads == (total + NoiseSampler::CHUNK_SAMPLES - 1) / NoiseSampler::CHUNK_SAMPLES);
}

void testNoisePercentilesSingleLoudBlock() {
  BlockMicHal hal;
  hal.amplitude_of_block = [](size_t b) -> int16_t { return b == 17 ? 20000 : 500; };
  const NoiseReading r = NoiseSampler::measure(hal, NoiseBand::QUIET);
  // One block is 2 % of the window: L10 and L90 ignore it, the window RMS does not
  assert(r.valid && r.levels_valid && r.l10_rms == 500 && r.l90_rms == 500);
  assert(r.rms > 2000);
  // Five loud blocks (10 %) leave L10 at the background; six loud blocks raise it
  hal = BlockMicHal{};
  hal.amplitude_of_block = [](size_t b) -> int16_t { return b < 5 ? 20000 : 500; };
  NoiseReading five = NoiseSampler::measure(hal, NoiseBand::QUIET);
  assert(five.l10_rms == 500 && five.l90_rms == 500);
  hal = BlockMicHal{};
  hal.amplitude_of_block = [](size_t b) -> int16_t { return b < 6 ? 20000 : 500; };
  NoiseReading six = NoiseSampler::measure(hal, NoiseBand::QUIET);
  assert(six.l10_rms == 20000 && six.l90_rms == 500);
}

void testNoisePercentilesRamp() {
  BlockMicHal hal;
  hal.amplitude_of_block = [](size_t b) -> int16_t { return static_cast<int16_t>(100 * (b + 1)); };
  const NoiseReading r = NoiseSampler::measure(hal, NoiseBand::QUIET);
  // 50 blocks of 100..5000: 5 blocks exceed L10 (4500), 45 blocks exceed L90 (500)
  assert(r.valid && r.levels_valid && r.l10_rms == 4500 && r.l90_rms == 500);
  assert(r.l10_rms >= r.l90_rms);
  // A shorter window has fewer blocks: 1 s gives 10 blocks, L10 is the 9th and L90 the 1st
  BlockMicHal shortw;
  shortw.amplitude_of_block = [](size_t b) -> int16_t { return static_cast<int16_t>(100 * (b + 1)); };
  const NoiseReading s = NoiseSampler::measure(shortw, NoiseBand::QUIET, 1000);
  assert(s.levels_valid && s.l10_rms == 900 && s.l90_rms == 100);
}

void testNoisePercentilesWindowLengthAndChannels() {
  // 250 ms: two whole blocks, the rest of 50 ms is ignored
  BlockMicHal part;
  part.amplitude_of_block = [](size_t b) -> int16_t { return b == 0 ? 200 : 800; };
  NoiseReading r = NoiseSampler::measure(part, NoiseBand::QUIET, 250);
  assert(r.levels_valid && r.l10_rms == 800 && r.l90_rms == 200);
  // 6 s: more than 50 blocks give no percentiles, the level stays valid
  FakeMicHal longw;
  r = NoiseSampler::measure(longw, NoiseBand::QUIET, 6000);
  assert(r.valid && !r.levels_valid);
  // One silent channel: the percentiles use the usable channel only, like the window RMS
  struct OneChannelHal : FakeMicHal {
    size_t read(int16_t* samples, size_t count) {
      for (size_t i = 0; i < count; ++i) {
        samples[i] = (i & 1) ? static_cast<int16_t>(0) : (((i / 2) & 1) ? -1000 : 1000);
      }
      return count;
    }
  } one;
  r = NoiseSampler::measure(one, NoiseBand::QUIET);
  assert(r.valid && r.mic.channelOk(0) && !r.mic.channelOk(1));
  assert(r.rms == 1000 && r.levels_valid && r.l10_rms == 1000 && r.l90_rms == 1000);
}

void testNoisePercentilesFailedWindow() {
  // Both channels silent after the settling: no level, no percentiles
  struct SilentHal : FakeMicHal {
    size_t read(int16_t* samples, size_t count) {
      for (size_t i = 0; i < count; ++i) samples[i] = 0;
      return count;
    }
  } silent;
  NoiseReading r = NoiseSampler::measure(silent, NoiseBand::QUIET);
  assert(!r.valid && !r.levels_valid && r.l10_rms == 0 && r.l90_rms == 0);
  FakeMicHal bus;
  bus.bus_ok = false;
  r = NoiseSampler::measure(bus, NoiseBand::QUIET);
  assert(!r.levels_valid);
  FakeMicHal cut;
  cut.reads_before_fail = 10;
  r = NoiseSampler::measure(cut, NoiseBand::QUIET);
  assert(!r.levels_valid);
  // Fewer samples than one block: no percentile
  FakeMicHal tiny;
  r = NoiseSampler::measure(tiny, NoiseBand::QUIET, 50);
  assert(r.valid && !r.levels_valid);
  assert(!NoiseReading{}.levels_valid);
}

void testNoisePercentilesText() {
  char line[40];
  NoiseReading r;
  r.valid = true;
  r.levels_valid = true;
  r.l10_rms = 1000; // -30 dBFS
  r.l90_rms = 100;  // -50 dBFS
  MicCalibration uncal;
  DashboardView::formatNoisePercentiles(line, sizeof(line), r, uncal);
  assert(strcmp(line, "L10/L90: -30/-50 DBFS") == 0);
  MicCalibration cal;
  MicCalibration::fromFloor(100, MicCalibration::DEFAULT_SPL_OFFSET_DB, cal);
  DashboardView::formatNoisePercentiles(line, sizeof(line), r, cal);
  assert(strcmp(line, "L10/L90: 60/40 DBA") == 0);
  DashboardView::formatNoisePercentiles(line, sizeof(line), NoiseReading{}, cal);
  assert(strcmp(line, "L10/L90: --") == 0);
  r.l10_rms = 32767;
  r.l90_rms = 32767;
  MicCalibration loud = cal;
  loud.spl_offset_db = MicCalibration::MAX_SPL_OFFSET_DB;
  DashboardView::formatNoisePercentiles(line, sizeof(line), r, loud);
  assert(strcmp(line, "L10/L90: 140/140 DBA") == 0 && strlen(line) == 20);
}

void testDiagnosticsPercentileLineBounds() {
  BatteryReading battery;
  battery.valid = true;
  battery.voltage_v = 3.9f;
  battery.percentage = 70;
  SystemInfo none, wide;
  wide.mic_cal.calibrated = true;
  wide.mic_cal.spl_offset_db = MicCalibration::MAX_SPL_OFFSET_DB;
  wide.noise.valid = true;
  wide.noise.levels_valid = true;
  wide.noise.l10_rms = 32767;
  wide.noise.l90_rms = 32767;
  St7305Framebuffer a, b;
  DashboardView::renderDiagnostics(a, battery, none, "12:00");
  DashboardView::renderDiagnostics(b, battery, wide, "12:00");
  const uint16_t X0 = DashboardView::PERCENTILE_X, Y0 = DashboardView::PERCENTILE_Y;
  size_t ink = 0;
  for (uint16_t y = Y0; y < Y0 + 7; ++y) {
    for (uint16_t x = X0; x < St7305Framebuffer::WIDTH; ++x) {
      ink += b.getPixel(x, y) ? 1 : 0;
      // "L10/L90: 140/140 DBA" = 20 glyphs of 6 px: the text ends before the margin
      if (x >= X0 + 20 * 6 || x >= St7305Framebuffer::WIDTH - 8) {
        assert(!b.getPixel(x, y));
      }
    }
  }
  assert(ink > 50);
  // Only the percentile text differs on the lines above the noise line (SD FREE ends at x = 236)
  for (uint16_t y = 0; y < 204; ++y) {
    for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
      if (y >= Y0 && y < Y0 + 7 && x >= X0) continue;
      assert(a.getPixel(x, y) == b.getPixel(x, y));
    }
  }
  // The text stays clear of the lines above (SD FREE ends y = 158) and below (uptime at y = 164)
  assert(X0 >= 10 + 19 * 12);
  assert(Y0 + 7 <= 164);
}

void testSdLogPercentileColumns() {
  char buf[SdLogCodec::MAX_ROW_SIZE];
  const size_t hl = SdLogCodec::formatHeader(buf, sizeof(buf));
  assert(hl > 0);
  size_t header_commas = 0;
  for (size_t i = 0; i < hl; ++i) header_commas += buf[i] == ',';
  assert(header_commas == 20); // 21 columns
  assert(strstr(buf, "s3_battery_pct,noise_rms,noise_db,noise_band,noise_events,noise_l10,noise_l90\n") != nullptr);

  RtcTime t;
  t.valid = true; t.year = 2026; t.month = 3; t.day = 9; t.hour = 7; t.minute = 5; t.second = 1;
  char path[SdLogCodec::MAX_PATH_SIZE];
  assert(SdLogCodec::formatFilename(&t, path, sizeof(path)) == strlen("/sdcard/climate_v4_202603.csv"));
  assert(strcmp(path, "/sdcard/climate_v4_202603.csv") == 0);
  assert(SdLogCodec::formatFilename(&t, path, strlen("/sdcard/climate_v4_202603.csv")) == 0);

  const MicCalibration cal;
  NoiseReading noise = NoiseClassifier::makeReading(NoiseBand::QUIET, 1000);
  noise.levels_valid = true;
  noise.l10_rms = 1000; // 60 dB
  noise.l90_rms = 100;  // 40 dB
  assert(SdLogCodec::formatRow(&t, nullptr, nullptr, nullptr, 0, 0, &noise, &cal, buf, sizeof(buf)) > 0);
  assert(strcmp(buf, "2026-03-09 07:05:01,,,,,,,,,,,,,,,1000,60,MODERATE,0,60,40\n") == 0);
  size_t row_commas = 0;
  for (size_t i = 0; buf[i] != '\0'; ++i) row_commas += buf[i] == ',';
  assert(row_commas == header_commas);

  // A window without percentiles keeps the other noise fields and leaves the two new ones empty
  noise.levels_valid = false;
  SdLogCodec::formatRow(&t, nullptr, nullptr, nullptr, 0, 0, &noise, &cal, buf, sizeof(buf));
  assert(strstr(buf, ",1000,60,MODERATE,0,,\n") != nullptr);

  // A failed window or no window: every noise field is empty
  const NoiseReading invalid;
  SdLogCodec::formatRow(&t, nullptr, nullptr, nullptr, 0, 0, &invalid, &cal, buf, sizeof(buf));
  assert(strcmp(buf, "2026-03-09 07:05:01,,,,,,,,,,,,,,,,,,,,\n") == 0);
  SdLogCodec::formatRow(&t, nullptr, nullptr, nullptr, 0, 0, nullptr, &cal, buf, sizeof(buf));
  row_commas = 0;
  for (size_t i = 0; buf[i] != '\0'; ++i) row_commas += buf[i] == ',';
  assert(row_commas == header_commas);

  // Worst-case row stays below the buffer limit
  BatteryReading bat;
  bat.valid = true; bat.percentage = 100; bat.voltage_v = 4.2f; bat.usb_powered = true;
  Shtc3Reading onboard = makeOnboard(-20.5f, 100.0f);
  SwitchBotReading slots[MAX_SWITCHBOT_SENSORS];
  for (auto& r : slots) {
    r.valid = true; r.temperature_c = -40.0f; r.humidity_pct = 100; r.battery_pct = 100; r.updated_ms = 10;
  }
  noise = NoiseClassifier::makeReading(NoiseBand::QUIET, 32767);
  noise.levels_valid = true;
  noise.l10_rms = 32767;
  noise.l90_rms = 32767;
  noise.events = 65535;
  assert(SdLogCodec::formatRow(&t, &bat, &onboard, slots, 3, 20, &noise, &cal, buf, sizeof(buf)) > 0);
  assert(strstr(buf, ",32767,90,LOUD,65535,90,90\n") != nullptr);
  assert(strlen(buf) < SdLogCodec::MAX_ROW_SIZE);
}

void testSdLogV4FileNames() {
  int y = 0, m = 0;
  assert(SdLogCodec::parseLogFileName("climate_v4_202611.csv", y, m) && y == 2026 && m == 11);
  assert(SdLogCodec::isLogFileName("climate_v4_200001.csv"));
  assert(SdLogCodec::isLogFileName("climate_v4_202611.csv")); // old files stay valid
  assert(SdLogCodec::isLogFileName("climate_v2_202611.csv"));
  assert(!SdLogCodec::isLogFileName("climate_v5_202611.csv"));
  assert(!SdLogCodec::isLogFileName("climate_v4_202613.csv"));
  assert(!SdLogCodec::isLogFileName("climate_v4_202611.csv "));
  assert(!SdLogCodec::isLogFileName("../climate_v4_202611.csv"));
  assert(SdLogCodec::isConsoleFileName("climate_v4_202611.csv"));
  RtcTime now;
  now.valid = true; now.year = 2026; now.month = 6; now.day = 1;
  assert(SdLogCodec::shouldDeleteLog("climate_v4_202601.csv", now, 3));
  assert(!SdLogCodec::shouldDeleteLog("climate_v4_202606.csv", now, 1));
  assert(!SdLogCodec::shouldDeleteLog("climate_v4_202605.csv", now, 3));
}

void testSdLogNoiseColumns() {
  char buf[SdLogCodec::MAX_ROW_SIZE];
  const size_t hl = SdLogCodec::formatHeader(buf, sizeof(buf));
  assert(hl > 0);
  size_t header_commas = 0;
  for (size_t i = 0; i < hl; ++i) header_commas += buf[i] == ',';
  assert(header_commas == 20); // 21 columns
  assert(strstr(buf, "s3_battery_pct,noise_rms,noise_db,noise_band,noise_events,noise_l10,noise_l90\n") != nullptr);

  RtcTime t;
  t.valid = true; t.year = 2026; t.month = 3; t.day = 9; t.hour = 7; t.minute = 5; t.second = 1;
  // The v2 file name never matches the old 15-column file
  char path[SdLogCodec::MAX_PATH_SIZE];
  assert(SdLogCodec::formatFilename(&t, path, sizeof(path)) == strlen("/sdcard/climate_v4_202603.csv"));
  assert(strcmp(path, "/sdcard/climate_v4_202603.csv") == 0);
  assert(SdLogCodec::formatFilename(&t, path, strlen("/sdcard/climate_v4_202603.csv")) == 0); // no room for NUL
  assert(SdLogCodec::formatFilename(&t, path, strlen("/sdcard/climate_v4_202603.csv") + 1) > 0);
  t.year = 2099; t.month = 12;
  assert(SdLogCodec::formatFilename(&t, path, sizeof(path)) > 0);
  assert(strcmp(path, "/sdcard/climate_v4_209912.csv") == 0);
  assert(strlen(path) < SdLogCodec::MAX_PATH_SIZE);
  t.year = 2026; t.month = 3;

  // Valid noise: RMS 1000 = -30 dBFS, +90 dB offset = 60 dB, band MODERATE
  NoiseReading noise = NoiseClassifier::makeReading(NoiseBand::QUIET, 1000);
  const MicCalibration cal;
  assert(SdLogCodec::formatRow(&t, nullptr, nullptr, nullptr, 0, 0, &noise, &cal, buf, sizeof(buf)) > 0);
  assert(strcmp(buf, "2026-03-09 07:05:01,,,,,,,,,,,,,,,1000,60,MODERATE,0,,\n") == 0);
  // The calibration offset applies
  MicCalibration cal2;
  cal2.spl_offset_db = 100;
  SdLogCodec::formatRow(&t, nullptr, nullptr, nullptr, 0, 0, &noise, &cal2, buf, sizeof(buf));
  assert(strstr(buf, ",1000,70,MODERATE,0,,\n") != nullptr);
  // A null calibration uses the default
  SdLogCodec::formatRow(&t, nullptr, nullptr, nullptr, 0, 0, &noise, nullptr, buf, sizeof(buf));
  assert(strstr(buf, ",1000,60,MODERATE,0,,\n") != nullptr);
  // Every band label. The first argument is the previous band (hysteresis); the RMS sets the class.
  noise = NoiseClassifier::makeReading(NoiseBand::QUIET, 10);
  SdLogCodec::formatRow(&t, nullptr, nullptr, nullptr, 0, 0, &noise, &cal, buf, sizeof(buf));
  assert(strstr(buf, ",10,") != nullptr && strstr(buf, ",QUIET,0,,\n") != nullptr);
  noise = NoiseClassifier::makeReading(NoiseBand::QUIET, 3500);
  SdLogCodec::formatRow(&t, nullptr, nullptr, nullptr, 0, 0, &noise, &cal, buf, sizeof(buf));
  assert(strstr(buf, ",3500,") != nullptr && strstr(buf, ",LOUD,0,,\n") != nullptr);
  size_t row_commas = 0;
  for (size_t i = 0; buf[i] != '\0'; ++i) row_commas += buf[i] == ',';
  assert(row_commas == header_commas);

  // Invalid or missing noise: six empty fields, same column count
  const NoiseReading invalid;
  assert(SdLogCodec::formatRow(&t, nullptr, nullptr, nullptr, 0, 0, &invalid, &cal, buf, sizeof(buf)) > 0);
  assert(strcmp(buf, "2026-03-09 07:05:01,,,,,,,,,,,,,,,,,,,,\n") == 0);
  SdLogCodec::formatRow(&t, nullptr, nullptr, nullptr, 0, 0, nullptr, &cal, buf, sizeof(buf));
  assert(strcmp(buf, "2026-03-09 07:05:01,,,,,,,,,,,,,,,,,,,,\n") == 0);
  row_commas = 0;
  for (size_t i = 0; buf[i] != '\0'; ++i) row_commas += buf[i] == ',';
  assert(row_commas == header_commas);

  // A full row with all sensors and noise fits in MAX_ROW_SIZE
  BatteryReading bat;
  bat.valid = true; bat.percentage = 100; bat.voltage_v = 4.2f; bat.usb_powered = true;
  Shtc3Reading onboard = makeOnboard(-20.5f, 100.0f);
  SwitchBotReading slots[MAX_SWITCHBOT_SENSORS];
  for (auto& r : slots) {
    r.valid = true; r.temperature_c = -40.0f; r.humidity_pct = 100; r.battery_pct = 100; r.updated_ms = 10;
  }
  noise = NoiseClassifier::makeReading(NoiseBand::QUIET, 32767);
  assert(SdLogCodec::formatRow(&t, &bat, &onboard, slots, 3, 20, &noise, &cal, buf, sizeof(buf)) > 0);
  assert(strstr(buf, ",32767,90,LOUD,0,,\n") != nullptr);
  // The worst-case row stays below the buffer limit; a smaller buffer is rejected
  assert(strlen(buf) < SdLogCodec::MAX_ROW_SIZE);
  assert(SdLogCodec::formatRow(&t, &bat, &onboard, slots, 3, 20, &noise, &cal, buf, SdLogCodec::MAX_ROW_SIZE - 1) == 0);
}

void testSdLogNoiseGate() {
  const uint32_t H = SdLogCodec::LOG_INTERVAL_MS;
  BatteryReading ok;
  ok.valid = true; ok.percentage = 80; ok.voltage_v = 3.9f;
  // First record: due
  assert(SdLogCodec::noiseMeasureWanted(false, 0, 0, true, ok));
  // Timer wakeup before the hour is over: microphones stay off
  assert(!SdLogCodec::noiseMeasureWanted(true, 1000, 1000 + H - 1, true, ok));
  assert(SdLogCodec::noiseMeasureWanted(true, 1000, 1000 + H, true, ok));
  // Wrap-safe
  assert(!SdLogCodec::noiseMeasureWanted(true, 0xFFFFFF00u, 100, true, ok));
  assert(SdLogCodec::noiseMeasureWanted(true, 0xFFFFFF00u, 0xFFFFFF00u + H, true, ok));
  // No valid RTC time: no row, no window
  assert(!SdLogCodec::noiseMeasureWanted(false, 0, 0, false, ok));
  // After a failed write the retry is due after the retry interval, and measures again
  const uint32_t stamp = SdLogCodec::retryStamp(5000000);
  assert(!SdLogCodec::noiseMeasureWanted(true, stamp, 5000000 + SdLogCodec::RETRY_INTERVAL_MS - 1, true, ok));
  assert(SdLogCodec::noiseMeasureWanted(true, stamp, 5000000 + SdLogCodec::RETRY_INTERVAL_MS, true, ok));

  // Low battery alert: skip, also when a record is due
  BatteryReading low = ok;
  low.percentage = 10; low.voltage_v = 3.3f;
  low.low_battery = BatteryMonitor::updateLowBattery(low, false);
  assert(low.low_battery);
  assert(!SdLogCodec::noiseMeasureWanted(false, 0, 0, true, low));
  assert(!SdLogCodec::noiseMeasureWanted(true, 1000, 1000 + H, true, low));
  // The latch ignores a battery reading that is not valid
  BatteryReading invalid_latched;
  invalid_latched.low_battery = true;
  assert(SdLogCodec::noiseMeasureWanted(false, 0, 0, true, invalid_latched));
  // The latch clears: measure again
  low.low_battery = false;
  assert(SdLogCodec::noiseMeasureWanted(false, 0, 0, true, low));
}

void testNoiseWindowPowersDownForLogging() {
  // Guard for the hourly record: the 5 s window with the stored calibration powers the ADC down on
  // each exit path (the sampler already did so before the logger used it)
  const MicCalibration cal;
  FakeMicHal ok;
  assert(NoiseSampler::measure(ok, NoiseBand::QUIET, NoiseSampler::WINDOW_MS, cal).valid);
  assert(ok.shutdown_calls == 1 && ok.end_calls == 1);
  for (int path = 0; path < 4; ++path) {
    FakeMicHal hal;
    hal.bus_ok = path != 0;
    hal.adc_ok = path != 1;
    if (path == 2) hal.reads_before_fail = 0;
    if (path == 3) hal.reads_before_fail = 200;
    assert(!NoiseSampler::measure(hal, NoiseBand::QUIET, NoiseSampler::WINDOW_MS, cal).valid);
    assert(hal.shutdown_calls == 1 && hal.end_calls == 1);
  }
}

void testNoiseHardwareConstants() {
  assert(I2C_ADDR_ES7210 == 0x40);
  assert(PIN_I2S_DIN == 10 && PIN_I2S_BCLK == 9 && PIN_I2S_LRCLK == 45 && PIN_I2S_MCLK == 16);
  assert(NoiseSampler::WINDOW_MS == 5000);
  // The shutdown sequence powers down the ADCs and microphone bias
  bool bias_off = false, adc_off = false;
  for (const Es7210Reg& r : es7210::SHUTDOWN_SEQUENCE) {
    bias_off = bias_off || (r.reg == 0x41 && r.value == 0x00);
    adc_off = adc_off || (r.reg == 0x4B && r.value == 0xFF);
  }
  assert(bias_off && adc_off);
}

void testEs7210InitEnablesAllClocks() {
  // Register 0x01 is CLK_OFF: a set bit switches that clock off, so 0x00 keeps every clock on
  int writes = 0;
  uint8_t clock_off = 0xFF;
  for (const Es7210Reg& r : es7210::INIT_SEQUENCE) {
    if (r.reg == 0x01) {
      clock_off = r.value;
      ++writes;
    }
  }
  assert(writes == 1);
  assert(clock_off == 0x00);
}

void testDiagnosticsNoiseIndicator() {
  BatteryReading battery;
  battery.valid = true;
  battery.voltage_v = 3.9f;
  battery.percentage = 70;

  auto render = [&](St7305Framebuffer& fb, const NoiseReading& noise) {
    SystemInfo info;
    info.free_heap_bytes = 8u * 1024 * 1024;
    info.uptime_ms = 0xFFFFFFFFFFull * 1000;
    info.noise = noise;
    fb.clear(true);
    DashboardView::renderDiagnostics(fb, battery, info, "88:88");
  };
  auto inkIn = [](const St7305Framebuffer& fb, uint16_t y0, uint16_t y1) {
    size_t ink = 0;
    for (uint16_t y = y0; y < y1; ++y) {
      for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
        ink += fb.getPixel(x, y) ? 1 : 0;
      }
    }
    return ink;
  };

  St7305Framebuffer none, quiet, moderate, loud;
  render(none, NoiseReading{});
  render(quiet, NoiseClassifier::makeReading(NoiseBand::QUIET, 10));
  render(moderate, NoiseClassifier::makeReading(NoiseBand::QUIET, 600));
  render(loud, NoiseClassifier::makeReading(NoiseBand::QUIET, 32767));

  // The noise line is at y = 204: it has ink in every state and differs by band
  assert(inkIn(none, 204, 218) > 20);
  assert(inkIn(quiet, 204, 218) > 20);
  bool differ_quiet_moderate = false, differ_moderate_loud = false, differ_none_quiet = false;
  for (uint16_t y = 204; y < 218; ++y) {
    for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
      differ_quiet_moderate = differ_quiet_moderate || quiet.getPixel(x, y) != moderate.getPixel(x, y);
      differ_moderate_loud = differ_moderate_loud || moderate.getPixel(x, y) != loud.getPixel(x, y);
      differ_none_quiet = differ_none_quiet || none.getPixel(x, y) != quiet.getPixel(x, y);
    }
  }
  assert(differ_quiet_moderate && differ_moderate_loud && differ_none_quiet);

  // No clipping with the widest text: blank right and bottom margins, and the line ends
  // inside the screen ("NOISE: MODERATE -96 DBFS" = 288 px from x = 10)
  const NoiseReading widest = {true, 0, NoiseBand::MODERATE, MicSelfTest{}}; // -96 DB: widest number
  St7305Framebuffer wide;
  render(wide, widest);
  St7305Framebuffer* frames[] = {&none, &quiet, &moderate, &loud, &wide};
  for (St7305Framebuffer* fb : frames) {
    for (uint16_t y = DashboardView::HEADER_HEIGHT; y < St7305Framebuffer::HEIGHT; ++y) {
      for (uint16_t x = St7305Framebuffer::WIDTH - 8; x < St7305Framebuffer::WIDTH; ++x) {
        assert(!fb->getPixel(x, y));
      }
    }
    for (uint16_t y = St7305Framebuffer::HEIGHT - 8; y < St7305Framebuffer::HEIGHT; ++y) {
      for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
        assert(!fb->getPixel(x, y));
      }
    }
  }
  // The line stays clear of the RTC drift line above it
  assert(inkIn(wide, 179, 184) == 0 && inkIn(wide, 198, 204) == 0);
  // Right of the text nothing is drawn
  for (uint16_t y = 204; y < 218; ++y) {
    for (uint16_t x = 10 + 24 * 12; x < St7305Framebuffer::WIDTH; ++x) {
      assert(!wide.getPixel(x, y));
    }
  }

  // Other views ignore the noise value
  SystemInfo info;
  info.noise = NoiseClassifier::makeReading(NoiseBand::QUIET, 600);
  St7305Framebuffer a, b;
  Shtc3Reading onboard;
  DashboardView::renderMode(a, DisplayMode::SENSOR_STATS, onboard, battery, nullptr, 0, info, "88:88", 0);
  DashboardView::renderMode(b, DisplayMode::SENSOR_STATS, onboard, battery, nullptr, 0, SystemInfo{}, "88:88", 0);
  assert(memcmp(a.data(), b.data(), RLCD_BUFFER_SIZE) == 0);
}

// Stereo buffer generator: fills `frames` interleaved frames from one function per channel.
template <typename F0, typename F1>
std::vector<int16_t> makeStereo(size_t frames, F0 mic1, F1 mic2) {
  std::vector<int16_t> buf(frames * 2);
  for (size_t i = 0; i < frames; ++i) {
    buf[2 * i] = mic1(i);
    buf[2 * i + 1] = mic2(i);
  }
  return buf;
}

int16_t squareWave(size_t i, int16_t amplitude) {
  return ((i / 4) & 1) ? static_cast<int16_t>(-amplitude) : amplitude;
}

MicSelfTest analyzeStereo(const std::vector<int16_t>& buf) {
  StereoAnalyzer a;
  a.add(buf.data(), buf.size());
  return a.selfTest();
}

void testMicSelfTestClassification() {
  const size_t N = 4000;
  auto good = [](size_t i) { return squareWave(i, 1000); };
  auto zero = [](size_t) { return static_cast<int16_t>(0); };
  auto constant = [](size_t) { return static_cast<int16_t>(700); };
  auto dc = [](size_t i) { return static_cast<int16_t>(8000 + squareWave(i, 500)); };
  auto negative_dc = [](size_t i) { return static_cast<int16_t>(-8000 + squareWave(i, 500)); };
  auto clipped = [](size_t i) { return (i / 4) & 1 ? INT16_MIN : INT16_MAX; };

  // No samples: not valid
  assert(!StereoAnalyzer{}.selfTest().valid);

  // Two good channels
  MicSelfTest t = analyzeStereo(makeStereo(N, good, good));
  assert(t.valid && t.channel[0] == MicStatus::OK && t.channel[1] == MicStatus::OK);
  assert(t.channelOk(0) && t.channelOk(1) && !t.bothFailed());
  assert(t.imbalance_db == 0 && !t.imbalanced());

  // One zero channel
  t = analyzeStereo(makeStereo(N, good, zero));
  assert(t.channel[0] == MicStatus::OK && t.channel[1] == MicStatus::SILENT);
  assert(!t.bothFailed());
  t = analyzeStereo(makeStereo(N, zero, good));
  assert(t.channel[0] == MicStatus::SILENT && t.channel[1] == MicStatus::OK);

  // A constant channel (peak to peak 0) is STUCK, not SILENT
  t = analyzeStereo(makeStereo(N, constant, good));
  assert(t.channel[0] == MicStatus::STUCK && t.channel[1] == MicStatus::OK);
  // A peak to peak of 1 is still stuck, a peak to peak of 2 passes
  t = analyzeStereo(makeStereo(N, [](size_t i) { return static_cast<int16_t>(i & 1); }, good));
  assert(t.channel[0] == MicStatus::STUCK);
  t = analyzeStereo(makeStereo(N, [](size_t i) { return static_cast<int16_t>((i & 1) * 2); }, good));
  assert(t.channel[0] == MicStatus::OK);

  // DC offset above the limit, in both directions
  t = analyzeStereo(makeStereo(N, dc, good));
  assert(t.channel[0] == MicStatus::DC && t.channel[1] == MicStatus::OK);
  t = analyzeStereo(makeStereo(N, good, negative_dc));
  assert(t.channel[1] == MicStatus::DC);
  // A mean just inside the limit passes
  t = analyzeStereo(makeStereo(
      N, [](size_t i) { return static_cast<int16_t>(MicChannelStats::DC_MEAN_LIMIT + squareWave(i, 500)); }, good));
  assert(t.channel[0] == MicStatus::OK);
  // A mean just above the limit is DC
  t = analyzeStereo(makeStereo(
      N, [](size_t i) { return static_cast<int16_t>(MicChannelStats::DC_MEAN_LIMIT + 1 + squareWave(i, 500)); }, good));
  assert(t.channel[0] == MicStatus::DC);

  // Clipped channel: the peak reaches full scale
  t = analyzeStereo(makeStereo(N, good, clipped));
  assert(t.channel[0] == MicStatus::OK && t.channel[1] == MicStatus::CLIP);
  // A single full scale glitch is not clipping
  t = analyzeStereo(makeStereo(N, [](size_t i) { return i == 100 ? INT16_MAX : squareWave(i, 1000); }, good));
  assert(t.channel[0] == MicStatus::OK);

  // Both channels failing
  t = analyzeStereo(makeStereo(N, zero, constant));
  assert(t.bothFailed() && t.channel[0] == MicStatus::SILENT && t.channel[1] == MicStatus::STUCK);

  // Labels
  assert(strcmp(MicSelfTest::label(MicStatus::OK), "OK") == 0);
  assert(strcmp(MicSelfTest::label(MicStatus::SILENT), "SILENT") == 0);
  assert(strcmp(MicSelfTest::label(MicStatus::STUCK), "STUCK") == 0);
  assert(strcmp(MicSelfTest::label(MicStatus::DC), "DC") == 0);
  assert(strcmp(MicSelfTest::label(MicStatus::CLIP), "CLIP") == 0);

  // Chunks of odd size keep the channel order
  const std::vector<int16_t> buf = makeStereo(N, good, zero);
  StereoAnalyzer odd;
  for (size_t pos = 0; pos < buf.size();) {
    const size_t n = std::min<size_t>(7, buf.size() - pos);
    odd.add(buf.data() + pos, n);
    pos += n;
  }
  assert(odd.selfTest().channel[0] == MicStatus::OK && odd.selfTest().channel[1] == MicStatus::SILENT);
  // Skipped samples (settling) keep the channel order, also for an odd skip count
  StereoAnalyzer skipped;
  skipped.skip(1);
  skipped.add(buf.data() + 1, buf.size() - 1); // starts with MIC2: swapped without skip()
  assert(skipped.selfTest().channel[0] == MicStatus::OK && skipped.selfTest().channel[1] == MicStatus::SILENT);
}

void testMicSelfTestImbalance() {
  const size_t N = 4000;
  auto level = [](int16_t amp) { return [amp](size_t i) { return squareWave(i, amp); }; };

  // Equal levels, and a 6 dB difference (factor 2), are inside the limit
  MicSelfTest t = analyzeStereo(makeStereo(N, level(1000), level(1000)));
  assert(t.imbalance_db == 0 && !t.imbalanced());
  t = analyzeStereo(makeStereo(N, level(2000), level(1000)));
  assert(t.imbalance_db == 6 && !t.imbalanced());
  // Exactly 10 dB is not above the limit
  t = analyzeStereo(makeStereo(N, level(3162), level(1000)));
  assert(t.imbalance_db == 10 && !t.imbalanced());
  // 20 dB (factor 10) is reported, in both directions
  t = analyzeStereo(makeStereo(N, level(10000), level(1000)));
  assert(t.imbalance_db == 20 && t.imbalanced());
  t = analyzeStereo(makeStereo(N, level(1000), level(10000)));
  assert(t.imbalance_db == 20 && t.imbalanced());
  assert(t.channel[0] == MicStatus::OK && t.channel[1] == MicStatus::OK);
  // A failed channel has no level to compare: no imbalance
  t = analyzeStereo(makeStereo(N, level(1000), [](size_t) { return static_cast<int16_t>(0); }));
  assert(t.imbalance_db == 0 && !t.imbalanced());
}

void testNoiseSamplerWithSelfTest() {
  // A sampler HAL that serves a caller-defined stereo signal
  struct SignalHal : FakeMicHal {
    int16_t (*mic1)(size_t) = nullptr;
    int16_t (*mic2)(size_t) = nullptr;
    size_t frame = 0;
    size_t read(int16_t* samples, size_t count) {
      for (size_t i = 0; i + 1 < count + 1; i += 2) {
        samples[i] = mic1(frame);
        if (i + 1 < count) samples[i + 1] = mic2(frame);
        ++frame;
      }
      return count;
    }
  };
  auto good = [](size_t i) { return squareWave(i, 1000); };
  auto quiet = [](size_t i) { return squareWave(i, 10); };
  auto zero = [](size_t) { return static_cast<int16_t>(0); };

  // Two good channels: the level is the combined RMS and both channels are OK
  SignalHal both;
  both.mic1 = good; both.mic2 = good;
  NoiseReading r = NoiseSampler::measure(both, NoiseBand::QUIET, 1000);
  assert(r.valid && r.rms == 1000 && r.mic.valid);
  assert(r.mic.channel[0] == MicStatus::OK && r.mic.channel[1] == MicStatus::OK);
  assert(both.shutdown_calls == 1 && both.end_calls == 1);

  // One dead channel: the level is that of the working microphone, not halved
  SignalHal one;
  one.mic1 = zero; one.mic2 = good;
  r = NoiseSampler::measure(one, NoiseBand::QUIET, 1000);
  assert(r.valid && r.rms == 1000 && r.band == NoiseBand::MODERATE);
  assert(r.mic.channel[0] == MicStatus::SILENT && r.mic.channel[1] == MicStatus::OK);

  // Both dead: no usable level, no band, but the failure is reported
  SignalHal dead;
  dead.mic1 = zero; dead.mic2 = zero;
  r = NoiseSampler::measure(dead, NoiseBand::LOUD, 1000);
  assert(!r.valid && r.rms == 0 && r.mic.valid && r.mic.bothFailed());
  assert(dead.shutdown_calls == 1 && dead.end_calls == 1);

  // Imbalance reaches the reading
  SignalHal imb;
  imb.mic1 = good; imb.mic2 = quiet;
  r = NoiseSampler::measure(imb, NoiseBand::QUIET, 1000);
  assert(r.valid && r.mic.imbalanced() && r.mic.imbalance_db == 40);

  // A failed read gives no self-test at all
  FakeMicHal cut;
  cut.reads_before_fail = 10;
  r = NoiseSampler::measure(cut, NoiseBand::QUIET);
  assert(!r.valid && !r.mic.valid);
}

void testDiagnosticsMicSelfTestLine() {
  BatteryReading battery;
  battery.valid = true;
  battery.voltage_v = 3.9f;
  battery.percentage = 70;

  auto render = [&](St7305Framebuffer& fb, const NoiseReading& noise) {
    SystemInfo info;
    info.free_heap_bytes = 8u * 1024 * 1024;
    info.uptime_ms = 0xFFFFFFFFFFull * 1000;
    info.noise = noise;
    fb.clear(true);
    DashboardView::renderDiagnostics(fb, battery, info, "88:88");
  };
  auto inkIn = [](const St7305Framebuffer& fb, uint16_t y0, uint16_t y1, uint16_t x0 = 0) {
    size_t ink = 0;
    for (uint16_t y = y0; y < y1; ++y) {
      for (uint16_t x = x0; x < St7305Framebuffer::WIDTH; ++x) {
        ink += fb.getPixel(x, y) ? 1 : 0;
      }
    }
    return ink;
  };
  auto same = [](const St7305Framebuffer& a, const St7305Framebuffer& b, uint16_t y0, uint16_t y1) {
    for (uint16_t y = y0; y < y1; ++y) {
      for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
        if (a.getPixel(x, y) != b.getPixel(x, y)) return false;
      }
    }
    return true;
  };
  auto reading = [](MicStatus c1, MicStatus c2, uint8_t imbalance = 0, bool usable = true) {
    NoiseReading r = NoiseClassifier::makeReading(NoiseBand::QUIET, 650);
    r.valid = usable;
    r.mic.valid = true;
    r.mic.channel[0] = c1;
    r.mic.channel[1] = c2;
    r.mic.imbalance_db = imbalance;
    return r;
  };
  const uint16_t Y0 = DashboardView::MIC_TEST_Y, Y1 = Y0 + 14;

  // The line is at y = 224 and has ink for every state, also without a window ("MIC: --")
  St7305Framebuffer none, ok, silent, stuck, dc, clip, imb, both_failed;
  render(none, NoiseReading{});
  render(ok, reading(MicStatus::OK, MicStatus::OK));
  render(silent, reading(MicStatus::OK, MicStatus::SILENT));
  render(stuck, reading(MicStatus::STUCK, MicStatus::OK));
  render(dc, reading(MicStatus::DC, MicStatus::OK));
  render(clip, reading(MicStatus::OK, MicStatus::CLIP));
  render(imb, reading(MicStatus::OK, MicStatus::OK, 20));
  render(both_failed, reading(MicStatus::SILENT, MicStatus::SILENT, 0, false));
  St7305Framebuffer* all[] = {&none, &ok, &silent, &stuck, &dc, &clip, &imb, &both_failed};
  for (St7305Framebuffer* fb : all) {
    assert(inkIn(*fb, Y0, Y1) > 20);
  }
  // Every status gives a different line
  assert(!same(ok, silent, Y0, Y1) && !same(ok, stuck, Y0, Y1) && !same(ok, dc, Y0, Y1));
  assert(!same(ok, clip, Y0, Y1) && !same(silent, stuck, Y0, Y1) && !same(none, ok, Y0, Y1));
  assert(!same(ok, imb, Y0, Y1)); // the imbalance is appended to the line
  assert(same(silent, silent, Y0, Y1));

  // Width: "MIC1: OK MIC2: OK" = 17 glyphs; "MIC1: SILENT MIC2: SILENT" = 25 glyphs = 300 px
  //        "MIC1: OK MIC2: OK IMB 255 DB" = 28 glyphs = 336 px
  assert(inkIn(ok, Y0, Y1, 10 + 17 * 12) == 0);
  assert(inkIn(ok, Y0, Y1, 10 + 17 * 12 - 12) > 0);
  assert(inkIn(both_failed, Y0, Y1, 10 + 25 * 12) == 0);
  assert(inkIn(both_failed, Y0, Y1, 10 + 25 * 12 - 12) > 0);
  St7305Framebuffer widest;
  render(widest, reading(MicStatus::OK, MicStatus::OK, 255));
  assert(inkIn(widest, Y0, Y1, 10 + 28 * 12) == 0);
  assert(inkIn(widest, Y0, Y1, 10 + 28 * 12 - 12) > 0);
  assert(10 + 28 * 12 <= St7305Framebuffer::WIDTH);
  St7305Framebuffer wide_labels;
  render(wide_labels, reading(MicStatus::SILENT, MicStatus::SILENT, 0, false));
  St7305Framebuffer* frames[] = {&none, &ok, &silent, &stuck, &dc, &clip, &imb, &both_failed, &widest};
  for (St7305Framebuffer* fb : frames) {
    // Blank right margin, and the line stays clear of the noise line above and the status below
    for (uint16_t y = DashboardView::HEADER_HEIGHT; y < St7305Framebuffer::HEIGHT; ++y) {
      for (uint16_t x = St7305Framebuffer::WIDTH - 8; x < St7305Framebuffer::WIDTH; ++x) {
        assert(!fb->getPixel(x, y));
      }
    }
    assert(inkIn(*fb, 220, Y0) == 0 && inkIn(*fb, Y1 + 1, 244) == 0);
  }

  // The noise line does not depend on the self-test when the window is usable
  assert(same(ok, silent, 204, 218));
  assert(same(ok, imb, 204, 218));
  // Both channels failed: no noise band. The noise line shows "NOISE: --", as without a window
  assert(same(both_failed, none, 204, 218));
  assert(!same(both_failed, ok, 204, 218));
  // The same RMS with a failed channel keeps its band when the other channel works
  assert(same(silent, ok, 204, 218));
  // The failed state differs from "no window" only in the self-test line
  assert(!same(both_failed, none, Y0, Y1));

  // Other views ignore the self-test
  SystemInfo info;
  info.noise = reading(MicStatus::SILENT, MicStatus::SILENT, 0, false);
  St7305Framebuffer a, b;
  Shtc3Reading onboard;
  DashboardView::renderMode(a, DisplayMode::SENSOR_STATS, onboard, battery, nullptr, 0, info, "88:88", 0);
  DashboardView::renderMode(b, DisplayMode::SENSOR_STATS, onboard, battery, nullptr, 0, SystemInfo{}, "88:88", 0);
  assert(memcmp(a.data(), b.data(), RLCD_BUFFER_SIZE) == 0);
}

void testPreviewDiagnosticsShowsSelfTest() {
  St7305Framebuffer fb;
  preview::renderScene(fb, DisplayMode::SYSTEM_DIAGNOSTICS, false);
  size_t ink = 0;
  for (uint16_t y = DashboardView::MIC_TEST_Y; y < DashboardView::MIC_TEST_Y + 14; ++y) {
    for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) ink += fb.getPixel(x, y) ? 1 : 0;
  }
  assert(ink > 100); // "MIC1: OK MIC2: OK", not the "MIC: --" placeholder
}

void testNoiseStatePersistence() {
  PersistentState s;
  assert(s.noise_band == NoiseBand::QUIET);
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  PersistentState r;
  const NoiseBand bands[] = {NoiseBand::QUIET, NoiseBand::MODERATE, NoiseBand::LOUD};
  for (NoiseBand band : bands) {
    s.noise_band = band;
    s.muted = true;
    s.alert_mask = ALERT_HUMIDITY;
    assert(PowerManager::serialize(s, buf, sizeof(buf)));
    assert(PowerManager::deserialize(buf, sizeof(buf), r));
    assert(r.noise_band == band && r.muted && r.alert_mask == ALERT_HUMIDITY);
  }
  // Band value 3 does not exist and is rejected even with a valid CRC
  const size_t audio_pos = PowerManager::SERIALIZED_SIZE - 4 - PowerManager::CONFIG_SIZE - PowerManager::NTP_SIZE - 1;
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  buf[audio_pos] = static_cast<uint8_t>(3 << 6);
  const uint32_t crc = PowerManager::crc32(buf, sizeof(buf) - 4);
  for (int i = 0; i < 4; ++i) buf[sizeof(buf) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  assert(!PowerManager::deserialize(buf, sizeof(buf), r));
}

bool calendarIs(int64_t epoch, uint16_t y, uint8_t mo, uint8_t d, uint8_t wd, uint8_t h, uint8_t mi, uint8_t sec) {
  RtcTime t;
  return NtpTime::epochToCalendar(epoch, t) && t.valid && t.year == y && t.month == mo &&
         t.day == d && t.weekday == wd && t.hour == h && t.minute == mi && t.second == sec;
}

void testNtpEpochToCalendar() {
  assert(calendarIs(946684800, 2000, 1, 1, 6, 0, 0, 0));          // Saturday
  assert(calendarIs(951782400, 2000, 2, 29, 2, 0, 0, 0));         // 2000 is a leap year
  assert(calendarIs(951868800, 2000, 3, 1, 3, 0, 0, 0));
  assert(calendarIs(1709164800, 2024, 2, 29, 4, 0, 0, 0));        // 2024 is a leap year
  assert(calendarIs(1709251199, 2024, 2, 29, 4, 23, 59, 59));     // hour rollover edge
  assert(calendarIs(1709251200, 2024, 3, 1, 5, 0, 0, 0));         // month boundary
  assert(calendarIs(1735689599, 2024, 12, 31, 2, 23, 59, 59));    // year boundary
  assert(calendarIs(1735689600, 2025, 1, 1, 3, 0, 0, 0));
  assert(calendarIs(1767225599, 2025, 12, 31, 3, 23, 59, 59));
  assert(calendarIs(1788246245, 2026, 9, 1, 2, 7, 4, 5));
  {
    // 2100 is no leap year: the day after 2100-02-28 is 2100-03-01 (outside the RTC range)
    int32_t y = 0;
    uint8_t mo = 0, d = 0;
    NtpTime::civilFromDays(NtpTime::daysFromCivil(2100, 2, 28) + 1, y, mo, d);
    assert(y == 2100 && mo == 3 && d == 1);
    NtpTime::civilFromDays(NtpTime::daysFromCivil(2000, 2, 28) + 1, y, mo, d);
    assert(y == 2000 && mo == 2 && d == 29);
    assert(NtpTime::daysFromCivil(1970, 1, 1) == 0);
  }
  assert(calendarIs(4102444799, 2099, 12, 31, 4, 23, 59, 59));    // last second of the RTC range
  RtcTime t;
  assert(!NtpTime::epochToCalendar(946684799, t) && !t.valid);    // before 2000
  assert(!NtpTime::epochToCalendar(4102444800, t) && !t.valid);   // 2100 is out of range
  assert(!NtpTime::epochToCalendar(-1, t));
  assert(NtpTime::isLeapYear(2000) && NtpTime::isLeapYear(2024));
  assert(!NtpTime::isLeapYear(2100) && !NtpTime::isLeapYear(2023));
  // The weekday agrees with the RTC codec for every day of a leap year
  for (int64_t e = 1709164800; e < 1709164800 + 366 * 86400LL; e += 86400) {
    RtcTime d;
    assert(NtpTime::epochToCalendar(e, d));
    assert(d.weekday == Pcf85063Codec::computeWeekday(d.year, d.month, d.day));
  }
}

void testNtpTimezoneDst() {
  const TimeZone cet;
  // 2024: DST starts Sunday 31 March 01:00 UTC (1711846800), ends Sunday 27 October 01:00 UTC
  assert(NtpTime::lastSunday(2024, 3) == 31 && NtpTime::lastSunday(2024, 10) == 27);
  assert(NtpTime::lastSunday(2026, 3) == 29 && NtpTime::lastSunday(2026, 10) == 25);
  assert(NtpTime::euDstStartUtc(2024) == 1711846800);
  assert(NtpTime::euDstEndUtc(2024) == 1729990800);
  assert(NtpTime::offsetSeconds(1711846799, cet) == 3600);   // 1 s before the start: CET
  assert(NtpTime::offsetSeconds(1711846800, cet) == 7200);   // at the start: CEST
  assert(NtpTime::offsetSeconds(1729990799, cet) == 7200);   // 1 s before the end: CEST
  assert(NtpTime::offsetSeconds(1729990800, cet) == 3600);   // at the end: CET
  assert(NtpTime::offsetSeconds(1720000000, cet) == 7200);   // July
  assert(NtpTime::offsetSeconds(1704067200, cet) == 3600);   // January
  assert(NtpTime::offsetSeconds(NtpTime::EPOCH_2100, cet) == 3600); // out of range: no DST
  // Local time across the transitions: 01:59:59 UTC -> 03:00:00 CEST, and back
  RtcTime t;
  assert(NtpTime::utcToLocal(1711846799, cet, t) && t.hour == 1 && t.minute == 59 && t.day == 31);
  assert(NtpTime::utcToLocal(1711846800, cet, t) && t.hour == 3 && t.minute == 0 && t.day == 31);
  assert(NtpTime::utcToLocal(1729990799, cet, t) && t.hour == 2 && t.minute == 59 && t.day == 27);
  assert(NtpTime::utcToLocal(1729990800, cet, t) && t.hour == 2 && t.minute == 0 && t.day == 27);
  // The offset carries into the next day and year
  assert(NtpTime::utcToLocal(1735686000, cet, t)); // 2024-12-31 23:00:00 UTC
  assert(t.year == 2025 && t.month == 1 && t.day == 1 && t.hour == 0 && t.weekday == 3);
  // Local time inside 2000 is accepted for 1999-12-31 23:59:59 UTC; below that it is rejected
  assert(NtpTime::utcToLocal(946684800 - 1, cet, t) && t.year == 2000); // 1999-12-31 23:59:59 UTC
  assert(!NtpTime::utcToLocal(946684800 - 7200, cet, t));
  // A zone without DST and a western zone
  TimeZone utc{0, false};
  assert(NtpTime::utcToLocal(1720000000, utc, t) && t.hour == 9 && t.minute == 46);
  TimeZone west{-18000, false};
  assert(NtpTime::utcToLocal(1704067200, west, t) && t.year == 2023 && t.month == 12 &&
         t.day == 31 && t.hour == 19);
}

void testNtpRegisterEncoding() {
  const TimeZone cet;
  uint8_t regs[Pcf85063Codec::TIME_REGISTER_COUNT];
  // 2026-09-01 07:04:05 UTC = 09:04:05 CEST, a Tuesday
  assert(NtpTime::epochToRegisters(1788246245, cet, regs, sizeof(regs)));
  assert(regs[0] == 0x05 && regs[1] == 0x04 && regs[2] == 0x09 && regs[3] == 0x01);
  assert(regs[4] == 0x02 && regs[5] == 0x09 && regs[6] == 0x26);
  // 2024-12-31 23:59:59 UTC = 2025-01-01 00:59:59 CET
  assert(NtpTime::epochToRegisters(1735689599, cet, regs, sizeof(regs)));
  assert(regs[0] == 0x59 && regs[1] == 0x59 && regs[2] == 0x00 && regs[3] == 0x01);
  assert(regs[4] == 0x03 && regs[5] == 0x01 && regs[6] == 0x25);
  // The registers decode to the same local time
  RtcTime decoded;
  assert(Pcf85063Codec::decodeRegisters(regs, sizeof(regs), decoded));
  assert(decoded.year == 2025 && decoded.hour == 0 && decoded.second == 59);
  // 2099-12-31 23:00 UTC leaves the RTC range in local time
  assert(!NtpTime::epochToRegisters(4102444800 - 3600, cet, regs, sizeof(regs)));
  assert(!NtpTime::epochToRegisters(0, cet, regs, sizeof(regs)));
  assert(!NtpTime::epochToRegisters(1788246245, cet, regs, 3));
  assert(!NtpTime::epochToRegisters(1788246245, cet, nullptr, 7));
}

void testNtpScheduleAndOfflineFallback() {
  NtpSyncState s;
  const uint32_t day = NtpSchedule::syncIntervalMs(DeviceConfig::DEFAULT_NTP_SYNC_INTERVAL_H);
  // Credentials
  assert(!NtpSchedule::hasCredentials("", ""));
  assert(!NtpSchedule::hasCredentials(nullptr, "x"));
  assert(!NtpSchedule::hasCredentials("ssid", nullptr));
  assert(!NtpSchedule::hasCredentials("123456789012345678901234567890123", ""));
  assert(NtpSchedule::hasCredentials("ssid", ""));
  assert(!NtpSchedule::hasCredentials("ssid", "secret"));        // WPA2 needs 8..63 characters
  assert(NtpSchedule::hasCredentials("ssid", "secret12"));
  assert(NtpSchedule::hasCredentials("ssid", "0123456789abcdefABCDEF0123456789abcdefABCDEF0123456789abcdefABCD")); // 64-digit hex PSK
  assert(!NtpSchedule::hasCredentials("ssid", "0123456789abcdefABCDEF0123456789abcdefABCDEF0123456789abcdefABCG"));
  assert(!NtpSchedule::hasCredentials("ssid", "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"));
  // First wake cycle: due at once
  assert(NtpSchedule::due(s, 1000, 24));
  assert(NtpSchedule::decide(s, 1000, false, true, true, false, 24) == NtpDecision::ATTEMPT);
  // Offline fallback: no credentials, no RTC, key wakeup and low battery never start the radio
  assert(NtpSchedule::decide(s, 1000, false, false, true, false, 24) == NtpDecision::SKIP_NO_CREDENTIALS);
  assert(NtpSchedule::decide(s, 1000, false, true, false, false, 24) == NtpDecision::SKIP_NO_RTC);
  assert(NtpSchedule::decide(s, 1000, true, true, true, false, 24) == NtpDecision::SKIP_KEY_WAKEUP);
  assert(NtpSchedule::decide(s, 1000, false, true, true, true, 24) == NtpDecision::SKIP_LOW_BATTERY);
  // Skips leave the state untouched, so the sync stays due
  assert(!s.has_attempted && NtpSchedule::due(s, 2000, 24));
  // Success: next attempt after 24 h
  NtpSchedule::record(s, 5000, true);
  assert(s.last_ok && s.failures == 0 && s.has_attempted);
  assert(!NtpSchedule::due(s, 5000 + day - 1, 24));
  assert(NtpSchedule::due(s, 5000 + day, 24));
  assert(NtpSchedule::decide(s, 6000, false, true, true, false, 24) == NtpDecision::SKIP_NOT_DUE);
  // Failure: not marked as done, retry with a doubling backoff up to 6 h
  NtpSchedule::record(s, 100000, false);
  assert(!s.last_ok && s.failures == 1);
  assert(NtpSchedule::retryDelayMs(1) == 900000);
  assert(NtpSchedule::retryDelayMs(2) == 1800000);
  assert(NtpSchedule::retryDelayMs(3) == 3600000);
  assert(NtpSchedule::retryDelayMs(5) == 14400000);
  assert(NtpSchedule::retryDelayMs(6) == NtpSchedule::RETRY_MAX_MS);
  assert(NtpSchedule::retryDelayMs(255) == NtpSchedule::RETRY_MAX_MS);
  assert(!NtpSchedule::due(s, 100000 + 899999, 24));
  assert(NtpSchedule::due(s, 100000 + 900000, 24));
  NtpSchedule::record(s, 2000000, false);
  assert(s.failures == 2 && !NtpSchedule::due(s, 2000000 + 1799999, 24) && NtpSchedule::due(s, 2000000 + 1800000, 24));
  for (int i = 0; i < 100; ++i) NtpSchedule::record(s, 3000000, false);
  assert(s.failures == NtpSchedule::MAX_FAILURES);
  // A later success resets the backoff
  NtpSchedule::record(s, 90000000, true);
  assert(s.failures == 0 && s.last_ok);
  // Clock wrap-around and a clock reset give an early try, never a stall
  NtpSyncState w;
  NtpSchedule::record(w, 0xFFFFFF00u, true);
  assert(!NtpSchedule::due(w, 0x100u, 24));
  assert(NtpSchedule::due(w, 0xFFFFFF00u + day, 24));
  NtpSyncState r;
  NtpSchedule::record(r, 4000000, true);
  assert(NtpSchedule::due(r, 1000, 24)); // clock restarted below the stamp
}

void testNtpSystemClockRestore() {
  int64_t sec = 0, usec = 0;
  advanceTime(100, 250000, 0, sec, usec);
  assert(sec == 100 && usec == 250000);
  advanceTime(100, 900000, 200000, sec, usec); // microsecond carry
  assert(sec == 101 && usec == 100000);
  advanceTime(100, 0, 14000000, sec, usec);    // 14 s of sync time
  assert(sec == 114 && usec == 0);
  advanceTime(0, 999999, 1, sec, usec);
  assert(sec == 1 && usec == 0);
}

void testNtpStatePersistence() {
  PersistentState s;
  NtpSchedule::record(s.ntp, 123456, false);
  NtpSchedule::record(s.ntp, 234567, false);
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  PersistentState r;
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(r.ntp.has_attempted && !r.ntp.last_ok && r.ntp.failures == 2 && r.ntp.last_attempt_ms == 234567);
  NtpSchedule::record(s.ntp, 345678, true);
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(r.ntp.last_ok && r.ntp.failures == 0 && r.ntp.last_attempt_ms == 345678);
  // Defaults: never attempted
  PersistentState d;
  assert(!d.ntp.has_attempted && NtpSchedule::due(d.ntp, 0, 24));
  // The previous format (version 4) is rejected, even with a valid CRC
  assert(PowerManager::STATE_VERSION == 26);
  buf[4] = 4;
  uint32_t crc = PowerManager::crc32(buf, sizeof(buf) - 4);
  for (int i = 0; i < 4; ++i) buf[sizeof(buf) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  assert(!PowerManager::deserialize(buf, sizeof(buf), r));
  // Reserved flag bit, success without attempt, and too many failures are rejected
  const size_t flags_pos = PowerManager::SERIALIZED_SIZE - 4 - PowerManager::CONFIG_SIZE - PowerManager::NTP_SIZE + 4;
  const uint8_t bad_flags[3][2] = {{4, 0}, {2, 0}, {1, NtpSchedule::MAX_FAILURES + 1}};
  for (const auto& b : bad_flags) {
    assert(PowerManager::serialize(s, buf, sizeof(buf)));
    buf[flags_pos] = b[0];
    buf[flags_pos + 1] = b[1];
    crc = PowerManager::crc32(buf, sizeof(buf) - 4);
    for (int i = 0; i < 4; ++i) buf[sizeof(buf) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
    assert(!PowerManager::deserialize(buf, sizeof(buf), r));
  }
  // No attempt, but a failure count or a stamp is rejected
  const uint8_t no_attempt[2][2] = {{0, 1}, {0, 0}};
  for (int k = 0; k < 2; ++k) {
    assert(PowerManager::serialize(s, buf, sizeof(buf)));
    buf[flags_pos] = no_attempt[k][0];
    buf[flags_pos + 1] = no_attempt[k][1]; // k = 1 keeps the stamp from s, which is nonzero
    crc = PowerManager::crc32(buf, sizeof(buf) - 4);
    for (int i = 0; i < 4; ++i) buf[sizeof(buf) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
    assert(!PowerManager::deserialize(buf, sizeof(buf), r));
  }
  // Success with failures left is rejected
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  buf[flags_pos] = 3;
  buf[flags_pos + 1] = 1;
  crc = PowerManager::crc32(buf, sizeof(buf) - 4);
  for (int i = 0; i < 4; ++i) buf[sizeof(buf) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  assert(!PowerManager::deserialize(buf, sizeof(buf), r));
}


void testMicSplConversionAndClamp() {
  MicCalibration cal;
  assert(!cal.calibrated && cal.spl_offset_db == MIC_SPL_OFFSET_DB);
  assert(MicCalibration::DEFAULT_SPL_OFFSET_DB == MIC_SPL_OFFSET_DB);

  // dB SPL = dBFS + offset. Full scale (32767) is 0 dBFS, 1024 is -30 dBFS.
  cal.spl_offset_db = 94;
  assert(cal.splDb(32767) == 94);
  assert(cal.splDb(1024) == 64);
  assert(cal.splDb(0) == 0); // -96 + 94 = -2: clamped to 0

  // Clamp to 0..140 dB SPL
  cal.spl_offset_db = 200;
  assert(cal.splDb(32767) == MicCalibration::SPL_MAX_DB);
  cal.spl_offset_db = 10;
  assert(cal.splDb(10) == MicCalibration::SPL_MIN_DB);
  assert(cal.splDb(0) == MicCalibration::SPL_MIN_DB);

  // The offset shifts the level one to one
  cal.spl_offset_db = 80;
  const int a = cal.splDb(2000);
  cal.spl_offset_db = 90;
  assert(cal.splDb(2000) == a + 10);

  // fromFloor accepts a quiet room only and keeps `out` unchanged otherwise
  MicCalibration out;
  assert(!MicCalibration::fromFloor(0, 90, out) && !out.calibrated);
  assert(!MicCalibration::fromFloor(MicCalibration::MAX_FLOOR_RMS + 1, 90, out) && !out.calibrated);
  assert(!MicCalibration::fromFloor(100, -1, out) && !out.calibrated);
  assert(!MicCalibration::fromFloor(100, 201, out) && !out.calibrated);
  assert(MicCalibration::fromFloor(100, 90, out));
  assert(out.calibrated && out.noise_floor_rms == 100 && out.spl_offset_db == 90);
  assert(MicCalibration::fromFloor(MicCalibration::MIN_FLOOR_RMS, 0, out));
  assert(MicCalibration::fromFloor(MicCalibration::MAX_FLOOR_RMS, 200, out));
  assert(MicCalibration::CALIBRATION_WINDOW_MS == 10000);
}

void testDerivedBandLimitsWithHysteresis() {
  using NC = NoiseClassifier;
  // Without calibration the fixed limits apply
  const NoiseBandLimits fixed = NC::limits(MicCalibration{});
  assert(fixed.moderate_trigger == NC::MODERATE_TRIGGER_RMS);
  assert(fixed.loud_release == NC::LOUD_RELEASE_RMS);

  // Floor RMS 100: +10 dB = 316, +30 dB = 3162, release 3 dB lower (224 and 2239)
  MicCalibration cal;
  assert(MicCalibration::fromFloor(100, 90, cal));
  const NoiseBandLimits l = NC::limits(cal);
  assert(l.moderate_trigger == 316 && l.moderate_release == 224);
  assert(l.loud_trigger == 3162 && l.loud_release == 2239);
  assert(l.moderate_release < l.moderate_trigger && l.loud_release < l.loud_trigger);
  assert(l.moderate_trigger < l.loud_release);

  // Trigger edges
  assert(NC::classify(NoiseBand::QUIET, 315, cal) == NoiseBand::QUIET);
  assert(NC::classify(NoiseBand::QUIET, 316, cal) == NoiseBand::MODERATE);
  assert(NC::classify(NoiseBand::QUIET, 3161, cal) == NoiseBand::MODERATE);
  assert(NC::classify(NoiseBand::QUIET, 3162, cal) == NoiseBand::LOUD);
  // Hysteresis: a latched band holds down to the release limit
  assert(NC::classify(NoiseBand::MODERATE, 224, cal) == NoiseBand::MODERATE);
  assert(NC::classify(NoiseBand::MODERATE, 223, cal) == NoiseBand::QUIET);
  assert(NC::classify(NoiseBand::LOUD, 2239, cal) == NoiseBand::LOUD);
  assert(NC::classify(NoiseBand::LOUD, 2238, cal) == NoiseBand::MODERATE);
  // A level of 300 is moderate with the fixed limits, quiet with this floor
  assert(NC::classify(NoiseBand::QUIET, 300) == NoiseBand::MODERATE);
  assert(NC::classify(NoiseBand::QUIET, 300, cal) == NoiseBand::QUIET);

  // A sequence around the trigger does not flip on every window
  NoiseBand band = NoiseBand::QUIET;
  const uint16_t levels[] = {315, 316, 300, 250, 224, 223, 316};
  const NoiseBand expected[] = {
      NoiseBand::QUIET, NoiseBand::MODERATE, NoiseBand::MODERATE, NoiseBand::MODERATE,
      NoiseBand::MODERATE, NoiseBand::QUIET, NoiseBand::MODERATE};
  for (size_t i = 0; i < sizeof(levels) / sizeof(levels[0]); ++i) {
    band = NC::classify(band, levels[i], cal);
    assert(band == expected[i]);
  }

  // A very loud floor keeps the limits inside the 16-bit range and in order
  MicCalibration loud;
  assert(MicCalibration::fromFloor(MicCalibration::MAX_FLOOR_RMS, 90, loud));
  const NoiseBandLimits ll = NC::limits(loud);
  assert(ll.loud_trigger <= 32767 && ll.moderate_trigger < ll.loud_release);
  assert(ll.loud_trigger < 32767); // the accepted floor range keeps the limit below full scale
  // A calibrated record with an out-of-range floor gives the fixed limits
  const uint16_t bad_floors[] = {0, 65535};
  for (const uint16_t bad_floor : bad_floors) {
    MicCalibration bad;
    bad.calibrated = true;
    bad.noise_floor_rms = bad_floor;
    assert(NC::limits(bad).moderate_trigger == NC::MODERATE_TRIGGER_RMS);
  }
  // An uncalibrated record with a stray floor value gives the fixed limits
  MicCalibration stray;
  stray.noise_floor_rms = 100;
  assert(NC::limits(stray).moderate_trigger == NC::MODERATE_TRIGGER_RMS);
  // makeReading and the sampler use the calibration
  assert(NC::makeReading(NoiseBand::QUIET, 400, cal).band == NoiseBand::MODERATE);
  FakeMicHal hal;
  hal.amplitude = 250;
  assert(NoiseSampler::measure(hal, NoiseBand::QUIET).band == NoiseBand::QUIET);
  FakeMicHal hal2;
  hal2.amplitude = 400;
  assert(NoiseSampler::measure(hal2, NoiseBand::QUIET, 1000, cal).band == NoiseBand::MODERATE);
}

void testLongPressDetection() {
  LongPressDetector d;
  using R = LongPressDetector::Result;
  assert(LongPressDetector::LONG_PRESS_MS == 2000);
  assert(d.update(true, 0) == R::NONE && !d.active()); // not started

  // Release before the limit: short press
  d.begin(1000);
  assert(d.active());
  assert(d.update(true, 2999) == R::NONE); // 1999 ms
  assert(d.update(false, 2999) == R::SHORT && !d.active());
  assert(d.update(false, 3000) == R::NONE);

  // Exactly at the limit: long press, once; the release afterwards gives nothing
  d.begin(1000);
  assert(d.update(true, 2999) == R::NONE);
  assert(d.update(true, 3000) == R::LONG && !d.active());
  assert(d.update(true, 3500) == R::NONE);
  assert(d.update(false, 3600) == R::NONE);

  // Wrap-around of the 32-bit clock
  d.begin(0xFFFFFC00u);
  assert(d.update(true, 0x000003CFu) == R::NONE); // 1999 ms
  assert(d.update(true, 0x000003D0u) == R::LONG);

  // cancel() drops a pending press
  d.begin(0);
  d.cancel();
  assert(!d.active() && d.update(false, 10) == R::NONE);

  // Controller: in the diagnostics view a short press changes the mode on release
  DisplayModeController c;
  c.setMode(DisplayMode::SYSTEM_DIAGNOSTICS);
  uint32_t t = 0;
  assert(c.updateWithLongPress(false, t) == ButtonEvent::NONE); // seeds: released
  t += 100;
  c.updateWithLongPress(true, t);
  t += 40;
  assert(c.updateWithLongPress(true, t) == ButtonEvent::NONE); // debounced press, mode unchanged
  assert(c.mode() == DisplayMode::SYSTEM_DIAGNOSTICS && c.pressPending());
  t += 500;
  c.updateWithLongPress(false, t);
  t += 40;
  assert(c.updateWithLongPress(false, t) == ButtonEvent::MODE_CHANGED);
  assert(c.mode() == DisplayMode::AUDIO_SPECTRUM && !c.pressPending());

  // A hold of 2 s or more calibrates and keeps the mode
  DisplayModeController h;
  h.setMode(DisplayMode::SYSTEM_DIAGNOSTICS);
  t = 0;
  h.updateWithLongPress(false, t);
  t += 100;
  h.updateWithLongPress(true, t);
  t += 40;
  h.updateWithLongPress(true, t); // press confirmed at t = 140
  const uint32_t press_ms = t;
  assert(h.updateWithLongPress(true, press_ms + 1999) == ButtonEvent::NONE);
  assert(h.updateWithLongPress(true, press_ms + 2000) == ButtonEvent::CALIBRATE);
  assert(h.mode() == DisplayMode::SYSTEM_DIAGNOSTICS);
  assert(h.updateWithLongPress(true, press_ms + 2500) == ButtonEvent::NONE);
  h.updateWithLongPress(false, press_ms + 3000);
  assert(h.updateWithLongPress(false, press_ms + 3040) == ButtonEvent::NONE); // no mode change
  assert(h.mode() == DisplayMode::SYSTEM_DIAGNOSTICS);

  // Other views change the mode at once, as before
  DisplayModeController o;
  t = 0;
  o.updateWithLongPress(false, t);
  t += 100;
  o.updateWithLongPress(true, t);
  t += 40;
  assert(o.updateWithLongPress(true, t) == ButtonEvent::MODE_CHANGED);
  assert(o.mode() == DisplayMode::SENSOR_STATS && !o.pressPending());

  // A button already held at the first call (the wake press) raises no event
  DisplayModeController w;
  w.setMode(DisplayMode::SYSTEM_DIAGNOSTICS);
  assert(w.updateWithLongPress(true, 0) == ButtonEvent::NONE);
  assert(w.updateWithLongPress(true, 5000) == ButtonEvent::NONE && !w.pressPending());
}

void testMicCalibrationSerialization() {
  using Codec = MicCalibrationCodec;
  MicCalibration cal;
  assert(MicCalibration::fromFloor(123, 94, cal));
  uint8_t buf[Codec::SERIALIZED_SIZE];
  assert(Codec::serialize(cal, buf, sizeof(buf)));

  MicCalibration back;
  assert(Codec::deserialize(buf, sizeof(buf), back));
  assert(back.calibrated && back.noise_floor_rms == 123 && back.spl_offset_db == 94);

  // The default record round trips too
  uint8_t dbuf[Codec::SERIALIZED_SIZE];
  assert(Codec::serialize(MicCalibration{}, dbuf, sizeof(dbuf)));
  MicCalibration dback;
  dback.noise_floor_rms = 7; // overwritten
  assert(Codec::deserialize(dbuf, sizeof(dbuf), dback));
  assert(!dback.calibrated && dback.noise_floor_rms == 0);

  // Buffer and record checks
  assert(!Codec::serialize(cal, buf, sizeof(buf) - 1));
  assert(!Codec::serialize(cal, nullptr, 16));
  MicCalibration bad;
  bad.calibrated = true; // floor 0 is not valid for a calibrated record
  assert(!Codec::serialize(bad, buf, sizeof(buf)));
  MicCalibration bad_offset;
  bad_offset.spl_offset_db = 201;
  assert(!Codec::serialize(bad_offset, buf, sizeof(buf)));

  // Every single flipped bit is rejected (magic, version, flags, fields, CRC)
  for (size_t byte = 0; byte < sizeof(buf); ++byte) {
    for (int bit = 0; bit < 8; ++bit) {
      uint8_t copy[Codec::SERIALIZED_SIZE];
      memcpy(copy, buf, sizeof(copy));
      copy[byte] ^= static_cast<uint8_t>(1u << bit);
      MicCalibration out;
      assert(!Codec::deserialize(copy, sizeof(copy), out));
      assert(!Codec::decodeOrDefault(copy, sizeof(copy)).calibrated);
    }
  }

  // A newer version with a matching CRC is rejected
  uint8_t v2[Codec::SERIALIZED_SIZE];
  memcpy(v2, buf, sizeof(v2));
  v2[2] = Codec::VERSION + 1;
  const uint32_t crc = Codec::crc32(v2, 8);
  for (int i = 0; i < 4; ++i) {
    v2[8 + i] = static_cast<uint8_t>(crc >> (8 * i));
  }
  MicCalibration out;
  assert(!Codec::deserialize(v2, sizeof(v2), out));

  // A valid CRC with an out-of-range field is rejected
  uint8_t range[Codec::SERIALIZED_SIZE];
  memcpy(range, buf, sizeof(range));
  range[6] = 0xFF;
  range[7] = 0xFF; // floor 65535
  const uint32_t crc2 = Codec::crc32(range, 8);
  for (int i = 0; i < 4; ++i) {
    range[8 + i] = static_cast<uint8_t>(crc2 >> (8 * i));
  }
  assert(!Codec::deserialize(range, sizeof(range), out));

  // Wrong length, null, erased flash (0xFF) and zero fill give the default
  assert(!Codec::deserialize(buf, sizeof(buf) - 1, out));
  assert(!Codec::deserialize(buf, sizeof(buf) + 1, out));
  assert(!Codec::deserialize(nullptr, sizeof(buf), out));
  uint8_t erased[Codec::SERIALIZED_SIZE];
  memset(erased, 0xFF, sizeof(erased));
  const MicCalibration d1 = Codec::decodeOrDefault(erased, sizeof(erased));
  assert(!d1.calibrated && d1.spl_offset_db == MIC_SPL_OFFSET_DB && d1.noise_floor_rms == 0);
  memset(erased, 0, sizeof(erased));
  assert(!Codec::decodeOrDefault(erased, sizeof(erased)).calibrated);
  assert(!Codec::decodeOrDefault(nullptr, 0).calibrated);
  // A good record decodes to itself
  assert(Codec::decodeOrDefault(buf, sizeof(buf)).noise_floor_rms == 123);
  // CRC-32 check value of the standard test string
  assert(Codec::crc32(reinterpret_cast<const uint8_t*>("123456789"), 9) == 0xCBF43926u);
}

void testApplyCalibrationWindow() {
  MicCalibration active;
  assert(MicCalibration::fromFloor(50, 90, active));
  NoiseReading reading;
  NoiseBand band = NoiseBand::LOUD;
  int saves = 0;
  MicCalibration saved;
  auto ok_save = [&](const MicCalibration& c) { ++saves; saved = c; return true; };
  auto bad_save = [&](const MicCalibration&) { ++saves; return false; };

  // Success: new record, offset from the build default, band reset to the new limits
  assert(applyCalibrationWindow(NoiseReading{true, 100, NoiseBand::QUIET, MicSelfTest{}}, active, reading, band, ok_save));
  assert(saves == 1 && saved.noise_floor_rms == 100 && saved.spl_offset_db == MIC_SPL_OFFSET_DB);
  assert(active.noise_floor_rms == 100 && active.calibrated);
  assert(reading.valid && reading.rms == 100 && reading.band == NoiseBand::QUIET);
  assert(band == NoiseBand::QUIET);

  // The stored offset is replaced by the build default
  active.spl_offset_db = 120;
  assert(applyCalibrationWindow(NoiseReading{true, 80, NoiseBand::QUIET, MicSelfTest{}}, active, reading, band, ok_save));
  assert(active.spl_offset_db == MIC_SPL_OFFSET_DB);

  // Failures change nothing: no reading, a loud room, a failed save
  const MicCalibration before = active;
  reading = NoiseReading{};
  band = NoiseBand::MODERATE;
  saves = 0;
  assert(!applyCalibrationWindow(NoiseReading{}, active, reading, band, ok_save));
  assert(!applyCalibrationWindow(
      NoiseReading{true, static_cast<uint16_t>(MicCalibration::MAX_FLOOR_RMS + 1), NoiseBand::LOUD, MicSelfTest{}},
      active, reading, band, ok_save));
  assert(!applyCalibrationWindow(NoiseReading{true, 0, NoiseBand::QUIET, MicSelfTest{}}, active, reading, band, ok_save));
  assert(saves == 0);
  assert(!applyCalibrationWindow(NoiseReading{true, 100, NoiseBand::QUIET, MicSelfTest{}}, active, reading, band, bad_save));
  assert(saves == 1);
  assert(active.noise_floor_rms == before.noise_floor_rms && !reading.valid && band == NoiseBand::MODERATE);
}

void testDiagnosticsCalibrationLines() {
  BatteryReading battery;
  battery.valid = true;
  battery.voltage_v = 3.9f;
  battery.percentage = 70;

  MicCalibration cal;
  assert(MicCalibration::fromFloor(100, 90, cal));
  auto render = [&](St7305Framebuffer& fb, const NoiseReading& noise, const MicCalibration& mc,
                    bool busy = false, bool failed = false) {
    SystemInfo info;
    info.free_heap_bytes = 8u * 1024 * 1024;
    info.uptime_ms = 0xFFFFFFFFFFull * 1000;
    info.noise = noise;
    info.mic_cal = mc;
    info.mic_cal_busy = busy;
    info.mic_cal_failed = failed;
    fb.clear(true);
    DashboardView::renderDiagnostics(fb, battery, info, "88:88");
  };
  auto inkIn = [](const St7305Framebuffer& fb, uint16_t y0, uint16_t y1, uint16_t x0 = 0) {
    size_t ink = 0;
    for (uint16_t y = y0; y < y1; ++y) {
      for (uint16_t x = x0; x < St7305Framebuffer::WIDTH; ++x) {
        ink += fb.getPixel(x, y) ? 1 : 0;
      }
    }
    return ink;
  };
  auto same = [](const St7305Framebuffer& a, const St7305Framebuffer& b, uint16_t y0, uint16_t y1) {
    for (uint16_t y = y0; y < y1; ++y) {
      for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
        if (a.getPixel(x, y) != b.getPixel(x, y)) {
          return false;
        }
      }
    }
    return true;
  };

  const NoiseReading reading = NoiseClassifier::makeReading(NoiseBand::QUIET, 650, cal);
  St7305Framebuffer dbfs, dba, nothing, busy, def, def_reading;
  render(dbfs, reading, MicCalibration{});
  render(dba, reading, cal);
  render(nothing, NoiseReading{}, cal);
  render(busy, reading, cal, true);
  render(def, reading, MicCalibration{});
  // DBA and DBFS lines differ (noise line y = 204); the status line (y = 244) differs between
  // calibrated and default, and while measuring.
  assert(!same(dbfs, dba, 204, 218));
  assert(!same(dbfs, dba, 244, 258));
  assert(same(dbfs, def, 204, 262));
  assert(!same(dba, busy, 244, 258) && same(dba, busy, 204, 218));
  assert(inkIn(nothing, 244, 258) > 20 && inkIn(dbfs, 244, 258) > 20);
  assert(same(nothing, dba, 244, 258)); // the status line does not depend on the reading

  // Text width: the widest lines end inside the screen with blank margins
  //   "NOISE: MODERATE 140 DBA"  23 glyphs = 276 px, "NOISE: MODERATE -96 DBFS" 24 = 288 px
  //   "MIC CAL: MEASURING"      18 glyphs = 216 px
  MicCalibration hot;
  assert(MicCalibration::fromFloor(100, 200, hot));
  const NoiseReading loud = NoiseClassifier::makeReading(NoiseBand::QUIET, 32767, hot);
  const NoiseReading silent = {true, 0, NoiseBand::MODERATE, MicSelfTest{}};
  St7305Framebuffer failed_fb;
  render(failed_fb, reading, cal, false, true);
  assert(!same(failed_fb, dba, 244, 258) && !same(failed_fb, busy, 244, 258));
  assert(inkIn(failed_fb, 244, 258, 10 + 16 * 12) == 0); // "MIC CAL: FAILED" = 15 glyphs
  assert(same(failed_fb, dba, 204, 218));
  St7305Framebuffer wide_dba, wide_dbfs, wide_busy;
  render(wide_dba, loud, hot);
  render(wide_dbfs, silent, MicCalibration{});
  render(wide_busy, silent, cal, true);
  St7305Framebuffer* frames[] = {&dbfs, &dba, &nothing, &busy, &wide_dba, &wide_dbfs, &wide_busy};
  for (St7305Framebuffer* fb : frames) {
    for (uint16_t y = DashboardView::HEADER_HEIGHT; y < St7305Framebuffer::HEIGHT; ++y) {
      for (uint16_t x = St7305Framebuffer::WIDTH - 8; x < St7305Framebuffer::WIDTH; ++x) {
        assert(!fb->getPixel(x, y));
      }
    }
    for (uint16_t y = St7305Framebuffer::HEIGHT - 8; y < St7305Framebuffer::HEIGHT; ++y) {
      assert(inkIn(*fb, y, y + 1) == 0);
    }
    // The noise line stays clear of the uptime line above and the status line stays clear of it
    assert(inkIn(*fb, 179, 184) == 0 && inkIn(*fb, 198, 204) == 0);
    assert(inkIn(*fb, 220, 224) == 0 && inkIn(*fb, 239, 244) == 0);
  }
  assert(inkIn(wide_dba, 204, 218, 10 + 23 * 12) == 0);
  assert(inkIn(wide_dbfs, 204, 218, 10 + 24 * 12) == 0);
  assert(inkIn(wide_dbfs, 204, 218, 10 + 24 * 12 - 12) > 0);
  assert(inkIn(wide_busy, 244, 258, 10 + 18 * 12) == 0);
  // Status line text lengths: "OK" is shorter than "DEFAULT" and "MEASURING"
  assert(inkIn(dba, 244, 258) < inkIn(dbfs, 244, 258));
  assert(inkIn(dbfs, 244, 258) < inkIn(busy, 244, 258));
  // Other views ignore the calibration
  SystemInfo info;
  info.mic_cal = cal;
  info.mic_cal_busy = true;
  St7305Framebuffer a, b;
  Shtc3Reading onboard;
  DashboardView::renderMode(a, DisplayMode::SENSOR_STATS, onboard, battery, nullptr, 0, info, "88:88", 0);
  DashboardView::renderMode(b, DisplayMode::SENSOR_STATS, onboard, battery, nullptr, 0, SystemInfo{}, "88:88", 0);
  assert(memcmp(a.data(), b.data(), RLCD_BUFFER_SIZE) == 0);
}

DeviceConfig parseText(const char* text, ConfigParseResult* result = nullptr) {
  DeviceConfig cfg;
  const ConfigParseResult r = DeviceConfigParser::parse(text, strlen(text), cfg);
  if (result != nullptr) {
    *result = r;
  }
  return cfg;
}

void testDeviceConfigDefaultsAndValidValues() {
  const DeviceConfig d;
  assert(approxEqual(d.temp_offset_c, -1.9f) && approxEqual(d.freeze_alert_limit_c, 0.0f));
  assert(d.mold_alert_limit_rh == 70 && d.chime_volume_pct == 30);
  assert(d.quiet_hours_start_hour == 22 && d.quiet_hours_end_hour == 7);
  assert(approxEqual(d.mic_spl_offset_db, 0.0f) && d.valid() && d.isDefault());
  assert(approxEqual(Shtc3Codec::DEFAULT_TEMP_OFFSET_C, DeviceConfig::DEFAULT_TEMP_OFFSET_C));

  ConfigParseResult r;
  DeviceConfig c = parseText(
      "temp_offset_c=-2.5\nfreeze_alert_limit_c=3.5\nmold_alert_limit_rh=80\n"
      "chime_volume_pct=55\nquiet_hours_start_hour=23\nquiet_hours_end_hour=6\n"
      "mic_spl_offset_db=-4.5\n",
      &r);
  assert(r.file_ok && r.keys_set == 7 && r.rejected == 0);
  assert(approxEqual(c.temp_offset_c, -2.5f) && approxEqual(c.freeze_alert_limit_c, 3.5f));
  assert(c.mold_alert_limit_rh == 80 && c.chime_volume_pct == 55);
  assert(c.quiet_hours_start_hour == 23 && c.quiet_hours_end_hour == 6);
  assert(approxEqual(c.mic_spl_offset_db, -4.5f) && c.valid() && !c.isDefault());

  // Range limits are inclusive
  c = parseText("temp_offset_c=-10\nfreeze_alert_limit_c=15\nmold_alert_limit_rh=40\nchime_volume_pct=0\n"
                "quiet_hours_start_hour=0\nquiet_hours_end_hour=23\nmic_spl_offset_db=+20.0\n", &r);
  assert(r.keys_set == 7 && r.rejected == 0);
  assert(approxEqual(c.temp_offset_c, -10.0f) && approxEqual(c.freeze_alert_limit_c, 15.0f));
  assert(c.mold_alert_limit_rh == 40 && c.chime_volume_pct == 0);
  assert(c.quiet_hours_start_hour == 0 && c.quiet_hours_end_hour == 23);
  assert(approxEqual(c.mic_spl_offset_db, 20.0f));
}

void testDeviceConfigCommentsSpacesAndLineEnds() {
  ConfigParseResult r;
  DeviceConfig c = parseText(
      "# comment\n; other comment\n\n   \n  temp_offset_c =  1.5  \n\tchime_volume_pct\t=\t10\t\n"
      "  # indented comment\nunknown_key=5\nwifi_ssid=secret\nwifi_password=secret\n", &r);
  assert(r.file_ok && r.keys_set == 2 && r.rejected == 0);
  assert(approxEqual(c.temp_offset_c, 1.5f) && c.chime_volume_pct == 10);
  assert(c.mold_alert_limit_rh == 70); // not in the file: the default

  // A lone CR is no line end: the file is one malformed line
  c = parseText("chime_volume_pct=10\rmold_alert_limit_rh=60\r", &r);
  assert(r.keys_set == 0 && r.rejected == 1 && c.isDefault());

  // CRLF line ends and a file without a final newline
  c = parseText("temp_offset_c=2.0\r\nmold_alert_limit_rh=60\r\n# note\r\nchime_volume_pct=45", &r);
  assert(r.keys_set == 3 && r.rejected == 0);
  assert(approxEqual(c.temp_offset_c, 2.0f) && c.mold_alert_limit_rh == 60 && c.chime_volume_pct == 45);

  // A UTF-8 byte order mark does not hide the first key
  c = parseText("\xEF\xBB\xBF" "chime_volume_pct=12\n", &r);
  assert(r.keys_set == 1 && c.chime_volume_pct == 12);

  // The card never supplies Wi-Fi credentials: the parser has no such field and counts no key
  parseText("wifi_ssid=net\nwifi_password=pw\nRLCD_WIFI_SSID=net\n", &r);
  assert(r.file_ok && r.keys_set == 0 && r.rejected == 0);
  // Key names are case sensitive
  c = parseText("Chime_Volume_Pct=99\n", &r);
  assert(r.keys_set == 0 && c.chime_volume_pct == 30);
}

void testDeviceConfigMissingMalformedAndOutOfRange() {
  ConfigParseResult r;
  DeviceConfig c = parseText("", &r);
  assert(r.file_ok && r.keys_set == 0 && c.isDefault());

  // Out of range: the default stays
  c = parseText("temp_offset_c=10.5\nfreeze_alert_limit_c=-20.1\nmold_alert_limit_rh=39\n"
                "chime_volume_pct=101\nquiet_hours_start_hour=24\nquiet_hours_end_hour=-1\n"
                "mic_spl_offset_db=20.5\n", &r);
  assert(r.file_ok && r.keys_set == 0 && r.rejected == 7 && c.isDefault());
  c = parseText("mold_alert_limit_rh=96\n", &r);
  assert(r.rejected == 1 && c.mold_alert_limit_rh == 70);

  // Malformed values: the default stays
  const char* bad[] = {"temp_offset_c=abc\n", "temp_offset_c=\n", "temp_offset_c=1.2.3\n",
                       "temp_offset_c=1e1\n", "temp_offset_c=nan\n", "temp_offset_c=inf\n",
                       "temp_offset_c=--1\n", "temp_offset_c=1.\n", "temp_offset_c=.5\n",
                       "temp_offset_c=1 2\n", "chime_volume_pct=30.5\n", "chime_volume_pct=0x10\n",
                       "chime_volume_pct=12abc\n", "chime_volume_pct=1234567890123456789\n",
                       "chime_volume_pct\n", "=5\n"};
  for (const char* text : bad) {
    c = parseText(text, &r);
    assert(r.file_ok && r.keys_set == 0 && c.isDefault());
  }

  // One bad line does not spoil the valid lines around it
  c = parseText("temp_offset_c=1.0\nchime_volume_pct=500\nmold_alert_limit_rh=65\n", &r);
  assert(r.keys_set == 2 && r.rejected == 1);
  assert(approxEqual(c.temp_offset_c, 1.0f) && c.chime_volume_pct == 30 && c.mold_alert_limit_rh == 65);
}

void testDeviceConfigDuplicateKeysLastWins() {
  ConfigParseResult r;
  DeviceConfig c = parseText("chime_volume_pct=10\nchime_volume_pct=20\nchime_volume_pct=75\n", &r);
  assert(r.keys_set == 1 && c.chime_volume_pct == 75);
  // An invalid later line does not replace an earlier valid value
  c = parseText("chime_volume_pct=10\nchime_volume_pct=999\n", &r);
  assert(r.keys_set == 1 && r.rejected == 1 && c.chime_volume_pct == 10);
}

void testDeviceConfigFileSizeLimit() {
  static char big[DeviceConfigParser::MAX_FILE_SIZE + 2];
  // A file of exactly 4096 bytes is read
  memset(big, '#', sizeof(big));
  const char* line = "chime_volume_pct=77\n";
  memcpy(big, line, strlen(line));
  big[DeviceConfigParser::MAX_FILE_SIZE - 1] = '\n';
  DeviceConfig c;
  ConfigParseResult r = DeviceConfigParser::parse(big, DeviceConfigParser::MAX_FILE_SIZE, c);
  assert(r.file_ok && r.keys_set == 1 && c.chime_volume_pct == 77);
  // One byte more: the whole file is rejected, even though the first line is valid
  r = DeviceConfigParser::parse(big, DeviceConfigParser::MAX_FILE_SIZE + 1, c);
  assert(!r.file_ok && r.keys_set == 0 && c.isDefault());
  // A null buffer with a size is rejected; an empty null buffer is an empty file
  c.chime_volume_pct = 99;
  r = DeviceConfigParser::parse(nullptr, 5, c);
  assert(!r.file_ok && c.isDefault());
  r = DeviceConfigParser::parse(nullptr, 0, c);
  assert(r.file_ok && c.isDefault());
}

void testDeviceConfigWiring() {
  // Temperature offset reaches the SHTC3 decoder
  uint8_t frame[6] = {0x64, 0x8C, 0, 0x80, 0x00, 0};
  frame[2] = Shtc3Codec::computeCrc8(frame, 2);
  frame[5] = Shtc3Codec::computeCrc8(frame + 3, 2);
  Shtc3Reading a, b;
  assert(Shtc3Codec::decodeFrame(frame, sizeof(frame), a, 0.0f));
  assert(Shtc3Codec::decodeFrame(frame, sizeof(frame), b, parseText("temp_offset_c=2.5\n").temp_offset_c));
  assert(approxEqual(b.temperature_c - a.temperature_c, 2.5f));

  // Alert limits move the trigger and keep a fixed hysteresis
  const AlertLimits def;
  // One source of truth for the defaults
  assert(def.freezer_trigger_c == DeviceConfig::DEFAULT_FREEZE_ALERT_LIMIT_C);
  assert(def.humidity_trigger_pct == static_cast<float>(DeviceConfig::DEFAULT_MOLD_ALERT_LIMIT_RH));
  assert(def.freezer_trigger_c == AlertEvaluator::FREEZER_TRIGGER_C);
  assert(def.humidity_trigger_pct == AlertEvaluator::HUMIDITY_TRIGGER_PCT);
  assert(QuietHoursWindow{}.start_hour == DeviceConfig::DEFAULT_QUIET_HOURS_START_HOUR);
  assert(QuietHoursWindow{}.end_hour == DeviceConfig::DEFAULT_QUIET_HOURS_END_HOUR);
  assert(ChimeSynth::DEFAULT_VOLUME_PCT == DeviceConfig::DEFAULT_CHIME_VOLUME_PCT);
  assert(approxEqual(def.freezerReleaseC(), AlertEvaluator::FREEZER_RELEASE_C));
  assert(approxEqual(def.humidityReleasePct(), AlertEvaluator::HUMIDITY_RELEASE_PCT));
  AlertLimits lim;
  lim.freezer_trigger_c = 5.0f;
  lim.humidity_trigger_pct = 50.0f;
  Shtc3Reading onboard = makeOnboard(4.0f, 52.0f);
  assert(AlertEvaluator::evaluate(ALERT_NONE, onboard, nullptr, 0, 0) == ALERT_NONE);
  assert(AlertEvaluator::evaluate(ALERT_NONE, onboard, nullptr, 0, 0, lim) == (ALERT_FREEZER | ALERT_HUMIDITY));
  // Between trigger and release limit an active alert holds
  onboard = makeOnboard(5.5f, 48.0f);
  assert(AlertEvaluator::evaluate((ALERT_FREEZER | ALERT_HUMIDITY), onboard, nullptr, 0, 0, lim) == (ALERT_FREEZER | ALERT_HUMIDITY));
  onboard = makeOnboard(6.5f, 46.0f);
  assert(AlertEvaluator::evaluate((ALERT_FREEZER | ALERT_HUMIDITY), onboard, nullptr, 0, 0, lim) == ALERT_NONE);

  // Quiet hours window from the two hours
  const DeviceConfig q = parseText("quiet_hours_start_hour=1\nquiet_hours_end_hour=5\n");
  const QuietHoursWindow w = QuietHoursWindow::fromHours(q.quiet_hours_start_hour, q.quiet_hours_end_hour);
  assert(w.isInQuietHours(1, 0) && w.isInQuietHours(4, 59) && !w.isInQuietHours(5, 0) && !w.isInQuietHours(23, 0));
  assert(!QuietHoursWindow::fromHours(8, 8).isInQuietHours(8, 0)); // equal hours: never quiet

  // Volume 0 stays silent
  struct NullHal {
    int writes = 0;
    bool beginBus() { return true; }
    bool configureCodec() { return true; }
    void setAmplifier(bool) {}
    bool write(const int16_t*, size_t) { ++writes; return true; }
    void drain() {}
    void shutdownCodec() {}
    void endBus() {}
  } silent, loud;
  assert(!AudioSequencer::play(silent, ALERT_FREEZER, 0) && silent.writes == 0);
  assert(AudioSequencer::play(loud, ALERT_FREEZER, parseText("chime_volume_pct=80\n").chime_volume_pct) &&
         loud.writes > 0);

  // SPL offset adds to the stored calibration, with a clamp, and leaves the stored record alone
  MicCalibration cal;
  assert(MicCalibration::fromFloor(100, 90, cal));
  const MicCalibration shifted = cal.withExtraOffset(-4.5f);
  assert(shifted.spl_offset_db == 86 || shifted.spl_offset_db == 85);
  assert(shifted.splDb(650) == cal.splDb(650) - (90 - shifted.spl_offset_db));
  assert(cal.spl_offset_db == 90 && shifted.calibrated && shifted.noise_floor_rms == 100);
  MicCalibration low;
  low.spl_offset_db = 5;
  assert(low.withExtraOffset(-20.0f).spl_offset_db == 0);
  MicCalibration high;
  high.spl_offset_db = 195;
  assert(high.withExtraOffset(20.0f).spl_offset_db == 200);
  assert(cal.withExtraOffset(NAN).spl_offset_db == 90);
}

void testDeviceConfigRtcRoundTrip() {
  assert(PowerManager::needsConfigLoad(WakeupCause::POWER_ON));
  assert(PowerManager::needsConfigLoad(WakeupCause::KEY_BUTTON));
  assert(!PowerManager::needsConfigLoad(WakeupCause::TIMER));
  assert(!PowerManager::needsConfigLoad(WakeupCause::OTHER));

  PersistentState s;
  s.config = parseText(
      "temp_offset_c=-3.25\nfreeze_alert_limit_c=-5\nmold_alert_limit_rh=85\nchime_volume_pct=60\n"
      "quiet_hours_start_hour=21\nquiet_hours_end_hour=8\nmic_spl_offset_db=7.5\n");
  s.config_from_sd = true;
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  PersistentState r;
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(r.config_from_sd);
  assert(approxEqual(r.config.temp_offset_c, -3.25f) && approxEqual(r.config.freeze_alert_limit_c, -5.0f));
  assert(r.config.mold_alert_limit_rh == 85 && r.config.chime_volume_pct == 60);
  assert(r.config.quiet_hours_start_hour == 21 && r.config.quiet_hours_end_hour == 8);
  assert(approxEqual(r.config.mic_spl_offset_db, 7.5f));

  // A default state restores as the defaults and as "not from SD"
  PersistentState d;
  assert(PowerManager::serialize(d, buf, sizeof(buf)));
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(r.config.isDefault() && !r.config_from_sd);

  // A corrupt record is rejected, with a bad checksum and with a valid checksum
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  const size_t cfg_pos = PowerManager::SERIALIZED_SIZE - 4 - PowerManager::CONFIG_SIZE;
  uint8_t bad[PowerManager::SERIALIZED_SIZE];
  memcpy(bad, buf, sizeof(bad));
  bad[cfg_pos + 8] ^= 0x40;
  assert(!PowerManager::deserialize(bad, sizeof(bad), r));
  assert(r.config.isDefault());

  struct Corrupt { size_t offset; uint8_t value; };
  const Corrupt cases[] = {
      {8, 90},   // mold limit 90 is valid: control case below handles it
      {8, 39},   // mold limit below range
      {8, 96},   // mold limit above range
      {9, 101},  // volume above range
      {10, 24},  // quiet start hour above range
      {11, 24},  // quiet end hour above range
      {16, 121}, // log retention above range
      {18, 32},  // chime alert mask above range
      {19, 5},   // NTP sync interval below range
      {19, 169}, // NTP sync interval above range
      {20, 29},  // wake interval low byte 29 with high byte 0: below range
      {21, 4},   // wake interval high byte 4: 60 + 1024 above range
      {50, 2},   // invalid character in the onboard name
      {PowerManager::CONFIG_SIZE - PowerManager::REJECTS_SIZE - 1, 2}, // unknown flag bit
  };
  for (const Corrupt& c : cases) {
    memcpy(bad, buf, sizeof(bad));
    bad[cfg_pos + c.offset] = c.value;
    const uint32_t crc = PowerManager::crc32(bad, sizeof(bad) - 4);
    for (int i = 0; i < 4; ++i) bad[sizeof(bad) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
    const bool accepted = PowerManager::deserialize(bad, sizeof(bad), r);
    assert(accepted == (c.offset == 8 && c.value == 90));
  }
  // A float that is NaN or outside the range is rejected
  const float nan_value = NAN;
  const float far_value = 99.0f;
  for (const float f : {nan_value, far_value}) {
    for (const size_t off : {static_cast<size_t>(0), static_cast<size_t>(4), static_cast<size_t>(12), static_cast<size_t>(22)}) {
      memcpy(bad, buf, sizeof(bad));
      memcpy(bad + cfg_pos + off, &f, 4);
      const uint32_t crc = PowerManager::crc32(bad, sizeof(bad) - 4);
      for (int i = 0; i < 4; ++i) bad[sizeof(bad) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
      assert(!PowerManager::deserialize(bad, sizeof(bad), r));
    }
  }
  // The old state version (without the config trailer) gives a clean start
  memcpy(bad, buf, sizeof(bad));
  bad[4] = PowerManager::STATE_VERSION - 1;
  const uint32_t crc = PowerManager::crc32(bad, sizeof(bad) - 4);
  for (int i = 0; i < 4; ++i) bad[sizeof(bad) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  assert(!PowerManager::deserialize(bad, sizeof(bad), r) && r.config.isDefault());
}

void testShtc3HumidityOffset() {
  // 0x8000 -> 50.0 %RH
  auto makeFrame = [](uint16_t raw_rh, uint8_t* frame) {
    frame[0] = 0x66; frame[1] = 0x66;
    frame[3] = static_cast<uint8_t>(raw_rh >> 8); frame[4] = static_cast<uint8_t>(raw_rh & 0xFF);
    frame[2] = Shtc3Codec::computeCrc8(frame, 2);
    frame[5] = Shtc3Codec::computeCrc8(frame + 3, 2);
  };
  uint8_t frame[6];
  makeFrame(0x8000, frame);
  Shtc3Reading out;
  // Default: no humidity offset
  assert(DeviceConfig{}.rh_offset_pct == 0.0f && DeviceConfig{}.isDefault());
  assert(Shtc3Codec::decodeFrame(frame, sizeof(frame), out));
  assert(approxEqual(out.humidity_pct, 50.0f, 0.01f));
  // Edges
  assert(Shtc3Codec::decodeFrame(frame, sizeof(frame), out, -1.9f, 10.0f));
  assert(approxEqual(out.humidity_pct, 60.0f, 0.01f) && approxEqual(out.temperature_c, 23.1f, 0.1f));
  assert(Shtc3Codec::decodeFrame(frame, sizeof(frame), out, -1.9f, -10.0f));
  assert(approxEqual(out.humidity_pct, 40.0f, 0.01f));
  // Clamp at 0 and 100 %RH
  makeFrame(0x0000, frame);
  assert(Shtc3Codec::decodeFrame(frame, sizeof(frame), out, -1.9f, -10.0f));
  assert(out.humidity_pct == 0.0f);
  makeFrame(0xFFFF, frame);
  assert(Shtc3Codec::decodeFrame(frame, sizeof(frame), out, -1.9f, 10.0f));
  assert(out.humidity_pct == 100.0f);
  makeFrame(0xF000, frame); // 93.75 %RH
  assert(Shtc3Codec::decodeFrame(frame, sizeof(frame), out, -1.9f, 10.0f));
  assert(out.humidity_pct == 100.0f);

  // Parser: edges, out of range and malformed values
  DeviceConfig cfg;
  auto parse = [&](const char* text) { return DeviceConfigParser::parse(text, strlen(text), cfg); };
  assert(parse("rh_offset_pct=10.0\n").keys_set == 1 && approxEqual(cfg.rh_offset_pct, 10.0f));
  assert(!cfg.isDefault() && cfg.valid());
  assert(parse("rh_offset_pct = -10.0\r\n").keys_set == 1 && approxEqual(cfg.rh_offset_pct, -10.0f));
  assert(parse("rh_offset_pct=-3.5\n").keys_set == 1 && approxEqual(cfg.rh_offset_pct, -3.5f));
  for (const char* bad : {"rh_offset_pct=10.1\n", "rh_offset_pct=-10.1\n", "rh_offset_pct=abc\n",
                          "rh_offset_pct=\n", "rh_offset_pct=1.2.3\n", "rh_offset_pct=nan\n",
                          "rh_offset_pct=inf\n", "rh_offset_pct=1e1\n"}) {
    cfg = DeviceConfig{};
    const ConfigParseResult r = parse(bad);
    assert(r.keys_set == 0 && r.rejected == 1 && cfg.rh_offset_pct == 0.0f);
  }
  cfg.rh_offset_pct = 10.5f;
  assert(!cfg.valid());
  cfg.rh_offset_pct = NAN;
  assert(!cfg.valid());

  // RTC round trip and rejection of the old state version
  PersistentState s;
  s.config.rh_offset_pct = -4.25f;
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  PersistentState r;
  assert(PowerManager::deserialize(buf, sizeof(buf), r) && approxEqual(r.config.rh_offset_pct, -4.25f));
  assert(PowerManager::STATE_VERSION == 26);
  buf[4] = 16;
  const uint32_t crc = PowerManager::crc32(buf, sizeof(buf) - 4);
  for (int i = 0; i < 4; ++i) buf[sizeof(buf) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  assert(!PowerManager::deserialize(buf, sizeof(buf), r));

  // The corrected value reaches the tile, the sparkline sample and the CSV row
  makeFrame(0x8000, frame);
  Shtc3Reading corrected;
  assert(Shtc3Codec::decodeFrame(frame, sizeof(frame), corrected, -1.9f, 5.0f));
  assert(approxEqual(corrected.humidity_pct, 55.0f, 0.01f));
  assert(HumidityHistory::encode(corrected.humidity_pct) == 550);
}

void testSlotCalibrationOffsets() {
  const char* const temp_keys[3] = {"slot1_temp_offset_c", "slot2_temp_offset_c", "slot3_temp_offset_c"};
  const char* const rh_keys[3] = {"slot1_rh_offset_pct", "slot2_rh_offset_pct", "slot3_rh_offset_pct"};

  // Defaults: no offset, so a slot keeps its raw value
  const DeviceConfig defaults;
  for (size_t i = 0; i < 3; ++i) {
    assert(defaults.slot_temp_offset_c[i] == 0.0f && defaults.slot_rh_offset_pct[i] == 0.0f);
  }
  assert(defaults.isDefault() && defaults.valid());

  // Parser: edges, each key sets its own slot only
  DeviceConfig cfg;
  char line[64];
  for (size_t i = 0; i < 3; ++i) {
    snprintf(line, sizeof(line), "%s=5.0\n%s=-10.0\n", temp_keys[i], rh_keys[i]);
    const ConfigParseResult r = DeviceConfigParser::parse(line, strlen(line), cfg);
    assert(r.keys_set == 2 && r.rejected == 0 && !cfg.isDefault() && cfg.valid());
    for (size_t j = 0; j < 3; ++j) {
      assert(approxEqual(cfg.slot_temp_offset_c[j], j == i ? 5.0f : 0.0f));
      assert(approxEqual(cfg.slot_rh_offset_pct[j], j == i ? -10.0f : 0.0f));
    }
    snprintf(line, sizeof(line), " %s = -5.0 \r\n%s=10.0\n", temp_keys[i], rh_keys[i]);
    assert(DeviceConfigParser::parse(line, strlen(line), cfg).keys_set == 2);
    assert(approxEqual(cfg.slot_temp_offset_c[i], -5.0f) && approxEqual(cfg.slot_rh_offset_pct[i], 10.0f));
    // The onboard keys stay untouched
    assert(approxEqual(cfg.temp_offset_c, DeviceConfig::DEFAULT_TEMP_OFFSET_C) && cfg.rh_offset_pct == 0.0f);
  }

  // Out of range and malformed values keep the previous value
  for (size_t i = 0; i < 3; ++i) {
    for (const char* bad : {"=5.1\n", "=-5.1\n", "=abc\n", "=\n", "=1.2.3\n", "=nan\n", "=inf\n", "=1e1\n"}) {
      snprintf(line, sizeof(line), "%s%s", temp_keys[i], bad);
      cfg = DeviceConfig{};
      const ConfigParseResult r = DeviceConfigParser::parse(line, strlen(line), cfg);
      assert(r.keys_set == 0 && r.rejected == 1 && cfg.slot_temp_offset_c[i] == 0.0f);
    }
    for (const char* bad : {"=10.1\n", "=-10.1\n", "=abc\n", "=\n", "=1.2.3\n", "=nan\n", "=inf\n", "=1e1\n"}) {
      snprintf(line, sizeof(line), "%s%s", rh_keys[i], bad);
      cfg = DeviceConfig{};
      const ConfigParseResult r = DeviceConfigParser::parse(line, strlen(line), cfg);
      assert(r.keys_set == 0 && r.rejected == 1 && cfg.slot_rh_offset_pct[i] == 0.0f);
    }
  }
  {
    const char* text = "slot2_temp_offset_c=2.0\nslot2_temp_offset_c=9.0\n";
    DeviceConfigParser::parse(text, strlen(text), cfg);
    assert(approxEqual(cfg.slot_temp_offset_c[1], 2.0f)); // the rejected line keeps the earlier value
  }
  // The validity check rejects a value outside the range or NaN
  for (size_t i = 0; i < 3; ++i) {
    cfg = DeviceConfig{};
    cfg.slot_temp_offset_c[i] = 5.5f;
    assert(!cfg.valid());
    cfg.slot_temp_offset_c[i] = NAN;
    assert(!cfg.valid());
    cfg = DeviceConfig{};
    cfg.slot_rh_offset_pct[i] = -10.5f;
    assert(!cfg.valid());
    cfg.slot_rh_offset_pct[i] = NAN;
    assert(!cfg.valid());
  }

  // Application to the correct slot only (SwitchBot in slot 0 and 1, BTHome in slot 2)
  SwitchBotRegistry registry;
  assert(registry.upsert(makeReading(1, 4.0f, 50, 90)));
  assert(registry.upsert(makeReading(2, 4.0f, 50, 90)));
  SwitchBotReading bthome;
  const uint8_t mac[6] = {1, 2, 3, 4, 5, 6};
  const uint8_t payload[] = {0x40, 0x02, 0x90, 0x01, 0x03, 0x88, 0x13}; // 4.00 C, 50.00 %
  assert(BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, mac, payload, sizeof(payload), bthome));
  assert(registry.upsert(bthome));
  const float temp_off[3] = {1.5f, 0.0f, -2.5f};
  const float rh_off[3] = {0.0f, -8.0f, 7.0f};
  const SwitchBotRegistry calibrated = registry.calibrated(temp_off, rh_off);
  assert(approxEqual(calibrated.slot(0).temperature_c, 5.5f) && calibrated.slot(0).humidity_pct == 50);
  assert(approxEqual(calibrated.slot(1).temperature_c, 4.0f) && calibrated.slot(1).humidity_pct == 42);
  assert(approxEqual(calibrated.slot(2).temperature_c, 1.5f) && calibrated.slot(2).humidity_pct == 57);
  // Everything else and the raw registry stay as they are
  assert(calibrated.slot(1).battery_pct == 90 && calibrated.slot(1).rssi == -71 &&
         memcmp(calibrated.slot(1).mac, registry.slot(1).mac, 6) == 0 &&
         calibrated.slot(1).updated_ms == registry.slot(1).updated_ms);
  assert(approxEqual(registry.slot(0).temperature_c, 4.0f) && registry.slot(1).humidity_pct == 50);
  // An empty slot stays empty
  assert(!calibrated.slot(2).valid == !registry.slot(2).valid);
  SwitchBotRegistry two;
  two.upsert(makeReading(1, 4.0f, 50, 90));
  assert(!two.calibrated(temp_off, rh_off).slot(1).valid && !two.calibrated(temp_off, rh_off).slot(2).valid);
  // Null offsets and zero offsets keep the raw value
  const SwitchBotRegistry raw = registry.calibrated(nullptr, nullptr);
  const float zero[3] = {0.0f, 0.0f, 0.0f};
  const SwitchBotRegistry zeroed = registry.calibrated(zero, zero);
  for (size_t i = 0; i < 3; ++i) {
    assert(raw.slot(i).temperature_c == registry.slot(i).temperature_c && raw.slot(i).humidity_pct == registry.slot(i).humidity_pct);
    assert(zeroed.slot(i).temperature_c == registry.slot(i).temperature_c && zeroed.slot(i).humidity_pct == registry.slot(i).humidity_pct);
  }

  // Humidity clamp at 0 and 100 %RH
  SwitchBotRegistry edge;
  edge.upsert(makeReading(1, 20.0f, 5, 50));
  edge.upsert(makeReading(2, 20.0f, 97, 50));
  const float rh_edge[3] = {-10.0f, 10.0f, 0.0f};
  const SwitchBotRegistry clamped = edge.calibrated(zero, rh_edge);
  assert(clamped.slot(0).humidity_pct == 0 && clamped.slot(1).humidity_pct == 100);

  // Effect on the freezer and mold alerts: the alert sees the corrected value
  auto fresh = [](uint8_t id, float temp, uint8_t hum) {
    SwitchBotReading r = makeReading(id, temp, hum, 50);
    r.updated_ms = 1000; // fresh at the evaluation time
    return r;
  };
  SwitchBotRegistry cold;
  cold.upsert(fresh(1, 0.5f, 60)); // raw 0.5 C: above the freezer limit
  cold.upsert(fresh(2, 20.0f, 68)); // raw 68 %RH: below the mold limit
  Shtc3Reading none; // invalid onboard reading
  auto slots_of = [](const SwitchBotRegistry& rg, SwitchBotReading* out) {
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) out[i] = rg.slot(i);
  };
  SwitchBotReading view[MAX_SWITCHBOT_SENSORS];
  slots_of(cold, view);
  assert(AlertEvaluator::evaluate(ALERT_NONE, none, view, MAX_SWITCHBOT_SENSORS, 1000) == ALERT_NONE);
  const float freezer_off[3] = {-1.0f, 0.0f, 0.0f};
  const float mold_off[3] = {0.0f, 5.0f, 0.0f};
  slots_of(cold.calibrated(freezer_off, zero), view);
  assert((AlertEvaluator::evaluate(ALERT_NONE, none, view, MAX_SWITCHBOT_SENSORS, 1000) & ALERT_FREEZER) != 0);
  slots_of(cold.calibrated(zero, mold_off), view);
  assert((AlertEvaluator::evaluate(ALERT_NONE, none, view, MAX_SWITCHBOT_SENSORS, 1000) & ALERT_HUMIDITY) != 0);
  // A negative offset clears an alert that the raw value would set
  SwitchBotRegistry wet;
  wet.upsert(fresh(1, 20.0f, 72));
  slots_of(wet, view);
  assert((AlertEvaluator::evaluate(ALERT_NONE, none, view, MAX_SWITCHBOT_SENSORS, 1000) & ALERT_HUMIDITY) != 0);
  const float dry_off[3] = {0.0f, 0.0f, 0.0f};
  const float rh_dry[3] = {-5.0f, 0.0f, 0.0f};
  slots_of(wet.calibrated(dry_off, rh_dry), view);
  assert(AlertEvaluator::evaluate(ALERT_NONE, none, view, MAX_SWITCHBOT_SENSORS, 1000) == ALERT_NONE);

  // The history receives the corrected value
  ClimateHistory history;
  assert(history.record(1000, false, 0.0f, wet.calibrated(dry_off, rh_dry)));
  assert(history.slots[1].size() == 1 && history.slots[1].at(0) == HumidityHistory::encode(67.0f));

  // RTC round trip and rejection of the old state version
  PersistentState s;
  for (size_t i = 0; i < 3; ++i) {
    s.config.slot_temp_offset_c[i] = -5.0f + 2.5f * static_cast<float>(i);
    s.config.slot_rh_offset_pct[i] = 10.0f - 7.5f * static_cast<float>(i);
  }
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  PersistentState r;
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  for (size_t i = 0; i < 3; ++i) {
    assert(r.config.slot_temp_offset_c[i] == s.config.slot_temp_offset_c[i]);
    assert(r.config.slot_rh_offset_pct[i] == s.config.slot_rh_offset_pct[i]);
  }
  assert(PowerManager::STATE_VERSION == 26);
  uint8_t old[PowerManager::SERIALIZED_SIZE];
  memcpy(old, buf, sizeof(old));
  old[4] = 17;
  const uint32_t crc = PowerManager::crc32(old, sizeof(old) - 4);
  for (int i = 0; i < 4; ++i) old[sizeof(old) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  assert(!PowerManager::deserialize(old, sizeof(old), r));
  // A corrupt offset never reaches the registry: out of range gives a clean start
  PersistentState bad = s;
  bad.config.slot_rh_offset_pct[2] = 11.0f;
  assert(PowerManager::serialize(bad, buf, sizeof(buf)));
  assert(!PowerManager::deserialize(buf, sizeof(buf), r) && r.config.isDefault());
}

void testSensorNames() {
  const char* const keys[4] = {"onboard_name", "slot1_name", "slot2_name", "slot3_name"};
  auto nameOf = [](DeviceConfig& c, size_t k) -> const char* { return k == 0 ? c.onboard_name : c.slot_name[k - 1]; };

  // Defaults: no name, default titles
  const DeviceConfig defaults;
  for (size_t k = 0; k < 4; ++k) {
    DeviceConfig d;
    assert(nameOf(d, k)[0] == '\0');
  }
  assert(defaults.isDefault() && defaults.valid());
  char title[24];
  DashboardView::tileTitle(title, sizeof(title), nullptr, 0);
  assert(strcmp(title, "ONBOARD SHTC3") == 0);
  DashboardView::tileTitle(title, sizeof(title), &defaults, 0);
  assert(strcmp(title, "ONBOARD SHTC3") == 0);
  for (size_t t = 1; t <= 3; ++t) {
    DashboardView::tileTitle(title, sizeof(title), &defaults, t);
    char expect[16];
    snprintf(expect, sizeof(expect), "SENSOR %u", static_cast<unsigned>(t));
    assert(strcmp(title, expect) == 0);
  }

  // Valid names: 12 characters, digits, space, minus; spaces and CRLF around the value are trimmed
  char line[96];
  for (size_t k = 0; k < 4; ++k) {
    DeviceConfig cfg;
    snprintf(line, sizeof(line), "%s=KUECHE 1-2 O\n", keys[k]);
    ConfigParseResult r = DeviceConfigParser::parse(line, strlen(line), cfg);
    assert(r.keys_set == 1 && r.rejected == 0 && strcmp(nameOf(cfg, k), "KUECHE 1-2 O") == 0);
    for (size_t other = 0; other < 4; ++other) {
      assert(other == k || nameOf(cfg, other)[0] == '\0');
    }
    snprintf(line, sizeof(line), "%s = WOHNZIMMER-1 \r\n", keys[k]);
    r = DeviceConfigParser::parse(line, strlen(line), cfg);
    assert(r.keys_set == 1 && r.rejected == 0 && strcmp(nameOf(cfg, k), "WOHNZIMMER-1") == 0);
    snprintf(line, sizeof(line), "%s=A\n", keys[k]);
    r = DeviceConfigParser::parse(line, strlen(line), cfg);
    assert(r.keys_set == 1 && strcmp(nameOf(cfg, k), "A") == 0);
    snprintf(line, sizeof(line), "%s=ABCDEFGHIJKL\n", keys[k]);
    r = DeviceConfigParser::parse(line, strlen(line), cfg);
    assert(r.keys_set == 1 && strcmp(nameOf(cfg, k), "ABCDEFGHIJKL") == 0 && cfg.valid() && !cfg.isDefault());
  }

  // Empty, too long, unsupported characters: rejected, the earlier value stays
  for (size_t k = 0; k < 4; ++k) {
    for (const char* bad : {"=\n", "=   \n", "=ABCDEFGHIJKLM\n", "=abc\n", "=A_B\n", "=A.B\n", "=A:B\n", "=A%B\n",
                            "=A/B\n", "=\xC3\x84RGER\n", "=A\tB\n", "=A=B\n"}) {
      DeviceConfig cfg;
      snprintf(line, sizeof(line), "%s=KEEP\n%s%s", keys[k], keys[k], bad);
      const ConfigParseResult r = DeviceConfigParser::parse(line, strlen(line), cfg);
      assert(r.keys_set == 1 && r.rejected == 1 && strcmp(nameOf(cfg, k), "KEEP") == 0);
    }
  }
  assert(DeviceConfig::validName("A B-1", 5));
  assert(!DeviceConfig::validName("", 0) && !DeviceConfig::validName("ABCDEFGHIJKLM", 13));
  assert(!DeviceConfig::validName("a", 1) && !DeviceConfig::validName("A!", 2));

  // Titles take the name; a missing config object keeps the default
  DeviceConfig named;
  strcpy(named.onboard_name, "FLUR");
  strcpy(named.slot_name[0], "WWWWWWWWWWWW");
  strcpy(named.slot_name[2], "KELLER 2");
  DashboardView::tileTitle(title, sizeof(title), &named, 0);
  assert(strcmp(title, "FLUR") == 0);
  DashboardView::tileTitle(title, sizeof(title), &named, 1);
  assert(strcmp(title, "WWWWWWWWWWWW") == 0);
  DashboardView::tileTitle(title, sizeof(title), &named, 2);
  assert(strcmp(title, "SENSOR 2") == 0);
  DashboardView::tileTitle(title, sizeof(title), &named, 3);
  assert(strcmp(title, "KELLER 2") == 0);

  // Title bounds: the widest name stays left of the stale badge and inside the tile
  Shtc3Reading onboard;
  onboard.valid = true;
  onboard.temperature_c = 21.0f;
  onboard.humidity_pct = 50.0f;
  BatteryReading battery;
  battery.valid = true;
  battery.voltage_v = 3.9f;
  battery.percentage = 70;
  SwitchBotReading slots[MAX_SWITCHBOT_SENSORS];
  for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
    slots[i].valid = true;
    slots[i].temperature_c = 18.0f;
    slots[i].humidity_pct = 55;
    slots[i].battery_pct = 90;
    slots[i].updated_ms = 100000;
  }
  DeviceConfig wide;
  strcpy(wide.onboard_name, "WWWWWWWWWWWW");
  for (size_t i = 0; i < 3; ++i) {
    strcpy(wide.slot_name[i], "WWWWWWWWWWWW");
  }
  St7305Framebuffer plain, with_names, stale;
  DashboardView::render(plain, onboard, battery, slots, MAX_SWITCHBOT_SENSORS, "12:00", 100000);
  DashboardView::render(with_names, onboard, battery, slots, MAX_SWITCHBOT_SENSORS, "12:00", 100000, nullptr, false, &wide);
  assert(memcmp(plain.data(), with_names.data(), RLCD_BUFFER_SIZE) != 0);
  St7305Framebuffer same;
  DashboardView::render(same, onboard, battery, slots, MAX_SWITCHBOT_SENSORS, "12:00", 100000, nullptr, false, &defaults);
  assert(memcmp(plain.data(), same.data(), RLCD_BUFFER_SIZE) == 0);
  slots[0].updated_ms = 100000 - SENSOR_STALE_TIMEOUT_MS - 1;
  slots[1].updated_ms = 100000 - SENSOR_STALE_TIMEOUT_MS - 1;
  slots[2].updated_ms = 100000 - SENSOR_STALE_TIMEOUT_MS - 1;
  DashboardView::render(stale, onboard, battery, slots, MAX_SWITCHBOT_SENSORS, "12:00", 100000, nullptr, false, &wide);
  const uint16_t tile_x[4] = {6, 204, 6, 204};
  const uint16_t tile_y[4] = {36, 36, 168, 168};
  const uint16_t tile_w = 190;
  // Title glyphs end at x + 8 + 12 * 12 - 2 = x + 150; the badge starts at x + w - 35 - 4 = x + 151.
  assert(8 + 12 * 12 - 2 < tile_w - DashboardView::STALE_BADGE_W - 4);
  for (size_t t = 0; t < 4; ++t) {
    bool ink_in_title = false;
    for (uint16_t dy = 6; dy < 6 + 14; ++dy) {
      for (uint16_t dx = 8; dx <= 150; ++dx) {
        ink_in_title = ink_in_title || with_names.getPixel(tile_x[t] + dx, tile_y[t] + dy);
      }
      // Fresh tile: right of the last glyph the title band stays empty up to the tile border
      for (uint16_t dx = 151; dx < tile_w - 1; ++dx) {
        assert(!with_names.getPixel(tile_x[t] + dx, tile_y[t] + dy));
      }
    }
    assert(ink_in_title);
    // Stale tile (slots only): the badge area holds the badge, the glyphs stay left of it
    if (t > 0) {
      bool badge = false;
      for (uint16_t dx = 151; dx < tile_w - 4; ++dx) {
        badge = badge || stale.getPixel(tile_x[t] + dx, tile_y[t] + 6);
      }
      assert(badge);
    }
  }

  // Stats view: the name replaces "S1" to "S3"; the longest line fits the 400 px width
  St7305Framebuffer stats_plain, stats_named, stats_default;
  SwitchBotReading live[MAX_SWITCHBOT_SENSORS];
  for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
    live[i].valid = true;
    live[i].rssi = -100;
    live[i].updated_ms = 100000;
  }
  DashboardView::renderSensorStats(stats_plain, battery, live, MAX_SWITCHBOT_SENSORS, "12:00", 100000);
  DashboardView::renderSensorStats(stats_default, battery, live, MAX_SWITCHBOT_SENSORS, "12:00", 100000, false, nullptr,
                                   nullptr, &defaults);
  DashboardView::renderSensorStats(stats_named, battery, live, MAX_SWITCHBOT_SENSORS, "12:00", 100000, false, nullptr,
                                   nullptr, &wide);
  assert(memcmp(stats_plain.data(), stats_default.data(), RLCD_BUFFER_SIZE) == 0);
  assert(memcmp(stats_plain.data(), stats_named.data(), RLCD_BUFFER_SIZE) != 0);
  char stats_line[48];
  DashboardView::statsLabel(stats_line, sizeof(stats_line), &wide, 0);
  assert(strcmp(stats_line, "WWWWWWWWWWWW") == 0);
  DashboardView::statsLabel(stats_line, sizeof(stats_line), &defaults, 2);
  assert(strcmp(stats_line, "S3") == 0);
  DashboardView::statsLabel(stats_line, sizeof(stats_line), nullptr, 1);
  assert(strcmp(stats_line, "S2") == 0);
  assert((12 + 1 + 13) * 12 <= static_cast<int>(RLCD_WIDTH)); // name + " RSSI -100 DBM"

  // Config text writes the names back and the parser reads them
  DeviceConfig round = wide;
  strcpy(round.slot_name[1], "A-B 1");
  char text[DeviceConfigText::MAX_SIZE];
  const size_t n = DeviceConfigText::format(round, text, sizeof(text));
  assert(n > 0);
  DeviceConfig back;
  const ConfigParseResult pr = DeviceConfigParser::parse(text, n, back);
  assert(pr.rejected == 0 && strcmp(back.onboard_name, "WWWWWWWWWWWW") == 0 && strcmp(back.slot_name[1], "A-B 1") == 0);
  assert(DeviceConfigText::format(defaults, text, sizeof(text)) > 0 && strstr(text, "_name") == nullptr);

  // RTC round trip, state version 21, and rejection of a corrupt name
  assert(PowerManager::STATE_VERSION == 26);
  PersistentState s;
  s.config = round;
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  PersistentState r;
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(strcmp(r.config.onboard_name, "WWWWWWWWWWWW") == 0 && strcmp(r.config.slot_name[0], "WWWWWWWWWWWW") == 0);
  assert(strcmp(r.config.slot_name[1], "A-B 1") == 0 && strcmp(r.config.slot_name[2], "WWWWWWWWWWWW") == 0);
  PersistentState empty;
  assert(PowerManager::serialize(empty, buf, sizeof(buf)));
  assert(PowerManager::deserialize(buf, sizeof(buf), r) && r.config.onboard_name[0] == '\0' && r.config.isDefault());
  PersistentState bad = s;
  bad.config.slot_name[1][0] = 'a';
  assert(PowerManager::serialize(bad, buf, sizeof(buf)));
  assert(!PowerManager::deserialize(buf, sizeof(buf), r) && r.config.isDefault());
  bad = s;
  memset(bad.config.onboard_name, 'A', sizeof(bad.config.onboard_name)); // no terminator
  assert(PowerManager::serialize(bad, buf, sizeof(buf)));
  assert(!PowerManager::deserialize(buf, sizeof(buf), r));
}

void testConfigRejectedLines() {
  ConfigParseResult r;
  // No error: count 0, no line
  parseText("temp_offset_c=1.0\n# note\n\nmold_alert_limit_rh=65\n", &r);
  assert(r.rejected == 0 && r.rejects().count == 0 && r.rejects().recorded() == 0 && r.rejected_lines[0] == 0);
  // One malformed value (line 2)
  parseText("temp_offset_c=1.0\nchime_volume_pct=abc\n", &r);
  assert(r.rejected == 1 && r.rejected_lines[0] == 2 && r.rejected_lines[1] == 0 && r.rejects().recorded() == 1);
  // One out-of-range value (line 3, after a comment and a blank line)
  parseText("# c\n\nmold_alert_limit_rh=96\n", &r);
  assert(r.rejected == 1 && r.rejected_lines[0] == 3);
  // A line without '=' is rejected too
  parseText("temp_offset_c\n", &r);
  assert(r.rejected == 1 && r.rejected_lines[0] == 1);
  // Six rejected lines: count 6, the first four line numbers
  parseText("chime_volume_pct=x\nchime_volume_pct=y\nok_unknown=1\nchime_volume_pct=z\n"
            "chime_volume_pct=200\nchime_volume_pct=300\nchime_volume_pct=400\n", &r);
  assert(r.rejected == 6 && r.rejects().count == 6 && r.rejects().recorded() == 4);
  assert(r.rejected_lines[0] == 1 && r.rejected_lines[1] == 2 && r.rejected_lines[2] == 4 &&
         r.rejected_lines[3] == 5);
  // CRLF line ends count like LF
  parseText("temp_offset_c=1.0\r\nchime_volume_pct=abc\r\n\r\nmold_alert_limit_rh=1\r\n", &r);
  assert(r.rejected == 2 && r.rejected_lines[0] == 2 && r.rejected_lines[1] == 4);
  // A byte order mark does not shift the line numbers
  parseText("\xEF\xBB\xBF" "chime_volume_pct=abc\n", &r);
  assert(r.rejected == 1 && r.rejected_lines[0] == 1);
  // An unknown key adds no error
  parseText("wifi_ssid=home\nunknown=1\n", &r);
  assert(r.rejected == 0 && r.rejected_lines[0] == 0);
  // The count saturates at 255; the line numbers stay the first four
  std::string many;
  for (int i = 0; i < 300; ++i) many += "x\n";
  parseText(many.c_str(), &r);
  assert(r.rejected == 255 && r.rejected_lines[3] == 4);
}

void testConfigRejectedRtcRoundTrip() {
  PersistentState s;
  ConfigParseResult r;
  s.config = parseText("chime_volume_pct=x\n\nchime_volume_pct=y\n", &r);
  s.applyConfig(s.config, true, r.rejects());
  assert(s.config_rejects.count == 2);
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  PersistentState back;
  assert(PowerManager::deserialize(buf, sizeof(buf), back));
  assert(back.config_rejects.count == 2 && back.config_rejects.lines[0] == 1 &&
         back.config_rejects.lines[1] == 3 && back.config_rejects.lines[2] == 0);
  // A new config without errors clears them
  s.applyConfig(DeviceConfig{}, true, ConfigRejects{});
  assert(s.config_rejects.count == 0);
  // A line number of 1000 survives (16 bit)
  s.config_rejects.count = 1;
  s.config_rejects.lines[0] = 1000;
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  assert(PowerManager::deserialize(buf, sizeof(buf), back) && back.config_rejects.lines[0] == 1000);
  // A corrupt record with a valid checksum is rejected: line 0 for a counted entry
  s.config_rejects.lines[0] = 0;
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  assert(!PowerManager::deserialize(buf, sizeof(buf), back));
  // ... and a line number for an uncounted entry
  s.config_rejects.count = 0;
  s.config_rejects.lines[0] = 7;
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  assert(!PowerManager::deserialize(buf, sizeof(buf), back));
}

void testFramebufferParenthesisGlyphs() {
  St7305Framebuffer fb;
  fb.clear(true);
  fb.drawText(0, 0, "()", 1, true);
  bool left = false, right = false;
  for (uint16_t y = 0; y < 7; ++y) {
    for (uint16_t x = 0; x < 6; ++x) left = left || fb.getPixel(x, y);
    for (uint16_t x = 6; x < 12; ++x) right = right || fb.getPixel(x, y);
  }
  assert(left && right); // both glyphs draw ink
}

void testDiagnosticsConfigErrorLine() {
  BatteryReading battery;
  battery.valid = true;
  battery.voltage_v = 3.9f;
  battery.percentage = 70;
  SystemInfo info;
  info.config_from_sd = true;
  St7305Framebuffer ok, err, err255, def;
  DashboardView::renderDiagnostics(ok, battery, info, "12:00");
  info.config_rejected = 3;
  DashboardView::renderDiagnostics(err, battery, info, "12:00");
  assert(memcmp(ok.data(), err.data(), RLCD_BUFFER_SIZE) != 0);
  info.config_rejected = 255;
  DashboardView::renderDiagnostics(err255, battery, info, "12:00");
  // The error text stays in the config lines: nothing differs above y = 270
  for (uint16_t y = 0; y < 270; ++y) {
    for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
      assert(ok.getPixel(x, y) == err255.getPixel(x, y));
    }
  }
  // "CONFIG: SD (255 ERR)" is 20 glyphs = 240 px, from x = 10 to x = 250. The short events text
  // "EV: N" starts at EVENTS_ERR_X = 264, so the gap x = 250 to 263 stays empty.
  bool ink_left = false;
  bool ink_events = false;
  for (uint16_t y = DashboardView::EVENTS_Y; y < DashboardView::EVENTS_Y + 14; ++y) {
    for (uint16_t x = 10; x < 250; ++x) ink_left = ink_left || err255.getPixel(x, y);
    for (uint16_t x = 250; x < DashboardView::EVENTS_ERR_X; ++x) assert(!err255.getPixel(x, y));
    for (uint16_t x = DashboardView::EVENTS_ERR_X; x < St7305Framebuffer::WIDTH; ++x) {
      ink_events = ink_events || err255.getPixel(x, y);
    }
  }
  assert(ink_left && ink_events);
  // The events text ends inside the panel: 9 glyphs from x = 264 end at x = 372
  for (uint16_t y = DashboardView::EVENTS_Y; y < DashboardView::EVENTS_Y + 14; ++y) {
    for (uint16_t x = 372; x < St7305Framebuffer::WIDTH; ++x) assert(!err255.getPixel(x, y));
  }
  // Defaults from a missing file never show the count
  info.config_from_sd = false;
  St7305Framebuffer none;
  DashboardView::renderDiagnostics(none, battery, info, "12:00");
  info.config_rejected = 0;
  St7305Framebuffer none0;
  DashboardView::renderDiagnostics(none0, battery, info, "12:00");
  assert(memcmp(none.data(), none0.data(), RLCD_BUFFER_SIZE) == 0);
}

void testDiagnosticsConfigLine() {
  BatteryReading battery;
  battery.valid = true;
  battery.voltage_v = 3.9f;
  battery.percentage = 70;
  St7305Framebuffer sd, def;
  SystemInfo info;
  info.config_from_sd = true;
  sd.clear(true);
  DashboardView::renderDiagnostics(sd, battery, info, "12:00");
  info.config_from_sd = false;
  def.clear(true);
  DashboardView::renderDiagnostics(def, battery, info, "12:00");
  size_t diff = 0;
  size_t ink_below = 0;
  for (uint16_t y = 0; y < St7305Framebuffer::HEIGHT; ++y) {
    for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
      if (sd.getPixel(x, y) != def.getPixel(x, y)) {
        ++diff;
        assert(y >= 270); // only the config line differs
      }
      if (y >= 270 && def.getPixel(x, y)) ++ink_below;
    }
  }
  assert(diff > 0 && ink_below > 0);
  // "CONFIG: DEFAULT" is 15 glyphs = 180 px; the events text starts at EVENTS_X
  for (uint16_t y = 270; y < St7305Framebuffer::HEIGHT; ++y) {
    for (uint16_t x = 10 + 15 * 12; x < DashboardView::EVENTS_X; ++x) {
      assert(!def.getPixel(x, y));
    }
  }
}

// ---- MicroSD free space and log retention ----

RtcTime rtcAt(uint16_t year, uint8_t month) {
  RtcTime t;
  t.valid = true;
  t.year = year;
  t.month = month;
  return t;
}

void testLogRetentionAge() {
  assert(SdLogCodec::monthsBetween(2026, 11, 2026, 11) == 0);
  assert(SdLogCodec::monthsBetween(2026, 10, 2026, 11) == 1);
  assert(SdLogCodec::monthsBetween(2025, 12, 2026, 1) == 1); // year change
  assert(SdLogCodec::monthsBetween(2025, 1, 2026, 1) == 12);
  assert(SdLogCodec::monthsBetween(2000, 1, 2099, 12) == 1199);
  assert(SdLogCodec::monthsBetween(2026, 12, 2026, 11) == -1); // a file from the future

  int y = 0, m = 0;
  assert(SdLogCodec::parseLogFileName("climate_v2_202512.csv", y, m) && y == 2025 && m == 12);
  y = m = 0;
  assert(!SdLogCodec::parseLogFileName("climate_v2_202513.csv", y, m) && y == 0 && m == 0);
}

void testLogRetentionDecision() {
  const RtcTime now = rtcAt(2026, 1);
  // Default 0 keeps every file, however old
  assert(!SdLogCodec::shouldDeleteLog("climate_v2_200001.csv", now, 0));
  // Retention 3 at January 2026: October 2025 (3 months) stays, September 2025 (4) goes
  assert(!SdLogCodec::shouldDeleteLog("climate_v2_202510.csv", now, 3));
  assert(SdLogCodec::shouldDeleteLog("climate_v2_202509.csv", now, 3));
  assert(SdLogCodec::shouldDeleteLog("climate_v2_202412.csv", now, 3));
  // Retention 1: last month stays, two months ago goes
  assert(!SdLogCodec::shouldDeleteLog("climate_v2_202512.csv", now, 1));
  assert(SdLogCodec::shouldDeleteLog("climate_v2_202511.csv", now, 1));
  // The current month and a future month never go, at any retention
  for (int r = 0; r <= 255; ++r) {
    assert(!SdLogCodec::shouldDeleteLog("climate_v2_202601.csv", now, static_cast<uint8_t>(r)));
    assert(!SdLogCodec::shouldDeleteLog("climate_v2_202602.csv", now, static_cast<uint8_t>(r)));
  }
  // Maximum 120 months: exactly 10 years stay
  assert(!SdLogCodec::shouldDeleteLog("climate_v2_201601.csv", now, 120));
  assert(SdLogCodec::shouldDeleteLog("climate_v2_201512.csv", now, 120));
  // Out-of-range retention deletes nothing
  assert(!SdLogCodec::retentionActive(0));
  assert(SdLogCodec::retentionActive(1) && SdLogCodec::retentionActive(120));
  assert(!SdLogCodec::retentionActive(121));
  assert(!SdLogCodec::shouldDeleteLog("climate_v2_200001.csv", now, 121));
  assert(!SdLogCodec::shouldDeleteLog("climate_v2_200001.csv", now, 255));
  // An invalid clock, a stopped oscillator and an implausible year delete nothing
  RtcTime bad = now;
  bad.valid = false;
  assert(!SdLogCodec::shouldDeleteLog("climate_v2_200001.csv", bad, 1));
  bad = now;
  bad.oscillator_stopped = true;
  assert(!SdLogCodec::shouldDeleteLog("climate_v2_200001.csv", bad, 1));
  assert(!SdLogCodec::shouldDeleteLog("climate_v2_200001.csv", rtcAt(2000, 6), 1));
  assert(!SdLogCodec::shouldDeleteLog("climate_v2_202001.csv", rtcAt(2024, 12), 1));
  assert(SdLogCodec::shouldDeleteLog("climate_v2_202001.csv", rtcAt(2025, 1), 1));
  // A wrong far-future clock still needs a trusted year: the 2099 case is the known limit
  assert(!SdLogCodec::shouldDeleteLog("climate_v2_202601.csv", rtcAt(2026, 1), 1));
}

void testLogRetentionNamePattern() {
  const RtcTime now = rtcAt(2026, 11);
  const char* keep[] = {
      nullptr, "", "config.ini", "climate_202001.csv", "climate_v2_202001.txt",
      "climate_v2_202001.csv.bak", "climate_v2_2020.csv", "climate_v2_20200a.csv",
      "CLIMATE_V2_202001.CSV", "/climate_v2_202001.csv", "../climate_v2_202001.csv",
      "climate_v2_202013.csv", "climate_v2_202000.csv", "climate_v2_199901.csv",
      "System Volume Information", "climate_v2_202001.csv ",
  };
  for (const char* name : keep) {
    assert(!SdLogCodec::shouldDeleteLog(name, now, 1));
  }
  assert(SdLogCodec::shouldDeleteLog("climate_v2_202001.csv", now, 1));
}

void testLogRetentionConfig() {
  DeviceConfig cfg;
  assert(cfg.log_retention_months == 0 && cfg.isDefault() && cfg.valid());
  auto parse = [&](const char* text) {
    return DeviceConfigParser::parse(text, strlen(text), cfg);
  };
  assert(parse("log_retention_months=12\n").keys_set == 1 && cfg.log_retention_months == 12);
  assert(parse("log_retention_months = 120").keys_set == 1 && cfg.log_retention_months == 120);
  assert(parse("log_retention_months=0\n").keys_set == 1 && cfg.log_retention_months == 0);
  // Out of range, negative, fractional and text values are rejected; the default stays
  for (const char* bad : {"log_retention_months=121\n", "log_retention_months=-1\n",
                          "log_retention_months=1.5\n", "log_retention_months=abc\n",
                          "log_retention_months=\n", "log_retention_months=256\n"}) {
    const ConfigParseResult r = parse(bad);
    assert(r.keys_set == 0 && r.rejected == 1 && cfg.log_retention_months == 0);
  }
  // A later bad line keeps the earlier valid value
  parse("log_retention_months=6\nlog_retention_months=999\n");
  assert(cfg.log_retention_months == 6);
  assert(!cfg.isDefault() && cfg.valid());
  cfg.log_retention_months = 121;
  assert(!cfg.valid());

  // The value survives the RTC round trip
  PersistentState s;
  s.config.log_retention_months = 36;
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  PersistentState r;
  assert(PowerManager::deserialize(buf, sizeof(buf), r) && r.config.log_retention_months == 36);
}

void testNtpSyncIntervalConfig() {
  // Default and range edges
  DeviceConfig cfg;
  assert(cfg.ntp_sync_interval_h == 24 && cfg.isDefault() && cfg.valid());
  auto parse = [&](const char* text) {
    return DeviceConfigParser::parse(text, strlen(text), cfg);
  };
  assert(parse("ntp_sync_interval_h=6\n").keys_set == 1 && cfg.ntp_sync_interval_h == 6);
  assert(parse("ntp_sync_interval_h = 168\r\n").keys_set == 1 && cfg.ntp_sync_interval_h == 168);
  assert(!cfg.isDefault() && cfg.valid());
  // Out of range and malformed values are rejected; the previous value stays
  for (const char* bad : {"ntp_sync_interval_h=5\n", "ntp_sync_interval_h=0\n", "ntp_sync_interval_h=169\n",
                          "ntp_sync_interval_h=256\n", "ntp_sync_interval_h=-6\n", "ntp_sync_interval_h=12.5\n",
                          "ntp_sync_interval_h=abc\n", "ntp_sync_interval_h=\n"}) {
    cfg = DeviceConfig{};
    const ConfigParseResult r = parse(bad);
    assert(r.keys_set == 0 && r.rejected == 1 && cfg.ntp_sync_interval_h == 24);
  }
  cfg = DeviceConfig{};
  parse("ntp_sync_interval_h=48\nntp_sync_interval_h=999\n");
  assert(cfg.ntp_sync_interval_h == 48);
  cfg.ntp_sync_interval_h = 5;
  assert(!cfg.valid());
  cfg.ntp_sync_interval_h = 169;
  assert(!cfg.valid());

  // Schedule decision with 6 h and 168 h
  constexpr uint32_t H = 3600000;
  assert(NtpSchedule::syncIntervalMs(6) == 6 * H && NtpSchedule::syncIntervalMs(168) == 168 * H);
  assert(NtpSchedule::syncIntervalMs(24) == 86400000);
  NtpSyncState s;
  NtpSchedule::record(s, 5000, true);
  assert(!NtpSchedule::due(s, 5000 + 6 * H - 1, 6) && NtpSchedule::due(s, 5000 + 6 * H, 6));
  assert(NtpSchedule::due(s, 5000 + 6 * H, 6) && !NtpSchedule::due(s, 5000 + 6 * H, 24));
  assert(!NtpSchedule::due(s, 5000 + 168 * H - 1, 168) && NtpSchedule::due(s, 5000 + 168 * H, 168));
  assert(NtpSchedule::decide(s, 5000 + 7 * H, false, true, true, false, 6) == NtpDecision::ATTEMPT);
  assert(NtpSchedule::decide(s, 5000 + 7 * H, false, true, true, false, 168) == NtpDecision::SKIP_NOT_DUE);

  // The retry backoff after a failure does not depend on the interval
  for (uint8_t interval : {uint8_t(6), uint8_t(24), uint8_t(168)}) {
    NtpSyncState f;
    NtpSchedule::record(f, 100000, false);
    assert(!NtpSchedule::due(f, 100000 + 899999, interval) && NtpSchedule::due(f, 100000 + 900000, interval));
    NtpSchedule::record(f, 2000000, false);
    assert(!NtpSchedule::due(f, 2000000 + 1799999, interval) && NtpSchedule::due(f, 2000000 + 1800000, interval));
  }
  assert(NtpSchedule::retryDelayMs(0) == 900000 && NtpSchedule::retryDelayMs(1) == 900000);
  assert(NtpSchedule::retryDelayMs(2) == 1800000 && NtpSchedule::retryDelayMs(16) == NtpSchedule::RETRY_MAX_MS);

  // RTC round trip and corrupt fields
  PersistentState st;
  assert(st.config.ntp_sync_interval_h == 24);
  st.config.ntp_sync_interval_h = 168;
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(st, buf, sizeof(buf)));
  PersistentState r;
  assert(PowerManager::deserialize(buf, sizeof(buf), r) && r.config.ntp_sync_interval_h == 168);
  st.config.ntp_sync_interval_h = 6;
  assert(PowerManager::serialize(st, buf, sizeof(buf)));
  assert(PowerManager::deserialize(buf, sizeof(buf), r) && r.config.ntp_sync_interval_h == 6);
  st.config.ntp_sync_interval_h = 5; // corrupt value never reaches the schedule
  assert(PowerManager::serialize(st, buf, sizeof(buf)));
  PersistentState bad;
  assert(!PowerManager::deserialize(buf, sizeof(buf), bad));
  st.config.ntp_sync_interval_h = 169;
  assert(PowerManager::serialize(st, buf, sizeof(buf)));
  assert(!PowerManager::deserialize(buf, sizeof(buf), bad));
  // The old state version gives a clean start
  st.config.ntp_sync_interval_h = 24;
  assert(PowerManager::serialize(st, buf, sizeof(buf)));
  buf[4] = PowerManager::STATE_VERSION - 1;
  const uint32_t crc = PowerManager::crc32(buf, sizeof(buf) - 4);
  for (int i = 0; i < 4; ++i) buf[sizeof(buf) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  assert(!PowerManager::deserialize(buf, sizeof(buf), bad));
  buf[4] = PowerManager::STATE_VERSION; // the same record with the current version is accepted
  const uint32_t crc2 = PowerManager::crc32(buf, sizeof(buf) - 4);
  for (int i = 0; i < 4; ++i) buf[sizeof(buf) - 4 + i] = static_cast<uint8_t>(crc2 >> (8 * i));
  assert(PowerManager::deserialize(buf, sizeof(buf), bad) && bad.config.ntp_sync_interval_h == 24);
}

void testWakeIntervalConfig() {
  // Default and range edges
  DeviceConfig cfg;
  assert(cfg.wake_interval_s == 60 && cfg.isDefault() && cfg.valid());
  assert(cfg.wake_interval_s == PowerConfig::DEFAULT_SLEEP_INTERVAL_S);
  auto parse = [&](const char* text) {
    return DeviceConfigParser::parse(text, strlen(text), cfg);
  };
  assert(parse("wake_interval_s=30\n").keys_set == 1 && cfg.wake_interval_s == 30);
  assert(parse("wake_interval_s = 900\r\n").keys_set == 1 && cfg.wake_interval_s == 900);
  assert(!cfg.isDefault() && cfg.valid());
  // Out of range and malformed values are rejected; the previous value stays
  for (const char* bad : {"wake_interval_s=29\n", "wake_interval_s=0\n", "wake_interval_s=901\n",
                          "wake_interval_s=65536\n", "wake_interval_s=-60\n", "wake_interval_s=60.5\n",
                          "wake_interval_s=abc\n", "wake_interval_s=\n"}) {
    cfg = DeviceConfig{};
    const ConfigParseResult r = parse(bad);
    assert(r.keys_set == 0 && r.rejected == 1 && cfg.wake_interval_s == 60);
  }
  cfg = DeviceConfig{};
  parse("wake_interval_s=120\nwake_interval_s=5\n");
  assert(cfg.wake_interval_s == 120);
  cfg.wake_interval_s = 29;
  assert(!cfg.valid());
  cfg.wake_interval_s = 901;
  assert(!cfg.valid());

  // Text round trip
  cfg = DeviceConfig{};
  cfg.wake_interval_s = 900;
  char text[DeviceConfigText::MAX_SIZE];
  const size_t n = DeviceConfigText::format(cfg, text, sizeof(text));
  assert(n > 0);
  DeviceConfig back;
  DeviceConfigParser::parse(text, n, back);
  assert(back.wake_interval_s == 900);

  // Power config: battery takes the configured value, USB power keeps the default
  BatteryReading battery;
  battery.valid = true;
  battery.usb_powered = false;
  BatteryReading usb = battery;
  usb.usb_powered = true;
  for (uint16_t v : {uint16_t(30), uint16_t(60), uint16_t(900)}) {
    DeviceConfig c;
    c.wake_interval_s = v;
    assert(PowerManager::powerConfig(c, battery).sleep_interval_s == v);
    assert(PowerManager::powerConfig(c, usb).sleep_interval_s == PowerConfig::DEFAULT_SLEEP_INTERVAL_S);
    assert(PowerManager::powerConfig(c, battery).scan_duration_s == PowerConfig::DEFAULT_SCAN_DURATION_S);
  }
  BatteryReading none; // no reading: battery rule
  DeviceConfig c900;
  c900.wake_interval_s = 900;
  assert(PowerManager::powerConfig(c900, none).sleep_interval_s == 900);

  // Wakeup timer maths at both edges and the default
  for (uint16_t v : {uint16_t(30), uint16_t(60), uint16_t(900)}) {
    DeviceConfig c;
    c.wake_interval_s = v;
    const PowerConfig pc = PowerManager::powerConfig(c, battery);
    const uint64_t full = static_cast<uint64_t>(v) * 1000000ULL;
    assert(PowerManager::sleepMicros(pc, 5000, 5000) == full);
    assert(PowerManager::sleepMicros(pc, 5000 + 4000, 5000) == full - 4000000ULL);
    assert(PowerManager::sleepMicros(pc, 5000 + v * 1000u, 5000) == PowerManager::MIN_SLEEP_US);
    assert(PowerManager::sleepMicros(pc, 5000 + v * 1000u - 1, 5000) == PowerManager::MIN_SLEEP_US);
    assert(PowerManager::planSleep(pc, 5000, 5000, false).timer_us == full);
    assert(PowerManager::continuousListenAllowed(pc, 5000 + 1000, 5000));
    assert(pc.sanitized().scan_duration_s == PowerConfig::DEFAULT_SCAN_DURATION_S);
  }

  // Hourly gate with 900 s: the sample comes on the fourth wake, not earlier. The interval does
  // not move the 3600 s gate of the history.
  {
    BatteryHistory h;
    const uint32_t start = 100000;
    battery.voltage_v = 3.9f;
    assert(h.record(start, battery) && h.has_sampled);
    for (uint32_t wake = 1; wake <= 3; ++wake) {
      assert(!h.record(start + wake * 900000u, battery));
    }
    assert(h.record(start + 4 * 900000u, battery) && h.size() == 2);
    ClimateHistory climate;
    assert(climate.sampleDue(start));
    SwitchBotRegistry reg;
    assert(climate.record(start, true, 40.0f, reg, 20.0f));
    for (uint32_t wake = 1; wake <= 3; ++wake) {
      assert(!climate.record(start + wake * 900000u, true, 41.0f, reg, 20.0f));
    }
    assert(climate.record(start + 4 * 900000u, true, 41.0f, reg, 20.0f));
  }

  // Runtime estimate with samples off the hour grid: the 900 s wake cycle plus 7 s of awake time
  // gives a sample every fourth wake, 3628 s apart. The fit uses the stamps, not the sample count.
  {
    DeviceConfig c;
    c.wake_interval_s = 900;
    const PowerConfig pc = PowerManager::powerConfig(c, battery);
    BatteryHistory h;
    BatteryReading now;
    now.valid = true;
    double last_v = 0.0;
    uint32_t wake_ms = 1000;
    for (int k = 0; h.size() < 8; ++k) {
      wake_ms = 1000u + static_cast<uint32_t>(k) * (pc.sleep_interval_s * 1000u + 7000u);
      const double hrs = (wake_ms - 1000u) / 3600000.0;
      now.voltage_v = static_cast<float>(4.00 - 0.016 * hrs);
      if (h.record(wake_ms, now)) {
        last_v = now.voltage_v;
      }
    }
    assert(h.timeAt(1) - h.timeAt(0) == 4u * (900000u + 7000u));
    const double expected_h = (last_v - BatteryMonitor::V_EMPTY) / 0.016;
    const BatteryRuntime rt = h.estimate(now);
    assert(rt.valid && rt.hours + 1 >= expected_h && rt.hours <= expected_h + 1);
    // The same slope on the hour grid gives the same fit per elapsed hour
    BatteryHistory g;
    for (int i = 0; i < 8; ++i) {
      assert(g.push(4.00f - 0.016f * static_cast<float>(i), 1000u + i * 3600000u));
    }
    double slope_off = 0.0, slope_grid = 0.0;
    assert(h.slope(slope_off) && g.slope(slope_grid));
    assert(slope_off < -0.0159 && slope_off > -0.0161 && slope_grid < -0.0159 && slope_grid > -0.0161);
    // Uneven jitter keeps the fit on the stamps, not on the sample count
    BatteryHistory j;
    const uint32_t off[8] = {0, 3600000, 7200000 + 600000, 10800000 + 900000, 14400000 + 300000,
                             18000000 + 900000, 21600000, 25200000 + 450000};
    for (int i = 0; i < 8; ++i) {
      const double hrs = off[i] / 3600000.0;
      assert(j.push(static_cast<float>(4.00 - 0.016 * hrs), 1000u + off[i]));
    }
    BatteryReading now3;
    now3.valid = true;
    now3.voltage_v = static_cast<float>(4.00 - 0.016 * (off[7] / 3600000.0));
    const BatteryRuntime rj = j.estimate(now3);
    const double expected_j = (4.00 - 0.016 * (off[7] / 3600000.0) - BatteryMonitor::V_EMPTY) / 0.016;
    assert(rj.valid && rj.hours + 1 >= expected_j && rj.hours <= expected_j + 1);
  }

  // RTC round trip, corrupt values and the version bump
  PersistentState st;
  assert(st.config.wake_interval_s == 60);
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  PersistentState r;
  for (uint16_t v : {uint16_t(30), uint16_t(60), uint16_t(257), uint16_t(900)}) {
    st.config.wake_interval_s = v;
    assert(PowerManager::serialize(st, buf, sizeof(buf)));
    assert(PowerManager::deserialize(buf, sizeof(buf), r) && r.config.wake_interval_s == v);
  }
  PersistentState bad;
  for (uint16_t v : {uint16_t(29), uint16_t(0), uint16_t(901)}) {
    st.config.wake_interval_s = v;
    assert(PowerManager::serialize(st, buf, sizeof(buf)));
    assert(!PowerManager::deserialize(buf, sizeof(buf), bad));
  }
  st.config.wake_interval_s = 600;
  assert(PowerManager::serialize(st, buf, sizeof(buf)));
  buf[4] = PowerManager::STATE_VERSION - 1;
  const uint32_t crc = PowerManager::crc32(buf, sizeof(buf) - 4);
  for (int i = 0; i < 4; ++i) buf[sizeof(buf) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  assert(!PowerManager::deserialize(buf, sizeof(buf), bad));
}

void testSdFreeMegabytes() {
  constexpr uint64_t MB = 1024ULL * 1024ULL;
  assert(SdLogCodec::freeMegabytes(8000 * MB, 579 * MB) == 7421);
  assert(SdLogCodec::freeMegabytes(8000 * MB, 8000 * MB) == 0);
  assert(SdLogCodec::freeMegabytes(8000 * MB, 8000 * MB - 1) == 0); // below 1 MB rounds down
  assert(SdLogCodec::freeMegabytes(0, 0) == -1);
  assert(SdLogCodec::freeMegabytes(100 * MB, 101 * MB) == -1);
  assert(SdLogCodec::freeMegabytes(1ULL << 50, 0) == static_cast<int32_t>(SdLogCodec::MAX_FREE_MB));
}

void testDiagnosticsSdFreeLine() {
  BatteryReading battery;
  battery.valid = true;
  battery.voltage_v = 3.9f;
  battery.percentage = 70;
  constexpr uint16_t SD_Y = 144;
  constexpr uint16_t SD_H = 14;
  St7305Framebuffer unmounted, normal, widest, zero;
  SystemInfo info;
  info.sd_free_mb = -1;
  unmounted.clear(true);
  DashboardView::renderDiagnostics(unmounted, battery, info, "12:00");
  info.sd_free_mb = 7421;
  normal.clear(true);
  DashboardView::renderDiagnostics(normal, battery, info, "12:00");
  info.sd_free_mb = static_cast<int32_t>(SdLogCodec::MAX_FREE_MB);
  widest.clear(true);
  DashboardView::renderDiagnostics(widest, battery, info, "12:00");
  info.sd_free_mb = 0;
  zero.clear(true);
  DashboardView::renderDiagnostics(zero, battery, info, "12:00");

  // Only the SD line differs between the states
  const St7305Framebuffer* others[] = {&normal, &widest, &zero};
  for (const St7305Framebuffer* fb : others) {
    size_t diff = 0;
    for (uint16_t y = 0; y < St7305Framebuffer::HEIGHT; ++y) {
      for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
        if (fb->getPixel(x, y) != unmounted.getPixel(x, y)) {
          ++diff;
          assert(y >= SD_Y && y < SD_Y + SD_H);
          assert(x < DashboardView::PERCENTILE_X); // the percentile text does not depend on the card
        }
      }
    }
    assert(diff > 0);
  }
  assert(memcmp(normal.data(), zero.data(), St7305Framebuffer::BYTE_COUNT) != 0);
  // "SD FREE: 9999999 MB" is 19 glyphs = 226 px: no ink right of x = 10 + 19 * 12 in the line,
  // and no ink in the 10 px margin on the left. The L10/L90 text starts at PERCENTILE_X.
  for (const St7305Framebuffer* fb : {&unmounted, &normal, &widest, &zero}) {
    for (uint16_t y = SD_Y; y < SD_Y + SD_H; ++y) {
      for (uint16_t x = 10 + 19 * 12; x < DashboardView::PERCENTILE_X; ++x) {
        assert(!fb->getPixel(x, y));
      }
      for (uint16_t x = 0; x < 10; ++x) {
        assert(!fb->getPixel(x, y));
      }
    }
  }
}

// ---- RTC drift measurement ----

static RtcTime driftCal(int64_t epoch) {
  RtcTime t;
  assert(NtpTime::epochToCalendar(epoch, t));
  return t;
}

static bool driftSync(RtcDriftState& st, int64_t utc, int32_t rtc_offset_s, bool ok = true) {
  return RtcDrift::record(st, ok, driftCal(utc + rtc_offset_s), driftCal(utc), utc);
}

void testRtcDriftFirstSyncHasNoRate() {
  constexpr int64_t E0 = 1767225600; // 2026-01-01 00:00:00 UTC
  RtcDriftState st;
  char buf[48];
  RtcDrift::describe(st, buf, sizeof(buf));
  assert(strcmp(buf, "RTC DRIFT: --") == 0);
  assert(!driftSync(st, E0, 5)); // the first sync only stores the time
  assert(st.has_sync && !st.has_drift);
  RtcDrift::describe(st, buf, sizeof(buf));
  assert(strcmp(buf, "RTC DRIFT: --") == 0);
}

void testRtcDriftPositiveNegativeZero() {
  constexpr int64_t E0 = 1767225600;
  char buf[48];
  RtcDriftState st;
  driftSync(st, E0, 0);
  assert(driftSync(st, E0 + 86400, 3)); // RTC runs ahead
  assert(st.drift_s == 3 && st.rate_ppm == 35);
  RtcDrift::describe(st, buf, sizeof(buf));
  assert(strcmp(buf, "RTC DRIFT: +3 S (+35 PPM)") == 0);
  assert(driftSync(st, E0 + 2 * 86400, -3)); // RTC runs behind
  assert(st.drift_s == -3 && st.rate_ppm == -35);
  RtcDrift::describe(st, buf, sizeof(buf));
  assert(strcmp(buf, "RTC DRIFT: -3 S (-35 PPM)") == 0);
  assert(driftSync(st, E0 + 3 * 86400, 0));
  assert(st.has_drift && st.drift_s == 0 && st.rate_ppm == 0);
  RtcDrift::describe(st, buf, sizeof(buf));
  assert(strcmp(buf, "RTC DRIFT: +0 S (+0 PPM)") == 0);
}

void testRtcDriftRateOver24And168Hours() {
  constexpr int64_t E0 = 1767225600;
  RtcDriftState st;
  driftSync(st, E0, 0);
  assert(driftSync(st, E0 + 24 * 3600, 1));
  assert(st.rate_ppm == 12); // 1 s / 86400 s = 11.57 ppm
  RtcDriftState w;
  driftSync(w, E0, 0);
  assert(driftSync(w, E0 + 168 * 3600, 3));
  assert(w.rate_ppm == 5); // 3 s / 604800 s = 4.96 ppm
  RtcDriftState n;
  driftSync(n, E0, 0);
  assert(driftSync(n, E0 + 168 * 3600, -10));
  assert(n.rate_ppm == -17); // -16.53 ppm
}

void testRtcDriftRejectsFailedSync() {
  constexpr int64_t E0 = 1767225600;
  RtcDriftState st;
  driftSync(st, E0, 0);
  driftSync(st, E0 + 86400, 2);
  const RtcDriftState before = st;
  assert(!driftSync(st, E0 + 2 * 86400, 7, false));
  assert(st.has_sync == before.has_sync && st.has_drift == before.has_drift);
  assert(st.last_sync_s == before.last_sync_s && st.drift_s == before.drift_s &&
         st.rate_ppm == before.rate_ppm);
  // A failed first sync stores nothing
  RtcDriftState fresh;
  assert(!driftSync(fresh, E0, 0, false));
  assert(!fresh.has_sync && !fresh.has_drift);
}

void testRtcDriftRejectsUnusableReadings() {
  constexpr int64_t E0 = 1767225600;
  RtcDriftState st;
  driftSync(st, E0, 0);
  // RTC could not be read: no drift, but the sync time moves on
  RtcTime bad;
  assert(!RtcDrift::record(st, true, bad, driftCal(E0 + 86400), E0 + 86400));
  assert(st.has_sync && !st.has_drift);
  // A clock that went backwards gives no rate
  assert(!driftSync(st, E0, 1));
  assert(!st.has_drift);
  // Implausible drift (RTC never set) is rejected
  driftSync(st, E0 + 86400, 0);
  assert(!driftSync(st, E0 + 2 * 86400, RtcDrift::MAX_DRIFT_S + 1));
  assert(!st.has_drift);
  driftSync(st, E0 + 3 * 86400, 0);
  assert(driftSync(st, E0 + 4 * 86400, RtcDrift::MAX_DRIFT_S));
  assert(st.drift_s == RtcDrift::MAX_DRIFT_S);
  // Out of range epoch
  assert(!RtcDrift::record(st, true, driftCal(E0), driftCal(E0), 100));
}

void testRtcDriftRejectsShortInterval() {
  constexpr int64_t E0 = 1767225600;
  RtcDriftState st;
  driftSync(st, E0, 0);
  assert(!driftSync(st, E0 + 60, 1)); // 60 s would show 16667 PPM
  assert(st.has_sync && !st.has_drift && st.last_sync_s == E0 + 60);
  assert(driftSync(st, E0 + 60 + RtcDrift::MIN_INTERVAL_S, 1));
  assert(st.rate_ppm == 278); // 1 s / 3600 s
}

void testRtcDriftReferenceAcrossDst() {
  const TimeZone tz{3600, true};
  constexpr int64_t WINTER = 1767225600; // 2026-01-01 00:00:00 UTC, CET
  constexpr int64_t SUMMER = 1783555200; // 2026-07-09 00:00:00 UTC, CEST
  assert(NtpTime::offsetSeconds(WINTER, tz) == 3600 && NtpTime::offsetSeconds(SUMMER, tz) == 7200);
  // The RTC kept the winter offset: the reference is one hour behind the summer local time
  RtcTime ref;
  assert(NtpTime::driftReference(SUMMER, WINTER, tz, ref));
  RtcTime summer_local;
  assert(NtpTime::utcToLocal(SUMMER, tz, summer_local));
  assert(RtcDrift::calendarSeconds(summer_local) - RtcDrift::calendarSeconds(ref) == 3600);
  // An RTC 2 s ahead shows a drift of 2 s, not 1 h 2 s
  RtcDriftState st;
  RtcDrift::record(st, true, driftCal(WINTER + 3600), driftCal(WINTER + 3600), WINTER);
  RtcTime rtc = driftCal(SUMMER + 3600 + 2);
  assert(RtcDrift::record(st, true, rtc, ref, SUMMER));
  assert(st.drift_s == 2);
  // Same offset at both syncs: reference equals the plain local time
  assert(NtpTime::driftReference(WINTER + 86400, WINTER, tz, ref));
  RtcTime local;
  assert(NtpTime::utcToLocal(WINTER + 86400, tz, local));
  assert(RtcDrift::calendarSeconds(ref) == RtcDrift::calendarSeconds(local));
}

void testRtcDriftSerializeSanitizes() {
  PersistentState s;
  s.rtc_drift.has_sync = true;
  s.rtc_drift.has_drift = true;
  s.rtc_drift.last_sync_s = 1767225600;
  s.rtc_drift.drift_s = RtcDrift::MAX_DRIFT_S + 1;
  s.rtc_drift.rate_ppm = 5;
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  PersistentState r;
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  assert(PowerManager::deserialize(buf, sizeof(buf), r)); // the state survives
  assert(r.rtc_drift.has_sync && !r.rtc_drift.has_drift && r.rtc_drift.drift_s == 0);
  s.rtc_drift.last_sync_s = 5; // sync time outside 2000..2099: no sync on record
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(!r.rtc_drift.has_sync && !r.rtc_drift.has_drift && r.rtc_drift.last_sync_s == 0);
}

void testRtcDriftPersistence() {
  constexpr int64_t E0 = 1767225600;
  PersistentState s;
  driftSync(s.rtc_drift, E0, 0);
  driftSync(s.rtc_drift, E0 + 86400, -4);
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  PersistentState r;
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(r.rtc_drift.has_sync && r.rtc_drift.has_drift);
  assert(r.rtc_drift.drift_s == -4 && r.rtc_drift.rate_ppm == -46);
  assert(r.rtc_drift.last_sync_s == s.rtc_drift.last_sync_s);
  // Default: nothing measured
  PersistentState d;
  assert(PowerManager::serialize(d, buf, sizeof(buf)));
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(!r.rtc_drift.has_sync && !r.rtc_drift.has_drift);
  // Corrupt records are rejected, even with a valid CRC
  const size_t drift_pos = PowerManager::SERIALIZED_SIZE - 4 - PowerManager::CONFIG_SIZE - 13;
  auto reseal = [&]() {
    const uint32_t crc = PowerManager::crc32(buf, sizeof(buf) - 4);
    for (int i = 0; i < 4; ++i) buf[sizeof(buf) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  };
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  buf[drift_pos] = 4; // reserved flag bit
  reseal();
  assert(!PowerManager::deserialize(buf, sizeof(buf), r));
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  buf[drift_pos] = 2; // drift without a sync
  reseal();
  assert(!PowerManager::deserialize(buf, sizeof(buf), r));
  assert(PowerManager::serialize(d, buf, sizeof(buf)));
  buf[drift_pos + 5] = 1; // value without a measurement
  reseal();
  assert(!PowerManager::deserialize(buf, sizeof(buf), r));
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  buf[drift_pos + 8] = 0x7F; // drift beyond the limit
  reseal();
  assert(!PowerManager::deserialize(buf, sizeof(buf), r));
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  for (int i = 1; i <= 4; ++i) buf[drift_pos + i] = 0; // sync time far below 2000
  reseal();
  assert(!PowerManager::deserialize(buf, sizeof(buf), r));
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  buf[drift_pos + 9] = 0x10; // positive rate for a negative drift
  buf[drift_pos + 10] = buf[drift_pos + 11] = buf[drift_pos + 12] = 0;
  reseal();
  assert(!PowerManager::deserialize(buf, sizeof(buf), r));
}

void testDiagnosticsRtcDriftLine() {
  BatteryReading battery;
  battery.valid = true;
  battery.voltage_v = 3.9f;
  battery.percentage = 70;
  constexpr uint16_t Y = 184;
  constexpr uint16_t H = 14;
  St7305Framebuffer none, normal, widest;
  SystemInfo info;
  none.clear(true);
  DashboardView::renderDiagnostics(none, battery, info, "12:00");
  info.rtc_drift.has_sync = true;
  info.rtc_drift.has_drift = true;
  info.rtc_drift.drift_s = 3;
  info.rtc_drift.rate_ppm = 35;
  normal.clear(true);
  DashboardView::renderDiagnostics(normal, battery, info, "12:00");
  info.rtc_drift.drift_s = -RtcDrift::MAX_DRIFT_S;
  info.rtc_drift.rate_ppm = -RtcDrift::MAX_PPM;
  widest.clear(true);
  DashboardView::renderDiagnostics(widest, battery, info, "12:00");
  for (const St7305Framebuffer* fb : {&normal, &widest}) {
    size_t diff = 0;
    for (uint16_t y = 0; y < St7305Framebuffer::HEIGHT; ++y) {
      for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
        if (fb->getPixel(x, y) != none.getPixel(x, y)) {
          ++diff;
          assert(y >= Y && y < Y + H);
        }
      }
    }
    assert(diff > 0);
  }
  // "RTC DRIFT: -86400 S (-99999 PPM)" is 32 glyphs = 384 px: it ends inside the screen
  char buf[48];
  RtcDrift::describe(info.rtc_drift, buf, sizeof(buf));
  assert(strlen(buf) == 32);
  for (const St7305Framebuffer* fb : {&none, &normal, &widest}) {
    for (uint16_t y = Y; y < Y + H; ++y) {
      for (uint16_t x = 0; x < 10; ++x) assert(!fb->getPixel(x, y));
      for (uint16_t x = 10 + 32 * 12; x < St7305Framebuffer::WIDTH; ++x) assert(!fb->getPixel(x, y));
    }
  }
}

// ---- Battery charge cycle counter ----

// Portable stand-in for NVS: counts the writes and can fail them.
struct FakeCycleNvs {
  uint32_t value = 0;
  bool readable = true;
  bool fail_writes = false;
  int writes = 0;
};

// One wake cycle through CycleTracker::step, the path the sketch runs.
struct FakeCycleStore {
  FakeCycleNvs& nvs;
  bool load(uint32_t& pct) const {
    pct = nvs.value;
    return nvs.readable;
  }
  bool save(uint32_t pct) {
    if (nvs.fail_writes) {
      return false;
    }
    nvs.value = pct;
    ++nvs.writes;
    return true;
  }
};

void cycleWake(CycleTracker& t, FakeCycleNvs& nvs, int8_t pct, bool usb) {
  BatteryReading r;
  r.valid = pct >= 0;
  r.percentage = pct;
  r.voltage_v = r.valid ? 3.9f : 0.0f;
  r.usb_powered = usb;
  FakeCycleStore store{nvs};
  t.step(r, store);
}

void testCycleTrackerPartialDischarges() {
  CycleTracker t;
  FakeCycleNvs nvs;
  // 100 -> 60 -> 20 on battery: 80 points, no NVS write yet
  cycleWake(t, nvs, 100, false);
  cycleWake(t, nvs, 60, false);
  cycleWake(t, nvs, 20, false);
  assert(t.totalPct() == 80 && t.cycleTenths() == 8 && nvs.writes == 0);
  // Charge event: one write with the 80 points
  cycleWake(t, nvs, 90, true);
  assert(nvs.writes == 1 && nvs.value == 80 && t.pendingPct() == 0);
  // A second partial discharge crosses 100 points (80 + 25): milestone write, 1.05 cycles
  cycleWake(t, nvs, 80, false);
  assert(nvs.writes == 1);
  cycleWake(t, nvs, 55, false);
  assert(nvs.writes == 2 && nvs.value == 105 && t.cycleTenths() == 10);
}

void testCycleTrackerMilestoneWrite() {
  CycleTracker t;
  FakeCycleNvs nvs;
  cycleWake(t, nvs, 100, false);
  cycleWake(t, nvs, 70, false);
  cycleWake(t, nvs, 40, false);
  cycleWake(t, nvs, 10, false);
  assert(nvs.writes == 0 && t.totalPct() == 90);
  cycleWake(t, nvs, 0, false); // 100 points
  assert(nvs.writes == 1 && nvs.value == 100 && t.cycleTenths() == 10);
  char line[32];
  CycleTracker::describe(t.cycleTenths(), line, sizeof(line));
  assert(strcmp(line, "CYCLES: 1.0") == 0);
}

void testCycleTrackerChargeDetection() {
  CycleTracker t;
  FakeCycleNvs nvs;
  cycleWake(t, nvs, 80, false);
  cycleWake(t, nvs, 70, false);
  // Many USB cycles in a row: one write only
  for (int i = 0; i < 20; ++i) {
    cycleWake(t, nvs, 75 + i / 4, true);
  }
  assert(nvs.writes == 1 && nvs.value == 10);
  // After the charge the baseline restarts: 95 -> 90 adds 5, the charge itself adds nothing
  cycleWake(t, nvs, 95, false);
  cycleWake(t, nvs, 90, false);
  assert(t.pendingPct() == 5 && nvs.writes == 1);
  // USB power without a discharge since the last write: no write
  cycleWake(t, nvs, 100, true);
  assert(nvs.writes == 2);
  cycleWake(t, nvs, 100, false);
  cycleWake(t, nvs, 100, true);
  assert(nvs.writes == 2);
}

void testCycleTrackerUsbAndInvalidReadings() {
  CycleTracker t;
  FakeCycleNvs nvs;
  cycleWake(t, nvs, 90, false);
  cycleWake(t, nvs, -1, false); // no battery: nothing changes
  cycleWake(t, nvs, -1, true);
  assert(t.pendingPct() == 0 && nvs.writes == 0);
  cycleWake(t, nvs, 80, false);
  assert(t.pendingPct() == 10); // the invalid readings kept the baseline
  BatteryReading bad;
  bad.valid = true;
  bad.percentage = 101;
  assert(!t.update(bad));
  bad.percentage = -1;
  assert(!t.update(bad));
  // A jump of more than 40 points (cell swap) only moves the baseline
  cycleWake(t, nvs, 30, false);
  assert(t.pendingPct() == 10);
  // A rise of 3 points is ADC noise: the baseline stays, the return adds nothing twice
  cycleWake(t, nvs, 33, false);
  cycleWake(t, nvs, 30, false);
  assert(t.pendingPct() == 10);
  // A larger rise (charge) writes the 10 points and moves the baseline up
  cycleWake(t, nvs, 60, false);
  assert(nvs.writes == 1 && nvs.value == 10 && t.pendingPct() == 0);
  cycleWake(t, nvs, 55, false);
  assert(t.pendingPct() == 5);
}

void testCycleTrackerNvsFailures() {
  CycleTracker t;
  FakeCycleNvs nvs;
  nvs.fail_writes = true;
  cycleWake(t, nvs, 100, false);
  cycleWake(t, nvs, 70, false);
  cycleWake(t, nvs, 40, false);
  cycleWake(t, nvs, 50, true); // write fails, the points stay
  assert(nvs.writes == 0 && t.pendingPct() == 60);
  nvs.fail_writes = false;
  cycleWake(t, nvs, 52, true); // retry on the next cycle
  assert(nvs.writes == 1 && nvs.value == 60 && t.pendingPct() == 0);
  cycleWake(t, nvs, 52, true);
  assert(nvs.writes == 1);
  // Unreadable NVS: unknown total shows "--" and nothing is written
  CycleTracker u;
  FakeCycleNvs bad;
  bad.readable = false;
  cycleWake(u, bad, 90, false);
  cycleWake(u, bad, 10, false);
  cycleWake(u, bad, 50, true);
  assert(bad.writes == 0 && u.cycleTenths() < 0);
  char line[32];
  CycleTracker::describe(u.cycleTenths(), line, sizeof(line));
  assert(strcmp(line, "CYCLES: --") == 0);
}

void testCycleTrackerWriteCountPerDischarge() {
  CycleTracker t;
  FakeCycleNvs nvs;
  // 10 full discharge/charge rounds in 1 % steps: at most 2 writes per round (milestone + charge)
  cycleWake(t, nvs, 100, false);
  for (int round = 0; round < 10; ++round) {
    for (int p = 99; p >= 0; --p) {
      cycleWake(t, nvs, static_cast<int8_t>(p), false);
    }
    for (int i = 0; i < 12; ++i) {
      cycleWake(t, nvs, static_cast<int8_t>(i * 9), true);
    }
    cycleWake(t, nvs, 100, false);
  }
  assert(nvs.writes <= 20);
  assert(nvs.value >= 990 && nvs.value <= 1000);
}

void testCycleTrackerChargerWithoutHost() {
  CycleTracker t;
  FakeCycleNvs nvs;
  cycleWake(t, nvs, 80, false);
  cycleWake(t, nvs, 60, false);
  // The charger shows no USB host: a rise above the 3 point noise limit is the charge event
  cycleWake(t, nvs, 70, false);
  assert(nvs.writes == 1 && nvs.value == 20 && t.pendingPct() == 0);
  cycleWake(t, nvs, 80, false);
  assert(nvs.writes == 1);
  // A rise of 3 points is noise and writes nothing
  cycleWake(t, nvs, 70, false);
  cycleWake(t, nvs, 73, false);
  assert(nvs.writes == 1);
}

void testCycleTrackerChargeEventWithUnreadableNvs() {
  CycleTracker t;
  FakeCycleNvs nvs;
  cycleWake(t, nvs, 90, false);
  cycleWake(t, nvs, 60, false);
  nvs.readable = false;
  cycleWake(t, nvs, 70, true); // charge event, NVS unreadable: the write is owed
  assert(nvs.writes == 0 && t.pendingPct() == 30);
  nvs.readable = true;
  cycleWake(t, nvs, 75, true); // still on USB power: the owed write happens now
  assert(nvs.writes == 1 && nvs.value == 30 && t.pendingPct() == 0);
  cycleWake(t, nvs, 80, true);
  assert(nvs.writes == 1);
}

void testCycleTrackerRtcRoundTrip() {
  CycleTracker a;
  FakeCycleNvs nvs;
  cycleWake(a, nvs, 90, false);
  cycleWake(a, nvs, 70, false);
  CycleTracker b;
  assert(b.fromRtc(a.toRtc()));
  assert(b.pendingPct() == 20);
  cycleWake(b, nvs, 60, false);
  assert(b.pendingPct() == 30);
  // Blank, damaged or invalid memory is rejected and leaves the tracker unchanged
  CycleTracker c;
  CycleTracker::RtcState blank;
  assert(!c.fromRtc(blank));
  CycleTracker::RtcState broken = a.toRtc();
  broken.pending_pct ^= 1;
  assert(!c.fromRtc(broken));
  broken = a.toRtc();
  broken.last_pct = 120;
  broken.check = 0;
  assert(!c.fromRtc(broken));
  assert(c.pendingPct() == 0);
}

void testCycleDescribeFormat() {
  char line[32];
  CycleTracker::describe(0, line, sizeof(line));
  assert(strcmp(line, "CYCLES: 0.0") == 0);
  CycleTracker::describe(123, line, sizeof(line));
  assert(strcmp(line, "CYCLES: 12.3") == 0);
  CycleTracker::describe(99999, line, sizeof(line));
  assert(strcmp(line, "CYCLES: 9999.9") == 0 && strlen(line) == 14);
  // The total clamps at 9999.9 cycles
  CycleTracker t;
  t.setStored(5000000, true);
  assert(t.cycleTenths() == 99999);
}

void testDiagnosticsCycleLineBounds() {
  BatteryReading battery;
  battery.valid = true;
  battery.voltage_v = 3.9f;
  battery.percentage = 70;
  const int32_t values[] = {-1, 0, 123, 99999};
  St7305Framebuffer fbs[4];
  for (size_t i = 0; i < 4; ++i) {
    SystemInfo info;
    info.cycle_tenths = values[i];
    DashboardView::renderDiagnostics(fbs[i], battery, info, "12:00");
  }
  const uint16_t Y0 = 64;
  const uint16_t Y1 = 64 + 14;
  bool ink = false;
  for (size_t i = 1; i < 4; ++i) {
    for (uint16_t y = 0; y < St7305Framebuffer::HEIGHT; ++y) {
      for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
        if (fbs[i].getPixel(x, y) != fbs[0].getPixel(x, y)) {
          assert(y >= Y0 && y < Y1); // only the cycle line changes
        }
      }
    }
  }
  // "CYCLES: 9999.9" = 14 glyphs = 168 px: no ink right of x = 10 + 14 * 12, none in the margin
  for (size_t i = 0; i < 4; ++i) {
    for (uint16_t y = Y0; y < Y1; ++y) {
      for (uint16_t x = 10 + 14 * 12; x < St7305Framebuffer::WIDTH; ++x) {
        assert(!fbs[i].getPixel(x, y));
      }
      for (uint16_t x = 0; x < 10; ++x) {
        assert(!fbs[i].getPixel(x, y));
      }
      for (uint16_t x = 10; x < 10 + 14 * 12; ++x) {
        ink = ink || fbs[3].getPixel(x, y);
      }
    }
  }
  assert(ink);
  assert(memcmp(fbs[1].data(), fbs[3].data(), St7305Framebuffer::BYTE_COUNT) != 0);
}

// ---- Speaker to microphone loopback ----

std::vector<int16_t> sineBuffer(float freq_hz, float amplitude, size_t frames) {
  std::vector<int16_t> v(frames);
  for (size_t i = 0; i < frames; ++i) {
    v[i] = static_cast<int16_t>(lroundf(
        amplitude * sinf(6.28318530718f * freq_hz * static_cast<float>(i) / 16000.0f)));
  }
  return v;
}

LoopbackResult detect(const std::vector<int16_t>& mono) {
  LoopbackDetector d;
  std::vector<int16_t> stereo;
  for (int16_t x : mono) {
    stereo.push_back(x);
    stereo.push_back(x);
  }
  d.addInterleaved(stereo.data(), stereo.size());
  return d.result();
}

void testGoertzelDetectsOneKhzSine() {
  const LoopbackResult r = detect(sineBuffer(1000.0f, 16384.0f, 6400)); // amplitude 0.5 = -6 dBFS
  assert(r.status == LoopbackStatus::PASS);
  assert(r.passed());
  assert(r.level_db >= -7 && r.level_db <= -5);
}

void testGoertzelRejectsOtherFrequencies() {
  assert(detect(sineBuffer(2000.0f, 16384.0f, 6400)).status == LoopbackStatus::FAIL_TONE);
  assert(detect(sineBuffer(500.0f, 16384.0f, 6400)).status == LoopbackStatus::FAIL_TONE);
  assert(detect(sineBuffer(1100.0f, 16384.0f, 6400)).status == LoopbackStatus::FAIL_TONE);
}

void testGoertzelRejectsSilenceAndNoise() {
  const LoopbackResult silence = detect(std::vector<int16_t>(6400, 0));
  assert(silence.status == LoopbackStatus::FAIL_TONE);
  assert(silence.level_db == -120);

  uint32_t seed = 12345;
  std::vector<int16_t> noise(6400);
  for (int16_t& x : noise) {
    seed = seed * 1664525u + 1013904223u;
    x = static_cast<int16_t>((static_cast<int32_t>(seed >> 16) - 32768) / 2);
  }
  assert(detect(noise).status == LoopbackStatus::FAIL_TONE);

  // A loud tone in strong noise: tone share below the limit.
  std::vector<int16_t> mixed = sineBuffer(1000.0f, 2000.0f, 6400);
  for (size_t i = 0; i < mixed.size(); ++i) {
    seed = seed * 1664525u + 1013904223u;
    mixed[i] = static_cast<int16_t>(mixed[i] + (static_cast<int32_t>(seed >> 16) - 32768) / 2);
  }
  assert(detect(mixed).status == LoopbackStatus::FAIL_TONE);
}

void testLoopbackThresholdAndDbReporting() {
  // -40 dBFS tone passes, -70 dBFS tone is below the -60 dB limit.
  const LoopbackResult loud = detect(sineBuffer(1000.0f, 327.68f, 6400));
  assert(loud.status == LoopbackStatus::PASS);
  assert(loud.level_db >= -41 && loud.level_db <= -39);
  const LoopbackResult weak = detect(sineBuffer(1000.0f, 10.4f, 6400));
  assert(weak.status == LoopbackStatus::FAIL_TONE);
  assert(weak.level_db >= -71 && weak.level_db <= -69);

  ToneMeasurement m;
  m.valid = true;
  m.level_db = LoopbackDetector::MIN_LEVEL_DB;
  m.tone_ratio = LoopbackDetector::MIN_TONE_RATIO;
  assert(LoopbackDetector::passes(m));
  m.level_db = LoopbackDetector::MIN_LEVEL_DB - 0.1f;
  assert(!LoopbackDetector::passes(m));
  m.level_db = -10.0f;
  m.tone_ratio = LoopbackDetector::MIN_TONE_RATIO - 0.01f;
  assert(!LoopbackDetector::passes(m));
  assert(!LoopbackDetector::passes(ToneMeasurement{}));

  // The stronger channel decides; an empty detector is a hardware failure.
  LoopbackDetector d;
  std::vector<int16_t> tone = sineBuffer(1000.0f, 16384.0f, 6400);
  std::vector<int16_t> stereo;
  for (int16_t x : tone) {
    stereo.push_back(0);
    stereo.push_back(x);
  }
  d.addInterleaved(stereo.data(), stereo.size());
  assert(d.result().passed());
  assert(LoopbackDetector{}.result().status == LoopbackStatus::FAIL_HARDWARE);

  char text[40];
  LoopbackResult r;
  r.status = LoopbackStatus::PASS;
  r.level_db = -24;
  r.describe(text, sizeof(text));
  assert(strcmp(text, "AUDIO LOOP: PASS -24 DB") == 0);
  r.status = LoopbackStatus::FAIL_TONE;
  r.describe(text, sizeof(text));
  assert(strcmp(text, "AUDIO LOOP: FAIL") == 0);
  r.status = LoopbackStatus::FAIL_HARDWARE;
  r.describe(text, sizeof(text));
  assert(strcmp(text, "AUDIO LOOP: FAIL") == 0);
}

// Hardware layer double. `echo` plays the written samples back into the read path.
struct FakeLoopbackHal {
  std::vector<const char*> calls;
  bool fail_bus = false, fail_codec = false, fail_adc = false, fail_write = false;
  size_t short_read_after = SIZE_MAX; // reads beyond this many calls return 0
  float echo_gain = 0.5f;
  size_t garbage_frames = 0; // the first frames of the echo hold loud noise (latency, settling)
  int16_t tx_peak = 0;
  bool last_write_silent = false;
  bool amplifier_on = false;
  bool amplifier_was_on_while_writing = true;
  size_t reads = 0;
  size_t read_frames = 0;
  std::vector<int16_t> last_tx;

  bool beginBus() { calls.push_back("begin"); return !fail_bus; }
  bool configureCodec() { calls.push_back("codec"); return !fail_codec; }
  bool configureAdc() { calls.push_back("adc"); return !fail_adc; }
  void setAmplifier(bool on) { calls.push_back(on ? "amp_on" : "amp_off"); amplifier_on = on; }
  bool write(const int16_t* s, size_t n) {
    if (!amplifier_on) amplifier_was_on_while_writing = false;
    last_tx.assign(s, s + n);
    last_write_silent = true;
    for (size_t i = 0; i < n; ++i) {
      if (s[i] != 0) last_write_silent = false;
      tx_peak = std::max<int16_t>(tx_peak, static_cast<int16_t>(abs(s[i])));
    }
    return !fail_write;
  }
  size_t read(int16_t* s, size_t n) {
    if (reads++ >= short_read_after) return 0;
    for (size_t i = 0; i < n; ++i) {
      s[i] = static_cast<int16_t>(static_cast<float>(last_tx[i]) * echo_gain);
      if (read_frames + i / 2 < garbage_frames) s[i] = (i / 2) % 2 == 0 ? 30000 : -30000;
    }
    read_frames += n / 2;
    return n;
  }
  void drain() { calls.push_back("drain"); }
  void shutdownCodec() { calls.push_back("codec_off"); }
  void shutdownAdc() { calls.push_back("adc_off"); }
  void endBus() { calls.push_back("end"); }

  // The shutdown tail must be amp_off, codec_off, adc_off, end and must be the last four calls.
  bool shutdownOrderOk() const {
    const size_t n = calls.size();
    return n >= 4 && strcmp(calls[n - 4], "amp_off") == 0 && strcmp(calls[n - 3], "codec_off") == 0 &&
           strcmp(calls[n - 2], "adc_off") == 0 && strcmp(calls[n - 1], "end") == 0;
  }
};

void testLoopbackPassPowersDownInOrder() {
  FakeLoopbackHal hal;
  const LoopbackResult r = AudioLoopback::run(hal, 30, false, false);
  assert(r.passed());
  assert(hal.shutdownOrderOk());
  assert(hal.amplifier_was_on_while_writing);
  assert(!hal.amplifier_on);
  // Order of the whole run: bring-up, amplifier on, drain, shutdown.
  assert(strcmp(hal.calls[0], "begin") == 0 && strcmp(hal.calls[1], "codec") == 0 &&
         strcmp(hal.calls[2], "adc") == 0 && strcmp(hal.calls[3], "amp_on") == 0 &&
         strcmp(hal.calls[4], "drain") == 0);
  // 500 ms at 16 kHz = 8000 frames = 16000 interleaved samples were transferred.
  assert(hal.reads == (8000 + AudioLoopback::CHUNK_FRAMES - 1) / AudioLoopback::CHUNK_FRAMES);
  assert(LoopbackTone::DURATION_MS == 500);
}

void testLoopbackFailurePathsPowerDown() {
  {
    FakeLoopbackHal hal;
    hal.fail_bus = true;
    assert(AudioLoopback::run(hal, 30, false, false).status == LoopbackStatus::FAIL_HARDWARE);
    assert(hal.shutdownOrderOk());
    assert(!hal.amplifier_on);
  }
  {
    FakeLoopbackHal hal;
    hal.fail_codec = true;
    assert(AudioLoopback::run(hal, 30, false, false).status == LoopbackStatus::FAIL_HARDWARE);
    assert(hal.shutdownOrderOk());
    for (const char* c : hal.calls) assert(strcmp(c, "amp_on") != 0); // never on before the codec
  }
  {
    FakeLoopbackHal hal;
    hal.fail_adc = true;
    assert(AudioLoopback::run(hal, 30, false, false).status == LoopbackStatus::FAIL_HARDWARE);
    assert(hal.shutdownOrderOk());
    for (const char* c : hal.calls) assert(strcmp(c, "amp_on") != 0);
  }
  {
    FakeLoopbackHal hal;
    hal.fail_write = true;
    assert(AudioLoopback::run(hal, 30, false, false).status == LoopbackStatus::FAIL_HARDWARE);
    assert(hal.shutdownOrderOk());
    assert(!hal.amplifier_on);
  }
  {
    FakeLoopbackHal hal;
    hal.short_read_after = 5;
    assert(AudioLoopback::run(hal, 30, false, false).status == LoopbackStatus::FAIL_HARDWARE);
    assert(hal.shutdownOrderOk());
    assert(!hal.amplifier_on);
  }
  {
    // A dead path (no echo) is a tone failure, and the hardware is still off.
    FakeLoopbackHal hal;
    hal.echo_gain = 0.0f;
    assert(AudioLoopback::run(hal, 30, false, false).status == LoopbackStatus::FAIL_TONE);
    assert(hal.shutdownOrderOk());
  }
}

void testLoopbackSkipsWhenMutedQuietOrZeroVolume() {
  {
    FakeLoopbackHal hal;
    const LoopbackResult r = AudioLoopback::run(hal, 30, true, false);
    assert(r.status == LoopbackStatus::SKIPPED_MUTED && r.skipped() && !r.passed());
    assert(hal.calls.empty()); // the hardware stays untouched
  }
  {
    FakeLoopbackHal hal;
    assert(AudioLoopback::run(hal, 30, false, true).status == LoopbackStatus::SKIPPED_QUIET);
    assert(hal.calls.empty());
  }
  {
    FakeLoopbackHal hal;
    assert(AudioLoopback::run(hal, 0, false, false).status == LoopbackStatus::SKIPPED_VOLUME);
    assert(hal.calls.empty());
  }
  {
    FakeLoopbackHal hal; // mute wins over quiet hours
    assert(AudioLoopback::run(hal, 30, true, true).status == LoopbackStatus::SKIPPED_MUTED);
  }
  assert(AudioLoopback::skipReason(false, false, 30) == LoopbackStatus::NOT_RUN);
  char text[40];
  LoopbackResult r;
  r.status = LoopbackStatus::SKIPPED_MUTED;
  r.describe(text, sizeof(text));
  assert(strcmp(text, "AUDIO LOOP: SKIP MUTED") == 0);
  r.status = LoopbackStatus::SKIPPED_QUIET;
  r.describe(text, sizeof(text));
  assert(strcmp(text, "AUDIO LOOP: SKIP QUIET") == 0);
}

void testLoopbackSettleDcAndTrailingSilence() {
  // Loud garbage in the first 100 ms of the echo stays out of the analysis.
  {
    FakeLoopbackHal hal;
    hal.garbage_frames = AudioLoopback::SETTLE_FRAMES;
    assert(AudioLoopback::run(hal, 30, false, false).passed());
  }
  // Garbage after the settling time fails the test.
  {
    FakeLoopbackHal hal;
    hal.garbage_frames = AudioLoopback::SETTLE_FRAMES + 2000;
    assert(AudioLoopback::run(hal, 30, false, false).status == LoopbackStatus::FAIL_TONE);
  }
  // A microphone DC offset does not lower the tone share.
  std::vector<int16_t> tone = sineBuffer(1000.0f, 3000.0f, 6400);
  for (int16_t& x : tone) x = static_cast<int16_t>(x + 12000);
  assert(detect(tone).passed());
  // The last write before the shutdown is silence.
  FakeLoopbackHal hal;
  AudioLoopback::run(hal, 30, false, false);
  assert(hal.last_write_silent);
}

void testLoopbackToneUsesChimeVolume() {
  FakeLoopbackHal hal;
  AudioLoopback::run(hal, 100, false, false);
  const int16_t peak = hal.tx_peak;
  assert(peak > 0);
  FakeLoopbackHal low;
  AudioLoopback::run(low, 10, false, false);
  assert(low.tx_peak > 0 && low.tx_peak < peak);
}

void testBootButtonLongPressInDiagnostics() {
  // Outside the diagnostics view a press toggles the mute flag at once.
  {
    BootButtonController b;
    assert(b.update(false, 0, false) == BootEvent::NONE);
    assert(b.update(true, 100, false) == BootEvent::NONE);
    assert(b.update(true, 140, false) == BootEvent::TOGGLE_MUTE);
    assert(!b.pressPending());
  }
  // In the diagnostics view a short press toggles on release.
  {
    BootButtonController b;
    b.update(false, 0, true);
    b.update(true, 100, true);
    assert(b.update(true, 140, true) == BootEvent::NONE);
    assert(b.pressPending());
    b.update(false, 400, true);
    assert(b.update(false, 440, true) == BootEvent::TOGGLE_MUTE);
  }
  // A long press starts the loopback and does not toggle the mute flag on release.
  {
    BootButtonController b;
    b.update(false, 0, true);
    b.update(true, 100, true);
    b.update(true, 140, true);
    // The press edge is debounced at 140 ms, so the limit is 2140 ms.
    assert(b.update(true, 2139, true) == BootEvent::NONE);
    assert(b.update(true, 2140, true) == BootEvent::LOOPBACK);
    assert(b.update(false, 2300, true) == BootEvent::NONE);
    assert(b.update(false, 2400, true) == BootEvent::NONE);
  }
  // A button down at the first call (the wake press) raises no event.
  {
    BootButtonController b;
    assert(b.update(true, 0, true) == BootEvent::NONE);
    assert(b.update(true, 3000, true) == BootEvent::NONE);
    assert(!b.pressPending());
  }
}

void testDiagnosticsLoopbackLine() {
  BatteryReading battery;
  battery.valid = true;
  battery.voltage_v = 3.9f;
  battery.percentage = 70;
  SystemInfo info;
  St7305Framebuffer none, pass, fail;
  none.clear(true);
  pass.clear(true);
  fail.clear(true);
  DashboardView::renderDiagnostics(none, battery, info, "12:00");
  info.loopback.status = LoopbackStatus::PASS;
  info.loopback.level_db = -120;
  DashboardView::renderDiagnostics(pass, battery, info, "12:00");
  info.loopback.status = LoopbackStatus::FAIL_TONE;
  DashboardView::renderDiagnostics(fail, battery, info, "12:00");

  size_t pass_diff = 0, fail_diff = 0, fail_vs_pass = 0;
  const uint16_t Y0 = DashboardView::LOOPBACK_Y, Y1 = Y0 + 14;
  for (uint16_t y = 0; y < St7305Framebuffer::HEIGHT; ++y) {
    for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
      const bool inside = y >= Y0 && y < Y1;
      if (none.getPixel(x, y) != pass.getPixel(x, y)) {
        ++pass_diff;
        assert(inside);
      }
      if (none.getPixel(x, y) != fail.getPixel(x, y)) {
        ++fail_diff;
        assert(inside);
      }
      if (pass.getPixel(x, y) != fail.getPixel(x, y)) ++fail_vs_pass;
      // "AUDIO LOOP: PASS -120 DB" = 24 glyphs = 288 px; the right margin stays blank.
      if (inside && x >= 10 + 24 * 12) assert(!pass.getPixel(x, y));
    }
  }
  assert(pass_diff > 0 && fail_diff > 0 && fail_vs_pass > 0);
  // The line sits clear of the calibration line above and the config line below.
  assert(Y0 >= 244 + 14 && Y1 <= 276);
}

void testPreviewDiagnosticsShowsLoopback() {
  // The preview scene shows the PASS line: it equals a render with the same PASS result and
  // differs from a render without a result.
  St7305Framebuffer scene, expected, not_run;
  preview::renderScene(scene, DisplayMode::SYSTEM_DIAGNOSTICS, false);
  expected.clear(true);
  not_run.clear(true);
  BatteryReading battery;
  battery.valid = true;
  battery.voltage_v = 3.92f;
  battery.percentage = 72;
  battery.usb_powered = false;
  SystemInfo info;
  info.loopback.status = LoopbackStatus::PASS;
  info.loopback.level_db = -24;
  DashboardView::renderDiagnostics(expected, battery, info, "14:32");
  info.loopback = LoopbackResult{};
  DashboardView::renderDiagnostics(not_run, battery, info, "14:32");
  size_t scene_vs_pass = 0, scene_vs_none = 0;
  for (uint16_t y = DashboardView::LOOPBACK_Y; y < DashboardView::LOOPBACK_Y + 14; ++y) {
    for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
      if (scene.getPixel(x, y) != expected.getPixel(x, y)) ++scene_vs_pass;
      if (scene.getPixel(x, y) != not_run.getPixel(x, y)) ++scene_vs_none;
    }
  }
  assert(scene_vs_pass == 0 && scene_vs_none > 0);
}

// Stereo sine source for the spectrum tests: both channels hold the same tone.
struct SineMicHal {
  float freq_hz = 1000.0f;
  float amplitude = 16000.0f;
  bool bus_ok = true;
  bool adc_ok = true;
  size_t reads_before_fail = static_cast<size_t>(-1);
  size_t reads = 0;
  uint64_t frame = 0;
  int shutdown_calls = 0, end_calls = 0;

  bool beginBus() { return bus_ok; }
  bool configureAdc() { return adc_ok; }
  size_t read(int16_t* samples, size_t count) {
    if (reads++ >= reads_before_fail) {
      return 0;
    }
    for (size_t i = 0; i + 1 < count; i += 2, ++frame) {
      const float t = static_cast<float>(frame) / static_cast<float>(SpectrumAnalyzer::SAMPLE_RATE_HZ);
      const int16_t v = static_cast<int16_t>(lrintf(amplitude * sinf(2.0f * 3.14159265f * freq_hz * t)));
      samples[i] = v;
      samples[i + 1] = v;
    }
    return count;
  }
  void shutdownAdc() { ++shutdown_calls; }
  void endBus() { ++end_calls; }
};

SpectrumReading analyzeSine(float freq_hz, float amplitude = 16000.0f) {
  static SpectrumAnalyzer analyzer;
  SineMicHal hal;
  hal.freq_hz = freq_hz;
  hal.amplitude = amplitude;
  return SpectrumCapture::measure(hal, analyzer);
}

void testSpectrumBandEdges() {
  assert(approxEqual(SpectrumAnalyzer::bandEdgeHz(0), 100.0f, 0.01f));
  assert(approxEqual(SpectrumAnalyzer::bandEdgeHz(SpectrumAnalyzer::BANDS), 8000.0f, 0.5f));
  for (size_t b = 0; b < SpectrumAnalyzer::BANDS; ++b) {
    assert(SpectrumAnalyzer::bandEdgeHz(b + 1) > SpectrumAnalyzer::bandEdgeHz(b));
    const float c = SpectrumAnalyzer::bandCenterHz(b);
    assert(c > SpectrumAnalyzer::bandEdgeHz(b) && c < SpectrumAnalyzer::bandEdgeHz(b + 1));
    assert(SpectrumAnalyzer::bandForFrequency(c) == static_cast<int>(b));
  }
  // Edges are the lower limit of their band; the frequency just below belongs to the band before.
  for (size_t b = 1; b < SpectrumAnalyzer::BANDS; ++b) {
    const float edge = SpectrumAnalyzer::bandEdgeHz(b);
    assert(SpectrumAnalyzer::bandForFrequency(edge * 1.0001f) == static_cast<int>(b));
    assert(SpectrumAnalyzer::bandForFrequency(edge * 0.9999f) == static_cast<int>(b) - 1);
  }
  // Outside the range there is no band.
  assert(SpectrumAnalyzer::bandForFrequency(99.0f) == -1);
  assert(SpectrumAnalyzer::bandForFrequency(8000.0f) == -1);
  assert(SpectrumAnalyzer::bandForFrequency(20000.0f) == -1);
  assert(SpectrumAnalyzer::bandForFrequency(0.0f) == -1);
  assert(SpectrumAnalyzer::bandForFrequency(-5.0f) == -1);
  assert(SpectrumAnalyzer::bandForFrequency(NAN) == -1);
  assert(SpectrumAnalyzer::bandForFrequency(100.0f) == 0);
}

void testSpectrumFft() {
  // A bin-centered sine gives its peak in the right bin; the FFT of an impulse is flat.
  static float re[SpectrumAnalyzer::FFT_SIZE];
  static float im[SpectrumAnalyzer::FFT_SIZE];
  for (size_t n = 0; n < SpectrumAnalyzer::FFT_SIZE; ++n) {
    re[n] = sinf(2.0f * 3.14159265f * 32.0f * static_cast<float>(n) / 512.0f);
    im[n] = 0.0f;
  }
  SpectrumAnalyzer::fft(re, im);
  size_t best = 0;
  float best_mag = 0.0f;
  for (size_t k = 0; k <= SpectrumAnalyzer::FFT_SIZE / 2; ++k) {
    const float m = sqrtf(re[k] * re[k] + im[k] * im[k]);
    if (m > best_mag) {
      best_mag = m;
      best = k;
    }
  }
  assert(best == 32);
  assert(approxEqual(best_mag, 256.0f, 0.5f));

  for (size_t n = 0; n < SpectrumAnalyzer::FFT_SIZE; ++n) {
    re[n] = n == 0 ? 1.0f : 0.0f;
    im[n] = 0.0f;
  }
  SpectrumAnalyzer::fft(re, im);
  for (size_t k = 0; k < SpectrumAnalyzer::FFT_SIZE; ++k) {
    assert(approxEqual(re[k], 1.0f, 0.001f) && approxEqual(im[k], 0.0f, 0.001f));
  }
}

void testSpectrumSinePeaks() {
  const float tones[] = {250.0f, 1000.0f, 4000.0f};
  for (const float hz : tones) {
    const SpectrumReading r = analyzeSine(hz);
    assert(r.valid);
    const int want = SpectrumAnalyzer::bandForFrequency(hz);
    assert(want >= 0);
    // The tone may sit on a band edge; the highest bar lies in the band of the tone or next to it.
    const int got = r.loudestBand();
    assert(got >= 0);
    assert(got == want || got == want - 1 || got == want + 1);
    // The tone band stands clearly above bands far away from it.
    for (size_t b = 0; b < SpectrumReading::BANDS; ++b) {
      if (abs(static_cast<int>(b) - got) >= 4) {
        assert(r.bars[b] + 20 < r.bars[got]);
      }
      assert(r.peaks[b] >= r.bars[b]);
      assert(r.bars[b] <= SpectrumReading::LEVEL_MAX && r.peaks[b] <= SpectrumReading::LEVEL_MAX);
    }
  }
  // A tone in the middle of a band lands exactly in that band.
  for (const size_t band : {size_t(3), size_t(12), size_t(20), size_t(28)}) {
    const SpectrumReading r = analyzeSine(SpectrumAnalyzer::bandCenterHz(band));
    assert(r.loudestBand() == static_cast<int>(band));
  }
  // A louder tone gives a higher bar.
  assert(analyzeSine(1000.0f, 20000.0f).bars[SpectrumAnalyzer::bandForFrequency(1000.0f)] >
         analyzeSine(1000.0f, 500.0f).bars[SpectrumAnalyzer::bandForFrequency(1000.0f)]);
}

void testSpectrumSilenceAndLevels() {
  const SpectrumReading silent = analyzeSine(1000.0f, 0.0f);
  assert(silent.valid);
  assert(silent.loudestBand() == -1);
  for (size_t b = 0; b < SpectrumReading::BANDS; ++b) {
    assert(silent.bars[b] == 0 && silent.peaks[b] == 0);
  }
  assert(SpectrumAnalyzer::levelFromMagnitude(0.0f) == 0);
  assert(SpectrumAnalyzer::levelFromMagnitude(-1.0f) == 0);
  assert(SpectrumAnalyzer::levelFromMagnitude(NAN) == 0);
  assert(SpectrumAnalyzer::levelFromMagnitude(0.00009f) == 0);  // below -80 dBFS
  assert(SpectrumAnalyzer::levelFromMagnitude(0.01f) == 50);    // -40 dBFS
  assert(SpectrumAnalyzer::levelFromMagnitude(1.0f) == 100);
  assert(SpectrumAnalyzer::levelFromMagnitude(50.0f) == 100);
  // A reading without data has no loudest band.
  assert(SpectrumReading{}.loudestBand() == -1);
}

void testSpectrumCaptureLifecycle() {
  static SpectrumAnalyzer analyzer;
  // A good window: 1 s plus the 100 ms settling time gives 34 full frames at 16 kHz.
  SineMicHal ok;
  const SpectrumReading r = SpectrumCapture::measure(ok, analyzer);
  assert(r.valid && analyzer.frameCount() == 31);
  assert(ok.shutdown_calls == 1 && ok.end_calls == 1);

  // Each failure path still powers the ADC down and ends the bus.
  SineMicHal bus;
  bus.bus_ok = false;
  assert(!SpectrumCapture::measure(bus, analyzer).valid);
  assert(bus.shutdown_calls == 1 && bus.end_calls == 1);
  SineMicHal adc;
  adc.adc_ok = false;
  assert(!SpectrumCapture::measure(adc, analyzer).valid);
  assert(adc.shutdown_calls == 1 && adc.end_calls == 1);
  SineMicHal shortread;
  shortread.reads_before_fail = 10;
  assert(!SpectrumCapture::measure(shortread, analyzer).valid);
  assert(shortread.shutdown_calls == 1 && shortread.end_calls == 1);
  SineMicHal zero;
  assert(!SpectrumCapture::measure(zero, analyzer, 0).valid);
  assert(zero.shutdown_calls == 1 && zero.end_calls == 1);
  // A window below one frame gives no reading.
  SineMicHal tiny;
  assert(!SpectrumCapture::measure(tiny, analyzer, 10).valid);
  // Null input and an odd sample count are safe.
  analyzer.reset();
  analyzer.addSamples(nullptr, 100);
  int16_t odd[3] = {1, 2, 3};
  analyzer.addSamples(odd, 3);
  assert(analyzer.frameCount() == 0 && !analyzer.result().valid);
}

void testSpectrumView() {
  BatteryReading battery;
  battery.valid = true;
  battery.percentage = 100;
  SystemInfo info;
  info.spectrum.valid = true;
  // Worst case: every bar and peak at full scale must stay inside the plot area.
  for (size_t b = 0; b < SpectrumReading::BANDS; ++b) {
    info.spectrum.bars[b] = 100;
    info.spectrum.peaks[b] = 100;
  }
  St7305Framebuffer full;
  full.clear(true); // poison: a full redraw must overwrite it
  DashboardView::renderMode(
      full, DisplayMode::AUDIO_SPECTRUM, Shtc3Reading{}, battery, nullptr, 0, info, "88:88", 0);
  for (uint16_t y = DashboardView::HEADER_HEIGHT; y < DashboardView::SPECTRUM_TOP_Y; ++y) {
    for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
      assert(!full.getPixel(x, y));
    }
  }
  // The tallest bar reaches the top of the plot, and the gap between two bars stays white.
  assert(full.getPixel(DashboardView::SPECTRUM_X, DashboardView::SPECTRUM_BASE_Y - 1));
  assert(full.getPixel(DashboardView::SPECTRUM_X, DashboardView::SPECTRUM_TOP_Y));
  assert(!full.getPixel(DashboardView::SPECTRUM_X + DashboardView::SPECTRUM_BAR_W, 120));
  // Right edge of the last bar: x = 8 + 31 * 12 + 9 = 389, inside the 400 px width.
  assert(full.getPixel(389, 120) && !full.getPixel(390, 120));
  assert(!full.getPixel(DashboardView::SPECTRUM_X - 1, 120));

  // Bar height follows the level; a zero level draws no bar; peak marker sits above the bar.
  info.spectrum.valid = true;
  for (size_t b = 0; b < SpectrumReading::BANDS; ++b) {
    info.spectrum.bars[b] = 0;
    info.spectrum.peaks[b] = 0;
  }
  info.spectrum.bars[5] = 50;
  info.spectrum.peaks[5] = 80;
  St7305Framebuffer part;
  DashboardView::renderMode(
      part, DisplayMode::AUDIO_SPECTRUM, Shtc3Reading{}, battery, nullptr, 0, info, "88:88", 0);
  const uint16_t x5 = DashboardView::SPECTRUM_X + 5 * DashboardView::SPECTRUM_BAR_PITCH;
  assert(DashboardView::spectrumBarHeight(50) == 100 && DashboardView::spectrumBarHeight(255) == 200);
  assert(part.getPixel(x5, DashboardView::SPECTRUM_BASE_Y - 100));
  assert(!part.getPixel(x5, DashboardView::SPECTRUM_BASE_Y - 101));
  assert(part.getPixel(x5, DashboardView::SPECTRUM_BASE_Y - 160 - 3));
  assert(!part.getPixel(x5 + DashboardView::SPECTRUM_BAR_PITCH, DashboardView::SPECTRUM_BASE_Y - 10));

  // An invalid reading shows no bars; the frequency labels stay and the view differs from the full one.
  St7305Framebuffer none;
  info.spectrum = SpectrumReading{};
  DashboardView::renderMode(
      none, DisplayMode::AUDIO_SPECTRUM, Shtc3Reading{}, battery, nullptr, 0, info, "88:88", 0);
  assert(!none.getPixel(x5, DashboardView::SPECTRUM_BASE_Y - 10));
  assert(memcmp(none.data(), full.data(), St7305Framebuffer::BYTE_COUNT) != 0);

  // Labels "100", "1K" and "8K" under the baseline: ink inside the label row, none below it.
  bool label_ink = false;
  for (uint16_t y = DashboardView::SPECTRUM_LABEL_Y; y < DashboardView::SPECTRUM_LABEL_Y + 14; ++y) {
    for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
      label_ink = label_ink || none.getPixel(x, y);
    }
  }
  assert(label_ink);
  // No ink between the label row and the text line below it.
  for (uint16_t y = DashboardView::SPECTRUM_LABEL_Y + 14; y < 272; ++y) {
    for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
      assert(!none.getPixel(x, y));
    }
  }
  // The "1K" label sits near the log position of 1 kHz (x = 8 + 0.5 * 382 = 199).
  bool one_k_ink = false;
  for (uint16_t x = 185; x < 215; ++x) {
    for (uint16_t y = DashboardView::SPECTRUM_LABEL_Y; y < DashboardView::SPECTRUM_LABEL_Y + 14; ++y) {
      one_k_ink = one_k_ink || none.getPixel(x, y);
    }
  }
  assert(one_k_ink);
  // "8K" ends at or before the right screen edge: 2 glyphs, 22 px.
  bool right_ink = false;
  for (uint16_t x = 380; x < St7305Framebuffer::WIDTH; ++x) {
    for (uint16_t y = DashboardView::SPECTRUM_LABEL_Y; y < DashboardView::SPECTRUM_LABEL_Y + 14; ++y) {
      right_ink = right_ink || none.getPixel(x, y);
    }
  }
  assert(right_ink);
}

void testSpectrumKeyHandling() {
  // In the spectrum view a short press captures again and keeps the mode.
  DisplayModeController c;
  c.setMode(DisplayMode::AUDIO_SPECTRUM);
  uint32_t t = 0;
  assert(c.updateWithLongPress(false, t) == ButtonEvent::NONE);
  t += 100;
  assert(c.updateWithLongPress(true, t) == ButtonEvent::NONE);
  t += 40;
  assert(c.updateWithLongPress(true, t) == ButtonEvent::NONE); // debounced press, wait for release
  assert(c.pressPending());
  t += 100;
  assert(c.updateWithLongPress(false, t) == ButtonEvent::NONE);
  t += 40;
  assert(c.updateWithLongPress(false, t) == ButtonEvent::RECAPTURE);
  assert(c.mode() == DisplayMode::AUDIO_SPECTRUM);
  assert(!c.pressPending());

  // A hold of 2 s leaves the view: the next mode is the dashboard.
  t += 100;
  c.updateWithLongPress(true, t);
  t += 40;
  c.updateWithLongPress(true, t);
  t += LongPressDetector::LONG_PRESS_MS;
  assert(c.updateWithLongPress(true, t) == ButtonEvent::MODE_CHANGED);
  assert(c.mode() == DisplayMode::DASHBOARD);

  // Entry from the diagnostics view: a short press goes to the spectrum view at once on release.
  DisplayModeController d;
  d.setMode(DisplayMode::SYSTEM_DIAGNOSTICS);
  t = 0;
  d.updateWithLongPress(false, t);
  t += 100;
  d.updateWithLongPress(true, t);
  t += 40;
  d.updateWithLongPress(true, t);
  t += 100;
  d.updateWithLongPress(false, t);
  t += 40;
  assert(d.updateWithLongPress(false, t) == ButtonEvent::MODE_CHANGED);
  assert(d.mode() == DisplayMode::AUDIO_SPECTRUM);

  // Plain update() still cycles through all four modes.
  DisplayModeController e;
  for (uint8_t i = 0; i < DISPLAY_MODE_COUNT; ++i) {
    e.next();
  }
  assert(e.mode() == DisplayMode::DASHBOARD);
  assert(DISPLAY_MODE_COUNT == 4 && static_cast<uint8_t>(DisplayMode::AUDIO_SPECTRUM) == 3);
}

void testSpectrumStoredViewMode() {
  PersistentState s;
  s.mode = DisplayMode::AUDIO_SPECTRUM;
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  PersistentState r;
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(r.mode == DisplayMode::AUDIO_SPECTRUM);

  // A stored mode above 3 fails, also behind a valid checksum.
  for (const uint8_t bad_mode : {uint8_t(4), uint8_t(5), uint8_t(255)}) {
    uint8_t bad[PowerManager::SERIALIZED_SIZE];
    memcpy(bad, buf, sizeof(buf));
    bad[5] = bad_mode; // magic (4) + version (1), then the mode byte
    const uint32_t crc = PowerManager::crc32(bad, sizeof(bad) - 4);
    for (int i = 0; i < 4; ++i) bad[sizeof(bad) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
    PersistentState out;
    assert(!PowerManager::deserialize(bad, sizeof(bad), out));
    assert(out.mode == DisplayMode::DASHBOARD);
  }
}


// Builds a level envelope in dBFS, one value per ms. Noise floor -70 dB with a slight ripple.
struct ClapEnvelope {
  std::vector<int> level;
  explicit ClapEnvelope(size_t ms) : level(ms, -70) {
    for (size_t i = 0; i < ms; ++i) level[i] = -70 + static_cast<int>(i % 3);
  }
  // Fast rise to -10 dB, decay to the floor in `decay_ms`.
  void clap(size_t at, size_t decay_ms = 30) {
    for (size_t i = 0; i < decay_ms && at + i < level.size(); ++i) {
      level[at + i] = -10 - static_cast<int>(60 * i / decay_ms);
    }
  }
  void fill(size_t from, size_t to, int db) {
    for (size_t i = from; i < to && i < level.size(); ++i) level[i] = db;
  }
  // Number of double clap reports.
  int run() const {
    ClapDetector d;
    int hits = 0;
    for (size_t i = 0; i < level.size(); ++i) {
      if (d.update(level[i], static_cast<uint32_t>(i))) ++hits;
    }
    return hits;
  }
};

void testClapSingleClapDoesNotTrigger() {
  ClapEnvelope e(3000);
  e.clap(1000);
  assert(e.run() == 0);
}

void testClapTwoClapsGapRules() {
  ClapEnvelope tight(3000);
  tight.clap(1000);
  tight.clap(1100);
  assert(tight.run() == 0); // 100 ms: an echo, not a second clap
  ClapEnvelope good(3000);
  good.clap(1000);
  good.clap(1300);
  assert(good.run() == 1); // 300 ms
  ClapEnvelope lo(3000);
  lo.clap(1000);
  lo.clap(1150);
  assert(lo.run() == 1); // lower limit
  ClapEnvelope hi(3000);
  hi.clap(1000);
  hi.clap(1600);
  assert(hi.run() == 1); // upper limit
  ClapEnvelope slow(4000);
  slow.clap(1000);
  slow.clap(1800);
  assert(slow.run() == 0); // 800 ms
}

void testClapThirdClapIsIgnored() {
  ClapEnvelope e(5000);
  e.clap(1000);
  e.clap(1300);
  e.clap(1600);
  assert(e.run() == 0); // applause
  // The lockout also covers a later pair
  e.clap(2000);
  e.clap(2300);
  assert(e.run() == 0);
  // After the lockout a new pair works
  e.clap(3500);
  e.clap(3800);
  assert(e.run() == 1);
}

void testClapThirdClapJustInsideTriggerWindow() {
  ClapEnvelope e(4000);
  e.clap(1000);
  e.clap(1300);
  e.clap(1690, 30); // onset 390 ms after the second clap, decay ends after the 400 ms timer
  assert(e.run() == 0);
}

void testClapReportsOnceAndLocksOut() {
  ClapEnvelope e(5000);
  e.clap(1000);
  e.clap(1300);
  e.clap(1900); // 600 ms after the second clap: after the triple window
  assert(e.run() == 1);
  // A pair inside the 1 s lockout after a report gives no second report
  ClapEnvelope l(5000);
  l.clap(1000);
  l.clap(1300);
  l.clap(1900);
  l.clap(2200);
  assert(l.run() == 1);
}

void testClapContinuousLoudNoise() {
  ClapEnvelope e(5000);
  e.fill(1000, 4000, -20); // loud and steady
  assert(e.run() == 0);
  // A loud room with a clap on top (15 dB step is below the rise limit)
  ClapEnvelope loud(4000);
  loud.fill(0, 4000, -30);
  loud.fill(1000, 1030, -18);
  loud.fill(1300, 1330, -18);
  assert(loud.run() == 0);
}

void testClapSpeechLikeBursts() {
  ClapEnvelope e(6000);
  // Syllables: slow rise over 30 ms, plateau, slow decay over 150 ms; 250 ms apart
  for (size_t t = 1000; t < 5000; t += 250) {
    for (size_t i = 0; i < 30; ++i) e.level[t + i] = -70 + static_cast<int>(50 * i / 30);
    for (size_t i = 0; i < 40; ++i) e.level[t + 30 + i] = -20;
    for (size_t i = 0; i < 150; ++i) e.level[t + 70 + i] = -20 - static_cast<int>(50 * i / 150);
  }
  assert(e.run() == 0);
  // Abrupt but long bursts do not decay within 100 ms
  ClapEnvelope b(4000);
  b.fill(1000, 1300, -15);
  b.fill(1500, 1800, -15);
  assert(b.run() == 0);
}

void testClapBelowMinimumPeak() {
  ClapEnvelope e(3000);
  for (size_t i = 0; i < 3000; ++i) e.level[i] = -96;
  e.fill(1000, 1010, -50);
  e.fill(1300, 1310, -50);
  assert(e.run() == 0); // a rustle in a silent room
}

void testClapTimeWrapAround() {
  ClapDetector d;
  int hits = 0;
  const uint32_t start = 0xFFFFFF00u;
  ClapEnvelope e(3000);
  e.clap(1000);
  e.clap(1300);
  for (size_t i = 0; i < e.level.size(); ++i) {
    if (d.update(e.level[i], start + static_cast<uint32_t>(i))) ++hits;
  }
  assert(hits == 1);
}

// Fake ADC that serves a scripted level envelope as stereo frames: one millisecond is 16 frames
// with the peak amplitude of the level value.
struct FakeClapHal {
  std::vector<int> level;
  size_t pos_ms = 0;
  bool bus_ok = true;
  bool adc_ok = true;
  size_t reads_before_fail = static_cast<size_t>(-1);
  size_t reads = 0;
  int begin_calls = 0, config_calls = 0, shutdown_calls = 0, end_calls = 0;
  bool powered = false;

  bool beginBus() { ++begin_calls; return bus_ok; }
  bool configureAdc() { ++config_calls; powered = adc_ok; return adc_ok; }
  size_t read(int16_t* samples, size_t count) {
    if (reads++ >= reads_before_fail) return 0;
    for (size_t i = 0; i < count; i += 2) {
      const size_t ms = pos_ms + (i / 2) / 16;
      const int db = ms < level.size() ? level[ms] : -70;
      const float amp = 32768.0f * powf(10.0f, static_cast<float>(db) / 20.0f);
      const int16_t v = static_cast<int16_t>(amp > 32767.0f ? 32767.0f : amp);
      samples[i] = ((i / 2) & 1) ? static_cast<int16_t>(-v) : v;
      samples[i + 1] = samples[i];
    }
    pos_ms += count / 32;
    return count;
  }
  void shutdownAdc() { ++shutdown_calls; powered = false; }
  void endBus() { ++end_calls; }
};

void testClapListenerDetectsDoubleClapFromAudio() {
  FakeClapHal hal;
  ClapEnvelope e(4000);
  // The listener skips the first 100 ms of settling
  e.clap(1000);
  e.clap(1300);
  hal.level = e.level;
  int hits = 0;
  {
    ClapListener<FakeClapHal> listener(hal);
    for (int i = 0; i < 200; ++i) {
      if (listener.poll()) ++hits;
    }
    assert(listener.listening());
    assert(hal.begin_calls == 1 && hal.config_calls == 1);
  }
  assert(hits == 1);
  assert(!hal.powered && hal.shutdown_calls == 1 && hal.end_calls == 1);
}

void testClapListenerPowersDownOnEveryPath() {
  // Bus failure
  FakeClapHal bus;
  bus.bus_ok = false;
  {
    ClapListener<FakeClapHal> l(bus);
    assert(!l.poll() && !l.listening());
    assert(bus.shutdown_calls == 1 && bus.end_calls == 1);
    assert(!l.poll()); // stays stopped
    assert(bus.begin_calls == 1);
  }
  // ADC failure
  FakeClapHal adc;
  adc.adc_ok = false;
  {
    ClapListener<FakeClapHal> l(adc);
    assert(!l.poll() && !l.listening());
    assert(adc.shutdown_calls == 1);
  }
  // Read failure after two frames
  FakeClapHal rd;
  rd.reads_before_fail = 2;
  {
    ClapListener<FakeClapHal> l(rd);
    l.poll();
    l.poll();
    assert(l.listening() && rd.powered);
    assert(!l.poll() && !l.listening() && !rd.powered);
  }
  // Explicit stop, then the destructor adds nothing
  FakeClapHal st;
  {
    ClapListener<FakeClapHal> l(st);
    l.poll();
    l.stop();
    assert(!st.powered && st.shutdown_calls == 1 && st.end_calls == 1);
    l.stop();
    assert(st.shutdown_calls == 1);
  }
  assert(st.shutdown_calls == 1);
  // Scope exit without stop powers down
  FakeClapHal sc;
  {
    ClapListener<FakeClapHal> l(sc);
    l.poll();
    assert(sc.powered);
  }
  assert(!sc.powered && sc.end_calls == 1);
  // Never polled: the hardware stays untouched
  FakeClapHal idle;
  {
    ClapListener<FakeClapHal> l(idle);
  }
  assert(idle.begin_calls == 0 && idle.shutdown_calls == 0);
}

void testClapListenerReleaseAndReopen() {
  FakeClapHal hal;
  ClapListener<FakeClapHal> l(hal);
  l.poll();
  l.release();
  assert(!hal.powered && !l.listening());
  l.poll();
  assert(l.listening() && hal.begin_calls == 2);
  l.stop();
  assert(!hal.powered && hal.end_calls == 2);
}

void testClapListenerReopenFailurePowersDown() {
  FakeClapHal hal;
  ClapListener<FakeClapHal> l(hal);
  l.poll();
  l.release();
  hal.bus_ok = false;
  assert(!l.poll() && !l.listening() && !l.available());
  assert(!hal.powered && hal.shutdown_calls == 2 && hal.end_calls == 2);
}

void testClapListenModeGate() {
  BatteryReading usb;
  usb.valid = true;
  usb.usb_powered = true;
  BatteryReading bat;
  bat.valid = true;
  bat.percentage = 80;
  BatteryReading low = bat;
  low.low_battery = true;
  BatteryReading none; // no reading

  // USB: continuous, also after a timer wakeup
  assert(PowerManager::clapListenMode(usb, WakeupCause::TIMER) == ClapListenMode::CONTINUOUS);
  assert(PowerManager::clapListenMode(usb, WakeupCause::KEY_BUTTON) == ClapListenMode::CONTINUOUS);
  assert(PowerManager::clapListenMode(usb, WakeupCause::POWER_ON) == ClapListenMode::CONTINUOUS);
  // Battery: grace window only
  assert(PowerManager::clapListenMode(bat, WakeupCause::KEY_BUTTON) == ClapListenMode::GRACE);
  assert(PowerManager::clapListenMode(bat, WakeupCause::POWER_ON) == ClapListenMode::GRACE);
  assert(PowerManager::clapListenMode(bat, WakeupCause::TIMER) == ClapListenMode::OFF);
  assert(PowerManager::clapListenMode(none, WakeupCause::TIMER) == ClapListenMode::OFF);
  assert(PowerManager::clapListenMode(none, WakeupCause::KEY_BUTTON) == ClapListenMode::GRACE);
  // Low battery alert: off
  assert(PowerManager::clapListenMode(low, WakeupCause::KEY_BUTTON) == ClapListenMode::OFF);
  assert(PowerManager::clapListenMode(low, WakeupCause::TIMER) == ClapListenMode::OFF);

  // A continuous listener ends when the next scan is due
  PowerConfig cfg;
  assert(PowerManager::continuousListenAllowed(cfg, 10000, 0));
  assert(PowerManager::continuousListenAllowed(cfg, 58000, 0));
  assert(!PowerManager::continuousListenAllowed(cfg, 60000, 0));
  assert(!PowerManager::continuousListenAllowed(cfg, 90000, 0));
}

void testClapUsbPowerWatch() {
  UsbPowerWatch w(1000);
  assert(!w.due(1000) && !w.due(5999) && w.due(6000));
  assert(w.update(true, 6000));
  assert(!w.due(10999) && w.due(11000));
  // One low sample is a bad read, two in a row end the watch
  assert(w.update(false, 11000));
  assert(w.update(true, 16000)); // the count restarts
  assert(w.update(false, 21000));
  assert(!w.update(false, 26000));
  // Clock wrap-around
  UsbPowerWatch wrap(0xFFFFF000u);
  assert(!wrap.due(0xFFFFF800u) && wrap.due(0x00000400u + 0x1000u));
}

// ---- Battery runtime estimator ----

static BatteryReading runtimeCell(float voltage_v, bool usb = false) {
  BatteryReading r;
  r.valid = true;
  r.voltage_v = voltage_v;
  r.percentage = BatteryMonitor::voltageToPercent(voltage_v);
  r.usb_powered = usb;
  return r;
}

void testBatteryRuntimeLinearDischarge() {
  BatteryHistory h;
  for (int hour = 0; hour < 12; ++hour) {
    assert(h.push(4.00f - 0.02f * static_cast<float>(hour)));
  }
  double slope = 0.0;
  assert(h.slope(slope));
  assert(fabs(slope + 0.02) < 1e-4);
  // 3.78 V now, 0.58 V above empty at 0.02 V per hour: 29 h
  const BatteryRuntime r = h.estimate(runtimeCell(3.78f));
  assert(r.valid && r.hours == 29);
  char line[32];
  r.describe(line, sizeof(line));
  assert(strcmp(line, "RUNTIME: ~29 H") == 0);
  // The ring keeps 24 samples; the slope stays correct after the wrap
  for (int hour = 12; hour < 40; ++hour) {
    h.push(4.20f - 0.01f * static_cast<float>(hour));
  }
  assert(h.size() == BatteryHistory::CAPACITY);
  assert(h.slope(slope) && fabs(slope + 0.01) < 1e-4);
}

void testBatteryRuntimeDaysFormat() {
  char line[32];
  BatteryRuntime r;
  r.describe(line, sizeof(line));
  assert(strcmp(line, "RUNTIME: --") == 0);
  r.valid = true;
  r.hours = 47;
  r.describe(line, sizeof(line));
  assert(strcmp(line, "RUNTIME: ~47 H") == 0);
  r.hours = 48;
  r.describe(line, sizeof(line));
  assert(strcmp(line, "RUNTIME: ~2 D") == 0);
  r.hours = 100000;
  r.describe(line, sizeof(line));
  assert(strcmp(line, "RUNTIME: ~99 D") == 0);
  // A slow discharge: 1 mV per hour from 3.90 V, 700 h, shown as days
  BatteryHistory h;
  for (int hour = 0; hour < 10; ++hour) {
    h.push(3.91f - 0.001f * static_cast<float>(hour));
  }
  const BatteryRuntime slow = h.estimate(runtimeCell(3.90f));
  assert(slow.valid && slow.hours > 600 && slow.hours < 800);
  slow.describe(line, sizeof(line));
  assert(strncmp(line, "RUNTIME: ~", 10) == 0 && line[strlen(line) - 1] == 'D');
}

void testBatteryRuntimeFlatCurve() {
  BatteryHistory h;
  for (int hour = 0; hour < 12; ++hour) {
    h.push(3.80f);
  }
  double slope = 1.0;
  assert(h.slope(slope) && fabs(slope) < 1e-9);
  assert(!h.estimate(runtimeCell(3.80f)).valid);
  // A rising curve below the charge step also gives no estimate
  BatteryHistory rising;
  for (int hour = 0; hour < 12; ++hour) {
    rising.push(3.70f + 0.005f * static_cast<float>(hour));
  }
  assert(rising.size() == 12);
  assert(!rising.estimate(runtimeCell(3.75f)).valid);
}

void testBatteryRuntimeChargeStepResets() {
  BatteryHistory h;
  for (int hour = 0; hour < 10; ++hour) {
    h.push(3.60f - 0.01f * static_cast<float>(hour));
  }
  assert(h.size() == 10);
  // 3.51 V to 3.62 V is +0.11 V: the old samples drop out
  assert(h.push(3.62f));
  assert(h.size() == 1 && h.at(0) == 3620);
  assert(!h.estimate(runtimeCell(3.62f)).valid);
  // A rise of 0.09 V keeps the history
  BatteryHistory keep;
  keep.push(3.60f);
  keep.push(3.69f);
  assert(keep.size() == 2);
  // A rise of exactly 0.10 V clears it
  BatteryHistory edge;
  edge.push(3.60f);
  edge.push(3.70f);
  assert(edge.size() == 1);
  // USB power clears the history and records nothing
  BatteryHistory usb;
  for (int hour = 0; hour < 8; ++hour) {
    usb.push(3.80f - 0.01f * static_cast<float>(hour));
  }
  assert(!usb.record(0, runtimeCell(4.30f, true)));
  assert(usb.size() == 0);
  assert(!usb.estimate(runtimeCell(4.30f, true)).valid);
}

void testBatteryRuntimeTooFewSamples() {
  BatteryHistory h;
  for (int hour = 0; hour < 5; ++hour) {
    h.push(3.90f - 0.02f * static_cast<float>(hour));
    assert(!h.estimate(runtimeCell(3.80f)).valid);
  }
  assert(h.size() == 5);
  h.push(3.80f);
  assert(h.size() == BatteryHistory::MIN_SAMPLES);
  assert(h.estimate(runtimeCell(3.80f)).valid);
}

void testBatteryRuntimeInvalidReadings() {
  BatteryHistory h;
  assert(!h.push(NAN));
  assert(!h.push(INFINITY));
  assert(!h.push(0.0f));
  assert(!h.push(-3.7f));
  assert(!h.push(2.0f));
  assert(!h.push(9.9f));
  assert(h.size() == 0);
  for (int hour = 0; hour < 10; ++hour) {
    h.push(3.90f - 0.02f * static_cast<float>(hour));
  }
  const size_t before = h.size();
  assert(!h.push(NAN) && h.size() == before);
  // A missing battery, a NaN voltage and an implausible voltage give no estimate
  assert(!h.estimate(BatteryReading{}).valid);
  assert(!h.estimate(runtimeCell(NAN)).valid);
  assert(!h.estimate(runtimeCell(5.0f)).valid);
  // record() rejects an invalid reading and keeps the hourly gate open
  BatteryHistory gate;
  assert(!gate.record(1000, BatteryReading{}));
  assert(!gate.has_sampled);
  assert(gate.record(1000, runtimeCell(3.9f)));
  assert(!gate.record(1000 + 1000, runtimeCell(3.9f))); // inside the hour
  assert(gate.record(1000 + BatteryHistory::SAMPLE_INTERVAL_MS, runtimeCell(3.88f)));
  assert(gate.size() == 2);
  // The estimate is 0 h at or below the empty voltage, never negative
  BatteryHistory low;
  for (int hour = 0; hour < 8; ++hour) {
    low.push(3.30f - 0.01f * static_cast<float>(hour));
  }
  const BatteryRuntime empty = low.estimate(runtimeCell(3.15f));
  assert(empty.valid && empty.hours == 0);
}

void testBatteryRuntimeIrregularSpacingAndSlowCharge() {
  // Samples 90 min apart at 0.03 V each: 0.02 V per hour, not 0.03 V per hour
  BatteryHistory h;
  const uint32_t step = BatteryHistory::SAMPLE_INTERVAL_MS * 3 / 2;
  for (uint32_t i = 0; i < 8; ++i) {
    assert(h.push(4.00f - 0.03f * static_cast<float>(i), 5000 + i * step));
  }
  double slope = 0.0;
  assert(h.slope(slope) && fabs(slope + 0.02) < 1e-4);
  // The clock wrap does not change the slope
  BatteryHistory wrap;
  for (uint32_t i = 0; i < 8; ++i) {
    wrap.push(4.00f - 0.02f * static_cast<float>(i), 0xFFFFFFFFu - 3 * BatteryHistory::SAMPLE_INTERVAL_MS + i * BatteryHistory::SAMPLE_INTERVAL_MS);
  }
  assert(wrap.slope(slope) && fabs(slope + 0.02) < 1e-4);
  // A slow charge in steps below 0.10 V per sample still clears the stale discharge samples
  BatteryHistory slow;
  for (int i = 0; i < 6; ++i) {
    slow.push(3.60f - 0.01f * static_cast<float>(i));
  }
  slow.push(3.60f);
  slow.push(3.64f);
  assert(slow.size() == 8);
  slow.push(3.68f); // 3.68 V - lowest 3.55 V = 0.13 V
  assert(slow.size() == 1);
}

void testBatteryHistoryCorruptStamps() {
  PersistentState s;
  for (int hour = 0; hour < 9; ++hour) {
    s.battery_history.push(3.95f - 0.015f * static_cast<float>(hour), 1000u + hour * 3600000u);
  }
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  const size_t base = PowerManager::SERIALIZED_SIZE - 4 - PowerManager::CONFIG_SIZE -
                      PowerManager::NTP_SIZE - PowerManager::AUDIO_SIZE - PowerManager::SD_LOG_SIZE -
                      PowerManager::BATTERY_HISTORY_SIZE;
  const size_t stamps = base + 2 + BatteryHistory::CAPACITY * 2;
  // Stamp 3 equals stamp 2: not increasing
  uint8_t bad[PowerManager::SERIALIZED_SIZE];
  memcpy(bad, buf, sizeof(bad));
  memcpy(bad + stamps + 3 * 4, bad + stamps + 2 * 4, 4);
  const uint32_t crc = PowerManager::crc32(bad, sizeof(bad) - 4);
  for (int i = 0; i < 4; ++i) bad[sizeof(bad) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  PersistentState x;
  assert(!PowerManager::deserialize(bad, sizeof(bad), x));
}

void testBatteryHistoryPersistence() {
  PersistentState s;
  for (int hour = 0; hour < 9; ++hour) {
    s.battery_history.push(3.95f - 0.015f * static_cast<float>(hour));
  }
  s.battery_history.last_sample_ms = 123456;
  s.battery_history.has_sampled = true;
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  PersistentState r;
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(r.battery_history.size() == 9);
  for (size_t i = 0; i < 9; ++i) {
    assert(r.battery_history.at(i) == s.battery_history.at(i));
  }
  assert(r.battery_history.last_sample_ms == 123456 && r.battery_history.has_sampled);
  double a = 0.0, b = 0.0;
  assert(s.battery_history.slope(a) && r.battery_history.slope(b) && a == b);

  // A flipped byte fails the checksum
  const size_t base = PowerManager::SERIALIZED_SIZE - 4 - PowerManager::CONFIG_SIZE -
                      PowerManager::NTP_SIZE - PowerManager::AUDIO_SIZE - PowerManager::SD_LOG_SIZE -
                      PowerManager::BATTERY_HISTORY_SIZE;
  uint8_t bad[PowerManager::SERIALIZED_SIZE];
  memcpy(bad, buf, sizeof(bad));
  bad[base + 4] ^= 0x01;
  PersistentState rejected;
  assert(!PowerManager::deserialize(bad, sizeof(bad), rejected));
  assert(rejected.battery_history.size() == 0);

  // Each corrupt field with a valid CRC is rejected: count, head, live sample, sampled flag
  const size_t sampled_pos = base + PowerManager::BATTERY_HISTORY_SIZE - 1;
  // Newest sample sits at index head - 1 = 8; low byte at base + 2 + 16
  const size_t cases[5][2] = {
      {base, 25}, {base + 1, 24}, {base + 2 + 16 + 1, 0x00}, {base + 2 + 16 + 1, 0xFF}, {sampled_pos, 2}};
  for (const auto& c : cases) {
    uint8_t b2[PowerManager::SERIALIZED_SIZE];
    memcpy(b2, buf, sizeof(b2));
    b2[c[0]] = static_cast<uint8_t>(c[1]);
    const uint32_t crc = PowerManager::crc32(b2, sizeof(b2) - 4);
    for (int i = 0; i < 4; ++i) b2[sizeof(b2) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
    PersistentState x;
    assert(!PowerManager::deserialize(b2, sizeof(b2), x));
    assert(x.battery_history.size() == 0);
  }
  // The previous state version gives a clean start
  uint8_t old[PowerManager::SERIALIZED_SIZE];
  memcpy(old, buf, sizeof(old));
  old[4] = PowerManager::STATE_VERSION - 1;
  const uint32_t crc = PowerManager::crc32(old, sizeof(old) - 4);
  for (int i = 0; i < 4; ++i) old[sizeof(old) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  PersistentState clean;
  assert(!PowerManager::deserialize(old, sizeof(old), clean));
}

void testDiagnosticsRuntimeLine() {
  BatteryReading battery = runtimeCell(3.9f);
  auto render = [&](St7305Framebuffer& fb, const BatteryRuntime& runtime) {
    SystemInfo info;
    info.free_heap_bytes = 8u * 1024 * 1024;
    info.uptime_ms = 0xFFFFFFFFFFull * 1000;
    info.runtime = runtime;
    fb.clear(true);
    DashboardView::renderDiagnostics(fb, battery, info, "88:88");
  };
  auto inkIn = [](const St7305Framebuffer& fb, uint16_t y0, uint16_t y1, uint16_t x0 = 0, uint16_t x1 = 400) {
    size_t ink = 0;
    for (uint16_t y = y0; y < y1; ++y) {
      for (uint16_t x = x0; x < x1; ++x) {
        ink += fb.getPixel(x, y) ? 1 : 0;
      }
    }
    return ink;
  };
  St7305Framebuffer none, hours, days;
  BatteryRuntime h;
  h.valid = true;
  h.hours = 36;
  BatteryRuntime d;
  d.valid = true;
  d.hours = 24 * 10;
  render(none, BatteryRuntime{});
  render(hours, h);
  render(days, d);
  // The runtime line (y = 104..118) differs between the states and stays inside the screen
  assert(inkIn(none, 104, 118) > 0);
  assert(inkIn(hours, 104, 118) != inkIn(none, 104, 118));
  assert(inkIn(days, 104, 118) != inkIn(hours, 104, 118));
  // No clipping: the longest text ends before x = 400 and the neighbour lines stay unchanged
  assert(inkIn(hours, 104, 118, 10 + 14 * 12, 400) == 0);
  assert(inkIn(hours, 84, 100) == inkIn(none, 84, 100));
  assert(inkIn(hours, 124, 300) == inkIn(none, 124, 300));
}

void testTildeGlyphIsDrawn() {
  St7305Framebuffer fb;
  fb.clear(false);
  fb.drawText(0, 0, "~", 2, true);
  size_t ink = 0;
  for (uint16_t y = 0; y < 14; ++y) {
    for (uint16_t x = 0; x < 10; ++x) ink += fb.getPixel(x, y) ? 1 : 0;
  }
  assert(ink > 0);
}

void testPreviewRuntimeScene() {
  using namespace rlcd_panel::preview;
  bool found = false;
  for (uint8_t i = 0; i < PREVIEW_SCENE_COUNT; ++i) {
    if (!PREVIEW_SCENES[i].long_runtime) continue;
    found = true;
    assert(PREVIEW_SCENES[i].mode == DisplayMode::SYSTEM_DIAGNOSTICS);
    St7305Framebuffer fb, base;
    renderScene(fb, PREVIEW_SCENES[i].mode, false, false, true);
    renderScene(base, DisplayMode::SYSTEM_DIAGNOSTICS, false, false, false);
    size_t diff = 0;
    for (uint16_t y = 104; y < 118; ++y) {
      for (uint16_t x = 0; x < 400; ++x) diff += fb.getPixel(x, y) != base.getPixel(x, y);
    }
    assert(diff > 0); // days text differs from the hours text
    for (uint16_t y = 104; y < 118; ++y) {
      for (uint16_t x = 10 + 14 * 12; x < 400; ++x) assert(!fb.getPixel(x, y));
    }
  }
  assert(found);
}

// ---- USB serial console ----

namespace {

struct FakeConsoleStorage {
  std::vector<SerialLogEntry> entries;
  std::vector<std::string> contents;
  bool card = true;
  int mounts = 0;
  int unmounts = 0;
  bool open = false;
  size_t pos = 0;
  size_t index = 0;
  size_t max_read = 1000;
  bool fail_read = false;

  bool mount() {
    ++mounts;
    return card;
  }
  void unmount() { ++unmounts; }
  size_t listLogs(SerialLogEntry* out, size_t cap) {
    size_t n = 0;
    for (const SerialLogEntry& e : entries) {
      if (n < cap) {
        out[n++] = e;
      }
    }
    return n;
  }
  bool openLog(const char* name, uint32_t& size) {
    for (size_t i = 0; i < entries.size(); ++i) {
      if (strcmp(entries[i].name, name) == 0) {
        open = true;
        pos = 0;
        index = i;
        size = static_cast<uint32_t>(contents[i].size());
        return true;
      }
    }
    return false;
  }
  size_t read(uint8_t* out, size_t len) {
    if (fail_read) {
      return 0;
    }
    const size_t n = std::min(std::min(len, max_read), contents[index].size() - pos);
    memcpy(out, contents[index].data() + pos, n);
    pos += n;
    return n;
  }
  void closeLog() { open = false; }
  uint64_t totalBytes() { return 1000; }
  uint64_t usedBytes() { return 250; }

  void add(const char* name, const std::string& data) {
    SerialLogEntry e;
    snprintf(e.name, sizeof(e.name), "%s", name);
    e.size = static_cast<uint32_t>(data.size());
    entries.push_back(e);
    contents.push_back(data);
  }
};

struct FakeSink {
  std::string out;
  size_t limit = static_cast<size_t>(-1); // bytes the sink still accepts
  size_t write(const uint8_t* d, size_t n) {
    const size_t take = n < limit ? n : limit;
    out.append(reinterpret_cast<const char*>(d), take);
    limit -= take;
    return take;
  }
};

SerialParseStatus parseText(const std::string& s, SerialCommand& cmd) {
  return SerialConsole::parse(s.data(), s.size(), cmd);
}

using TestRunner = SerialConsoleRunner<FakeConsoleStorage, FakeSink>;

} // namespace

void testSerialCrc32KnownVectors() {
  SerialCrc32 empty;
  assert(empty.value() == 0x00000000u);
  SerialCrc32 check;
  check.update(reinterpret_cast<const uint8_t*>("123456789"), 9);
  assert(check.value() == 0xCBF43926u); // standard CRC-32 check value
  SerialCrc32 split;
  split.update(reinterpret_cast<const uint8_t*>("1234"), 4);
  split.update(reinterpret_cast<const uint8_t*>("56789"), 5);
  assert(split.value() == 0xCBF43926u);
}

void testSerialFileNameValidation() {
  assert(SdLogCodec::isLogFileName("climate_v2_202611.csv"));
  assert(SdLogCodec::isLogFileName("climate_v2_200001.csv"));
  assert(SdLogCodec::isLogFileName("climate_v2_209912.csv"));
  assert(!SdLogCodec::isLogFileName(nullptr));
  assert(!SdLogCodec::isLogFileName(""));
  assert(!SdLogCodec::isLogFileName("climate_v2_202613.csv")); // month 13
  assert(!SdLogCodec::isLogFileName("climate_v2_202600.csv")); // month 0
  assert(!SdLogCodec::isLogFileName("climate_v2_199912.csv")); // year before 2000
  assert(!SdLogCodec::isLogFileName("climate_v2_210001.csv")); // year after 2099
  assert(!SdLogCodec::isLogFileName("climate_202611.csv"));    // old v1 name
  assert(!SdLogCodec::isLogFileName("climate_v2_20261.csv"));
  assert(!SdLogCodec::isLogFileName("climate_v2_2026111.csv"));
  assert(!SdLogCodec::isLogFileName("climate_v2_2026a1.csv"));
  assert(!SdLogCodec::isLogFileName("climate_v2_202611.txt"));
  assert(!SdLogCodec::isLogFileName("climate_v2_202611.csv "));
  assert(!SdLogCodec::isLogFileName("CLIMATE_V2_202611.CSV"));
  assert(!SdLogCodec::isLogFileName("/climate_v2_202611.csv"));
  assert(!SdLogCodec::isLogFileName("../climate_v2_202611.csv"));
  assert(!SdLogCodec::isLogFileName("climate_v2_202611.csv/.."));
}

void testSerialParserValidCommands() {
  SerialCommand cmd;
  assert(parseText("LIST", cmd) == SerialParseStatus::OK && cmd.kind == SerialCommandKind::LIST);
  assert(parseText("INFO", cmd) == SerialParseStatus::OK && cmd.kind == SerialCommandKind::INFO);
  assert(parseText("DUMP climate_v4_202611.csv", cmd) == SerialParseStatus::OK);
  assert(cmd.kind == SerialCommandKind::DUMP && strcmp(cmd.file, "climate_v4_202611.csv") == 0);
  assert(parseText("DUMP climate_v5_202611.csv", cmd) == SerialParseStatus::BAD_NAME);
  assert(parseText("DUMP ../climate_v4_202611.csv", cmd) == SerialParseStatus::BAD_NAME);
  assert(parseText("DUMP climate_v2_202611.csv", cmd) == SerialParseStatus::OK);
  assert(cmd.kind == SerialCommandKind::DUMP && strcmp(cmd.file, "climate_v2_202611.csv") == 0);
  // CR and LF at the line end are tolerated
  assert(parseText("LIST\r\n", cmd) == SerialParseStatus::OK && cmd.kind == SerialCommandKind::LIST);
  assert(parseText("DUMP climate_v2_202601.csv\r", cmd) == SerialParseStatus::OK);
  assert(strcmp(cmd.file, "climate_v2_202601.csv") == 0);
}

void testSerialParserRejectsUnknownCommands() {
  SerialCommand cmd;
  assert(parseText("", cmd) == SerialParseStatus::EMPTY && cmd.kind == SerialCommandKind::NONE);
  assert(parseText("\r\n", cmd) == SerialParseStatus::EMPTY);
  assert(SerialConsole::parse(nullptr, 0, cmd) == SerialParseStatus::EMPTY);
  assert(parseText("list", cmd) == SerialParseStatus::UNKNOWN); // case-sensitive
  assert(parseText("DELETE climate_v2_202611.csv", cmd) == SerialParseStatus::UNKNOWN);
  assert(parseText("LISTX", cmd) == SerialParseStatus::UNKNOWN);
  assert(parseText("LIST now", cmd) == SerialParseStatus::UNKNOWN);
  assert(parseText("INFO ", cmd) == SerialParseStatus::UNKNOWN);
  assert(parseText("LIST\tINFO", cmd) == SerialParseStatus::UNKNOWN);
  assert(parseText(std::string("LIST\0x", 6), cmd) == SerialParseStatus::UNKNOWN);
  assert(parseText("\xC3\xA4", cmd) == SerialParseStatus::UNKNOWN);
  assert(cmd.kind == SerialCommandKind::NONE);
}

void testSerialParserRejectsLongLines() {
  SerialCommand cmd;
  const std::string at_limit = "DUMP " + std::string(SerialConsole::MAX_LINE_SIZE - 5, 'a');
  assert(at_limit.size() == SerialConsole::MAX_LINE_SIZE);
  assert(parseText(at_limit, cmd) == SerialParseStatus::BAD_NAME); // long, but not too long
  assert(parseText(at_limit + "a", cmd) == SerialParseStatus::TOO_LONG);
  assert(parseText(std::string(5000, 'L'), cmd) == SerialParseStatus::TOO_LONG);
  assert(cmd.kind == SerialCommandKind::NONE);
}

void testSerialParserRejectsPathTraversal() {
  SerialCommand cmd;
  const char* bad[] = {
      "DUMP",
      "DUMP ",
      "DUMP  climate_v2_202611.csv",
      "DUMP ../climate_v2_202611.csv",
      "DUMP /climate_v2_202611.csv",
      "DUMP /sdcard/climate_v2_202611.csv",
      "DUMP ..",
      "DUMP /config.ini",
      "DUMP config.ini",
      "DUMP climate_v2_202611.csv/",
      "DUMP climate_v2_202611.csv extra",
      "DUMP ..\\climate_v2_202611.csv",
      "DUMP climate_v2_202613.csv",
      "DUMP *",
      "DUMP climate_v2_%%%%%%.csv",
  };
  for (const char* line : bad) {
    assert(parseText(line, cmd) == SerialParseStatus::BAD_NAME);
    assert(cmd.kind == SerialCommandKind::NONE && cmd.file[0] == '\0');
  }
}

void testSerialLineAssembler() {
  SerialLineAssembler a;
  const char* text = "LIST\r\nINFO\n";
  std::vector<std::string> lines;
  for (const char* p = text; *p != '\0'; ++p) {
    if (a.feed(static_cast<uint8_t>(*p)) == SerialLineAssembler::Event::LINE) {
      lines.push_back(std::string(a.line(), a.length()));
    }
  }
  assert(lines.size() == 2 && lines[0] == "LIST" && lines[1] == "INFO");
  // An overlong line is dropped whole; its tail never runs as a command
  SerialLineAssembler b;
  int overflow = 0;
  int ok = 0;
  const std::string longline = std::string(100, 'x') + "LIST\n";
  for (char c : longline) {
    const SerialLineAssembler::Event e = b.feed(static_cast<uint8_t>(c));
    overflow += e == SerialLineAssembler::Event::LINE_TOO_LONG;
    ok += e == SerialLineAssembler::Event::LINE;
  }
  assert(overflow == 1 && ok == 0);
  // The assembler recovers on the next line
  for (char c : std::string("INFO\n")) {
    ok += b.feed(static_cast<uint8_t>(c)) == SerialLineAssembler::Event::LINE;
  }
  assert(ok == 1 && strcmp(b.line(), "INFO") == 0);
  // A line of exactly MAX_LINE_SIZE bytes is accepted
  SerialLineAssembler c;
  SerialLineAssembler::Event last = SerialLineAssembler::Event::NONE;
  for (size_t i = 0; i < SerialConsole::MAX_LINE_SIZE; ++i) {
    c.feed('a');
  }
  last = c.feed('\n');
  assert(last == SerialLineAssembler::Event::LINE && c.length() == SerialConsole::MAX_LINE_SIZE);
}

void testSerialDumpFrame() {
  FakeConsoleStorage storage;
  std::string data;
  for (int i = 0; i < 1500; ++i) { // several chunks, last one partial
    data += static_cast<char>('a' + i % 26);
    if (i % 60 == 59) {
      data += '\n';
    }
  }
  storage.add("climate_v2_202611.csv", data);
  storage.max_read = 100; // short reads must still give a complete frame
  FakeSink sink;
  SerialCommand cmd;
  assert(parseText("DUMP climate_v2_202611.csv", cmd) == SerialParseStatus::OK);
  TestRunner::execute(storage, sink, cmd);

  char begin[64];
  snprintf(begin, sizeof(begin), "BEGIN climate_v2_202611.csv %zu\n", data.size());
  assert(sink.out.compare(0, strlen(begin), begin) == 0);
  const std::string payload = sink.out.substr(strlen(begin), data.size());
  assert(payload == data); // exactly the byte count, unchanged
  SerialCrc32 crc;
  crc.update(reinterpret_cast<const uint8_t*>(data.data()), data.size());
  char end[32];
  snprintf(end, sizeof(end), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
  assert(sink.out.substr(strlen(begin) + data.size()) == end);
  // The card mounts once and unmounts once
  assert(storage.mounts == 1 && storage.unmounts == 1 && !storage.open);
}

void testSerialDumpEmptyFile() {
  FakeConsoleStorage storage;
  storage.add("climate_v2_202611.csv", "");
  FakeSink sink;
  SerialCommand cmd;
  assert(parseText("DUMP climate_v2_202611.csv", cmd) == SerialParseStatus::OK);
  TestRunner::execute(storage, sink, cmd);
  assert(sink.out == "BEGIN climate_v2_202611.csv 0\nCRC32 00000000\n");
}

void testSerialDumpFailures() {
  FakeConsoleStorage storage;
  storage.add("climate_v2_202611.csv", std::string(300, 'x'));
  SerialCommand cmd;
  // Missing file
  FakeSink missing;
  assert(parseText("DUMP climate_v2_202601.csv", cmd) == SerialParseStatus::OK);
  TestRunner::execute(storage, missing, cmd);
  assert(missing.out == "ERR NOT_FOUND\n" && storage.unmounts == 1);
  // Read error mid-file: no CRC line, so the host sees a failed transfer
  storage.fail_read = true;
  FakeSink broken;
  assert(parseText("DUMP climate_v2_202611.csv", cmd) == SerialParseStatus::OK);
  TestRunner::execute(storage, broken, cmd);
  // The frame keeps the announced length (zero padded), then the error line replaces the CRC line
  const std::string head = "BEGIN climate_v2_202611.csv 300\n";
  assert(broken.out == head + std::string(300, '\0') + "ERR READ_FAILED\n");
  assert(broken.out.find("CRC32") == std::string::npos);
  assert(storage.unmounts == 2 && !storage.open);
  // No card
  FakeConsoleStorage empty;
  empty.card = false;
  FakeSink nocard;
  TestRunner::execute(empty, nocard, cmd);
  assert(nocard.out == "ERR NO_CARD\n" && empty.mounts == 1);
}

void testSerialDumpStopsOnShortWrite() {
  std::string data(1000, 'q');
  SerialCommand cmd;
  assert(parseText("DUMP climate_v2_202611.csv", cmd) == SerialParseStatus::OK);
  // The sink fails in the header, in the payload, and just before the CRC line
  const size_t header = strlen("BEGIN climate_v2_202611.csv 1000\n");
  const size_t limits[] = {5, header, header + 1, header + 300, header + 1000, header + 1000 + 3};
  for (size_t limit : limits) {
    FakeConsoleStorage storage;
    storage.add("climate_v2_202611.csv", data);
    FakeSink sink;
    sink.limit = limit;
    TestRunner::execute(storage, sink, cmd);
    assert(sink.out.size() == limit);
    assert(sink.out.find("CRC32 ") == std::string::npos || limit > header + 1000);
    assert(!storage.open && storage.mounts == 1 && storage.unmounts == 1);
  }
}

void testSerialListAndInfo() {
  FakeConsoleStorage storage;
  storage.add("climate_v2_202612.csv", std::string(20, 'b'));
  storage.add("climate_v2_202611.csv", std::string(10, 'a'));
  SerialCommand cmd;
  FakeSink list;
  assert(parseText("LIST", cmd) == SerialParseStatus::OK);
  TestRunner::execute(storage, list, cmd);
  assert(list.out ==
         "FILE climate_v2_202611.csv 10\nFILE climate_v2_202612.csv 20\nEND LIST 2\n");
  FakeSink info;
  assert(parseText("INFO", cmd) == SerialParseStatus::OK);
  TestRunner::execute(storage, info, cmd);
  assert(info.out == "INFO total=1000 used=250 files=2\n");
  assert(storage.mounts == 2 && storage.unmounts == 2);
  // Empty card
  FakeConsoleStorage none;
  FakeSink nolist;
  assert(parseText("LIST", cmd) == SerialParseStatus::OK);
  TestRunner::execute(none, nolist, cmd);
  assert(nolist.out == "END LIST 0\n");
}

void testSerialRejectKeepsCardUnmounted() {
  FakeConsoleStorage storage;
  FakeSink sink;
  TestRunner::reject(sink, SerialParseStatus::UNKNOWN);
  TestRunner::reject(sink, SerialParseStatus::TOO_LONG);
  TestRunner::reject(sink, SerialParseStatus::BAD_NAME);
  TestRunner::reject(sink, SerialParseStatus::EMPTY);
  assert(sink.out == "ERR UNKNOWN\nERR TOO_LONG\nERR BAD_NAME\n");
  assert(storage.mounts == 0);
  SerialCommand none;
  TestRunner::execute(storage, sink, none);
  assert(storage.mounts == 0);
}

void testSerialConfigParser() {
  SerialCommand cmd;
  assert(parseText("CONFIG", cmd) == SerialParseStatus::OK && cmd.kind == SerialCommandKind::CONFIG);
  assert(parseText("CONFIG\r\n", cmd) == SerialParseStatus::OK && cmd.kind == SerialCommandKind::CONFIG);
  assert(parseText("CONFIG now", cmd) == SerialParseStatus::UNKNOWN && cmd.kind == SerialCommandKind::NONE);
  assert(parseText("CONFIG ", cmd) == SerialParseStatus::UNKNOWN);
  assert(parseText("CONFIGX", cmd) == SerialParseStatus::UNKNOWN);
  assert(parseText("config", cmd) == SerialParseStatus::UNKNOWN);
  assert(parseText("CONFIG climate_v2_202611.csv", cmd) == SerialParseStatus::UNKNOWN);
}

void testSerialConfigLineFormat() {
  DeviceConfig cfg; // defaults
  char buf[DeviceConfigText::MAX_SIZE];
  const size_t n = DeviceConfigText::format(cfg, buf, sizeof(buf));
  assert(n > 0 && n == strlen(buf));
  const std::string text(buf, n);
  assert(text.find("temp_offset_c=-1.9\n") == 0);
  assert(text.find("\nrh_offset_pct=0.0\n") != std::string::npos);
  assert(text.find("\nfreeze_alert_limit_c=0.0\n") != std::string::npos);
  assert(text.find("\nmold_alert_limit_rh=70\n") != std::string::npos);
  assert(text.find("\nchime_volume_pct=30\n") != std::string::npos);
  assert(text.find("\nquiet_hours_start_hour=22\n") != std::string::npos);
  assert(text.find("\nquiet_hours_end_hour=7\n") != std::string::npos);
  assert(text.find("\nmic_spl_offset_db=0.0\n") != std::string::npos);
  assert(text.find("\nlog_retention_months=0\n") != std::string::npos);
  assert(text.find("\nnoise_alert_limit_db=0\n") != std::string::npos);
  assert(text.find("\nchime_alert_mask=31\n") != std::string::npos);
  assert(text.find("\nntp_sync_interval_h=24\n") != std::string::npos);
  for (int i = 1; i <= 3; ++i) {
    assert(text.find("\nslot" + std::to_string(i) + "_temp_offset_c=0.0\n") != std::string::npos);
    assert(text.find("\nslot" + std::to_string(i) + "_rh_offset_pct=0.0\n") != std::string::npos);
  }
  // 13 base keys plus six slot keys, one per line, no Wi-Fi key
  size_t lines = 0;
  for (char c : text) {
    lines += c == '\n' ? 1 : 0;
  }
  assert(text.find("\nwake_interval_s=60\n") != std::string::npos);
  assert(lines == 19);
  assert(text.find("wifi") == std::string::npos && text.find("ssid") == std::string::npos &&
         text.find("pass") == std::string::npos);
  // Too small a buffer gives 0 and an empty string
  char small[32];
  assert(DeviceConfigText::format(cfg, small, sizeof(small)) == 0 && small[0] == '\0');
  assert(DeviceConfigText::format(cfg, nullptr, 0) == 0);
}

void testSerialConfigFrameAndCrc() {
  FakeConsoleStorage storage;
  FakeSink sink;
  DeviceConfig cfg;
  cfg.temp_offset_c = 2.25f;
  cfg.noise_alert_limit_db = 65;
  TestRunner::showConfig(sink, cfg, true);
  const std::string& out = sink.out;
  const size_t crc_at = out.rfind("CRC32 ");
  assert(crc_at != std::string::npos && out.size() == crc_at + 6 + 8 + 1 && out.back() == '\n');
  const size_t source_at = out.find("SOURCE SD\n");
  assert(source_at != std::string::npos && source_at + 10 == crc_at);
  SerialCrc32 crc;
  crc.update(reinterpret_cast<const uint8_t*>(out.data()), crc_at);
  char expected[16];
  snprintf(expected, sizeof(expected), "%08lx", static_cast<unsigned long>(crc.value()));
  assert(out.substr(crc_at + 6, 8) == expected);
  FakeSink defaults;
  TestRunner::showConfig(defaults, DeviceConfig{}, false);
  assert(defaults.out.find("SOURCE DEFAULT\nCRC32 ") != std::string::npos);
  // The card stays untouched
  assert(storage.mounts == 0);
}

void testSerialConfigRunsWithoutMountingCard() {
  FakeConsoleStorage storage;
  FakeSink sink;
  SerialCommand cmd;
  assert(parseText("CONFIG", cmd) == SerialParseStatus::OK);
  TestRunner::execute(storage, sink, cmd); // a CONFIG command never reaches the card
  assert(storage.mounts == 0 && storage.unmounts == 0 && sink.out.empty());
}

void testSerialConfigRejectedLines() {
  ConfigParseResult r;
  const DeviceConfig cfg = parseText("chime_volume_pct=x\nunknown=1\nmold_alert_limit_rh=1\n", &r);
  FakeSink sink;
  TestRunner::showConfig(sink, cfg, true, r.rejects());
  const std::string& out = sink.out;
  const size_t source_at = out.find("SOURCE SD\n");
  const size_t crc_at = out.rfind("CRC32 ");
  assert(source_at != std::string::npos && crc_at != std::string::npos);
  assert(out.substr(source_at, crc_at - source_at) == "SOURCE SD\nREJECTED line=1\nREJECTED line=3\n");
  SerialCrc32 crc;
  crc.update(reinterpret_cast<const uint8_t*>(out.data()), crc_at);
  char expected[16];
  snprintf(expected, sizeof(expected), "%08lx", static_cast<unsigned long>(crc.value()));
  assert(out.substr(crc_at + 6, 8) == expected && out.size() == crc_at + 6 + 8 + 1);
  // Four lines at most, even with six rejects
  ConfigParseResult six;
  parseText("a\nb\nc\nd\ne\nf\n", &six);
  FakeSink capped;
  TestRunner::showConfig(capped, cfg, true, six.rejects());
  size_t count = 0;
  for (size_t at = capped.out.find("REJECTED"); at != std::string::npos; at = capped.out.find("REJECTED", at + 1)) ++count;
  assert(count == 4 && capped.out.find("REJECTED line=5") == std::string::npos);
  // Defaults never list rejected lines
  FakeSink defaults;
  TestRunner::showConfig(defaults, cfg, false, six.rejects());
  assert(defaults.out.find("REJECTED") == std::string::npos);
  // No error: no REJECTED line
  FakeSink clean;
  TestRunner::showConfig(clean, cfg, true);
  assert(clean.out.find("REJECTED") == std::string::npos);
}

void testSerialConfigRoundTrip() {
  const char* ini =
      "temp_offset_c=-3.25\nrh_offset_pct=4.5\nfreeze_alert_limit_c=-7.5\nmold_alert_limit_rh=85\n"
      "chime_volume_pct=100\nquiet_hours_start_hour=0\nquiet_hours_end_hour=23\nmic_spl_offset_db=-12.75\n"
      "log_retention_months=120\nnoise_alert_limit_db=40\nchime_alert_mask=5\nntp_sync_interval_h=168\n"
      "slot1_temp_offset_c=0.5\nslot2_temp_offset_c=-5.0\nslot3_temp_offset_c=4.75\n"
      "slot1_rh_offset_pct=-10.0\nslot2_rh_offset_pct=3.5\nslot3_rh_offset_pct=10.0\n";
  DeviceConfig original;
  DeviceConfigParser::parse(ini, strlen(ini), original);
  FakeSink sink;
  TestRunner::showConfig(sink, original, true);
  // Parse only the key=value block; the SOURCE and CRC32 lines follow it.
  const size_t source_at = sink.out.find("SOURCE ");
  assert(source_at != std::string::npos);
  DeviceConfig back;
  const ConfigParseResult result = DeviceConfigParser::parse(sink.out.data(), source_at, back);
  assert(result.file_ok && result.rejected == 0 && result.keys_set == 19);
  assert(back.temp_offset_c == original.temp_offset_c && back.rh_offset_pct == original.rh_offset_pct);
  assert(back.freeze_alert_limit_c == original.freeze_alert_limit_c);
  assert(back.mold_alert_limit_rh == 85 && back.chime_volume_pct == 100);
  assert(back.quiet_hours_start_hour == 0 && back.quiet_hours_end_hour == 23);
  assert(back.mic_spl_offset_db == original.mic_spl_offset_db);
  assert(back.log_retention_months == 120 && back.noise_alert_limit_db == 40);
  assert(back.chime_alert_mask == 5 && back.ntp_sync_interval_h == 168);
  for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
    assert(back.slot_temp_offset_c[i] == original.slot_temp_offset_c[i]);
    assert(back.slot_rh_offset_pct[i] == original.slot_rh_offset_pct[i]);
  }
  // The defaults round trip too
  FakeSink dsink;
  TestRunner::showConfig(dsink, DeviceConfig{}, false);
  DeviceConfig dback;
  DeviceConfigParser::parse(dsink.out.data(), dsink.out.find("SOURCE "), dback);
  assert(dback.isDefault());
}

void testSerialConfigNegativeSmallOffset() {
  DeviceConfig cfg;
  cfg.temp_offset_c = -0.004f; // prints as "-0.0"
  cfg.rh_offset_pct = -10.0f;
  FakeSink sink;
  TestRunner::showConfig(sink, cfg, true);
  assert(sink.out.find("temp_offset_c=-0.0\n") == 0);
  DeviceConfig back;
  const ConfigParseResult result = DeviceConfigParser::parse(sink.out.data(), sink.out.find("SOURCE "), back);
  assert(result.rejected == 0 && back.temp_offset_c == 0.0f && back.rh_offset_pct == -10.0f);
}

void testSerialConfigStopsOnShortWrite() {
  FakeSink sink;
  sink.limit = 10;
  TestRunner::showConfig(sink, DeviceConfig{}, true);
  assert(sink.out.size() == 10 && sink.out.find("CRC32") == std::string::npos);
}

void testSerialConsoleUsbGate() {
  BatteryReading usb;
  usb.valid = true;
  usb.usb_powered = true;
  BatteryReading battery = usb;
  battery.usb_powered = false;
  BatteryReading invalid;
  invalid.valid = false;
  invalid.usb_powered = true;
  assert(PowerManager::serialConsoleAllowed(usb));
  assert(!PowerManager::serialConsoleAllowed(battery));
  assert(!PowerManager::serialConsoleAllowed(invalid));
}

void testNoiseAlertLimitEdges() {
  using E = NoiseAlertEvaluator;
  // Range of the limit: 0 is off, 40 to 100 is usable, everything else is not
  assert(E::limitValid(0) && E::limitValid(40) && E::limitValid(100));
  assert(!E::limitValid(1) && !E::limitValid(39) && !E::limitValid(101) && !E::limitValid(255));
  // Edge values: the alert needs a level above the limit, equal is not enough
  assert(E::evaluate(ALERT_NONE, true, 40, 40) == ALERT_NONE);
  assert(E::evaluate(ALERT_NONE, true, 41, 40) == ALERT_NOISE_HIGH);
  assert(E::evaluate(ALERT_NONE, true, 100, 100) == ALERT_NONE);
  assert(E::evaluate(ALERT_NONE, true, 101, 100) == ALERT_NOISE_HIGH);
  // An out-of-range limit is off, also when the latch was set
  assert(E::evaluate(ALERT_NONE, true, 120, 101) == ALERT_NONE);
  assert(E::evaluate(ALERT_NONE, true, 120, 39) == ALERT_NONE);
  assert(E::evaluate(ALERT_NOISE_HIGH, true, 120, 255) == ALERT_NONE);
}

void testNoiseAlertDisabledByDefault() {
  assert(DeviceConfig{}.noise_alert_limit_db == 0);
  assert(NoiseAlertEvaluator::evaluate(ALERT_NONE, true, 100, DeviceConfig{}.noise_alert_limit_db) == ALERT_NONE);
  // Turning the limit off clears a set latch and keeps the other bits
  assert(NoiseAlertEvaluator::evaluate(ALERT_NOISE_HIGH | ALERT_FREEZER, true, 100, 0) == ALERT_FREEZER);
  assert(NoiseAlertEvaluator::evaluate(ALERT_FREEZER, false, 0, 0) == ALERT_FREEZER);
}

void testNoiseAlertHysteresis() {
  using E = NoiseAlertEvaluator;
  uint8_t mask = ALERT_NONE;
  mask = E::evaluate(mask, true, 71, 70);
  assert(mask == ALERT_NOISE_HIGH);
  // The latch holds inside the 5 dB band, also at the limit itself
  mask = E::evaluate(mask, true, 70, 70);
  assert(mask == ALERT_NOISE_HIGH);
  mask = E::evaluate(mask, true, 66, 70);
  assert(mask == ALERT_NOISE_HIGH);
  // 65 dB is exactly 5 dB below the limit: release
  assert(E::evaluate(mask, true, 65, 70) == ALERT_NONE);
  // Without a latch, a level in the band does not switch the alert on
  assert(E::evaluate(ALERT_NONE, true, 68, 70) == ALERT_NONE);
  // Other alert bits stay unchanged
  assert(E::evaluate(ALERT_HUMIDITY, true, 80, 70) == (ALERT_HUMIDITY | ALERT_NOISE_HIGH));
  assert(E::evaluate(ALERT_HUMIDITY | ALERT_NOISE_HIGH, true, 50, 70) == ALERT_HUMIDITY);
  // withClimate() keeps the noise bit and takes the climate bits from the climate evaluator
  assert(AlertEvaluator::withClimate(ALERT_NOISE_HIGH | ALERT_FREEZER, ALERT_HUMIDITY) ==
         (ALERT_NOISE_HIGH | ALERT_HUMIDITY));
  assert(AlertEvaluator::withClimate(ALERT_NONE, ALERT_NOISE_HIGH) == ALERT_NONE);
}

void testNoiseAlertInvalidReading() {
  using E = NoiseAlertEvaluator;
  // A failed window never switches the alert on or off
  assert(E::evaluate(ALERT_NONE, false, 100, 50) == ALERT_NONE);
  assert(E::evaluate(ALERT_NOISE_HIGH, false, 0, 50) == ALERT_NOISE_HIGH);
  // A held latch gives no second chime when a valid loud window follows
  const uint8_t held = E::evaluate(ALERT_NOISE_HIGH, false, 0, 50);
  const uint8_t again = E::evaluate(held, true, 90, 50);
  assert(AlertEvaluator::chimeBits(false, held, again, false) == ALERT_NONE);
}

void testNoiseAlertChimeSuppression() {
  // The chime sounds once, when the bit switches on
  assert(AlertEvaluator::chimeBits(false, ALERT_NONE, ALERT_NOISE_HIGH, false) == ALERT_NOISE_HIGH);
  assert(AlertEvaluator::chimeBits(false, ALERT_NOISE_HIGH, ALERT_NOISE_HIGH, false) == ALERT_NONE);
  assert(AlertEvaluator::chimeBits(false, ALERT_NOISE_HIGH, ALERT_NONE, false) == ALERT_NONE);
  // Mute and quiet hours suppress the sound
  assert(AlertEvaluator::chimeBits(true, ALERT_NONE, ALERT_NOISE_HIGH, false) == ALERT_NONE);
  const QuietHoursWindow w = QuietHoursWindow::fromHours(22, 7);
  assert(AlertEvaluator::chimeBits(false, ALERT_NONE, ALERT_NOISE_HIGH, w.isActive(makeRtc(23, 0))) ==
         ALERT_NONE);
  assert(AlertEvaluator::chimeBits(false, ALERT_NONE, ALERT_NOISE_HIGH, w.isActive(makeRtc(12, 0))) ==
         ALERT_NOISE_HIGH);
  // The sound: a pattern of its own, played together with the climate chimes
  const ChimePattern noise = ChimeSynth::pattern(ALERT_NOISE_HIGH);
  assert(noise.notes != nullptr && noise.count > 0);
  assert(noise.notes != ChimeSynth::pattern(ALERT_FREEZER).notes);
  assert(noise.notes != ChimeSynth::pattern(ALERT_HUMIDITY).notes);
  struct Hal {
    int samples = 0, amp_on = 0, amp_off = 0, ends = 0;
    bool beginBus() { return true; }
    bool configureCodec() { return true; }
    void setAmplifier(bool on) { on ? ++amp_on : ++amp_off; }
    bool write(const int16_t*, size_t n) { samples += static_cast<int>(n); return true; }
    void drain() {}
    void shutdownCodec() {}
    void endBus() { ++ends; }
  };
  Hal alone;
  assert(AudioSequencer::play(alone, ALERT_NOISE_HIGH, 30));
  assert(alone.samples == static_cast<int>(ChimeSynth::totalSamples(noise)));
  assert(alone.amp_on == 1 && alone.amp_off == 1 && alone.ends == 1);
  Hal all;
  assert(AudioSequencer::play(all, ALERT_MASK_ALL, 30));
  assert(all.samples == static_cast<int>(ChimeSynth::totalSamples(noise) +
                                        ChimeSynth::totalSamples(ChimeSynth::pattern(ALERT_FREEZER)) +
                                        ChimeSynth::totalSamples(ChimeSynth::pattern(ALERT_HUMIDITY)) +
                                        ChimeSynth::totalSamples(ChimeSynth::pattern(ALERT_SENSOR_LOST)) +
                                        ChimeSynth::totalSamples(ChimeSynth::pattern(ALERT_CHARGE_FULL))));
  // Volume 0 stays silent
  Hal silent;
  assert(!AudioSequencer::play(silent, ALERT_NOISE_HIGH, 0) && silent.samples == 0);
}

void testNoiseAlertConfigKey() {
  ConfigParseResult r;
  assert(parseText("", &r).noise_alert_limit_db == 0);
  assert(parseText("noise_alert_limit_db=40\n").noise_alert_limit_db == 40);
  assert(parseText("noise_alert_limit_db=100\n").noise_alert_limit_db == 100);
  assert(parseText("noise_alert_limit_db = 75\r\n").noise_alert_limit_db == 75);
  assert(parseText("noise_alert_limit_db=0\n").noise_alert_limit_db == 0);
  // Out-of-range, malformed and fractional values are rejected; the previous value stays
  const char* bad[] = {"noise_alert_limit_db=39\n", "noise_alert_limit_db=1\n", "noise_alert_limit_db=101\n",
                       "noise_alert_limit_db=-5\n", "noise_alert_limit_db=abc\n", "noise_alert_limit_db=70.5\n",
                       "noise_alert_limit_db=\n"};
  for (const char* text : bad) {
    const DeviceConfig c = parseText(text, &r);
    assert(c.noise_alert_limit_db == 0 && r.rejected == 1 && r.keys_set == 0);
  }
  const DeviceConfig kept = parseText("noise_alert_limit_db=80\nnoise_alert_limit_db=20\n", &r);
  assert(kept.noise_alert_limit_db == 80 && r.rejected == 1 && r.keys_set == 1);
  // The ninth key counts as a key of its own
  parseText("temp_offset_c=1\nnoise_alert_limit_db=60\nlog_retention_months=3\n", &r);
  assert(r.keys_set == 3 && DeviceConfigParser::KEY_COUNT == 9);
  // valid() and isDefault() cover the new field
  DeviceConfig c;
  assert(c.isDefault() && c.valid());
  c.noise_alert_limit_db = 39;
  assert(!c.valid() && !c.isDefault());
  c.noise_alert_limit_db = 101;
  assert(!c.valid());
  c.noise_alert_limit_db = 40;
  assert(c.valid() && !c.isDefault());
  c.noise_alert_limit_db = 100;
  assert(c.valid());
}

void testNoiseAlertStatePersistence() {
  PersistentState s;
  s.config = parseText("noise_alert_limit_db=72\n");
  s.alert_mask = ALERT_NOISE_HIGH | ALERT_FREEZER;
  s.noise_band = NoiseBand::LOUD;
  s.muted = true;
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  PersistentState r;
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(r.alert_mask == (ALERT_NOISE_HIGH | ALERT_FREEZER) && r.noise_band == NoiseBand::LOUD && r.muted);
  assert(r.config.noise_alert_limit_db == 72);
  for (uint8_t mask = 0; mask <= ALERT_MASK_ALL; ++mask) {
    s.alert_mask = mask;
    assert(PowerManager::serialize(s, buf, sizeof(buf)));
    assert(PowerManager::deserialize(buf, sizeof(buf), r) && r.alert_mask == mask);
  }
  // A limit outside the range is rejected, even with a valid CRC
  s.alert_mask = ALERT_NONE;
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  const size_t limit_pos = PowerManager::SERIALIZED_SIZE - 4 - PowerManager::REJECTS_SIZE - PowerManager::MACS_SIZE - PowerManager::NAMES_SIZE - 10 - 24;
  const uint8_t bad_limits[] = {1, 39, 101, 255};
  for (uint8_t limit : bad_limits) {
    uint8_t bad[PowerManager::SERIALIZED_SIZE];
    memcpy(bad, buf, sizeof(bad));
    bad[limit_pos] = limit;
    const uint32_t crc = PowerManager::crc32(bad, sizeof(bad) - 4);
    for (int i = 0; i < 4; ++i) bad[sizeof(bad) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
    assert(!PowerManager::deserialize(bad, sizeof(bad), r));
  }
  // An invalid band value in the audio byte is rejected
  uint8_t bad[PowerManager::SERIALIZED_SIZE];
  memcpy(bad, buf, sizeof(bad));
  bad[PowerManager::SERIALIZED_SIZE - 4 - PowerManager::CONFIG_SIZE - PowerManager::NTP_SIZE - 1] = 0xC0;
  const uint32_t crc = PowerManager::crc32(bad, sizeof(bad) - 4);
  for (int i = 0; i < 4; ++i) bad[sizeof(bad) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  assert(!PowerManager::deserialize(bad, sizeof(bad), r));
}

void testChargeFullChime() {
  using E = AlertEvaluator;
  using B = BatteryMonitor;
  auto reading = [](int pct, bool usb) {
    BatteryReading r;
    r.valid = true;
    r.percentage = static_cast<int8_t>(pct);
    r.usb_powered = usb;
    return r;
  };
  // Trigger at 100 % on USB power, not below
  assert(B::updateChargeFull(reading(100, true), false));
  assert(!B::updateChargeFull(reading(99, true), false));
  // 99 % to 100 % noise never replays: the latch holds down to 95 %
  bool full = B::updateChargeFull(reading(100, true), false);
  for (int pct : {99, 100, 98, 100, 96, 95}) {
    full = B::updateChargeFull(reading(pct, true), full);
    assert(full);
  }
  // Release below 95 %, then 100 % triggers again
  assert(!B::updateChargeFull(reading(94, true), true));
  // No trigger on battery power, and the latch ends there
  assert(!B::updateChargeFull(reading(100, false), false));
  assert(!B::updateChargeFull(reading(100, false), true));
  // An invalid reading neither sets nor clears the latch
  BatteryReading bad;
  assert(!B::updateChargeFull(bad, false) && B::updateChargeFull(bad, true));
  assert(!B::updateChargeFull(reading(-1, true), false) && B::updateChargeFull(reading(-1, true), true));
  // A stale percentage of 100 on an invalid reading never sets the latch
  BatteryReading stale = reading(100, true);
  stale.valid = false;
  assert(!B::updateChargeFull(stale, false) && B::updateChargeFull(stale, true));

  // Step: one chime on the edge, none while the bit stays set
  E::Step st = E::chargeStep(false, ALERT_NONE, reading(100, true), false, 31);
  assert(st.mask == ALERT_CHARGE_FULL && st.chime == ALERT_CHARGE_FULL);
  st = E::chargeStep(false, st.mask, reading(99, true), false, 31);
  assert(st.mask == ALERT_CHARGE_FULL && st.chime == ALERT_NONE);
  st = E::chargeStep(false, st.mask, reading(100, true), false, 31);
  assert(st.chime == ALERT_NONE);
  st = E::chargeStep(false, st.mask, reading(94, true), false, 31);
  assert(st.mask == ALERT_NONE && st.chime == ALERT_NONE);
  st = E::chargeStep(false, st.mask, reading(100, true), false, 31);
  assert(st.chime == ALERT_CHARGE_FULL);
  // Other bits stay unchanged
  st = E::chargeStep(false, ALERT_FREEZER | ALERT_NOISE_HIGH, reading(100, true), false, 31);
  assert(st.mask == (ALERT_FREEZER | ALERT_NOISE_HIGH | ALERT_CHARGE_FULL) && st.chime == ALERT_CHARGE_FULL);
  st = E::chargeStep(false, ALERT_FREEZER | ALERT_CHARGE_FULL, reading(90, true), false, 31);
  assert(st.mask == ALERT_FREEZER);
  // Climate and noise steps keep the charge bit
  st = E::climateStep(false, ALERT_CHARGE_FULL, ALERT_HUMIDITY, false, 31);
  assert(st.mask == (ALERT_CHARGE_FULL | ALERT_HUMIDITY) && st.chime == ALERT_HUMIDITY);
  st = NoiseAlertEvaluator::step(false, ALERT_CHARGE_FULL, true, 90, 80, false, 31);
  assert(st.mask == (ALERT_CHARGE_FULL | ALERT_NOISE_HIGH) && st.chime == ALERT_NOISE_HIGH);
  // Mask bit 4: only a set bit sounds
  assert(E::chargeStep(false, ALERT_NONE, reading(100, true), false, 15).chime == ALERT_NONE);
  assert(E::chargeStep(false, ALERT_NONE, reading(100, true), false, 15).mask == ALERT_CHARGE_FULL);
  assert(E::chargeStep(false, ALERT_NONE, reading(100, true), false, ALERT_CHARGE_FULL).chime == ALERT_CHARGE_FULL);
  // Mute and quiet hours suppress the sound; the mask is stored, so no replay follows
  const QuietHoursWindow w = QuietHoursWindow::fromHours(22, 7);
  st = E::chargeStep(true, ALERT_NONE, reading(100, true), false, 31);
  assert(st.mask == ALERT_CHARGE_FULL && st.chime == ALERT_NONE);
  assert(E::chargeStep(false, st.mask, reading(100, true), false, 31).chime == ALERT_NONE);
  st = E::chargeStep(false, ALERT_NONE, reading(100, true), w.isActive(makeRtc(23, 0)), 31);
  assert(st.mask == ALERT_CHARGE_FULL && st.chime == ALERT_NONE);
  assert(E::chargeStep(false, ALERT_NONE, reading(100, true), w.isActive(makeRtc(12, 0)), 31).chime ==
         ALERT_CHARGE_FULL);

  // Pattern: two falling notes, distinct from the other chimes
  const ChimePattern p = ChimeSynth::pattern(ALERT_CHARGE_FULL);
  assert(p.notes != nullptr && p.count == 3 && p.notes[0].freq_hz > p.notes[2].freq_hz);
  assert(p.notes[1].freq_hz == 0 && p.notes[0].freq_hz != 0 && p.notes[2].freq_hz != 0);
  assert(p.notes != ChimeSynth::pattern(ALERT_HUMIDITY).notes);
  struct Hal {
    int samples = 0, amp_on = 0, amp_off = 0, ends = 0;
    bool beginBus() { return true; }
    bool configureCodec() { return true; }
    void setAmplifier(bool on) { on ? ++amp_on : ++amp_off; }
    bool write(const int16_t*, size_t n) { samples += static_cast<int>(n); return true; }
    void drain() {}
    void shutdownCodec() {}
    void endBus() { ++ends; }
  };
  Hal hal;
  assert(AudioSequencer::play(hal, ALERT_CHARGE_FULL, 30));
  assert(static_cast<uint32_t>(hal.samples) == ChimeSynth::totalSamples(p));
  assert(hal.amp_on == 1 && hal.amp_off == 1 && hal.ends == 1);
  assert(ALERT_MASK_ALL == 31);
}

void testChimeAlertMask() {
  using E = AlertEvaluator;
  using N = NoiseAlertEvaluator;
  const uint8_t bits[] = {ALERT_FREEZER, ALERT_HUMIDITY, ALERT_NOISE_HIGH, ALERT_SENSOR_LOST, ALERT_CHARGE_FULL};
  const QuietHoursWindow w = QuietHoursWindow::fromHours(22, 7);
  // Default: all five chimes are on
  ConfigParseResult r;
  assert(DeviceConfig{}.chime_alert_mask == 31 && parseText("", &r).chime_alert_mask == 31);
  assert(PersistentState{}.config.chime_alert_mask == 31 && DeviceConfig{}.isDefault());
  for (uint8_t b : bits) {
    assert(E::chimeBits(false, ALERT_NONE, b, false, 31) == b);
  }
  // Each single bit sounds only its own alert
  for (uint8_t m : bits) {
    for (uint8_t b : bits) {
      assert(E::chimeBits(false, ALERT_NONE, b, false, m) == (b == m ? b : ALERT_NONE));
    }
  }
  // Combination and zero
  assert(E::chimeBits(false, ALERT_NONE, ALERT_MASK_ALL, false, ALERT_FREEZER | ALERT_NOISE_HIGH) ==
         (ALERT_FREEZER | ALERT_NOISE_HIGH));
  assert(E::chimeBits(false, ALERT_NONE, ALERT_MASK_ALL, false, 0) == ALERT_NONE);
  // The mask is stored although the sound is off
  E::Step st = E::step(false, ALERT_NONE, ALERT_FREEZER | ALERT_SENSOR_LOST, false, ALERT_FREEZER);
  assert(st.mask == (ALERT_FREEZER | ALERT_SENSOR_LOST) && st.chime == ALERT_FREEZER);
  st = E::climateStep(false, ALERT_NOISE_HIGH, ALERT_HUMIDITY, false, 0);
  assert(st.mask == (ALERT_NOISE_HIGH | ALERT_HUMIDITY) && st.chime == ALERT_NONE);
  st = N::step(false, ALERT_NONE, true, 90, 80, false, 0);
  assert(st.mask == ALERT_NOISE_HIGH && st.chime == ALERT_NONE);
  st = N::step(false, ALERT_NONE, true, 90, 80, false, ALERT_NOISE_HIGH);
  assert(st.chime == ALERT_NOISE_HIGH);
  // Mute and quiet hours still win over a set bit
  assert(E::step(true, ALERT_NONE, ALERT_FREEZER, false, 31).chime == ALERT_NONE);
  assert(E::step(false, ALERT_NONE, ALERT_FREEZER, w.isActive(makeRtc(23, 0)), 31).chime == ALERT_NONE);
  assert(E::step(false, ALERT_NONE, ALERT_FREEZER, w.isActive(makeRtc(12, 0)), 31).chime == ALERT_FREEZER);
  // No replay after the bit is set again
  st = E::step(false, ALERT_NONE, ALERT_HUMIDITY, false, 0);
  assert(st.chime == ALERT_NONE);
  assert(E::step(false, st.mask, ALERT_HUMIDITY, false, 31).chime == ALERT_NONE);
  // A new edge after the bit is set again sounds
  st = E::step(false, st.mask, ALERT_NONE, false, 31);
  assert(E::step(false, st.mask, ALERT_HUMIDITY, false, 31).chime == ALERT_HUMIDITY);
  // Parser: valid values, range and rejection
  for (int v = 0; v <= 31; ++v) {
    char line[40];
    snprintf(line, sizeof(line), "chime_alert_mask=%d\n", v);
    assert(parseText(line, &r).chime_alert_mask == v && r.keys_set == 1 && r.rejected == 0);
  }
  const char* bad[] = {"chime_alert_mask=32\n", "chime_alert_mask=255\n", "chime_alert_mask=-1\n",
                       "chime_alert_mask=abc\n", "chime_alert_mask=3.5\n", "chime_alert_mask=\n"};
  for (const char* t : bad) {
    const DeviceConfig c = parseText(t, &r);
    assert(c.chime_alert_mask == 31 && r.rejected == 1 && r.keys_set == 0);
  }
  assert(parseText("chime_alert_mask=5\nchime_alert_mask=99\n", &r).chime_alert_mask == 5 && r.rejected == 1);
  DeviceConfig inv;
  inv.chime_alert_mask = 32;
  assert(!inv.valid());
  inv.chime_alert_mask = 0;
  assert(inv.valid() && !inv.isDefault());
  // RTC round trip for every value
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  for (int v = 0; v <= 31; ++v) {
    PersistentState s;
    s.config.chime_alert_mask = static_cast<uint8_t>(v);
    PersistentState out;
    assert(PowerManager::serialize(s, buf, sizeof(buf)));
    assert(PowerManager::deserialize(buf, sizeof(buf), out) && out.config.chime_alert_mask == v);
  }
  // An out-of-range byte is rejected even with a valid CRC
  PersistentState s;
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  uint8_t badbuf[PowerManager::SERIALIZED_SIZE];
  memcpy(badbuf, buf, sizeof(badbuf));
  badbuf[PowerManager::SERIALIZED_SIZE - 4 - PowerManager::REJECTS_SIZE - PowerManager::MACS_SIZE - PowerManager::NAMES_SIZE - 9 - 24] = 32;
  const uint32_t crc = PowerManager::crc32(badbuf, sizeof(badbuf) - 4);
  for (int i = 0; i < 4; ++i) badbuf[sizeof(badbuf) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  PersistentState out;
  assert(!PowerManager::deserialize(badbuf, sizeof(badbuf), out));
  // An old record version is rejected
  memcpy(badbuf, buf, sizeof(badbuf));
  badbuf[4] = PowerManager::STATE_VERSION - 1;
  assert(!PowerManager::deserialize(badbuf, sizeof(badbuf), out));
}

void testNoiseAlertStepWiring() {
  using N = NoiseAlertEvaluator;
  const QuietHoursWindow w = QuietHoursWindow::fromHours(22, 7);
  // Loud window: the bit is stored and the chime sounds
  AlertEvaluator::Step st = N::step(false, ALERT_NONE, true, 80, 70, false);
  assert(st.mask == ALERT_NOISE_HIGH && st.chime == ALERT_NOISE_HIGH);
  // Muted: the bit is stored, no sound, and no replay after unmuting
  st = N::step(true, ALERT_NONE, true, 80, 70, false);
  assert(st.mask == ALERT_NOISE_HIGH && st.chime == ALERT_NONE);
  assert(N::step(false, st.mask, true, 80, 70, false).chime == ALERT_NONE);
  // Quiet hours: the same
  st = N::step(false, ALERT_NONE, true, 80, 70, w.isActive(makeRtc(23, 0)));
  assert(st.mask == ALERT_NOISE_HIGH && st.chime == ALERT_NONE);
  assert(N::step(false, st.mask, true, 80, 70, false).chime == ALERT_NONE);
  // Climate bits stay through a noise step, also while a climate alert is active
  st = N::step(false, ALERT_FREEZER, true, 80, 70, false);
  assert(st.mask == (ALERT_FREEZER | ALERT_NOISE_HIGH) && st.chime == ALERT_NOISE_HIGH);
  // Release at -5 dB clears the bit and gives no sound
  st = N::step(false, ALERT_NOISE_HIGH | ALERT_FREEZER, true, 65, 70, false);
  assert(st.mask == ALERT_FREEZER && st.chime == ALERT_NONE);
  // A failed window holds the mask and gives no sound
  st = N::step(false, ALERT_NOISE_HIGH, false, 0, 70, false);
  assert(st.mask == ALERT_NOISE_HIGH && st.chime == ALERT_NONE);
  // Climate step keeps the noise bit and sounds only the new climate alert
  st = AlertEvaluator::climateStep(false, ALERT_NOISE_HIGH, ALERT_HUMIDITY, false);
  assert(st.mask == (ALERT_NOISE_HIGH | ALERT_HUMIDITY) && st.chime == ALERT_HUMIDITY);
  st = AlertEvaluator::climateStep(false, ALERT_NOISE_HIGH, ALERT_NONE, false);
  assert(st.mask == ALERT_NOISE_HIGH && st.chime == ALERT_NONE);
  st = AlertEvaluator::climateStep(true, ALERT_NONE, ALERT_FREEZER, false);
  assert(st.mask == ALERT_FREEZER && st.chime == ALERT_NONE);
}

void testEmptyBatteryProtectionLatch() {
  BatteryReading r;
  r.valid = true;
  r.usb_powered = false;
  // Trigger below 3.30 V, no trigger at 3.30 V
  r.voltage_v = 3.29f;
  assert(BatteryMonitor::updateEmptyProtection(r, false));
  r.voltage_v = 3.30f;
  assert(!BatteryMonitor::updateEmptyProtection(r, false));
  r.voltage_v = 3.60f;
  assert(!BatteryMonitor::updateEmptyProtection(r, false));
  // Hysteresis: a set latch holds below 3.50 V and ends at 3.50 V
  r.voltage_v = 3.40f;
  assert(BatteryMonitor::updateEmptyProtection(r, true));
  r.voltage_v = 3.49f;
  assert(BatteryMonitor::updateEmptyProtection(r, true));
  r.voltage_v = 3.50f;
  assert(!BatteryMonitor::updateEmptyProtection(r, true));
  // USB power ends the protection at any voltage
  r.voltage_v = 3.10f;
  r.usb_powered = true;
  assert(!BatteryMonitor::updateEmptyProtection(r, true));
  assert(!BatteryMonitor::updateEmptyProtection(r, false));
  // An invalid reading never sets the latch and holds a set latch
  BatteryReading bad;
  assert(!BatteryMonitor::updateEmptyProtection(bad, false));
  assert(BatteryMonitor::updateEmptyProtection(bad, true));
  const BatteryReading none = BatteryMonitor::decodeMillivolts(0);
  assert(!none.valid && !BatteryMonitor::updateEmptyProtection(none, false));
}

void testEmptyBatteryProtectionPlan() {
  BatteryReading b;
  b.valid = true;
  assert(PowerManager::modulesAllowed(b));
  b.empty_protection = true;
  assert(!PowerManager::modulesAllowed(b));
  // The warning is drawn once, when the latch sets
  assert(PowerManager::needsProtectionRender(false, true));
  assert(!PowerManager::needsProtectionRender(true, true));
  assert(!PowerManager::needsProtectionRender(false, false));
  assert(!PowerManager::needsProtectionRender(true, false));
  // One hour timer and KEY wakeup, unless the button is held
  assert(PowerManager::PROTECTED_SLEEP_US == 3600ULL * 1000000ULL);
  WakeupPlan plan = PowerManager::planProtectedSleep(false);
  assert(plan.timer_us == 3600000000ULL && plan.enable_key_wakeup);
  plan = PowerManager::planProtectedSleep(true);
  assert(plan.timer_us == 3600000000ULL && !plan.enable_key_wakeup);
}

void testEmptyBatteryProtectionRtcRoundTrip() {
  PersistentState s;
  s.battery.valid = true;
  s.battery.voltage_v = 3.25f;
  s.battery.percentage = 0;
  s.battery.low_battery = true;
  s.battery.empty_protection = true;
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  PersistentState r;
  assert(PowerManager::restore(WakeupCause::TIMER, buf, sizeof(buf), r));
  assert(r.battery.empty_protection && r.battery.low_battery);
  assert(PowerManager::restore(WakeupCause::KEY_BUTTON, buf, sizeof(buf), r));
  assert(r.battery.empty_protection);
  // A power-on keeps no state: the latch starts off
  assert(!PowerManager::restore(WakeupCause::POWER_ON, buf, sizeof(buf), r));
  assert(!r.battery.empty_protection);
  // The flag is independent of the other battery flags
  s.battery.low_battery = false;
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(r.battery.empty_protection && !r.battery.low_battery);
  s.battery.empty_protection = false;
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(!r.battery.empty_protection);
  // An unknown flag bit is rejected
  s.battery.empty_protection = true;
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  buf[6] |= 16;
  const uint32_t crc = PowerManager::crc32(buf, sizeof(buf) - 4);
  for (int i = 0; i < 4; ++i) buf[sizeof(buf) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  assert(!PowerManager::deserialize(buf, sizeof(buf), r));
}

void testEmptyBatteryProtectionRendering() {
  using namespace rlcd_panel::preview;
  uint8_t index = PREVIEW_SCENE_COUNT;
  for (uint8_t i = 0; i < PREVIEW_SCENE_COUNT; ++i) {
    if (PREVIEW_SCENES[i].battery_empty) {
      index = i;
    }
  }
  assert(index < PREVIEW_SCENE_COUNT);
  St7305Framebuffer warn;
  warn.clear(true);
  renderScene(warn, PREVIEW_SCENES[index].mode, false, false, false, true);
  // Frame at the border, white interior, ink in the title band, no dashboard header bar
  assert(warn.getPixel(0, 0));
  assert(!warn.getPixel(3, 3));
  assert(!warn.getPixel(20, 20));
  size_t title_ink = 0;
  for (uint16_t y = 70; y < 100; ++y) {
    for (uint16_t x = 40; x < 360; ++x) {
      title_ink += warn.getPixel(x, y) ? 1 : 0;
    }
  }
  assert(title_ink > 200);
  // Every display mode shows the warning while the latch is set
  St7305Framebuffer a, b;
  BatteryReading bat;
  bat.valid = true;
  bat.voltage_v = 3.2f;
  bat.empty_protection = true;
  Shtc3Reading onboard;
  SystemInfo info;
  DashboardView::renderMode(a, DisplayMode::DASHBOARD, onboard, bat, nullptr, 0, info, "12:00", 0);
  DashboardView::renderMode(b, DisplayMode::SYSTEM_DIAGNOSTICS, onboard, bat, nullptr, 0, info, "12:00", 0);
  for (uint16_t y = 0; y < St7305Framebuffer::HEIGHT; ++y) {
    for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
      assert(a.getPixel(x, y) == b.getPixel(x, y));
      assert(a.getPixel(x, y) == warn.getPixel(x, y) || y >= 180);
    }
  }
}

// ---- Daily climate summary file ----

namespace {

// Pushes the tenths of a value to a temperature or humidity history through the public API.
ClimateHistory dailyHistory() {
  ClimateHistory h;
  h.temperatures[0].pushCelsius(20.0f);
  h.temperatures[0].pushCelsius(22.5f);
  h.temperatures[0].pushCelsius(21.0f);
  h.slots[0].pushPercent(40.0f);
  h.slots[0].pushPercent(50.0f);
  h.slots[0].pushPercent(45.0f);
  h.temperatures[1].pushCelsius(-3.5f); // one sample is enough for a summary
  h.slots[1].pushPercent(80.0f);
  return h;
}

RtcTime localTime(uint16_t y, uint8_t mo, uint8_t d, uint8_t h, uint8_t mi = 5) {
  RtcTime t;
  t.valid = true;
  t.year = y;
  t.month = mo;
  t.day = d;
  t.hour = h;
  t.minute = mi;
  return t;
}

} // namespace

void testDailySummaryStatistics() {
  HumidityHistory rh;
  int16_t lo = 0, hi = 0, mean = 0;
  assert(!rh.summary(lo, hi, mean)); // no sample
  rh.pushPercent(40.0f);
  assert(rh.summary(lo, hi, mean) && lo == 400 && hi == 400 && mean == 400); // one sample
  rh.pushPercent(50.0f);
  rh.pushPercent(45.1f);
  assert(rh.summary(lo, hi, mean) && lo == 400 && hi == 500 && mean == 450);
  TemperatureHistory t;
  assert(!t.summary(lo, hi, mean));
  t.pushCelsius(-1.0f);
  t.pushCelsius(-2.0f);
  t.pushCelsius(0.5f);
  assert(t.summary(lo, hi, mean) && lo == -20 && hi == 5 && mean == -8); // -25/3 = -8.33
  t.pushCelsius(NAN); // rejected sample: no change
  assert(t.size() == 3);
}

void testDailySummaryRowFormat() {
  const ClimateHistory h = dailyHistory();
  NoiseHistory noise;
  noise.push(40);
  noise.push(55);
  noise.pushInvalid();
  char buf[SdLogCodec::MAX_ROW_SIZE];
  const size_t len = SdLogCodec::formatDailyRow(2026, 3, 8, h, noise, buf, sizeof(buf));
  assert(len == strlen(buf));
  const std::string expected = std::string("2026-03-08,20.0,22.5,21.2,40.0,50.0,45.0,-3.5,-3.5,-3.5,80.0,80.0,80.0") +
                               std::string(12, ',') + ",40,55\n";
  assert(expected == buf);

  // Header and row have the same column count, 27.
  char header[SdLogCodec::DAILY_HEADER_SIZE];
  const size_t hl = SdLogCodec::formatDailyHeader(header, sizeof(header));
  assert(hl == strlen(header) && header[hl - 1] == '\n');
  assert(strncmp(header, "date,onboard_temp_min_c,", 24) == 0);
  assert(strstr(header, ",noise_min_db,noise_max_db\n") != nullptr);
  assert(std::count(header, header + hl, ',') == 26 && std::count(buf, buf + len, ',') == 26);

  // Sensors without samples and no valid noise: empty fields, same column count.
  NoiseHistory silent;
  silent.pushInvalid();
  const size_t empty_len = SdLogCodec::formatDailyRow(2026, 12, 31, ClimateHistory{}, silent, buf, sizeof(buf));
  assert(empty_len > 0 && std::string(buf) == "2026-12-31" + std::string(26, ',') + "\n");

  // Bad arguments.
  assert(SdLogCodec::formatDailyRow(2026, 3, 8, h, noise, nullptr, 256) == 0);
  assert(SdLogCodec::formatDailyRow(2026, 3, 8, h, noise, buf, SdLogCodec::MAX_ROW_SIZE - 1) == 0 && buf[0] == '\0');
  assert(SdLogCodec::formatDailyRow(1999, 3, 8, h, noise, buf, sizeof(buf)) == 0);
  assert(SdLogCodec::formatDailyRow(2026, 13, 8, h, noise, buf, sizeof(buf)) == 0);
  assert(SdLogCodec::formatDailyRow(2026, 3, 0, h, noise, buf, sizeof(buf)) == 0);
  assert(SdLogCodec::formatDailyHeader(nullptr, 600) == 0);
  assert(SdLogCodec::formatDailyHeader(header, hl) == 0); // no room for the terminator

  // Worst case: every value at its widest still fits the row buffer.
  ClimateHistory wide;
  for (size_t i = 0; i < ClimateHistory::SLOT_COUNT; ++i) {
    wide.temperatures[i].pushCelsius(-100.0f);
    wide.temperatures[i].pushCelsius(100.0f);
    wide.slots[i].pushPercent(100.0f);
  }
  NoiseHistory loud;
  loud.push(140);
  assert(SdLogCodec::formatDailyRow(2099, 12, 31, wide, loud, buf, sizeof(buf)) > 0);

  // The hourly header has 21 columns (format version 4).
  char hourly[SdLogCodec::MAX_ROW_SIZE];
  assert(SdLogCodec::formatHeader(hourly, sizeof(hourly)) > 0);
  assert(std::count(hourly, hourly + strlen(hourly), ',') == 20);
}

void testDailySummaryFileNames() {
  char path[SdLogCodec::MAX_PATH_SIZE];
  assert(SdLogCodec::formatDailyFilename(2026, path, sizeof(path)) > 0);
  assert(strcmp(path, "/sdcard/daily_v1_2026.csv") == 0);
  assert(SdLogCodec::formatDailyFilename(1999, path, sizeof(path)) == 0 && path[0] == '\0');
  assert(SdLogCodec::formatDailyFilename(2100, path, sizeof(path)) == 0);
  assert(SdLogCodec::formatDailyFilename(2026, path, 10) == 0);
  assert(SdLogCodec::formatDailyFilename(2026, nullptr, 32) == 0);

  int year = 0;
  assert(SdLogCodec::parseDailyFileName("daily_v1_2026.csv", year) && year == 2026);
  assert(!SdLogCodec::parseDailyFileName(nullptr, year));
  assert(!SdLogCodec::parseDailyFileName("daily_v1_1999.csv", year));
  assert(!SdLogCodec::parseDailyFileName("daily_v1_2100.csv", year));
  assert(!SdLogCodec::parseDailyFileName("daily_v1_26.csv", year));
  assert(!SdLogCodec::parseDailyFileName("daily_v1_202a.csv", year));
  assert(!SdLogCodec::parseDailyFileName("daily_v2_2026.csv", year));
  assert(!SdLogCodec::parseDailyFileName("daily_v1_2026.txt", year));
  assert(!SdLogCodec::parseDailyFileName("../daily_v1_2026.csv", year));
  // The hourly check does not accept the daily name; the console check accepts both.
  assert(!SdLogCodec::isLogFileName("daily_v1_2026.csv"));
  assert(SdLogCodec::isDailyFileName("daily_v1_2026.csv"));
  assert(SdLogCodec::isConsoleFileName("daily_v1_2026.csv"));
  assert(SdLogCodec::isConsoleFileName("climate_v2_202611.csv"));
  assert(!SdLogCodec::isConsoleFileName("daily_v1_2026.csv/.."));
  assert(!SdLogCodec::isConsoleFileName("config.ini"));
  assert(!SdLogCodec::isConsoleFileName(nullptr));
}

void testDailySummaryRetention() {
  const RtcTime now = localTime(2027, 6, 15, 12);
  // Year file counts as December of its year: 2025 is 18 months old, 2026 is 6 months old.
  assert(SdLogCodec::shouldDeleteFile("daily_v1_2025.csv", now, 12));
  assert(!SdLogCodec::shouldDeleteFile("daily_v1_2026.csv", now, 12));
  assert(!SdLogCodec::shouldDeleteFile("daily_v1_2027.csv", now, 12));
  assert(!SdLogCodec::shouldDeleteFile("daily_v1_2025.csv", now, 0));  // keep all
  assert(!SdLogCodec::shouldDeleteFile("daily_v1_2025.csv", RtcTime{}, 12));
  RtcTime stopped = now;
  stopped.oscillator_stopped = true;
  assert(!SdLogCodec::shouldDeleteFile("daily_v1_2025.csv", stopped, 12));
  assert(!SdLogCodec::shouldDeleteFile("daily_v1_2025.csv", localTime(2024, 1, 1, 0), 12)); // untrusted clock
  assert(!SdLogCodec::shouldDeleteFile("notes_2025.csv", now, 12));
  // The hourly rule is the same as before.
  assert(SdLogCodec::shouldDeleteFile("climate_v2_202401.csv", now, 12));
  assert(!SdLogCodec::shouldDeleteFile("climate_v2_202701.csv", now, 12));
}

namespace {

struct DailyRun {
  int writes = 0;
  int marks = 0;
  std::vector<std::string> dates;
  std::vector<int> hours;
};

// Walks hourly UTC steps with the given zone and plays the wake cycle gate.
DailyRun runDailyGate(int64_t start_utc, int hours, const TimeZone& tz, DailyLogState& state, int fail_first = 0) {
  DailyRun run;
  int failures_left = fail_first;
  for (int i = 0; i < hours; ++i) {
    RtcTime local;
    assert(NtpTime::utcToLocal(start_utc + int64_t{i} * 3600 + 300, tz, local)); // :05 after the hour
    const DailyAction action = DailyPlanner::decide(state, local, true);
    if (action == DailyAction::MARK) {
      state.markDone(local);
      ++run.marks;
    } else if (action == DailyAction::WRITE) {
      if (failures_left > 0) {
        --failures_left; // failed write: no stamp, the next hourly write retries
        continue;
      }
      uint16_t y = 0;
      uint8_t m = 0, d = 0;
      DailyPlanner::previousDay(local, y, m, d);
      char date[16];
      snprintf(date, sizeof(date), "%04u-%02u-%02u", static_cast<unsigned>(y), static_cast<unsigned>(m),
               static_cast<unsigned>(d));
      run.dates.push_back(date);
      run.hours.push_back(local.hour);
      state.markDone(local);
      ++run.writes;
    }
  }
  return run;
}

} // namespace

void testDailySummaryMidnightGateCetAndCest() {
  const TimeZone tz{3600, true};
  // CET to CEST on 2026-03-29, CEST to CET on 2026-10-25. Start 2026-03-27 12:00 UTC.
  for (const int64_t start : {NtpTime::daysFromCivil(2026, 3, 27) * 86400 + 12 * 3600,
                              NtpTime::daysFromCivil(2026, 10, 23) * 86400 + 12 * 3600}) {
    DailyLogState state;
    const DailyRun run = runDailyGate(start, 4 * 24, tz, state);
    assert(run.marks == 1);  // the first cycle only stamps the day
    assert(run.writes == 4); // one row for each midnight that follows, none twice
    for (size_t i = 0; i < run.hours.size(); ++i) {
      assert(run.hours[i] == 0); // the first hourly write after local midnight
    }
    for (size_t i = 1; i < run.dates.size(); ++i) {
      assert(run.dates[i] != run.dates[i - 1]);
    }
  }
  // The row dates are the days that ended: 2026-03-27 (Fri) to 2026-03-30.
  DailyLogState state;
  const DailyRun cest = runDailyGate(NtpTime::daysFromCivil(2026, 3, 27) * 86400 + 12 * 3600, 4 * 24, tz, state);
  assert(cest.dates.size() == 4);
  assert(cest.dates[0] == "2026-03-27" && cest.dates[1] == "2026-03-28" && cest.dates[2] == "2026-03-29" &&
         cest.dates[3] == "2026-03-30");
  // The 23 h day: 2026-03-29 has hours 0, 1, 3, ... local. The write still comes at hour 0 of 03-30.
  DailyLogState oct;
  const DailyRun fall = runDailyGate(NtpTime::daysFromCivil(2026, 10, 23) * 86400 + 12 * 3600, 4 * 24, tz, oct);
  assert(fall.dates.size() == 4 && fall.dates[2] == "2026-10-25" && fall.dates[3] == "2026-10-26");
}

void testDailySummaryOneRowPerDay() {
  DailyLogState state;
  const RtcTime day1 = localTime(2026, 5, 10, 14);
  assert(DailyPlanner::decide(state, day1, true) == DailyAction::MARK);
  state.markDone(day1);
  assert(DailyPlanner::decide(state, localTime(2026, 5, 10, 23), true) == DailyAction::NONE);
  const RtcTime midnight = localTime(2026, 5, 11, 0);
  assert(DailyPlanner::decide(state, midnight, true) == DailyAction::WRITE);
  state.markDone(midnight);
  assert(DailyPlanner::decide(state, midnight, true) == DailyAction::NONE);
  assert(DailyPlanner::decide(state, localTime(2026, 5, 11, 1), true) == DailyAction::NONE);
  // A clock that runs back, or an untrusted clock, writes nothing.
  assert(DailyPlanner::decide(state, localTime(2026, 5, 9, 12), true) == DailyAction::NONE);
  RtcTime invalid = localTime(2026, 5, 12, 0);
  invalid.valid = false;
  assert(DailyPlanner::decide(state, invalid, true) == DailyAction::NONE);
  RtcTime stopped = localTime(2026, 5, 12, 0);
  stopped.oscillator_stopped = true;
  assert(DailyPlanner::decide(state, stopped, true) == DailyAction::NONE);
  assert(DailyPlanner::decide(state, localTime(2020, 5, 12, 0), true) == DailyAction::NONE);
  // Stale histories (no sample for 24 h) give no row, but the day is stamped.
  assert(DailyPlanner::decide(state, localTime(2026, 5, 12, 0), false) == DailyAction::MARK);
  // An impossible date from a bad RTC read writes nothing.
  assert(DailyPlanner::decide(state, localTime(2026, 2, 31, 0), true) == DailyAction::NONE);
  assert(NtpTime::isValidDate(2028, 2, 29) && !NtpTime::isValidDate(2027, 2, 29) && !NtpTime::isValidDate(2026, 4, 31));
  // Year change: the row of 2026-12-31 belongs to the file of 2026.
  uint16_t y = 0;
  uint8_t m = 0, d = 0;
  DailyPlanner::previousDay(localTime(2027, 1, 1, 0), y, m, d);
  assert(y == 2026 && m == 12 && d == 31);
  DailyPlanner::previousDay(localTime(2028, 3, 1, 0), y, m, d); // leap year
  assert(y == 2028 && m == 2 && d == 29);
  // History freshness.
  ClimateHistory h;
  assert(!h.freshWithin24h(5000));
  h.has_sampled = true;
  h.last_sample_ms = 1000;
  assert(h.freshWithin24h(1000 + 3600UL * 1000UL) && !h.freshWithin24h(1000 + 24UL * 3600UL * 1000UL));
}

void testDailySummaryFailedWriteRetries() {
  const TimeZone tz{3600, true};
  DailyLogState state;
  // Without a failure each midnight gives one row.
  const DailyRun run = runDailyGate(NtpTime::daysFromCivil(2026, 6, 1) * 86400 + 12 * 3600, 2 * 24, tz, state, 0);
  assert(run.writes == 2 && run.marks == 1);
  // Two failed writes at 00:05 and 01:05, the third try at 02:05 succeeds. The row is written once.
  DailyLogState retry;
  const DailyRun r = runDailyGate(NtpTime::daysFromCivil(2026, 6, 1) * 86400 + 12 * 3600, 30, tz, retry, 2);
  assert(r.marks == 1);
  assert(r.writes == 1); // midnight 06-02 failed twice and succeeded at 02:05; 06-03 midnight is outside
  assert(r.hours.size() == 1 && r.hours[0] == 2); // CEST: UTC+2, local hour 2 is the third try
  assert(r.dates[0] == "2026-06-01");
  // After the success nothing repeats that day.
  assert(DailyPlanner::decide(retry, localTime(2026, 6, 2, 3), true) == DailyAction::NONE);
}

void testDailySummaryStateRoundTrip() {
  PersistentState s;
  s.daily.has_date = true;
  s.daily.year = 2026;
  s.daily.month = 7;
  s.daily.day = 9;
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  PersistentState r;
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(r.daily.has_date && r.daily.year == 2026 && r.daily.month == 7 && r.daily.day == 9);
  PersistentState fresh;
  assert(PowerManager::serialize(fresh, buf, sizeof(buf)));
  assert(PowerManager::deserialize(buf, sizeof(buf), r) && !r.daily.has_date);

  // Corrupt stamp fields with a valid CRC are rejected.
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  const size_t daily_pos = PowerManager::SERIALIZED_SIZE - 4 - PowerManager::CONFIG_SIZE - PowerManager::NTP_SIZE -
                           PowerManager::AUDIO_SIZE - 5;
  assert(buf[daily_pos] == 1 && buf[daily_pos + 1] == (2026 & 0xFF));
  const size_t bad[5][3] = {{daily_pos, 2, 0}, {daily_pos + 3, 13, 0}, {daily_pos + 3, 0, 0}, {daily_pos + 4, 32, 0}, {daily_pos + 3, 2, 31}};
  for (const auto& c : bad) {
    uint8_t b[PowerManager::SERIALIZED_SIZE];
    memcpy(b, buf, sizeof(b));
    b[c[0]] = static_cast<uint8_t>(c[1]);
    if (c[2] != 0) {
      b[daily_pos + 4] = static_cast<uint8_t>(c[2]); // 31 February
    }
    const uint32_t cc = PowerManager::crc32(b, sizeof(b) - 4);
    for (int i = 0; i < 4; ++i) {
      b[sizeof(b) - 4 + i] = static_cast<uint8_t>(cc >> (8 * i));
    }
    PersistentState rejected;
    assert(!PowerManager::deserialize(b, sizeof(b), rejected));
  }
}

void testDailySummaryConsole() {
  SerialCommand cmd;
  assert(parseText("DUMP daily_v1_2026.csv", cmd) == SerialParseStatus::OK);
  assert(cmd.kind == SerialCommandKind::DUMP && strcmp(cmd.file, "daily_v1_2026.csv") == 0);
  assert(parseText("DUMP daily_v1_2026.csv\r\n", cmd) == SerialParseStatus::OK);
  assert(parseText("DUMP daily_v1_1999.csv", cmd) == SerialParseStatus::BAD_NAME);
  assert(parseText("DUMP daily_v2_2026.csv", cmd) == SerialParseStatus::BAD_NAME);
  assert(parseText("DUMP daily_v1_2026.csv ", cmd) == SerialParseStatus::BAD_NAME);
  assert(parseText("DUMP ../daily_v1_2026.csv", cmd) == SerialParseStatus::BAD_NAME);
  assert(parseText("DUMP daily_v1_2026", cmd) == SerialParseStatus::BAD_NAME);
  assert(parseText("DUMP climate_v2_202611.csv", cmd) == SerialParseStatus::OK); // unchanged

  FakeConsoleStorage storage;
  storage.add("climate_v2_202611.csv", "hourly\n");
  storage.add("daily_v1_2026.csv", "date\n2026-11-11\n");
  FakeSink list_sink;
  SerialCommand list;
  list.kind = SerialCommandKind::LIST;
  TestRunner::execute(storage, list_sink, list);
  assert(list_sink.out ==
         "FILE climate_v2_202611.csv 7\nFILE daily_v1_2026.csv 16\nEND LIST 2\n");

  FakeSink dump_sink;
  SerialCommand dump;
  dump.kind = SerialCommandKind::DUMP;
  snprintf(dump.file, sizeof(dump.file), "%s", "daily_v1_2026.csv");
  TestRunner::execute(storage, dump_sink, dump);
  SerialCrc32 crc;
  crc.update(reinterpret_cast<const uint8_t*>("date\n2026-11-11\n"), 16);
  char tail[24];
  snprintf(tail, sizeof(tail), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
  assert(dump_sink.out == std::string("BEGIN daily_v1_2026.csv 16\ndate\n2026-11-11\n") + tail);
}

// ---- Hourly count of loud transient events ----

// Measures one 5 s window over a scripted level envelope. The ADC settles for 100 ms first, so the
// envelope starts with that much padding.
NoiseReading measureEnvelope(const ClapEnvelope& e, FakeClapHal& hal) {
  hal.level = e.level;
  return NoiseSampler::measure(hal, NoiseBand::QUIET);
}

void testNoiseWindowCountsTransients() {
  const size_t pad = NoiseSampler::SETTLE_FRAMES / 16; // 100 ms
  const size_t total = pad + NoiseSampler::WINDOW_MS;

  // No transient: 0 events
  FakeClapHal quiet;
  NoiseReading r = measureEnvelope(ClapEnvelope(total), quiet);
  assert(r.valid && r.events == 0);

  // One transient: a rise of 20 dB or more in under 5 ms
  ClapEnvelope one(total);
  one.clap(pad + 2000);
  FakeClapHal hal1;
  r = measureEnvelope(one, hal1);
  assert(r.valid && r.events == 1);

  // Ten transients, 400 ms apart
  ClapEnvelope ten(total);
  for (size_t i = 0; i < 10; ++i) ten.clap(pad + 500 + i * 400);
  FakeClapHal hal10;
  r = measureEnvelope(ten, hal10);
  assert(r.valid && r.events == 10);

  // A slow rise (speech, 10 ms) is no transient
  ClapEnvelope slow(total);
  for (size_t i = 0; i < 40; ++i) slow.level[pad + 2000 + i] = -70 + static_cast<int>(i < 20 ? i * 3 : 60 - (i - 20) * 3);
  FakeClapHal hal_slow;
  r = measureEnvelope(slow, hal_slow);
  assert(r.valid && r.events == 0);

  // A steady tone has no event, also a loud one
  ClapEnvelope tone(total);
  tone.fill(0, total, -20);
  FakeClapHal hal_tone;
  r = measureEnvelope(tone, hal_tone);
  assert(r.valid && r.events == 0);
  FakeMicHal square;
  r = NoiseSampler::measure(square, NoiseBand::QUIET);
  assert(r.valid && r.events == 0);

  // The microphones stay on for the existing window only: 100 ms settling plus 5 s, 256 samples a read
  const uint64_t samples = static_cast<uint64_t>(NoiseSampler::SETTLE_FRAMES + 16000 * 5) * 2;
  const uint64_t expected_reads = (samples + NoiseSampler::CHUNK_SAMPLES - 1) / NoiseSampler::CHUNK_SAMPLES;
  assert(hal10.reads == expected_reads && hal10.shutdown_calls == 1 && hal10.end_calls == 1);

  // A failed window has no valid reading and no events
  FakeClapHal failing;
  failing.level = ten.level;
  failing.reads_before_fail = 100;
  r = NoiseSampler::measure(failing, NoiseBand::QUIET);
  assert(!r.valid && r.events == 0 && failing.shutdown_calls == 1);
}

void testTransientCounterSaturates() {
  TransientCounter counter;
  assert(counter.count() == 0);
  // 70000 isolated bursts would exceed 16 bits; the count stops at the maximum
  int16_t quiet[32 * 2];
  int16_t loud[32 * 2];
  for (size_t i = 0; i < 64; ++i) {
    quiet[i] = (i / 2) & 1 ? -5 : 5;
    loud[i] = (i / 2) & 1 ? -20000 : 20000;
  }
  for (uint32_t i = 0; i < 70000; ++i) {
    for (int k = 0; k < 20; ++k) counter.add(quiet, 64); // 40 ms of quiet floor
    counter.add(loud, 64);                                // one loud transient
    for (int k = 0; k < 20; ++k) counter.add(quiet, 64);
    if (counter.count() == TransientCounter::MAX_COUNT) break;
  }
  assert(counter.count() == TransientCounter::MAX_COUNT);
  counter.add(loud, 64);
  for (int k = 0; k < 40; ++k) counter.add(quiet, 64);
  assert(counter.count() == TransientCounter::MAX_COUNT);
}

void testSdLogEventsColumn() {
  char buf[SdLogCodec::MAX_ROW_SIZE];
  const size_t hl = SdLogCodec::formatHeader(buf, sizeof(buf));
  assert(hl > 0 && strstr(buf, ",noise_rms,noise_db,noise_band,noise_events,noise_l10,noise_l90\n") != nullptr);
  size_t header_commas = 0;
  for (size_t i = 0; i < hl; ++i) header_commas += buf[i] == ',';
  assert(header_commas == 20); // 21 columns

  RtcTime t;
  t.valid = true; t.year = 2026; t.month = 3; t.day = 9; t.hour = 7; t.minute = 5; t.second = 1;
  const MicCalibration cal;
  NoiseReading noise = NoiseClassifier::makeReading(NoiseBand::QUIET, 1000);

  // Events 0, 1 and 10 show as numbers
  const uint16_t counts[] = {0, 1, 10};
  for (uint16_t n : counts) {
    noise.events = n;
    assert(SdLogCodec::formatRow(&t, nullptr, nullptr, nullptr, 0, 0, &noise, &cal, buf, sizeof(buf)) > 0);
    char expected[96];
    snprintf(expected, sizeof(expected), "2026-03-09 07:05:01,,,,,,,,,,,,,,,1000,60,MODERATE,%u,,\n", static_cast<unsigned>(n));
    assert(strcmp(buf, expected) == 0);
    size_t row_commas = 0;
    for (size_t i = 0; buf[i] != '\0'; ++i) row_commas += buf[i] == ',';
    assert(row_commas == header_commas);
  }

  // A failed window gives an empty field, even with a stale count
  NoiseReading failed;
  failed.events = 7;
  assert(SdLogCodec::formatRow(&t, nullptr, nullptr, nullptr, 0, 0, &failed, &cal, buf, sizeof(buf)) > 0);
  assert(strcmp(buf, "2026-03-09 07:05:01,,,,,,,,,,,,,,,,,,,,\n") == 0);
  assert(SdLogCodec::formatRow(&t, nullptr, nullptr, nullptr, 0, 0, nullptr, &cal, buf, sizeof(buf)) > 0);
  assert(strcmp(buf, "2026-03-09 07:05:01,,,,,,,,,,,,,,,,,,,,\n") == 0);

  // The longest row (all sensors, maximum events) stays inside the line bound
  BatteryReading bat;
  bat.valid = true; bat.percentage = 100; bat.voltage_v = 4.2f; bat.usb_powered = true;
  Shtc3Reading onboard = makeOnboard(-20.5f, 100.0f);
  SwitchBotReading slots[MAX_SWITCHBOT_SENSORS];
  for (auto& r : slots) {
    r.valid = true; r.temperature_c = -40.0f; r.humidity_pct = 100; r.battery_pct = 100; r.updated_ms = 10;
  }
  noise = NoiseClassifier::makeReading(NoiseBand::QUIET, 32767);
  noise.events = 65535;
  assert(SdLogCodec::formatRow(&t, &bat, &onboard, slots, 3, 20, &noise, &cal, buf, sizeof(buf)) > 0);
  assert(strstr(buf, ",32767,90,LOUD,65535,,\n") != nullptr);
  assert(strlen(buf) < SdLogCodec::MAX_ROW_SIZE);
  assert(SdLogCodec::formatRow(&t, &bat, &onboard, slots, 3, 20, &noise, &cal, buf, SdLogCodec::MAX_ROW_SIZE - 1) == 0);

  // File names: the v4 name for new rows; the v2 files stay valid names for listing and retention
  char path[SdLogCodec::MAX_PATH_SIZE];
  assert(SdLogCodec::formatFilename(&t, path, sizeof(path)) > 0);
  assert(strcmp(path, "/sdcard/climate_v4_202603.csv") == 0);
  assert(SdLogCodec::isLogFileName("climate_v4_202603.csv"));
  assert(SdLogCodec::isLogFileName("climate_v2_202603.csv"));
  assert(!SdLogCodec::isLogFileName("climate_v5_202603.csv"));
  assert(!SdLogCodec::isLogFileName("climate_v5_202613.csv"));
  assert(SdLogCodec::shouldDeleteLog("climate_v3_202001.csv", rtcAt(2026, 3), 12));
  assert(SdLogCodec::shouldDeleteLog("climate_v2_202001.csv", rtcAt(2026, 3), 12));
  assert(strlen("climate_v4_202603.csv") + 1 == SdLogCodec::LOG_NAME_SIZE);
  int py = 0, pm = 0;
  assert(SdLogCodec::parseLogFileName("climate_v3_202512.csv", py, pm) && py == 2025 && pm == 12);
  assert(SdLogCodec::parseLogFileName("climate_v2_202512.csv", py, pm) && py == 2025 && pm == 12);
  assert(!SdLogCodec::parseLogFileName("climate_v3_202513.csv", py, pm));
  assert(!SdLogCodec::shouldDeleteLog("climate_v3_202512.csv", rtcAt(2026, 1), 1)); // age 1 of 1
  assert(SdLogCodec::shouldDeleteLog("climate_v3_202511.csv", rtcAt(2026, 1), 1));  // age 2 of 1
  assert(!SdLogCodec::shouldDeleteLog("climate_v4_202601.csv", rtcAt(2026, 1), 1)); // current month

  // Serial console: the v4 name is a valid DUMP target and fits the 40 byte line bound
  SerialCommand cmd;
  assert(parseText("DUMP climate_v4_202603.csv", cmd) == SerialParseStatus::OK);
  assert(strcmp(cmd.file, "climate_v4_202603.csv") == 0);
  assert(strlen("DUMP climate_v4_202603.csv") <= SerialConsole::MAX_LINE_SIZE);
}

void testDiagnosticsShowsNoiseEvents() {
  SystemInfo info;
  info.noise = NoiseClassifier::makeReading(NoiseBand::QUIET, 650);
  info.noise.events = 3;
  BatteryReading bat;
  bat.valid = true; bat.percentage = 80; bat.voltage_v = 3.9f;
  St7305Framebuffer a, b, c;
  DashboardView::renderDiagnostics(a, bat, info, "12:00");
  info.noise.events = 12;
  DashboardView::renderDiagnostics(b, bat, info, "12:00");
  info.noise = NoiseReading{}; // failed window: "EVENTS: --"
  DashboardView::renderDiagnostics(c, bat, info, "12:00");
  uint32_t ab = 0, ac = 0, outside = 0;
  for (uint16_t y = 0; y < St7305Framebuffer::HEIGHT; ++y) {
    for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
      if (a.getPixel(x, y) != b.getPixel(x, y)) {
        ++ab;
        if (y < 276 || y >= 290 || x < 10 + 16 * 12) ++outside;
      }
      if (a.getPixel(x, y) != c.getPixel(x, y) && y >= 276 && y < 290 && x >= 10 + 16 * 12) ++ac;
    }
  }
  assert(ab > 0 && ac > 0 && outside == 0); // only the events text changes, and it differs with a failed window
}

// ---- Fixed MAC address for each sensor slot (slot1_mac to slot3_mac) ----

SwitchBotReading macReading(const uint8_t (&mac)[6], uint8_t humidity = 50, uint32_t updated_ms = 1000) {
  SwitchBotReading r;
  r.valid = true;
  memcpy(r.mac, mac, 6);
  r.temperature_c = 20.0f;
  r.humidity_pct = humidity;
  r.updated_ms = updated_ms;
  return r;
}

const uint8_t MAC_A[6] = {0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0x01};
const uint8_t MAC_B[6] = {0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0x02};
const uint8_t MAC_C[6] = {0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0x03};

void testSlotMacParser() {
  ConfigParseResult r;
  DeviceConfig c = parseText("slot1_mac=AA:BB:CC:DD:EE:FF\n", &r);
  assert(r.keys_set == 1 && r.rejected == 0 && c.hasSlotMac(0) && !c.hasSlotMac(1) && !c.hasSlotMac(2));
  const uint8_t expected[6] = {0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF};
  assert(memcmp(c.slot_mac[0], expected, 6) == 0);
  assert(c.valid() && !c.isDefault());

  // Lowercase hex, spaces around the value, slot 2 and 3
  c = parseText("slot2_mac = aa:bb:cc:dd:ee:0f \nslot3_mac=01:23:45:67:89:aB\n", &r);
  assert(r.keys_set == 2 && r.rejected == 0 && c.hasSlotMac(1) && c.hasSlotMac(2));
  assert(c.slot_mac[1][5] == 0x0F && c.slot_mac[2][5] == 0xAB);

  // Wrong separator, short value, long value, bad digit, all zero: rejected, slot stays unfixed
  const char* const bad[] = {
      "slot1_mac=AA-BB-CC-DD-EE-FF\n", "slot1_mac=AABBCCDDEEFF\n", "slot1_mac=AA:BB:CC:DD:EE\n",
      "slot1_mac=AA:BB:CC:DD:EE:F\n",  "slot1_mac=AA:BB:CC:DD:EE:FF:00\n", "slot1_mac=AA:BB:CC:DD:EE:GG\n",
      "slot1_mac=AA:BB:CC:DD:EE;FF\n",  "slot1_mac=00:00:00:00:00:00\n"};
  for (const char* text : bad) {
    c = parseText(text, &r);
    assert(r.keys_set == 0 && r.rejected == 1 && !c.hasSlotMac(0) && c.isDefault());
  }

  // Empty value: accepted and clears an earlier value of the same key
  c = parseText("slot1_mac=\n", &r);
  assert(r.keys_set == 1 && r.rejected == 0 && !c.hasSlotMac(0) && c.isDefault());
  c = parseText("slot1_mac=AA:BB:CC:DD:EE:FF\nslot1_mac=\n", &r);
  assert(r.keys_set == 1 && !c.hasSlotMac(0));
  // A rejected line keeps the earlier value
  c = parseText("slot1_mac=AA:BB:CC:DD:EE:FF\nslot1_mac=AA:BB\n", &r);
  assert(r.rejected == 1 && c.hasSlotMac(0) && c.slot_mac[0][5] == 0xFF);

  // The same MAC in two keys: the second key is rejected, also with another letter case
  c = parseText("slot1_mac=AA:BB:CC:DD:EE:FF\nslot2_mac=aa:bb:cc:dd:ee:ff\n", &r);
  assert(r.keys_set == 1 && r.rejected == 1 && c.hasSlotMac(0) && !c.hasSlotMac(1));
  // The same key may repeat its own MAC
  c = parseText("slot1_mac=AA:BB:CC:DD:EE:FF\nslot1_mac=AA:BB:CC:DD:EE:FF\n", &r);
  assert(r.rejected == 0 && c.hasSlotMac(0));

  // Config text writes fixed MACs back and the parser reads them
  c = parseText("slot1_mac=AA:BB:CC:DD:EE:FF\nslot3_mac=01:02:03:04:05:06\n");
  char text[DeviceConfigText::MAX_SIZE];
  const size_t n = DeviceConfigText::format(c, text, sizeof(text));
  assert(n > 0 && strstr(text, "slot1_mac=AA:BB:CC:DD:EE:FF\n") != nullptr && strstr(text, "slot2_mac") == nullptr);
  DeviceConfig back;
  r = DeviceConfigParser::parse(text, n, back);
  assert(r.rejected == 0 && memcmp(back.slot_mac, c.slot_mac, sizeof(c.slot_mac)) == 0);
  assert(DeviceConfigText::format(DeviceConfig{}, text, sizeof(text)) > 0 && strstr(text, "_mac") == nullptr);

  // A config with a duplicate fixed MAC is not valid
  c = DeviceConfig{};
  memcpy(c.slot_mac[0], MAC_A, 6);
  memcpy(c.slot_mac[1], MAC_A, 6);
  assert(!c.valid());
}

void testSlotMacRegistry() {
  // First come, first served without fixed MACs
  {
    SwitchBotRegistry reg;
    assert(reg.upsert(macReading(MAC_B)) && reg.upsert(macReading(MAC_A)));
    assert(memcmp(reg.slot(0).mac, MAC_B, 6) == 0 && memcmp(reg.slot(1).mac, MAC_A, 6) == 0);
    assert(!reg.isFixed(0) && reg.fixedMac(0) == nullptr);
  }
  // A fixed slot rejects a foreign MAC and takes its own MAC, also when earlier slots are free
  {
    SwitchBotRegistry reg;
    reg.setFixedMac(1, MAC_A);
    assert(reg.isFixed(1) && memcmp(reg.fixedMac(1), MAC_A, 6) == 0);
    assert(reg.upsert(macReading(MAC_A, 41)));
    assert(!reg.slot(0).valid && reg.slot(1).valid && reg.slot(1).humidity_pct == 41);
    assert(reg.upsert(macReading(MAC_A, 42)) && reg.slot(1).humidity_pct == 42 && reg.slot(1).packet_count == 2);
    // A foreign MAC lands in a free unfixed slot, never in the fixed one
    assert(reg.upsert(macReading(MAC_B)) && memcmp(reg.slot(0).mac, MAC_B, 6) == 0);
    assert(reg.upsert(macReading(MAC_C)) && memcmp(reg.slot(2).mac, MAC_C, 6) == 0);
    assert(memcmp(reg.slot(1).mac, MAC_A, 6) == 0);
  }
  // A fixed slot that is empty stays empty for a foreign MAC
  {
    SwitchBotRegistry reg;
    reg.setFixedMac(0, MAC_A);
    reg.setFixedMac(1, MAC_B);
    reg.setFixedMac(2, MAC_C);
    const uint8_t other[6] = {1, 2, 3, 4, 5, 6};
    assert(!reg.upsert(macReading(other)) && reg.activeCount() == 0);
    assert(reg.upsert(macReading(MAC_C)) && reg.slot(2).valid && !reg.slot(0).valid);
  }
  // An unfixed slot rejects a MAC that another slot has fixed
  {
    SwitchBotRegistry reg;
    reg.setFixedMac(2, MAC_A);
    assert(reg.upsert(macReading(MAC_B)) && reg.upsert(macReading(MAC_C)));
    assert(!reg.slot(2).valid && reg.activeCount() == 2);
    assert(reg.upsert(macReading(MAC_A)) && memcmp(reg.slot(2).mac, MAC_A, 6) == 0);
    assert(reg.activeCount() == 3);
    // Only two unfixed slots exist; a third foreign MAC finds no place
    SwitchBotRegistry full;
    full.setFixedMac(2, MAC_A);
    assert(full.upsert(macReading(MAC_B)) && full.upsert(macReading(MAC_C)));
    const uint8_t other[6] = {1, 2, 3, 4, 5, 6};
    assert(!full.upsert(macReading(other)));
  }
  // BTHome packets follow the same rule
  {
    SwitchBotRegistry reg;
    reg.setFixedMac(0, BTHOME_MAC);
    reg.setFixedMac(1, MAC_A);
    const uint8_t pkt[] = {0x40, 0x01, 77, 0x02, 0x4B, 0x08, 0x03, 0x97, 0x11};
    SwitchBotReading bt;
    assert(BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, BTHOME_MAC, pkt, sizeof(pkt), bt));
    bt.updated_ms = 2000;
    assert(reg.upsert(macReading(MAC_B)) && reg.upsert(bt));
    assert(memcmp(reg.slot(0).mac, BTHOME_MAC, 6) == 0 && memcmp(reg.slot(2).mac, MAC_B, 6) == 0);
    // A neighbour BTHome sensor cannot take a fixed slot
    const uint8_t neighbour[6] = {0xA4, 0xC1, 0x38, 0x99, 0x99, 0x99};
    SwitchBotReading nb;
    assert(BtHomeBleParser::parseServiceData(BTHOME_SERVICE_UUID, neighbour, pkt, sizeof(pkt), nb));
    assert(!reg.upsert(nb));
    // A SwitchBot packet cannot take a slot that is fixed for another MAC
    SwitchBotRegistry reg2;
    reg2.setFixedMac(0, MAC_A);
    assert(reg2.upsert(macReading(MAC_B)) && !reg2.slot(0).valid && reg2.slot(1).valid);
  }
  // clearSlot, setFixedMac with nullptr, and consistent()
  {
    SwitchBotRegistry reg;
    reg.upsert(macReading(MAC_A));
    assert(reg.consistent());
    reg.setFixedMac(1, MAC_A); // slot 0 holds a MAC that slot 1 has fixed
    assert(!reg.consistent());
    reg.clearSlot(0);
    assert(reg.consistent() && reg.activeCount() == 0);
    reg.setFixedMac(1, nullptr);
    assert(!reg.isFixed(1));
    reg.clearSlot(7); // out of range: no effect
  }
}

void testSlotMacConfigApply() {
  PersistentState s;
  for (size_t i = 0; i < ClimateHistory::SLOT_COUNT; ++i) {
    s.history.slots[i].pushPercent(50.0f);
    s.history.temperatures[i].pushCelsius(20.0f);
  }
  s.registry.upsert(macReading(MAC_A));
  s.registry.upsert(macReading(MAC_B));
  s.registry.upsert(macReading(MAC_C));

  // Same config again (a KEY wakeup): nothing changes
  s.applyConfig(DeviceConfig{}, true, ConfigRejects{});
  assert(s.registry.activeCount() == 3 && s.history.slots[2].size() == 1 && s.config_from_sd);

  // Slot 2 gets a new fixed MAC: its reading and its history go, the other slots stay
  const uint8_t new_mac[6] = {9, 9, 9, 9, 9, 9};
  DeviceConfig cfg;
  memcpy(cfg.slot_mac[1], new_mac, 6);
  s.applyConfig(cfg, true, ConfigRejects{});
  assert(!s.registry.slot(1).valid && s.registry.slot(0).valid && s.registry.slot(2).valid);
  assert(s.history.slots[2].size() == 0 && s.history.temperatures[2].size() == 0);
  assert(s.history.slots[1].size() == 1 && s.history.slots[3].size() == 1 && s.history.slots[0].size() == 1);
  assert(s.registry.isFixed(1) && memcmp(s.registry.fixedMac(1), new_mac, 6) == 0);
  assert(memcmp(s.config.slot_mac[1], new_mac, 6) == 0);

  // The same fixed MAC again keeps what the slot got in the meantime
  assert(s.registry.upsert(macReading(new_mac)) && s.registry.slot(1).valid);
  s.history.slots[2].pushPercent(40.0f);
  s.applyConfig(cfg, true, ConfigRejects{});
  assert(s.registry.slot(1).valid && s.history.slots[2].size() == 1);

  // Slot 1 takes the MAC that slot 3 holds: slot 1 is reset, slot 3 loses its reading too
  DeviceConfig cfg2 = cfg;
  memcpy(cfg2.slot_mac[0], MAC_C, 6);
  s.applyConfig(cfg2, true, ConfigRejects{});
  assert(!s.registry.slot(0).valid && !s.registry.slot(2).valid && s.registry.slot(1).valid);
  assert(s.history.slots[1].size() == 0 && s.history.slots[3].size() == 0 && s.history.slots[2].size() == 1);
  assert(s.registry.consistent());

  // Clearing a fixed MAC changes it as well
  DeviceConfig cfg3 = cfg2;
  memset(cfg3.slot_mac[1], 0, 6);
  s.applyConfig(cfg3, false, ConfigRejects{});
  assert(!s.registry.slot(1).valid && !s.registry.isFixed(1) && s.history.slots[2].size() == 0 && !s.config_from_sd);
}

void testSlotMacRtcRoundTrip() {
  PersistentState s;
  DeviceConfig cfg;
  memcpy(cfg.slot_mac[0], MAC_A, 6);
  memcpy(cfg.slot_mac[2], MAC_C, 6);
  s.applyConfig(cfg, true, ConfigRejects{});
  s.registry.upsert(macReading(MAC_A));
  s.registry.upsert(macReading(MAC_B));
  uint8_t buf[PowerManager::SERIALIZED_SIZE];
  assert(PowerManager::serialize(s, buf, sizeof(buf)));
  PersistentState r;
  assert(PowerManager::deserialize(buf, sizeof(buf), r));
  assert(memcmp(r.config.slot_mac, cfg.slot_mac, sizeof(cfg.slot_mac)) == 0);
  assert(r.registry.isFixed(0) && !r.registry.isFixed(1) && r.registry.isFixed(2));
  // The restored registry keeps enforcing the fixed MACs
  const uint8_t other[6] = {1, 2, 3, 4, 5, 6};
  assert(!r.registry.upsert(macReading(other)));
  assert(r.registry.upsert(macReading(MAC_C)) && memcmp(r.registry.slot(2).mac, MAC_C, 6) == 0);
  assert(PowerManager::STATE_VERSION == 26);

  // A valid checksum with the same MAC in two keys is rejected
  const size_t mac_pos = PowerManager::SERIALIZED_SIZE - 4 - PowerManager::REJECTS_SIZE - 1 - PowerManager::MACS_SIZE;
  uint8_t bad[PowerManager::SERIALIZED_SIZE];
  memcpy(bad, buf, sizeof(bad));
  memcpy(bad + mac_pos + 6, MAC_A, 6);
  uint32_t crc = PowerManager::crc32(bad, sizeof(bad) - 4);
  for (int i = 0; i < 4; ++i) bad[sizeof(bad) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  assert(!PowerManager::deserialize(bad, sizeof(bad), r));

  // A stored reading that breaks a fixed MAC is rejected: slot 2 holds MAC_B, slot 1 fixes it
  memcpy(bad, buf, sizeof(bad));
  memcpy(bad + mac_pos, MAC_B, 6);
  crc = PowerManager::crc32(bad, sizeof(bad) - 4);
  for (int i = 0; i < 4; ++i) bad[sizeof(bad) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  assert(!PowerManager::deserialize(bad, sizeof(bad), r));

  // The previous state version starts clean
  memcpy(bad, buf, sizeof(bad));
  bad[4] = 23;
  crc = PowerManager::crc32(bad, sizeof(bad) - 4);
  for (int i = 0; i < 4; ++i) bad[sizeof(bad) - 4 + i] = static_cast<uint8_t>(crc >> (8 * i));
  assert(!PowerManager::deserialize(bad, sizeof(bad), r));
}

void testSlotMacStatsLine() {
  char text[24];
  const uint8_t mac[6] = {0xB0, 0xE9, 0xFE, 0x12, 0x34, 0x56};
  DashboardView::formatMac(text, sizeof(text), mac);
  assert(strcmp(text, "MAC ..:12:34:56") == 0);
  assert(10 + strlen(text) * 12 <= RLCD_WIDTH - 8);

  // A slot without signal but with a fixed MAC shows the MAC line; without a fixed MAC it stays blank
  Shtc3Reading onboard;
  BatteryReading battery;
  SystemInfo info;
  SwitchBotReading none[MAX_SWITCHBOT_SENSORS] = {};
  DeviceConfig cfg;
  memcpy(cfg.slot_mac[0], mac, 6);
  St7305Framebuffer with, without;
  DashboardView::renderMode(with, DisplayMode::SENSOR_STATS, onboard, battery, none, MAX_SWITCHBOT_SENSORS, info,
                            "12:34", 0, nullptr, nullptr, &cfg);
  DashboardView::renderMode(without, DisplayMode::SENSOR_STATS, onboard, battery, none, MAX_SWITCHBOT_SENSORS, info,
                            "12:34", 0);
  size_t ink = 0;
  for (uint16_t y = 52; y < 66; ++y) {
    for (uint16_t x = 0; x < St7305Framebuffer::WIDTH; ++x) {
      ink += with.getPixel(x, y) ? 1 : 0;
      assert(!without.getPixel(x, y));
    }
  }
  assert(ink > 20);
}

int main() {
  testCycleTrackerPartialDischarges();
  testCycleTrackerMilestoneWrite();
  testCycleTrackerChargeDetection();
  testCycleTrackerUsbAndInvalidReadings();
  testCycleTrackerNvsFailures();
  testCycleTrackerWriteCountPerDischarge();
  testCycleTrackerChargerWithoutHost();
  testCycleTrackerChargeEventWithUnreadableNvs();
  testCycleTrackerRtcRoundTrip();
  testCycleDescribeFormat();
  testDiagnosticsCycleLineBounds();
  testEmptyBatteryProtectionLatch();
  testEmptyBatteryProtectionPlan();
  testEmptyBatteryProtectionRtcRoundTrip();
  testEmptyBatteryProtectionRendering();
  testSwitchBotManufacturerPositiveTemp();
  testSwitchBotManufacturerNegativeTemp();
  testSwitchBotInvalidPacketsRejected();
  testSwitchBotServiceBatteryAndRegistry();
  testSt7305FramebufferPixelMapping();
  testShtc3Codec();
  testBatteryMonitorDecoding();
  testBatteryMonitorUsbPlugged();
  testDashboardViewRendering();
  testPcf85063BcdCodec();
  testPcf85063InvalidValues();
  testDashboardHeaderClock();
  testSwitchBotStaleDetection();
  testBtHomeValidPackets();
  testBtHomeNegativeTemperature();
  testBtHomeInvalidPackets();
  testBtHomeUnknownObjectIds();
  testBtHomeCombinedRegistry();
  testClimateMathReferencePoints();
  testClimateMathInvalidInput();
  testDashboardClimateLine();
  testDashboardStaleIndicator();
  testButtonDebounceAndModeTransitions();
  testDisplayModesRenderWithoutClipping();
  testBleSignalStrength();
  testSpectrumBandEdges();
  testSpectrumFft();
  testSpectrumSinePeaks();
  testSpectrumSilenceAndLevels();
  testSpectrumCaptureLifecycle();
  testSpectrumView();
  testSpectrumKeyHandling();
  testSpectrumStoredViewMode();
  testSwitchBotPacketCounter();
  testPowerSleepTimerCalculation();
  testPowerStateSerialization();
  testPowerWakeupAndStateMachine();
  testLowBatteryHysteresis();
  testLowBatteryBanner();
  testHumidityHistoryFifo();
  testClimateHistorySampleGate();
  testTemperatureHistoryEncoding();
  testTemperatureHistoryRange();
  testTemperatureHistoryRecordGate();
  testRecordsStatsView();
  testRecordsPersistence();
  testSparklineAutoScale();
  testSparklineBoundsAndClipping();
  testDashboardSparklineInsideTiles();
  testHistoryPersistence();
  testNoiseHistoryFifo();
  testNoiseSparklineBounds();
  testNoiseHistoryStatsView();
  testNoiseHistoryPersistence();
  testPreviewStatsShowNoiseCurve();
  testPreviewScenesCoverAllDisplayModes();
  testSdLogCodec();
  testNoisePercentilesSteadyTone();
  testNoisePercentilesSingleLoudBlock();
  testNoisePercentilesRamp();
  testNoisePercentilesWindowLengthAndChannels();
  testNoisePercentilesFailedWindow();
  testNoisePercentilesText();
  testDiagnosticsPercentileLineBounds();
  testSdLogPercentileColumns();
  testSdLogV4FileNames();
  testSdLogNoiseColumns();
  testSdLogEventsColumn();
  testDiagnosticsShowsNoiseEvents();
  testNoiseWindowCountsTransients();
  testTransientCounterSaturates();
  testSdLogNoiseGate();
  testNoiseWindowPowersDownForLogging();
  testSdLogSchedule();
  testAlertThresholds();
  testChimeMuteAndEdge();
  testSensorLostAlert();
  testQuietHoursWindowEdges();
  testQuietHoursSameDayAndEmptyWindow();
  testQuietHoursNeedsValidRtc();
  testQuietHoursSuppressChimeButLatchState();
  testQuietHoursHeaderMarker();
  testChimePcmSynthesis();
  testAudioStatePersistence();
  testNoiseRmsCalculation();
  testNoiseBandThresholds();
  testNoiseSamplerPowersDownOnEveryPath();
  testNoiseHardwareConstants();
  testEs7210InitEnablesAllClocks();
  testDiagnosticsNoiseIndicator();
  testMicSelfTestClassification();
  testMicSelfTestImbalance();
  testNoiseSamplerWithSelfTest();
  testDiagnosticsMicSelfTestLine();
  testPreviewDiagnosticsShowsSelfTest();
  testMicSplConversionAndClamp();
  testDerivedBandLimitsWithHysteresis();
  testLongPressDetection();
  testMicCalibrationSerialization();
  testApplyCalibrationWindow();
  testDiagnosticsCalibrationLines();
  testNoiseStatePersistence();
  testNoiseAlertLimitEdges();
  testNoiseAlertDisabledByDefault();
  testNoiseAlertHysteresis();
  testNoiseAlertInvalidReading();
  testNoiseAlertChimeSuppression();
  testNoiseAlertConfigKey();
  testNoiseAlertStepWiring();
  testNoiseAlertStatePersistence();
  testChimeAlertMask();
  testChargeFullChime();
  testNtpEpochToCalendar();
  testNtpTimezoneDst();
  testNtpRegisterEncoding();
  testNtpScheduleAndOfflineFallback();
  testNtpSystemClockRestore();
  testNtpStatePersistence();
  testDeviceConfigDefaultsAndValidValues();
  testDeviceConfigCommentsSpacesAndLineEnds();
  testDeviceConfigMissingMalformedAndOutOfRange();
  testDeviceConfigDuplicateKeysLastWins();
  testDeviceConfigFileSizeLimit();
  testDeviceConfigWiring();
  testDeviceConfigRtcRoundTrip();
  testSlotMacParser();
  testSlotMacRegistry();
  testSlotMacConfigApply();
  testSlotMacRtcRoundTrip();
  testSlotMacStatsLine();
  testShtc3HumidityOffset();
  testSlotCalibrationOffsets();
  testSensorNames();
  testNtpSyncIntervalConfig();
  testWakeIntervalConfig();
  testDiagnosticsConfigLine();
  testConfigRejectedLines();
  testConfigRejectedRtcRoundTrip();
  testFramebufferParenthesisGlyphs();
  testDiagnosticsConfigErrorLine();
  testLogRetentionAge();
  testLogRetentionDecision();
  testLogRetentionNamePattern();
  testLogRetentionConfig();
  testSdFreeMegabytes();
  testDiagnosticsSdFreeLine();
  testGoertzelDetectsOneKhzSine();
  testGoertzelRejectsOtherFrequencies();
  testGoertzelRejectsSilenceAndNoise();
  testLoopbackThresholdAndDbReporting();
  testLoopbackPassPowersDownInOrder();
  testLoopbackFailurePathsPowerDown();
  testLoopbackSkipsWhenMutedQuietOrZeroVolume();
  testLoopbackSettleDcAndTrailingSilence();
  testLoopbackToneUsesChimeVolume();
  testBootButtonLongPressInDiagnostics();
  testDiagnosticsLoopbackLine();
  testPreviewDiagnosticsShowsLoopback();
  testClapSingleClapDoesNotTrigger();
  testClapTwoClapsGapRules();
  testClapThirdClapIsIgnored();
  testClapThirdClapJustInsideTriggerWindow();
  testClapReportsOnceAndLocksOut();
  testClapContinuousLoudNoise();
  testClapSpeechLikeBursts();
  testClapBelowMinimumPeak();
  testClapTimeWrapAround();
  testClapListenerDetectsDoubleClapFromAudio();
  testClapListenerPowersDownOnEveryPath();
  testClapListenerReleaseAndReopen();
  testClapListenerReopenFailurePowersDown();
  testClapListenModeGate();
  testClapUsbPowerWatch();
  testBatteryRuntimeLinearDischarge();
  testBatteryRuntimeDaysFormat();
  testBatteryRuntimeFlatCurve();
  testBatteryRuntimeChargeStepResets();
  testBatteryRuntimeTooFewSamples();
  testBatteryRuntimeInvalidReadings();
  testBatteryRuntimeIrregularSpacingAndSlowCharge();
  testBatteryHistoryCorruptStamps();
  testBatteryHistoryPersistence();
  testDiagnosticsRuntimeLine();
  testTildeGlyphIsDrawn();
  testPreviewRuntimeScene();
  testSerialCrc32KnownVectors();
  testSerialFileNameValidation();
  testSerialParserValidCommands();
  testSerialParserRejectsUnknownCommands();
  testSerialParserRejectsLongLines();
  testSerialParserRejectsPathTraversal();
  testSerialLineAssembler();
  testSerialDumpFrame();
  testSerialDumpEmptyFile();
  testSerialDumpFailures();
  testSerialDumpStopsOnShortWrite();
testSerialConfigParser();
testSerialConfigLineFormat();
testSerialConfigFrameAndCrc();
testSerialConfigRunsWithoutMountingCard();
testSerialConfigRoundTrip();
testSerialConfigRejectedLines();
testSerialConfigStopsOnShortWrite();
testSerialConfigNegativeSmallOffset();
  testRtcDriftFirstSyncHasNoRate();
  testRtcDriftPositiveNegativeZero();
  testRtcDriftRateOver24And168Hours();
  testRtcDriftRejectsFailedSync();
  testRtcDriftRejectsUnusableReadings();
  testRtcDriftRejectsShortInterval();
  testRtcDriftReferenceAcrossDst();
  testRtcDriftSerializeSanitizes();
  testRtcDriftPersistence();
  testDiagnosticsRtcDriftLine();
  testSerialListAndInfo();
  testSerialRejectKeepsCardUnmounted();
  testSerialConsoleUsbGate();
  testDailySummaryStatistics();
  testDailySummaryRowFormat();
  testDailySummaryFileNames();
  testDailySummaryRetention();
  testDailySummaryMidnightGateCetAndCest();
  testDailySummaryOneRowPerDay();
  testDailySummaryFailedWriteRetries();
  testDailySummaryStateRoundTrip();
  testDailySummaryConsole();
  printf("All 143 firmware logic test suites passed.\n");
  return 0;
}
