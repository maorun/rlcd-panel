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
#include "ntp_sync.h"
#include "pcf85063_rtc.h"
#include "power_manager.h"
#include "sd_logger.h"
#include "shtc3_sensor.h"
#include "st7305_framebuffer.h"
#include "st7305_panel.h"
#include "switchbot_ble.h"

using namespace rlcd_panel;

namespace {

St7305Framebuffer g_framebuffer;
St7305Panel g_panel;
PersistentState g_state; // registry, battery, and view mode; saved to RTC memory before sleep
portMUX_TYPE g_registry_mux = portMUX_INITIALIZER_UNLOCKED;
Pcf85063Rtc g_rtc;
RtcTime g_rtc_time;
Shtc3Reading g_onboard_reading;
BLEScan* g_ble_scan = nullptr;
DisplayModeController g_mode_controller;
std::atomic<bool> g_scan_running{false};
PowerConfig g_power_config;
NoiseReading g_noise; // measured at most once per wake cycle: diagnostics view or hourly SD record
bool g_noise_attempted = false; // a failed window is not retried in the same wake cycle
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
    // Keep this timestamp at or before the nowMs() read in renderDashboard() so a fresh reading
    // never appears to lie in the future (unsigned subtraction would flag it stale).
    reading.updated_ms = nowMs();

    portENTER_CRITICAL(&g_registry_mux);
    g_state.registry.upsert(reading);
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
  return Shtc3Codec::decodeFrame(frame, sizeof(frame), out, config().temp_offset_c);
}

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
  if (NtpSchedule::decide(g_state.ntp, nowMs(), key_wakeup, credentials, g_rtc.present(), battery_low) !=
      NtpDecision::ATTEMPT) {
    return false;
  }
  const NtpResult result = NtpClient::sync(g_rtc, RLCD_WIFI_SSID, RLCD_WIFI_PASSWORD, tz);
  NtpSchedule::record(g_state.ntp, nowMs(), result == NtpResult::OK);
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
  } else {
    Serial.println("Noise measurement failed");
  }
}

void renderDashboard() {
  SwitchBotReading snapshot[MAX_SWITCHBOT_SENSORS];
  portENTER_CRITICAL(&g_registry_mux);
  for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
    snapshot[i] = g_state.registry.slot(i);
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
  info.mic_cal_busy = g_mic_cal_busy;
  info.mic_cal_failed = g_mic_cal_failed;
  info.quiet_hours = quietHours().isActive(g_rtc_time);
  info.loopback = g_loopback;
  info.spectrum = g_spectrum;

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
      &g_state.noise_history);
  g_panel.flush(g_framebuffer);
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
void scanOnce() {
  if (g_ble_scan == nullptr) {
    return;
  }
  const uint32_t duration_s = g_power_config.sanitized().scan_duration_s;
  // Set the flag first: a fast completion callback in the BLE task must not be overwritten.
  g_scan_running = true;
  if (!g_ble_scan->start(duration_s, onScanComplete, false)) {
    g_scan_running = false;
    return;
  }
  const uint32_t started = millis();
  while (g_scan_running && millis() - started < duration_s * 1000 + SCAN_WATCHDOG_MARGIN_MS) {
    delay(10);
  }
  if (g_scan_running) {
    g_ble_scan->stop();
    g_scan_running = false;
    delay(100); // let a late completion callback from the BLE task finish
  }
}

void refreshSensors() {
  readOnboardShtc3(g_onboard_reading);
  // After a reset of the RTC state the latch starts off: a cell at 16..19 % raises no alert until
  // it reaches the set threshold.
  const bool was_low = g_state.battery.low_battery;
  g_state.battery = BatteryMonitor::read();
  g_state.battery.low_battery = BatteryMonitor::updateLowBattery(g_state.battery, was_low);
  updateRtc();
  // One sample per hour and sensor; the gate lives in the persisted state.
  g_state.history.record(
      nowMs(), g_onboard_reading.valid, g_onboard_reading.humidity_pct, g_state.registry);
}

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
  for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
    snapshot[i] = g_state.registry.slot(i);
  }
  const MicCalibration cal = activeMicCal();
  const SdLogResult result = SdLogger::append(
      g_rtc_time, g_state.battery, g_onboard_reading, snapshot, MAX_SWITCHBOT_SENSORS, now,
      have_noise ? &g_noise : nullptr, &cal);
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
}

AlertLimits alertLimits() {
  AlertLimits limits;
  limits.freezer_trigger_c = config().freeze_alert_limit_c;
  limits.humidity_trigger_pct = static_cast<float>(config().mold_alert_limit_rh);
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
  const SdConfigResult result = SdLogger::loadConfig(loaded);
  if (result == SdConfigResult::NO_CARD) {
    return;
  }
  g_state.config = loaded;
  g_state.config_from_sd = result == SdConfigResult::LOADED;
}

// Evaluates the climate thresholds and sounds a chime for each alert that switched on. The mask
// is stored even while muted or in quiet hours, so unmuting or the end of the quiet hours never
// replays an old alert. The amplifier is off again
// before this function returns, so deep sleep draws no speaker current.
void chimeOnAlerts() {
  SwitchBotReading snapshot[MAX_SWITCHBOT_SENSORS];
  for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
    snapshot[i] = g_state.registry.slot(i);
  }
  const uint8_t current = AlertEvaluator::evaluate(
      g_state.alert_mask, g_onboard_reading, snapshot, MAX_SWITCHBOT_SENSORS, nowMs(), alertLimits());
  // Quiet hours suppress the sound only. The mask below is stored, so no chime replays later.
  const bool quiet = quietHours().isActive(g_rtc_time);
  const uint8_t bits = AlertEvaluator::chimeBits(g_state.muted, g_state.alert_mask, current, quiet);
  g_state.alert_mask = current;
  if (bits != ALERT_NONE) {
    Es8311Hal hal;
    if (!AudioSequencer::play(hal, bits, config().chime_volume_pct)) {
      Serial.println("Chime failed");
    }
  }
}

} // namespace

void setup() {
  Serial.begin(115200);

  PowerPlatform::releasePinsAfterSleep(); // KEY and display pins stayed held during deep sleep
  pinMode(PIN_BTN_KEY, INPUT_PULLUP);
  pinMode(PIN_BTN_BOOT, INPUT_PULLUP);

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
  g_mic_cal = MicCalibrationStore::load();
  ++g_state.boot_count;
  loadConfigFromCard(cause);

  Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL);
  g_rtc.begin(Wire);
  g_panel.begin();

  if (cycle.advance() == PowerPhase::SCAN) {
    g_state.last_scan_ms = nowMs();
    initBle();
    scanOnce();
    BLEDevice::deinit(false);
    cycle.advance();
  }

  // The KEY press that woke the board cycles the view.
  if (cause == WakeupCause::KEY_BUTTON) {
    g_mode_controller.next();
  }
  refreshSensors();
  renderDashboard();
  chimeOnAlerts();
  logToSdCard(); // after the render: the display shows fresh data even if the card is slow
  // The sync runs after the first render, so a slow or absent network never delays the display.
  // A new time redraws the header clock.
  if (syncNtpTime(cause == WakeupCause::KEY_BUTTON)) {
    updateRtc();
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
  const bool wants_grace = cause != WakeupCause::TIMER || clap_mode == ClapListenMode::CONTINUOUS;
  // Continuous listening ends when USB power ends or the listener fails; the grace window stays.
  bool continuous_listen = clap_mode == ClapListenMode::CONTINUOUS;
  UsbPowerWatch usb_watch(millis());
  // A pending KEY or BOOT press (long press check) keeps the window open; it ends within 2 s.
  while (wants_grace &&
         (millis() - grace_start < KEY_GRACE_MS || g_mode_controller.pressPending() ||
          g_boot_button.pressPending() ||
          (continuous_listen &&
           PowerManager::continuousListenAllowed(g_power_config, nowMs(), g_state.last_scan_ms)))) {
    if (continuous_listen && usb_watch.due(millis()) &&
        !usb_watch.update(BatteryMonitor::read().usb_powered, millis())) {
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
