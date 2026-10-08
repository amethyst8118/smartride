#include "battery.h"

namespace battery {
namespace {
struct Point { uint16_t mv; uint8_t pct; };
// Highest voltage first.
constexpr Point CURVE[] = {
    {4200, 100}, {4150, 95}, {4110, 90}, {4080, 85}, {4020, 80}, {3980, 75}, {3950, 70},
    {3910, 65},  {3870, 60}, {3850, 55}, {3840, 50}, {3820, 45}, {3800, 40}, {3790, 35},
    {3770, 30},  {3750, 25}, {3730, 20}, {3710, 15}, {3690, 10}, {3610, 5},  {3270, 0},
};
constexpr int N = sizeof(CURVE) / sizeof(CURVE[0]);
}  // namespace

uint8_t socFromMillivolts(uint16_t mv) {
    if (mv >= CURVE[0].mv) return 100;
    if (mv <= CURVE[N - 1].mv) return 0;
    for (int i = 1; i < N; ++i) {
        if (mv >= CURVE[i].mv) {
            const Point& hi = CURVE[i - 1];
            const Point& lo = CURVE[i];
            const float t = static_cast<float>(mv - lo.mv) / (hi.mv - lo.mv);
            return static_cast<uint8_t>(lo.pct + t * (hi.pct - lo.pct) + 0.5f);
        }
    }
    return 0;
}

}  // namespace battery
