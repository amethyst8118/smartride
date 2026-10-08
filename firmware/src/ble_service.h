// SmartRide GATT server (NimBLE). See docs/ble-protocol.md.
//
// BLE callbacks run in the NimBLE host task, so they never do work themselves:
// CONTROL writes are validated and forwarded to the CommandSink (a queue),
// and everything else is driven from ble_task in main.cpp.
#pragma once

#include <stddef.h>
#include <stdint.h>
#include "protocol.h"
#include "types.h"

namespace ble {

using CommandSink = void (*)(const Command&);

// Starts the GATT server and advertising as "SmartRide-XXXX" (last MAC bytes).
void begin(CommandSink sink);

const char* deviceName();
bool connected();
uint16_t mtu();

void publishLive(const proto::LivePacket& p);    // set value + notify if subscribed
void publishCrash(const proto::CrashPacket& p);

// Notify one LOG frame, retrying while the controller's buffers are full.
// Returns false if the client disconnected or the frame could not be sent.
bool sendLogFrame(const void* frame, size_t len);

}  // namespace ble
