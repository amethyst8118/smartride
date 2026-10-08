// Tunable constants for the SmartRide on-board unit.
// Values marked (report) come from the Phase 1 report; values marked (tune)
// are placeholders to be set from the controlled drop / tilt / road trials.
#pragma once

#include <stdint.h>

#ifndef FW_VERSION
#define FW_VERSION "0.0.0-dev"
#endif
#ifndef SMARTRIDE_BOARD
#define SMARTRIDE_BOARD "ESP32"
#endif
#ifndef BUILD_EPOCH
#define BUILD_EPOCH 1790000000UL
#endif

namespace cfg {

// ---- Ride statistics -------------------------------------------------------
constexpr double EARTH_RADIUS_M      = 6371000.0;  // (report) mean Earth radius
constexpr float  V_MAX_KMH           = 160.0f;     // (report) plausibility gate d/dt <= v_max
constexpr float  STATIONARY_STEP_M   = 2.0f;       // ignore position jitter below this...
constexpr float  STATIONARY_KMH      = 3.0f;       // ...when GNSS speed is also below this
constexpr float  ROUTE_POINT_STEP_M  = 25.0f;      // decimation of stored route points
constexpr uint32_t ROUTE_POINT_MAX_GAP_MS = 15000; // ...or at least one point every 15 s
constexpr uint16_t ROUTE_POINTS_MAX  = 600;

// ---- Crash rule (impact -> sustained tilt -> stillness) --------------------
constexpr float    IMPACT_G          = 4.0f;    // (report) nominal, (tune)
constexpr float    TILT_DEG          = 60.0f;   // (report) nominal, (tune)
constexpr uint32_t TILT_HOLD_MS      = 1000;    // (tune) tilt must persist this long
constexpr uint32_t TILT_WINDOW_MS    = 5000;    // (tune) tilt must start within this after impact
constexpr float    STILL_EPS_G       = 0.15f;   // (tune) | |a| - 1g | below this = still
constexpr uint32_t STILL_HOLD_MS     = 2000;    // (tune)
constexpr uint32_t STILL_WINDOW_MS   = 10000;   // (tune)
constexpr uint8_t  IMU_SMOOTH_N      = 10;      // moving average length (100 ms @ 100 Hz)

// ---- Rates -----------------------------------------------------------------
constexpr uint32_t GNSS_PERIOD_MS    = 1000;    // 1 Hz fixes
constexpr uint32_t IMU_PERIOD_MS     = 10;      // 100 Hz motion samples

// ---- Ride log store (RAM stub; NVS/LittleFS later) -------------------------
constexpr uint8_t  LOG_CAPACITY      = 8;
constexpr bool     ACK_DELETES_LOG   = true;
constexpr uint32_t LOG_FRAME_GAP_MS  = 6;       // pacing between notifications

// ---- Hardware --------------------------------------------------------------
constexpr int      BOOT_BUTTON_PIN   = 0;       // BOOT button = simulate crash
constexpr bool     AUTO_START_RIDE   = true;    // start a ride as soon as the unit boots

}  // namespace cfg
