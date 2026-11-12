#pragma once

#include <math.h>
#include <stddef.h>
#include <stdint.h>

#include "board_pins.h"
#include "clap_detector.h"

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

// Self-test result of one microphone channel (MIC1 = left slot, MIC2 = right slot).
enum class MicStatus : uint8_t {
  OK = 0,
  SILENT = 1, // all samples are zero: dead microphone or missing I2S clock
  STUCK = 2,  // peak to peak below the limit: a constant or almost constant signal
  DC = 3,     // mean offset above the limit
  CLIP = 4,   // the peak reaches full scale
};

// Self-test of both channels for one sampling window. `valid` is false without a finished window.
// The imbalance is only set when both channels are OK, because a failed channel has no usable level.
struct MicSelfTest {
  static constexpr uint8_t CHANNELS = 2;
  static constexpr int IMBALANCE_LIMIT_DB = 10; // a larger level difference is reported

  bool valid = false;
  MicStatus channel[CHANNELS] = {MicStatus::OK, MicStatus::OK};
  uint8_t imbalance_db = 0; // level difference of the two channels, whole dB (both OK only)

  bool channelOk(uint8_t index) const {
    return valid && index < CHANNELS && channel[index] == MicStatus::OK;
  }
  // Both channels failed: the window has no usable level, so no noise band can be shown.
  bool bothFailed() const { return valid && !channelOk(0) && !channelOk(1); }
  bool imbalanced() const { return imbalance_db > IMBALANCE_LIMIT_DB; }

  static const char* label(MicStatus status) {
    switch (status) {
      case MicStatus::SILENT: return "SILENT";
      case MicStatus::STUCK: return "STUCK";
      case MicStatus::DC: return "DC";
      case MicStatus::CLIP: return "CLIP";
      case MicStatus::OK:
      default: return "OK";
    }
  }
};

// One measurement result of a sampling window. `valid` is true when at least one channel is OK.
// `rms` is the level of the usable channels only, so a dead microphone does not halve the level.
// When both channels fail, `valid` is false but `mic` still holds the failure for the view.
struct NoiseReading {
  bool valid = false;
  uint16_t rms = 0; // RMS of the usable channels of the window, 16-bit PCM units (0..32767)
  NoiseBand band = NoiseBand::QUIET;
  MicSelfTest mic;  // per-channel self-test of the same window
  uint16_t events = 0; // loud transients in the window (ClapDetector front end); 0 when invalid
  // Percentile levels of the 100 ms blocks of the window, as RMS of the usable channels. L10 is the
  // level that 10 % of the blocks exceed, L90 the background level that 90 % exceed (L10 >= L90).
  // `levels_valid` is false for a failed window and for a window shorter than one block.
  bool levels_valid = false;
  uint16_t l10_rms = 0;
  uint16_t l90_rms = 0;
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

// Per-channel statistics of a window: RMS, DC mean, peak values and full scale count. They decide
// the self-test status of the channel.
class MicChannelStats {
public:
  static constexpr int STUCK_PEAK_TO_PEAK_LIMIT = 2; // a peak to peak below 2 steps is stuck
  static constexpr int DC_MEAN_LIMIT = 4096;          // 12.5 % of full scale
  static constexpr uint32_t CLIP_MIN_SAMPLES = 8;     // a single glitch is not clipping

  void add(int16_t sample) {
    if (count_ == 0 || sample < min_) {
      min_ = sample;
    }
    if (count_ == 0 || sample > max_) {
      max_ = sample;
    }
    if (sample == INT16_MAX || sample == INT16_MIN) {
      ++full_scale_count_;
    }
    sum_ += sample;
    rms_.add(&sample, 1);
    ++count_;
  }

  uint64_t sampleCount() const { return count_; }
  uint16_t rms() const { return rms_.rms(); }
  int32_t peakToPeak() const { return count_ == 0 ? 0 : static_cast<int32_t>(max_) - min_; }
  double mean() const {
    return count_ == 0 ? 0.0 : static_cast<double>(sum_) / static_cast<double>(count_);
  }

  MicStatus status() const {
    if (count_ == 0 || (min_ == 0 && max_ == 0)) {
      return MicStatus::SILENT;
    }
    if (peakToPeak() < STUCK_PEAK_TO_PEAK_LIMIT) {
      return MicStatus::STUCK;
    }
    if (full_scale_count_ >= CLIP_MIN_SAMPLES) {
      return MicStatus::CLIP;
    }
    const double m = mean();
    if (m > DC_MEAN_LIMIT || m < -DC_MEAN_LIMIT) {
      return MicStatus::DC;
    }
    return MicStatus::OK;
  }

private:
  RmsAccumulator rms_;
  int64_t sum_ = 0;
  int16_t min_ = 0;
  int16_t max_ = 0;
  uint32_t full_scale_count_ = 0;
  uint64_t count_ = 0;
};

// Splits interleaved stereo samples (MIC1, MIC2) into the two channel statistics. The channel
// follows a running sample index, so a chunk with an odd length does not swap the channels.
class StereoAnalyzer {
public:
  void add(const int16_t* samples, size_t count) {
    for (size_t i = 0; samples != nullptr && i < count; ++i, ++index_) {
      channel_[index_ & 1].add(samples[i]);
    }
  }

  // Advances the channel position over samples that the caller drops (the settling time).
  void skip(size_t count) { index_ += count; }

  const MicChannelStats& channel(uint8_t index) const { return channel_[index & 1]; }

  MicSelfTest selfTest() const {
    MicSelfTest t;
    t.valid = channel_[0].sampleCount() > 0 && channel_[1].sampleCount() > 0;
    if (!t.valid) {
      return t;
    }
    for (uint8_t c = 0; c < MicSelfTest::CHANNELS; ++c) {
      t.channel[c] = channel_[c].status();
    }
    if (t.channelOk(0) && t.channelOk(1)) {
      // An OK channel has a peak to peak of at least 2, so its RMS is at least 1.
      const float a = static_cast<float>(channel_[0].rms() < 1 ? 1 : channel_[0].rms());
      const float b = static_cast<float>(channel_[1].rms() < 1 ? 1 : channel_[1].rms());
      const long db = lroundf(fabsf(20.0f * log10f(a / b)));
      t.imbalance_db = static_cast<uint8_t>(db > 255 ? 255 : db);
    }
    return t;
  }

  // Level of the usable channels: both together, or the one channel that is OK. False (rms 0)
  // when both channels failed.
  bool usableRms(const MicSelfTest& t, uint16_t& rms) const {
    rms = 0;
    if (t.channelOk(0) && t.channelOk(1)) {
      // RMS of both channels together: sqrt of the mean of the two mean squares.
      const double a = channel_[0].rms(), b = channel_[1].rms();
      const double v = sqrt((a * a + b * b) / 2.0);
      rms = v >= 32767.0 ? 32767 : static_cast<uint16_t>(lround(v));
      return true;
    }
    if (t.channelOk(0) || t.channelOk(1)) {
      rms = channel_[t.channelOk(0) ? 0 : 1].rms();
      return true;
    }
    return false;
  }

private:
  MicChannelStats channel_[2];
  size_t index_ = 0;
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

  // Copy with `extra_db` (config.ini) added to the offset, rounded and clamped to the valid range.
  // The offset has whole dB steps, so a fraction below 0.5 dB has no effect. The stored record keeps
  // its own offset, so a later calibration never saves the extra value.
  MicCalibration withExtraOffset(float extra_db) const {
    MicCalibration c = *this;
    if (extra_db != extra_db) {
      return c; // NaN
    }
    float v = static_cast<float>(spl_offset_db) + extra_db;
    v = v < MIN_SPL_OFFSET_DB ? MIN_SPL_OFFSET_DB : (v > MAX_SPL_OFFSET_DB ? MAX_SPL_OFFSET_DB : v);
    c.spl_offset_db = static_cast<int16_t>(v < 0 ? v - 0.5f : v + 0.5f);
    return c;
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
    {0x01, 0x00}, // clock off register: 0 = clock on, all clocks on
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

// Level of each 100 ms block of a window. The class keeps the sum of squares per block and channel
// (50 blocks, 800 bytes), so the usable channels can be chosen after the window, when the
// self-test is known. L10 and L90 are order statistics of the block RMS values. The conversion to
// dB SPL rises with the RMS, so the percentiles of the RMS give the percentiles of the level.
class BlockLevels {
public:
  static constexpr size_t MAX_BLOCKS = 50;      // a 5 s window; blocks of a longer window are ignored
  static constexpr uint32_t BLOCK_MS = 100;
  static constexpr size_t BLOCK_FRAMES = 1600; // 100 ms at 16 kHz

  // `blocks` complete blocks are counted (at most MAX_BLOCKS); further samples are ignored.
  explicit BlockLevels(size_t blocks) : blocks_(blocks > MAX_BLOCKS ? MAX_BLOCKS : blocks) {}

  // Adds interleaved stereo samples that follow the settling time.
  void add(const int16_t* samples, size_t count) {
    for (size_t i = 0; samples != nullptr && i < count; ++i, ++pos_) {
      const size_t block = pos_ / (BLOCK_FRAMES * 2);
      if (block >= blocks_) {
        return;
      }
      const int64_t v = samples[i];
      sum_[block][pos_ & 1] += static_cast<uint64_t>(v * v);
    }
  }

  size_t blocks() const { return blocks_; }

  // L10 and L90 of the blocks, from the channels that the self-test calls usable. False without a
  // block or without a usable channel.
  bool percentiles(const MicSelfTest& t, uint16_t& l10, uint16_t& l90) const {
    l10 = 0;
    l90 = 0;
    const bool use0 = t.channelOk(0), use1 = t.channelOk(1);
    if (blocks_ == 0 || (!use0 && !use1)) {
      return false;
    }
    uint16_t level[MAX_BLOCKS];
    for (size_t b = 0; b < blocks_; ++b) {
      const double m0 = static_cast<double>(sum_[b][0]) / BLOCK_FRAMES;
      const double m1 = static_cast<double>(sum_[b][1]) / BLOCK_FRAMES;
      const double mean = use0 && use1 ? (m0 + m1) / 2.0 : (use0 ? m0 : m1);
      const double v = sqrt(mean);
      level[b] = v >= 32767.0 ? 32767 : static_cast<uint16_t>(lround(v));
    }
    for (size_t i = 1; i < blocks_; ++i) { // insertion sort, ascending
      const uint16_t key = level[i];
      size_t j = i;
      for (; j > 0 && level[j - 1] > key; --j) {
        level[j] = level[j - 1];
      }
      level[j] = key;
    }
    // k blocks exceed the level at index n - 1 - k. L10: k = n / 10. L90: k = 9 n / 10.
    l10 = level[blocks_ - 1 - blocks_ / 10];
    l90 = level[blocks_ - 1 - (blocks_ * 9) / 10];
    return true;
  }

private:
  uint64_t sum_[MAX_BLOCKS][2] = {};
  size_t blocks_;
  size_t pos_ = 0;
};

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
  static constexpr size_t BLOCK_FRAMES = BlockLevels::BLOCK_FRAMES; // 100 ms block for L10 and L90
  static_assert(BLOCK_FRAMES == SAMPLE_RATE_HZ / 1000 * BlockLevels::BLOCK_MS, "block size");
  // BlockLevels takes the channel from the sample position after the settling time, so the
  // settling sample count must be even to match the StereoAnalyzer channel order.
  static_assert((SETTLE_FRAMES * CHANNELS) % 2 == 0, "settling samples must keep the channel order");

  // Returns an invalid reading when the bus or the ADC fails, or a read stops before the window
  // ends. `previous` is the band of the last measurement (hysteresis). L10 and L90 use whole 100 ms
  // blocks: a rest below 100 ms is ignored, and a window above MAX_BLOCKS blocks gives no
  // percentiles (`levels_valid` false).
  template <typename Hal>
  static NoiseReading measure(
      Hal& hal,
      NoiseBand previous,
      uint32_t window_ms = WINDOW_MS,
      const MicCalibration& cal = MicCalibration{}) {
    NoiseReading result;
    if (hal.beginBus() && hal.configureAdc()) {
      StereoAnalyzer analyzer;
      TransientCounter transients;
      BlockLevels blocks(static_cast<size_t>(window_ms / BlockLevels::BLOCK_MS));
      if (collect(hal, analyzer, transients, blocks, window_ms)) {
        result = evaluate(analyzer, previous, cal);
        if (result.valid) {
          result.events = transients.count();
          result.levels_valid =
              window_ms / BlockLevels::BLOCK_MS <= BlockLevels::MAX_BLOCKS &&
              blocks.percentiles(result.mic, result.l10_rms, result.l90_rms);
        }
      }
    }
    hal.shutdownAdc();
    hal.endBus();
    return result;
  }

private:
  // Reading of a finished window. The level covers the usable channels only. When both channels
  // fail the reading stays invalid (no band), but it keeps the self-test for the view.
  static NoiseReading evaluate(
      const StereoAnalyzer& analyzer, NoiseBand previous, const MicCalibration& cal) {
    NoiseReading r;
    r.mic = analyzer.selfTest();
    uint16_t rms = 0;
    if (analyzer.usableRms(r.mic, rms)) {
      const NoiseReading level = NoiseClassifier::makeReading(previous, rms, cal);
      r.valid = true;
      r.rms = level.rms;
      r.band = level.band;
    }
    return r;
  }

  template <typename Hal>
  static bool collect(
      Hal& hal,
      StereoAnalyzer& acc,
      TransientCounter& transients,
      BlockLevels& blocks,
      uint32_t window_ms) {
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
      acc.skip(skip);
      acc.add(chunk + skip, n - skip);
      transients.add(chunk + skip, n - skip);
      blocks.add(chunk + skip, n - skip);
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
