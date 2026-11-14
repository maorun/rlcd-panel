# Roadmap

This file lists open work for `rlcd-panel`, the standalone Arduino CLI firmware for the **Waveshare ESP32-S3-RLCD-4.2** development board.
The device renders climate telemetry from onboard sensors and external SwitchBot thermo-hygrometers onto a 4.2-inch reflective LCD.
The list uses priorities. Priority 1 is the highest priority.
Do the work in priority order, unless the maintainer gives a different order.

## Rules for this file

- Write in ASD-STE100 Simplified Technical English.
- Add one entry for each open item. Do not give an entry a status. Each entry in this file is open.
- Remove an entry in the pull request that completes it. Describe finished work in `FEATURES.md`.
- Each entry names the code location, the goal, and the acceptance test.
- Each entry has a `Rank` line with the model, the total score, the four single scores, and the effort size. See "Rules for ranking".
- Put each entry in the priority that matches its total score. Order the entries inside a priority by total score, highest first. For equal scores, put the smaller effort first. Put a dependency above the entry that needs it.
- Priority 1 holds the most critical user-facing enhancements and core hardware integrations.
- Do not record review findings in this file. Name them in the review report and in the pull request description.

## Priority 1: Core Tooling and Calibration (Score 34–40)

- [ ] Native macOS companion app for the BLE telemetry and the BLE console
  - Rank: [Total: 33, Use: 9, Setup: 8, Harmony: 7, Safety: 9, Effort: M]
  - Location: `macos/RlcdCompanion/` (new Swift package: library `RlcdProtocol`, app target `RlcdCompanion`, tests `RlcdProtocolTests`), `tests/fixtures/ble_telemetry_vectors.txt`, `.github/workflows/ci.yml`, `README.md`
  - Goal: Add a SwiftUI menu bar app for macOS 14 and later. It uses `CoreBluetooth` to scan passively for the telemetry frame of the entry "BLE telemetry broadcast of the climate tiles after each scan". The library `RlcdProtocol` decodes the frame, checks the format version and the CRC-8, and rejects a bad frame. The menu bar shows the onboard temperature. The window shows the four tiles, the cell percentage, the active alerts and the age of the last frame. When the panel is on USB power and `ble_console` is not 0, the app connects to the console bridge. It sends `STATUS`, `HISTORY`, `EVENTS` and `CONFIG`, checks the `CRC32` line of each reply, and shows two 24-hour charts (Swift Charts) from the `HISTORY` rows. With `ble_console=2`, the app changes one key with `SET`. As a fallback, the app opens the USB serial port (`/dev/cu.usbmodem*`, 115200 baud) and sends the same commands. The app does not store data on the panel and does not send a command on its own. Add a CI job on `macos-latest` that runs `swift test` in `macos/RlcdCompanion`. Thus the user monitors the climate from the desk without a look at the panel.
  - Acceptance test: `swift test` in `macos/RlcdCompanion` passes. The tests decode each hex vector in `tests/fixtures/ble_telemetry_vectors.txt` with the same values as the C++ test, and reject a frame with a bad CRC-8, a wrong company ID, an unknown format version and a short frame. Other tests parse a recorded `STATUS`, `HISTORY`, `EVENTS` and `CONFIG` reply, accept a correct CRC-32, reject a changed byte, and split a reply over several BLE notifications. `swift build -c release` passes. The C++ host tests and `arduino-cli compile rlcd_panel` stay unchanged and pass.

## Priority 3: Score 22–27

- [ ] Skip of the display refresh for an unchanged frame on a timer wakeup
  - Rank: [Total: 25, Use: 7, Setup: 2, Harmony: 9, Safety: 7, Effort: S]
  - Location: `rlcd_panel/st7305_framebuffer.h`, `rlcd_panel/st7305_panel.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
  - Goal: Add `St7305Framebuffer::crc32()` over the 15,000 bytes after `display_invert`. Store the CRC-32 of the last flushed frame and a refresh counter in `PersistentState` (5 bytes) and increase the state version. On a timer wakeup with an equal CRC-32, the wake cycle skips the panel start and the flush, because the reflective panel keeps its image. A power-on, a `KEY` wakeup, a view change, the console command `PATTERN` and each 60th skipped cycle always flush. Thus the board saves the SPI transfer and the panel start in many night cycles.
  - Acceptance test: Host tests cover the CRC-32 of a known pattern, one changed pixel, the skip for an equal frame on a timer wakeup, the flush for a power-on, a `KEY` wakeup and a view change, the forced flush after 60 skips, the `PATTERN` path, and the RTC round trip with a corrupt field and the old state version. `arduino-cli compile rlcd_panel` passes. On the board, the `AWAKE` line in `STATUS` shows a shorter awake time for an unchanged frame, and the display shows the correct view after 61 cycles.

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components and firmware modules does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
