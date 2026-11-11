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
      const MicCalibration* noise_cal) {
    char path[SdLogCodec::MAX_PATH_SIZE];
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
  static SdConfigResult loadConfig(DeviceConfig& out) {
    out = DeviceConfig{};
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
    SD_MMC.end();
    return result;
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
