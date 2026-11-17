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

- [ ] Sensor display names bound to MAC addresses
  - Rank: [Total: 34, Use: 8, Setup: 9, Harmony: 9, Safety: 8, Effort: M]
  - Location: rlcd_panel/device_config.h, rlcd_panel/dashboard_view.h, rlcd_panel/serial_console.h, rlcd_panel/rlcd_panel.ino, macos/RlcdCompanion/Sources/RlcdProtocol/Replies.swift, macos/RlcdCompanion/Sources/RlcdCompanion/DiagnosticsView.swift, wearos/RlcdWatch/app/src/main/java/de/mdriemel/rlcdwatch/telemetry/TelemetrySnapshot.kt, android/RlcdPhone/app/src/main/java/de/mdriemel/rlcdphone/telemetry/TelemetrySnapshot.kt, tests/test_firmware_logic.cpp, macos/RlcdCompanion/Tests/RlcdProtocolTests/ReplyParserTests.swift
  - Goal: Currently `slotN_name` binds to the slot index `N` (1 to 10). When a sensor is discovered dynamically or reassigned to a different slot, it receives the name of the slot instead of its own name. An unknown sensor in slot 1 shows the name of slot 1. Bind each configured sensor name to the sensor MAC address: `DeviceConfig` resolves a slot display name by looking up the active MAC address of the slot in `slot_mac` (or pairing `slotN_name` with `slotN_mac`). When a slot holds an unknown MAC address without a configured name, the firmware shows the default fallback title ("S1" to "S10") instead of `slotN_name`. When a known MAC address is assigned to any slot, the slot displays the configured name of that MAC address. Update the tile headers, the stats view, the console command `STATUS`, and the companion apps (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) to resolve sensor names by MAC address.
  - Acceptance test: Host tests assert that a slot with a known sensor MAC displays its configured name regardless of slot index, a slot with an unknown sensor MAC displays its default fallback title ("S1" to "S10") even when `slotN_name` is set, a reassigned sensor retains its name in the new slot, and the serial console `STATUS` line displays the name matched by MAC. Companion app tests in macOS, Wear OS, and Android verify name lookup by sensor MAC address. `test_firmware_logic`, `swift test`, and Android/Wear OS unit tests pass.

## Priority 2: Configuration and Control (Score 28–33)

- [ ] Restore of the 24-hour histories from the MicroSD card after a power-on
  - Rank: [Total: 28, Use: 7, Setup: 5, Harmony: 9, Safety: 7, Effort: M]
  - Location: rlcd_panel/sd_logger.h, rlcd_panel/sparkline.h, rlcd_panel/rlcd_panel.ino, tests/test_firmware_logic.cpp
  - Goal: A power-on (battery change, reset) clears the RTC memory. The sparklines, the records, the mold hours and the charts stay empty for 24 hours, but the card holds the hourly rows. At a power-on with a valid RTC time, the firmware mounts the card for the config load and then reads the end of the current `climate_v5_YYYYMM.csv` file (and the previous month file when the current file has fewer than 24 rows). Add `SdLogCodec::parseClimateRow` (27 columns; an empty field is a missing value; a wrong column count rejects the row) and `ClimateHistory::restore`. The restore pushes the newest contiguous hours, at most 24 and not older than 24 hours, into the onboard history, the onboard `usb_mask` (from the `usb` column) and the history of each slot with a fixed MAC (`slotN_mac`). Slots without a fixed MAC stay empty, because the registry order after a power-on is not known. A gap of more than one hour ends the restore at the gap. The read is limited to the last 8192 bytes of each file. A missing file, a read failure or a bad row changes nothing and never blocks the wake cycle. `last_sample_ms` follows the newest restored row, so the hourly gate does not push the same hour two times.
  - Acceptance test: Host tests assert the parse of a known row, the rejected row with 26 columns, empty fields as missing values, a restore of 24 rows into the onboard history and a fixed MAC slot, no restore for a slot without a fixed MAC, the stop at a 2-hour gap, the skip of rows older than 24 hours, the month change with 10 rows in the current file and 30 rows in the previous file, the `usb_mask` bits, and no change for an invalid RTC time and for a missing file.

## Priority 3: Display and Logging (Score 22–27)

## Rules for ranking

Rate each entry with the feature model. The model has four criteria. Each criterion has a value from 0 to 10. The total score is the sum, with a maximum of 40.
Effort does not add to the total. Give the effort as a size: S (one small pull request), M (one large pull request), L (several pull requests).

**Feature model:**
1. **Use:** How much does the feature help the user in daily climate monitoring? (0–10)
2. **Setup:** How much does it help the user configure, calibrate, or troubleshoot the device? (0–10)
3. **Harmony:** How many existing hardware components, firmware modules, and companion app modules (`macos/RlcdCompanion`, `wearos/RlcdWatch`, `android/RlcdPhone`) does it use or extend? (0–10)
4. **Safety:** How small is the risk of power drain, corruption, hardware wear, or data loss? 10 means no risk. (0–10)
