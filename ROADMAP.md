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

- [ ] Event code for an onboard sensor fault
  - Rank: [Total: 31, Use: 5, Setup: 8, Harmony: 9, Safety: 9, Effort: M]
  - Location: rlcd_panel/shtc3_sensor.h, rlcd_panel/sd_logger.h, rlcd_panel/rlcd_panel.ino, tests/test_firmware_logic.cpp, tests/fixtures/events_reply_sample.txt, macos/RlcdCompanion/Sources/RlcdProtocol/EventText.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/EventTextTests.swift, macos/RlcdCompanion/Sources/RlcdProtocol/EventFilter.swift, macos/RlcdCompanion/Tests/RlcdProtocolTests/EventFilterTests.swift, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/EventText.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/EventTextTest.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/EventsReplyTest.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/EventCsvTest.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/EventText.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/EventTextTest.kt, AGENTS.md
  - Goal: `Shtc3Health` counts the failed `SHTC3` reads, and the diagnostics view inverts `SHTC3 ERR` from 3 failures in a row. The event log does not show when the fault started or ended. Add `EventCode::SHTC3_FAULT` 18 and set `EventRing::MAX_CODE` to 18. Add `Shtc3Health::faultEdge(previous_run, result)`: it gives a fault edge when `run` goes from 2 to 3 and a recovery edge when `run` goes from 3 or more to 0. Record one event for each edge in `refreshSensors()`. Set the detail low byte to 1 for a fault and 0 for a recovery. Set the high byte to the `Shtc3Result` of the read that caused the fault, and to 0 for a recovery. Record nothing for a run of 1 or 2, and nothing at a power-on. Do not change the RTC layout or `STATE_VERSION`. Add the texts `Onboard sensor fault (<NACK|SHORT|CRC>)` and `Onboard sensor back` to `EventText` of the three apps. Give the raw text `<event>,<detail>` for another detail. Put `SHTC3_FAULT` into the `.sensors` category of `EventFilter.swift`. Keep `EventText.kt` equal in both Android targets.
  - Acceptance test: In `tests/test_firmware_logic.cpp`, assert one fault edge with detail `0x0101` for three `NACK` reads, one recovery edge with detail `0x0000` for the next `OK` read, no edge for two failures and an `OK` read, and no second fault edge for a fourth failure. Assert that the RTC ring check accepts code 18 and rejects code 19. Change the four existing asserts `EventRing::MAX_CODE == 17` (near lines 18886, 18910, 18982 and 19908) to 18. Change each existing rejection test with `static_cast<EventCode>(18)` (find them with `rg -n 'static_cast<EventCode>\(18\)' tests/test_firmware_logic.cpp`) to code 19. Assert that a ring with code 18 restores as `SHTC3_FAULT`. Add the code name `SHTC3_FAULT` to the event name switch of `sd_logger.h`. Add two `SHTC3_FAULT` rows to `tests/fixtures/events_reply_sample.txt` and calculate the `CRC32` line again. Update the fixture row counts: 19 in `EventTextTests` and `EventFilterTests` (now 17), 18 in `EventsReplyTest` (now 16), and the `.sensors` count in `EventFilterTests` from 4 to 6. Check the row counts of `EventCsvTest` and the watch `EventTextTest`. In `EventTextTests`, `EventTextTest` (phone) and `EventTextTest` (watch), assert both texts and the raw text for detail `0x0401`. In `EventFilterTests`, assert that a `SHTC3_FAULT` row is in the sensor category. Run `c++ -std=c++17 -Wall -Wextra -Werror tests/test_firmware_logic.cpp -o test_runner && ./test_runner && rm -f test_runner`, `arduino-cli compile rlcd_panel`, `swift test` in `macos/RlcdCompanion`, `gradle testDebugUnitTest` in `android/RlcdPhone` and `wearos/RlcdWatch`, and `./scripts/check-protocol-sync.sh`.

- [ ] Panel diagnostics lines in the Android sensor health screen
  - Rank: [Total: 31, Use: 3, Setup: 9, Harmony: 9, Safety: 10, Effort: M]
  - Location: android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/StatusReply.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/StatusReply.kt, android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/SensorHealthScreen.kt, android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/StatusReplyTest.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/StatusReplyTest.kt, tests/fixtures/status_reply_sample.txt
  - Goal: The macOS `DiagnosticsView` shows the `STATUS` lines `RESET`, `SHTC3` and `MEMORY`. The Kotlin `StatusReply.parse` ignores these lines, so the Android user cannot see a watchdog reset, a failing onboard sensor or a low heap. Add `StatusReply.reset` (`ResetState(reason, abnormalCount)`, `isAbnormal` for `PANIC`, `INT_WDT`, `TASK_WDT`, `WDT` and `BROWNOUT`), `StatusReply.shtc3` (`Shtc3State(nack, shortReads, crc, run, retried)`, `isAlert` at run 3 or more) and `StatusReply.memory` (`MemoryState(free, min, largest, psram)`, `isLow` below 16384 bytes for `largest`). Use the same limits as `ResetMetrics`, `Shtc3Stats` and `MemoryMetrics` in `Diagnostics.swift`. Return null for a missing line. For `abnormal=--`, set `abnormalCount` to null and keep `reason`. Throw `ParseException` for a bad number. Put the same code into the watch `StatusReply.kt`, so `scripts/check-protocol-sync.sh` passes. Show the three groups in `SensorHealthScreen` of the phone with a warning symbol for each alert state.
  - Acceptance test: In `StatusReplyTest` (phone) and `StatusReplyTest` (watch), parse `tests/fixtures/status_reply_sample.txt` and assert `reset.reason == "POWERON"`, `reset.abnormalCount == 0`, `shtc3.run == 0` and `memory.largest == 110592`. Assert `isAbnormal` for `RESET reason=TASK_WDT abnormal=2`, `reason == "PANIC"` and a null `abnormalCount` for `RESET reason=PANIC abnormal=--`, `isAlert` for `run=3`, `isLow` for `largest=16383`, null for a reply without the lines and `ParseException` for `SHTC3 nack=x`. Run `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone`, `gradle testDebugUnitTest` in `wearos/RlcdWatch`, `./scripts/check-protocol-sync.sh` and `./scripts/check-protocol-sync.sh --self-test`.

## Priority 3: Display and Logging (Score 22–27)

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
