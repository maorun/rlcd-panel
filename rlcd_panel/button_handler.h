#pragma once

#include <stdint.h>

namespace rlcd_panel {

// Debounced tracking of an active-low push button. The caller reads the pin (after
// pinMode(PIN_BTN_KEY, INPUT_PULLUP)) and passes the raw level, so this class has no Arduino
// dependency and runs in the host unit tests.
class ButtonDebouncer {
public:
  static constexpr uint32_t DEFAULT_DEBOUNCE_MS = 30;

  explicit ButtonDebouncer(uint32_t debounce_ms = DEFAULT_DEBOUNCE_MS)
      : debounce_ms_(debounce_ms) {}

  // Returns true exactly once per debounced press (released -> pressed edge).
  // A button already held at the first call seeds the state and raises no event.
  // Unsigned subtraction keeps the timing correct when millis() wraps around.
  bool update(bool raw_pressed, uint32_t now_ms) {
    if (!initialized_) {
      initialized_ = true;
      stable_pressed_ = raw_pressed;
      candidate_pressed_ = raw_pressed;
      candidate_since_ms_ = now_ms;
      return false;
    }

    if (raw_pressed != candidate_pressed_) {
      candidate_pressed_ = raw_pressed;
      candidate_since_ms_ = now_ms;
      return false;
    }

    if (candidate_pressed_ != stable_pressed_ && (now_ms - candidate_since_ms_) >= debounce_ms_) {
      stable_pressed_ = candidate_pressed_;
      return stable_pressed_;
    }
    return false;
  }

  bool pressed() const {
    return stable_pressed_;
  }

private:
  uint32_t debounce_ms_;
  bool initialized_ = false;
  bool stable_pressed_ = false;
  bool candidate_pressed_ = false;
  uint32_t candidate_since_ms_ = 0;
};

enum class DisplayMode : uint8_t {
  DASHBOARD = 0,
  SENSOR_STATS = 1,
  SYSTEM_DIAGNOSTICS = 2,
};

constexpr uint8_t DISPLAY_MODE_COUNT = 3;

// Display mode state machine: every debounced press cycles DASHBOARD -> SENSOR_STATS ->
// SYSTEM_DIAGNOSTICS -> DASHBOARD.
class DisplayModeController {
public:
  DisplayMode mode() const {
    return mode_;
  }

  void setMode(DisplayMode mode) {
    if (static_cast<uint8_t>(mode) < DISPLAY_MODE_COUNT) {
      mode_ = mode;
    }
  }

  void next() {
    mode_ = static_cast<DisplayMode>((static_cast<uint8_t>(mode_) + 1) % DISPLAY_MODE_COUNT);
  }

  // Feeds one raw sample (true = pin LOW). Returns true when the mode changed.
  bool update(bool raw_pressed, uint32_t now_ms) {
    if (!button_.update(raw_pressed, now_ms)) {
      return false;
    }
    next();
    return true;
  }

private:
  ButtonDebouncer button_;
  DisplayMode mode_ = DisplayMode::DASHBOARD;
};

} // namespace rlcd_panel
