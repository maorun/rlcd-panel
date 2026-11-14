#pragma once

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "audio_alert.h"
#include "battery_monitor.h"
#include "device_config.h"
#include "pcf85063_rtc.h"
#include "sd_logger.h"
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

enum class SerialCommandKind : uint8_t { NONE = 0, LIST, DUMP, INFO, CONFIG, CALIB, STATUS, BEEP, TIME, HISTORY, VERSION };

enum class SerialParseStatus : uint8_t {
  OK = 0,
  EMPTY,    // blank line: ignored without a reply
  UNKNOWN,  // unknown command, extra argument or non-printable byte
  TOO_LONG, // line above MAX_LINE_SIZE
  BAD_ARG,  // CALIB without a reference, with a reference above 3 or with extra text; BEEP without a bit, above 6 or with extra text; TIME with an argument other than SET, TIME SET with a bad format or date or a year outside 2025 to 2099
  BAD_NAME, // DUMP without a name, or a name that is not climate_v4_YYYYMM.csv, climate_v3_YYYYMM.csv, climate_v2_YYYYMM.csv, daily_v1_YYYY.csv or events_v1_YYYY.csv
};

struct SerialCommand {
  SerialCommandKind kind = SerialCommandKind::NONE;
  char file[SdLogCodec::LOG_NAME_SIZE] = {0};
  uint8_t reference = 0; // CALIB: 0 onboard, 1 to 3 a slot; BEEP: alert bit number 0 to 6
  bool time_set = false; // TIME: true for "TIME SET <time>"; `time` then holds the parsed local time
  RtcTime time;
};

// One log file as the storage reports it.
struct SerialLogEntry {
  char name[SdLogCodec::LOG_NAME_SIZE] = {0};
  uint32_t size = 0;
};

// Line-based parser for the USB serial console. It has no Arduino dependency. The grammar is
// "LIST", "INFO", "CONFIG", "STATUS", "CALIB 0..3", "BEEP 0..6", "TIME", "TIME SET YYYY-MM-DDTHH:MM:SS", "HISTORY", "VERSION" and "DUMP climate_v4_YYYYMM.csv" (or the old v3 and v2 names) or "DUMP daily_v1_YYYY.csv" or "DUMP events_v1_YYYY.csv": uppercase commands, single spaces, no path.
class SerialConsole {
public:
  static constexpr size_t MAX_LINE_SIZE = 40;
  static constexpr size_t MAX_LIST_ENTRIES = 48;

  static bool matches(const char* line, size_t len, const char* word) {
    return strlen(word) == len && memcmp(line, word, len) == 0;
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
    if (matches(line, cmd_len, "HISTORY")) {
      if (cmd_len != len) {
        return SerialParseStatus::UNKNOWN; // HISTORY takes no argument
      }
      out.kind = SerialCommandKind::HISTORY;
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
      // exactly one digit 0 to 3
      if (len != cmd_len + 2 || line[cmd_len + 1] < '0' || line[cmd_len + 1] > '3') {
        return SerialParseStatus::BAD_ARG;
      }
      out.kind = SerialCommandKind::CALIB;
      out.reference = static_cast<uint8_t>(line[cmd_len + 1] - '0');
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
      // exactly one digit 0 to 6 (the bit number of ALERT_FREEZER to ALERT_SENSOR_BATTERY)
      if (len != cmd_len + 2 || line[cmd_len + 1] < '0' || line[cmd_len + 1] > '6') {
        return SerialParseStatus::BAD_ARG;
      }
      out.kind = SerialCommandKind::BEEP;
      out.reference = static_cast<uint8_t>(line[cmd_len + 1] - '0');
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

// RTC copy of the values that STATUS reports. The pointers stay valid during the call.
struct SerialStatusSnapshot {
  const SwitchBotRegistry* registry = nullptr;
  const ClimateHistory* history = nullptr;
  const DeviceConfig* config = nullptr;
  BatteryReading battery;
  uint8_t alert_mask = 0;
  uint8_t sensor_battery_latch = 0; // SensorBatteryLatch bits, one per registry slot
  bool muted = false;
  uint32_t now_ms = 0; // RTC-backed clock
  SdStatus sd_status = SdStatus::UNKNOWN; // last card result
  uint32_t sd_status_ms = 0;              // clock value of that result
  const char* reset_reason = "UNKNOWN";   // ResetReasonCodec::text of the last boot
  int32_t abnormal_resets = -1;           // NVS count; negative when NVS is unreadable
};

// Runs one parsed command. Storage mounts the card for the command and unmounts it on every
// path. Interfaces:
//   Storage: bool mount(); void unmount(); size_t listLogs(SerialLogEntry*, size_t cap);
//            bool openLog(const char* name, uint32_t& size); size_t read(uint8_t*, size_t);
//            void closeLog(); uint64_t totalBytes(); uint64_t usedBytes();
//   Sink:    size_t write(const uint8_t*, size_t); returns the count written. A short count
//            ends a DUMP at once: the host sees a missing CRC32 line and rejects the file.
// Reply frames (text lines, LF):
//   LIST -> "FILE <name> <bytes>" per file, then "END LIST <count>"
//   INFO -> "INFO total=<bytes> used=<bytes> files=<count>"
//   DUMP -> "BEGIN <name> <bytes>", exactly <bytes> raw bytes, then "CRC32 <8 hex digits>"
//   CONFIG -> "key=value" per DeviceConfig setting, "SOURCE <SD|DEFAULT>", "REJECTED line=<n>" for each of the first four rejected lines, then "CRC32 <8 hex
//   digits>" over all lines before it. showConfig() sends it from the RTC copy: no card access,
//   so execute() ignores CONFIG.
//   STATUS -> "SENSOR <index> <name> temp=<C> rh=<%RH> bat=<%> rssi=<dBm> age=<s> state=<OK|STALE|EMPTY|BATLOW>" for index 0
//   (onboard, newest hourly sample, bat and rssi are "--") and 1 to 3 (slots, calibrated readings), a missing value gives "--";
//   then "SD status=<UNKNOWN|OK|NO_CARD|MOUNT_FAILED|WRITE_FAILED|FULL> age=<s|-->" (age since the last card result), then "RESET reason=<TEXT> abnormal=<N|-->", then "POWER src=<USB|BAT> v=<V> pct=<%> alerts=<hex mask> muted=<0|1>", then "CRC32 <8 hex digits>" over all lines
//   before it. A space in a name becomes "_". showStatus() works on the RTC copy: no card access, so execute() ignores STATUS.
//   BEEP N -> "OK BEEP <N> <duration ms>" after one playback of the chime of alert bit N (0 to 6), or "ERR MUTED"
//   (mute, quiet hours, volume 0) or "ERR PLAY_FAILED". beep() needs no card: execute() ignores BEEP.
//   TIME -> "TIME <YYYY-MM-DDTHH:MM:SS> valid=<0|1> osf=<0|1>" with the local time of the RTC (osf: oscillator stop flag);
//   TIME SET <YYYY-MM-DDTHH:MM:SS> -> "OK TIME" after the write (the write clears the oscillator stop flag and the
//   RtcDrift baseline), or "ERR RTC_FAILED". A bad format or date or a year outside 2025 to 2099 gives "ERR BAD_ARG"
//   from the parser. showTime() and setTime() need no card: execute() ignores TIME.
//   VERSION -> "VERSION fw=<version> git=<hash> core=<core> state=<STATE_VERSION> built=<YYYY-MM-DDTHH:MM:SS>", then "CRC32 <8 hex
//   digits>" over that line. The build time comes from __DATE__ and __TIME__. showVersion() needs no card: execute() ignores VERSION.
//   CALIB N -> "REFERENCE <name>", one "SENSOR <name> hours=<n> temp_offset=<x> rh_offset=<x> temp_diff=<x> rh_diff=<x>" or
//   "SKIP <name> hours=<n>" per other sensor, the "key=value" lines to paste into config.ini, then "CRC32 <8 hex digits>"
//   over all lines before it. showCalib() works on the RTC history: no card access, so execute() ignores CALIB.
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
    char text[DeviceConfigText::MAX_SIZE + 16 + ConfigRejects::MAX_LINES * 20];
    size_t len = DeviceConfigText::format(config, text, DeviceConfigText::MAX_SIZE);
    if (len == 0) {
      return;
    }
    len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "SOURCE %s\n", from_sd ? "SD" : "DEFAULT"));
    for (size_t i = 0; from_sd && i < rejects.recorded(); ++i) {
      len += static_cast<size_t>(
          snprintf(text + len, sizeof(text) - len, "REJECTED line=%u\n", static_cast<unsigned>(rejects.lines[i])));
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

  // Sends the mean differences of every sensor to the reference and the offsets that would remove them.
  // The hourly samples are paired by age (newest with newest), and only the hours with a sample in both
  // histories count. The samples already contain the active offsets. The card stays untouched.
  static void showCalib(Sink& sink, uint8_t reference, const ClimateHistory& history, const DeviceConfig& config) {
    static const char* const names[ClimateHistory::SLOT_COUNT] = {"onboard", "slot1", "slot2", "slot3"};
    static const char* const prefixes[ClimateHistory::SLOT_COUNT] = {"", "slot1_", "slot2_", "slot3_"};
    if (reference >= ClimateHistory::SLOT_COUNT) {
      return;
    }
    char text[1024];
    size_t len = static_cast<size_t>(snprintf(text, sizeof(text), "REFERENCE %s\n", names[reference]));
    char suggestions[512] = {0};
    size_t slen = 0;
    for (size_t s = 0; s < ClimateHistory::SLOT_COUNT; ++s) {
      if (s == reference) {
        continue;
      }
      const size_t hours = commonHours(history, reference, s);
      if (hours < MIN_COMMON_HOURS) {
        len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "SKIP %s hours=%u\n", names[s],
                                            static_cast<unsigned>(hours)));
        continue;
      }
      int32_t temp_sum = 0;
      int32_t rh_sum = 0;
      for (size_t k = 0; k < hours; ++k) {
        temp_sum += history.temperatures[s].at(history.temperatures[s].size() - 1 - k) -
                    history.temperatures[reference].at(history.temperatures[reference].size() - 1 - k);
        rh_sum += history.slots[s].at(history.slots[s].size() - 1 - k) -
                  history.slots[reference].at(history.slots[reference].size() - 1 - k);
      }
      const float temp_mean = static_cast<float>(temp_sum) / static_cast<float>(hours); // 0.1 steps
      const float rh_mean = static_cast<float>(rh_sum) / static_cast<float>(hours);
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
      len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len,
                                          "SENSOR %s hours=%u temp_offset=%s rh_offset=%s temp_diff=%s rh_diff=%s\n",
                                          names[s], static_cast<unsigned>(hours), f[0], f[1], f[2], f[3]));
      const int32_t temp_new = clampTenths(lroundf(tenths(temp_active) - temp_mean), temp_min, temp_max);
      const int32_t rh_new = clampTenths(lroundf(tenths(rh_active) - rh_mean), rh_min, rh_max);
      formatTenths(f[0], sizeof(f[0]), temp_new);
      formatTenths(f[1], sizeof(f[1]), rh_new);
      slen += static_cast<size_t>(snprintf(suggestions + slen, sizeof(suggestions) - slen,
                                           "%stemp_offset_c=%s\n%srh_offset_pct=%s\n", prefixes[s], f[0],
                                           prefixes[s], f[1]));
    }
    len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "%s", suggestions));
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
  static void showHistory(Sink& sink, const ClimateHistory& history, const NoiseHistory& noise) {
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
      const size_t temps = history.temperatures[s].size();
      const size_t rhs = history.slots[s].size();
      const size_t noises = s == ClimateHistory::ONBOARD ? noise.size() : 0;
      size_t hours = temps > rhs ? temps : rhs;
      hours = noises > hours ? noises : hours;
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

  // Sends a live snapshot of all sensors and of the power state. The card stays untouched.
  static void showStatus(Sink& sink, const SerialStatusSnapshot& snap) {
    if (snap.registry == nullptr || snap.history == nullptr || snap.config == nullptr) {
      return;
    }
    static const char* const defaults[ClimateHistory::SLOT_COUNT] = {"onboard", "slot1", "slot2", "slot3"};
    const DeviceConfig& cfg = *snap.config;
    const SwitchBotRegistry cal = snap.registry->calibrated(cfg.slot_temp_offset_c, cfg.slot_rh_offset_pct);
    char text[640];
    size_t len = 0;
    for (size_t i = 0; i < ClimateHistory::SLOT_COUNT; ++i) {
      const char* raw = i == 0 ? cfg.onboard_name : cfg.slot_name[i - 1];
      char name[DeviceConfig::NAME_MAX_CHARS + 1];
      snprintf(name, sizeof(name), "%s", raw[0] != '\0' ? raw : defaults[i]);
      for (char* c = name; *c != '\0'; ++c) {
        *c = *c == ' ' ? '_' : *c;
      }
      char temp[16] = "--", rh[8] = "--", bat[8] = "--", rssi[8] = "--", age[12] = "--";
      const char* state = "EMPTY";
      if (i == 0) {
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
        const SwitchBotReading& r = cal.slot(i - 1);
        if (r.valid) {
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
      len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len,
                                          "SENSOR %u %s temp=%s rh=%s bat=%s rssi=%s age=%s state=%s\n",
                                          static_cast<unsigned>(i), name, temp, rh, bat, rssi, age, state));
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
    if (snap.sd_status == SdStatus::UNKNOWN) {
      len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "SD status=UNKNOWN age=--\n"));
    } else {
      len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "SD status=%s age=%lu\n",
                                          SdStatusCodec::name(snap.sd_status),
                                          static_cast<unsigned long>(static_cast<uint32_t>(snap.now_ms - snap.sd_status_ms) / 1000UL)));
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
    len += static_cast<size_t>(snprintf(text + len, sizeof(text) - len, "POWER src=%s v=%s pct=%s alerts=%02x muted=%u\n",
                                        snap.battery.usb_powered ? "USB" : "BAT", volts, pct,
                                        static_cast<unsigned>(snap.alert_mask), snap.muted ? 1u : 0u));
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
  // Returns true when the RTC got the new time.
  template <typename Rtc>
  static bool setTime(Sink& sink, Rtc& rtc, RtcDriftState& drift, const RtcTime& t) {
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
    send(sink, "OK TIME\n");
    return true;
  }

  // Plays the chime of alert bit `bit` (0 to 6) once and replies "OK BEEP <bit> <duration ms>". The
  // reply "ERR MUTED" (mute, quiet hours or volume 0) leaves the hardware untouched, "ERR PLAY_FAILED"
  // follows a failed playback. The Hal powers down on every path (AudioSequencer). A cleared bit in
  // chime_alert_mask does not block the test, and no alert state is read or written here.
  template <typename Hal>
  static void beep(Sink& sink, Hal& hal, uint8_t bit, bool muted, bool quiet_hours, uint8_t volume_pct) {
    if (bit > 6) { // defense in depth: the parser already limits the bit to 0 to 6
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

  static void execute(Storage& storage, Sink& sink, const SerialCommand& command) {
    if (command.kind == SerialCommandKind::NONE || command.kind == SerialCommandKind::CONFIG ||
        command.kind == SerialCommandKind::CALIB || command.kind == SerialCommandKind::STATUS ||
        command.kind == SerialCommandKind::BEEP || command.kind == SerialCommandKind::TIME ||
        command.kind == SerialCommandKind::HISTORY || command.kind == SerialCommandKind::VERSION) {
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
      case SerialCommandKind::STATUS:
      case SerialCommandKind::BEEP:
      case SerialCommandKind::TIME:
      case SerialCommandKind::HISTORY:
      case SerialCommandKind::VERSION:
      default: break;
    }
    storage.unmount();
  }

  static constexpr size_t MIN_COMMON_HOURS = 6;

private:
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

  void closeLog() {
    if (file_) {
      file_.close();
    }
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
