# PlatformIO Flash Guide — EPD 3D G6

## Prerequisites

| Tool | Install |
|------|---------|
| VS Code | https://code.visualstudio.com |
| PlatformIO IDE extension | VS Code → Extensions → search **PlatformIO IDE** |
| USB driver (Windows only) | ESP32-C6 SuperMini uses a **USB CDC** (native USB). Windows 10/11 includes the driver. If the port does not appear, install the [Silicon Labs CP210x driver](https://www.silabs.com/developers/usb-to-uart-bridge-vcp-drivers) or the [WCH CH340 driver](https://www.wch-ic.com/downloads/CH341SER_EXE.html) depending on the USB chip on your SuperMini board. |

---

## 1. Open the project

1. Launch VS Code.
2. **File → Open Folder** → select the firmware subfolder you want to flash:
   - `firmware/stepper_test/` — back-and-forth motion smoke test
   - `firmware/homing_test/` — blind homing sequence (retract → min, extend → 12 mm)
   - `firmware/main/` — full operational firmware with BLE, OLED, FSR, battery monitoring
   PlatformIO detects `platformio.ini` and automatically installs the **Espressif32** platform and toolchain on first open (takes 1–3 minutes).

> **Note — NimBLE first build:** `firmware/main/` depends on `h2zero/NimBLE-Arduino @ ^2.1.0`. The first build will download and compile the NimBLE library from scratch which can take **2–4 minutes** on first run. Subsequent builds use the cached library and are much faster.

> **Arduino IDE test sketch:** A minimal BLE server sketch for quick connectivity testing lives in `firmware/ARDUINO IDE APP TEST/sketch_apr14a/`. Open it directly in the **Arduino IDE** (not PlatformIO). You will need to install the following libraries via the Library Manager: **NimBLE-Arduino** (by h2zero) and **ArduinoJson** (by Benoit Blanchon).

---

## 2. Connect the board

1. Plug the ESP32-C6 SuperMini into your PC via USB-C.
2. Confirm the COM port appears:
   - **Windows:** Device Manager → Ports (COM & LPT) → look for *USB Serial Device* or *CP210x*.
   - **macOS/Linux:** `ls /dev/tty*` — look for `/dev/ttyACM0` or `/dev/ttyUSB0`.

> If PlatformIO cannot auto-detect the port, add the following line to `platformio.ini`:
> ```ini
> upload_port = COM3       ; Windows example
> ; upload_port = /dev/ttyACM0  ; Linux/macOS example
> ```

---

## 3. Enter boot / download mode (if required)

The SuperMini usually auto-resets into download mode. If the upload fails:

1. Hold the **BOOT** button on the board.
2. Press and release **RESET** while still holding **BOOT**.
3. Release **BOOT**.
4. Retry the upload immediately.

---

## 4. Build and upload

### Via VS Code UI

Click the **→ Upload** arrow in the PlatformIO toolbar at the bottom of the VS Code window.

### Via PlatformIO CLI (terminal)

```bash
cd firmware/stepper_test
pio run --target upload
```

A successful upload ends with output similar to:

```
Writing at 0x00010000... (100 %)
Hash of data verified.
Leaving...
Hard resetting via RTS pin...
```

---

## 5. Monitor serial output

After upload the board resets and prints motion status over USB.

### VS Code UI

Click the **plug icon** (Serial Monitor) in the PlatformIO toolbar.  
Baud rate: **115200**.

### CLI

```bash
pio device monitor --baud 115200
```

Expected output (stepper_test):

```
EPD 3D G6 — stepper back-and-forth test
  STEP pin : GPIO20
  DIR  pin : GPIO19
  ENN  pin : GND (hardwired on PCB)
Driver enabled. Starting motion...
→ Forward  (7680 steps)
← Reverse  (7680 steps)
→ Forward  (7680 steps)
...
```

Expected output (homing_test):

```
EPD 3D G6 — homing test
  STEP : GPIO20  DIR : GPIO19  ENN : GND (hardwired)
Homing: retracting to min extension...
Homed. Position = 0 (min extension)
Extending to max (18432 steps = 12mm, 1536 steps/mm)...
At max extension. Done.
```

Expected output (main firmware):

```
EPD 3D G6 — main firmware booting...
[ADC] VBAT raw sanity: 2217
[BLE] Advertising as EPD3DG6
System ready.
```

The `[ADC] VBAT raw sanity` value confirms the ADC attenuation is set correctly. A value near 0 means the attenuation is not applied and battery readings will be wrong.

---

## 6. Tuning motion parameters

### stepper_test

Edit the constants at the top of `src/main.cpp`:

| Constant | Current value | Effect |
|----------|--------------|--------|
| `STEPS_PER_MOVE` | `7680` | Steps per direction pass (~5 mm) |
| `STEP_DELAY_US` | `600` | Delay between pulses (lower = faster, ~1600 steps/s) |
| `STEP_PULSE_US` | `5` | Step pulse HIGH width (keep ≥ 1 µs) |
| `PAUSE_MS` | `500` | Pause between direction changes |

### homing_test

| Constant | Current value | Effect |
|----------|--------------|--------|
| `STEPS_PER_MM` | `1536` | Calibrated steps per mm |
| `STEPS_FULL_TRAVEL` | `18432` | Full 12 mm stroke (1536 × 12) |
| `STEPS_HOME_OVERSHOOT` | `22000` | Overdrive steps for guaranteed end stop contact |
| `STEP_HOME_DELAY` | `600` | Homing speed delay µs (~1600 steps/s) |
| `STEP_NORMAL_DELAY` | `300` | Extension speed delay µs (~3200 steps/s) |

After editing, re-upload with the **→ Upload** button.

---

## 7. Troubleshooting

| Symptom | Fix |
|---------|-----|
| Port not found | Check USB cable, install/reinstall USB driver |
| Upload times out | Enter manual boot mode (step 3) |
| Motor does not move | Verify ENN is hardwired to GND on PCB; check VM (5 V) present on TMC2209 |
| Motor vibrates but no movement | Swap motor coil pair (OA1↔OA2 or OB1↔OB2) |
| Overheating driver | Reduce `STEPS_PER_MOVE` / increase `STEP_DELAY_US`; adjust current via trim pot |
| Battery shows 0% | `ADC_11db` attenuation must be set (already done in main firmware) |
| BLE device not found in app | Confirm firmware is advertising (`[BLE] Advertising...` in serial output); cycle BLE on phone |
