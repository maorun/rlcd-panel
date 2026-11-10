# Features

This file describes finished work for `rlcd-panel`. Open work is in `ROADMAP.md`.

## Deep sleep power management with periodic wakeup

- **Code:** `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`.
- The board runs one wake cycle, then enters deep sleep: restore state, scan BLE for 4 s, read sensors, render, save state, sleep.
- The sleep timer starts the next scan 60 s after the start of the last scan. The timer subtracts the awake time and clamps to 1 s to 1 h.
- The registry, battery reading, view mode, and boot count stay in RTC memory (`RTC_DATA_ATTR`). A magic number, a version, and a CRC-32 protect them. Corrupt or blank data gives a clean start with an immediate scan.
- The `KEY` button (`GPIO18`) wakes the board through `ext0`. A press cycles the display mode without a new scan. The board stays awake for 3 s after a render, so more presses redraw at once.
- Timestamps use the RTC-backed system clock, because `millis()` restarts after deep sleep.
- Host tests cover the timer maths, the serialization round trip with corruption rejection, and the wakeup cause and state machine logic.
