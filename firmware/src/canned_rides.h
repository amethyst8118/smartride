// STUB: three realistic past rides built from the simulated route so the app's
// ride-log sync has something to pull before real rides exist.
#pragma once

#include <stdint.h>
#include "ride_log.h"

void addCannedRides(LogStore& store, uint32_t nowEpoch);
