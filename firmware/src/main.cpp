// SmartRide on-board unit -- v0.1 (simulated sensors).
//
// Task layout follows the Phase 1 report ("Data Processing Flow within the
// ESP32-S3"):
//
//   gnss_task --fix queue-->    ride_task  --notify--> ble_task --> LIVE
//   imu_task  --motion queue--> crash_task --event q-> ble_task --> CRASH
//                                                      ble_task <-- CONTROL (cmd queue)
//                                                      ble_task --> LOG (ride-log transfer)
//
// Serial console (115200): h = help. BOOT button = simulate crash / cancel.
#include <Arduino.h>

#include "ble_service.h"
#include "canned_rides.h"
#include "config.h"
#include "crash_detector.h"
#include "protocol.h"
#include "ride_log.h"
#include "sim_sensors.h"
#include "types.h"

// ------------------------------------------------------------------ state ----

static QueueHandle_t     qFix, qMotion, qCmd, qCrashEvt;
static SemaphoreHandle_t gLock;          // guards session, logs, clock, gLastFix
static TaskHandle_t      hBleTask;

static SimGnss       gnss;
static SimImu        imu;
static CrashDetector detector;
static RideSession   session;
static LogStore      logs;

static GnssFix        gLastFix{};
static volatile float gSpeedKmh = 0;
static volatile bool  gCrashPending = false;
static volatile uint8_t gImuRequest = 0;  // 1 pothole, 2 crash, 3 recover (applied by imu_task)
static volatile uint32_t gImuOverruns = 0;
static volatile bool  gDetectorReset = false;  // set by ble_task, applied by crash_task (detector is single-owner)

// Device clock: build time until the phone (or, later, GNSS) sets it.
static uint32_t gEpochBase = BUILD_EPOCH;
static uint32_t gMillisBase = 0;
static bool     gTimeSynced = false;

struct Lock {
    Lock()  { xSemaphoreTake(gLock, portMAX_DELAY); }
    ~Lock() { xSemaphoreGive(gLock); }
};

static uint32_t nowEpoch() { return gEpochBase + (millis() - gMillisBase) / 1000; }

static int32_t e7(double deg) { return static_cast<int32_t>(deg * proto::COORD_SCALE + (deg >= 0 ? 0.5 : -0.5)); }

static void fmtDuration(char* out, size_t n, uint32_t s) {
    snprintf(out, n, "%02u:%02u:%02u", (unsigned)(s / 3600), (unsigned)(s / 60 % 60), (unsigned)(s % 60));
}

static void pushCommand(const Command& c) { xQueueSend(qCmd, &c, 0); }

// Called with gLock held.
static void storeFinishedRide(const char* why) {
    RideLog log = session.finish(nowEpoch());
    if (log.points.size() < 2) {
        Serial.printf("[RIDE] stopped (%s) - too short, discarded\n", why);
        return;
    }
    const uint32_t dist = log.distanceM, dur = log.durationS;
    const size_t pts = log.points.size();
    const uint16_t id = logs.add(std::move(log));
    char d[12];
    fmtDuration(d, sizeof d, dur);
    Serial.printf("[RIDE] stopped (%s) -> saved log #%u: %.2f km, %s, %u route points\n",
                  why, id, dist / 1000.0, d, (unsigned)pts);
}

// ------------------------------------------------------------------ tasks ----

static void gnssTask(void*) {
    TickType_t wake = xTaskGetTickCount();
    for (;;) {
        gnss.setHold(imu.vehicleDown());
        GnssFix fix = gnss.next(millis());
        xQueueSend(qFix, &fix, 0);
        vTaskDelayUntil(&wake, pdMS_TO_TICKS(cfg::GNSS_PERIOD_MS));
    }
}

static void imuTask(void*) {
    TickType_t wake = xTaskGetTickCount();
    for (;;) {
        const uint32_t t = millis();
        switch (gImuRequest) {
            case 1: imu.triggerPothole(t); break;
            case 2: imu.triggerCrash(t); break;
            case 3: imu.recover(t); break;
        }
        gImuRequest = 0;
        ImuSample s = imu.sample(t, gSpeedKmh);
        if (xQueueSend(qMotion, &s, 0) != pdTRUE) ++gImuOverruns;
        vTaskDelayUntil(&wake, pdMS_TO_TICKS(cfg::IMU_PERIOD_MS));
    }
}

static void rideTask(void*) {
    GnssFix fix;
    uint32_t n = 0;
    for (;;) {
        xQueueReceive(qFix, &fix, portMAX_DELAY);
        {
            Lock l;
            if (gnss.takeWrapped() && session.active()) {
                storeFinishedRide("route loop completed");
                session.start(nowEpoch(), gTimeSynced);
                Serial.println("[RIDE] new ride started");
            }

            if (session.active()) {
                const auto res = session.addFix(fix);
                const auto& st = session.stats();
                switch (res) {
                case RideStats::Result::RejectedInvalid:
                    Serial.printf("[GNSS] no fix (sats=%u) - distance paused, crash detection unaffected\n", fix.sats);
                    break;
                case RideStats::Result::RejectedImplausible:
                    Serial.printf("[GNSS] REJECTED outlier: %.0f m jump in 1 s (%.0f km/h > v_max %.0f)\n",
                                  st.lastStepM(), st.lastStepM() * 3.6, cfg::V_MAX_KMH);
                    break;
                default:
                    if (++n % 5 == 0) {
                        char d[12];
                        fmtDuration(d, sizeof d, st.durationS());
                        Serial.printf("[RIDE] %6.2f km  %s  %5.1f km/h  max %5.1f  sats %u  pts %u\n",
                                      st.distanceM() / 1000.0, d, fix.speedKmh, st.maxSpeedKmh(), fix.sats,
                                      (unsigned)session.pointCount());
                    }
                }
            }
            if (fix.valid) gLastFix = fix;
            gLastFix.valid = fix.valid;  // keep last good position, but report fix loss
            gLastFix.sats = fix.sats;
            gSpeedKmh = fix.valid ? fix.speedKmh : gSpeedKmh;
        }
        xTaskNotifyGive(hBleTask);  // publish LIVE
    }
}

static void crashTask(void*) {
    ImuSample s;
    for (;;) {
        xQueueReceive(qMotion, &s, portMAX_DELAY);
        if (gDetectorReset) {
            detector.reset();
            gDetectorReset = false;
        }
        if (!detector.update(s)) continue;

        const auto st = detector.state();
        Serial.printf("[CRASH] -> %s (%s)  peak %.1f g  tilt %.0f deg\n",
                      st == CrashDetector::State::Idle ? "IDLE" :
                      st == CrashDetector::State::Impact ? "IMPACT" :
                      st == CrashDetector::State::Tilt ? "TILT" : "CONFIRMED",
                      detector.lastReason(), detector.peakG(), detector.tiltDeg());

        proto::CrashPacket p{};
        p.version = proto::VERSION;
        p.state = static_cast<uint8_t>(st);
        p.peakG = static_cast<uint16_t>(detector.peakG() * proto::G_SCALE);
        {
            Lock l;
            p.epochS = nowEpoch();
            p.lat = e7(gLastFix.lat);
            p.lon = e7(gLastFix.lon);
            if (st == CrashDetector::State::Confirmed) {
                gCrashPending = true;
                if (session.active()) session.addCrash();
            }
        }
        xQueueSend(qCrashEvt, &p, 0);
    }
}

// ----------------------------------------------------- ride-log transfer ----

static void sendLogList() {
    std::vector<proto::LogListEntry> entries;
    {
        Lock l;
        for (const auto& r : logs.all()) {
            proto::LogListEntry e{};
            e.type = proto::LOG_LIST_ENTRY;
            e.id = r.id;
            e.startEpoch = r.startEpoch;
            e.distanceM = r.distanceM;
            e.durationS = r.durationS;
            e.pointCount = static_cast<uint16_t>(r.points.size());
            entries.push_back(e);
        }
    }
    for (const auto& e : entries) if (!ble::sendLogFrame(&e, sizeof e)) return;
    proto::LogListEnd end{proto::LOG_LIST_END, static_cast<uint16_t>(entries.size())};
    ble::sendLogFrame(&end, sizeof end);
    Serial.printf("[LOG] listed %u ride logs\n", (unsigned)entries.size());
}

static void sendLog(uint16_t id) {
    RideLog copy;
    bool found = false;
    {
        Lock l;
        if (const RideLog* r = logs.find(id)) { copy = *r; found = true; }
    }
    if (!found) {
        proto::LogErrorFrame err{proto::LOG_ERROR, proto::LOG_ERR_UNKNOWN_ID, id};
        ble::sendLogFrame(&err, sizeof err);
        Serial.printf("[LOG] GET #%u: unknown id\n", id);
        return;
    }

    const uint32_t t0 = millis();
    proto::LogRideHeader h{};
    h.type = proto::LOG_RIDE_HEADER;
    h.id = id;
    h.startEpoch = copy.startEpoch;
    h.endEpoch = copy.endEpoch;
    h.distanceM = copy.distanceM;
    h.maxSpeed = copy.maxSpeed;
    h.avgSpeed = copy.avgSpeed;
    h.pointCount = static_cast<uint16_t>(copy.points.size());
    h.crashCount = copy.crashCount;
    if (!ble::sendLogFrame(&h, sizeof h)) return;

    uint16_t crc = 0xFFFF;
    for (uint16_t i = 0; i < copy.points.size(); ++i) {
        proto::LogPoint p{};
        p.type = proto::LOG_POINT;
        p.id = id;
        p.seq = i;
        p.lat = copy.points[i].lat;
        p.lon = copy.points[i].lon;
        p.speed = copy.points[i].speed;
        crc = proto::crc16_update(crc, reinterpret_cast<const uint8_t*>(&p) + 3, sizeof p - 3);
        if (!ble::sendLogFrame(&p, sizeof p)) {
            Serial.printf("[LOG] GET #%u aborted at point %u (link lost)\n", id, i);
            return;
        }
    }
    proto::LogRideEnd end{proto::LOG_RIDE_END, id, crc};
    ble::sendLogFrame(&end, sizeof end);
    Serial.printf("[LOG] sent #%u: %u points in %lu ms (crc %04X)\n", id, (unsigned)copy.points.size(),
                  (unsigned long)(millis() - t0), crc);
}

// ------------------------------------------------------- command handler ----

static uint16_t le16(const uint8_t* b) { return b[0] | (b[1] << 8); }
static uint32_t le32(const uint8_t* b) { return b[0] | (b[1] << 8) | (b[2] << 16) | ((uint32_t)b[3] << 24); }

static void publishCrashState(uint8_t state) {
    proto::CrashPacket p{};
    p.version = proto::VERSION;
    p.state = state;
    {
        Lock l;
        p.epochS = nowEpoch();
        p.lat = e7(gLastFix.lat);
        p.lon = e7(gLastFix.lon);
    }
    ble::publishCrash(p);
}

static void handleCommand(const Command& c) {
    switch (c.op) {
    case proto::OP_RIDE_START: {
        Lock l;
        if (session.active()) { Serial.println("[CMD] ride already active"); break; }
        session.start(nowEpoch(), gTimeSynced);
        Serial.println("[CMD] ride started");
        break;
    }
    case proto::OP_RIDE_STOP: {
        Lock l;
        if (!session.active()) { Serial.println("[CMD] no active ride"); break; }
        storeFinishedRide("stop command");
        break;
    }
    case proto::OP_SET_TIME: {
        const uint32_t epoch = le32(c.args);
        Lock l;
        const int64_t delta = static_cast<int64_t>(epoch) - nowEpoch();
        gEpochBase = epoch;
        gMillisBase = millis();
        if (!gTimeSynced) {
            if (session.active()) session.shiftStart(delta);
            logs.shiftUnsynced(delta);
        }
        gTimeSynced = true;
        Serial.printf("[CMD] clock set to %lu (delta %+lld s)\n", (unsigned long)epoch, (long long)delta);
        break;
    }
    case proto::OP_CRASH_CANCEL:
        if (!gCrashPending && detector.state() == CrashDetector::State::Idle) {
            Serial.println("[CMD] cancel: no crash pending");
            break;
        }
        gDetectorReset = true;
        gImuRequest = 3;  // simulated rider lifts the vehicle back up
        gCrashPending = false;
        publishCrashState(proto::CRASH_CANCELLED);
        Serial.println("[CMD] crash alert CANCELLED by rider");
        break;
    case proto::OP_LOG_LIST:
        sendLogList();
        break;
    case proto::OP_LOG_GET:
        sendLog(le16(c.args));
        break;
    case proto::OP_LOG_ACK: {
        const uint16_t id = le16(c.args);
        if (cfg::ACK_DELETES_LOG) {
            Lock l;
            Serial.printf("[LOG] ACK #%u -> %s\n", id, logs.remove(id) ? "deleted from device" : "unknown id");
        }
        break;
    }
    case proto::OP_SIM_POTHOLE:
        Serial.println("[SIM] pothole: impact without fall (should be REJECTED)");
        gImuRequest = 1;
        break;
    case proto::OP_SIM_CRASH:
        if (imu.vehicleDown()) { Serial.println("[SIM] vehicle already down"); break; }
        Serial.println("[SIM] crash: impact -> roll onto side -> lie still");
        gImuRequest = 2;
        break;
    default:
        Serial.printf("[CMD] unknown opcode 0x%02x\n", c.op);
    }
}

static void bleTask(void*) {
    Command cmd;
    proto::CrashPacket crash;
    for (;;) {
        if (xQueueReceive(qCmd, &cmd, pdMS_TO_TICKS(20)) == pdTRUE) handleCommand(cmd);

        while (xQueueReceive(qCrashEvt, &crash, 0) == pdTRUE) ble::publishCrash(crash);

        if (ulTaskNotifyTake(pdTRUE, 0)) {
            proto::LivePacket p{};
            p.version = proto::VERSION;
            {
                Lock l;
                const auto& st = session.stats();
                p.flags = proto::LIVE_IMU_OK | proto::LIVE_SIMULATED;
                if (gLastFix.valid) p.flags |= proto::LIVE_GNSS_FIX;
                if (session.active()) p.flags |= proto::LIVE_RIDE_ACTIVE;
                if (gCrashPending) p.flags |= proto::LIVE_CRASH_PENDING;
                if (gTimeSynced) p.flags |= proto::LIVE_TIME_SYNCED;
                p.sats = gLastFix.sats;
                p.batteryPct = proto::BATTERY_UNKNOWN;
                p.speed = static_cast<uint16_t>(gLastFix.valid ? gLastFix.speedKmh * proto::SPEED_SCALE : 0);
                p.rideDistanceM = session.active() ? static_cast<uint32_t>(st.distanceM()) : 0;
                p.rideDurationS = session.active() ? st.durationS() : 0;
                p.lat = e7(gLastFix.lat);
                p.lon = e7(gLastFix.lon);
            }
            ble::publishLive(p);
        }
    }
}

// --------------------------------------------------------- console / LED ----

static void printHelp() {
    Serial.println(
        "\nCommands:  h help | i info | l list logs | s start ride | x stop ride\n"
        "           c simulate crash | p simulate pothole | k cancel crash alert\n"
        "BOOT button: simulate crash (or cancel when an alert is pending)\n");
}

static void printInfo() {
    Lock l;
    Serial.printf("\nSmartRide OBU %s on %s | BLE %s (%s, MTU %u) | clock %lu (%s)\n", FW_VERSION, SMARTRIDE_BOARD,
                  ble::deviceName(), ble::connected() ? "connected" : "advertising", ble::mtu(),
                  (unsigned long)nowEpoch(), gTimeSynced ? "synced" : "build time");
    Serial.printf("ride: %s  route idx %u  crash state %u  imu overruns %lu  free heap %lu\n",
                  session.active() ? "ACTIVE" : "idle", gnss.index(), (unsigned)detector.state(),
                  (unsigned long)gImuOverruns, (unsigned long)ESP.getFreeHeap());
}

static void printLogs() {
    Lock l;
    Serial.printf("\n%u ride logs on device:\n", (unsigned)logs.all().size());
    for (const auto& r : logs.all()) {
        char d[12];
        fmtDuration(d, sizeof d, r.durationS);
        Serial.printf("  #%-3u start %lu  %6.2f km  %s  avg %5.1f  max %5.1f km/h  %3u pts  crashes %u\n", r.id,
                      (unsigned long)r.startEpoch, r.distanceM / 1000.0, d, r.avgSpeed / 100.0, r.maxSpeed / 100.0,
                      (unsigned)r.points.size(), r.crashCount);
    }
}

static void pollConsole() {
    while (Serial.available()) {
        const char ch = static_cast<char>(Serial.read());
        Command c{};
        switch (ch) {
            case 'h': case '?': printHelp(); break;
            case 'i': printInfo(); break;
            case 'l': printLogs(); break;
            case 's': c.op = proto::OP_RIDE_START; pushCommand(c); break;
            case 'x': c.op = proto::OP_RIDE_STOP; pushCommand(c); break;
            case 'c': c.op = proto::OP_SIM_CRASH; pushCommand(c); break;
            case 'p': c.op = proto::OP_SIM_POTHOLE; pushCommand(c); break;
            case 'k': c.op = proto::OP_CRASH_CANCEL; pushCommand(c); break;
            default: break;
        }
    }
}

static void pollBootButton() {
    static bool last = true;
    static uint32_t lastChange = 0;
    const bool now = digitalRead(cfg::BOOT_BUTTON_PIN);
    if (now != last && millis() - lastChange > 50) {
        lastChange = millis();
        last = now;
        if (!now) {  // pressed
            Command c{};
            c.op = (gCrashPending || imu.vehicleDown()) ? proto::OP_CRASH_CANCEL : proto::OP_SIM_CRASH;
            pushCommand(c);
        }
    }
}

static void updateLed() {
#ifdef RGB_BUILTIN
    const uint32_t t = millis();
    uint8_t r = 0, g = 0, b = 0;
    if (gCrashPending)            r = (t / 150) % 2 ? 40 : 0;          // fast red blink
    else if (ble::connected())    g = 12;                              // steady green
    else                          b = (t / 1000) % 2 ? 0 : 12;         // slow blue blink
    static uint32_t lastRgb = 0xFFFFFFFF;
    const uint32_t rgb = (r << 16) | (g << 8) | b;
    if (rgb != lastRgb) { neopixelWrite(RGB_BUILTIN, r, g, b); lastRgb = rgb; }
#endif
}

// ------------------------------------------------------------- setup/loop ----

void setup() {
    Serial.begin(115200);
    const uint32_t t0 = millis();
    while (!Serial && millis() - t0 < 1500) delay(10);
    pinMode(cfg::BOOT_BUTTON_PIN, INPUT_PULLUP);

    Serial.printf("\n=== SmartRide OBU %s (%s) - SIMULATED SENSORS ===\n", FW_VERSION, SMARTRIDE_BOARD);

    gLock = xSemaphoreCreateMutex();
    qFix = xQueueCreate(4, sizeof(GnssFix));
    qMotion = xQueueCreate(64, sizeof(ImuSample));
    qCmd = xQueueCreate(16, sizeof(Command));
    qCrashEvt = xQueueCreate(8, sizeof(proto::CrashPacket));
    gMillisBase = millis();

    addCannedRides(logs, BUILD_EPOCH);
    Serial.printf("[LOG] %u canned ride logs loaded (stub)\n", (unsigned)logs.all().size());
    if (cfg::AUTO_START_RIDE) session.start(nowEpoch(), false);

    ble::begin(pushCommand);

    // App tasks on core 1; the NimBLE host runs on core 0.
    // ble_task first: ride_task notifies it by handle.
    xTaskCreatePinnedToCore(bleTask,   "ble_task",   6144, nullptr, 2, &hBleTask, 1);
    xTaskCreatePinnedToCore(imuTask,   "imu_task",   3072, nullptr, 5, nullptr, 1);
    xTaskCreatePinnedToCore(crashTask, "crash_task", 4096, nullptr, 4, nullptr, 1);
    xTaskCreatePinnedToCore(rideTask,  "ride_task",  4096, nullptr, 4, nullptr, 1);
    xTaskCreatePinnedToCore(gnssTask,  "gnss_task",  3072, nullptr, 3, nullptr, 1);

    printHelp();
}

void loop() {
    pollConsole();
    pollBootButton();
    updateLed();
    delay(20);
}
