/**
 * EPD 3D G6 — Pumped Up Kicks V2
 * Full operational prototype firmware
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
 *                    "mode":"<STATE>","batt":<0-100>}
 *   Notified every ~250 ms when a client is subscribed.
 *
 * Connector detect wiring:
 *   J_MAIN pin 1 (3V3_SENSE) ── 10 kΩ ──► GPIO15
 *   GPIO15 configured INPUT_PULLDOWN
 *
 * Force → extension mapping (per reference v4.1 §6):
 *   r_i  = F_i / ΣF          (relative load fraction)
 *   ext_i ∝ (1 − r_i)        (lowest load → largest extension)
 *   FSR1 → ACT1, FSR2 → ACT2, FSR3 → ACT3, FSR4 = auxiliary
 */

#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <NimBLEDevice.h>

// ── Pin map (Hardware Reference v6.0) ───────────────────────────────────────────

static constexpr uint8_t PIN_FSR[4]  = {0, 1, 2, 3};   // ADC — FSR1–4
static constexpr uint8_t PIN_ENN     = 7;                // STEPPER_ENN shared (active LOW)
static constexpr uint8_t PIN_VBAT    = 5;                // ADC — VBAT_SENSE, 1:2 divider
static constexpr uint8_t PIN_STEP[3] = {20, 18, 16};    // ACT1 ACT2 ACT3 STEP
static constexpr uint8_t PIN_DIR[3]  = {19, 14, 17};    // ACT1 ACT2 ACT3 DIR
static constexpr uint8_t PIN_SDA     = 9;
static constexpr uint8_t PIN_SCL     = 15;
static constexpr uint8_t PIN_BUTTON  = 4;               // INPUT_PULLUP, LOW = pressed (boot-safe)
static constexpr uint8_t PIN_CONN    = 15;              // INPUT_PULLDOWN, HIGH = connected

// ── OLED (0.91" SSD1306 / SH1106 — I2C 0x3C) ────────────────────────────────

static constexpr uint8_t OLED_ADDR   = 0x3C;
static Adafruit_SSD1306  oled(128, 32, &Wire, -1);
static bool              gOledOk     = false;

// ── Motion parameters ────────────────────────────────────────────────────────

static constexpr uint32_t MAX_EXT_STEPS  = 400;    // steps = full extension travel
static constexpr uint32_t STEP_DELAY_US  = 1500;   // µs between step edges → ~333 steps/s
static constexpr uint32_t STEP_PULSE_US  = 5;      // µs STEP pin HIGH width
static constexpr uint32_t ACT_STAGGER_MS = 75;     // delay between consecutive motor starts
static constexpr float    EXT_DEADBAND   = 0.08f;  // suppress negligible extensions

// ── Battery thresholds ───────────────────────────────────────────────────────

static constexpr float VBAT_WARN   = 3.5f;   // show low-battery warning on OLED
static constexpr float VBAT_CUTOFF = 3.35f;  // disable motor actuation
static constexpr float VBAT_NOISE  = 0.5f;   // guard: ignore reads below this (no battery)

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
static float   gRelLoad[4] = {};  // normalised relative load fractions (sum = 1.0)

// ── BLE ───────────────────────────────────────────────────────────────────────

#define BLE_DEVICE_NAME  "EPD3DG6"
#define BLE_SVC_UUID     "4fa0c560-78a3-11ee-b962-0242ac120002"
#define BLE_CHAR_UUID    "4fa0c561-78a3-11ee-b962-0242ac120002"

static NimBLECharacteristic* gBleChar     = nullptr;
static bool                   gBleConnected = false;
static uint32_t               gBleLastNotify = 0;

static const char* stateName(State s) {
    switch (s) {
        case ST_DISCON:    return "DISCONNECTED";
        case ST_CONN:      return "CONNECTED";
        case ST_MEASURING: return "MEASURING";
        case ST_MEASURED:  return "MEASURED";
        case ST_ACTUATING: return "ACTUATING";
        case ST_DONE:      return "DONE";
        case ST_HOMING:    return "HOMING";
        default:           return "UNKNOWN";
    }
}

/** Clamp VBAT → 0-100% (3.3V=0%, 4.2V=100%). */
static int battPercent(float v) {
    if (v < 3.3f) return 0;
    if (v > 4.2f) return 100;
    return (int)((v - 3.3f) / (4.2f - 3.3f) * 100.0f);
}

class BleServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer*) override {
        gBleConnected = true;
        Serial.println("[BLE] Client connected");
    }
    void onDisconnect(NimBLEServer* server) override {
        gBleConnected = false;
        Serial.println("[BLE] Client disconnected — restarting advertising");
        server->startAdvertising();
    }
};

static void bleNotify(float vbat) {
    if (!gBleChar) return;
    char buf[128];
    snprintf(buf, sizeof(buf),
        "{\"fsr1\":%d,\"fsr2\":%d,\"fsr3\":%d,\"mode\":\"%s\",\"batt\":%d}",
        (int)(gRelLoad[0] * 100.0f),
        (int)(gRelLoad[1] * 100.0f),
        (int)(gRelLoad[2] * 100.0f),
        stateName(gState),
        battPercent(vbat));
    gBleChar->setValue((uint8_t*)buf, strlen(buf));
    gBleChar->notify();
}

static void setupBLE() {
    NimBLEDevice::init(BLE_DEVICE_NAME);
    NimBLEDevice::setMTU(128);
    NimBLEServer* server = NimBLEDevice::createServer();
    server->setCallbacks(new BleServerCallbacks());

    NimBLEService* svc = server->createService(BLE_SVC_UUID);
    gBleChar = svc->createCharacteristic(
        BLE_CHAR_UUID,
        NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    svc->start();

    NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
    adv->addServiceUUID(BLE_SVC_UUID);
    adv->setScanResponse(true);
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

/** VBAT voltage (V). 1:2 resistor divider, 3.3 V ADC reference, 12-bit. */
static float readVbat() {
    float vadc = (medianADC(PIN_VBAT) / 4095.0f) * 3.3f;
    return vadc * 2.0f;
}

// ─────────────────────────────────────────────────────────────────────────────
// Digital inputs
// ─────────────────────────────────────────────────────────────────────────────

/** True when J_MAIN connector is attached (GPIO9 pulled HIGH by 3V3_SENSE). */
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
 * Uses content caching — only redraws when text changes, which prevents
 * unnecessary I2C writes during motor current spikes.
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

// ─────────────────────────────────────────────────────────────────────────────
// Mode 1 — Measurement
// ─────────────────────────────────────────────────────────────────────────────

static void doMeasure() {
    // Read and median-filter all FSR channels
    float raw[4];
    for (int i = 0; i < 4; i++) {
        raw[i] = (float)medianADC(PIN_FSR[i]);
    }

    // Normalise to relative load fractions: r_i = F_i / ΣF
    float sum = raw[0] + raw[1] + raw[2] + raw[3];
    if (sum < 1.0f) sum = 1.0f; // guard against no load / open pins
    for (int i = 0; i < 4; i++) gRelLoad[i] = raw[i] / sum;

    // Compute actuator target steps: extension_i ∝ (1 − r_i)
    // FSR1→ACT1  FSR2→ACT2  FSR3→ACT3  (FSR4 = auxiliary reference, no actuator)
    for (int i = 0; i < 3; i++) {
        float ext = 1.0f - gRelLoad[i];
        if (ext < EXT_DEADBAND) ext = 0.0f;             // suppress noise
        ext = constrain(ext, 0.0f, 1.0f);
        gTarget[i] = (int32_t)(ext * MAX_EXT_STEPS);
    }

    Serial.printf("[MEAS] raw=%.0f %.0f %.0f %.0f  "
                  "rel=%.3f %.3f %.3f %.3f\n",
                  raw[0], raw[1], raw[2], raw[3],
                  gRelLoad[0], gRelLoad[1], gRelLoad[2], gRelLoad[3]);
    Serial.printf("[MEAS] targets=%d %d %d steps\n",
                  (int)gTarget[0], (int)gTarget[1], (int)gTarget[2]);
}

// ─────────────────────────────────────────────────────────────────────────────
// Mode 2 — Actuation
// ─────────────────────────────────────────────────────────────────────────────

static void doActuate() {
    if (readVbat() < VBAT_CUTOFF) {
        showOLED("LOW BATTERY!", "Actuation aborted", "Charge device");
        Serial.println("[ACT] Aborted — low battery");
        delay(2000);
        return;
    }

    digitalWrite(PIN_ENN, LOW); // enable all three drivers
    delay(10);                  // driver wake-up time

    for (int i = 0; i < 3; i++) {
        // Abort if connector pulled mid-actuation
        if (!connPresent()) {
            Serial.println("[ACT] Connector lost — aborting");
            digitalWrite(PIN_ENN, HIGH);
            return;
        }
        char msg[22];
        snprintf(msg, sizeof(msg), "ACT%d  %d steps", i + 1, (int)gTarget[i]);
        showOLED("ACTUATING...", msg);
        Serial.println(msg);
        moveToTarget(i, gTarget[i]);
        delay(ACT_STAGGER_MS); // stagger motor starts (ref §16)
    }
    // Leave ENN LOW — hold torque on actuators after reaching position
}

// ─────────────────────────────────────────────────────────────────────────────
// Setup
// ─────────────────────────────────────────────────────────────────────────────

void setup() {
    Serial.begin(115200);
    delay(300);
    Serial.println("EPD 3D G6 — boot");

    // Stepper output pins
    for (int i = 0; i < 3; i++) {
        pinMode(PIN_STEP[i], OUTPUT); digitalWrite(PIN_STEP[i], LOW);
        pinMode(PIN_DIR[i],  OUTPUT); digitalWrite(PIN_DIR[i],  LOW);
    }
    pinMode(PIN_ENN, OUTPUT);
    digitalWrite(PIN_ENN, HIGH); // drivers DISABLED at boot (safe default)

    // Input pins
    pinMode(PIN_BUTTON, INPUT_PULLUP);
    pinMode(PIN_CONN,   INPUT_PULLDOWN);

    // OLED
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
    float vbat = readVbat();

    // ── Hard low-battery override (any state) ────────────────────────────────
    if (vbat > VBAT_NOISE && vbat < VBAT_CUTOFF) {
        digitalWrite(PIN_ENN, HIGH);
        showOLED("LOW BATTERY!", "Connect charger");
        delay(5000);
        return;
    }

    // ── Connector removal — disable immediately, any state ───────────────────
    if (!conn && gState != ST_DISCON) {
        digitalWrite(PIN_ENN, HIGH);   // cut motor power immediately
        // gPos is intentionally NOT reset — position is remembered across
        // plug/unplug so homing is triggered correctly on reconnect
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

        // ── No connector ─────────────────────────────────────────────────────
        case ST_DISCON:
            showOLED("DISCONNECTED", "Attach connector");
            break;

        // ── Connector present, idle ───────────────────────────────────────────
        case ST_CONN: {
            bool lowBatt = (vbat > VBAT_NOISE && vbat < VBAT_WARN);
            showOLED("CONNECTED",
                     lowBatt ? "! LOW BATTERY" : "Ready",
                     "Btn: measure");
            if (btn) {
                gState = ST_MEASURING;
            }
            break;
        }

        // ── Mode 1: measure FSRs ──────────────────────────────────────────────
        case ST_MEASURING:
            showOLED("MEASURING...", "Hold still");
            doMeasure();
            gState = ST_MEASURED;
            break;

        // ── Show result, wait for second press ────────────────────────────────
        case ST_MEASURED: {
            char l2[33], l3[33];
            snprintf(l2, sizeof(l2), "r:%.2f %.2f %.2f",
                     gRelLoad[0], gRelLoad[1], gRelLoad[2]);
            snprintf(l3, sizeof(l3), "T:%d %d %d stp",
                     (int)gTarget[0], (int)gTarget[1], (int)gTarget[2]);
            showOLED("MEAS COMPLETE", l2, l3);
            if (btn) gState = ST_ACTUATING;
            break;
        }

        // ── Mode 2: drive actuators ───────────────────────────────────────────
        case ST_ACTUATING:
            doActuate();
            gState = ST_DONE;
            break;

        // ── Insole shaped — user disconnects to use ──────────────────────────
        case ST_DONE: {
            char l2[33];
            snprintf(l2, sizeof(l2), "Ext:%d %d %d stp",
                     (int)gPos[0], (int)gPos[1], (int)gPos[2]);
            showOLED("INSOLE SHAPED", l2, "Disconnect to use");
            // No button action — user simply unplugs the connector.
            // On next reconnect, ST_HOMING will retract before new cycle.
            break;
        }

        // ── Auto-home on reconnect with actuators extended ────────────────────
        case ST_HOMING:
            showOLED("HOMING...", "Retracting all", "Please wait");
            retractAll(); // drives all axes to 0 and sets gPos[i]=0, ENN→HIGH
            gState = ST_CONN;
            Serial.println("[HOME] Complete");
            break;
    }

    delay(50); // ~20 Hz main loop rate

    // ── BLE notify (~4 Hz) ───────────────────────────────────────────────────
    uint32_t now = millis();
    if (gBleConnected && (now - gBleLastNotify) >= 250) {
        gBleLastNotify = now;
        bleNotify(vbat);
    }
}
