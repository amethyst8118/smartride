#include "sim_sensors.h"

#include <math.h>
#include "sim_route.h"

// ---------------------------------------------------------------- SimGnss ----

GnssFix SimGnss::next(uint32_t tMs) {
    const sim_route::Fix& f = sim_route::FIXES[idx_];
    GnssFix fix{};
    fix.tMs = tMs;
    fix.lat = f.lat_e7 / 1e7;
    fix.lon = f.lon_e7 / 1e7;
    fix.valid = true;
    fix.sats = static_cast<uint8_t>(8 + rng_.next() % 4);  // 8..11

    if (hold_) {
        fix.speedKmh = 0;
        return fix;  // position frozen where the vehicle went down
    }

    const float v = f.speed_x100 / 100.0f;
    fix.speedKmh = v > 0.5f ? fmaxf(0.f, v + rng_.uniform(-0.4f, 0.4f)) : 0.f;

    ++n_;
    if (n_ % INVALID_EVERY == 0) {
        fix.valid = false;
        fix.sats = 2;
    } else if (n_ % OUTLIER_EVERY == 0) {
        fix.lat += 0.004;  // ~445 m jump in one second: multipath-style outlier
    }

    if (++idx_ >= sim_route::COUNT) {
        idx_ = 0;
        wrapped_ = true;
    }
    return fix;
}

// ----------------------------------------------------------------- SimImu ----

ImuSample SimImu::sample(uint32_t tMs, float speedKmh) {
    const float vib = 0.02f + 0.12f * fminf(speedKmh, 45.f) / 45.f;
    ImuSample s{tMs, rng_.uniform(-vib, vib), rng_.uniform(-vib, vib), 1.f + rng_.uniform(-vib, vib)};
    const uint32_t dt = tMs - t0_;

    constexpr float LYING_DEG = 85.f;
    constexpr float DEG = 0.0174533f;

    switch (sc_) {
    case Scenario::Riding:
        break;

    case Scenario::Pothole:
        // Sharp vertical spike (~4.7 g) and rebound, vehicle stays upright.
        if (dt < 40)       { s.az += 3.6f; s.ay -= 0.8f; }
        else if (dt < 120) { s.az -= 1.2f; }
        else               { sc_ = Scenario::Riding; }
        break;

    case Scenario::Crash:
        if (dt < 60) {
            // Impact: ~5.3 g resultant
            s.ax = 3.0f + rng_.uniform(-0.2f, 0.2f);
            s.ay = 2.2f + rng_.uniform(-0.2f, 0.2f);
            s.az = 3.8f + rng_.uniform(-0.2f, 0.2f);
        } else if (dt < 1200) {
            // Tumble / slide: rolling onto the side with large noise
            const float th = LYING_DEG * DEG * (dt - 60) / 1140.f;
            s.ax = sinf(th) + rng_.uniform(-0.5f, 0.5f);
            s.ay = rng_.uniform(-0.5f, 0.5f);
            s.az = cosf(th) + rng_.uniform(-0.5f, 0.5f);
        } else {
            // Lying on its side, motionless
            s.ax = sinf(LYING_DEG * DEG) + rng_.uniform(-0.01f, 0.01f);
            s.ay = rng_.uniform(-0.01f, 0.01f);
            s.az = cosf(LYING_DEG * DEG) + rng_.uniform(-0.01f, 0.01f);
        }
        break;

    case Scenario::Recovering:
        // Rider lifts the vehicle back upright over 1.5 s.
        if (dt < 1500) {
            const float th = LYING_DEG * DEG * (1.f - dt / 1500.f);
            s.ax = sinf(th) + rng_.uniform(-0.05f, 0.05f);
            s.az = cosf(th) + rng_.uniform(-0.05f, 0.05f);
        } else {
            sc_ = Scenario::Riding;
            down_ = false;
        }
        break;
    }
    return s;
}
