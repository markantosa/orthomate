/**
 * VBAT SENSE diagnostic sketch — ESP32-C6 SuperMini
 * Hardware: GPIO5 → 1:2 resistor divider (VBAT_SENSE)
 *
 * Tries four reading methods every 2 s so you can compare them in Serial Monitor.
 * Board: ESP32C6 Dev Module (Arduino IDE → Board Manager → esp32 by Espressif)
 * Baud:  115200
 */

#define PIN_VBAT 5

void setup() {
    Serial.begin(115200);
    delay(2000); // give USB CDC time to enumerate before first print
    Serial.println("=== VBAT SENSE TEST ===");
    Serial.printf("Testing GPIO%d (1:2 divider — multiply reading x2 for full VBAT)\n\n", PIN_VBAT);
}

void loop() {
    Serial.println("--- sample ---");

    // ── Method A: no attenuation config (default) ────────────────────────────
    analogReadResolution(12);
    int rawA = analogRead(PIN_VBAT);
    float vA = (rawA / 4095.0f) * 3.3f * 2.0f;
    Serial.printf("A  default attn     raw=%4d  pin=%.3fV  vbat=%.3fV\n",
                  rawA, (rawA / 4095.0f) * 3.3f, vA);

    // ── Method B: global ADC_11db ────────────────────────────────────────────
    analogSetAttenuation(ADC_11db);
    delay(5);
    int rawB = analogRead(PIN_VBAT);
    float vB = (rawB / 4095.0f) * 3.3f * 2.0f;
    Serial.printf("B  ADC_11db global  raw=%4d  pin=%.3fV  vbat=%.3fV\n",
                  rawB, (rawB / 4095.0f) * 3.3f, vB);

    // ── Method C: analogReadMilliVolts after global ADC_11db ─────────────────
    uint32_t mvC = analogReadMilliVolts(PIN_VBAT);
    float vC = (mvC / 1000.0f) * 2.0f;
    Serial.printf("C  millivolts        pin=%4dmV             vbat=%.3fV\n",
                  mvC, vC);

    // ── Method D: per-pin ADC_11db ───────────────────────────────────────────
    analogSetAttenuation(ADC_0db);   // reset global first
    delay(5);
    analogSetPinAttenuation(PIN_VBAT, ADC_11db);
    delay(5);
    int rawD = analogRead(PIN_VBAT);
    uint32_t mvD = analogReadMilliVolts(PIN_VBAT);
    float vD = (mvD / 1000.0f) * 2.0f;
    Serial.printf("D  ADC_11db per-pin raw=%4d  pin=%4dmV  vbat=%.3fV\n\n",
                  rawD, mvD, vD);

    delay(2000);
}
