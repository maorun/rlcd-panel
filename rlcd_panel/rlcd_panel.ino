#include <Arduino.h>
#include <Wire.h>
#include <BLEDevice.h>
#include <BLEAdvertisedDevice.h>
#include <BLEScan.h>
#include <atomic>
#include <freertos/FreeRTOS.h>
#include <freertos/portmacro.h>

#include "audio_alert.h"
#include "audio_loopback.h"
#include "audio_spectrum.h"
#include "board_pins.h"
#include "battery_monitor.h"
#include "button_handler.h"
#include "clap_detector.h"
#include "dashboard_view.h"
#include "device_config.h"
#include "es7210_mic.h"
#include "firmware_version.h"
#include "ntp_sync.h"
#include "pcf85063_rtc.h"
#include "power_manager.h"
#include "sd_logger.h"
#include "serial_console.h"
#include "shtc3_sensor.h"
#include "st7305_framebuffer.h"
#include "st7305_panel.h"
#include "switchbot_ble.h"
#include "bthome_ble.h"
#include "atc_ble.h"

using namespace rlcd_panel;

namespace {

// Writes console replies to the USB serial port.
struct SerialSink {
  size_t write(const uint8_t* data, size_t len) {
    return Serial.write(data, len);
  }
};

St7305Framebuffer g_framebuffer;
St7305Panel g_panel;
// Cycle counter tracking state; separate from g_state so a rejected state restore keeps it.
RTC_DATA_ATTR CycleTracker::RtcState g_cycle_rtc;
// Scan time that the last early stop saved (ms). Only the runtime estimate reads it.
RTC_DATA_ATTR uint32_t g_scan_saved_ms = 0;
ScanCompletion g_scan_completion;
CycleTracker g_cycles;
PersistentState g_state; // registry, battery, and view mode; saved to RTC memory before sleep
portMUX_TYPE g_registry_mux = portMUX_INITIALIZER_UNLOCKED;
Pcf85063Rtc g_rtc;
RtcTime g_rtc_time;
Shtc3Reading g_onboard_reading;
BLEScan* g_ble_scan = nullptr;
DisplayModeController g_mode_controller;
std::atomic<bool> g_scan_running{false};
PowerConfig g_power_config;
int32_t g_sd_free_mb = -1; // free MicroSD space in MB of this wake cycle; -1 = card not read
NoiseReading g_noise; // measured at most once per wake cycle: diagnostics view or hourly SD record
bool g_noise_attempted = false; // a failed window is not retried in the same wake cycle
ResetReason g_reset_reason = ResetReason::UNKNOWN; // reason of the last boot
MicCalibration g_mic_cal;       // loaded from NVS in setup(); the default when absent or corrupt
bool g_mic_cal_busy = false;    // the 10 s calibration window runs
bool g_mic_cal_failed = false;  // the last calibration of this wake cycle was rejected
LoopbackResult g_loopback;      // last loopback test of this wake cycle
BootButtonController g_boot_button;
SpectrumAnalyzer g_spectrum_analyzer; // about 7 KB of buffers: static, not on the task stack
SpectrumReading g_spectrum;           // last capture of this wake cycle; not kept in deep sleep
bool g_spectrum_attempted = false;    // the view captured once since it was entered

const DeviceConfig& config() {
  return g_state.config;
}

QuietHoursWindow quietHours() {
  return QuietHoursWindow::fromHours(config().quiet_hours_start_hour, config().quiet_hours_end_hour);
}

// The stored calibration plus the SPL offset from config.ini.
MicCalibration activeMicCal() {
  return g_mic_cal.withExtraOffset(config().mic_spl_offset_db);
}

// After a render the board stays awake this long so more KEY presses can cycle the view
// without a new wakeup.
constexpr uint32_t KEY_GRACE_MS = 3000;
// Watchdog: a scan that never reports completion is stopped after its duration plus this margin.
constexpr uint32_t SCAN_WATCHDOG_MARGIN_MS = 3000;

// Deep sleep resets millis(), so all timestamps use the RTC-backed clock instead.
uint32_t nowMs() {
  return PowerPlatform::clockMs();
}

class SwitchBotAdvertisedCallbacks : public BLEAdvertisedDeviceCallbacks {
  // BTHome v2 packets carry no MAC address, so the BLE address identifies the sensor.
  static void handleBtHome(BLEAdvertisedDevice& advertisedDevice) {
    // getNative() returns the address in big-endian order on the Bluedroid stack. Keep this order
    // with another BLE stack, because the slot identity uses the MAC bytes.
    const String svc = advertisedDevice.getServiceData();
    SwitchBotReading reading;
    if (!BtHomeBleParser::parseServiceData(
            BTHOME_SERVICE_UUID,
            advertisedDevice.getAddress().getNative(),
            reinterpret_cast<const uint8_t*>(svc.c_str()),
            svc.length(),
            reading)) {
      return;
    }
    reading.rssi = SwitchBotBleParser::clampRssi(advertisedDevice.getRSSI());
    reading.updated_ms = nowMs();

    portENTER_CRITICAL(&g_registry_mux);
    g_state.registry.upsert(reading, BleFormat::BTHOME);
    g_scan_completion.onPacket(g_state.registry, reading);
    portEXIT_CRITICAL(&g_registry_mux);
  }

  // Xiaomi sensors with ATC or pvvx firmware carry their MAC address in the packet.
  static void handleAtc(BLEAdvertisedDevice& advertisedDevice) {
    const String svc = advertisedDevice.getServiceData();
    SwitchBotReading reading;
    if (!AtcBleParser::parseServiceData(
            ATC_SERVICE_UUID,
            reinterpret_cast<const uint8_t*>(svc.c_str()),
            svc.length(),
            reading)) {
      return;
    }
    reading.rssi = SwitchBotBleParser::clampRssi(advertisedDevice.getRSSI());
    reading.updated_ms = nowMs();

    portENTER_CRITICAL(&g_registry_mux);
    g_state.registry.upsert(reading, BleFormat::ATC);
    g_scan_completion.onPacket(g_state.registry, reading);
    portEXIT_CRITICAL(&g_registry_mux);
  }

  void onResult(BLEAdvertisedDevice advertisedDevice) override {
    if (advertisedDevice.haveServiceData() &&
        advertisedDevice.getServiceDataUUID().equals(BLEUUID(BTHOME_SERVICE_UUID))) {
      handleBtHome(advertisedDevice);
      return;
    }
    if (advertisedDevice.haveServiceData() &&
        advertisedDevice.getServiceDataUUID().equals(BLEUUID(ATC_SERVICE_UUID))) {
      handleAtc(advertisedDevice);
      return;
    }

    const String mfg = advertisedDevice.haveManufacturerData() ? advertisedDevice.getManufacturerData() : String();
    const bool has_switchbot_svc =
        advertisedDevice.haveServiceData() &&
        advertisedDevice.getServiceDataUUID().equals(BLEUUID(SWITCHBOT_SERVICE_UUID));
    const String svc = has_switchbot_svc ? advertisedDevice.getServiceData() : String();
    if (mfg.length() == 0 && svc.length() == 0) {
      return;
    }

    // Manufacturer Data wins (WoIOSensor); the Service Data gives the battery. A Meter or Meter Plus
    // has no valid Manufacturer Data, so its Service Data and the BLE address give the reading.
    // getNative() is big-endian on the Bluedroid stack, like the MAC in the WoIOSensor packet.
    SwitchBotReading reading;
    if (!SwitchBotBleParser::parseAdvertisement(
            reinterpret_cast<const uint8_t*>(mfg.c_str()),
            mfg.length(),
            reinterpret_cast<const uint8_t*>(svc.c_str()),
            svc.length(),
            advertisedDevice.getAddress().getNative(),
            reading)) {
      return;
    }

    // Valid Manufacturer Data means a WoIOSensor packet; otherwise the Service Data of a Meter gave the reading.
    SwitchBotReading mfg_probe;
    const BleFormat format = SwitchBotBleParser::parseManufacturerData(reinterpret_cast<const uint8_t*>(mfg.c_str()), mfg.length(), mfg_probe)
                                 ? BleFormat::WOIO
                                 : BleFormat::METER;
    reading.rssi = SwitchBotBleParser::clampRssi(advertisedDevice.getRSSI());
    // Keep this timestamp at or before the nowMs() read in renderDashboard() so a fresh reading
    // never appears to lie in the future (unsigned subtraction would flag it stale).
    reading.updated_ms = nowMs();

    portENTER_CRITICAL(&g_registry_mux);
    g_state.registry.upsert(reading, format);
    g_scan_completion.onPacket(g_state.registry, reading);
    portEXIT_CRITICAL(&g_registry_mux);
  }
};

void onScanComplete(BLEScanResults) {
  // Runs in the BLE task: clearing here avoids racing the scan callback from the loop task.
  if (g_ble_scan != nullptr) {
    g_ble_scan->clearResults();
  }
  g_scan_running = false;
}

Shtc3Result readOnboardShtc3(Shtc3Reading& out) {
  out = Shtc3Reading{};

  // Wake command 0x3517
  Wire.beginTransmission(I2C_ADDR_SHTC3);
  Wire.write(0x35);
  Wire.write(0x17);
  if (Wire.endTransmission() != 0) {
    return Shtc3Result::NACK;
  }
  delay(1);

  // Measure T first, normal mode, clock stretching disabled: 0x7866
  Wire.beginTransmission(I2C_ADDR_SHTC3);
  Wire.write(0x78);
  Wire.write(0x66);
  if (Wire.endTransmission() != 0) {
    return Shtc3Result::NACK;
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

  return Shtc3Codec::classify(true, received == 6 ? 6 : 0, frame, out, config().temp_offset_c,
                              config().rh_offset_pct);
}

// Events that happen before the RTC is read (config load, release of the empty protection) wait here
// until refreshSensors() has the time. -1 means none.
int32_t g_pending_config_rejects = -1;
bool g_pending_protection_release = false;

void updateRtc() {
  if (!g_rtc.present()) {
    g_rtc.begin(Wire); // retry after a failed probe at boot
  }
  if (g_rtc.readTime(g_rtc_time) || !g_rtc.present() || !g_rtc_time.oscillator_stopped) {
    return;
  }
  // Oscillator-stop flag set (first boot or battery loss): seed the clock from the build time.
  RtcTime seed;
  if (Pcf85063Codec::fromCompileStamp(__DATE__, __TIME__, seed) && g_rtc.setTime(seed)) {
    g_state.rtc_drift = RtcDriftState{}; // the old baseline is void: the next sync measures nothing
    g_rtc.readTime(g_rtc_time);
  }
}

// Sets the RTC from NTP when a sync is due. The radio runs only inside this call. A missing
// configuration, a failed connection or a timeout leaves the RTC unchanged and starts a backoff.
// The call is bounded to about 14 s. Returns true when the RTC got a new time.
bool syncNtpTime(bool key_wakeup) {
  const TimeZone tz{RLCD_TZ_STD_OFFSET_S, RLCD_TZ_EU_DST != 0};
  const bool credentials = NtpSchedule::hasCredentials(RLCD_WIFI_SSID, RLCD_WIFI_PASSWORD);
  const bool battery_low = g_state.battery.valid && g_state.battery.low_battery;
  if (NtpSchedule::decide(g_state.ntp, nowMs(), key_wakeup, credentials, g_rtc.present(), battery_low,
                         config().ntp_sync_interval_h) !=
      NtpDecision::ATTEMPT) {
    return false;
  }
  const NtpResult result = NtpClient::sync(g_rtc, RLCD_WIFI_SSID, RLCD_WIFI_PASSWORD, tz, g_state.rtc_drift);
  NtpSchedule::record(g_state.ntp, nowMs(), result == NtpResult::OK);
  if (result == NtpResult::OK) {
    updateRtc(); // the event takes the new time
  }
  g_state.events.record(g_rtc_time, EventCode::NTP_SYNC, static_cast<uint16_t>(result));
  Serial.printf("NTP sync result: %u\n", static_cast<unsigned>(result));
  return result == NtpResult::OK;
}

// Measures one 5 s noise window, at most once per wake cycle. The diagnostics view and the hourly
// SD record request it. The microphones stay off otherwise, so they cost no battery there. The ADC is powered down again
// before this function returns. The band is stored for the hysteresis of the next window.
void measureNoiseOnce() {
  if (g_noise_attempted) {
    return;
  }
  g_noise_attempted = true;
  Es7210Hal hal;
  g_noise = NoiseSampler::measure(hal, g_state.noise_band, NoiseSampler::WINDOW_MS, activeMicCal());
  if (g_noise.valid) {
    g_state.noise_band = g_noise.band;
    Serial.printf("Noise window: %u loud events\n", static_cast<unsigned>(g_noise.events));
    if (g_noise.levels_valid) {
      Serial.printf(
          "Noise levels: L10 RMS %u, L90 RMS %u\n", static_cast<unsigned>(g_noise.l10_rms),
          static_cast<unsigned>(g_noise.l90_rms));
    }
  } else {
    Serial.println("Noise measurement failed");
  }
}

// Registry copy with the slot offsets of config.ini applied. The alerts, the history, the CSV log
// and the views read this copy; the stored registry keeps the raw readings. Call it only after
// scanOnce() stopped the scan, or inside g_registry_mux: the BLE callback writes the registry.
SwitchBotRegistry calibratedRegistry() {
  return g_state.registry.calibrated(config().slot_temp_offset_c, config().slot_rh_offset_pct);
}

// Every flush goes through here, so display_invert applies to each screen.
void flushFramebuffer() {
  if (config().display_invert != 0) {
    g_framebuffer.invert();
  }
  g_panel.flush(g_framebuffer);
}

void renderDashboard() {
  SwitchBotReading snapshot[MAX_SWITCHBOT_SENSORS];
  portENTER_CRITICAL(&g_registry_mux);
  const SwitchBotRegistry calibrated = calibratedRegistry();
  for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
    snapshot[i] = calibrated.slot(i);
  }
  portEXIT_CRITICAL(&g_registry_mux);

  char clock_text[8];
  Pcf85063Codec::formatClock(g_rtc_time, clock_text, sizeof(clock_text));

  SystemInfo info;
  info.free_heap_bytes = ESP.getFreeHeap();
  // System time since power-on. A reset that keeps the RTC clock does not restart it.
  info.uptime_ms = PowerPlatform::clockMs64();
  info.noise = g_noise;
  info.mic_cal = activeMicCal();
  info.config_from_sd = g_state.config_from_sd;
  info.config_rejected = g_state.config_rejects.count;
  info.mic_cal_busy = g_mic_cal_busy;
  info.mic_cal_failed = g_mic_cal_failed;
  info.quiet_hours = quietHours().isActive(g_rtc_time);
  info.ventilate = (g_state.alert_mask & ALERT_VENTILATE) != 0;
  info.ventilate_tiles = g_state.ventilate_tiles;
  info.sensor_battery_latch = g_state.sensor_battery_latch;
  info.loopback = g_loopback;
  info.spectrum = g_spectrum;
  info.chart_sensor = g_mode_controller.chartSensor();
  info.runtime = g_state.battery_history.estimate(g_state.battery, g_scan_saved_ms);
  info.rtc_drift = g_state.rtc_drift;
  info.sd_free_mb = g_sd_free_mb;
  info.sd_status = g_state.sd_status;
  info.cycle_tenths = g_cycles.cycleTenths();
  info.shtc3 = g_state.shtc3;

  DashboardView::renderMode(
      g_framebuffer,
      g_mode_controller.mode(),
      g_onboard_reading,
      g_state.battery,
      snapshot,
      MAX_SWITCHBOT_SENSORS,
      info,
      clock_text,
      nowMs(),
      &g_state.history,
      &g_state.noise_history,
      &g_state.config);
  flushFramebuffer();
}

// Adds the noise level to the diagnostics view after the first render, so the 5 s window never
// delays the display, the chime or the SD log. `measure` is false on timer wakeups. A measurement
// in the KEY grace loop outlasts the 3 s grace window; KEY presses during it are not seen.
void measureNoiseAndRedraw(bool measure) {
  if (measure && !g_noise_attempted &&
      g_mode_controller.mode() == DisplayMode::SYSTEM_DIAGNOSTICS) {
    measureNoiseOnce();
    renderDashboard();
  }
}

// Captures 1 s of audio for the spectrum view and redraws it. `capture` is false on timer
// wakeups, so the microphones stay off there. The first call after entry to the view captures; a
// KEY press in the view forces a new capture. The ADC is powered down again before the capture
// returns. The capture outlasts the 3 s grace window start: KEY presses during it are not seen.
void captureSpectrumAndRedraw(bool capture, bool force = false) {
  if (!capture || g_mode_controller.mode() != DisplayMode::AUDIO_SPECTRUM ||
      (g_spectrum_attempted && !force)) {
    return;
  }
  g_spectrum_attempted = true;
  Es7210Hal hal;
  g_spectrum = SpectrumCapture::measure(hal, g_spectrum_analyzer);
  if (!g_spectrum.valid) {
    Serial.println("Spectrum capture failed");
  }
  renderDashboard();
}

// Measures the noise floor of a quiet room for 10 s and stores it in NVS. The floor is measured
// without band limits. A failed window or a floor outside the accepted range keeps the old
// calibration. The wake cycle takes about 11 s longer; KEY presses during it are not seen.
void calibrateMicrophone() {
  // The window starts after the finger left KEY, so the release click stays out of the floor.
  const uint32_t wait_start = millis();
  while (digitalRead(PIN_BTN_KEY) == LOW && millis() - wait_start < 3000) {
    delay(10);
  }
  delay(300);
  g_mic_cal_failed = false;
  g_mic_cal_busy = true;
  renderDashboard(); // shows "MIC CAL: MEASURING"
  Es7210Hal hal;
  const NoiseReading floor = NoiseSampler::measure(
      hal, NoiseBand::QUIET, MicCalibration::CALIBRATION_WINDOW_MS, MicCalibration{});
  g_mic_cal_busy = false;
  if (applyCalibrationWindow(floor, g_mic_cal, g_noise, g_state.noise_band, MicCalibrationStore::save)) {
    g_noise_attempted = true;
  } else {
    g_mic_cal_failed = true;
    Serial.println("Mic calibration failed");
  }
  renderDashboard();
}

// Plays a 1 kHz tone for 500 ms at the chime volume and records it over the shared I2S bus. A
// muted board, quiet hours and chime volume 0 skip the test. The amplifier and both codecs are
// off again before this function returns. The test takes about 1 s; KEY presses during it are
// not seen.
void runLoopbackTest() {
  const bool quiet = quietHours().isActive(g_rtc_time);
  const uint8_t volume = config().chime_volume_pct;
  if (AudioLoopback::skipReason(g_state.muted, quiet, volume) == LoopbackStatus::NOT_RUN) {
    g_loopback.status = LoopbackStatus::RUNNING;
    renderDashboard(); // shows "AUDIO LOOP: RUNNING"
  }
  LoopbackHal hal;
  g_loopback = AudioLoopback::run(hal, volume, g_state.muted, quiet);
  if (!g_loopback.passed() && !g_loopback.skipped()) {
    Serial.println("Audio loopback failed");
  }
  renderDashboard();
}

SwitchBotAdvertisedCallbacks g_ble_callbacks;

void initBle() {
  BLEDevice::init("rlcd-panel");
  g_ble_scan = BLEDevice::getScan();
  if (g_ble_scan != nullptr) {
    g_ble_scan->setAdvertisedDeviceCallbacks(&g_ble_callbacks, true);
    g_ble_scan->setActiveScan(true);
    g_ble_scan->setInterval(100);
    g_ble_scan->setWindow(99);
  }
}

// Blocks for one scan. The deep sleep cycle needs no concurrent work during the scan.
void scanOnce(bool full_scan) {
  g_scan_saved_ms = 0;
  if (g_ble_scan == nullptr) {
    return;
  }
  const uint32_t duration_s = g_power_config.sanitized().scan_duration_s;
  // Set the flag first: a fast completion callback in the BLE task must not be overwritten.
  g_scan_completion.begin(full_scan);
  portENTER_CRITICAL(&g_registry_mux);
  g_state.registry.resetRejects();
  portEXIT_CRITICAL(&g_registry_mux);
  g_scan_running = true;
  if (!g_ble_scan->start(duration_s, onScanComplete, false)) {
    g_scan_running = false;
    return;
  }
  const uint32_t started = millis();
  while (g_scan_running && millis() - started < duration_s * 1000 + SCAN_WATCHDOG_MARGIN_MS) {
    delay(10);
    portENTER_CRITICAL(&g_registry_mux);
    const bool stop = g_scan_completion.canStop(g_state.registry, millis() - started);
    portEXIT_CRITICAL(&g_registry_mux);
    if (stop) {
      g_scan_saved_ms = ScanCompletion::savedMs(duration_s, millis() - started);
      break;
    }
  }
  if (g_scan_running) {
    g_ble_scan->stop();
    g_scan_running = false;
    delay(100); // let a late completion callback from the BLE task finish
  }
}

// Adds the discharge of this wake cycle. NVS is written only when update() reports a charge event
// or a whole cycle. The tracking state goes to RTC memory; the NVS total is the reference.
void updateCycles() {
  CycleStore store;
  g_cycles.step(g_state.battery, store);
  g_cycle_rtc = g_cycles.toRtc();
}

void refreshSensors() {
  g_state.shtc3.record(readOnboardShtc3(g_onboard_reading));
  // After a reset of the RTC state the latch starts off: a cell at 16..19 % raises no alert until
  // it reaches the set threshold.
  const bool was_low = g_state.battery.low_battery;
  g_state.battery = BatteryMonitor::read(config().battery_offset_mv);
  g_state.battery.low_battery = BatteryMonitor::updateLowBattery(g_state.battery, was_low);
  g_state.battery_history.record(nowMs(), g_state.battery);
  updateCycles();
  updateRtc();
  if (was_low != g_state.battery.low_battery) {
    const uint16_t pct = g_state.battery.percentage > 0 ? static_cast<uint16_t>(g_state.battery.percentage) : 0;
    g_state.events.record(g_rtc_time, g_state.battery.low_battery ? EventCode::BATTERY_LOW_SET : EventCode::BATTERY_LOW_CLEAR, pct);
  }
  if (g_pending_config_rejects >= 0) {
    g_state.events.record(g_rtc_time, EventCode::CONFIG_LOAD, static_cast<uint16_t>(g_pending_config_rejects));
    g_pending_config_rejects = -1;
  }
  if (g_pending_protection_release) {
    g_state.events.record(g_rtc_time, EventCode::EMPTY_PROTECTION, 0);
    g_pending_protection_release = false;
  }
  // One sample per hour and sensor; the gate lives in the persisted state.
  g_state.history.record(
      nowMs(),
      g_onboard_reading.valid,
      g_onboard_reading.humidity_pct,
      calibratedRegistry(),
      g_onboard_reading.temperature_c,
      config().staleTimeoutMs());
  // The tile badges have no chime and do not depend on the alert mask. The latch updates before the
  // first render, so the display shows the current state.
  // The BLE scan is stopped here, so the registry copy needs no g_registry_mux.
  SwitchBotReading tiles[MAX_SWITCHBOT_SENSORS];
  const SwitchBotRegistry calibrated = calibratedRegistry();
  for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
    tiles[i] = calibrated.slot(i);
  }
  g_state.ventilate_tiles = VentilateLatch::update(
      g_state.ventilate_tiles, g_onboard_reading, tiles, MAX_SWITCHBOT_SENSORS, nowMs(),
      config().ventilate_limit_rh, config().staleTimeoutMs());
  // The sensor battery latch updates before the first render. The chime follows in chimeOnSensorBattery().
  g_state.sensor_battery_latch = SensorBatteryLatch::update(
      g_state.sensor_battery_latch, tiles, MAX_SWITCHBOT_SENSORS, nowMs(), config().sensor_battery_limit_pct,
      config().staleTimeoutMs());
}

void playChime(uint8_t bits) {
  if (bits != ALERT_NONE) {
    Es8311Hal hal;
    if (!AudioSequencer::play(hal, bits, config().chime_volume_pct)) {
      Serial.println("Chime failed");
    }
  }
}

// Stores the new mask and plays the chimes of the step. The amplifier is off again before this
// function returns.
void applyAlertStep(const AlertEvaluator::Step& step) {
  const uint8_t changed = static_cast<uint8_t>((g_state.alert_mask ^ step.mask) & ALERT_MASK_ALL);
  for (uint8_t bit = 1; bit != 0 && bit <= ALERT_MASK_ALL; bit = static_cast<uint8_t>(bit << 1)) {
    if ((changed & bit) != 0) {
      g_state.events.record(g_rtc_time, (step.mask & bit) != 0 ? EventCode::ALERT_RISE : EventCode::ALERT_FALL, bit);
    }
  }
  // A new rising edge starts the reminder timer (chime_repeat_h), also when the sound is suppressed.
  g_state.last_chime_ms = AlertEvaluator::edgeStamp(
      g_state.alert_mask, step.mask, config().chime_alert_mask, g_state.last_chime_ms, nowMs());
  g_state.alert_mask = step.mask;
  playChime(step.chime);
}

// Checks the hourly noise window against the limit of config.ini. It runs after the window ended,
// so the chime never plays while the microphones record. A failed window holds the latch. The
// microphones are not powered on for this check.
void chimeOnNoise(bool have_noise, const MicCalibration& cal) {
  const int level_db = have_noise ? cal.splDb(g_noise.rms) : 0;
  applyAlertStep(NoiseAlertEvaluator::step(
      g_state.muted, g_state.alert_mask, have_noise, level_db, config().noise_alert_limit_db,
      quietHours().isActive(g_rtc_time), config().chime_alert_mask));
}

void logDailySummary(uint32_t now, bool card_ok);
void logEvents(bool card_ok);

// Appends one hourly record to the MicroSD card. A missing card or a failed write never blocks
// the cycle; the next attempt follows after a short retry interval. The wake cycle that writes the
// record, also a timer wakeup, runs one 5 s noise window first. It is skipped with a low battery
// alert, and then the noise fields stay empty. Other wake cycles keep the microphones off, except
// the clap listener on USB power.
// The window outlasts the 3 s KEY grace window start: KEY presses during it are not seen. A failed
// window gives a row with empty noise fields; the record is not repeated.
void logToSdCard() {
  const uint32_t now = nowMs();
  const bool due = SdLogCodec::logDue(g_state.has_logged, g_state.last_log_ms, now);
  if (!due || !g_rtc_time.valid) {
    return;
  }
  bool have_noise = false;
  bool noise_measured = false;
  // The card check comes last: a missing card must not cost a 5 s window on each 10 min retry.
  if (SdLogCodec::noiseMeasureWanted(
          g_state.has_logged, g_state.last_log_ms, now, g_rtc_time.valid, g_state.battery) &&
      SdLogger::cardPresent()) {
    measureNoiseOnce();
    noise_measured = true;
    have_noise = g_noise.valid;
    if (g_mode_controller.mode() == DisplayMode::SYSTEM_DIAGNOSTICS) {
      renderDashboard(); // the diagnostics view shows the new level
    }
  }
  SwitchBotReading snapshot[MAX_SWITCHBOT_SENSORS];
  const SwitchBotRegistry calibrated = calibratedRegistry();
  for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
    snapshot[i] = calibrated.slot(i);
  }
  const MicCalibration cal = activeMicCal();
  if (noise_measured) {
    chimeOnNoise(have_noise, cal); // after the window: the chime never overlaps the recording
  }
  int32_t free_mb = -1;
  const SdLogResult result = SdLogger::append(
      g_rtc_time, g_state.battery, g_onboard_reading, snapshot, MAX_SWITCHBOT_SENSORS, now,
      have_noise ? &g_noise : nullptr, &cal, config().log_retention_months, &free_mb,
      config().staleTimeoutMs());
  g_sd_free_mb = free_mb; // -1 after a failed mount, so the view never shows an old value
  if (result != SdLogResult::NO_TIME) { // no card access: the stored status stays
    g_state.setSdStatus(SdStatusCodec::fromLog(result, free_mb, g_state.sd_status), nowMs()); // after the noise window
  }
  if (g_mode_controller.mode() == DisplayMode::SYSTEM_DIAGNOSTICS) {
    renderDashboard(); // the diagnostics view shows the free space after the write
  }
  g_state.has_logged = true;
  if (result == SdLogResult::OK) {
    g_state.last_log_ms = now;
    // One value per written record, so the curve has one sample per hour. A failed window is an
    // invalid hour. A skipped window (low battery, no card) adds nothing.
    if (noise_measured) {
      if (have_noise) {
        g_state.noise_history.push(NoiseHistory::encode(cal.splDb(g_noise.rms)));
      } else {
        g_state.noise_history.pushInvalid();
      }
    }
  } else {
    g_state.last_log_ms = SdLogCodec::retryStamp(now);
    Serial.printf("SD log failed: %u\n", static_cast<unsigned>(result));
  }
  const bool card_ok = result != SdLogResult::MOUNT_FAILED && result != SdLogResult::NO_CARD;
  logDailySummary(now, card_ok);
  logEvents(card_ok);
}

// Appends the events of the ring to the yearly events file at the hourly write. The ring is cleared
// only after a successful write, so a failed write keeps the events for the next attempt. A card
// that did not mount for the hourly write skips the second mount.
void logEvents(bool card_ok) {
  if (!card_ok || g_state.events.size() == 0) {
    return;
  }
  const SdLogResult result = SdLogger::appendEvents(g_rtc_time, g_state.events);
  if (!EventFlush::apply(g_state.events, result)) {
    Serial.printf("SD events log failed: %u\n", static_cast<unsigned>(result));
  }
}
// Writes the daily row at the first hourly write after local midnight. The RTC holds local time, so
// CET and CEST need no case. The 24 h histories in the RTC state are the source. A failed write leaves
// the day unstamped, and the next hourly write retries. The first run and a stale history only stamp
// the day. The row covers the last 24 hourly samples, not the calendar day: the first write after
// midnight ends the day, and a late write after a card gap holds the newest samples. A card that did
// not mount for the hourly write skips the second mount.
void logDailySummary(uint32_t now, bool card_ok) {
  const DailyAction action = DailyPlanner::decide(g_state.daily, g_rtc_time, g_state.history.freshWithin24h(now));
  if (action == DailyAction::MARK) {
    g_state.daily.markDone(g_rtc_time);
  } else if (action == DailyAction::WRITE && card_ok) {
    const SdLogResult result = SdLogger::appendDaily(g_rtc_time, g_state.history, g_state.noise_history);
    if (result == SdLogResult::OK) {
      g_state.daily.markDone(g_rtc_time);
    } else {
      Serial.printf("SD daily log failed: %u\n", static_cast<unsigned>(result));
    }
  }
}

AlertLimits alertLimits() {
  AlertLimits limits;
  limits.freezer_trigger_c = config().freeze_alert_limit_c;
  limits.humidity_trigger_pct = static_cast<float>(config().mold_alert_limit_rh);
  limits.outdoor_slot = config().outdoor_slot;
  limits.climate_tiles = config().climate_alert_tiles;
  limits.stale_timeout_ms = config().staleTimeoutMs();
  return limits;
}

// Reads /sdcard/config.ini on power-on and on a KEY wakeup, and keeps the result in the RTC state.
// A timer wakeup reuses the stored copy, so the card stays unmounted there. A card without a usable
// file gives the defaults. A card that does not mount keeps the previous settings, so a transient
// error never resets them; after a power-on they are the defaults anyway. This runs before the
// first sensor read, so the offset applies at once.
void loadConfigFromCard(WakeupCause cause) {
  if (!PowerManager::needsConfigLoad(cause)) {
    return;
  }
  DeviceConfig loaded;
  ConfigRejects rejects;
  int32_t free_mb = -1;
  const SdConfigResult result = SdLogger::loadConfig(loaded, &free_mb, &rejects);
  g_state.setSdStatus(SdStatusCodec::fromConfig(result, g_state.sd_status), nowMs());
  if (result == SdConfigResult::NO_CARD || result == SdConfigResult::MOUNT_FAILED) {
    return;
  }
  g_sd_free_mb = free_mb;
  portENTER_CRITICAL(&g_registry_mux);
  g_state.applyConfig(loaded, result == SdConfigResult::LOADED, rejects);
  portEXIT_CRITICAL(&g_registry_mux);
  if (result == SdConfigResult::LOADED) {
    g_pending_config_rejects = rejects.count; // recorded once the RTC time is known
  }
}

// Evaluates the climate thresholds and the sensor dropout, and sounds a chime for each alert that
// switched on. A valid sensor slot that went stale sets ALERT_SENSOR_LOST. The mask is stored even
// while muted or in quiet hours, so unmuting or the end of the quiet hours never replays an old
// alert. The amplifier is off again before this function returns, so deep sleep draws no speaker
// current.
void chimeOnAlerts() {
  SwitchBotReading snapshot[MAX_SWITCHBOT_SENSORS];
  const SwitchBotRegistry calibrated = calibratedRegistry();
  for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
    snapshot[i] = calibrated.slot(i);
  }
  const uint8_t climate = AlertEvaluator::evaluate(
      g_state.alert_mask, g_onboard_reading, snapshot, MAX_SWITCHBOT_SENSORS, nowMs(), alertLimits());
  // Quiet hours suppress the sound only. The mask is stored, so no chime replays later. The noise
  // bit stays as it is: only the hourly noise window changes it.
  applyAlertStep(AlertEvaluator::climateStep(
      g_state.muted, g_state.alert_mask, climate, quietHours().isActive(g_rtc_time), config().chime_alert_mask));
}

// Sounds one chime when the first external sensor latch switches on. The latch itself updates in
// refreshSensors(). Mute and quiet hours suppress the sound only; the mask is stored.
void chimeOnSensorBattery() {
  applyAlertStep(AlertEvaluator::batteryStep(
      g_state.muted, g_state.alert_mask, g_state.sensor_battery_latch, quietHours().isActive(g_rtc_time),
      config().chime_alert_mask));
}

// Sounds one chime when the cell reaches 100 % on USB power. The latch releases below 95 % and on
// battery power. Mute and quiet hours suppress the sound only; the mask is stored.
void chimeOnChargeFull() {
  applyAlertStep(AlertEvaluator::chargeStep(
      g_state.muted, g_state.alert_mask, g_state.battery, quietHours().isActive(g_rtc_time),
      config().chime_alert_mask));
}

// Reminder chime of the lowest active alert after chime_repeat_h hours (AlertEvaluator::repeat). The
// stamp advances also when mute, quiet hours or chime_alert_mask suppress the sound.
void chimeRepeat() {
  const AlertEvaluator::Repeat r = AlertEvaluator::repeat(
      g_state.muted, g_state.alert_mask, quietHours().isActive(g_rtc_time), config().chime_alert_mask,
      config().chime_repeat_h, g_state.last_chime_ms, nowMs());
  g_state.last_chime_ms = r.stamp;
  playChime(r.chime);
}

} // namespace

void setup() {
  Serial.begin(115200);

  PowerPlatform::releasePinsAfterSleep(); // KEY and display pins stayed held during deep sleep
  pinMode(PIN_BTN_KEY, INPUT_PULLUP);
  pinMode(PIN_BTN_BOOT, INPUT_PULLUP);

  g_reset_reason = PowerPlatform::resetReason();
  {
    // Writes NVS only after an abnormal reset; a deep sleep wakeup never writes.
    ResetCountStore reset_store;
    AbnormalResetCounter::onBoot(g_reset_reason, reset_store);
  }

  WakeupCause cause = PowerPlatform::wakeupCause();
  PowerCycle cycle;
  cycle.begin(cause);

  // A failed restore (blank or corrupt RTC memory) behaves like a power-on: scan at once.
  if (!PowerPlatform::loadState(cause, g_state) ||
      !PowerManager::timestampsConsistent(g_state, nowMs())) {
    g_state = PersistentState{};
    cause = WakeupCause::POWER_ON;
    cycle.begin(cause);
  }
  g_mode_controller.setMode(g_state.mode);
  g_mode_controller.setChartSensor(g_state.chart_sensor);
  g_cycles.fromRtc(g_cycle_rtc); // blank memory after a power-on keeps the empty tracker

  // Empty battery protection: check the cell before any module starts. A set latch skips the BLE
  // scan, the audio, the MicroSD card and Wi-Fi; the board only reads the voltage and sleeps again.
  const bool was_protected = g_state.battery.empty_protection;
  const BatteryReading protect_reading = BatteryMonitor::readAveraged(config().battery_offset_mv);
  g_state.battery.empty_protection = BatteryMonitor::updateEmptyProtection(protect_reading, was_protected);
  if (g_state.battery.empty_protection && !was_protected) {
    g_state.events.record(g_rtc_time, EventCode::EMPTY_PROTECTION, 1); // the RTC is not read here: no time
  } else if (was_protected && !g_state.battery.empty_protection) {
    g_pending_protection_release = true;
  }
  if (!PowerManager::modulesAllowed(g_state.battery)) {
    ++g_state.boot_count;
    g_state.battery = protect_reading;
    g_state.battery.low_battery = true;
    g_state.battery.empty_protection = true;
    updateCycles(); // the last discharge steps before the protection count too
    if (PowerManager::needsProtectionRender(was_protected, true)) {
      g_panel.begin();
      DashboardView::renderEmptyBattery(g_framebuffer, g_state.battery);
      flushFramebuffer();
    }
    if (!PowerPlatform::saveState(g_state)) {
      Serial.println("RTC state save failed");
    }
    PowerPlatform::enterDeepSleep(PowerManager::planProtectedSleep(digitalRead(PIN_BTN_KEY) == LOW));
  }

  g_mic_cal = MicCalibrationStore::load();
  ++g_state.boot_count;
  loadConfigFromCard(cause);

  Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL);
  g_rtc.begin(Wire);
  g_panel.begin();

  g_state.releaseDeadSlots(nowMs()); // before the scan, so a free slot takes a new sensor in this cycle

  if (cycle.advance() == PowerPhase::SCAN) {
    g_state.last_scan_ms = nowMs();
    initBle();
    // The battery reading is from the last wake cycle: one cycle after a plug-in keeps the old power state.
    scanOnce(ScanCompletion::fullScanRequired(
        cause, g_state.battery, g_state.registry, nowMs(), g_state.history.sampleDue(nowMs()), config().staleTimeoutMs()));
    BLEDevice::deinit(false);
    cycle.advance();
  }

  // The KEY press that woke the board cycles the view.
  if (cause == WakeupCause::KEY_BUTTON) {
    g_mode_controller.next();
  }
  refreshSensors();
  g_power_config = PowerManager::powerConfig(config(), g_state.battery, quietHours(), g_rtc_time);
  renderDashboard();
  chimeOnAlerts();
  chimeOnChargeFull();
  chimeOnSensorBattery();
  chimeRepeat();
  logToSdCard(); // after the render: the display shows fresh data even if the card is slow
  // The sync runs after the first render, so a slow or absent network never delays the display.
  // A new time redraws the header clock.
  if (syncNtpTime(cause == WakeupCause::KEY_BUTTON)) { // syncNtpTime read the new time
    renderDashboard();
  }
  measureNoiseAndRedraw(cause != WakeupCause::TIMER);
  captureSpectrumAndRedraw(cause != WakeupCause::TIMER);

  // Grace window after a KEY wakeup or a power-on: further KEY presses redraw without a new
  // wakeup. A timer wakeup skips it to save battery.
  const uint32_t grace_start = millis();
  // A double clap acts like a KEY press. On USB power the board listens until the next scan is due;
  // on battery it listens in the grace window only.
  const ClapListenMode clap_mode = PowerManager::clapListenMode(g_state.battery, cause);
  Es7210Hal clap_hal;
  ClapListener<Es7210Hal> clap_listener(clap_hal);
  // The serial console runs on USB power only and has its own USB watch, so a failed clap listener
  // does not end it. A command mounts the card for itself only.
  bool console_active = PowerManager::serialConsoleAllowed(g_state.battery);
  UsbPowerWatch console_watch(millis());
  if (console_active) {
    Serial.setTxTimeoutMs(1000); // a slow host reader must not shorten a DUMP frame
  }
  SerialLineAssembler console_line;
  SerialSink console_sink;
  SdLogStorage console_storage;
  const bool wants_grace =
      cause != WakeupCause::TIMER || clap_mode == ClapListenMode::CONTINUOUS || console_active;
  // Continuous listening ends when USB power ends or the listener fails; the grace window stays.
  bool continuous_listen = clap_mode == ClapListenMode::CONTINUOUS;
  UsbPowerWatch usb_watch(millis());
  // A pending KEY or BOOT press (long press check) keeps the window open; it ends within 2 s.
  while (wants_grace &&
         (millis() - grace_start < KEY_GRACE_MS || g_mode_controller.pressPending() ||
          g_boot_button.pressPending() ||
          ((continuous_listen || console_active) &&
           PowerManager::continuousListenAllowed(g_power_config, nowMs(), g_state.last_scan_ms)))) {
    if (console_active && console_watch.due(millis()) &&
        !console_watch.update(BatteryMonitor::read(config().battery_offset_mv).usb_powered, millis())) {
      console_active = false;
    }
    while (console_active && Serial.available() > 0) {
      const SerialLineAssembler::Event event = console_line.feed(static_cast<uint8_t>(Serial.read()));
      if (event == SerialLineAssembler::Event::NONE) {
        continue;
      }
      SerialCommand command;
      const SerialParseStatus status = event == SerialLineAssembler::Event::LINE_TOO_LONG
          ? SerialParseStatus::TOO_LONG
          : SerialConsole::parse(console_line.line(), console_line.length(), command);
      if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::CONFIG) {
        // The RTC copy answers: no card access, so the clap listener keeps running.
        SerialConsoleRunner<SdLogStorage, SerialSink>::showConfig(console_sink, g_state.config,
                                                                  g_state.config_from_sd, g_state.config_rejects);
      } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::CALIB) {
        // The RTC histories answer: no card access, so the clap listener keeps running.
        SerialConsoleRunner<SdLogStorage, SerialSink>::showCalib(console_sink, command.reference, g_state.history,
                                                                 g_state.config);
      } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::HISTORY) {
        // The RTC histories answer: no card access, so the clap listener keeps running.
        SerialConsoleRunner<SdLogStorage, SerialSink>::showHistory(console_sink, g_state.history,
                                                                   g_state.noise_history);
      } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::EVENTS) {
        // The RTC ring answers: no card access, so the clap listener keeps running. The ring stays.
        RtcTime events_now = g_rtc_time; // the wakeup time is old in a long USB session: read the clock again
        if (!g_rtc.readTime(events_now)) {
          events_now = g_rtc_time;
        }
        SerialConsoleRunner<SdLogStorage, SerialSink>::showEvents(console_sink, g_state.events, events_now);
      } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::VERSION) {
        // Constants answer: no card access, so the clap listener keeps running.
        SerialConsoleRunner<SdLogStorage, SerialSink>::showVersion(console_sink, FIRMWARE_VERSION, RLCD_GIT_HASH,
                                                                   ARDUINO_CORE_VERSION, PowerManager::STATE_VERSION,
                                                                   __DATE__, __TIME__);
      } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::SET) {
        clap_listener.release(); // keep the I2S bus idle while the card transfers
        // The new text takes effect as after a KEY wakeup: parse it and apply it inside the registry lock.
        SerialConsoleRunner<SdLogStorage, SerialSink>::setConfig(
            console_storage, console_sink, command, [](const char* text, size_t len) {
              DeviceConfig loaded;
              const ConfigParseResult parsed = DeviceConfigParser::parse(text, len, loaded);
              portENTER_CRITICAL(&g_registry_mux);
              g_state.applyConfig(loaded, parsed.file_ok, parsed.rejects());
              portEXIT_CRITICAL(&g_registry_mux);
              // Same follow-up as a KEY wakeup: card status, event, and the sleep and listen settings.
              g_state.setSdStatus(SdStatus::OK, nowMs());
              g_state.events.record(g_rtc_time, EventCode::CONFIG_LOAD, static_cast<uint16_t>(parsed.rejects().count));
              g_power_config = PowerManager::powerConfig(config(), g_state.battery, quietHours(), g_rtc_time);
            });
      } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::STATUS) {
        // The RTC copy answers: no card access, so the clap listener keeps running.
        portENTER_CRITICAL(&g_registry_mux); // the BLE callback writes the registry
        const SwitchBotRegistry registry_copy = g_state.registry;
        portEXIT_CRITICAL(&g_registry_mux);
        SerialStatusSnapshot snap;
        snap.registry = &registry_copy;
        snap.history = &g_state.history;
        snap.config = &g_state.config;
        snap.battery = g_state.battery;
        snap.alert_mask = g_state.alert_mask;
        snap.sensor_battery_latch = g_state.sensor_battery_latch;
        snap.muted = g_state.muted;
        snap.now_ms = nowMs();
        snap.sd_status = g_state.sd_status;
        snap.sd_status_ms = g_state.sd_status_ms;
        snap.shtc3 = g_state.shtc3;
        snap.battery_history = &g_state.battery_history;
        snap.cycle_tenths = g_cycles.cycleTenths();
        snap.scan_saved_ms = g_scan_saved_ms;
        snap.reset_reason = ResetReasonCodec::text(g_reset_reason);
        ResetCountStore reset_store;
        snap.abnormal_resets = AbnormalResetCounter::read(reset_store);
        SerialConsoleRunner<SdLogStorage, SerialSink>::showStatus(console_sink, snap);
      } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::TIME) {
        // The RTC answers over I2C: no card access, so the clap listener keeps running.
        if (command.time_set) {
          if (SerialConsoleRunner<SdLogStorage, SerialSink>::setTime(console_sink, g_rtc, g_state.rtc_drift,
                                                                     command.time)) {
            RtcTime written;
            if (g_rtc.readTime(written)) {
              g_rtc_time = written; // a failed re-read keeps the old header clock
            }
            renderDashboard(); // the header clock shows the new time
          }
        } else {
          RtcTime now_time;
          g_rtc.readTime(now_time);
          SerialConsoleRunner<SdLogStorage, SerialSink>::showTime(console_sink, now_time);
        }
      } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::BEEP) {
        clap_listener.release(); // the chime uses the same I2S bus; the next poll() reopens it
        Es8311Hal beep_hal;
        SerialConsoleRunner<SdLogStorage, SerialSink>::beep(console_sink, beep_hal, command.reference,
                                                           g_state.muted, quietHours().isActive(g_rtc_time),
                                                           config().chime_volume_pct);
      } else if (status == SerialParseStatus::OK) {
        clap_listener.release(); // keep the I2S bus idle while the card transfers
        SerialConsoleRunner<SdLogStorage, SerialSink>::execute(console_storage, console_sink, command);
      } else {
        SerialConsoleRunner<SdLogStorage, SerialSink>::reject(console_sink, status);
      }
    }
    if (continuous_listen && usb_watch.due(millis()) &&
        !usb_watch.update(BatteryMonitor::read(config().battery_offset_mv).usb_powered, millis())) {
      continuous_listen = false;
    }
    if (continuous_listen && !clap_listener.available()) {
      continuous_listen = false;
    }
    // The read blocks for one 20 ms audio frame, so the CPU idles between frames.
    if (clap_mode != ClapListenMode::OFF && clap_listener.poll()) {
      clap_listener.release(); // a noise window uses the same I2S bus; the next poll() reopens it
      g_mode_controller.next();
      renderDashboard();
      measureNoiseAndRedraw(true);
      g_spectrum_attempted = false; // a new entry to the view captures again
      g_spectrum = SpectrumReading{};
      captureSpectrumAndRedraw(true);
    }
    // A short BOOT press in the grace window toggles the mute flag; a short beep confirms
    // unmuting. A long BOOT press in the diagnostics view runs the loopback test instead.
    switch (g_boot_button.update(
        digitalRead(PIN_BTN_BOOT) == LOW,
        millis(),
        g_mode_controller.mode() == DisplayMode::SYSTEM_DIAGNOSTICS)) {
      case BootEvent::TOGGLE_MUTE:
        g_state.muted = !g_state.muted;
        if (!g_state.muted) {
          clap_listener.release(); // the confirm beep uses the same I2S bus
          Es8311Hal hal;
          AudioSequencer::playConfirm(hal, config().chime_volume_pct);
        }
        break;
      case BootEvent::LOOPBACK:
        clap_listener.release(); // the loopback test uses the same I2S bus
        runLoopbackTest();
        g_boot_button.cancelPress();
        break;
      case BootEvent::NONE:
      default:
        break;
    }
    switch (g_mode_controller.updateWithLongPress(digitalRead(PIN_BTN_KEY) == LOW, millis())) {
      case ButtonEvent::MODE_CHANGED:
        clap_listener.release(); // a noise window uses the same I2S bus
        renderDashboard();
        measureNoiseAndRedraw(true);
        g_spectrum_attempted = false; // a new entry to the view captures again
        g_spectrum = SpectrumReading{}; // old bars never show while the new capture runs
        captureSpectrumAndRedraw(true);
        break;
      case ButtonEvent::NEXT_SENSOR:
        renderDashboard(); // the chart reads only the RTC histories: no sensor read, no microphone
        break;
      case ButtonEvent::RECAPTURE:
        clap_listener.release(); // the spectrum capture uses the same I2S bus
        captureSpectrumAndRedraw(true, true);
        break;
      case ButtonEvent::CALIBRATE:
        clap_listener.release(); // the calibration window uses the same I2S bus
        calibrateMicrophone();
        g_mode_controller.cancelPress();
        break;
      case ButtonEvent::NONE:
      default:
        break;
    }
    if (!clap_listener.listening()) { // an open listener blocks in poll() instead
      delay(10);
    }
  }
  clap_listener.stop(); // microphones off before deep sleep on each path

  cycle.advance(); // SAVE
  g_state.mode = g_mode_controller.mode();
  g_state.chart_sensor = g_mode_controller.chartSensor();
  if (!PowerPlatform::saveState(g_state)) {
    Serial.println("RTC state save failed");
  }
  cycle.advance(); // SLEEP

  const WakeupPlan plan = PowerManager::planSleep(
      g_power_config, nowMs(), g_state.last_scan_ms, digitalRead(PIN_BTN_KEY) == LOW);
  PowerPlatform::enterDeepSleep(plan);
}

void loop() {
  // Not reached: setup() ends in deep sleep.
}
