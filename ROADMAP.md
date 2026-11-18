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

- [ ] Ventilation advice in the Wear OS tile
  - Rank: [Total: 27, Use: 7, Setup: 2, Harmony: 8, Safety: 10, Effort: S]
  - Location: wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/tile/ClimateTileService.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/protocol/TelemetryCache.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/MainActivity.kt, wearos/RlcdWatch/app/src/main/java/com/rlcdpanel/watch/ventilation/VentilationGuide.kt, wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/tile/ClimateTileModelTest.kt
  - Goal: Only `MainActivity` shows the ventilation advice. The user must open the app to see it. Store the outdoor source of `MainActivity` in `TelemetryCache`. Store the tile index of a sensor slot, or the last Open-Meteo reading and its time. Make `ClimateTileModel.build` calculate `VentilationGuide.analysiereLueftung` for the focused room. Show the `titel` of the advice as one line below the climate line. Show no advice for a stale frame or a stale outdoor slot. Show no advice when the outdoor tile is the focused room. Show no advice for an Open-Meteo reading older than 1 hour. Do not start a BLE scan or a network request in the tile.
  - Acceptance test: In `ClimateTileModelTest`, build the model from a fixture frame and a fresh outdoor tile. Assert the advice line. Assert no advice line for the same tile as outdoor source. Assert no advice line for an Open-Meteo reading of 61 minutes. Assert no advice line for a frame older than 35 minutes. Run `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch`.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
