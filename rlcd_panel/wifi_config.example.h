#pragma once

// Optional Wi-Fi settings for the daily NTP time sync.
// Copy this file to wifi_config.h. The copy is ignored by Git, so do not commit credentials.
// Without wifi_config.h, or with an empty SSID, the radio stays off and the RTC keeps its time.

#define RLCD_WIFI_SSID "your-network"
#define RLCD_WIFI_PASSWORD "your-password" // 8..63 characters, a 64-digit hex key, or "" for an open network

// Time zone. The defaults are CET/CEST (Central Europe).
// #define RLCD_TZ_STD_OFFSET_S 3600 // offset to UTC outside of DST, in seconds
// #define RLCD_TZ_EU_DST 1          // 1: apply the EU DST rule, 0: no DST
