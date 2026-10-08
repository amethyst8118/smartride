// On-target unit tests for the hardware-independent core (lib/core).
//   pio test -e esp32-s3
#include <Arduino.h>
#include <math.h>
#include <unity.h>
#include <vector>

#include "battery.h"
#include "crash_detector.h"
#include "geo.h"
#include "protocol.h"
#include "ride_log.h"
#include "ride_stats.h"
#include "route_simplifier.h"

// ------------------------------------------------------------- helpers ----

static constexpr double LAT0 = 8.995, LON0 = 76.696;
static const geo::Scale S0 = geo::scaleAt(LAT0);

// A fix `northM` / `eastM` metres from (LAT0, LON0).
static GnssFix fixXY(uint32_t tMs, double eastM, double northM, float kmh, bool valid = true) {
    GnssFix f{};
    f.tMs = tMs;
    f.lat = LAT0 + northM / S0.mPerDegLat;
    f.lon = LON0 + eastM / S0.mPerDegLon;
    f.speedKmh = kmh;
    f.sats = 9;
    f.valid = valid;
    return f;
}

static GnssFix fixAt(uint32_t tMs, double northM, float kmh, bool valid = true) { return fixXY(tMs, 0, northM, kmh, valid); }

static ImuSample upright(uint32_t t, float noise = 0.03f) {
    const float n = noise * sinf(t * 0.7f);
    return {t, n, -n, 1.f + n};
}

static ImuSample atTilt(uint32_t t, float deg, float noise = 0.01f) {
    const float r = deg * 0.0174533f, n = noise * sinf(t * 1.3f);
    return {t, sinf(r) + n, n, cosf(r) + n};
}

// Feeds samples at 100 Hz from t0 to t1 (ms) and returns the highest state reached.
template <typename Gen>
static CrashDetector::State run(CrashDetector& d, uint32_t t0, uint32_t t1, Gen gen) {
    auto highest = d.state();
    for (uint32_t t = t0; t < t1; t += 10) {
        d.update(gen(t));
        if (d.state() > highest) highest = d.state();
    }
    return highest;
}

// ------------------------------------------------------------ geodesy ----

// Reference values: exact WGS-84 geodesics from geographiclib (Karney).
void test_geo_matches_ellipsoid_reference() {
    TEST_ASSERT_FLOAT_WITHIN(2.0f, 110601.48f, geo::distanceM(8.5, 76.0, 9.5, 76.0));       // 1 deg latitude
    TEST_ASSERT_FLOAT_WITHIN(2.0f, 109957.94f, geo::distanceM(9.0, 76.0, 9.0, 77.0));       // 1 deg longitude
    TEST_ASSERT_FLOAT_WITHIN(0.5f, 2728.168f, geo::distanceM(8.9950, 76.6960, 8.9795, 76.7153));  // college -> Ezhukone
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 15.596f, geo::distanceM(8.99, 76.69, 8.9901, 76.6901));  // one GNSS step
}

void test_geo_zero_symmetric_and_resolution() {
    TEST_ASSERT_EQUAL_FLOAT(0.f, geo::distanceM(8.99, 76.69, 8.99, 76.69));
    TEST_ASSERT_EQUAL_FLOAT(geo::distanceM(8.9950, 76.6960, 8.9795, 76.7153), geo::distanceM(8.9795, 76.7153, 8.9950, 76.6960));
    // 1e-7 deg (the protocol's coordinate unit) is ~1.1 cm and must still be resolved
    TEST_ASSERT_FLOAT_WITHIN(0.002f, 0.0111f, geo::distanceM(8.9950000, 76.696, 8.9950001, 76.696));
}

void test_geo_point_segment_distance() {
    const geo::Xy a{0, 0}, b{100, 0};
    TEST_ASSERT_FLOAT_WITHIN(1e-4f, 5.f, geo::pointSegmentDistanceM({50, 5}, a, b));
    TEST_ASSERT_FLOAT_WITHIN(1e-4f, 5.f, geo::pointSegmentDistanceM({-3, 4}, a, b));   // beyond the start
    TEST_ASSERT_FLOAT_WITHIN(1e-4f, 0.f, geo::pointSegmentDistanceM({70, 0}, a, b));
}

void test_geo_is_fast() {
    // Not a correctness test: documents the per-fix cost on the target.
    volatile float sink = 0;
    const uint32_t t0 = micros();
    for (int i = 0; i < 10000; ++i) sink += geo::distanceM(8.99 + i * 1e-6, 76.69, 8.9901, 76.6901 + i * 1e-6);
    const uint32_t us = micros() - t0;
    char msg[64];
    snprintf(msg, sizeof msg, "geo::distanceM: %.2f us/call", us / 10000.0);
    TEST_MESSAGE(msg);
    TEST_ASSERT_TRUE(us < 100000);  // < 10 us per call
}

// --------------------------------------------------------- ride stats ----

void test_ride_accumulates_distance_and_time() {
    RideStats s;
    for (int i = 0; i < 10; ++i) s.addFix(fixAt(i * 1000, i * 10.0, 36.f));  // 10 m/s north
    TEST_ASSERT_DOUBLE_WITHIN(0.5, 90.0, s.distanceM());
    TEST_ASSERT_EQUAL_UINT32(9, s.durationS());
    TEST_ASSERT_FLOAT_WITHIN(0.5f, 36.f, s.avgSpeedKmh());
    TEST_ASSERT_FLOAT_WITHIN(0.01f, 36.f, s.maxSpeedKmh());
}

void test_ride_rejects_outlier_and_keeps_reference() {
    RideStats s;
    s.addFix(fixAt(0, 0, 36));
    s.addFix(fixAt(1000, 10, 36));
    TEST_ASSERT_EQUAL(RideStats::Result::RejectedImplausible, s.addFix(fixAt(2000, 510, 36)));  // 500 m in 1 s
    TEST_ASSERT_DOUBLE_WITHIN(0.5, 10.0, s.distanceM());
    // Next good fix is compared with the last GOOD one (10 m), over 2 s.
    TEST_ASSERT_EQUAL(RideStats::Result::Accepted, s.addFix(fixAt(3000, 30, 36)));
    TEST_ASSERT_DOUBLE_WITHIN(0.5, 30.0, s.distanceM());
    TEST_ASSERT_EQUAL_UINT32(1, s.rejectedCount());
}

void test_ride_ignores_invalid_fix() {
    RideStats s;
    s.addFix(fixAt(0, 0, 36));
    TEST_ASSERT_EQUAL(RideStats::Result::RejectedInvalid, s.addFix(fixAt(1000, 5000, 36, false)));
    TEST_ASSERT_DOUBLE_WITHIN(1e-9, 0.0, s.distanceM());
}

void test_ride_stationary_jitter_adds_no_distance() {
    RideStats s;
    for (int i = 0; i < 60; ++i) s.addFix(fixAt(i * 1000, (i % 2 ? 0.8 : -0.8), 0.5f));
    TEST_ASSERT_DOUBLE_WITHIN(1e-9, 0.0, s.distanceM());
    TEST_ASSERT_EQUAL_UINT32(59, s.durationS());
}

void test_ride_slow_creep_is_not_lost() {
    RideStats s;  // 1 m/s at walking pace: individual steps are below the jitter gate
    for (int i = 0; i <= 20; ++i) s.addFix(fixAt(i * 1000, i * 1.0, 2.0f));
    TEST_ASSERT_DOUBLE_WITHIN(2.01, 20.0, s.distanceM());
}

// -------------------------------------------------------- crash rule ----

void test_crash_full_signature_is_confirmed() {
    CrashDetector d;
    run(d, 0, 1000, [](uint32_t t) { return upright(t); });
    // impact
    run(d, 1000, 1060, [](uint32_t t) { return ImuSample{t, 3.0f, 2.2f, 3.8f}; });
    TEST_ASSERT_TRUE(d.peakG() > 4.0f);
    // roll onto side then lie still
    run(d, 1060, 1600, [](uint32_t t) { return atTilt(t, 85.f * (t - 1060) / 540.f, 0.3f); });
    const auto s = run(d, 1600, 6000, [](uint32_t t) { return atTilt(t, 85.f); });
    TEST_ASSERT_EQUAL(CrashDetector::State::Confirmed, s);
    TEST_ASSERT_EQUAL(CrashDetector::State::Confirmed, d.state());  // latched
    d.reset();
    TEST_ASSERT_EQUAL(CrashDetector::State::Idle, d.state());
}

void test_pothole_is_rejected() {
    CrashDetector d;
    run(d, 0, 500, [](uint32_t t) { return upright(t); });
    run(d, 500, 540, [](uint32_t t) { return ImuSample{t, 0.f, -0.8f, 4.6f}; });
    TEST_ASSERT_EQUAL(CrashDetector::State::Impact, d.state());
    const auto highest = run(d, 540, 7000, [](uint32_t t) { return upright(t, 0.1f); });
    TEST_ASSERT_EQUAL(CrashDetector::State::Impact, highest);  // never reached Tilt
    TEST_ASSERT_EQUAL(CrashDetector::State::Idle, d.state());
}

void test_cornering_lean_without_impact_is_ignored() {
    CrashDetector d;  // 65 deg lean held for 5 s, no impact
    const auto highest = run(d, 0, 5000, [](uint32_t t) { return atTilt(t, 65.f, 0.1f); });
    TEST_ASSERT_EQUAL(CrashDetector::State::Idle, highest);
}

void test_fall_then_lifted_before_stillness_is_rejected() {
    CrashDetector d;
    run(d, 0, 60, [](uint32_t t) { return ImuSample{t, 3.f, 2.f, 4.f}; });
    run(d, 60, 1500, [](uint32_t t) { return atTilt(t, 80.f, 0.4f); });  // tumbling, not still
    TEST_ASSERT_EQUAL(CrashDetector::State::Tilt, d.state());
    run(d, 1500, 2500, [](uint32_t t) { return upright(t); });           // picked up
    TEST_ASSERT_EQUAL(CrashDetector::State::Idle, d.state());
}

// -------------------------------------------------- log store + proto ----

void test_logstore_capacity_and_ids() {
    LogStore st;
    for (int i = 0; i < 10; ++i) {
        RideLog l;
        l.distanceM = i;
        st.add(std::move(l));
    }
    TEST_ASSERT_EQUAL(8, (int)st.all().size());
    TEST_ASSERT_EQUAL_UINT16(3, st.all().front().id);  // 1 and 2 dropped
    TEST_ASSERT_NULL(st.find(1));
    TEST_ASSERT_NOT_NULL(st.find(10));
    TEST_ASSERT_TRUE(st.remove(10));
    TEST_ASSERT_FALSE(st.remove(10));
}

static RouteSimplifier makeSimplifier() {
    return RouteSimplifier({cfg::ROUTE_TOLERANCE_M, cfg::ROUTE_SPEED_STEP_KMH, cfg::ROUTE_MAX_GAP_M, cfg::ROUTE_MAX_GAP_MS});
}

void test_route_straight_road_collapses() {
    RideSession s;
    s.start(1000, true);
    for (int i = 0; i <= 100; ++i) s.addFix(fixAt(i * 1000, i * 6.0, 21.6f));  // 600 m straight, 6 m/s
    RideLog l = s.finish(1100);
    // A straight line needs no intermediate points; only the 30 s max gap forces them
    // (6 m/s x 30 s = 180 m): start, 180, 360, 540, end = 5 points instead of 101 fixes.
    TEST_ASSERT_EQUAL(5, (int)l.points.size());
    TEST_ASSERT_UINT32_WITHIN(1, 600, l.distanceM);
    TEST_ASSERT_EQUAL_UINT16(2160, l.maxSpeed);
}

void test_route_keeps_corner() {
    RouteSimplifier r = makeSimplifier();
    std::vector<GnssFix> kept;
    GnssFix out;
    uint32_t t = 0;
    for (int i = 0; i <= 20; ++i, t += 1000) if (r.add(fixXY(t, i * 5.0, 0, 18), out)) kept.push_back(out);       // 100 m east
    for (int i = 1; i <= 20; ++i, t += 1000) if (r.add(fixXY(t, 100, i * 5.0, 18), out)) kept.push_back(out);     // 100 m north
    if (r.flush(out)) kept.push_back(out);
    TEST_ASSERT_EQUAL(3, (int)kept.size());
    const geo::Xy corner = geo::toLocal(kept[1].lat, kept[1].lon, LAT0, LON0, S0);
    TEST_ASSERT_FLOAT_WITHIN(0.05f, 100.f, corner.x);
    TEST_ASSERT_FLOAT_WITHIN(0.05f, 0.f, corner.y);
}

void test_route_keeps_speed_changes() {
    RouteSimplifier r = makeSimplifier();
    int kept = 0;
    GnssFix out;
    for (int i = 0; i <= 30; ++i) if (r.add(fixAt(i * 1000, i * 5.0, i < 15 ? 15.f : 40.f), out)) ++kept;
    if (r.flush(out)) ++kept;
    // start, last fix before the jump, first fix after it (pins the colour boundary), end
    TEST_ASSERT_EQUAL(4, kept);
}

void test_route_curve_within_tolerance() {
    // Quarter circle, radius 60 m, one fix per 3 degrees: every dropped fix must lie
    // within tolerance of the simplified polyline.
    RouteSimplifier r = makeSimplifier();
    std::vector<geo::Xy> all, kept;
    GnssFix out;
    auto toXy = [](const GnssFix& f) { return geo::toLocal(f.lat, f.lon, LAT0, LON0, S0); };
    for (int i = 0; i <= 30; ++i) {
        const float a = i * 3.f * 0.0174533f;
        GnssFix f = fixXY(i * 1000, 60.0 * cosf(a), 60.0 * sinf(a), 11);
        all.push_back(toXy(f));
        if (r.add(f, out)) kept.push_back(toXy(out));
    }
    if (r.flush(out)) kept.push_back(toXy(out));
    TEST_ASSERT_TRUE(kept.size() < all.size() / 2);   // real reduction
    TEST_ASSERT_TRUE(kept.size() >= 4);               // but the bend keeps its shape
    for (const auto& p : all) {
        float best = 1e9f;
        for (size_t k = 1; k < kept.size(); ++k) best = fminf(best, geo::pointSegmentDistanceM(p, kept[k - 1], kept[k]));
        TEST_ASSERT_TRUE(best <= cfg::ROUTE_TOLERANCE_M + 0.01f);
    }
}

void test_battery_soc_curve() {
    TEST_ASSERT_EQUAL_UINT8(100, battery::socFromMillivolts(4250));
    TEST_ASSERT_EQUAL_UINT8(100, battery::socFromMillivolts(4200));
    TEST_ASSERT_EQUAL_UINT8(50, battery::socFromMillivolts(3840));
    TEST_ASSERT_EQUAL_UINT8(93, battery::socFromMillivolts(4130));  // between 4110 (90 %) and 4150 (95 %)
    TEST_ASSERT_EQUAL_UINT8(0, battery::socFromMillivolts(3270));
    TEST_ASSERT_EQUAL_UINT8(0, battery::socFromMillivolts(3000));
    uint8_t prev = 0;  // monotonic in voltage
    for (uint16_t mv = 3200; mv <= 4250; mv += 5) {
        const uint8_t s = battery::socFromMillivolts(mv);
        TEST_ASSERT_TRUE(s >= prev);
        prev = s;
    }
}

void test_crc16_ccitt_false_known_vector() {
    const uint8_t v[] = {'1', '2', '3', '4', '5', '6', '7', '8', '9'};
    TEST_ASSERT_EQUAL_HEX16(0x29B1, proto::crc16_update(0xFFFF, v, sizeof v));
}

void setup() {
    delay(2000);  // let the USB serial attach
    UNITY_BEGIN();
    RUN_TEST(test_geo_matches_ellipsoid_reference);
    RUN_TEST(test_geo_zero_symmetric_and_resolution);
    RUN_TEST(test_geo_point_segment_distance);
    RUN_TEST(test_geo_is_fast);
    RUN_TEST(test_ride_accumulates_distance_and_time);
    RUN_TEST(test_ride_rejects_outlier_and_keeps_reference);
    RUN_TEST(test_ride_ignores_invalid_fix);
    RUN_TEST(test_ride_stationary_jitter_adds_no_distance);
    RUN_TEST(test_ride_slow_creep_is_not_lost);
    RUN_TEST(test_crash_full_signature_is_confirmed);
    RUN_TEST(test_pothole_is_rejected);
    RUN_TEST(test_cornering_lean_without_impact_is_ignored);
    RUN_TEST(test_fall_then_lifted_before_stillness_is_rejected);
    RUN_TEST(test_logstore_capacity_and_ids);
    RUN_TEST(test_route_straight_road_collapses);
    RUN_TEST(test_route_keeps_corner);
    RUN_TEST(test_route_keeps_speed_changes);
    RUN_TEST(test_route_curve_within_tolerance);
    RUN_TEST(test_battery_soc_curve);
    RUN_TEST(test_crc16_ccitt_false_known_vector);
    UNITY_END();
}

void loop() {}
