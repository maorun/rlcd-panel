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

// Local day of the last handled daily summary. It lives in the RTC memory (PersistentState).
struct DailyLogState {
  bool has_date = false;
  uint16_t year = 2000;
  uint8_t month = 1;
  uint8_t day = 1;

  // Stamps `today` as handled.
  void markDone(const RtcTime& today) {
    has_date = true;
    year = today.year;
    month = today.month;
    day = today.day;
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

// Decoupled CSV codec. It has no Arduino or SD_MMC dependency, so the host tests build it.
class SdLogCodec {
public:
  static constexpr uint32_t LOG_INTERVAL_MS = 3600000; // one record per hour
  static constexpr uint32_t RETRY_INTERVAL_MS = 600000; // after a failed write, retry in 10 min
  static constexpr size_t MAX_PATH_SIZE = 32;
  static constexpr size_t MAX_ROW_SIZE = 256;
  // Header line of the daily summary file, with the terminator.
  static constexpr size_t DAILY_HEADER_SIZE = 512;
  static constexpr const char* MOUNT_POINT = "/sdcard";
  // "climate_v4_YYYYMM.csv" plus the terminator.
  static constexpr size_t LOG_NAME_SIZE = 22;
  // Upper bound of the shown free space (about 9.5 TB), so the diagnostics line has a fixed width.
  static constexpr uint32_t MAX_FREE_MB = 9999999;
  // Earliest RTC year that the retention accepts. A wrong clock in the future must not delete logs.
  static constexpr uint16_t MIN_TRUSTED_YEAR = 2025;
  // Files that one prune pass deletes at most. More old files go in the next hourly pass.
  static constexpr size_t MAX_PRUNE_PER_PASS = 24;

  // Splits a bare log file name "climate_v4_YYYYMM.csv" (or the old v3 and v2 names) into year 2000..2099 and month 01..12.
  // Anything else is false, so a path separator or "." never reaches the card.
  static bool parseLogFileName(const char* name, int& year, int& month) {
    static const char PREFIX[] = "climate_v4_";
    static const char PREFIX_V3[] = "climate_v3_"; // old 19-column files: listed and pruned, never written
    static const char PREFIX_V2[] = "climate_v2_"; // old 18-column files: listed and pruned, never written
    static const char SUFFIX[] = ".csv";
    constexpr size_t PREFIX_LEN = sizeof(PREFIX) - 1;
    constexpr size_t SUFFIX_LEN = sizeof(SUFFIX) - 1;
    if (name == nullptr || strlen(name) != PREFIX_LEN + 6 + SUFFIX_LEN ||
        (memcmp(name, PREFIX, PREFIX_LEN) != 0 && memcmp(name, PREFIX_V3, PREFIX_LEN) != 0 &&
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

  // True for a bare log file name "climate_v4_YYYYMM.csv", "climate_v3_YYYYMM.csv" or "climate_v2_YYYYMM.csv" with year 2000..2099 and month 01..12.
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

  // ---- Daily summary file "daily_v1_YYYY.csv" ----

  // Splits a bare daily file name "daily_v1_YYYY.csv" into the year 2000..2099.
  static bool parseDailyFileName(const char* name, int& year) {
    static const char PREFIX[] = "daily_v1_";
    static const char SUFFIX[] = ".csv";
    constexpr size_t PREFIX_LEN = sizeof(PREFIX) - 1;
    constexpr size_t SUFFIX_LEN = sizeof(SUFFIX) - 1;
    if (name == nullptr || strlen(name) != PREFIX_LEN + 4 + SUFFIX_LEN ||
        memcmp(name, PREFIX, PREFIX_LEN) != 0 || memcmp(name + PREFIX_LEN + 4, SUFFIX, SUFFIX_LEN) != 0) {
      return false;
    }
    int y = 0;
    for (size_t i = 0; i < 4; ++i) {
      if (name[PREFIX_LEN + i] < '0' || name[PREFIX_LEN + i] > '9') {
        return false;
      }
      y = y * 10 + (name[PREFIX_LEN + i] - '0');
    }
    if (y < 2000 || y > 2099) {
      return false;
    }
    year = y;
    return true;
  }

  static bool isDailyFileName(const char* name) {
    int year = 0;
    return parseDailyFileName(name, year);
  }

  // True for each file that the serial console lists and dumps: hourly and daily files.
  static bool isConsoleFileName(const char* name) {
    return isLogFileName(name) || isDailyFileName(name);
  }

  // True when `name` is an hourly or daily file that the retention deletes. A daily file counts as
  // December of its year, so it stays until the whole year is older than the retention.
  static bool shouldDeleteFile(const char* name, const RtcTime& now, uint8_t retention_months) {
    int year = 0;
    if (!parseDailyFileName(name, year)) {
      return shouldDeleteLog(name, now, retention_months);
    }
    if (!retentionActive(retention_months) || !now.valid || now.oscillator_stopped ||
        now.year < MIN_TRUSTED_YEAR) {
      return false;
    }
    return monthsBetween(year, 12, static_cast<int>(now.year), static_cast<int>(now.month)) >
           static_cast<int>(retention_months);
  }

  // Writes "/sdcard/daily_v1_YYYY.csv". Returns the length, or 0 for a null or small buffer or a
  // year outside 2000..2099.
  static size_t formatDailyFilename(uint16_t year, char* out, size_t capacity) {
    if (out == nullptr || capacity == 0) {
      return 0;
    }
    out[0] = '\0';
    if (year < 2000 || year > 2099) {
      return 0;
    }
    const int n = snprintf(out, capacity, "%s/daily_v1_%04u.csv", MOUNT_POINT, static_cast<unsigned>(year));
    if (n <= 0 || static_cast<size_t>(n) >= capacity) {
      out[0] = '\0';
      return 0;
    }
    return static_cast<size_t>(n);
  }

  // Header line of the daily file: the date, six columns for each of the four sensors, and the
  // noise range.
  static size_t formatDailyHeader(char* out, size_t capacity) {
    static const char* const SENSORS[] = {"onboard", "s1", "s2", "s3"};
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
        n = snprintf(out + pos, capacity - pos, ",noise_min_db,noise_max_db\n");
        pos += n > 0 ? static_cast<size_t>(n) : capacity;
      }
    }
    if (pos >= capacity) {
      out[0] = '\0';
      return 0;
    }
    return pos;
  }

  // Writes one daily row, including the newline: the date, then minimum, maximum and mean of the
  // temperature and of the humidity for the onboard sensor and the three slots, then the minimum and
  // maximum noise level. A sensor without samples gives empty fields, so every row has 27 columns.
  // Returns the length, or 0 for a null or small buffer or an invalid date.
  static size_t formatDailyRow(
      uint16_t year, uint8_t month, uint8_t day, const ClimateHistory& history, const NoiseHistory& noise,
      char* out, size_t capacity) {
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
    for (size_t i = 0; i < ClimateHistory::SLOT_COUNT; ++i) {
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
    pos += static_cast<size_t>(snprintf(out + pos, capacity - pos, "\n"));
    if (pos >= capacity) {
      out[0] = '\0';
      return 0;
    }
    return pos;
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
        "noise_rms,noise_db,noise_band,noise_events,noise_l10,noise_l90\n";
    const size_t len = sizeof(HEADER) - 1;
    if (out == nullptr || capacity < len + 1) {
      return 0;
    }
    memcpy(out, HEADER, len + 1);
    return len;
  }

  // Writes "/sdcard/climate_v4_YYYYMM.csv". The v4 name keeps the 21-column format apart from
  // the old v3 (19 columns) and v2 (18 columns) files, which stay unchanged. Returns the length, or 0 for a null buffer, a small
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
        out, capacity, "%s/climate_v4_%04u%02u.csv", MOUNT_POINT,
        static_cast<unsigned>(time->year), static_cast<unsigned>(time->month));
    if (n <= 0 || static_cast<size_t>(n) >= capacity) {
      out[0] = '\0';
      return 0;
    }
    return static_cast<size_t>(n);
  }

  // Writes one CSV record, including the newline. A missing, invalid or stale sensor gives
  // empty fields, so every row has the same column count (21). A null or invalid `noise` gives empty
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

    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
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

private:
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

#if defined(ARDUINO) || defined(ESP_PLATFORM)

enum class SdLogResult : uint8_t {
  OK = 0,
  NO_TIME,      // RTC time invalid: no file name
  MOUNT_FAILED, // no card or unreadable card
  OPEN_FAILED,
  WRITE_FAILED,
};

enum class SdConfigResult : uint8_t {
  LOADED = 0,  // the file was parsed
  NO_FILE,     // card mounted, but no usable file
  NO_CARD,     // the card did not mount
};

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
    if (!mount()) {
      return SdLogResult::MOUNT_FAILED;
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
      const RtcTime& now, const ClimateHistory& history, const NoiseHistory& noise) {
    uint16_t year = 0;
    uint8_t month = 0, day = 0;
    DailyPlanner::previousDay(now, year, month, day);
    char path[SdLogCodec::MAX_PATH_SIZE];
    char row[SdLogCodec::MAX_ROW_SIZE];
    if (SdLogCodec::formatDailyFilename(year, path, sizeof(path)) == 0) {
      return SdLogResult::NO_TIME;
    }
    const size_t row_len = SdLogCodec::formatDailyRow(year, month, day, history, noise, row, sizeof(row));
    if (row_len == 0) {
      return SdLogResult::NO_TIME;
    }
    if (!mount()) {
      return SdLogResult::MOUNT_FAILED;
    }
    const SdLogResult result = writeRecord(path, row, row_len, true);
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
    if (!mount()) {
      return SdConfigResult::NO_CARD;
    }
    // Static: the 4 KB buffer would use half of the 8 KB loop task stack. setup() runs once per
    // wake cycle, so one shared buffer is safe.
    static char buf[DeviceConfigParser::MAX_FILE_SIZE + 1];
    SdConfigResult result = SdConfigResult::NO_FILE;
    File file = SD_MMC.open("/config.ini", FILE_READ);
    if (file && !file.isDirectory()) {
      // One byte above the limit shows an oversized file; the parser then rejects it whole.
      const size_t n = file.read(reinterpret_cast<uint8_t*>(buf), sizeof(buf));
      const size_t expected = file.size() < sizeof(buf) ? static_cast<size_t>(file.size()) : sizeof(buf);
      if (n != expected) {
        result = SdConfigResult::NO_CARD; // a short read is a card error: keep the old settings
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

  // Mounts the card for a console command; the caller unmounts with SD_MMC.end().
  static bool mountCard() {
    return mount();
  }

private:
  // Pins first, then begin(): required by ESP32 core 3.3.12. 1-bit mode.
  static bool mount() {
    if (!SD_MMC.setPins(PIN_SDMMC_CLK, PIN_SDMMC_CMD, PIN_SDMMC_D0)) {
      return false;
    }
    if (!SD_MMC.begin(SdLogCodec::MOUNT_POINT, true)) {
      SD_MMC.end();
      return false;
    }
    if (SD_MMC.cardType() == CARD_NONE) {
      SD_MMC.end();
      return false;
    }
    return true;
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
