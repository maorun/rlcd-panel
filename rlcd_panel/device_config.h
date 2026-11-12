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
  static constexpr uint8_t DEFAULT_CHIME_ALERT_MASK = 31; // all five alerts may sound
  static constexpr uint8_t MAX_CHIME_ALERT_MASK = 31;
  static constexpr uint8_t DEFAULT_NTP_SYNC_INTERVAL_H = 24;
  static constexpr uint8_t MIN_NTP_SYNC_INTERVAL_H = 6;
  static constexpr uint8_t MAX_NTP_SYNC_INTERVAL_H = 168;

  static constexpr size_t NAME_MAX_CHARS = 12; // longest sensor name; a name buffer adds the terminator

  // Display names of the onboard sensor and of the three registry slots (keys onboard_name and
  // slot1_name to slot3_name). Empty means the default title. A name holds 1 to 12 characters of
  // A-Z, 0-9, space and '-': all of them have a glyph in the 5x7 font.
  char onboard_name[NAME_MAX_CHARS + 1] = {0};
  char slot_name[SLOT_COUNT][NAME_MAX_CHARS + 1] = {{0}, {0}, {0}};

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
  uint8_t chime_alert_mask = DEFAULT_CHIME_ALERT_MASK; // bit 0 freezer, 1 humidity, 2 noise, 3 sensor lost, 4 charge full
  uint8_t ntp_sync_interval_h = DEFAULT_NTP_SYNC_INTERVAL_H; // hours between two successful NTP syncs, 6..168

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

  // True when every field lies inside its range. Used for the RTC record check.
  bool valid() const {
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
           (noise_alert_limit_db == 0 ||
            (noise_alert_limit_db >= MIN_NOISE_ALERT_LIMIT_DB && noise_alert_limit_db <= MAX_NOISE_ALERT_LIMIT_DB)) &&
           ntp_sync_interval_h >= MIN_NTP_SYNC_INTERVAL_H && ntp_sync_interval_h <= MAX_NTP_SYNC_INTERVAL_H;
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
      if (slot_temp_offset_c[i] != d.slot_temp_offset_c[i] || slot_rh_offset_pct[i] != d.slot_rh_offset_pct[i]) {
        return false;
      }
    }
    return temp_offset_c == d.temp_offset_c && rh_offset_pct == d.rh_offset_pct && freeze_alert_limit_c == d.freeze_alert_limit_c &&
           mold_alert_limit_rh == d.mold_alert_limit_rh && chime_volume_pct == d.chime_volume_pct &&
           quiet_hours_start_hour == d.quiet_hours_start_hour &&
           quiet_hours_end_hour == d.quiet_hours_end_hour && mic_spl_offset_db == d.mic_spl_offset_db &&
           log_retention_months == d.log_retention_months && noise_alert_limit_db == d.noise_alert_limit_db &&
           chime_alert_mask == d.chime_alert_mask &&
           ntp_sync_interval_h == d.ntp_sync_interval_h;
  }
};

// Outcome of one parse. `file_ok` is false for a file above the size limit; the config then keeps
// all defaults. `keys_set` counts distinct keys with an accepted value. `rejected` counts lines
// that were malformed or out of range; the previous value of their key stays.
struct ConfigParseResult {
  bool file_ok = false;
  uint8_t keys_set = 0;
  uint8_t rejected = 0;
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
  static constexpr size_t KEY_COUNT = 9;
  static constexpr size_t MAX_NUMBER_CHARS = 15;

  // Always resets `out` to the defaults first.
  static ConfigParseResult parse(const char* data, size_t len, DeviceConfig& out) {
    out = DeviceConfig{};
    ConfigParseResult result;
    if ((data == nullptr && len != 0) || len > MAX_FILE_SIZE) {
      return result;
    }
    result.file_ok = true;
    uint32_t seen = 0;
    size_t pos = 0;
    if (len >= 3 && static_cast<uint8_t>(data[0]) == 0xEF && static_cast<uint8_t>(data[1]) == 0xBB &&
        static_cast<uint8_t>(data[2]) == 0xBF) {
      pos = 3; // UTF-8 byte order mark from a Windows editor
    }
    while (pos < len) {
      size_t end = pos;
      while (end < len && data[end] != '\n') {
        ++end;
      }
      applyLine(data + pos, end - pos, out, seen, result);
      pos = end + 1;
    }
    for (uint32_t m = seen; m != 0; m &= m - 1) {
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

  static bool parseIntIn(const char* s, size_t n, int lo, int hi, uint8_t& out) {
    double v;
    if (!parseNumber(s, n, v) || !(v >= lo && v <= hi) || v != static_cast<double>(static_cast<int>(v))) {
      return false;
    }
    out = static_cast<uint8_t>(static_cast<int>(v));
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

  static void applyLine(const char* line, size_t n, DeviceConfig& cfg, uint32_t& seen, ConfigParseResult& result) {
    trim(line, n);
    if (n == 0 || line[0] == '#' || line[0] == ';') {
      return;
    }
    const char* eq = static_cast<const char*>(memchr(line, '=', n));
    if (eq == nullptr) {
      ++result.rejected;
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
    } else if (keyIs(key, key_len, "rh_offset_pct")) {
      index = 11;
      ok = parseFloatIn(val, val_len, DeviceConfig::MIN_RH_OFFSET_PCT, DeviceConfig::MAX_RH_OFFSET_PCT,
                        cfg.rh_offset_pct);
    } else if (parseSlotKey(key, key_len, val, val_len, cfg, ok, index)) {
      // index and ok set by parseSlotKey
    } else if (parseNameKey(key, key_len, val, val_len, cfg, ok, index)) {
      // index and ok set by parseNameKey
    }
    if (index < 0) {
      return; // unknown key
    }
    if (ok) {
      seen = seen | (1u << index);
    } else if (result.rejected < 255) {
      ++result.rejected;
    }
  }
};

// Writes a DeviceConfig as config.ini text: one "key=value" line for each setting, in the key order
// of the parser. Floats print with at most two decimals and at least one, so DeviceConfigParser
// reads the text back to the same values. The text holds no Wi-Fi setting: DeviceConfig has none.
class DeviceConfigText {
public:
  static constexpr size_t MAX_SIZE = 768;

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

  static bool putInt(char* out, size_t cap, size_t& pos, const char* key, uint8_t value) {
    char text[8];
    snprintf(text, sizeof(text), "%u", static_cast<unsigned>(value));
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

} // namespace rlcd_panel
