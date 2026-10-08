#include "ble_service.h"

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <esp_mac.h>
#include "config.h"

namespace ble {
namespace {

CommandSink         gSink = nullptr;
NimBLEServer*       gServer = nullptr;
NimBLECharacteristic* gLive = nullptr;
NimBLECharacteristic* gCrash = nullptr;
NimBLECharacteristic* gLog = nullptr;
volatile bool       gConnected = false;
volatile uint16_t   gMtu = 23;
char                gName[20] = "SmartRide";

// Expected argument length per opcode; -1 = unknown opcode.
int argLen(uint8_t op) {
    switch (op) {
    case proto::OP_RIDE_START:
    case proto::OP_RIDE_STOP:
    case proto::OP_CRASH_CANCEL:
    case proto::OP_LOG_LIST:
    case proto::OP_SIM_POTHOLE:
    case proto::OP_SIM_CRASH:   return 0;
    case proto::OP_SET_TIME:    return 4;
    case proto::OP_LOG_GET:
    case proto::OP_LOG_ACK:     return 2;
    default:                    return -1;
    }
}

class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* s, NimBLEConnInfo& info) override {
        gConnected = true;
        gMtu = info.getMTU();
        Serial.printf("[BLE] connected: %s\n", info.getAddress().toString().c_str());
        // Ask for a 15-30 ms interval: snappy log transfer, modest power.
        s->updateConnParams(info.getConnHandle(), 12, 24, 0, 400);
    }
    void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int reason) override {
        gConnected = false;
        gMtu = 23;
        Serial.printf("[BLE] disconnected (reason 0x%02x), advertising again\n", reason);
    }
    void onMTUChange(uint16_t mtu, NimBLEConnInfo&) override {
        gMtu = mtu;
        Serial.printf("[BLE] MTU = %u\n", mtu);
    }
};

class ControlCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* c, NimBLEConnInfo&) override {
        const NimBLEAttValue& v = c->getValue();
        if (v.size() < 1) return;
        const uint8_t op = v.data()[0];
        const int need = argLen(op);
        if (need < 0 || static_cast<int>(v.size()) - 1 != need) {
            Serial.printf("[BLE] bad CONTROL write: op=0x%02x len=%u\n", op, (unsigned)v.size());
            return;
        }
        Command cmd{};
        cmd.op = op;
        cmd.len = static_cast<uint8_t>(need);
        memcpy(cmd.args, v.data() + 1, need);
        if (gSink) gSink(cmd);
    }
};

ServerCallbacks  gServerCb;
ControlCallbacks gControlCb;

}  // namespace

void begin(CommandSink sink) {
    gSink = sink;

    uint8_t mac[6];
    esp_read_mac(mac, ESP_MAC_BT);
    snprintf(gName, sizeof(gName), "SmartRide-%02X%02X", mac[4], mac[5]);

    NimBLEDevice::init(gName);
    NimBLEDevice::setMTU(proto::PREFERRED_MTU);
    NimBLEDevice::setPower(9);  // dBm (clamped to the chip's max)

    gServer = NimBLEDevice::createServer();
    gServer->setCallbacks(&gServerCb);
    gServer->advertiseOnDisconnect(true);

    // --- SmartRide service ---------------------------------------------------
    NimBLEService* svc = gServer->createService(proto::SERVICE_UUID);
    gLive  = svc->createCharacteristic(proto::LIVE_UUID,  NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    gCrash = svc->createCharacteristic(proto::CRASH_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    NimBLECharacteristic* control =
        svc->createCharacteristic(proto::CONTROL_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    gLog   = svc->createCharacteristic(proto::LOG_UUID,   NIMBLE_PROPERTY::NOTIFY);
    control->setCallbacks(&gControlCb);

    // Human-readable descriptions (shown by nRF Connect).
    gLive->createDescriptor("2901", NIMBLE_PROPERTY::READ)->setValue("Live ride state");
    gCrash->createDescriptor("2901", NIMBLE_PROPERTY::READ)->setValue("Crash state");
    control->createDescriptor("2901", NIMBLE_PROPERTY::READ)->setValue("Control");
    gLog->createDescriptor("2901", NIMBLE_PROPERTY::READ)->setValue("Ride log transfer");

    proto::LivePacket live{};
    live.version = proto::VERSION;
    live.batteryPct = proto::BATTERY_UNKNOWN;
    gLive->setValue(reinterpret_cast<const uint8_t*>(&live), sizeof(live));
    proto::CrashPacket crash{};
    crash.version = proto::VERSION;
    gCrash->setValue(reinterpret_cast<const uint8_t*>(&crash), sizeof(crash));

    // --- Device Information service (0x180A) -----------------------------------
    NimBLEService* dis = gServer->createService("180A");
    dis->createCharacteristic("2A29", NIMBLE_PROPERTY::READ)->setValue("SmartRide - TKMIT CSE Group 14");
    dis->createCharacteristic("2A24", NIMBLE_PROPERTY::READ)->setValue("SmartRide OBU");
    dis->createCharacteristic("2A26", NIMBLE_PROPERTY::READ)->setValue(FW_VERSION);
    dis->createCharacteristic("2A27", NIMBLE_PROPERTY::READ)->setValue(SMARTRIDE_BOARD);

    // --- Advertising --------------------------------------------------------------
    // 128-bit UUID + flags fill most of the 31-byte packet, so the name goes in
    // the scan response. Apps filter scans on the service UUID.
    NimBLEAdvertisementData adv;
    adv.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
    adv.setCompleteServices(NimBLEUUID(proto::SERVICE_UUID));
    NimBLEAdvertisementData scan;
    scan.setName(gName);

    NimBLEAdvertising* a = NimBLEDevice::getAdvertising();
    a->setAdvertisementData(adv);
    a->setScanResponseData(scan);
    a->start();

    Serial.printf("[BLE] advertising as %s\n", gName);
}

const char* deviceName() { return gName; }
bool connected() { return gConnected; }
uint16_t mtu() { return gMtu; }

void publishLive(const proto::LivePacket& p) {
    gLive->setValue(reinterpret_cast<const uint8_t*>(&p), sizeof(p));
    if (gConnected) gLive->notify();
}

void publishCrash(const proto::CrashPacket& p) {
    gCrash->setValue(reinterpret_cast<const uint8_t*>(&p), sizeof(p));
    if (gConnected) gCrash->notify();
}

bool sendLogFrame(const void* frame, size_t len) {
    if (len + 3 > gMtu) {
        Serial.printf("[BLE] log frame %u B exceeds MTU %u - client must request a larger MTU\n",
                      (unsigned)len, gMtu);
        return false;
    }
    for (int attempt = 0; attempt < 20; ++attempt) {
        if (!gConnected) return false;
        if (gLog->notify(static_cast<const uint8_t*>(frame), len)) {
            delay(cfg::LOG_FRAME_GAP_MS);
            return true;
        }
        delay(20);  // controller buffers full: back off
    }
    return false;
}

}  // namespace ble
