#pragma once

#include <math.h>
#include <stddef.h>
#include <stdint.h>

#include "board_pins.h"

#if defined(ARDUINO) || defined(ESP_PLATFORM)
#include <Arduino.h>
#include <ESP_I2S.h>
#include <Preferences.h>
#include <Wire.h>
#endif

// Sound pressure level that matches 0 dBFS. Measure it once against a reference sound level
// meter and override it at build time. The default is an estimate and is not verified on hardware.
#ifndef MIC_SPL_OFFSET_DB
#define MIC_SPL_OFFSET_DB 90
#endif

namespace rlcd_panel {

// Noise bands. The band is persisted in RTC memory (two bits), so the values stay fixed.
enum class NoiseBand : uint8_t {
  QUIET = 0,
  MODERATE = 1,
  LOUD = 2,
};

// One measurement result of a sampling window.
struct NoiseReading {
  bool valid = false;
  uint16_t rms = 0; // average RMS of the window, 16-bit PCM units (0..32767)
  NoiseBand band = NoiseBand::QUIET;
};

// Decoupled acoustic energy calculator. It has no hardware dependency, so the host tests build it.
// The sum of squares of a whole window gives one RMS value; chunks of any size can be added.
class RmsAccumulator {
public:
  void add(const int16_t* samples, size_t count) {
    for (size_t i = 0; samples != nullptr && i < count; ++i) {
      const int64_t s = samples[i];
      sum_squares_ += static_cast<uint64_t>(s * s);
    }
    if (samples != nullptr) {
      count_ += count;
    }
  }

  uint64_t sampleCount() const { return count_; }

  // RMS of all samples added so far; 0 without samples. The maximum is 32768, clamped to 32767.
  uint16_t rms() const {
    if (count_ == 0) {
      return 0;
    }
    const double value = sqrt(static_cast<double>(sum_squares_) / static_cast<double>(count_));
    return value >= 32767.0 ? 32767 : static_cast<uint16_t>(lround(value));
  }

  void reset() {
    sum_squares_ = 0;
    count_ = 0;
  }

private:
  uint64_t sum_squares_ = 0; // 2^63 samples of full scale would be needed to overflow
  uint64_t count_ = 0;
};

constexpr int DBFS_FLOOR_DB = -96; // level of a silent window (16-bit)

// Level relative to full scale in whole dB, from DBFS_FLOOR_DB to 0.
inline int rmsToDbfs(uint16_t rms) {
  if (rms == 0) {
    return DBFS_FLOOR_DB;
  }
  const int db = static_cast<int>(lroundf(20.0f * log10f(static_cast<float>(rms) / 32768.0f)));
  return db < DBFS_FLOOR_DB ? DBFS_FLOOR_DB : (db > 0 ? 0 : db);
}

// Microphone calibration: the measured noise floor of a quiet room and the SPL offset (the dB SPL
// level that matches 0 dBFS). `calibrated` is true after a stored floor measurement. Without it
// the fixed uncalibrated band limits apply and the view shows dBFS.
struct MicCalibration {
  static constexpr int16_t DEFAULT_SPL_OFFSET_DB = MIC_SPL_OFFSET_DB;
  static constexpr int16_t MIN_SPL_OFFSET_DB = 0;
  static constexpr int16_t MAX_SPL_OFFSET_DB = 200;
  static constexpr int SPL_MIN_DB = 0;   // clamp of the converted level
  static constexpr int SPL_MAX_DB = 140;
  static constexpr uint16_t MIN_FLOOR_RMS = 1;
  static constexpr uint16_t MAX_FLOOR_RMS = 1000; // a louder room is not quiet: reject it (floor + 30 dB stays below full scale)
  static constexpr uint32_t CALIBRATION_WINDOW_MS = 10000;
  static_assert(
      DEFAULT_SPL_OFFSET_DB >= MIN_SPL_OFFSET_DB && DEFAULT_SPL_OFFSET_DB <= MAX_SPL_OFFSET_DB,
      "MIC_SPL_OFFSET_DB is out of range");

  bool calibrated = false;
  int16_t spl_offset_db = DEFAULT_SPL_OFFSET_DB;
  uint16_t noise_floor_rms = 0;

  static bool floorValid(uint16_t rms) { return rms >= MIN_FLOOR_RMS && rms <= MAX_FLOOR_RMS; }
  static bool offsetValid(int16_t db) { return db >= MIN_SPL_OFFSET_DB && db <= MAX_SPL_OFFSET_DB; }

  // Builds a calibrated record from a quiet-room window. Returns false and leaves `out` unchanged
  // when the floor is outside the accepted range.
  static bool fromFloor(uint16_t floor_rms, int16_t spl_offset_db, MicCalibration& out) {
    if (!floorValid(floor_rms) || !offsetValid(spl_offset_db)) {
      return false;
    }
    out.calibrated = true;
    out.spl_offset_db = spl_offset_db;
    out.noise_floor_rms = floor_rms;
    return true;
  }

  // dB SPL of an RMS value: dBFS plus the offset, clamped to SPL_MIN_DB..SPL_MAX_DB. The value is
  // unweighted: no A-weighting filter runs, so the "DBA" label is an approximation.
  int splDb(uint16_t rms) const {
    const int db = rmsToDbfs(rms) + static_cast<int>(spl_offset_db);
    return db < SPL_MIN_DB ? SPL_MIN_DB : (db > SPL_MAX_DB ? SPL_MAX_DB : db);
  }
};

// Applies the result of a calibration window. `save` stores the new record (NVS) and returns true
// on success. On success `active` is the new record, the reading is classified with its limits from
// QUIET, and `band` is the new hysteresis latch. On any failure (no reading, a floor outside the
// accepted range, a failed save) nothing changes and the result is false. The offset is always the
// build default, so a changed MIC_SPL_OFFSET_DB takes effect with the next calibration.
template <typename Saver>
bool applyCalibrationWindow(
    const NoiseReading& floor,
    MicCalibration& active,
    NoiseReading& reading,
    NoiseBand& band,
    Saver save);

// RMS limits of the noise bands.
struct NoiseBandLimits {
  uint16_t moderate_trigger;
  uint16_t moderate_release;
  uint16_t loud_trigger;
  uint16_t loud_release;
};

// Noise band classifier with hysteresis. A band changes up at its trigger level and back down
// only below its (lower) release level, so a level at the limit does not flip the band on every
// wake cycle. Without a calibration the levels are fixed PCM RMS values. With a calibration the
// limits follow the measured noise floor: moderate at floor + 10 dB, loud at floor + 30 dB, and
// both release 3 dB lower.
class NoiseClassifier {
public:
  static constexpr uint16_t MODERATE_TRIGGER_RMS = 300; // about -41 dBFS
  static constexpr uint16_t MODERATE_RELEASE_RMS = 200; // about -44 dBFS
  static constexpr uint16_t LOUD_TRIGGER_RMS = 3000;    // about -21 dBFS
  static constexpr uint16_t LOUD_RELEASE_RMS = 2000;    // about -24 dBFS
  static constexpr int DBFS_FLOOR = DBFS_FLOOR_DB;
  static constexpr int MODERATE_ABOVE_FLOOR_DB = 10;
  static constexpr int LOUD_ABOVE_FLOOR_DB = 30;
  static constexpr int HYSTERESIS_DB = 3;

  // Band limits for a calibration. An uncalibrated record gives the fixed limits.
  static NoiseBandLimits limits(const MicCalibration& cal) {
    if (!cal.calibrated || !MicCalibration::floorValid(cal.noise_floor_rms)) {
      return {MODERATE_TRIGGER_RMS, MODERATE_RELEASE_RMS, LOUD_TRIGGER_RMS, LOUD_RELEASE_RMS};
    }
    const uint16_t f = cal.noise_floor_rms;
    return {
        scaleRms(f, MODERATE_ABOVE_FLOOR_DB),
        scaleRms(f, MODERATE_ABOVE_FLOOR_DB - HYSTERESIS_DB),
        scaleRms(f, LOUD_ABOVE_FLOOR_DB),
        scaleRms(f, LOUD_ABOVE_FLOOR_DB - HYSTERESIS_DB)};
  }

  static NoiseBand classify(
      NoiseBand previous, uint16_t rms, const MicCalibration& cal = MicCalibration{}) {
    const NoiseBandLimits l = limits(cal);
    if (rms >= l.loud_trigger) {
      return NoiseBand::LOUD;
    }
    if (previous == NoiseBand::LOUD && rms >= l.loud_release) {
      return NoiseBand::LOUD;
    }
    if (rms >= l.moderate_trigger) {
      return NoiseBand::MODERATE;
    }
    if (previous != NoiseBand::QUIET && rms >= l.moderate_release) {
      return NoiseBand::MODERATE;
    }
    return NoiseBand::QUIET;
  }

  static const char* label(NoiseBand band) {
    switch (band) {
      case NoiseBand::MODERATE: return "MODERATE";
      case NoiseBand::LOUD: return "LOUD";
      case NoiseBand::QUIET:
      default: return "QUIET";
    }
  }

  // Level relative to full scale in whole dB, from DBFS_FLOOR to 0.
  static int dbfs(uint16_t rms) { return rmsToDbfs(rms); }

  static NoiseReading makeReading(
      NoiseBand previous, uint16_t rms, const MicCalibration& cal = MicCalibration{}) {
    NoiseReading r;
    r.valid = true;
    r.rms = rms;
    r.band = classify(previous, rms, cal);
    return r;
  }

private:
  // `rms` raised by `db` dB, rounded and kept in 1..32767.
  static uint16_t scaleRms(uint16_t rms, int db) {
    const long v = lroundf(static_cast<float>(rms) * powf(10.0f, static_cast<float>(db) / 20.0f));
    return v < 1 ? 1 : (v > 32767 ? 32767 : static_cast<uint16_t>(v));
  }
};

template <typename Saver>
bool applyCalibrationWindow(
    const NoiseReading& floor,
    MicCalibration& active,
    NoiseReading& reading,
    NoiseBand& band,
    Saver save) {
  MicCalibration fresh;
  if (!floor.valid ||
      !MicCalibration::fromFloor(floor.rms, MicCalibration::DEFAULT_SPL_OFFSET_DB, fresh) ||
      !save(fresh)) {
    return false;
  }
  active = fresh;
  reading = NoiseClassifier::makeReading(NoiseBand::QUIET, floor.rms, fresh);
  band = reading.band;
  return true;
}

// Serialization of the calibration record for NVS (12 bytes, little endian):
//   magic (2), version (1), calibrated (1), SPL offset (2), noise floor RMS (2), CRC-32 (4).
// The CRC covers the first 8 bytes. A wrong magic, version, CRC or field gives the default.
class MicCalibrationCodec {
public:
  static constexpr size_t SERIALIZED_SIZE = 12;
  static constexpr uint16_t MAGIC = 0x4D43; // "CM" in memory order
  static constexpr uint8_t VERSION = 1;

  static uint32_t crc32(const uint8_t* data, size_t len) {
    uint32_t crc = 0xFFFFFFFFu;
    for (size_t i = 0; data != nullptr && i < len; ++i) {
      crc ^= data[i];
      for (int bit = 0; bit < 8; ++bit) {
        crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u)));
      }
    }
    return ~crc;
  }

  static bool valid(const MicCalibration& cal) {
    if (!MicCalibration::offsetValid(cal.spl_offset_db)) {
      return false;
    }
    return cal.calibrated ? MicCalibration::floorValid(cal.noise_floor_rms)
                          : cal.noise_floor_rms == 0;
  }

  // Returns false for an invalid record or a small buffer.
  static bool serialize(const MicCalibration& cal, uint8_t* out, size_t capacity) {
    if (out == nullptr || capacity < SERIALIZED_SIZE || !valid(cal)) {
      return false;
    }
    putU16(out, 0, MAGIC);
    out[2] = VERSION;
    out[3] = cal.calibrated ? 1 : 0;
    putU16(out, 4, static_cast<uint16_t>(cal.spl_offset_db));
    putU16(out, 6, cal.noise_floor_rms);
    const uint32_t crc = crc32(out, 8);
    for (int i = 0; i < 4; ++i) {
      out[8 + i] = static_cast<uint8_t>(crc >> (8 * i));
    }
    return true;
  }

  // Returns false and leaves `out` unchanged for a wrong size, magic, version, CRC or field.
  static bool deserialize(const uint8_t* data, size_t len, MicCalibration& out) {
    if (data == nullptr || len != SERIALIZED_SIZE || getU16(data, 0) != MAGIC ||
        data[2] != VERSION || data[3] > 1) {
      return false;
    }
    uint32_t stored = 0;
    for (int i = 0; i < 4; ++i) {
      stored |= static_cast<uint32_t>(data[8 + i]) << (8 * i);
    }
    if (stored != crc32(data, 8)) {
      return false;
    }
    MicCalibration cal;
    cal.calibrated = data[3] == 1;
    cal.spl_offset_db = static_cast<int16_t>(getU16(data, 4));
    cal.noise_floor_rms = getU16(data, 6);
    if (!valid(cal)) {
      return false;
    }
    out = cal;
    return true;
  }

  // The stored record, or the default (uncalibrated, MIC_SPL_OFFSET_DB) when it is bad.
  static MicCalibration decodeOrDefault(const uint8_t* data, size_t len) {
    MicCalibration cal;
    return deserialize(data, len, cal) ? cal : MicCalibration{};
  }

private:
  static void putU16(uint8_t* out, size_t pos, uint16_t v) {
    out[pos] = static_cast<uint8_t>(v);
    out[pos + 1] = static_cast<uint8_t>(v >> 8);
  }
  static uint16_t getU16(const uint8_t* d, size_t pos) {
    return static_cast<uint16_t>(d[pos] | (d[pos + 1] << 8));
  }
};

// ES7210 register writes (reg, value). Derived from the Espressif reference driver for 16-bit
// I2S slave mode, MCLK = 256 x 16 kHz, microphones 1 and 2. The chip needs MCLK running before
// this sequence. The sequence is not verified on hardware.
struct Es7210Reg {
  uint8_t reg;
  uint8_t value;
};

namespace es7210 {

constexpr Es7210Reg INIT_SEQUENCE[] = {
    {0x00, 0xFF}, // reset
    {0x00, 0x41}, // release reset, slave mode
    {0x01, 0x1F}, // clock off register: all clocks on
    {0x09, 0x30}, // chip state machine timing
    {0x0A, 0x30},
    {0x23, 0x2A}, // high-pass filters of ADC 1 to 4
    {0x22, 0x0A},
    {0x21, 0x2A},
    {0x20, 0x0A},
    {0x40, 0x43}, // analog supply
    {0x41, 0x70}, // microphone bias 1/2 at 2.87 V
    {0x42, 0x70}, // microphone bias 3/4
    {0x07, 0x20}, // oversampling
    {0x02, 0xC1}, // main clock: ADC divider 1, doubler, DLL
    {0x04, 0x01}, // LRCK divider high (256)
    {0x05, 0x00}, // LRCK divider low
    {0x11, 0x60}, // I2S format, 16-bit
    {0x12, 0x00}, // normal I2S, not TDM
    {0x43, 0x1A}, // microphone 1 gain on, 30 dB
    {0x44, 0x1A}, // microphone 2 gain on, 30 dB
    {0x47, 0x08}, // power up microphone 1
    {0x48, 0x08}, // power up microphone 2
    {0x4B, 0x00}, // power up ADC 1 and 2
    {0x4C, 0xFF}, // power down ADC 3 and 4
    {0x06, 0x00}, // power down register: all blocks on
};

constexpr Es7210Reg SHUTDOWN_SEQUENCE[] = {
    {0x4B, 0xFF}, // power down ADC 1 and 2
    {0x4C, 0xFF}, // power down ADC 3 and 4
    {0x47, 0xC0}, // power down microphone 1 to 4
    {0x48, 0xC0},
    {0x49, 0xC0},
    {0x4A, 0xC0},
    {0x41, 0x00}, // microphone bias off
    {0x42, 0x00},
    {0x40, 0xC0}, // analog supply off
    {0x06, 0x07}, // power down all blocks
    {0x01, 0x7F}, // clocks off
};

} // namespace es7210

// Runs one sampling window against a hardware layer. The layer type provides:
//   bool beginBus(); bool configureAdc(); size_t read(int16_t* samples, size_t count);
//   void shutdownAdc(); void endBus();
// read() blocks and returns the number of samples read; 0 means a failure or a timeout. The
// samples are interleaved stereo (microphone 1, microphone 2). The ADC is powered down on every
// exit path, because a powered ADC and microphone bias drain the battery in deep sleep.
class NoiseSampler {
public:
  static constexpr uint32_t SAMPLE_RATE_HZ = 16000;
  static constexpr uint8_t CHANNELS = 2;
  static constexpr uint32_t WINDOW_MS = 5000;
  static constexpr size_t CHUNK_SAMPLES = 256;
  static constexpr uint32_t SETTLE_FRAMES = SAMPLE_RATE_HZ / 10; // the ADC settles for 100 ms

  // Returns an invalid reading when the bus or the ADC fails, or a read stops before the window
  // ends. `previous` is the band of the last measurement (hysteresis).
  template <typename Hal>
  static NoiseReading measure(
      Hal& hal,
      NoiseBand previous,
      uint32_t window_ms = WINDOW_MS,
      const MicCalibration& cal = MicCalibration{}) {
    NoiseReading result;
    if (hal.beginBus() && hal.configureAdc()) {
      RmsAccumulator acc;
      if (collect(hal, acc, window_ms)) {
        result = NoiseClassifier::makeReading(previous, acc.rms(), cal);
      }
    }
    hal.shutdownAdc();
    hal.endBus();
    return result;
  }

private:
  template <typename Hal>
  static bool collect(Hal& hal, RmsAccumulator& acc, uint32_t window_ms) {
    int16_t chunk[CHUNK_SAMPLES];
    const uint64_t settle = static_cast<uint64_t>(SETTLE_FRAMES) * CHANNELS;
    const uint64_t wanted =
        static_cast<uint64_t>(SAMPLE_RATE_HZ) * window_ms / 1000 * CHANNELS;
    if (wanted == 0) {
      return false;
    }
    uint64_t seen = 0;
    while (seen < settle + wanted) {
      const size_t n = hal.read(chunk, CHUNK_SAMPLES);
      if (n == 0 || n > CHUNK_SAMPLES) {
        return false;
      }
      // Skip the settling samples, then count toward the window.
      size_t skip = 0;
      if (seen < settle) {
        skip = static_cast<size_t>(settle - seen < n ? settle - seen : n);
      }
      acc.add(chunk + skip, n - skip);
      seen += n;
    }
    return true;
  }
};

#if defined(ARDUINO) || defined(ESP_PLATFORM)

// NVS storage of the calibration record. A missing, erased, or corrupt record gives the default.
class MicCalibrationStore {
public:
  static MicCalibration load() {
    uint8_t buf[MicCalibrationCodec::SERIALIZED_SIZE];
    Preferences prefs;
    if (!prefs.begin(NAMESPACE, true)) { // read-only open fails when the namespace is absent
      return MicCalibration{};
    }
    const size_t n = prefs.getBytesLength(KEY) == sizeof(buf) ? prefs.getBytes(KEY, buf, sizeof(buf)) : 0;
    prefs.end();
    return MicCalibrationCodec::decodeOrDefault(buf, n);
  }

  static bool save(const MicCalibration& cal) {
    uint8_t buf[MicCalibrationCodec::SERIALIZED_SIZE];
    if (!MicCalibrationCodec::serialize(cal, buf, sizeof(buf))) {
      return false;
    }
    Preferences prefs;
    if (!prefs.begin(NAMESPACE, false)) {
      return false;
    }
    const bool ok = prefs.putBytes(KEY, buf, sizeof(buf)) == sizeof(buf);
    prefs.end();
    return ok;
  }

private:
  static constexpr const char* NAMESPACE = "rlcd";
  static constexpr const char* KEY = "miccal";
};

// ESP32-S3 hardware layer: ES7210 over the shared I2C bus (already started) and I2S std mode
// with MCLK output, receive only.
class Es7210Hal {
public:
  bool beginBus() {
    i2s_.setPins(PIN_I2S_BCLK, PIN_I2S_LRCLK, -1, PIN_I2S_DIN, PIN_I2S_MCLK);
    i2s_.setTimeout(1000);
    bus_open_ = i2s_.begin(
        I2S_MODE_STD,
        NoiseSampler::SAMPLE_RATE_HZ,
        I2S_DATA_BIT_WIDTH_16BIT,
        I2S_SLOT_MODE_STEREO);
    return bus_open_;
  }

  bool configureAdc() {
    delay(5); // MCLK settles
    for (const Es7210Reg& r : es7210::INIT_SEQUENCE) {
      if (!writeReg(r)) {
        return false;
      }
    }
    return true;
  }

  size_t read(int16_t* samples, size_t count) {
    const size_t bytes = count * sizeof(int16_t);
    return i2s_.readBytes(reinterpret_cast<char*>(samples), bytes) / sizeof(int16_t);
  }

  // Runs on every exit path, also when the bus never opened (the chip may still be powered).
  // A failed write repeats the whole sequence once, because bias left on drains the battery.
  void shutdownAdc() {
    for (int attempt = 0; attempt < 2; ++attempt) {
      bool ok = true;
      for (const Es7210Reg& r : es7210::SHUTDOWN_SEQUENCE) {
        ok = writeReg(r) && ok;
      }
      if (ok) {
        return;
      }
    }
  }

  void endBus() {
    if (bus_open_) {
      i2s_.end();
      bus_open_ = false;
    }
  }

private:
  static bool writeReg(const Es7210Reg& r) {
    Wire.beginTransmission(I2C_ADDR_ES7210);
    Wire.write(r.reg);
    Wire.write(r.value);
    return Wire.endTransmission() == 0;
  }

  I2SClass i2s_;
  bool bus_open_ = false;
};

#endif

} // namespace rlcd_panel
