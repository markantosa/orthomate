/**
 * EPD 3D G6 — Actuator 1 Cycle Test
 * Continuously drives ACT1 forward 22000 steps then back 22000 steps.
 * OLED shows FORWARD / BACKWARD during motion.
 *
 * Pin map (Hardware Reference v6.0):
 *   PIN_ENN      = 7   — STEPPER_ENN shared, active LOW to enable
 *   ACT1 STEP    = 20
 *   ACT1 DIR     = 19  — HIGH = extend, LOW = retract
 *   PIN_SDA      = 7   — OLED SDA
 *   PIN_SCL      = 6   — OLED SCL
 */

#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

// ── Pin map ──────────────────────────────────────────────────────────────────

static constexpr uint8_t PIN_ENN      = 7;
static constexpr uint8_t ACT1_STEP    = 20;
static constexpr uint8_t ACT1_DIR     = 19;
static constexpr uint8_t PIN_SDA      = 7;
static constexpr uint8_t PIN_SCL      = 6;

// ── OLED ─────────────────────────────────────────────────────────────────────

static Adafruit_SSD1306 oled(128, 32, &Wire, -1);
static bool gOledOk = false;

static void showOLED(const char* line1, const char* line2 = "") {
    if (!gOledOk) return;
    oled.clearDisplay();
    oled.setTextColor(SSD1306_WHITE);
    oled.setTextSize(2);
    oled.setCursor(0, 0);  oled.print(line1);
    oled.setTextSize(1);
    oled.setCursor(0, 22); oled.print(line2);
    oled.display();
}

// ── Motion parameters ────────────────────────────────────────────────────────

static constexpr uint32_t CYCLE_STEPS    = 22000;  // steps per direction
static constexpr uint32_t STEP_DELAY_US  = 1500;   // µs between step edges
static constexpr uint32_t STEP_PULSE_US  = 5;      // µs STEP pin HIGH width
static constexpr uint32_t PAUSE_MS       = 300;    // pause at each end of travel

// ─────────────────────────────────────────────────────────────────────────────

static void driveSteps(bool extend, uint32_t steps) {
    digitalWrite(ACT1_DIR, extend ? HIGH : LOW);
    delayMicroseconds(2); // DIR setup time before first pulse
    for (uint32_t i = 0; i < steps; i++) {
        digitalWrite(ACT1_STEP, HIGH); delayMicroseconds(STEP_PULSE_US);
        digitalWrite(ACT1_STEP, LOW);  delayMicroseconds(STEP_DELAY_US);
    }
}

void setup() {
    Serial.begin(115200);

    Wire.begin(PIN_SDA, PIN_SCL);
    gOledOk = oled.begin(SSD1306_SWITCHCAPVCC, 0x3C);

    pinMode(ACT1_STEP, OUTPUT);
    pinMode(ACT1_DIR,  OUTPUT);
    pinMode(PIN_ENN,   OUTPUT);

    digitalWrite(ACT1_STEP, LOW);
    digitalWrite(ACT1_DIR,  LOW);
    digitalWrite(PIN_ENN,   LOW); // enable driver

    delay(50); // let driver settle

    showOLED("ACT1", "Cycle test ready");
    Serial.println("[ACT1 CYCLE] Running — 22000 steps fwd/back continuously");
}

void loop() {
    Serial.println("[ACT1 CYCLE] Extending 22000 steps...");
    showOLED("FORWARD", "22000 steps");
    driveSteps(true, CYCLE_STEPS);
    delay(PAUSE_MS);

    Serial.println("[ACT1 CYCLE] Retracting 22000 steps...");
    showOLED("BACKWARD", "22000 steps");
    driveSteps(false, CYCLE_STEPS);
    delay(PAUSE_MS);
}
