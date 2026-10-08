#include "ride_stats.h"

#include "config.h"
#include "geo.h"

void RideStats::reset() { *this = RideStats(); }

float RideStats::avgSpeedKmh() const {
    return durationMs_ == 0 ? 0.f : static_cast<float>(distanceM_ / (durationMs_ / 1000.0) * 3.6);
}

RideStats::Result RideStats::addFix(const GnssFix& fix) {
    if (!fix.valid) {
        ++rejected_;
        return Result::RejectedInvalid;
    }
    if (!haveRef_) {
        haveRef_ = true;
        refLat_ = fix.lat;
        refLon_ = fix.lon;
        lastTMs_ = fix.tMs;
        if (fix.speedKmh > maxSpeedKmh_) maxSpeedKmh_ = fix.speedKmh;
        return Result::First;
    }

    const uint32_t dtMs = fix.tMs - lastTMs_;
    const double d = geo::distanceM(refLat_, refLon_, fix.lat, fix.lon);
    lastStepM_ = d;

    // Plausibility gate: a single bad fix must never corrupt the odometer.
    // The reference is NOT advanced, so the next good fix is compared with the
    // last good one (dt grows, so a genuine jump after an outage still passes).
    const double dtS = dtMs / 1000.0;
    if (dtS <= 0 || (d / dtS) * 3.6 > cfg::V_MAX_KMH) {
        ++rejected_;
        return Result::RejectedImplausible;
    }

    lastTMs_ = fix.tMs;
    durationMs_ += dtMs;

    // Stationary: accept the time, ignore sub-metre wander of the position.
    if (d < cfg::STATIONARY_STEP_M && fix.speedKmh < cfg::STATIONARY_KMH) {
        return Result::Stationary;
    }

    distanceM_ += d;
    refLat_ = fix.lat;
    refLon_ = fix.lon;
    if (fix.speedKmh > maxSpeedKmh_) maxSpeedKmh_ = fix.speedKmh;
    return Result::Accepted;
}
