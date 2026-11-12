#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "audio_alert.h"
#include "es7210_mic.h"
#include "battery_monitor.h"
#include "board_pins.h"
#include "button_handler.h"
#include "clap_detector.h"
#include "device_config.h"
#include "ntp_sync.h"
#include "sd_logger.h"
#include "sparkline.h"
#include "switchbot_ble.h"

#if defined(ARDUINO) || defined(ESP_PLATFORM)
#include <driver/gpio.h>
#include <driver/rtc_io.h>
#include <esp_attr.h>
#include <esp_sleep.h>
#include <esp_system.h>
#include <sys/time.h>
#endif

namespace rlcd_panel {

// Wakeup cause as seen by the firmware. KEY_BUTTON wakes the board through ext0 on GPIO 18.
enum class WakeupCause : uint8_t {
  POWER_ON = 0, // cold boot, reset, or any wakeup that keeps no RTC memory
  TIMER = 1,
  KEY_BUTTON = 2,
  OTHER = 3,
};

// One wake cycle runs these phases in order. A KEY wakeup skips SCAN.
enum class PowerPhase : uint8_t {
  BOOT = 0,
  RESTORE = 1,
  SCAN = 2,
  RENDER = 3,
  SAVE = 4,
  SLEEP = 5,
};

struct PowerConfig {
  static constexpr uint32_t DEFAULT_SLEEP_INTERVAL_S = 60;
  static constexpr uint32_t DEFAULT_SCAN_DURATION_S = 4;
  static constexpr uint32_t MIN_SLEEP_INTERVAL_S = 10;
  static constexpr uint32_t MAX_SLEEP_INTERVAL_S = 3600;

  uint32_t sleep_interval_s = DEFAULT_SLEEP_INTERVAL_S; // start of one scan to start of the next
  uint32_t scan_duration_s = DEFAULT_SCAN_DURATION_S;

  // Returns a copy with the interval inside its limits and a scan that fits into the interval.
  PowerConfig sanitized() const {
    PowerConfig c = *this;
    if (c.sleep_interval_s < MIN_SLEEP_INTERVAL_S) {
      c.sleep_interval_s = MIN_SLEEP_INTERVAL_S;
    } else if (c.sleep_interval_s > MAX_SLEEP_INTERVAL_S) {
      c.sleep_interval_s = MAX_SLEEP_INTERVAL_S;
    }
    if (c.scan_duration_s < 1) {
      c.scan_duration_s = 1;
    }
    if (c.scan_duration_s > c.sleep_interval_s / 2) {
      c.scan_duration_s = c.sleep_interval_s / 2;
    }
    return c;
  }
};

// Everything that must survive deep sleep.
struct PersistentState {
  uint32_t boot_count = 0;    // wake cycles since power-on
  uint32_t last_scan_ms = 0;  // clock value at the start of the last BLE scan
  DisplayMode mode = DisplayMode::DASHBOARD;
  BatteryReading battery;
  SwitchBotRegistry registry;
  ClimateHistory history; // hourly humidity samples for the trend curves
  NoiseHistory noise_history; // hourly noise levels (dB) for the stats view
  BatteryHistory battery_history; // hourly cell voltage for the runtime estimate
  uint32_t last_log_ms = 0;  // clock value of the last MicroSD record
  bool has_logged = false;   // false until the first MicroSD attempt, success or failure
  DailyLogState daily;       // local day of the last handled daily summary
  bool muted = false;        // true: alerts stay silent
  uint8_t alert_mask = ALERT_NONE; // alerts active in the last wake cycle (hysteresis latch)
  NoiseBand noise_band = NoiseBand::QUIET; // band of the last noise window (hysteresis latch)
  NtpSyncState ntp;          // NTP attempt time, result and backoff
  RtcDriftState rtc_drift;   // RTC drift of the last NTP sync
  DeviceConfig config;       // settings from /sdcard/config.ini; a timer wakeup reuses them
  bool config_from_sd = false; // true: the card supplied `config`; false: the defaults apply
};

struct WakeupPlan {
  uint64_t timer_us = 0;
  bool enable_key_wakeup = false;
};

// Watches the USB power during continuous clap listening. due() is true every RECHECK_MS. The
// caller then reads the battery and passes the result to update(). One low sample can come from a
// bad ADC read, so LOST_SAMPLES samples in a row without USB power end the watch. Unsigned
// subtraction keeps the timing correct when the clock wraps around.
class UsbPowerWatch {
public:
  static constexpr uint32_t RECHECK_MS = 5000;
  static constexpr uint8_t LOST_SAMPLES = 2;

  explicit UsbPowerWatch(uint32_t now_ms) : last_ms_(now_ms) {}

  bool due(uint32_t now_ms) const {
    return now_ms - last_ms_ >= RECHECK_MS;
  }

  // Returns true while USB power stays present.
  bool update(bool usb_powered, uint32_t now_ms) {
    last_ms_ = now_ms;
    low_count_ = usb_powered ? 0 : static_cast<uint8_t>(low_count_ + 1);
    return low_count_ < LOST_SAMPLES;
  }

private:
  uint32_t last_ms_;
  uint8_t low_count_ = 0;
};

class PowerManager {
public:
  static constexpr uint32_t STATE_MAGIC = 0x52504C43; // 'RPLC'
  static constexpr uint8_t STATE_VERSION = 23;
  static constexpr uint64_t MIN_SLEEP_US = 1000000ULL;
  // Sleep time while the empty battery protection is set: one hour, then the voltage is checked.
  static constexpr uint64_t PROTECTED_SLEEP_US = 3600ULL * 1000000ULL;
  // Slot layout: valid, mac[6], temperature (float bits), humidity, battery, fahrenheit,
  // rssi (int8), updated_ms, packet_count.
  static constexpr size_t SLOT_SIZE = 1 + 6 + 4 + 1 + 1 + 1 + 1 + 4 + 4;
  // History ring (humidity, then temperature, per slot): count, head, 24 samples (int16). Trailer: last sample time, sampled flag.
  static constexpr size_t HISTORY_RING_SIZE = 1 + 1 + HumidityHistory::CAPACITY * 2;
  // Each slot has a humidity ring and a temperature ring of the same layout.
  static constexpr size_t HISTORY_SIZE =
      ClimateHistory::SLOT_COUNT * 2 * HISTORY_RING_SIZE + 4 + 1;
  // Noise history: count, head, 24 samples (one byte, 0xFF is an invalid hour).
  static constexpr size_t NOISE_HISTORY_SIZE = 1 + 1 + NoiseHistory::CAPACITY;
  // Battery history: count, head, 24 samples (uint16 millivolts), 24 stamps (uint32), last
  // sample time, sampled flag.
  static constexpr size_t BATTERY_HISTORY_SIZE = 1 + 1 + BatteryHistory::CAPACITY * (2 + 4) + 4 + 1;
  // SD log trailer: last log time, logged flag, then the daily summary stamp: flag, year (uint16), month, day.
  static constexpr size_t SD_LOG_SIZE = 4 + 1 + 1 + 2 + 1 + 1;
  // Audio trailer: one byte, bit 0 muted, bits 1..5 alert mask, bits 6..7 noise band.
  static constexpr size_t AUDIO_SIZE = 1;
  // NTP trailer: last attempt time, flags (bit 0 attempted, bit 1 last ok), failure count, then the
  // RTC drift: flags (bit 0 synced, bit 1 drift known), last sync (UTC seconds), drift, rate (int32).
  static constexpr size_t NTP_SIZE = 4 + 1 + 1 + 1 + 4 + 4 + 4;
  // Sensor names: the onboard name and one name for each slot, each with its terminator.
  static constexpr size_t NAMES_SIZE = (1 + DeviceConfig::SLOT_COUNT) * (DeviceConfig::NAME_MAX_CHARS + 1);
  // Config trailer: temp offset, freeze limit (floats), mold limit, volume, quiet start hour,
  // quiet end hour, SPL offset (float), log retention months, noise alert limit, chime alert mask, NTP sync interval, RH offset (float), three slot temperature offsets and three slot RH offsets (floats), the sensor names, flags (bit 0 loaded from the card).
  static constexpr size_t CONFIG_SIZE = 4 + 4 + 1 + 1 + 1 + 1 + 4 + 1 + 1 + 1 + 1 + 4 + 6 * 4 + NAMES_SIZE + 1;
  // Header: magic, version, mode, battery flags, battery pct, battery voltage, boot_count,
  // last_scan_ms. Trailer: CRC-32 over all earlier bytes.
  static constexpr size_t HEADER_SIZE = 4 + 1 + 1 + 1 + 1 + 4 + 4 + 4;
  static constexpr size_t SERIALIZED_SIZE = HEADER_SIZE + MAX_SWITCHBOT_SENSORS * SLOT_SIZE + HISTORY_SIZE + NOISE_HISTORY_SIZE + BATTERY_HISTORY_SIZE + SD_LOG_SIZE + AUDIO_SIZE + NTP_SIZE + CONFIG_SIZE + 4;

  static WakeupCause decodeWakeupCause(int esp_cause) {
    // Values of esp_sleep_source_t; the ESP-only wrapper asserts them against the SDK.
    switch (esp_cause) {
      case 0: return WakeupCause::POWER_ON;  // ESP_SLEEP_WAKEUP_UNDEFINED
      case 4: return WakeupCause::TIMER;     // ESP_SLEEP_WAKEUP_TIMER
      case 2:                                // ESP_SLEEP_WAKEUP_EXT0
      case 3: return WakeupCause::KEY_BUTTON; // ESP_SLEEP_WAKEUP_EXT1
      default: return WakeupCause::OTHER;
    }
  }

  // Only a wakeup from deep sleep leaves valid RTC memory and a continuous clock.
  static bool stateSurvives(WakeupCause cause) {
    return cause == WakeupCause::TIMER || cause == WakeupCause::KEY_BUTTON;
  }

  static bool needsScan(WakeupCause cause) {
    return cause != WakeupCause::KEY_BUTTON;
  }

  // The card is read on power-on and on a KEY wakeup only. A timer wakeup reuses the RTC copy,
  // so the card stays unmounted in the normal cycle.
  static bool needsConfigLoad(WakeupCause cause) {
    return cause == WakeupCause::POWER_ON || cause == WakeupCause::KEY_BUTTON;
  }

  // While the protection is set, the wake cycle skips the BLE scan, the audio (chimes, microphones,
  // clap listener), the MicroSD card and Wi-Fi. Only the battery read, the warning and the sleep run.
  static bool modulesAllowed(const BatteryReading& battery) {
    return !battery.empty_protection;
  }

  // The warning is drawn once, when the protection sets. The reflective panel keeps the image
  // without power, so every later wake cycle with a set latch leaves the display alone.
  static bool needsProtectionRender(bool was_latched, bool latched) {
    return latched && !was_latched;
  }

  // Where the board listens for a double clap in this wake cycle. USB power: listen
  // continuously, also after a timer wakeup. Battery: listen in the KEY grace window only, which
  // runs after a KEY wakeup or a power-on. A timer wakeup on battery keeps the microphones off.
  // A low battery alert on battery power keeps them off.
  static ClapListenMode clapListenMode(const BatteryReading& battery, WakeupCause cause) {
    if (battery.valid && battery.usb_powered) {
      return ClapListenMode::CONTINUOUS;
    }
    if (battery.valid && battery.low_battery) {
      return ClapListenMode::OFF;
    }
    return (cause == WakeupCause::TIMER) ? ClapListenMode::OFF : ClapListenMode::GRACE;
  }

  // The USB serial console takes commands on USB power only. On battery the board neither reads
  // the port nor stays awake for it, so the awake time does not grow. An invalid battery reading
  // counts as no USB power.
  static bool serialConsoleAllowed(const BatteryReading& battery) {
    return battery.valid && battery.usb_powered;
  }

  // True while a continuous listener may keep the board awake: the next scan is not yet due.
  static bool continuousListenAllowed(const PowerConfig& config, uint32_t now_ms, uint32_t last_scan_ms) {
    return sleepMicros(config, now_ms, last_scan_ms) > MIN_SLEEP_US;
  }

  // Microseconds until the next scan is due: interval minus the time since the last scan start.
  // The result lies in [MIN_SLEEP_US, interval]. Unsigned subtraction survives clock wrap-around.
  static uint64_t sleepMicros(const PowerConfig& config, uint32_t now_ms, uint32_t last_scan_ms) {
    const PowerConfig c = config.sanitized();
    const uint64_t interval_ms = static_cast<uint64_t>(c.sleep_interval_s) * 1000ULL;
    const uint64_t elapsed_ms = static_cast<uint32_t>(now_ms - last_scan_ms);
    if (elapsed_ms >= interval_ms) {
      return MIN_SLEEP_US;
    }
    const uint64_t us = (interval_ms - elapsed_ms) * 1000ULL;
    return us < MIN_SLEEP_US ? MIN_SLEEP_US : us;
  }

  // The KEY wakeup stays off while the button is still held: ext0 on a LOW level would wake
  // the board again at once.
  static WakeupPlan planSleep(
      const PowerConfig& config, uint32_t now_ms, uint32_t last_scan_ms, bool key_pressed) {
    WakeupPlan plan;
    plan.timer_us = sleepMicros(config, now_ms, last_scan_ms);
    plan.enable_key_wakeup = !key_pressed;
    return plan;
  }

  // Sleep plan of the empty battery protection: fixed one hour timer, KEY wakeup unless the button
  // is still held.
  static WakeupPlan planProtectedSleep(bool key_pressed) {
    WakeupPlan plan;
    plan.timer_us = PROTECTED_SLEEP_US;
    plan.enable_key_wakeup = !key_pressed;
    return plan;
  }

  static uint32_t crc32(const uint8_t* data, size_t len) {
    uint32_t crc = 0xFFFFFFFFu;
    for (size_t i = 0; i < len; ++i) {
      crc ^= data[i];
      for (int bit = 0; bit < 8; ++bit) {
        crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u)));
      }
    }
    return ~crc;
  }

  // Writes SERIALIZED_SIZE bytes. Returns false when the buffer is too small. The caller must
  // make sure the BLE scan callback no longer writes to the registry.
  static bool serialize(const PersistentState& state, uint8_t* out, size_t capacity) {
    if (out == nullptr || capacity < SERIALIZED_SIZE) {
      return false;
    }
    memset(out, 0, SERIALIZED_SIZE);
    size_t pos = 0;
    putU32(out, pos, STATE_MAGIC);
    out[pos++] = STATE_VERSION;
    out[pos++] = static_cast<uint8_t>(state.mode);
    out[pos++] = static_cast<uint8_t>((state.battery.valid ? 1 : 0) | (state.battery.usb_powered ? 2 : 0) |
                                     (state.battery.low_battery ? 4 : 0) | (state.battery.empty_protection ? 8 : 0));
    out[pos++] = static_cast<uint8_t>(state.battery.percentage);
    putFloat(out, pos, state.battery.voltage_v);
    putU32(out, pos, state.boot_count);
    putU32(out, pos, state.last_scan_ms);
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      const SwitchBotReading& r = state.registry.slot(i);
      if (!r.valid) {
        pos += SLOT_SIZE; // zero-filled
        continue;
      }
      out[pos++] = 1;
      memcpy(out + pos, r.mac, 6);
      pos += 6;
      putFloat(out, pos, r.temperature_c);
      out[pos++] = r.humidity_pct;
      out[pos++] = static_cast<uint8_t>(r.battery_pct);
      out[pos++] = r.fahrenheit_mode ? 1 : 0;
      out[pos++] = static_cast<uint8_t>(r.rssi);
      putU32(out, pos, r.updated_ms);
      putU32(out, pos, r.packet_count);
    }
    for (size_t i = 0; i < ClimateHistory::SLOT_COUNT; ++i) {
      const HumidityHistory& h = state.history.slots[i];
      out[pos++] = static_cast<uint8_t>(h.size());
      out[pos++] = h.head();
      for (size_t k = 0; k < HumidityHistory::CAPACITY; ++k) {
        const uint16_t bits = static_cast<uint16_t>(h.rawSample(k));
        out[pos++] = static_cast<uint8_t>(bits & 0xFF);
        out[pos++] = static_cast<uint8_t>(bits >> 8);
      }
      const TemperatureHistory& t = state.history.temperatures[i];
      out[pos++] = static_cast<uint8_t>(t.size());
      out[pos++] = t.head();
      for (size_t k = 0; k < TemperatureHistory::CAPACITY; ++k) {
        const uint16_t bits = static_cast<uint16_t>(t.rawSample(k));
        out[pos++] = static_cast<uint8_t>(bits & 0xFF);
        out[pos++] = static_cast<uint8_t>(bits >> 8);
      }
    }
    putU32(out, pos, state.history.last_sample_ms);
    out[pos++] = state.history.has_sampled ? 1 : 0;
    out[pos++] = static_cast<uint8_t>(state.noise_history.size());
    out[pos++] = state.noise_history.head();
    for (size_t k = 0; k < NoiseHistory::CAPACITY; ++k) {
      out[pos++] = state.noise_history.rawSample(k);
    }
    out[pos++] = static_cast<uint8_t>(state.battery_history.size());
    out[pos++] = state.battery_history.head();
    for (size_t k = 0; k < BatteryHistory::CAPACITY; ++k) {
      const uint16_t mv = state.battery_history.rawSample(k);
      out[pos++] = static_cast<uint8_t>(mv & 0xFF);
      out[pos++] = static_cast<uint8_t>(mv >> 8);
    }
    for (size_t k = 0; k < BatteryHistory::CAPACITY; ++k) {
      putU32(out, pos, state.battery_history.rawTime(k));
    }
    putU32(out, pos, state.battery_history.last_sample_ms);
    out[pos++] = state.battery_history.has_sampled ? 1 : 0;
    putU32(out, pos, state.last_log_ms);
    out[pos++] = state.has_logged ? 1 : 0;
    out[pos++] = state.daily.has_date ? 1 : 0;
    out[pos++] = static_cast<uint8_t>(state.daily.year & 0xFF);
    out[pos++] = static_cast<uint8_t>(state.daily.year >> 8);
    out[pos++] = state.daily.month;
    out[pos++] = state.daily.day;
    out[pos++] = static_cast<uint8_t>((state.muted ? 1 : 0) | ((state.alert_mask & ALERT_MASK_ALL) << 1) |
        (static_cast<uint8_t>(state.noise_band) << 6));
    putU32(out, pos, state.ntp.last_attempt_ms);
    out[pos++] = static_cast<uint8_t>((state.ntp.has_attempted ? 1 : 0) | (state.ntp.last_ok ? 2 : 0));
    out[pos++] = state.ntp.failures;
    // An out-of-range figure is stored as "no figure", so deserialize never drops the whole state.
    const RtcDriftState& rd = state.rtc_drift;
    const bool figure = rd.has_sync && rd.has_drift && rd.drift_s >= -RtcDrift::MAX_DRIFT_S &&
                        rd.drift_s <= RtcDrift::MAX_DRIFT_S && rd.rate_ppm >= -RtcDrift::MAX_PPM &&
                        rd.rate_ppm <= RtcDrift::MAX_PPM && !(rd.drift_s > 0 && rd.rate_ppm < 0) &&
                        !(rd.drift_s < 0 && rd.rate_ppm > 0) && !(rd.drift_s == 0 && rd.rate_ppm != 0);
    const bool synced = rd.has_sync && rd.last_sync_s >= RtcDrift::EPOCH_2000 && rd.last_sync_s < RtcDrift::EPOCH_2100;
    out[pos++] = static_cast<uint8_t>((synced ? 1 : 0) | (synced && figure ? 2 : 0));
    putU32(out, pos, synced ? rd.last_sync_s : 0);
    putU32(out, pos, synced && figure ? static_cast<uint32_t>(rd.drift_s) : 0);
    putU32(out, pos, synced && figure ? static_cast<uint32_t>(rd.rate_ppm) : 0);
    putFloat(out, pos, state.config.temp_offset_c);
    putFloat(out, pos, state.config.freeze_alert_limit_c);
    out[pos++] = state.config.mold_alert_limit_rh;
    out[pos++] = state.config.chime_volume_pct;
    out[pos++] = state.config.quiet_hours_start_hour;
    out[pos++] = state.config.quiet_hours_end_hour;
    putFloat(out, pos, state.config.mic_spl_offset_db);
    out[pos++] = state.config.log_retention_months;
    out[pos++] = state.config.noise_alert_limit_db;
    out[pos++] = state.config.chime_alert_mask;
    out[pos++] = state.config.ntp_sync_interval_h;
    putFloat(out, pos, state.config.rh_offset_pct);
    for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
      putFloat(out, pos, state.config.slot_temp_offset_c[i]);
    }
    for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
      putFloat(out, pos, state.config.slot_rh_offset_pct[i]);
    }
    memcpy(out + pos, state.config.onboard_name, DeviceConfig::NAME_MAX_CHARS + 1);
    pos += DeviceConfig::NAME_MAX_CHARS + 1;
    for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
      memcpy(out + pos, state.config.slot_name[i], DeviceConfig::NAME_MAX_CHARS + 1);
      pos += DeviceConfig::NAME_MAX_CHARS + 1;
    }
    out[pos++] = state.config_from_sd ? 1 : 0;
    putU32(out, pos, crc32(out, pos));
    return pos == SERIALIZED_SIZE;
  }

  // Rejects wrong size, magic, version, checksum, and out-of-range content. On failure, `out`
  // keeps its default values.
  static bool deserialize(const uint8_t* data, size_t len, PersistentState& out) {
    out = PersistentState{};
    if (data == nullptr || len != SERIALIZED_SIZE) {
      return false;
    }
    const size_t crc_pos = SERIALIZED_SIZE - 4;
    size_t pos = crc_pos;
    if (getU32(data, pos) != crc32(data, crc_pos)) {
      return false;
    }

    PersistentState s;
    pos = 0;
    if (getU32(data, pos) != STATE_MAGIC || data[pos++] != STATE_VERSION) {
      return false;
    }
    const uint8_t mode = data[pos++];
    if (mode >= DISPLAY_MODE_COUNT) {
      return false;
    }
    s.mode = static_cast<DisplayMode>(mode);
    const uint8_t flags = data[pos++];
    if ((flags & ~15u) != 0) {
      return false;
    }
    s.battery.valid = (flags & 1) != 0;
    s.battery.usb_powered = (flags & 2) != 0;
    s.battery.low_battery = (flags & 4) != 0;
    s.battery.empty_protection = (flags & 8) != 0;
    s.battery.percentage = static_cast<int8_t>(data[pos++]);
    s.battery.voltage_v = getFloat(data, pos);
    if (s.battery.percentage < -1 || s.battery.percentage > 100 ||
        !(s.battery.voltage_v >= 0.0f && s.battery.voltage_v <= 10.0f)) {
      return false;
    }
    s.boot_count = getU32(data, pos);
    s.last_scan_ms = getU32(data, pos);

    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      const uint8_t valid = data[pos++];
      if (valid > 1) {
        return false;
      }
      if (valid == 0) {
        pos += SLOT_SIZE - 1;
        continue;
      }
      SwitchBotReading r;
      r.valid = true;
      memcpy(r.mac, data + pos, 6);
      pos += 6;
      r.temperature_c = getFloat(data, pos);
      r.humidity_pct = data[pos++];
      r.battery_pct = static_cast<int8_t>(data[pos++]);
      const uint8_t fahrenheit = data[pos++];
      r.rssi = static_cast<int8_t>(data[pos++]);
      r.updated_ms = getU32(data, pos);
      r.packet_count = getU32(data, pos);
      if (!(r.temperature_c >= -100.0f && r.temperature_c <= 100.0f) || r.humidity_pct > 100 ||
          r.battery_pct < -1 || r.battery_pct > 100 || fahrenheit > 1) {
        return false;
      }
      r.fahrenheit_mode = fahrenheit == 1;
      if (!s.registry.restoreSlot(i, r)) {
        return false; // duplicate MAC
      }
    }
    for (size_t i = 0; i < ClimateHistory::SLOT_COUNT; ++i) {
      const uint8_t count = data[pos++];
      const uint8_t head = data[pos++];
      int16_t samples[HumidityHistory::CAPACITY];
      for (size_t k = 0; k < HumidityHistory::CAPACITY; ++k) {
        samples[k] = static_cast<int16_t>(
            static_cast<uint16_t>(data[pos]) | (static_cast<uint16_t>(data[pos + 1]) << 8));
        pos += 2;
      }
      if (!s.history.slots[i].restore(count, head, samples)) {
        return false;
      }
      const uint8_t temp_count = data[pos++];
      const uint8_t temp_head = data[pos++];
      int16_t temp_samples[TemperatureHistory::CAPACITY];
      for (size_t k = 0; k < TemperatureHistory::CAPACITY; ++k) {
        temp_samples[k] = static_cast<int16_t>(
            static_cast<uint16_t>(data[pos]) | (static_cast<uint16_t>(data[pos + 1]) << 8));
        pos += 2;
      }
      if (!s.history.temperatures[i].restore(temp_count, temp_head, temp_samples)) {
        return false;
      }
    }
    s.history.last_sample_ms = getU32(data, pos);
    const uint8_t sampled = data[pos++];
    if (sampled > 1) {
      return false;
    }
    s.history.has_sampled = sampled == 1;
    const uint8_t noise_count = data[pos++];
    const uint8_t noise_head = data[pos++];
    uint8_t noise_samples[NoiseHistory::CAPACITY];
    for (size_t k = 0; k < NoiseHistory::CAPACITY; ++k) {
      noise_samples[k] = data[pos++];
    }
    if (!s.noise_history.restore(noise_count, noise_head, noise_samples)) {
      return false;
    }
    const uint8_t bat_count = data[pos++];
    const uint8_t bat_head = data[pos++];
    uint16_t bat_samples[BatteryHistory::CAPACITY];
    for (size_t k = 0; k < BatteryHistory::CAPACITY; ++k) {
      bat_samples[k] = static_cast<uint16_t>(
          static_cast<uint16_t>(data[pos]) | (static_cast<uint16_t>(data[pos + 1]) << 8));
      pos += 2;
    }
    uint32_t bat_stamps[BatteryHistory::CAPACITY];
    for (size_t k = 0; k < BatteryHistory::CAPACITY; ++k) {
      bat_stamps[k] = getU32(data, pos);
    }
    if (!s.battery_history.restore(bat_count, bat_head, bat_samples, bat_stamps)) {
      return false;
    }
    s.battery_history.last_sample_ms = getU32(data, pos);
    const uint8_t bat_sampled = data[pos++];
    if (bat_sampled > 1) {
      return false;
    }
    s.battery_history.has_sampled = bat_sampled == 1;
    s.last_log_ms = getU32(data, pos);
    const uint8_t logged = data[pos++];
    if (logged > 1) {
      return false;
    }
    s.has_logged = logged == 1;
    const uint8_t daily_flag = data[pos++];
    const uint16_t daily_year = static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8));
    pos += 2;
    const uint8_t daily_month = data[pos++];
    const uint8_t daily_day = data[pos++];
    if (daily_flag > 1) {
      return false;
    }
    if (daily_flag == 1) {
      if (daily_year < 2000 || daily_year > 2099 || daily_month < 1 || daily_month > 12 || daily_day < 1 ||
          !NtpTime::isValidDate(daily_year, daily_month, daily_day)) {
        return false;
      }
      s.daily.has_date = true;
      s.daily.year = daily_year;
      s.daily.month = daily_month;
      s.daily.day = daily_day;
    }
    const uint8_t audio = data[pos++];
    const uint8_t band = static_cast<uint8_t>((audio >> 6) & 3);
    if (band > static_cast<uint8_t>(NoiseBand::LOUD)) {
      return false;
    }
    s.muted = (audio & 1) != 0;
    s.alert_mask = static_cast<uint8_t>((audio >> 1) & ALERT_MASK_ALL);
    s.noise_band = static_cast<NoiseBand>(band);
    s.ntp.last_attempt_ms = getU32(data, pos);
    const uint8_t ntp_flags = data[pos++];
    s.ntp.failures = data[pos++];
    // A success needs an attempt and leaves no failures; the failure count has a ceiling.
    // Without an attempt, the stamp and the failure count stay zero.
    if ((ntp_flags & ~3u) != 0 || ((ntp_flags & 2) != 0 && (ntp_flags & 1) == 0) ||
        ((ntp_flags & 1) == 0 && (s.ntp.failures != 0 || s.ntp.last_attempt_ms != 0)) ||
        s.ntp.failures > NtpSchedule::MAX_FAILURES ||
        ((ntp_flags & 2) != 0 && s.ntp.failures != 0)) {
      return false;
    }
    s.ntp.has_attempted = (ntp_flags & 1) != 0;
    s.ntp.last_ok = (ntp_flags & 2) != 0;
    const uint8_t drift_flags = data[pos++];
    s.rtc_drift.last_sync_s = getU32(data, pos);
    s.rtc_drift.drift_s = static_cast<int32_t>(getU32(data, pos));
    s.rtc_drift.rate_ppm = static_cast<int32_t>(getU32(data, pos));
    s.rtc_drift.has_sync = (drift_flags & 1) != 0;
    s.rtc_drift.has_drift = (drift_flags & 2) != 0;
    // A figure needs a sync; without a figure the values stay zero; both stay within their limits,
    // the rate has the sign of the drift, and the sync time lies in 2000..2099.
    if ((drift_flags & ~3u) != 0 || (s.rtc_drift.has_drift && !s.rtc_drift.has_sync) ||
        (!s.rtc_drift.has_sync && s.rtc_drift.last_sync_s != 0) ||
        (!s.rtc_drift.has_drift && (s.rtc_drift.drift_s != 0 || s.rtc_drift.rate_ppm != 0)) ||
        s.rtc_drift.drift_s > RtcDrift::MAX_DRIFT_S || s.rtc_drift.drift_s < -RtcDrift::MAX_DRIFT_S ||
        s.rtc_drift.rate_ppm > RtcDrift::MAX_PPM || s.rtc_drift.rate_ppm < -RtcDrift::MAX_PPM ||
        (s.rtc_drift.has_sync && (s.rtc_drift.last_sync_s < RtcDrift::EPOCH_2000 ||
                                  s.rtc_drift.last_sync_s >= RtcDrift::EPOCH_2100)) ||
        (s.rtc_drift.drift_s == 0 && s.rtc_drift.rate_ppm != 0) ||
        (s.rtc_drift.drift_s > 0 && s.rtc_drift.rate_ppm < 0) ||
        (s.rtc_drift.drift_s < 0 && s.rtc_drift.rate_ppm > 0)) {
      return false;
    }
    s.config.temp_offset_c = getFloat(data, pos);
    s.config.freeze_alert_limit_c = getFloat(data, pos);
    s.config.mold_alert_limit_rh = data[pos++];
    s.config.chime_volume_pct = data[pos++];
    s.config.quiet_hours_start_hour = data[pos++];
    s.config.quiet_hours_end_hour = data[pos++];
    s.config.mic_spl_offset_db = getFloat(data, pos);
    s.config.log_retention_months = data[pos++];
    s.config.noise_alert_limit_db = data[pos++];
    s.config.chime_alert_mask = data[pos++];
    s.config.ntp_sync_interval_h = data[pos++];
    s.config.rh_offset_pct = getFloat(data, pos);
    for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
      s.config.slot_temp_offset_c[i] = getFloat(data, pos);
    }
    for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
      s.config.slot_rh_offset_pct[i] = getFloat(data, pos);
    }
    memcpy(s.config.onboard_name, data + pos, DeviceConfig::NAME_MAX_CHARS + 1);
    pos += DeviceConfig::NAME_MAX_CHARS + 1;
    for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
      memcpy(s.config.slot_name[i], data + pos, DeviceConfig::NAME_MAX_CHARS + 1);
      pos += DeviceConfig::NAME_MAX_CHARS + 1;
    }
    const uint8_t config_flags = data[pos++];
    if ((config_flags & ~1u) != 0 || !s.config.valid()) {
      return false; // a corrupt record never reaches the alert limits
    }
    s.config_from_sd = (config_flags & 1) != 0;
    out = s;
    return true;
  }

  // A restored state is consistent when no timestamp lies in the future of the current clock.
  // A reset clock with intact RTC memory breaks this and forces a clean start with a scan. The
  // 32-bit clock wraps after 49.7 days; the same clean start then runs once, which is harmless.
  static bool timestampsConsistent(const PersistentState& state, uint32_t now_ms) {
    // The history, the SD log gate and the NTP schedule are exempt: they use wrap-safe subtraction, so a reset or
    // wrapped clock only triggers an early sample or record. The history keeps its 24 hours.
    if (state.last_scan_ms > now_ms) {
      return false;
    }
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      const SwitchBotReading& r = state.registry.slot(i);
      if (r.valid && r.updated_ms > now_ms) {
        return false;
      }
    }
    return true;
  }

  // Restores state for a wakeup. Returns false and leaves defaults when the cause keeps no
  // state or when the stored bytes fail validation.
  static bool restore(WakeupCause cause, const uint8_t* data, size_t len, PersistentState& out) {
    if (!stateSurvives(cause)) {
      out = PersistentState{};
      return false;
    }
    return deserialize(data, len, out);
  }

private:
  static void putU32(uint8_t* buf, size_t& pos, uint32_t v) {
    for (int i = 0; i < 4; ++i) {
      buf[pos++] = static_cast<uint8_t>(v >> (8 * i));
    }
  }

  static uint32_t getU32(const uint8_t* buf, size_t& pos) {
    uint32_t v = 0;
    for (int i = 0; i < 4; ++i) {
      v |= static_cast<uint32_t>(buf[pos++]) << (8 * i);
    }
    return v;
  }

  static void putFloat(uint8_t* buf, size_t& pos, float f) {
    uint32_t bits;
    memcpy(&bits, &f, sizeof(bits));
    putU32(buf, pos, bits);
  }

  static float getFloat(const uint8_t* buf, size_t& pos) {
    const uint32_t bits = getU32(buf, pos);
    float f;
    memcpy(&f, &bits, sizeof(f));
    return f;
  }
};

// State machine for one wake cycle: BOOT -> RESTORE -> [SCAN] -> RENDER -> SAVE -> SLEEP.
class PowerCycle {
public:
  void begin(WakeupCause cause) {
    cause_ = cause;
    phase_ = PowerPhase::RESTORE;
  }

  PowerPhase phase() const {
    return phase_;
  }

  WakeupCause cause() const {
    return cause_;
  }

  // Moves to the next phase and returns it. SLEEP is final.
  PowerPhase advance() {
    switch (phase_) {
      case PowerPhase::BOOT: phase_ = PowerPhase::RESTORE; break;
      case PowerPhase::RESTORE:
        phase_ = PowerManager::needsScan(cause_) ? PowerPhase::SCAN : PowerPhase::RENDER;
        break;
      case PowerPhase::SCAN: phase_ = PowerPhase::RENDER; break;
      case PowerPhase::RENDER: phase_ = PowerPhase::SAVE; break;
      case PowerPhase::SAVE: phase_ = PowerPhase::SLEEP; break;
      case PowerPhase::SLEEP: break;
    }
    return phase_;
  }

private:
  WakeupCause cause_ = WakeupCause::POWER_ON;
  PowerPhase phase_ = PowerPhase::BOOT;
};

#if defined(ARDUINO) || defined(ESP_PLATFORM)

static_assert(ESP_SLEEP_WAKEUP_UNDEFINED == 0, "wakeup cause mapping");
static_assert(ESP_SLEEP_WAKEUP_EXT0 == 2, "wakeup cause mapping");
static_assert(ESP_SLEEP_WAKEUP_EXT1 == 3, "wakeup cause mapping");
static_assert(ESP_SLEEP_WAKEUP_TIMER == 4, "wakeup cause mapping");

// Survives deep sleep. A power-on clears it; a reset keeps it, so restore() also checks the cause.
RTC_DATA_ATTR static uint8_t g_rtc_state_blob[PowerManager::SERIALIZED_SIZE];

class PowerPlatform {
public:
  static constexpr uint8_t kHeldDisplayPins[3] = {PIN_RLCD_CS, PIN_RLCD_DC, PIN_RLCD_RST};

  static WakeupCause wakeupCause() {
    return PowerManager::decodeWakeupCause(static_cast<int>(esp_sleep_get_wakeup_cause()));
  }

  // Milliseconds of the system time. The RTC timer keeps it running through deep sleep, unlike
  // millis(). It starts at 0 on power-on; a reset may or may not keep it.
  static uint64_t clockMs64() {
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    return static_cast<uint64_t>(tv.tv_sec) * 1000ULL + tv.tv_usec / 1000;
  }

  // 32-bit view for timestamps in the persisted state. Wraps after 49.7 days.
  static uint32_t clockMs() {
    return static_cast<uint32_t>(clockMs64());
  }

  static bool loadState(WakeupCause cause, PersistentState& out) {
    return PowerManager::restore(cause, g_rtc_state_blob, sizeof(g_rtc_state_blob), out);
  }

  static bool saveState(const PersistentState& state) {
    if (PowerManager::serialize(state, g_rtc_state_blob, sizeof(g_rtc_state_blob))) {
      return true;
    }
    memset(g_rtc_state_blob, 0, sizeof(g_rtc_state_blob)); // never leave stale state behind
    return false;
  }

  // Returns the KEY pin from RTC control and frees the held display pins. Call it first in
  // setup(), before pinMode() and before the panel driver starts.
  static void releasePinsAfterSleep() {
    // The amplifier pin stayed held low; the audio driver sets its level when it needs it.
    gpio_hold_dis(static_cast<gpio_num_t>(PIN_PA_CTRL));
    rtc_gpio_deinit(static_cast<gpio_num_t>(PIN_BTN_KEY));
    for (const uint8_t pin : kHeldDisplayPins) {
      // Drive the idle level (high) before the release, so the pad never floats.
      const gpio_num_t gpio = static_cast<gpio_num_t>(pin);
      gpio_set_direction(gpio, GPIO_MODE_OUTPUT);
      gpio_set_level(gpio, 1);
      gpio_hold_dis(gpio);
    }
    gpio_deep_sleep_hold_dis();
  }

  // Does not return.
  static void enterDeepSleep(const WakeupPlan& plan) {
    if (esp_sleep_enable_timer_wakeup(plan.timer_us) != ESP_OK) {
      esp_restart(); // without a timer the board would sleep until a KEY press
    }
    // Hold CS, DC, and RST at their last level so a floating pin cannot disturb the panel.
    for (const uint8_t pin : kHeldDisplayPins) {
      gpio_hold_en(static_cast<gpio_num_t>(pin));
    }
    // Hold the speaker amplifier off so a floating pad cannot drain the battery in deep sleep.
    const gpio_num_t pa = static_cast<gpio_num_t>(PIN_PA_CTRL);
    gpio_set_direction(pa, GPIO_MODE_OUTPUT);
    gpio_set_level(pa, 0);
    gpio_hold_en(pa);
    gpio_deep_sleep_hold_en();
    if (plan.enable_key_wakeup) {
      const gpio_num_t key = static_cast<gpio_num_t>(PIN_BTN_KEY);
      rtc_gpio_init(key);
      rtc_gpio_set_direction(key, RTC_GPIO_MODE_INPUT_ONLY);
      rtc_gpio_pulldown_dis(key);
      rtc_gpio_pullup_en(key);
      esp_sleep_enable_ext0_wakeup(key, 0); // KEY is active low
    }
    esp_deep_sleep_start();
  }
};

#endif

} // namespace rlcd_panel
