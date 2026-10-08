// STUB sensor sources. They produce the same GnssFix / ImuSample structs the
// real ATGM336H-5N and MPU6500 drivers will, so everything downstream (ride
// statistics, crash rule, BLE, logs) is the real code path.
//
// Replace with drivers in Phase 3:
//   SimGnss -> Atgm336h  (UART NMEA parser + 1 PPS)
//   SimImu  -> Mpu6500   (SPI/I2C FIFO reader)
#pragma once

#include <stdint.h>
#include "types.h"

// Tiny deterministic PRNG so simulated noise is reproducible run to run.
class Lcg {
public:
    explicit Lcg(uint32_t seed) : s_(seed) {}
    uint32_t next() { s_ = s_ * 1664525u + 1013904223u; return s_; }
    float uniform(float lo, float hi) { return lo + (hi - lo) * ((next() >> 8) / 16777216.0f); }
private:
    uint32_t s_;
};

class SimGnss {
public:
    // Next 1 Hz fix. Every OUTLIER_EVERY fixes a position jump is injected,
    // every INVALID_EVERY fixes the receiver "loses" the fix, so the
    // plausibility gate visibly does its job.
    GnssFix next(uint32_t tMs);

    void setHold(bool hold) { hold_ = hold; }   // vehicle down: position frozen, speed 0
    bool takeWrapped() { bool w = wrapped_; wrapped_ = false; return w; }
    uint16_t index() const { return idx_; }

    static constexpr uint32_t OUTLIER_EVERY = 97;
    static constexpr uint32_t INVALID_EVERY = 211;

private:
    uint16_t idx_ = 0;
    uint32_t n_ = 0;
    bool     hold_ = false;
    bool     wrapped_ = false;
    Lcg      rng_{0x5EED1234};
};

class SimImu {
public:
    enum class Scenario : uint8_t { Riding, Pothole, Crash, Recovering };

    void triggerPothole(uint32_t tMs) { start(Scenario::Pothole, tMs); }
    void triggerCrash(uint32_t tMs)   { start(Scenario::Crash, tMs); down_ = true; }
    void recover(uint32_t tMs)        { if (down_) start(Scenario::Recovering, tMs); }

    // 100 Hz body-frame sample. Vibration grows with road speed.
    ImuSample sample(uint32_t tMs, float speedKmh);

    bool vehicleDown() const { return down_; }

private:
    void start(Scenario s, uint32_t tMs) { sc_ = s; t0_ = tMs; }
    Scenario sc_ = Scenario::Riding;
    uint32_t t0_ = 0;
    bool     down_ = false;
    Lcg      rng_{0xC0FFEE};
};
