# EPD 3D G6 — Pumped Up Kicks V2

Battery-powered adaptive orthopedic insole controller. Redistributes plantar pressure in real-time using three micro linear stepper actuators driven by force sensor feedback.

## Hardware at a glance

| Component | Detail |
|-----------|--------|
| MCU | ESP32-C6 SuperMini |
| Stepper drivers | 3× TMC2209 (STEP/DIR mode) |
| Actuators | 3× micro linear bipolar stepper |
| Pressure sensors | 4× FSR |
| Display | 0.91" OLED (SSD1306, I2C) |
| Charger | TP4056 Type-C |
| Boost converter | XL6009 → 5 V system rail |
| Battery | 1S LiPo 2000–3000 mAh |
| User input | 1× momentary mode button |
| Power control | Hardware latching power switch |

## Repository structure

```
pumped-up-kicks/
├── Electronics Reference.txt   # Hardware design spec (v4.1)
├── BOM.txt                     # Bill of materials
├── firmware/
│   ├── main/                   # Full operational firmware (PlatformIO)
│   │   ├── platformio.ini
│   │   └── src/main.cpp
│   └── stepper_test/           # Single-axis stepper smoke test
│       ├── platformio.ini
│       └── src/main.cpp
├── kicad pcb/                  # KiCad schematic + layout files
└── docs/
    └── FLASH_GUIDE.md          # PlatformIO build & upload instructions
```

## Firmware — operational logic

The main firmware (`firmware/main/`) implements a three-state cycle:

```
CONNECTED  ──[btn]──►  MEASURING  ──(auto)──►  MEASURED
                                                   │
                                                [btn]
                                                   ▼
                                              ACTUATING  ──(auto)──►  DONE
                                                                        │
                                                                     [btn]
                                                                        ▼
                                                                    RETRACT → CONNECTED
```

| Mode | Behaviour |
|------|-----------|
| **Measurement** | Reads all 4 FSR channels, median-filters, computes relative load fractions |
| **Actuation** | Drives each actuator inversely proportional to its region's load — lowest load = largest extension |
| **Connector detect** | GPIO9 detects J_MAIN; unplugging at any point disables drivers immediately |

Extension formula: $ext_i \propto (1 - r_i)$ where $r_i = F_i / \sum F$

## GPIO assignment (ESP32-C6 SuperMini, v4.1)

| GPIO | Function |
|------|----------|
| 0 | FSR1 METATARSAL (ADC) |
| 1 | FSR2 ARCH (ADC) |
| 2 | FSR3 CALCANEUS (ADC) |
| 3 | FSR4 AUX (ADC) |
| 4 | STEPPER\_ENN\_SHARED (active LOW) |
| 5 | VBAT\_SENSE (ADC) |
| 6 | ACT1\_STEP |
| 7 | ACT1\_DIR |
| 9 | CONN\_DETECT (INPUT\_PULLDOWN) |
| 12 | ACT2\_STEP |
| 13 | ACT2\_DIR |
| 14 | MODE\_BUTTON (INPUT\_PULLUP) |
| 20 | ACT3\_STEP |
| 21 | ACT3\_DIR |
| 22 | OLED SDA (I2C) |
| 23 | OLED SCL (I2C) |

## Quick start — flashing firmware

See [docs/FLASH_GUIDE.md](docs/FLASH_GUIDE.md).

## Design rules

- Charging and actuator operation are **mutually exclusive** — firmware locks out motors on USB detect.
- Never start all 3 motors simultaneously — stagger motor starts by 50–100 ms.
- Sample FSRs only when motors are idle; use median filtering (≥ 5 samples).
- VBAT < 3.35 V → disable actuators; < 3.2 V → deep protection mode.
- OLED updates capped at ~10 Hz — avoid redraws during motor current spikes.

## License

MIT — see [LICENSE](LICENSE)
