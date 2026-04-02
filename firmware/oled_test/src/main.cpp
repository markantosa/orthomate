/**
 * OLED screen test — EPD 3D G6
 *
 * Display: 128×64 SSD1306 (I2C)
 *   SCL → GPIO15
 *   SDA → GPIO9
 *   VCC → 3.3V
 *   GND → GND
 *
 * Shows "MEASURING" centred on screen, then cycles through
 * the other operational status strings so you can verify
 * font size and alignment before integrating into main firmware.
 */

#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

static constexpr uint8_t PIN_SCL   = 15;
static constexpr uint8_t PIN_SDA   = 9;
static constexpr uint8_t OLED_ADDR = 0x3C;
static constexpr int     OLED_W    = 128;
static constexpr int     OLED_H    = 64;

static Adafruit_SSD1306 oled(OLED_W, OLED_H, &Wire, -1);

/** Draw a single centred string in large text, small subtitle below. */
static void showStatus(const char* title, const char* sub = "") {
    oled.clearDisplay();

    // ── Large centred title (textSize 2 = 12px tall, 6px per char wide * 2 = 12) ─
    oled.setTextSize(2);
    oled.setTextColor(SSD1306_WHITE);
    int16_t x1, y1; uint16_t tw, th;
    oled.getTextBounds(title, 0, 0, &x1, &y1, &tw, &th);
    int16_t cx = (OLED_W - (int16_t)tw) / 2;
    oled.setCursor(cx < 0 ? 0 : cx, 18);
    oled.print(title);

    // ── Small subtitle ───────────────────────────────────────────────────────────
    if (sub[0] != '\0') {
        oled.setTextSize(1);
        oled.getTextBounds(sub, 0, 0, &x1, &y1, &tw, &th);
        cx = (OLED_W - (int16_t)tw) / 2;
        oled.setCursor(cx < 0 ? 0 : cx, 50);
        oled.print(sub);
    }

    oled.display();
}

void setup() {
    Serial.begin(115200);
    delay(300);

    Wire.begin(PIN_SDA, PIN_SCL);

    if (!oled.begin(SSD1306_SWITCHCAPVCC, OLED_ADDR)) {
        Serial.println("ERROR: OLED not found — check wiring and I2C address");
        while (true) { delay(1000); }
    }

    Serial.println("OLED OK — starting display test");
    oled.clearDisplay();
    oled.display();
    delay(200);
}

void loop() {
    // Cycle through all status strings so the full screen can be verified
    const char* msgs[][2] = {
        { "MEASURING",  "hold still..."  },
        { "MEASURED",   "btn: actuate"   },
        { "ACTUATING",  ""               },
        { "DONE",       "btn: retract"   },
        { "CONNECTED",  "btn: measure"   },
        { "DISCONNECTD","attach cable"   },  // truncated to fit 2× font
        { "LOW BATT!",  "charge device"  },
    };

    for (auto& m : msgs) {
        Serial.printf("Showing: %s\n", m[0]);
        showStatus(m[0], m[1]);
        delay(2000);
    }
}
