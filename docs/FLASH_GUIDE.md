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
2. **File → Open Folder** → select `firmware/stepper_test/`.  
   PlatformIO detects `platformio.ini` and automatically installs the **Espressif32** platform and toolchain on first open (takes 1–3 minutes).

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

Expected output:

```
EPD 3D G6 — stepper back-and-forth test
  STEP pin : GPIO20
  DIR  pin : GPIO19
  ENN  pin : GPIO7 (active LOW)
Driver enabled. Starting motion...
→ Forward  (200 steps)
← Reverse  (200 steps)
→ Forward  (200 steps)
...
```

---

## 6. Tuning motion parameters

Edit the constants at the top of `src/main.cpp`:

| Constant | Default | Effect |
|----------|---------|--------|
| `STEPS_PER_MOVE` | `200` | Steps per direction pass |
| `STEP_DELAY_US` | `1500` | Delay between pulses (lower = faster) |
| `STEP_PULSE_US` | `5` | Step pulse HIGH width (keep ≥ 1 µs) |
| `PAUSE_MS` | `500` | Pause between direction changes |

After editing, re-upload with the **→ Upload** button.

---

## 7. Troubleshooting

| Symptom | Fix |
|---------|-----|
| Port not found | Check USB cable, install/reinstall USB driver |
| Upload times out | Enter manual boot mode (step 3) |
| Motor does not move | Verify ENN is wired to GPIO 7 and pulled LOW; check VM (5 V) present on TMC2209 |
| Motor vibrates but no movement | Swap motor coil pair (OA1↔OA2 or OB1↔OB2) |
| Overheating driver | Reduce `STEPS_PER_MOVE` / increase `STEP_DELAY_US`; adjust current via trim pot |
