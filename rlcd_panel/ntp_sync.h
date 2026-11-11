#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "pcf85063_rtc.h"

// Optional Wi-Fi credentials. The file is not versioned (see .gitignore); copy
// wifi_config.example.h to wifi_config.h. Without it, or with an empty SSID, the firmware never
// starts the radio and the RTC keeps its time. Host builds ignore the file.
#ifdef ARDUINO
#if defined(__has_include)
#if __has_include("wifi_config.h")
#include "wifi_config.h"
#endif
#endif
#include <WiFi.h>
#include <esp_sntp.h>
#include <esp_timer.h>
#include <sys/time.h>
#include <time.h>
#endif

#ifndef RLCD_WIFI_SSID
#define RLCD_WIFI_SSID ""
#endif
#ifndef RLCD_WIFI_PASSWORD
#define RLCD_WIFI_PASSWORD ""
#endif
#ifndef RLCD_TZ_STD_OFFSET_S
#define RLCD_TZ_STD_OFFSET_S 3600 // CET, UTC+1
#endif
#ifndef RLCD_TZ_EU_DST
#define RLCD_TZ_EU_DST 1 // CEST, UTC+2, from the last Sunday of March to the last of October
#endif

namespace rlcd_panel {

// Local time zone. The DST rule is the EU rule: +1 h from 01:00 UTC on the last Sunday of March
// to 01:00 UTC on the last Sunday of October.
struct TimeZone {
  int32_t std_offset_s = 3600; // offset to UTC outside of DST
  bool eu_dst = true;
};

// Pure date and time maths. It has no Arduino dependency, so the host tests build it.
class NtpTime {
public:
  static constexpr int64_t EPOCH_2000 = 946684800;   // 2000-01-01 00:00:00 UTC
  static constexpr int64_t EPOCH_2100 = 4102444800;  // 2100-01-01 00:00:00 UTC
  static constexpr int32_t DST_SAVING_S = 3600;

  static bool isLeapYear(int32_t year) {
    return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0);
  }

  // Days since 1970-01-01 for a proleptic Gregorian date.
  static int64_t daysFromCivil(int32_t year, uint8_t month, uint8_t day) {
    const int64_t y = year - (month <= 2 ? 1 : 0);
    const int64_t era = (y >= 0 ? y : y - 399) / 400;
    const int64_t yoe = y - era * 400;
    const int64_t mp = month > 2 ? month - 3 : month + 9;
    const int64_t doy = (153 * mp + 2) / 5 + day - 1;
    const int64_t doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146097 + doe - 719468;
  }

  static void civilFromDays(int64_t days, int32_t& year, uint8_t& month, uint8_t& day) {
    const int64_t z = days + 719468;
    const int64_t era = (z >= 0 ? z : z - 146096) / 146097;
    const int64_t doe = z - era * 146097;
    const int64_t yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    const int64_t doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    const int64_t mp = (5 * doy + 2) / 153;
    const int64_t d = doy - (153 * mp + 2) / 5 + 1;
    const int64_t m = mp < 10 ? mp + 3 : mp - 9;
    year = static_cast<int32_t>(yoe + era * 400 + (m <= 2 ? 1 : 0));
    month = static_cast<uint8_t>(m);
    day = static_cast<uint8_t>(d);
  }

  // UTC epoch seconds to calendar fields. The PCF85063A holds the years 2000..2099, so any other
  // epoch returns false and leaves `out` default.
  static bool epochToCalendar(int64_t epoch, RtcTime& out) {
    out = RtcTime{};
    if (epoch < EPOCH_2000 || epoch >= EPOCH_2100) {
      return false;
    }
    const int64_t days = epoch / 86400;
    const int64_t secs = epoch % 86400;
    int32_t year = 0;
    uint8_t month = 0, day = 0;
    civilFromDays(days, year, month, day);
    out.year = static_cast<uint16_t>(year);
    out.month = month;
    out.day = day;
    out.weekday = static_cast<uint8_t>((days + 4) % 7); // 1970-01-01 was a Thursday
    out.hour = static_cast<uint8_t>(secs / 3600);
    out.minute = static_cast<uint8_t>((secs % 3600) / 60);
    out.second = static_cast<uint8_t>(secs % 60);
    out.valid = true;
    return true;
  }

  // Day of month of the last Sunday.
  static uint8_t lastSunday(int32_t year, uint8_t month) {
    static const uint8_t days[12] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    const uint8_t last = static_cast<uint8_t>(days[month - 1] + ((month == 2 && isLeapYear(year)) ? 1 : 0));
    const int64_t weekday = (daysFromCivil(year, month, last) + 4) % 7;
    return static_cast<uint8_t>(last - weekday);
  }

  // DST starts at 01:00 UTC on the last Sunday of March.
  static int64_t euDstStartUtc(int32_t year) {
    return daysFromCivil(year, 3, lastSunday(year, 3)) * 86400 + 3600;
  }

  // DST ends at 01:00 UTC on the last Sunday of October.
  static int64_t euDstEndUtc(int32_t year) {
    return daysFromCivil(year, 10, lastSunday(year, 10)) * 86400 + 3600;
  }

  static bool isEuDst(int64_t utc_epoch) {
    RtcTime utc;
    if (!epochToCalendar(utc_epoch, utc)) {
      return false;
    }
    return utc_epoch >= euDstStartUtc(utc.year) && utc_epoch < euDstEndUtc(utc.year);
  }

  static int32_t offsetSeconds(int64_t utc_epoch, const TimeZone& tz) {
    return tz.std_offset_s + ((tz.eu_dst && isEuDst(utc_epoch)) ? DST_SAVING_S : 0);
  }

  // UTC epoch to local calendar fields. Returns false when the local time lies outside 2000..2099.
  static bool utcToLocal(int64_t utc_epoch, const TimeZone& tz, RtcTime& out) {
    return epochToCalendar(utc_epoch + offsetSeconds(utc_epoch, tz), out);
  }

  // UTC epoch to the PCF85063A time registers 0x04..0x0A (BCD, local time).
  static bool epochToRegisters(int64_t utc_epoch, const TimeZone& tz, uint8_t* regs, size_t len) {
    RtcTime local;
    return utcToLocal(utc_epoch, tz, local) && Pcf85063Codec::encodeRegisters(local, regs, len);
  }
};

// Moves a system time forward by the time that passed while SNTP had set it. The sync restores
// the old course of the system clock with this, so the stored timestamps stay consistent.
inline void advanceTime(int64_t sec, int64_t usec, int64_t elapsed_us, int64_t& out_sec, int64_t& out_usec) {
  const int64_t total_us = usec + elapsed_us;
  out_sec = sec + total_us / 1000000;
  out_usec = total_us % 1000000;
}

// Sync bookkeeping that survives deep sleep.
struct NtpSyncState {
  uint32_t last_attempt_ms = 0; // clock value of the last attempt
  bool has_attempted = false;   // false until the first attempt
  bool last_ok = false;         // the last attempt set the RTC
  uint8_t failures = 0;         // failed attempts in a row
};

enum class NtpDecision : uint8_t {
  ATTEMPT = 0,
  SKIP_KEY_WAKEUP,
  SKIP_NO_CREDENTIALS,
  SKIP_NO_RTC,
  SKIP_LOW_BATTERY,
  SKIP_NOT_DUE,
};

// When to try a sync. The policy never blocks a wake cycle and keeps the radio off whenever it
// can: one attempt per configured interval (default 24 h) after a success, and a doubling backoff after a failure.
class NtpSchedule {
public:
  static constexpr uint32_t MS_PER_HOUR = 3600000;
  static constexpr uint32_t RETRY_BASE_MS = 900000;       // 15 min after the first failure
  static constexpr uint32_t RETRY_MAX_MS = 21600000;      // 6 h
  static constexpr uint8_t MAX_FAILURES = 16;
  static constexpr size_t SSID_MAX_CHARS = 32;
  static constexpr size_t PASSWORD_MIN_CHARS = 8;
  static constexpr size_t PASSWORD_MAX_CHARS = 63;
  static constexpr size_t PSK_HEX_CHARS = 64;

  static bool hasCredentials(const char* ssid, const char* password) {
    if (ssid == nullptr || password == nullptr) {
      return false;
    }
    const size_t ssid_len = strlen(ssid);
    const size_t password_len = strlen(password);
    if (ssid_len == 0 || ssid_len > SSID_MAX_CHARS) {
      return false;
    }
    // WPA2 needs 8..63 characters or a 64-digit hex key; an empty password means an open network.
    if (password_len == 0 || (password_len >= PASSWORD_MIN_CHARS && password_len <= PASSWORD_MAX_CHARS)) {
      return true;
    }
    if (password_len != PSK_HEX_CHARS) {
      return false;
    }
    for (size_t i = 0; i < password_len; ++i) {
      const char c = password[i];
      if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
        return false;
      }
    }
    return true;
  }

  // Time between two successful syncs. The caller passes DeviceConfig::ntp_sync_interval_h (6..168).
  static uint32_t syncIntervalMs(uint8_t interval_h) {
    return static_cast<uint32_t>(interval_h) * MS_PER_HOUR;
  }

  // Delay before the next attempt after `failures` failures in a row: 15 min, 30 min, ... 6 h.
  static uint32_t retryDelayMs(uint8_t failures) {
    if (failures == 0) {
      return RETRY_BASE_MS;
    }
    uint32_t delay = RETRY_BASE_MS;
    for (uint8_t i = 1; i < failures && delay < RETRY_MAX_MS; ++i) {
      delay *= 2;
    }
    return delay < RETRY_MAX_MS ? delay : RETRY_MAX_MS;
  }

  // Unsigned subtraction survives clock wrap-around and a clock reset (both give an early try).
  static bool due(const NtpSyncState& s, uint32_t now_ms, uint8_t interval_h) {
    if (!s.has_attempted) {
      return true;
    }
    const uint32_t elapsed = static_cast<uint32_t>(now_ms - s.last_attempt_ms);
    return elapsed >= (s.last_ok ? syncIntervalMs(interval_h) : retryDelayMs(s.failures));
  }

  static NtpDecision decide(const NtpSyncState& s, uint32_t now_ms, bool key_wakeup,
                            bool credentials, bool rtc_present, bool battery_low, uint8_t interval_h) {
    if (key_wakeup) return NtpDecision::SKIP_KEY_WAKEUP; // the user waits for the redraw
    if (!credentials) return NtpDecision::SKIP_NO_CREDENTIALS;
    if (!rtc_present) return NtpDecision::SKIP_NO_RTC;
    if (battery_low) return NtpDecision::SKIP_LOW_BATTERY;
    if (!due(s, now_ms, interval_h)) return NtpDecision::SKIP_NOT_DUE;
    return NtpDecision::ATTEMPT;
  }

  // Records one attempt. A failure keeps the RTC unchanged and starts or extends the backoff.
  static void record(NtpSyncState& s, uint32_t now_ms, bool ok) {
    s.has_attempted = true;
    s.last_attempt_ms = now_ms;
    s.last_ok = ok;
    if (ok) {
      s.failures = 0;
    } else if (s.failures < MAX_FAILURES) {
      ++s.failures;
    }
  }
};

#ifdef ARDUINO

enum class NtpResult : uint8_t {
  OK = 0,
  WIFI_FAILED,
  NTP_TIMEOUT,
  RTC_WRITE_FAILED,
};

class NtpClient {
public:
  static constexpr uint32_t WIFI_TIMEOUT_MS = 8000;
  static constexpr uint32_t NTP_TIMEOUT_MS = 6000;
  static constexpr int64_t MIN_PLAUSIBLE_EPOCH = 1735689600; // 2025-01-01; below it SNTP has not set the time

  // Connects, reads the UTC time, switches the radio off, then writes the local time to the
  // PCF85063A. The radio is off before the I2C write. Both waits are bounded, and a failure leaves
  // the RTC unchanged. SNTP sets the system time, which the firmware uses as its timestamp clock
  // (PowerPlatform::clockMs), so the function puts the system time back to its old course.
  static NtpResult sync(Pcf85063Rtc& rtc, const char* ssid, const char* password, const TimeZone& tz) {
    struct timeval saved;
    gettimeofday(&saved, nullptr);
    const int64_t start_us = esp_timer_get_time();

    WiFi.persistent(false);
    WiFi.mode(WIFI_STA);
    WiFi.begin(ssid, password);
    const uint32_t wifi_start = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - wifi_start < WIFI_TIMEOUT_MS) {
      delay(100);
    }

    NtpResult result = NtpResult::WIFI_FAILED;
    int64_t epoch = 0;
    int64_t acquired_us = 0;
    if (WiFi.status() == WL_CONNECTED) {
      result = NtpResult::NTP_TIMEOUT;
      configTime(0, 0, "0.pool.ntp.org", "1.pool.ntp.org", "2.pool.ntp.org");
      const uint32_t ntp_start = millis();
      while (millis() - ntp_start < NTP_TIMEOUT_MS) {
        struct timeval now;
        gettimeofday(&now, nullptr);
        if (now.tv_sec >= MIN_PLAUSIBLE_EPOCH) {
          epoch = now.tv_sec;
          acquired_us = esp_timer_get_time();
          result = NtpResult::OK;
          break;
        }
        delay(50);
      }
    }

    // Radio off first: nothing below needs it.
    esp_sntp_stop();
    WiFi.disconnect(true);
    WiFi.mode(WIFI_OFF);

    // esp_timer keeps counting through settimeofday(), so it measures the time spent here.
    const int64_t elapsed_us = esp_timer_get_time() - start_us;
    int64_t restored_sec = 0, restored_usec = 0;
    advanceTime(saved.tv_sec, saved.tv_usec, elapsed_us, restored_sec, restored_usec);
    struct timeval restored;
    restored.tv_sec = static_cast<time_t>(restored_sec);
    restored.tv_usec = static_cast<suseconds_t>(restored_usec);
    settimeofday(&restored, nullptr);

    if (result != NtpResult::OK) {
      return result;
    }
    epoch += (esp_timer_get_time() - acquired_us) / 1000000;
    RtcTime local;
    if (!NtpTime::utcToLocal(epoch, tz, local) || !rtc.setTime(local)) {
      return NtpResult::RTC_WRITE_FAILED;
    }
    return NtpResult::OK;
  }
};

#endif

} // namespace rlcd_panel
