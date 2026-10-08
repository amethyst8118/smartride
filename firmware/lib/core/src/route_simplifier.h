// Online polyline simplification for GNSS tracks (sliding-window Douglas-Peucker).
//
// Fixes arrive at 1 Hz; storing all of them wastes flash and BLE time, and a
// fixed-distance decimation both wastes points on straight roads and cuts corners.
// This keeps a fix only when the track since the last kept point can no longer be
// drawn as one straight segment within `toleranceM`, so straights collapse to
// their end points while bends keep their shape. Extra break conditions preserve
// what the app shows: speed changes (route colouring) and a maximum gap in
// distance and time (replay, stops).
#pragma once

#include <stdint.h>
#include "types.h"

class RouteSimplifier {
public:
    struct Config {
        float    toleranceM;    // max perpendicular deviation of a dropped fix
        float    speedStepKmh;  // keep a point when speed changes by more than this
        float    maxGapM;       // ...or the kept points would be further apart than this
        uint32_t maxGapMs;      // ...or longer apart in time than this
    };

    explicit RouteSimplifier(const Config& c) : cfg_(c) {}

    void reset() { haveAnchor_ = false; n_ = 0; }

    // Feed one fix. Returns true when a point must be stored; it is written to
    // `out` and may be an earlier fix (the last one that still fitted the segment).
    bool add(const GnssFix& f, GnssFix& out);

    // End of ride: emits the final fix if it has not been stored yet.
    bool flush(GnssFix& out);

    static constexpr int WINDOW = 64;  // bounds work per fix and RAM

private:
    bool mustBreak(const GnssFix& candidate) const;

    Config  cfg_;
    bool    haveAnchor_ = false;
    GnssFix anchor_{};
    GnssFix window_[WINDOW];  // fixes after the anchor, not yet stored
    int     n_ = 0;
};
