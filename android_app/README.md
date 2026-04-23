# Orthomate ⚡
**Adaptive Insole System with AI Analysis**

Orthomate is an intelligent orthotic system that combines real-time pressure sensing, automated physical actuation, and AI-powered foot health analysis. The system consists of a smart insole (ESP32-C6 firmware) and this Android companion application.

---

## 🚀 Features

- **Real-time Pressure Mapping**: Visualizes foot pressure distribution using a dynamic heat-map and precise percentage bars.
- **Gemini 2.5 AI Analysis**: Integrates with Google's Gemini 2.5 Flash model to provide personalized insights into foot posture, health considerations, and practical stretches.
- **BLE Control Center**: Manage hardware states (Measuring, Actuating, Homing) and send commands directly to the insole via Bluetooth Low Energy.
- **Snapshot Memory**: Automatically saves the last measurement session for offline review and AI analysis.
- **Modular Architecture**: Clean, industry-standard codebase split into dedicated layers for Data, UI, and Logic.

---

## 🛠️ Setup Instructions

### 1. Prerequisites
- Android Studio Panda 4.
- A Google Gemini API Key from [Google AI Studio](https://aistudio.google.com/).

### 2. API Key Configuration
For security, the API key is **not** stored in the repository. You must add it locally:
1. Open the project in Android Studio.
2. Locate (or create) the `local.properties` file in the root directory.
3. Add the following line:
   ```properties
   gemini.api.key=YOUR_ACTUAL_API_KEY_HERE
   ```
4. Perform a **Gradle Sync** to allow the app to bake the key into the build.

### 3. Build & Run
- Connect an Android device or start an emulator.
- Go to `Build > Build Bundle(s) / APK(s) > Build APK(s)` for a test version.
- Or simply click the **Run** icon (Green Play button) in Android Studio.

---

## 📂 Project Structure

- **`com.epd3dg6.bleapp.data`**:
    - `BleConstants`: UUIDs and command bytes for hardware communication.
    - `Models`: Data classes for sensor readings and connection states.
    - `GeminiApi`: Retrofit interface for the Gemini 2.5 Flash API.
- **`com.epd3dg6.bleapp.ui`**:
    - `MainScreen`: The primary layout orchestrator.
    - `Components`: Reusable UI elements (Heatmap, FsrBars, Cards).
    - `Theme`: Custom dark-mode color scheme and styling.
- **`MainActivity.kt`**: Handles Bluetooth lifecycle, permissions, and top-level app logic.

---

## 🦶 How to Use

1. **Connect**: Power on the Orthomate insole and tap "Connect" in the app.
2. **Monitor**: Watch the live pressure map as you stand or walk.
3. **Capture**: Tap "Capture" (or trigger from hardware) to save a snapshot of your pressure distribution.
4. **Analyse**: Tap "Analyse with Gemini 2.5" to get AI insights based on your specific foot profile.
5. **Adjust**: Use the Home/Actuate controls to physically reshape the insole based on the data.

---

## 🛡️ Security Note
This project uses `.gitignore` to protect sensitive files like `local.properties` and auto-generated build files. Always ensure your API keys are never committed to version control.
