#include "geo.h"

#include <math.h>

namespace geo {

Scale scaleAt(double latDeg) {
    // Series for the WGS-84 meridional and parallel arc lengths per degree,
    // with cos(n*phi) built from one cosf via Chebyshev recurrences.
    const float c = cosf(static_cast<float>(latDeg) * 0.017453292f);
    const float c2 = 2.f * c * c - 1.f;            // cos 2phi
    const float c3 = (4.f * c * c - 3.f) * c;      // cos 3phi
    const float c4 = 2.f * c2 * c2 - 1.f;          // cos 4phi
    const float c5 = 2.f * c2 * c3 - c;            // cos 5phi
    return {111132.92f - 559.82f * c2 + 1.175f * c4,
            111412.84f * c - 93.5f * c3 + 0.118f * c5};
}

float distanceM(double lat1, double lon1, double lat2, double lon2) {
    const Scale s = scaleAt(0.5 * (lat1 + lat2));
    const float dy = static_cast<float>(lat2 - lat1) * s.mPerDegLat;
    const float dx = static_cast<float>(lon2 - lon1) * s.mPerDegLon;
    return sqrtf(dx * dx + dy * dy);
}

float pointSegmentDistanceM(Xy p, Xy a, Xy b) {
    const float vx = b.x - a.x, vy = b.y - a.y;
    const float wx = p.x - a.x, wy = p.y - a.y;
    const float len2 = vx * vx + vy * vy;
    float t = len2 > 1e-6f ? (wx * vx + wy * vy) / len2 : 0.f;
    t = t < 0.f ? 0.f : (t > 1.f ? 1.f : t);
    const float dx = wx - t * vx, dy = wy - t * vy;
    return sqrtf(dx * dx + dy * dy);
}

}  // namespace geo
