// On-target unit tests for the hardware-independent core (lib/core).
//   pio test -e esp32-s3
#include <Arduino.h>
#include <math.h>
#include <unity.h>

#include "crash_detector.h"
#include "protocol.h"
#include "ride_log.h"
#include "ride_stats.h"

// ------------------------------------------------------------- helpers ----

static constexpr double M_PER_DEG_LAT = 111194.93;  // pi/180 * 6371 km

static GnssFix fixAt(uint32_t tMs, double northM, float kmh, bool valid = true) {
    GnssFix f{};
    f.tMs = tMs;
    f.lat = 8.995 + northM / M_PER_DEG_LAT;
    f.lon = 76.696;
    f.speedKmh = kmh;
    f.sats = 9;
    f.valid = valid;
    return f;
}

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

void test_haversine_one_degree_latitude() {
    TEST_ASSERT_DOUBLE_WITHIN(1.0, M_PER_DEG_LAT, geo::haversineM(8.0, 76.0, 9.0, 76.0));
}

void test_haversine_zero_and_symmetric() {
    TEST_ASSERT_DOUBLE_WITHIN(1e-6, 0.0, geo::haversineM(8.99, 76.69, 8.99, 76.69));
    const double ab = geo::haversineM(8.9950, 76.6960, 8.9795, 76.7153);
    const double ba = geo::haversineM(8.9795, 76.7153, 8.9950, 76.6960);
    TEST_ASSERT_DOUBLE_WITHIN(1e-6, ab, ba);
    TEST_ASSERT_DOUBLE_WITHIN(1.0, 2732.0, ab);  // college -> Ezhukone, straight line
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

void test_session_decimates_route() {
    RideSession s;
    s.start(1000, true);
    for (int i = 0; i <= 100; ++i) s.addFix(fixAt(i * 1000, i * 6.0, 21.6f));  // 600 m at 6 m/s
    RideLog l = s.finish(1100);
    // 25 m threshold -> a point every 5th fix (30 m): 0, 30, ..., 600
    TEST_ASSERT_EQUAL(21, (int)l.points.size());
    TEST_ASSERT_UINT32_WITHIN(1, 600, l.distanceM);
    TEST_ASSERT_EQUAL_UINT16(2160, l.maxSpeed);
}

void test_crc16_ccitt_false_known_vector() {
    const uint8_t v[] = {'1', '2', '3', '4', '5', '6', '7', '8', '9'};
    TEST_ASSERT_EQUAL_HEX16(0x29B1, proto::crc16_update(0xFFFF, v, sizeof v));
}

void setup() {
    delay(2000);  // let the USB serial attach
    UNITY_BEGIN();
    RUN_TEST(test_haversine_one_degree_latitude);
    RUN_TEST(test_haversine_zero_and_symmetric);
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
    RUN_TEST(test_session_decimates_route);
    RUN_TEST(test_crc16_ccitt_false_known_vector);
    UNITY_END();
}

void loop() {}
