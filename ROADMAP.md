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

- [ ] Badges in the Android panel preview
  - **Location:** `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/protocol/PanelRenderer.kt`, `android/RlcdPhone/app/src/main/java/com/rlcdpanel/phone/PanelPreviewScreen.kt`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/PanelRendererTest.kt`, `FEATURES.md`
  - **Goal:** Show the `V`, `D`, `W`, `C`, `R` and `S` badges in the phone preview. The preview has no badges because the telemetry frame does not carry them. Read the badge masks from the last `STATUS` `LATCH` line in memory when it exists. Draw each badge with the positions of `dashboard_view.h`. Keep the label `Preview from telemetry, not the panel image`.
  - **Acceptance:** A unit test renders a frame with the `vent` bit of tile 1 and checks the inverted badge pixels against a reference from `tools/render_display_preview.cpp`. A unit test checks no badge without a `STATUS` reply. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` passes.
  - **Rank:** [Total: 25, Use: 4, Setup: 3, Harmony: 8, Safety: 10, Effort: M]

- [ ] Self-test script for install-phone-apk.sh
  - **Location:** `scripts/install-phone-apk.sh`, `.github/workflows/ci.yml`, `tests/test_scripts.sh`, `FEATURES.md`
  - **Goal:** Test the target resolution of the phone install script like `install-watch-apk.sh`. Add `--self-test` with fake `adb` through `RLCD_ADB_BIN`: a serial, `host:port` and no argument. Check the `adb -s` arguments. Add `-h`/`--help`. Run the self-test in the CI job `Host tests`.
  - **Acceptance:** `./scripts/install-phone-apk.sh --self-test` exits 0. A broken resolution exits 1. `tests/test_scripts.sh` passes.
  - **Rank:** [Total: 24, Use: 1, Setup: 5, Harmony: 8, Safety: 10, Effort: S]

- [ ] Self-test of check-protocol-sync.sh against an unlisted shared file
  - **Location:** `scripts/check-protocol-sync.sh`, `.github/workflows/ci.yml`, `FEATURES.md`
  - **Goal:** Find a protocol file that exists in both apps but is not in `FILES`. Add a check: list the `.kt` files with the same name in both `protocol` directories, remove the package line, and print `UNLISTED <name>` for an equal pair outside `FILES`. Exit 1 for an unlisted equal pair. Keep a different pair outside `FILES` as no error.
  - **Acceptance:** The `--self-test` checks one unlisted equal pair (exit 1) and one unlisted different pair (exit 0). `./scripts/check-protocol-sync.sh` passes for the current tree after the needed `FILES` entries.
  - **Rank:** [Total: 24, Use: 1, Setup: 4, Harmony: 9, Safety: 10, Effort: S]

- [ ] Parser fuzz test for the console reply assemblers
  - **Location:** `macos/RlcdCompanion/Tests/RlcdProtocolTests/ReplyAssemblerTests.swift`, `android/RlcdPhone/app/src/test/java/com/rlcdpanel/phone/protocol/ReplyAssemblerTest.kt`, `wearos/RlcdWatch/app/src/test/java/com/rlcdpanel/watch/protocol/ReplyAssemblerTest.kt`, `FEATURES.md`
  - **Goal:** Make sure that a damaged BLE reply never crashes an app. Feed each fixture of `tests/fixtures` through the `ReplyAssembler` of each app with fixed seeds: drop one byte, flip one byte, cut the reply at each position of the first 200 bytes. The assembler must give a CRC error or an incomplete reply, never an exception other than the parse error types. Use a fixed seed list so the test is repeatable.
  - **Acceptance:** The new tests pass in the three apps. A test fails when you remove the CRC check of the assembler. `swift test` and `swift build -c release` in `macos/RlcdCompanion` pass. `gradle testDebugUnitTest assembleDebug` in `android/RlcdPhone` passes. `gradle testDebugUnitTest assembleDebug` in `wearos/RlcdWatch` passes.
  - **Rank:** [Total: 24, Use: 2, Setup: 3, Harmony: 9, Safety: 10, Effort: M]

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
