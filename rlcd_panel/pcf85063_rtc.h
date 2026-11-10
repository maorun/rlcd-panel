#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "board_pins.h"

#ifdef ARDUINO
#include <Wire.h>
#endif

namespace rlcd_panel {

struct RtcTime {
  bool valid = false;
  bool oscillator_stopped = false;
  uint16_t year = 2000; // 2000..2099
  uint8_t month = 1;    // 1..12
  uint8_t day = 1;      // 1..31
  uint8_t weekday = 0;  // 0..6 (0 = Sunday)
  uint8_t hour = 0;     // 0..23
  uint8_t minute = 0;   // 0..59
  uint8_t second = 0;   // 0..59
};

class Pcf85063Codec {
public:
  static constexpr uint8_t REG_SECONDS = 0x04;
  static constexpr size_t TIME_REGISTER_COUNT = 7; // seconds..years
  static constexpr uint8_t OSCILLATOR_STOP_MASK = 0x80;

  static bool bcdToBin(uint8_t bcd, uint8_t& out) {
    const uint8_t tens = static_cast<uint8_t>(bcd >> 4);
    const uint8_t ones = static_cast<uint8_t>(bcd & 0x0F);
    if (tens > 9 || ones > 9) {
      return false;
    }
    out = static_cast<uint8_t>(tens * 10 + ones);
    return true;
  }

  static uint8_t binToBcd(uint8_t value) {
    return static_cast<uint8_t>(((value / 10) << 4) | (value % 10));
  }

  // Day of week for a Gregorian date (0 = Sunday), Sakamoto's method.
  static uint8_t computeWeekday(uint16_t year, uint8_t month, uint8_t day) {
    static const uint8_t offsets[12] = {0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4};
    if (month < 1 || month > 12) {
      return 0;
    }
    const uint16_t y = (month < 3) ? static_cast<uint16_t>(year - 1) : year;
    return static_cast<uint8_t>((y + y / 4 - y / 100 + y / 400 + offsets[month - 1] + day) % 7);
  }

  static uint8_t daysInMonth(uint16_t year, uint8_t month) {
    static const uint8_t days[12] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    if (month < 1 || month > 12) {
      return 0;
    }
    const bool leap = (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0);
    return static_cast<uint8_t>(days[month - 1] + ((month == 2 && leap) ? 1 : 0));
  }

  static bool isTimeInRange(const RtcTime& t) {
    return t.year >= 2000 && t.year <= 2099 && t.month >= 1 && t.month <= 12 &&
           t.day >= 1 && t.day <= daysInMonth(t.year, t.month) && t.weekday <= 6 &&
           t.hour <= 23 && t.minute <= 59 && t.second <= 59;
  }

  // Decode registers 0x04..0x0A (seconds, minutes, hours, days, weekdays, months, years).
  // Assumes 24-hour mode. Returns false on invalid BCD, out-of-range values or when the
  // oscillator-stop flag is set; out.oscillator_stopped reports the flag in that case.
  static bool decodeRegisters(const uint8_t* regs, size_t len, RtcTime& out) {
    out = RtcTime{};
    if (regs == nullptr || len < TIME_REGISTER_COUNT) {
      return false;
    }

    out.oscillator_stopped = (regs[0] & OSCILLATOR_STOP_MASK) != 0;

    uint8_t second = 0, minute = 0, hour = 0, day = 0, weekday = 0, month = 0, year = 0;
    if (!bcdToBin(regs[0] & 0x7F, second) || !bcdToBin(regs[1] & 0x7F, minute) ||
        !bcdToBin(regs[2] & 0x3F, hour) || !bcdToBin(regs[3] & 0x3F, day) ||
        !bcdToBin(regs[4] & 0x07, weekday) || !bcdToBin(regs[5] & 0x1F, month) ||
        !bcdToBin(regs[6], year)) {
      return false;
    }

    out.second = second;
    out.minute = minute;
    out.hour = hour;
    out.day = day;
    out.weekday = weekday;
    out.month = month;
    out.year = static_cast<uint16_t>(2000 + year);

    if (!isTimeInRange(out) || out.oscillator_stopped) {
      return false;
    }
    out.valid = true;
    return true;
  }

  // Encode a time into registers 0x04..0x0A. The oscillator-stop flag is cleared.
  static bool encodeRegisters(const RtcTime& t, uint8_t* regs, size_t len) {
    if (regs == nullptr || len < TIME_REGISTER_COUNT || !isTimeInRange(t)) {
      return false;
    }
    regs[0] = binToBcd(t.second);
    regs[1] = binToBcd(t.minute);
    regs[2] = binToBcd(t.hour);
    regs[3] = binToBcd(t.day);
    regs[4] = binToBcd(t.weekday);
    regs[5] = binToBcd(t.month);
    regs[6] = binToBcd(static_cast<uint8_t>(t.year - 2000));
    return true;
  }

  // Format "HH:MM" for the header, or "--:--" when the time is not valid.
  // The buffer needs at least 6 bytes.
  static void formatClock(const RtcTime& t, char* buf, size_t len) {
    if (buf == nullptr || len < 6) {
      return;
    }
    if (!t.valid) {
      memcpy(buf, "--:--", 6);
      return;
    }
    buf[0] = static_cast<char>('0' + t.hour / 10);
    buf[1] = static_cast<char>('0' + t.hour % 10);
    buf[2] = ':';
    buf[3] = static_cast<char>('0' + t.minute / 10);
    buf[4] = static_cast<char>('0' + t.minute % 10);
    buf[5] = '\0';
  }

  // Parse the compiler stamps __DATE__ ("Nov 10 2026") and __TIME__ ("14:03:47").
  static bool fromCompileStamp(const char* date, const char* time, RtcTime& out) {
    static const char months[] = "JanFebMarAprMayJunJulAugSepOctNovDec";
    out = RtcTime{};
    if (date == nullptr || time == nullptr || strlen(date) != 11 || strlen(time) != 8) {
      return false;
    }

    uint8_t month = 0;
    for (uint8_t i = 0; i < 12; ++i) {
      if (strncmp(date, months + i * 3, 3) == 0) {
        month = static_cast<uint8_t>(i + 1);
        break;
      }
    }

    auto digit = [](char c, uint8_t& d) {
      if (c < '0' || c > '9') {
        return false;
      }
      d = static_cast<uint8_t>(c - '0');
      return true;
    };
    uint8_t d1 = 0, d2 = 0, y1 = 0, y2 = 0, y3 = 0, y4 = 0;
    uint8_t h1 = 0, h2 = 0, m1 = 0, m2 = 0, s1 = 0, s2 = 0;
    const bool day_space = date[4] == ' ';
    if (month == 0 || (!day_space && !digit(date[4], d1)) || !digit(date[5], d2) ||
        !digit(date[7], y1) || !digit(date[8], y2) || !digit(date[9], y3) ||
        !digit(date[10], y4) || !digit(time[0], h1) || !digit(time[1], h2) ||
        !digit(time[3], m1) || !digit(time[4], m2) || !digit(time[6], s1) ||
        !digit(time[7], s2)) {
      return false;
    }

    out.month = month;
    out.day = static_cast<uint8_t>(d1 * 10 + d2);
    out.year = static_cast<uint16_t>(y1 * 1000 + y2 * 100 + y3 * 10 + y4);
    out.hour = static_cast<uint8_t>(h1 * 10 + h2);
    out.minute = static_cast<uint8_t>(m1 * 10 + m2);
    out.second = static_cast<uint8_t>(s1 * 10 + s2);
    out.weekday = computeWeekday(out.year, out.month, out.day);
    if (!isTimeInRange(out)) {
      out = RtcTime{};
      return false;
    }
    out.valid = true;
    return true;
  }
};

#ifdef ARDUINO
class Pcf85063Rtc {
public:
  bool begin(TwoWire& wire = Wire) {
    wire_ = &wire;
    wire_->beginTransmission(I2C_ADDR_PCF85063);
    present_ = (wire_->endTransmission() == 0);
    return present_;
  }

  bool present() const {
    return present_;
  }

  // Returns false when the chip is absent, the bus read fails, the time is invalid or the
  // oscillator-stop flag is set. out.oscillator_stopped tells the caller to set the time.
  bool readTime(RtcTime& out) {
    out = RtcTime{};
    if (!present_ || wire_ == nullptr) {
      return false;
    }
    wire_->beginTransmission(I2C_ADDR_PCF85063);
    wire_->write(Pcf85063Codec::REG_SECONDS);
    if (wire_->endTransmission() != 0) {
      return false;
    }
    const size_t want = Pcf85063Codec::TIME_REGISTER_COUNT;
    if (wire_->requestFrom(static_cast<int>(I2C_ADDR_PCF85063), static_cast<int>(want)) !=
        static_cast<int>(want)) {
      return false;
    }
    uint8_t regs[Pcf85063Codec::TIME_REGISTER_COUNT];
    for (size_t i = 0; i < want; ++i) {
      regs[i] = static_cast<uint8_t>(wire_->read());
    }
    return Pcf85063Codec::decodeRegisters(regs, want, out);
  }

  // Writing the seconds register clears the oscillator-stop flag.
  bool setTime(const RtcTime& t) {
    uint8_t regs[Pcf85063Codec::TIME_REGISTER_COUNT];
    if (!present_ || wire_ == nullptr ||
        !Pcf85063Codec::encodeRegisters(t, regs, sizeof(regs))) {
      return false;
    }
    wire_->beginTransmission(I2C_ADDR_PCF85063);
    wire_->write(Pcf85063Codec::REG_SECONDS);
    for (size_t i = 0; i < sizeof(regs); ++i) {
      wire_->write(regs[i]);
    }
    return wire_->endTransmission() == 0;
  }

private:
  TwoWire* wire_ = nullptr;
  bool present_ = false;
};
#endif

} // namespace rlcd_panel
