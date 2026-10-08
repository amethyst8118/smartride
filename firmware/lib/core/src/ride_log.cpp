#include "ride_log.h"

#include <algorithm>
#include "config.h"

namespace {
uint16_t toX100(float kmh) {
    const float v = kmh * 100.f + 0.5f;
    return v <= 0 ? 0 : v >= 65535.f ? 65535 : static_cast<uint16_t>(v);
}
int32_t toE7(double deg) { return static_cast<int32_t>(deg * 1e7 + (deg >= 0 ? 0.5 : -0.5)); }
}  // namespace

// --------------------------------------------------------------- LogStore ----

uint16_t LogStore::add(RideLog&& log) {
    if (logs_.size() >= cfg::LOG_CAPACITY) logs_.erase(logs_.begin());
    log.id = nextId_++;
    if (nextId_ == 0) nextId_ = 1;  // 0 is never a valid id
    logs_.push_back(std::move(log));
    return logs_.back().id;
}

const RideLog* LogStore::find(uint16_t id) const {
    for (const auto& l : logs_) if (l.id == id) return &l;
    return nullptr;
}

bool LogStore::remove(uint16_t id) {
    auto it = std::find_if(logs_.begin(), logs_.end(), [id](const RideLog& l) { return l.id == id; });
    if (it == logs_.end()) return false;
    logs_.erase(it);
    return true;
}

void LogStore::shiftUnsynced(int64_t deltaS) {
    for (auto& l : logs_) {
        if (l.timeSynced) continue;
        l.startEpoch += deltaS;
        l.endEpoch += deltaS;
        l.timeSynced = true;
    }
}

// ------------------------------------------------------------ RideSession ----

void RideSession::start(uint32_t epoch, bool timeSynced) {
    *this = RideSession();
    active_ = true;
    startEpoch_ = epoch;
    timeSynced_ = timeSynced;
    points_.reserve(256);
}

void RideSession::pushPoint(const GnssFix& f) {
    if (points_.size() >= cfg::ROUTE_POINTS_MAX) return;
    points_.push_back({toE7(f.lat), toE7(f.lon), toX100(f.speedKmh)});
    lastPointFix_ = f;
}

RideStats::Result RideSession::addFix(const GnssFix& fix) {
    const auto res = stats_.addFix(fix);
    if (res == RideStats::Result::First) {
        pushPoint(fix);
    } else if (res == RideStats::Result::Accepted || res == RideStats::Result::Stationary) {
        const double fromLast = geo::haversineM(lastPointFix_.lat, lastPointFix_.lon, fix.lat, fix.lon);
        if (fromLast >= cfg::ROUTE_POINT_STEP_M || fix.tMs - lastPointFix_.tMs >= cfg::ROUTE_POINT_MAX_GAP_MS)
            pushPoint(fix);
    }
    if (res != RideStats::Result::RejectedInvalid && res != RideStats::Result::RejectedImplausible) {
        lastValidFix_ = fix;
        haveLastValid_ = true;
    }
    return res;
}

RideLog RideSession::finish(uint32_t endEpoch) {
    if (haveLastValid_ && !points_.empty() && lastValidFix_.tMs != lastPointFix_.tMs) pushPoint(lastValidFix_);
    RideLog log;
    log.startEpoch = startEpoch_;
    log.endEpoch = endEpoch;
    log.distanceM = static_cast<uint32_t>(stats_.distanceM() + 0.5);
    log.durationS = stats_.durationS();
    log.maxSpeed = toX100(stats_.maxSpeedKmh());
    log.avgSpeed = toX100(stats_.avgSpeedKmh());
    log.crashCount = crashes_;
    log.timeSynced = timeSynced_;
    log.points = std::move(points_);
    active_ = false;
    return log;
}
