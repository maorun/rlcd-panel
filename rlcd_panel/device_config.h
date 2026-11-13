#pragma once

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
  static constexpr float DEFAULT_RH_OFFSET_PCT = 0.0f;
  static constexpr float MIN_RH_OFFSET_PCT = -10.0f;
  static constexpr float MAX_RH_OFFSET_PCT = 10.0f;
  static constexpr size_t SLOT_COUNT = 3; // SwitchBot and BTHome registry slots
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
  static constexpr uint8_t DEFAULT_CHIME_ALERT_MASK = 127; // all seven alerts may sound
  static constexpr uint8_t MAX_CHIME_ALERT_MASK = 127;
  static constexpr uint8_t DEFAULT_SENSOR_BATTERY_LIMIT_PCT = 10; // 0 switches the sensor battery warning off
  static constexpr uint8_t MAX_SENSOR_BATTERY_LIMIT_PCT = 50;
  static constexpr uint8_t DEFAULT_OUTDOOR_SLOT = 0; // 0 switches the ventilation advice off
  static constexpr uint8_t MAX_OUTDOOR_SLOT = 3;
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
  static constexpr uint8_t DEFAULT_DISPLAY_INVERT = 0; // 1 shows light text on a dark background
  static constexpr uint8_t MAX_DISPLAY_INVERT = 1;
  static constexpr uint8_t DEFAULT_CLIMATE_ALERT_TILES = 15; // all four tiles feed the freezer and mold alerts
  static constexpr uint8_t MAX_CLIMATE_ALERT_TILES = 15;
  static constexpr uint32_t SCAN_DURATION_S = 4; // scan time that adds to the wake interval

  static constexpr size_t MAC_BYTES = 6;
  static constexpr size_t NAME_MAX_CHARS = 12; // longest sensor name; a name buffer adds the terminator

  // Display names of the onboard sensor and of the three registry slots (keys onboard_name and
  // slot1_name to slot3_name). Empty means the default title. A name holds 1 to 12 characters of
  // A-Z, 0-9, space and '-': all of them have a glyph in the 5x7 font.
  char onboard_name[NAME_MAX_CHARS + 1] = {0};
  char slot_name[SLOT_COUNT][NAME_MAX_CHARS + 1] = {{0}, {0}, {0}};

  // Fixed MAC address of each registry slot (keys slot1_mac to slot3_mac, format AA:BB:CC:DD:EE:FF).
  // All zero means no fixed MAC: the slot keeps the first-come rule. A fixed MAC is never all zero
  // and never appears in two slots.
  uint8_t slot_mac[SLOT_COUNT][MAC_BYTES] = {{0}, {0}, {0}};

  float temp_offset_c = DEFAULT_TEMP_OFFSET_C;
  float rh_offset_pct = DEFAULT_RH_OFFSET_PCT; // added to the onboard humidity, result clamped to 0..100 %RH
  // Offsets of the three registry slots (keys slot1_* to slot3_*), added to the decoded value of the
  // slot by SwitchBotRegistry::calibrated(). The humidity result is clamped to 0..100 %RH.
  float slot_temp_offset_c[SLOT_COUNT] = {DEFAULT_SLOT_TEMP_OFFSET_C, DEFAULT_SLOT_TEMP_OFFSET_C,
                                          DEFAULT_SLOT_TEMP_OFFSET_C};
  float slot_rh_offset_pct[SLOT_COUNT] = {DEFAULT_SLOT_RH_OFFSET_PCT, DEFAULT_SLOT_RH_OFFSET_PCT,
                                          DEFAULT_SLOT_RH_OFFSET_PCT};
  float freeze_alert_limit_c = DEFAULT_FREEZE_ALERT_LIMIT_C;
  uint8_t mold_alert_limit_rh = DEFAULT_MOLD_ALERT_LIMIT_RH;
  uint8_t chime_volume_pct = DEFAULT_CHIME_VOLUME_PCT;
  uint8_t quiet_hours_start_hour = DEFAULT_QUIET_HOURS_START_HOUR;
  uint8_t quiet_hours_end_hour = DEFAULT_QUIET_HOURS_END_HOUR;
  float mic_spl_offset_db = DEFAULT_MIC_SPL_OFFSET_DB; // added to the microphone calibration offset
  uint8_t log_retention_months = DEFAULT_LOG_RETENTION_MONTHS; // log files older than this are deleted
  uint8_t noise_alert_limit_db = DEFAULT_NOISE_ALERT_LIMIT_DB; // 0 = off, else 40..100 dB
  uint8_t chime_alert_mask = DEFAULT_CHIME_ALERT_MASK; // bit 0 freezer, 1 humidity, 2 noise, 3 sensor lost, 4 charge full, 5 ventilate, 6 sensor battery
  uint8_t outdoor_slot = DEFAULT_OUTDOOR_SLOT; // registry slot (1..3) of the outdoor sensor for the ventilation advice, 0 = off
  uint8_t ventilate_limit_rh = DEFAULT_VENTILATE_LIMIT_RH; // a tile above this %RH shows the ventilation badge, 50..80
  uint8_t sensor_battery_limit_pct = DEFAULT_SENSOR_BATTERY_LIMIT_PCT; // an external sensor at or below this % shows BAT LOW, 0 = off, else 1..50
  uint8_t ntp_sync_interval_h = DEFAULT_NTP_SYNC_INTERVAL_H; // hours between two successful NTP syncs, 6..168
  uint16_t wake_interval_s = DEFAULT_WAKE_INTERVAL_S; // seconds from one scan start to the next on battery power, 30..900
  uint16_t sensor_stale_min = DEFAULT_SENSOR_STALE_MIN; // minutes without a packet until a sensor is stale, 5..240
  uint8_t display_invert = DEFAULT_DISPLAY_INVERT; // 1 inverts the framebuffer before each flush, 0..1
  int16_t battery_offset_mv = DEFAULT_BATTERY_OFFSET_MV; // corrects divider and ADC tolerance, -300..300 mV
  uint8_t climate_alert_tiles = DEFAULT_CLIMATE_ALERT_TILES; // bit 0 onboard, bits 1..3 slots 1..3: tiles of the freezer and mold alerts, 0..15

  // Stale timeout in ms for every SwitchBotReading::isStale call. A value below one wake interval plus
  // the scan duration would mark each sensor stale between two scans, so the sum is the lower bound.
  uint32_t staleTimeoutMs() const {
    const uint32_t configured = static_cast<uint32_t>(sensor_stale_min) * 60000UL;
    const uint32_t floor_ms = (static_cast<uint32_t>(wake_interval_s) + SCAN_DURATION_S) * 1000UL;
    return configured < floor_ms ? floor_ms : configured;
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

  // True when `mac` is a fixed MAC of a slot other than `except` (pass SLOT_COUNT for none).
  bool macFixedElsewhere(const uint8_t* mac, size_t except) const {
    for (size_t i = 0; i < SLOT_COUNT; ++i) {
      if (i != except && hasSlotMac(i) && memcmp(slot_mac[i], mac, MAC_BYTES) == 0) {
        return true;
      }
    }
    return false;
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
      if (!inRange(slot_temp_offset_c[i], MIN_SLOT_TEMP_OFFSET_C, MAX_SLOT_TEMP_OFFSET_C) ||
          !inRange(slot_rh_offset_pct[i], MIN_SLOT_RH_OFFSET_PCT, MAX_SLOT_RH_OFFSET_PCT)) {
        return false;
      }
    }
    return inRange(temp_offset_c, MIN_TEMP_OFFSET_C, MAX_TEMP_OFFSET_C) &&
           inRange(rh_offset_pct, MIN_RH_OFFSET_PCT, MAX_RH_OFFSET_PCT) &&
           inRange(freeze_alert_limit_c, MIN_FREEZE_ALERT_LIMIT_C, MAX_FREEZE_ALERT_LIMIT_C) &&
           mold_alert_limit_rh >= MIN_MOLD_ALERT_LIMIT_RH && mold_alert_limit_rh <= MAX_MOLD_ALERT_LIMIT_RH &&
           chime_volume_pct <= MAX_CHIME_VOLUME_PCT && quiet_hours_start_hour <= MAX_HOUR &&
           quiet_hours_end_hour <= MAX_HOUR &&
           inRange(mic_spl_offset_db, MIN_MIC_SPL_OFFSET_DB, MAX_MIC_SPL_OFFSET_DB) &&
           log_retention_months <= MAX_LOG_RETENTION_MONTHS && chime_alert_mask <= MAX_CHIME_ALERT_MASK &&
           outdoor_slot <= MAX_OUTDOOR_SLOT &&
           ventilate_limit_rh >= MIN_VENTILATE_LIMIT_RH && ventilate_limit_rh <= MAX_VENTILATE_LIMIT_RH &&
           sensor_battery_limit_pct <= MAX_SENSOR_BATTERY_LIMIT_PCT &&
           (noise_alert_limit_db == 0 ||
            (noise_alert_limit_db >= MIN_NOISE_ALERT_LIMIT_DB && noise_alert_limit_db <= MAX_NOISE_ALERT_LIMIT_DB)) &&
           ntp_sync_interval_h >= MIN_NTP_SYNC_INTERVAL_H && ntp_sync_interval_h <= MAX_NTP_SYNC_INTERVAL_H &&
           wake_interval_s >= MIN_WAKE_INTERVAL_S && wake_interval_s <= MAX_WAKE_INTERVAL_S &&
           sensor_stale_min >= MIN_SENSOR_STALE_MIN && sensor_stale_min <= MAX_SENSOR_STALE_MIN &&
           display_invert <= MAX_DISPLAY_INVERT && climate_alert_tiles <= MAX_CLIMATE_ALERT_TILES &&
           battery_offset_mv >= MIN_BATTERY_OFFSET_MV && battery_offset_mv <= MAX_BATTERY_OFFSET_MV;
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
          hasSlotMac(i)) {
        return false;
      }
    }
    return temp_offset_c == d.temp_offset_c && rh_offset_pct == d.rh_offset_pct && freeze_alert_limit_c == d.freeze_alert_limit_c &&
           mold_alert_limit_rh == d.mold_alert_limit_rh && chime_volume_pct == d.chime_volume_pct &&
           quiet_hours_start_hour == d.quiet_hours_start_hour &&
           quiet_hours_end_hour == d.quiet_hours_end_hour && mic_spl_offset_db == d.mic_spl_offset_db &&
           log_retention_months == d.log_retention_months && noise_alert_limit_db == d.noise_alert_limit_db &&
           chime_alert_mask == d.chime_alert_mask && outdoor_slot == d.outdoor_slot &&
           ventilate_limit_rh == d.ventilate_limit_rh && sensor_battery_limit_pct == d.sensor_battery_limit_pct &&
           ntp_sync_interval_h == d.ntp_sync_interval_h && wake_interval_s == d.wake_interval_s &&
           sensor_stale_min == d.sensor_stale_min && display_invert == d.display_invert &&
           climate_alert_tiles == d.climate_alert_tiles && battery_offset_mv == d.battery_offset_mv;
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
  static constexpr size_t MAX_FILE_SIZE = 4096;
  static constexpr size_t KEY_COUNT = 33;
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
  };
  static constexpr size_t MAX_NUMBER_CHARS = 15;

  // Always resets `out` to the defaults first.
  static ConfigParseResult parse(const char* data, size_t len, DeviceConfig& out) {
    out = DeviceConfig{};
    ConfigParseResult result;
    if ((data == nullptr && len != 0) || len > MAX_FILE_SIZE) {
      return result;
    }
    result.file_ok = true;
    uint64_t seen = 0;
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
    for (uint64_t m = seen; m != 0; m &= m - 1) {
      ++result.keys_set;
    }
    return result;
  }

private:
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

  // Keys onboard_name (index 18) and slot1_name to slot3_name (index 19..21). The value is already
  // trimmed. A rejected value keeps the earlier name. Returns false for any other key.
  static bool parseNameKey(const char* key, size_t key_len, const char* val, size_t val_len, DeviceConfig& cfg,
                           bool& ok, int& index) {
    static const char* const slot_keys[DeviceConfig::SLOT_COUNT] = {"slot1_name", "slot2_name", "slot3_name"};
    char* target = nullptr;
    if (keyIs(key, key_len, "onboard_name")) {
      index = 18;
      target = cfg.onboard_name;
    }
    for (size_t i = 0; target == nullptr && i < DeviceConfig::SLOT_COUNT; ++i) {
      if (keyIs(key, key_len, slot_keys[i])) {
        index = static_cast<int>(19 + i);
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

  // Keys slot1_mac to slot3_mac (index 22..24). An empty value clears the fixed MAC. A malformed
  // value, the all-zero MAC and a MAC that another slot has fixed are rejected; the previous value
  // stays. Returns false for any other key.
  static bool parseMacKey(const char* key, size_t key_len, const char* val, size_t val_len, DeviceConfig& cfg,
                          bool& ok, int& index) {
    static const char* const mac_keys[DeviceConfig::SLOT_COUNT] = {"slot1_mac", "slot2_mac", "slot3_mac"};
    for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
      if (!keyIs(key, key_len, mac_keys[i])) {
        continue;
      }
      index = static_cast<int>(22 + i);
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

  // Keys slot1_temp_offset_c to slot3_temp_offset_c (index 12..14) and slot1_rh_offset_pct to
  // slot3_rh_offset_pct (index 15..17). Returns false for any other key.
  static bool parseSlotKey(const char* key, size_t key_len, const char* val, size_t val_len, DeviceConfig& cfg,
                           bool& ok, int& index) {
    static const char* const temp_keys[DeviceConfig::SLOT_COUNT] = {"slot1_temp_offset_c", "slot2_temp_offset_c",
                                                                    "slot3_temp_offset_c"};
    static const char* const rh_keys[DeviceConfig::SLOT_COUNT] = {"slot1_rh_offset_pct", "slot2_rh_offset_pct",
                                                                  "slot3_rh_offset_pct"};
    for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
      if (keyIs(key, key_len, temp_keys[i])) {
        index = static_cast<int>(12 + i);
        ok = parseFloatIn(val, val_len, DeviceConfig::MIN_SLOT_TEMP_OFFSET_C,
                          DeviceConfig::MAX_SLOT_TEMP_OFFSET_C, cfg.slot_temp_offset_c[i]);
        return true;
      }
      if (keyIs(key, key_len, rh_keys[i])) {
        index = static_cast<int>(15 + i);
        ok = parseFloatIn(val, val_len, DeviceConfig::MIN_SLOT_RH_OFFSET_PCT,
                          DeviceConfig::MAX_SLOT_RH_OFFSET_PCT, cfg.slot_rh_offset_pct[i]);
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

  static void applyLine(const char* line, size_t n, uint16_t line_no, DeviceConfig& cfg, uint64_t& seen,
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
    } else if (keyIs(key, key_len, "outdoor_slot")) {
      index = 26;
      ok = parseIntIn(val, val_len, 0, DeviceConfig::MAX_OUTDOOR_SLOT, cfg.outdoor_slot);
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
    } else if (parseNameKey(key, key_len, val, val_len, cfg, ok, index)) {
      // index and ok set by parseNameKey
    } else if (parseMacKey(key, key_len, val, val_len, cfg, ok, index)) {
      // index and ok set by parseMacKey
    }
    if (index < 0) {
      return; // unknown key
    }
    if (ok) {
      seen = seen | (1ull << index);
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
  static constexpr size_t MAX_SIZE = 896;

  // Returns the text length, or 0 with an empty string when `cap` is too small.
  static size_t format(const DeviceConfig& cfg, char* out, size_t cap) {
    if (out == nullptr || cap == 0) {
      return 0;
    }
    out[0] = '\0';
    size_t pos = 0;
    bool ok = true;
    ok = ok && putFloat(out, cap, pos, "temp_offset_c", cfg.temp_offset_c);
    ok = ok && putFloat(out, cap, pos, "rh_offset_pct", cfg.rh_offset_pct);
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
    ok = ok && putInt(out, cap, pos, "outdoor_slot", cfg.outdoor_slot);
    ok = ok && putInt(out, cap, pos, "ventilate_limit_rh", cfg.ventilate_limit_rh);
    ok = ok && putInt(out, cap, pos, "sensor_battery_limit_pct", cfg.sensor_battery_limit_pct);
    for (size_t i = 0; ok && i < DeviceConfig::SLOT_COUNT; ++i) {
      char key[32];
      snprintf(key, sizeof(key), "slot%u_temp_offset_c", static_cast<unsigned>(i + 1));
      ok = putFloat(out, cap, pos, key, cfg.slot_temp_offset_c[i]);
    }
    for (size_t i = 0; ok && i < DeviceConfig::SLOT_COUNT; ++i) {
      char key[32];
      snprintf(key, sizeof(key), "slot%u_rh_offset_pct", static_cast<unsigned>(i + 1));
      ok = putFloat(out, cap, pos, key, cfg.slot_rh_offset_pct[i]);
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
