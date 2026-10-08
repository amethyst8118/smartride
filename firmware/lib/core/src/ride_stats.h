// Ride statistics from GNSS fixes:
//   d  = geo::distanceM(fix_i-1, fix_i)   (ellipsoidal local projection, see geo.h)
//   D  = sum(d_i),  d_i accepted iff fix valid AND d_i / dt_i <= v_max
//   v  = v_GNSS (Doppler),  v_avg = D / T,  T = sum(dt_i)
//
// Pure C++ (no Arduino headers) so it can be unit-tested on the host.
#pragma once

#include <stdint.h>
#include "types.h"

class RideStats {
public:
    enum class Result : uint8_t {
        First,              // first valid fix of the ride: becomes the reference
        Accepted,           // distance increment added
        Stationary,         // time added, position jitter ignored
        RejectedInvalid,    // receiver reported no fix
        RejectedImplausible // implied speed above v_max (outlier)
    };

    void reset();
    Result addFix(const GnssFix& fix);

    double   distanceM()   const { return distanceM_; }
    uint32_t durationS()   const { return static_cast<uint32_t>(durationMs_ / 1000); }
    float    maxSpeedKmh() const { return maxSpeedKmh_; }
    float    avgSpeedKmh() const;
    double   lastStepM()   const { return lastStepM_; }  // for logging
    uint32_t rejectedCount() const { return rejected_; }

private:
    bool     haveRef_    = false;
    double   refLat_     = 0, refLon_ = 0;  // last position that contributed distance
    uint32_t lastTMs_    = 0;               // time of the last valid fix
    double   distanceM_  = 0;
    uint64_t durationMs_ = 0;
    float    maxSpeedKmh_= 0;
    double   lastStepM_  = 0;
    uint32_t rejected_   = 0;
};
