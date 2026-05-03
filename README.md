# EPD 3D G6 — Orthomate

Battery-powered adaptive orthopedic insole controller. Redistributes plantar pressure in real-time using three micro linear stepper actuators driven by force sensor feedback. Includes a BLE companion Android app for live monitoring.

> **🏆 Winner — Top Course Project, EPD 3D Exhibition April 2026**

## Hardware at a glance

| Component | Detail |
|-----------|--------|
| MCU | ESP32-C6 SuperMini |
| Stepper drivers | 3× TMC2209 (STEP/DIR mode, ENN hardwired LOW on PCB) |
| Actuators | 3× micro linear bipolar stepper |
| Pressure sensors | 4× FSR (R1 1kΩ + C 100nF RC filter per channel, R2 10kΩ pull-down) |
| Display | 0.91" OLED (SSD1306, I2C) |
| Charger | TP4056 Type-C |
| Boost converter | XL6009 → 5 V system rail |
| Battery | 1S LiPo 2000–3000 mAh |
| User input | 1× momentary mode button |
| BLE | NimBLE-Arduino 2.5.0 — advertises as `EPD3DG6` |

## PCB placement

Orthomate uses two custom PCBs that work together inside a modified shoe:

**Controller PCB** — mounts in a controller module. Houses the ESP32-C6, three TMC2209 stepper drivers, TP4056 charger, XL6009 boost converter, and the 20-pin connector (J9) to the insole. The LiPo battery sits alongside it.

**Insole PCB** — sits directly underfoot inside the shoe. Hosts the three FSR pressure-sensing zones (metatarsal, arch, calcaneus) and the three micro linear stepper actuator assemblies. The actuators extend upward to redistribute localised plantar pressure. Connects back to the controller PCB via a 20-pin ribbon/wire harness.

<img src="Exhibition%20Files%20and%20Photos/Controller%20PCB%20KiCad%20Screenshot.png" width="48%" alt="Controller PCB"> <img src="Exhibition%20Files%20and%20Photos/Insole%203D%20model.jpg" width="48%" alt="Insole 3D Model">

## Exhibition highlights

### Final product

![Final Product Shot](Exhibition%20Files%20and%20Photos/Final%20Product%20Shot.jpg)

### ANSYS stress analysis

<img src="Exhibition%20Files%20and%20Photos/Stress%20Analysis%20Colour%20Plot%20Ansys%20GIF.gif" width="60%" alt="Stress Analysis">

### Exhibition day

<img src="Exhibition%20Files%20and%20Photos/Exhibition%20Photo%202.jpg" width="48%" alt="Exhibition Booth"> <img src="Exhibition%20Files%20and%20Photos/Exhibition%20Photo%201.jpg" width="48%" alt="Team at Exhibition">

<img src="Exhibition%20Files%20and%20Photos/Group%20Shot.jpg" width="60%" alt="Group Shot">

### Poster

<img src="Exhibition%20Files%20and%20Photos/Orthomate%20Poster.png" width="70%" alt="Orthomate Poster">

### Downloadable resources

- [Orthomate Final Slides (PDF)](Exhibition%20Files%20and%20Photos/Orthomate%20Final%20Slides.pdf)
- [Orthomate Poster (PDF)](Exhibition%20Files%20and%20Photos/Orthomate%20Poster.pdf)

## Repository structure

```
orthomate/
├── Electronics Reference.txt           # Hardware design spec (v6.0)
├── BOM.txt                             # Bill of materials
├── firmware/
│   ├── main/                           # Full operational firmware (PlatformIO)
│   │   ├── platformio.ini
│   │   └── src/main.cpp
│   ├── ARDUINO IDE MAIN/               # Arduino IDE version of the full firmware
│   │   └── EPD3DG6_Main/EPD3DG6_Main.ino
│   ├── ARDUINO IDE APP TEST/           # Minimal Arduino IDE sketch for BLE app testing
│   │   └── sketch_apr14a/sketch_apr14a.ino
│   ├── stepper_test/                   # Single-axis stepper back-and-forth smoke test
│   ├── homing_test/                    # Blind homing: retract to min, extend to 12 mm
│   ├── button_test/                    # Button + OLED input test
│   └── oled_test/                      # OLED display test
├── android_app/                        # Kotlin/Compose BLE companion app
│   └── app/src/main/AndroidManifest.xml
├── Controller PCB KiCad Files/         # KiCad schematic + layout for controller PCB
├── Controller PCB Iterations/          # Gerber files from each revision (V2–V5)
├── Insole PCB KiCad Files/             # KiCad schematic + layout for insole PCB
├── Insole PCB Iterations/              # Gerber files from each revision
├── Exhibition Files and Photos/        # Exhibition media, poster, and slides
└── docs/
    ├── FIRMWARE_MAIN.md                # Detailed firmware walkthrough
    ├── FLASH_GUIDE.md                  # PlatformIO build & upload instructions
    ├── ANDROID_APP_GUIDE.md            # Android app build & sideload instructions
    └── TMC2209_CURRENT_TUNING.md
```

## Firmware — operational logic

The main firmware (`firmware/main/`) implements a full fitting cycle driven by one button:

```
DISCONNECTED ──[plug in]──► CONNECTED ──[btn]──► MEASURING
                                                      │ (auto)
                                                  MEASURED ──[btn]──► ACTUATING
                                                                           │ (auto)
                                                                         DONE ──[unplug]──► DISCONNECTED
                                                                         (insole retains shape)

On next plug-in with actuators extended:  DISCONNECTED ──► HOMING ──► CONNECTED
```

| Mode | Behaviour |
|------|-----------|
| **CONNECTED** | Idle; shows battery %, BLE status on OLED |
| **MEASURING** | Reads all 4 FSR channels, 7-sample median filter, computes relative load fractions |
| **ACTUATING** | Drives each actuator inversely proportional to region load — lowest load = largest extension |
| **DONE** | Holds position; unplug to use insole as-is |
| **HOMING** | Auto-triggered on reconnect when actuators are extended; retracts all to home |

Extension formula: $ext_i \propto (1 - r_i)$ where $r_i = F_i / \sum F$

## BLE — Android companion app

The ESP32 advertises a GATT service and notifies a JSON payload at ~4 Hz:

```json
{"fsr1": 42, "fsr2": 18, "fsr3": 75, "mode": "MEASURING", "batt": 67}
```

| BLE field | Value |
|-----------|-------|
| Device name | `EPD3DG6` |
| Service UUID | `4fa0c560-78a3-11ee-b962-0242ac120002` |
| Characteristic UUID | `4fa0c561-78a3-11ee-b962-0242ac120002` (NOTIFY + READ) |
| Notify rate | ~4 Hz (every 250 ms) |
| MTU | 185 bytes |

The Android app (`android_app/`) displays real-time FSR forces as animated colour circles, current mode, battery %, and BLE status. See [docs/ANDROID_APP_GUIDE.md](docs/ANDROID_APP_GUIDE.md) for build and install instructions.

## GPIO assignment (ESP32-C6 SuperMini, Hardware Ref v6.0)

| GPIO | Function |
|------|----------|
| 0 | FSR1 METATARSAL (ADC, 11 dB attenuation) |
| 1 | FSR2 ARCH (ADC, 11 dB attenuation) |
| 2 | FSR3 CALCANEUS (ADC, 11 dB attenuation) |
| 3 | FSR4 AUX (ADC, 11 dB attenuation) |
| 4 | MODE\_BUTTON (INPUT\_PULLUP) — boot-safe |
| 5 | VBAT\_SENSE (ADC1 ch5, 11 dB attenuation, 1:2 divider) |
| 6 | OLED SCL (I2C) |
| 7 | OLED SDA (I2C) |
| 14 | ACT3\_DIR ⚠ dead channel (stepper motor pad detached) |
| 15 | CONN\_DETECT (INPUT\_PULLDOWN) |
| 16 | ACT2\_STEP (arch actuator) |
| 17 | ACT2\_DIR (arch actuator) |
| 18 | ACT3\_STEP ⚠ dead channel (stepper motor pad detached) |
| 19 | ACT1\_DIR |
| 20 | ACT1\_STEP |

## Quick start

- **Flash firmware:** [docs/FLASH_GUIDE.md](docs/FLASH_GUIDE.md)
- **Install Android app:** [docs/ANDROID_APP_GUIDE.md](docs/ANDROID_APP_GUIDE.md)
- **Firmware internals:** [docs/FIRMWARE_MAIN.md](docs/FIRMWARE_MAIN.md)

## Design rules

- Charging and actuator operation are **mutually exclusive** — never simultaneous.
- Never start all 3 motors simultaneously — stagger motor starts by 75 ms.
- Sample FSRs only when motors are idle; use 7-sample median filtering with 4 ms settling per sample.
- ADC attenuation must be set to **11 dB** (`ADC_11db`) on all analog pins for correct readings at the operating voltage range.
- VBAT < 3.35 V → disable actuators; VBAT < 3.5 V → show low battery warning.
- OLED updates are content-cached — only redraws when text changes (prevents I2C writes during motor current spikes).

## License

MIT — see [LICENSE](LICENSE)
