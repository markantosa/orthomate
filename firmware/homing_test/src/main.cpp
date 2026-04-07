/**
 * homing_test — EPD 3D G6
 *
 * Blind homing sequence for ACT1 (lead screw linear actuator, 12mm stroke):
 *   1. Retract slowly into the mechanical end stop (overdrive past full travel).
 *   2. Pause, then extend to max (12mm).
 *   Runs once on boot; driver is disabled afterwards.
 *
 * Wiring (matches Electronics Reference v5.0):
 *   GPIO 20 → ACT1_STEP
 *   GPIO 19 → ACT1_DIR
 *   GPIO 7  → STEPPER_ENN_SHARED  (active LOW — pulled LOW to enable)
 *
 * DIR LOW  = retract (min extension)
 * DIR HIGH = extend  (max extension)
 */

#include <Arduino.h>

// ── Pin definitions ──────────────────────────────────────────────────────────
static constexpr uint8_t PIN_STEP = 20;
static constexpr uint8_t PIN_DIR  = 19;
static constexpr uint8_t PIN_ENN  = 7;

// ── Motion parameters ────────────────────────────────────────────────────────
static constexpr uint32_t STEPS_PER_MM         = 1536;  // calibrated: 7680 steps = 5mm
static constexpr uint32_t STEPS_FULL_TRAVEL    = 18432; // 12mm full stroke (1536 * 12)
static constexpr uint32_t STEPS_HOME_OVERSHOOT = 22000; // overdrive past full travel to guarantee end stop

static constexpr uint32_t STEP_PULSE_US       = 5;    // step HIGH pulse width (µs)
static constexpr uint32_t STEP_NORMAL_DELAY   = 300;  // normal speed ~3200 steps/s
static constexpr uint32_t STEP_HOME_DELAY     = 600;  // slow homing ~1600 steps/s (gentler on end stop)

static constexpr uint32_t PAUSE_AFTER_HOME_MS = 800;

// ── Helper ───────────────────────────────────────────────────────────────────
static void moveSteps(uint32_t count, uint32_t delayUs) {
    for (uint32_t i = 0; i < count; i++) {
        digitalWrite(PIN_STEP, HIGH);
        delayMicroseconds(STEP_PULSE_US);
        digitalWrite(PIN_STEP, LOW);
        delayMicroseconds(delayUs);
    }
}

// ── Arduino entry points ─────────────────────────────────────────────────────
void setup() {
    Serial.begin(115200);
    delay(500);

    Serial.println("EPD 3D G6 — homing test");
    Serial.printf("  STEP : GPIO%d  DIR : GPIO%d  ENN : GPIO%d\n",
                  PIN_STEP, PIN_DIR, PIN_ENN);

    pinMode(PIN_STEP, OUTPUT);
    pinMode(PIN_DIR,  OUTPUT);
    pinMode(PIN_ENN,  OUTPUT);

    digitalWrite(PIN_STEP, LOW);
    digitalWrite(PIN_DIR,  LOW);
    digitalWrite(PIN_ENN,  LOW); // enable driver
    delay(10);

    // ── 1. Home → retract to min extension ───────────────────────────────────
    Serial.println("Homing: retracting to min extension...");
    digitalWrite(PIN_DIR, LOW);
    delayMicroseconds(2);
    moveSteps(STEPS_HOME_OVERSHOOT, STEP_HOME_DELAY);

    delay(PAUSE_AFTER_HOME_MS);
    Serial.println("Homed. Position = 0 (min extension)");

    // ── 2. Extend to max ──────────────────────────────────────────────────────
    Serial.printf("Extending to max (%lu steps = 12mm, %u steps/mm)...\n", STEPS_FULL_TRAVEL, STEPS_PER_MM);
    digitalWrite(PIN_DIR, HIGH);
    delayMicroseconds(2);
    moveSteps(STEPS_FULL_TRAVEL, STEP_NORMAL_DELAY);

    Serial.println("At max extension. Done.");

    digitalWrite(PIN_ENN, HIGH); // disable driver
}

void loop() {
    // Sequence runs once in setup()
}
