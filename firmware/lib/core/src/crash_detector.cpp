#include "crash_detector.h"

#include <math.h>
#include "config.h"

static_assert(cfg::IMU_SMOOTH_N == 10, "CrashDetector::N must match cfg::IMU_SMOOTH_N");

void CrashDetector::smooth(const ImuSample& s, float& ax, float& ay, float& az) {
    bx_[head_] = s.ax; by_[head_] = s.ay; bz_[head_] = s.az;
    head_ = (head_ + 1) % N;
    if (filled_ < N) ++filled_;
    ax = ay = az = 0;
    for (int i = 0; i < filled_; ++i) { ax += bx_[i]; ay += by_[i]; az += bz_[i]; }
    ax /= filled_; ay /= filled_; az /= filled_;
}

bool CrashDetector::enter(State st, uint32_t tMs, const char* why) {
    state_ = st;
    stateTMs_ = tMs;
    condTMs_ = 0;
    reason_ = why;
    return true;
}

void CrashDetector::reset() {
    state_ = State::Idle;
    condTMs_ = 0;
    peakG_ = 0;
    reason_ = "reset";
}

bool CrashDetector::update(const ImuSample& s) {
    // Impact uses the raw resultant: smoothing would blunt the transient.
    const float raw = sqrtf(s.ax * s.ax + s.ay * s.ay + s.az * s.az);
    lastRaw_ = raw;

    // Tilt and stillness use the smoothed vector.
    float ax, ay, az;
    smooth(s, ax, ay, az);
    const float r = sqrtf(ax * ax + ay * ay + az * az);
    tiltDeg_ = r > 0.05f ? acosf(fmaxf(-1.f, fminf(1.f, az / r))) * 57.29578f : 0.f;

    const uint32_t t = s.tMs;
    const uint32_t inState = t - stateTMs_;

    switch (state_) {
    case State::Idle:
        if (raw > cfg::IMPACT_G) {
            peakG_ = raw;
            return enter(State::Impact, t, "impact");
        }
        return false;

    case State::Impact:
        if (raw > peakG_) peakG_ = raw;
        if (tiltDeg_ > cfg::TILT_DEG) {
            if (condTMs_ == 0) condTMs_ = t;
            if (t - condTMs_ >= cfg::TILT_HOLD_MS) return enter(State::Tilt, t, "sustained tilt");
        } else {
            condTMs_ = 0;
        }
        if (inState > cfg::TILT_WINDOW_MS) return enter(State::Idle, t, "rejected: impact without sustained tilt");
        return false;

    case State::Tilt:
        if (tiltDeg_ < cfg::TILT_DEG * 0.75f) return enter(State::Idle, t, "rejected: vehicle back upright");
        if (fabsf(r - 1.f) < cfg::STILL_EPS_G && fabsf(raw - 1.f) < 3 * cfg::STILL_EPS_G) {
            if (condTMs_ == 0) condTMs_ = t;
            if (t - condTMs_ >= cfg::STILL_HOLD_MS) return enter(State::Confirmed, t, "stillness -> CRASH");
        } else {
            condTMs_ = 0;
        }
        if (inState > cfg::STILL_WINDOW_MS) return enter(State::Idle, t, "rejected: no stillness (vehicle moving)");
        return false;

    case State::Confirmed:
        // Latched until the rider cancels (reset()).
        return false;
    }
    return false;
}
