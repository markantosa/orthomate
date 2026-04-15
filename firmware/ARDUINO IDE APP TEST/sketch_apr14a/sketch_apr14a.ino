#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <ArduinoJson.h>

#define DEVICE_NAME  "EPD3DG6"
#define SVC_UUID     "4fa0c560-78a3-11ee-b962-0242ac120002"
#define CHAR_UUID    "4fa0c561-78a3-11ee-b962-0242ac120002"

// FSR ADC pins (GPIO0-2 = FSR1-3)
static const uint8_t PIN_FSR[3] = {0, 1, 2};

// Voltage divider floor: with 10k pull-down and FSR ~1MΩ at no load,
// ADC reads ~37 counts minimum (not 0). Clamp map range accordingly.
static const int ADC_FLOOR = 37;   // ~0.03 V at no load
static const int ADC_CEIL  = 4095; // ~3.3 V at full load

// RC filter time constant: τ = R1*C = 1kΩ * 100nF = 100µs
// Allow 5τ = 500µs settling after each channel switch.
// Using 2ms gives comfortable margin and matches main firmware practice.
static const int ADC_SETTLE_MS = 2;
static const int ADC_SAMPLES   = 5; // odd number for simple median

BLECharacteristic *pCharacteristic;
bool deviceConnected = false;

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer*)    override { deviceConnected = true; }
  void onDisconnect(BLEServer* s) override {
    deviceConnected = false;
    s->startAdvertising(); // re-advertise after disconnect
  }
};

/** Median of ADC_SAMPLES reads with RC settling delay between each. */
int medianRead(uint8_t pin) {
  int buf[ADC_SAMPLES];
  for (int i = 0; i < ADC_SAMPLES; i++) {
    buf[i] = analogRead(pin);
    delay(ADC_SETTLE_MS);
  }
  // Insertion sort
  for (int i = 1; i < ADC_SAMPLES; i++) {
    int k = buf[i], j = i - 1;
    while (j >= 0 && buf[j] > k) { buf[j+1] = buf[j--]; }
    buf[j+1] = k;
  }
  return buf[ADC_SAMPLES / 2];
}

void setup() {
  Serial.begin(115200);
  analogReadResolution(12); // 12-bit ADC (0-4095)

  BLEDevice::init(DEVICE_NAME);
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  BLEService *service = server->createService(SVC_UUID);
  pCharacteristic = service->createCharacteristic(
    CHAR_UUID,
    BLECharacteristic::PROPERTY_READ |
    BLECharacteristic::PROPERTY_NOTIFY
  );
  pCharacteristic->addDescriptor(new BLE2902());

  service->start();
  BLEDevice::getAdvertising()->addServiceUUID(SVC_UUID);
  BLEDevice::getAdvertising()->start();
  Serial.println("BLE advertising as " DEVICE_NAME);
}

void loop() {
  // Read FSRs with RC settling delay between channel switches
  int raw[3];
  for (int i = 0; i < 3; i++) {
    raw[i] = medianRead(PIN_FSR[i]);
  }

  // Map to 0-100 using real divider floor (37 counts at no load)
  int fsr[3];
  for (int i = 0; i < 3; i++) {
    fsr[i] = constrain(map(raw[i], ADC_FLOOR, ADC_CEIL, 0, 100), 0, 100);
  }

  // Serial debug
  Serial.printf("raw: %4d %4d %4d  →  fsr: %3d %3d %3d\n",
                raw[0], raw[1], raw[2], fsr[0], fsr[1], fsr[2]);

  // Build JSON matching what the app expects
  StaticJsonDocument<128> doc;
  doc["fsr1"] = fsr[0];
  doc["fsr2"] = fsr[1];
  doc["fsr3"] = fsr[2];
  doc["mode"] = "CONNECTED";  // static label for bench test
  doc["batt"] = 100;          // no battery on bench — show full

  char buf[128];
  serializeJson(doc, buf);

  if (deviceConnected) {
    pCharacteristic->setValue(buf);
    pCharacteristic->notify();
  }

  delay(250); // 4 Hz update — matches main firmware BLE notify rate
}