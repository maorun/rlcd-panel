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
#include "pcf85063_rtc.h"
#include "shtc3_sensor.h"
#include "switchbot_ble.h"

#if defined(ARDUINO) || defined(ESP_PLATFORM)
#include <FS.h>
#include <SD_MMC.h>
#endif

namespace rlcd_panel {

// Decoupled CSV codec. It has no Arduino or SD_MMC dependency, so the host tests build it.
class SdLogCodec {
public:
  static constexpr uint32_t LOG_INTERVAL_MS = 3600000; // one record per hour
  static constexpr uint32_t RETRY_INTERVAL_MS = 600000; // after a failed write, retry in 10 min
  static constexpr size_t MAX_PATH_SIZE = 32;
  static constexpr size_t MAX_ROW_SIZE = 256;
  static constexpr const char* MOUNT_POINT = "/sdcard";
  // "climate_v2_YYYYMM.csv" plus the terminator.
  static constexpr size_t LOG_NAME_SIZE = 22;
  // Upper bound of the shown free space (about 9.5 TB), so the diagnostics line has a fixed width.
  static constexpr uint32_t MAX_FREE_MB = 9999999;
  // Earliest RTC year that the retention accepts. A wrong clock in the future must not delete logs.
  static constexpr uint16_t MIN_TRUSTED_YEAR = 2025;
  // Files that one prune pass deletes at most. More old files go in the next hourly pass.
  static constexpr size_t MAX_PRUNE_PER_PASS = 24;

  // Splits a bare log file name "climate_v2_YYYYMM.csv" into year 2000..2099 and month 01..12.
  // Anything else is false, so a path separator or "." never reaches the card.
  static bool parseLogFileName(const char* name, int& year, int& month) {
    static const char PREFIX[] = "climate_v2_";
    static const char SUFFIX[] = ".csv";
    constexpr size_t PREFIX_LEN = sizeof(PREFIX) - 1;
    constexpr size_t SUFFIX_LEN = sizeof(SUFFIX) - 1;
    if (name == nullptr || strlen(name) != PREFIX_LEN + 6 + SUFFIX_LEN ||
        memcmp(name, PREFIX, PREFIX_LEN) != 0 ||
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

  // True for a bare log file name "climate_v2_YYYYMM.csv" with year 2000..2099 and month 01..12.
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
        "noise_rms,noise_db,noise_band\n";
    const size_t len = sizeof(HEADER) - 1;
    if (out == nullptr || capacity < len + 1) {
      return 0;
    }
    memcpy(out, HEADER, len + 1);
    return len;
  }

  // Writes "/sdcard/climate_v2_YYYYMM.csv". The v2 name keeps the 18-column format apart from
  // the old 15-column files. Returns the length, or 0 for a null buffer, a small
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
        out, capacity, "%s/climate_v2_%04u%02u.csv", MOUNT_POINT,
        static_cast<unsigned>(time->year), static_cast<unsigned>(time->month));
    if (n <= 0 || static_cast<size_t>(n) >= capacity) {
      out[0] = '\0';
      return 0;
    }
    return static_cast<size_t>(n);
  }

  // Writes one CSV record, including the newline. A missing, invalid or stale sensor gives
  // empty fields, so every row has the same column count. A null or invalid `noise` gives empty
  // noise fields; the dB value uses `noise_cal` (the default calibration when null). Returns the
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
      size_t capacity) {
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
      if (r != nullptr && r->valid && !r->isStale(now_ms) && isfinite(r->temperature_c)) {
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
      pos += snprintf(out + pos, capacity - pos, ",%u,%d,%s", static_cast<unsigned>(noise->rms),
                      cal.splDb(noise->rms), NoiseClassifier::label(noise->band));
    } else {
      pos += snprintf(out + pos, capacity - pos, ",,,");
    }
    pos += snprintf(out + pos, capacity - pos, "\n");
    if (pos >= capacity) {
      out[0] = '\0';
      return 0;
    }
    return pos;
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
      int32_t* free_mb = nullptr) {
    char path[SdLogCodec::MAX_PATH_SIZE];
    if (free_mb != nullptr) {
      *free_mb = -1;
    }
    char row[SdLogCodec::MAX_ROW_SIZE];
    if (SdLogCodec::formatFilename(&time, path, sizeof(path)) == 0) {
      return SdLogResult::NO_TIME;
    }
    const size_t row_len =
        SdLogCodec::formatRow(&time, &battery, &onboard, slots, slot_count, now_ms, noise, noise_cal, row, sizeof(row));
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
  static SdConfigResult loadConfig(DeviceConfig& out, int32_t* free_mb = nullptr) {
    out = DeviceConfig{};
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
      } else if (DeviceConfigParser::parse(buf, n, out).file_ok) {
        result = SdConfigResult::LOADED;
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

  // Deletes the log files older than the retention period in the root directory. The names are
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
        if (!entry.isDirectory() && name != nullptr && SdLogCodec::shouldDeleteLog(name, now, retention_months)) {
          memcpy(names[count], name, SdLogCodec::LOG_NAME_SIZE); // the match proves length 21
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

  static SdLogResult writeRecord(const char* path, const char* row, size_t row_len) {
    const char* fs_path = path + strlen(SdLogCodec::MOUNT_POINT); // SD_MMC prefixes the mount
    const bool is_new = !SD_MMC.exists(fs_path);
    File file = SD_MMC.open(fs_path, FILE_APPEND);
    if (!file) {
      return SdLogResult::OPEN_FAILED;
    }
    bool ok = true;
    if (is_new || file.size() == 0) {
      char header[256];
      const size_t header_len = SdLogCodec::formatHeader(header, sizeof(header));
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
