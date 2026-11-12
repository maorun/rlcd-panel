#pragma once

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "device_config.h"
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

enum class SerialCommandKind : uint8_t { NONE = 0, LIST, DUMP, INFO, CONFIG, CALIB };

enum class SerialParseStatus : uint8_t {
  OK = 0,
  EMPTY,    // blank line: ignored without a reply
  UNKNOWN,  // unknown command, extra argument or non-printable byte
  TOO_LONG, // line above MAX_LINE_SIZE
  BAD_ARG,  // CALIB without a reference, with a reference above 3 or with extra text
  BAD_NAME, // DUMP without a name, or a name that is not climate_v4_YYYYMM.csv, climate_v3_YYYYMM.csv, climate_v2_YYYYMM.csv or daily_v1_YYYY.csv
};

struct SerialCommand {
  SerialCommandKind kind = SerialCommandKind::NONE;
  char file[SdLogCodec::LOG_NAME_SIZE] = {0};
  uint8_t reference = 0; // CALIB: 0 onboard, 1 to 3 a slot
};

// One log file as the storage reports it.
struct SerialLogEntry {
  char name[SdLogCodec::LOG_NAME_SIZE] = {0};
  uint32_t size = 0;
};

// Line-based parser for the USB serial console. It has no Arduino dependency. The grammar is
// "LIST", "INFO", "CONFIG", "CALIB 0..3" and "DUMP climate_v4_YYYYMM.csv" (or the old v3 and v2 names) or "DUMP daily_v1_YYYY.csv": uppercase commands, single spaces, no path.
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
    if (is_list || is_info || is_config) {
      if (cmd_len != len) {
        return SerialParseStatus::UNKNOWN; // these commands take no argument
      }
      out.kind = is_list ? SerialCommandKind::LIST
                         : (is_info ? SerialCommandKind::INFO : SerialCommandKind::CONFIG);
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
    return SerialParseStatus::UNKNOWN;
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

  static void execute(Storage& storage, Sink& sink, const SerialCommand& command) {
    if (command.kind == SerialCommandKind::NONE || command.kind == SerialCommandKind::CONFIG ||
        command.kind == SerialCommandKind::CALIB) {
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
