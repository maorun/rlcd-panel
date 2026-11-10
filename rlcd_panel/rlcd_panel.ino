#include <Arduino.h>
#include <Wire.h>
#include <BLEDevice.h>
#include <BLEAdvertisedDevice.h>
#include <BLEScan.h>
#include <freertos/FreeRTOS.h>
#include <freertos/portmacro.h>

#include "board_pins.h"
#include "dashboard_view.h"
#include "shtc3_sensor.h"
#include "st7305_framebuffer.h"
#include "st7305_panel.h"
#include "switchbot_ble.h"

using namespace rlcd_panel;

namespace {

St7305Framebuffer g_framebuffer;
St7305Panel g_panel;
SwitchBotRegistry g_switchbot_registry;
portMUX_TYPE g_registry_mux = portMUX_INITIALIZER_UNLOCKED;
Shtc3Reading g_onboard_reading;
BLEScan* g_ble_scan = nullptr;
uint32_t g_last_refresh_ms = 0;

constexpr uint32_t BLE_SCAN_DURATION_SEC = 4;
constexpr uint32_t DISPLAY_REFRESH_INTERVAL_MS = 15000;

class SwitchBotAdvertisedCallbacks : public BLEAdvertisedDeviceCallbacks {
  void onResult(BLEAdvertisedDevice advertisedDevice) override {
    if (!advertisedDevice.haveManufacturerData()) {
      return;
    }

    const String mfg = advertisedDevice.getManufacturerData();
    SwitchBotReading reading;
    if (!SwitchBotBleParser::parseManufacturerData(
            reinterpret_cast<const uint8_t*>(mfg.c_str()),
            mfg.length(),
            reading)) {
      return;
    }

    if (advertisedDevice.haveServiceData() &&
        advertisedDevice.getServiceDataUUID().equals(BLEUUID(SWITCHBOT_SERVICE_UUID))) {
      const String svc = advertisedDevice.getServiceData();
      int8_t battery_pct = -1;
      if (SwitchBotBleParser::parseServiceDataBattery(
              SWITCHBOT_SERVICE_UUID,
              reinterpret_cast<const uint8_t*>(svc.c_str()),
              svc.length(),
              battery_pct)) {
        reading.battery_pct = battery_pct;
      }
    }

    reading.rssi = advertisedDevice.getRSSI();
    reading.updated_ms = millis();

    portENTER_CRITICAL(&g_registry_mux);
    g_switchbot_registry.upsert(reading);
    portEXIT_CRITICAL(&g_registry_mux);
  }
};

bool readOnboardShtc3(Shtc3Reading& out) {
  out = Shtc3Reading{};

  // Wake command 0x3517
  Wire.beginTransmission(I2C_ADDR_SHTC3);
  Wire.write(0x35);
  Wire.write(0x17);
  if (Wire.endTransmission() != 0) {
    return false;
  }
  delay(1);

  // Measure T first, normal mode, clock stretching disabled: 0x7866
  Wire.beginTransmission(I2C_ADDR_SHTC3);
  Wire.write(0x78);
  Wire.write(0x66);
  if (Wire.endTransmission() != 0) {
    return false;
  }
  delay(15);

  uint8_t frame[6] = {0};
  const int received = Wire.requestFrom(static_cast<int>(I2C_ADDR_SHTC3), 6);
  if (received == 6) {
    for (int i = 0; i < 6; ++i) {
      frame[i] = static_cast<uint8_t>(Wire.read());
    }
  }

  // Always send sleep command 0xB098 even if read failed
  Wire.beginTransmission(I2C_ADDR_SHTC3);
  Wire.write(0xB0);
  Wire.write(0x98);
  Wire.endTransmission();

  if (received != 6) {
    return false;
  }
  return Shtc3Codec::decodeFrame(frame, sizeof(frame), out);
}

void renderDashboard() {
  SwitchBotReading snapshot[MAX_SWITCHBOT_SENSORS];
  portENTER_CRITICAL(&g_registry_mux);
  for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
    snapshot[i] = g_switchbot_registry.slot(i);
  }
  portEXIT_CRITICAL(&g_registry_mux);

  DashboardView::render(g_framebuffer, g_onboard_reading, snapshot, MAX_SWITCHBOT_SENSORS);
  g_panel.flush(g_framebuffer);
}

SwitchBotAdvertisedCallbacks g_ble_callbacks;

} // namespace

void setup() {
  Serial.begin(115200);

  pinMode(PIN_BTN_KEY, INPUT_PULLUP);
  pinMode(PIN_BTN_BOOT, INPUT_PULLUP);

  Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL);
  g_panel.begin();

  BLEDevice::init("rlcd-panel");
  g_ble_scan = BLEDevice::getScan();
  if (g_ble_scan != nullptr) {
    g_ble_scan->setAdvertisedDeviceCallbacks(&g_ble_callbacks, true);
    g_ble_scan->setActiveScan(true);
    g_ble_scan->setInterval(100);
    g_ble_scan->setWindow(99);
  }

  readOnboardShtc3(g_onboard_reading);
  renderDashboard();
  g_last_refresh_ms = millis();
}

void loop() {
  if (g_ble_scan != nullptr) {
    g_ble_scan->start(BLE_SCAN_DURATION_SEC, false);
    g_ble_scan->clearResults();
  }

  const uint32_t now = millis();
  if (now - g_last_refresh_ms >= DISPLAY_REFRESH_INTERVAL_MS ||
      digitalRead(PIN_BTN_KEY) == LOW) {
    readOnboardShtc3(g_onboard_reading);
    renderDashboard();
    g_last_refresh_ms = now;
  }

  delay(250);
}
