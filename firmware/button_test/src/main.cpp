/**
 * button_test — EPD 3D G6
 *
 * MODE button (GPIO4, INPUT_PULLUP, active LOW) triggers a sequence:
 *   Press → extend 5 s → retract 5 s → stop
 *
 * Wiring (Electronics Reference v5.0):
 *   GPIO 20 → ACT1_STEP
 *   GPIO 19 → ACT1_DIR
 *   GPIO 7  → OLED SDA
 *   GPIO 6  → OLED SCL
 *   GPIO 4  → MODE_BTN  (INPUT_PULLUP — do NOT hold at boot)
 *   ENN     → GND       (driver always enabled)
 */

#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

// ── Pins ─────────────────────────────────────────────────────────────────────
static constexpr uint8_t PIN_STEP = 20;
static constexpr uint8_t PIN_DIR  = 19;
static constexpr uint8_t PIN_BTN  = 4;   // MODE_BTN, active LOW
static constexpr uint8_t PIN_SDA  = 7;
static constexpr uint8_t PIN_SCL  = 6;

// ── OLED ─────────────────────────────────────────────────────────────────────
static Adafruit_SSD1306 gDisplay(128, 64, &Wire, -1);

static void oledShow(const char* status) {
    gDisplay.clearDisplay();
    gDisplay.setTextColor(SSD1306_WHITE);
    gDisplay.setTextSize(2);
    gDisplay.setCursor(0, 24);
    gDisplay.println(status);
    gDisplay.display();
}

// ── Motion ───────────────────────────────────────────────────────────────────
static constexpr uint32_t STEP_PULSE_US   = 20;    // step HIGH pulse width (µs)
static constexpr uint32_t STEP_START_US   = 16000; // starting step period (~60 steps/s)
static constexpr uint32_t STEP_MIN_US     = 4000;  // top-speed step period (~250 steps/s)
static constexpr uint32_t ACCEL_STEPS     = 200;   // steps to ramp from start to top speed
static constexpr uint32_t RUN_DURATION_MS = 5000;  // extend/retract time (ms)

// ── State ────────────────────────────────────────────────────────────────────
enum class RunState { IDLE, EXTENDING, RETRACTING };

static RunState gState        = RunState::IDLE;
static uint32_t gPhaseStart   = 0;
static uint32_t gLastStepUs   = 0;
static uint32_t gStepCount    = 0;   // steps taken in current phase (for ramp)
static bool     gLastBtn      = false;

// ── Helpers ──────────────────────────────────────────────────────────────────
static void motorStart(bool forward) {
    digitalWrite(PIN_DIR, forward ? HIGH : LOW);
    delayMicroseconds(100);
    gPhaseStart = millis();
    gLastStepUs = micros();
    gStepCount  = 0;
    oledShow(forward ? "EXTENDING" : "RETRACTING");
    Serial.printf("%s for %lus\n", forward ? "→ Extending" : "← Retracting", RUN_DURATION_MS / 1000);
}

static void motorStop() {
    oledShow("STOPPED");
    Serial.println("■ Stopped");
}

static void tickMotor() {
    // Linear ramp: interpolate period from STEP_START_US down to STEP_MIN_US
    uint32_t period = (gStepCount < ACCEL_STEPS)
        ? STEP_START_US - (uint32_t)((STEP_START_US - STEP_MIN_US) * gStepCount / ACCEL_STEPS)
        : STEP_MIN_US;

    uint32_t now = micros();
    if (now - gLastStepUs >= period) {
        digitalWrite(PIN_STEP, HIGH);
        delayMicroseconds(STEP_PULSE_US);
        digitalWrite(PIN_STEP, LOW);
        gLastStepUs = micros();
        gStepCount++;
    }
}

// ── Arduino entry points ─────────────────────────────────────────────────────
void setup() {
    Serial.begin(115200);
    delay(500);
    Serial.println("EPD 3D G6 — button test");
    Serial.printf("  Press -> extend %lus -> retract %lus\n", RUN_DURATION_MS / 1000, RUN_DURATION_MS / 1000);

    Wire.begin(PIN_SDA, PIN_SCL);
    gDisplay.begin(SSD1306_SWITCHCAPVCC, 0x3C);
    oledShow("STOPPED");

    pinMode(PIN_STEP, OUTPUT);
    pinMode(PIN_DIR,  OUTPUT);
    pinMode(PIN_BTN,  INPUT_PULLUP);

    digitalWrite(PIN_STEP, LOW);
    digitalWrite(PIN_DIR,  LOW);
}

void loop() {
    const bool pressed = (digitalRead(PIN_BTN) == LOW);

    switch (gState) {

        // ── Waiting for button press ──────────────────────────────────────────
        case RunState::IDLE:
            if (pressed && !gLastBtn) {  // rising edge only
                motorStart(true);
                gState = RunState::EXTENDING;
            }
            break;

        // ── Extending 5 s ────────────────────────────────────────────────────
        case RunState::EXTENDING:
            tickMotor();
            if (millis() - gPhaseStart >= RUN_DURATION_MS) {
                motorStart(false);
                gState = RunState::RETRACTING;
            }
            break;

        // ── Retracting 5 s ───────────────────────────────────────────
        case RunState::RETRACTING:
            tickMotor();
            if (millis() - gPhaseStart >= RUN_DURATION_MS) {
                motorStop();
                gState = RunState::IDLE;
            }
            break;
    }

    gLastBtn = pressed;
}
