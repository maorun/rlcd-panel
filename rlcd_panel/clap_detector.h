#pragma once

#include <math.h>
#include <stddef.h>
#include <stdint.h>

namespace rlcd_panel {

// Where the board listens for a double clap. A sound cannot wake the board from deep sleep (the
// ES7210 has no interrupt output and deep sleep cannot sample I2S), so the board listens only
// while it is awake.
enum class ClapListenMode : uint8_t {
  OFF,        // the microphones stay off
  GRACE,      // listen in the KEY grace window only (battery power)
  CONTINUOUS, // listen until the next scan is due (USB power)
};

// Detects a double clap in a stream of level values. The caller gives one level in dBFS for each
// step (1 ms) together with the time of the step. All times are in ms. Unsigned subtraction keeps
// the timing correct when the time wraps around.
//
// A clap is a short energy transient:
//   - the level rises from the noise floor to the floor + RISE_DB in less than RISE_MAX_MS,
//   - the level is at least MIN_PEAK_DB,
//   - the level falls below the floor + DECAY_DB in less than DECAY_MAX_MS.
// A slow rise (speech) and a sound that does not decay (continuous noise) are not claps. The
// noise floor follows the level slowly, and stands still while a transient runs.
//
// Two claps are a double clap when their onsets are MIN_GAP_MS to MAX_GAP_MS apart. A clap that
// follows its predecessor too fast is an echo and is ignored. A third clap within TRIPLE_WINDOW_MS
// of the second clap is applause: the detector discards all three claps and ignores claps for
// LOCKOUT_MS. The double clap is reported TRIPLE_WINDOW_MS after the second clap. After a report,
// the detector ignores claps for LOCKOUT_MS.
class ClapDetector {
public:
  static constexpr int RISE_DB = 20;
  static constexpr int DECAY_DB = 10;
  static constexpr int QUIET_MARGIN_DB = 6;
  static constexpr int MIN_PEAK_DB = -40;
  static constexpr uint32_t RISE_MAX_MS = 5;
  static constexpr uint32_t DECAY_MAX_MS = 100;
  static constexpr uint32_t MIN_GAP_MS = 150;
  static constexpr uint32_t MAX_GAP_MS = 600;
  static constexpr uint32_t TRIPLE_WINDOW_MS = 400;
  static constexpr uint32_t LOCKOUT_MS = 1000;
  static constexpr float FLOOR_TAU_MS = 500.0f;
  static constexpr float WAIT_TAU_MS = 1000.0f;

  void reset() {
    *this = ClapDetector{};
  }

  // Feeds one level. Returns true once per double clap.
  bool update(int level_db, uint32_t now_ms) {
    const float level = static_cast<float>(level_db);
    if (!started_) {
      started_ = true;
      floor_db_ = level;
      base_ms_ = now_ms;
      last_ms_ = now_ms;
    }
    const uint32_t dt = now_ms - last_ms_;
    last_ms_ = now_ms;

    bool clap = false;
    switch (state_) {
      case State::IDLE:
        adaptFloor(level, dt, FLOOR_TAU_MS);
        if (level < floor_db_ + static_cast<float>(QUIET_MARGIN_DB)) {
          base_ms_ = now_ms;
        } else if (level >= floor_db_ + static_cast<float>(RISE_DB) &&
                   level >= static_cast<float>(MIN_PEAK_DB)) {
          if (now_ms - base_ms_ < RISE_MAX_MS) {
            state_ = State::PEAK;
            onset_ms_ = now_ms;
          } else {
            state_ = State::WAIT_QUIET;
          }
        }
        break;
      case State::PEAK:
        if (level < floor_db_ + static_cast<float>(DECAY_DB)) {
          clap = true;
          state_ = State::IDLE;
          base_ms_ = now_ms;
        } else if (now_ms - onset_ms_ >= DECAY_MAX_MS) {
          state_ = State::WAIT_QUIET;
        }
        break;
      case State::WAIT_QUIET:
        adaptFloor(level, dt, WAIT_TAU_MS);
        if (level < floor_db_ + static_cast<float>(DECAY_DB)) {
          state_ = State::IDLE;
          base_ms_ = now_ms;
        }
        break;
    }

    if (clap) {
      onClap(onset_ms_, now_ms);
    }
    return pollTimers(now_ms);
  }

  // Noise floor in dBFS, for tests and diagnostics.
  float noiseFloorDb() const {
    return floor_db_;
  }

private:
  enum class State : uint8_t { IDLE, PEAK, WAIT_QUIET };

  void adaptFloor(float level, uint32_t dt, float tau_ms) {
    float a = static_cast<float>(dt) / tau_ms;
    if (a > 1.0f) {
      a = 1.0f;
    }
    floor_db_ += (level - floor_db_) * a;
  }

  bool lockedOut(uint32_t now_ms) const {
    return locked_ && static_cast<int32_t>(lockout_until_ms_ - now_ms) > 0;
  }

  void lockOut(uint32_t now_ms) {
    locked_ = true;
    lockout_until_ms_ = now_ms + LOCKOUT_MS;
  }

  // `onset` is the time of the rise, `now_ms` the time the decay confirmed the clap.
  void onClap(uint32_t onset, uint32_t now_ms) {
    if (lockedOut(now_ms)) {
      return;
    }
    if (pending_) {
      if (onset - second_ms_ < MIN_GAP_MS) {
        return; // echo of the second clap
      }
      pending_ = false; // third clap: applause
      first_ = false;
      lockOut(now_ms);
      return;
    }
    if (first_) {
      const uint32_t gap = onset - first_ms_;
      if (gap < MIN_GAP_MS) {
        return; // echo of the first clap
      }
      if (gap <= MAX_GAP_MS) {
        pending_ = true;
        second_ms_ = onset;
        return;
      }
    }
    first_ = true;
    first_ms_ = onset;
  }

  bool pollTimers(uint32_t now_ms) {
    if (pending_) {
      // A running transient may be a third clap: wait for its decay before the report.
      if (now_ms - second_ms_ >= TRIPLE_WINDOW_MS && state_ != State::PEAK) {
        pending_ = false;
        first_ = false;
        lockOut(now_ms);
        return true;
      }
    } else if (first_ && now_ms - first_ms_ > MAX_GAP_MS + DECAY_MAX_MS) {
      // The second clap is confirmed after its decay, so the first clap waits that long more.
      first_ = false;
    }
    return false;
  }

  State state_ = State::IDLE;
  bool started_ = false;
  float floor_db_ = -96.0f;
  uint32_t last_ms_ = 0;
  uint32_t base_ms_ = 0;  // last time the level was within QUIET_MARGIN_DB of the floor
  uint32_t onset_ms_ = 0; // time the level crossed floor + RISE_DB
  bool first_ = false;
  uint32_t first_ms_ = 0;
  bool pending_ = false; // second clap seen, waiting out the triple window
  uint32_t second_ms_ = 0;
  bool locked_ = false;
  uint32_t lockout_until_ms_ = 0;
};

// Listens for a double clap on a microphone hardware layer. The layer type is the one that
// NoiseSampler uses:
//   bool beginBus(); bool configureAdc(); size_t read(int16_t* samples, size_t count);
//   void shutdownAdc(); void endBus();
// poll() reads one 20 ms frame (the read blocks, so the CPU idles between frames), reduces it to
// the peak level of each millisecond, and feeds the detector. The time base is the audio time, so
// it does not depend on the system clock. The first poll() opens the bus and configures the ADC;
// the ADC settles for SETTLE_MS before the detector sees a level. release() and stop() power the
// ADC down and close the bus. After release() the next poll() opens the microphone again. After
// stop() or after a hardware failure, poll() does nothing. The destructor stops the listener.
template <typename Hal>
class ClapListener {
public:
  static constexpr uint32_t SAMPLE_RATE_HZ = 16000;
  static constexpr size_t CHANNELS = 2;
  static constexpr size_t FRAME_MS = 20;
  static constexpr size_t FRAMES_PER_MS = SAMPLE_RATE_HZ / 1000;
  static constexpr size_t FRAME_SAMPLES = FRAME_MS * FRAMES_PER_MS * CHANNELS;
  static constexpr uint32_t SETTLE_MS = 100;

  explicit ClapListener(Hal& hal) : hal_(hal) {}
  ClapListener(const ClapListener&) = delete;
  ClapListener& operator=(const ClapListener&) = delete;
  ~ClapListener() {
    stop();
  }

  // Returns true when a double clap ends in this frame.
  bool poll() {
    if (stopped_) {
      return false;
    }
    if (!open_ && !open()) {
      stop();
      return false;
    }
    int16_t frame[FRAME_SAMPLES];
    const size_t n = hal_.read(frame, FRAME_SAMPLES);
    if (n == 0 || n > FRAME_SAMPLES) {
      stop(); // a failed read: switch the microphone off for good
      return false;
    }
    bool clap = false;
    for (size_t i = 0; i + 1 < n; i += CHANNELS) {
      const int a = abs16(frame[i]);
      const int b = abs16(frame[i + 1]);
      const int m = a > b ? a : b;
      if (m > peak_) {
        peak_ = m;
      }
      if (++frames_in_ms_ == FRAMES_PER_MS) {
        clap = endMillisecond() || clap;
      }
    }
    return clap;
  }

  // True while the microphone is powered.
  bool listening() const {
    return open_;
  }

  // False after stop() or a hardware failure: the listener does nothing any more.
  bool available() const {
    return !stopped_;
  }

  // Powers the microphone down. The next poll() opens it again.
  void release() {
    if (touched_) {
      hal_.shutdownAdc(); // also when the bus never opened: the chip may still be powered
      hal_.endBus();
      touched_ = false;
    }
    open_ = false;
  }

  // Powers the microphone down for good.
  void stop() {
    release();
    stopped_ = true;
  }

private:
  static int abs16(int16_t v) {
    return v < 0 ? -static_cast<int>(v) : static_cast<int>(v);
  }

  bool open() {
    touched_ = true;
    if (!hal_.beginBus() || !hal_.configureAdc()) {
      return false;
    }
    open_ = true;
    detector_.reset();
    audio_ms_ = 0;
    frames_in_ms_ = 0;
    peak_ = 0;
    return true;
  }

  bool endMillisecond() {
    const int peak = peak_;
    peak_ = 0;
    frames_in_ms_ = 0;
    const uint32_t now = audio_ms_++;
    if (now < SETTLE_MS) {
      return false;
    }
    int db = -96;
    if (peak > 0) {
      db = static_cast<int>(lroundf(20.0f * log10f(static_cast<float>(peak) / 32768.0f)));
      db = db < -96 ? -96 : (db > 0 ? 0 : db);
    }
    return detector_.update(db, now);
  }

  Hal& hal_;
  ClapDetector detector_;
  bool open_ = false;
  bool touched_ = false; // the hardware may be powered
  bool stopped_ = false;
  uint32_t audio_ms_ = 0;
  size_t frames_in_ms_ = 0;
  int peak_ = 0;
};

} // namespace rlcd_panel
