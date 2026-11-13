#pragma once

// Build identity of the firmware. `scripts/flash-firmware.sh` sets RLCD_GIT_HASH with the compiler flag
// -DRLCD_GIT_HASH="<short hash>[-dirty]". A build without the flag (for example CI) shows "unknown".
#ifndef RLCD_GIT_HASH
#define RLCD_GIT_HASH "unknown"
#endif

static constexpr const char* FIRMWARE_VERSION = "1.0.0";
static constexpr const char* ARDUINO_CORE_VERSION = "3.3.12"; // esp32:esp32 in rlcd_panel/sketch.yaml
