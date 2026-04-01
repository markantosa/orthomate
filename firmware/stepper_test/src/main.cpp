/**
 * stepper_test — EPD 3D G6
 *
 * Moves ACT1 (TMC2209 in STEP/DIR mode) back and forth.
 *
 * Wiring (matches Electronics Reference v3.1):
 *   GPIO 6  → ACT1_STEP
 *   GPIO 7  → ACT1_DIR
 *   GPIO 4  → STEPPER_ENN_SHARED  (active LOW — pulled LOW to enable)
 *
 * The TMC2209 is in stand-alone STEP/DIR mode; no UART is used here.
 * Current / microstep settings are configured by the on-board trim pot
 * and MS1/MS2 pins on the driver module.
 */

#include <Arduino.h>

// ── Pin definitions ──────────────────────────────────────────────────────────
static constexpr uint8_t PIN_STEP = 6;   // ACT1_STEP
static constexpr uint8_t PIN_DIR  = 7;   // ACT1_DIR
static constexpr uint8_t PIN_ENN  = 4;   // STEPPER_ENN_SHARED (active LOW)

// ── Motion parameters ────────────────────────────────────────────────────────
// Adjust STEPS_PER_MOVE and STEP_DELAY_US to match your actuator travel.
static constexpr uint32_t STEPS_PER_MOVE  = 200;   // steps per direction pass
static constexpr uint32_t STEP_DELAY_US   = 1500;  // µs between step pulses → ~333 steps/s
static constexpr uint32_t STEP_PULSE_US   = 5;     // µs HIGH pulse width (TMC2209 min ≥ 100 ns)
static constexpr uint32_t PAUSE_MS        = 500;   // pause between direction changes

// ── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Send `count` step pulses in the current direction.
 */
static void moveSteps(uint32_t count) {
    for (uint32_t i = 0; i < count; i++) {
        digitalWrite(PIN_STEP, HIGH);
        delayMicroseconds(STEP_PULSE_US);
        digitalWrite(PIN_STEP, LOW);
        delayMicroseconds(STEP_DELAY_US);
    }
}

// ── Arduino entry points ─────────────────────────────────────────────────────

void setup() {
    Serial.begin(115200);
    delay(500); // let USB CDC enumerate

    Serial.println("EPD 3D G6 — stepper back-and-forth test");
    Serial.printf("  STEP pin : GPIO%d\n", PIN_STEP);
    Serial.printf("  DIR  pin : GPIO%d\n", PIN_DIR);
    Serial.printf("  ENN  pin : GPIO%d (active LOW)\n", PIN_ENN);

    pinMode(PIN_STEP, OUTPUT);
    pinMode(PIN_DIR,  OUTPUT);
    pinMode(PIN_ENN,  OUTPUT);

    digitalWrite(PIN_STEP, LOW);
    digitalWrite(PIN_DIR,  LOW);

    // Enable drivers: ENN LOW = enabled
    digitalWrite(PIN_ENN, LOW);
    delay(10); // allow driver to settle after enable

    Serial.println("Driver enabled. Starting motion...");
}

void loop() {
    // ── Forward pass ─────────────────────────────────────────────────────────
    Serial.printf("→ Forward  (%lu steps)\n", STEPS_PER_MOVE);
    digitalWrite(PIN_DIR, HIGH);
    delayMicroseconds(2); // DIR setup time before first step
    moveSteps(STEPS_PER_MOVE);

    delay(PAUSE_MS);

    // ── Reverse pass ─────────────────────────────────────────────────────────
    Serial.printf("← Reverse  (%lu steps)\n", STEPS_PER_MOVE);
    digitalWrite(PIN_DIR, LOW);
    delayMicroseconds(2);
    moveSteps(STEPS_PER_MOVE);

    delay(PAUSE_MS);
}
