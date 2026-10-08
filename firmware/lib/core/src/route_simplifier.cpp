#include "route_simplifier.h"

#include <math.h>
#include "geo.h"

bool RouteSimplifier::mustBreak(const GnssFix& c) const {
    if (n_ >= WINDOW) return true;
    if (fabsf(c.speedKmh - anchor_.speedKmh) > cfg_.speedStepKmh) return true;
    if (c.tMs - anchor_.tMs > cfg_.maxGapMs) return true;

    const geo::Scale s = geo::scaleAt(anchor_.lat);
    const geo::Xy a{0.f, 0.f};
    const geo::Xy b = geo::toLocal(c.lat, c.lon, anchor_.lat, anchor_.lon, s);
    if (b.x * b.x + b.y * b.y > cfg_.maxGapM * cfg_.maxGapM) return true;

    for (int i = 0; i < n_; ++i) {
        const geo::Xy p = geo::toLocal(window_[i].lat, window_[i].lon, anchor_.lat, anchor_.lon, s);
        if (geo::pointSegmentDistanceM(p, a, b) > cfg_.toleranceM) return true;
    }
    return false;
}

bool RouteSimplifier::add(const GnssFix& f, GnssFix& out) {
    if (!haveAnchor_) {
        haveAnchor_ = true;
        anchor_ = f;
        n_ = 0;
        out = f;
        return true;
    }
    if (mustBreak(f)) {
        if (n_ > 0) {
            // The previous fix was the last one the straight segment could reach:
            // store it and start a new segment from it.
            anchor_ = window_[n_ - 1];
            window_[0] = f;
            n_ = 1;
            out = anchor_;
        } else {
            anchor_ = f;  // the gap alone forces a point
            out = f;
        }
        return true;
    }
    window_[n_++] = f;
    return false;
}

bool RouteSimplifier::flush(GnssFix& out) {
    if (!haveAnchor_ || n_ == 0) return false;
    out = window_[n_ - 1];
    anchor_ = out;
    n_ = 0;
    return true;
}
