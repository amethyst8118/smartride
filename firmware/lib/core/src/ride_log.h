// Ride records and the on-device ride-log store ("D1 Flash Record Buffer" in
// the report's Level 1 DFD).
//
// STUB: LogStore keeps records in RAM. Phase 3 replaces the storage with
// LittleFS/NVS framed records so they survive power loss; the interface
// (add / find / remove / list) stays the same.
#pragma once

#include <stdint.h>
#include <vector>
#include "ride_stats.h"
#include "types.h"

struct RoutePoint {
    int32_t  lat;    // 1e-7 deg
    int32_t  lon;    // 1e-7 deg
    uint16_t speed;  // 0.01 km/h
};

struct RideLog {
    uint16_t id = 0;
    uint32_t startEpoch = 0;
    uint32_t endEpoch = 0;
    uint32_t distanceM = 0;
    uint32_t durationS = 0;
    uint16_t maxSpeed = 0;   // 0.01 km/h
    uint16_t avgSpeed = 0;   // 0.01 km/h
    uint8_t  crashCount = 0;
    bool     timeSynced = false;  // epochs came from a set clock
    std::vector<RoutePoint> points;
};

class LogStore {
public:
    // Stores the log, assigns and returns its id. Drops the oldest when full.
    uint16_t add(RideLog&& log);
    const RideLog* find(uint16_t id) const;
    bool remove(uint16_t id);
    const std::vector<RideLog>& all() const { return logs_; }
    // Re-base logs recorded before the clock was set.
    void shiftUnsynced(int64_t deltaS);

private:
    std::vector<RideLog> logs_;
    uint16_t nextId_ = 1;
};

// One ride in progress: statistics + decimated route.
class RideSession {
public:
    void start(uint32_t epoch, bool timeSynced);
    RideStats::Result addFix(const GnssFix& fix);
    void addCrash() { if (crashes_ < 255) ++crashes_; }
    RideLog finish(uint32_t endEpoch);

    bool active() const { return active_; }
    const RideStats& stats() const { return stats_; }
    uint32_t startEpoch() const { return startEpoch_; }
    void shiftStart(int64_t deltaS) { startEpoch_ += deltaS; timeSynced_ = true; }
    size_t pointCount() const { return points_.size(); }

private:
    void pushPoint(const GnssFix& f);

    bool      active_ = false;
    RideStats stats_;
    uint32_t  startEpoch_ = 0;
    bool      timeSynced_ = false;
    uint8_t   crashes_ = 0;
    std::vector<RoutePoint> points_;
    GnssFix   lastPointFix_{};
    GnssFix   lastValidFix_{};
    bool      haveLastValid_ = false;
};
