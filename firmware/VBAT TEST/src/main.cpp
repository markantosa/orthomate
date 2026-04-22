/**
 * VBAT SENSE diagnostic v3 — PlatformIO / ESP32-C6 SuperMini
 * Hardware: GPIO5 (ADC1_CH5) → 1:2 resistor divider (VBAT_SENSE)
 *
 * Uses IDF5 adc_oneshot API directly to properly configure LP ADC
 * attenuation — Arduino analogSetAttenuation() silently fails for
 * ESP32-C6 LP GPIO pins (0-6) in this framework version.
 *
 * Monitor: pio device monitor --baud 115200
 */

#include <Arduino.h>
#include "esp_adc/adc_oneshot.h"
#include "esp_adc/adc_cali.h"
#include "esp_adc/adc_cali_scheme.h"

#define ADC_CHANNEL   ADC_CHANNEL_5   // GPIO5
#define DIVIDER_RATIO 2.0f            // 100k / 100k divider

static adc_oneshot_unit_handle_t s_adc1;
static adc_cali_handle_t         s_cali;
static bool                      s_cali_ok = false;

void setup() {
    Serial.begin(115200);
    unsigned long t0 = millis();
    while (!Serial && (millis() - t0 < 5000)) { delay(10); }
    delay(200);

    // ── Init ADC1 unit ────────────────────────────────────————
    adc_oneshot_unit_init_cfg_t unit_cfg = {};
    unit_cfg.unit_id  = ADC_UNIT_1;
    unit_cfg.ulp_mode = ADC_ULP_MODE_DISABLE;
    ESP_ERROR_CHECK(adc_oneshot_new_unit(&unit_cfg, &s_adc1));

    // ── Configure GPIO5 (ADC1_CH5) with 12dB attenuation ─────
    adc_oneshot_chan_cfg_t chan_cfg = {};
    chan_cfg.atten    = ADC_ATTEN_DB_12;   // 0–3100 mV range
    chan_cfg.bitwidth = ADC_BITWIDTH_12;
    ESP_ERROR_CHECK(adc_oneshot_config_channel(s_adc1, ADC_CHANNEL, &chan_cfg));

    // ── Calibration (curve fitting, ESP32-C6 supports it) ─────
    adc_cali_curve_fitting_config_t cali_cfg = {};
    cali_cfg.unit_id  = ADC_UNIT_1;
    cali_cfg.chan     = ADC_CHANNEL;
    cali_cfg.atten    = ADC_ATTEN_DB_12;
    cali_cfg.bitwidth = ADC_BITWIDTH_12;
    esp_err_t ret = adc_cali_create_scheme_curve_fitting(&cali_cfg, &s_cali);
    s_cali_ok = (ret == ESP_OK);

    Serial.println("=== VBAT SENSE TEST (IDF5 adc_oneshot, ADC_ATTEN_DB_12) ===");
    Serial.printf("GPIO5, 1:2 divider.  Calibration: %s\n\n",
                  s_cali_ok ? "OK" : "FAILED");
}

void loop() {
    int raw = 0;
    adc_oneshot_read(s_adc1, ADC_CHANNEL, &raw);

    int mv_raw = (int)((raw / 4095.0f) * 3100.0f);  // linear approx for display
    int mv_cal = mv_raw;
    if (s_cali_ok) {
        adc_cali_raw_to_voltage(s_cali, raw, &mv_cal);
    }

    float vbat = (mv_cal / 1000.0f) * DIVIDER_RATIO;
    Serial.printf("raw=%4d  linear~%4dmV  cali=%4dmV  vbat=%.3fV\n",
                  raw, mv_raw, mv_cal, vbat);
    delay(2000);
}
