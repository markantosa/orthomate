# Android App — Build & Install Guide

## What the app does

| Feature | Detail |
|---|---|
| BLE device | `EPD3DG6` |
| Service UUID | `4fa0c560-78a3-11ee-b962-0242ac120002` |
| Characteristic | `4fa0c561-78a3-11ee-b962-0242ac120002` (NOTIFY / READ) |
| Update rate | ~4 Hz |

The app connects over BLE and displays:
1. **Relative force** for FSR1, FSR2, FSR3 as animated colour/size circles + percentage bars  
   (green < 34 % → yellow < 67 % → red ≥ 67 %)
2. **Current mode** (DISCONNECTED / CONNECTED / MEASURING / ACTUATING / etc.)
3. **Battery percentage** (0–100 %)
4. **BLE connection status** dot (grey / yellow / green)

---

## Requirements

| Tool | Minimum version |
|---|---|
| Android Studio | Hedgehog (2023.1.1) or newer |
| Android SDK | API 35 (compile), API 26 (min — Android 8.0) |
| JDK | 17 (bundled with Android Studio) |
| Phone | Android 8.0+ with Bluetooth LE |

---

## Step 1 — Open the project

1. Launch **Android Studio**
2. `File → Open` → select the folder:
   ```
   EPD 3D G6/android_app/
   ```
3. Wait for Gradle sync to complete (first sync downloads ~500 MB of dependencies)

---

## Step 2 — Enable Developer Mode on your phone

1. **Settings → About phone** → tap **Build number** 7 times  
   *(exact path varies by manufacturer; search "Build number" in Settings)*
2. A toast will say **"You are now a developer"**
3. Go to **Settings → Developer options** → enable **USB debugging**

---

## Step 3 — Connect your phone and build

### Option A — USB (fastest)

1. Connect phone to PC via USB cable
2. Accept the **"Allow USB debugging?"** prompt on the phone
3. In Android Studio, select your phone from the device dropdown (top toolbar)
4. Click **▶ Run** (Shift+F10)
5. The app installs and launches automatically — done

### Option B — Build a release APK, then sideload

1. In Android Studio: **Build → Build Bundle(s) / APK(s) → Build APK(s)**
2. Gradle produces the file at:
   ```
   android_app/app/build/outputs/apk/release/app-release.apk
   ```
3. Transfer `app-release.apk` to your phone (USB, email, Google Drive, etc.)
4. On your phone:
   - Open **Settings → Apps → Special app access → Install unknown apps**
   - Grant permission to the app you used to receive the file (Files, Gmail, etc.)
5. Open the APK from your file manager and tap **Install**

---

## Step 4 — Grant Bluetooth permissions at first launch

On first launch the app will trigger a system permission dialog:

- **Android 11 and below:** Allow *Location* (required by system for BLE scanning)
- **Android 12+:** Allow *Nearby devices* (Bluetooth Scan + Connect)

Tap **Allow** (or **Allow while using app** on Android 12+).

---

## Step 5 — Connect to the device

1. Flash the firmware (see [FLASH_GUIDE.md](FLASH_GUIDE.md))
2. Power on the EPD 3D G6 board
3. Open the app and tap **CONNECT**
4. The app scans for a device named `EPD3DG6` (up to 10 s)
5. Once found it connects automatically and the display updates live

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Device not found" | Make sure the ESP32 is powered and advertising; move phone closer (< 5 m) |
| "BLE permissions denied" | Go to **Settings → Apps → EPD 3D G6 → Permissions** and grant Nearby Devices / Location |
| App crashes on Android 11 | Ensure Location permission is granted (system requirement for BLE scanning) |
| Scan finds device but connection drops | Power-cycle the ESP32 and retry; avoid USB-3 ports near the phone |
| BATTERY shows — | Normal when not CONNECTED; check firmware is built with BLE enabled |
| FSR circles don't animate | Device is connected but no measurement has been taken; press the MODE button on the board to start a measurement cycle |

---

## Uninstalling

**Settings → Apps → EPD 3D G6 → Uninstall**
