#pragma once

#include <math.h>
#include <stddef.h>
#include <stdint.h>

#include "es7210_mic.h"

namespace rlcd_panel {

// Result of one spectrum capture: 32 logarithmic bands from 100 Hz to 8 kHz. A bar is the mean
// level of a band over the capture. A peak is the highest frame level of the band. Both values
// run from 0 (at or below -80 dBFS) to 100 (full scale). An invalid reading holds no data.
struct SpectrumReading {
  static constexpr size_t BANDS = 32;
  static constexpr uint8_t LEVEL_MAX = 100;

  bool valid = false;
  uint8_t bars[BANDS] = {};
  uint8_t peaks[BANDS] = {};

  // Index of the highest bar, or -1 when the reading is invalid or all bars are zero. The first
  // band wins a tie.
  int loudestBand() const {
    int best = -1;
    uint8_t best_level = 0;
    if (!valid) {
      return best;
    }
    for (size_t b = 0; b < BANDS; ++b) {
      if (bars[b] > best_level) {
        best_level = bars[b];
        best = static_cast<int>(b);
      }
    }
    return best;
  }
};

// Spectrum analysis of a mono audio stream. The caller feeds interleaved stereo samples. The
// class mixes them to mono and cuts them into frames of 512 samples without overlap. Each frame
// gets a Hann window and a radix-2 FFT. The analyzer holds about 7 KB of float buffers, so keep
// one instance in static memory and not on the task stack of the ESP32.
class SpectrumAnalyzer {
public:
  static constexpr size_t FFT_SIZE = 512;
  static constexpr size_t BINS = FFT_SIZE / 2 + 1; // bin 0 to the Nyquist bin
  static constexpr size_t BANDS = SpectrumReading::BANDS;
  static constexpr uint32_t SAMPLE_RATE_HZ = NoiseSampler::SAMPLE_RATE_HZ;
  static constexpr float MIN_HZ = 100.0f;
  static constexpr float MAX_HZ = 8000.0f;
  static constexpr float FLOOR_DB = -80.0f; // a band at or below this level has the bar level 0

  SpectrumAnalyzer() {
    reset();
  }

  // Lower edge of band `index` in Hz. Index 32 gives the upper edge of the last band (8 kHz).
  // The edges grow by a constant factor, so every band has the same width on a log axis.
  static float bandEdgeHz(size_t index) {
    return MIN_HZ * powf(MAX_HZ / MIN_HZ, static_cast<float>(index) / static_cast<float>(BANDS));
  }

  // Geometric center of a band in Hz.
  static float bandCenterHz(size_t index) {
    return sqrtf(bandEdgeHz(index) * bandEdgeHz(index + 1));
  }

  // Band that holds a frequency, or -1 below 100 Hz and from 8 kHz up.
  static int bandForFrequency(float hz) {
    if (!(hz >= bandEdgeHz(0)) || hz >= bandEdgeHz(BANDS)) {
      return -1;
    }
    for (size_t b = 0; b < BANDS; ++b) {
      if (hz < bandEdgeHz(b + 1)) {
        return static_cast<int>(b);
      }
    }
    return -1;
  }

  // In-place radix-2 FFT of 512 points (decimation in time).
  static void fft(float* re, float* im) {
    for (size_t i = 1, j = 0; i < FFT_SIZE; ++i) {
      size_t bit = FFT_SIZE >> 1;
      for (; j & bit; bit >>= 1) {
        j ^= bit;
      }
      j ^= bit;
      if (i < j) {
        const float tr = re[i];
        const float ti = im[i];
        re[i] = re[j];
        im[i] = im[j];
        re[j] = tr;
        im[j] = ti;
      }
    }
    for (size_t len = 2; len <= FFT_SIZE; len <<= 1) {
      const size_t half = len / 2;
      for (size_t k = 0; k < half; ++k) {
        const float angle = -2.0f * 3.14159265358979f * static_cast<float>(k) / static_cast<float>(len);
        const float wr = cosf(angle);
        const float wi = sinf(angle);
        for (size_t start = k; start < FFT_SIZE; start += len) {
          const size_t a = start;
          const size_t b = start + half;
          const float xr = re[b] * wr - im[b] * wi;
          const float xi = re[b] * wi + im[b] * wr;
          re[b] = re[a] - xr;
          im[b] = im[a] - xi;
          re[a] += xr;
          im[a] += xi;
        }
      }
    }
  }

  // Maps a normalized magnitude (1.0 = full scale sine) to a bar level from 0 to 100.
  static uint8_t levelFromMagnitude(float magnitude) {
    if (!(magnitude > 0.0f)) {
      return 0;
    }
    const float db = 20.0f * log10f(magnitude);
    if (db <= FLOOR_DB) {
      return 0;
    }
    if (db >= 0.0f) {
      return SpectrumReading::LEVEL_MAX;
    }
    return static_cast<uint8_t>(lroundf((db - FLOOR_DB) * SpectrumReading::LEVEL_MAX / -FLOOR_DB));
  }

  void reset() {
    fill_ = 0;
    frames_ = 0;
    for (size_t b = 0; b < BANDS; ++b) {
      sum_[b] = 0.0f;
      max_[b] = 0.0f;
    }
  }

  // Adds interleaved stereo samples (microphone 1, microphone 2). An odd last sample is ignored.
  void addSamples(const int16_t* samples, size_t count) {
    if (samples == nullptr) {
      return;
    }
    for (size_t i = 0; i + 1 < count; i += 2) {
      const float mono =
          (static_cast<float>(samples[i]) + static_cast<float>(samples[i + 1])) * 0.5f / 32768.0f;
      frame_[fill_++] = mono;
      if (fill_ == FFT_SIZE) {
        processFrame();
        fill_ = 0;
      }
    }
  }

  size_t frameCount() const {
    return frames_;
  }

  // Reading of the frames so far. Without a full frame the reading is invalid.
  SpectrumReading result() const {
    SpectrumReading r;
    if (frames_ == 0) {
      return r;
    }
    r.valid = true;
    for (size_t b = 0; b < BANDS; ++b) {
      r.bars[b] = levelFromMagnitude(sum_[b] / static_cast<float>(frames_));
      r.peaks[b] = levelFromMagnitude(max_[b]);
    }
    return r;
  }

private:
  // One frame: window, FFT, magnitudes, then the band values.
  void processFrame() {
    for (size_t n = 0; n < FFT_SIZE; ++n) {
      const float hann =
          0.5f * (1.0f - cosf(2.0f * 3.14159265358979f * static_cast<float>(n) / static_cast<float>(FFT_SIZE)));
      re_[n] = frame_[n] * hann;
      im_[n] = 0.0f;
    }
    fft(re_, im_);
    // A full scale sine in the middle of a bin gives A * N / 4 after the Hann window.
    const float norm = 4.0f / static_cast<float>(FFT_SIZE);
    for (size_t k = 0; k < BINS; ++k) {
      mag_[k] = sqrtf(re_[k] * re_[k] + im_[k] * im_[k]) * norm;
    }
    const float bin_hz = static_cast<float>(SAMPLE_RATE_HZ) / static_cast<float>(FFT_SIZE);
    for (size_t b = 0; b < BANDS; ++b) {
      const float lo = bandEdgeHz(b);
      const float hi = bandEdgeHz(b + 1);
      // A band with bins takes the highest bin. This reads a little higher than the interpolation
      // of a narrow band below, so a flat noise floor tilts slightly toward the high bands.
      float level = 0.0f;
      bool have_bin = false;
      for (size_t k = 0; k < BINS; ++k) {
        const float f = static_cast<float>(k) * bin_hz;
        if (f >= lo && f < hi) {
          level = (!have_bin || mag_[k] > level) ? mag_[k] : level;
          have_bin = true;
        }
      }
      if (!have_bin) {
        // A band narrower than one bin holds no bin center: interpolate at the band center.
        const float pos = bandCenterHz(b) / bin_hz;
        size_t k = static_cast<size_t>(pos);
        if (k >= BINS - 1) {
          k = BINS - 2;
        }
        const float frac = pos - static_cast<float>(k);
        level = mag_[k] * (1.0f - frac) + mag_[k + 1] * frac;
      }
      sum_[b] += level;
      if (level > max_[b]) {
        max_[b] = level;
      }
    }
    ++frames_;
  }

  float frame_[FFT_SIZE];
  float re_[FFT_SIZE];
  float im_[FFT_SIZE];
  float mag_[BINS];
  float sum_[BANDS];
  float max_[BANDS];
  size_t fill_ = 0;
  size_t frames_ = 0;
};

// Captures one window against a hardware layer and returns the spectrum. The layer has the same
// interface as for NoiseSampler (see es7210_mic.h). The ADC is powered down on every exit path.
// The caller starts a capture only on entry to the spectrum view and on a KEY press there, so
// the microphones stay off on timer wakeups.
class SpectrumCapture {
public:
  static constexpr uint32_t WINDOW_MS = 1000;

  // Returns an invalid reading when the bus or the ADC fails, when a read stops before the
  // window ends, or when the window holds no full frame.
  template <typename Hal>
  static SpectrumReading measure(Hal& hal, SpectrumAnalyzer& analyzer, uint32_t window_ms = WINDOW_MS) {
    SpectrumReading result;
    if (hal.beginBus() && hal.configureAdc()) {
      analyzer.reset();
      if (collect(hal, analyzer, window_ms)) {
        result = analyzer.result();
      }
    }
    hal.shutdownAdc();
    hal.endBus();
    return result;
  }

private:
  template <typename Hal>
  static bool collect(Hal& hal, SpectrumAnalyzer& analyzer, uint32_t window_ms) {
    int16_t chunk[NoiseSampler::CHUNK_SAMPLES];
    const uint64_t settle = static_cast<uint64_t>(NoiseSampler::SETTLE_FRAMES) * NoiseSampler::CHANNELS;
    const uint64_t wanted =
        static_cast<uint64_t>(NoiseSampler::SAMPLE_RATE_HZ) * window_ms / 1000 * NoiseSampler::CHANNELS;
    if (wanted == 0) {
      return false;
    }
    uint64_t seen = 0;
    while (seen < settle + wanted) {
      const size_t n = hal.read(chunk, NoiseSampler::CHUNK_SAMPLES);
      if (n == 0 || n > NoiseSampler::CHUNK_SAMPLES) {
        return false;
      }
      // Skip the settling samples, then count toward the window.
      size_t skip = 0;
      if (seen < settle) {
        skip = static_cast<size_t>(settle - seen < n ? settle - seen : n);
      }
      // Keep the stereo pairs together: an odd skip would swap the channels.
      skip &= ~static_cast<size_t>(1);
      analyzer.addSamples(chunk + skip, n - skip);
      seen += n;
    }
    return true;
  }
};

} // namespace rlcd_panel
