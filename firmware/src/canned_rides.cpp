#include "canned_rides.h"

#include "sim_route.h"

// ----------------------------------------------------------- Canned rides ----

namespace {
RideLog replay(uint16_t from, uint16_t to, uint32_t startEpoch, uint8_t crashes) {
    RideSession s;
    s.start(startEpoch, true);
    for (uint16_t i = from; i < to; ++i) {
        const auto& f = sim_route::FIXES[i];
        GnssFix fix{};
        fix.tMs = (i - from) * 1000u;
        fix.lat = f.lat_e7 / 1e7;
        fix.lon = f.lon_e7 / 1e7;
        fix.speedKmh = f.speed_x100 / 100.f;
        fix.sats = 9;
        fix.valid = true;
        s.addFix(fix);
    }
    for (uint8_t c = 0; c < crashes; ++c) s.addCrash();
    return s.finish(startEpoch + (to - from));
}
}  // namespace

void addCannedRides(LogStore& store, uint32_t nowEpoch) {
    constexpr uint32_t DAY = 86400;
    const uint32_t today = nowEpoch / DAY * DAY;  // 00:00 UTC (05:30 IST)
    const uint16_t half = sim_route::COUNT / 2;

    // Times in UTC; IST = UTC + 5:30.
    store.add(replay(0, sim_route::COUNT, today - 6 * DAY + 3 * 3600 + 10 * 60, 0));  // full loop, 08:40 IST
    store.add(replay(0, half, today - 3 * DAY + 11 * 3600 + 45 * 60, 1));             // to Ezhukone, 17:15 IST, 1 alert
    store.add(replay(half, sim_route::COUNT, today - 1 * DAY + 2 * 3600 + 50 * 60, 0)); // back to campus, 08:20 IST
}
