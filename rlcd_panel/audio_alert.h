#pragma once

#include <math.h>
#include <stddef.h>
#include <stdint.h>

#include "board_pins.h"
#include "shtc3_sensor.h"
#include "switchbot_ble.h"

#if defined(ARDUINO) || defined(ESP_PLATFORM)
#include <Arduino.h>
#include <ESP_I2S.h>
#include <Wire.h>
#endif

namespace rlcd_panel {

// Alert kinds as bit mask. The mask is persisted in RTC memory (two bits).
constexpr uint8_t ALERT_NONE = 0;
constexpr uint8_t ALERT_FREEZER = 1;  // a sensor reads below the freezer limit
constexpr uint8_t ALERT_HUMIDITY = 2; // a sensor reads above the mold risk limit
constexpr uint8_t ALERT_MASK_ALL = ALERT_FREEZER | ALERT_HUMIDITY;

// Decoupled threshold evaluator. It has no hardware dependency, so the host tests build it.
// Each alert has a trigger limit and a release limit (hysteresis), so a reading at the limit
// does not chime on every wake cycle.
class AlertEvaluator {
public:
  static constexpr float FREEZER_TRIGGER_C = 0.0f;   // alert below this temperature
  static constexpr float FREEZER_RELEASE_C = 1.0f;   // clear at or above this temperature
  static constexpr float HUMIDITY_TRIGGER_PCT = 70.0f; // alert above this humidity (mold risk)
  static constexpr float HUMIDITY_RELEASE_PCT = 67.0f; // clear at or below this humidity

  // Returns the active alert mask. `previous` is the mask of the last wake cycle: an active
  // alert stays active until a usable sensor passed its release limit and no usable sensor
  // is above it; with no usable reading the alert holds. Invalid, stale and NaN readings are
  // ignored. One bit covers all sensors: a second sensor that enters the alert does not chime.
  static uint8_t evaluate(
      uint8_t previous,
      const Shtc3Reading& onboard,
      const SwitchBotReading* slots,
      size_t slot_count,
      uint32_t now_ms) {
    Tally tally;
    if (onboard.valid) {
      tally.add(onboard.temperature_c, onboard.humidity_pct);
    }
    for (size_t i = 0; slots != nullptr && i < slot_count; ++i) {
      const SwitchBotReading& r = slots[i];
      if (r.valid && !r.isStale(now_ms)) {
        tally.add(r.temperature_c, static_cast<float>(r.humidity_pct));
      }
    }
    uint8_t mask = ALERT_NONE;
    // Without a usable reading the latch holds: a sensor that drops out must not clear an alert,
    // or its return would chime again.
    const bool cold_hold = tally.cold_hold || !tally.have_temperature;
    const bool humid_hold = tally.humid_hold || !tally.have_humidity;
    if (tally.cold_trigger || ((previous & ALERT_FREEZER) != 0 && cold_hold)) {
      mask |= ALERT_FREEZER;
    }
    if (tally.humid_trigger || ((previous & ALERT_HUMIDITY) != 0 && humid_hold)) {
      mask |= ALERT_HUMIDITY;
    }
    return mask;
  }

  // Alerts that switched on since the last cycle and may sound. Mute suppresses the sound only;
  // the caller still stores the new mask, so unmuting never replays an old alert.
  static uint8_t chimeBits(bool muted, uint8_t previous, uint8_t current) {
    if (muted) {
      return ALERT_NONE;
    }
    return static_cast<uint8_t>(current & ~previous & ALERT_MASK_ALL);
  }

private:
  struct Tally {
    bool cold_trigger = false;
    bool cold_hold = false;
    bool humid_trigger = false;
    bool humid_hold = false;
    bool have_temperature = false;
    bool have_humidity = false;

    void add(float temperature_c, float humidity_pct) {
      have_temperature = have_temperature || !isnan(temperature_c);
      have_humidity = have_humidity || !isnan(humidity_pct);
      if (!isnan(temperature_c)) {
        cold_trigger = cold_trigger || temperature_c < FREEZER_TRIGGER_C;
        cold_hold = cold_hold || temperature_c < FREEZER_RELEASE_C;
      }
      if (!isnan(humidity_pct)) {
        humid_trigger = humid_trigger || humidity_pct > HUMIDITY_TRIGGER_PCT;
        humid_hold = humid_hold || humidity_pct > HUMIDITY_RELEASE_PCT;
      }
    }
  };
};

// One note of a chime. A frequency of 0 is a pause.
struct ChimeNote {
  uint16_t freq_hz;
  uint16_t duration_ms;
};

struct ChimePattern {
  const ChimeNote* notes;
  uint8_t count;
};

// Decoupled PCM synthesizer: 16-bit mono sine tones. Any sample is a pure function of its
// index, so the driver streams small chunks and needs no large buffer.
class ChimeSynth {
public:
  static constexpr uint32_t SAMPLE_RATE_HZ = 16000;
  static constexpr uint8_t DEFAULT_VOLUME_PCT = 30; // subtle by default
  static constexpr uint8_t MAX_VOLUME_PCT = 100;
  static constexpr uint32_t RAMP_MS = 5; // linear fade in and out against clicks
  static constexpr uint32_t SAMPLES_PER_MS = SAMPLE_RATE_HZ / 1000;

  // Freezer: three short high beeps. Humidity: two notes, rising.
  static ChimePattern pattern(uint8_t alert_bit) {
    static const ChimeNote FREEZER[] = {{880, 120}, {0, 80}, {880, 120}, {0, 80}, {880, 120}};
    static const ChimeNote HUMIDITY[] = {{660, 160}, {0, 40}, {880, 240}};
    switch (alert_bit) {
      case ALERT_FREEZER: return {FREEZER, sizeof(FREEZER) / sizeof(FREEZER[0])};
      case ALERT_HUMIDITY: return {HUMIDITY, sizeof(HUMIDITY) / sizeof(HUMIDITY[0])};
      default: return {nullptr, 0};
    }
  }

  // Short single tone that confirms unmuting. It differs from both alert chimes.
  static ChimePattern confirmPattern() {
    static const ChimeNote CONFIRM[] = {{1320, 80}};
    return {CONFIRM, 1};
  }

  static uint32_t totalSamples(const ChimePattern& p) {
    uint32_t total = 0;
    for (uint8_t i = 0; p.notes != nullptr && i < p.count; ++i) {
      total += static_cast<uint32_t>(p.notes[i].duration_ms) * SAMPLES_PER_MS;
    }
    return total;
  }

  // Writes up to `count` samples starting at sample index `start`. Returns the number written;
  // 0 at or after the end of the pattern. Volume is a percentage of full scale (clamped).
  static size_t render(
      const ChimePattern& p, uint8_t volume_pct, uint32_t start, int16_t* out, size_t count) {
    if (p.notes == nullptr || out == nullptr) {
      return 0;
    }
    const uint32_t volume = volume_pct > MAX_VOLUME_PCT ? MAX_VOLUME_PCT : volume_pct;
    const float amplitude = 32767.0f * static_cast<float>(volume) / 100.0f;
    uint32_t note_start = 0;
    uint8_t n = 0;
    size_t written = 0;
    uint32_t index = start;
    while (written < count && n < p.count) {
      const uint32_t note_len = static_cast<uint32_t>(p.notes[n].duration_ms) * SAMPLES_PER_MS;
      if (index >= note_start + note_len) {
        note_start += note_len;
        ++n;
        continue;
      }
      out[written++] = sample(p.notes[n], note_len, index - note_start, amplitude);
      ++index;
    }
    return written;
  }

private:
  static int16_t sample(const ChimeNote& note, uint32_t note_len, uint32_t local, float amplitude) {
    if (note.freq_hz == 0) {
      return 0;
    }
    uint32_t ramp = RAMP_MS * SAMPLES_PER_MS;
    if (ramp * 2 > note_len) {
      ramp = note_len / 2;
    }
    float envelope = 1.0f;
    if (ramp > 0 && local < ramp) {
      envelope = static_cast<float>(local) / static_cast<float>(ramp);
    } else if (ramp > 0 && note_len - 1 - local < ramp) {
      envelope = static_cast<float>(note_len - 1 - local) / static_cast<float>(ramp);
    }
    // Integer phase keeps the tone exact for long notes.
    const uint32_t phase = static_cast<uint32_t>(
        (static_cast<uint64_t>(note.freq_hz) * local) % SAMPLE_RATE_HZ);
    const float angle = 6.28318530718f * static_cast<float>(phase) / static_cast<float>(SAMPLE_RATE_HZ);
    return static_cast<int16_t>(lrintf(amplitude * envelope * sinf(angle)));
  }
};

// ES8311 register writes (reg, value). Derived from the Espressif reference driver for 16-bit
// I2S slave mode, MCLK = 256 x 16 kHz. The chip needs MCLK running before this sequence.
struct Es8311Reg {
  uint8_t reg;
  uint8_t value;
};

namespace es8311 {

constexpr Es8311Reg INIT_SEQUENCE[] = {
    {0x00, 0x1F}, // reset
    {0x00, 0x00},
    {0x00, 0x80}, // power on, slave mode
    {0x01, 0x3F}, // all clocks on, MCLK from pin
    {0x02, 0x00}, // pre-divider 1, multiplier 1
    {0x03, 0x10}, // ADC oversampling
    {0x04, 0x10}, // DAC oversampling
    {0x05, 0x00}, // ADC and DAC divider 1
    {0x06, 0x03}, // BCLK divider 4
    {0x07, 0x00}, // LRCK divider high
    {0x08, 0xFF}, // LRCK divider low (256)
    {0x09, 0x0C}, // I2S, 16-bit input
    {0x0A, 0x0C}, // I2S, 16-bit output
    {0x0D, 0x01}, // power up analog circuits
    {0x0E, 0x02}, // enable analog PGA and ADC modulator
    {0x12, 0x00}, // power up DAC
    {0x13, 0x10}, // enable output to headphone driver
    {0x1C, 0x6A}, // ADC equalizer bypass
    {0x37, 0x08}, // DAC equalizer bypass
    {0x31, 0x00}, // unmute DAC
    {0x32, 0xBF}, // DAC volume 0 dB
};

constexpr Es8311Reg SHUTDOWN_SEQUENCE[] = {
    {0x32, 0x00}, // DAC volume to minimum
    {0x31, 0x60}, // mute DAC
    {0x12, 0x02}, // power down DAC
    {0x0D, 0xFA}, // power down analog circuits
    {0x00, 0x00}, // power down state machine
};

} // namespace es8311

// Runs one playback against a hardware layer. The layer type provides:
//   bool beginBus(); bool configureCodec(); void setAmplifier(bool on);
//   bool write(const int16_t* samples, size_t count); void drain();
//   void shutdownCodec(); void endBus();
// The amplifier is on only while samples play and is off on every exit path, because a powered
// amplifier drains the battery in deep sleep. Returns true when the chime played.
class AudioSequencer {
public:
  static constexpr size_t CHUNK_SAMPLES = 256;

  template <typename Hal>
  static bool play(Hal& hal, uint8_t alert_bits, uint8_t volume_pct) {
    if ((alert_bits & ALERT_MASK_ALL) == 0) {
      return false;
    }
    ChimePattern patterns[2];
    uint8_t count = 0;
    if ((alert_bits & ALERT_FREEZER) != 0) patterns[count++] = ChimeSynth::pattern(ALERT_FREEZER);
    if ((alert_bits & ALERT_HUMIDITY) != 0) patterns[count++] = ChimeSynth::pattern(ALERT_HUMIDITY);
    return playPatterns(hal, patterns, count, volume_pct);
  }

  template <typename Hal>
  static bool playConfirm(Hal& hal, uint8_t volume_pct) {
    const ChimePattern pattern = ChimeSynth::confirmPattern();
    return playPatterns(hal, &pattern, 1, volume_pct);
  }

private:
  template <typename Hal>
  static bool playPatterns(Hal& hal, const ChimePattern* patterns, uint8_t count, uint8_t volume_pct) {
    if (volume_pct == 0) {
      return false;
    }
    bool played = false;
    if (hal.beginBus() && hal.configureCodec()) {
      hal.setAmplifier(true);
      played = true;
      for (uint8_t i = 0; i < count && played; ++i) {
        played = stream(hal, patterns[i], volume_pct);
      }
      hal.drain(); // let the queued samples play out before the codec shuts down
    }
    hal.setAmplifier(false);
    hal.shutdownCodec();
    hal.endBus();
    return played;
  }

  // Returns false when the hardware layer rejects a write.
  template <typename Hal>
  static bool stream(Hal& hal, const ChimePattern& pattern, uint8_t volume_pct) {
    int16_t chunk[CHUNK_SAMPLES];
    uint32_t pos = 0;
    for (;;) {
      const size_t n = ChimeSynth::render(pattern, volume_pct, pos, chunk, CHUNK_SAMPLES);
      if (n == 0) {
        return true;
      }
      if (!hal.write(chunk, n)) {
        return false;
      }
      pos += static_cast<uint32_t>(n);
    }
  }
};

#if defined(ARDUINO) || defined(ESP_PLATFORM)

// ESP32-S3 hardware layer: ES8311 over the shared I2C bus (already started), I2S std mode with
// MCLK output, and the PA_CTRL pin of the speaker amplifier.
class Es8311Hal {
public:
  bool beginBus() {
    i2s_.setPins(PIN_I2S_BCLK, PIN_I2S_LRCLK, PIN_I2S_DOUT, PIN_I2S_DIN, PIN_I2S_MCLK);
    bus_open_ = i2s_.begin(
        I2S_MODE_STD, ChimeSynth::SAMPLE_RATE_HZ, I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_MONO);
    return bus_open_;
  }

  bool configureCodec() {
    delay(5); // MCLK settles
    for (const Es8311Reg& r : es8311::INIT_SEQUENCE) {
      if (!writeReg(r)) {
        return false;
      }
    }
    delay(20); // DAC output settles before the amplifier turns on
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

  // The I2S driver holds a few DMA buffers; wait until they play out.
  void drain() {
    delay(100);
  }

  void shutdownCodec() {
    if (!bus_open_) {
      return;
    }
    for (const Es8311Reg& r : es8311::SHUTDOWN_SEQUENCE) {
      writeReg(r);
    }
  }

  void endBus() {
    if (bus_open_) {
      i2s_.end();
      bus_open_ = false;
    }
  }

private:
  static bool writeReg(const Es8311Reg& r) {
    Wire.beginTransmission(I2C_ADDR_ES8311);
    Wire.write(r.reg);
    Wire.write(r.value);
    return Wire.endTransmission() == 0;
  }

  I2SClass i2s_;
  bool bus_open_ = false;
};

#endif

} // namespace rlcd_panel
