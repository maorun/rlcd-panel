#pragma once

#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include <utility>

#include "audio_alert.h"
#include "es7210_mic.h"
#include "frame_refresh.h"
#include "battery_monitor.h"
#include "board_pins.h"
#include "button_handler.h"
#include "device_config.h"
#include "ntp_sync.h"
#include "sd_logger.h"
#include "shtc3_sensor.h"
#include "sparkline.h"
#include "drift_latch.h"
#include "dry_latch.h"
#include "wall_latch.h"
#include "window_latch.h"
#include "ventilate_latch.h"
#include "switchbot_ble.h"

#if defined(ARDUINO) || defined(ESP_PLATFORM)
#include <driver/gpio.h>
#include <driver/rtc_io.h>
#include <esp_attr.h>
#include <esp_sleep.h>
#include <esp_system.h>
#include <Preferences.h>
#include <sys/time.h>
#endif

namespace rlcd_panel {

// Wakeup cause as seen by the firmware. KEY_BUTTON wakes the board through ext0 on GPIO 18.
enum class WakeupCause : uint8_t {
  POWER_ON = 0, // cold boot, reset, or any wakeup that keeps no RTC memory
  TIMER = 1,
  KEY_BUTTON = 2,
  OTHER = 3,
};

// Text of a wakeup cause for the console command STATUS.
class WakeupCauseCodec {
 public:
  static const char* text(WakeupCause cause) {
    switch (cause) {
      case WakeupCause::POWER_ON: return "POWER_ON";
      case WakeupCause::TIMER: return "TIMER";
      case WakeupCause::KEY_BUTTON: return "KEY";
      default: return "OTHER";
    }
  }
};

// One wake cycle runs these phases in order. A KEY wakeup skips SCAN.
enum class PowerPhase : uint8_t {
  BOOT = 0,
  RESTORE = 1,
  SCAN = 2,
  RENDER = 3,
  SAVE = 4,
  SLEEP = 5,
};

struct PowerConfig {
  static constexpr uint32_t DEFAULT_SLEEP_INTERVAL_S = 60;
  static constexpr uint32_t USB_SLEEP_INTERVAL_S = 10;
  static constexpr uint32_t DEFAULT_SCAN_DURATION_S = 4;
  static constexpr uint32_t MIN_SLEEP_INTERVAL_S = 10;
  static constexpr uint32_t MAX_SLEEP_INTERVAL_S = 3600;

  uint32_t sleep_interval_s = DEFAULT_SLEEP_INTERVAL_S; // start of one scan to start of the next
  uint32_t scan_duration_s = DEFAULT_SCAN_DURATION_S;

  // Returns a copy with the interval inside its limits and a scan that fits into the interval.
  PowerConfig sanitized() const {
    PowerConfig c = *this;
    if (c.sleep_interval_s < MIN_SLEEP_INTERVAL_S) {
      c.sleep_interval_s = MIN_SLEEP_INTERVAL_S;
    } else if (c.sleep_interval_s > MAX_SLEEP_INTERVAL_S) {
      c.sleep_interval_s = MAX_SLEEP_INTERVAL_S;
    }
    if (c.scan_duration_s < 1) {
      c.scan_duration_s = 1;
    }
    if (c.scan_duration_s > c.sleep_interval_s / 2) {
      c.scan_duration_s = c.sleep_interval_s / 2;
    }
    return c;
  }
};

// Everything that must survive deep sleep.
struct PersistentState {
  uint32_t boot_count = 0;    // wake cycles since power-on
  uint32_t last_scan_ms = 0;  // clock value at the start of the last BLE scan
  DisplayMode mode = DisplayMode::DASHBOARD;
  uint8_t chart_sensor = 0; // sensor of the climate chart view: 0 onboard, 1 to 5 registry slots
  uint8_t stats_page = 0;   // page of the stats view: 0 slots 1 to 3, 1 slots 4 and 5
  BatteryReading battery;
  SwitchBotRegistry registry;
  ClimateHistory history; // hourly humidity samples for the trend curves
  NoiseHistory noise_history; // hourly noise levels (dB) for the stats view
  BatteryHistory battery_history; // hourly cell voltage for the runtime estimate
  uint32_t last_log_ms = 0;  // clock value of the last MicroSD record
  bool has_logged = false;   // false until the first MicroSD attempt, success or failure
  DailyLogState daily;       // local day of the last handled daily summary
  bool muted = false;        // true: alerts stay silent
  uint16_t ventilate_tiles = 0; // ventilation badge latch, bit 0 onboard, bits 1..10 slots (VentilateLatch)
  uint16_t dry_tiles = 0;       // dry air badge latch, bit 0 onboard, bits 1..10 slots (DryLatch)
  uint16_t window_tiles = 0;    // open window badge latch, bit 0 onboard, bits 1..10 slots (WindowLatch)
  uint16_t wall_tiles = 0;      // wall humidity badge latch, bit 0 onboard, bits 1..10 slots (WallLatch)
  WindowLatch::Anchors window_anchors; // reference temperature and stamp of each tile (WindowLatch)
  uint16_t sensor_battery_latch = 0; // low battery latch, bits 0..9 are the registry slots (SensorBatteryLatch)
  uint32_t last_chime_ms = 0; // clock value of the last chime or alert rising edge (chime_repeat_h)
  uint8_t alert_mask = ALERT_NONE; // alerts active in the last wake cycle (hysteresis latch)
  int32_t sd_free_mb = -1;         // free MicroSD space in MB at the last card read; -1 is unknown (STATUS)
  uint8_t scan_fault = 0;          // 1: the last BLE scan start failed (recordScanResult); a power-on sets 0
  uint16_t drift_tiles = 0;     // sensor drift latch, bit 0 onboard, bits 1..10 slots (DriftLatch)
  uint16_t reception_low = 0;      // low reception latch, bits 0..9 are the registry slots (recordReceptionChanges)
  static constexpr uint8_t RECEPTION_LOW_MIN_SCANS = 12; // counted scans before the latch may set
  uint16_t stale_slots = 0;        // stale latch, bits 0..9 are the registry slots (recordStaleChanges)
  Snooze snooze;                   // timed mute (console SNOOZE); active alerts work as for muted
  uint8_t alert_ack = ALERT_NONE;  // acknowledged alert bits (console ACK): no chime reminder while the alert stays on
  NoiseBand noise_band = NoiseBand::QUIET; // band of the last noise window (hysteresis latch)
  NtpSyncState ntp;          // NTP attempt time, result and backoff
  RtcDriftState rtc_drift;   // RTC drift of the last NTP sync
  DeviceConfig config;       // settings from /sdcard/config.ini; a timer wakeup reuses them
  bool config_from_sd = false; // true: the card supplied `config`; false: the defaults apply
  ConfigRejects config_rejects; // rejected lines of the last config.ini parse
  SdStatus sd_status = SdStatus::UNKNOWN; // result of the last card access (hourly write or config load)
  uint32_t sd_status_ms = 0;  // clock value of that result; meaningless while sd_status is UNKNOWN
  EventRing events;           // events since the last successful hourly write (events_v1_YYYY.csv)
  Shtc3Health shtc3;          // failed reads of the onboard SHTC3 by cause; a power-on sets them to 0
  AwakeTimeStats awake;       // measured awake time of the timer wakeups on battery power
  FrameRefresh frame;         // CRC-32 of the last flushed frame and the skipped cycles in a row
  RejectLog reject_log;       // rejected packets of the last BLE scan (REJECTS command)
  ReceptionHistory reception; // BLE reception mask and scan count of each registry slot
  WeekHistory week;           // daily minimum and maximum of the last 7 days for the week chart

  // Stores the result of a card access. A wake cycle without card access never calls it, so the
  // stored status stays.
  void setSdStatus(SdStatus status, uint32_t now_ms) {
    sd_status = status;
    sd_status_ms = now_ms;
  }

  // Takes over a new config. A slot whose fixed MAC changed (set, changed or removed) loses its
  // reading and its 24-hour histories. A slot that holds a MAC another slot has fixed loses them
  // too. The registry then enforces the fixed MACs. Call it before the BLE scan starts, or inside
  // the registry lock.
  void applyConfig(const DeviceConfig& next, bool from_sd, const ConfigRejects& rejects) {
    for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
      if (memcmp(config.slot_mac[i], next.slot_mac[i], DeviceConfig::MAC_BYTES) != 0 ||
          (config.isSlotEnabled(i) && !next.isSlotEnabled(i))) {
        resetSlot(i);
      }
    }
    // A changed temperature offset steps the readings of its tile at once. A closed tile starts a new peak, so the
    // step is no fall. An open tile keeps its lowest reading.
    if (config.temp_offset_c != next.temp_offset_c || config.usb_temp_offset_c != next.usb_temp_offset_c) {
      restartClosedWindowAnchor(0);
    }
    for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
      if (config.slot_temp_offset_c[i] != next.slot_temp_offset_c[i]) {
        restartClosedWindowAnchor(1 + i);
      }
    }
    config = next;
    config_from_sd = from_sd;
    config_rejects = rejects;
    registry.setAutoAssign(config.slot_auto_assign != 0);
    registry.setMinAssignRssi(config.auto_assign_min_rssi);
    for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
      registry.setSlotEnabled(i, config.isSlotEnabled(i));
      registry.setFixedMac(i, config.hasSlotMac(i) ? config.slot_mac[i] : nullptr);
    }
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (registry.slot(i).valid && breaksFixedMac(i)) {
        resetSlot(i);
      }
    }
  }

  // Releases each slot without a fixed MAC whose last packet is `slot_release_h` hours old or older: the
  // slot loses its reading, histories and latches (resetSlot) and takes the next new sensor. A slot with
  // a fixed MAC never releases; key 0 is off. Runs once per wake cycle before the BLE scan, so
  // ALERT_SENSOR_LOST no longer sees the slot. Returns a bit mask of the released slots. A value below
  // sensor_stale_min releases the slot before it turns stale, so the alert never fires for it. The caller
  // runs timestampsConsistent() first: a reading stamped in the future would release at once.
  uint8_t releaseDeadSlots(uint32_t now_ms) {
    const uint32_t release_ms = config.slotReleaseMs();
    uint8_t released = 0;
    for (size_t i = 0; release_ms != 0 && i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (config.hasSlotMac(i) || registry.isFixed(i) || !slotExpired(registry.slot(i), now_ms, release_ms)) {
        continue;
      }
      resetSlot(i);
      released = static_cast<uint8_t>(released | (1u << i));
    }
    return released;
  }

  // Records the slot changes of this wake cycle. `released` is the mask from releaseDeadSlots (before the
  // scan): one SLOT_RELEASE event for each slot, detail is the slot number 1 to 3. Then one SLOT_ASSIGN
  // event for each slot that took a new MAC in the scan: detail is the slot number in the high byte and
  // the BleFormat in the low byte. A known MAC and a fixed MAC slot that never released give no event.
  // Only releaseDeadSlots records SLOT_RELEASE: applyConfig clears a slot without an event. After a
  // power-on each sensor takes a slot again, so the first cycle can add up to three SLOT_ASSIGN events.
  void recordSlotEvents(const RtcTime& now, uint8_t released) {
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      if ((released & (1u << i)) != 0) {
        events.record(now, EventCode::SLOT_RELEASE, static_cast<uint16_t>(i + 1));
      }
    }
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      BleFormat format = BleFormat::WOIO;
      if (registry.takeAssigned(i, format)) {
        events.record(now, EventCode::SLOT_ASSIGN,
                      static_cast<uint16_t>(((i + 1) << 8) | static_cast<uint8_t>(format)));
      }
    }
    // One JUMP_DROP event for each slot with a dropped jump since the last call, detail is the slot number 1 to 10.
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (registry.takeJumped(i)) {
        events.record(now, EventCode::JUMP_DROP, static_cast<uint16_t>(i + 1));
      }
    }
  }

  // Compares isStale of each valid slot with its bit in stale_slots after a BLE scan. One SLOT_STALE event for
  // each change: slot number 1 to 10 in the low byte, high byte 0 for lost (newly stale), 1 for back. A slot
  // without a reading loses its bit and records nothing.
  void recordStaleChanges(const RtcTime& now, uint32_t now_ms, uint32_t timeout_ms) {
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      const uint16_t bit = static_cast<uint16_t>(1u << i);
      const SwitchBotReading& r = registry.slot(i);
      if (!r.valid) {
        stale_slots = static_cast<uint16_t>(stale_slots & ~bit);
        continue;
      }
      const bool stale = r.isStale(now_ms, timeout_ms);
      if (stale == ((stale_slots & bit) != 0)) {
        continue;
      }
      stale_slots = static_cast<uint16_t>(stale ? (stale_slots | bit) : (stale_slots & ~bit));
      events.record(now, EventCode::SLOT_STALE, static_cast<uint16_t>((stale ? 0u : 0x0100u) | (i + 1)));
    }
  }

  // Low reception latch after ReceptionHistory::recordScan. A slot with at least 12 counted scans sets its bit below
  // limit_pct (reception_low_pct, default 50) and releases it at limit_pct + 20 or more; between the two the bit holds.
  // limit_pct 0 clears each bit without an event. One RECEPTION_LOW event for each change: slot
  // number 1 to 10 in the low byte, high byte 1 low, 0 good again. A slot without a reading loses its bit silently.
  // The firmware always passes config().reception_low_pct; the default argument only serves the host tests.
  void recordReceptionChanges(const RtcTime& now, uint8_t limit_pct = DeviceConfig::DEFAULT_RECEPTION_LOW_PCT) {
    if (limit_pct == 0) {
      reception_low = 0;
      return;
    }
    const unsigned release_pct = static_cast<unsigned>(limit_pct) + DeviceConfig::RECEPTION_RELEASE_MARGIN_PCT;
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      const uint16_t bit = static_cast<uint16_t>(1u << i);
      if (!registry.slot(i).valid) {
        reception_low = static_cast<uint16_t>(reception_low & ~bit);
        continue;
      }
      const uint8_t scans = reception.scans(i);
      const bool was = (reception_low & bit) != 0;
      bool low = was;
      if (scans >= RECEPTION_LOW_MIN_SCANS) {
        const unsigned got = reception.received(i);
        if (got * 100u < static_cast<unsigned>(limit_pct) * scans) {
          low = true;
        } else if (got * 100u >= release_pct * scans) {
          low = false;
        }
      }
      if (low == was) {
        continue;
      }
      reception_low = static_cast<uint16_t>(low ? (reception_low | bit) : (reception_low & ~bit));
      events.record(now, EventCode::RECEPTION_LOW, static_cast<uint16_t>((low ? 0x0100u : 0u) | (i + 1)));
    }
  }

  // One SCAN_FAULT event for each change of the scan start result: detail 1 for the first failed start, 0 for the
  // next good start. An equal state records nothing, so a radio that fails in each wake cycle does not fill the ring.
  void recordScanResult(bool started, const RtcTime& now) {
    const uint8_t fault = started ? 0 : 1;
    if (fault == scan_fault) {
      return;
    }
    scan_fault = fault;
    events.record(now, EventCode::SCAN_FAULT, fault);
  }

  // A change of the power source moves the onboard reading by usb_temp_offset_c at once. The onboard tile starts a new
  // peak then, so the step is no fall. An open onboard tile keeps its bit and its lowest reading: a step down moves
  // the release threshold with the reading only after the next lower reading, and a step up can release the bit early.
  // Only the last valid pair of readings counts: an invalid battery read in one cycle hides the change.
  void restartWindowOnPowerChange(const BatteryReading& previous, const BatteryReading& current) {
    if (previous.valid && current.valid && previous.usb_powered != current.usb_powered) {
      restartClosedWindowAnchor(0);
    }
  }

  // Clears the anchor of `tile` when its window bit is clear.
  void restartClosedWindowAnchor(size_t tile) {
    if (tile < WindowLatch::TILE_COUNT && (window_tiles & (1u << tile)) == 0) {
      window_anchors.clearTile(tile);
    }
  }

  // Records one WINDOW_OPEN event for each changed bit between two window_tiles values: the tile (0 onboard, 1 to 10
  // slots) in the low byte, high byte 1 for an open window, 0 for a closed one. A limit set to 0 clears the bits, so
  // it records the close events of the open tiles.
  void recordWindowChanges(uint16_t previous, uint16_t current, const RtcTime& now) {
    recordTileChanges(EventCode::WINDOW_OPEN, previous, current, WindowLatch::ALL_MASK, WindowLatch::TILE_COUNT, now);
  }

  // Records one WALL_DAMP event for each changed bit between two wall_tiles values: the tile (0 onboard, 1 to 10
  // slots) in the low byte, high byte 1 for a damp wall, 0 for a dry wall again.
  void recordWallChanges(uint16_t previous, uint16_t current, const RtcTime& now) {
    recordTileChanges(EventCode::WALL_DAMP, previous, current, WallLatch::ALL_MASK, WallLatch::TILE_COUNT, now);
  }

  // Records one DRY_AIR event for each changed bit between two dry_tiles values: same detail layout as WALL_DAMP.
  void recordDryChanges(uint16_t previous, uint16_t current, const RtcTime& now) {
    recordTileChanges(EventCode::DRY_AIR, previous, current, DryLatch::ALL_MASK, DryLatch::TILE_COUNT, now);
  }

  // Records one SENSOR_DRIFT event for each changed bit between two drift_tiles values: same detail layout as WALL_DAMP.
  void recordDriftChanges(uint16_t previous, uint16_t current, const RtcTime& now) {
    recordTileChanges(EventCode::SENSOR_DRIFT, previous, current, DriftLatch::ALL_MASK, DriftLatch::TILE_COUNT, now);
  }

  // Runs DriftLatch::update on the RTC histories. `tile_mask` holds the tiles that count: the caller leaves out empty and
  // stale slots. A tile outside the mask does not count in the median; its bit clears and records one SENSOR_DRIFT event
  // with high byte 0 when it was set, so each drift event pair closes. Fewer than SpreadMath::MIN_SENSORS sensors clear
  // each bit without an event; any other change of a counted tile records one SENSOR_DRIFT event.
  void updateDrift(uint16_t tile_mask, const RtcTime& now) {
    const uint16_t previous = drift_tiles;
    SpreadMath::Result r;
    SpreadMath::compute(history, r, tile_mask);
    drift_tiles = DriftLatch::update(previous, history, tile_mask);
    if (r.enough()) {
      recordTileChanges(EventCode::SENSOR_DRIFT, previous, drift_tiles, static_cast<uint16_t>((tile_mask | previous) & DriftLatch::ALL_MASK),
                        DriftLatch::TILE_COUNT, now);
    }
  }

  void recordTileChanges(EventCode code, uint16_t previous, uint16_t current, uint16_t mask, size_t tile_count,
                         const RtcTime& now) {
    const uint16_t changed = static_cast<uint16_t>((previous ^ current) & mask);
    for (size_t i = 0; i < tile_count; ++i) {
      const uint16_t bit = static_cast<uint16_t>(1u << i);
      if ((changed & bit) != 0) {
        events.record(now, code, static_cast<uint16_t>(((current & bit) != 0 ? 0x0100u : 0u) | i));
      }
    }
  }

  // Records one SENSOR_BATTERY event for each changed bit between two sensor_battery_latch values: slot number 1 to 10
  // in the low byte, high byte 1 for a low battery, 0 for a battery that is OK again. A limit of 0 records nothing.
  void recordBatteryChanges(uint16_t previous, uint16_t current, uint8_t limit_pct, const RtcTime& now) {
    if (limit_pct == 0) {
      return;
    }
    const uint16_t changed = static_cast<uint16_t>((previous ^ current) & SensorBatteryLatch::ALL_MASK);
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      const uint16_t bit = static_cast<uint16_t>(1u << i);
      if ((changed & bit) == 0) {
        continue;
      }
      events.record(now, EventCode::SENSOR_BATTERY,
                    static_cast<uint16_t>(((current & bit) != 0 ? 0x0100u : 0u) | (i + 1)));
    }
  }

  // Compares the usb_powered flag of the previous wake cycle (RTC copy, read before the overwrite) with the new
  // reading. One POWER_SOURCE event when both readings are valid and the flag changed: detail 1 USB power, 0 battery
  // power. A power-on has no valid previous reading and records nothing.
  void recordPowerChange(const BatteryReading& previous, const BatteryReading& current, const RtcTime& now) {
    if (!previous.valid || !current.valid || previous.usb_powered == current.usb_powered) {
      return;
    }
    events.record(now, EventCode::POWER_SOURCE, current.usb_powered ? 1 : 0);
  }

  // Manual release (console SLOT CLEAR): false for a slot with a fixed MAC, no change (the next scan takes the same
  // sensor again). Otherwise resetSlot; an occupied slot records SLOT_RELEASE with the slot number in the low byte and
  // 1 (manual) in the high byte. An empty slot records nothing.
  bool clearSlotByUser(size_t index, const RtcTime& now) {
    if (index >= MAX_SWITCHBOT_SENSORS || config.hasSlotMac(index) || registry.isFixed(index)) {
      return false;
    }
    const bool occupied = registry.slot(index).valid;
    resetSlot(index);
    if (occupied) {
      events.record(now, EventCode::SLOT_RELEASE, static_cast<uint16_t>(0x0100u | (index + 1)));
    }
    return true;
  }

  // Result of swapSlotsByUser.
  enum class SlotSwap : uint8_t { OK, FIXED_MAC, SLOT_OFF, BAD_ARG };

  // Console SLOT SWAP: swaps the reading, both 24-hour histories, the week days, the reception history and the
  // badge latch bits (battery, ventilation, dry air, stale) of slots `a` and `b` (indexes 0 to 9). FIXED_MAC when a
  // slot has a fixed MAC and SLOT_OFF when a slot is disabled: no change. BAD_ARG for an equal or bad index. One
  // SLOT_ASSIGN event for each occupied slot after the swap (slot number in the high byte, BleFormat in the low
  // byte). Call it inside the registry lock. No card access; the next save writes the state.
  SlotSwap swapSlotsByUser(size_t a, size_t b, const RtcTime& now) {
    if (a >= MAX_SWITCHBOT_SENSORS || b >= MAX_SWITCHBOT_SENSORS || a == b) {
      return SlotSwap::BAD_ARG;
    }
    if (config.hasSlotMac(a) || config.hasSlotMac(b) || registry.isFixed(a) || registry.isFixed(b)) {
      return SlotSwap::FIXED_MAC;
    }
    if (!registry.isSlotEnabled(a) || !registry.isSlotEnabled(b)) {
      return SlotSwap::SLOT_OFF;
    }
    registry.swapSlots(a, b);
    swapBit(sensor_battery_latch, a, b, 0);
    swapBit(ventilate_tiles, a, b, 1);
    swapBit(dry_tiles, a, b, 1);
    swapBit(window_tiles, a, b, 1);
    swapBit(wall_tiles, a, b, 1);
    swapBit(drift_tiles, a, b, 1);
    std::swap(window_anchors.ref[1 + a], window_anchors.ref[1 + b]);
    std::swap(window_anchors.prev[1 + a], window_anchors.prev[1 + b]);
    std::swap(window_anchors.stamp_ms[1 + a], window_anchors.stamp_ms[1 + b]);
    swapBit(stale_slots, a, b, 0);
    swapBit(reception_low, a, b, 0);
    std::swap(history.slots[1 + a], history.slots[1 + b]);
    std::swap(history.temperatures[1 + a], history.temperatures[1 + b]);
    reception.swap(a, b);
    week.swapSensors(1 + a, 1 + b);
    const size_t order[2] = {a < b ? a : b, a < b ? b : a};
    for (size_t index : order) {
      const SwitchBotReading& r = registry.slot(index);
      if (r.valid) {
        events.record(now, EventCode::SLOT_ASSIGN,
                      static_cast<uint16_t>(((index + 1) << 8) | static_cast<uint8_t>(r.format)));
      }
    }
    return SlotSwap::OK;
  }

  // Console ACK: stops the chime reminder of the active alert bit `bit` (0 to 7). False for an alert that is off.
  bool acknowledgeAlert(uint8_t bit, const RtcTime& now) {
    const uint8_t flag = static_cast<uint8_t>(1u << (bit & 7));
    if ((alert_mask & flag & ALERT_MASK_ALL) == 0) {
      return false;
    }
    if ((alert_ack & flag) != 0) {
      return true; // repeated ACK: no second event, so a script cannot flush the ring
    }
    alert_ack = static_cast<uint8_t>(alert_ack | flag);
    events.record(now, EventCode::ALERT_ACK, flag);
    return true;
  }

  // Console ACK ALL: acknowledges each active repeatable alert bit that is not acknowledged yet, lowest bit first, one
  // ALERT_ACK event for each new bit. Returns the mask of the new bits (0: nothing to do).
  uint8_t acknowledgeAll(const RtcTime& now) {
    const uint8_t fresh = static_cast<uint8_t>(AlertEvaluator::repeatableBits(alert_mask) & ~alert_ack);
    for (uint8_t bit = 0; bit < 8; ++bit) {
      if ((fresh & (1u << bit)) != 0) {
        acknowledgeAlert(bit, now);
      }
    }
    return fresh;
  }

  // Clears the reading, both 24-hour histories and the battery, ventilation and dry air latches of slot `index`.
  void resetSlot(size_t index) {
    if (index >= MAX_SWITCHBOT_SENSORS) {
      return;
    }
    registry.clearSlot(index);
    sensor_battery_latch = static_cast<uint16_t>(sensor_battery_latch & ~(1u << index));
    ventilate_tiles = static_cast<uint16_t>(ventilate_tiles & ~(2u << index));
    dry_tiles = static_cast<uint16_t>(dry_tiles & ~(2u << index));
    window_tiles = static_cast<uint16_t>(window_tiles & ~(2u << index));
    wall_tiles = static_cast<uint16_t>(wall_tiles & ~(2u << index));
    drift_tiles = static_cast<uint16_t>(drift_tiles & ~(2u << index));
    window_anchors.clearTile(1 + index);
    stale_slots = static_cast<uint16_t>(stale_slots & ~(1u << index));
    reception_low = static_cast<uint16_t>(reception_low & ~(1u << index));
    history.slots[1 + index].clear();
    history.temperatures[1 + index].clear();
    reception.clear(index);
    week.clear(1 + index);
  }

private:
  // Swaps bit `a + shift` and bit `b + shift` of a latch mask.
  static void swapBit(uint16_t& mask, size_t a, size_t b, unsigned shift) {
    const uint16_t bit_a = static_cast<uint16_t>(1u << (a + shift));
    const uint16_t bit_b = static_cast<uint16_t>(1u << (b + shift));
    const bool set_a = (mask & bit_a) != 0;
    const bool set_b = (mask & bit_b) != 0;
    mask = static_cast<uint16_t>(mask & ~(bit_a | bit_b));
    if (set_a) {
      mask = static_cast<uint16_t>(mask | bit_b);
    }
    if (set_b) {
      mask = static_cast<uint16_t>(mask | bit_a);
    }
  }

  // True when the reading of slot `index` breaks a fixed MAC.
  bool breaksFixedMac(size_t index) const {
    SwitchBotRegistry probe = registry;
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (i != index) {
        probe.clearSlot(i);
      }
    }
    return !probe.consistent();
  }

};

struct WakeupPlan {
  uint64_t timer_us = 0;
  bool enable_key_wakeup = false;
};

// Decides when the BLE scan may end before scan_duration_s. The scan stops early when each valid
// slot received a packet with temperature, humidity and battery in this scan and at least
// MIN_SCAN_MS of the scan are done. fullScanRequired() lists the cases that keep the full scan:
// every wakeup except a timer wakeup, USB power, the first wake cycle of each hour, and an empty or
// stale slot. Thus a new sensor can still fill an empty slot. Call onPacket() inside the registry
// lock, after the registry took the packet.
class ScanCompletion {
public:
  static constexpr uint32_t MIN_SCAN_MS = 1000;

  static bool fullScanRequired(
      WakeupCause cause,
      const BatteryReading& battery,
      const SwitchBotRegistry& registry,
      uint32_t now_ms,
      bool hour_due,
      uint32_t stale_timeout_ms = SENSOR_STALE_TIMEOUT_MS) {
    if (cause != WakeupCause::TIMER || (battery.valid && battery.usb_powered) || hour_due) {
      return true;
    }
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (!registry.slot(i).valid || registry.slot(i).isStale(now_ms, stale_timeout_ms)) {
        return true;
      }
    }
    return false;
  }

  // Starts a scan. A full scan never stops early.
  void begin(bool full_scan) {
    full_scan_ = full_scan;
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      reported_[i] = false;
    }
  }

  // Notes a packet. `reading` is the parsed packet, so a battery value kept from an earlier scan
  // does not count. The packet counts for the slot that holds its MAC.
  void onPacket(const SwitchBotRegistry& registry, const SwitchBotReading& reading) {
    if (!reading.valid || reading.battery_pct < 0 || !isfinite(reading.temperature_c)) {
      return;
    }
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      const SwitchBotReading& slot = registry.slot(i);
      if (slot.valid && memcmp(slot.mac, reading.mac, 6) == 0) {
        reported_[i] = true;
      }
    }
  }

  // True when the scan may stop now. `registry` is the live registry. fullScanRequired() keeps
  // every scan with an empty slot full, so a stop scan starts with all slots valid and the valid
  // slots are the sensors to wait for.
  bool canStop(const SwitchBotRegistry& registry, uint32_t elapsed_ms) const {
    if (full_scan_ || elapsed_ms < MIN_SCAN_MS) {
      return false;
    }
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      if (!registry.slot(i).valid || !reported_[i]) {
        return false;
      }
    }
    return true;
  }

  // Scan time saved by a stop at `elapsed_ms`, in ms. Zero for a scan that ran to its end.
  static uint32_t savedMs(uint32_t scan_duration_s, uint32_t elapsed_ms) {
    const uint32_t full_ms = scan_duration_s * 1000UL;
    return elapsed_ms < full_ms ? full_ms - elapsed_ms : 0;
  }

private:
  bool full_scan_ = true;
  bool reported_[MAX_SWITCHBOT_SENSORS] = {};
};

// Watches the USB power while the serial console runs. due() is true every RECHECK_MS. The
// caller then reads the battery and passes the result to update(). One low sample can come from a
// bad ADC read, so LOST_SAMPLES samples in a row without USB power end the watch. Unsigned
// subtraction keeps the timing correct when the clock wraps around.
class UsbPowerWatch {
public:
  static constexpr uint32_t RECHECK_MS = 5000;
  static constexpr uint8_t LOST_SAMPLES = 2;

  explicit UsbPowerWatch(uint32_t now_ms) : last_ms_(now_ms) {}

  bool due(uint32_t now_ms) const {
    return now_ms - last_ms_ >= RECHECK_MS;
  }

  // Returns true while USB power stays present.
  bool update(bool usb_powered, uint32_t now_ms) {
    last_ms_ = now_ms;
    low_count_ = usb_powered ? 0 : static_cast<uint8_t>(low_count_ + 1);
    return low_count_ < LOST_SAMPLES;
  }

private:
  uint32_t last_ms_;
  uint8_t low_count_ = 0;
};

class PowerManager {
public:
  static constexpr uint32_t STATE_MAGIC = 0x52504C43; // 'RPLC'
  static constexpr uint8_t STATE_VERSION = 77;
  static constexpr uint64_t MIN_SLEEP_US = 1000000ULL;
  // Sleep time while the empty battery protection is set: one hour, then the voltage is checked.
  static constexpr uint64_t PROTECTED_SLEEP_US = 3600ULL * 1000000ULL;
  // Slot layout: valid, mac[6], temperature (float bits), humidity, battery, fahrenheit,
  // rssi (int8), format (uint8), updated_ms, packet_count.
  static constexpr size_t SLOT_SIZE = 1 + 6 + 4 + 1 + 1 + 1 + 1 + 1 + 4 + 4;
  // History ring (humidity, then temperature, per slot): count, head, 24 samples (int16). Trailer: last sample time, sampled flag.
  static constexpr size_t HISTORY_RING_SIZE = 1 + 1 + HumidityHistory::CAPACITY * 2;
  // Each slot has a humidity ring and a temperature ring of the same layout.
  static constexpr size_t HISTORY_SIZE =
      ClimateHistory::SLOT_COUNT * 2 * HISTORY_RING_SIZE + 4 + 1;
  // Noise history: count, head, 24 samples (one byte, 0xFF is an invalid hour).
  static constexpr size_t NOISE_HISTORY_SIZE = 1 + 1 + NoiseHistory::CAPACITY;
  // Battery history: count, head, 24 samples (uint16 millivolts), 24 stamps (uint32), last
  // sample time, sampled flag.
  static constexpr size_t BATTERY_HISTORY_SIZE = 1 + 1 + BatteryHistory::CAPACITY * (2 + 4) + 4 + 1;
  // SD log trailer: last log time, logged flag, then the daily summary stamp: flag, year (uint16), month, day,
  // then the daily noise summary (peak dB, peak hour, event sum uint16), then the card status (one byte) and the clock stamp of that result (uint32).
  static constexpr size_t SD_LOG_SIZE = 4 + 1 + 1 + 2 + 1 + 1 + 1 + 1 + 2 + 1 + 4;
  // Audio trailer: last chime stamp (uint32), flags byte, muted | mask << 1 | band << 6, sensor battery latch (uint16), tile badge latch (uint16).
  static constexpr size_t AUDIO_SIZE = 4 + 6;
  // NTP trailer: last attempt time, flags (bit 0 attempted, bit 1 last ok), failure count, then the
  // RTC drift: flags (bit 0 synced, bit 1 drift known), last sync (UTC seconds), drift, rate (int32), interval (uint32).
  static constexpr size_t NTP_SIZE = 4 + 1 + 1 + 1 + 4 + 4 + 4 + 4;
  // Sensor names: the onboard name and one name for each slot, each with its terminator.
  static constexpr size_t NAMES_SIZE = (1 + DeviceConfig::SLOT_COUNT) * (DeviceConfig::NAME_MAX_CHARS + 1);
  // Fixed MACs: one 6-byte address for each slot, all zero for no fixed MAC.
  static constexpr size_t MACS_SIZE = DeviceConfig::SLOT_COUNT * DeviceConfig::MAC_BYTES;
  // Rejected config.ini lines: the count (one byte), then four 16-bit line numbers.
  static constexpr size_t REJECTS_SIZE = 1 + ConfigRejects::MAX_LINES * 2;
  // The offset arrays keep one entry for each of the slots. Each slot has offset keys.
  // Config trailer: temp offset, freeze limit (floats), mold limit, volume, quiet start hour,
  // quiet end hour, SPL offset (float), log retention months, noise alert limit, chime alert mask, NTP sync interval, wake interval (uint16), sensor stale minutes (uint16), outdoor slot, door ref slot, ventilate limit, sensor battery limit, display invert, climate alert tiles (uint16), battery offset (int16), slot release hours, night wake interval (uint16), temperature unit (one byte, 'C' or 'F'), chime repeat hours, heat alert limit, dry alert limit, wall FRSI percent, BLE broadcast enabled, BLE console, USB temperature offset (int8), RH gain and one RH gain for each slot (int8 in 0.002 steps around 1.00), RH offset (float), one temperature offset and one RH offset for each slot (floats), the sensor names, the fixed MACs, flags (bit 0 loaded from the card), slot enabled mask (uint16), the rejected line count and the line numbers of the first four rejected lines (uint16 each), auto assign minimum RSSI (int8), mold alert delay hours, reception low percent.
  static constexpr size_t CONFIG_SIZE = 4 + 4 + 1 + 1 + 1 + 1 + 4 + 1 + 1 + 1 + 1 + 2 + 2 + 1 + 1 + 1 + 1 + 1 + 2 + 2 + 1 + 2 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 4 + (1 + DeviceConfig::SLOT_COUNT) + 2 * DeviceConfig::SLOT_COUNT * 4 + NAMES_SIZE + MACS_SIZE + 1 + 2 + REJECTS_SIZE + 1 + 1 + 1;
  // Header: magic, version, mode, chart sensor, battery flags, battery pct, battery voltage, boot_count,
  // last_scan_ms. Trailer: CRC-32 over all earlier bytes.
  static constexpr size_t HEADER_SIZE = 4 + 1 + 1 + 1 + 1 + 1 + 1 + 4 + 4 + 4;
  // Event ring trailer (just before the checksum): count, dropped count (uint16), then 16 entries of
  // time flag, year (uint16), month, day, hour, minute, second, code, detail (uint16).
  static constexpr size_t EVENT_ENTRY_SIZE = 1 + 2 + 5 + 1 + 2;
  static constexpr size_t EVENTS_SIZE = 1 + 2 + EventRing::CAPACITY * EVENT_ENTRY_SIZE;
  // SHTC3 health trailer (just before the checksum): NACK, SHORT and CRC counters (uint16), run (uint8).
  static constexpr size_t SHTC3_SIZE = Shtc3Health::SERIALIZED_SIZE;
  static constexpr size_t AWAKE_SIZE = AwakeTimeStats::SERIALIZED_SIZE;
  static constexpr size_t FRAME_SIZE = FrameRefresh::SERIALIZED_SIZE;
  // BLE reception trailer (just before the checksum): for each registry slot a 24-bit mask (3 bytes, little-endian) and the scan count.
  static constexpr size_t RECEPTION_SIZE = ReceptionHistory::SERIALIZED_SIZE;
  // Dry air badge latch (uint16, bits 0..10, just before the checksum).
  static constexpr size_t DRY_SIZE = 2;
  // Week chart trailer (just before the checksum): 6 sensors x 7 days x 4 bytes, then the day stamp.
  static constexpr size_t WEEK_SIZE = WeekHistory::SERIALIZED_SIZE;
  // USB power mask of the onboard temperature samples (3 bytes, little-endian, just before the checksum).
  static constexpr size_t USB_MASK_SIZE = 3;
  // Reject log of the last BLE scan (just before the checksum).
  static constexpr size_t REJECT_LOG_SIZE = RejectLog::SERIALIZED_SIZE;
  // Acknowledged alert bits (1 byte, just before the checksum).
  static constexpr size_t ALERT_ACK_SIZE = 1;
  // Stale slot latch (uint16, bits 0..9, just before the checksum).
  static constexpr size_t STALE_SIZE = 2;
  // Free MicroSD space (int32, -1 unknown, just before the checksum).
  static constexpr size_t SD_FREE_SIZE = 4;
  // Scan fault flag (1 byte, just before the checksum).
  static constexpr size_t SCAN_FAULT_SIZE = 1;
  // Open window latch (just before the checksum): the key window_drop_c in 0.1 C steps (uint8), the bits (uint16,
  // bits 0..10), then for each tile the reference temperature and the previous peak (both int16, 0.01 C, INT16_MIN none) and the clock stamp (uint32).
  static constexpr size_t WINDOW_SIZE = 1 + 2 + WindowLatch::TILE_COUNT * (2 + 2 + 4);
  static constexpr size_t WALL_SIZE = 2;
  // Snooze (just before the checksum): start stamp (uint32), minutes (uint16).
  static constexpr size_t SNOOZE_SIZE = 4 + 2;
  static constexpr size_t RECEPTION_LOW_SIZE = 2;
  static constexpr size_t DRIFT_SIZE = 2;
  static constexpr size_t SERIALIZED_SIZE = HEADER_SIZE + MAX_SWITCHBOT_SENSORS * SLOT_SIZE + HISTORY_SIZE + NOISE_HISTORY_SIZE + BATTERY_HISTORY_SIZE + SD_LOG_SIZE + AUDIO_SIZE + NTP_SIZE + CONFIG_SIZE + EVENTS_SIZE + SHTC3_SIZE + AWAKE_SIZE + FRAME_SIZE + RECEPTION_SIZE + DRY_SIZE + WEEK_SIZE + USB_MASK_SIZE + REJECT_LOG_SIZE + ALERT_ACK_SIZE + STALE_SIZE + SD_FREE_SIZE + SCAN_FAULT_SIZE + WINDOW_SIZE + WALL_SIZE + SNOOZE_SIZE + RECEPTION_LOW_SIZE + DRIFT_SIZE + 4;

  static WakeupCause decodeWakeupCause(int esp_cause) {
    // Values of esp_sleep_source_t; the ESP-only wrapper asserts them against the SDK.
    switch (esp_cause) {
      case 0: return WakeupCause::POWER_ON;  // ESP_SLEEP_WAKEUP_UNDEFINED
      case 4: return WakeupCause::TIMER;     // ESP_SLEEP_WAKEUP_TIMER
      case 2:                                // ESP_SLEEP_WAKEUP_EXT0
      case 3: return WakeupCause::KEY_BUTTON; // ESP_SLEEP_WAKEUP_EXT1
      default: return WakeupCause::OTHER;
    }
  }

  // Only a wakeup from deep sleep leaves valid RTC memory and a continuous clock.
  static bool stateSurvives(WakeupCause cause) {
    return cause == WakeupCause::TIMER || cause == WakeupCause::KEY_BUTTON;
  }

  static bool needsScan(WakeupCause cause) {
    return cause != WakeupCause::KEY_BUTTON;
  }

  // The card is read on power-on and on a KEY wakeup only. A timer wakeup reuses the RTC copy,
  // so the card stays unmounted in the normal cycle.
  static bool needsConfigLoad(WakeupCause cause) {
    return cause == WakeupCause::POWER_ON || cause == WakeupCause::KEY_BUTTON;
  }

  // While the protection is set, the wake cycle skips the BLE scan, the audio (chimes, microphones), the MicroSD card and Wi-Fi. Only the battery read, the warning and the sleep run.
  static bool modulesAllowed(const BatteryReading& battery) {
    return !battery.empty_protection;
  }

  // The warning is drawn once, when the protection sets. The reflective panel keeps the image
  // without power, so every later wake cycle with a set latch leaves the display alone.
  static bool needsProtectionRender(bool was_latched, bool latched) {
    return latched && !was_latched;
  }

  // The USB serial console takes commands on USB power only. On battery the board neither reads
  // the port nor stays awake for it, so the awake time does not grow. An invalid battery reading
  // counts as no USB power.
  static bool serialConsoleAllowed(const BatteryReading& battery) {
    return battery.valid && battery.usb_powered;
  }

  // The BLE console service (serial_console.h, BleConsoleBridge) runs on USB power only, while the
  // board stays awake, and only with ble_console 1 or 2. On battery power the board never
  // advertises it.
  static bool bleConsoleAllowed(const BatteryReading& battery, uint8_t ble_console) {
    return serialConsoleAllowed(battery) && ble_console != 0;
  }

  // Sleep settings of this wake cycle. Battery power takes the interval from config.ini. USB power
  // uses USB_SLEEP_INTERVAL_S (10 s), so telemetry and sensors update frequently without draining
  // a battery. The interval only moves the wakeup timer: the hourly log, the histories and the
  // NTP schedule compare RTC stamps and do not depend on it. On battery power in the quiet hours a
  // set night_wake_interval_s replaces the interval. An invalid RTC time is no quiet hours.
  static PowerConfig powerConfig(const DeviceConfig& config, const BatteryReading& battery,
                                 const QuietHoursWindow& quiet = QuietHoursWindow(),
                                 const RtcTime& time = RtcTime()) {
    PowerConfig c;
    if (battery.valid && battery.usb_powered) {
      c.sleep_interval_s = PowerConfig::USB_SLEEP_INTERVAL_S;
    } else {
      c.sleep_interval_s = config.wake_interval_s;
      if (config.night_wake_interval_s > 0 && quiet.isActive(time)) {
        c.sleep_interval_s = config.night_wake_interval_s;
      }
    }
    return c.sanitized();
  }

  // True while the console may keep the board awake: the next scan is not yet due.
  static bool continuousListenAllowed(const PowerConfig& config, uint32_t now_ms, uint32_t last_scan_ms) {
    return sleepMicros(config, now_ms, last_scan_ms) > MIN_SLEEP_US;
  }

  // Microseconds until the next scan is due: interval minus the time since the last scan start.
  // The result lies in [MIN_SLEEP_US, interval]. Unsigned subtraction survives clock wrap-around.
  static uint64_t sleepMicros(const PowerConfig& config, uint32_t now_ms, uint32_t last_scan_ms) {
    const PowerConfig c = config.sanitized();
    const uint64_t interval_ms = static_cast<uint64_t>(c.sleep_interval_s) * 1000ULL;
    const uint64_t elapsed_ms = static_cast<uint32_t>(now_ms - last_scan_ms);
    if (elapsed_ms >= interval_ms) {
      return MIN_SLEEP_US;
    }
    const uint64_t us = (interval_ms - elapsed_ms) * 1000ULL;
    return us < MIN_SLEEP_US ? MIN_SLEEP_US : us;
  }

  // The KEY wakeup stays off while the button is still held: ext0 on a LOW level would wake
  // the board again at once.
  static WakeupPlan planSleep(
      const PowerConfig& config, uint32_t now_ms, uint32_t last_scan_ms, bool key_pressed) {
    WakeupPlan plan;
    plan.timer_us = sleepMicros(config, now_ms, last_scan_ms);
    plan.enable_key_wakeup = !key_pressed;
    return plan;
  }

  // Sleep plan of the empty battery protection: fixed one hour timer, KEY wakeup unless the button
  // is still held.
  static WakeupPlan planProtectedSleep(bool key_pressed) {
    WakeupPlan plan;
    plan.timer_us = PROTECTED_SLEEP_US;
    plan.enable_key_wakeup = !key_pressed;
    return plan;
  }

  static uint32_t crc32(const uint8_t* data, size_t len) {
    uint32_t crc = 0xFFFFFFFFu;
    for (size_t i = 0; i < len; ++i) {
      crc ^= data[i];
      for (int bit = 0; bit < 8; ++bit) {
        crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u)));
      }
    }
    return ~crc;
  }

  // Writes SERIALIZED_SIZE bytes. Returns false when the buffer is too small. The caller must
  // make sure the BLE scan callback no longer writes to the registry.
  static bool serialize(const PersistentState& state, uint8_t* out, size_t capacity) {
    if (out == nullptr || capacity < SERIALIZED_SIZE) {
      return false;
    }
    memset(out, 0, SERIALIZED_SIZE);
    size_t pos = 0;
    putU32(out, pos, STATE_MAGIC);
    out[pos++] = STATE_VERSION;
    out[pos++] = static_cast<uint8_t>(state.mode);
    out[pos++] = state.chart_sensor;
    out[pos++] = state.stats_page;
    out[pos++] = static_cast<uint8_t>((state.battery.valid ? 1 : 0) | (state.battery.usb_powered ? 2 : 0) |
                                     (state.battery.low_battery ? 4 : 0) | (state.battery.empty_protection ? 8 : 0));
    out[pos++] = static_cast<uint8_t>(state.battery.percentage);
    putFloat(out, pos, state.battery.voltage_v);
    putU32(out, pos, state.boot_count);
    putU32(out, pos, state.last_scan_ms);
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      const SwitchBotReading& r = state.registry.slot(i);
      if (!r.valid) {
        pos += SLOT_SIZE; // zero-filled
        continue;
      }
      out[pos++] = 1;
      memcpy(out + pos, r.mac, 6);
      pos += 6;
      putFloat(out, pos, r.temperature_c);
      out[pos++] = r.humidity_pct;
      out[pos++] = static_cast<uint8_t>(r.battery_pct);
      out[pos++] = r.fahrenheit_mode ? 1 : 0;
      out[pos++] = static_cast<uint8_t>(r.rssi);
      out[pos++] = static_cast<uint8_t>(r.format);
      putU32(out, pos, r.updated_ms);
      putU32(out, pos, r.packet_count);
    }
    for (size_t i = 0; i < ClimateHistory::SLOT_COUNT; ++i) {
      const HumidityHistory& h = state.history.slots[i];
      out[pos++] = static_cast<uint8_t>(h.size());
      out[pos++] = h.head();
      for (size_t k = 0; k < HumidityHistory::CAPACITY; ++k) {
        const uint16_t bits = static_cast<uint16_t>(h.rawSample(k));
        out[pos++] = static_cast<uint8_t>(bits & 0xFF);
        out[pos++] = static_cast<uint8_t>(bits >> 8);
      }
      const TemperatureHistory& t = state.history.temperatures[i];
      out[pos++] = static_cast<uint8_t>(t.size());
      out[pos++] = t.head();
      for (size_t k = 0; k < TemperatureHistory::CAPACITY; ++k) {
        const uint16_t bits = static_cast<uint16_t>(t.rawSample(k));
        out[pos++] = static_cast<uint8_t>(bits & 0xFF);
        out[pos++] = static_cast<uint8_t>(bits >> 8);
      }
    }
    putU32(out, pos, state.history.last_sample_ms);
    out[pos++] = state.history.has_sampled ? 1 : 0;
    out[pos++] = static_cast<uint8_t>(state.noise_history.size());
    out[pos++] = state.noise_history.head();
    for (size_t k = 0; k < NoiseHistory::CAPACITY; ++k) {
      out[pos++] = state.noise_history.rawSample(k);
    }
    out[pos++] = static_cast<uint8_t>(state.battery_history.size());
    out[pos++] = state.battery_history.head();
    for (size_t k = 0; k < BatteryHistory::CAPACITY; ++k) {
      const uint16_t mv = state.battery_history.rawSample(k);
      out[pos++] = static_cast<uint8_t>(mv & 0xFF);
      out[pos++] = static_cast<uint8_t>(mv >> 8);
    }
    for (size_t k = 0; k < BatteryHistory::CAPACITY; ++k) {
      putU32(out, pos, state.battery_history.rawTime(k));
    }
    putU32(out, pos, state.battery_history.last_sample_ms);
    out[pos++] = state.battery_history.has_sampled ? 1 : 0;
    putU32(out, pos, state.last_log_ms);
    out[pos++] = state.has_logged ? 1 : 0;
    out[pos++] = state.daily.has_date ? 1 : 0;
    out[pos++] = static_cast<uint8_t>(state.daily.year & 0xFF);
    out[pos++] = static_cast<uint8_t>(state.daily.year >> 8);
    out[pos++] = state.daily.month;
    out[pos++] = state.daily.day;
    out[pos++] = state.daily.noise.peak_db;
    out[pos++] = state.daily.noise.peak_hour;
    out[pos++] = static_cast<uint8_t>(state.daily.noise.events_sum & 0xFF);
    out[pos++] = static_cast<uint8_t>(state.daily.noise.events_sum >> 8);
    out[pos++] = static_cast<uint8_t>(state.sd_status);
    putU32(out, pos, state.sd_status_ms);
    putU32(out, pos, state.last_chime_ms);
    out[pos++] = static_cast<uint8_t>(((state.alert_mask & ALERT_VENTILATE) != 0 ? 1 : 0) |
                                      ((state.alert_mask & ALERT_SENSOR_BATTERY) != 0 ? 2 : 0) |
                                      ((state.alert_mask & ALERT_HEAT) != 0 ? 4 : 0));
    out[pos++] = static_cast<uint8_t>((state.muted ? 1 : 0) | ((state.alert_mask & (ALERT_MASK_ALL & ~ALERT_VENTILATE & ~ALERT_SENSOR_BATTERY & ~ALERT_HEAT)) << 1) |
        (static_cast<uint8_t>(state.noise_band) << 6));
    out[pos++] = static_cast<uint8_t>(state.sensor_battery_latch & 0xFF);
    out[pos++] = static_cast<uint8_t>((state.sensor_battery_latch >> 8) & 0xFF);
    out[pos++] = static_cast<uint8_t>(state.ventilate_tiles & 0xFF);
    out[pos++] = static_cast<uint8_t>((state.ventilate_tiles >> 8) & 0xFF);
    putU32(out, pos, state.ntp.last_attempt_ms);
    out[pos++] = static_cast<uint8_t>((state.ntp.has_attempted ? 1 : 0) | (state.ntp.last_ok ? 2 : 0));
    out[pos++] = state.ntp.failures;
    // An out-of-range figure is stored as "no figure", so deserialize never drops the whole state.
    const RtcDriftState& rd = state.rtc_drift;
    const bool figure = rd.has_sync && rd.has_drift && rd.drift_s >= -RtcDrift::MAX_DRIFT_S &&
                        rd.drift_s <= RtcDrift::MAX_DRIFT_S && rd.rate_ppm >= -RtcDrift::MAX_PPM &&
                        rd.rate_ppm <= RtcDrift::MAX_PPM && !(rd.drift_s > 0 && rd.rate_ppm < 0) &&
                        !(rd.drift_s < 0 && rd.rate_ppm > 0) && !(rd.drift_s == 0 && rd.rate_ppm != 0) &&
                        rd.interval_s >= RtcDrift::MIN_INTERVAL_S && rd.interval_s <= RtcDrift::MAX_INTERVAL_S;
    const bool synced = rd.has_sync && rd.last_sync_s >= RtcDrift::EPOCH_2000 && rd.last_sync_s < RtcDrift::EPOCH_2100;
    out[pos++] = static_cast<uint8_t>((synced ? 1 : 0) | (synced && figure ? 2 : 0));
    putU32(out, pos, synced ? rd.last_sync_s : 0);
    putU32(out, pos, synced && figure ? static_cast<uint32_t>(rd.drift_s) : 0);
    putU32(out, pos, synced && figure ? static_cast<uint32_t>(rd.rate_ppm) : 0);
    putU32(out, pos, synced && figure ? rd.interval_s : 0);
    putFloat(out, pos, state.config.temp_offset_c);
    putFloat(out, pos, state.config.freeze_alert_limit_c);
    out[pos++] = state.config.mold_alert_limit_rh;
    out[pos++] = state.config.chime_volume_pct;
    out[pos++] = state.config.quiet_hours_start_hour;
    out[pos++] = state.config.quiet_hours_end_hour;
    putFloat(out, pos, state.config.mic_spl_offset_db);
    out[pos++] = state.config.log_retention_months;
    out[pos++] = state.config.noise_alert_limit_db;
    out[pos++] = state.config.chime_alert_mask;
    out[pos++] = state.config.ntp_sync_interval_h;
    out[pos++] = static_cast<uint8_t>(state.config.wake_interval_s & 0xFF);
    out[pos++] = static_cast<uint8_t>(state.config.wake_interval_s >> 8);
    out[pos++] = static_cast<uint8_t>(state.config.sensor_stale_min & 0xFF);
    out[pos++] = static_cast<uint8_t>(state.config.sensor_stale_min >> 8);
    out[pos++] = state.config.outdoor_slot;
    out[pos++] = state.config.door_ref_slot;
    out[pos++] = state.config.ventilate_limit_rh;
    out[pos++] = state.config.sensor_battery_limit_pct;
    out[pos++] = state.config.display_invert;
    out[pos++] = static_cast<uint8_t>(state.config.climate_alert_tiles & 0xFF);
    out[pos++] = static_cast<uint8_t>((state.config.climate_alert_tiles >> 8) & 0xFF);
    out[pos++] = static_cast<uint8_t>(static_cast<uint16_t>(state.config.battery_offset_mv) & 0xFF);
    out[pos++] = static_cast<uint8_t>(static_cast<uint16_t>(state.config.battery_offset_mv) >> 8);
    out[pos++] = state.config.slot_release_h;
    out[pos++] = static_cast<uint8_t>(state.config.night_wake_interval_s & 0xFF);
    out[pos++] = static_cast<uint8_t>(state.config.night_wake_interval_s >> 8);
    out[pos++] = static_cast<uint8_t>(state.config.temp_unit);
    out[pos++] = state.config.chime_repeat_h;
    out[pos++] = state.config.heat_alert_limit_c;
    out[pos++] = state.config.dry_alert_limit_rh;
    out[pos++] = state.config.wall_frsi_pct;
    out[pos++] = state.config.ble_broadcast_enabled;
    out[pos++] = state.config.ble_console;
    out[pos++] = state.config.slot_auto_assign;
    {
      long usb_tenths = lroundf(state.config.usb_temp_offset_c * 10.0f);
      usb_tenths = usb_tenths < -50 ? -50 : (usb_tenths > 0 ? 0 : usb_tenths); // valid() range
      out[pos++] = static_cast<uint8_t>(static_cast<int8_t>(usb_tenths));
    }
    out[pos++] = static_cast<uint8_t>(static_cast<int8_t>(DeviceConfig::gainToSteps(state.config.rh_gain)));
    for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
      out[pos++] = static_cast<uint8_t>(static_cast<int8_t>(DeviceConfig::gainToSteps(state.config.slot_rh_gain[i])));
    }
    putFloat(out, pos, state.config.rh_offset_pct);
    for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
      putFloat(out, pos, state.config.slot_temp_offset_c[i]);
    }
    for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
      putFloat(out, pos, state.config.slot_rh_offset_pct[i]);
    }
    memcpy(out + pos, state.config.onboard_name, DeviceConfig::NAME_MAX_CHARS + 1);
    pos += DeviceConfig::NAME_MAX_CHARS + 1;
    for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
      memcpy(out + pos, state.config.slot_name[i], DeviceConfig::NAME_MAX_CHARS + 1);
      pos += DeviceConfig::NAME_MAX_CHARS + 1;
    }
    memcpy(out + pos, state.config.slot_mac, MACS_SIZE);
    pos += MACS_SIZE;
    out[pos++] = state.config_from_sd ? 1 : 0;
    out[pos++] = static_cast<uint8_t>(state.config.slot_enabled_mask & 0xFF);
    out[pos++] = static_cast<uint8_t>((state.config.slot_enabled_mask >> 8) & 0xFF);
    out[pos++] = state.config_rejects.count;
    for (size_t i = 0; i < ConfigRejects::MAX_LINES; ++i) {
      out[pos++] = static_cast<uint8_t>(state.config_rejects.lines[i] & 0xFF);
      out[pos++] = static_cast<uint8_t>(state.config_rejects.lines[i] >> 8);
    }
    out[pos++] = static_cast<uint8_t>(state.config.auto_assign_min_rssi);
    out[pos++] = state.config.mold_alert_delay_h;
    out[pos++] = state.config.reception_low_pct;
    out[pos++] = static_cast<uint8_t>(state.events.size());
    out[pos++] = static_cast<uint8_t>(state.events.dropped() & 0xFF);
    out[pos++] = static_cast<uint8_t>(state.events.dropped() >> 8);
    for (size_t i = 0; i < EventRing::CAPACITY; ++i) {
      const LogEvent e = state.events.at(i); // unused entries stay zero
      out[pos++] = e.has_time ? 1 : 0;
      out[pos++] = static_cast<uint8_t>(e.year & 0xFF);
      out[pos++] = static_cast<uint8_t>(e.year >> 8);
      out[pos++] = e.month;
      out[pos++] = e.day;
      out[pos++] = e.hour;
      out[pos++] = e.minute;
      out[pos++] = e.second;
      out[pos++] = static_cast<uint8_t>(e.code);
      out[pos++] = static_cast<uint8_t>(e.detail & 0xFF);
      out[pos++] = static_cast<uint8_t>(e.detail >> 8);
    }
    out[pos++] = static_cast<uint8_t>(state.shtc3.nack & 0xFF);
    out[pos++] = static_cast<uint8_t>(state.shtc3.nack >> 8);
    out[pos++] = static_cast<uint8_t>(state.shtc3.short_reads & 0xFF);
    out[pos++] = static_cast<uint8_t>(state.shtc3.short_reads >> 8);
    out[pos++] = static_cast<uint8_t>(state.shtc3.crc & 0xFF);
    out[pos++] = static_cast<uint8_t>(state.shtc3.crc >> 8);
    out[pos++] = state.shtc3.run;
    out[pos++] = static_cast<uint8_t>(state.shtc3.retried & 0xFF);
    out[pos++] = static_cast<uint8_t>(state.shtc3.retried >> 8);
    state.awake.serialize(out + pos);
    pos += AWAKE_SIZE;
    state.frame.serialize(out + pos);
    pos += FRAME_SIZE;
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      const uint32_t m = state.reception.mask(i);
      out[pos++] = static_cast<uint8_t>(m & 0xFF);
      out[pos++] = static_cast<uint8_t>((m >> 8) & 0xFF);
      out[pos++] = static_cast<uint8_t>((m >> 16) & 0xFF);
      out[pos++] = state.reception.scans(i);
    }
    out[pos++] = static_cast<uint8_t>(state.dry_tiles & 0xFF);
    out[pos++] = static_cast<uint8_t>((state.dry_tiles >> 8) & 0xFF);
    state.week.serialize(out + pos);
    pos += WEEK_SIZE;
    out[pos++] = static_cast<uint8_t>(state.history.usb_mask & 0xFF);
    out[pos++] = static_cast<uint8_t>((state.history.usb_mask >> 8) & 0xFF);
    out[pos++] = static_cast<uint8_t>((state.history.usb_mask >> 16) & 0xFF);
    state.reject_log.serialize(out + pos);
    pos += REJECT_LOG_SIZE;
    out[pos++] = state.alert_ack;
    out[pos++] = static_cast<uint8_t>(state.stale_slots & 0xFF);
    out[pos++] = static_cast<uint8_t>((state.stale_slots >> 8) & 0xFF);
    putU32(out, pos, static_cast<uint32_t>(state.sd_free_mb));
    out[pos++] = state.scan_fault;
    {
      long tenths = lroundf(state.config.window_drop_c * 10.0f);
      tenths = tenths < 0 ? 0 : (tenths > 50 ? 50 : tenths); // valid() range
      out[pos++] = static_cast<uint8_t>(tenths);
    }
    out[pos++] = static_cast<uint8_t>(state.window_tiles & 0xFF);
    out[pos++] = static_cast<uint8_t>((state.window_tiles >> 8) & 0xFF);
    for (size_t i = 0; i < WindowLatch::TILE_COUNT; ++i) {
      const uint16_t ref = static_cast<uint16_t>(state.window_anchors.ref[i]);
      out[pos++] = static_cast<uint8_t>(ref & 0xFF);
      out[pos++] = static_cast<uint8_t>(ref >> 8);
      const uint16_t prev = static_cast<uint16_t>(state.window_anchors.prev[i]);
      out[pos++] = static_cast<uint8_t>(prev & 0xFF);
      out[pos++] = static_cast<uint8_t>(prev >> 8);
      putU32(out, pos, state.window_anchors.stamp_ms[i]);
    }
    out[pos++] = static_cast<uint8_t>(state.wall_tiles & 0xFF);
    out[pos++] = static_cast<uint8_t>((state.wall_tiles >> 8) & 0xFF);
    putU32(out, pos, state.snooze.start_ms);
    out[pos++] = static_cast<uint8_t>(state.snooze.minutes & 0xFF);
    out[pos++] = static_cast<uint8_t>(state.snooze.minutes >> 8);
    out[pos++] = static_cast<uint8_t>(state.reception_low & 0xFF);
    out[pos++] = static_cast<uint8_t>((state.reception_low >> 8) & 0xFF);
    out[pos++] = static_cast<uint8_t>(state.drift_tiles & 0xFF);
    out[pos++] = static_cast<uint8_t>((state.drift_tiles >> 8) & 0xFF);
    putU32(out, pos, crc32(out, pos));
    return pos == SERIALIZED_SIZE;
  }

  // Rejects wrong size, magic, version, checksum, and out-of-range content. On failure, `out`
  // keeps its default values.
  static bool deserialize(const uint8_t* data, size_t len, PersistentState& out) {
    out = PersistentState{};
    if (data == nullptr || len != SERIALIZED_SIZE) {
      return false;
    }
    const size_t crc_pos = SERIALIZED_SIZE - 4;
    size_t pos = crc_pos;
    if (getU32(data, pos) != crc32(data, crc_pos)) {
      return false;
    }

    PersistentState s;
    pos = 0;
    if (getU32(data, pos) != STATE_MAGIC || data[pos++] != STATE_VERSION) {
      return false;
    }
    const uint8_t mode = data[pos++];
    if (mode >= DISPLAY_MODE_COUNT) {
      return false;
    }
    s.mode = static_cast<DisplayMode>(mode);
    s.chart_sensor = data[pos++];
    if (s.chart_sensor >= CHART_SENSOR_COUNT) {
      return false;
    }
    s.stats_page = data[pos++];
    if (s.stats_page >= STATS_PAGE_COUNT) {
      return false;
    }
    const uint8_t flags = data[pos++];
    if ((flags & ~15u) != 0) {
      return false;
    }
    s.battery.valid = (flags & 1) != 0;
    s.battery.usb_powered = (flags & 2) != 0;
    s.battery.low_battery = (flags & 4) != 0;
    s.battery.empty_protection = (flags & 8) != 0;
    s.battery.percentage = static_cast<int8_t>(data[pos++]);
    s.battery.voltage_v = getFloat(data, pos);
    if (s.battery.percentage < -1 || s.battery.percentage > 100 ||
        !(s.battery.voltage_v >= 0.0f && s.battery.voltage_v <= 10.0f)) {
      return false;
    }
    s.boot_count = getU32(data, pos);
    s.last_scan_ms = getU32(data, pos);

    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      const uint8_t valid = data[pos++];
      if (valid > 1) {
        return false;
      }
      if (valid == 0) {
        pos += SLOT_SIZE - 1;
        continue;
      }
      SwitchBotReading r;
      r.valid = true;
      memcpy(r.mac, data + pos, 6);
      pos += 6;
      r.temperature_c = getFloat(data, pos);
      r.humidity_pct = data[pos++];
      r.battery_pct = static_cast<int8_t>(data[pos++]);
      const uint8_t fahrenheit = data[pos++];
      r.rssi = static_cast<int8_t>(data[pos++]);
      const uint8_t fmt = data[pos++];
      r.updated_ms = getU32(data, pos);
      r.packet_count = getU32(data, pos);
      if (!(r.temperature_c >= -100.0f && r.temperature_c <= 100.0f) || r.humidity_pct > 100 ||
          r.battery_pct < -1 || r.battery_pct > 100 || fahrenheit > 1 || fmt > static_cast<uint8_t>(BleFormat::THERMOPRO)) {
        return false;
      }
      r.fahrenheit_mode = fahrenheit == 1;
      r.format = static_cast<BleFormat>(fmt);
      if (!s.registry.restoreSlot(i, r)) {
        return false; // duplicate MAC
      }
    }
    for (size_t i = 0; i < ClimateHistory::SLOT_COUNT; ++i) {
      const uint8_t count = data[pos++];
      const uint8_t head = data[pos++];
      int16_t samples[HumidityHistory::CAPACITY];
      for (size_t k = 0; k < HumidityHistory::CAPACITY; ++k) {
        samples[k] = static_cast<int16_t>(
            static_cast<uint16_t>(data[pos]) | (static_cast<uint16_t>(data[pos + 1]) << 8));
        pos += 2;
      }
      if (!s.history.slots[i].restore(count, head, samples)) {
        return false;
      }
      const uint8_t temp_count = data[pos++];
      const uint8_t temp_head = data[pos++];
      int16_t temp_samples[TemperatureHistory::CAPACITY];
      for (size_t k = 0; k < TemperatureHistory::CAPACITY; ++k) {
        temp_samples[k] = static_cast<int16_t>(
            static_cast<uint16_t>(data[pos]) | (static_cast<uint16_t>(data[pos + 1]) << 8));
        pos += 2;
      }
      if (!s.history.temperatures[i].restore(temp_count, temp_head, temp_samples)) {
        return false;
      }
    }
    s.history.last_sample_ms = getU32(data, pos);
    const uint8_t sampled = data[pos++];
    if (sampled > 1) {
      return false;
    }
    s.history.has_sampled = sampled == 1;
    const uint8_t noise_count = data[pos++];
    const uint8_t noise_head = data[pos++];
    uint8_t noise_samples[NoiseHistory::CAPACITY];
    for (size_t k = 0; k < NoiseHistory::CAPACITY; ++k) {
      noise_samples[k] = data[pos++];
    }
    if (!s.noise_history.restore(noise_count, noise_head, noise_samples)) {
      return false;
    }
    const uint8_t bat_count = data[pos++];
    const uint8_t bat_head = data[pos++];
    uint16_t bat_samples[BatteryHistory::CAPACITY];
    for (size_t k = 0; k < BatteryHistory::CAPACITY; ++k) {
      bat_samples[k] = static_cast<uint16_t>(
          static_cast<uint16_t>(data[pos]) | (static_cast<uint16_t>(data[pos + 1]) << 8));
      pos += 2;
    }
    uint32_t bat_stamps[BatteryHistory::CAPACITY];
    for (size_t k = 0; k < BatteryHistory::CAPACITY; ++k) {
      bat_stamps[k] = getU32(data, pos);
    }
    if (!s.battery_history.restore(bat_count, bat_head, bat_samples, bat_stamps)) {
      return false;
    }
    s.battery_history.last_sample_ms = getU32(data, pos);
    const uint8_t bat_sampled = data[pos++];
    if (bat_sampled > 1) {
      return false;
    }
    s.battery_history.has_sampled = bat_sampled == 1;
    s.last_log_ms = getU32(data, pos);
    const uint8_t logged = data[pos++];
    if (logged > 1) {
      return false;
    }
    s.has_logged = logged == 1;
    const uint8_t daily_flag = data[pos++];
    const uint16_t daily_year = static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8));
    pos += 2;
    const uint8_t daily_month = data[pos++];
    const uint8_t daily_day = data[pos++];
    if (daily_flag > 1) {
      return false;
    }
    if (daily_flag == 1) {
      if (daily_year < 2000 || daily_year > 2099 || daily_month < 1 || daily_month > 12 || daily_day < 1 ||
          !NtpTime::isValidDate(daily_year, daily_month, daily_day)) {
        return false;
      }
      s.daily.has_date = true;
      s.daily.year = daily_year;
      s.daily.month = daily_month;
      s.daily.day = daily_day;
    }
    const uint8_t peak_db = data[pos++];
    const uint8_t peak_hour = data[pos++];
    const uint16_t events_sum = static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8));
    pos += 2;
    if (peak_db == DailyNoiseStats::NO_PEAK) {
      if (peak_hour != 0 || events_sum != 0) {
        return false;
      }
    } else if (!NoiseHistory::isValid(peak_db) || peak_hour > 23) {
      return false;
    }
    s.daily.noise.peak_db = peak_db;
    s.daily.noise.peak_hour = peak_hour;
    s.daily.noise.events_sum = events_sum;
    const uint8_t sd_status = data[pos++];
    if (sd_status > SdStatusCodec::MAX_VALUE) {
      return false;
    }
    s.sd_status = static_cast<SdStatus>(sd_status);
    s.sd_status_ms = getU32(data, pos);
    s.last_chime_ms = getU32(data, pos);
    const uint8_t vent = data[pos++];
    if (vent > 7) {
      return false;
    }
    const uint8_t audio = data[pos++];
    const uint8_t band = static_cast<uint8_t>((audio >> 6) & 3);
    if (band > static_cast<uint8_t>(NoiseBand::LOUD)) {
      return false;
    }
    s.muted = (audio & 1) != 0;
    s.alert_mask = static_cast<uint8_t>(((audio >> 1) & (ALERT_MASK_ALL & ~ALERT_VENTILATE & ~ALERT_SENSOR_BATTERY & ~ALERT_HEAT)) |
                                        ((vent & 1) != 0 ? ALERT_VENTILATE : 0) |
                                        ((vent & 2) != 0 ? ALERT_SENSOR_BATTERY : 0) |
                                        ((vent & 4) != 0 ? ALERT_HEAT : 0));
    s.noise_band = static_cast<NoiseBand>(band);
    s.sensor_battery_latch = static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8));
    pos += 2;
    if ((s.sensor_battery_latch & ~SensorBatteryLatch::ALL_MASK) != 0) {
      return false;
    }
    s.ventilate_tiles = static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8));
    pos += 2;
    if ((s.ventilate_tiles & ~VentilateLatch::ALL_MASK) != 0) {
      return false;
    }
    s.ntp.last_attempt_ms = getU32(data, pos);
    const uint8_t ntp_flags = data[pos++];
    s.ntp.failures = data[pos++];
    // A success needs an attempt and leaves no failures; the failure count has a ceiling.
    // Without an attempt, the stamp and the failure count stay zero.
    if ((ntp_flags & ~3u) != 0 || ((ntp_flags & 2) != 0 && (ntp_flags & 1) == 0) ||
        ((ntp_flags & 1) == 0 && (s.ntp.failures != 0 || s.ntp.last_attempt_ms != 0)) ||
        s.ntp.failures > NtpSchedule::MAX_FAILURES ||
        ((ntp_flags & 2) != 0 && s.ntp.failures != 0)) {
      return false;
    }
    s.ntp.has_attempted = (ntp_flags & 1) != 0;
    s.ntp.last_ok = (ntp_flags & 2) != 0;
    const uint8_t drift_flags = data[pos++];
    s.rtc_drift.last_sync_s = getU32(data, pos);
    s.rtc_drift.drift_s = static_cast<int32_t>(getU32(data, pos));
    s.rtc_drift.rate_ppm = static_cast<int32_t>(getU32(data, pos));
    s.rtc_drift.interval_s = getU32(data, pos);
    s.rtc_drift.has_sync = (drift_flags & 1) != 0;
    s.rtc_drift.has_drift = (drift_flags & 2) != 0;
    // A figure needs a sync; without a figure the values stay zero; both stay within their limits,
    // the rate has the sign of the drift, and the sync time lies in 2000..2099.
    if ((drift_flags & ~3u) != 0 || (s.rtc_drift.has_drift && !s.rtc_drift.has_sync) ||
        (!s.rtc_drift.has_sync && s.rtc_drift.last_sync_s != 0) ||
        (!s.rtc_drift.has_drift && (s.rtc_drift.drift_s != 0 || s.rtc_drift.rate_ppm != 0 || s.rtc_drift.interval_s != 0)) ||
        s.rtc_drift.drift_s > RtcDrift::MAX_DRIFT_S || s.rtc_drift.drift_s < -RtcDrift::MAX_DRIFT_S ||
        s.rtc_drift.rate_ppm > RtcDrift::MAX_PPM || s.rtc_drift.rate_ppm < -RtcDrift::MAX_PPM ||
        (s.rtc_drift.has_sync && (s.rtc_drift.last_sync_s < RtcDrift::EPOCH_2000 ||
                                  s.rtc_drift.last_sync_s >= RtcDrift::EPOCH_2100)) ||
        (s.rtc_drift.has_drift && (s.rtc_drift.interval_s < RtcDrift::MIN_INTERVAL_S ||
                                   s.rtc_drift.interval_s > RtcDrift::MAX_INTERVAL_S)) ||
        (s.rtc_drift.drift_s == 0 && s.rtc_drift.rate_ppm != 0) ||
        (s.rtc_drift.drift_s > 0 && s.rtc_drift.rate_ppm < 0) ||
        (s.rtc_drift.drift_s < 0 && s.rtc_drift.rate_ppm > 0)) {
      return false;
    }
    s.config.temp_offset_c = getFloat(data, pos);
    s.config.freeze_alert_limit_c = getFloat(data, pos);
    s.config.mold_alert_limit_rh = data[pos++];
    s.config.chime_volume_pct = data[pos++];
    s.config.quiet_hours_start_hour = data[pos++];
    s.config.quiet_hours_end_hour = data[pos++];
    s.config.mic_spl_offset_db = getFloat(data, pos);
    s.config.log_retention_months = data[pos++];
    s.config.noise_alert_limit_db = data[pos++];
    s.config.chime_alert_mask = data[pos++];
    s.config.ntp_sync_interval_h = data[pos++];
    s.config.wake_interval_s = static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8));
    pos += 2;
    s.config.sensor_stale_min = static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8));
    pos += 2;
    s.config.outdoor_slot = data[pos++];
    s.config.door_ref_slot = data[pos++];
    s.config.ventilate_limit_rh = data[pos++];
    s.config.sensor_battery_limit_pct = data[pos++];
    s.config.display_invert = data[pos++];
    s.config.climate_alert_tiles = static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8));
    pos += 2;
    s.config.battery_offset_mv = static_cast<int16_t>(static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8)));
    pos += 2;
    s.config.slot_release_h = data[pos++];
    s.config.night_wake_interval_s = static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8));
    pos += 2;
    s.config.temp_unit = static_cast<char>(data[pos++]);
    s.config.chime_repeat_h = data[pos++];
    s.config.heat_alert_limit_c = data[pos++];
    s.config.dry_alert_limit_rh = data[pos++];
    s.config.wall_frsi_pct = data[pos++];
    s.config.ble_broadcast_enabled = data[pos++];
    s.config.ble_console = data[pos++];
    s.config.slot_auto_assign = data[pos++];
    s.config.usb_temp_offset_c = static_cast<float>(static_cast<int8_t>(data[pos++])) / 10.0f;
    {
      const int8_t g = static_cast<int8_t>(data[pos++]);
      if (g < -DeviceConfig::RH_GAIN_MAX_STEPS || g > DeviceConfig::RH_GAIN_MAX_STEPS) {
        return false;
      }
      s.config.rh_gain = DeviceConfig::gainFromSteps(g);
    }
    for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
      const int8_t g = static_cast<int8_t>(data[pos++]);
      if (g < -DeviceConfig::RH_GAIN_MAX_STEPS || g > DeviceConfig::RH_GAIN_MAX_STEPS) {
        return false;
      }
      s.config.slot_rh_gain[i] = DeviceConfig::gainFromSteps(g);
    }
    s.config.rh_offset_pct = getFloat(data, pos);
    for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
      s.config.slot_temp_offset_c[i] = getFloat(data, pos);
    }
    for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
      s.config.slot_rh_offset_pct[i] = getFloat(data, pos);
    }
    memcpy(s.config.onboard_name, data + pos, DeviceConfig::NAME_MAX_CHARS + 1);
    pos += DeviceConfig::NAME_MAX_CHARS + 1;
    for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
      memcpy(s.config.slot_name[i], data + pos, DeviceConfig::NAME_MAX_CHARS + 1);
      pos += DeviceConfig::NAME_MAX_CHARS + 1;
    }
    memcpy(s.config.slot_mac, data + pos, MACS_SIZE);
    pos += MACS_SIZE;
    const uint8_t config_flags = data[pos++];
    s.config.slot_enabled_mask = static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8));
    pos += 2;
    s.config_rejects.count = data[pos++];
    for (size_t i = 0; i < ConfigRejects::MAX_LINES; ++i) {
      s.config_rejects.lines[i] = static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8));
      pos += 2;
    }
    s.config.auto_assign_min_rssi = static_cast<int8_t>(data[pos++]);
    s.config.mold_alert_delay_h = data[pos++];
    s.config.reception_low_pct = data[pos++];
    if ((config_flags & ~1u) != 0 || !s.config.valid() || !rejectsValid(s.config_rejects)) {
      return false; // a corrupt record never reaches the alert limits
    }
    const uint8_t event_count = data[pos++];
    const uint16_t event_dropped = static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8));
    pos += 2;
    LogEvent event_items[EventRing::CAPACITY];
    for (size_t i = 0; i < EventRing::CAPACITY; ++i) {
      const uint8_t time_flag = data[pos++];
      LogEvent& e = event_items[i];
      e.year = static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8));
      pos += 2;
      e.month = data[pos++];
      e.day = data[pos++];
      e.hour = data[pos++];
      e.minute = data[pos++];
      e.second = data[pos++];
      e.code = static_cast<EventCode>(data[pos++]);
      e.detail = static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8));
      pos += 2;
      if (time_flag > 1) {
        return false;
      }
      e.has_time = time_flag == 1;
    }
    if (!s.events.restore(event_count, event_dropped, event_items)) {
      return false; // a corrupt ring never reaches the card
    }
    s.shtc3.nack = static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8));
    s.shtc3.short_reads = static_cast<uint16_t>(data[pos + 2] | (data[pos + 3] << 8));
    s.shtc3.crc = static_cast<uint16_t>(data[pos + 4] | (data[pos + 5] << 8));
    s.shtc3.run = data[pos + 6];
    s.shtc3.retried = static_cast<uint16_t>(data[pos + 7] | (data[pos + 8] << 8));
    pos += Shtc3Health::SERIALIZED_SIZE;
    if (!s.shtc3.consistent()) {
      return false; // a run longer than the counted failures is corrupt
    }
    if (!s.awake.restore(data + pos)) {
      return false;
    }
    pos += AWAKE_SIZE;
    if (!s.frame.restore(data + pos)) {
      return false;
    }
    pos += FRAME_SIZE;
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      const uint32_t m = static_cast<uint32_t>(data[pos]) | (static_cast<uint32_t>(data[pos + 1]) << 8) |
                         (static_cast<uint32_t>(data[pos + 2]) << 16);
      if ((!s.registry.slot(i).valid && (m != 0 || data[pos + 3] != 0)) ||
          !s.reception.restore(i, m, data[pos + 3])) {
        return false; // a count above 24 or a mask beyond the counted scans is corrupt
      }
      pos += 4;
    }
    s.dry_tiles = static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8));
    pos += 2;
    if ((s.dry_tiles & ~DryLatch::ALL_MASK) != 0) {
      return false;
    }
    if (!s.week.restore(data + pos)) {
      return false;
    }
    pos += WEEK_SIZE;
    if (!s.history.restoreUsbMask(static_cast<uint32_t>(data[pos]) | (static_cast<uint32_t>(data[pos + 1]) << 8) |
                                  (static_cast<uint32_t>(data[pos + 2]) << 16))) {
      return false; // a bit beyond the onboard samples is corrupt
    }
    pos += USB_MASK_SIZE;
    if (!s.reject_log.restore(data + pos)) {
      return false; // a bad reason, a zero count or a gap is corrupt
    }
    pos += REJECT_LOG_SIZE;
    s.alert_ack = data[pos++];
    if ((s.alert_ack & ~s.alert_mask) != 0) {
      return false; // an acknowledged bit of an alert that is off is corrupt
    }
    s.stale_slots = static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8));
    pos += 2;
    if ((s.stale_slots >> MAX_SWITCHBOT_SENSORS) != 0) {
      return false; // a bit above bit 9 is corrupt
    }
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      if ((s.stale_slots & (1u << i)) != 0 && !s.registry.slot(i).valid) {
        return false; // a stale bit of an empty slot is corrupt
      }
    }
    s.sd_free_mb = static_cast<int32_t>(getU32(data, pos));
    if (s.sd_free_mb < -1) {
      return false;
    }
    s.scan_fault = data[pos++];
    if (s.scan_fault > 1) {
      return false; // only 0 and 1 are valid
    }
    s.config.window_drop_c = static_cast<float>(data[pos++]) / 10.0f;
    if (!s.config.valid()) {
      return false; // a limit outside 0 or 1.0 to 5.0 C is corrupt
    }
    s.window_tiles = static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8));
    pos += 2;
    if ((s.window_tiles & ~WindowLatch::ALL_MASK) != 0) {
      return false; // a bit above bit 10 is corrupt
    }
    for (size_t i = 0; i < WindowLatch::TILE_COUNT; ++i) {
      s.window_anchors.ref[i] = static_cast<int16_t>(static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8)));
      pos += 2;
      s.window_anchors.prev[i] = static_cast<int16_t>(static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8)));
      pos += 2;
      s.window_anchors.stamp_ms[i] = getU32(data, pos);
      if (!WindowLatch::anchorValid((s.window_tiles & (1u << i)) != 0, s.window_anchors.ref[i],
                                    s.window_anchors.prev[i], s.window_anchors.stamp_ms[i])) {
        return false; // an open tile without a reference, or a reference out of range, is corrupt
      }
    }
    s.wall_tiles = static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8));
    pos += 2;
    if ((s.wall_tiles & ~WallLatch::ALL_MASK) != 0) {
      return false; // a bit above bit 10 is corrupt
    }
    s.snooze.start_ms = getU32(data, pos);
    s.snooze.minutes = static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8));
    pos += 2;
    if (!s.snooze.valid()) {
      return false; // minutes above 720 or a stamp without minutes is corrupt
    }
    s.reception_low = static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8));
    pos += 2;
    if ((s.reception_low >> MAX_SWITCHBOT_SENSORS) != 0) {
      return false; // a bit above bit 9 is corrupt
    }
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      if ((s.reception_low & (1u << i)) != 0 && !s.registry.slot(i).valid) {
        return false; // a low reception bit of an empty slot is corrupt
      }
    }
    s.drift_tiles = static_cast<uint16_t>(data[pos] | (data[pos + 1] << 8));
    pos += 2;
    if ((s.drift_tiles & ~DriftLatch::ALL_MASK) != 0) {
      return false; // a bit above bit 10 is corrupt
    }
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      if ((s.drift_tiles & (2u << i)) != 0 && !s.registry.slot(i).valid) {
        return false; // a drift bit of an empty slot is corrupt
      }
    }
    s.registry.setAutoAssign(s.config.slot_auto_assign != 0);
    s.registry.setMinAssignRssi(s.config.auto_assign_min_rssi);
    for (size_t i = 0; i < DeviceConfig::SLOT_COUNT; ++i) {
      if (!s.config.isSlotEnabled(i) && s.registry.slot(i).valid) {
        return false; // disabled slot must not have a valid reading in consistent state
      }
      s.registry.setSlotEnabled(i, s.config.isSlotEnabled(i));
      s.registry.setFixedMac(i, s.config.hasSlotMac(i) ? s.config.slot_mac[i] : nullptr);
    }
    if (!s.registry.consistent()) {
      return false; // a stored reading breaks a fixed MAC
    }
    s.config_from_sd = (config_flags & 1) != 0;
    out = s;
    return true;
  }

  // A recorded line number is 1 or more; an unused entry is 0.
  static bool rejectsValid(const ConfigRejects& r) {
    for (size_t i = 0; i < ConfigRejects::MAX_LINES; ++i) {
      if ((i < r.recorded()) != (r.lines[i] != 0)) {
        return false;
      }
    }
    return true;
  }

  // A restored state is consistent when no timestamp lies in the future of the current clock.
  // A reset clock with intact RTC memory breaks this and forces a clean start with a scan. The
  // 32-bit clock wraps after 49.7 days; the same clean start then runs once, which is harmless.
  static bool timestampsConsistent(const PersistentState& state, uint32_t now_ms) {
    // A chime stamp in the future also forces a clean start: the reminder timer then restarts with the
    // alert mask. The history, the SD log gate and the NTP schedule are exempt: they use wrap-safe subtraction, so a reset or
    // wrapped clock only triggers an early sample or record. The history keeps its 24 hours.
    if (state.last_scan_ms > now_ms || state.last_chime_ms > now_ms) {
      return false;
    }
    for (size_t i = 0; i < MAX_SWITCHBOT_SENSORS; ++i) {
      const SwitchBotReading& r = state.registry.slot(i);
      if (r.valid && r.updated_ms > now_ms) {
        return false;
      }
    }
    return true;
  }

  // Restores state for a wakeup. Returns false and leaves defaults when the cause keeps no
  // state or when the stored bytes fail validation.
  static bool restore(WakeupCause cause, const uint8_t* data, size_t len, PersistentState& out) {
    if (!stateSurvives(cause)) {
      out = PersistentState{};
      return false;
    }
    return deserialize(data, len, out);
  }

private:
  static void putU32(uint8_t* buf, size_t& pos, uint32_t v) {
    for (int i = 0; i < 4; ++i) {
      buf[pos++] = static_cast<uint8_t>(v >> (8 * i));
    }
  }

  static uint32_t getU32(const uint8_t* buf, size_t& pos) {
    uint32_t v = 0;
    for (int i = 0; i < 4; ++i) {
      v |= static_cast<uint32_t>(buf[pos++]) << (8 * i);
    }
    return v;
  }

  static void putFloat(uint8_t* buf, size_t& pos, float f) {
    uint32_t bits;
    memcpy(&bits, &f, sizeof(bits));
    putU32(buf, pos, bits);
  }

  static float getFloat(const uint8_t* buf, size_t& pos) {
    const uint32_t bits = getU32(buf, pos);
    float f;
    memcpy(&f, &bits, sizeof(f));
    return f;
  }
};

// State machine for one wake cycle: BOOT -> RESTORE -> [SCAN] -> RENDER -> SAVE -> SLEEP.
class PowerCycle {
public:
  void begin(WakeupCause cause) {
    cause_ = cause;
    phase_ = PowerPhase::RESTORE;
  }

  PowerPhase phase() const {
    return phase_;
  }

  WakeupCause cause() const {
    return cause_;
  }

  // Moves to the next phase and returns it. SLEEP is final.
  PowerPhase advance() {
    switch (phase_) {
      case PowerPhase::BOOT: phase_ = PowerPhase::RESTORE; break;
      case PowerPhase::RESTORE:
        phase_ = PowerManager::needsScan(cause_) ? PowerPhase::SCAN : PowerPhase::RENDER;
        break;
      case PowerPhase::SCAN: phase_ = PowerPhase::RENDER; break;
      case PowerPhase::RENDER: phase_ = PowerPhase::SAVE; break;
      case PowerPhase::SAVE: phase_ = PowerPhase::SLEEP; break;
      case PowerPhase::SLEEP: break;
    }
    return phase_;
  }

private:
  WakeupCause cause_ = WakeupCause::POWER_ON;
  PowerPhase phase_ = PowerPhase::BOOT;
};

// Reset reason of the chip. The values match `esp_reset_reason_t` (checked on the board).
enum class ResetReason : uint8_t {
  UNKNOWN = 0,
  POWERON = 1,
  EXT = 2,
  SW = 3,
  PANIC = 4,
  INT_WDT = 5,
  TASK_WDT = 6,
  WDT = 7,
  DEEPSLEEP = 8,
  BROWNOUT = 9,
  SDIO = 10,
};

class ResetReasonCodec {
public:
  static ResetReason decode(int esp_reason) {
    return esp_reason >= 1 && esp_reason <= 10 ? static_cast<ResetReason>(esp_reason) : ResetReason::UNKNOWN;
  }

  static const char* text(ResetReason r) {
    switch (r) {
      case ResetReason::POWERON: return "POWERON";
      case ResetReason::EXT: return "EXT";
      case ResetReason::SW: return "SW";
      case ResetReason::PANIC: return "PANIC";
      case ResetReason::INT_WDT: return "INT_WDT";
      case ResetReason::TASK_WDT: return "TASK_WDT";
      case ResetReason::WDT: return "WDT";
      case ResetReason::DEEPSLEEP: return "DEEPSLEEP";
      case ResetReason::BROWNOUT: return "BROWNOUT";
      case ResetReason::SDIO: return "SDIO";
      default: return "UNKNOWN";
    }
  }

  // True when the start gets a RESET event: each start except a deep sleep wakeup.
  static bool recordsEvent(ResetReason r) {
    return r != ResetReason::DEEPSLEEP;
  }

  // Detail of the RESET event of this start, or -1 for no event. A failed RTC restore after a deep sleep
  // wakeup also counts as a start: the ring is gone, the wakeup cause is a power-on, the reason is unknown.
  static int32_t eventDetail(ResetReason r, bool restore_failed) {
    if (recordsEvent(r)) {
      return static_cast<int32_t>(r);
    }
    return restore_failed ? static_cast<int32_t>(ResetReason::UNKNOWN) : -1;
  }

  // A crash, a watchdog or a brownout. A power-on, a deep sleep wakeup and a software reset are normal.
  static bool isAbnormal(ResetReason r) {
    return r == ResetReason::PANIC || r == ResetReason::INT_WDT || r == ResetReason::TASK_WDT ||
           r == ResetReason::WDT || r == ResetReason::BROWNOUT;
  }
};

// Count of abnormal resets in NVS. `Store` provides `bool load(uint16_t&)` and `bool save(uint16_t)`
// (ResetCountStore on the board). Only an abnormal reset writes; a deep sleep wakeup never does.
class AbnormalResetCounter {
public:
  static constexpr uint16_t MAX_COUNT = 65535;

  // Call once per boot. Returns false when NVS failed to read or to write.
  template <typename Store>
  static bool onBoot(ResetReason reason, Store& store) {
    if (!ResetReasonCodec::isAbnormal(reason)) {
      return true;
    }
    uint16_t count = 0;
    if (!store.load(count)) {
      return false;
    }
    if (count == MAX_COUNT) {
      return true; // saturated: no write
    }
    return store.save(static_cast<uint16_t>(count + 1));
  }

  // The count for STATUS, or -1 when NVS is unreadable.
  template <typename Store>
  static int32_t read(Store& store) {
    uint16_t count = 0;
    return store.load(count) ? static_cast<int32_t>(count) : -1;
  }
};

#if defined(ARDUINO) || defined(ESP_PLATFORM)

static_assert(ESP_RST_POWERON == 1 && ESP_RST_EXT == 2 && ESP_RST_SW == 3 && ESP_RST_PANIC == 4 &&
                  ESP_RST_INT_WDT == 5 && ESP_RST_TASK_WDT == 6 && ESP_RST_WDT == 7 && ESP_RST_DEEPSLEEP == 8 &&
                  ESP_RST_BROWNOUT == 9 && ESP_RST_SDIO == 10,
              "reset reason mapping");

// NVS storage of the abnormal reset count. A missing key reads as 0; an unreadable namespace fails.
class ResetCountStore {
public:
  bool load(uint16_t& count) const {
    Preferences prefs;
    if (!prefs.begin("rlcd", false)) { // read-write open creates the namespace on first use, as in CycleStore
      return false;
    }
    count = prefs.getUShort("abn_resets", 0);
    prefs.end();
    return true;
  }

  bool save(uint16_t count) const {
    Preferences prefs;
    if (!prefs.begin("rlcd", false)) {
      return false;
    }
    const bool ok = prefs.putUShort("abn_resets", count) == sizeof(uint16_t);
    prefs.end();
    return ok;
  }
};

static_assert(ESP_SLEEP_WAKEUP_UNDEFINED == 0, "wakeup cause mapping");
static_assert(ESP_SLEEP_WAKEUP_EXT0 == 2, "wakeup cause mapping");
static_assert(ESP_SLEEP_WAKEUP_EXT1 == 3, "wakeup cause mapping");
static_assert(ESP_SLEEP_WAKEUP_TIMER == 4, "wakeup cause mapping");

// Survives deep sleep. A power-on clears it; a reset keeps it, so restore() also checks the cause.
RTC_DATA_ATTR static uint8_t g_rtc_state_blob[PowerManager::SERIALIZED_SIZE];

class PowerPlatform {
public:
  static constexpr uint8_t kHeldDisplayPins[3] = {PIN_RLCD_CS, PIN_RLCD_DC, PIN_RLCD_RST};

  static ResetReason resetReason() {
    return ResetReasonCodec::decode(static_cast<int>(esp_reset_reason()));
  }

  static WakeupCause wakeupCause() {
    return PowerManager::decodeWakeupCause(static_cast<int>(esp_sleep_get_wakeup_cause()));
  }

  // Milliseconds of the system time. The RTC timer keeps it running through deep sleep, unlike
  // millis(). It starts at 0 on power-on; a reset may or may not keep it.
  static uint64_t clockMs64() {
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    return static_cast<uint64_t>(tv.tv_sec) * 1000ULL + tv.tv_usec / 1000;
  }

  // 32-bit view for timestamps in the persisted state. Wraps after 49.7 days.
  static uint32_t clockMs() {
    return static_cast<uint32_t>(clockMs64());
  }

  static bool loadState(WakeupCause cause, PersistentState& out) {
    return PowerManager::restore(cause, g_rtc_state_blob, sizeof(g_rtc_state_blob), out);
  }

  static bool saveState(const PersistentState& state) {
    if (PowerManager::serialize(state, g_rtc_state_blob, sizeof(g_rtc_state_blob))) {
      return true;
    }
    memset(g_rtc_state_blob, 0, sizeof(g_rtc_state_blob)); // never leave stale state behind
    return false;
  }

  // Returns the KEY pin from RTC control and frees the held display pins. Call it first in
  // setup(), before pinMode() and before the panel driver starts.
  static void releasePinsAfterSleep() {
    // The amplifier pin stayed held low; the audio driver sets its level when it needs it.
    gpio_hold_dis(static_cast<gpio_num_t>(PIN_PA_CTRL));
    rtc_gpio_deinit(static_cast<gpio_num_t>(PIN_BTN_KEY));
    for (const uint8_t pin : kHeldDisplayPins) {
      // Drive the idle level (high) before the release, so the pad never floats.
      const gpio_num_t gpio = static_cast<gpio_num_t>(pin);
      gpio_set_direction(gpio, GPIO_MODE_OUTPUT);
      gpio_set_level(gpio, 1);
      gpio_hold_dis(gpio);
    }
    gpio_deep_sleep_hold_dis();
  }

  // Does not return.
  static void enterDeepSleep(const WakeupPlan& plan) {
    if (esp_sleep_enable_timer_wakeup(plan.timer_us) != ESP_OK) {
      esp_restart(); // without a timer the board would sleep until a KEY press
    }
    // Hold CS, DC, and RST at their last level so a floating pin cannot disturb the panel.
    for (const uint8_t pin : kHeldDisplayPins) {
      gpio_hold_en(static_cast<gpio_num_t>(pin));
    }
    // Hold the speaker amplifier off so a floating pad cannot drain the battery in deep sleep.
    const gpio_num_t pa = static_cast<gpio_num_t>(PIN_PA_CTRL);
    gpio_set_direction(pa, GPIO_MODE_OUTPUT);
    gpio_set_level(pa, 0);
    gpio_hold_en(pa);
    gpio_deep_sleep_hold_en();
    if (plan.enable_key_wakeup) {
      const gpio_num_t key = static_cast<gpio_num_t>(PIN_BTN_KEY);
      rtc_gpio_init(key);
      rtc_gpio_set_direction(key, RTC_GPIO_MODE_INPUT_ONLY);
      rtc_gpio_pulldown_dis(key);
      rtc_gpio_pullup_en(key);
      esp_sleep_enable_ext0_wakeup(key, 0); // KEY is active low
    }
    esp_deep_sleep_start();
  }
};

#endif

} // namespace rlcd_panel
