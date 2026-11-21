#pragma once

#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

namespace rlcd_panel {

// Settings that the user can change in an optional /sdcard/config.ini. Every field has a default
// and a safe range. The struct has no Arduino dependency, so the host tests build it.
struct DeviceConfig {
  static constexpr float DEFAULT_TEMP_OFFSET_C = -1.9f;
  static constexpr float MIN_TEMP_OFFSET_C = -10.0f;
  static constexpr float MAX_TEMP_OFFSET_C = 10.0f;
  static constexpr float DEFAULT_USB_TEMP_OFFSET_C = 0.0f;
  static constexpr float MIN_USB_TEMP_OFFSET_C = -5.0f;
  static constexpr float MAX_USB_TEMP_OFFSET_C = 0.0f;
  static constexpr float DEFAULT_RH_OFFSET_PCT = 0.0f;
  static constexpr float MIN_RH_OFFSET_PCT = -10.0f;
  static constexpr float MAX_RH_OFFSET_PCT = 10.0f;
  static constexpr float DEFAULT_RH_GAIN = 1.0f; // humidity = raw * gain + offset, 0.002 steps around 1.00
  static constexpr float MIN_RH_GAIN = 0.90f;
  static constexpr float MAX_RH_GAIN = 1.10f;
  static constexpr float RH_GAIN_STEP = 0.002f;
  static constexpr int RH_GAIN_MAX_STEPS = 50; // (1.10 - 1.00) / 0.002
  static constexpr size_t SLOT_COUNT = 10; // SwitchBot and BTHome registry slots
  static constexpr size_t OFFSET_SLOT_COUNT = 10; // slots 1 to 10 have offset keys
  static constexpr float DEFAULT_SLOT_TEMP_OFFSET_C = 0.0f;
  static constexpr float MIN_SLOT_TEMP_OFFSET_C = -5.0f;
  static constexpr float MAX_SLOT_TEMP_OFFSET_C = 5.0f;
  static constexpr float DEFAULT_SLOT_RH_OFFSET_PCT = 0.0f;
  static constexpr float MIN_SLOT_RH_OFFSET_PCT = -10.0f;
  static constexpr float MAX_SLOT_RH_OFFSET_PCT = 10.0f;
  static constexpr float DEFAULT_FREEZE_ALERT_LIMIT_C = 0.0f;
  static constexpr float MIN_FREEZE_ALERT_LIMIT_C = -20.0f;
  static constexpr float MAX_FREEZE_ALERT_LIMIT_C = 15.0f;
  static constexpr uint8_t DEFAULT_MOLD_ALERT_LIMIT_RH = 70;
  static constexpr uint8_t MIN_MOLD_ALERT_LIMIT_RH = 40;
  static constexpr uint8_t MAX_MOLD_ALERT_LIMIT_RH = 95;
  static constexpr uint8_t DEFAULT_CHIME_VOLUME_PCT = 30;
  static constexpr uint8_t MAX_CHIME_VOLUME_PCT = 100;
  static constexpr uint8_t DEFAULT_QUIET_HOURS_START_HOUR = 22;
  static constexpr uint8_t DEFAULT_QUIET_HOURS_END_HOUR = 7;
  static constexpr uint8_t MAX_HOUR = 23;
  static constexpr float DEFAULT_MIC_SPL_OFFSET_DB = 0.0f;
  static constexpr float MIN_MIC_SPL_OFFSET_DB = -20.0f;
  static constexpr float MAX_MIC_SPL_OFFSET_DB = 20.0f;
  static constexpr uint8_t DEFAULT_LOG_RETENTION_MONTHS = 0; // 0 keeps all log files
  static constexpr uint8_t MAX_LOG_RETENTION_MONTHS = 120;
  static constexpr uint8_t DEFAULT_NOISE_ALERT_LIMIT_DB = 0; // 0 switches the noise alert off
  static constexpr uint8_t MIN_NOISE_ALERT_LIMIT_DB = 40;
  static constexpr uint8_t MAX_NOISE_ALERT_LIMIT_DB = 100;
  static constexpr uint8_t DEFAULT_CHIME_ALERT_MASK = 255; // all eight alerts may sound
  static constexpr uint8_t MAX_CHIME_ALERT_MASK = 255;
  static constexpr uint8_t DEFAULT_SENSOR_BATTERY_LIMIT_PCT = 10; // 0 switches the sensor battery warning off
  static constexpr uint8_t MAX_SENSOR_BATTERY_LIMIT_PCT = 50;
  static constexpr uint8_t DEFAULT_OUTDOOR_SLOT = 0; // 0 switches the ventilation advice off
  static constexpr uint8_t MAX_OUTDOOR_SLOT = 10;
  static constexpr uint8_t DEFAULT_DOOR_REF_SLOT = 0; // 0 switches the door ventilation comparison off
  static constexpr uint8_t MAX_DOOR_REF_SLOT = 10;
  static constexpr uint8_t DEFAULT_VENTILATE_LIMIT_RH = 60;
  static constexpr uint8_t MIN_VENTILATE_LIMIT_RH = 50;
  static constexpr uint8_t MAX_VENTILATE_LIMIT_RH = 80;
  static constexpr uint8_t DEFAULT_NTP_SYNC_INTERVAL_H = 24;
  static constexpr uint8_t MIN_NTP_SYNC_INTERVAL_H = 6;
  static constexpr uint8_t MAX_NTP_SYNC_INTERVAL_H = 168;
  static constexpr uint16_t DEFAULT_WAKE_INTERVAL_S = 60; // equals PowerConfig::DEFAULT_SLEEP_INTERVAL_S
  static constexpr uint16_t MIN_WAKE_INTERVAL_S = 30;
  static constexpr uint16_t MAX_WAKE_INTERVAL_S = 900;
  static constexpr uint16_t DEFAULT_SENSOR_STALE_MIN = 30; // equals SENSOR_STALE_TIMEOUT_MS
  static constexpr uint16_t MIN_SENSOR_STALE_MIN = 5;
  static constexpr uint16_t MAX_SENSOR_STALE_MIN = 240;
  static constexpr int16_t DEFAULT_BATTERY_OFFSET_MV = 0; // added to the cell voltage before every battery limit
  static constexpr int16_t MIN_BATTERY_OFFSET_MV = -300;
  static constexpr int16_t MAX_BATTERY_OFFSET_MV = 300;
  static constexpr uint16_t DEFAULT_NIGHT_WAKE_INTERVAL_S = 0; // 0 keeps wake_interval_s during the quiet hours
  static constexpr uint16_t MIN_NIGHT_WAKE_INTERVAL_S = 60;
  static constexpr uint16_t MAX_NIGHT_WAKE_INTERVAL_S = 1800;
  static constexpr uint8_t DEFAULT_SLOT_RELEASE_H = 0; // 0 keeps a silent slot for ever
  static constexpr uint8_t MAX_SLOT_RELEASE_H = 168;
  static constexpr uint8_t DEFAULT_DISPLAY_INVERT = 0; // 1 shows light text on a dark background
  static constexpr uint8_t MAX_DISPLAY_INVERT = 1;
  static constexpr char DEFAULT_TEMP_UNIT = 'C'; // display unit of the temperature: 'C' or 'F'
  static constexpr uint8_t DEFAULT_CHIME_REPEAT_H = 0; // 0 plays no reminder chime
  static constexpr uint8_t MAX_CHIME_REPEAT_H = 24;
  static constexpr uint8_t DEFAULT_HEAT_ALERT_LIMIT_C = 0; // 0 switches the heat alert off
  static constexpr uint8_t MIN_HEAT_ALERT_LIMIT_C = 27;
  static constexpr uint8_t MAX_HEAT_ALERT_LIMIT_C = 45;
  static constexpr uint8_t DEFAULT_DRY_ALERT_LIMIT_RH = 0; // 0 switches the dry air badge off
  static constexpr uint8_t MIN_DRY_ALERT_LIMIT_RH = 20;
  static constexpr uint8_t MAX_DRY_ALERT_LIMIT_RH = 45;
  static constexpr float DEFAULT_WINDOW_DROP_C = 0.0f; // 0 switches the open window badge off
  static constexpr float MIN_WINDOW_DROP_C = 1.0f;
  static constexpr float MAX_WINDOW_DROP_C = 5.0f;
  static constexpr uint8_t DEFAULT_WALL_FRSI_PCT = 0; // 0 switches the wall humidity text off
  static constexpr uint8_t MIN_WALL_FRSI_PCT = 50;
  static constexpr uint8_t MAX_WALL_FRSI_PCT = 95;
  static constexpr uint8_t DEFAULT_BLE_BROADCAST_ENABLED = 1; // 1 advertises BLE telemetry by default
  static constexpr uint8_t MAX_BLE_BROADCAST_ENABLED = 1;
  static constexpr uint8_t DEFAULT_SLOT_AUTO_ASSIGN = 1; // 1 gives a new MAC the first free slot
  static constexpr uint8_t MAX_SLOT_AUTO_ASSIGN = 1;
  static constexpr int8_t DEFAULT_AUTO_ASSIGN_MIN_RSSI = 0; // 0 = off
  static constexpr int8_t MIN_AUTO_ASSIGN_MIN_RSSI = -100;
  static constexpr int8_t MAX_AUTO_ASSIGN_MIN_RSSI = -40;
  static constexpr uint16_t DEFAULT_SLOT_ENABLED_MASK = 0x03FF; // all 10 slots enabled (bits 0..9)
  static constexpr uint16_t SLOT_ENABLED_ALL = 0x03FF;
  static constexpr uint8_t DEFAULT_BLE_CONSOLE = 0; // 0 starts no BLE console service
  static constexpr uint8_t MAX_BLE_CONSOLE = 2;
  static constexpr uint16_t DEFAULT_CLIMATE_ALERT_TILES = 0x07FF; // all 11 tiles feed the freezer and mold alerts
  static constexpr uint16_t MAX_CLIMATE_ALERT_TILES = 0x07FF;
  static constexpr uint32_t SCAN_DURATION_S = 4; // scan time that adds to the wake interval

  static constexpr size_t MAC_BYTES = 6;
  static constexpr size_t NAME_MAX_CHARS = 12; // longest sensor name; a name buffer adds the terminator

  // Display names of the onboard sensor and of the registry slots (keys onboard_name and
  // slot1_name to slot10_name). Empty means the default title. A slot name belongs to the fixed MAC
  // of the same slot (slotN_mac): nameForMac() finds it by the MAC of the sensor, not by the slot index.
  // A name holds 1 to 12 characters of A-Z, 0-9, space and '-': all of them have a glyph in the 5x7 font.
  char onboard_name[NAME_MAX_CHARS + 1] = {0};
  char slot_name[SLOT_COUNT][NAME_MAX_CHARS + 1] = {};

  // Fixed MAC address of each registry slot (keys slot1_mac to slot3_mac, format AA:BB:CC:DD:EE:FF).
  // All zero means no fixed MAC: the slot keeps the first-come rule. A fixed MAC is never all zero
  // and never appears in two slots.
  uint8_t slot_mac[SLOT_COUNT][MAC_BYTES] = {};

  float temp_offset_c = DEFAULT_TEMP_OFFSET_C;
  // Added to temp_offset_c on USB power only (charger and awake CPU heat the PCB); 0.1 C steps, -5.0..0.0.
  float usb_temp_offset_c = DEFAULT_USB_TEMP_OFFSET_C;
  float rh_offset_pct = DEFAULT_RH_OFFSET_PCT; // added to the onboard humidity, result clamped to 0..100 %RH
  // Offsets of the registry slots 1 to 10 (keys slot1_* to slot10_*), added to the decoded value of the
  // slot by SwitchBotRegistry::calibrated(). The humidity result is clamped to 0..100 %RH.
  float slot_temp_offset_c[SLOT_COUNT] = {DEFAULT_SLOT_TEMP_OFFSET_C, DEFAULT_SLOT_TEMP_OFFSET_C,
                                          DEFAULT_SLOT_TEMP_OFFSET_C, DEFAULT_SLOT_TEMP_OFFSET_C,
                                          DEFAULT_SLOT_TEMP_OFFSET_C, DEFAULT_SLOT_TEMP_OFFSET_C,
                                          DEFAULT_SLOT_TEMP_OFFSET_C, DEFAULT_SLOT_TEMP_OFFSET_C,
                                          DEFAULT_SLOT_TEMP_OFFSET_C, DEFAULT_SLOT_TEMP_OFFSET_C};
  float slot_rh_offset_pct[SLOT_COUNT] = {DEFAULT_SLOT_RH_OFFSET_PCT, DEFAULT_SLOT_RH_OFFSET_PCT,
                                          DEFAULT_SLOT_RH_OFFSET_PCT, DEFAULT_SLOT_RH_OFFSET_PCT,
                                          DEFAULT_SLOT_RH_OFFSET_PCT, DEFAULT_SLOT_RH_OFFSET_PCT,
                                          DEFAULT_SLOT_RH_OFFSET_PCT, DEFAULT_SLOT_RH_OFFSET_PCT,
                                          DEFAULT_SLOT_RH_OFFSET_PCT, DEFAULT_SLOT_RH_OFFSET_PCT};
  // Gain of the humidity (keys rh_gain and slot1_rh_gain to slot10_rh_gain): humidity = raw * gain + offset,
  // clamped to 0..100 %RH. Always a multiple of RH_GAIN_STEP around 1.00 (the RTC state keeps one byte).
  float rh_gain = DEFAULT_RH_GAIN;
  float slot_rh_gain[SLOT_COUNT] = {DEFAULT_RH_GAIN, DEFAULT_RH_GAIN, DEFAULT_RH_GAIN, DEFAULT_RH_GAIN,
                                    DEFAULT_RH_GAIN, DEFAULT_RH_GAIN, DEFAULT_RH_GAIN, DEFAULT_RH_GAIN,
                                    DEFAULT_RH_GAIN, DEFAULT_RH_GAIN};
  float freeze_alert_limit_c = DEFAULT_FREEZE_ALERT_LIMIT_C;
  uint8_t mold_alert_limit_rh = DEFAULT_MOLD_ALERT_LIMIT_RH;
  uint8_t chime_volume_pct = DEFAULT_CHIME_VOLUME_PCT;
  uint8_t quiet_hours_start_hour = DEFAULT_QUIET_HOURS_START_HOUR;
  uint8_t quiet_hours_end_hour = DEFAULT_QUIET_HOURS_END_HOUR;
  float mic_spl_offset_db = DEFAULT_MIC_SPL_OFFSET_DB; // added to the microphone calibration offset
  uint8_t log_retention_months = DEFAULT_LOG_RETENTION_MONTHS; // log files older than this are deleted
  uint8_t noise_alert_limit_db = DEFAULT_NOISE_ALERT_LIMIT_DB; // 0 = off, else 40..100 dB
  uint8_t chime_alert_mask = DEFAULT_CHIME_ALERT_MASK; // bit 0 freezer, 1 humidity, 2 noise, 3 sensor lost, 4 charge full, 5 ventilate, 6 sensor battery, 7 heat
  uint8_t outdoor_slot = DEFAULT_OUTDOOR_SLOT; // registry slot (1..5) of the outdoor sensor for the ventilation advice, 0 = off
  uint8_t door_ref_slot = DEFAULT_DOOR_REF_SLOT; // reference slot (0=off, 2..5) for door-based ventilation of slot 1
  uint8_t ventilate_limit_rh = DEFAULT_VENTILATE_LIMIT_RH; // a tile above this %RH shows the ventilation badge, 50..80
  uint8_t sensor_battery_limit_pct = DEFAULT_SENSOR_BATTERY_LIMIT_PCT; // an external sensor at or below this % shows BAT LOW, 0 = off, else 1..50
  uint8_t ntp_sync_interval_h = DEFAULT_NTP_SYNC_INTERVAL_H; // hours between two successful NTP syncs, 6..168
  uint16_t wake_interval_s = DEFAULT_WAKE_INTERVAL_S; // seconds from one scan start to the next on battery power, 30..900
  uint16_t sensor_stale_min = DEFAULT_SENSOR_STALE_MIN; // minutes without a packet until a sensor is stale, 5..240
  uint8_t display_invert = DEFAULT_DISPLAY_INVERT; // 1 inverts the framebuffer before each flush, 0..1
  int16_t battery_offset_mv = DEFAULT_BATTERY_OFFSET_MV; // corrects divider and ADC tolerance, -300..300 mV
  uint8_t slot_release_h = DEFAULT_SLOT_RELEASE_H; // hours without a packet until a slot without a fixed MAC is released, 0 = off, else 1..168
  uint16_t night_wake_interval_s = DEFAULT_NIGHT_WAKE_INTERVAL_S; // seconds from one scan start to the next on battery power in the quiet hours, 0 = off, else 60..1800
  char temp_unit = DEFAULT_TEMP_UNIT; // 'C' or 'F': only the screen and the console command STATUS use it; the card files, limits and alerts stay in Celsius
  uint8_t chime_repeat_h = DEFAULT_CHIME_REPEAT_H; // hours until the chime of an active alert sounds again, 0 = off, else 1..24
  uint8_t heat_alert_limit_c = DEFAULT_HEAT_ALERT_LIMIT_C; // feels-like limit of the heat alert in C, 0 = off, else 27..45
  uint8_t dry_alert_limit_rh = DEFAULT_DRY_ALERT_LIMIT_RH; // a tile at or below this %RH shows the dry air badge, 0 = off, else 20..45
  float window_drop_c = DEFAULT_WINDOW_DROP_C; // a tile that falls by this many C within 15 minutes shows the "W" badge, 0 = off, else 1.0..5.0, 0.1 C steps
  uint8_t wall_frsi_pct = DEFAULT_WALL_FRSI_PCT; // temperature factor of the outer wall for the WALL text in percent, 0 = off, else 50..95
  uint8_t ble_broadcast_enabled = DEFAULT_BLE_BROADCAST_ENABLED; // 1 = advertise the climate tiles after each scan (ble_telemetry.h), 0 = off
  int8_t auto_assign_min_rssi = DEFAULT_AUTO_ASSIGN_MIN_RSSI; // a new MAC with a weaker RSSI in dBm gets no free slot, 0 = off, else -100..-40
  uint8_t slot_auto_assign = DEFAULT_SLOT_AUTO_ASSIGN; // 1 = a new MAC takes the first free slot, 0 = only a slot with a fixed MAC (slotN_mac) takes a reading
  uint16_t slot_enabled_mask = DEFAULT_SLOT_ENABLED_MASK; // bit i (0..9) = 1 enables slot i + 1, 0 disables and hides it
  uint8_t ble_console = DEFAULT_BLE_CONSOLE; // BLE console on USB power: 0 off, 1 read commands, 2 also SET (serial_console.h)
  uint16_t climate_alert_tiles = DEFAULT_CLIMATE_ALERT_TILES; // bit 0 onboard, bits 1..10 slots 1..10: tiles of the freezer and mold alerts, 0..2047

  bool isSlotEnabled(size_t slot) const {
    return slot < SLOT_COUNT && ((slot_enabled_mask & (1u << slot)) != 0);
  }

  void setSlotEnabled(size_t slot, bool enabled) {
    if (slot < SLOT_COUNT) {
      if (enabled) {
        slot_enabled_mask = static_cast<uint16_t>(slot_enabled_mask | (1u << slot));
      } else {
        slot_enabled_mask = static_cast<uint16_t>(slot_enabled_mask & ~(1u << slot));
      }
    }
  }

  // Onboard temperature offset: temp_offset_c, plus usb_temp_offset_c on USB power. Battery power ignores the USB key.
  float onboardTempOffsetC(bool usb_powered) const {
    return usb_powered ? temp_offset_c + usb_temp_offset_c : temp_offset_c;
  }

  // Release time in ms of a silent slot without a fixed MAC (0 = never). 168 h fit into 32 bits.
  uint32_t slotReleaseMs() const {
    return static_cast<uint32_t>(slot_release_h) * 3600000UL;
  }

  // Stale timeout in ms for every SwitchBotReading::isStale call. A value below one wake interval plus
  // the scan duration would mark each sensor stale between two scans, so the sum is the lower bound.
  uint32_t staleTimeoutMs() const {
    const uint32_t configured = static_cast<uint32_t>(sensor_stale_min) * 60000UL;
    // The night interval is the longest interval of the board, so the floor uses the larger one.
    const uint32_t longest_s = night_wake_interval_s > wake_interval_s ? night_wake_interval_s : wake_interval_s;
    const uint32_t floor_ms = (longest_s + SCAN_DURATION_S) * 1000UL;
    return configured < floor_ms ? floor_ms : configured;
  }

  // Window of the open window latch in ms: 15 minutes, or one wake interval plus the scan time when the longest
  // interval of the board is longer, so two readings always fall into one window. Equals WindowLatch::DROP_WINDOW_MS.
  // A night_wake_interval_s above 15 minutes widens the window all day, because the RTC copy does not know the next interval.
  uint32_t windowDropMs() const {
    const uint32_t longest_s = night_wake_interval_s > wake_interval_s ? night_wake_interval_s : wake_interval_s;
    const uint32_t interval_ms = (longest_s + SCAN_DURATION_S) * 1000UL;
    const uint32_t base_ms = 15UL * 60UL * 1000UL;
    return interval_ms > base_ms ? interval_ms : base_ms;
  }

  static bool inRange(float v, float lo, float hi) {
    return v >= lo && v <= hi; // false for NaN
  }

  // True for 1 to 12 characters of A-Z, 0-9, space and '-'.
  static bool validName(const char* s, size_t n) {
    if (s == nullptr || n == 0 || n > NAME_MAX_CHARS) {
      return false;
    }
    for (size_t i = 0; i < n; ++i) {
      const char c = s[i];
      if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == ' ' || c == '-')) {
        return false;
      }
    }
    return true;
  }

  // A stored name is empty or valid, and ends with a terminator inside the buffer.
  static bool storedNameOk(const char (&name)[NAME_MAX_CHARS + 1]) {
    size_t n = 0;
    while (n < sizeof(name) && name[n] != '\0') {
      ++n;
    }
    return n < sizeof(name) && (n == 0 || validName(name, n));
  }

  static bool macIsZero(const uint8_t* mac) {
    for (size_t i = 0; i < MAC_BYTES; ++i) {
      if (mac[i] != 0) {
        return false;
      }
    }
    return true;
  }

  bool hasSlotMac(size_t slot) const {
    return slot < SLOT_COUNT && !macIsZero(slot_mac[slot]);
  }

  // Configured display name of the sensor with `mac`: the name of the slot whose fixed MAC equals `mac`.
  // nullptr for a missing or zero MAC, an unknown MAC and a fixed MAC without a name.
  const char* nameForMac(const uint8_t* mac) const {
    if (mac == nullptr || macIsZero(mac)) {
      return nullptr;
    }
    for (size_t i = 0; i < SLOT_COUNT; ++i) {
      if (hasSlotMac(i) && memcmp(slot_mac[i], mac, MAC_BYTES) == 0) {
        return slot_name[i][0] != '\0' ? slot_name[i] : nullptr;
      }
    }
    return nullptr;
  }

  // True when `mac` is a fixed MAC of a slot other than `except` (pass SLOT_COUNT for none).
  bool macFixedElsewhere(const uint8_t* mac, size_t except) const {
    for (size_t i = 0; i < SLOT_COUNT; ++i) {
      if (i != except && hasSlotMac(i) && memcmp(slot_mac[i], mac, MAC_BYTES) == 0) {
        return true;
      }
    }
    return false;
  }

  // Gain from its step count (-50..50 steps of 0.002 around 1.00) and the reverse; both clamp to that range.
  static float gainFromSteps(int steps) {
    steps = steps < -RH_GAIN_MAX_STEPS ? -RH_GAIN_MAX_STEPS : (steps > RH_GAIN_MAX_STEPS ? RH_GAIN_MAX_STEPS : steps);
    return 1.0f + static_cast<float>(steps) * RH_GAIN_STEP;
  }
  static int gainToSteps(float gain) {
    const long steps = lroundf((gain - 1.0f) / RH_GAIN_STEP);
    return static_cast<int>(steps < -RH_GAIN_MAX_STEPS ? -RH_GAIN_MAX_STEPS : (steps > RH_GAIN_MAX_STEPS ? RH_GAIN_MAX_STEPS : steps));
  }

  // True when every field lies inside its range. Used for the RTC record check.
  bool valid() const {
    for (size_t i = 0; i < SLOT_COUNT; ++i) {
      if (hasSlotMac(i) && macFixedElsewhere(slot_mac[i], i)) {
        return false;
      }
    }
    if (!storedNameOk(onboard_name)) {
      return false;
    }
    for (size_t i = 0; i < SLOT_COUNT; ++i) {
      if (!storedNameOk(slot_name[i])) {
        return false;
      }
      if (!inRange(slot_rh_gain[i], MIN_RH_GAIN, MAX_RH_GAIN) ||
          !inRange(slot_temp_offset_c[i], MIN_SLOT_TEMP_OFFSET_C, MAX_SLOT_TEMP_OFFSET_C) ||
          !inRange(slot_rh_offset_pct[i], MIN_SLOT_RH_OFFSET_PCT, MAX_SLOT_RH_OFFSET_PCT)) {
        return false;
      }
    }
    return inRange(temp_offset_c, MIN_TEMP_OFFSET_C, MAX_TEMP_OFFSET_C) &&
           (window_drop_c == 0.0f || inRange(window_drop_c, MIN_WINDOW_DROP_C, MAX_WINDOW_DROP_C)) &&
           inRange(usb_temp_offset_c, MIN_USB_TEMP_OFFSET_C, MAX_USB_TEMP_OFFSET_C) &&
           inRange(rh_offset_pct, MIN_RH_OFFSET_PCT, MAX_RH_OFFSET_PCT) &&
           inRange(rh_gain, MIN_RH_GAIN, MAX_RH_GAIN) &&
           inRange(freeze_alert_limit_c, MIN_FREEZE_ALERT_LIMIT_C, MAX_FREEZE_ALERT_LIMIT_C) &&
           mold_alert_limit_rh >= MIN_MOLD_ALERT_LIMIT_RH && mold_alert_limit_rh <= MAX_MOLD_ALERT_LIMIT_RH &&
           chime_volume_pct <= MAX_CHIME_VOLUME_PCT && quiet_hours_start_hour <= MAX_HOUR &&
           quiet_hours_end_hour <= MAX_HOUR &&
           inRange(mic_spl_offset_db, MIN_MIC_SPL_OFFSET_DB, MAX_MIC_SPL_OFFSET_DB) &&
           log_retention_months <= MAX_LOG_RETENTION_MONTHS && chime_alert_mask <= MAX_CHIME_ALERT_MASK &&
           outdoor_slot <= MAX_OUTDOOR_SLOT && door_ref_slot <= MAX_DOOR_REF_SLOT &&
           ventilate_limit_rh >= MIN_VENTILATE_LIMIT_RH && ventilate_limit_rh <= MAX_VENTILATE_LIMIT_RH &&
           sensor_battery_limit_pct <= MAX_SENSOR_BATTERY_LIMIT_PCT &&
           (noise_alert_limit_db == 0 ||
            (noise_alert_limit_db >= MIN_NOISE_ALERT_LIMIT_DB && noise_alert_limit_db <= MAX_NOISE_ALERT_LIMIT_DB)) &&
           ntp_sync_interval_h >= MIN_NTP_SYNC_INTERVAL_H && ntp_sync_interval_h <= MAX_NTP_SYNC_INTERVAL_H &&
           wake_interval_s >= MIN_WAKE_INTERVAL_S && wake_interval_s <= MAX_WAKE_INTERVAL_S &&
           sensor_stale_min >= MIN_SENSOR_STALE_MIN && sensor_stale_min <= MAX_SENSOR_STALE_MIN &&
           display_invert <= MAX_DISPLAY_INVERT && (temp_unit == 'C' || temp_unit == 'F') && climate_alert_tiles <= MAX_CLIMATE_ALERT_TILES &&
           battery_offset_mv >= MIN_BATTERY_OFFSET_MV && battery_offset_mv <= MAX_BATTERY_OFFSET_MV &&
           slot_release_h <= MAX_SLOT_RELEASE_H && chime_repeat_h <= MAX_CHIME_REPEAT_H &&
           (heat_alert_limit_c == 0 ||
            (heat_alert_limit_c >= MIN_HEAT_ALERT_LIMIT_C && heat_alert_limit_c <= MAX_HEAT_ALERT_LIMIT_C)) &&
           (dry_alert_limit_rh == 0 ||
            (dry_alert_limit_rh >= MIN_DRY_ALERT_LIMIT_RH && dry_alert_limit_rh <= MAX_DRY_ALERT_LIMIT_RH)) &&
           (wall_frsi_pct == 0 || (wall_frsi_pct >= MIN_WALL_FRSI_PCT && wall_frsi_pct <= MAX_WALL_FRSI_PCT)) &&
           ble_broadcast_enabled <= MAX_BLE_BROADCAST_ENABLED && ble_console <= MAX_BLE_CONSOLE &&
           (auto_assign_min_rssi == 0 ||
            (auto_assign_min_rssi >= MIN_AUTO_ASSIGN_MIN_RSSI && auto_assign_min_rssi <= MAX_AUTO_ASSIGN_MIN_RSSI)) &&
           slot_auto_assign <= MAX_SLOT_AUTO_ASSIGN && (slot_enabled_mask & ~SLOT_ENABLED_ALL) == 0 &&
           (night_wake_interval_s == 0 ||
            (night_wake_interval_s >= MIN_NIGHT_WAKE_INTERVAL_S && night_wake_interval_s <= MAX_NIGHT_WAKE_INTERVAL_S));
  }

  bool isDefault() const {
    const DeviceConfig d;
    if (strcmp(onboard_name, d.onboard_name) != 0) {
      return false;
    }
    for (size_t i = 0; i < SLOT_COUNT; ++i) {
      if (strcmp(slot_name[i], d.slot_name[i]) != 0) {
        return false;
      }
      if (slot_temp_offset_c[i] != d.slot_temp_offset_c[i] || slot_rh_offset_pct[i] != d.slot_rh_offset_pct[i] ||
          slot_rh_gain[i] != d.slot_rh_gain[i] || hasSlotMac(i)) {
        return false;
      }
    }
    return temp_offset_c == d.temp_offset_c && usb_temp_offset_c == d.usb_temp_offset_c && rh_offset_pct == d.rh_offset_pct && rh_gain == d.rh_gain && freeze_alert_limit_c == d.freeze_alert_limit_c &&
           mold_alert_limit_rh == d.mold_alert_limit_rh && chime_volume_pct == d.chime_volume_pct &&
           quiet_hours_start_hour == d.quiet_hours_start_hour &&
           quiet_hours_end_hour == d.quiet_hours_end_hour && mic_spl_offset_db == d.mic_spl_offset_db &&
           log_retention_months == d.log_retention_months && noise_alert_limit_db == d.noise_alert_limit_db &&
           chime_alert_mask == d.chime_alert_mask && outdoor_slot == d.outdoor_slot && door_ref_slot == d.door_ref_slot &&
           ventilate_limit_rh == d.ventilate_limit_rh && sensor_battery_limit_pct == d.sensor_battery_limit_pct &&
           ntp_sync_interval_h == d.ntp_sync_interval_h && wake_interval_s == d.wake_interval_s &&
           sensor_stale_min == d.sensor_stale_min && display_invert == d.display_invert &&
           climate_alert_tiles == d.climate_alert_tiles && battery_offset_mv == d.battery_offset_mv &&
           slot_release_h == d.slot_release_h && night_wake_interval_s == d.night_wake_interval_s &&
           temp_unit == d.temp_unit && chime_repeat_h == d.chime_repeat_h &&
           heat_alert_limit_c == d.heat_alert_limit_c && dry_alert_limit_rh == d.dry_alert_limit_rh && wall_frsi_pct == d.wall_frsi_pct && window_drop_c == d.window_drop_c && ble_broadcast_enabled == d.ble_broadcast_enabled &&
           ble_console == d.ble_console && slot_auto_assign == d.slot_auto_assign &&
           auto_assign_min_rssi == d.auto_assign_min_rssi &&
           slot_enabled_mask == d.slot_enabled_mask;
  }
};

// Rejected lines of the last parse: the total count (saturates at 255) and the 1-based line
// numbers of the first four. It lives in the RTC state and feeds the diagnostics view and the
// console command CONFIG.
struct ConfigRejects {
  static constexpr size_t MAX_LINES = 4;
  uint8_t count = 0;
  uint16_t lines[MAX_LINES] = {0, 0, 0, 0};

  // Number of valid entries in `lines`.
  size_t recorded() const {
    return count < MAX_LINES ? count : MAX_LINES;
  }
};

// Outcome of one parse. `file_ok` is false for a file above the size limit; the config then keeps
// all defaults. `keys_set` counts distinct keys with an accepted value. `rejected` counts lines
// that were malformed or out of range (it saturates at 255); the previous value of their key
// stays. `rejected_lines` holds the 1-based numbers of the first four of them. An unknown key is
// no error.
struct ConfigParseResult {
  bool file_ok = false;
  uint8_t keys_set = 0;
  uint8_t rejected = 0;
  uint16_t rejected_lines[ConfigRejects::MAX_LINES] = {0, 0, 0, 0};

  ConfigRejects rejects() const {
    ConfigRejects r;
    r.count = rejected;
    memcpy(r.lines, rejected_lines, sizeof(r.lines));
    return r;
  }
};

// Parser for the key=value text. Rules:
// - A line is "key=value". Spaces, tabs and CR around the key and the value are ignored.
// - A line that starts with '#' or ';' is a comment. An empty line is skipped.
// - An unknown key is ignored. The card never supplies Wi-Fi credentials: those keys are unknown.
// - A malformed value or a value outside the range of its key is rejected. The key keeps its
//   previous value (the default, or an earlier line of the file). The last valid line wins.
// - A file above MAX_FILE_SIZE is rejected as a whole.
// - Lines end with LF or CRLF. A lone CR is not a line end.
class DeviceConfigParser {
public:
  static constexpr size_t MAX_FILE_SIZE = 8192;
  static constexpr size_t KEY_COUNT = 96;
  // Every key that applyLine accepts. docs/config.example.ini lists each of them; a host test checks
  // that file against this table and checks that the parser knows each entry.
  static constexpr const char* KEYS[KEY_COUNT] = {
      "temp_offset_c", "rh_offset_pct", "freeze_alert_limit_c",
      "mold_alert_limit_rh", "chime_volume_pct", "quiet_hours_start_hour",
      "quiet_hours_end_hour", "mic_spl_offset_db", "log_retention_months",
      "noise_alert_limit_db", "chime_alert_mask", "ntp_sync_interval_h",
      "wake_interval_s", "sensor_stale_min", "display_invert",
      "climate_alert_tiles", "battery_offset_mv", "outdoor_slot",
      "ventilate_limit_rh", "sensor_battery_limit_pct", "slot1_temp_offset_c",
      "slot2_temp_offset_c", "slot3_temp_offset_c", "slot1_rh_offset_pct",
      "slot2_rh_offset_pct", "slot3_rh_offset_pct", "onboard_name",
      "slot1_name", "slot2_name", "slot3_name",
      "slot1_mac", "slot2_mac", "slot3_mac",
      "slot_release_h", "night_wake_interval_s", "temp_unit",
      "chime_repeat_h", "heat_alert_limit_c", "ble_broadcast_enabled",
      "ble_console", "slot4_name", "slot5_name",
      "slot4_mac", "slot5_mac", "slot4_temp_offset_c",
      "slot5_temp_offset_c", "slot4_rh_offset_pct", "slot5_rh_offset_pct",
      "usb_temp_offset_c", "dry_alert_limit_rh", "rh_gain",
      "slot1_rh_gain", "slot2_rh_gain", "slot3_rh_gain",
      "slot4_rh_gain", "slot5_rh_gain", "door_ref_slot",
      "slot6_name", "slot7_name", "slot8_name", "slot9_name", "slot10_name",
      "slot6_mac", "slot7_mac", "slot8_mac", "slot9_mac", "slot10_mac",
      "slot6_temp_offset_c", "slot7_temp_offset_c", "slot8_temp_offset_c",
      "slot9_temp_offset_c", "slot10_temp_offset_c",
      "slot6_rh_offset_pct", "slot7_rh_offset_pct", "slot8_rh_offset_pct",
      "slot9_rh_offset_pct", "slot10_rh_offset_pct",
      "slot6_rh_gain", "slot7_rh_gain", "slot8_rh_gain", "slot9_rh_gain", "slot10_rh_gain",
      "wall_frsi_pct", "slot_auto_assign",
      "slot1_enabled", "slot2_enabled", "slot3_enabled", "slot4_enabled", "slot5_enabled",
      "slot6_enabled", "slot7_enabled", "slot8_enabled", "slot9_enabled", "slot10_enabled",
      "auto_assign_min_rssi", "window_drop_c",
  };
  static constexpr size_t MAX_NUMBER_CHARS = 15;

  enum class SettingCheck : uint8_t { OK = 0, BAD_KEY, BAD_VALUE };

  // Checks one "key=value" pair of the console command SET with the rules of parse(): the key must
  // be an entry of KEYS (exact match), and the value must give one accepted line and no rejected line.
  static SettingCheck checkSetting(const char* key, size_t key_len, const char* val, size_t val_len) {
    bool known = false;
    for (size_t i = 0; key != nullptr && i < KEY_COUNT; ++i) {
      known = known || keyIs(key, key_len, KEYS[i]);
    }
    if (!known) {
      return SettingCheck::BAD_KEY;
    }
    char line[128];
    if (val == nullptr || key_len + val_len + 2 > sizeof(line)) {
      return SettingCheck::BAD_VALUE;
    }
    for (size_t i = 0; i < val_len; ++i) {
      if (static_cast<unsigned char>(val[i]) < 0x20) {
        return SettingCheck::BAD_VALUE; // a line break would add a second line
      }
    }
    memcpy(line, key, key_len);
    line[key_len] = '=';
    memcpy(line + key_len + 1, val, val_len);
    line[key_len + 1 + val_len] = '\n';
    DeviceConfig scratch;
    const ConfigParseResult r = parse(line, key_len + val_len + 2, scratch);
    return (r.file_ok && r.rejected == 0 && r.keys_set == 1) ? SettingCheck::OK : SettingCheck::BAD_VALUE;
  }

  // Always resets `out` to the defaults first.
  static ConfigParseResult parse(const char* data, size_t len, DeviceConfig& out) {
    out = DeviceConfig{};
    ConfigParseResult result;
    if ((data == nullptr && len != 0) || len > MAX_FILE_SIZE) {
      return result;
    }
    result.file_ok = true;
    SeenMask seen;
    size_t pos = 0;
    uint16_t line_no = 0;
    if (len >= 3 && static_cast<uint8_t>(data[0]) == 0xEF && static_cast<uint8_t>(data[1]) == 0xBB &&
        static_cast<uint8_t>(data[2]) == 0xBF) {
      pos = 3; // UTF-8 byte order mark from a Windows editor
    }
    while (pos < len) {
      size_t end = pos;
      while (end < len && data[end] != '\n') {
        ++end;
      }
      ++line_no;
      applyLine(data + pos, end - pos, line_no, out, seen, result);
      pos = end + 1;
    }
    result.keys_set = static_cast<uint8_t>(seen.count());
    return result;
  }

private:
  struct SeenMask {
    uint64_t lo = 0;
    uint64_t hi = 0;
    void set(size_t index) {
      if (index < 64) {
        lo |= (1ull << index);
      } else {
        hi |= (1ull << (index - 64));
      }
    }
    size_t count() const {
      size_t c = 0;
      for (uint64_t m = lo; m != 0; m &= m - 1) ++c;
      for (uint64_t m = hi; m != 0; m &= m - 1) ++c;
      return c;
    }
  };
  static bool isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\r';
  }

  static void trim(const char*& s, size_t& n) {
    while (n > 0 && isSpace(s[0])) {
      ++s;
      --n;
    }
    while (n > 0 && isSpace(s[n - 1])) {
      --n;
    }
  }

  static bool keyIs(const char* key, size_t key_len, const char* name) {
    return strlen(name) == key_len && memcmp(key, name, key_len) == 0;
  }

  // Decimal number: optional sign, digits, optional '.' with digits. No exponent, no NaN, no
  // trailing text.
  static bool parseNumber(const char* s, size_t n, double& out) {
    if (n == 0 || n > MAX_NUMBER_CHARS) {
      return false;
    }
    size_t i = 0;
    bool negative = false;
    if (s[i] == '+' || s[i] == '-') {
      negative = s[i] == '-';
      ++i;
    }
    double value = 0.0;
    size_t int_digits = 0;
    while (i < n && s[i] >= '0' && s[i] <= '9') {
      value = value * 10.0 + (s[i] - '0');
      ++i;
      ++int_digits;
    }
    if (int_digits == 0) {
      return false;
    }
    if (i < n && s[i] == '.') {
      ++i;
      double scale = 0.1;
      size_t frac_digits = 0;
      while (i < n && s[i] >= '0' && s[i] <= '9') {
        value += (s[i] - '0') * scale;
        scale *= 0.1;
        ++i;
        ++frac_digits;
      }
      if (frac_digits == 0) {
        return false;
      }
    }
    if (i != n) {
      return false;
    }
    out = negative ? -value : value;
    return true;
  }

  static bool parseFloatIn(const char* s, size_t n, float lo, float hi, float& out) {
    double v;
    if (!parseNumber(s, n, v) || !(v >= lo && v <= hi)) {
      return false;
    }
    out = static_cast<float>(v);
    return true;
  }

  template <typename T>
  static bool parseIntIn(const char* s, size_t n, int lo, int hi, T& out) {
    double v;
    if (!parseNumber(s, n, v) || !(v >= lo && v <= hi) || v != static_cast<double>(static_cast<int>(v))) {
      return false;
    }
    out = static_cast<T>(static_cast<int>(v));
    return true;
  }

  // night_wake_interval_s: 0 (off) or 60 to 1800.
  static bool parseNightInterval(const char* s, size_t n, uint16_t& out) {
    uint16_t v = 0;
    if (!parseIntIn(s, n, 0, DeviceConfig::MAX_NIGHT_WAKE_INTERVAL_S, v) ||
        (v != 0 && v < DeviceConfig::MIN_NIGHT_WAKE_INTERVAL_S)) {
      return false;
    }
    out = v;
    return true;
  }

  // heat_alert_limit_c: 0 (off) or 27 to 45.
  static bool parseHeatLimit(const char* s, size_t n, uint8_t& out) {
    uint8_t v = 0;
    if (!parseIntIn(s, n, 0, DeviceConfig::MAX_HEAT_ALERT_LIMIT_C, v) ||
        (v != 0 && v < DeviceConfig::MIN_HEAT_ALERT_LIMIT_C)) {
      return false;
    }
    out = v;
    return true;
  }

  // dry_alert_limit_rh: 0 (off) or 20 to 45.
  static bool parseDryLimit(const char* s, size_t n, uint8_t& out) {
    uint8_t v = 0;
    if (!parseIntIn(s, n, 0, DeviceConfig::MAX_DRY_ALERT_LIMIT_RH, v) ||
        (v != 0 && v < DeviceConfig::MIN_DRY_ALERT_LIMIT_RH)) {
      return false;
    }
    out = v;
    return true;
  }

  // wall_frsi_pct: 0 (off) or 50 to 95.
  static bool parseWallFrsi(const char* s, size_t n, uint8_t& out) {
    uint8_t v = 0;
    if (!parseIntIn(s, n, 0, DeviceConfig::MAX_WALL_FRSI_PCT, v) ||
        (v != 0 && v < DeviceConfig::MIN_WALL_FRSI_PCT)) {
      return false;
    }
    out = v;
    return true;
  }

  // window_drop_c: 0 (off) or 1.0 to 5.0 C, rounded to 0.1 C steps (the RTC byte holds tenths).
  static bool parseWindowDrop(const char* s, size_t n, float& out) {
    float v = 0.0f;
    if (!parseFloatIn(s, n, 0.0f, DeviceConfig::MAX_WINDOW_DROP_C, v) ||
        (v != 0.0f && v < DeviceConfig::MIN_WINDOW_DROP_C)) {
      return false;
    }
    out = static_cast<float>(lroundf(v * 10.0f)) / 10.0f;
    return true;
  }

  // auto_assign_min_rssi: 0 (off) or -100 to -40 dBm.
  static bool parseAutoAssignRssi(const char* s, size_t n, int8_t& out) {
    int v = 0;
    if (!parseIntIn(s, n, DeviceConfig::MIN_AUTO_ASSIGN_MIN_RSSI, 0, v) ||
        (v != 0 && v > DeviceConfig::MAX_AUTO_ASSIGN_MIN_RSSI)) {
      return false;
    }
    out = static_cast<int8_t>(v);
    return true;
  }

  // Keys onboard_name (index 18), slot1_name to slot3_name (index 19..21), slot4_name, slot5_name (index 40, 41)
  // and slot6_name to slot10_name (index 57..61). The value is already trimmed. A rejected value keeps
  // the earlier name. Returns false for any other key.
  static bool parseNameKey(const char* key, size_t key_len, const char* val, size_t val_len, DeviceConfig& cfg,
                           bool& ok, int& index) {
    static const char* const slot_keys[DeviceConfig::SLOT_COUNT] = {
        "slot1_name", "slot2_name", "slot3_name", "slot4_name", "slot5_name",
        "slot6_name", "slot7_name", "slot8_name", "slot9_name", "slot10_name"};
    char* target = nullptr;
    if (keyIs(key, key_len, "onboard_name")) {
      index = 18;
      target = cfg.onboard_name;
    }
    for (size_t i = 0; target == nullptr && i < DeviceConfig::SLOT_COUNT; ++i) {
      if (keyIs(key, key_len, slot_keys[i])) {
        if (i < 3) {
          index = static_cast<int>(19 + i);
        } else if (i < 5) {
          index = static_cast<int>(37 + i);
        } else {
          index = static_cast<int>(52 + i);
        }
        target = cfg.slot_name[i];
      }
    }
    if (target == nullptr) {
      return false;
    }
    ok = DeviceConfig::validName(val, val_len);
    if (ok) {
      memcpy(target, val, val_len);
      target[val_len] = '\0';
    }
    return true;
  }

  static int hexDigit(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
  }

  // Exactly "AA:BB:CC:DD:EE:FF" (17 characters, upper or lower case hex, ':' only).
  static bool parseMac(const char* s, size_t n, uint8_t (&out)[DeviceConfig::MAC_BYTES]) {
    if (n != DeviceConfig::MAC_BYTES * 3 - 1) {
      return false;
    }
    uint8_t mac[DeviceConfig::MAC_BYTES];
    for (size_t i = 0; i < DeviceConfig::MAC_BYTES; ++i) {
      const char* p = s + i * 3;
      const int hi = hexDigit(p[0]);
      const int lo = hexDigit(p[1]);
      if (hi < 0 || lo < 0 || (i + 1 < DeviceConfig::MAC_BYTES && p[2] != ':')) {
        return false;
      }
      mac[i] = static_cast<uint8_t>(hi * 16 + lo);
    }
    memcpy(out, mac, sizeof(mac));
    return true;
  }

  // Keys slot1_mac to slot3_mac (index 22..24), slot4_mac, slot5_mac (index 42, 43) and
  // slot6_mac to slot10_mac (index 62..66). An empty value clears the fixed MAC. A malformed
  // value, the all-zero MAC and a MAC that another slot has fixed are rejected; the previous value
  // stays. Returns false for any other key.
  static bool parseMacKey(const char* key, size_t key_len, const char* val, size_t val_len, DeviceConfig& cfg,
                          bool& ok, int& index) {
    static const char* const mac_keys[DeviceConfig::SLOT_COUNT] = {
        "slot1_mac", "slot2_mac", "slot3_mac", "slot4_mac", "slot5_mac",
        "slot6_mac", "slot7_mac", "slot8_mac", "slot9_mac", "slot10_mac"};
    for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
      if (!keyIs(key, key_len, mac_keys[i])) {
        continue;
      }
      if (i < 3) {
        index = static_cast<int>(22 + i);
      } else if (i < 5) {
        index = static_cast<int>(39 + i);
      } else {
        index = static_cast<int>(57 + i);
      }
      if (val_len == 0) {
        memset(cfg.slot_mac[i], 0, DeviceConfig::MAC_BYTES);
        ok = true;
        return true;
      }
      uint8_t mac[DeviceConfig::MAC_BYTES];
      ok = parseMac(val, val_len, mac) && !DeviceConfig::macIsZero(mac) && !cfg.macFixedElsewhere(mac, i);
      if (ok) {
        memcpy(cfg.slot_mac[i], mac, sizeof(mac));
      }
      return true;
    }
    return false;
  }

  // Keys slot1_temp_offset_c to slot3_temp_offset_c (index 12..14), slot1_rh_offset_pct to
  // slot3_rh_offset_pct (index 15..17), slot4_temp_offset_c and slot5_temp_offset_c (index 44, 45),
  // slot4_rh_offset_pct and slot5_rh_offset_pct (index 46, 47), slot6_temp_offset_c to
  // slot10_temp_offset_c (index 67..71) and slot6_rh_offset_pct to slot10_rh_offset_pct (index 72..76).
  // Returns false for any other key.
  static bool parseSlotKey(const char* key, size_t key_len, const char* val, size_t val_len, DeviceConfig& cfg,
                           bool& ok, int& index) {
    static const char* const temp_keys[DeviceConfig::OFFSET_SLOT_COUNT] = {
        "slot1_temp_offset_c", "slot2_temp_offset_c", "slot3_temp_offset_c", "slot4_temp_offset_c",
        "slot5_temp_offset_c", "slot6_temp_offset_c", "slot7_temp_offset_c", "slot8_temp_offset_c",
        "slot9_temp_offset_c", "slot10_temp_offset_c"};
    static const char* const rh_keys[DeviceConfig::OFFSET_SLOT_COUNT] = {
        "slot1_rh_offset_pct", "slot2_rh_offset_pct", "slot3_rh_offset_pct", "slot4_rh_offset_pct",
        "slot5_rh_offset_pct", "slot6_rh_offset_pct", "slot7_rh_offset_pct", "slot8_rh_offset_pct",
        "slot9_rh_offset_pct", "slot10_rh_offset_pct"};
    for (size_t i = 0; i < DeviceConfig::OFFSET_SLOT_COUNT; ++i) {
      if (keyIs(key, key_len, temp_keys[i])) {
        if (i < 3) {
          index = static_cast<int>(12 + i);
        } else if (i < 5) {
          index = static_cast<int>(41 + i);
        } else {
          index = static_cast<int>(62 + i);
        }
        ok = parseFloatIn(val, val_len, DeviceConfig::MIN_SLOT_TEMP_OFFSET_C,
                          DeviceConfig::MAX_SLOT_TEMP_OFFSET_C, cfg.slot_temp_offset_c[i]);
        return true;
      }
      if (keyIs(key, key_len, rh_keys[i])) {
        if (i < 3) {
          index = static_cast<int>(15 + i);
        } else if (i < 5) {
          index = static_cast<int>(43 + i);
        } else {
          index = static_cast<int>(67 + i);
        }
        ok = parseFloatIn(val, val_len, DeviceConfig::MIN_SLOT_RH_OFFSET_PCT,
                          DeviceConfig::MAX_SLOT_RH_OFFSET_PCT, cfg.slot_rh_offset_pct[i]);
        return true;
      }
    }
    return false;
  }

  // Keys rh_gain (index 50), slot1_rh_gain to slot5_rh_gain (index 51..55) and
  // slot6_rh_gain to slot10_rh_gain (index 77..81): 0.90 to 1.10, stored in 0.002
  // steps around 1.00. Returns false for any other key.
  static bool parseGainKey(const char* key, size_t key_len, const char* val, size_t val_len, DeviceConfig& cfg,
                           bool& ok, int& index) {
    static const char* const slot_keys[DeviceConfig::OFFSET_SLOT_COUNT] = {
        "slot1_rh_gain", "slot2_rh_gain", "slot3_rh_gain", "slot4_rh_gain", "slot5_rh_gain",
        "slot6_rh_gain", "slot7_rh_gain", "slot8_rh_gain", "slot9_rh_gain", "slot10_rh_gain"};
    float* target = nullptr;
    if (keyIs(key, key_len, "rh_gain")) {
      index = 50;
      target = &cfg.rh_gain;
    }
    for (size_t i = 0; target == nullptr && i < DeviceConfig::OFFSET_SLOT_COUNT; ++i) {
      if (keyIs(key, key_len, slot_keys[i])) {
        if (i < 5) {
          index = static_cast<int>(51 + i);
        } else {
          index = static_cast<int>(72 + i);
        }
        target = &cfg.slot_rh_gain[i];
      }
    }
    if (target == nullptr) {
      return false;
    }
    float v = 0.0f;
    ok = parseFloatIn(val, val_len, DeviceConfig::MIN_RH_GAIN, DeviceConfig::MAX_RH_GAIN, v);
    if (ok) {
      *target = DeviceConfig::gainFromSteps(DeviceConfig::gainToSteps(v)); // 0.002 steps, like the RTC byte
    }
    return true;
  }

  // Keys slot1_enabled to slot10_enabled (index 84..93): 0 or 1.
  // Returns false for any other key.
  static bool parseEnabledKey(const char* key, size_t key_len, const char* val, size_t val_len, DeviceConfig& cfg,
                              bool& ok, int& index) {
    static const char* const enabled_keys[DeviceConfig::SLOT_COUNT] = {
        "slot1_enabled", "slot2_enabled", "slot3_enabled", "slot4_enabled", "slot5_enabled",
        "slot6_enabled", "slot7_enabled", "slot8_enabled", "slot9_enabled", "slot10_enabled"};
    for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
      if (keyIs(key, key_len, enabled_keys[i])) {
        index = static_cast<int>(84 + i);
        uint8_t enabled = 0;
        ok = parseIntIn(val, val_len, 0, 1, enabled);
        if (ok) {
          cfg.setSlotEnabled(i, enabled != 0);
        }
        return true;
      }
    }
    return false;
  }

  static void reject(ConfigParseResult& result, uint16_t line_no) {
    if (result.rejected < ConfigRejects::MAX_LINES) {
      result.rejected_lines[result.rejected] = line_no;
    }
    if (result.rejected < 255) {
      ++result.rejected;
    }
  }

  static void applyLine(const char* line, size_t n, uint16_t line_no, DeviceConfig& cfg, SeenMask& seen,
                        ConfigParseResult& result) {
    trim(line, n);
    if (n == 0 || line[0] == '#' || line[0] == ';') {
      return;
    }
    const char* eq = static_cast<const char*>(memchr(line, '=', n));
    if (eq == nullptr) {
      reject(result, line_no);
      return;
    }
    const char* key = line;
    size_t key_len = static_cast<size_t>(eq - line);
    const char* val = eq + 1;
    size_t val_len = n - key_len - 1;
    trim(key, key_len);
    trim(val, val_len);

    bool ok = false;
    int index = -1;
    if (keyIs(key, key_len, "temp_offset_c")) {
      index = 0;
      ok = parseFloatIn(val, val_len, DeviceConfig::MIN_TEMP_OFFSET_C, DeviceConfig::MAX_TEMP_OFFSET_C, cfg.temp_offset_c);
    } else if (keyIs(key, key_len, "usb_temp_offset_c")) {
      index = 48;
      ok = parseFloatIn(val, val_len, DeviceConfig::MIN_USB_TEMP_OFFSET_C, DeviceConfig::MAX_USB_TEMP_OFFSET_C,
                        cfg.usb_temp_offset_c);
      if (ok) {
        cfg.usb_temp_offset_c = static_cast<float>(lroundf(cfg.usb_temp_offset_c * 10.0f)) / 10.0f; // 0.1 C steps, like the RTC byte
      }
    } else if (keyIs(key, key_len, "freeze_alert_limit_c")) {
      index = 1;
      ok = parseFloatIn(val, val_len, DeviceConfig::MIN_FREEZE_ALERT_LIMIT_C, DeviceConfig::MAX_FREEZE_ALERT_LIMIT_C,
                        cfg.freeze_alert_limit_c);
    } else if (keyIs(key, key_len, "mold_alert_limit_rh")) {
      index = 2;
      ok = parseIntIn(val, val_len, DeviceConfig::MIN_MOLD_ALERT_LIMIT_RH, DeviceConfig::MAX_MOLD_ALERT_LIMIT_RH,
                      cfg.mold_alert_limit_rh);
    } else if (keyIs(key, key_len, "chime_volume_pct")) {
      index = 3;
      ok = parseIntIn(val, val_len, 0, DeviceConfig::MAX_CHIME_VOLUME_PCT, cfg.chime_volume_pct);
    } else if (keyIs(key, key_len, "quiet_hours_start_hour")) {
      index = 4;
      ok = parseIntIn(val, val_len, 0, DeviceConfig::MAX_HOUR, cfg.quiet_hours_start_hour);
    } else if (keyIs(key, key_len, "quiet_hours_end_hour")) {
      index = 5;
      ok = parseIntIn(val, val_len, 0, DeviceConfig::MAX_HOUR, cfg.quiet_hours_end_hour);
    } else if (keyIs(key, key_len, "mic_spl_offset_db")) {
      index = 6;
      ok = parseFloatIn(val, val_len, DeviceConfig::MIN_MIC_SPL_OFFSET_DB, DeviceConfig::MAX_MIC_SPL_OFFSET_DB,
                        cfg.mic_spl_offset_db);
    } else if (keyIs(key, key_len, "log_retention_months")) {
      index = 7;
      ok = parseIntIn(val, val_len, 0, DeviceConfig::MAX_LOG_RETENTION_MONTHS, cfg.log_retention_months);
    } else if (keyIs(key, key_len, "noise_alert_limit_db")) {
      index = 8;
      // 0 is off; 1..39 is rejected, so a typo never gives a limit below the useful range. The
      // previous value stays on a rejected line.
      uint8_t limit = 0;
      ok = parseIntIn(val, val_len, 0, DeviceConfig::MAX_NOISE_ALERT_LIMIT_DB, limit) &&
           (limit == 0 || limit >= DeviceConfig::MIN_NOISE_ALERT_LIMIT_DB);
      if (ok) {
        cfg.noise_alert_limit_db = limit;
      }
    } else if (keyIs(key, key_len, "chime_alert_mask")) {
      index = 9;
      ok = parseIntIn(val, val_len, 0, DeviceConfig::MAX_CHIME_ALERT_MASK, cfg.chime_alert_mask);
    } else if (keyIs(key, key_len, "ntp_sync_interval_h")) {
      index = 10;
      ok = parseIntIn(val, val_len, DeviceConfig::MIN_NTP_SYNC_INTERVAL_H, DeviceConfig::MAX_NTP_SYNC_INTERVAL_H,
                      cfg.ntp_sync_interval_h);
    } else if (keyIs(key, key_len, "wake_interval_s")) {
      index = 25;
      ok = parseIntIn(val, val_len, DeviceConfig::MIN_WAKE_INTERVAL_S, DeviceConfig::MAX_WAKE_INTERVAL_S,
                      cfg.wake_interval_s);
    } else if (keyIs(key, key_len, "sensor_stale_min")) {
      index = 29;
      ok = parseIntIn(val, val_len, DeviceConfig::MIN_SENSOR_STALE_MIN, DeviceConfig::MAX_SENSOR_STALE_MIN,
                      cfg.sensor_stale_min);
    } else if (keyIs(key, key_len, "display_invert")) {
      index = 30;
      ok = parseIntIn(val, val_len, 0, DeviceConfig::MAX_DISPLAY_INVERT, cfg.display_invert);
    } else if (keyIs(key, key_len, "climate_alert_tiles")) {
      index = 31;
      ok = parseIntIn(val, val_len, 0, DeviceConfig::MAX_CLIMATE_ALERT_TILES, cfg.climate_alert_tiles);
    } else if (keyIs(key, key_len, "battery_offset_mv")) {
      index = 32;
      ok = parseIntIn(val, val_len, DeviceConfig::MIN_BATTERY_OFFSET_MV, DeviceConfig::MAX_BATTERY_OFFSET_MV,
                      cfg.battery_offset_mv);
    } else if (keyIs(key, key_len, "slot_release_h")) {
      index = 33;
      ok = parseIntIn(val, val_len, 0, DeviceConfig::MAX_SLOT_RELEASE_H, cfg.slot_release_h);
    } else if (keyIs(key, key_len, "night_wake_interval_s")) {
      index = 34;
      ok = parseNightInterval(val, val_len, cfg.night_wake_interval_s);
    } else if (keyIs(key, key_len, "temp_unit")) {
      index = 35;
      // Exactly one upper-case letter: 'f', 'K', a longer text and an empty value are rejected.
      ok = val_len == 1 && (val[0] == 'C' || val[0] == 'F');
      if (ok) {
        cfg.temp_unit = val[0];
      }
    } else if (keyIs(key, key_len, "chime_repeat_h")) {
      index = 36;
      ok = parseIntIn(val, val_len, 0, DeviceConfig::MAX_CHIME_REPEAT_H, cfg.chime_repeat_h);
    } else if (keyIs(key, key_len, "heat_alert_limit_c")) {
      index = 37;
      ok = parseHeatLimit(val, val_len, cfg.heat_alert_limit_c);
    } else if (keyIs(key, key_len, "dry_alert_limit_rh")) {
      index = 49;
      ok = parseDryLimit(val, val_len, cfg.dry_alert_limit_rh);
    } else if (keyIs(key, key_len, "wall_frsi_pct")) {
      index = 82;
      ok = parseWallFrsi(val, val_len, cfg.wall_frsi_pct);
    } else if (keyIs(key, key_len, "ble_broadcast_enabled")) {
      index = 38;
      ok = parseIntIn(val, val_len, 0, DeviceConfig::MAX_BLE_BROADCAST_ENABLED, cfg.ble_broadcast_enabled);
    } else if (keyIs(key, key_len, "slot_auto_assign")) {
      index = 83;
      ok = parseIntIn(val, val_len, 0, DeviceConfig::MAX_SLOT_AUTO_ASSIGN, cfg.slot_auto_assign);
    } else if (keyIs(key, key_len, "auto_assign_min_rssi")) {
      index = 94;
      ok = parseAutoAssignRssi(val, val_len, cfg.auto_assign_min_rssi);
    } else if (keyIs(key, key_len, "window_drop_c")) {
      index = 95;
      ok = parseWindowDrop(val, val_len, cfg.window_drop_c);
    } else if (keyIs(key, key_len, "ble_console")) {
      index = 39;
      ok = parseIntIn(val, val_len, 0, DeviceConfig::MAX_BLE_CONSOLE, cfg.ble_console);
    } else if (keyIs(key, key_len, "outdoor_slot")) {
      index = 26;
      ok = parseIntIn(val, val_len, 0, DeviceConfig::MAX_OUTDOOR_SLOT, cfg.outdoor_slot);
    } else if (keyIs(key, key_len, "door_ref_slot")) {
      index = 56;
      ok = parseIntIn(val, val_len, 0, DeviceConfig::MAX_DOOR_REF_SLOT, cfg.door_ref_slot);
    } else if (keyIs(key, key_len, "ventilate_limit_rh")) {
      index = 27;
      ok = parseIntIn(val, val_len, DeviceConfig::MIN_VENTILATE_LIMIT_RH, DeviceConfig::MAX_VENTILATE_LIMIT_RH,
                      cfg.ventilate_limit_rh);
    } else if (keyIs(key, key_len, "sensor_battery_limit_pct")) {
      index = 28;
      ok = parseIntIn(val, val_len, 0, DeviceConfig::MAX_SENSOR_BATTERY_LIMIT_PCT, cfg.sensor_battery_limit_pct);
    } else if (keyIs(key, key_len, "rh_offset_pct")) {
      index = 11;
      ok = parseFloatIn(val, val_len, DeviceConfig::MIN_RH_OFFSET_PCT, DeviceConfig::MAX_RH_OFFSET_PCT,
                        cfg.rh_offset_pct);
    } else if (parseSlotKey(key, key_len, val, val_len, cfg, ok, index)) {
      // index and ok set by parseSlotKey
    } else if (parseGainKey(key, key_len, val, val_len, cfg, ok, index)) {
      // index and ok set by parseGainKey
    } else if (parseEnabledKey(key, key_len, val, val_len, cfg, ok, index)) {
      // index and ok set by parseEnabledKey
    } else if (parseNameKey(key, key_len, val, val_len, cfg, ok, index)) {
      // index and ok set by parseNameKey
    } else if (parseMacKey(key, key_len, val, val_len, cfg, ok, index)) {
      // index and ok set by parseMacKey
    }
    if (index < 0) {
      return; // unknown key
    }
    if (ok) {
      seen.set(static_cast<size_t>(index));
    } else {
      reject(result, line_no);
    }
  }
};

// Writes a DeviceConfig as config.ini text: one "key=value" line for each setting, in the key order
// of the parser. Floats print with at most two decimals and at least one, so DeviceConfigParser
// reads the text back to the same values. The text holds no Wi-Fi setting: DeviceConfig has none.
class DeviceConfigText {
public:
  static constexpr size_t MAX_SIZE = 2560; // worst case with every key at its longest value

  // Returns the text length, or 0 with an empty string when `cap` is too small.
  static size_t format(const DeviceConfig& cfg, char* out, size_t cap) {
    if (out == nullptr || cap == 0) {
      return 0;
    }
    out[0] = '\0';
    size_t pos = 0;
    bool ok = true;
    ok = ok && putFloat(out, cap, pos, "temp_offset_c", cfg.temp_offset_c);
    ok = ok && putFloat(out, cap, pos, "usb_temp_offset_c", cfg.usb_temp_offset_c);
    ok = ok && putFloat(out, cap, pos, "rh_offset_pct", cfg.rh_offset_pct);
    ok = ok && putGain(out, cap, pos, "rh_gain", cfg.rh_gain);
    ok = ok && putFloat(out, cap, pos, "freeze_alert_limit_c", cfg.freeze_alert_limit_c);
    ok = ok && putInt(out, cap, pos, "mold_alert_limit_rh", cfg.mold_alert_limit_rh);
    ok = ok && putInt(out, cap, pos, "chime_volume_pct", cfg.chime_volume_pct);
    ok = ok && putInt(out, cap, pos, "quiet_hours_start_hour", cfg.quiet_hours_start_hour);
    ok = ok && putInt(out, cap, pos, "quiet_hours_end_hour", cfg.quiet_hours_end_hour);
    ok = ok && putFloat(out, cap, pos, "mic_spl_offset_db", cfg.mic_spl_offset_db);
    ok = ok && putInt(out, cap, pos, "log_retention_months", cfg.log_retention_months);
    ok = ok && putInt(out, cap, pos, "noise_alert_limit_db", cfg.noise_alert_limit_db);
    ok = ok && putInt(out, cap, pos, "chime_alert_mask", cfg.chime_alert_mask);
    ok = ok && putInt(out, cap, pos, "ntp_sync_interval_h", cfg.ntp_sync_interval_h);
    ok = ok && putInt(out, cap, pos, "wake_interval_s", cfg.wake_interval_s);
    ok = ok && putInt(out, cap, pos, "sensor_stale_min", cfg.sensor_stale_min);
    ok = ok && putInt(out, cap, pos, "display_invert", cfg.display_invert);
    ok = ok && putInt(out, cap, pos, "climate_alert_tiles", cfg.climate_alert_tiles);
    ok = ok && putSignedInt(out, cap, pos, "battery_offset_mv", cfg.battery_offset_mv);
    ok = ok && putInt(out, cap, pos, "slot_release_h", cfg.slot_release_h);
    ok = ok && putInt(out, cap, pos, "night_wake_interval_s", cfg.night_wake_interval_s);
    ok = ok && putLine(out, cap, pos, "temp_unit", cfg.temp_unit == 'F' ? "F" : "C");
    ok = ok && putInt(out, cap, pos, "chime_repeat_h", cfg.chime_repeat_h);
    ok = ok && putInt(out, cap, pos, "heat_alert_limit_c", cfg.heat_alert_limit_c);
    ok = ok && putInt(out, cap, pos, "dry_alert_limit_rh", cfg.dry_alert_limit_rh);
    ok = ok && putInt(out, cap, pos, "wall_frsi_pct", cfg.wall_frsi_pct);
    ok = ok && putFloat(out, cap, pos, "window_drop_c", cfg.window_drop_c);
    ok = ok && putInt(out, cap, pos, "ble_broadcast_enabled", cfg.ble_broadcast_enabled);
    ok = ok && putInt(out, cap, pos, "ble_console", cfg.ble_console);
    ok = ok && putInt(out, cap, pos, "slot_auto_assign", cfg.slot_auto_assign);
    ok = ok && putSignedInt(out, cap, pos, "auto_assign_min_rssi", cfg.auto_assign_min_rssi);
    ok = ok && putInt(out, cap, pos, "outdoor_slot", cfg.outdoor_slot);
    ok = ok && putInt(out, cap, pos, "door_ref_slot", cfg.door_ref_slot);
    ok = ok && putInt(out, cap, pos, "ventilate_limit_rh", cfg.ventilate_limit_rh);
    ok = ok && putInt(out, cap, pos, "sensor_battery_limit_pct", cfg.sensor_battery_limit_pct);
    for (size_t i = 0; ok && i < DeviceConfig::OFFSET_SLOT_COUNT; ++i) {
      char key[32];
      snprintf(key, sizeof(key), "slot%u_temp_offset_c", static_cast<unsigned>(i + 1));
      ok = putFloat(out, cap, pos, key, cfg.slot_temp_offset_c[i]);
    }
    for (size_t i = 0; ok && i < DeviceConfig::OFFSET_SLOT_COUNT; ++i) {
      char key[32];
      snprintf(key, sizeof(key), "slot%u_rh_offset_pct", static_cast<unsigned>(i + 1));
      ok = putFloat(out, cap, pos, key, cfg.slot_rh_offset_pct[i]);
    }
    for (size_t i = 0; ok && i < DeviceConfig::OFFSET_SLOT_COUNT; ++i) {
      char key[32];
      snprintf(key, sizeof(key), "slot%u_rh_gain", static_cast<unsigned>(i + 1));
      ok = putGain(out, cap, pos, key, cfg.slot_rh_gain[i]);
    }
    ok = ok && (cfg.onboard_name[0] == '\0' || putLine(out, cap, pos, "onboard_name", cfg.onboard_name));
    for (size_t i = 0; ok && i < DeviceConfig::SLOT_COUNT; ++i) {
      char key[16];
      snprintf(key, sizeof(key), "slot%u_name", static_cast<unsigned>(i + 1));
      ok = cfg.slot_name[i][0] == '\0' || putLine(out, cap, pos, key, cfg.slot_name[i]);
    }
    for (size_t i = 0; ok && i < DeviceConfig::SLOT_COUNT; ++i) {
      if (!cfg.hasSlotMac(i)) {
        continue;
      }
      char key[16];
      char mac[18];
      snprintf(key, sizeof(key), "slot%u_mac", static_cast<unsigned>(i + 1));
      const uint8_t* m = cfg.slot_mac[i];
      snprintf(mac, sizeof(mac), "%02X:%02X:%02X:%02X:%02X:%02X", m[0], m[1], m[2], m[3], m[4], m[5]);
      ok = putLine(out, cap, pos, key, mac);
    }
    for (size_t i = 0; ok && i < DeviceConfig::SLOT_COUNT; ++i) {
      char key[16];
      snprintf(key, sizeof(key), "slot%u_enabled", static_cast<unsigned>(i + 1));
      ok = putInt(out, cap, pos, key, cfg.isSlotEnabled(i) ? 1 : 0);
    }
    if (!ok) {
      out[0] = '\0';
      return 0;
    }
    return pos;
  }

private:
  static bool putLine(char* out, size_t cap, size_t& pos, const char* key, const char* value) {
    const int n = snprintf(out + pos, cap - pos, "%s=%s\n", key, value);
    if (n < 0 || static_cast<size_t>(n) >= cap - pos) {
      return false;
    }
    pos += static_cast<size_t>(n);
    return true;
  }

  static bool putInt(char* out, size_t cap, size_t& pos, const char* key, uint16_t value) {
    char text[8];
    snprintf(text, sizeof(text), "%u", static_cast<unsigned>(value));
    return putLine(out, cap, pos, key, text);
  }

  static bool putSignedInt(char* out, size_t cap, size_t& pos, const char* key, int16_t value) {
    char text[8];
    snprintf(text, sizeof(text), "%d", static_cast<int>(value));
    return putLine(out, cap, pos, key, text);
  }

  // Gain with three decimals: the steps are 0.002.
  static bool putGain(char* out, size_t cap, size_t& pos, const char* key, float value) {
    char text[16];
    snprintf(text, sizeof(text), "%.3f", static_cast<double>(value));
    return putLine(out, cap, pos, key, text);
  }

  static bool putFloat(char* out, size_t cap, size_t& pos, const char* key, float value) {
    char text[16];
    snprintf(text, sizeof(text), "%.2f", static_cast<double>(value));
    size_t len = strlen(text);
    if (len >= 2 && text[len - 1] == '0' && text[len - 2] != '.') {
      text[--len] = '\0'; // "-1.90" -> "-1.9", "0.00" -> "0.0"
    }
    return putLine(out, cap, pos, key, text);
  }
};

// Edits a config.ini text for the console command SET. The last line of the key (the line that wins in
// DeviceConfigParser::parse) is replaced by "key=value"; its line end (LF, CRLF or none) stays. Without
// such a line the pair is appended as a new line in the line end style of the file (LF for an empty file).
// Each other line, comment and a byte order mark stay unchanged. A result above MAX_FILE_SIZE gives
// TOO_LARGE and `out` stays unusable.
class DeviceConfigEditor {
public:
  enum class Result : uint8_t { OK = 0, TOO_LARGE };

  // `out` needs DeviceConfigParser::MAX_FILE_SIZE bytes.
  static Result set(const char* in, size_t in_len, const char* key, const char* value, char* out, size_t& out_len) {
    out_len = 0;
    const size_t key_len = strlen(key);
    const size_t pair_len = key_len + 1 + strlen(value);
    if (in_len > DeviceConfigParser::MAX_FILE_SIZE) {
      return Result::TOO_LARGE;
    }
    size_t pos = (in_len >= 3 && static_cast<uint8_t>(in[0]) == 0xEF && static_cast<uint8_t>(in[1]) == 0xBB &&
                  static_cast<uint8_t>(in[2]) == 0xBF) ? 3 : 0;
    bool found = false;
    size_t found_start = 0;
    size_t found_end = 0; // end of the line content, before CR and LF
    while (pos < in_len) {
      size_t nl = pos;
      while (nl < in_len && in[nl] != '\n') {
        ++nl;
      }
      const char* line = in + pos;
      size_t n = nl - pos;
      while (n > 0 && (line[0] == ' ' || line[0] == '\t' || line[0] == '\r')) {
        ++line;
        --n;
      }
      if (n > 0 && line[0] != '#' && line[0] != ';') {
        const char* eq = static_cast<const char*>(memchr(line, '=', n));
        if (eq != nullptr) {
          const char* k = line;
          size_t kl = static_cast<size_t>(eq - line);
          while (kl > 0 && (k[kl - 1] == ' ' || k[kl - 1] == '\t' || k[kl - 1] == '\r')) {
            --kl;
          }
          if (kl == key_len && memcmp(k, key, key_len) == 0) {
            found = true;
            found_start = pos;
            found_end = nl > pos && in[nl - 1] == '\r' ? nl - 1 : nl;
          }
        }
      }
      pos = nl + 1;
    }
    if (found) {
      const size_t total = found_start + pair_len + (in_len - found_end);
      if (total > DeviceConfigParser::MAX_FILE_SIZE) {
        return Result::TOO_LARGE;
      }
      memcpy(out, in, found_start);
      size_t at = found_start;
      memcpy(out + at, key, key_len);
      at += key_len;
      out[at++] = '=';
      memcpy(out + at, value, strlen(value));
      at += strlen(value);
      memcpy(out + at, in + found_end, in_len - found_end);
      out_len = at + (in_len - found_end);
      return Result::OK;
    }
    bool crlf = false;
    for (size_t i = in_len; i > 0; --i) {
      if (in[i - 1] == '\n') {
        crlf = i >= 2 && in[i - 2] == '\r';
        break;
      }
    }
    const char* eol = crlf ? "\r\n" : "\n";
    const size_t eol_len = crlf ? 2 : 1;
    const bool need_eol = in_len > 0 && in[in_len - 1] != '\n';
    const size_t total = in_len + (need_eol ? eol_len : 0) + pair_len + eol_len;
    if (total > DeviceConfigParser::MAX_FILE_SIZE) {
      return Result::TOO_LARGE;
    }
    size_t at = in_len;
    memcpy(out, in, in_len);
    if (need_eol) {
      memcpy(out + at, eol, eol_len);
      at += eol_len;
    }
    memcpy(out + at, key, key_len);
    at += key_len;
    out[at++] = '=';
    memcpy(out + at, value, strlen(value));
    at += strlen(value);
    memcpy(out + at, eol, eol_len);
    out_len = at + eol_len;
    return Result::OK;
  }
};

// Host check of a config.ini text (tools/check_config.cpp). It runs DeviceConfigParser with the
// firmware rules and writes the report: the accepted values as DeviceConfigText, then one line
// "REJECTED line=<N>" for each recorded line and "REJECTED count=<N>". The exit code is 0 for no
// rejected line, 1 for rejected lines and 2 for a file above DeviceConfigParser::MAX_FILE_SIZE
// (the report is then one ERROR line). The code needs no Arduino header.
class DeviceConfigCheck {
public:
  static constexpr int EXIT_OK = 0;
  static constexpr int EXIT_REJECTED = 1;
  static constexpr int EXIT_UNUSABLE = 2;
  // Enough for the longest report: the values, four line numbers and the count.
  static constexpr size_t MAX_REPORT_SIZE = DeviceConfigText::MAX_SIZE + 128;

  // Returns the exit code. `out` always holds a terminated string, empty when `cap` is 0.
  static int run(const char* data, size_t len, char* out, size_t cap) {
    if (out == nullptr || cap == 0) {
      return EXIT_UNUSABLE;
    }
    out[0] = '\0';
    DeviceConfig cfg;
    const ConfigParseResult result = DeviceConfigParser::parse(data, len, cfg);
    if (!result.file_ok) {
      snprintf(out, cap, "ERROR file above %u bytes\n", static_cast<unsigned>(DeviceConfigParser::MAX_FILE_SIZE));
      return EXIT_UNUSABLE;
    }
    size_t pos = DeviceConfigText::format(cfg, out, cap);
    const ConfigRejects rejects = result.rejects();
    for (size_t i = 0; i < rejects.recorded() && pos < cap; ++i) {
      pos += append(out, cap, pos, "REJECTED line=%u\n", static_cast<unsigned>(rejects.lines[i]));
    }
    if (pos < cap) {
      append(out, cap, pos, "REJECTED count=%u\n", static_cast<unsigned>(rejects.count));
    }
    return rejects.count == 0 ? EXIT_OK : EXIT_REJECTED;
  }

private:
  static size_t append(char* out, size_t cap, size_t pos, const char* fmt, unsigned value) {
    const int n = snprintf(out + pos, cap - pos, fmt, value);
    return n < 0 ? 0 : static_cast<size_t>(n);
  }
};

} // namespace rlcd_panel
