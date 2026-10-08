// Sequential crash rule -- Phase 1 report, "Impact and Orientation Mechanics":
//   Crash = (|a| > a_th)  AND  (tilt > theta_th sustained)  AND  (stillness)
// evaluated in that order inside bounded windows. Failure of any stage returns
// to IDLE, which is how potholes (impact, no tilt) and cornering (tilt, no
// impact) are rejected.
//
// Pure C++ (no Arduino headers) so it can be unit-tested on the host.
#pragma once

#include <stdint.h>
#include "types.h"

class CrashDetector {
public:
    enum class State : uint8_t { Idle = 0, Impact = 1, Tilt = 2, Confirmed = 3 };

    // Feed one sample. Returns true when the state changed.
    bool update(const ImuSample& s);

    // Rider cancelled from the app (or reset after recovery).
    void reset();

    State       state()      const { return state_; }
    float       peakG()      const { return peakG_; }
    float       tiltDeg()    const { return tiltDeg_; }
    float       resultantG() const { return lastRaw_; }
    // Why the last transition back to Idle happened (for the serial log).
    const char* lastReason() const { return reason_; }

private:
    void smooth(const ImuSample& s, float& ax, float& ay, float& az);
    bool enter(State st, uint32_t tMs, const char* why);

    State    state_     = State::Idle;
    uint32_t stateTMs_  = 0;      // when the current state was entered
    uint32_t condTMs_   = 0;      // when the current stage's condition started holding (0 = not holding)
    float    peakG_     = 0;
    float    tiltDeg_   = 0;
    float    lastRaw_   = 1;
    const char* reason_ = "";

    // moving-average ring buffer
    static constexpr int N = 10;  // == cfg::IMU_SMOOTH_N
    float bx_[N] = {}, by_[N] = {}, bz_[N] = {};
    int   head_ = 0, filled_ = 0;
};
