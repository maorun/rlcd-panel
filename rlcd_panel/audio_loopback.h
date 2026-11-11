#pragma once

#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

#include "audio_alert.h"
#include "board_pins.h"
#include "es7210_mic.h"

#if defined(ARDUINO) || defined(ESP_PLATFORM)
#include <Arduino.h>
#include <ESP_I2S.h>
#include <Wire.h>
#endif

namespace rlcd_panel {

// Goertzel filter for one frequency. It has no hardware dependency, so the host tests feed it
// synthetic buffers. Samples are scaled to full scale = 1.0.
class GoertzelFilter {
public:
  GoertzelFilter(float freq_hz, uint32_t sample_rate_hz)
      : coeff_(2.0f * cosf(6.28318530718f * freq_hz / static_cast<float>(sample_rate_hz))) {}

  void add(int16_t sample) {
    const float x = static_cast<float>(sample) / 32768.0f;
    const float s0 = x + coeff_ * s1_ - s2_;
    s2_ = s1_;
    s1_ = s0;
    energy_ += x * x;
    sum_ += x;
    ++count_;
  }

  uint32_t count() const { return count_; }
  float totalEnergy() const { return energy_; }
  // Energy around the mean: a microphone DC offset adds nothing to it.
  float acEnergy() const {
    return count_ == 0 ? 0.0f : energy_ - sum_ * sum_ / static_cast<float>(count_);
  }

  // Squared magnitude of the DFT bin at the filter frequency.
  float tonePower() const {
    const float p = s1_ * s1_ + s2_ * s2_ - coeff_ * s1_ * s2_;
    return p > 0.0f ? p : 0.0f;
  }

private:
  float coeff_;
  float s1_ = 0.0f;
  float s2_ = 0.0f;
  float energy_ = 0.0f;
  float sum_ = 0.0f;
  uint32_t count_ = 0;
};

// Level of the 1 kHz tone in one window and the share of the window energy that the tone holds.
struct ToneMeasurement {
  bool valid = false;
  float level_db = -120.0f; // tone amplitude in dBFS, floored at -120
  float tone_ratio = 0.0f;  // tone power / AC power (DC offset removed), 0..1
  // The level is the mean over the whole window, so the playback latency lowers it a little.

  static constexpr float FLOOR_DB = -120.0f;

  static ToneMeasurement from(const GoertzelFilter& filter) {
    ToneMeasurement m;
    const uint32_t n = filter.count();
    if (n == 0) {
      return m;
    }
    m.valid = true;
    // A sine of amplitude A gives a bin power of (A * N / 2)^2.
    const float amplitude = 2.0f * sqrtf(filter.tonePower()) / static_cast<float>(n);
    const float level = amplitude > 1e-6f ? 20.0f * log10f(amplitude) : FLOOR_DB;
    m.level_db = level < FLOOR_DB ? FLOOR_DB : level;
    const float mean_square = filter.acEnergy() / static_cast<float>(n);
    if (mean_square > 0.0f) {
      const float ratio = (amplitude * amplitude * 0.5f) / mean_square;
      m.tone_ratio = ratio > 1.0f ? 1.0f : ratio;
    }
    return m;
  }
};

enum class LoopbackStatus : uint8_t {
  NOT_RUN,
  RUNNING,
  PASS,
  FAIL_TONE,     // the tone is too weak or not dominant
  FAIL_HARDWARE, // the bus, a codec or a transfer failed
  SKIPPED_MUTED,
  SKIPPED_QUIET,
  SKIPPED_VOLUME, // the chime volume is 0
};

struct LoopbackResult {
  LoopbackStatus status = LoopbackStatus::NOT_RUN;
  int16_t level_db = 0; // tone level in dBFS; valid for PASS and FAIL_TONE

  bool passed() const { return status == LoopbackStatus::PASS; }
  bool skipped() const {
    return status == LoopbackStatus::SKIPPED_MUTED || status == LoopbackStatus::SKIPPED_QUIET ||
           status == LoopbackStatus::SKIPPED_VOLUME;
  }

  // Text for the diagnostics view. Widest: "AUDIO LOOP: PASS -120 DB" = 24 glyphs = 288 px.
  void describe(char* out, size_t capacity) const {
    switch (status) {
      case LoopbackStatus::RUNNING: snprintf(out, capacity, "AUDIO LOOP: RUNNING"); break;
      case LoopbackStatus::PASS:
        snprintf(out, capacity, "AUDIO LOOP: PASS %d DB", static_cast<int>(level_db));
        break;
      case LoopbackStatus::FAIL_TONE:
      case LoopbackStatus::FAIL_HARDWARE: snprintf(out, capacity, "AUDIO LOOP: FAIL"); break;
      case LoopbackStatus::SKIPPED_MUTED: snprintf(out, capacity, "AUDIO LOOP: SKIP MUTED"); break;
      case LoopbackStatus::SKIPPED_QUIET: snprintf(out, capacity, "AUDIO LOOP: SKIP QUIET"); break;
      case LoopbackStatus::SKIPPED_VOLUME: snprintf(out, capacity, "AUDIO LOOP: SKIP VOLUME"); break;
      case LoopbackStatus::NOT_RUN:
      default: snprintf(out, capacity, "AUDIO LOOP: --"); break;
    }
  }
};

// Detector for the loopback tone: 1 kHz at 16 kHz sample rate, two interleaved microphone
// channels. The window passes when the better channel holds the tone at or above MIN_LEVEL_DB
// and the tone makes at least MIN_TONE_RATIO of the window power. The ratio rejects noise and
// other frequencies that are loud but not the tone. The limits are estimates and are not
// verified on hardware.
class LoopbackDetector {
public:
  static constexpr float TONE_HZ = 1000.0f;
  static constexpr uint32_t SAMPLE_RATE_HZ = ChimeSynth::SAMPLE_RATE_HZ;
  static constexpr float MIN_LEVEL_DB = -60.0f;
  static constexpr float MIN_TONE_RATIO = 0.5f;

  LoopbackDetector() : left_(TONE_HZ, SAMPLE_RATE_HZ), right_(TONE_HZ, SAMPLE_RATE_HZ) {}

  // `count` interleaved samples (left, right); an odd trailing sample is ignored.
  void addInterleaved(const int16_t* samples, size_t count) {
    for (size_t i = 0; samples != nullptr && i + 1 < count; i += 2) {
      left_.add(samples[i]);
      right_.add(samples[i + 1]);
    }
  }

  // The measurement of the channel with the stronger tone.
  ToneMeasurement best() const {
    const ToneMeasurement l = ToneMeasurement::from(left_);
    const ToneMeasurement r = ToneMeasurement::from(right_);
    if (!l.valid) return r;
    if (!r.valid) return l;
    return r.level_db > l.level_db ? r : l;
  }

  static bool passes(const ToneMeasurement& m) {
    return m.valid && m.level_db >= MIN_LEVEL_DB && m.tone_ratio >= MIN_TONE_RATIO;
  }

  LoopbackResult result() const {
    const ToneMeasurement m = best();
    LoopbackResult r;
    if (!m.valid) {
      r.status = LoopbackStatus::FAIL_HARDWARE;
      return r;
    }
    r.level_db = static_cast<int16_t>(lroundf(m.level_db));
    r.status = passes(m) ? LoopbackStatus::PASS : LoopbackStatus::FAIL_TONE;
    return r;
  }

private:
  GoertzelFilter left_;
  GoertzelFilter right_;
};

// The 500 ms test tone at 1 kHz.
struct LoopbackTone {
  static constexpr uint16_t DURATION_MS = 500;

  static ChimePattern pattern() {
    static const ChimeNote TONE[] = {{1000, DURATION_MS}};
    return {TONE, 1};
  }
};

// Runs the speaker to microphone loopback against a hardware layer. The layer type provides:
//   bool beginBus(); bool configureCodec(); bool configureAdc(); void setAmplifier(bool on);
//   bool write(const int16_t* samples, size_t count); size_t read(int16_t* samples, size_t count);
//   void drain(); void shutdownCodec(); void shutdownAdc(); void endBus();
// write() and read() move interleaved stereo samples (count = samples, not frames). The
// amplifier and both codecs are powered down on every exit path, in this order: amplifier,
// speaker codec, microphone ADC, bus. A powered amplifier or microphone bias drains the battery
// in deep sleep.
class AudioLoopback {
public:
  static constexpr size_t CHUNK_FRAMES = 256;
  static constexpr uint32_t SETTLE_FRAMES = LoopbackDetector::SAMPLE_RATE_HZ / 10; // ADC settles 100 ms

  // The reason to skip the test, or NOT_RUN when it may run. No test sounds while muted, in
  // quiet hours, or with chime volume 0.
  static LoopbackStatus skipReason(bool muted, bool quiet_hours, uint8_t volume_pct) {
    if (muted) return LoopbackStatus::SKIPPED_MUTED;
    if (quiet_hours) return LoopbackStatus::SKIPPED_QUIET;
    if (volume_pct == 0) return LoopbackStatus::SKIPPED_VOLUME;
    return LoopbackStatus::NOT_RUN;
  }

  template <typename Hal>
  static LoopbackResult run(Hal& hal, uint8_t volume_pct, bool muted, bool quiet_hours) {
    LoopbackResult result;
    const LoopbackStatus skip = skipReason(muted, quiet_hours, volume_pct);
    if (skip != LoopbackStatus::NOT_RUN) {
      result.status = skip; // the hardware stays untouched
      return result;
    }
    result.status = LoopbackStatus::FAIL_HARDWARE;
    if (hal.beginBus() && hal.configureCodec() && hal.configureAdc()) {
      hal.setAmplifier(true);
      LoopbackDetector detector;
      if (capture(hal, volume_pct, detector)) {
        result = detector.result();
      }
      // Silence after the tone, so no stale tone stays in the DMA buffers when the amplifier stops.
      const int16_t silence[CHUNK_FRAMES * 2] = {0};
      hal.write(silence, CHUNK_FRAMES * 2); // a failed write changes nothing: the shutdown follows
      hal.drain(); // let the queued samples play out before the codecs shut down
    }
    hal.setAmplifier(false);
    hal.shutdownCodec();
    hal.shutdownAdc();
    hal.endBus();
    return result;
  }

private:
  // Plays the tone and records it at the same time. The first SETTLE_FRAMES frames of the
  // recording stay out of the analysis. Returns false when a transfer fails or comes up short.
  template <typename Hal>
  static bool capture(Hal& hal, uint8_t volume_pct, LoopbackDetector& detector) {
    const ChimePattern pattern = LoopbackTone::pattern();
    int16_t mono[CHUNK_FRAMES];
    int16_t tx[CHUNK_FRAMES * 2];
    int16_t rx[CHUNK_FRAMES * 2];
    uint32_t pos = 0;
    for (;;) {
      const size_t frames = ChimeSynth::render(pattern, volume_pct, pos, mono, CHUNK_FRAMES);
      if (frames == 0) {
        return true;
      }
      for (size_t i = 0; i < frames; ++i) {
        tx[2 * i] = mono[i];
        tx[2 * i + 1] = mono[i];
      }
      if (!hal.write(tx, frames * 2) || hal.read(rx, frames * 2) != frames * 2) {
        return false;
      }
      size_t skip = 0;
      if (pos < SETTLE_FRAMES) {
        skip = static_cast<size_t>(SETTLE_FRAMES - pos < frames ? SETTLE_FRAMES - pos : frames);
      }
      detector.addInterleaved(rx + skip * 2, (frames - skip) * 2);
      pos += static_cast<uint32_t>(frames);
    }
  }
};

#if defined(ARDUINO) || defined(ESP_PLATFORM)

// ESP32-S3 hardware layer: one full duplex I2S bus (transmit to the ES8311, receive from the
// ES7210), both codecs over the shared I2C bus (already started), and the PA_CTRL pin. The
// register sequences come from the chime and the noise monitor. They are not verified on hardware.
class LoopbackHal {
public:
  bool beginBus() {
    i2s_.setPins(PIN_I2S_BCLK, PIN_I2S_LRCLK, PIN_I2S_DOUT, PIN_I2S_DIN, PIN_I2S_MCLK);
    i2s_.setTimeout(1000);
    bus_open_ = i2s_.begin(
        I2S_MODE_STD,
        LoopbackDetector::SAMPLE_RATE_HZ,
        I2S_DATA_BIT_WIDTH_16BIT,
        I2S_SLOT_MODE_STEREO);
    return bus_open_;
  }

  bool configureCodec() {
    delay(5); // MCLK settles
    for (const Es8311Reg& r : es8311::INIT_SEQUENCE) {
      if (!writeReg(I2C_ADDR_ES8311, r.reg, r.value)) {
        return false;
      }
    }
    delay(20); // DAC output settles before the amplifier turns on
    return true;
  }

  bool configureAdc() {
    for (const Es7210Reg& r : es7210::INIT_SEQUENCE) {
      if (!writeReg(I2C_ADDR_ES7210, r.reg, r.value)) {
        return false;
      }
    }
    return true;
  }

  // pinMode() first, then digitalWrite(): required by ESP32 core 3.3.12.
  void setAmplifier(bool on) {
    pinMode(PIN_PA_CTRL, OUTPUT);
    digitalWrite(PIN_PA_CTRL, on ? HIGH : LOW);
  }

  bool write(const int16_t* samples, size_t count) {
    const size_t bytes = count * sizeof(int16_t);
    return i2s_.write(reinterpret_cast<const uint8_t*>(samples), bytes) == bytes;
  }

  size_t read(int16_t* samples, size_t count) {
    return i2s_.readBytes(reinterpret_cast<char*>(samples), count * sizeof(int16_t)) /
           sizeof(int16_t);
  }

  void drain() { delay(100); }

  // Both shutdown calls run on every exit path, also when the bus never opened (the chips may
  // still be powered). A failed write repeats the whole sequence once.
  void shutdownCodec() {
    for (int attempt = 0; attempt < 2; ++attempt) {
      bool ok = true;
      for (const Es8311Reg& r : es8311::SHUTDOWN_SEQUENCE) {
        ok = writeReg(I2C_ADDR_ES8311, r.reg, r.value) && ok;
      }
      if (ok) {
        return;
      }
    }
  }

  void shutdownAdc() {
    for (int attempt = 0; attempt < 2; ++attempt) {
      bool ok = true;
      for (const Es7210Reg& r : es7210::SHUTDOWN_SEQUENCE) {
        ok = writeReg(I2C_ADDR_ES7210, r.reg, r.value) && ok;
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
  static bool writeReg(uint8_t address, uint8_t reg, uint8_t value) {
    Wire.beginTransmission(address);
    Wire.write(reg);
    Wire.write(value);
    return Wire.endTransmission() == 0;
  }

  I2SClass i2s_;
  bool bus_open_ = false;
};

#endif

} // namespace rlcd_panel
