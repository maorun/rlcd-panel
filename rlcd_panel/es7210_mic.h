#pragma once

#include <math.h>
#include <stddef.h>
#include <stdint.h>

#include "board_pins.h"

#if defined(ARDUINO) || defined(ESP_PLATFORM)
#include <Arduino.h>
#include <ESP_I2S.h>
#include <Wire.h>
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

// Noise band classifier with hysteresis. A band changes up at its trigger level and back down
// only below its (lower) release level, so a level at the limit does not flip the band on every
// wake cycle. The levels are uncalibrated PCM RMS values, not sound pressure levels.
class NoiseClassifier {
public:
  static constexpr uint16_t MODERATE_TRIGGER_RMS = 300; // about -41 dBFS
  static constexpr uint16_t MODERATE_RELEASE_RMS = 200; // about -44 dBFS
  static constexpr uint16_t LOUD_TRIGGER_RMS = 3000;    // about -21 dBFS
  static constexpr uint16_t LOUD_RELEASE_RMS = 2000;    // about -24 dBFS
  static constexpr int DBFS_FLOOR = -96;                // level of a silent window (16-bit)

  static NoiseBand classify(NoiseBand previous, uint16_t rms) {
    if (rms >= LOUD_TRIGGER_RMS) {
      return NoiseBand::LOUD;
    }
    if (previous == NoiseBand::LOUD && rms >= LOUD_RELEASE_RMS) {
      return NoiseBand::LOUD;
    }
    if (rms >= MODERATE_TRIGGER_RMS) {
      return NoiseBand::MODERATE;
    }
    if (previous != NoiseBand::QUIET && rms >= MODERATE_RELEASE_RMS) {
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
  static int dbfs(uint16_t rms) {
    if (rms == 0) {
      return DBFS_FLOOR;
    }
    const int db = static_cast<int>(lroundf(20.0f * log10f(static_cast<float>(rms) / 32768.0f)));
    return db < DBFS_FLOOR ? DBFS_FLOOR : (db > 0 ? 0 : db);
  }

  static NoiseReading makeReading(NoiseBand previous, uint16_t rms) {
    NoiseReading r;
    r.valid = true;
    r.rms = rms;
    r.band = classify(previous, rms);
    return r;
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
  static NoiseReading measure(Hal& hal, NoiseBand previous, uint32_t window_ms = WINDOW_MS) {
    NoiseReading result;
    if (hal.beginBus() && hal.configureAdc()) {
      RmsAccumulator acc;
      if (collect(hal, acc, window_ms)) {
        result = NoiseClassifier::makeReading(previous, acc.rms());
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
