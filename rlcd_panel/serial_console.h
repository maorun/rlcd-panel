#pragma once

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "audio_alert.h"
#include "battery_monitor.h"
#include "device_config.h"
#include "drift_latch.h"
#include "heat_latch.h"
#include "stuck_latch.h"
#include "es7210_mic.h"
#include "frame_refresh.h"
#include "pcf85063_rtc.h"
#include "sd_logger.h"
#include "shtc3_sensor.h"
#include "sparkline.h"

namespace rlcd_panel {

// Incremental CRC-32 (IEEE 802.3, polynomial 0xEDB88320). The value agrees with zlib and gzip.
class SerialCrc32 {
public:
  void update(const uint8_t* data, size_t len) {
    for (size_t i = 0; data != nullptr && i < len; ++i) {
      crc_ ^= data[i];
      for (int bit = 0; bit < 8; ++bit) {
        crc_ = (crc_ >> 1) ^ (0xEDB88320U & (0U - (crc_ & 1U)));
      }
    }
  }

  uint32_t value() const {
    return ~crc_;
  }

private:
  uint32_t crc_ = 0xFFFFFFFFU;
};

enum class SerialCommandKind : uint8_t { NONE = 0, LIST, DUMP, INFO, CONFIG, CALIB, STATUS, BEEP, TIME, HISTORY, VERSION, SET, EVENTS, PATTERN, SCAN, MUTE, SCREEN, SALT, RTC_TRIM, WEEK, SLOT_CLEAR, SLOT_SWAP, REJECTS, ACK, IDENTIFY, SNOOZE, NTP_SYNC, FRSI, SELFTEST, SPREAD, TAIL, TODAY, DAYS, RECEPTION, ALERTS };

enum class SerialParseStatus : uint8_t {
  OK = 0,
  EMPTY,    // blank line: ignored without a reply
  UNKNOWN,  // unknown command, extra argument or non-printable byte
  TOO_LONG, // line above MAX_LINE_SIZE
  BAD_ARG,  // CONFIG CHANGED with extra text (CHANGEDX, CHANGED 1); DAYS with 0, above 31, a leading zero, a non-digit, no number or extra text; CONFIG <key> with extra text; CALIB without a reference, with a reference above 10 or with extra text; BEEP and ACK without a bit, above 7 or with extra text; TIME with an argument other than SET, TIME SET with a bad format or date or a year outside 2025 to 2099; EVENTS with a number outside 1 to 16, a leading zero, a non-digit or extra text; PATTERN without a digit, above 2, negative or with extra text; SCAN with extra text; MUTE without a digit, above 1 or with extra text; SNOOZE without a number, above 720, with a non-digit, a sign or extra text; SLOT CLEAR without a slot number, with a number outside 1 to 10 or with extra text; SLOT SWAP without two different slot numbers 1 to 10 or with extra text; SCREEN and IDENTIFY with extra text; NTP SYNC with extra text; FRSI without a room number 0 to 10 and a different wall slot 1 to 10, with a window outside 3 to 24, a leading zero, a non-digit or extra text; SELFTEST with extra text; RECEPTION and ALERTS with extra text; TODAY with a sensor above 10, a leading zero, a non-digit or extra text; SPREAD with a number outside 6 to 24, a leading zero, a non-digit or extra text; TAIL without a number, with a number outside 1 to 72, with a leading zero, a non-digit or extra text; HISTORY with a sensor above 10, a non-digit or extra text; SALT without a reference, with a reference above 10, with a salt other than MGCL2 or with extra text
  BAD_KEY,   // SET without a key of DeviceConfigParser::KEYS (also a missing '='); CONFIG <key> with an unknown key
  BAD_VALUE, // SET with a value that the DeviceConfigParser rules reject
  BAD_NAME, // DUMP without a name, or a name that is not climate_v5_YYYYMM.csv, climate_v4_YYYYMM.csv, climate_v3_YYYYMM.csv, climate_v2_YYYYMM.csv, daily_v1_YYYY.csv to daily_v7_YYYY.csv or events_v1_YYYY.csv
};

constexpr size_t SET_KEY_SIZE = 41;
constexpr size_t SET_VALUE_SIZE = 41;

struct SerialCommand {
  SerialCommandKind kind = SerialCommandKind::NONE;
  char file[SdLogCodec::LOG_NAME_SIZE] = {0};
  uint8_t reference = 0; // CALIB and SALT: 0 onboard, 1 to 10 a slot; BEEP and ACK: alert bit number 0 to 7; PATTERN: pattern number 0 to 2; MUTE: 0 or 1; SNOOZE: minutes 0 to 720 (uint16 in `minutes`); SLOT CLEAR: slot number 1 to 10
  uint16_t minutes = 0; // SNOOZE: length in minutes 0 to 720
  uint8_t frsi_hours = 0; // FRSI: sample window H 3 to 24 (6 without the argument)
  uint8_t reference_b = 0; // SLOT SWAP: second slot number 1 to 10; FRSI: wall slot 1 to 10 (`reference` holds the first, the room sensor 0 to 10)
  bool week_one = false; // WEEK: true for "WEEK <N>"; `reference` then holds the sensor 0 to 10
  bool history_one = false; // HISTORY: true for "HISTORY <N>"; `reference` then holds the sensor 0 to 10
  uint8_t history_hours = 0; // HISTORY: window H 1 to 24 of "HISTORY <N> <H>" (0 without the argument, all hours)
  bool today_one = false; // TODAY: true for "TODAY <N>"; `reference` then holds the sensor 0 to 10
  bool events_newest = false; // EVENTS: true for "EVENTS <N>"; `reference` then holds N 1 to 16
  bool ack_all = false; // ACK: true for "ACK ALL" (every active repeatable alert bit that is not acknowledged)
  bool config_changed = false; // CONFIG: true for "CONFIG CHANGED" (only the keys that differ from the default)
  bool salt_mgcl2 = false; // SALT: true for "SALT N MGCL2" (magnesium chloride reference, no proposal line)
  bool time_set = false; // TIME: true for "TIME SET <time>"; `time` then holds the parsed local time
  RtcTime time;
  char set_key[SET_KEY_SIZE] = {0};     // SET and "CONFIG <key>": key of DeviceConfigParser::KEYS
  char set_value[SET_VALUE_SIZE] = {0}; // SET: value, checked with the parser rules
};

// One log file as the storage reports it.
struct SerialLogEntry {
  char name[SdLogCodec::LOG_NAME_SIZE] = {0};
  uint32_t size = 0;
};

// Line-based parser for the USB serial console. It has no Arduino dependency. The grammar is
// "LIST", "INFO", "CONFIG", "CONFIG <key>", "CONFIG CHANGED", "STATUS", "CALIB 0..10", "SALT 0..10", "SALT 0..10 MGCL2", "FRSI 0..10 0..10", "FRSI 0..10 0..10 3..24", "SPREAD", "SPREAD 6..24", "TODAY", "TODAY 0..10", "TAIL 1..72", "DAYS 1..31", "BEEP 0..7", "ACK 0..7", "TIME", "TIME SET YYYY-MM-DDTHH:MM:SS", "HISTORY", "HISTORY 0..10", "REJECTS", "RECEPTION", "ALERTS", "EVENTS", "EVENTS 1..16", "PATTERN 0..2", "MUTE 0..1", "SLOT CLEAR 1..10", "SLOT SWAP 1..10 1..10", "SCAN", "SELFTEST", "SCREEN", "IDENTIFY", "VERSION", "SET <key>=<value>" and "DUMP climate_v5_YYYYMM.csv" (or the old v4, v3 and v2 names) or "DUMP daily_v1_YYYY.csv|daily_v2_YYYY.csv|daily_v3_YYYY.csv|daily_v4_YYYY.csv|daily_v5_YYYY.csv|daily_v6_YYYY.csv|daily_v7_YYYY.csv" or "DUMP events_v1_YYYY.csv": uppercase commands, single spaces, no path.
class SerialConsole {
public:
  static constexpr size_t MAX_LINE_SIZE = 40;
  static constexpr size_t MAX_LIST_ENTRIES = 48;

  static bool matches(const char* line, size_t len, const char* word) {
    return strlen(word) == len && memcmp(line, word, len) == 0;
  }

  // Sensor argument " <N>" after the command word (N 0 to 10, no leading zero): the number, or -1 for any other text.
  static int parseSensorArgument(const char* line, size_t cmd_len, size_t len) {
    const char* p = line + cmd_len + 1;
    const size_t arg_len = len - cmd_len - 1;
    if (line[cmd_len] == ' ' && arg_len == 1 && p[0] >= '0' && p[0] <= '9') {
      return p[0] - '0';
    }
    if (line[cmd_len] == ' ' && arg_len == 2 && p[0] == '1' && p[1] == '0') {
      return 10;
    }
    return -1;
  }

  // `len` excludes the line end. A trailing CR or LF is tolerated.
  static SerialParseStatus parse(const char* line, size_t len, SerialCommand& out) {
    out = SerialCommand{};
    if (line == nullptr) {
      return SerialParseStatus::EMPTY;
    }
    while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) {
      --len;
    }
    if (len > MAX_LINE_SIZE) {
      return SerialParseStatus::TOO_LONG;
    }
    for (size_t i = 0; i < len; ++i) {
      const unsigned char c = static_cast<unsigned char>(line[i]);
      if (c < 0x20 || c > 0x7E) {
        return SerialParseStatus::UNKNOWN;
      }
    }
    if (len == 0) {
      return SerialParseStatus::EMPTY;
    }
    size_t cmd_len = 0;
    while (cmd_len < len && line[cmd_len] != ' ') {
      ++cmd_len;
    }
    const bool is_list = matches(line, cmd_len, "LIST");
    const bool is_info = matches(line, cmd_len, "INFO");
    const bool is_config = matches(line, cmd_len, "CONFIG");
    const bool is_status = matches(line, cmd_len, "STATUS");
    if (matches(line, cmd_len, "VERSION")) {
      if (cmd_len != len) {
        return SerialParseStatus::UNKNOWN; // VERSION takes no argument
      }
      out.kind = SerialCommandKind::VERSION;
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "SET")) {
      // "SET <key>=<value>": one key of DeviceConfigParser::KEYS and a value that its rules accept
      if (len < cmd_len + 2) {
        return SerialParseStatus::BAD_KEY;
      }
      const char* pair = line + cmd_len + 1;
      const size_t pair_len = len - cmd_len - 1;
      const char* eq = static_cast<const char*>(memchr(pair, '=', pair_len));
      if (eq == nullptr) {
        return SerialParseStatus::BAD_KEY;
      }
      const size_t key_len = static_cast<size_t>(eq - pair);
      const size_t val_len = pair_len - key_len - 1;
      const DeviceConfigParser::SettingCheck check = DeviceConfigParser::checkSetting(pair, key_len, eq + 1, val_len);
      if (check == DeviceConfigParser::SettingCheck::BAD_KEY) {
        return SerialParseStatus::BAD_KEY;
      }
      if (check == DeviceConfigParser::SettingCheck::BAD_VALUE) {
        return SerialParseStatus::BAD_VALUE;
      }
      memcpy(out.set_key, pair, key_len);
      out.set_key[key_len] = '\0';
      memcpy(out.set_value, eq + 1, val_len);
      out.set_value[val_len] = '\0';
      out.kind = SerialCommandKind::SET;
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "PATTERN")) {
      // exactly one digit 0 to 2
      if (len != cmd_len + 2 || line[cmd_len + 1] < '0' || line[cmd_len + 1] > '2') {
        return SerialParseStatus::BAD_ARG;
      }
      out.kind = SerialCommandKind::PATTERN;
      out.reference = static_cast<uint8_t>(line[cmd_len + 1] - '0');
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "IDENTIFY")) {
      if (len != cmd_len) {
        return SerialParseStatus::BAD_ARG; // no argument exists
      }
      out.kind = SerialCommandKind::IDENTIFY;
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "MUTE")) {
      // exactly one digit 0 or 1
      if (len != cmd_len + 2 || line[cmd_len + 1] < '0' || line[cmd_len + 1] > '1') {
        return SerialParseStatus::BAD_ARG;
      }
      out.kind = SerialCommandKind::MUTE;
      out.reference = static_cast<uint8_t>(line[cmd_len + 1] - '0');
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "SNOOZE")) {
      // one to three digits, value 0 to 720
      const size_t digits = len - cmd_len - 1;
      if (len <= cmd_len + 1 || digits > 3 || (digits > 1 && line[cmd_len + 1] == '0')) {
        return SerialParseStatus::BAD_ARG; // one canonical form: no leading zero
      }
      uint16_t value = 0;
      for (size_t i = 0; i < digits; ++i) {
        const char d = line[cmd_len + 1 + i];
        if (d < '0' || d > '9') {
          return SerialParseStatus::BAD_ARG;
        }
        value = static_cast<uint16_t>(value * 10 + static_cast<uint16_t>(d - '0'));
      }
      if (value > Snooze::MAX_MINUTES) {
        return SerialParseStatus::BAD_ARG;
      }
      out.kind = SerialCommandKind::SNOOZE;
      out.minutes = value;
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "SLOT")) {
      // exactly "SLOT CLEAR <N>" or "SLOT SWAP <A> <B>" with numbers 1 to 10
      static const char clear_prefix[] = "SLOT CLEAR";
      static const char swap_prefix[] = "SLOT SWAP";
      const size_t clear_len = sizeof(clear_prefix) - 1;
      const size_t swap_len = sizeof(swap_prefix) - 1;
      const bool is_clear = len >= clear_len && memcmp(line, clear_prefix, clear_len) == 0 &&
                            (len == clear_len || line[clear_len] == ' ');
      const bool is_swap = len >= swap_len && memcmp(line, swap_prefix, swap_len) == 0 &&
                           (len == swap_len || line[swap_len] == ' ');
      if (!is_clear && !is_swap) {
        return SerialParseStatus::UNKNOWN; // only "SLOT CLEAR" and "SLOT SWAP" exist (a bare "SLOT" is no known command, like a bare "RTC")
      }
      const size_t prefix_len = is_clear ? clear_len : swap_len;
      if (len <= prefix_len + 1 || line[prefix_len] != ' ') {
        return SerialParseStatus::BAD_ARG;
      }
      const char* p = line + prefix_len + 1;
      size_t remaining = len - (prefix_len + 1);
      int numbers[2] = {0, 0};
      const size_t wanted = is_clear ? 1 : 2;
      for (size_t n = 0; n < wanted; ++n) {
        size_t digits = 0;
        if (remaining >= 2 && p[0] == '1' && p[1] == '0') {
          numbers[n] = 10;
          digits = 2;
        } else if (remaining >= 1 && p[0] >= '1' && p[0] <= '9') {
          numbers[n] = p[0] - '0';
          digits = 1;
        } else {
          return SerialParseStatus::BAD_ARG;
        }
        p += digits;
        remaining -= digits;
        if (n + 1 < wanted) {
          if (remaining < 2 || p[0] != ' ') {
            return SerialParseStatus::BAD_ARG;
          }
          ++p;
          --remaining;
        }
      }
      if (remaining != 0 || (is_swap && numbers[0] == numbers[1])) {
        return SerialParseStatus::BAD_ARG;
      }
      out.kind = is_clear ? SerialCommandKind::SLOT_CLEAR : SerialCommandKind::SLOT_SWAP;
      out.reference = static_cast<uint8_t>(numbers[0]);
      out.reference_b = static_cast<uint8_t>(numbers[1]);
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "FRSI")) {
      // "FRSI <R> <W>" or "FRSI <R> <W> <H>": R 0 to 10 (room), W 1 to 10 (wall slot), two different numbers, no leading zero;
      // H 3 to 24 (sample window, no leading zero, default 6)
      if (len <= cmd_len + 1 || line[cmd_len] != ' ') {
        return SerialParseStatus::BAD_ARG;
      }
      const char* p = line + cmd_len + 1;
      size_t remaining = len - cmd_len - 1;
      int numbers[2] = {0, 0};
      for (size_t n = 0; n < 2; ++n) {
        size_t digits = 0;
        if (remaining >= 2 && p[0] == '1' && p[1] == '0') {
          numbers[n] = 10;
          digits = 2;
        } else if (remaining >= 1 && p[0] >= '0' && p[0] <= '9') {
          numbers[n] = p[0] - '0';
          digits = 1;
        } else {
          return SerialParseStatus::BAD_ARG;
        }
        p += digits;
        remaining -= digits;
        if (n == 0) {
          if (remaining < 2 || p[0] != ' ') {
            return SerialParseStatus::BAD_ARG;
          }
          ++p;
          --remaining;
        }
      }
      int hours = 6;
      if (remaining != 0) {
        // one more number: a space, then 1 or 2 digits without a leading zero
        if (remaining < 2 || remaining > 3 || p[0] != ' ' || p[1] < '1' || p[1] > '9') {
          return SerialParseStatus::BAD_ARG;
        }
        hours = p[1] - '0';
        if (remaining == 3) {
          if (p[2] < '0' || p[2] > '9') {
            return SerialParseStatus::BAD_ARG;
          }
          hours = hours * 10 + (p[2] - '0');
        }
        if (hours < 3 || hours > 24) {
          return SerialParseStatus::BAD_ARG;
        }
      }
      if (numbers[0] == numbers[1] || numbers[1] == 0) {
        return SerialParseStatus::BAD_ARG; // the wall sensor is a slot (1 to 10), never the onboard sensor
      }
      out.kind = SerialCommandKind::FRSI;
      out.reference = static_cast<uint8_t>(numbers[0]);
      out.reference_b = static_cast<uint8_t>(numbers[1]);
      out.frsi_hours = static_cast<uint8_t>(hours);
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "SCAN")) {
      if (cmd_len != len) {
        return SerialParseStatus::BAD_ARG; // SCAN takes no argument
      }
      out.kind = SerialCommandKind::SCAN;
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "SPREAD")) {
      // "SPREAD" (6 hours) or "SPREAD <H>", H 6 to 24, no leading zero
      unsigned hours = static_cast<unsigned>(SpreadMath::HOURS);
      if (cmd_len != len) {
        const size_t digits = len - cmd_len - 1;
        if (line[cmd_len] != ' ' || digits < 1 || digits > 2 || (digits > 1 && line[cmd_len + 1] == '0')) {
          return SerialParseStatus::BAD_ARG;
        }
        hours = 0;
        for (size_t i = 0; i < digits; ++i) {
          const char d = line[cmd_len + 1 + i];
          if (d < '0' || d > '9') {
            return SerialParseStatus::BAD_ARG;
          }
          hours = hours * 10 + static_cast<unsigned>(d - '0');
        }
        if (hours < SpreadMath::HOURS || hours > SpreadMath::MAX_HOURS) {
          return SerialParseStatus::BAD_ARG;
        }
      }
      out.kind = SerialCommandKind::SPREAD;
      out.reference = static_cast<uint8_t>(hours);
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "TODAY")) {
      // "TODAY" or "TODAY <N>", N 0 to 10, no leading zero
      out.kind = SerialCommandKind::TODAY;
      if (cmd_len == len) {
        return SerialParseStatus::OK;
      }
      const int sensor = parseSensorArgument(line, cmd_len, len);
      if (sensor < 0) {
        out = SerialCommand{};
        return SerialParseStatus::BAD_ARG;
      }
      out.today_one = true;
      out.reference = static_cast<uint8_t>(sensor);
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "TAIL")) {
      // exactly "TAIL <N>", N 1 to 72, no leading zero
      const size_t digits = len - cmd_len - 1;
      if (len <= cmd_len + 1 || line[cmd_len] != ' ' || digits > 2 || (digits > 1 && line[cmd_len + 1] == '0')) {
        return SerialParseStatus::BAD_ARG;
      }
      unsigned value = 0;
      for (size_t i = 0; i < digits; ++i) {
        const char d = line[cmd_len + 1 + i];
        if (d < '0' || d > '9') {
          return SerialParseStatus::BAD_ARG;
        }
        value = value * 10 + static_cast<unsigned>(d - '0');
      }
      if (value < 1 || value > SdLogCodec::TAIL_MAX_ROWS) {
        return SerialParseStatus::BAD_ARG;
      }
      out.kind = SerialCommandKind::TAIL;
      out.reference = static_cast<uint8_t>(value);
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "DAYS")) {
      // exactly "DAYS <N>", N 1 to 31, no leading zero
      const size_t digits = len - cmd_len - 1;
      if (len <= cmd_len + 1 || line[cmd_len] != ' ' || digits > 2 || (digits > 1 && line[cmd_len + 1] == '0')) {
        return SerialParseStatus::BAD_ARG;
      }
      unsigned value = 0;
      for (size_t i = 0; i < digits; ++i) {
        const char d = line[cmd_len + 1 + i];
        if (d < '0' || d > '9') {
          return SerialParseStatus::BAD_ARG;
        }
        value = value * 10 + static_cast<unsigned>(d - '0');
      }
      if (value < 1 || value > SdLogCodec::DAYS_MAX_ROWS) {
        return SerialParseStatus::BAD_ARG;
      }
      out.kind = SerialCommandKind::DAYS;
      out.reference = static_cast<uint8_t>(value);
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "SELFTEST")) {
      if (cmd_len != len) {
        return SerialParseStatus::BAD_ARG; // SELFTEST takes no argument
      }
      out.kind = SerialCommandKind::SELFTEST;
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "SCREEN")) {
      if (cmd_len != len) {
        return SerialParseStatus::BAD_ARG; // SCREEN takes no argument
      }
      out.kind = SerialCommandKind::SCREEN;
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "EVENTS")) {
      out.kind = SerialCommandKind::EVENTS;
      if (cmd_len == len) {
        return SerialParseStatus::OK;
      }
      // optional count argument: "EVENTS <N>" with N 1 to 16, no leading zero
      const char* p = line + cmd_len + 1;
      const size_t arg_len = len - cmd_len - 1;
      int count = 0;
      if (line[cmd_len] == ' ' && arg_len >= 1 && arg_len <= 2 && p[0] >= '1' && p[0] <= '9') {
        count = p[0] - '0';
        if (arg_len == 2) {
          count = (p[1] >= '0' && p[1] <= '9') ? count * 10 + (p[1] - '0') : 0;
        }
      }
      if (count < 1 || count > 16) {
        out = SerialCommand{};
        return SerialParseStatus::BAD_ARG;
      }
      out.events_newest = true;
      out.reference = static_cast<uint8_t>(count);
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "WEEK")) {
      out.kind = SerialCommandKind::WEEK;
      if (cmd_len == len) {
        return SerialParseStatus::OK;
      }
      // optional sensor argument: "WEEK <N>" with N 0 to 10, no leading zero (same rule as "TODAY <N>")
      const int sensor = parseSensorArgument(line, cmd_len, len);
      if (sensor < 0) {
        out = SerialCommand{};
        return SerialParseStatus::BAD_ARG;
      }
      out.week_one = true;
      out.reference = static_cast<uint8_t>(sensor);
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "REJECTS")) {
      if (cmd_len != len) {
        return SerialParseStatus::BAD_ARG; // REJECTS takes no argument
      }
      out.kind = SerialCommandKind::REJECTS;
      return SerialParseStatus::OK;
    }
    // The word prefix check (not matches()) makes RECEPTIONX a BAD_ARG, as the roadmap item demands.
    if (cmd_len >= 9 && memcmp(line, "RECEPTION", 9) == 0) {
      if (cmd_len != 9 || len != 9) { // RECEPTIONX (longer word) and RECEPTION <text> both fail
        return SerialParseStatus::BAD_ARG; // RECEPTION takes no argument; a longer word gives BAD_ARG, not UNKNOWN
      }
      out.kind = SerialCommandKind::RECEPTION;
      return SerialParseStatus::OK;
    }
    // Like RECEPTION: the word prefix check makes ALERTSX a BAD_ARG.
    if (cmd_len >= 6 && memcmp(line, "ALERTS", 6) == 0) {
      if (cmd_len != 6 || len != 6) {
        return SerialParseStatus::BAD_ARG; // ALERTS takes no argument
      }
      out.kind = SerialCommandKind::ALERTS;
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "HISTORY")) {
      out.kind = SerialCommandKind::HISTORY;
      if (cmd_len == len) {
        return SerialParseStatus::OK;
      }
      // optional sensor argument "HISTORY <N>" with N 0 to 10, then optional hours "HISTORY <N> <H>" with H 1 to 24
      const char* arg = line + cmd_len + 1;
      const size_t arg_len = len - cmd_len - 1;
      const char* space = line[cmd_len] == ' ' ? static_cast<const char*>(memchr(arg, ' ', arg_len)) : nullptr;
      const size_t sensor_end = space != nullptr ? cmd_len + 1 + static_cast<size_t>(space - arg) : len;
      const int sensor = parseSensorArgument(line, cmd_len, sensor_end);
      if (sensor < 0) {
        out = SerialCommand{};
        return SerialParseStatus::BAD_ARG;
      }
      if (space != nullptr) {
        const char* h = space + 1;
        const size_t h_len = len - sensor_end - 1;
        int hours = -1;
        if (h_len == 1 && h[0] >= '1' && h[0] <= '9') {
          hours = h[0] - '0';
        } else if (h_len == 2 && (h[0] == '1' || h[0] == '2') && h[1] >= '0' && h[1] <= '9') {
          hours = (h[0] - '0') * 10 + (h[1] - '0');
        }
        if (hours < 1 || hours > 24) {
          out = SerialCommand{};
          return SerialParseStatus::BAD_ARG;
        }
        out.history_hours = static_cast<uint8_t>(hours);
      }
      out.history_one = true;
      out.reference = static_cast<uint8_t>(sensor);
      return SerialParseStatus::OK;
    }
    if (is_config && cmd_len < len) {
      // "CONFIG <key>": one key of DeviceConfigParser::KEYS, no further text
      const char* key = line + cmd_len + 1;
      const size_t key_len = len - cmd_len - 1;
      if (key_len == 0) {
        return SerialParseStatus::UNKNOWN;
      }
      if (key_len >= 7 && memcmp(key, "CHANGED", 7) == 0) {
        // "CONFIG CHANGED": no key starts with CHANGED, so any further text is an argument error
        if (key_len != 7) {
          return SerialParseStatus::BAD_ARG;
        }
        out.kind = SerialCommandKind::CONFIG;
        out.config_changed = true;
        return SerialParseStatus::OK;
      }
      if (memchr(key, ' ', key_len) != nullptr) {
        return SerialParseStatus::BAD_ARG;
      }
      bool known = false;
      for (size_t i = 0; !known && i < DeviceConfigParser::KEY_COUNT; ++i) {
        known = matches(key, key_len, DeviceConfigParser::KEYS[i]);
      }
      if (!known) {
        return SerialParseStatus::BAD_KEY;
      }
      memcpy(out.set_key, key, key_len);
      out.set_key[key_len] = '\0';
      out.kind = SerialCommandKind::CONFIG;
      return SerialParseStatus::OK;
    }
    if (is_list || is_info || is_config || is_status) {
      if (cmd_len != len) {
        return SerialParseStatus::UNKNOWN; // these commands take no argument
      }
      out.kind = is_list ? SerialCommandKind::LIST
                         : (is_info ? SerialCommandKind::INFO
                                    : (is_config ? SerialCommandKind::CONFIG : SerialCommandKind::STATUS));
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "CALIB")) {
      if (len <= cmd_len + 1 || line[cmd_len] != ' ') {
        return SerialParseStatus::BAD_ARG;
      }
      const char* p = line + cmd_len + 1;
      const size_t arg_len = len - cmd_len - 1;
      int ref = -1;
      if (arg_len == 1 && p[0] >= '0' && p[0] <= '9') {
        ref = p[0] - '0';
      } else if (arg_len == 2 && p[0] == '1' && p[1] == '0') {
        ref = 10;
      }
      if (ref < 0) {
        return SerialParseStatus::BAD_ARG;
      }
      out.kind = SerialCommandKind::CALIB;
      out.reference = static_cast<uint8_t>(ref);
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "SALT")) {
      if (len <= cmd_len + 1 || line[cmd_len] != ' ') {
        return SerialParseStatus::BAD_ARG;
      }
      const char* p = line + cmd_len + 1;
      size_t rem = len - cmd_len - 1;
      bool with_salt = false;
      if (rem >= 6 && memcmp(p + rem - 6, " MGCL2", 6) == 0) {
        with_salt = true;
        rem -= 6;
      }
      int ref = -1;
      if (rem == 1 && p[0] >= '0' && p[0] <= '9') {
        ref = p[0] - '0';
      } else if (rem == 2 && p[0] == '1' && p[1] == '0') {
        ref = 10;
      }
      if (ref < 0) {
        return SerialParseStatus::BAD_ARG;
      }
      out.salt_mgcl2 = with_salt;
      out.kind = SerialCommandKind::SALT;
      out.reference = static_cast<uint8_t>(ref);
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "DUMP")) {
      if (cmd_len == len || len - cmd_len < 2) {
        return SerialParseStatus::BAD_NAME;
      }
      const char* name = line + cmd_len + 1;
      const size_t name_len = len - cmd_len - 1;
      if (name_len + 1 > sizeof(out.file)) {
        return SerialParseStatus::BAD_NAME;
      }
      char copy[SdLogCodec::LOG_NAME_SIZE];
      memcpy(copy, name, name_len);
      copy[name_len] = '\0';
      if (!SdLogCodec::isConsoleFileName(copy)) {
        return SerialParseStatus::BAD_NAME;
      }
      memcpy(out.file, copy, name_len + 1);
      out.kind = SerialCommandKind::DUMP;
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "BEEP")) {
      // exactly one digit 0 to 7 (the bit number of ALERT_FREEZER to ALERT_HEAT)
      if (len != cmd_len + 2 || line[cmd_len + 1] < '0' || line[cmd_len + 1] > '7') {
        return SerialParseStatus::BAD_ARG;
      }
      out.kind = SerialCommandKind::BEEP;
      out.reference = static_cast<uint8_t>(line[cmd_len + 1] - '0');
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "ACK")) {
      if (len > cmd_len + 1 && line[cmd_len + 1] == 'A') {
        if (len != cmd_len + 4 || memcmp(line + cmd_len + 1, "ALL", 3) != 0) {
          return SerialParseStatus::BAD_ARG; // only "ACK ALL" exists
        }
        out.kind = SerialCommandKind::ACK;
        out.ack_all = true;
        return SerialParseStatus::OK;
      }
      // exactly one digit 0 to 7 (the bit number of ALERT_FREEZER to ALERT_HEAT)
      if (len != cmd_len + 2 || line[cmd_len + 1] < '0' || line[cmd_len + 1] > '7') {
        return SerialParseStatus::BAD_ARG;
      }
      out.kind = SerialCommandKind::ACK;
      out.reference = static_cast<uint8_t>(line[cmd_len + 1] - '0');
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "NTP")) {
      static const char ntp_prefix[] = "NTP SYNC";
      const size_t ntp_len = sizeof(ntp_prefix) - 1;
      if (len < ntp_len || memcmp(line, ntp_prefix, ntp_len) != 0 || (len > ntp_len && line[ntp_len] != ' ')) {
        return SerialParseStatus::UNKNOWN; // only "NTP SYNC" exists
      }
      if (len != ntp_len) {
        return SerialParseStatus::BAD_ARG; // NTP SYNC takes no argument
      }
      out.kind = SerialCommandKind::NTP_SYNC;
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "RTC")) {
      if (len != 8 || memcmp(line, "RTC TRIM", 8) != 0) {
        return SerialParseStatus::UNKNOWN; // only "RTC TRIM" exists
      }
      out.kind = SerialCommandKind::RTC_TRIM;
      return SerialParseStatus::OK;
    }
    if (matches(line, cmd_len, "TIME")) {
      out.kind = SerialCommandKind::TIME;
      if (cmd_len == len) {
        return SerialParseStatus::OK; // read the clock
      }
      static const char prefix[] = "TIME SET ";
      const size_t prefix_len = sizeof(prefix) - 1;
      if (len != prefix_len + TIME_TEXT_SIZE || memcmp(line, prefix, prefix_len) != 0 ||
          !parseTime(line + prefix_len, out.time)) {
        out = SerialCommand{};
        return SerialParseStatus::BAD_ARG;
      }
      out.time_set = true;
      return SerialParseStatus::OK;
    }
    return SerialParseStatus::UNKNOWN;
  }

  // "YYYY-MM-DDTHH:MM:SS" (19 characters): a real calendar date from 2025 to 2099 and a time of day.
  static constexpr size_t TIME_TEXT_SIZE = 19;
  static constexpr uint16_t MIN_SET_YEAR = 2025;
  static constexpr uint16_t MAX_SET_YEAR = 2099;

  static bool parseTime(const char* text, RtcTime& out) {
    static const char shape[] = "dddd-dd-ddTdd:dd:dd";
    for (size_t i = 0; i < TIME_TEXT_SIZE; ++i) {
      const bool digit = text[i] >= '0' && text[i] <= '9';
      if (shape[i] == 'd' ? !digit : text[i] != shape[i]) {
        return false;
      }
    }
    auto num = [text](size_t at, size_t n) {
      unsigned v = 0;
      for (size_t i = 0; i < n; ++i) {
        v = v * 10 + static_cast<unsigned>(text[at + i] - '0');
      }
      return v;
    };
    RtcTime t;
    t.year = static_cast<uint16_t>(num(0, 4));
    t.month = static_cast<uint8_t>(num(5, 2));
    t.day = static_cast<uint8_t>(num(8, 2));
    t.hour = static_cast<uint8_t>(num(11, 2));
    t.minute = static_cast<uint8_t>(num(14, 2));
    t.second = static_cast<uint8_t>(num(17, 2));
    if (t.year < MIN_SET_YEAR || t.year > MAX_SET_YEAR || t.month < 1 || t.month > 12 || t.day < 1 ||
        t.day > Pcf85063Codec::daysInMonth(t.year, t.month) || t.hour > 23 || t.minute > 59 || t.second > 59) {
      return false;
    }
    t.weekday = Pcf85063Codec::computeWeekday(t.year, t.month, t.day);
    t.valid = true;
    out = t;
    return true;
  }

  static const char* errorLine(SerialParseStatus status) {
    switch (status) {
      case SerialParseStatus::UNKNOWN: return "ERR UNKNOWN\n";
      case SerialParseStatus::TOO_LONG: return "ERR TOO_LONG\n";
      case SerialParseStatus::BAD_NAME: return "ERR BAD_NAME\n";
      case SerialParseStatus::BAD_ARG: return "ERR BAD_ARG\n";
      case SerialParseStatus::BAD_KEY: return "ERR BAD_KEY\n";
      case SerialParseStatus::BAD_VALUE: return "ERR BAD_VALUE\n";
      case SerialParseStatus::OK:
      case SerialParseStatus::EMPTY:
      default: return "";
    }
  }
};

// Collects serial bytes into lines. A line above MAX_LINE_SIZE gives LINE_TOO_LONG at its line end;
// the whole line is dropped, so its tail never runs as a command.
class SerialLineAssembler {
public:
  enum class Event : uint8_t { NONE = 0, LINE, LINE_TOO_LONG };

  Event feed(uint8_t byte) {
    if (done_) {
      size_ = 0;
      overflow_ = false;
      done_ = false;
    }
    if (byte == '\r') {
      return Event::NONE;
    }
    if (byte == '\n') {
      done_ = true;
      buffer_[size_] = '\0';
      return overflow_ ? Event::LINE_TOO_LONG : Event::LINE;
    }
    if (size_ < SerialConsole::MAX_LINE_SIZE) {
      buffer_[size_++] = static_cast<char>(byte);
    } else {
      overflow_ = true;
    }
    return Event::NONE;
  }

  // Valid after a LINE event, until the next feed().
  const char* line() const {
    return buffer_;
  }
  size_t length() const {
    return size_;
  }

private:
  char buffer_[SerialConsole::MAX_LINE_SIZE + 1] = {0};
  size_t size_ = 0;
  bool overflow_ = false;
  bool done_ = false;
};

// Output sink of the BLE console: collects the reply bytes and sends them in notifications of
// max(1, mtu - 3) bytes, in the same byte order as on USB serial. Only the last chunk of a reply is
// shorter; flush() sends it. Notifier: bool notify(const uint8_t*, size_t); false means that the
// central is gone. The BLE stack gives no result per notification: a drop inside the stack shows only in
// the CRC32 line, which the central checks. A failed notification ends the reply (short write count),
// like a short USB write: the host sees no CRC32 line.
// ATT default MTU: payload 20 bytes until the central negotiates a larger one.
constexpr uint16_t BLE_CONSOLE_DEFAULT_MTU = 23;

template <typename Notifier>
class BleChunkSink {
public:
  static constexpr uint16_t DEFAULT_MTU = BLE_CONSOLE_DEFAULT_MTU;
  static constexpr uint16_t MAX_MTU = 517;
  static constexpr size_t BUFFER_SIZE = MAX_MTU;

  explicit BleChunkSink(Notifier& notifier, uint16_t mtu = DEFAULT_MTU) : notifier_(notifier) {
    setMtu(mtu);
  }

  static size_t payloadSize(uint16_t mtu) {
    return mtu > 3 ? static_cast<size_t>(mtu) - 3 : 1;
  }

  void setMtu(uint16_t mtu) {
    payload_ = payloadSize(mtu > MAX_MTU ? MAX_MTU : mtu);
  }

  size_t write(const uint8_t* data, size_t len) {
    size_t taken = 0;
    while (taken < len && !failed_) {
      const size_t room = payload_ - used_;
      const size_t n = len - taken < room ? len - taken : room;
      memcpy(buffer_ + used_, data + taken, n);
      used_ += n;
      taken += n;
      if (used_ == payload_) {
        send();
      }
    }
    return taken;
  }

  // Sends the rest of the reply. Returns false when any notification of the reply failed.
  bool flush() {
    if (used_ > 0 && !failed_) {
      send();
    }
    const bool ok = !failed_;
    used_ = 0;
    failed_ = false;
    return ok;
  }

private:
  void send() {
    if (!notifier_.notify(buffer_, used_)) {
      failed_ = true;
    }
    used_ = 0;
  }

  Notifier& notifier_;
  size_t payload_ = payloadSize(DEFAULT_MTU);
  size_t used_ = 0;
  bool failed_ = false;
  uint8_t buffer_[BUFFER_SIZE] = {0};
};

// One check of the console command SELFTEST: a status and one detail word (no space, at most 23 characters).
struct SelfTestOutcome {
  enum class Status : uint8_t { OK = 0, FAIL, SKIP };
  Status status = Status::FAIL;
  char detail[24] = {0};

  static SelfTestOutcome of(Status status, const char* detail) {
    SelfTestOutcome o;
    o.status = status;
    snprintf(o.detail, sizeof(o.detail), "%s", detail);
    return o;
  }
  static const char* label(Status status) {
    return status == Status::OK ? "OK" : (status == Status::FAIL ? "FAIL" : "SKIP");
  }
};

// Pure verdicts of the SELFTEST checks. The sketch reads the hardware and passes the result in; the host tests pass fixed values.
struct SelfTestChecks {
  static constexpr int32_t BATTERY_MIN_MV = 2500;
  static constexpr int32_t BATTERY_MAX_MV = 4400;
  static constexpr uint32_t MIC_WINDOW_MS = 1000;
  static constexpr uint32_t MIN_SCANS = 12; // same as PersistentState::RECEPTION_LOW_MIN_SCANS

  // `retried` is true when the first read failed and the retry ran (readShtc3WithRetry).
  static SelfTestOutcome shtc3(Shtc3Result result, bool retried) {
    switch (result) {
      case Shtc3Result::OK:
        return SelfTestOutcome::of(SelfTestOutcome::Status::OK, retried ? "RETRIED" : "READ");
      case Shtc3Result::NACK: return SelfTestOutcome::of(SelfTestOutcome::Status::FAIL, "NACK");
      case Shtc3Result::SHORT: return SelfTestOutcome::of(SelfTestOutcome::Status::FAIL, "SHORT");
      case Shtc3Result::CRC:
      default: return SelfTestOutcome::of(SelfTestOutcome::Status::FAIL, "CRC");
    }
  }

  // `readTime` result and the oscillator stop flag of the PCF85063A; the check reads only.
  static SelfTestOutcome rtc(bool present, bool read_ok, bool oscillator_stopped) {
    if (!present) {
      return SelfTestOutcome::of(SelfTestOutcome::Status::FAIL, "NO_RTC");
    }
    if (oscillator_stopped) {
      return SelfTestOutcome::of(SelfTestOutcome::Status::FAIL, "OSC_STOPPED");
    }
    if (!read_ok) {
      return SelfTestOutcome::of(SelfTestOutcome::Status::FAIL, "READ_FAILED");
    }
    return SelfTestOutcome::of(SelfTestOutcome::Status::OK, "RUNNING");
  }

  // `free_mb` is -1 for an unknown size.
  static SelfTestOutcome card(bool mounted, int32_t free_mb) {
    if (!mounted) {
      return SelfTestOutcome::of(SelfTestOutcome::Status::FAIL, "NO_CARD");
    }
    if (free_mb < 0) {
      return SelfTestOutcome::of(SelfTestOutcome::Status::FAIL, "SIZE_UNKNOWN");
    }
    char detail[24];
    snprintf(detail, sizeof(detail), "free_mb=%ld", static_cast<long>(free_mb));
    return SelfTestOutcome::of(SelfTestOutcome::Status::OK, detail);
  }

  static SelfTestOutcome battery(int32_t millivolts) {
    char detail[24];
    snprintf(detail, sizeof(detail), "mv=%ld", static_cast<long>(millivolts));
    const bool ok = millivolts >= BATTERY_MIN_MV && millivolts <= BATTERY_MAX_MV;
    return SelfTestOutcome::of(ok ? SelfTestOutcome::Status::OK : SelfTestOutcome::Status::FAIL, detail);
  }

  // BLE reception of the occupied registry slots from the RTC copy of ReceptionHistory. `limit_pct` is reception_low_pct:
  // 0 gives SKIP OFF. Only slots with a valid reading and at least MIN_SCANS (12, RECEPTION_LOW_MIN_SCANS) counted scans
  // count (SKIP NO_SLOT without an occupied slot, SKIP FEW_SCANS without a counted one). A rate below the limit
  // (received * 100 < limit * scans) gives FAIL LOW=<first slot 1..5>, else OK <lowest rate, rounded down>%.
  static SelfTestOutcome reception(const SwitchBotRegistry& registry, const ReceptionHistory& history, uint8_t limit_pct) {
    if (limit_pct == 0) {
      return SelfTestOutcome::of(SelfTestOutcome::Status::SKIP, "OFF");
    }
    bool occupied = false;
    int first_low = 0;
    bool counted = false;
    uint32_t low_received = 0;
    uint32_t low_scans = 1;
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (!registry.slot(i).valid) {
        continue;
      }
      occupied = true;
      const uint32_t scans = history.scans(i);
      if (scans < MIN_SCANS) {
        continue;
      }
      const uint32_t received = history.received(i);
      if (first_low == 0 && received * 100U < static_cast<uint32_t>(limit_pct) * scans) {
        first_low = static_cast<int>(i) + 1;
      }
      if (!counted || received * low_scans < low_received * scans) {
        low_received = received;
        low_scans = scans;
      }
      counted = true;
    }
    if (!occupied) {
      return SelfTestOutcome::of(SelfTestOutcome::Status::SKIP, "NO_SLOT");
    }
    if (!counted) {
      return SelfTestOutcome::of(SelfTestOutcome::Status::SKIP, "FEW_SCANS");
    }
    char detail[24];
    if (first_low != 0) {
      snprintf(detail, sizeof(detail), "LOW=%d", first_low);
      return SelfTestOutcome::of(SelfTestOutcome::Status::FAIL, detail);
    }
    snprintf(detail, sizeof(detail), "%u%%", static_cast<unsigned>(low_received * 100U / low_scans));
    return SelfTestOutcome::of(SelfTestOutcome::Status::OK, detail);
  }

  // One window of the StereoAnalyzer: both channels must be OK and within the imbalance limit.
  static SelfTestOutcome microphone(const NoiseReading& reading) {
    if (!reading.mic.valid) {
      return SelfTestOutcome::of(SelfTestOutcome::Status::FAIL, "NO_DATA");
    }
    char detail[24];
    snprintf(detail, sizeof(detail), "L=%s,R=%s", MicSelfTest::label(reading.mic.channel[0]),
             MicSelfTest::label(reading.mic.channel[1]));
    if (!reading.mic.channelOk(0) || !reading.mic.channelOk(1)) {
      return SelfTestOutcome::of(SelfTestOutcome::Status::FAIL, detail);
    }
    if (reading.mic.imbalanced()) {
      snprintf(detail, sizeof(detail), "IMBALANCE=%udB", static_cast<unsigned>(reading.mic.imbalance_db));
      return SelfTestOutcome::of(SelfTestOutcome::Status::FAIL, detail);
    }
    return SelfTestOutcome::of(SelfTestOutcome::Status::OK, detail);
  }

  // One window with the injected ADC. NoiseSampler::measure powers the ADC and the bus down on each path.
  template <typename Hal>
  static SelfTestOutcome microphoneWindow(Hal& hal) {
    return microphone(NoiseSampler::measure(hal, NoiseBand::QUIET, MIC_WINDOW_MS));
  }

  static SelfTestOutcome bleScan(bool started) {
    return started ? SelfTestOutcome::of(SelfTestOutcome::Status::OK, "SCAN")
                   : SelfTestOutcome::of(SelfTestOutcome::Status::FAIL, "START_FAILED");
  }
};

// Line side of the BLE console. The RX characteristic feeds feed(); a complete line goes through
// the same SerialConsole parser as on USB. The bridge only decides what a central may run:
//   ble_console 0: nothing (the service does not run)
//   ble_console 1: the read commands; SET, MUTE, SNOOZE, ACK, SLOT CLEAR, TIME SET and RTC TRIM reply "ERR READ_ONLY"
//   ble_console 2: also SET, MUTE, SNOOZE, ACK, SLOT CLEAR, TIME SET and RTC TRIM
//   DUMP (a monthly file is too large for BLE), SCREEN (15,000 bytes), SCAN (the scan stops the BLE stack that carries
//   the console) and NTP SYNC (the Wi-Fi sync blocks the loop and shares the radio with the console) reply "ERR NOT_OVER_BLE" at each level.
// Only one central connects. A disconnect clears the partial line.
class BleConsoleBridge {
public:
  enum class Action : uint8_t {
    NONE = 0, // partial line, blank line or console off
    RUN,      // `command` is valid: run it with the runner and a BleChunkSink
    REPLY,    // send `reply` (fixed text with LF)
  };

  void setLevel(uint8_t level) {
    level_ = level;
  }
  uint8_t level() const {
    return level_;
  }

  // True when the central is accepted. A second central is refused.
  bool onConnect() {
    if (connected_) {
      return false;
    }
    connected_ = true;
    line_ = SerialLineAssembler();
    return true;
  }

  void onDisconnect() {
    connected_ = false;
    line_ = SerialLineAssembler(); // the partial line is gone
  }

  bool connected() const {
    return connected_;
  }

  // Drops the partial line (after lost RX bytes) so a spliced tail never runs as a command.
  void resetLine() {
    line_ = SerialLineAssembler();
  }

  Action feed(uint8_t byte, SerialCommand& command, const char*& reply) {
    reply = "";
    if (!connected_ || level_ == 0) {
      return Action::NONE;
    }
    const SerialLineAssembler::Event event = line_.feed(byte);
    if (event == SerialLineAssembler::Event::NONE) {
      return Action::NONE;
    }
    const SerialParseStatus status = event == SerialLineAssembler::Event::LINE_TOO_LONG
        ? SerialParseStatus::TOO_LONG
        : SerialConsole::parse(line_.line(), line_.length(), command);
    if (status == SerialParseStatus::EMPTY) {
      return Action::NONE;
    }
    if (status != SerialParseStatus::OK) {
      reply = SerialConsole::errorLine(status);
      return Action::REPLY;
    }
    reply = gate(command, level_);
    return reply[0] == '\0' ? Action::RUN : Action::REPLY;
  }

  // Empty text: the command may run at this level.
  static const char* gate(const SerialCommand& command, uint8_t level) {
    if (command.kind == SerialCommandKind::DUMP || command.kind == SerialCommandKind::SCAN ||
        command.kind == SerialCommandKind::SCREEN || command.kind == SerialCommandKind::NTP_SYNC) {
      return "ERR NOT_OVER_BLE\n";
    }
    const bool writes = command.kind == SerialCommandKind::SET || command.kind == SerialCommandKind::MUTE || command.kind == SerialCommandKind::SNOOZE ||
                        command.kind == SerialCommandKind::RTC_TRIM || command.kind == SerialCommandKind::SLOT_CLEAR ||
                        command.kind == SerialCommandKind::SLOT_SWAP || command.kind == SerialCommandKind::ACK ||
                        (command.kind == SerialCommandKind::TIME && command.time_set);
    if (writes && level < 2) {
      return "ERR READ_ONLY\n";
    }
    return "";
  }

private:
  uint8_t level_ = 0;
  bool connected_ = false;
  SerialLineAssembler line_;
};

// Queue between the BLE task (connect, disconnect and RX write events) and the loop task. It has
// no lock: the caller holds one around each call. A disconnect clears the queued bytes of the old
// central. Connect and disconnect events stay in order (disconnect first), so a quick reconnect
// keeps its connect event. A full ring drops the rest of a write and all later writes until the
// loop task pops the overflow (once the ring is empty); the loop task then drops the partial line
// and replies ERR TOO_LONG. No byte after the gap joins a line before the gap.
class BleConsoleIngress {
public:
  static constexpr size_t RING_SIZE = 256;
  static constexpr uint8_t EVENT_CONNECT = 1;
  static constexpr uint8_t EVENT_DISCONNECT = 2;
  static constexpr uint16_t DEFAULT_MTU = BLE_CONSOLE_DEFAULT_MTU;

  struct Step {
    uint8_t events = 0;    // EVENT_* bits since the last pop
    bool have = false;     // `byte` is valid
    uint8_t byte = 0;
    bool overflow = false; // bytes were lost; the ring is empty
    bool connected = false; // a central is connected now (the last event wins)
  };

  void connect() {
    connected_ = true;
    events_ = static_cast<uint8_t>(events_ | EVENT_CONNECT);
    mtu_ = DEFAULT_MTU; // until the central negotiates
  }

  void disconnect() {
    connected_ = false;
    events_ = static_cast<uint8_t>(events_ | EVENT_DISCONNECT);
    head_ = tail_ = 0;
    overflow_ = false;
  }

  void setMtu(uint16_t mtu) {
    mtu_ = mtu;
  }

  uint16_t mtu() const {
    return mtu_;
  }

  void push(const uint8_t* data, size_t len) {
    if (overflow_) {
      return; // gap: bytes after it must not join the partial line before it
    }
    for (size_t i = 0; i < len; ++i) {
      const size_t next = (head_ + 1) % RING_SIZE;
      if (next == tail_) {
        overflow_ = true;
        return;
      }
      ring_[head_] = data[i];
      head_ = next;
    }
  }

  bool pending() const {
    return head_ != tail_ || events_ != 0 || overflow_;
  }

  Step pop() {
    Step step;
    step.events = events_;
    step.connected = connected_;
    events_ = 0;
    if (head_ != tail_) {
      step.have = true;
      step.byte = ring_[tail_];
      tail_ = (tail_ + 1) % RING_SIZE;
    } else if (overflow_) {
      step.overflow = true;
      overflow_ = false;
    }
    return step;
  }

  void clear() {
    head_ = tail_ = 0;
    events_ = 0;
    connected_ = false;
    overflow_ = false;
    mtu_ = DEFAULT_MTU;
  }

private:
  uint8_t ring_[RING_SIZE] = {0};
  size_t head_ = 0;
  size_t tail_ = 0;
  uint8_t events_ = 0;
  bool overflow_ = false;
  bool connected_ = false;
  uint16_t mtu_ = DEFAULT_MTU;
};

// RTC copy of the values that STATUS reports. The pointers stay valid during the call.
struct SerialStatusSnapshot {
  const SwitchBotRegistry* registry = nullptr;
  const ClimateHistory* history = nullptr;
  const DeviceConfig* config = nullptr;
  BatteryReading battery;
  uint8_t alert_mask = 0;
  uint16_t sensor_battery_latch = 0; // SensorBatteryLatch bits, one per registry slot
  uint16_t ventilate_tiles = 0;      // VentilateLatch bits, bit 0 onboard, bits 1..10 slots
  uint16_t dry_tiles = 0;            // DryLatch bits, bit 0 onboard, bits 1..10 slots
  uint16_t wall_tiles = 0;           // WallLatch bits, bit 0 onboard, bits 1..10 slots
  uint16_t window_tiles = 0;         // WindowLatch bits, bit 0 onboard, bits 1..10 slots
  uint16_t stale_slots = 0;          // PersistentState::stale_slots, bits 0..9 are the registry slots
  uint16_t reception_low = 0;        // PersistentState::reception_low, bits 0..9 are the registry slots
  uint16_t drift_tiles = 0;          // DriftLatch bits, bit 0 onboard, bits 1..10 slots
  uint16_t heat_tiles = 0;           // HeatLatch bits, bit 0 onboard, bits 1..10 slots
  uint16_t stuck_tiles = 0;          // StuckLatch bits, bit 0 onboard, bits 1..10 slots
  NoiseHistory noise_history;        // RTC copy of the hourly noise levels
  NoiseBand noise_band = NoiseBand::QUIET; // RTC copy of the noise band latch
  DailyNoiseStats daily_noise;       // RTC copy of the noise summary of the current day
  bool muted = false;
  uint8_t alert_ack = 0; // acknowledged alert bits (ACK)
  Snooze snooze;      // RTC copy of PersistentState::snooze
  uint32_t last_chime_ms = 0; // RTC copy of PersistentState::last_chime_ms, 0 is no stamp
  int8_t quiet_active = -1; // QuietHoursWindow::isActive at the RTC time of the command; -1 is unknown

  // 1 or 0 from QuietHoursWindow::isActive; -1 for a failed read, an invalid time or a stopped oscillator.
  static int8_t quietFlag(bool read_ok, const RtcTime& time, const QuietHoursWindow& window) {
    if (!read_ok || !time.valid || time.oscillator_stopped) {
      return -1;
    }
    return window.isActive(time) ? 1 : 0;
  }
  uint32_t now_ms = 0; // RTC-backed clock
  SdStatus sd_status = SdStatus::UNKNOWN; // last card result
  uint32_t sd_status_ms = 0;              // clock value of that result
  int32_t sd_free_mb = -1;                // free card space in MB at the last card read; -1 is unknown
  const char* reset_reason = "UNKNOWN";   // ResetReasonCodec::text of the last boot
  int32_t abnormal_resets = -1;           // NVS count; negative when NVS is unreadable
  Shtc3Health shtc3;                      // failed onboard SHTC3 reads by cause
  const BatteryHistory* battery_history = nullptr; // RTC copy of the hourly cell voltages
  int32_t cycle_tenths = -1;              // CycleTracker::cycleTenths(); negative when NVS is unreadable
  uint32_t scan_saved_ms = 0;             // scan time that the last early stop saved
  const AwakeTimeStats* awake = nullptr;  // measured awake time of the timer wakeups
  uint32_t free_heap = 0;                 // ESP.getFreeHeap(), bytes; not stored in RTC memory
  uint32_t min_free_heap = 0;             // ESP.getMinFreeHeap(), bytes
  uint32_t largest_heap = 0;              // ESP.getMaxAllocHeap(), bytes
  uint32_t free_psram = 0;                // ESP.getFreePsram(), bytes
  FrameRefresh frame;                     // RTC copy of the last flushed frame CRC and the skip counter
  WakeupCause wake_cause = WakeupCause::POWER_ON; // cause of this wake cycle
  uint32_t boot_count = 0;                // RTC copy of the wake cycles since power-on
  uint32_t battery_interval_s = 0;        // sleep interval on battery power at the current RTC time
  const ReceptionHistory* reception = nullptr; // BLE reception rate of each registry slot
  bool rtc_trim_valid = false;            // the offset register was read
  int32_t rtc_trim = 0;                   // PCF85063A offset register value, -64 to 63
  const RtcDriftState* rtc_drift = nullptr; // last NTP drift figure
  const NtpSyncState* ntp = nullptr;      // RTC copy of the NTP attempt state
  bool ntp_credentials = false;           // Wi-Fi credentials exist in wifi_config.h
};

// Runs one parsed command. Storage mounts the card for the command and unmounts it on every
// path. Interfaces:
//   Storage: bool mount(); void unmount(); size_t listLogs(SerialLogEntry*, size_t cap);
//            int readTail(const char* name, char* buf, size_t capacity, size_t& len, bool& whole) (TAIL and DAYS only);
//            bool openLog(const char* name, uint32_t& size); size_t read(uint8_t*, size_t);
//            void closeLog(); uint64_t totalBytes(); uint64_t usedBytes();
//   Sink:    size_t write(const uint8_t*, size_t); returns the count written. A short count
//            ends a DUMP at once: the host sees a missing CRC32 line and rejects the file.
// Reply frames (text lines, LF):
//   LIST -> "FILE <name> <bytes>" per file, then "END LIST <count>"
//   INFO -> "INFO total=<bytes> used=<bytes> files=<count>"
//   DUMP -> "BEGIN <name> <bytes>", exactly <bytes> raw bytes, then "CRC32 <8 hex digits>"
//   CONFIG -> "key=value" per DeviceConfig setting, "SOURCE <SD|DEFAULT>", "REJECTED line=<n> reason=<UNKNOWN_KEY|BAD_VALUE|DUPLICATE_MAC>" for each of the first four rejected lines, "WARN key=<key> reason=<REASON>" for each conflicting key pair, then "CRC32 <8 hex
//   digits>" over all lines before it. showConfig() sends it from the RTC copy: no card access,
//   so execute() ignores CONFIG.
//   CONFIG <key> -> the single line "<key>=<value>" of that key (an empty name or MAC gives "<key>=") and "CRC32 <8 hex digits>" over it;
//   "ERR BAD_KEY" for a key outside DeviceConfigParser::KEYS, "ERR BAD_ARG" for extra text. showConfigKey() works on the RTC copy.
//   CONFIG CHANGED -> one line "<key>=<value>" (same text as CONFIG; an empty name or MAC gives "<key>=") for each key of
//   DeviceConfigParser::KEYS whose value differs from the default DeviceConfig, in KEYS order, then "END CHANGED <N>" and "CRC32 <8 hex digits>"
//   over all lines before it. An empty slot name and an empty MAC equal the default (the default config holds none). "ERR BAD_ARG" for extra text (CHANGEDX, CHANGED 1).
//   showConfigChanged() works on the RTC copy: no card access, so execute() ignores CONFIG. USB and BLE at each ble_console level.
//   STATUS -> "SENSOR <index> <name> temp=<C> rh=<%RH> bat=<%> rssi=<dBm> age=<s> state=<OK|STALE|EMPTY|BATLOW> format=<...> mac=<...> mold_h=<N|--> temp_trend=<RISE|FALL|STEADY|--> rh_trend=<RISE|FALL|STEADY|--> dry_h=<N|--> heat_h=<N|-->" for index 0
//   (onboard, newest hourly sample, bat and rssi are "--") and 1 to 5 (slots, calibrated readings), a missing value gives "--";
//   then "SD status=<UNKNOWN|OK|NO_CARD|MOUNT_FAILED|WRITE_FAILED|FULL> age=<s|--> free_mb=<N|-->" (age since the last card result; free card space of the last card read), then "RESET reason=<TEXT> abnormal=<N|-->", then "RTC trim=<N|--> drift_ppm=<N|-->" (offset register of the PCF85063A; the drift rate only for a figure of 24 h or more), then "NTP ok=<0|1|--> failures=<N> age_s=<N|--> next_s=<N|-->" (RTC copy of the NTP state; "--" without an attempt or without Wi-Fi credentials; next_s is the time to the next due attempt; age_s and next_s use the 32-bit clock difference of NtpSchedule::due and wrap like it), then "SHTC3 nack=<N> short=<N> crc=<N> run=<N> retried=<N>[ usb_offset=<x>]" (the offset only on USB power), then "BLE rejected=<N> last=<MAC|--> woio=<N> meter=<N> bthome=<N> atc=<N> thermopro=<N> unassigned=<N> jumps=<N>" (packets of the current scan that no slot took; unassigned counts the packets blocked by slot_auto_assign=0; jumps counts the pending packets of a large reading jump that JumpGuard dropped), then "RECEPTION s1=<N>/<M> ... s5=<N>/<M>" (scans with a packet of the last <M> scans, "--" for an empty slot), then "POWER src=<USB|BAT> v=<V> pct=<%> alerts=<hex mask> muted=<0|1> acked=<hex mask>", then "RUNTIME h=<N|--> samples=<N> cycles=<N.N|--> scan_saved_ms=<N>" (remaining hours, battery samples, charge cycles), then "AWAKE last_ms=<N|--> avg_ms=<N|--> max_ms=<N|--> samples=<N>" (measured awake time of the timer wakeups on battery power), then "MEMORY free=<N> min=<N> largest=<N> psram=<N>" (heap and PSRAM bytes of the current wake cycle; no RTC state), then "FRAME crc=<8 hex digits|--> skips=<N>" (RTC copy of FrameRefresh: CRC-32 of the last flushed frame, "--" for 0, and the skip counter), then "WAKE cause=<POWER_ON|TIMER|KEY|OTHER> boots=<N> battery_interval_s=<N>" (cause of this wake cycle, RTC copy of boot_count, and the sleep interval that PowerManager::powerConfig gives on battery power at the current RTC time), then "LATCH vent=<3 hex digits> dry=<3 hex digits> batlow=<3 hex digits> stale=<3 hex digits> wall=<3 hex digits> window=<3 hex digits> rxlow=<3 hex digits> drift=<3 hex digits> heat=<3 hex digits> stuck=<3 hex digits>" (RTC copy of the badge latches; vent, dry, wall, window, drift, heat and stuck: bit 0 onboard, bits 1 to 10 slots; batlow, stale and rxlow: bits 0 to 9 slots), then "NOISE db=<N|--> band=<QUIET|MODERATE|LOUD> peak_db=<N|--> peak_hour=<N|--> events_sum=<N>" (RTC copy: newest hourly noise level, noise band latch, peak of the current day; no microphone access), then "CHIME repeat_h=<N> bits=<2 hex digits> last_s=<N|--> next_s=<N|--> snooze_s=<N|-->" (chime_repeat_h; bits are the repeatable active alerts without the acknowledged ones; seconds since the last chime stamp, "--" for 0; seconds to the next reminder, at least 0, "--" for repeat_h 0 and for bits 0; remaining snooze seconds, "--" without an active snooze), then "CRC32 <8 hex digits>" over all lines
//   before it. A space in a name becomes "_". showStatus() works on the RTC copy: no card access, so execute() ignores STATUS.
//   BEEP N -> "OK BEEP <N> <duration ms>" after one playback of the chime of alert bit N (0 to 7), or "ERR MUTED"
//   (mute, quiet hours, volume 0) or "ERR PLAY_FAILED". beep() needs no card: execute() ignores BEEP.
//   SLOT CLEAR N -> "OK SLOT CLEAR <N>" after PersistentState::clearSlotByUser freed registry slot N (1 to 10): reading, histories,
//   reception mask, week days and latches; an occupied slot records SLOT_RELEASE (detail 0x01<N>), an empty one records nothing. A slot
//   with a fixed MAC gives "ERR FIXED_MAC" and no change. No card access; ERR READ_ONLY on BLE with ble_console 1.
//   SLOT SWAP A B -> "OK SLOT SWAP <A> <B>" after PersistentState::swapSlotsByUser swapped registry slots A and B (1 to 10, different):
//   reading, both 24-hour histories, reception mask, week days and the battery, ventilation, dry air and stale latch bits; one SLOT_ASSIGN
//   event for each occupied slot. A slot with a fixed MAC gives "ERR FIXED_MAC", a disabled slot "ERR SLOT_OFF", an equal or bad number
//   "ERR BAD_ARG"; no change in these cases. No card access; ERR READ_ONLY on BLE with ble_console 1. RTC layout and STATE_VERSION do not change.
//   MUTE N -> "OK MUTE <N>" after the mute flag of the RTC state is set (0 or 1); setMute() needs no card: execute() ignores MUTE.
//   IDENTIFY -> "OK IDENTIFY <4 hex digits>" after the identify screen (RLCD plus the last 4 hex digits of the BLE MAC, version,
//   transport) is flushed and one short note played under the BEEP rules; identify() needs no card: execute() ignores IDENTIFY.
//   PATTERN N -> "OK PATTERN <N>" after the framebuffer shows test pattern N (0 black, 1 checkerboard, 2 border with cross) and the
//   panel got it without display_invert; the next wake cycle renders the normal view. showPattern() needs no card: execute() ignores PATTERN.
//   SCAN -> one 4 s BLE scan, then "BEGIN SCAN", the header "mac,format,rssi,slot,temp_c,rh_pct,battery_pct", one row for each MAC (format WOIO|METER|BTHOME|ATC|THERMOPRO,
//   the strongest RSSI wins, slot 1 to 5 or "-", battery empty when the packet has none, temperature always in Celsius), up to 16 MACs, "DROPPED,<N>" when packets of further MACs found
//   no entry, then "CRC32 <8 hex digits>" over all lines before it; "ERR SCAN_FAILED" when the scan does not start. The registry, the fixed MACs
//   and the persistent state stay unchanged. scan() needs no card: execute() ignores SCAN.
//   TIME -> "TIME <YYYY-MM-DDTHH:MM:SS> valid=<0|1> osf=<0|1>" with the local time of the RTC (osf: oscillator stop flag);
//   TIME SET <YYYY-MM-DDTHH:MM:SS> -> "OK TIME" after the write (the write clears the oscillator stop flag and the
//   RtcDrift baseline), or "ERR RTC_FAILED". A bad format or date or a year outside 2025 to 2099 gives "ERR BAD_ARG"
//   from the parser. showTime() and setTime() need no card: execute() ignores TIME.
//   VERSION -> "VERSION fw=<version> git=<hash> core=<core> state=<STATE_VERSION> built=<YYYY-MM-DDTHH:MM:SS>", then "CRC32 <8 hex
//   digits>" over that line. The build time comes from __DATE__ and __TIME__. showVersion() needs no card: execute() ignores VERSION.
//   SET <key>=<value> -> "OK SET <key>" after the new config.ini is in place and applied, "ERR BAD_KEY" or "ERR BAD_VALUE"
//   from the parser, "ERR NO_CARD", "ERR TOO_LARGE" (result above 8192 bytes, nothing written) or "ERR WRITE_FAILED".
//   setConfig() mounts the card for the command. The last line of the key is replaced or a line is appended; other lines and
//   comments stay. The text goes to /sdcard/config.tmp, then the old file is removed and the new file is renamed. A failure
//   before the remove keeps the old file. If the rename fails after the remove, the old text is written back. execute()
//   ignores SET. A failed restore keeps config.tmp, which then holds the new text. Storage for SET:
//   bool readConfig(char* buf, size_t cap, size_t& len, bool& exists) (false: read error; an absent file gives true with
//   exists false); bool writeTemp(const char*, size_t); bool removeConfig(); bool renameTemp(); void removeTemp();
//   bool restoreConfig(const char*, size_t).
//   CALIB N -> "REFERENCE <name>", one "SENSOR <name> hours=<n> temp_offset=<x> rh_offset=<x> temp_diff=<x> rh_diff=<x>" or
//   "SKIP <name> hours=<n>" per other sensor (the onboard SENSOR line is followed by "USB hours=<n> bat_hours=<n>
//   usb_temp_offset=<x> usb_temp_diff=<x|-->", the USB mean difference minus the battery mean difference; the onboard
//   temp_offset_c proposal uses the battery hours when 3 or more exist; the proposal usb_temp_offset_c needs 3 USB and
//   3 battery hours), the "key=value" lines to paste into config.ini, then "CRC32 <8 hex digits>"
//   over all lines before it. A stale slot and the outdoor_slot get "SKIP <name> stale" or "SKIP <name> outdoor" (outdoor wins)
//   instead of the SENSOR line and the proposal lines; a stale or outdoor reference gives "ERR BAD_REF" (no CRC32 line).
//   showCalib() works on the RTC history: no card access, so execute() ignores CALIB.
//   SALT N -> "SALT <name> hours=<n> rh_mean=<x> rh_span=<x> temp_mean=<x> reference=<x>" for the last 6 hourly samples of
//   sensor N (0 onboard, 1 to 10 a slot) in a closed box with saturated sodium chloride, the line "<key>=<value>" (the active
//   humidity offset plus reference minus mean, clamped to the key range) and "CRC32 <8 hex digits>" over the lines before it.
//   SALT N MGCL2 -> the same checks and reply with the magnesium chloride reference (10 C 33.5 %RH to 35 C 32.1 %RH) and
//   no proposal line: the host calculates gain and offset from the two points.
//   Fewer than 6 samples give "SKIP <name> hours=<n>", a humidity span above 1.0 %RH gives "UNSTABLE <name> span=<x>" (both
//   with the CRC32 line), a mean temperature outside 10 to 35 C gives "ERR BAD_TEMP". showSalt() works on the RTC history: no
//   card access, so execute() ignores SALT.
//   FRSI R W [H] (H 3 to 24, no leading zero, default 6: the newest H samples of each history count, SKIP hours=<n> below H) -> "FRSI room=<name> wall=<name> hours=<n> frsi=<x.xx> span=<x.xx> delta_c=<x>", the line "wall_frsi_pct=<N>" (the
//   mean temperature factor (t_wall - t_out) / (t_room - t_out) of the last H (default 6) hourly samples of R (room, 0 onboard, 1 to 10 a slot),
//   W (a slot on the inner wall surface) and outdoor_slot, in percent, clamped to 50 to 95) and "CRC32 <8 hex digits>". Fewer than
//   H samples give "SKIP hours=<n>", a mean difference of room and outdoor below 10 C gives "ERR LOW_DELTA", a factor span above
//   0.10 (or an hour without a usable factor, "span=--") gives "UNSTABLE span=<x>", no fresh outdoor_slot reading gives
//   "ERR NO_OUTDOOR", equal sensors, the outdoor slot or a number above 10 give "ERR BAD_ARG". showFrsi() works on the RTC copy:
//   no card access, no state change, so execute() ignores FRSI. USB and BLE at each ble_console level.
//   SPREAD [<H>] (H 6 to 24, no leading zero, default 6) -> "SPREAD sensors=<n> temp_median=<x> rh_median=<x>", one line "SENSOR <name> temp_dev=<x> rh_dev=<x> <OK|OUTLIER>" for
//   each sensor and "CRC32 <8 hex digits>". All sensors sit at one place for H hours; the mean of the last H hourly samples of each
//   sensor with H temperature and H humidity samples (onboard and slots 1 to 10) counts, the median of these means is the
//   reference (the mean of the two middle means for an even count) and OUTLIER means a deviation above 0.5 C or 3.0 %RH.
//   Each OUTLIER line is followed by one proposal line "<temp_key>=<x> <rh_key>=<x>" (temp_offset_c and rh_offset_pct for the onboard
//   sensor, slotN_temp_offset_c and slotN_rh_offset_pct for slots 1 to 10): active offset + median - sensor mean (rounded to 0.1),
//   clamped to the key range; OK sensors have no proposal line.
//   A stale slot does not count (no SENSOR line, not in sensors= and in the median). The outdoor slot (`outdoor_slot`) does not count either.
//   Fewer than 3 such sensors give "SKIP sensors=<n>" and the CRC32 line, a number outside 6 to 24, a leading zero, a non-digit or extra text gives "ERR BAD_ARG". showSpread() works on
//   the RTC copy and the registry: no card access, no state change, so execute() ignores SPREAD. USB and BLE at each ble_console level.
//   TODAY -> "BEGIN TODAY", the header "sensor,name,hours,temp_min_c,temp_max_c,temp_mean_c,rh_min_pct,rh_max_pct,rh_mean_pct,mold_h,dry_h" (11 columns), one row for
//   each sensor (0 onboard, 1 to 10 a slot) that holds at least one hourly sample, and "CRC32 <8 hex digits>". The row covers the hourly
//   samples of the RTC ClimateHistory since the last local midnight of the RTC time: the newest sample is as old as the time since
//   ClimateHistory::last_sample_ms, each older sample is one hour older, and a sample counts when its age does not exceed the minutes
//   since midnight. `hours` is the larger sample count of the two histories; a sensor without a sample since midnight has hours 0 and
//   empty value fields; a half-empty pair has empty fields on its empty side. Values use 0.1 steps (the mean rounds half away from
//   zero). "ERR NO_TIME" for an invalid, stopped or untrusted RTC time (year before 2025 or after 2099, or a failed clock read), "ERR BAD_ARG" for extra text. The age of the
//   samples is an approximation: it follows the newest push and assumes 60 minutes between pushes, but the real step is 60 minutes plus up
//   to one wake interval, so a sample near midnight can fall on the wrong side. A sensor that skipped a push shifts by one hour. A slot
//   without new samples (stale, lost) keeps its old samples and shows them as today's values (like SPREAD); TODAY has no STALE marker.
//   The day boundary is the local wall clock of the RTC (a plain 24-hour day, also on a DST change day). showToday() works on the
//   RTC copy: no card access, no state change, so execute() ignores TODAY. USB and BLE at each ble_console level.
//   mold_h counts the humidity samples since midnight strictly above mold_alert_limit_rh (HumidityHistory::countAbove rule, a sample at the
//   limit does not count) and dry_h those at or below dry_alert_limit_rh (countAtOrBelow rule); both use the same sample age rule as the
//   humidity fields and are empty for a sensor without a humidity sample since midnight; dry_h is also empty for dry_alert_limit_rh 0.
//   TODAY <N> (N 0 to 10, no leading zero) sends the same header and only the row of sensor N (no row without a sample), then the CRC32 line over
//   the filtered bytes; "ERR BAD_ARG" for 11, a leading zero, a non-digit and extra text; "ERR NO_TIME" stays.
//   TAIL <N> -> "BEGIN TAIL <n>", the CSV header of climate_v6, the newest rows (N 1 to 72) oldest first and "CRC32 <8 hex digits>". <n> is
//   the row count sent. The rows come from the last 16384 bytes of the current month file; fewer than N rows there add the rows of the
//   last 16384 bytes of the previous month file, but only when the window of the current file reaches its start (no gap). Only complete rows with the 42 columns count (a partial first line, a line without
//   newline and a bad line are skipped). "ERR NO_CARD" without a card, "ERR NO_FILE" without a valid RTC time or without both files,
//   "ERR READ_FAILED" for a read error (after the BEGIN line it takes the place of the CRC32 line, like DUMP), "ERR BAD_ARG" for 0, a number above 72, a non-digit, a leading zero, no number or extra text.
//   showTail() mounts and unmounts the card itself, so execute() ignores TAIL. No state change. USB and BLE at each ble_console level.
//   DAYS <N> -> "BEGIN DAYS <n>", the CSV header of daily_v7 (105 columns), the newest rows (N 1 to 31) oldest first and "CRC32 <8 hex digits>". <n> is
//   the row count sent. The rows come from the last 16384 bytes of the current year file daily_v7_YYYY.csv (a missing file falls back to
//   daily_v6_YYYY.csv and then daily_v5_YYYY.csv of the same year; each daily_v6 row gets 11 empty heat_h fields, each daily_v5 row 22 empty
//   dry_h and heat_h fields); fewer than N rows there add
//   the rows of the last 16384 bytes of the previous year file, but only when the window of the current file reaches its start. Only
//   complete rows that SdLogCodec::parseDailyRow accepts count (a partial first line, a line without newline and a bad line are
//   skipped). Same errors as TAIL: "ERR NO_CARD", "ERR NO_FILE", "ERR READ_FAILED", "ERR BAD_ARG" (0, above 31, a non-digit, a leading
//   zero, no number or extra text). showDays() mounts and unmounts the card itself, so execute() ignores DAYS. No state change.
//   USB and BLE at each ble_console level.
//   Any failure -> "ERR <NO_CARD|NOT_FOUND|READ_FAILED>". A read error after BEGIN pads the
//   payload with zero bytes to the announced count, then sends "ERR READ_FAILED" in place of the
//   CRC32 line, so the host stays in step with the frame and sees the error.
template <typename Storage, typename Sink>
class SerialConsoleRunner {
public:
  static constexpr size_t CHUNK_SIZE = 256;

  static void reject(Sink& sink, SerialParseStatus status) {
    const char* text = SerialConsole::errorLine(status);
    send(sink, text);
  }

  // Sends the active configuration. The card stays untouched.
  static void showConfig(Sink& sink, const DeviceConfig& config, bool from_sd,
                         const ConfigRejects& rejects = ConfigRejects{}) {
    // Static: 1.4 KB text buffer does not fit the default Arduino loopTask stack.
    static char text[DeviceConfigText::MAX_SIZE + 16 + ConfigRejects::MAX_LINES * 48 + DeviceConfigWarnings::MAX_TEXT_SIZE];
    size_t len = DeviceConfigText::format(config, text, DeviceConfigText::MAX_SIZE);
    if (len == 0) {
      return;
    }
    len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "SOURCE %s\n", from_sd ? "SD" : "DEFAULT"));
    for (size_t i = 0; from_sd && i < rejects.recorded(); ++i) {
      len += static_cast<size_t>(
          snprintf(text + len, sizeof(text) - len, "REJECTED line=%u reason=%s\n",
                   static_cast<unsigned>(rejects.lines[i]), ConfigRejects::reasonText(rejects.reasons[i])));
    }
    // WARN lines of conflicting keys follow the REJECTED lines; calculated for each command, no RTC state.
    len += DeviceConfigWarnings::format(config, text + len, sizeof(text) - len);
    SerialCrc32 crc;
    crc.update(reinterpret_cast<const uint8_t*>(text), len);
    if (!put(sink, reinterpret_cast<const uint8_t*>(text), len)) {
      return;
    }
    char line[24];
    snprintf(line, sizeof(line), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
    send(sink, line);
  }

  // Sends one "<key>=<value>" line of the active configuration and its CRC32 line. The card stays untouched.
  // `key` is a key of DeviceConfigParser::KEYS. The text omits an empty name or MAC, so the value is then empty.
  static void showConfigKey(Sink& sink, const DeviceConfig& config, const char* key) {
    static char text[DeviceConfigText::MAX_SIZE];
    const size_t len = DeviceConfigText::format(config, text, sizeof(text));
    if (len == 0) {
      return;
    }
    size_t line_len = 0;
    const char* found = findKeyLine(text, len, key, line_len);
    char reply[96];
    const size_t reply_len = static_cast<size_t>(
        found == nullptr ? snprintf(reply, sizeof(reply), "%s=\n", key)
                         : snprintf(reply, sizeof(reply), "%.*s\n", static_cast<int>(line_len), found));
    if (reply_len >= sizeof(reply)) {
      return;
    }
    SerialCrc32 crc;
    crc.update(reinterpret_cast<const uint8_t*>(reply), reply_len);
    if (!put(sink, reinterpret_cast<const uint8_t*>(reply), reply_len)) {
      return;
    }
    char line[24];
    snprintf(line, sizeof(line), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
    send(sink, line);
  }

  // Sends the keys whose value differs from the default DeviceConfig as "<key>=<value>" lines in KEYS order,
  // then "END CHANGED <N>" and the CRC32 line. An empty name or MAC has no text line and equals the default (the default config holds no name and no MAC).
  static void showConfigChanged(Sink& sink, const DeviceConfig& config) {
    static char active[DeviceConfigText::MAX_SIZE];
    static char defaults[DeviceConfigText::MAX_SIZE];
    static char reply[DeviceConfigText::MAX_SIZE + 40];
    const size_t active_len = DeviceConfigText::format(config, active, sizeof(active));
    const size_t default_len = DeviceConfigText::format(DeviceConfig{}, defaults, sizeof(defaults));
    if (active_len == 0 || default_len == 0) {
      return;
    }
    size_t reply_len = 0;
    unsigned count = 0;
    for (size_t i = 0; i < DeviceConfigParser::KEY_COUNT; ++i) {
      const char* key = DeviceConfigParser::KEYS[i];
      size_t a_len = 0;
      size_t d_len = 0;
      const char* a = findKeyLine(active, active_len, key, a_len);
      const char* d = findKeyLine(defaults, default_len, key, d_len);
      if (a_len == d_len && (a_len == 0 || memcmp(a, d, a_len) == 0)) {
        continue;
      }
      // The default config has no name and no MAC, so a differing key always has an active line.
      if (a == nullptr) {
        continue;
      }
      const int n = snprintf(reply + reply_len, sizeof(reply) - reply_len, "%.*s\n", static_cast<int>(a_len), a);
      if (n < 0 || reply_len + static_cast<size_t>(n) + 32 > sizeof(reply)) {
        return;
      }
      reply_len += static_cast<size_t>(n);
      ++count;
    }
    reply_len += static_cast<size_t>(snprintf(reply + reply_len, sizeof(reply) - reply_len, "END CHANGED %u\n", count));
    SerialCrc32 crc;
    crc.update(reinterpret_cast<const uint8_t*>(reply), reply_len);
    if (!put(sink, reinterpret_cast<const uint8_t*>(reply), reply_len)) {
      return;
    }
    char line[24];
    snprintf(line, sizeof(line), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
    send(sink, line);
  }

  // Finds the line "<key>=..." in the config text; `len` is the line length without the LF, 0 when the key has no line.
  static const char* findKeyLine(const char* text, size_t text_len, const char* key, size_t& len) {
    const size_t key_len = strlen(key);
    len = 0;
    for (size_t pos = 0; pos < text_len;) {
      const char* nl = static_cast<const char*>(memchr(text + pos, '\n', text_len - pos));
      const size_t end = nl == nullptr ? text_len : static_cast<size_t>(nl - text);
      if (end - pos > key_len && memcmp(text + pos, key, key_len) == 0 && text[pos + key_len] == '=') {
        len = end - pos;
        return text + pos;
      }
      pos = end + 1;
    }
    return nullptr;
  }

  // Sends the mean differences of every sensor to the reference and the offsets that would remove them.
  // The hourly samples are paired by age (newest with newest), and only the hours with a sample in both
  // histories count. The samples already contain the active offsets. The card stays untouched.
  // A stale slot and the outdoor slot are no calibration partners (like SPREAD and DriftLatch): the reply sends
  // "SKIP <name> stale" or "SKIP <name> outdoor" for them, and a stale or outdoor reference gives "ERR BAD_REF".
  static void showCalib(Sink& sink, uint8_t reference, const ClimateHistory& history, const DeviceConfig& config,
                        const SwitchBotRegistry& registry, uint32_t now_ms) {
    static const char* const names[ClimateHistory::SLOT_COUNT] = {
        "onboard", "slot1", "slot2", "slot3", "slot4", "slot5",
        "slot6", "slot7", "slot8", "slot9", "slot10"};
    static const char* const prefixes[ClimateHistory::SLOT_COUNT] = {
        "", "slot1_", "slot2_", "slot3_", "slot4_", "slot5_",
        "slot6_", "slot7_", "slot8_", "slot9_", "slot10_"};
    constexpr size_t CALIB_SOURCE_COUNT = 1 + DeviceConfig::OFFSET_SLOT_COUNT;
    if (reference >= CALIB_SOURCE_COUNT) {
      return;
    }
    const uint16_t fresh = SpreadMath::freshMask(registry, now_ms, config.staleTimeoutMs());
    const uint16_t outdoor = SpreadMath::outdoorMask(config.outdoor_slot);
    if (((outdoor | static_cast<uint16_t>(~fresh)) >> reference) & 1u) {
      send(sink, "ERR BAD_REF\n");
      return;
    }
    static char text[2048];
    size_t len = static_cast<size_t>(snprintf(text, sizeof(text), "REFERENCE %s\n", names[reference]));
    static char suggestions[1024];
    suggestions[0] = '\0';
    size_t slen = 0;
    char usb_suggestion[40] = {0};
    size_t slen_usb = 0;
    for (size_t s = 0; s < CALIB_SOURCE_COUNT; ++s) {
      if (s == reference) {
        continue;
      }
      const bool is_outdoor = ((outdoor >> s) & 1u) != 0;
      if (is_outdoor || ((fresh >> s) & 1u) == 0) {
        if (len < sizeof(text)) {
          const int n = snprintf(text + len, sizeof(text) - len, "SKIP %s %s\n", names[s],
                                 is_outdoor ? "outdoor" : "stale");
          if (n > 0) {
            len += static_cast<size_t>(n);
          }
        }
        continue;
      }
      const size_t hours = commonHours(history, reference, s);
      if (hours < MIN_COMMON_HOURS) {
        if (len < sizeof(text)) {
          const int n = snprintf(text + len, sizeof(text) - len, "SKIP %s hours=%u\n", names[s],
                                 static_cast<unsigned>(hours));
          if (n > 0) {
            len += static_cast<size_t>(n);
          }
        }
        continue;
      }
      int32_t temp_sum = 0;
      int32_t rh_sum = 0;
      int32_t usb_sum = 0;
      int32_t bat_sum = 0;
      size_t usb_hours = 0;
      for (size_t k = 0; k < hours; ++k) {
        const int32_t temp_delta = history.temperatures[s].at(history.temperatures[s].size() - 1 - k) -
                                   history.temperatures[reference].at(history.temperatures[reference].size() - 1 - k);
        temp_sum += temp_delta;
        if (s == 0 && history.onboardOnUsb(k)) {
          usb_sum += temp_delta;
          ++usb_hours;
        } else {
          bat_sum += temp_delta;
        }
        rh_sum += history.slots[s].at(history.slots[s].size() - 1 - k) -
                  history.slots[reference].at(history.slots[reference].size() - 1 - k);
      }
      const float temp_mean = static_cast<float>(temp_sum) / static_cast<float>(hours); // 0.1 steps
      const float rh_mean = static_cast<float>(rh_sum) / static_cast<float>(hours);
      // The onboard suggestion replaces temp_offset_c only; usb_temp_offset_c is a separate key and stays.
      const float temp_active = s == 0 ? config.temp_offset_c : config.slot_temp_offset_c[s - 1];
      const float rh_active = s == 0 ? config.rh_offset_pct : config.slot_rh_offset_pct[s - 1];
      const float temp_min = s == 0 ? DeviceConfig::MIN_TEMP_OFFSET_C : DeviceConfig::MIN_SLOT_TEMP_OFFSET_C;
      const float temp_max = s == 0 ? DeviceConfig::MAX_TEMP_OFFSET_C : DeviceConfig::MAX_SLOT_TEMP_OFFSET_C;
      const float rh_min = s == 0 ? DeviceConfig::MIN_RH_OFFSET_PCT : DeviceConfig::MIN_SLOT_RH_OFFSET_PCT;
      const float rh_max = s == 0 ? DeviceConfig::MAX_RH_OFFSET_PCT : DeviceConfig::MAX_SLOT_RH_OFFSET_PCT;
      char f[4][12];
      formatTenths(f[0], sizeof(f[0]), tenths(temp_active));
      formatTenths(f[1], sizeof(f[1]), tenths(rh_active));
      formatTenths(f[2], sizeof(f[2]), static_cast<int32_t>(lroundf(temp_mean)));
      formatTenths(f[3], sizeof(f[3]), static_cast<int32_t>(lroundf(rh_mean)));
      if (len < sizeof(text)) {
        const int n = snprintf(text + len, sizeof(text) - len,
                               "SENSOR %s hours=%u temp_offset=%s rh_offset=%s temp_diff=%s rh_diff=%s\n",
                               names[s], static_cast<unsigned>(hours), f[0], f[1], f[2], f[3]);
        if (n > 0) {
          len += static_cast<size_t>(n);
          if (len >= sizeof(text)) {
            len = sizeof(text) - 1;
          }
        }
      }
      // The onboard proposal uses the battery hours only when enough exist: the PCB is warmer on USB power.
      const size_t bat_hours = hours - usb_hours;
      const float temp_basis = (s == 0 && bat_hours >= MIN_POWER_HOURS) ? static_cast<float>(bat_sum) / static_cast<float>(bat_hours)
                                                                          : temp_mean;
      const int32_t temp_new = clampTenths(lroundf(tenths(temp_active) - temp_basis), temp_min, temp_max);
      const int32_t rh_new = clampTenths(lroundf(tenths(rh_active) - rh_mean), rh_min, rh_max);
      if (s == 0) {
        // usb_temp_diff: mean difference of the USB hours minus the mean difference of the battery hours.
        const bool usb_ok = usb_hours >= MIN_POWER_HOURS && bat_hours >= MIN_POWER_HOURS;
        const float usb_diff = usb_ok ? static_cast<float>(usb_sum) / static_cast<float>(usb_hours) -
                                            static_cast<float>(bat_sum) / static_cast<float>(bat_hours)
                                      : 0.0f;
        char u[2][12];
        formatTenths(u[0], sizeof(u[0]), tenths(config.usb_temp_offset_c));
        if (usb_ok) {
          formatTenths(u[1], sizeof(u[1]), static_cast<int32_t>(lroundf(usb_diff)));
        } else {
          snprintf(u[1], sizeof(u[1]), "--");
        }
        if (len < sizeof(text)) {
          const int n = snprintf(text + len, sizeof(text) - len,
                                 "USB hours=%u bat_hours=%u usb_temp_offset=%s usb_temp_diff=%s\n",
                                 static_cast<unsigned>(usb_hours), static_cast<unsigned>(bat_hours), u[0], u[1]);
          if (n > 0) {
            len += static_cast<size_t>(n);
            if (len >= sizeof(text)) {
              len = sizeof(text) - 1;
            }
          }
        }
        if (usb_ok) {
          const int32_t usb_new = clampTenths(lroundf(tenths(config.usb_temp_offset_c) - usb_diff),
                                              DeviceConfig::MIN_USB_TEMP_OFFSET_C, DeviceConfig::MAX_USB_TEMP_OFFSET_C);
          formatTenths(u[0], sizeof(u[0]), usb_new);
          slen_usb += static_cast<size_t>(snprintf(usb_suggestion, sizeof(usb_suggestion), "usb_temp_offset_c=%s\n", u[0]));
        }
      }
      formatTenths(f[0], sizeof(f[0]), temp_new);
      formatTenths(f[1], sizeof(f[1]), rh_new);
      if (slen < sizeof(suggestions)) {
        const int sn = snprintf(suggestions + slen, sizeof(suggestions) - slen,
                                "%stemp_offset_c=%s\n%srh_offset_pct=%s\n", prefixes[s], f[0],
                                prefixes[s], f[1]);
        if (sn > 0) {
          slen += static_cast<size_t>(sn);
          if (slen >= sizeof(suggestions)) {
            slen = sizeof(suggestions) - 1;
          }
        }
      }
    }
    if (len < sizeof(text)) {
      const int n = snprintf(text + len, sizeof(text) - len, "%s", suggestions);
      if (n > 0) {
        len += static_cast<size_t>(n);
        if (len >= sizeof(text)) {
          len = sizeof(text) - 1;
        }
      }
    }
    if (slen_usb != 0 && len < sizeof(text)) {
      const int n = snprintf(text + len, sizeof(text) - len, "%s", usb_suggestion);
      if (n > 0) {
        len += static_cast<size_t>(n);
        if (len >= sizeof(text)) {
          len = sizeof(text) - 1;
        }
      }
    }
    SerialCrc32 crc;
    crc.update(reinterpret_cast<const uint8_t*>(text), len);
    if (!put(sink, reinterpret_cast<const uint8_t*>(text), len)) {
      return;
    }
    char line[24];
    snprintf(line, sizeof(line), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
    send(sink, line);
  }

  // SELFTEST: runs each check once in the fixed order shtc3, rtc, sd, battery, mic, ble, reception and replies
  // "BEGIN SELFTEST", one "CHECK <name> <OK|FAIL|SKIP> <detail>" line per check, "END SELFTEST pass=<N> fail=<N>" and the
  // CRC32 line (SKIP counts as neither). `shtc3`, `rtc`, `battery`, `mic`, `ble` and `reception` are injected and return a SelfTestOutcome;
  // the card check mounts `storage` itself and unmounts it. The mic check is skipped while `muted` (MUTED) or in `quiet_hours`
  // (QUIET). On BLE (`over_ble`) the scan check is skipped with CONSOLE, because the BLE console holds the radio, and
  // `ble` does not run. No state change, no RTC write.
  template <typename Shtc3Fn, typename RtcFn, typename BatteryFn, typename MicFn, typename BleFn, typename ReceptionFn>
  static void selfTest(Storage& storage, Sink& sink, bool over_ble, bool muted, bool quiet_hours, Shtc3Fn shtc3, RtcFn rtc,
                       BatteryFn battery, MicFn mic, BleFn ble, ReceptionFn reception) {
    using Status = SelfTestOutcome::Status;
    struct Row {
      const char* name;
      SelfTestOutcome outcome;
    };
    Row rows[7];
    rows[0] = {"shtc3", shtc3()};
    rows[1] = {"rtc", rtc()};
    if (storage.mount()) {
      const int32_t free_mb = SdLogCodec::freeMegabytes(storage.totalBytes(), storage.usedBytes());
      rows[2] = {"sd", SelfTestChecks::card(true, free_mb)};
      storage.unmount();
    } else {
      rows[2] = {"sd", SelfTestChecks::card(false, -1)};
    }
    rows[3] = {"battery", battery()};
    if (muted || quiet_hours) {
      rows[4] = {"mic", SelfTestOutcome::of(Status::SKIP, muted ? "MUTED" : "QUIET")};
    } else {
      rows[4] = {"mic", mic()};
    }
    rows[5] = {"ble", over_ble ? SelfTestOutcome::of(Status::SKIP, "CONSOLE") : ble()};
    rows[6] = {"reception", reception()};
    SerialCrc32 crc;
    auto emit = [&](const char* text) {
      const size_t len = strlen(text);
      if (!put(sink, reinterpret_cast<const uint8_t*>(text), len)) {
        return false;
      }
      crc.update(reinterpret_cast<const uint8_t*>(text), len);
      return true;
    };
    if (!emit("BEGIN SELFTEST\n")) {
      return;
    }
    unsigned pass = 0;
    unsigned fail = 0;
    for (const Row& row : rows) {
      pass += row.outcome.status == Status::OK ? 1U : 0U;
      fail += row.outcome.status == Status::FAIL ? 1U : 0U;
      char line[64];
      snprintf(line, sizeof(line), "CHECK %s %s %s\n", row.name, SelfTestOutcome::label(row.outcome.status), row.outcome.detail);
      if (!emit(line)) {
        return;
      }
    }
    char end[48];
    snprintf(end, sizeof(end), "END SELFTEST pass=%u fail=%u\n", pass, fail);
    if (!emit(end)) {
      return;
    }
    char line[24];
    snprintf(line, sizeof(line), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
    send(sink, line);
  }

  // Wall surface factor check: the mean of (t_wall - t_out) / (t_room - t_out) over the last `window` (3 to 24, default FRSI_HOURS 6) hourly samples of the
  // room sensor, the wall sensor (a slot on the inner wall surface) and the outdoor slot. The card and the state stay untouched.
  // The samples pair by age: the H newest samples of each history count, so a skipped hourly push of one history shifts its age.
  static void showFrsi(Sink& sink, uint8_t room, uint8_t wall, const ClimateHistory& history, const DeviceConfig& config,
                       const SwitchBotRegistry& registry, uint32_t now_ms, uint8_t window = FRSI_HOURS) {
    static const char* const names[ClimateHistory::SLOT_COUNT] = {
        "onboard", "slot1", "slot2", "slot3", "slot4", "slot5",
        "slot6", "slot7", "slot8", "slot9", "slot10"};
    const uint8_t outdoor = config.outdoor_slot;
    if (window < 3 || window > 24) {
      send(sink, "ERR BAD_ARG\n");
      return;
    }
    const size_t need = window;
    if (room >= ClimateHistory::SLOT_COUNT || wall >= ClimateHistory::SLOT_COUNT || wall == 0 || room == wall || (outdoor != 0 && (room == outdoor || wall == outdoor))) {
      send(sink, "ERR BAD_ARG\n");
      return;
    }
    if (outdoor < 1 || outdoor > MAX_SWITCHBOT_SENSORS) {
      send(sink, "ERR NO_OUTDOOR\n");
      return;
    }
    const SwitchBotReading& out_reading = registry.slot(outdoor - 1);
    if (!out_reading.valid || out_reading.isStale(now_ms, config.staleTimeoutMs())) {
      send(sink, "ERR NO_OUTDOOR\n");
      return;
    }
    const TemperatureHistory& t_room = history.temperatures[room];
    const TemperatureHistory& t_wall = history.temperatures[wall];
    const TemperatureHistory& t_out = history.temperatures[outdoor];
    size_t hours = t_room.size() < t_wall.size() ? t_room.size() : t_wall.size();
    hours = hours < t_out.size() ? hours : t_out.size();
    char text[256];
    size_t len = 0;
    if (hours < need) {
      len = static_cast<size_t>(snprintf(text, sizeof(text), "SKIP hours=%u\n", static_cast<unsigned>(hours)));
    } else {
      int32_t delta_sum = 0;
      float factor_sum = 0.0f;
      float factor_min = 0.0f;
      float factor_max = 0.0f;
      bool usable = true;
      for (size_t k = 0; k < need; ++k) {
        const float r = static_cast<float>(t_room.at(t_room.size() - 1 - k)) / 10.0f;
        const float w = static_cast<float>(t_wall.at(t_wall.size() - 1 - k)) / 10.0f;
        const float o = static_cast<float>(t_out.at(t_out.size() - 1 - k)) / 10.0f;
        delta_sum += t_room.at(t_room.size() - 1 - k) - t_out.at(t_out.size() - 1 - k);
        const float factor = ClimateMath::wallFactor(r, w, o);
        if (!isfinite(factor)) {
          usable = false;
          continue;
        }
        factor_sum += factor;
        factor_min = k == 0 || factor < factor_min ? factor : factor_min;
        factor_max = k == 0 || factor > factor_max ? factor : factor_max;
      }
      // The mean difference is checked first: a low difference gives ERR LOW_DELTA also when one hour has no usable factor.
      const int32_t delta_tenths = static_cast<int32_t>(lroundf(static_cast<float>(delta_sum) / static_cast<float>(need)));
      if (delta_tenths < FRSI_MIN_DELTA_TENTHS) {
        send(sink, "ERR LOW_DELTA\n");
        return;
      }
      const int32_t span = static_cast<int32_t>(lroundf((factor_max - factor_min) * 100.0f));
      if (!usable || span > FRSI_MAX_SPAN_HUNDREDTHS) {
        char f[12];
        if (usable) {
          formatHundredths(f, sizeof(f), span);
        } else {
          snprintf(f, sizeof(f), "--");
        }
        len = static_cast<size_t>(snprintf(text, sizeof(text), "UNSTABLE span=%s\n", f));
      } else {
        const int32_t mean = static_cast<int32_t>(lroundf(factor_sum / static_cast<float>(need) * 100.0f));
        const int32_t pct = mean < 50 ? 50 : (mean > 95 ? 95 : mean);
        char f[3][12];
        formatHundredths(f[0], sizeof(f[0]), mean);
        formatHundredths(f[1], sizeof(f[1]), span);
        formatTenths(f[2], sizeof(f[2]), delta_tenths);
        len = static_cast<size_t>(snprintf(text, sizeof(text),
                                           "FRSI room=%s wall=%s hours=%u frsi=%s span=%s delta_c=%s\nwall_frsi_pct=%ld\n",
                                           names[room], names[wall], static_cast<unsigned>(need), f[0], f[1], f[2],
                                           static_cast<long>(pct)));
      }
    }
    SerialCrc32 crc;
    crc.update(reinterpret_cast<const uint8_t*>(text), len);
    if (!put(sink, reinterpret_cast<const uint8_t*>(text), len)) {
      return;
    }
    char line[24];
    snprintf(line, sizeof(line), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
    send(sink, line);
  }

  // Co-location check: the mean of the last `hours` (6 by default, SPREAD <H> 6 to 24) hourly samples of each sensor with enough temperature and humidity
  // samples, the median of these means and the deviation of each sensor from it. The card and the state stay untouched.
  // A slot that SwitchBotRegistry::isStale reports (with DeviceConfig::staleTimeoutMs()) does not count: it keeps its old hourly
  // samples, so it has no SENSOR line and is not in sensors= and in the median. The outdoor slot (`outdoor_slot`) leaves in the same way. An empty slot has no samples and drops out as before.
  // The OUTLIER check uses the exact deviation (sixths of 0.1 units), the printed value rounds to one decimal, so "0.5" can show
  // OUTLIER when the exact deviation is above the limit.
  // All maths run in integers: a sum of `hours` tenths and its doubled median keep the limit checks exact.
  static void showSpread(Sink& sink, const ClimateHistory& history, const DeviceConfig& config,
                         const SwitchBotRegistry& registry, uint32_t now_ms, size_t hours = SpreadMath::HOURS) {
    static const char* const names[ClimateHistory::SLOT_COUNT] = {
        "onboard", "slot1", "slot2", "slot3", "slot4", "slot5",
        "slot6", "slot7", "slot8", "slot9", "slot10"};
    static const char* const prefixes[ClimateHistory::SLOT_COUNT] = {
        "",       "slot1_", "slot2_", "slot3_", "slot4_", "slot5_",
        "slot6_", "slot7_", "slot8_", "slot9_", "slot10_"};
    SpreadMath::Result r;
    // The outdoor slot never sits beside the indoor sensors: it has no SENSOR line and is not in sensors=.
    SpreadMath::compute(history, r,
                        static_cast<uint16_t>(SpreadMath::freshMask(registry, now_ms, config.staleTimeoutMs()) &
                                              ~SpreadMath::outdoorMask(config.outdoor_slot)),
                        hours);
    const SpreadMath::Limits limits = SpreadMath::Limits::fromConfig(config);
    const size_t count = r.count;
    const int32_t hours2 = 2 * static_cast<int32_t>(r.hours);
    char text[1024];
    size_t len = 0;
    if (!r.enough()) {
      len = static_cast<size_t>(snprintf(text, sizeof(text), "SKIP sensors=%u\n", static_cast<unsigned>(count)));
    } else {
      char f[2][12];
      formatTenths(f[0], sizeof(f[0]), divRound(r.t_med2, hours2));
      formatTenths(f[1], sizeof(f[1]), divRound(r.rh_med2, hours2));
      len = static_cast<size_t>(snprintf(text, sizeof(text), "SPREAD sensors=%u temp_median=%s rh_median=%s\n",
                                         static_cast<unsigned>(count), f[0], f[1]));
      for (size_t k = 0; k < count; ++k) {
        const int32_t t_dev2 = r.t_dev2[k];
        const int32_t rh_dev2 = r.rh_dev2[k];
        const bool outlier = SpreadMath::isOutlier(r, k, limits);
        formatTenths(f[0], sizeof(f[0]), divRound(t_dev2, hours2));
        formatTenths(f[1], sizeof(f[1]), divRound(rh_dev2, hours2));
        len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "SENSOR %s temp_dev=%s rh_dev=%s %s\n",
                                            names[r.sensor_of[k]], f[0], f[1], outlier ? "OUTLIER" : "OK"));
        if (outlier) {
          // Proposal: active offset + median - sensor mean (the mean is sum / hours), clamped to the key range.
          const size_t s = r.sensor_of[k];
          const float temp_active = s == 0 ? config.temp_offset_c : config.slot_temp_offset_c[s - 1];
          const float rh_active = s == 0 ? config.rh_offset_pct : config.slot_rh_offset_pct[s - 1];
          const float temp_lo = s == 0 ? DeviceConfig::MIN_TEMP_OFFSET_C : DeviceConfig::MIN_SLOT_TEMP_OFFSET_C;
          const float temp_hi = s == 0 ? DeviceConfig::MAX_TEMP_OFFSET_C : DeviceConfig::MAX_SLOT_TEMP_OFFSET_C;
          const float rh_lo = s == 0 ? DeviceConfig::MIN_RH_OFFSET_PCT : DeviceConfig::MIN_SLOT_RH_OFFSET_PCT;
          const float rh_hi = s == 0 ? DeviceConfig::MAX_RH_OFFSET_PCT : DeviceConfig::MAX_SLOT_RH_OFFSET_PCT;
          const int32_t temp_new = clampTenths(tenths(temp_active) - divRound(t_dev2, hours2), temp_lo, temp_hi);
          const int32_t rh_new = clampTenths(tenths(rh_active) - divRound(rh_dev2, hours2), rh_lo, rh_hi);
          formatTenths(f[0], sizeof(f[0]), temp_new);
          formatTenths(f[1], sizeof(f[1]), rh_new);
          len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "%stemp_offset_c=%s %srh_offset_pct=%s\n",
                                              prefixes[s], f[0], prefixes[s], f[1]));
        }
      }
    }
    SerialCrc32 crc;
    crc.update(reinterpret_cast<const uint8_t*>(text), len);
    if (!put(sink, reinterpret_cast<const uint8_t*>(text), len)) {
      return;
    }
    char line[24];
    snprintf(line, sizeof(line), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
    send(sink, line);
  }

  // Minimum, maximum and mean of the hourly samples of each sensor since the last local midnight of `now` (see TODAY above). `now_ms`
  // is the RTC-backed clock of `history.last_sample_ms`. The card and the state stay untouched.
  // `only_sensor` 0 to 10 sends only the row of that sensor (-1 sends every row).
  // `mold_limit_rh` and `dry_limit_rh` (whole %RH, the config keys mold_alert_limit_rh and dry_alert_limit_rh) feed mold_h and dry_h.
  // `heat_limit_c` (whole C, the key heat_alert_limit_c) feeds heat_h (ClimateHistory::heatHoursNewest over the same pairs
  // since midnight; empty for limit 0 and an unknown count). The defaults (mold 70, dry 0, heat 0) only serve host tests; the sketch always passes the config values.
  static void showToday(Sink& sink, const ClimateHistory& history, const RtcTime& now, uint32_t now_ms,
                        int only_sensor = -1, uint8_t mold_limit_rh = DeviceConfig::DEFAULT_MOLD_ALERT_LIMIT_RH,
                        uint8_t dry_limit_rh = 0, uint8_t heat_limit_c = 0) {
    static const char* const names[ClimateHistory::SLOT_COUNT] = {
        "onboard", "slot1", "slot2", "slot3", "slot4", "slot5",
        "slot6", "slot7", "slot8", "slot9", "slot10"};
    if (!now.valid || now.oscillator_stopped || now.year < ClimateHistory::RESTORE_MIN_TRUSTED_YEAR || now.year > 2099 ||
        !NtpTime::isValidDate(now.year, now.month, now.day) || now.hour > 23 || now.minute > 59) {
      send(sink, "ERR NO_TIME\n");
      return;
    }
    const int64_t since_midnight_min = static_cast<int64_t>(now.hour) * 60 + now.minute;
    const int64_t newest_age_min =
        history.has_sampled ? static_cast<int64_t>(static_cast<uint32_t>(now_ms - history.last_sample_ms) / 60000UL) : 0;
    // number of newest samples (0 is the newest) that lie after midnight
    const int64_t fit = history.has_sampled && newest_age_min <= since_midnight_min
                            ? (since_midnight_min - newest_age_min) / 60 + 1
                            : 0;
    char text[1536];
    size_t len = static_cast<size_t>(snprintf(
        text, sizeof(text),
        "BEGIN TODAY\nsensor,name,hours,temp_min_c,temp_max_c,temp_mean_c,rh_min_pct,rh_max_pct,rh_mean_pct,mold_h,dry_h,heat_h\n"));
    for (size_t i = 0; i < ClimateHistory::SLOT_COUNT; ++i) {
      if (only_sensor >= 0 && i != static_cast<size_t>(only_sensor)) {
        continue;
      }
      const TemperatureHistory& th = history.temperatures[i];
      const HumidityHistory& hh = history.slots[i];
      if (th.size() == 0 && hh.size() == 0) {
        continue;
      }
      char t_field[3][12] = {"", "", ""};
      char h_field[3][12] = {"", "", ""};
      size_t t_n = th.size() < static_cast<size_t>(fit) ? th.size() : static_cast<size_t>(fit);
      size_t h_n = hh.size() < static_cast<size_t>(fit) ? hh.size() : static_cast<size_t>(fit);
      if (t_n > 0) {
        int32_t lo = th.at(th.size() - 1);
        int32_t hi = lo;
        int32_t sum = 0;
        for (size_t k = 0; k < t_n; ++k) {
          const int32_t v = th.at(th.size() - 1 - k);
          lo = v < lo ? v : lo;
          hi = v > hi ? v : hi;
          sum += v;
        }
        formatTenths(t_field[0], sizeof(t_field[0]), lo);
        formatTenths(t_field[1], sizeof(t_field[1]), hi);
        formatTenths(t_field[2], sizeof(t_field[2]), divRound(sum, static_cast<int32_t>(t_n)));
      }
      if (h_n > 0) {
        int32_t lo = hh.at(hh.size() - 1);
        int32_t hi = lo;
        int32_t sum = 0;
        for (size_t k = 0; k < h_n; ++k) {
          const int32_t v = hh.at(hh.size() - 1 - k);
          lo = v < lo ? v : lo;
          hi = v > hi ? v : hi;
          sum += v;
        }
        formatTenths(h_field[0], sizeof(h_field[0]), lo);
        formatTenths(h_field[1], sizeof(h_field[1]), hi);
        formatTenths(h_field[2], sizeof(h_field[2]), divRound(sum, static_cast<int32_t>(h_n)));
      }
      // Hours above the mold limit (strict) and at or below the dry limit, over the same samples as the humidity fields.
      char mold_field[8] = "";
      char dry_field[8] = "";
      if (h_n > 0) {
        snprintf(mold_field, sizeof(mold_field), "%u",
                 static_cast<unsigned>(hh.countAboveNewest(h_n, static_cast<uint16_t>(mold_limit_rh * 10u))));
        if (dry_limit_rh > 0) {
          snprintf(dry_field, sizeof(dry_field), "%u",
                   static_cast<unsigned>(hh.countAtOrBelowNewest(h_n, static_cast<uint16_t>(dry_limit_rh * 10u))));
        }
      }
      // Hot pairs among the newest samples since midnight (the larger of the two counts, like `hours`).
      char heat_field[12] = "";
      const int heat = history.heatHoursNewest(i, t_n > h_n ? t_n : h_n, heat_limit_c);
      if (heat >= 0) {
        snprintf(heat_field, sizeof(heat_field), "%u", static_cast<unsigned>(heat));
      }
      const int row = snprintf(text + len, sizeof(text) - len, "%u,%s,%u,%s,%s,%s,%s,%s,%s,%s,%s,%s\n",
                               static_cast<unsigned>(i), names[i], static_cast<unsigned>(t_n > h_n ? t_n : h_n),
                               t_field[0], t_field[1], t_field[2], h_field[0], h_field[1], h_field[2], mold_field,
                               dry_field, heat_field);
      if (row < 0 || static_cast<size_t>(row) >= sizeof(text) - len) {
        break; // cannot happen with 11 rows of at most 90 bytes (text holds 1536); never read past the buffer
      }
      len += static_cast<size_t>(row);
    }
    SerialCrc32 crc;
    crc.update(reinterpret_cast<const uint8_t*>(text), len);
    if (!put(sink, reinterpret_cast<const uint8_t*>(text), len)) {
      return;
    }
    char line[24];
    snprintf(line, sizeof(line), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
    send(sink, line);
  }

  // Checks one sensor against saturated sodium chloride, or with `magnesium` against saturated magnesium chloride
  // (reply "SALT ..." line without the proposal line). The last SALT_HOURS hourly samples must exist in the humidity and
  // the temperature history. The samples already contain the active offset. The card stays untouched.
  static void showSalt(Sink& sink, uint8_t reference, const ClimateHistory& history, const DeviceConfig& config,
                       bool magnesium = false) {
    static const char* const names[ClimateHistory::SLOT_COUNT] = {
        "onboard", "slot1", "slot2", "slot3", "slot4", "slot5",
        "slot6", "slot7", "slot8", "slot9", "slot10"};
    static const char* const prefixes[ClimateHistory::SLOT_COUNT] = {
        "", "slot1_", "slot2_", "slot3_", "slot4_", "slot5_",
        "slot6_", "slot7_", "slot8_", "slot9_", "slot10_"};
    if (reference >= ClimateHistory::SLOT_COUNT) {
      return;
    }
    const HumidityHistory& rh = history.slots[reference];
    const TemperatureHistory& temp = history.temperatures[reference];
    const size_t hours = rh.size() < temp.size() ? rh.size() : temp.size();
    char text[256];
    size_t len = 0;
    if (hours < SALT_HOURS) {
      len = static_cast<size_t>(snprintf(text, sizeof(text), "SKIP %s hours=%u\n", names[reference],
                                         static_cast<unsigned>(hours)));
    } else {
      int32_t rh_sum = 0;
      int32_t temp_sum = 0;
      int32_t rh_min = rh.at(rh.size() - 1);
      int32_t rh_max = rh_min;
      for (size_t k = 0; k < SALT_HOURS; ++k) {
        const int32_t r = rh.at(rh.size() - 1 - k);
        rh_sum += r;
        temp_sum += temp.at(temp.size() - 1 - k);
        rh_min = r < rh_min ? r : rh_min;
        rh_max = r > rh_max ? r : rh_max;
      }
      const float count = static_cast<float>(SALT_HOURS);
      const float rh_mean = static_cast<float>(rh_sum) / count; // 0.1 steps
      const float temp_mean = static_cast<float>(temp_sum) / count / 10.0f;
      const int32_t span = rh_max - rh_min;
      const float ref = magnesium ? ClimateMath::magnesiumReferenceRh(temp_mean) : ClimateMath::saltReferenceRh(temp_mean);
      if (span > SALT_MAX_SPAN_TENTHS) {
        char f[12];
        formatTenths(f, sizeof(f), span);
        len = static_cast<size_t>(snprintf(text, sizeof(text), "UNSTABLE %s span=%s\n", names[reference], f));
      } else if (!isfinite(ref)) {
        send(sink, "ERR BAD_TEMP\n");
        return;
      } else {
        const bool onboard = reference == 0;
        const float active = onboard ? config.rh_offset_pct : config.slot_rh_offset_pct[reference - 1];
        const float lo = onboard ? DeviceConfig::MIN_RH_OFFSET_PCT : DeviceConfig::MIN_SLOT_RH_OFFSET_PCT;
        const float hi = onboard ? DeviceConfig::MAX_RH_OFFSET_PCT : DeviceConfig::MAX_SLOT_RH_OFFSET_PCT;
        const float ref_tenths = static_cast<float>(lroundf(ref * 10.0f)); // the printed value is the one that counts
        char f[5][12];
        formatTenths(f[0], sizeof(f[0]), static_cast<int32_t>(lroundf(rh_mean)));
        formatTenths(f[1], sizeof(f[1]), span);
        formatTenths(f[2], sizeof(f[2]), static_cast<int32_t>(lroundf(temp_mean * 10.0f)));
        formatTenths(f[3], sizeof(f[3]), static_cast<int32_t>(lroundf(ref_tenths)));
        len = static_cast<size_t>(snprintf(text, sizeof(text),
                                           "SALT %s hours=%u rh_mean=%s rh_span=%s temp_mean=%s reference=%s\n",
                                           names[reference], static_cast<unsigned>(SALT_HOURS), f[0], f[1], f[2], f[3]));
        if (!magnesium) {
          const int32_t proposal =
              clampTenths(lroundf(static_cast<float>(tenths(active)) + ref_tenths - rh_mean), lo, hi);
          formatTenths(f[4], sizeof(f[4]), proposal);
          len += static_cast<size_t>(
              snprintf(text + len, sizeof(text) - len, "%srh_offset_pct=%s\n", prefixes[reference], f[4]));
        }
      }
    }
    SerialCrc32 crc;
    crc.update(reinterpret_cast<const uint8_t*>(text), len);
    if (!put(sink, reinterpret_cast<const uint8_t*>(text), len)) {
      return;
    }
    char line[24];
    snprintf(line, sizeof(line), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
    send(sink, line);
  }

  // Sends the 24-hour histories as CSV. A sensor has one row per age up to the longest of its histories (sensor 0
  // includes the noise history). Each history counts its age from its own newest sample: the hourly write pushes all
  // histories in one step, and a sensor without a fresh reading skips its step, so a gap can shift that sensor's age_h. The card stays untouched. A short write ends the reply, so the host sees no CRC32 line.
  // `only_sensor` 0 to 10 sends the rows of that sensor with the same header; a negative value sends all sensors.
  // `max_hours` above 0 sends only the rows with age_h below it; the CRC32 line covers the filtered bytes.
  static void showHistory(Sink& sink, const ClimateHistory& history, const NoiseHistory& noise, int only_sensor = -1,
                          int max_hours = 0) {
    SerialCrc32 crc;
    auto emit = [&](const char* text) {
      const size_t len = strlen(text);
      if (!put(sink, reinterpret_cast<const uint8_t*>(text), len)) {
        return false;
      }
      crc.update(reinterpret_cast<const uint8_t*>(text), len);
      return true;
    };
    if (!emit("BEGIN HISTORY\n") || !emit("sensor,age_h,temp_c,rh_pct,noise_db\n")) {
      return;
    }
    for (size_t s = 0; s < ClimateHistory::SLOT_COUNT; ++s) {
      if (only_sensor >= 0 && s != static_cast<size_t>(only_sensor)) {
        continue;
      }
      const size_t temps = history.temperatures[s].size();
      const size_t rhs = history.slots[s].size();
      const size_t noises = s == ClimateHistory::ONBOARD ? noise.size() : 0;
      size_t hours = temps > rhs ? temps : rhs;
      hours = noises > hours ? noises : hours;
      if (max_hours > 0 && hours > static_cast<size_t>(max_hours)) {
        hours = static_cast<size_t>(max_hours);
      }
      for (size_t age = 0; age < hours; ++age) {
        char temp[12] = "", rh[12] = "", db[8] = "";
        if (age < temps) {
          formatTenths(temp, sizeof(temp), history.temperatures[s].at(temps - 1 - age));
        }
        if (age < rhs) {
          formatTenths(rh, sizeof(rh), history.slots[s].at(rhs - 1 - age));
        }
        if (age < noises) {
          const uint8_t v = noise.at(noises - 1 - age);
          if (NoiseHistory::isValid(v)) {
            snprintf(db, sizeof(db), "%u", static_cast<unsigned>(v));
          }
        }
        char line[48];
        snprintf(line, sizeof(line), "%u,%u,%s,%s,%s\n", static_cast<unsigned>(s), static_cast<unsigned>(age), temp, rh, db);
        if (!emit(line)) {
          return;
        }
      }
    }
    char line[24];
    snprintf(line, sizeof(line), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
    send(sink, line);
  }

  // Sends the newest `count` rows (1 to 72) of the climate files as CSV (see TAIL above). Storage::readTail(path, buf, capacity,
  // len, whole) returns 0 for a read, 1 for a missing file and 2 for a read error; `whole` is true when the buffer starts at the
  // file start. The buffer holds one file at a time: the current file is read first, the previous file only when it has fewer
  // than `count` rows, then the current file is read again for the output. A short write ends the reply (no CRC32 line).
  static void showTail(Storage& storage, Sink& sink, uint8_t count, const RtcTime& now) {
    char path[SdLogCodec::MAX_PATH_SIZE];
    char older_path[SdLogCodec::MAX_PATH_SIZE];
    if (count < 1 || count > SdLogCodec::TAIL_MAX_ROWS) {
      send(sink, "ERR BAD_ARG\n");
      return;
    }
    if (!storage.mount()) {
      send(sink, "ERR NO_CARD\n");
      return;
    }
    // Static: the buffer would use the whole loop task stack, and the console runs one command at a time.
    static char buf[SdLogCodec::TAIL_BYTES];
    SdLogCodec::TailSpan newer[SdLogCodec::TAIL_MAX_ROWS];
    SdLogCodec::TailSpan older[SdLogCodec::TAIL_MAX_ROWS];
    size_t newer_count = 0;
    size_t older_count = 0;
    size_t len = 0;
    bool whole = false;
    bool read_failed = false;
    if (!now.valid || SdLogCodec::formatFilename(&now, path, sizeof(path)) == 0) {
      storage.unmount();
      send(sink, "ERR NO_FILE\n");
      return;
    }
    const char* name = path + strlen(SdLogCodec::MOUNT_POINT) + 1; // without the slash
    int status = storage.readTail(name, buf, sizeof(buf), len, whole);
    bool have_file = status == 0;
    read_failed = status == 2;
    const bool current_whole = whole; // the window reached the file start: no hours lie before it in this file
    if (have_file) {
      newer_count = SdLogCodec::collectTailRows(buf, len, whole, newer, count);
    }
    size_t older_wanted = 0;
    bool older_ok = false;
    RtcTime before;
    if (!read_failed && newer_count < count && (!have_file || current_whole) && SdLogCodec::previousMonth(now, before) &&
        SdLogCodec::formatFilename(&before, older_path, sizeof(older_path)) != 0) {
      const char* older_name = older_path + strlen(SdLogCodec::MOUNT_POINT) + 1;
      status = storage.readTail(older_name, buf, sizeof(buf), len, whole);
      if (status == 0) {
        have_file = true;
        older_ok = true;
        older_count = SdLogCodec::collectTailRows(buf, len, whole, older, count - newer_count);
        older_wanted = older_count;
      } else if (status == 2) {
        read_failed = true;
      }
    }
    if (read_failed || !have_file) {
      storage.unmount();
      send(sink, read_failed ? "ERR READ_FAILED\n" : "ERR NO_FILE\n");
      return;
    }
    SerialCrc32 crc;
    auto emit = [&](const char* text, size_t n) {
      if (!put(sink, reinterpret_cast<const uint8_t*>(text), n)) {
        return false;
      }
      crc.update(reinterpret_cast<const uint8_t*>(text), n);
      return true;
    };
    char head[32];
    const size_t total = newer_count + older_wanted;
    snprintf(head, sizeof(head), "BEGIN TAIL %u\n", static_cast<unsigned>(total));
    char header[512];
    const size_t header_len = SdLogCodec::formatHeader(header, sizeof(header));
    bool ok = emit(head, strlen(head)) && header_len > 0 && emit(header, header_len);
    // The previous file rows first: the buffer still holds that file when it was read last.
    if (ok && older_ok) {
      for (size_t i = 0; ok && i < older_count; ++i) {
        ok = emit(buf + older[i].start, older[i].len) && emit("\n", 1);
      }
    }
    if (ok && newer_count > 0) {
      status = storage.readTail(name, buf, sizeof(buf), len, whole);
      if (status != 0) {
        // The file changed under the read: stop without a CRC32 line, the host rejects the reply.
        storage.unmount();
        send(sink, "ERR READ_FAILED\n");
        return;
      }
      const size_t again = SdLogCodec::collectTailRows(buf, len, whole, newer, count);
      if (again != newer_count) {
        storage.unmount();
        send(sink, "ERR READ_FAILED\n");
        return;
      }
      for (size_t i = 0; ok && i < newer_count; ++i) {
        ok = emit(buf + newer[i].start, newer[i].len) && emit("\n", 1);
      }
    }
    storage.unmount();
    if (!ok) {
      return;
    }
    char line[24];
    snprintf(line, sizeof(line), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
    send(sink, line);
  }

  // Sends the newest `count` rows (1 to 31) of the daily files as CSV (see DAYS above). The sources run from the newest to the
  // oldest: daily_v7, daily_v6 and daily_v5 of the current year, then the same three of the previous year. The next source is read
  // only while fewer than `count` rows are found and the window of the last file that exists reaches its start (no gap). A missing
  // file is skipped; a read error ends the command. One file sits in the buffer at a time, so the rows are sent oldest source first
  // and each source except the last read one is read again. A daily_v6 row (94 columns) gets 11 empty heat_h fields, a daily_v5 row
  // (83 columns) gets 22 empty dry_h and heat_h fields.
  static void showDays(Storage& storage, Sink& sink, uint8_t count, const RtcTime& now) {
    constexpr size_t SOURCE_COUNT = 6; // daily_v7, daily_v6, daily_v5 of this year and of the year before
    if (count < 1 || count > SdLogCodec::DAYS_MAX_ROWS) {
      send(sink, "ERR BAD_ARG\n");
      return;
    }
    if (!storage.mount()) {
      send(sink, "ERR NO_CARD\n");
      return;
    }
    static const char HEAT_PADDING[] = ",,,,,,,,,,,";             // 11 empty heat_h fields of a daily_v6 row
    static const char DRY_HEAT_PADDING[] = ",,,,,,,,,,,,,,,,,,,,,,"; // 22 empty dry_h and heat_h fields of a daily_v5 row
    auto rowFields = [](const char* text, size_t n) {
      size_t commas = 0;
      for (size_t i = 0; i < n; ++i) {
        commas += text[i] == ',' ? 1 : 0;
      }
      return commas + 1;
    };
    // static: the buffers would use the whole loop task stack
    static char buf[SdLogCodec::DAYS_BYTES];
    struct Source {
      char path[SdLogCodec::MAX_PATH_SIZE];
      SdLogCodec::TailSpan spans[SdLogCodec::DAYS_MAX_ROWS];
      size_t rows;
      bool read;
    };
    Source sources[SOURCE_COUNT]; // about 1.1 KB of stack; only the 16 KB read buffer is static
    for (size_t i = 0; i < SOURCE_COUNT; ++i) {
      const uint16_t year = static_cast<uint16_t>(now.year - (i / 3));
      const size_t n = (i % 3 == 0)   ? SdLogCodec::formatDailyFilename(year, sources[i].path, sizeof(sources[i].path))
                       : (i % 3 == 1) ? SdLogCodec::formatDailyFilenameV6(year, sources[i].path, sizeof(sources[i].path))
                                      : SdLogCodec::formatDailyFilenameV5(year, sources[i].path, sizeof(sources[i].path));
      if (n == 0) {
        sources[i].path[0] = '\0';
      }
      sources[i].rows = 0;
      sources[i].read = false;
    }
    if (!now.valid || sources[0].path[0] == '\0') {
      storage.unmount();
      send(sink, "ERR NO_FILE\n");
      return;
    }
    auto nameOf = [](const Source& s) { return s.path + strlen(SdLogCodec::MOUNT_POINT) + 1; }; // without the slash
    size_t total = 0;
    size_t read_count = 0;
    size_t last_read = 0;
    bool read_failed = false;
    for (size_t i = 0; i < SOURCE_COUNT && total < count && !read_failed; ++i) {
      if (sources[i].path[0] == '\0') {
        continue;
      }
      size_t len = 0;
      bool whole = false;
      const int status = storage.readTail(nameOf(sources[i]), buf, sizeof(buf), len, whole);
      if (status == 2) {
        read_failed = true;
      } else if (status == 0) {
        sources[i].read = true;
        sources[i].rows = SdLogCodec::collectDailyTailRows(buf, len, whole, sources[i].spans, count - total);
        total += sources[i].rows;
        ++read_count;
        last_read = i;
        if (!whole) {
          break; // the window does not reach the file start: older rows would leave a gap
        }
      }
    }
    if (read_failed || read_count == 0) {
      storage.unmount();
      send(sink, read_failed ? "ERR READ_FAILED\n" : "ERR NO_FILE\n");
      return;
    }
    SerialCrc32 crc;
    auto emit = [&](const char* text, size_t n) {
      if (!put(sink, reinterpret_cast<const uint8_t*>(text), n)) {
        return false;
      }
      crc.update(reinterpret_cast<const uint8_t*>(text), n);
      return true;
    };
    char head[32];
    snprintf(head, sizeof(head), "BEGIN DAYS %u\n", static_cast<unsigned>(total));
    char header[SdLogCodec::DAILY_HEADER_SIZE];
    const size_t header_len = SdLogCodec::formatDailyHeader(header, sizeof(header));
    bool ok = emit(head, strlen(head)) && header_len > 0 && emit(header, header_len);
    for (size_t k = SOURCE_COUNT; ok && k-- > 0;) {
      Source& s = sources[k];
      if (!s.read || s.rows == 0) {
        continue;
      }
      if (!(k == last_read && read_count > 1)) {
        // The buffer holds another file: read this one again and check that the rows did not change.
        size_t len = 0;
        bool whole = false;
        SdLogCodec::TailSpan again[SdLogCodec::DAYS_MAX_ROWS];
        if (storage.readTail(nameOf(s), buf, sizeof(buf), len, whole) != 0 ||
            SdLogCodec::collectDailyTailRows(buf, len, whole, again, s.rows) != s.rows) {
          storage.unmount();
          send(sink, "ERR READ_FAILED\n");
          return;
        }
        memcpy(s.spans, again, s.rows * sizeof(again[0]));
      }
      for (size_t i = 0; ok && i < s.rows; ++i) {
        const size_t fields = rowFields(buf + s.spans[i].start, s.spans[i].len);
        const char* padding = fields == SdLogCodec::DAILY_COLUMNS_V6 ? HEAT_PADDING
                              : fields == SdLogCodec::DAILY_COLUMNS_V5 ? DRY_HEAT_PADDING
                                                                       : "";
        ok = emit(buf + s.spans[i].start, s.spans[i].len) && emit(padding, strlen(padding)) && emit("\n", 1);
      }
    }
    storage.unmount();
    if (!ok) {
      return;
    }
    char line[24];
    snprintf(line, sizeof(line), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
    send(sink, line);
  }

  // Sends the RejectLog copy of the last BLE scan from the RTC state as CSV: one row per rejected MAC with its format,
  // reason (FULL, UNASSIGNED, DISABLED, JUMP or WEAK) and packet count, then a DROPPED,<N> row when MACs were lost. The card and
  // the state stay untouched. A short write ends the reply, so the host sees no CRC32 line.
  static void showRejects(Sink& sink, const RejectLog& log) {
    static const char* const FORMATS[BLE_FORMAT_COUNT] = {"WOIO", "METER", "BTHOME", "ATC", "THERMOPRO"};
    static const char* const REASONS[] = {"NONE", "FULL", "UNASSIGNED", "DISABLED", "JUMP", "WEAK"};
    SerialCrc32 crc;
    auto emit = [&](const char* text) {
      const size_t len = strlen(text);
      if (!put(sink, reinterpret_cast<const uint8_t*>(text), len)) {
        return false;
      }
      crc.update(reinterpret_cast<const uint8_t*>(text), len);
      return true;
    };
    if (!emit("BEGIN REJECTS\n") || !emit("mac,format,reason,count\n")) {
      return;
    }
    for (size_t i = 0; i < log.size(); ++i) {
      const RejectLog::Entry& e = log.entries[i];
      char line[64];
      snprintf(line, sizeof(line), "%02X:%02X:%02X:%02X:%02X:%02X,%s,%s,%u\n", static_cast<unsigned>(e.mac[0]),
               static_cast<unsigned>(e.mac[1]), static_cast<unsigned>(e.mac[2]), static_cast<unsigned>(e.mac[3]),
               static_cast<unsigned>(e.mac[4]), static_cast<unsigned>(e.mac[5]),
               FORMATS[static_cast<size_t>(e.format) < BLE_FORMAT_COUNT ? static_cast<size_t>(e.format) : 0],
               REASONS[static_cast<size_t>(e.reason) <= static_cast<size_t>(RejectReason::WEAK) ? static_cast<size_t>(e.reason) : 0],
               static_cast<unsigned>(e.count));
      if (!emit(line)) {
        return;
      }
    }
    if (log.dropped > 0) {
      char line[24];
      snprintf(line, sizeof(line), "DROPPED,%u\n", static_cast<unsigned>(log.dropped));
      if (!emit(line)) {
        return;
      }
    }
    char line[24];
    snprintf(line, sizeof(line), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
    send(sink, line);
  }

  // Sends the BLE scan mask of each occupied registry slot from the RTC copy of ReceptionHistory: header
  // slot,mac,name,scans,received,mask, one row per occupied slot (1 to MAX_SWITCHBOT_SENSORS), then the CRC32 line.
  // mask has `scans` characters, oldest scan first: 1 for a scan with a packet, 0 for a missed scan; received counts the 1
  // characters. name comes from DeviceConfig::nameForMac (empty for an unknown MAC). No card access, no state change.
  // A short write ends the reply, so the host sees no CRC32 line.
  static void showReception(Sink& sink, const SwitchBotRegistry& registry, const ReceptionHistory& reception,
                            const DeviceConfig& config) {
    SerialCrc32 crc;
    auto emit = [&](const char* text) {
      const size_t len = strlen(text);
      if (!put(sink, reinterpret_cast<const uint8_t*>(text), len)) {
        return false;
      }
      crc.update(reinterpret_cast<const uint8_t*>(text), len);
      return true;
    };
    if (!emit("BEGIN RECEPTION\n") || !emit("slot,mac,name,scans,received,mask\n")) {
      return;
    }
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      const SwitchBotReading& r = registry.slot(i);
      if (!r.valid) {
        continue;
      }
      const uint8_t scans = reception.scans(i) < ReceptionHistory::WINDOW ? reception.scans(i) : ReceptionHistory::WINDOW;
      const uint32_t mask = reception.mask(i);
      char bits[ReceptionHistory::WINDOW + 1];
      unsigned received = 0;
      for (uint8_t k = 0; k < scans; ++k) {
        const bool got = ((mask >> (scans - 1 - k)) & 1u) != 0; // bit 0 is the newest scan, the text starts oldest
        bits[k] = got ? '1' : '0';
        received += got ? 1u : 0u;
      }
      bits[scans] = '\0';
      const char* name = config.nameForMac(r.mac);
      char line[96];
      snprintf(line, sizeof(line), "%u,%02X:%02X:%02X:%02X:%02X:%02X,%s,%u,%u,%s\n", static_cast<unsigned>(i + 1),
               static_cast<unsigned>(r.mac[0]), static_cast<unsigned>(r.mac[1]), static_cast<unsigned>(r.mac[2]),
               static_cast<unsigned>(r.mac[3]), static_cast<unsigned>(r.mac[4]), static_cast<unsigned>(r.mac[5]),
               name != nullptr ? name : "", static_cast<unsigned>(scans), received, bits);
      if (!emit(line)) {
        return;
      }
    }
    char line[24];
    snprintf(line, sizeof(line), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
    send(sink, line);
  }

  // Sends the cause of each alert: BEGIN ALERTS, the header alert,active,acked,tiles,limit, one row for each of the 8 alert
  // bits (FREEZER, HUMIDITY, NOISE, SENSOR_LOST, CHARGE_FULL, VENTILATE, SENSOR_BATTERY, HEAT) and the CRC32 line.
  // active is the bit of `alert_mask`, acked the bit of `alert_ack`. tiles are 3 hex digits (bit 0 onboard, bits 1 to 10
  // slots) of the tiles at or beyond the trigger limit now (AlertEvaluator::triggerTiles; SENSOR_BATTERY: the latch
  // `battery_latch`, bits 0 to 9 are slots 1 to 10); a held alert without such a tile gives 000. NOISE and CHARGE_FULL
  // have no tiles (empty). limit is the trigger limit of `limits` (NOISE and SENSOR_BATTERY: `config`) (FREEZER in C with one decimal, HUMIDITY in %RH, NOISE in
  // dB, HEAT in C, SENSOR_BATTERY in %; 0 is off for NOISE, HEAT and SENSOR_BATTERY); empty for SENSOR_LOST, CHARGE_FULL
  // and VENTILATE. RTC copy only, no card access, no state change. A short write ends the reply without a CRC32 line.
  static void showAlerts(Sink& sink, uint8_t alert_mask, uint8_t alert_ack, const Shtc3Reading& onboard,
                         const SwitchBotRegistry& registry, uint32_t now_ms, const AlertLimits& limits,
                         uint16_t battery_latch, const DeviceConfig& config) {
    SerialCrc32 crc;
    auto emit = [&](const char* text) {
      const size_t len = strlen(text);
      if (!put(sink, reinterpret_cast<const uint8_t*>(text), len)) {
        return false;
      }
      crc.update(reinterpret_cast<const uint8_t*>(text), len);
      return true;
    };
    if (!emit("BEGIN ALERTS\n") || !emit("alert,active,acked,tiles,limit\n")) {
      return;
    }
    SwitchBotReading slots[MAX_SWITCHBOT_SENSORS];
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      slots[i] = registry.slot(i);
    }
    const AlertEvaluator::TriggerTiles trigger =
        AlertEvaluator::triggerTiles(onboard, slots, MAX_SWITCHBOT_SENSORS, now_ms, limits);
    const uint16_t battery_tiles = static_cast<uint16_t>((battery_latch & SensorBatteryLatch::ALL_MASK) << 1);
    struct Row {
      const char* name;
      uint8_t bit;
      int tiles; // -1: no tiles
    };
    const Row rows[8] = {
        {"FREEZER", ALERT_FREEZER, trigger.freezer},
        {"HUMIDITY", ALERT_HUMIDITY, trigger.humidity},
        {"NOISE", ALERT_NOISE_HIGH, -1},
        {"SENSOR_LOST", ALERT_SENSOR_LOST, trigger.sensor_lost},
        {"CHARGE_FULL", ALERT_CHARGE_FULL, -1},
        {"VENTILATE", ALERT_VENTILATE, trigger.ventilate},
        {"SENSOR_BATTERY", ALERT_SENSOR_BATTERY, battery_tiles},
        {"HEAT", ALERT_HEAT, trigger.heat},
    };
    for (const Row& row : rows) {
      char tiles[8] = "";
      if (row.tiles >= 0) {
        snprintf(tiles, sizeof(tiles), "%03x", static_cast<unsigned>(row.tiles) & 0x7FFu);
      }
      char limit[16] = "";
      switch (row.bit) {
        case ALERT_FREEZER: snprintf(limit, sizeof(limit), "%.1f", static_cast<double>(limits.freezer_trigger_c)); break;
        case ALERT_HUMIDITY: snprintf(limit, sizeof(limit), "%.0f", static_cast<double>(limits.humidity_trigger_pct)); break;
        case ALERT_NOISE_HIGH: snprintf(limit, sizeof(limit), "%u", static_cast<unsigned>(config.noise_alert_limit_db)); break;
        case ALERT_SENSOR_BATTERY: snprintf(limit, sizeof(limit), "%u", static_cast<unsigned>(config.sensor_battery_limit_pct)); break;
        case ALERT_HEAT: snprintf(limit, sizeof(limit), "%.0f", static_cast<double>(limits.heat_trigger_c)); break;
        default: break;
      }
      char line[64];
      snprintf(line, sizeof(line), "%s,%u,%u,%s,%s\n", row.name, (alert_mask & row.bit) != 0 ? 1u : 0u,
               (alert_ack & row.bit) != 0 ? 1u : 0u, tiles, limit);
      if (!emit(line)) {
        return;
      }
    }
    char line[24];
    snprintf(line, sizeof(line), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
    send(sink, line);
  }

  // Sends the 7-day minimum and maximum of each sensor from the RTC copy as CSV: one row per sensor (0 onboard, 1 to 10 a
  // slot) and day (day 0 is the day before the stamp). The date is empty without a stamp, a gap day gives empty value
  // fields. `only_sensor` 0 to 10 sends only the 7 rows of that sensor, -1 sends all. Values are in 0.5 steps; `mold_h` is the hour count above mold_alert_limit_rh (empty for a gap day); `dry_h` is the hour count at or below dry_alert_limit_rh (empty for a gap day and for limit 0); `heat_h` is the hour count at or above heat_alert_limit_c (empty for a gap day and for limit 0). The card and the state stay untouched. A short write ends the reply, so the host
  // sees no CRC32 line.
  static void showWeek(Sink& sink, const WeekHistory& week, int only_sensor = -1) {
    SerialCrc32 crc;
    auto emit = [&](const char* text) {
      const size_t len = strlen(text);
      if (!put(sink, reinterpret_cast<const uint8_t*>(text), len)) {
        return false;
      }
      crc.update(reinterpret_cast<const uint8_t*>(text), len);
      return true;
    };
    if (!emit("BEGIN WEEK\n") || !emit("sensor,day,date,temp_min_c,temp_max_c,rh_min_pct,rh_max_pct,mold_h,dry_h,heat_h\n")) {
      return;
    }
    for (size_t s = 0; s < WeekHistory::SENSORS; ++s) {
      if (only_sensor >= 0 && s != static_cast<size_t>(only_sensor)) {
        continue;
      }
      for (size_t d = 0; d < WeekHistory::DAYS; ++d) {
        char date[16] = "";
        if (week.hasDate()) {
          int32_t y = 0;
          uint8_t m = 0, dd = 0;
          NtpTime::civilFromDays(
              NtpTime::daysFromCivil(week.stampYear(), week.stampMonth(), week.stampDay()) - 1 - static_cast<int64_t>(d), y, m, dd);
          snprintf(date, sizeof(date), "%04d-%02u-%02u", static_cast<int>(y), static_cast<unsigned>(m), static_cast<unsigned>(dd));
        }
        char f[4][12] = {"", "", "", ""};
        if (week.hasTemperature(s, d)) {
          formatTenths(f[0], sizeof(f[0]), week.temperatureMin(s, d));
          formatTenths(f[1], sizeof(f[1]), week.temperatureMax(s, d));
        }
        if (week.hasHumidity(s, d)) {
          formatTenths(f[2], sizeof(f[2]), week.humidityMin(s, d));
          formatTenths(f[3], sizeof(f[3]), week.humidityMax(s, d));
        }
        char mold[4] = "";
        if (week.hasMoldHours(s, d)) {
          snprintf(mold, sizeof(mold), "%u", static_cast<unsigned>(week.moldHours(s, d)));
        }
        char dry[4] = "";
        if (week.hasDryHours(s, d)) {
          snprintf(dry, sizeof(dry), "%u", static_cast<unsigned>(week.dryHours(s, d)));
        }
        char heat[4] = "";
        if (week.hasHeatHours(s, d)) {
          snprintf(heat, sizeof(heat), "%u", static_cast<unsigned>(week.heatHours(s, d)));
        }
        char line[104];
        snprintf(line, sizeof(line), "%u,%u,%s,%s,%s,%s,%s,%s,%s,%s\n", static_cast<unsigned>(s), static_cast<unsigned>(d), date, f[0], f[1], f[2], f[3], mold, dry, heat);
        if (!emit(line)) {
          return;
        }
      }
    }
    char line[24];
    snprintf(line, sizeof(line), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
    send(sink, line);
  }

  // Sends the event ring from the RTC copy as CSV, oldest event first, with the rows of events_v1_YYYY.csv. A ring with
  // lost events starts with the EVENTS_DROPPED row of the current time (no row for an invalid time, as in the file).
  // The card and the ring stay untouched. A short write ends the reply, so the host sees no CRC32 line.
  // `newest` 1 to 16 sends only the newest rows (oldest first); the EVENTS_DROPPED row stays only when `newest` covers
  // all stored events. 0 sends the whole ring.
  static void showEvents(Sink& sink, const EventRing& ring, const RtcTime& now, size_t newest = 0) {
    SerialCrc32 crc;
    auto emit = [&](const char* text, size_t len) {
      if (!put(sink, reinterpret_cast<const uint8_t*>(text), len)) {
        return false;
      }
      crc.update(reinterpret_cast<const uint8_t*>(text), len);
      return true;
    };
    static const char begin[] = "BEGIN EVENTS\ntime,event,detail\n";
    if (!emit(begin, sizeof(begin) - 1)) {
      return;
    }
    char row[SdLogCodec::MAX_EVENT_ROW_SIZE];
    const size_t first = (newest > 0 && newest < ring.size()) ? ring.size() - newest : 0;
    const size_t dropped_len =
        first == 0 ? SdLogCodec::formatEventsDroppedRow(now, ring.dropped(), row, sizeof(row)) : 0;
    if (dropped_len > 0 && !emit(row, dropped_len)) {
      return;
    }
    for (size_t i = first; i < ring.size(); ++i) {
      const size_t len = SdLogCodec::formatEventRow(ring.at(i), row, sizeof(row));
      if (len > 0 && !emit(row, len)) {
        return;
      }
    }
    char line[24];
    snprintf(line, sizeof(line), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
    send(sink, line);
  }

  // Sends the result of one BLE scan. `run_scan(ScanCollector&)` runs the scan and fills the collector; it returns false
  // when the scan does not start ("ERR SCAN_FAILED"). `registry` only gives the slot column: nothing is stored.
  // A short write ends the reply, so the host sees no CRC32 line.
  template <typename RunScan>
  static void scan(Sink& sink, const SwitchBotRegistry& registry, RunScan run_scan) {
    ScanCollector collector;
    if (!run_scan(collector)) {
      send(sink, "ERR SCAN_FAILED\n");
      return;
    }
    showScan(sink, collector, registry);
  }

  static void showScan(Sink& sink, const ScanCollector& collector, const SwitchBotRegistry& registry) {
    SerialCrc32 crc;
    auto emit = [&](const char* text) {
      const size_t len = strlen(text);
      if (!put(sink, reinterpret_cast<const uint8_t*>(text), len)) {
        return false;
      }
      crc.update(reinterpret_cast<const uint8_t*>(text), len);
      return true;
    };
    if (!emit("BEGIN SCAN\nmac,format,rssi,slot,temp_c,rh_pct,battery_pct\n")) {
      return;
    }
    for (size_t i = 0; i < collector.size(); ++i) {
      const ScanCollector::Entry& e = collector.at(i);
      const SwitchBotReading& r = e.reading;
      char slot[4] = "-", temp[16], battery[8] = "";
      const uint8_t slot_no = ScanCollector::slotOf(registry, r.mac);
      if (slot_no != 0) {
        snprintf(slot, sizeof(slot), "%u", static_cast<unsigned>(slot_no));
      }
      formatTenths(temp, sizeof(temp), tenths(r.temperature_c));
      if (r.battery_pct >= 0) {
        snprintf(battery, sizeof(battery), "%d", static_cast<int>(r.battery_pct));
      }
      char row[96];
      snprintf(row, sizeof(row), "%02X:%02X:%02X:%02X:%02X:%02X,%s,%d,%s,%s,%u,%s\n", static_cast<unsigned>(r.mac[0]),
               static_cast<unsigned>(r.mac[1]), static_cast<unsigned>(r.mac[2]), static_cast<unsigned>(r.mac[3]),
               static_cast<unsigned>(r.mac[4]), static_cast<unsigned>(r.mac[5]), ScanCollector::formatName(e.format),
               static_cast<int>(r.rssi), slot, temp, static_cast<unsigned>(r.humidity_pct), battery);
      if (!emit(row)) {
        return;
      }
    }
    if (collector.dropped() > 0) {
      char row[32];
      snprintf(row, sizeof(row), "DROPPED,%u\n", static_cast<unsigned>(collector.dropped()));
      if (!emit(row)) {
        return;
      }
    }
    char line[24];
    snprintf(line, sizeof(line), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
    send(sink, line);
  }

  // Sends a live snapshot of all sensors and of the power state. The card stays untouched.
  static void showStatus(Sink& sink, const SerialStatusSnapshot& snap) {
    if (snap.registry == nullptr || snap.history == nullptr || snap.config == nullptr) {
      return;
    }
    static const char* const defaults[ClimateHistory::SLOT_COUNT] = {
        "onboard", "slot1", "slot2", "slot3", "slot4", "slot5",
        "slot6", "slot7", "slot8", "slot9", "slot10"};
    const DeviceConfig& cfg = *snap.config;
    const SwitchBotRegistry cal = snap.registry->calibrated(cfg.slot_temp_offset_c, cfg.slot_rh_offset_pct, cfg.slot_rh_gain);
    // Static: buffer does not fit the default Arduino loopTask stack.
    static char text[3072];
    size_t len = 0;
    for (size_t i = 0; i < ClimateHistory::SLOT_COUNT; ++i) {
      // A slot name follows the MAC of the sensor in the slot (reading, else fixed MAC), not the slot index.
      const char* raw = cfg.onboard_name;
      if (i > 0) {
        const SwitchBotReading& sr = cal.slot(i - 1);
        raw = cfg.nameForMac(sr.valid ? sr.mac : (cfg.hasSlotMac(i - 1) ? cfg.slot_mac[i - 1] : nullptr));
      }
      char name[DeviceConfig::NAME_MAX_CHARS + 1];
      snprintf(name, sizeof(name), "%s", raw != nullptr && raw[0] != '\0' ? raw : defaults[i]);
      for (char* c = name; *c != '\0'; ++c) {
        *c = *c == ' ' ? '_' : *c;
      }
      char temp[16] = "--", rh[8] = "--", bat[8] = "--", rssi[8] = "--", age[12] = "--";
      const char* state = "EMPTY";
      const char* format_str = "--";
      if (i == 0) {
        format_str = "SHTC3";
        const ClimateHistory& h = *snap.history;
        if (h.temperatures[0].size() > 0) {
          formatTemp(temp, sizeof(temp), h.temperatures[0].at(h.temperatures[0].size() - 1) / 10.0f, cfg.temp_unit);
        }
        if (h.slots[0].size() > 0) {
          snprintf(rh, sizeof(rh), "%d", static_cast<int>((h.slots[0].at(h.slots[0].size() - 1) + 5) / 10));
        }
        if (h.has_sampled && (h.temperatures[0].size() > 0 || h.slots[0].size() > 0)) {
          const uint32_t since = static_cast<uint32_t>(snap.now_ms - h.last_sample_ms);
          snprintf(age, sizeof(age), "%lu", static_cast<unsigned long>(since / 1000UL));
          // The history takes one sample per hour: the onboard sensor is stale after one interval plus the timeout.
          state = since > ClimateHistory::SAMPLE_INTERVAL_MS + cfg.staleTimeoutMs() ? "STALE" : "OK";
        }
      } else {
        if (!cfg.isSlotEnabled(i - 1)) {
          state = "DISABLED";
        } else {
          const SwitchBotReading& r = cal.slot(i - 1);
          if (r.valid) {
            switch (r.format) {
              case BleFormat::WOIO: format_str = "WOIO"; break;
              case BleFormat::METER: format_str = "METER"; break;
              case BleFormat::BTHOME: format_str = "BTHOME"; break;
              case BleFormat::ATC: format_str = "ATC"; break;
              case BleFormat::THERMOPRO: format_str = "THERMOPRO"; break;
            }
            formatTemp(temp, sizeof(temp), r.temperature_c, cfg.temp_unit);
            snprintf(rh, sizeof(rh), "%u", static_cast<unsigned>(r.humidity_pct));
            if (r.battery_pct >= 0) {
              snprintf(bat, sizeof(bat), "%d", static_cast<int>(r.battery_pct));
            }
            snprintf(rssi, sizeof(rssi), "%d", static_cast<int>(r.rssi));
            snprintf(age, sizeof(age), "%lu", static_cast<unsigned long>(static_cast<uint32_t>(snap.now_ms - r.updated_ms) / 1000UL));
            state = r.isStale(snap.now_ms, cfg.staleTimeoutMs()) ? "STALE"
                                           : (SensorBatteryLatch::isSet(snap.sensor_battery_latch, i - 1) ? "BATLOW" : "OK");
          }
        }
      }
      char mac[18] = "--";
      if (i > 0 && cfg.isSlotEnabled(i - 1)) {
        const SwitchBotReading& r = cal.slot(i - 1);
        if (r.valid) {
          snprintf(mac, sizeof(mac), "%02X:%02X:%02X:%02X:%02X:%02X",
                   static_cast<unsigned>(r.mac[0]), static_cast<unsigned>(r.mac[1]),
                   static_cast<unsigned>(r.mac[2]), static_cast<unsigned>(r.mac[3]),
                   static_cast<unsigned>(r.mac[4]), static_cast<unsigned>(r.mac[5]));
        }
      }
      // Same rules as DashboardView::formatMold: a live (OK or BATLOW) sensor with enough samples shows the hours above the limit.
      const bool live = strcmp(state, "OK") == 0 || strcmp(state, "BATLOW") == 0;
      char mold[8] = "--";
      const HumidityHistory& rh_history = snap.history->slots[i];
      if (live && rh_history.size() >= HumidityHistory::MIN_RANGE_SAMPLES) {
        snprintf(mold, sizeof(mold), "%u",
                 static_cast<unsigned>(rh_history.countAbove(static_cast<uint16_t>(cfg.mold_alert_limit_rh * 10u))));
      }
      // dry_h: live sensor with enough samples shows the hours at or below dry_alert_limit_rh; 0 = off gives "--".
      char dry[8] = "--";
      if (live && cfg.dry_alert_limit_rh != 0 && rh_history.size() >= HumidityHistory::MIN_RANGE_SAMPLES) {
        snprintf(dry, sizeof(dry), "%u",
                 static_cast<unsigned>(rh_history.countAtOrBelow(static_cast<uint16_t>(cfg.dry_alert_limit_rh * 10u))));
      }
      // heat_h: live sensor with a usable pair history shows the hours at or above heat_alert_limit_c (ClimateHistory::heatHours); 0 = off, unequal sizes and too few pairs give "--".
      char heat[12] = "--";
      if (live) {
        const int heat_hours = snap.history->heatHours(i, cfg.heat_alert_limit_c);
        if (heat_hours >= 0) {
          snprintf(heat, sizeof(heat), "%d", heat_hours);
        }
      }
      // Same rules as the trend marks of the tiles: a live sensor shows the trend of its 24-hour histories.
      const char* temp_trend = live ? TrendClassifier::statusText(TrendClassifier::temperature(snap.history->temperatures[i])) : "--";
      const char* rh_trend = live ? TrendClassifier::statusText(TrendClassifier::humidity(rh_history)) : "--";
      len += static_cast<size_t>(snprintf(
          text + len, sizeof(text) - len,
          "SENSOR %u %s temp=%s rh=%s bat=%s rssi=%s age=%s state=%s format=%s mac=%s mold_h=%s temp_trend=%s rh_trend=%s dry_h=%s heat_h=%s\n",
          static_cast<unsigned>(i), name, temp, rh, bat, rssi, age, state, format_str, mac, mold, temp_trend, rh_trend, dry, heat));
      if (len >= sizeof(text)) {
        return; // a truncated frame has no valid CRC: send nothing
      }
    }
    char volts[12] = "--", pct[8] = "--";
    if (snap.battery.valid) {
      snprintf(volts, sizeof(volts), "%.2f", static_cast<double>(snap.battery.voltage_v));
      if (snap.battery.percentage >= 0) {
        snprintf(pct, sizeof(pct), "%d", static_cast<int>(snap.battery.percentage));
      }
    }
    char free_mb[16] = "--";
    if (snap.sd_free_mb >= 0) {
      snprintf(free_mb, sizeof(free_mb), "%ld", static_cast<long>(snap.sd_free_mb));
    }
    if (snap.sd_status == SdStatus::UNKNOWN) {
      len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "SD status=UNKNOWN age=-- free_mb=%s\n", free_mb));
    } else {
      len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "SD status=%s age=%lu free_mb=%s\n",
                                          SdStatusCodec::name(snap.sd_status),
                                          static_cast<unsigned long>(static_cast<uint32_t>(snap.now_ms - snap.sd_status_ms) / 1000UL),
                                          free_mb));
    }
    if (len >= sizeof(text)) {
      return;
    }
    char abnormal[12] = "--";
    if (snap.abnormal_resets >= 0) {
      snprintf(abnormal, sizeof(abnormal), "%ld", static_cast<long>(snap.abnormal_resets));
    }
    len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "RESET reason=%s abnormal=%s\n",
                                        snap.reset_reason != nullptr ? snap.reset_reason : "UNKNOWN", abnormal));
    if (len >= sizeof(text)) {
      return;
    }
    {
      char trim[12] = "--", ppm[12] = "--";
      if (snap.rtc_trim_valid) {
        snprintf(trim, sizeof(trim), "%ld", static_cast<long>(snap.rtc_trim));
      }
      if (snap.rtc_drift != nullptr && RtcTrim::hasFigure(*snap.rtc_drift)) {
        snprintf(ppm, sizeof(ppm), "%ld", static_cast<long>(snap.rtc_drift->rate_ppm));
      }
      len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "RTC trim=%s drift_ppm=%s\n", trim, ppm));
      if (len >= sizeof(text)) {
        return;
      }
    }
    {
      char ok[4] = "--", failures[8] = "0", age[12] = "--", next[12] = "--";
      if (snap.ntp != nullptr && snap.ntp_credentials && snap.ntp->has_attempted) {
        snprintf(ok, sizeof(ok), "%u", snap.ntp->last_ok ? 1U : 0U);
        snprintf(failures, sizeof(failures), "%u", static_cast<unsigned>(snap.ntp->failures));
        const uint32_t elapsed = static_cast<uint32_t>(snap.now_ms - snap.ntp->last_attempt_ms);
        const uint32_t delay = snap.ntp->last_ok ? NtpSchedule::syncIntervalMs(snap.config->ntp_sync_interval_h)
                                                 : NtpSchedule::retryDelayMs(snap.ntp->failures);
        snprintf(age, sizeof(age), "%lu", static_cast<unsigned long>(elapsed / 1000UL));
        snprintf(next, sizeof(next), "%lu", static_cast<unsigned long>(elapsed >= delay ? 0UL : (delay - elapsed) / 1000UL));
      }
      len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "NTP ok=%s failures=%s age_s=%s next_s=%s\n",
                                          ok, failures, age, next));
      if (len >= sizeof(text)) {
        return;
      }
    }
    char usb_offset[24] = "";
    if (snap.battery.valid && snap.battery.usb_powered) {
      char tenths_text[12];
      formatTenths(tenths_text, sizeof(tenths_text), tenths(snap.config->usb_temp_offset_c));
      snprintf(usb_offset, sizeof(usb_offset), " usb_offset=%s", tenths_text);
    }
    len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "SHTC3 nack=%u short=%u crc=%u run=%u retried=%u%s\n",
                                        static_cast<unsigned>(snap.shtc3.nack), static_cast<unsigned>(snap.shtc3.short_reads),
                                        static_cast<unsigned>(snap.shtc3.crc), static_cast<unsigned>(snap.shtc3.run),
                                        static_cast<unsigned>(snap.shtc3.retried), usb_offset));
    if (len >= sizeof(text)) {
      return;
    }
    {
      const BleRejectStats& rej = snap.registry->rejects();
      char last[20] = "--";
      if (rej.has_last) {
        snprintf(last, sizeof(last), "%02X:%02X:%02X:%02X:%02X:%02X", static_cast<unsigned>(rej.last_mac[0]),
                 static_cast<unsigned>(rej.last_mac[1]), static_cast<unsigned>(rej.last_mac[2]),
                 static_cast<unsigned>(rej.last_mac[3]), static_cast<unsigned>(rej.last_mac[4]),
                 static_cast<unsigned>(rej.last_mac[5]));
      }
      len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len,
                                          "BLE rejected=%lu last=%s woio=%u meter=%u bthome=%u atc=%u thermopro=%u unassigned=%u jumps=%u\n",
                                          static_cast<unsigned long>(rej.total()), last,
                                          static_cast<unsigned>(rej.by_format[0]), static_cast<unsigned>(rej.by_format[1]),
                                          static_cast<unsigned>(rej.by_format[2]), static_cast<unsigned>(rej.by_format[3]),
                                          static_cast<unsigned>(rej.by_format[4]), static_cast<unsigned>(rej.unassigned),
                                          static_cast<unsigned>(rej.jumps)));
    }
    if (len >= sizeof(text)) {
      return;
    }
    {
      len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "RECEPTION"));
      for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS && len < sizeof(text); ++i) {
        if (snap.reception == nullptr || snap.reception->scans(i) == 0) {
          len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, " s%u=--", static_cast<unsigned>(i + 1)));
        } else {
          len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, " s%u=%u/%u", static_cast<unsigned>(i + 1),
                                              static_cast<unsigned>(snap.reception->received(i)),
                                              static_cast<unsigned>(snap.reception->scans(i))));
        }
      }
      if (len < sizeof(text)) {
        len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "\n"));
      }
    }
    if (len >= sizeof(text)) {
      return;
    }
    len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "POWER src=%s v=%s pct=%s alerts=%02x muted=%u acked=%02x\n",
                                        snap.battery.usb_powered ? "USB" : "BAT", volts, pct,
                                        static_cast<unsigned>(snap.alert_mask), snap.muted ? 1u : 0u,
                                        static_cast<unsigned>(snap.alert_ack)));
    if (len >= sizeof(text)) {
      return;
    }
    {
      // Reads the RTC copy of the history and the cycle total of the caller; nothing is recorded.
      char hours[24] = "--", cycles[40] = "--";
      unsigned long samples = 0;
      if (snap.battery_history != nullptr) {
        samples = static_cast<unsigned long>(snap.battery_history->size());
        const BatteryRuntime runtime = snap.battery_history->estimate(snap.battery, snap.scan_saved_ms, snap.awake);
        if (runtime.valid) {
          snprintf(hours, sizeof(hours), "%lu", static_cast<unsigned long>(runtime.hours));
        }
      }
      if (snap.cycle_tenths >= 0) {
        snprintf(cycles, sizeof(cycles), "%ld.%ld", static_cast<long>(snap.cycle_tenths / 10),
                 static_cast<long>(snap.cycle_tenths % 10));
      }
      len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len,
                                          "RUNTIME h=%s samples=%lu cycles=%s scan_saved_ms=%lu\n", hours, samples,
                                          cycles, static_cast<unsigned long>(snap.scan_saved_ms)));
    }
    if (len >= sizeof(text)) {
      return;
    }
    {
      // Reads the RTC copy of the awake time stats; "--" until the first timer wakeup on battery.
      char last[16] = "--", avg[16] = "--", max[16] = "--";
      unsigned long samples = 0;
      if (snap.awake != nullptr && snap.awake->samples() > 0) {
        samples = snap.awake->samples();
        snprintf(last, sizeof(last), "%lu", static_cast<unsigned long>(snap.awake->lastMs()));
        snprintf(avg, sizeof(avg), "%lu", static_cast<unsigned long>(snap.awake->meanMs()));
        snprintf(max, sizeof(max), "%lu", static_cast<unsigned long>(snap.awake->maxMs()));
      }
      len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len,
                                          "AWAKE last_ms=%s avg_ms=%s max_ms=%s samples=%lu\n", last, avg, max, samples));
    }
    if (len >= sizeof(text)) {
      return;
    }
    len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "MEMORY free=%lu min=%lu largest=%lu psram=%lu\n",
                                        static_cast<unsigned long>(snap.free_heap),
                                        static_cast<unsigned long>(snap.min_free_heap),
                                        static_cast<unsigned long>(snap.largest_heap),
                                        static_cast<unsigned long>(snap.free_psram)));
    if (len >= sizeof(text)) {
      return;
    }
    {
      char frame_crc[12] = "--";
      if (snap.frame.crc != 0) {
        snprintf(frame_crc, sizeof(frame_crc), "%08lx", static_cast<unsigned long>(snap.frame.crc));
      }
      len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "FRAME crc=%s skips=%lu\n", frame_crc,
                                          static_cast<unsigned long>(snap.frame.skips)));
    }
    if (len >= sizeof(text)) {
      return;
    }
    len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "WAKE cause=%s boots=%lu battery_interval_s=%lu\n",
                                        WakeupCauseCodec::text(snap.wake_cause),
                                        static_cast<unsigned long>(snap.boot_count),
                                        static_cast<unsigned long>(snap.battery_interval_s)));
    if (len >= sizeof(text)) {
      return;
    }
    len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "LATCH vent=%03x dry=%03x batlow=%03x stale=%03x wall=%03x window=%03x rxlow=%03x drift=%03x heat=%03x stuck=%03x\n",
                                        static_cast<unsigned>(snap.ventilate_tiles & 0x7FFu),
                                        static_cast<unsigned>(snap.dry_tiles & 0x7FFu),
                                        static_cast<unsigned>(snap.sensor_battery_latch & 0x3FFu),
                                        static_cast<unsigned>(snap.stale_slots & 0x3FFu),
                                        static_cast<unsigned>(snap.wall_tiles & 0x7FFu),
                                        static_cast<unsigned>(snap.window_tiles & 0x7FFu),
                                        static_cast<unsigned>(snap.reception_low & 0x3FFu),
                                        static_cast<unsigned>(snap.drift_tiles & 0x7FFu),
                                        static_cast<unsigned>(snap.heat_tiles & 0x7FFu),
                                        static_cast<unsigned>(snap.stuck_tiles & 0x7FFu)));
    if (len >= sizeof(text)) {
      return;
    }
    {
      char db[12] = "--";
      const size_t n = snap.noise_history.size();
      if (n > 0 && NoiseHistory::isValid(snap.noise_history.at(n - 1))) {
        snprintf(db, sizeof(db), "%u", static_cast<unsigned>(snap.noise_history.at(n - 1)));
      }
      const char* band = snap.noise_band == NoiseBand::LOUD       ? "LOUD"
                         : snap.noise_band == NoiseBand::MODERATE ? "MODERATE"
                                                                  : "QUIET";
      char peak[12] = "--";
      char hour[12] = "--";
      if (snap.daily_noise.hasReading()) {
        snprintf(peak, sizeof(peak), "%u", static_cast<unsigned>(snap.daily_noise.peak_db));
        snprintf(hour, sizeof(hour), "%u", static_cast<unsigned>(snap.daily_noise.peak_hour));
      }
      len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len,
                                          "NOISE db=%s band=%s peak_db=%s peak_hour=%s events_sum=%lu\n", db, band, peak,
                                          hour, static_cast<unsigned long>(snap.daily_noise.events_sum)));
    }
    {
      const uint8_t repeat_h = snap.config->chime_repeat_h;
      const uint8_t bits = static_cast<uint8_t>(AlertEvaluator::repeatableBits(snap.alert_mask) & ~snap.alert_ack);
      char last[16] = "--";
      char next[16] = "--";
      uint32_t last_s = 0;
      if (snap.last_chime_ms != 0) {
        last_s = (snap.now_ms - snap.last_chime_ms) / 1000UL;
        snprintf(last, sizeof(last), "%lu", static_cast<unsigned long>(last_s));
      }
      if (repeat_h != 0 && bits != 0) {
        const uint32_t period_s = static_cast<uint32_t>(repeat_h) * 3600UL;
        // The reminder counts from the stamp; a missing stamp counts as due for the elapsed clock.
        const uint32_t elapsed_s = (snap.now_ms - snap.last_chime_ms) / 1000UL;
        snprintf(next, sizeof(next), "%lu", static_cast<unsigned long>(elapsed_s >= period_s ? 0UL : period_s - elapsed_s));
      }
      char snooze[16] = "--";
      if (snap.snooze.active(snap.now_ms)) {
        snprintf(snooze, sizeof(snooze), "%lu", static_cast<unsigned long>(snap.snooze.remainingS(snap.now_ms)));
      }
      len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "CHIME repeat_h=%u bits=%02x last_s=%s next_s=%s snooze_s=%s\n",
                                          static_cast<unsigned>(repeat_h), static_cast<unsigned>(bits), last, next, snooze));
    }
    {
      char active[4] = "--";
      if (snap.quiet_active >= 0) {
        snprintf(active, sizeof(active), "%d", snap.quiet_active != 0 ? 1 : 0);
      }
      len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "QUIET start_h=%u end_h=%u active=%s night_interval_s=%u\n",
                                          static_cast<unsigned>(snap.config->quiet_hours_start_hour),
                                          static_cast<unsigned>(snap.config->quiet_hours_end_hour), active,
                                          static_cast<unsigned>(snap.config->night_wake_interval_s)));
    }
    if (len >= sizeof(text)) {
      return;
    }
    SerialCrc32 crc;
    crc.update(reinterpret_cast<const uint8_t*>(text), len);
    if (!put(sink, reinterpret_cast<const uint8_t*>(text), len)) {
      return;
    }
    char line[24];
    snprintf(line, sizeof(line), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
    send(sink, line);
  }

  // Sends the RTC time as read by the caller. An invalid, unreadable or stopped clock shows valid=0 with the
  // fields as decoded (the defaults when nothing decodes); osf=1 marks the oscillator stop flag.
  static void showTime(Sink& sink, const RtcTime& t) {
    char line[48];
    snprintf(line, sizeof(line), "TIME %04u-%02u-%02uT%02u:%02u:%02u valid=%u osf=%u\n", static_cast<unsigned>(t.year),
             static_cast<unsigned>(t.month), static_cast<unsigned>(t.day), static_cast<unsigned>(t.hour),
             static_cast<unsigned>(t.minute), static_cast<unsigned>(t.second), t.valid ? 1u : 0u,
             t.oscillator_stopped ? 1u : 0u);
    send(sink, line);
  }

  // Sends the build identity. `date` and `time` are the compiler stamps __DATE__ and __TIME__; an unreadable stamp
  // gives built=unknown. The fields have length limits, so the frame fits the buffer.
  static void showVersion(Sink& sink, const char* fw, const char* git, const char* core, uint8_t state_version,
                          const char* date, const char* time) {
    char built[40] = "unknown";
    RtcTime stamp;
    if (Pcf85063Codec::fromCompileStamp(date, time, stamp)) {
      snprintf(built, sizeof(built), "%04u-%02u-%02uT%02u:%02u:%02u", static_cast<unsigned>(stamp.year),
               static_cast<unsigned>(stamp.month), static_cast<unsigned>(stamp.day), static_cast<unsigned>(stamp.hour),
               static_cast<unsigned>(stamp.minute), static_cast<unsigned>(stamp.second));
    }
    char text[192];
    const int n = snprintf(text, sizeof(text), "VERSION fw=%.16s git=%.24s core=%.16s state=%u built=%s\n", fw, git,
                           core, static_cast<unsigned>(state_version), built);
    if (n <= 0 || static_cast<size_t>(n) >= sizeof(text)) {
      return; // a truncated frame has no valid CRC: send nothing
    }
    const size_t len = static_cast<size_t>(n);
    SerialCrc32 crc;
    crc.update(reinterpret_cast<const uint8_t*>(text), len);
    if (!put(sink, reinterpret_cast<const uint8_t*>(text), len)) {
      return;
    }
    char line[24];
    snprintf(line, sizeof(line), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
    send(sink, line);
  }

  // Writes a parsed local time to the RTC. The write clears the oscillator stop flag. After a successful write the
  // drift baseline is void, as after the seed from the compile stamp, so the next NTP sync measures no false drift.
  // A failed write leaves the baseline untouched. The NTP schedule is not touched.
  // Returns true when the RTC got the new time. With events, one CLOCK_SET event (detail low byte 0, transport in the
  // high byte: 0 USB, 1 BLE) records the written time after a successful write only.
  template <typename Rtc>
  static bool setTime(Sink& sink, Rtc& rtc, RtcDriftState& drift, const RtcTime& t, EventRing* events = nullptr,
                      uint8_t transport = 0) {
    if (!Pcf85063Codec::isTimeInRange(t) || t.year < SerialConsole::MIN_SET_YEAR ||
        t.year > SerialConsole::MAX_SET_YEAR) { // defense in depth: the parser already checks the time
      reject(sink, SerialParseStatus::BAD_ARG);
      return false;
    }
    if (!rtc.setTime(t)) {
      send(sink, "ERR RTC_FAILED\n");
      return false;
    }
    drift = RtcDriftState{};
    if (events != nullptr) {
      events->record(t, EventCode::CLOCK_SET, static_cast<uint16_t>((static_cast<uint16_t>(transport) << 8) | 0u));
    }
    send(sink, "OK TIME\n");
    return true;
  }

  // Trims the RTC frequency from the last drift figure. The figure needs an interval of 24 h or more,
  // else "ERR NO_DRIFT". The register is written, read back and compared; after a match the drift baseline
  // is void and the reply is "OK RTC TRIM <old> <new>". An unreadable register, a failed write or a
  // readback mismatch gives "ERR WRITE_FAILED" and keeps the baseline. The register keeps its value, so
  // nothing is stored. Returns true after a write. With events and now, one CLOCK_SET event (detail low byte 1,
  // transport in the high byte) records the trim after a successful write only.
  template <typename Rtc>
  static bool trimRtc(Sink& sink, Rtc& rtc, RtcDriftState& drift, EventRing* events = nullptr,
                      const RtcTime* now = nullptr, uint8_t transport = 0) {
    if (!RtcTrim::hasFigure(drift)) {
      send(sink, "ERR NO_DRIFT\n");
      return false;
    }
    int32_t active = 0;
    if (!rtc.readOffset(active)) {
      send(sink, "ERR WRITE_FAILED\n");
      return false;
    }
    const int32_t target = RtcTrim::targetValue(active, drift.rate_ppm);
    int32_t readback = 0;
    if (!rtc.writeOffset(target) || !rtc.readOffset(readback) || readback != target) {
      send(sink, "ERR WRITE_FAILED\n");
      return false;
    }
    drift = RtcDriftState{};
    if (events != nullptr && now != nullptr) {
      events->record(*now, EventCode::CLOCK_SET, static_cast<uint16_t>((static_cast<uint16_t>(transport) << 8) | 1u));
    }
    char line[48];
    snprintf(line, sizeof(line), "OK RTC TRIM %ld %ld\n", static_cast<long>(active), static_cast<long>(target));
    send(sink, line);
    return true;
  }

  // NTP SYNC: runs one NTP sync on request (USB only, "ERR NOT_OVER_BLE" on BLE). Without Wi-Fi credentials the reply is
  // "ERR NO_WIFI" and nothing runs or records. Else `run_sync()` runs NtpClient::sync and returns the NtpResult;
  // NtpSchedule::record stores the attempt, and one NTP_SYNC event records the result (detail = NtpResult) with the time
  // that `*now` holds after the call (the sketch re-reads the RTC in `run_sync`). Reply "OK NTP SYNC" or
  // "ERR NTP_FAILED <WIFI_FAILED|NTP_TIMEOUT|RTC_WRITE_FAILED>". No card access. Returns true after a successful sync.
  template <typename RunSync, typename Clock>
  static bool ntpSync(Sink& sink, NtpSyncState& state, bool credentials, EventRing* events, const RtcTime* now,
                      RunSync run_sync, Clock clock_ms) {
    if (!credentials) {
      send(sink, "ERR NO_WIFI\n");
      return false;
    }
    const NtpResult result = run_sync();
    NtpSchedule::record(state, clock_ms(), result == NtpResult::OK); // read after the call: the sync takes seconds
    if (events != nullptr && now != nullptr) {
      events->record(*now, EventCode::NTP_SYNC, static_cast<uint16_t>(result));
    }
    switch (result) {
      case NtpResult::OK: send(sink, "OK NTP SYNC\n"); return true;
      case NtpResult::NTP_TIMEOUT: send(sink, "ERR NTP_FAILED NTP_TIMEOUT\n"); return false;
      case NtpResult::RTC_WRITE_FAILED: send(sink, "ERR NTP_FAILED RTC_WRITE_FAILED\n"); return false;
      case NtpResult::WIFI_FAILED:
      default: send(sink, "ERR NTP_FAILED WIFI_FAILED\n"); return false;
    }
  }

  // Plays the chime of alert bit `bit` (0 to 7) once and replies "OK BEEP <bit> <duration ms>". The
  // reply "ERR MUTED" (mute, quiet hours or volume 0) leaves the hardware untouched, "ERR PLAY_FAILED"
  // follows a failed playback. The Hal powers down on every path (AudioSequencer). A cleared bit in
  // chime_alert_mask does not block the test, and no alert state is read or written here.
  template <typename Hal>
  static void beep(Sink& sink, Hal& hal, uint8_t bit, bool muted, bool quiet_hours, uint8_t volume_pct) {
    if (bit > 7) { // defense in depth: the parser already limits the bit to 0 to 7
      reject(sink, SerialParseStatus::BAD_ARG);
      return;
    }
    if (muted || quiet_hours || volume_pct == 0) {
      send(sink, "ERR MUTED\n");
      return;
    }
    const uint8_t alert_bit = static_cast<uint8_t>(1U << bit);
    if (!AudioSequencer::playTest(hal, alert_bit, volume_pct)) {
      send(sink, "ERR PLAY_FAILED\n");
      return;
    }
    const ChimePattern pattern = ChimeSynth::pattern(alert_bit);
    uint32_t duration_ms = 0;
    for (uint8_t i = 0; i < pattern.count; ++i) {
      duration_ms += pattern.notes[i].duration_ms;
    }
    char line[32];
    snprintf(line, sizeof(line), "OK BEEP %u %lu\n", static_cast<unsigned>(bit), static_cast<unsigned long>(duration_ms));
    send(sink, line);
  }

  // Shows the identify screen and replies "OK IDENTIFY <4 hex digits>" (`mac_tail`, upper case). `render(fb)` draws the
  // screen; it is flushed as it is, like PATTERN (display_invert is not applied). One short note plays under the rules
  // of BEEP (mute, quiet hours and volume 0 keep it silent and the reply stays OK, no ERR MUTED; a failed playback does not change the reply). `frame` (when
  // given) is invalidated, so the next wake cycle flushes the normal view. No card access.
  template <typename Framebuffer, typename Panel, typename Hal, typename Render>
  static void identify(Sink& sink, Framebuffer& fb, Panel& panel, Hal& hal, uint16_t mac_tail, Render render, bool muted,
                       bool quiet_hours, uint8_t volume_pct, FrameRefresh* frame = nullptr) {
    render(fb);
    panel.flush(fb);
    if (frame != nullptr) {
      frame->invalidate();
    }
    if (!muted && !quiet_hours && volume_pct != 0) {
      AudioSequencer::playConfirm(hal, volume_pct);
    }
    char line[32];
    snprintf(line, sizeof(line), "OK IDENTIFY %04X\n", static_cast<unsigned>(mac_tail));
    send(sink, line);
  }

  // SNOOZE <M>: starts a timed mute of `minutes` (0 ends it) and replies "OK SNOOZE <M>". No card access. One
  // MUTE_CHANGE event (detail low byte 2 a snooze start, 0 an end; source in the high byte) records a start, and an end
  // of a snooze that was still active. A new snooze while another runs restarts the timer and records the start again.
  // An unmuted flag stays as it is: the snooze acts like a mute only while it runs.
  static void setSnooze(Sink& sink, Snooze& snooze, uint16_t minutes, uint32_t now_ms, EventRing* events = nullptr,
                        const RtcTime* now = nullptr, uint8_t source = 1) {
    const bool was_active = snooze.active(now_ms);
    snooze.start(now_ms, minutes);
    if (events != nullptr && now != nullptr && (minutes != 0 || was_active)) {
      events->record(*now, EventCode::MUTE_CHANGE,
                     static_cast<uint16_t>((static_cast<uint16_t>(source) << 8) | (minutes != 0 ? 2u : 0u)));
    }
    char line[24];
    snprintf(line, sizeof(line), "OK SNOOZE %u\n", static_cast<unsigned>(minutes));
    send(sink, line);
  }

  // Sets the mute flag (0 or 1) and replies "OK MUTE <0|1>". No card access. The flag lives in the RTC state; the next
  // save of the wake cycle keeps it. A value above 1 never arrives (the parser limits it); it is treated as 1 here.
  // With events and now, one MUTE_CHANGE event records the change (detail: source in the high byte, state in the low
  // byte; source 0 BOOT, 1 USB, 2 BLE) only when the flag changed or a running snooze ended (the MUTE command clears `snooze`).
  static void setMute(Sink& sink, bool& muted, uint8_t value, EventRing* events = nullptr, const RtcTime* now = nullptr,
                      uint8_t source = 1, Snooze* snooze = nullptr, uint32_t now_ms = 0) {
    const bool was_muted = muted;
    // MUTE 0 and MUTE 1 end a running snooze. An unchanged flag then still records the end of the snooze (as unmuted).
    const bool snooze_ended = snooze != nullptr && snooze->active(now_ms);
    if (snooze != nullptr) {
      snooze->clear();
    }
    muted = value != 0;
    if (events != nullptr && now != nullptr && (muted != was_muted || (snooze_ended && !muted))) {
      events->record(*now, EventCode::MUTE_CHANGE, static_cast<uint16_t>((static_cast<uint16_t>(source) << 8) | (muted ? 1u : 0u)));
    }
    char line[16];
    snprintf(line, sizeof(line), "OK MUTE %u\n", muted ? 1u : 0u);
    send(sink, line);
  }

  // Reply of ACK <bit>: "OK ACK <N>" after PersistentState::acknowledgeAlert returned true, else "ERR NOT_ACTIVE" (the
  // alert is off). No card access. The next save of the wake cycle writes the state.
  static void ackReply(Sink& sink, bool acknowledged, uint8_t bit) {
    if (!acknowledged) {
      send(sink, "ERR NOT_ACTIVE\n");
      return;
    }
    char line[16];
    snprintf(line, sizeof(line), "OK ACK %u\n", static_cast<unsigned>(bit));
    send(sink, line);
  }

  // Reply of ACK ALL: "OK ACK ALL <2 hex digits>" with the mask of the new bits, else "ERR NOT_ACTIVE" (no bit is new).
  static void ackAllReply(Sink& sink, uint8_t new_bits) {
    if (new_bits == 0) {
      send(sink, "ERR NOT_ACTIVE\n");
      return;
    }
    char line[24];
    snprintf(line, sizeof(line), "OK ACK ALL %02x\n", static_cast<unsigned>(new_bits));
    send(sink, line);
  }

  // Reply of SLOT CLEAR <number>: "OK SLOT CLEAR <N>" after PersistentState::clearSlotByUser returned true, else
  // "ERR FIXED_MAC" (a slot with a fixed MAC, no change). No card access. The next save of the wake cycle writes the
  // state. The caller changes the state under the registry lock and replies after it, so no serial write runs inside it.
  static void slotClearReply(Sink& sink, bool cleared, uint8_t number) {
    if (!cleared) {
      send(sink, "ERR FIXED_MAC\n");
      return;
    }
    char line[24];
    snprintf(line, sizeof(line), "OK SLOT CLEAR %u\n", static_cast<unsigned>(number));
    send(sink, line);
  }

  // Reply of SLOT SWAP <A> <B>: "OK SLOT SWAP <A> <B>" after PersistentState::swapSlotsByUser returned OK, "ERR FIXED_MAC"
  // for a slot with a fixed MAC, "ERR SLOT_OFF" for a disabled slot, else "ERR BAD_ARG". No card access. The caller changes
  // the state under the registry lock and replies after it.
  static void slotSwapReply(Sink& sink, PersistentState::SlotSwap result, uint8_t a, uint8_t b) {
    switch (result) {
      case PersistentState::SlotSwap::OK: {
        char line[32];
        snprintf(line, sizeof(line), "OK SLOT SWAP %u %u\n", static_cast<unsigned>(a), static_cast<unsigned>(b));
        send(sink, line);
        break;
      }
      case PersistentState::SlotSwap::FIXED_MAC: send(sink, "ERR FIXED_MAC\n"); break;
      case PersistentState::SlotSwap::SLOT_OFF: send(sink, "ERR SLOT_OFF\n"); break;
      case PersistentState::SlotSwap::BAD_ARG:
      default: send(sink, "ERR BAD_ARG\n"); break;
    }
  }

  // Renders test pattern `pattern` (0 to 2) into `fb` and flushes it to `panel` as it is: display_invert is not
  // applied, so the user sees the raw packing. Replies "OK PATTERN <pattern>". No card access. The pattern is no
  // dashboard frame: `frame` (when given) is invalidated, so the next wake cycle flushes the normal view.
  template <typename Framebuffer, typename Panel>
  static void showPattern(Sink& sink, Framebuffer& fb, Panel& panel, uint8_t pattern, FrameRefresh* frame = nullptr) {
    if (!fb.fillPattern(pattern)) { // defense in depth: the parser already limits the pattern to 0 to 2
      reject(sink, SerialParseStatus::BAD_ARG);
      return;
    }
    panel.flush(fb);
    if (frame != nullptr) {
      frame->invalidate();
    }
    char line[32];
    snprintf(line, sizeof(line), "OK PATTERN %u\n", static_cast<unsigned>(pattern));
    send(sink, line);
  }

  // Sends the framebuffer as the last render left it (after invert() with display_invert 1): "BEGIN screen.bin 15000",
  // the raw bytes, then "CRC32 <hex>" over them (St7305Framebuffer::crc32()). `has_frame` is false before the first
  // render of the session: "ERR NO_FRAME". No card access, no panel flush.
  template <typename Framebuffer>
  static void showScreen(Sink& sink, const Framebuffer& fb, bool has_frame) {
    if (!has_frame) {
      send(sink, "ERR NO_FRAME\n");
      return;
    }
    char line[48];
    snprintf(line, sizeof(line), "BEGIN screen.bin %lu\n", static_cast<unsigned long>(Framebuffer::BYTE_COUNT));
    if (!send(sink, line) || !put(sink, fb.data(), Framebuffer::BYTE_COUNT)) {
      return;
    }
    snprintf(line, sizeof(line), "CRC32 %08lx\n", static_cast<unsigned long>(fb.crc32()));
    send(sink, line);
  }

  // Changes one key of /sdcard/config.ini. `apply(const char* text, size_t len)` runs after the new file is in place and
  // takes over the new config (PersistentState::applyConfig); the reply "OK SET <key>" follows it. Returns true on success.
  // With `events` set, one CONFIG_SET event follows the apply step (detail: key index, transport in the high byte).
  template <typename Apply>
  static bool setConfig(Storage& storage, Sink& sink, const SerialCommand& command, Apply apply,
                        EventRing* events = nullptr, const RtcTime* now = nullptr, uint8_t transport = 0) {
    // Defense in depth: the parser already checks the pair.
    const DeviceConfigParser::SettingCheck check =
        command.kind == SerialCommandKind::SET
            ? DeviceConfigParser::checkSetting(command.set_key, strlen(command.set_key), command.set_value,
                                               strlen(command.set_value))
            : DeviceConfigParser::SettingCheck::BAD_KEY;
    if (check != DeviceConfigParser::SettingCheck::OK) {
      reject(sink, check == DeviceConfigParser::SettingCheck::BAD_VALUE ? SerialParseStatus::BAD_VALUE
                                                                        : SerialParseStatus::BAD_KEY);
      return false;
    }
    if (!storage.mount()) {
      send(sink, "ERR NO_CARD\n");
      return false;
    }
    // Static: two 8 KB buffers do not fit the 8 KB loop task stack. One console command runs at a time.
    static char current[DeviceConfigParser::MAX_FILE_SIZE + 1];
    static char next[DeviceConfigParser::MAX_FILE_SIZE + 1];
    size_t current_len = 0;
    size_t next_len = 0;
    bool exists = false;
    const char* reply = nullptr;
    bool done = false;
    if (!storage.readConfig(current, sizeof(current), current_len, exists)) {
      reply = "ERR WRITE_FAILED\n";
    } else if (current_len > DeviceConfigParser::MAX_FILE_SIZE) {
      reply = "ERR TOO_LARGE\n";
    } else if (DeviceConfigEditor::set(current, current_len, command.set_key, command.set_value, next, next_len) !=
               DeviceConfigEditor::Result::OK) {
      reply = "ERR TOO_LARGE\n";
    } else if (!storage.writeTemp(next, next_len)) {
      storage.removeTemp();
      reply = "ERR WRITE_FAILED\n"; // the old file is untouched
    } else if (exists && !storage.removeConfig()) {
      storage.removeTemp();
      reply = "ERR WRITE_FAILED\n"; // the old file is untouched
    } else if (!storage.renameTemp()) {
      if (exists) {
        if (!storage.restoreConfig(current, current_len)) {
          storage.unmount(); // the old file is gone: config.tmp holds the new text and stays
          send(sink, "ERR WRITE_FAILED\n");
          return false;
        }
      }
      storage.removeTemp();
      reply = "ERR WRITE_FAILED\n";
    } else {
      done = true;
    }
    storage.unmount();
    if (!done) {
      send(sink, reply);
      return false;
    }
    apply(static_cast<const char*>(next), next_len);
    if (events != nullptr && now != nullptr) {
      // The key passed checkSetting, so the lookup always finds it; a miss would record nothing.
      for (size_t i = 0; i < DeviceConfigParser::KEY_COUNT; ++i) {
        if (strcmp(command.set_key, DeviceConfigParser::KEYS[i]) == 0) {
          events->record(*now, EventCode::CONFIG_SET, static_cast<uint16_t>((transport << 8) | i));
          break;
        }
      }
    }
    char line[SET_KEY_SIZE + 16];
    snprintf(line, sizeof(line), "OK SET %s\n", command.set_key);
    send(sink, line);
    return true;
  }

  static void execute(Storage& storage, Sink& sink, const SerialCommand& command) {
    if (command.kind == SerialCommandKind::NONE || command.kind == SerialCommandKind::CONFIG ||
        command.kind == SerialCommandKind::CALIB || command.kind == SerialCommandKind::SALT ||
        command.kind == SerialCommandKind::STATUS ||
        command.kind == SerialCommandKind::BEEP || command.kind == SerialCommandKind::TIME ||
        command.kind == SerialCommandKind::HISTORY || command.kind == SerialCommandKind::VERSION ||
        command.kind == SerialCommandKind::EVENTS || command.kind == SerialCommandKind::PATTERN ||
        command.kind == SerialCommandKind::SCAN || command.kind == SerialCommandKind::MUTE ||
        command.kind == SerialCommandKind::SCREEN || command.kind == SerialCommandKind::SET ||
        command.kind == SerialCommandKind::RTC_TRIM || command.kind == SerialCommandKind::WEEK ||
        command.kind == SerialCommandKind::SLOT_CLEAR || command.kind == SerialCommandKind::SLOT_SWAP ||
        command.kind == SerialCommandKind::REJECTS || command.kind == SerialCommandKind::ACK ||
        command.kind == SerialCommandKind::IDENTIFY || command.kind == SerialCommandKind::SNOOZE ||
        command.kind == SerialCommandKind::NTP_SYNC || command.kind == SerialCommandKind::FRSI ||
        command.kind == SerialCommandKind::SELFTEST || command.kind == SerialCommandKind::SPREAD ||
        command.kind == SerialCommandKind::TAIL || command.kind == SerialCommandKind::TODAY ||
        command.kind == SerialCommandKind::DAYS || command.kind == SerialCommandKind::RECEPTION ||
        command.kind == SerialCommandKind::ALERTS) {
      return;
    }
    if (!storage.mount()) {
      send(sink, "ERR NO_CARD\n");
      return;
    }
    switch (command.kind) {
      case SerialCommandKind::LIST: list(storage, sink); break;
      case SerialCommandKind::INFO: info(storage, sink); break;
      case SerialCommandKind::DUMP: dump(storage, sink, command.file); break;
      case SerialCommandKind::NONE:
      case SerialCommandKind::CONFIG:
      case SerialCommandKind::CALIB:
      case SerialCommandKind::SALT:
      case SerialCommandKind::STATUS:
      case SerialCommandKind::BEEP:
      case SerialCommandKind::TIME:
      case SerialCommandKind::HISTORY:
      case SerialCommandKind::EVENTS:
      case SerialCommandKind::PATTERN:
      case SerialCommandKind::SCAN:
      case SerialCommandKind::MUTE:
      case SerialCommandKind::SCREEN:
      case SerialCommandKind::RTC_TRIM:
      case SerialCommandKind::WEEK:
      case SerialCommandKind::SLOT_CLEAR:
      case SerialCommandKind::SLOT_SWAP:
      case SerialCommandKind::REJECTS:
      case SerialCommandKind::ACK:
      case SerialCommandKind::IDENTIFY:
      case SerialCommandKind::SNOOZE:
      case SerialCommandKind::NTP_SYNC:
      case SerialCommandKind::FRSI:
      case SerialCommandKind::SELFTEST:
      case SerialCommandKind::SPREAD:
      case SerialCommandKind::TAIL:
      case SerialCommandKind::TODAY:
      case SerialCommandKind::DAYS:
      case SerialCommandKind::RECEPTION:
      case SerialCommandKind::VERSION:
      case SerialCommandKind::SET:
      default: break;
    }
    storage.unmount();
  }

  static constexpr size_t MIN_COMMON_HOURS = 6;
  static constexpr size_t MIN_POWER_HOURS = 3; // USB or battery hours needed for the onboard split
  static constexpr size_t FRSI_HOURS = 6;
  static constexpr int32_t FRSI_MIN_DELTA_TENTHS = 100;  // 10.0 C
  static constexpr int32_t FRSI_MAX_SPAN_HUNDREDTHS = 10; // 0.10
  static constexpr size_t SPREAD_MIN_SENSORS = SpreadMath::MIN_SENSORS;
  static constexpr size_t SALT_HOURS = 6;
  static constexpr int32_t SALT_MAX_SPAN_TENTHS = 10; // 1.0 %RH

private:
  // Rounds a / b half away from zero (b > 0).
  static int32_t divRound(int32_t a, int32_t b) { return SpreadMath::divRound(a, b); }

  // Hours with a temperature and a humidity sample in the reference and in the sensor.
  static size_t commonHours(const ClimateHistory& h, size_t reference, size_t sensor) {
    size_t n = h.slots[reference].size();
    const size_t others[3] = {h.slots[sensor].size(), h.temperatures[reference].size(), h.temperatures[sensor].size()};
    for (size_t o : others) {
      n = o < n ? o : n;
    }
    return n;
  }

  static int32_t tenths(float value) {
    return static_cast<int32_t>(lroundf(value * 10.0f));
  }

  static int32_t clampTenths(long value, float lo, float hi) {
    const long lo_t = lroundf(lo * 10.0f);
    const long hi_t = lroundf(hi * 10.0f);
    return static_cast<int32_t>(value < lo_t ? lo_t : (value > hi_t ? hi_t : value));
  }

  // Prints a value in 0.1 steps with one decimal; no "-0.0".
  // Temperature text of the console command STATUS: tenths of a degree, with 'F' behind a value in
  // Fahrenheit. Celsius keeps the plain number.
  static void formatTemp(char* out, size_t size, float temp_c, char unit) {
    if (unit == 'F') {
      char num[12];
      formatTenths(num, sizeof(num), tenths(ClimateMath::toDisplay(temp_c, unit)));
      snprintf(out, size, "%sF", num);
    } else {
      formatTenths(out, size, tenths(temp_c));
    }
  }

  static void formatHundredths(char* out, size_t size, int32_t value) {
    const int32_t mag = value < 0 ? -value : value;
    snprintf(out, size, "%s%ld.%02ld", value < 0 ? "-" : "", static_cast<long>(mag / 100), static_cast<long>(mag % 100));
  }

  static void formatTenths(char* out, size_t size, int32_t value) {
    const int32_t mag = value < 0 ? -value : value;
    snprintf(out, size, "%s%ld.%ld", value < 0 ? "-" : "", static_cast<long>(mag / 10), static_cast<long>(mag % 10));
  }

  static bool put(Sink& sink, const uint8_t* data, size_t len) {
    return sink.write(data, len) == len;
  }

  static bool send(Sink& sink, const char* text) {
    return put(sink, reinterpret_cast<const uint8_t*>(text), strlen(text));
  }

  static size_t collect(Storage& storage, SerialLogEntry* entries) {
    size_t count = storage.listLogs(entries, SerialConsole::MAX_LIST_ENTRIES);
    if (count > SerialConsole::MAX_LIST_ENTRIES) {
      count = SerialConsole::MAX_LIST_ENTRIES;
    }
    for (size_t i = 1; i < count; ++i) { // insertion sort by name: stable output order
      SerialLogEntry key = entries[i];
      size_t j = i;
      while (j > 0 && strcmp(entries[j - 1].name, key.name) > 0) {
        entries[j] = entries[j - 1];
        --j;
      }
      entries[j] = key;
    }
    return count;
  }

  static void list(Storage& storage, Sink& sink) {
    SerialLogEntry entries[SerialConsole::MAX_LIST_ENTRIES];
    const size_t count = collect(storage, entries);
    char line[SdLogCodec::LOG_NAME_SIZE + 24];
    for (size_t i = 0; i < count; ++i) {
      snprintf(line, sizeof(line), "FILE %s %lu\n", entries[i].name,
               static_cast<unsigned long>(entries[i].size));
      send(sink, line);
    }
    snprintf(line, sizeof(line), "END LIST %u\n", static_cast<unsigned>(count));
    send(sink, line);
  }

  static void info(Storage& storage, Sink& sink) {
    SerialLogEntry entries[SerialConsole::MAX_LIST_ENTRIES];
    const size_t count = collect(storage, entries);
    char line[96];
    snprintf(line, sizeof(line), "INFO total=%llu used=%llu files=%u\n",
             static_cast<unsigned long long>(storage.totalBytes()),
             static_cast<unsigned long long>(storage.usedBytes()), static_cast<unsigned>(count));
    send(sink, line);
  }

  static void dump(Storage& storage, Sink& sink, const char* name) {
    uint32_t size = 0;
    if (!SdLogCodec::isConsoleFileName(name) || !storage.openLog(name, size)) {
      send(sink, "ERR NOT_FOUND\n");
      return;
    }
    char line[SdLogCodec::LOG_NAME_SIZE + 24];
    snprintf(line, sizeof(line), "BEGIN %s %lu\n", name, static_cast<unsigned long>(size));
    if (!send(sink, line)) {
      storage.closeLog();
      return;
    }
    SerialCrc32 crc;
    uint8_t chunk[CHUNK_SIZE];
    uint32_t sent = 0;
    bool read_ok = true;
    while (sent < size) {
      const size_t want = (size - sent) < CHUNK_SIZE ? (size - sent) : CHUNK_SIZE;
      size_t got = read_ok ? storage.read(chunk, want) : 0;
      if (got == 0 || got > want) {
        read_ok = false;
        got = want;
        memset(chunk, 0, got); // keep the frame length; the end line reports the error
      } else {
        crc.update(chunk, got);
      }
      if (!put(sink, chunk, got)) {
        storage.closeLog();
        return;
      }
      sent += static_cast<uint32_t>(got);
    }
    storage.closeLog();
    if (!read_ok) {
      send(sink, "ERR READ_FAILED\n");
      return;
    }
    snprintf(line, sizeof(line), "CRC32 %08lx\n", static_cast<unsigned long>(crc.value()));
    send(sink, line);
  }
};

#if defined(ARDUINO) || defined(ESP_PLATFORM)

// Card access for the USB serial console. The runner calls mount() once per command and
// unmount() after it, so the card never stays powered up between commands.
class SdLogStorage {
public:
  bool mount() {
    return SdLogger::mountCard();
  }

  void unmount() {
    closeLog();
    SD_MMC.end();
  }

  size_t listLogs(SerialLogEntry* out, size_t capacity) {
    File root = SD_MMC.open("/");
    if (!root || !root.isDirectory()) {
      return 0;
    }
    size_t count = 0;
    File entry = root.openNextFile();
    while (entry) {
      // Core 3.x returns the bare name; strip a leading path for older cores.
      const char* name = entry.name();
      const char* slash = strrchr(name, '/');
      name = slash != nullptr ? slash + 1 : name;
      if (!entry.isDirectory() && SdLogCodec::isConsoleFileName(name) && count < capacity) {
        memcpy(out[count].name, name, strlen(name) + 1);
        out[count].size = static_cast<uint32_t>(entry.size());
        ++count;
      }
      entry.close();
      entry = root.openNextFile();
    }
    root.close();
    return count;
  }

  bool openLog(const char* name, uint32_t& size) {
    closeLog();
    char path[SdLogCodec::LOG_NAME_SIZE + 1];
    snprintf(path, sizeof(path), "/%s", name);
    file_ = SD_MMC.open(path, FILE_READ);
    if (!file_ || file_.isDirectory()) {
      closeLog();
      return false;
    }
    size = static_cast<uint32_t>(file_.size());
    return true;
  }

  size_t read(uint8_t* out, size_t len) {
    return file_ ? file_.read(out, len) : 0;
  }

  // The last `capacity` bytes of /<name> for the console command TAIL. 0 read, 1 file missing, 2 read error.
  // `whole` is true when the bytes start at the file start.
  int readTail(const char* name, char* buf, size_t capacity, size_t& len, bool& whole) {
    return SdLogger::readTailBytes(name, buf, capacity, len, whole);
  }

  void closeLog() {
    if (file_) {
      file_.close();
    }
  }

  // config.ini for the console command SET. An absent file gives true with exists false; a read error gives false; a file above
  // `cap` gives a length above the limit. The parser needs one byte above the limit to see an oversized file.
  bool readConfig(char* buf, size_t cap, size_t& len, bool& exists) {
    len = 0;
    exists = SD_MMC.exists("/config.ini");
    if (!exists) {
      return true;
    }
    File f = SD_MMC.open("/config.ini", FILE_READ);
    if (!f || f.isDirectory()) {
      return false;
    }
    const size_t size = static_cast<size_t>(f.size());
    const size_t n = f.read(reinterpret_cast<uint8_t*>(buf), cap);
    f.close();
    len = n;
    return n == (size < cap ? size : cap);
  }

  bool writeTemp(const char* data, size_t len) {
    SD_MMC.remove("/config.tmp");
    File f = SD_MMC.open("/config.tmp", FILE_WRITE);
    if (!f) {
      return false;
    }
    const size_t n = f.write(reinterpret_cast<const uint8_t*>(data), len);
    f.close();
    return n == len;
  }

  bool removeConfig() {
    return SD_MMC.remove("/config.ini");
  }

  bool renameTemp() {
    return SD_MMC.rename("/config.tmp", "/config.ini");
  }

  bool restoreConfig(const char* data, size_t len) {
    File f = SD_MMC.open("/config.ini", FILE_WRITE);
    if (!f) {
      return false;
    }
    const size_t n = f.write(reinterpret_cast<const uint8_t*>(data), len);
    f.close();
    return n == len;
  }

  void removeTemp() {
    SD_MMC.remove("/config.tmp");
  }

  uint64_t totalBytes() {
    return SD_MMC.totalBytes();
  }

  uint64_t usedBytes() {
    return SD_MMC.usedBytes();
  }

private:
  File file_;
};

#endif

} // namespace rlcd_panel
