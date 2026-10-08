// Single-cell Li-ion state of charge from cell voltage.
// Pure C++ so it is unit-tested on the board alongside the rest of lib/core.
#pragma once

#include <stdint.h>

namespace battery {

// Piecewise-linear open-circuit-voltage curve for a typical 3.7 V Li-ion cell.
// Accurate enough for a rider-facing percentage; under load the reading sags,
// so the firmware smooths the voltage before converting.
uint8_t socFromMillivolts(uint16_t mv);

}  // namespace battery
