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

- [ ] Alert limit lines in the Android history chart
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/ChartLimits.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/HistoryScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/ChartLimitsTest.kt`, `FEATURES.md`
  - **Goal:** Show the active alert limits as dashed lines in the Android history charts, so the user sees how near a room is to an alert. Port `ChartLimits.from(config:)` of the macOS app with the same four definitions: `mold_alert_limit_rh` (humidity), `dry_alert_limit_rh` (humidity, 0 is off), `freeze_alert_limit_c` (temperature, 0 is a valid limit) and `heat_alert_limit_c` (temperature, 0 is off). Give no line for a value outside the `ConfigSchema` range, for a missing key and for a missing `CONFIG` reply. Add `humidity(limits)` and `temperature(limits)`. Draw each line with its label in the matching chart. Send no console command.
  - **Acceptance:** A unit test gives four lines for four active limits. A unit test gives no line for `dry_alert_limit_rh=0` and one line for `freeze_alert_limit_c=0`. A unit test gives no line for a value outside the range. A unit test gives an empty list without a `CONFIG` reply. A unit test checks the split into humidity and temperature lines. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` passes.
  - **Rank:** [Total: 29, Use: 7, Setup: 5, Harmony: 7, Safety: 10, Effort: S]

## Priority 3: Display and Logging (Score 22–27)

