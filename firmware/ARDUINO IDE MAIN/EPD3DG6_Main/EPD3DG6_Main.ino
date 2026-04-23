/**
 * EPD 3D G6 — Pumped Up Kicks V2
 * Full operational prototype firmware  |  Arduino IDE version
 * Hardware Reference v6.0
 *
 * ┌──────────────────────────────────────────────────────┐
 * │  State machine                                        │
 * │                                                       │
 * │  DISCONNECTED ──[plug in]───► CONNECTED              │
 * │  CONNECTED    ──[btn #1]────► MEASURING → MEASURED   │
 * │  MEASURED     ──[btn #2]────► ACTUATING → DONE       │
 * │  DONE         ──[btn #3]────► retract  ─► CONNECTED  │
 * │  any state    ──[unplug]────► DISCONNECTED            │
 * └──────────────────────────────────────────────────────┘
 *
 * BLE:
 *   Service UUID  : 4fa0c560-78a3-11ee-b962-0242ac120002
 *   Characteristic: 4fa0c561-78a3-11ee-b962-0242ac120002  (NOTIFY, READ)
 *   Payload (JSON): {"fsr1":<0-100>,"fsr2":<0-100>,"fsr3":<0-100>,
 *                    "mode":"<STATE>","snap":<0|1>}
 *   snap=0 → live FSR readings  (all states except ACTUATING / DONE)
 *   snap=1 → measurement snapshot (ACTUATING and DONE states)
 *   Notified every ~250 ms when a client is subscribed.
 *
 * ════════════════════════════════════════════════════════
 *  REQUIRED LIBRARIES
 *  Install via  Sketch > Include Library > Manage Libraries
 * ════════════════════════════════════════════════════════
 *  1. Adafruit SSD1306       by Adafruit    (^2.5.7)
 *     → automatically installs Adafruit GFX Library as a dependency
 *  2. NimBLE-Arduino         by h2zero      (^2.1.0)
 *
 * ════════════════════════════════════════════════════════
 *  BOARD SETUP  (Tools menu in Arduino IDE)
 * ════════════════════════════════════════════════════════
 *  Board Manager URL (add in File > Preferences):
 *    Espressif official:
 *      https://espressif.github.io/arduino-esp32/package_esp32_index.json
 *    OR pioarduino fork (recommended — better ESP32-C6 support):
 *      https://github.com/pioarduino/arduino-esp32/releases/download/3.1.3/package_esp32_index.json
 *
 *  Board:              ESP32C6 Dev Module
 *  USB CDC On Boot:    Enabled          ← REQUIRED for Serial over USB-C
 *  Flash Mode:         QIO
 *  Upload Speed:       921600
 *  Port:               (select the COM port for your ESP32-C6)
 *
 * Connector detect wiring:
 *   J_MAIN pin 1 (3V3_SENSE) ── 10 kΩ ──► GPIO15
 *   GPIO15 configured INPUT_PULLDOWN
 */

#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <NimBLEDevice.h>

// ── Pin map (Hardware Reference v6.0) ───────────────────────────────────────────

static constexpr uint8_t PIN_FSR[4]  = {0, 1, 2, 3};   // ADC — FSR1–4
static constexpr uint8_t PIN_ENN     = 7;                // STEPPER_ENN shared (active LOW)
static constexpr uint8_t PIN_STEP[3] = {20, 18, 16};    // ACT1 ACT2 ACT3 STEP
static constexpr uint8_t PIN_DIR[3]  = {19, 14, 17};    // ACT1 ACT2 ACT3 DIR
static constexpr uint8_t PIN_SDA     = 7;                // OLED SDA — GPIO7 (hw ref v6.0)
static constexpr uint8_t PIN_SCL     = 6;                // OLED SCL — GPIO6 (hw ref v6.0)
static constexpr uint8_t PIN_BUTTON  = 4;               // INPUT_PULLUP, LOW = pressed (boot-safe)
static constexpr uint8_t PIN_CONN    = 15;              // INPUT_PULLDOWN, HIGH = connected (GPIO15)

// ── OLED (0.91" SSD1306 / SH1106 — I2C 0x3C) ────────────────────────────────

static constexpr uint8_t OLED_ADDR   = 0x3C;
static Adafruit_SSD1306  oled(128, 32, &Wire, -1);
static bool              gOledOk     = false;

// ── Motion parameters ────────────────────────────────────────────────────────

static constexpr uint32_t MAX_EXT_STEPS  = 21000;  // steps = full extension travel
static constexpr uint32_t STEP_DELAY_US  = 500;    // µs between step edges (3× cycle test speed)
static constexpr uint32_t STEP_PULSE_US  = 5;      // µs STEP pin HIGH width
static constexpr uint32_t ACT_STAGGER_MS = 75;     // delay between consecutive motor starts
static constexpr float    EXT_DEADBAND   = 0.08f;  // suppress negligible extensions

// ── ADC sampling ─────────────────────────────────────────────────────────────

static constexpr int ADC_SAMPLES = 7; // must be odd for clean median

// ── State machine ────────────────────────────────────────────────────────────

enum State : uint8_t {
    ST_DISCON,      // connector not present
    ST_CONN,        // connector present, idle — waiting for first button press
    ST_MEASURING,   // reading FSR channels
    ST_MEASURED,    // measurement stored — waiting for second button press
    ST_ACTUATING,   // driving actuators to computed targets
    ST_DONE,        // actuation complete — insole shaped, user disconnects to use
    ST_HOMING       // reconnected with actuators extended — retract before new cycle
};

static State   gState     = ST_DISCON;
static int32_t gPos[3]    = {};   // tracked position in steps from home (0 = retracted)
static int32_t gTarget[3] = {};   // computed target steps from last measurement
static float   gRelLoad[4]      = {};  // live normalised relative load fractions (sum = 1.0)
static float   gMeasuredLoad[3] = {};  // snapshot saved at end of doMeasure() — used during ACTUATING/DONE
static int     gLiveRaw[3]      = {};  // continuous single-sample live ADC reads for BLE display

// Minimum total raw ADC sum across all 3 FSRs to consider as real pressure.
// Below this, all live channels are shown as 0 (noise floor).
static constexpr int ADC_NOISE_FLOOR = 150; // per-channel floor — below this, channel reads 0

// Per-sensor full-scale ADC value (12-bit, 0–4095).
// Set each to the raw ADC reading you see in Serial Monitor when pressing FIRMLY on that FSR.
// Watch Serial Monitor (115200 baud) — it prints: [LIVE] r0=XXXX r1=XXXX r2=XXXX
static constexpr int FSR_SCALE[3] = {
    3,  // FSR0 = Metatarsal — raise if bar barely moves; lower if it maxes out too easily
    3,  // FSR1 = Arch       — stuck at 100% means this is too low; raise it
    3   // FSR2 = Heel       — too sensitive means this is too high; lower it
};

// ── BLE ───────────────────────────────────────────────────────────────────────

#define BLE_DEVICE_NAME  "EPD3DG6"
#define BLE_SVC_UUID     "4fa0c560-78a3-11ee-b962-0242ac120002"
#define BLE_CHAR_UUID    "4fa0c561-78a3-11ee-b962-0242ac120002"
#define BLE_CMD_UUID     "4fa0c562-78a3-11ee-b962-0242ac120002"  // WRITE — app→device

// BLE commands (single-byte)
#define CMD_BUTTON  0x01  // virtual button press
#define CMD_HOME    0x02  // force home all actuators
#define CMD_ESTOP   0x03  // emergency stop — disable drivers immediately

static NimBLECharacteristic* gBleChar     = nullptr;
static NimBLECharacteristic* gBleCmdChar  = nullptr;
static bool                   gBleConnected = false;
static uint32_t               gBleLastNotify = 0;

// Pending commands set from BLE write callback, consumed in loop()
static volatile bool gCmdButton = false;
static volatile bool gCmdHome   = false;
static volatile bool gCmdEstop  = false;

static const char* stateName(State s) {
    switch (s) {
        case ST_DISCON:    return "DISCONNECTED";
        case ST_CONN:      return "IDLE";
        case ST_MEASURING: return "MEASURING";
        case ST_MEASURED:  return "MEASURED";
        case ST_ACTUATING: return "ACTUATING";
        case ST_DONE:      return "DONE";
        case ST_HOMING:    return "HOMING";
        default:           return "UNKNOWN";
    }
}

class BleServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer*, NimBLEConnInfo&) override {
        gBleConnected = true;
        Serial.println("[BLE] Client connected");
    }
    void onDisconnect(NimBLEServer* server, NimBLEConnInfo&, int) override {
        gBleConnected = false;
        Serial.println("[BLE] Client disconnected — restarting advertising");
        server->startAdvertising();
    }
};

static void bleNotify() {
    if (!gBleChar) return;

    bool useSnapshot = (gState == ST_ACTUATING || gState == ST_DONE);
    const float* fsrSrc = useSnapshot ? gMeasuredLoad : gRelLoad;

    char buf[160];
    snprintf(buf, sizeof(buf),
        "{\"fsr1\":%d,\"fsr2\":%d,\"fsr3\":%d,"
        "\"live1\":%d,\"live2\":%d,\"live3\":%d,"
        "\"mode\":\"%s\",\"snap\":%d,"
        "\"act1\":%d,\"act2\":%d,\"act3\":%d}",
        (int)(fsrSrc[0] * 100.0f),
        (int)(fsrSrc[1] * 100.0f),
        (int)(fsrSrc[2] * 100.0f),
        gLiveRaw[0],
        gLiveRaw[1],
        gLiveRaw[2],
        stateName(gState),
        useSnapshot ? 1 : 0,
        (gState == ST_ACTUATING || gState == ST_DONE) ? (int)gPos[0] : 0,
        (gState == ST_ACTUATING || gState == ST_DONE) ? (int)gPos[1] : 0,
        (gState == ST_ACTUATING || gState == ST_DONE) ? (int)gPos[2] : 0);
    gBleChar->setValue((uint8_t*)buf, strlen(buf));
    gBleChar->notify();
}

class BleCmdCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* c, NimBLEConnInfo&) override {
        auto val = c->getValue();
        if (val.size() == 0) return;
        uint8_t cmd = val[0];
        Serial.printf("[BLE CMD] 0x%02X\n", cmd);
        if      (cmd == CMD_BUTTON) gCmdButton = true;
        else if (cmd == CMD_HOME)   gCmdHome   = true;
        else if (cmd == CMD_ESTOP)  gCmdEstop  = true;
    }
};

static void setupBLE() {
    NimBLEDevice::init(BLE_DEVICE_NAME);
    NimBLEDevice::setMTU(185);
    NimBLEServer* server = NimBLEDevice::createServer();
    server->setCallbacks(new BleServerCallbacks());
    server->advertiseOnDisconnect(true);

    NimBLEService* svc = server->createService(BLE_SVC_UUID);
    gBleChar = svc->createCharacteristic(
        BLE_CHAR_UUID,
        NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    gBleCmdChar = svc->createCharacteristic(
        BLE_CMD_UUID,
        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    gBleCmdChar->setCallbacks(new BleCmdCallbacks());
    svc->start();

    NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
    adv->addServiceUUID(BLE_SVC_UUID);
    adv->setName(BLE_DEVICE_NAME);
    adv->enableScanResponse(true);
    adv->start();
    Serial.println("[BLE] Advertising as " BLE_DEVICE_NAME);
}

// ── Button debounce ───────────────────────────────────────────────────────────

static bool     gLastBtn     = HIGH;
static uint32_t gLastBtnTime = 0;
static constexpr uint32_t BTN_DEBOUNCE_MS = 30;

// ─────────────────────────────────────────────────────────────────────────────
// ADC helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Insertion-sort median of ADC_SAMPLES reads on `pin`. */
static int medianADC(uint8_t pin) {
    int buf[ADC_SAMPLES];
    for (int i = 0; i < ADC_SAMPLES; i++) {
        buf[i] = analogRead(pin);
        delay(4);
    }
    for (int i = 1; i < ADC_SAMPLES; i++) {
        int k = buf[i], j = i - 1;
        while (j >= 0 && buf[j] > k) { buf[j + 1] = buf[j--]; }
        buf[j + 1] = k;
    }
    return buf[ADC_SAMPLES / 2];
}

// ─────────────────────────────────────────────────────────────────────────────
// Digital inputs
// ─────────────────────────────────────────────────────────────────────────────

/** True when J_MAIN connector is attached (GPIO15 pulled HIGH by 3V3_SENSE). */
static bool connPresent() {
    return digitalRead(PIN_CONN) == HIGH;
}

/** Returns true exactly once per button falling edge (with debounce). */
static bool btnEdge() {
    bool cur = (digitalRead(PIN_BUTTON) == LOW);
    uint32_t now = millis();
    bool edge = false;
    if (cur && !gLastBtn && (now - gLastBtnTime) > BTN_DEBOUNCE_MS) {
        edge = true;
        gLastBtnTime = now;
    }
    gLastBtn = cur;
    return edge;
}

// ─────────────────────────────────────────────────────────────────────────────
// Stepper motion
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Send pulses on one stepper axis.
 * Positive steps = DIR HIGH (extend), negative = DIR LOW (retract).
 */
static void driveSteps(uint8_t sp, uint8_t dp, int32_t steps) {
    if (!steps) return;
    digitalWrite(dp, (steps > 0) ? HIGH : LOW);
    delayMicroseconds(2); // DIR setup time before first pulse
    uint32_t n = (uint32_t)abs((long)steps);
    for (uint32_t i = 0; i < n; i++) {
        digitalWrite(sp, HIGH); delayMicroseconds(STEP_PULSE_US);
        digitalWrite(sp, LOW);  delayMicroseconds(STEP_DELAY_US);
    }
}

/** Move actuator `idx` to absolute target steps from home. Updates gPos. */
static void moveToTarget(int idx, int32_t target) {
    int32_t delta = target - gPos[idx];
    if (!delta) return;
    driveSteps(PIN_STEP[idx], PIN_DIR[idx], delta);
    gPos[idx] = target;
}

/**
 * Enable drivers, retract all actuators to home sequentially, then disable.
 * Called after the user confirms reset in ST_DONE.
 */
static void retractAll() {
    digitalWrite(PIN_ENN, LOW);
    delay(10);
    for (int i = 0; i < 3; i++) {
        moveToTarget(i, 0);
        delay(ACT_STAGGER_MS);
    }
    digitalWrite(PIN_ENN, HIGH); // disable after fully retracted
}

// ─────────────────────────────────────────────────────────────────────────────
// OLED
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Display up to 3 rows on the 128×32 OLED.
 * Uses content caching — only redraws when text changes.
 */
static void showOLED(const char* r1, const char* r2 = "", const char* r3 = "") {
    static char p1[33] = "\x01", p2[33] = {}, p3[33] = {};
    if (!gOledOk) return;
    if (!strcmp(p1, r1) && !strcmp(p2, r2) && !strcmp(p3, r3)) return;
    strncpy(p1, r1, 32); strncpy(p2, r2, 32); strncpy(p3, r3, 32);
    oled.clearDisplay();
    oled.setTextColor(SSD1306_WHITE);
    oled.setTextSize(1);
    oled.setCursor(0,  0); oled.print(r1);
    oled.setCursor(0, 11); oled.print(r2);
    oled.setCursor(0, 22); oled.print(r3);
    oled.display();
}

/** Row-3 status bar: "BLE:OK" — always shown with every state message. */
static void showOLEDWithStatus(const char* r1, const char* r2 = "") {
    char r3[22];
    snprintf(r3, sizeof(r3), "BLE:%s",
             gBleConnected ? "OK" : "--");
    showOLED(r1, r2, r3);
}

// ─────────────────────────────────────────────────────────────────────────────
// Mode 1 — Measurement
// ─────────────────────────────────────────────────────────────────────────────

static void doMeasure() {
    float raw[4];
    for (int i = 0; i < 4; i++) {
        raw[i] = (float)medianADC(PIN_FSR[i]);
    }

    float sum = raw[0] + raw[1] + raw[2] + raw[3];
    if (sum < 1.0f) sum = 1.0f;
    for (int i = 0; i < 4; i++) gRelLoad[i] = raw[i] / sum;

    for (int i = 0; i < 3; i++) {
        float ext = 1.0f - gRelLoad[i];
        if (ext < EXT_DEADBAND) ext = 0.0f;
        ext = constrain(ext, 0.0f, 1.0f);
        gTarget[i] = (int32_t)(ext * MAX_EXT_STEPS);
    }

    for (int i = 0; i < 3; i++) gMeasuredLoad[i] = gRelLoad[i];

    Serial.printf("[MEAS] raw=%.0f %.0f %.0f %.0f  "
                  "rel=%.3f %.3f %.3f %.3f\n",
                  raw[0], raw[1], raw[2], raw[3],
                  gRelLoad[0], gRelLoad[1], gRelLoad[2], gRelLoad[3]);
    Serial.printf("[MEAS] targets=%d %d %d steps  snapshot saved\n",
                  (int)gTarget[0], (int)gTarget[1], (int)gTarget[2]);
}

// ─────────────────────────────────────────────────────────────────────────────
// Mode 2 — Actuation
// ─────────────────────────────────────────────────────────────────────────────

static void doActuate() {
    digitalWrite(PIN_ENN, LOW);
    delay(10);

    for (int i = 0; i < 3; i++) {
        if (!connPresent()) {
            Serial.println("[ACT] Connector lost — aborting");
            digitalWrite(PIN_ENN, HIGH);
            return;
        }
        char msg[22];
        snprintf(msg, sizeof(msg), "ACT%d  %d steps", i + 1, (int)gTarget[i]);
        showOLEDWithStatus("ACTUATING...", msg);
        Serial.println(msg);
        moveToTarget(i, gTarget[i]);
        delay(ACT_STAGGER_MS);
    }
    delay(20);
    digitalWrite(PIN_ENN, HIGH);
    Serial.println("[ACT] Complete — drivers disabled");
}

// ─────────────────────────────────────────────────────────────────────────────
// Setup
// ─────────────────────────────────────────────────────────────────────────────

void setup() {
    Serial.begin(115200);
    delay(3000); // wait for USB CDC host to reconnect after reset
    Serial.println("EPD 3D G6 — boot");

    for (int i = 0; i < 3; i++) {
        pinMode(PIN_STEP[i], OUTPUT); digitalWrite(PIN_STEP[i], LOW);
        pinMode(PIN_DIR[i],  OUTPUT); digitalWrite(PIN_DIR[i],  LOW);
    }
    pinMode(PIN_ENN, OUTPUT);
    digitalWrite(PIN_ENN, HIGH); // drivers DISABLED at boot (safe default)

    pinMode(PIN_BUTTON, INPUT_PULLUP);
    pinMode(PIN_CONN,   INPUT_PULLDOWN);

    // ADC — 12-bit resolution, 11 dB attenuation for FSR channels (GPIO 0-3).
    analogReadResolution(12);
    analogSetAttenuation(ADC_11db);

    Wire.begin(PIN_SDA, PIN_SCL);
    gOledOk = oled.begin(SSD1306_SWITCHCAPVCC, OLED_ADDR);
    if (gOledOk) {
        oled.clearDisplay();
        oled.display();
    } else {
        Serial.println("OLED not detected — check SDA/SCL and address");
    }

    showOLED("EPD 3D G6", "Initializing...");
    delay(600);

    setupBLE();

    Serial.println("Ready");
}

// ─────────────────────────────────────────────────────────────────────────────
// Main loop (~20 Hz poll)
// ─────────────────────────────────────────────────────────────────────────────

void loop() {
    bool  conn = connPresent();
    bool  btn  = btnEdge();

    // ── BLE command handling (consumed once per loop) ─────────────────────
    if (gCmdEstop) {
        gCmdEstop = false;
        digitalWrite(PIN_ENN, HIGH);
        showOLEDWithStatus("E-STOP", "BLE command");
        Serial.println("[ESTOP] BLE emergency stop");
    }
    if (gCmdHome) {
        gCmdHome = false;
        if (gState != ST_HOMING && gState != ST_ACTUATING) {
            gState = ST_HOMING;
            Serial.println("[HOME] BLE triggered");
        }
    }
    if (gCmdButton) {
        gCmdButton = false;
        btn = true;
        Serial.println("[BTN] BLE virtual press");
    }

    // ── Connector removal — disable immediately, any state ───────────────────
    if (!conn && gState != ST_DISCON) {
        digitalWrite(PIN_ENN, HIGH);
        gState = ST_DISCON;
        Serial.println("[CONN] Removed");
    }

    // ── Connector re-attach ──────────────────────────────────────────────────
    if (conn && gState == ST_DISCON) {
        bool needsHome = false;
        for (int i = 0; i < 3; i++) if (gPos[i] != 0) { needsHome = true; break; }
        gState = needsHome ? ST_HOMING : ST_CONN;
        Serial.printf("[CONN] Attached — %s\n", needsHome ? "homing first" : "ready");
    }

    // ── State machine ────────────────────────────────────────────────────────
    switch (gState) {

        case ST_DISCON:
            showOLEDWithStatus("DISCONNECTED", "Attach connector");
            break;

        case ST_CONN:
            showOLEDWithStatus("CONNECTED", "Btn: measure");
            if (btn) gState = ST_MEASURING;
            break;

        case ST_MEASURING:
            showOLEDWithStatus("MEASURING...", "Hold still");
            doMeasure();
            gState = ST_MEASURED;
            break;

        case ST_MEASURED: {
            char l2[33];
            snprintf(l2, sizeof(l2), "r:%.2f %.2f %.2f",
                     gRelLoad[0], gRelLoad[1], gRelLoad[2]);
            showOLEDWithStatus("MEAS COMPLETE", l2);
            if (btn) gState = ST_ACTUATING;
            break;
        }

        case ST_ACTUATING:
            doActuate();
            gState = ST_DONE;
            break;

        case ST_DONE:
            showOLEDWithStatus("INSOLE SHAPED", "Unplug to use");
            break;

        case ST_HOMING:
            showOLEDWithStatus("HOMING...", "Retracting...");
            retractAll();
            gState = ST_CONN;
            Serial.println("[HOME] Complete");
            break;
    }

    // ── Continuous live FSR read (single sample, fast) ────────────────────
    if (gState != ST_ACTUATING && gState != ST_HOMING) {
        int raw[3] = {
            analogRead(PIN_FSR[0]),
            analogRead(PIN_FSR[1]),
            analogRead(PIN_FSR[2])
        };
        static uint8_t logTick = 0;
        if (++logTick >= 20) {
            logTick = 0;
            Serial.printf("[LIVE] r0=%4d r1=%4d r2=%4d  ->  live%%: %d %d %d\n",
                raw[0], raw[1], raw[2],
                constrain((raw[0] * 100) / FSR_SCALE[0], 0, 100),
                constrain((raw[1] * 100) / FSR_SCALE[1], 0, 100),
                constrain((raw[2] * 100) / FSR_SCALE[2], 0, 100));
        }
        for (int i = 0; i < 3; i++) {
            if (raw[i] < ADC_NOISE_FLOOR)
                gLiveRaw[i] = 0;
            else
                gLiveRaw[i] = constrain((raw[i] * 100) / FSR_SCALE[i], 0, 100);
        }
    }

    delay(50); // ~20 Hz main loop rate

    // ── BLE notify (~4 Hz) ───────────────────────────────────────────────
    uint32_t now = millis();
    if (gBleConnected && (now - gBleLastNotify) >= 250) {
        gBleLastNotify = now;
        bleNotify();
    }
}
