// Plain data passed between tasks through FreeRTOS queues.
#pragma once

#include <stdint.h>

// One GNSS navigation solution (from the simulator now, ATGM336H later).
struct GnssFix {
    uint32_t tMs;        // monotonic time of the fix (millis)
    double   lat;        // degrees, WGS-84
    double   lon;        // degrees
    float    speedKmh;   // receiver velocity solution (Doppler), not differentiated position
    uint8_t  sats;
    bool     valid;      // receiver reports a valid fix
};

// One motion sample in the unit's body frame (z = up when mounted upright).
struct ImuSample {
    uint32_t tMs;
    float ax, ay, az;    // g
};

// A command from BLE CONTROL, the serial console or the BOOT button.
struct Command {
    uint8_t op;          // proto::Opcode
    uint8_t len;         // bytes used in args
    uint8_t args[8];
};
