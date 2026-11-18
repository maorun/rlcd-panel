#include <Arduino.h>
#include <Wire.h>
#include <BLEDevice.h>
#include <BLEAdvertisedDevice.h>
#include <BLEScan.h>
#include <BLEAdvertising.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <atomic>
#include <type_traits>
#include <freertos/FreeRTOS.h>
#include <freertos/portmacro.h>

#include "audio_alert.h"
#include "audio_loopback.h"
#include "audio_spectrum.h"
#include "board_pins.h"
#include "battery_monitor.h"
#include "ble_telemetry.h"
#include "button_handler.h"
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
#include "thermopro_ble.h"

// Allocate 16 KB for the Arduino loop task stack to prevent stack overflows during serial console operations (default 8 KB).
SET_LOOP_TASK_STACK_SIZE(16 * 1024);

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
bool g_frame_rendered = false;  // flushFramebuffer() ran and PATTERN did not overwrite it: g_framebuffer holds a render (console SCREEN)
bool g_panel_started = false;   // g_panel.begin() ran in this wake cycle
bool g_timer_wakeup = false;    // this wake cycle is a timer wakeup
bool g_skip_counted = false;    // a skip of this wake cycle already counted in g_state.frame
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

// Set only while the console command SCAN runs: the callback then fills the collector and leaves the registry alone.
// Read and written inside g_registry_mux.
ScanCollector* g_scan_collector = nullptr;

// True when SCAN took the packet. The registry, the scan completion and the reject counters stay untouched.
bool collectForConsoleScan(const SwitchBotReading& reading, BleFormat format) {
  portENTER_CRITICAL(&g_registry_mux);
  const bool taken = g_scan_collector != nullptr;
  if (taken) {
    g_scan_collector->add(reading, format);
  }
  portEXIT_CRITICAL(&g_registry_mux);
  return taken;
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

    if (collectForConsoleScan(reading, BleFormat::BTHOME)) {
      return;
    }
    portENTER_CRITICAL(&g_registry_mux);
    // A packet that JumpGuard holds is no reading yet: it must not end the scan early.
    if (g_state.registry.upsert(reading, BleFormat::BTHOME)) {
      g_scan_completion.onPacket(g_state.registry, reading);
    }
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

    if (collectForConsoleScan(reading, BleFormat::ATC)) {
      return;
    }
    portENTER_CRITICAL(&g_registry_mux);
    // A packet that JumpGuard holds is no reading yet: it must not end the scan early.
    if (g_state.registry.upsert(reading, BleFormat::ATC)) {
      g_scan_completion.onPacket(g_state.registry, reading);
    }
    portEXIT_CRITICAL(&g_registry_mux);
  }

  // ThermoPro sensors (TP357, TP358, etc.) carry their readings in Manufacturer Data (0xC2 header).
  static void handleThermoPro(BLEAdvertisedDevice& advertisedDevice, const String& mfg) {
    const String name = advertisedDevice.haveName() ? advertisedDevice.getName().c_str() : "";
    SwitchBotReading reading;
    if (!ThermoProBleParser::parseManufacturerData(
            reinterpret_cast<const uint8_t*>(mfg.c_str()),
            mfg.length(),
            advertisedDevice.getAddress().getNative(),
            name.c_str(),
            reading)) {
      return;
    }
    reading.rssi = SwitchBotBleParser::clampRssi(advertisedDevice.getRSSI());
    reading.updated_ms = nowMs();

    if (collectForConsoleScan(reading, BleFormat::THERMOPRO)) {
      return;
    }
    portENTER_CRITICAL(&g_registry_mux);
    // A packet that JumpGuard holds is no reading yet: it must not end the scan early.
    if (g_state.registry.upsert(reading, BleFormat::THERMOPRO)) {
      g_scan_completion.onPacket(g_state.registry, reading);
    }
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

    if (advertisedDevice.haveManufacturerData()) {
      const String mfg_probe = advertisedDevice.getManufacturerData();
      if (mfg_probe.length() >= THERMOPRO_MIN_PAYLOAD_LEN &&
          static_cast<uint8_t>(mfg_probe[0]) == THERMOPRO_HEADER_BYTE) {
        handleThermoPro(advertisedDevice, mfg_probe);
        return;
      }
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

    if (collectForConsoleScan(reading, format)) {
      return;
    }
    portENTER_CRITICAL(&g_registry_mux);
    // A packet that JumpGuard holds is no reading yet: it must not end the scan early.
    if (g_state.registry.upsert(reading, format)) {
      g_scan_completion.onPacket(g_state.registry, reading);
    }
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

Shtc3Result readOnboardShtcOnce(Shtc3Reading& out, bool usb_powered) {
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

  return Shtc3Codec::classify(true, received == 6 ? 6 : 0, frame, out, config().onboardTempOffsetC(usb_powered),
                              config().rh_offset_pct, config().rh_gain);
}

// A failed first read gets one retry after the soft reset 0x805D (1 ms wait); see readShtc3WithRetry.
Shtc3Result readOnboardShtc3(Shtc3Reading& out, bool usb_powered, Shtc3Health& health) {
  return readShtc3WithRetry(
      health, [&]() { return readOnboardShtcOnce(out, usb_powered); },
      []() {
        Wire.beginTransmission(I2C_ADDR_SHTC3);
        Wire.write(0x80);
        Wire.write(0x5D);
        Wire.endTransmission();
        delay(1);
      });
}

// Events that happen before the RTC is read (config load, release of the empty protection) wait here
// until refreshSensors() has the time. -1 means none.
int32_t g_pending_config_rejects = -1;
bool g_pending_protection_release = false;
bool g_reset_event_pending = false; // a start that is no deep sleep wakeup waits for its RESET event
uint16_t g_reset_event_detail = 0;  // ResetReason of that start
int32_t g_pending_sd_event = -1; // SD_FAULT detail of the config load, recorded once the RTC time is known
uint8_t g_pending_slot_release = 0; // mask of the slots releaseDeadSlots cleared, recorded once the RTC time is known

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
  return g_state.registry.calibrated(config().slot_temp_offset_c, config().slot_rh_offset_pct,
                                         config().slot_rh_gain);
}

// Starts the panel once per wake cycle. A skipped refresh never starts it.
void ensurePanelStarted() {
  if (!g_panel_started) {
    g_panel.begin();
    g_panel_started = true;
  }
}

// Every flush goes through here, so display_invert applies to each screen. A timer wakeup with the
// frame of the last flush and the same view skips the panel start and the SPI transfer: the
// reflective panel keeps its image. Each 60th skip in a row flushes again (FrameRefresh).
void flushFramebuffer() {
  g_frame_rendered = true;
  if (config().display_invert != 0) {
    g_framebuffer.invert();
  }
  const uint32_t frame_crc = g_framebuffer.crc32();
  const bool view_changed = g_mode_controller.mode() != g_state.mode ||
                            g_mode_controller.chartSensor() != g_state.chart_sensor ||
                            g_mode_controller.statsPage() != g_state.stats_page;
  // A redraw in the same cycle (for example after the NTP sync) counts once and keeps the skip, also at the 60th.
  const bool same_cycle_skip = g_skip_counted && g_timer_wakeup && !view_changed && frame_crc == g_state.frame.crc;
  if (same_cycle_skip || g_state.frame.shouldSkip(g_timer_wakeup, view_changed, frame_crc)) {
    if (!g_skip_counted) {
      g_state.frame.recordSkip();
      g_skip_counted = true;
    }
    return;
  }
  ensurePanelStarted();
  g_panel.flush(g_framebuffer);
  g_state.frame.recordFlush(frame_crc);
  g_skip_counted = false;
}

// Tells the mode controller how many pages of sensors exist in the stats view.
void updateStatsPages() {
  SwitchBotReading snapshot[MAX_SWITCHBOT_SENSORS];
  portENTER_CRITICAL(&g_registry_mux);
  for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
    snapshot[i] = g_state.registry.slot(i);
  }
  portEXIT_CRITICAL(&g_registry_mux);
  g_mode_controller.setStatsPages(
      DashboardView::statsTotalPages(snapshot, MAX_SWITCHBOT_SENSORS, &g_state.config));
  size_t slot_of_tile[DashboardView::MAX_TILES] = {};
  const size_t tiles = DashboardView::activeTileCount(snapshot, MAX_SWITCHBOT_SENSORS, &g_state.config, slot_of_tile);
  g_mode_controller.setDashboardPages(tiles > 6 ? 2 : 1);
}

void renderDashboard() {
  updateStatsPages(); // before the first render: a restored page falls back without active slots
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
  info.muted = g_state.muted;
  info.ventilate = (g_state.alert_mask & ALERT_VENTILATE) != 0;
  info.ventilate_tiles = g_state.ventilate_tiles;
  info.dry_tiles = g_state.dry_tiles;
  info.sensor_battery_latch = g_state.sensor_battery_latch;
  info.loopback = g_loopback;
  info.spectrum = g_spectrum;
  info.chart_sensor = g_mode_controller.chartSensor();
  info.dashboard_page = g_mode_controller.dashboardPage();
  info.stats_page = g_mode_controller.statsPage();
  info.runtime = g_state.battery_history.estimate(g_state.battery, g_scan_saved_ms, &g_state.awake);
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
      &g_state.config,
      &g_state.week);
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
  portENTER_CRITICAL(&g_registry_mux);
  g_state.registry.endScan();
  portEXIT_CRITICAL(&g_registry_mux);
}

// BLE console service (Nordic UART layout). The BLE task only queues the RX bytes and the
// connection events; serve() runs in the loop task, so the commands (card, I2C, scan) never run in
// the BLE callbacks. A disconnect clears the partial line. One central at a time.
class BleConsoleService : public BLEServerCallbacks, public BLECharacteristicCallbacks {
public:
  void setLevel(uint8_t level) { bridge_.setLevel(level); }
  uint8_t level() const { return bridge_.level(); }

  void start() {
    if (server_ != nullptr || bridge_.level() == 0 || start_failed_) {
      return;
    }
    if (!BLEDevice::init("rlcd-panel")) {
      start_failed_ = true; // no retry in each loop pass until the level drops to 0
      Serial.println("BLE console init failed");
      return;
    }
    BLEDevice::setMTU(185);
    server_ = BLEDevice::createServer();
    if (server_ == nullptr) {
      start_failed_ = true;
      Serial.println("BLE console server failed");
      BLEDevice::deinit(false); // stop() returns early without a server: release the stack here
      return;
    }
    BLEService* service = server_ != nullptr ? server_->createService(BleConsoleUuids::SERVICE) : nullptr;
    BLECharacteristic* rx = service != nullptr
        ? service->createCharacteristic(BleConsoleUuids::RX, BLECharacteristic::PROPERTY_WRITE |
                                                              BLECharacteristic::PROPERTY_WRITE_NR)
        : nullptr;
    tx_ = service != nullptr ? service->createCharacteristic(BleConsoleUuids::TX, BLECharacteristic::PROPERTY_NOTIFY)
                             : nullptr;
    if (rx == nullptr || tx_ == nullptr) {
      start_failed_ = true;
      Serial.println("BLE console service failed");
      stop();
      return;
    }
    server_->setCallbacks(this);
    server_->advertiseOnDisconnect(false); // one central: the advertising restarts in serve()
    rx->setCallbacks(this);
    tx_->addDescriptor(new BLE2902());
    service->start();
    advertising_ = BLEDevice::getAdvertising();
    advertising_->addServiceUUID(BleConsoleUuids::SERVICE);
    advertising_->setScanResponse(true);
    advertising_->start();
  }

  // Clears the failure latch; the next start() tries again.
  void resetFailure() { start_failed_ = false; }

  void stop() {
    if (server_ == nullptr) {
      return;
    }
    if (advertising_ != nullptr) {
      advertising_->stop();
    }
    BLEDevice::deinit(false); // no BLE callback runs after this: the reset below cannot race one
    portENTER_CRITICAL(&mux_);
    server_ = nullptr;
    tx_ = nullptr;
    advertising_ = nullptr;
    central_ = false;
    ingress_.clear();
    portEXIT_CRITICAL(&mux_);
    bridge_.onDisconnect(); // no stale central or partial line for the next start
  }

  bool pending() {
    portENTER_CRITICAL(&mux_);
    const bool any = ingress_.pending();
    portEXIT_CRITICAL(&mux_);
    return any;
  }

  // Applies the queued events and bytes. `run(sink, status, command)` runs one parsed command.
  template <typename Run>
  void serve(Run& run) {
    for (;;) {
      portENTER_CRITICAL(&mux_);
      const BleConsoleIngress::Step step = ingress_.pop();
      const uint16_t mtu = ingress_.mtu();
      portEXIT_CRITICAL(&mux_);
      if ((step.events & BleConsoleIngress::EVENT_DISCONNECT) != 0) {
        bridge_.onDisconnect();
        if (advertising_ != nullptr) {
          advertising_->start();
        }
      }
      if ((step.events & BleConsoleIngress::EVENT_CONNECT) != 0 && step.connected) { // after the disconnect: a quick reconnect works; a central that already left stays out
        bridge_.onConnect();
      }
      if (step.overflow) {
        bridge_.resetLine();
        BleChunkSink<BleConsoleService> sink(*this, mtu);
        sink.write(reinterpret_cast<const uint8_t*>("ERR TOO_LONG\n"), 13);
        sink.flush();
        return; // one reply per call: the loop task keeps USB serial running
      }
      if (!step.have) {
        if (step.events == 0) {
          return;
        }
        continue;
      }
      SerialCommand command;
      const char* reply = "";
      const BleConsoleBridge::Action action = bridge_.feed(step.byte, command, reply);
      if (action == BleConsoleBridge::Action::NONE) {
        continue;
      }
      BleChunkSink<BleConsoleService> sink(*this, mtu);
      if (action == BleConsoleBridge::Action::REPLY) {
        sink.write(reinterpret_cast<const uint8_t*>(reply), strlen(reply));
      } else {
        run(sink, SerialParseStatus::OK, command);
      }
      sink.flush();
      return; // one command per call: the loop task keeps USB serial running
    }
  }

  // False when no central is connected. The stack gives no per-notification result: a drop inside
  // the stack shows only in the CRC32 line, which the central checks.
  bool notify(const uint8_t* data, size_t len) {
    if (tx_ == nullptr || !bridge_.connected()) {
      return false;
    }
    tx_->setValue(const_cast<uint8_t*>(data), len);
    tx_->notify();
    delay(8); // pace the notifications: the stack drops them when its buffers are full
    return true;
  }

  // One central: a second one is disconnected at once, and only the accepted handle ends the session.
  void onConnect(BLEServer* server, ble_gap_conn_desc* desc) override {
    bool accepted = false;
    withLock([&] {
      if (!central_) {
        central_ = true;
        handle_ = desc->conn_handle;
        ingress_.connect();
        accepted = true;
      }
    });
    if (!accepted) {
      server->disconnect(desc->conn_handle);
    }
  }
  void onDisconnect(BLEServer*, ble_gap_conn_desc* desc) override {
    withLock([&] {
      if (central_ && desc->conn_handle == handle_) {
        central_ = false;
        ingress_.disconnect();
      }
    });
  }
  void onMtuChanged(BLEServer*, ble_gap_conn_desc* desc, uint16_t mtu) override {
    withLock([&] {
      if (central_ && desc->conn_handle == handle_) { // a refused second central never sets the MTU
        ingress_.setMtu(mtu);
      }
    });
  }
  // Only the accepted central writes: bytes of a refused second central never reach the line.
  void onWrite(BLECharacteristic* characteristic, ble_gap_conn_desc* desc) override {
    const String value = characteristic->getValue();
    withLock([&] {
      if (central_ && desc->conn_handle == handle_) {
        ingress_.push(reinterpret_cast<const uint8_t*>(value.c_str()), value.length());
      }
    });
  }

private:
  template <typename Fn>
  void withLock(Fn fn) {
    portENTER_CRITICAL(&mux_);
    fn();
    portEXIT_CRITICAL(&mux_);
  }

  BleConsoleBridge bridge_;
  BleConsoleIngress ingress_;
  bool central_ = false; // a central is accepted (BLE task and loop task, under mux_)
  uint16_t handle_ = 0;
  bool start_failed_ = false;
  BLEServer* server_ = nullptr;
  BLECharacteristic* tx_ = nullptr;
  BLEAdvertising* advertising_ = nullptr;
  portMUX_TYPE mux_ = portMUX_INITIALIZER_UNLOCKED;
};

// Console command SCAN: one 4 s scan that fills `collector` and changes no registry state. Blocks for the scan.
// The wake cycle stopped BLE after its own scan, or skipped it, so this function starts BLE and stops it again.
// BLEDevice::deinit() runs before the collector is released: no late callback reaches the registry.
constexpr uint32_t CONSOLE_SCAN_S = 4;
bool consoleScanOnce(ScanCollector& collector) {
  initBle();
  if (g_ble_scan == nullptr) {
    BLEDevice::deinit(false);
    return false;
  }
  portENTER_CRITICAL(&g_registry_mux);
  g_scan_collector = &collector;
  portEXIT_CRITICAL(&g_registry_mux);
  g_scan_running = true;
  const bool started = g_ble_scan->start(CONSOLE_SCAN_S, onScanComplete, false);
  if (!started) {
    g_scan_running = false;
  }
  const uint32_t begin = millis();
  while (g_scan_running && millis() - begin < CONSOLE_SCAN_S * 1000 + SCAN_WATCHDOG_MARGIN_MS) {
    delay(10);
  }
  if (g_scan_running) {
    g_ble_scan->stop();
    g_scan_running = false;
    delay(100); // let a late completion callback from the BLE task finish
  }
  BLEDevice::deinit(false);
  g_ble_scan = nullptr; // after deinit: the BLE task no longer calls onScanComplete()
  portENTER_CRITICAL(&g_registry_mux);
  g_scan_collector = nullptr;
  portEXIT_CRITICAL(&g_registry_mux);
  return started;
}

// Adds the discharge of this wake cycle. NVS is written only when update() reports a charge event
// or a whole cycle. The tracking state goes to RTC memory; the NVS total is the reference.
void updateCycles() {
  CycleStore store;
  g_cycles.step(g_state.battery, store);
  g_cycle_rtc = g_cycles.toRtc();
}

// Stores a card status and records one SD_FAULT event when it changes to a fault or back to OK.
void setSdStatusLogged(SdStatus next, const RtcTime& time) {
  const int32_t detail = SdStatusCodec::faultEventDetail(g_state.sd_status, next);
  if (detail >= 0) {
    g_state.events.record(time, EventCode::SD_FAULT, static_cast<uint16_t>(detail));
  }
  g_state.setSdStatus(next, nowMs());
}

void refreshSensors() {
  // After a reset of the RTC state the latch starts off: a cell at 16..19 % raises no alert until
  // it reaches the set threshold.
  const bool was_low = g_state.battery.low_battery;
  g_state.battery = BatteryMonitor::read(config().battery_offset_mv);
  g_state.battery.low_battery = BatteryMonitor::updateLowBattery(g_state.battery, was_low);
  g_state.battery_history.record(nowMs(), g_state.battery);
  // The SHTC3 read follows the battery read: the USB state selects the offset of the reading.
  readOnboardShtc3(g_onboard_reading, g_state.battery.valid && g_state.battery.usb_powered, g_state.shtc3);
  updateCycles();
  updateRtc();
  if (g_reset_event_pending) {
    g_state.events.record(g_rtc_time, EventCode::RESET, g_reset_event_detail);
    g_reset_event_pending = false;
  }
  if (was_low != g_state.battery.low_battery) {
    const uint16_t pct = g_state.battery.percentage > 0 ? static_cast<uint16_t>(g_state.battery.percentage) : 0;
    g_state.events.record(g_rtc_time, g_state.battery.low_battery ? EventCode::BATTERY_LOW_SET : EventCode::BATTERY_LOW_CLEAR, pct);
  }
  if (g_pending_sd_event >= 0) {
    g_state.events.record(g_rtc_time, EventCode::SD_FAULT, static_cast<uint16_t>(g_pending_sd_event));
    g_pending_sd_event = -1;
  }
  if (g_pending_config_rejects >= 0) {
    g_state.events.record(g_rtc_time, EventCode::CONFIG_LOAD, static_cast<uint16_t>(g_pending_config_rejects));
    g_pending_config_rejects = -1;
  }
  portENTER_CRITICAL(&g_registry_mux);
  g_state.recordSlotEvents(g_rtc_time, g_pending_slot_release);
  portEXIT_CRITICAL(&g_registry_mux);
  g_pending_slot_release = 0;
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
      config().staleTimeoutMs(),
      g_state.battery.valid && g_state.battery.usb_powered);
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
      config().ventilate_limit_rh, config().staleTimeoutMs(), config().door_ref_slot);
  g_state.dry_tiles = DryLatch::update(
      g_state.dry_tiles, g_onboard_reading, tiles, MAX_SWITCHBOT_SENSORS, nowMs(),
      config().dry_alert_limit_rh, config().climate_alert_tiles, config().staleTimeoutMs());
  // The sensor battery latch updates before the first render. The chime follows in chimeOnSensorBattery().
  g_state.sensor_battery_latch = SensorBatteryLatch::update(
      g_state.sensor_battery_latch, tiles, MAX_SWITCHBOT_SENSORS, nowMs(), config().sensor_battery_limit_pct,
      config().staleTimeoutMs());
}

// Advertises the climate tiles as non-connectable frames (Page 0 and Page 1) and stops
// the advertising again before the deep sleep. A low battery alert and the empty battery protection
// skip it (BleTelemetryCodec::shouldBroadcast). Call it after refreshSensors() (tiles and cell) and
// after the chime functions (chimeOnAlerts() stores the alert mask). The BLE scan is stopped here, so the registry needs no lock.
void broadcastTelemetry() {
  if (!BleTelemetryCodec::shouldBroadcast(config().ble_broadcast_enabled, g_state.battery)) {
    return;
  }
  BleTelemetryTile tiles[BleTelemetryCodec::TOTAL_TILES];
  BleTelemetryCodec::buildTiles(g_onboard_reading, calibratedRegistry(), nowMs(), config().staleTimeoutMs(), tiles);
  // AD structure: length, type 0xFF (Manufacturer Specific Data), company ID and payload. The frame
  // holds NUL bytes, so addData(char*, size_t) takes it, not a String.
  uint8_t ad[2 + BleTelemetryCodec::MANUFACTURER_DATA_SIZE];
  ad[0] = static_cast<uint8_t>(1 + BleTelemetryCodec::MANUFACTURER_DATA_SIZE);
  ad[1] = 0xFF;
  // The scan phase called deinit(false) before: init again for the advertising. A failed init skips the broadcast.
  if (!BLEDevice::init("rlcd-panel")) {
    Serial.println("BLE telemetry init failed");
    return;
  }
  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  if (advertising != nullptr) {
    advertising->setAdvertisementType(BLE_GAP_CONN_MODE_NON); // non-connectable (NimBLE conn_mode)
    advertising->setMinInterval(BleTelemetryCodec::INTERVAL_UNITS);
    advertising->setMaxInterval(BleTelemetryCodec::INTERVAL_UNITS);
    const uint32_t total_ms = g_state.battery.usb_powered ? 1000 : BleTelemetryCodec::ADVERTISE_MS;
    const uint32_t page_ms = total_ms / BleTelemetryCodec::PAGE_COUNT;

    for (uint8_t page = 0; page < BleTelemetryCodec::PAGE_COUNT; ++page) {
      BleTelemetryCodec::encode(tiles, BleTelemetryCodec::TOTAL_TILES,
                                BleTelemetryCodec::sequenceFrom(g_state.boot_count),
                                BleTelemetryCodec::cellPercent(g_state.battery), g_state.alert_mask,
                                page, ad + 2);
      BLEAdvertisementData adv_data;
      adv_data.setFlags(0x06); // general discoverable, no BR/EDR
      adv_data.addData(reinterpret_cast<char*>(ad), sizeof(ad));
      advertising->setAdvertisementData(adv_data);
      if (advertising->start()) {
        delay(page_ms);
        advertising->stop();
      } else {
        Serial.println("BLE telemetry start failed");
      }
    }
  }
  BLEDevice::deinit(false);
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
void logWeekSummary(uint32_t now);
void logEvents(bool card_ok);

// Appends one hourly record to the MicroSD card. A missing card or a failed write never blocks
// the cycle; the next attempt follows after a short retry interval. The wake cycle that writes the
// record, also a timer wakeup, runs one 5 s noise window first. It is skipped with a low battery
// alert, and then the noise fields stay empty. Other wake cycles keep the microphones off.
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
    setSdStatusLogged(SdStatusCodec::fromLog(result, free_mb, g_state.sd_status), g_rtc_time); // after the noise window
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
  logWeekSummary(now);
  // After the daily row: the reading of the first write after midnight belongs to the new day (markDone
  // in logDailySummary resets the stats first). A daily write that keeps failing keeps the stats, so
  // the row of the old day then also holds later readings.
  if (result == SdLogResult::OK && noise_measured && have_noise) {
    g_state.daily.noise.add(NoiseHistory::encode(cal.splDb(g_noise.rms)), g_rtc_time.hour, g_noise.events);
  }
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
    const SdLogResult result = SdLogger::appendDaily(g_rtc_time, g_state.history, g_state.noise_history, g_state.daily.noise,
                                                       config().mold_alert_limit_rh);
    if (result == SdLogResult::OK) {
      g_state.daily.markDone(g_rtc_time);
    } else {
      Serial.printf("SD daily log failed: %u\n", static_cast<unsigned>(result));
    }
  }
}

// Shifts the 7-day ring of the week chart at the first hourly write after local midnight and stores
// the previous day from the 24 h histories. It needs no card: the RTC memory is the only store. An
// invalid RTC time skips the shift. A stale history and a sensor without a reading in the last 24 hours
// store gaps. The render of the wake cycle runs before this step, so the week chart shows the new day
// one wake cycle later. After a gap of several days the step stores the newest 24 hours as day 0 and
// leaves gaps for the days in between.
void logWeekSummary(uint32_t now) {
  g_state.week.advance(
      g_rtc_time, g_state.history, g_state.history.freshWithin24h(now),
      WeekHistory::liveSensors(g_onboard_reading.valid, g_state.registry, now));
}

AlertLimits alertLimits() {
  AlertLimits limits;
  limits.freezer_trigger_c = config().freeze_alert_limit_c;
  limits.humidity_trigger_pct = static_cast<float>(config().mold_alert_limit_rh);
  limits.outdoor_slot = config().outdoor_slot;
  limits.climate_tiles = config().climate_alert_tiles;
  limits.heat_trigger_c = static_cast<float>(config().heat_alert_limit_c);
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
  const SdStatus config_status = SdStatusCodec::fromConfig(result, g_state.sd_status);
  g_pending_sd_event = SdStatusCodec::faultEventDetail(g_state.sd_status, config_status); // the RTC is not read yet
  g_state.setSdStatus(config_status, nowMs());
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

// Refills the 24-hour histories from the hourly rows of the card after a power-on. It needs a valid RTC
// time and a mounted card; each failure leaves the histories empty and never blocks the wake cycle.
void restoreHistoriesFromCard(WakeupCause cause) {
  if (cause != WakeupCause::POWER_ON || g_state.sd_status == SdStatus::NO_CARD ||
      g_state.sd_status == SdStatus::MOUNT_FAILED) {
    return;
  }
  uint16_t fixed_slots = 0;
  for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
    if (config().hasSlotMac(i)) {
      fixed_slots = static_cast<uint16_t>(fixed_slots | (1u << i));
    }
  }
  SdLogger::restoreHistories(g_state.history, g_rtc_time, nowMs(), fixed_slots);
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
  // Each start that is no deep sleep wakeup gets a RESET event, also a failed restore (see eventDetail).
  const int32_t reset_detail = ResetReasonCodec::eventDetail(g_reset_reason, cause == WakeupCause::POWER_ON &&
                                                                              g_reset_reason == ResetReason::DEEPSLEEP);
  g_reset_event_pending = reset_detail >= 0;
  g_reset_event_detail = static_cast<uint16_t>(reset_detail >= 0 ? reset_detail : 0);
  if (g_reset_reason != ResetReason::DEEPSLEEP) {
    g_state.frame.invalidate(); // a reset between a flush and the save leaves a CRC that the panel no longer shows
  }
  g_timer_wakeup = cause == WakeupCause::TIMER; // after the restore: a failed restore is a power-on
  g_mode_controller.setMode(g_state.mode);
  g_mode_controller.setChartSensor(g_state.chart_sensor);
  g_mode_controller.setStatsPage(g_state.stats_page);
  g_cycles.fromRtc(g_cycle_rtc); // blank memory after a power-on keeps the empty tracker

  // Empty battery protection: check the cell before any module starts. A set latch skips the BLE
  // scan, the audio, the MicroSD card and Wi-Fi; the board only reads the voltage and sleeps again.
  const bool was_protected = g_state.battery.empty_protection;
  const BatteryReading protect_reading = BatteryMonitor::readAveraged(config().battery_offset_mv);
  g_state.battery.empty_protection = BatteryMonitor::updateEmptyProtection(protect_reading, was_protected);
  if (g_state.battery.empty_protection && !was_protected) {
    if (g_reset_event_pending) { // the start came first: the reset goes before the protection in the ring
      g_state.events.record(g_rtc_time, EventCode::RESET, g_reset_event_detail);
      g_reset_event_pending = false;
    }
    g_state.events.record(g_rtc_time, EventCode::EMPTY_PROTECTION, 1); // the RTC is not read here: no time
  } else if (was_protected && !g_state.battery.empty_protection) {
    g_pending_protection_release = true;
  }
  if (!PowerManager::modulesAllowed(g_state.battery)) {
    if (g_reset_event_pending) {
      g_state.events.record(g_rtc_time, EventCode::RESET, g_reset_event_detail); // the RTC is not read here: no time
      g_reset_event_pending = false;
    }
    ++g_state.boot_count;
    g_state.battery = protect_reading;
    g_state.battery.low_battery = true;
    g_state.battery.empty_protection = true;
    updateCycles(); // the last discharge steps before the protection count too
    if (PowerManager::needsProtectionRender(was_protected, true)) {
      ensurePanelStarted();
      DashboardView::renderEmptyBattery(g_framebuffer, g_state.battery);
      g_panel.flush(g_framebuffer); // the empty battery screen always flushes
      g_state.frame.invalidate(); // the stored frame no longer shows the dashboard
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
  updateRtc();
  restoreHistoriesFromCard(cause);
  // The panel starts at the first flush; an unchanged frame on a timer wakeup never starts it.

  g_pending_slot_release = g_state.releaseDeadSlots(nowMs()); // before the scan, so a free slot takes a new sensor in this cycle

  const bool scanned = cycle.advance() == PowerPhase::SCAN;
  if (scanned) {
    g_state.last_scan_ms = nowMs();
    initBle();
    uint32_t packets_before[MAX_SWITCHBOT_SENSORS];
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      packets_before[i] = g_state.registry.slot(i).packet_count;
    }
    // The battery reading is from the last wake cycle: one cycle after a plug-in keeps the old power state.
    scanOnce(ScanCompletion::fullScanRequired(
        cause, g_state.battery, g_state.registry, nowMs(), g_state.history.sampleDue(nowMs()), config().staleTimeoutMs()));
    BLEDevice::deinit(false);
    portENTER_CRITICAL(&g_registry_mux);
    g_state.reception.recordScan(g_state.registry, packets_before);
    portEXIT_CRITICAL(&g_registry_mux);
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
  if (scanned) { // only a cycle with a scan broadcasts, so the frame follows each scan
    broadcastTelemetry();
  }
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
  // The display mode changes on a KEY press only. The serial console runs on USB power only and has
  // its own USB watch. A command mounts the card for itself only.
  bool console_active = PowerManager::serialConsoleAllowed(g_state.battery);
  UsbPowerWatch console_watch(millis());
  if (console_active) {
    Serial.setTxTimeoutMs(1000); // a slow host reader must not shorten a DUMP frame
  }
  SerialLineAssembler console_line;
  SerialSink console_sink;
  SdLogStorage console_storage;
  // BLE console (serial_console.h BleConsoleBridge): runs on USB power while the board stays awake.
  BleConsoleService ble_console;
  ble_console.setLevel(PowerManager::bleConsoleAllowed(g_state.battery, config().ble_console) ? config().ble_console : 0);
  const bool wants_grace =
      cause != WakeupCause::TIMER || console_active;
  // One parsed command, shared by the USB serial console and the BLE console: only the sink differs.
  auto run_console = [&](auto& sink, SerialParseStatus status, const SerialCommand& command) {
    using Runner = SerialConsoleRunner<SdLogStorage, std::decay_t<decltype(sink)>>;
    constexpr uint8_t transport = std::is_same_v<std::decay_t<decltype(sink)>, SerialSink> ? 0 : 1; // 0 USB, 1 BLE
    if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::CONFIG) {
      // The RTC copy answers: no card access, so the console keeps running.
      Runner::showConfig(sink, g_state.config,
                                                                g_state.config_from_sd, g_state.config_rejects);
    } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::CALIB) {
      // The RTC histories answer: no card access, so the console keeps running.
      Runner::showCalib(sink, command.reference, g_state.history,
                                                               g_state.config);
    } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::SALT) {
      // The RTC histories answer: no card access, so the console keeps running.
      Runner::showSalt(sink, command.reference, g_state.history, g_state.config, command.salt_mgcl2);
    } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::WEEK) {
      // The RTC week history answers: no card access, so the console keeps running.
      Runner::showWeek(sink, g_state.week);
    } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::HISTORY) {
      // The RTC histories answer: no card access, so the console keeps running.
      Runner::showHistory(sink, g_state.history,
                                                                 g_state.noise_history);
    } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::EVENTS) {
      // The RTC ring answers: no card access, so the console keeps running. The ring stays.
      RtcTime events_now = g_rtc_time; // the wakeup time is old in a long USB session: read the clock again
      if (!g_rtc.readTime(events_now)) {
        events_now = g_rtc_time;
      }
      Runner::showEvents(sink, g_state.events, events_now);
    } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::VERSION) {
      // Constants answer: no card access, so the console keeps running.
      Runner::showVersion(sink, FIRMWARE_VERSION, RLCD_GIT_HASH,
                                                                 ARDUINO_CORE_VERSION, PowerManager::STATE_VERSION,
                                                                 __DATE__, __TIME__);
    } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::SET) {
      // The new text takes effect as after a KEY wakeup: parse it and apply it inside the registry lock.
      RtcTime set_now = g_rtc_time; // the wakeup time is old in a long USB session: read the clock again
      if (!g_rtc.readTime(set_now)) {
        set_now = g_rtc_time;
      }
      Runner::setConfig(
          console_storage, sink, command, [&set_now](const char* text, size_t len) {
            DeviceConfig loaded;
            const ConfigParseResult parsed = DeviceConfigParser::parse(text, len, loaded);
            portENTER_CRITICAL(&g_registry_mux);
            g_state.applyConfig(loaded, parsed.file_ok, parsed.rejects());
            portEXIT_CRITICAL(&g_registry_mux);
            // Same follow-up as a KEY wakeup: card status, event, and the sleep and listen settings.
            setSdStatusLogged(SdStatus::OK, set_now);
            g_state.events.record(set_now, EventCode::CONFIG_LOAD, static_cast<uint16_t>(parsed.rejects().count));
            g_power_config = PowerManager::powerConfig(config(), g_state.battery, quietHours(), g_rtc_time);
          },
          &g_state.events, &set_now, transport);
    } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::STATUS) {
      // The RTC copy answers: no card access, so the console keeps running.
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
      snap.awake = &g_state.awake;
      snap.reception = &g_state.reception;
      snap.reset_reason = ResetReasonCodec::text(g_reset_reason);
      snap.rtc_trim_valid = g_rtc.readOffset(snap.rtc_trim);
      snap.rtc_drift = &g_state.rtc_drift;
      ResetCountStore reset_store;
      snap.abnormal_resets = AbnormalResetCounter::read(reset_store);
      Runner::showStatus(sink, snap);
    } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::TIME) {
      // The RTC answers over I2C: no card access, so the console keeps running.
      if (command.time_set) {
        if (Runner::setTime(sink, g_rtc, g_state.rtc_drift,
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
        Runner::showTime(sink, now_time);
      }
    } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::RTC_TRIM) {
      // The RTC answers over I2C: no card access, so the console keeps running.
      Runner::trimRtc(sink, g_rtc, g_state.rtc_drift);
    } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::SCAN) {
      // The scan fills a collector only; the registry copy gives the slot column (only a wake cycle scan writes the
      // registry, and none runs while the console is active). No card access. The BLE radio is not the I2S bus.
      portENTER_CRITICAL(&g_registry_mux);
      const SwitchBotRegistry scan_registry = g_state.registry;
      portEXIT_CRITICAL(&g_registry_mux);
      ble_console.stop();
      Runner::scan(sink, scan_registry, consoleScanOnce);
      ble_console.start(); // the scan stopped the BLE stack
    } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::PATTERN) {
      // Raw flush without display_invert: g_panel.flush, not flushFramebuffer(); keep it so (the host test checks the raw bytes). No card access. The pattern
      // stays until the next render; the next wake cycle draws the normal view again.
      ensurePanelStarted(); // a skipped refresh left the panel off
      Runner::showPattern(sink, g_framebuffer, g_panel,
                                                                command.reference, &g_state.frame);
      g_frame_rendered = false; // the framebuffer holds the pattern, not a render: SCREEN gives ERR NO_FRAME
    } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::SCREEN) {
      // The bytes of the last render (after invert() with display_invert 1). No card access, no panel flush.
      Runner::showScreen(sink, g_framebuffer, g_frame_rendered);
    } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::MUTE) {
      // The flag goes to the RTC copy; the save of this wake cycle writes it with the audio trailer. No card access.
      const bool was_muted = g_state.muted;
      Runner::setMute(sink, g_state.muted, command.reference);
      if (g_state.muted != was_muted) {
        renderDashboard(); // the header mute marker shows the new state
      }
    } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::SLOT_CLEAR) {
      // RTC copy only, no card access; the save of this wake cycle writes the state.
      RtcTime clear_now = g_rtc_time; // the wakeup time is old in a long USB session: read the clock again (I2C, outside the lock)
      if (!g_rtc.readTime(clear_now)) {
        clear_now = g_rtc_time;
      }
      portENTER_CRITICAL(&g_registry_mux); // the BLE callback writes the registry
      const bool slot_cleared = g_state.clearSlotByUser(static_cast<size_t>(command.reference) - 1, clear_now);
      portEXIT_CRITICAL(&g_registry_mux);
      Runner::slotClearReply(sink, slot_cleared, command.reference);
    } else if (status == SerialParseStatus::OK && command.kind == SerialCommandKind::BEEP) {
      Es8311Hal beep_hal;
      Runner::beep(sink, beep_hal, command.reference,
                                                         g_state.muted, quietHours().isActive(g_rtc_time),
                                                         config().chime_volume_pct);
    } else if (status == SerialParseStatus::OK) {
      Runner::execute(console_storage, sink, command);
    } else {
      Runner::reject(sink, status);
    }
  };
  // A pending KEY or BOOT press (long press check) keeps the window open; it ends within 2 s.
  while (wants_grace &&
         (millis() - grace_start < KEY_GRACE_MS || g_mode_controller.pressPending() ||
          g_boot_button.pressPending() ||
          (console_active &&
           PowerManager::continuousListenAllowed(g_power_config, nowMs(), g_state.last_scan_ms)))) {
    if (console_active && console_watch.due(millis()) &&
        !console_watch.update(BatteryMonitor::read(config().battery_offset_mv).usb_powered, millis())) {
      console_active = false;
    }
    // The gate follows the USB watch and the config: SET ble_console=0 or an unplugged cable stops the service.
    const uint8_t ble_level =
        console_active && PowerManager::bleConsoleAllowed(g_state.battery, config().ble_console) ? config().ble_console : 0;
    ble_console.setLevel(ble_level);
    if (ble_level == 0) {
      ble_console.resetFailure();
      ble_console.stop(); // no-op when stopped
    } else {
      ble_console.start(); // no-op while running
    }
    if (ble_console.level() != 0 && ble_console.pending()) {
      ble_console.serve(run_console); // one command per loop pass
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
      run_console(console_sink, status, command);
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
          Es8311Hal hal;
          AudioSequencer::playConfirm(hal, config().chime_volume_pct);
        }
        renderDashboard(); // the header mute marker shows the new state
        break;
      case BootEvent::LOOPBACK:
        runLoopbackTest();
        g_boot_button.cancelPress();
        break;
      case BootEvent::NONE:
      default:
        break;
    }
    if (g_mode_controller.mode() == DisplayMode::SENSOR_STATS) {
      updateStatsPages(); // only this view reads the flag
    }
    switch (g_mode_controller.updateWithLongPress(digitalRead(PIN_BTN_KEY) == LOW, millis())) {
      case ButtonEvent::MODE_CHANGED:
        renderDashboard();
        measureNoiseAndRedraw(true);
        g_spectrum_attempted = false; // a new entry to the view captures again
        g_spectrum = SpectrumReading{}; // old bars never show while the new capture runs
        captureSpectrumAndRedraw(true);
        break;
      case ButtonEvent::NEXT_PAGE:
        renderDashboard(); // the stats view reads only RTC state: no sensor read, no microphone
        break;
      case ButtonEvent::NEXT_SENSOR:
        renderDashboard(); // the chart reads only the RTC histories: no sensor read, no microphone
        break;
      case ButtonEvent::RECAPTURE:
        captureSpectrumAndRedraw(true, true);
        break;
      case ButtonEvent::CALIBRATE:
        calibrateMicrophone();
        g_mode_controller.cancelPress();
        break;
      case ButtonEvent::NONE:
      default:
        break;
    }
    delay(10);
  }
  ble_console.stop(); // the BLE service never outlives the awake window

  cycle.advance(); // SAVE
  // Awake time of this cycle; only a timer wakeup on battery power counts (millis() restarts after sleep).
  // An invalid battery reading has an unknown power source and counts as USB power.
  if (AwakeTimeStats::wanted(cause == WakeupCause::TIMER, !g_state.battery.valid || g_state.battery.usb_powered)) {
    g_state.awake.record(millis());
  }
  g_state.mode = g_mode_controller.mode();
  g_state.chart_sensor = g_mode_controller.chartSensor();
  g_state.stats_page = g_mode_controller.statsPage();
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
