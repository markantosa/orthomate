# Firmware — `firmware/main` Explained

**File:** `firmware/main/src/main.cpp`
**Hardware ref:** v6.0

---

## 1. What the firmware does

Single-session insole fitting cycle:

1. Detect insole connector
2. Measure foot pressure distribution (FSR sensors)
3. Compute how far each actuator should extend
4. Drive the three stepper actuators to those positions
5. Hold position until the user triggers retract

Everything is driven by one button and one connector-detect pin.

---

## 2. State machine

```
DISCONNECTED ──[plug in, pos=home]──► CONNECTED
DISCONNECTED ──[plug in, pos≠0]────► HOMING ──┐
                                               │
CONNECTED    ──[btn #1]────────────► MEASURING ◄─┘
                                       │
                                    MEASURED
                                       │ [btn #2]
                                    ACTUATING
                                       │
                                      DONE  ──[unplug]──► DISCONNECTED
                                             (insole retains shape)
any state    ──[unplug]────────────► DISCONNECTED
```

**Full user flow:**
1. Plug in connector → CONNECTED (or HOMING if actuators were extended)
2. Press button → measure → press button → actuate → DONE
3. Unplug connector → insole retains its shape, use as-is
4. To reshape: plug in → auto-home → back to CONNECTED → repeat

States are an `enum State` (`ST_DISCON`, `ST_CONN`, `ST_MEASURING`, `ST_MEASURED`, `ST_ACTUATING`, `ST_DONE`, `ST_HOMING`). The main loop polls at ~20 Hz (`delay(50)` at the bottom of `loop()`).

Two transitions fire outside the switch block and override any state:
- **Unplug** → immediately sets `PIN_ENN` HIGH (disables drivers), transitions to `ST_DISCON`. `gPos[]` is **not reset** — position is remembered so homing is triggered correctly on the next reconnect.
- **Hard low-battery** (`VBAT < VBAT_CUTOFF = 3.35 V`) → sets `PIN_ENN` HIGH, blocks the loop for 5 s, returns without advancing state.

**Reconnect logic:**
```cpp
bool needsHome = false;
for (int i = 0; i < 3; i++) if (gPos[i] != 0) { needsHome = true; break; }
gState = needsHome ? ST_HOMING : ST_CONN;
```
If any actuator has a non-zero tracked position, the firmware goes to `ST_HOMING` before allowing a new cycle.

---

## 3. Connector detect

**Circuit:**
```
J_MAIN pin 6 (3V3) ── insole 10 kΩ ──► pin 8 (CONN_DETECT) ──► GPIO15
                                                                     │
                                                           INPUT_PULLDOWN (~45 kΩ internal)
                                                                     │
                                                                    GND
```

**Logic:**
- Unplugged: GPIO15 pulled LOW by internal ~45 kΩ → `connPresent() = false`
- Plugged in: 3.3 V through insole 10 kΩ + internal 45 kΩ divider → V ≈ 2.7 V → `connPresent() = true`
- No external resistor needed on the controller PCB

---

## 4. FSR measurement

**Function:** `doMeasure()`

### 4.1 ADC sampling

Each FSR channel is read with a 7-sample insertion-sort **median filter** (`medianADC()`):

```cpp
// ADC_SAMPLES = 7, 4 ms apart
// Returns the middle value of the sorted array
return buf[ADC_SAMPLES / 2];
```

The median (not mean) is used to reject single-sample spikes from motor switching noise or contact bounce.

### 4.2 Relative load fractions

Raw ADC counts (0–4095, 12-bit) are normalised:

$$r_i = \frac{F_i}{\sum_{j=0}^{3} F_j}, \quad \sum r_i = 1$$

```cpp
float sum = raw[0] + raw[1] + raw[2] + raw[3];
if (sum < 1.0f) sum = 1.0f;   // guard: zero load (sensors floating/open)
for (int i = 0; i < 4; i++) gRelLoad[i] = raw[i] / sum;
```

FSR4 (`GPIO3`) contributes to the normalisation sum but has no actuator — it acts as an auxiliary pressure reference (rear heel).

### 4.3 Extension target calculation

The corrective logic is **inverse**: the zone bearing the *least* load gets the *most* extension (raises that zone to redistribute weight).

$$\text{ext}_i = 1 - r_i, \quad i \in \{1,2,3\}$$

$$\text{target\_steps}_i = \lfloor \text{ext}_i \times \texttt{MAX\_EXT\_STEPS} \rfloor$$

```cpp
float ext = 1.0f - gRelLoad[i];
if (ext < EXT_DEADBAND) ext = 0.0f;       // 8% deadband — suppress noise
ext = constrain(ext, 0.0f, 1.0f);
gTarget[i] = (int32_t)(ext * MAX_EXT_STEPS);
```

**Example:**

| FSR | Raw ADC | r_i  | ext = 1−r_i | target steps (MAX=400) |
|-----|---------|------|-------------|------------------------|
| 1   | 1200    | 0.40 | 0.60        | 240                    |
| 2   | 600     | 0.20 | 0.80        | 320                    |
| 3   | 900     | 0.30 | 0.70        | 280                    |
| 4   | 300     | 0.10 | (no ACT)    | —                      |
| Σ   | 3000    | 1.00 |             |                        |

FSR2 has the lowest load → ACT2 extends the most.

**Deadband** (`EXT_DEADBAND = 0.08`): if a computed extension fraction is less than 8%, it is zeroed. This prevents tiny actuator movements from wearing the mechanism on near-uniform pressure distributions.

---

## 5. Battery monitoring

**Circuit:** 1:2 resistor divider (100 kΩ / 100 kΩ) on VBAT → GPIO5 (ADC1 ch5)

$$V_{\text{BAT}} = \frac{\text{ADC count}}{4095} \times 3.3\,\text{V} \times 2$$

$$V_{\text{BAT}} = \frac{\text{ADC count}}{4095} \times 3.3\,\text{V} \times 2$$

```cpp
float vadc = (medianADC(PIN_VBAT) / 4095.0f) * 3.3f;
return vadc * 2.0f;
```

| Threshold | Value | Action |
|-----------|-------|--------|
| `VBAT_WARN` | 3.50 V | Show "! LOW BATTERY" warning on OLED in `ST_CONN` |
| `VBAT_CUTOFF` | 3.35 V | Disable motors, block loop for 5 s, require charge |
| `VBAT_NOISE` | 0.50 V | Ignore reads below this (no battery connected during dev) |

The cutoff check runs **before** the state machine in every loop iteration — it cannot be bypassed by state transitions.

---

## 6. Actuation

**Function:** `doActuate()`

### 6.1 Step generation

`driveSteps(step_pin, dir_pin, steps)`:
- Positive `steps` → `DIR = HIGH` (extend)
- Negative `steps` → `DIR = LOW` (retract)
- Each pulse: `STEP = HIGH` for `STEP_PULSE_US = 5 µs`, then `LOW` for `STEP_DELAY_US = 1500 µs`
- Effective step rate: $\frac{1}{1505\,\mu s} \approx 664\,\text{Hz}$ → ~664 steps/s

At `MAX_EXT_STEPS = 400`, full extension takes:

$$t = \frac{400}{664} \approx 0.6\,\text{s per actuator}$$

### 6.2 Position tracking

`gPos[3]` tracks current step position from home (0 = fully retracted) for each actuator. `moveToTarget()` computes the delta and calls `driveSteps()` with only the required number of additional steps:

```cpp
int32_t delta = target - gPos[idx];
driveSteps(PIN_STEP[idx], PIN_DIR[idx], delta);
gPos[idx] = target;
```

This allows calling `doActuate()` or `retractAll()` at any time without needing to know the current physical position from scratch.

### 6.3 Stagger

Motors are driven **sequentially** with `ACT_STAGGER_MS = 75 ms` delay between each. This prevents current spikes from three simultaneous motor starts from destabilising the 5V_SYS rail.

### 6.4 ENN hold

After actuation, `PIN_ENN` remains LOW (drivers enabled). This maintains holding torque so actuators do not back-drive under body weight while the connector is still attached.

On **unplug**, ENN goes HIGH immediately (no current draw during use — the insole is self-contained). Position is mechanically retained by the self-locking lead screw mechanism.

ENN is set HIGH by:
- Connector unplug (any state)
- `retractAll()` completion (ST_HOMING)
- Low-battery cutoff

---

## 7. Button logic

`btnEdge()` returns `true` exactly **once** per physical button press:

```
falling edge detected (LOW) + !gLastBtn + Δt > BTN_DEBOUNCE_MS (30 ms)
```

- `PIN_BUTTON = GPIO4` is `INPUT_PULLUP` → LOW when pressed
- GPIO4 is a strapping pin; the button must **not** be held at boot or it forces the chip into download mode

---

## 8. OLED display

`showOLED(r1, r2, r3)` draws up to 3 rows on the 128×32 SSD1306. It includes a **content cache** — if all three strings match the previous call, no I2C transaction is issued. This prevents unnecessary bus activity during motor current pulses.

The display is non-critical: `gOledOk` is checked at the start of every call, so firmware continues normally if no OLED is detected.

I2C bus: SDA = GPIO7, SCL = GPIO6.

---

## 9. Key constants summary

| Constant | Value | Meaning |
|---|---|---|
| `MAX_EXT_STEPS` | 400 | Full extension travel in steps |
| `STEP_DELAY_US` | 1500 µs | Inter-step interval (sets motor speed) |
| `STEP_PULSE_US` | 5 µs | STEP pin HIGH width (TMC2209 min = 2 µs) |
| `ACT_STAGGER_MS` | 75 ms | Delay between sequential motor starts |
| `EXT_DEADBAND` | 0.08 | Minimum extension fraction before zeroing |
| `ADC_SAMPLES` | 7 | Median filter depth (must be odd) |
| `BTN_DEBOUNCE_MS` | 30 ms | Minimum time between accepted button edges |
| `VBAT_WARN` | 3.50 V | OLED warning threshold |
| `VBAT_CUTOFF` | 3.35 V | Hard motor disable threshold |
