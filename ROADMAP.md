# Roadmap

This file lists open work for `rlcd-panel`, the standalone Arduino CLI firmware for the **Waveshare ESP32-S3-RLCD-4.2** development board, and its companion apps (`macos/RlcdCompanion` for macOS, `wearos/RlcdWatch` for Wear OS / Galaxy Watch Ultra 2 and `android/RlcdPhone` for Android smartphones).
The system renders climate telemetry from onboard sensors and external SwitchBot thermo-hygrometers onto a 4.2-inch reflective LCD and broadcasts BLE telemetry to the companion apps.
The list uses priorities. Priority 1 is the highest priority.
Do the work in priority order, unless the maintainer gives a different order.

## Rules for this file

- Write in ASD-STE100 Simplified Technical English.
- Include work across all system targets: the panel firmware (`rlcd_panel/`), the macOS companion app (`macos/RlcdCompanion/`), the Wear OS companion app (`wearos/RlcdWatch/`), and the Android phone companion app (`android/RlcdPhone/`). Do not restrict roadmap planning to the panel firmware alone.
- Add one entry for each open item. Do not give an entry a status. Each entry in this file is open.
- Remove an entry in the pull request that completes it. Describe finished work in `FEATURES.md`.
- Each entry names the code location, the goal, and the acceptance test.
- Each entry has a `Rank` line with the model, the total score, the four single scores, and the effort size. See "Rules for ranking".
- Put each entry in the priority that matches its total score. Order the entries inside a priority by total score, highest first. For equal scores, put the smaller effort first. Put a dependency above the entry that needs it.
- Priority 1 holds the most critical user-facing enhancements and core hardware integrations.
- Do not record review findings in this file. Name them in the review report and in the pull request description.

## Priority 1: Sensor Setup and Calibration (Score 34–40)

## Priority 2: Configuration and Control (Score 28–33)



## Priority 3: Display and Logging (Score 22–27)

- [ ] Daily mold hours in the seven-day history
  - Rank: [Total: 25, Use: 6, Setup: 3, Harmony: 8, Safety: 8, Effort: M]
  - Location: rlcd_panel/sparkline.h, rlcd_panel/power_manager.h, rlcd_panel/serial_console.h, tests/test_firmware_logic.cpp, tests/fixtures/week_reply_sample.txt, macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/WeekReply.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/WeekReply.kt, AGENTS.md
  - Goal: The daily file holds the mold hours of each day, but the `WEEK` reply has no mold value. Without a card, the user cannot see on which days a room was humid for a long time. Add one byte for each day and sensor to `WeekHistory`: the hours above `mold_alert_limit_rh` from `HumidityHistory::countAbove`, 0 to 24, `0xFF` for a gap day. `WeekHistory::advance` stores it with the minimum and maximum. Add the bytes to `PersistentState::week` and raise `STATE_VERSION` by 1. Reject a value from 25 to 254 and a value beside a gap pair. `WeekHistory::restore` sets the hours from the `mold_h_<N>` columns of the daily file, or `0xFF` for an empty field. Add the column `mold_h` to the `WEEK` reply (empty for a gap day). The macOS and Kotlin `WeekReply` parsers accept 7 or 8 columns and give `moldHours` as `nil` or `null` for 7 columns and for an empty field.
  - Acceptance test: In `tests/test_firmware_logic.cpp`, assert the stored hours after `advance`, the gap marker for a sensor without samples, the rejection of a stored 25, the `restore` value from a daily row, the `WEEK` header with `mold_h`, and the RTC round trip with the new `STATE_VERSION`. Add the column to `tests/fixtures/week_reply_sample.txt` and calculate the `CRC32` line again. In the macOS and Kotlin `WeekReply` tests, assert `moldHours` for 8 columns and `nil` or `null` for 7 columns. Run `c++ -std=c++17 -Wall -Wextra -Werror tests/test_firmware_logic.cpp -o test_runner && ./test_runner && rm -f test_runner`, `arduino-cli compile rlcd_panel`, `swift test` in `macos/RlcdCompanion`, `gradle testDebugUnitTest` in `android/RlcdPhone` and `wearos/RlcdWatch`, and `./scripts/check-protocol-sync.sh`.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
