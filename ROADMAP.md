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

- [ ] Measured awake time of each wake cycle in STATUS and the runtime estimate
  - Rank: [Total: 35, Use: 6, Setup: 9, Harmony: 10, Safety: 10, Effort: S]
  - Location: `rlcd_panel/battery_monitor.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`
  - Goal: Add `AwakeTimeStats` to `battery_monitor.h`. Just before `esp_deep_sleep_start()`, the wake cycle records `millis()` as the awake time of this cycle. `AwakeTimeStats` keeps the last value, the maximum and a mean of the last 24 timer wakeups (each value saturates at 65535 ms). A power-on, a `KEY` wakeup and a cycle on USB power do not add a sample. Store the stats in `PersistentState` (6 bytes) and increase the state version. When 8 or more samples exist, `BatteryHistory::estimate` uses the measured mean instead of the constant `BatteryRuntime::AWAKE_MS`. Add the line `AWAKE last_ms=<N|--> avg_ms=<N|--> max_ms=<N|--> samples=<N>` to the `STATUS` reply after `RUNTIME`. Thus the user sees the real cost of each feature, for example the BLE broadcast or the noise window, and gets a more accurate runtime.
  - Acceptance test: Host tests cover the first sample, 24 samples and the 25th sample (the oldest sample goes out), the saturation at 65535 ms, the skip for a power-on, a `KEY` wakeup and USB power, the estimate with 7 samples (constant) and with 8 samples (measured mean), the `STATUS` line format and its position after `RUNTIME`, and the RTC round trip with a corrupt field and the old state version. `arduino-cli compile rlcd_panel` passes.

- [ ] BLE telemetry broadcast of the climate tiles after each scan
  - Rank: [Total: 35, Use: 9, Setup: 7, Harmony: 10, Safety: 9, Effort: M]
  - Location: `rlcd_panel/ble_telemetry.h` (new), `rlcd_panel/rlcd_panel.ino`, `rlcd_panel/device_config.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/serial_console.h`, `docs/config.example.ini`, `tests/test_firmware_logic.cpp`, `tests/fixtures/ble_telemetry_vectors.txt` (new), `README.md`
  - Goal: Send the panel readings to a host over BLE, because BLE uses much less energy than Wi-Fi. The firmware already starts the BLE controller in each wake cycle for the sensor scan. A short broadcast with the warm radio takes approximately 20 to 50 ms at approximately 50 mA. A Wi-Fi connection with DHCP and TCP takes 1.5 to 4 s at approximately 250 mA and drains the 18650 cell fast. Add `BleTelemetryCodec` to the new header `ble_telemetry.h`. It encodes one Manufacturer Data frame with the company ID `0xFFFF` (21 bytes): format version (1), sequence number (1), cell percentage (1, 255 is unknown), active alert mask (1), valid tile mask (1), then for the onboard sensor and slots 1 to 3 the temperature (`int16`, 0.01 C, little-endian) and the relative humidity (`uint8`, 0.5 %RH), and a CRC-8 (`0x31`, the `SHTC3` polynomial) over all prior bytes. An empty or stale tile has its bit cleared in the valid mask and zero bytes. The temperature stays in Celsius; `temp_unit` does not change the frame. After `scanOnce()`, the wake cycle advertises the frame as non-connectable for 100 ms (interval 20 ms), then stops the advertising before the deep sleep. Add the `config.ini` key `ble_broadcast_enabled` (0 or 1, default 0). A low battery alert and the empty battery protection skip the broadcast. Store the key in the config trailer (1 byte) and increase the state version. Thus a host, for example the macOS app below, reads all four tiles without a cable.
  - Acceptance test: Host tests cover the frame for four valid tiles, for an empty slot and a stale slot, the extreme values -50 C, 100 C, 0 %RH and 100 %RH, the rounding to 0.5 %RH, an unknown cell percentage, the sequence wrap from 255 to 0, the CRC-8 value, a frame size that fits into the 31-byte advertising payload with the flags field, the skip for `ble_broadcast_enabled=0`, a low battery alert and the empty battery protection, the parser for 0, 1, 2 and a malformed value, the `CONFIG` line, the template sync test with `docs/config.example.ini`, and the RTC round trip with a corrupt field and the old state version. The test reads the shared hex vectors in `tests/fixtures/ble_telemetry_vectors.txt` and compares each encoded frame byte for byte. `arduino-cli compile rlcd_panel` passes. The `AWAKE` line in `STATUS` shows the added awake time on the board.

- [ ] BLE console bridge for the serial console commands on USB power
  - Rank: [Total: 34, Use: 7, Setup: 9, Harmony: 9, Safety: 9, Effort: M]
  - Location: `rlcd_panel/ble_telemetry.h`, `rlcd_panel/serial_console.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/device_config.h`, `rlcd_panel/rlcd_panel.ino`, `docs/config.example.ini`, `tests/test_firmware_logic.cpp`, `README.md`
  - Goal: Add a GATT service with the Nordic UART layout (`6E400001-B5A3-F393-E0A9-E50E24DCCA9E`, RX write characteristic, TX notify characteristic). The RX characteristic feeds a `SerialLineAssembler`. The bridge gives each complete line to the existing `SerialConsole` parser and `SerialConsoleRunner`. Send the reply bytes in TX notifications that fit the negotiated MTU, in the same byte order as on USB serial, with the same `BEGIN` and `CRC32` lines. Add an output sink interface to `SerialConsoleRunner`, so USB serial and BLE use the same code. Add `PowerManager::bleConsoleAllowed`: the service runs only on USB power, while the board stays awake. On battery power the board never advertises the service. Add the `config.ini` key `ble_console` (0 is off, 1 allows the read commands, 2 also allows `SET`; default 0). With value 1, `SET` replies `ERR READ_ONLY`. `DUMP` replies `ERR NOT_OVER_BLE`, because a monthly file is too large for BLE. Only one central can connect. A disconnect clears the partial line. Store the key in the config trailer (1 byte) and increase the state version. Thus the user reads `STATUS`, `HISTORY`, `EVENTS` and `CONFIG` and changes settings from a host without a USB cable to the computer.
  - Acceptance test: Host tests cover the line assembly across several write packets, a line above the 40-byte limit, the reply split for MTU 23 and MTU 185 with byte equality to the USB reply, the CRC-32 line, `SET` with `ble_console` 1 (`ERR READ_ONLY`) and 2 (`OK SET`), `DUMP` (`ERR NOT_OVER_BLE`), the gate on battery power, a disconnect in a partial line, the parser for 0, 1, 2, 3 and a malformed value, the `CONFIG` line, the template sync test, and the RTC round trip with a corrupt field and the old state version. `arduino-cli compile rlcd_panel` passes.

## Priority 2: Storage, and Configuration (Score 28–33)

- [ ] Serial console command SCAN to list nearby BLE sensors
  - Rank: [Total: 33, Use: 5, Setup: 10, Harmony: 9, Safety: 9, Effort: S]
  - Location: `rlcd_panel/serial_console.h`, `rlcd_panel/switchbot_ble.h`, `rlcd_panel/rlcd_panel.ino`, `tests/test_firmware_logic.cpp`, `README.md`
  - Goal: Add the console command `SCAN`. It runs one 4 s BLE scan and collects each decoded packet of the formats `WOIO`, `METER`, `BTHOME` and `ATC` in a `ScanCollector` with up to 16 MAC addresses. It replies `BEGIN SCAN`, the header `mac,format,rssi,slot,temp_c,rh_pct,battery_pct`, one row for each MAC (the strongest RSSI wins, `slot` is 1 to 3 or `-`), and a `CRC32` line. A 17th MAC increases a counter, and the reply then ends with the row `DROPPED,<N>` before the `CRC32` line. The command does not change the registry, the fixed MAC slots or `PersistentState`. The command runs on USB power only. Thus the user finds the MAC of a new sensor for a `slotN_mac` entry.
  - Acceptance test: Host tests cover the parser for `SCAN` and `SCAN` with extra text (`ERR BAD_ARG`), an empty scan, one packet of each format, two packets of one MAC with different RSSI, the slot column for an assigned and a free MAC, 17 MAC addresses with the `DROPPED` row, the CRC-32 value, the unchanged registry after the command, and the USB power gate. `arduino-cli compile rlcd_panel` passes.

- [ ] Native macOS companion app for the BLE telemetry and the BLE console
  - Rank: [Total: 33, Use: 9, Setup: 8, Harmony: 7, Safety: 9, Effort: M]
  - Location: `macos/RlcdCompanion/` (new Swift package: library `RlcdProtocol`, app target `RlcdCompanion`, tests `RlcdProtocolTests`), `tests/fixtures/ble_telemetry_vectors.txt`, `.github/workflows/ci.yml`, `README.md`
  - Goal: Add a SwiftUI menu bar app for macOS 14 and later. It uses `CoreBluetooth` to scan passively for the telemetry frame of the entry "BLE telemetry broadcast of the climate tiles after each scan". The library `RlcdProtocol` decodes the frame, checks the format version and the CRC-8, and rejects a bad frame. The menu bar shows the onboard temperature. The window shows the four tiles, the cell percentage, the active alerts and the age of the last frame. When the panel is on USB power and `ble_console` is not 0, the app connects to the console bridge. It sends `STATUS`, `HISTORY`, `EVENTS` and `CONFIG`, checks the `CRC32` line of each reply, and shows two 24-hour charts (Swift Charts) from the `HISTORY` rows. With `ble_console=2`, the app changes one key with `SET`. As a fallback, the app opens the USB serial port (`/dev/cu.usbmodem*`, 115200 baud) and sends the same commands. The app does not store data on the panel and does not send a command on its own. Add a CI job on `macos-latest` that runs `swift test` in `macos/RlcdCompanion`. Thus the user monitors the climate from the desk without a look at the panel.
  - Acceptance test: `swift test` in `macos/RlcdCompanion` passes. The tests decode each hex vector in `tests/fixtures/ble_telemetry_vectors.txt` with the same values as the C++ test, and reject a frame with a bad CRC-8, a wrong company ID, an unknown format version and a short frame. Other tests parse a recorded `STATUS`, `HISTORY`, `EVENTS` and `CONFIG` reply, accept a correct CRC-32, reject a changed byte, and split a reply over several BLE notifications. `swift build -c release` passes. The C++ host tests and `arduino-cli compile rlcd_panel` stay unchanged and pass.

- [ ] Event codes for resets and sensor slot changes in the event ring
  - Rank: [Total: 32, Use: 5, Setup: 9, Harmony: 9, Safety: 9, Effort: S]
  - Location: `rlcd_panel/sd_logger.h`, `rlcd_panel/switchbot_ble.h`, `rlcd_panel/power_manager.h`, `rlcd_panel/rlcd_panel.ino`, `scripts/dump-logs.sh`, `tests/test_firmware_logic.cpp`
  - Goal: Add three `EventCode` values. `RESET` (8) records each start that is not a deep sleep wakeup; the detail is the `ResetReason` value. `SLOT_ASSIGN` (9) records a new MAC in a registry slot; the detail is the slot number in the high byte and the `BleFormat` in the low byte. `SLOT_RELEASE` (10) records a slot that `PersistentState::releaseDeadSlots` cleared; the detail is the slot number. Add the text names to `SdLogCodec::formatEventRow`, so `events_v1_YYYY.csv` and the console command `EVENTS` show them. Accept the new codes in the RTC validation of the ring. The ring layout does not change. Thus the user finds the time of a crash or of a lost sensor in the event log.
  - Acceptance test: Host tests cover a power-on, a `PANIC` reset and a deep sleep wakeup (no event), a new MAC in a free slot, a known MAC (no event), a released slot, a fixed MAC slot that never releases, the row text of each new code, the RTC validation of a ring with code 10 and with code 11 (rejected), and the `EVENTS` reply with the new rows. `arduino-cli compile rlcd_panel` passes.

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
