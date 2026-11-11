#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

namespace rlcd_panel {

// Settings that the user can change in an optional /sdcard/config.ini. Every field has a default
// and a safe range. The struct has no Arduino dependency, so the host tests build it.
struct DeviceConfig {
  static constexpr float DEFAULT_TEMP_OFFSET_C = -1.9f;
  static constexpr float MIN_TEMP_OFFSET_C = -10.0f;
  static constexpr float MAX_TEMP_OFFSET_C = 10.0f;
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

  float temp_offset_c = DEFAULT_TEMP_OFFSET_C;
  float freeze_alert_limit_c = DEFAULT_FREEZE_ALERT_LIMIT_C;
  uint8_t mold_alert_limit_rh = DEFAULT_MOLD_ALERT_LIMIT_RH;
  uint8_t chime_volume_pct = DEFAULT_CHIME_VOLUME_PCT;
  uint8_t quiet_hours_start_hour = DEFAULT_QUIET_HOURS_START_HOUR;
  uint8_t quiet_hours_end_hour = DEFAULT_QUIET_HOURS_END_HOUR;
  float mic_spl_offset_db = DEFAULT_MIC_SPL_OFFSET_DB; // added to the microphone calibration offset
  uint8_t log_retention_months = DEFAULT_LOG_RETENTION_MONTHS; // log files older than this are deleted
  uint8_t noise_alert_limit_db = DEFAULT_NOISE_ALERT_LIMIT_DB; // 0 = off, else 40..100 dB

  static bool inRange(float v, float lo, float hi) {
    return v >= lo && v <= hi; // false for NaN
  }

  // True when every field lies inside its range. Used for the RTC record check.
  bool valid() const {
    return inRange(temp_offset_c, MIN_TEMP_OFFSET_C, MAX_TEMP_OFFSET_C) &&
           inRange(freeze_alert_limit_c, MIN_FREEZE_ALERT_LIMIT_C, MAX_FREEZE_ALERT_LIMIT_C) &&
           mold_alert_limit_rh >= MIN_MOLD_ALERT_LIMIT_RH && mold_alert_limit_rh <= MAX_MOLD_ALERT_LIMIT_RH &&
           chime_volume_pct <= MAX_CHIME_VOLUME_PCT && quiet_hours_start_hour <= MAX_HOUR &&
           quiet_hours_end_hour <= MAX_HOUR &&
           inRange(mic_spl_offset_db, MIN_MIC_SPL_OFFSET_DB, MAX_MIC_SPL_OFFSET_DB) &&
           log_retention_months <= MAX_LOG_RETENTION_MONTHS &&
           (noise_alert_limit_db == 0 ||
            (noise_alert_limit_db >= MIN_NOISE_ALERT_LIMIT_DB && noise_alert_limit_db <= MAX_NOISE_ALERT_LIMIT_DB));
  }

  bool isDefault() const {
    const DeviceConfig d;
    return temp_offset_c == d.temp_offset_c && freeze_alert_limit_c == d.freeze_alert_limit_c &&
           mold_alert_limit_rh == d.mold_alert_limit_rh && chime_volume_pct == d.chime_volume_pct &&
           quiet_hours_start_hour == d.quiet_hours_start_hour &&
           quiet_hours_end_hour == d.quiet_hours_end_hour && mic_spl_offset_db == d.mic_spl_offset_db &&
           log_retention_months == d.log_retention_months && noise_alert_limit_db == d.noise_alert_limit_db;
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
    uint16_t seen = 0;
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
    for (uint16_t m = seen; m != 0; m = static_cast<uint16_t>(m & (m - 1))) {
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

  static void applyLine(const char* line, size_t n, DeviceConfig& cfg, uint16_t& seen, ConfigParseResult& result) {
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
    }
    if (index < 0) {
      return; // unknown key
    }
    if (ok) {
      seen = static_cast<uint16_t>(seen | (1u << index));
    } else if (result.rejected < 255) {
      ++result.rejected;
    }
  }
};

} // namespace rlcd_panel
