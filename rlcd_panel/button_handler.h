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

// Tells a short press from a long press. begin() starts the timing at a debounced press edge.
// update() gets the debounced level: LONG fires once when the button has been down for
// LONG_PRESS_MS, SHORT fires on a release before that limit. After LONG the release gives NONE.
class LongPressDetector {
public:
  enum class Result : uint8_t { NONE, SHORT, LONG };

  static constexpr uint32_t LONG_PRESS_MS = 2000;

  void begin(uint32_t now_ms) {
    active_ = true;
    since_ms_ = now_ms;
  }

  bool active() const {
    return active_;
  }

  void cancel() {
    active_ = false;
  }

  // Unsigned subtraction keeps the timing correct when millis() wraps around.
  Result update(bool pressed, uint32_t now_ms) {
    if (!active_) {
      return Result::NONE;
    }
    if (!pressed) {
      active_ = false;
      return Result::SHORT;
    }
    if ((now_ms - since_ms_) >= LONG_PRESS_MS) {
      active_ = false;
      return Result::LONG;
    }
    return Result::NONE;
  }

private:
  bool active_ = false;
  uint32_t since_ms_ = 0;
};

enum class BootEvent : uint8_t {
  NONE,
  TOGGLE_MUTE, // short press: mute flag toggles
  LOOPBACK,    // long press in the diagnostics view: speaker to microphone test
};

// BOOT button: a short press toggles the mute flag, a long press in the diagnostics view starts
// the loopback test. Outside the diagnostics view every press toggles at once. In the diagnostics
// view the toggle waits for the release, so a long press never toggles the mute flag. A button that
// is down at the first call raises no event.
class BootButtonController {
public:
  BootEvent update(bool raw_pressed, uint32_t now_ms, bool diagnostics_view) {
    const bool edge = button_.update(raw_pressed, now_ms);
    if (edge) {
      if (!diagnostics_view) {
        return BootEvent::TOGGLE_MUTE;
      }
      long_press_.begin(now_ms);
    }
    switch (long_press_.update(button_.pressed(), now_ms)) {
      case LongPressDetector::Result::SHORT: return BootEvent::TOGGLE_MUTE;
      case LongPressDetector::Result::LONG: return BootEvent::LOOPBACK;
      case LongPressDetector::Result::NONE:
      default: return BootEvent::NONE;
    }
  }

  // True while a press waits for its release or the long press limit.
  bool pressPending() const { return long_press_.active(); }

  // Drops a pending press, for example after a test that blocked the loop.
  void cancelPress() { long_press_.cancel(); }

private:
  ButtonDebouncer button_;
  LongPressDetector long_press_;
};

enum class ButtonEvent : uint8_t {
  NONE,
  MODE_CHANGED,
  CALIBRATE, // long press in the diagnostics view; the mode stays
  RECAPTURE, // short press in the spectrum view; the mode stays
  NEXT_SENSOR, // short press in the climate chart view; the mode stays
};

enum class DisplayMode : uint8_t {
  DASHBOARD = 0,
  SENSOR_STATS = 1,
  SYSTEM_DIAGNOSTICS = 2,
  AUDIO_SPECTRUM = 3,
  CLIMATE_CHART = 4,
};

constexpr uint8_t DISPLAY_MODE_COUNT = 5;

// Number of sensors the climate chart view selects from: onboard + 3 SwitchBot slots.
constexpr uint8_t CHART_SENSOR_COUNT = 4;

// Display mode state machine: every debounced press cycles DASHBOARD -> SENSOR_STATS ->
// SYSTEM_DIAGNOSTICS -> AUDIO_SPECTRUM -> CLIMATE_CHART -> DASHBOARD.
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

  // Sensor shown by the climate chart view: 0 is the onboard sensor, 1 to 3 the registry slots.
  uint8_t chartSensor() const {
    return chart_sensor_;
  }

  // Ignores an index outside 0..CHART_SENSOR_COUNT - 1.
  void setChartSensor(uint8_t sensor) {
    if (sensor < CHART_SENSOR_COUNT) {
      chart_sensor_ = sensor;
    }
  }

  // Selects the next sensor and wraps from the last one to the onboard sensor.
  void nextChartSensor() {
    chart_sensor_ = static_cast<uint8_t>((chart_sensor_ + 1) % CHART_SENSOR_COUNT);
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

  // Like update(), with a long press in the diagnostics view. There a press does not change the
  // mode at once: a release before 2 s changes it (short press), a hold of 2 s or more returns
  // CALIBRATE and keeps the mode. In the other views every press changes the mode at once. A
  // button that is already down at the first call raises no event, so the press that woke the
  // board cannot start a calibration. In the spectrum view a short press returns RECAPTURE and
  // keeps the mode, and a hold of 2 s or more changes the mode. The press that woke the board
  // changes the mode through next(), so it never starts a capture. The climate chart view works
  // the same way: a short press selects the next sensor (NEXT_SENSOR) and keeps the mode.
  ButtonEvent updateWithLongPress(bool raw_pressed, uint32_t now_ms) {
    const bool edge = button_.update(raw_pressed, now_ms);
    if (edge) {
      if (mode_ != DisplayMode::SYSTEM_DIAGNOSTICS && mode_ != DisplayMode::AUDIO_SPECTRUM &&
          mode_ != DisplayMode::CLIMATE_CHART) {
        next();
        return ButtonEvent::MODE_CHANGED;
      }
      long_press_.begin(now_ms);
    }
    switch (long_press_.update(button_.pressed(), now_ms)) {
      case LongPressDetector::Result::SHORT:
        if (mode_ == DisplayMode::AUDIO_SPECTRUM) {
          return ButtonEvent::RECAPTURE;
        }
        if (mode_ == DisplayMode::CLIMATE_CHART) {
          nextChartSensor();
          return ButtonEvent::NEXT_SENSOR;
        }
        next();
        return ButtonEvent::MODE_CHANGED;
      case LongPressDetector::Result::LONG:
        if (mode_ == DisplayMode::AUDIO_SPECTRUM || mode_ == DisplayMode::CLIMATE_CHART) {
          next();
          return ButtonEvent::MODE_CHANGED;
        }
        return ButtonEvent::CALIBRATE;
      case LongPressDetector::Result::NONE:
      default:
        return ButtonEvent::NONE;
    }
  }

  // True while a press waits for its release or the long press limit. The caller keeps the board
  // awake meanwhile.
  bool pressPending() const {
    return long_press_.active();
  }

  // Drops a pending press, for example after a calibration that blocked the loop.
  void cancelPress() {
    long_press_.cancel();
  }

private:
  ButtonDebouncer button_;
  LongPressDetector long_press_;
  DisplayMode mode_ = DisplayMode::DASHBOARD;
  uint8_t chart_sensor_ = 0;
};

} // namespace rlcd_panel
