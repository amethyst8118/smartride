// SmartRide BLE protocol v1 -- firmware side.
//
// This header is the C mirror of docs/ble-protocol.md and of
// android/app/src/main/java/com/smartride/app/ble/SmartRideProtocol.kt.
// Change all three together.
//
// All multi-byte fields are little-endian (native on Xtensa / RISC-V ESP32s).
#pragma once

#include <stdint.h>

namespace proto {

constexpr uint8_t VERSION = 1;

// ---- GATT identifiers --------------------------------------------------------
constexpr const char* SERVICE_UUID = "2a1a0001-eabf-4905-8f89-45578e517f0a";
constexpr const char* LIVE_UUID    = "2a1a0002-eabf-4905-8f89-45578e517f0a";  // read, notify
constexpr const char* CRASH_UUID   = "2a1a0003-eabf-4905-8f89-45578e517f0a";  // read, notify
constexpr const char* CONTROL_UUID = "2a1a0004-eabf-4905-8f89-45578e517f0a";  // write
constexpr const char* LOG_UUID     = "2a1a0005-eabf-4905-8f89-45578e517f0a";  // notify

constexpr uint16_t PREFERRED_MTU = 247;

// ---- Scaling ----------------------------------------------------------------
constexpr double   COORD_SCALE = 1e7;   // degrees  -> int32
constexpr float    SPEED_SCALE = 100.f; // km/h     -> uint16 (0.01 km/h)
constexpr float    G_SCALE     = 100.f; // g        -> uint16 (0.01 g)
constexpr uint8_t  BATTERY_UNKNOWN = 0xFF;

// ---- LIVE characteristic (24 bytes, 1 Hz) -----------------------------------
enum LiveFlags : uint8_t {
    LIVE_GNSS_FIX     = 1 << 0,
    LIVE_RIDE_ACTIVE  = 1 << 1,
    LIVE_IMU_OK       = 1 << 2,
    LIVE_SIMULATED    = 1 << 3,  // data comes from the simulator, not real sensors
    LIVE_CRASH_PENDING= 1 << 4,
    LIVE_TIME_SYNCED  = 1 << 5,  // device clock was set by SET_TIME (or GNSS)
};

struct __attribute__((packed)) LivePacket {
    uint8_t  version;       // = VERSION
    uint8_t  flags;         // LiveFlags
    uint8_t  sats;          // satellites in use
    uint8_t  batteryPct;    // 0..100, BATTERY_UNKNOWN if not measured
    uint16_t speed;         // 0.01 km/h
    uint16_t batteryMv;     // unit battery voltage in mV, 0 = not measured
    uint32_t rideDistanceM; // metres in the current ride
    uint32_t rideDurationS; // seconds in the current ride
    int32_t  lat;           // 1e-7 deg
    int32_t  lon;           // 1e-7 deg
};
static_assert(sizeof(LivePacket) == 24, "LivePacket must be 24 bytes");

// ---- CRASH characteristic (16 bytes, on change) -----------------------------
enum CrashState : uint8_t {
    CRASH_IDLE      = 0,
    CRASH_IMPACT    = 1,  // stage 1: |a| above threshold
    CRASH_TILT      = 2,  // stage 2: sustained tilt
    CRASH_CONFIRMED = 3,  // stage 3: stillness -> crash declared
    CRASH_CANCELLED = 4,  // rider cancelled from the app
};

struct __attribute__((packed)) CrashPacket {
    uint8_t  version;
    uint8_t  state;     // CrashState
    uint16_t peakG;     // 0.01 g, peak resultant during the impact
    uint32_t epochS;    // device clock when the state was entered
    int32_t  lat;
    int32_t  lon;
};
static_assert(sizeof(CrashPacket) == 16, "CrashPacket must be 16 bytes");

// ---- CONTROL characteristic (write) ------------------------------------------
enum Opcode : uint8_t {
    OP_RIDE_START   = 0x01,
    OP_RIDE_STOP    = 0x02,
    OP_SET_TIME     = 0x03,  // + u32 epoch seconds (UTC)
    OP_CRASH_CANCEL = 0x10,
    OP_LOG_LIST     = 0x20,
    OP_LOG_GET      = 0x21,  // + u16 ride id
    OP_LOG_ACK      = 0x22,  // + u16 ride id -> device may delete it
    OP_SIM_POTHOLE  = 0x7E,  // debug: inject impact without fall (should be rejected)
    OP_SIM_CRASH    = 0x7F,  // debug: inject a full crash signature
};

// ---- LOG characteristic (notify) -----------------------------------------------
enum LogFrameType : uint8_t {
    LOG_LIST_ENTRY  = 0x01,
    LOG_LIST_END    = 0x02,
    LOG_RIDE_HEADER = 0x10,
    LOG_POINT       = 0x11,
    LOG_RIDE_END    = 0x12,
    LOG_ERROR       = 0x1F,
};

enum LogError : uint8_t {
    LOG_ERR_UNKNOWN_ID = 1,
    LOG_ERR_BUSY       = 2,
};

struct __attribute__((packed)) LogListEntry {
    uint8_t  type;        // LOG_LIST_ENTRY
    uint16_t id;
    uint32_t startEpoch;
    uint32_t distanceM;
    uint32_t durationS;
    uint16_t pointCount;
};
static_assert(sizeof(LogListEntry) == 17, "LogListEntry size");

struct __attribute__((packed)) LogListEnd {
    uint8_t  type;        // LOG_LIST_END
    uint16_t count;
};
static_assert(sizeof(LogListEnd) == 3, "LogListEnd size");

struct __attribute__((packed)) LogRideHeader {
    uint8_t  type;        // LOG_RIDE_HEADER
    uint16_t id;
    uint32_t startEpoch;
    uint32_t endEpoch;
    uint32_t distanceM;
    uint16_t maxSpeed;    // 0.01 km/h
    uint16_t avgSpeed;    // 0.01 km/h
    uint16_t pointCount;
    uint8_t  crashCount;
};
static_assert(sizeof(LogRideHeader) == 22, "LogRideHeader size");

struct __attribute__((packed)) LogPoint {
    uint8_t  type;        // LOG_POINT
    uint16_t id;
    uint16_t seq;         // 0..pointCount-1
    int32_t  lat;
    int32_t  lon;
    uint16_t speed;       // 0.01 km/h
};
static_assert(sizeof(LogPoint) == 15, "LogPoint size");

struct __attribute__((packed)) LogRideEnd {
    uint8_t  type;        // LOG_RIDE_END
    uint16_t id;
    uint16_t crc16;       // CRC-16/CCITT-FALSE over bytes [3..14] (seq..speed) of every LOG_POINT, in order
};
static_assert(sizeof(LogRideEnd) == 5, "LogRideEnd size");

struct __attribute__((packed)) LogErrorFrame {
    uint8_t  type;        // LOG_ERROR
    uint8_t  code;        // LogError
    uint16_t id;
};
static_assert(sizeof(LogErrorFrame) == 4, "LogErrorFrame size");

// CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, no reflection, no xorout).
inline uint16_t crc16_update(uint16_t crc, const uint8_t* data, uint32_t len) {
    while (len--) {
        crc ^= static_cast<uint16_t>(*data++) << 8;
        for (int i = 0; i < 8; ++i)
            crc = (crc & 0x8000) ? static_cast<uint16_t>((crc << 1) ^ 0x1021) : static_cast<uint16_t>(crc << 1);
    }
    return crc;
}

}  // namespace proto
