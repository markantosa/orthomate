# TMC2209 Current Limit Tuning Guide

The TMC2209 modules in this build run in **standalone STEP/DIR mode**.  
Current is set by adjusting the **VREF trim potentiometer** on each module — no UART required.

---

## How it works

The driver sets motor RMS current using this formula:

$$I_{RMS} = \frac{V_{REF}}{2.5 \times R_{SENSE}}$$

Most generic TMC2209 breakout modules use **R_SENSE = 0.11 Ω**, giving:

$$I_{RMS} = \frac{V_{REF}}{2.5 \times 0.11} = \frac{V_{REF}}{0.275}$$

| V_REF (V) | I_RMS (A) |
|-----------|-----------|
| 0.25      | 0.91      |
| 0.35      | 1.27      |
| 0.50      | 1.82      |
| 0.75      | 2.73      |
| 1.00      | 3.64      |

> Check your module's silkscreen or schematic for the actual R_SENSE value — some clones use 0.15 Ω or 0.22 Ω. The formula above still applies; just substitute the correct value.

---

## What you need

- Multimeter (DC voltage, mV range)
- Small flat-head screwdriver
- Motor connected and driver powered (5V_SYS on VM, 3.3V on VIO)
- ESP32 powered, `STEPPER_ENN` held LOW (drivers enabled) — flash a short sketch that just pulls GPIO4 LOW if needed

---

## Procedure

### 1. Locate VREF

On the TMC2209 module, VREF is the **wiper of the trim pot** — there is usually a small test pad labelled `VREF` next to the pot. If not, probe the centre pin of the potentiometer directly.

### 2. Reference GND

Place the negative probe on the **GND pad** of the module (not the motor coil pins).

### 3. Enable the driver

The driver must be powered and `ENN` must be LOW for VREF to be valid.  
Flash this minimal enable sketch or use the stepper_test firmware already on the board:

```cpp
// Minimal ENN-enable snippet — paste into setup() for a one-shot test
pinMode(4, OUTPUT);
digitalWrite(4, LOW);   // ENN LOW = driver enabled
```

### 4. Measure and adjust

- Power on the system (5V_SYS present, ESP32 running)
- Probe VREF with the multimeter
- Turn the pot **clockwise** to increase VREF (higher current)  
- Turn **counter-clockwise** to decrease VREF (lower current)
- Move slowly — the pot is sensitive; small turns = large change

### 5. Target value

For the **micro linear actuators** in this build, start conservatively:

| Recommendation | V_REF | I_RMS |
|----------------|-------|-------|
| Starting point | 0.30 V | ~1.1 A |
| Typical small actuator | 0.40–0.50 V | ~1.5–1.8 A |
| **Do not exceed** motor datasheet peak | — | check motor spec |

Set all three drivers to the same value unless the actuators differ.

---

## Verify with temperature check

Run the actuator for 30–60 seconds of continuous motion.  
After stopping, carefully touch the **driver IC** (the chip, not the pot):

- **Warm (< 50 °C)** — good
- **Hot but holdable** — acceptable, consider reducing by 0.05 V
- **Too hot to touch (> 70 °C)** — reduce VREF immediately; risk of thermal shutdown

TMC2209 has an internal thermal shutdown at ~150 °C but the modules have no heatsink — keep them well below that.

---

## Microstep setting (MS1 / MS2)

On standalone modules, microstep resolution is set by the **MS1 and MS2 pins**:

| MS1 | MS2 | Microsteps |
|-----|-----|------------|
| LOW | LOW | 8 (default if floating) |
| HIGH | LOW | 2 |
| LOW | HIGH | 4 |
| HIGH | HIGH | 16 |

Most modules leave MS1/MS2 floating, which defaults to **8 microsteps**.  
If you change these, update `MAX_EXT_STEPS` in `firmware/main/src/main.cpp` to match the new steps-per-mm.

---

## Current hold reduction (IHOLD)

In standalone mode, TMC2209 automatically reduces hold current to **50% of run current** when the motor is idle (TPOWERDOWN default).  
This reduces heat when the actuators are extended and holding position. No firmware change needed.

---

## Repeat for all three drivers

Each TMC2209 has its own pot. Set them one at a time while the other two are disabled by physically removing them or probing individually. All three should end up at the same VREF unless your motors differ.
