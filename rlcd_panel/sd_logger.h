#pragma once

#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "battery_monitor.h"
#include "board_pins.h"
#include "device_config.h"
#include "es7210_mic.h"
#include "ntp_sync.h"
#include "pcf85063_rtc.h"
#include "shtc3_sensor.h"
#include "sparkline.h"
#include "switchbot_ble.h"

#if defined(ARDUINO) || defined(ESP_PLATFORM)
#include <FS.h>
#include <SD_MMC.h>
#endif

namespace rlcd_panel {

// The CSV formats (climate_v5, daily_v4) hold the onboard sensor and slots 1 to 5. The BLE
// telemetry frame and the older files (climate_v2 to v4, daily_v1 and v2) keep the onboard sensor and slots 1 to 3.
constexpr size_t LOG_SOURCE_COUNT = 1 + MAX_SWITCHBOT_SENSORS;

// Noise summary of one day: the loudest hourly level, its local hour and the sum of the transient
// counts. It lives in the RTC memory (PersistentState) inside DailyLogState.
struct DailyNoiseStats {
  static constexpr uint8_t NO_PEAK = 0xFF; // no valid noise reading yet
  uint8_t peak_db = NO_PEAK;
  uint8_t peak_hour = 0;    // local hour 0 to 23 of the peak; 0 while there is no peak
  uint16_t events_sum = 0;  // saturates at 65535

  bool hasReading() const {
    return peak_db != NO_PEAK;
  }

  // Adds one hourly reading. The first of two equal peaks wins. An invalid `db` changes nothing.
  void add(uint8_t db, uint8_t hour, uint16_t events) {
    if (!NoiseHistory::isValid(db) || hour > 23) {
      return;
    }
    if (!hasReading() || db > peak_db) {
      peak_db = db;
      peak_hour = hour;
    }
    const uint32_t sum = static_cast<uint32_t>(events_sum) + events;
    events_sum = sum > 65535u ? static_cast<uint16_t>(65535) : static_cast<uint16_t>(sum);
  }
};

// Local day of the last handled daily summary. It lives in the RTC memory (PersistentState).
// `noise` collects the hourly readings since that stamp.
struct DailyLogState {
  bool has_date = false;
  uint16_t year = 2000;
  uint8_t month = 1;
  uint8_t day = 1;
  DailyNoiseStats noise;

  // Stamps `today` as handled and starts a new noise summary.
  void markDone(const RtcTime& today) {
    has_date = true;
    year = today.year;
    month = today.month;
    day = today.day;
    noise = DailyNoiseStats{};
  }
};

enum class DailyAction : uint8_t {
  NONE = 0, // nothing to do in this wake cycle
  MARK,     // stamp the day without a row (first run, or no fresh history)
  WRITE,    // write the row of the day that ended, then stamp
};

// Midnight gate of the daily summary. The RTC holds local time, so CET and CEST need no special case:
// the first hourly write on a new calendar date is the first one after local midnight.
class DailyPlanner {
public:
  static DailyAction decide(const DailyLogState& state, const RtcTime& now, bool history_fresh) {
    if (!now.valid || now.oscillator_stopped || now.year < SD_MIN_TRUSTED_YEAR ||
        !NtpTime::isValidDate(now.year, now.month, now.day)) {
      return DailyAction::NONE;
    }
    if (!state.has_date) {
      return DailyAction::MARK; // the first run has no complete day behind it
    }
    const int64_t today = NtpTime::daysFromCivil(now.year, now.month, now.day);
    const int64_t last = NtpTime::daysFromCivil(state.year, state.month, state.day);
    if (today <= last) {
      return DailyAction::NONE; // same day, or a clock that ran back
    }
    return history_fresh ? DailyAction::WRITE : DailyAction::MARK;
  }

  // Date of the day before `now`.
  static void previousDay(const RtcTime& now, uint16_t& year, uint8_t& month, uint8_t& day) {
    int32_t y = 0;
    NtpTime::civilFromDays(NtpTime::daysFromCivil(now.year, now.month, now.day) - 1, y, month, day);
    year = static_cast<uint16_t>(y);
  }

private:
  static constexpr uint16_t SD_MIN_TRUSTED_YEAR = 2025;
};

// Kind of a logged event. The values are stored in the RTC memory, so never renumber them.
enum class EventCode : uint8_t {
  NONE = 0,
  ALERT_RISE = 1,       // an alert bit went on; detail is the alert bit
  ALERT_FALL = 2,       // an alert bit went off; detail is the alert bit
  CONFIG_LOAD = 3,      // config.ini was loaded; detail is the count of rejected lines
  NTP_SYNC = 4,         // an NTP sync attempt ended; detail is the NtpResult (0 is OK)
  BATTERY_LOW_SET = 5,  // the low battery alert went on; detail is the cell percentage
  BATTERY_LOW_CLEAR = 6, // the low battery alert went off; detail is the cell percentage
  EMPTY_PROTECTION = 7, // the empty battery protection; detail 1 is set, 0 is release
  RESET = 8,            // a start that is no deep sleep wakeup; detail is the ResetReason
  SLOT_ASSIGN = 9,      // a new MAC took a registry slot; detail is slot (high byte) and BleFormat (low byte)
  SLOT_RELEASE = 10,    // releaseDeadSlots cleared a slot; detail is the slot number, or 0x01 in the high byte for a console SLOT CLEAR
  CONFIG_SET = 11,      // a console SET succeeded; detail is the DeviceConfigParser::KEYS index (low byte), transport (high byte: 0 USB, 1 BLE)
  SD_FAULT = 12,        // the card status changed to a fault or back to OK; detail is the SdStatus
  ALERT_ACK = 13,       // a console ACK stopped the chime reminder of an alert; detail is the alert bit (mask)
  SLOT_STALE = 14,      // a slot became stale or recovered; detail is the slot number (low byte), high byte 0 lost, 1 back
  POWER_SOURCE = 15,    // the power source changed; detail 1 is USB power, 0 is battery power
  MUTE_CHANGE = 16,     // the mute flag changed; detail low byte 1 muted, 0 unmuted, 2 snooze started; high byte source (0 BOOT, 1 USB, 2 BLE)
  CLOCK_SET = 17,       // the panel clock changed by a console command; detail low byte 0 TIME SET, 1 RTC TRIM; high byte transport (0 USB, 1 BLE)
  SHTC3_FAULT = 18,     // the onboard SHTC3 failed 3 reads in a row or recovered; detail low byte 1 fault, 0 back; high byte the Shtc3Result of the third failed read, 0 for back
  SENSOR_BATTERY = 19,  // the low battery latch of a slot changed; detail low byte is the slot number 1 to 10, high byte 1 low, 0 OK again
  JUMP_DROP = 20,       // JumpGuard dropped a reading jump of a slot; detail is the slot number 1 to 10
  SCAN_FAULT = 21,      // the BLE scan start failed or works again; detail 1 fault, 0 back
  WINDOW_OPEN = 22,     // the open window latch of a tile changed; detail low byte the tile (0 onboard, 1 to 10 slots), high byte 1 open, 0 closed
  RECEPTION_LOW = 23,   // the BLE reception rate of a slot fell below 50 % or is good again; detail low byte the slot number 1 to 10, high byte 1 low, 0 good
  WALL_DAMP = 24,       // the wall humidity latch of a tile changed; detail low byte the tile (0 onboard, 1 to 10 slots), high byte 1 damp, 0 dry again
  DRY_AIR = 25,         // the dry air latch of a tile changed; detail low byte the tile (0 onboard, 1 to 10 slots), high byte 1 dry, 0 good
  SENSOR_DRIFT = 26,    // the sensor drift latch of a tile changed; detail low byte the tile (0 onboard, 1 to 10 slots), high byte 1 drift, 0 good again
  HEAT_TILE = 27,       // the heat latch of a tile changed; detail low byte the tile (0 onboard, 1 to 10 slots), high byte 1 hot, 0 good
};

struct LogEvent {
  bool has_time = false; // false: the RTC time was invalid, the time column stays empty
  uint16_t year = 0;
  uint8_t month = 0;
  uint8_t day = 0;
  uint8_t hour = 0;
  uint8_t minute = 0;
  uint8_t second = 0;
  EventCode code = EventCode::NONE;
  uint16_t detail = 0;
};

// Ring of the last 16 events. It lives in the RTC memory (PersistentState). The hourly write appends
// the events to the yearly events file and clears the ring. A full ring drops the oldest event and
// counts it, so the next write reports the loss.
class EventRing {
public:
  static constexpr size_t CAPACITY = 16;
  static constexpr uint8_t MAX_CODE = static_cast<uint8_t>(EventCode::HEAT_TILE);

  size_t size() const { return count_; }
  uint16_t dropped() const { return dropped_; }

  // Event `index`, 0 is the oldest. Out of range gives an empty event.
  LogEvent at(size_t index) const { return index < count_ ? items_[index] : LogEvent{}; }

  // Adds an event. An invalid RTC time (or one with the oscillator-stop flag) stores no time.
  void record(const RtcTime& now, EventCode code, uint16_t detail) {
    if (code == EventCode::NONE) {
      return;
    }
    LogEvent e;
    e.code = code;
    e.detail = detail;
    if (timeUsable(now)) {
      e.has_time = true;
      e.year = now.year;
      e.month = now.month;
      e.day = now.day;
      e.hour = now.hour;
      e.minute = now.minute;
      e.second = now.second;
    }
    if (count_ == CAPACITY) {
      memmove(&items_[0], &items_[1], sizeof(LogEvent) * (CAPACITY - 1));
      --count_;
      if (dropped_ < 0xFFFF) {
        ++dropped_;
      }
    }
    items_[count_++] = e;
  }

  void clear() { *this = EventRing{}; }

  // Restores a stored ring. `items` holds CAPACITY entries; entries from `count` on must be empty.
  // False for a count above CAPACITY, a bad code, date or time, a dirty unused entry, or a dropped
  // count without a full ring. The ring is unchanged on failure.
  bool restore(uint8_t count, uint16_t dropped, const LogEvent* items) {
    if (items == nullptr || count > CAPACITY || (dropped != 0 && count != CAPACITY)) {
      return false;
    }
    for (size_t i = 0; i < CAPACITY; ++i) {
      const LogEvent& e = items[i];
      if (i >= count) {
        if (e.code != EventCode::NONE || e.detail != 0 || e.has_time || e.year != 0 || e.month != 0 ||
            e.day != 0 || e.hour != 0 || e.minute != 0 || e.second != 0) {
          return false;
        }
        continue;
      }
      if (static_cast<uint8_t>(e.code) < 1 || static_cast<uint8_t>(e.code) > MAX_CODE) {
        return false;
      }
      if (!e.has_time) {
        if (e.year != 0 || e.month != 0 || e.day != 0 || e.hour != 0 || e.minute != 0 || e.second != 0) {
          return false;
        }
      } else if (e.year < 2000 || e.year > 2099 || !NtpTime::isValidDate(e.year, e.month, e.day) ||
                 e.hour > 23 || e.minute > 59 || e.second > 59) {
        return false;
      }
    }
    EventRing r;
    for (size_t i = 0; i < count; ++i) {
      r.items_[i] = items[i];
    }
    r.count_ = count;
    r.dropped_ = dropped;
    *this = r;
    return true;
  }

private:
  static bool timeUsable(const RtcTime& t) {
    return t.valid && !t.oscillator_stopped && t.year >= 2000 && t.year <= 2099 &&
           NtpTime::isValidDate(t.year, t.month, t.day) && t.hour <= 23 && t.minute <= 59 && t.second <= 59;
  }

  LogEvent items_[CAPACITY];
  size_t count_ = 0;
  uint16_t dropped_ = 0;
};

// Decoupled CSV codec. It has no Arduino or SD_MMC dependency, so the host tests build it.
class SdLogCodec {
public:
  static constexpr uint32_t LOG_INTERVAL_MS = 3600000; // one record per hour
  static constexpr uint32_t RETRY_INTERVAL_MS = 600000; // after a failed write, retry in 10 min
  static constexpr size_t MAX_PATH_SIZE = 32;
  static constexpr size_t MAX_ROW_SIZE = 512;
  // One event row: time, longest name, 5-digit detail and newline fit in far less.
  static constexpr size_t MAX_EVENT_ROW_SIZE = 96;
  // Header line of the daily summary file, with the terminator.
  static constexpr size_t DAILY_HEADER_SIZE = 1280;
  static constexpr const char* MOUNT_POINT = "/sdcard";
  // "climate_v6_YYYYMM.csv" plus the terminator.
  static constexpr size_t LOG_NAME_SIZE = 22;
  // Upper bound of the shown free space (about 9.5 TB), so the diagnostics line has a fixed width.
  static constexpr uint32_t MAX_FREE_MB = 9999999;
  // Earliest RTC year that the retention accepts. A wrong clock in the future must not delete logs.
  static constexpr uint16_t MIN_TRUSTED_YEAR = 2025;
  // Files that one prune pass deletes at most. More old files go in the next hourly pass.
  static constexpr size_t MAX_PRUNE_PER_PASS = 24;

  // Splits a bare log file name "climate_v6_YYYYMM.csv" (or the old v5, v4, v3 and v2 names) into year 2000..2099 and month 01..12.
  // Anything else is false, so a path separator or "." never reaches the card.
  static bool parseLogFileName(const char* name, int& year, int& month) {
    static const char PREFIX[] = "climate_v6_";
    static const char PREFIX_V5[] = "climate_v5_"; // old 27-column files: listed and pruned, never written
    static const char PREFIX_V4[] = "climate_v4_"; // old 21-column files: listed and pruned, never written
    static const char PREFIX_V3[] = "climate_v3_"; // old 19-column files: listed and pruned, never written
    static const char PREFIX_V2[] = "climate_v2_"; // old 18-column files: listed and pruned, never written
    static const char SUFFIX[] = ".csv";
    constexpr size_t PREFIX_LEN = sizeof(PREFIX) - 1;
    constexpr size_t SUFFIX_LEN = sizeof(SUFFIX) - 1;
    if (name == nullptr || strlen(name) != PREFIX_LEN + 6 + SUFFIX_LEN ||
        (memcmp(name, PREFIX, PREFIX_LEN) != 0 && memcmp(name, PREFIX_V5, PREFIX_LEN) != 0 &&
         memcmp(name, PREFIX_V4, PREFIX_LEN) != 0 && memcmp(name, PREFIX_V3, PREFIX_LEN) != 0 &&
         memcmp(name, PREFIX_V2, PREFIX_LEN) != 0) ||
        memcmp(name + PREFIX_LEN + 6, SUFFIX, SUFFIX_LEN) != 0) {
      return false;
    }
    for (size_t i = 0; i < 6; ++i) {
      if (name[PREFIX_LEN + i] < '0' || name[PREFIX_LEN + i] > '9') {
        return false;
      }
    }
    const int y = (name[PREFIX_LEN] - '0') * 1000 + (name[PREFIX_LEN + 1] - '0') * 100 +
                  (name[PREFIX_LEN + 2] - '0') * 10 + (name[PREFIX_LEN + 3] - '0');
    const int m = (name[PREFIX_LEN + 4] - '0') * 10 + (name[PREFIX_LEN + 5] - '0');
    if (y < 2000 || y > 2099 || m < 1 || m > 12) {
      return false;
    }
    year = y;
    month = m;
    return true;
  }

  // True for a bare log file name "climate_v5_YYYYMM.csv", "climate_v4_YYYYMM.csv", "climate_v3_YYYYMM.csv" or "climate_v2_YYYYMM.csv" with year 2000..2099 and month 01..12.
  static bool isLogFileName(const char* name) {
    int year = 0;
    int month = 0;
    return parseLogFileName(name, year, month);
  }

  // Whole months from (year, month) to (now_year, now_month). Negative for a month in the future.
  // Works across a year change: December 2025 to January 2026 is 1.
  static int monthsBetween(int year, int month, int now_year, int now_month) {
    return (now_year * 12 + (now_month - 1)) - (year * 12 + (month - 1));
  }

  // True when the retention setting deletes old files: 1..MAX_RETENTION_MONTHS. 0 keeps all files,
  // and an out-of-range value deletes nothing.
  static bool retentionActive(uint8_t retention_months) {
    return retention_months >= 1 && retention_months <= DeviceConfig::MAX_LOG_RETENTION_MONTHS;
  }

  // True when `name` is a log file older than `retention_months`. The file of the current month
  // (age 0) and a file in the future are never old. A name that does not match the pattern is
  // never old, so no other file is deleted. A clock that is invalid, flagged by the oscillator-stop
  // bit, or before MIN_TRUSTED_YEAR deletes nothing.
  static bool shouldDeleteLog(const char* name, const RtcTime& now, uint8_t retention_months) {
    int year = 0;
    int month = 0;
    if (!retentionActive(retention_months) || !now.valid || now.oscillator_stopped ||
        now.year < MIN_TRUSTED_YEAR || !parseLogFileName(name, year, month)) {
      return false;
    }
    const int age = monthsBetween(year, month, static_cast<int>(now.year), static_cast<int>(now.month));
    return age > static_cast<int>(retention_months);
  }

  // ---- Daily summary file "daily_v7_YYYY.csv" (the older "daily_v6" to "daily_v1" files stay readable) ----

  // Splits a bare yearly file name "<prefix>YYYY.csv" into the year 2000..2099.
  static bool parseYearFileName(const char* name, const char* prefix, int& year) {
    static const char SUFFIX[] = ".csv";
    const size_t prefix_len = strlen(prefix);
    constexpr size_t SUFFIX_LEN = sizeof(SUFFIX) - 1;
    if (name == nullptr || strlen(name) != prefix_len + 4 + SUFFIX_LEN ||
        memcmp(name, prefix, prefix_len) != 0 || memcmp(name + prefix_len + 4, SUFFIX, SUFFIX_LEN) != 0) {
      return false;
    }
    int y = 0;
    for (size_t i = 0; i < 4; ++i) {
      if (name[prefix_len + i] < '0' || name[prefix_len + i] > '9') {
        return false;
      }
      y = y * 10 + (name[prefix_len + i] - '0');
    }
    if (y < 2000 || y > 2099) {
      return false;
    }
    year = y;
    return true;
  }

  // Splits a bare daily file name "daily_v1_YYYY.csv" to "daily_v7_YYYY.csv" into the year 2000..2099.
  static bool parseDailyFileName(const char* name, int& year) {
    return parseYearFileName(name, "daily_v7_", year) || parseYearFileName(name, "daily_v6_", year) || parseYearFileName(name, "daily_v5_", year) ||
           parseYearFileName(name, "daily_v4_", year) ||
           parseYearFileName(name, "daily_v3_", year) || parseYearFileName(name, "daily_v2_", year) ||
           parseYearFileName(name, "daily_v1_", year);
  }

  static bool isDailyFileName(const char* name) {
    int year = 0;
    return parseDailyFileName(name, year);
  }

  // Splits a bare event file name "events_v1_YYYY.csv" into the year 2000..2099.
  static bool parseEventsFileName(const char* name, int& year) {
    return parseYearFileName(name, "events_v1_", year);
  }

  static bool isEventsFileName(const char* name) {
    int year = 0;
    return parseEventsFileName(name, year);
  }

  // True for each file that the serial console lists and dumps: hourly, daily and event files.
  static bool isConsoleFileName(const char* name) {
    return isLogFileName(name) || isDailyFileName(name) || isEventsFileName(name);
  }

  // True when `name` is an hourly, daily or event file that the retention deletes. A daily or event
  // file counts as December of its year, so it stays until the whole year is older than the retention.
  static bool shouldDeleteFile(const char* name, const RtcTime& now, uint8_t retention_months) {
    int year = 0;
    if (!parseDailyFileName(name, year) && !parseEventsFileName(name, year)) {
      return shouldDeleteLog(name, now, retention_months);
    }
    if (!retentionActive(retention_months) || !now.valid || now.oscillator_stopped ||
        now.year < MIN_TRUSTED_YEAR) {
      return false;
    }
    return monthsBetween(year, 12, static_cast<int>(now.year), static_cast<int>(now.month)) >
           static_cast<int>(retention_months);
  }

  // Writes "/sdcard/daily_v7_YYYY.csv". Returns the length, or 0 for a null or small buffer or a
  // year outside 2000..2099.
  static size_t formatDailyFilename(uint16_t year, char* out, size_t capacity) {
    return formatDailyFilenameVersion(year, 7, out, capacity);
  }

  // Writes "/sdcard/daily_v6_YYYY.csv", the old 94-column file that is read but never written.
  static size_t formatDailyFilenameV6(uint16_t year, char* out, size_t capacity) {
    return formatDailyFilenameVersion(year, 6, out, capacity);
  }

  // Writes "/sdcard/daily_v5_YYYY.csv", the old 83-column file that is read but never written.
  static size_t formatDailyFilenameV5(uint16_t year, char* out, size_t capacity) {
    return formatDailyFilenameVersion(year, 5, out, capacity);
  }

  static size_t formatDailyFilenameVersion(uint16_t year, unsigned version, char* out, size_t capacity) {
    if (out == nullptr || capacity == 0) {
      return 0;
    }
    out[0] = '\0';
    if (year < 2000 || year > 2099) {
      return 0;
    }
    const int n = snprintf(out, capacity, "%s/daily_v%u_%04u.csv", MOUNT_POINT, version, static_cast<unsigned>(year));
    if (n <= 0 || static_cast<size_t>(n) >= capacity) {
      out[0] = '\0';
      return 0;
    }
    return static_cast<size_t>(n);
  }

  // Header line of the daily file: the date, six columns for each of the 11 sensors, the noise
  // range, the noise peak summary, the mold hours, the dry hours and the heat hours of each sensor (105 columns).
  static size_t formatDailyHeader(char* out, size_t capacity) {
    static const char* const SENSORS[] = {
        "onboard", "s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10"};
    static_assert(sizeof(SENSORS) / sizeof(SENSORS[0]) == LOG_SOURCE_COUNT, "one name for each logged sensor");
    if (out == nullptr || capacity == 0) {
      return 0;
    }
    size_t pos = 0;
    int n = snprintf(out, capacity, "date");
    for (size_t i = 0; n > 0 && i < sizeof(SENSORS) / sizeof(SENSORS[0]); ++i) {
      pos += static_cast<size_t>(n);
      if (pos >= capacity) {
        break;
      }
      n = snprintf(out + pos, capacity - pos,
                   ",%s_temp_min_c,%s_temp_max_c,%s_temp_mean_c,%s_rh_min,%s_rh_max,%s_rh_mean",
                   SENSORS[i], SENSORS[i], SENSORS[i], SENSORS[i], SENSORS[i], SENSORS[i]);
    }
    if (n > 0) {
      pos += static_cast<size_t>(n);
      if (pos < capacity) {
        n = snprintf(out + pos, capacity - pos, ",noise_min_db,noise_max_db,noise_peak_db,noise_peak_hour,noise_events_sum");
        pos += n > 0 ? static_cast<size_t>(n) : capacity;
      }
    }
    for (size_t i = 0; pos < capacity && i < LOG_SOURCE_COUNT; ++i) {
      const int n2 = snprintf(out + pos, capacity - pos, ",mold_h_%u", static_cast<unsigned>(i));
      pos += n2 > 0 ? static_cast<size_t>(n2) : capacity;
    }
    for (size_t i = 0; pos < capacity && i < LOG_SOURCE_COUNT; ++i) {
      const int n2 = snprintf(out + pos, capacity - pos, ",dry_h_%u", static_cast<unsigned>(i));
      pos += n2 > 0 ? static_cast<size_t>(n2) : capacity;
    }
    for (size_t i = 0; pos < capacity && i < LOG_SOURCE_COUNT; ++i) {
      const int n2 = snprintf(out + pos, capacity - pos, ",heat_h_%u", static_cast<unsigned>(i));
      pos += n2 > 0 ? static_cast<size_t>(n2) : capacity;
    }
    if (pos < capacity) {
      const int n3 = snprintf(out + pos, capacity - pos, "\n");
      pos += n3 > 0 ? static_cast<size_t>(n3) : capacity;
    }
    if (pos >= capacity) {
      out[0] = '\0';
      return 0;
    }
    return pos;
  }

  // Writes one daily row, including the newline: the date, then minimum, maximum and mean of the
  // temperature and of the humidity for the onboard sensor and the five slots, then the minimum and
  // maximum noise level, then the peak level, its local hour and the event sum of `stats`, then for each
  // sensor the hours above `mold_limit_rh` (HumidityHistory::countAbove), then for each sensor the hours at or
  // below `dry_limit_rh` (HumidityHistory::countAtOrBelow), then for each sensor the hours at or above
  // `heat_limit_c` (ClimateHistory::heatHours). A sensor without samples, a day without a valid noise reading,
  // `dry_limit_rh` 0 (the dry hours only), `heat_limit_c` 0 and an unknown heat count (unequal history sizes,
  // fewer than HumidityHistory::MIN_RANGE_SAMPLES pairs; the heat hours only) give empty fields, so every row has
  // 105 columns.
  // Returns the length, or 0 for a null or small buffer or an invalid date.
  static size_t formatDailyRow(
      uint16_t year, uint8_t month, uint8_t day, const ClimateHistory& history, const NoiseHistory& noise,
      const DailyNoiseStats& stats, char* out, size_t capacity,
      uint8_t mold_limit_rh = DeviceConfig::DEFAULT_MOLD_ALERT_LIMIT_RH, uint8_t dry_limit_rh = 0,
      uint8_t heat_limit_c = 0) {
    if (out == nullptr || capacity == 0) {
      return 0;
    }
    out[0] = '\0';
    if (capacity < MAX_ROW_SIZE || year < 2000 || year > 2099 || month < 1 || month > 12 || day < 1 ||
        day > 31) {
      return 0;
    }
    size_t pos = static_cast<size_t>(snprintf(
        out, capacity, "%04u-%02u-%02u", static_cast<unsigned>(year), static_cast<unsigned>(month),
        static_cast<unsigned>(day)));
    for (size_t i = 0; i < LOG_SOURCE_COUNT; ++i) {
      int16_t lo = 0, hi = 0, mean = 0;
      const bool has_t = history.temperatures[i].summary(lo, hi, mean);
      pos = appendTenths(out, capacity, pos, has_t, lo);
      pos = appendTenths(out, capacity, pos, has_t, hi);
      pos = appendTenths(out, capacity, pos, has_t, mean);
      const bool has_h = history.slots[i].summary(lo, hi, mean);
      pos = appendTenths(out, capacity, pos, has_h, lo);
      pos = appendTenths(out, capacity, pos, has_h, hi);
      pos = appendTenths(out, capacity, pos, has_h, mean);
    }
    uint8_t nlo = 0, nhi = 0;
    if (noise.range(nlo, nhi)) {
      pos += static_cast<size_t>(snprintf(out + pos, capacity - pos, ",%u,%u", static_cast<unsigned>(nlo),
                                          static_cast<unsigned>(nhi)));
    } else {
      pos += static_cast<size_t>(snprintf(out + pos, capacity - pos, ",,"));
    }
    if (stats.hasReading()) {
      pos += static_cast<size_t>(snprintf(
          out + pos, capacity - pos, ",%u,%u,%u", static_cast<unsigned>(stats.peak_db),
          static_cast<unsigned>(stats.peak_hour), static_cast<unsigned>(stats.events_sum)));
    } else {
      pos += static_cast<size_t>(snprintf(out + pos, capacity - pos, ",,,"));
    }
    for (size_t i = 0; i < LOG_SOURCE_COUNT; ++i) {
      if (history.slots[i].size() == 0) {
        pos += static_cast<size_t>(snprintf(out + pos, capacity - pos, ","));
      } else {
        pos += static_cast<size_t>(snprintf(
            out + pos, capacity - pos, ",%u",
            static_cast<unsigned>(history.slots[i].countAbove(static_cast<uint16_t>(mold_limit_rh * 10u)))));
      }
    }
    for (size_t i = 0; i < LOG_SOURCE_COUNT; ++i) {
      if (dry_limit_rh == 0 || history.slots[i].size() == 0) {
        pos += static_cast<size_t>(snprintf(out + pos, capacity - pos, ","));
      } else {
        pos += static_cast<size_t>(snprintf(
            out + pos, capacity - pos, ",%u",
            static_cast<unsigned>(history.slots[i].countAtOrBelow(static_cast<uint16_t>(dry_limit_rh * 10u)))));
      }
    }
    for (size_t i = 0; i < LOG_SOURCE_COUNT; ++i) {
      const int heat = history.heatHours(i, heat_limit_c); // -1 for limit 0 and an unknown count
      if (heat < 0) {
        pos += static_cast<size_t>(snprintf(out + pos, capacity - pos, ","));
      } else {
        pos += static_cast<size_t>(snprintf(out + pos, capacity - pos, ",%u", static_cast<unsigned>(heat)));
      }
    }
    pos += static_cast<size_t>(snprintf(out + pos, capacity - pos, "\n"));
    if (pos >= capacity) {
      out[0] = '\0';
      return 0;
    }
    return pos;
  }

  // Writes "/sdcard/events_v1_YYYY.csv". Returns the length, or 0 for a null or small buffer or a
  // year outside 2000..2099.
  static size_t formatEventsFilename(uint16_t year, char* out, size_t capacity) {
    if (out == nullptr || capacity == 0) {
      return 0;
    }
    out[0] = '\0';
    if (year < 2000 || year > 2099) {
      return 0;
    }
    const int n = snprintf(out, capacity, "%s/events_v1_%04u.csv", MOUNT_POINT, static_cast<unsigned>(year));
    if (n <= 0 || static_cast<size_t>(n) >= capacity) {
      out[0] = '\0';
      return 0;
    }
    return static_cast<size_t>(n);
  }

  // Header line of the events file, with the newline. Returns the length, or 0 for a null or small buffer.
  static size_t formatEventsHeader(char* out, size_t capacity) {
    static const char HEADER[] = "time,event,detail\n";
    const size_t len = sizeof(HEADER) - 1;
    if (out == nullptr || capacity < len + 1) {
      return 0;
    }
    memcpy(out, HEADER, len + 1);
    return len;
  }

  static const char* eventName(EventCode code) {
    switch (code) {
      case EventCode::ALERT_RISE: return "ALERT_RISE";
      case EventCode::ALERT_FALL: return "ALERT_FALL";
      case EventCode::CONFIG_LOAD: return "CONFIG_LOAD";
      case EventCode::NTP_SYNC: return "NTP_SYNC";
      case EventCode::BATTERY_LOW_SET: return "BATTERY_LOW_SET";
      case EventCode::BATTERY_LOW_CLEAR: return "BATTERY_LOW_CLEAR";
      case EventCode::EMPTY_PROTECTION: return "EMPTY_PROTECTION";
      case EventCode::RESET: return "RESET";
      case EventCode::SLOT_ASSIGN: return "SLOT_ASSIGN";
      case EventCode::SLOT_RELEASE: return "SLOT_RELEASE";
      case EventCode::CONFIG_SET: return "CONFIG_SET";
      case EventCode::SD_FAULT: return "SD_FAULT";
      case EventCode::ALERT_ACK: return "ALERT_ACK";
      case EventCode::SLOT_STALE: return "SLOT_STALE";
      case EventCode::POWER_SOURCE: return "POWER_SOURCE";
      case EventCode::MUTE_CHANGE: return "MUTE_CHANGE";
      case EventCode::CLOCK_SET: return "CLOCK_SET";
      case EventCode::SHTC3_FAULT: return "SHTC3_FAULT";
      case EventCode::SENSOR_BATTERY: return "SENSOR_BATTERY";
      case EventCode::JUMP_DROP: return "JUMP_DROP";
      case EventCode::SCAN_FAULT: return "SCAN_FAULT";
      case EventCode::WINDOW_OPEN: return "WINDOW_OPEN";
      case EventCode::RECEPTION_LOW: return "RECEPTION_LOW";
      case EventCode::WALL_DAMP: return "WALL_DAMP";
      case EventCode::DRY_AIR: return "DRY_AIR";
      case EventCode::SENSOR_DRIFT: return "SENSOR_DRIFT";
      case EventCode::HEAT_TILE: return "HEAT_TILE";
      case EventCode::NONE: break;
    }
    return nullptr;
  }

  // Writes one event row "YYYY-MM-DD hh:mm:ss,NAME,detail" with the newline. An event without a time
  // has an empty first column. Returns the length, or 0 for a null or small buffer or a bad code.
  static size_t formatEventRow(const LogEvent& event, char* out, size_t capacity) {
    if (out == nullptr || capacity == 0) {
      return 0;
    }
    out[0] = '\0';
    const char* name = eventName(event.code);
    if (name == nullptr || capacity < MAX_EVENT_ROW_SIZE) {
      return 0;
    }
    int n = 0;
    if (event.has_time) {
      n = snprintf(out, capacity, "%04u-%02u-%02u %02u:%02u:%02u,%s,%u\n", static_cast<unsigned>(event.year),
                   static_cast<unsigned>(event.month), static_cast<unsigned>(event.day),
                   static_cast<unsigned>(event.hour), static_cast<unsigned>(event.minute),
                   static_cast<unsigned>(event.second), name, static_cast<unsigned>(event.detail));
    } else {
      n = snprintf(out, capacity, ",%s,%u\n", name, static_cast<unsigned>(event.detail));
    }
    if (n <= 0 || static_cast<size_t>(n) >= capacity) {
      out[0] = '\0';
      return 0;
    }
    return static_cast<size_t>(n);
  }

  // Writes the row that reports lost events: "YYYY-MM-DD hh:mm:ss,EVENTS_DROPPED,<N>" with the time of
  // the write. Returns the length, or 0 for a null or small buffer, a zero count or an invalid time.
  static size_t formatEventsDroppedRow(const RtcTime& now, uint16_t dropped, char* out, size_t capacity) {
    if (out == nullptr || capacity == 0) {
      return 0;
    }
    out[0] = '\0';
    if (dropped == 0 || !now.valid || capacity < MAX_EVENT_ROW_SIZE) {
      return 0;
    }
    const int n = snprintf(out, capacity, "%04u-%02u-%02u %02u:%02u:%02u,EVENTS_DROPPED,%u\n",
                           static_cast<unsigned>(now.year), static_cast<unsigned>(now.month),
                           static_cast<unsigned>(now.day), static_cast<unsigned>(now.hour),
                           static_cast<unsigned>(now.minute), static_cast<unsigned>(now.second),
                           static_cast<unsigned>(dropped));
    if (n <= 0 || static_cast<size_t>(n) >= capacity) {
      out[0] = '\0';
      return 0;
    }
    return static_cast<size_t>(n);
  }

  // Free space in whole MB from the card totals. -1 (unknown) when the totals are not plausible.
  static int32_t freeMegabytes(uint64_t total_bytes, uint64_t used_bytes) {
    if (total_bytes == 0 || used_bytes > total_bytes) {
      return -1;
    }
    const uint64_t mb = (total_bytes - used_bytes) / (1024ULL * 1024ULL);
    return mb > MAX_FREE_MB ? static_cast<int32_t>(MAX_FREE_MB) : static_cast<int32_t>(mb);
  }

  // True when a record is due. Unsigned subtraction survives clock wrap-around.
  static bool logDue(bool has_logged, uint32_t last_log_ms, uint32_t now_ms) {
    return !has_logged || static_cast<uint32_t>(now_ms - last_log_ms) >= LOG_INTERVAL_MS;
  }

  // True when the wake cycle runs the 5 s noise window for the hourly record: a record is due,
  // the RTC time is valid (a row needs it), and no low battery alert is active. Any other wake
  // cycle keeps the microphones off. An invalid battery reading raises no alert.
  static bool noiseMeasureWanted(
      bool has_logged, uint32_t last_log_ms, uint32_t now_ms, bool time_valid,
      const BatteryReading& battery) {
    const bool low_battery = battery.valid && battery.low_battery;
    return time_valid && logDue(has_logged, last_log_ms, now_ms) && !low_battery;
  }

  // Clock value to store after a failed write, so the next attempt is due after the retry
  // interval and a missing card does not mount on every wake cycle.
  static uint32_t retryStamp(uint32_t now_ms) {
    return now_ms - (LOG_INTERVAL_MS - RETRY_INTERVAL_MS);
  }

  // Writes the CSV header line, including the newline. Returns the length, or 0 when the
  // buffer is null or too small.
  static size_t formatHeader(char* out, size_t capacity) {
    static const char HEADER[] =
        "timestamp,battery_pct,battery_v,usb,onboard_temp_c,onboard_rh,"
        "s1_temp_c,s1_rh,s1_battery_pct,"
        "s2_temp_c,s2_rh,s2_battery_pct,"
        "s3_temp_c,s3_rh,s3_battery_pct,"
        "s4_temp_c,s4_rh,s4_battery_pct,"
        "s5_temp_c,s5_rh,s5_battery_pct,"
        "s6_temp_c,s6_rh,s6_battery_pct,"
        "s7_temp_c,s7_rh,s7_battery_pct,"
        "s8_temp_c,s8_rh,s8_battery_pct,"
        "s9_temp_c,s9_rh,s9_battery_pct,"
        "s10_temp_c,s10_rh,s10_battery_pct,"
        "noise_rms,noise_db,noise_band,noise_events,noise_l10,noise_l90\n";
    const size_t len = sizeof(HEADER) - 1;
    if (out == nullptr || capacity < len + 1) {
      return 0;
    }
    memcpy(out, HEADER, len + 1);
    return len;
  }

  // Writes "/sdcard/climate_v6_YYYYMM.csv". The v6 name keeps the format apart from
  // the old v5, v4, v3 and v2 files, which stay unchanged. Returns the length, or 0 for a null buffer, a small
  // buffer, a null or invalid time.
  static size_t formatFilename(const RtcTime* time, char* out, size_t capacity) {
    if (out == nullptr || capacity == 0) {
      return 0;
    }
    out[0] = '\0';
    if (time == nullptr || !time->valid || time->year < 2000 || time->year > 2099 ||
        time->month < 1 || time->month > 12) {
      return 0;
    }
    const int n = snprintf(
        out, capacity, "%s/climate_v6_%04u%02u.csv", MOUNT_POINT,
        static_cast<unsigned>(time->year), static_cast<unsigned>(time->month));
    if (n <= 0 || static_cast<size_t>(n) >= capacity) {
      out[0] = '\0';
      return 0;
    }
    return static_cast<size_t>(n);
  }

  // Writes one CSV record, including the newline. A missing, invalid or stale sensor gives
  // empty fields, so every row has the same column count (27). A null or invalid `noise` gives empty
  // noise fields, also an empty event count and empty L10 and L90 fields (also for a window without percentiles); the dB value uses `noise_cal` (the default calibration when null). Returns the
  // length, or 0 on a null or small buffer, or a null or invalid time.
  static size_t formatRow(
      const RtcTime* time,
      const BatteryReading* battery,
      const Shtc3Reading* onboard,
      const SwitchBotReading* slots,
      size_t slot_count,
      uint32_t now_ms,
      const NoiseReading* noise,
      const MicCalibration* noise_cal,
      char* out,
      size_t capacity,
      uint32_t stale_timeout_ms = SENSOR_STALE_TIMEOUT_MS) {
    if (out == nullptr || capacity == 0) {
      return 0;
    }
    out[0] = '\0';
    if (time == nullptr || !time->valid || capacity < MAX_ROW_SIZE) {
      return 0;
    }
    size_t pos = 0;
    pos += snprintf(
        out + pos, capacity - pos, "%04u-%02u-%02u %02u:%02u:%02u",
        static_cast<unsigned>(time->year), static_cast<unsigned>(time->month),
        static_cast<unsigned>(time->day), static_cast<unsigned>(time->hour),
        static_cast<unsigned>(time->minute), static_cast<unsigned>(time->second));

    if (battery != nullptr && battery->valid) {
      pos += snprintf(out + pos, capacity - pos, ",%d,%.2f,%d", battery->percentage,
                      static_cast<double>(battery->voltage_v), battery->usb_powered ? 1 : 0);
    } else {
      pos += snprintf(out + pos, capacity - pos, ",,,");
    }

    if (onboard != nullptr && onboard->valid && isfinite(onboard->temperature_c) &&
        isfinite(onboard->humidity_pct)) {
      pos += snprintf(out + pos, capacity - pos, ",%.1f,%.1f",
                      static_cast<double>(onboard->temperature_c),
                      static_cast<double>(onboard->humidity_pct));
    } else {
      pos += snprintf(out + pos, capacity - pos, ",,");
    }

    for (size_t i = 0; i < LOG_SOURCE_COUNT - 1; ++i) { // slots 1 to 5
      const SwitchBotReading* r = (slots != nullptr && i < slot_count) ? &slots[i] : nullptr;
      if (r != nullptr && r->valid && !r->isStale(now_ms, stale_timeout_ms) && isfinite(r->temperature_c)) {
        pos += snprintf(out + pos, capacity - pos, ",%.1f,%u,", static_cast<double>(r->temperature_c),
                        static_cast<unsigned>(r->humidity_pct));
        if (r->battery_pct >= 0) {
          pos += snprintf(out + pos, capacity - pos, "%d", r->battery_pct);
        }
      } else {
        pos += snprintf(out + pos, capacity - pos, ",,,");
      }
    }
    if (noise != nullptr && noise->valid) {
      const MicCalibration cal = noise_cal != nullptr ? *noise_cal : MicCalibration{};
      pos += snprintf(out + pos, capacity - pos, ",%u,%d,%s,%u", static_cast<unsigned>(noise->rms),
                      cal.splDb(noise->rms), NoiseClassifier::label(noise->band),
                      static_cast<unsigned>(noise->events));
      if (noise->levels_valid) {
        pos += snprintf(out + pos, capacity - pos, ",%d,%d", cal.splDb(noise->l10_rms),
                        cal.splDb(noise->l90_rms));
      } else {
        pos += snprintf(out + pos, capacity - pos, ",,");
      }
    } else {
      pos += snprintf(out + pos, capacity - pos, ",,,,,,");
    }
    pos += snprintf(out + pos, capacity - pos, "\n");
    if (pos >= capacity) {
      out[0] = '\0';
      return 0;
    }
    return pos;
  }

  // ---- Restore of the 24-hour histories from the hourly rows ----

  static constexpr size_t CLIMATE_COLUMNS = 6 + 3 * (LOG_SOURCE_COUNT - 1) + 6; // 42
  static constexpr size_t RESTORE_TAIL_BYTES = 8192; // the read limit of each file
  static constexpr size_t RESTORE_ROW_CAPACITY = 48;

  // The newest rows of a log file, oldest first. A full buffer drops the oldest row.
  struct ClimateRowBuffer {
    ClimateHistory::RestoreRow rows[RESTORE_ROW_CAPACITY];
    size_t count = 0;

    void append(const ClimateHistory::RestoreRow& row) {
      if (count == RESTORE_ROW_CAPACITY) {
        memmove(rows, rows + 1, (RESTORE_ROW_CAPACITY - 1) * sizeof(rows[0]));
        --count;
      }
      rows[count++] = row;
    }

    // Puts the rows of `older` in front of the own rows (the newest rows win when they do not all fit).
    // Works in place: the 3 KB buffer must not be copied onto the small loop task stack.
    void prepend(const ClimateRowBuffer& older) {
      const size_t total = older.count + count;
      const size_t kept = total < RESTORE_ROW_CAPACITY ? total : RESTORE_ROW_CAPACITY;
      const size_t own = count < kept ? count : kept;
      const size_t from_older = kept - own;
      memmove(rows + from_older, rows + (count - own), own * sizeof(rows[0]));
      memcpy(rows, older.rows + (older.count - from_older), from_older * sizeof(rows[0]));
      count = kept;
    }
  };

  // Parses a decimal with at most one fractional digit ("-3.5", "80", "21.0") into tenths. False for an
  // empty field, other characters or a value outside [lo, hi].
  static bool parseTenths(const char* s, size_t len, int lo_tenths, int hi_tenths, int16_t& out) {
    size_t i = 0;
    bool negative = false;
    if (i < len && s[i] == '-') {
      negative = true;
      ++i;
    }
    int whole = 0;
    size_t digits = 0;
    while (i < len && s[i] >= '0' && s[i] <= '9' && digits < 4) {
      whole = whole * 10 + (s[i] - '0');
      ++i;
      ++digits;
    }
    int frac = 0;
    if (i < len && s[i] == '.') {
      ++i;
      if (i >= len || s[i] < '0' || s[i] > '9') {
        return false;
      }
      frac = s[i] - '0';
      ++i;
    }
    if (digits == 0 || i != len) {
      return false;
    }
    const int tenths = (negative ? -1 : 1) * (whole * 10 + frac);
    if (tenths < lo_tenths || tenths > hi_tenths) {
      return false;
    }
    out = static_cast<int16_t>(tenths);
    return true;
  }

  // Parses one hourly data row of the current format (42 columns, without the newline) for the restore.
  // An empty field is a missing value. A wrong column count, a bad timestamp or a bad number rejects the
  // row. The header line has a bad timestamp, so it is rejected too.
  static bool parseClimateRow(const char* line, size_t len, ClimateHistory::RestoreRow& out) {
    if (line == nullptr) {
      return false;
    }
    while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) {
      --len;
    }
    const char* fields[CLIMATE_COLUMNS];
    size_t lens[CLIMATE_COLUMNS];
    size_t n = 0;
    size_t start = 0;
    for (size_t i = 0; i <= len; ++i) {
      if (i == len || line[i] == ',') {
        if (n == CLIMATE_COLUMNS) {
          return false;
        }
        fields[n] = line + start;
        lens[n] = i - start;
        ++n;
        start = i + 1;
      }
    }
    if (n != CLIMATE_COLUMNS) {
      return false;
    }
    // "YYYY-MM-DD hh:mm:ss"
    const char* t = fields[0];
    if (lens[0] != 19 || t[4] != '-' || t[7] != '-' || t[10] != ' ' || t[13] != ':' || t[16] != ':') {
      return false;
    }
    int v[6];
    static const size_t POS[6] = {0, 5, 8, 11, 14, 17};
    static const size_t WIDTH[6] = {4, 2, 2, 2, 2, 2};
    for (size_t k = 0; k < 6; ++k) {
      v[k] = 0;
      for (size_t d = 0; d < WIDTH[k]; ++d) {
        const char c = t[POS[k] + d];
        if (c < '0' || c > '9') {
          return false;
        }
        v[k] = v[k] * 10 + (c - '0');
      }
    }
    if (v[0] < 2000 || v[0] > 2099 || v[1] < 1 || v[1] > 12 ||
        !NtpTime::isValidDate(v[0], static_cast<uint8_t>(v[1]), static_cast<uint8_t>(v[2])) || v[3] > 23 ||
        v[4] > 59 || v[5] > 59) {
      return false;
    }
    ClimateHistory::RestoreRow row;
    row.minutes = NtpTime::daysFromCivil(v[0], static_cast<uint8_t>(v[1]), static_cast<uint8_t>(v[2])) * 1440 +
                  v[3] * 60 + v[4];
    row.usb = lens[3] == 1 && fields[3][0] == '1';
    if (lens[3] != 0 && !(lens[3] == 1 && (fields[3][0] == '0' || fields[3][0] == '1'))) {
      return false;
    }
    // Onboard climate in columns 4 and 5, then three columns for each slot: temperature, humidity, battery.
    for (size_t src = 0; src < LOG_SOURCE_COUNT; ++src) {
      const size_t col = src == 0 ? 4 : 6 + 3 * (src - 1);
      if (lens[col] != 0) {
        if (!parseTenths(fields[col], lens[col], -1000, 1000, row.temp[src])) {
          return false;
        }
        row.temp_mask = static_cast<uint16_t>(row.temp_mask | (1u << src));
      }
      if (lens[col + 1] != 0) {
        if (!parseTenths(fields[col + 1], lens[col + 1], 0, 1000, row.rh[src])) {
          return false;
        }
        row.rh_mask = static_cast<uint16_t>(row.rh_mask | (1u << src));
      }
    }
    out = row;
    return true;
  }

  // Walks the complete lines of a file tail and calls `visit(start, end)` for each (`end` is the newline position). `from_start` is
  // true when the buffer starts at the file start, else the first line is partial and skipped. A line without a newline at the
  // end is partial and skipped. One walker serves the history restore and the console command TAIL, so both apply the same rules.
  template <typename Visit>
  static void forEachTailLine(const char* buf, size_t len, bool from_start, Visit visit) {
    if (buf == nullptr) {
      return;
    }
    size_t pos = 0;
    if (!from_start) {
      while (pos < len && buf[pos] != '\n') {
        ++pos;
      }
      if (pos < len) {
        ++pos;
      }
    }
    while (pos < len) {
      size_t eol = pos;
      while (eol < len && buf[eol] != '\n') {
        ++eol;
      }
      if (eol == len) {
        break; // no newline: a partial line
      }
      visit(pos, eol);
      pos = eol + 1;
    }
  }

  // Parses the rows of a file tail (see forEachTailLine). A bad line (also the header) is skipped, so a gap of hours ends the
  // restore at the gap. Returns the rows added.
  static size_t parseClimateTail(const char* buf, size_t len, bool from_start, ClimateRowBuffer& out) {
    size_t added = 0;
    forEachTailLine(buf, len, from_start, [&](size_t start, size_t eol) {
      ClimateHistory::RestoreRow row;
      if (parseClimateRow(buf + start, eol - start, row)) {
        out.append(row);
        ++added;
      }
    });
    return added;
  }

  // ---- Console command TAIL: the newest rows of the climate files ----

  static constexpr size_t TAIL_MAX_ROWS = 72;     // largest N of "TAIL <N>"
  static constexpr size_t TAIL_BYTES = 16384;     // read limit from the end of each file

  // One valid row inside a tail buffer (the line without its line end).
  struct TailSpan {
    uint16_t start = 0;
    uint16_t len = 0;
  };

  // Finds the valid climate rows of a file tail (same line rules as parseClimateTail, through forEachTailLine) and keeps the
  // newest `capacity` of them in `spans`, oldest first. The span excludes the line end (LF, CR LF). Returns the count kept.
  static size_t collectTailRows(const char* buf, size_t len, bool from_start, TailSpan* spans, size_t capacity) {
    return collectRows(buf, len, from_start, spans, capacity, [](const char* line, size_t n) {
      ClimateHistory::RestoreRow row;
      return parseClimateRow(line, n, row);
    });
  }

  // The shared walker of collectTailRows and collectDailyTailRows: `valid(line, len)` decides if a line is a row.
  template <typename Valid>
  static size_t collectRows(const char* buf, size_t len, bool from_start, TailSpan* spans, size_t capacity, Valid valid) {
    if (spans == nullptr || capacity == 0 || len > 0xFFFF) {
      return 0;
    }
    size_t count = 0;
    forEachTailLine(buf, len, from_start, [&](size_t start, size_t eol) {
      size_t end = eol;
      while (end > start && buf[end - 1] == '\r') {
        --end;
      }
      if (!valid(buf + start, end - start)) {
        return;
      }
      if (count == capacity) {
        memmove(spans, spans + 1, (capacity - 1) * sizeof(spans[0]));
        --count;
      }
      spans[count].start = static_cast<uint16_t>(start);
      spans[count].len = static_cast<uint16_t>(end - start);
      ++count;
    });
    return count;
  }

  // ---- Console command DAYS: the newest rows of the daily files ----

  static constexpr size_t DAYS_MAX_ROWS = 31;  // largest N of "DAYS <N>"
  static constexpr size_t DAYS_BYTES = 16384;  // read limit from the end of each file

  // Finds the valid daily rows of a file tail (same line rules as collectTailRows, row check by parseDailyRow) and keeps the
  // newest `capacity` of them in `spans`, oldest first. The span excludes the line end. Returns the count kept.
  static size_t collectDailyTailRows(const char* buf, size_t len, bool from_start, TailSpan* spans, size_t capacity) {
    return collectRows(buf, len, from_start, spans, capacity, [](const char* line, size_t n) {
      WeekHistory::RestoreRow row;
      return parseDailyRow(line, n, row);
    });
  }

  // ---- Restore of the seven-day history from the daily rows ----

  static constexpr size_t DAILY_COLUMNS_V5 = 1 + 6 * LOG_SOURCE_COUNT + 2 + 3 + LOG_SOURCE_COUNT; // 83
  static constexpr size_t DAILY_COLUMNS_V6 = DAILY_COLUMNS_V5 + LOG_SOURCE_COUNT;                // 94
  static constexpr size_t DAILY_COLUMNS = DAILY_COLUMNS_V6 + LOG_SOURCE_COUNT;                   // 105
  static constexpr size_t DAILY_TAIL_BYTES = 4096; // the read limit of each file
  static constexpr size_t DAILY_ROW_CAPACITY = 14;

  // The newest rows of a daily file, oldest first. A full buffer drops the oldest row.
  struct DailyRowBuffer {
    WeekHistory::RestoreRow rows[DAILY_ROW_CAPACITY];
    size_t count = 0;

    void append(const WeekHistory::RestoreRow& row) {
      if (count == DAILY_ROW_CAPACITY) {
        memmove(rows, rows + 1, (DAILY_ROW_CAPACITY - 1) * sizeof(rows[0]));
        --count;
      }
      rows[count++] = row;
    }

    // Appends the rows of `newer` after the own rows.
    void appendAll(const DailyRowBuffer& newer) {
      for (size_t i = 0; i < newer.count; ++i) {
        append(newer.rows[i]);
      }
    }
  };

  // Parses one row of the daily format (105 columns, 94 columns of daily_v6 without the heat hours or 83 columns
  // of daily_v5 without the dry and heat hours, without the newline) for the restore. An empty field is a
  // missing value; a sensor needs both its minimum and its maximum for a pair. A wrong column count, a bad date
  // or a bad number rejects the row. The header line has a bad date, so it is rejected too. The dry hours of a
  // daily_v5 row and the heat hours of a daily_v5 or daily_v6 row stay gaps.
  static bool parseDailyRow(const char* line, size_t len, WeekHistory::RestoreRow& out) {
    if (line == nullptr) {
      return false;
    }
    while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) {
      --len;
    }
    const char* fields[DAILY_COLUMNS];
    size_t lens[DAILY_COLUMNS];
    size_t n = 0;
    size_t start = 0;
    for (size_t i = 0; i <= len; ++i) {
      if (i == len || line[i] == ',') {
        if (n == DAILY_COLUMNS) {
          return false;
        }
        fields[n] = line + start;
        lens[n] = i - start;
        ++n;
        start = i + 1;
      }
    }
    if (n != DAILY_COLUMNS && n != DAILY_COLUMNS_V6 && n != DAILY_COLUMNS_V5) {
      return false;
    }
    // "YYYY-MM-DD"
    const char* t = fields[0];
    if (lens[0] != 10 || t[4] != '-' || t[7] != '-') {
      return false;
    }
    int v[3];
    static const size_t POS[3] = {0, 5, 8};
    static const size_t WIDTH[3] = {4, 2, 2};
    for (size_t k = 0; k < 3; ++k) {
      v[k] = 0;
      for (size_t d = 0; d < WIDTH[k]; ++d) {
        const char c = t[POS[k] + d];
        if (c < '0' || c > '9') {
          return false;
        }
        v[k] = v[k] * 10 + (c - '0');
      }
    }
    if (v[0] < 2000 || v[0] > 2099 || !NtpTime::isValidDate(v[0], static_cast<uint8_t>(v[1]), static_cast<uint8_t>(v[2]))) {
      return false;
    }
    WeekHistory::RestoreRow row;
    row.day = NtpTime::daysFromCivil(v[0], static_cast<uint8_t>(v[1]), static_cast<uint8_t>(v[2]));
    // Six columns for each sensor: temperature min, max, mean, humidity min, max, mean.
    for (size_t s = 0; s < LOG_SOURCE_COUNT; ++s) {
      const size_t col = 1 + 6 * s;
      int16_t lo = 0, hi = 0;
      const bool t_lo = lens[col] != 0, t_hi = lens[col + 1] != 0;
      if (t_lo != t_hi) {
        return false;
      }
      if (t_lo) {
        if (!parseTenths(fields[col], lens[col], -1000, 1000, lo) ||
            !parseTenths(fields[col + 1], lens[col + 1], -1000, 1000, hi)) {
          return false;
        }
        row.temp_min[s] = lo;
        row.temp_max[s] = hi;
        row.temp_mask = static_cast<uint16_t>(row.temp_mask | (1u << s));
      }
      const bool h_lo = lens[col + 3] != 0, h_hi = lens[col + 4] != 0;
      if (h_lo != h_hi) {
        return false;
      }
      if (h_lo) {
        if (!parseTenths(fields[col + 3], lens[col + 3], 0, 1000, lo) ||
            !parseTenths(fields[col + 4], lens[col + 4], 0, 1000, hi)) {
          return false;
        }
        row.rh_min[s] = lo;
        row.rh_max[s] = hi;
        row.rh_mask = static_cast<uint16_t>(row.rh_mask | (1u << s));
      }
    }
    // The mold_h_<N> columns follow the five noise columns, the dry_h_<N> columns (daily_v6) and the heat_h_<N>
    // columns (daily_v7) follow them; an empty field is a gap.
    const size_t mold_col = 1 + 6 * LOG_SOURCE_COUNT + 5;
    for (size_t s = 0; s < LOG_SOURCE_COUNT; ++s) {
      if (!parseHours(fields[mold_col + s], lens[mold_col + s], WeekHistory::MAX_MOLD_HOURS, row.mold_h[s])) {
        return false;
      }
      if (n >= DAILY_COLUMNS_V6 &&
          !parseHours(fields[DAILY_COLUMNS_V5 + s], lens[DAILY_COLUMNS_V5 + s], WeekHistory::MAX_DRY_HOURS, row.dry_h[s])) {
        return false;
      }
      if (n == DAILY_COLUMNS &&
          !parseHours(fields[DAILY_COLUMNS_V6 + s], lens[DAILY_COLUMNS_V6 + s], WeekHistory::MAX_HEAT_HOURS, row.heat_h[s])) {
        return false;
      }
    }
    out = row;
    return true;
  }

  // Parses the rows of a daily file tail like parseClimateTail: a partial first line (unless `from_start`),
  // a line without a newline and a bad line are skipped. Returns the rows added.
  static size_t parseDailyTail(const char* buf, size_t len, bool from_start, DailyRowBuffer& out) {
    if (buf == nullptr) {
      return 0;
    }
    size_t added = 0;
    size_t pos = 0;
    if (!from_start) {
      while (pos < len && buf[pos] != '\n') {
        ++pos;
      }
      if (pos < len) {
        ++pos;
      }
    }
    while (pos < len) {
      size_t eol = pos;
      while (eol < len && buf[eol] != '\n') {
        ++eol;
      }
      if (eol == len) {
        break;
      }
      WeekHistory::RestoreRow row;
      if (parseDailyRow(buf + pos, eol - pos, row)) {
        out.append(row);
        ++added;
      }
      pos = eol + 1;
    }
    return added;
  }

  // The month before `now`, for the file of the previous month. False for an invalid time.
  static bool previousMonth(const RtcTime& now, RtcTime& out) {
    if (!now.valid || now.month < 1 || now.month > 12 || now.year < 2001) {
      return false;
    }
    out = now;
    if (now.month == 1) {
      out.month = 12;
      out.year = static_cast<uint16_t>(now.year - 1);
    } else {
      out.month = static_cast<uint8_t>(now.month - 1);
    }
    return true;
  }

private:
  // Parses an hour count field of the daily file: empty keeps `out` (a gap), else 0 to `max_hours` in at most
  // two digits. False for a bad character or a count above `max_hours`.
  static bool parseHours(const char* field, size_t len, uint8_t max_hours, uint8_t& out) {
    if (len == 0) {
      return true;
    }
    uint32_t hours = 0;
    for (size_t d = 0; d < len; ++d) {
      if (field[d] < '0' || field[d] > '9' || d >= 2) {
        return false;
      }
      hours = hours * 10 + static_cast<uint32_t>(field[d] - '0');
    }
    if (hours > max_hours) {
      return false;
    }
    out = static_cast<uint8_t>(hours);
    return true;
  }

  // Appends ",<value in tenths as x.y>" or "," for a missing value.
  static size_t appendTenths(char* out, size_t capacity, size_t pos, bool present, int16_t tenths) {
    if (!present) {
      return pos + static_cast<size_t>(snprintf(out + pos, capacity - pos, ","));
    }
    const int v = tenths < 0 ? -tenths : tenths;
    return pos + static_cast<size_t>(snprintf(out + pos, capacity - pos, ",%s%d.%d", tenths < 0 ? "-" : "",
                                              v / 10, v % 10));
  }
};

enum class SdLogResult : uint8_t {
  OK = 0,
  NO_TIME,      // RTC time invalid: no file name
  MOUNT_FAILED, // the card interface did not start
  OPEN_FAILED,
  WRITE_FAILED,
  NO_CARD,      // no card, or a card that does not mount
};

// Decides what the hourly write does with the event ring after a write result.
class EventFlush {
public:
  // Clears the ring after OK only. Each other result (also NO_TIME) keeps the ring for the next write.
  // Returns true when the ring was cleared. An empty ring needs no write, so it counts as cleared.
  static bool apply(EventRing& ring, SdLogResult result) {
    if (result != SdLogResult::OK) {
      return false;
    }
    ring.clear();
    return true;
  }
};

enum class SdConfigResult : uint8_t {
  LOADED = 0,  // the file was parsed
  NO_FILE,     // card mounted, but no usable file
  NO_CARD,     // no card, or a card that does not mount
  MOUNT_FAILED, // the card interface did not start
};

// Last known state of the MicroSD card. It lives in the RTC memory (PersistentState).
enum class SdStatus : uint8_t {
  UNKNOWN = 0, // no card access since power-on
  OK,
  NO_CARD,
  MOUNT_FAILED,
  WRITE_FAILED,
  FULL, // a failed write with 0 MB free space
};

class SdStatusCodec {
public:
  static constexpr uint8_t MAX_VALUE = static_cast<uint8_t>(SdStatus::FULL);

  // Status after an hourly write. NO_TIME is no card result: the stored status stays.
  // `free_mb` is the free space read after the write, rounded down to whole MB; 0 with a failed write
  // means a full card (less than 1 MB free counts as full).
  static SdStatus fromLog(SdLogResult result, int32_t free_mb, SdStatus previous) {
    switch (result) {
      case SdLogResult::OK: return SdStatus::OK;
      case SdLogResult::NO_CARD: return SdStatus::NO_CARD;
      case SdLogResult::MOUNT_FAILED: return SdStatus::MOUNT_FAILED;
      case SdLogResult::OPEN_FAILED:
      case SdLogResult::WRITE_FAILED: return free_mb == 0 ? SdStatus::FULL : SdStatus::WRITE_FAILED;
      case SdLogResult::NO_TIME: break;
    }
    return previous;
  }

  // Status after a config load: a mounted card is OK, with or without a file. A read-only mount
  // proves no write, so a stored write fault stays until the next hourly write clears it.
  static SdStatus fromConfig(SdConfigResult result, SdStatus previous) {
    switch (result) {
      case SdConfigResult::LOADED:
      case SdConfigResult::NO_FILE:
        return (previous == SdStatus::WRITE_FAILED || previous == SdStatus::FULL) ? previous : SdStatus::OK;
      case SdConfigResult::NO_CARD: return SdStatus::NO_CARD;
      case SdConfigResult::MOUNT_FAILED: return SdStatus::MOUNT_FAILED;
    }
    return SdStatus::UNKNOWN;
  }

  // Detail of the SD_FAULT event for a status change, or -1 for no event. A change to a fault from a
  // different status records the fault; a change from a fault back to OK records OK (detail 1). A
  // first OK after UNKNOWN is no recovery, so it records nothing; the same status in a row neither.
  static int32_t faultEventDetail(SdStatus previous, SdStatus next) {
    if (previous == next || next == SdStatus::UNKNOWN) {
      return -1;
    }
    if (next == SdStatus::OK) {
      return (previous == SdStatus::UNKNOWN) ? -1 : static_cast<int32_t>(SdStatus::OK);
    }
    return static_cast<int32_t>(next);
  }

  // Name for the serial console.
  static const char* name(SdStatus status) {
    switch (status) {
      case SdStatus::UNKNOWN: return "UNKNOWN";
      case SdStatus::OK: return "OK";
      case SdStatus::NO_CARD: return "NO_CARD";
      case SdStatus::MOUNT_FAILED: return "MOUNT_FAILED";
      case SdStatus::WRITE_FAILED: return "WRITE_FAILED";
      case SdStatus::FULL: return "FULL";
    }
    return "UNKNOWN";
  }

  // Text of the diagnostics view for a fault, or nullptr while the free space shows (UNKNOWN, OK).
  static const char* faultText(SdStatus status) {
    switch (status) {
      case SdStatus::NO_CARD: return "SD: NO CARD";
      case SdStatus::MOUNT_FAILED: return "SD: MOUNT ERR";
      case SdStatus::WRITE_FAILED: return "SD: WRITE ERR";
      case SdStatus::FULL: return "SD: FULL";
      case SdStatus::UNKNOWN:
      case SdStatus::OK: break;
    }
    return nullptr;
  }
};

#if defined(ARDUINO) || defined(ESP_PLATFORM)

// Runtime driver. Each call mounts the card, appends one record, and unmounts again, so a
// removed card needs no cleanup and the card never stays powered up longer than needed.
class SdLogger {
public:
  static SdLogResult append(
      const RtcTime& time,
      const BatteryReading& battery,
      const Shtc3Reading& onboard,
      const SwitchBotReading* slots,
      size_t slot_count,
      uint32_t now_ms,
      const NoiseReading* noise,
      const MicCalibration* noise_cal,
      uint8_t retention_months = 0,
      int32_t* free_mb = nullptr,
      uint32_t stale_timeout_ms = SENSOR_STALE_TIMEOUT_MS) {
    char path[SdLogCodec::MAX_PATH_SIZE];
    if (free_mb != nullptr) {
      *free_mb = -1;
    }
    char row[SdLogCodec::MAX_ROW_SIZE];
    if (SdLogCodec::formatFilename(&time, path, sizeof(path)) == 0) {
      return SdLogResult::NO_TIME;
    }
    const size_t row_len =
        SdLogCodec::formatRow(&time, &battery, &onboard, slots, slot_count, now_ms, noise, noise_cal, row, sizeof(row),
                            stale_timeout_ms);
    if (row_len == 0) {
      return SdLogResult::NO_TIME;
    }
    const SdStatus mounted = tryMount();
    if (mounted != SdStatus::OK) {
      return mounted == SdStatus::NO_CARD ? SdLogResult::NO_CARD : SdLogResult::MOUNT_FAILED;
    }
    const SdLogResult result = writeRecord(path, row, row_len);
    // The prune runs also after a failed write, so a full card frees space for the next retry.
    pruneOldLogs(time, retention_months);
    if (free_mb != nullptr) {
      *free_mb = SdLogCodec::freeMegabytes(SD_MMC.totalBytes(), SD_MMC.usedBytes());
    }
    SD_MMC.end();
    return result;
  }

  // Appends the daily row of the day before `now`, with the header for a new file, and returns the
  // result like append(). The row comes from the 24 h histories. The file is named after the year
  // of the summarized day, so the row of 31 December goes to the file of that year.
  static SdLogResult appendDaily(
      const RtcTime& now, const ClimateHistory& history, const NoiseHistory& noise,
      const DailyNoiseStats& stats, uint8_t mold_limit_rh, uint8_t dry_limit_rh, uint8_t heat_limit_c) {
    uint16_t year = 0;
    uint8_t month = 0, day = 0;
    DailyPlanner::previousDay(now, year, month, day);
    char path[SdLogCodec::MAX_PATH_SIZE];
    char row[SdLogCodec::MAX_ROW_SIZE];
    if (SdLogCodec::formatDailyFilename(year, path, sizeof(path)) == 0) {
      return SdLogResult::NO_TIME;
    }
    const size_t row_len = SdLogCodec::formatDailyRow(year, month, day, history, noise, stats, row, sizeof(row), mold_limit_rh,
                                                     dry_limit_rh, heat_limit_c);
    if (row_len == 0) {
      return SdLogResult::NO_TIME;
    }
    const SdStatus mounted = tryMount();
    if (mounted != SdStatus::OK) {
      return mounted == SdStatus::NO_CARD ? SdLogResult::NO_CARD : SdLogResult::MOUNT_FAILED;
    }
    const SdLogResult result = writeRecord(path, row, row_len, true);
    SD_MMC.end();
    return result;
  }

  // Appends the events of `ring` to "/sdcard/events_v1_YYYY.csv" (year of `now`), with the header for
  // a new file, then reports like append(). A ring with lost events starts with the EVENTS_DROPPED row.
  // The caller clears the ring only after OK (EventFlush::apply). An empty ring writes nothing and gives OK.
  // A short write to an existing file can leave part of the batch: the File class cannot truncate, so the
  // retry may repeat rows. A new file is removed instead.
  static SdLogResult appendEvents(const RtcTime& now, const EventRing& ring) {
    if (ring.size() == 0) {
      return SdLogResult::OK;
    }
    char path[SdLogCodec::MAX_PATH_SIZE];
    if (!now.valid || SdLogCodec::formatEventsFilename(now.year, path, sizeof(path)) == 0) {
      return SdLogResult::NO_TIME;
    }
    const SdStatus mounted = tryMount();
    if (mounted != SdStatus::OK) {
      return mounted == SdStatus::NO_CARD ? SdLogResult::NO_CARD : SdLogResult::MOUNT_FAILED;
    }
    const char* fs_path = path + strlen(SdLogCodec::MOUNT_POINT);
    const bool is_new = !SD_MMC.exists(fs_path);
    File file = SD_MMC.open(fs_path, FILE_APPEND);
    SdLogResult result = SdLogResult::OPEN_FAILED;
    if (file) {
      // One buffer for the whole batch: a short write keeps the ring, so the retry repeats it.
      static char buf[SdLogCodec::MAX_EVENT_ROW_SIZE * (EventRing::CAPACITY + 2)];
      size_t len = 0;
      if (is_new || file.size() == 0) {
        len += SdLogCodec::formatEventsHeader(buf, sizeof(buf));
      }
      len += SdLogCodec::formatEventsDroppedRow(now, ring.dropped(), buf + len, sizeof(buf) - len);
      for (size_t i = 0; i < ring.size(); ++i) {
        len += SdLogCodec::formatEventRow(ring.at(i), buf + len, sizeof(buf) - len);
      }
      const bool ok = len > 0 && file.write(reinterpret_cast<const uint8_t*>(buf), len) == len;
      file.close();
      if (!ok && is_new) {
        SD_MMC.remove(fs_path); // never leave a partial header: the next attempt writes a new one
      }
      result = ok ? SdLogResult::OK : SdLogResult::WRITE_FAILED;
    }
    SD_MMC.end();
    return result;
  }

  // True when a card mounts. The wake cycle checks this before the noise window, so a missing card
  // does not cost a 5 s microphone window on every 10 min retry.
  static bool cardPresent() {
    if (!mount()) {
      return false;
    }
    SD_MMC.end();
    return true;
  }

  // Reads the optional /sdcard/config.ini into `out`. NO_CARD (mount failed) lets the caller keep
  // its previous settings, so a transient card error never resets them. NO_FILE (card without a
  // usable file, or a file above the size limit) leaves the defaults in `out`.
  // `free_mb` (optional) receives the free space of the mounted card in MB, or -1.
  // `rejects` (optional) receives the rejected lines of the file; empty without a usable file.
  static SdConfigResult loadConfig(DeviceConfig& out, int32_t* free_mb = nullptr, ConfigRejects* rejects = nullptr) {
    out = DeviceConfig{};
    if (rejects != nullptr) {
      *rejects = ConfigRejects{};
    }
    if (free_mb != nullptr) {
      *free_mb = -1;
    }
    const SdStatus mounted = tryMount();
    if (mounted != SdStatus::OK) {
      return mounted == SdStatus::NO_CARD ? SdConfigResult::NO_CARD : SdConfigResult::MOUNT_FAILED;
    }
    // Static: the 8 KB buffer would use the whole 8 KB loop task stack. setup() runs once per
    // wake cycle, so one shared buffer is safe.
    static char buf[DeviceConfigParser::MAX_FILE_SIZE + 1];
    SdConfigResult result = SdConfigResult::NO_FILE;
    File file = SD_MMC.open("/config.ini", FILE_READ);
    if (file && !file.isDirectory()) {
      // One byte above the limit shows an oversized file; the parser then rejects it whole.
      const size_t n = file.read(reinterpret_cast<uint8_t*>(buf), sizeof(buf));
      const size_t expected = file.size() < sizeof(buf) ? static_cast<size_t>(file.size()) : sizeof(buf);
      if (n != expected) {
        result = SdConfigResult::MOUNT_FAILED; // a short read is a card error: keep the old settings
      } else {
        const ConfigParseResult parsed = DeviceConfigParser::parse(buf, n, out);
        if (parsed.file_ok) {
          result = SdConfigResult::LOADED;
          if (rejects != nullptr) {
            *rejects = parsed.rejects();
          }
        }
      }
    }
    if (file) {
      file.close();
    }
    if (free_mb != nullptr) {
      *free_mb = SdLogCodec::freeMegabytes(SD_MMC.totalBytes(), SD_MMC.usedBytes());
    }
    SD_MMC.end();
    return result;
  }

  // Restores the 24-hour histories from the hourly rows of the card after a power-on (see
  // ClimateHistory::restore). Reads the last RESTORE_TAIL_BYTES of the current month file and, with fewer
  // than 24 rows, of the previous month file. `fixed_slots` has bit k set for a registry slot k + 1 with a
  // fixed MAC. A missing card or file, a read failure or a bad row changes nothing. Returns the restored rows.
  static size_t restoreHistories(
      ClimateHistory& history, const RtcTime& now, uint32_t now_ms, uint16_t fixed_slots) {
    char path[SdLogCodec::MAX_PATH_SIZE];
    if (!now.valid || SdLogCodec::formatFilename(&now, path, sizeof(path)) == 0) {
      return 0;
    }
    if (tryMount() != SdStatus::OK) {
      return 0;
    }
    // Static: the buffers would use the whole loop task stack. setup() runs once per wake cycle.
    static char buf[SdLogCodec::RESTORE_TAIL_BYTES];
    static SdLogCodec::ClimateRowBuffer rows;
    rows = SdLogCodec::ClimateRowBuffer{};
    readTail(path, buf, sizeof(buf), rows);
    if (rows.count < 24) {
      RtcTime before;
      static SdLogCodec::ClimateRowBuffer older;
      older = SdLogCodec::ClimateRowBuffer{};
      if (SdLogCodec::previousMonth(now, before) && SdLogCodec::formatFilename(&before, path, sizeof(path)) != 0 &&
          readTail(path, buf, sizeof(buf), older) > 0) {
        rows.prepend(older);
      }
    }
    SD_MMC.end();
    return history.restore(rows.rows, rows.count, now, now_ms, fixed_slots);
  }

  // Restores the seven-day history from the daily rows of the card after a power-on (see
  // WeekHistory::restore). Reads the last DAILY_TAIL_BYTES of the daily_v5, the daily_v6 and the daily_v7 file of
  // the current year (the newer rows come last, so the old files serve as fallback and cover a change of the
  // format within the year) and, in the first week of January, of the previous year. A missing card or file,
  // a read failure or a bad row changes nothing. Returns the days stored.
  static size_t restoreWeek(WeekHistory& week, const RtcTime& now) {
    char path[SdLogCodec::MAX_PATH_SIZE];
    if (!now.valid || now.oscillator_stopped || now.year < WeekHistory::MIN_TRUSTED_YEAR ||
        SdLogCodec::formatDailyFilename(now.year, path, sizeof(path)) == 0) {
      return 0;
    }
    if (tryMount() != SdStatus::OK) {
      return 0;
    }
    // Static: the buffers would use the whole loop task stack. setup() runs once per wake cycle.
    static char buf[SdLogCodec::DAILY_TAIL_BYTES];
    static SdLogCodec::DailyRowBuffer rows;
    rows = SdLogCodec::DailyRowBuffer{};
    readYearRows(now.year, path, buf, sizeof(buf), rows);
    if (now.month == 1 && now.day <= 7) {
      static SdLogCodec::DailyRowBuffer older;
      older = SdLogCodec::DailyRowBuffer{};
      if (readYearRows(static_cast<uint16_t>(now.year - 1), path, buf, sizeof(buf), older) > 0) {
        older.appendAll(rows);
        rows = older;
      }
    }
    SD_MMC.end();
    return week.restore(rows.rows, rows.count, now);
  }

  // Mounts the card for a console command; the caller unmounts with SD_MMC.end().
  static bool mountCard() {
    return mount();
  }

  // The last `capacity` bytes of /<name> for the console command TAIL (see readFileTail). The card must be mounted.
  static int readTailBytes(const char* name, char* buf, size_t capacity, size_t& len, bool& whole) {
    char path[SdLogCodec::MAX_PATH_SIZE];
    snprintf(path, sizeof(path), "/%s", name);
    return readFileTail(path, buf, capacity, len, whole);
  }

private:
  // Reads the last `capacity` bytes of the file at `sd_path` (for example "/climate_v6_202611.csv", without the mount point) into
  // `buf`. Returns 0 for a read, 1 for a missing file or a directory and 2 for a read error. `len` is the byte count read;
  // `whole` is true when the bytes start at the file start. Mounts nothing: the caller holds the card.
  static int readFileTail(const char* sd_path, char* buf, size_t capacity, size_t& len, bool& whole) {
    len = 0;
    whole = false;
    File file = SD_MMC.open(sd_path, FILE_READ);
    if (!file || file.isDirectory()) {
      return 1;
    }
    const size_t size = file.size();
    const size_t want = size < capacity ? size : capacity;
    int result = 0;
    if (want == 0) {
      whole = true;
    } else if (!file.seek(size - want) || file.read(reinterpret_cast<uint8_t*>(buf), want) != want) {
      result = 2;
    } else {
      len = want;
      whole = want == size;
    }
    file.close();
    return result;
  }

  // Reads the last `capacity` bytes of one file and parses its rows. A missing file or a short read gives 0.
  static size_t readTail(const char* path, char* buf, size_t capacity, SdLogCodec::ClimateRowBuffer& out) {
    size_t n = 0;
    bool whole = false;
    if (readFileTail(path + strlen(SdLogCodec::MOUNT_POINT), buf, capacity, n, whole) != 0) {
      return 0;
    }
    return SdLogCodec::parseClimateTail(buf, n, whole, out);
  }

  // Reads the rows of the daily_v5, the daily_v6 and then the daily_v7 file of `year` into `out`, oldest first. Returns the rows added.
  static size_t readYearRows(uint16_t year, char* path, char* buf, size_t capacity, SdLogCodec::DailyRowBuffer& out) {
    size_t added = 0;
    if (SdLogCodec::formatDailyFilenameV5(year, path, SdLogCodec::MAX_PATH_SIZE) != 0) {
      added += readDailyTail(path, buf, capacity, out);
    }
    if (SdLogCodec::formatDailyFilenameV6(year, path, SdLogCodec::MAX_PATH_SIZE) != 0) {
      added += readDailyTail(path, buf, capacity, out);
    }
    if (SdLogCodec::formatDailyFilename(year, path, SdLogCodec::MAX_PATH_SIZE) != 0) {
      added += readDailyTail(path, buf, capacity, out);
    }
    return added;
  }

  // Reads the last `capacity` bytes of one daily file and parses its rows. A missing file or a short read gives 0.
  static size_t readDailyTail(const char* path, char* buf, size_t capacity, SdLogCodec::DailyRowBuffer& out) {
    File file = SD_MMC.open(path + strlen(SdLogCodec::MOUNT_POINT), FILE_READ);
    if (!file || file.isDirectory()) {
      return 0;
    }
    const size_t size = file.size();
    const size_t want = size < capacity ? size : capacity;
    size_t added = 0;
    if (want > 0 && file.seek(size - want)) {
      const size_t n = file.read(reinterpret_cast<uint8_t*>(buf), want);
      if (n == want) {
        added = SdLogCodec::parseDailyTail(buf, n, want == size, out);
      }
    }
    file.close();
    return added;
  }

  // Pins first, then begin(): required by ESP32 core 3.3.12. 1-bit mode.
  static bool mount() {
    return tryMount() == SdStatus::OK;
  }

  // OK, NO_CARD (no card answers) or MOUNT_FAILED (the pins did not apply).
  static SdStatus tryMount() {
    if (!SD_MMC.setPins(PIN_SDMMC_CLK, PIN_SDMMC_CMD, PIN_SDMMC_D0)) {
      return SdStatus::MOUNT_FAILED;
    }
    if (!SD_MMC.begin(SdLogCodec::MOUNT_POINT, true)) {
      SD_MMC.end();
      return SdStatus::NO_CARD;
    }
    if (SD_MMC.cardType() == CARD_NONE) {
      SD_MMC.end();
      return SdStatus::NO_CARD;
    }
    return SdStatus::OK;
  }

  // Deletes the hourly and daily log files older than the retention period in the root directory. The names are
  // collected first and removed after the directory closes. Other files and directories stay.
  static void pruneOldLogs(const RtcTime& now, uint8_t retention_months) {
    if (!SdLogCodec::retentionActive(retention_months)) {
      return;
    }
    char names[SdLogCodec::MAX_PRUNE_PER_PASS][SdLogCodec::LOG_NAME_SIZE];
    size_t count = 0;
    File dir = SD_MMC.open("/");
    if (!dir) {
      return;
    }
    if (dir.isDirectory()) {
      for (File entry = dir.openNextFile(); entry && count < SdLogCodec::MAX_PRUNE_PER_PASS;
           entry = dir.openNextFile()) {
        const char* name = entry.name();
        if (!entry.isDirectory() && name != nullptr && SdLogCodec::shouldDeleteFile(name, now, retention_months)) {
          memcpy(names[count], name, strlen(name) + 1); // the match proves a length of 17 or 21
          ++count;
        }
        entry.close();
      }
    }
    dir.close();
    for (size_t i = 0; i < count; ++i) {
      char path[SdLogCodec::LOG_NAME_SIZE + 1];
      snprintf(path, sizeof(path), "/%s", names[i]);
      SD_MMC.remove(path);
    }
  }

  static SdLogResult writeRecord(const char* path, const char* row, size_t row_len, bool daily = false) {
    const char* fs_path = path + strlen(SdLogCodec::MOUNT_POINT); // SD_MMC prefixes the mount
    const bool is_new = !SD_MMC.exists(fs_path);
    File file = SD_MMC.open(fs_path, FILE_APPEND);
    if (!file) {
      return SdLogResult::OPEN_FAILED;
    }
    bool ok = true;
    if (is_new || file.size() == 0) {
      char header[SdLogCodec::DAILY_HEADER_SIZE];
      const size_t header_len = daily ? SdLogCodec::formatDailyHeader(header, sizeof(header))
                                      : SdLogCodec::formatHeader(header, sizeof(header));
      ok = header_len > 0 && file.write(reinterpret_cast<const uint8_t*>(header), header_len) == header_len;
    }
    ok = ok && file.write(reinterpret_cast<const uint8_t*>(row), row_len) == row_len;
    file.close();
    if (!ok && is_new) {
      SD_MMC.remove(fs_path); // never leave a partial header: the next attempt writes a new one
    }
    return ok ? SdLogResult::OK : SdLogResult::WRITE_FAILED;
  }
};

#endif

} // namespace rlcd_panel
