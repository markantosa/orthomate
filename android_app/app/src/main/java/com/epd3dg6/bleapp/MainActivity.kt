package com.epd3dg6.bleapp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.location.LocationManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import com.epd3dg6.bleapp.data.*
import com.epd3dg6.bleapp.data.BleConstants.CCCD_UUID
import com.epd3dg6.bleapp.data.BleConstants.CHAR_UUID
import com.epd3dg6.bleapp.data.BleConstants.CMD_BUTTON
import com.epd3dg6.bleapp.data.BleConstants.CMD_UUID
import com.epd3dg6.bleapp.data.BleConstants.CMD_ESTOP
import com.epd3dg6.bleapp.data.BleConstants.CMD_HOME
import com.epd3dg6.bleapp.data.BleConstants.DEVICE_NAME
import com.epd3dg6.bleapp.data.BleConstants.SVC_UUID
import com.epd3dg6.bleapp.ui.MainScreen
import com.epd3dg6.bleapp.ui.OrthomateTheme

private val GEMINI_API_KEY = BuildConfig.GEMINI_API_KEY

// ── MainActivity ─────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {

    private val connState  = mutableStateOf(ConnState.DISCONNECTED)
    private val deviceData = mutableStateOf(DeviceData())
    private val statusMsg  = mutableStateOf("Press CONNECT to scan")
    private val lastSnapshot = mutableStateOf<FsrSnapshot?>(null)
    private val geminiResponse = mutableStateOf<String?>(null)
    private val isGeminiLoading = mutableStateOf(false)

    private val geminiService by lazy {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GeminiService::class.java)
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.all { it.value }) startScan()
        else statusMsg.value = "Permissions denied"
    }

    private var encryptedPrefs: SharedPreferences? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var leScanner: BluetoothLeScanner? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        encryptedPrefs = try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "secret_shared_prefs",
                masterKeyAlias,
                this,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e("BLE_DEBUG", "EncryptedSharedPreferences init failed, using plain prefs: ${e.message}")
            getSharedPreferences("fallback_prefs", MODE_PRIVATE)
        }

        loadSnapshot()

        setContent {
            OrthomateTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(
                        connState  = connState.value,
                        data       = deviceData.value,
                        statusMsg  = statusMsg.value,
                        snapshot   = lastSnapshot.value,
                        geminiText = geminiResponse.value,
                        isLoading  = isGeminiLoading.value,
                        onConnect  = { handleConnect() },
                        onDisconnect = { disconnect() },
                        onAnalyse  = { checkApiKeyAndAnalyse() },
                        onDemo     = { runDemo() },
                        onCapture  = {
                            val d = deviceData.value
                            saveSnapshot(FsrSnapshot(d.live1, d.live2, d.live3, d.mode, System.currentTimeMillis()))
                        },
                        onButton   = { sendBleCommand(CMD_BUTTON) },
                        onHome     = { sendBleCommand(CMD_HOME) },
                        onEstop    = { sendBleCommand(CMD_ESTOP) }
                    )
                }
            }
        }
    }

    private fun loadSnapshot() {
        val json = encryptedPrefs?.getString("last_snapshot", null)
        if (json != null) {
            lastSnapshot.value = Gson().fromJson(json, FsrSnapshot::class.java)
        }
    }

    private fun saveSnapshot(s: FsrSnapshot) {
        lastSnapshot.value = s
        encryptedPrefs?.edit()?.putString("last_snapshot", Gson().toJson(s))?.apply()
    }

    private fun checkApiKeyAndAnalyse() {
        // Log available models for debugging
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val models = geminiService.listModels(GEMINI_API_KEY)
                Log.d("GEMINI_MODELS", "Available models: ${models.models.map { it.name }}")
            } catch (e: Exception) {
                Log.e("GEMINI_MODELS", "Failed to list models: ${e.message}")
            }
        }
        analyseWithGemini(GEMINI_API_KEY)
    }

    private fun analyseWithGemini(apiKey: String) {
        val snap = lastSnapshot.value ?: return
        isGeminiLoading.value = true
        geminiResponse.value = null

        val prompt = "I used a smart insole that measured the relative pressure distribution across my foot. " +
                "Results: Metatarsal (ball of foot): ${snap.fsr1}%, Arch: ${snap.fsr2}%, Heel: ${snap.fsr3}%. " +
                "Based on this distribution, give me: 1. What this pattern suggests about my foot shape or posture " +
                "2. Any foot health considerations I should be aware of 3. Practical tips for footwear, stretches, " +
                "or habits that suit this profile. Keep the response concise (1-3 lines) for each bullet point, clear and non-medical in tone."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = geminiService.generateContent(
                    apiKey = apiKey,
                    request = GeminiRequest(listOf(Content(listOf(Part(prompt)))))
                )
                val text = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                withContext(Dispatchers.Main) {
                    if (text != null) {
                        geminiResponse.value = text
                    } else {
                        val reason = response.candidates?.firstOrNull()?.finishReason
                        geminiResponse.value = if (reason != null) "Analysis blocked: $reason" else "No response from Gemini."
                    }
                    isGeminiLoading.value = false
                }
            } catch (e: Exception) {
                val errorDetail = if (e is HttpException) {
                    val body = e.response()?.errorBody()?.string()
                    "HTTP ${e.code()}: $body"
                } else {
                    e.message
                }
                Log.e("GEMINI_ERR", "Analysis failed: $errorDetail")
                withContext(Dispatchers.Main) {
                    geminiResponse.value = "Error: $errorDetail"
                    isGeminiLoading.value = false
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }

    private fun handleConnect() {
        if (connState.value != ConnState.DISCONNECTED) return
        val perms = requiredPermissions()
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startScan() else permLauncher.launch(missing.toTypedArray())
    }

    private fun requiredPermissions(): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        return list
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            statusMsg.value = "Bluetooth is off — enable it and retry"
            return
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            if (!isGpsEnabled && !isNetworkEnabled) {
                statusMsg.value = "Please enable Location Services"
                return
            }
        }

        leScanner = adapter.bluetoothLeScanner
        connState.value = ConnState.SCANNING
        statusMsg.value = "Scanning for EPD3DG6…"
        
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        leScanner?.startScan(null, settings, scanCallback)

        mainHandler.postDelayed({
            if (connState.value == ConnState.SCANNING) {
                leScanner?.stopScan(scanCallback)
                connState.value = ConnState.DISCONNECTED
                statusMsg.value = "Device not found — try again"
            }
        }, 10_000L)
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name ?: "Unknown"
            val uuids = result.scanRecord?.serviceUuids
            val hasService = uuids?.any { it.uuid == SVC_UUID } ?: false
            
            if (deviceName.contains("EPD", ignoreCase = true) || hasService) {
                leScanner?.stopScan(this)
                mainHandler.removeCallbacksAndMessages(null)
                connState.value = ConnState.CONNECTING
                statusMsg.value = "Connecting to $deviceName…"
                result.device.connectGatt(
                    this@MainActivity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            connState.value = ConnState.DISCONNECTED
            statusMsg.value = "Scan failed (err $errorCode)"
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                bluetoothGatt = gatt
                mainHandler.post { statusMsg.value = "Negotiating MTU…" }
                gatt.requestMtu(185)
            } else {
                mainHandler.post {
                    connState.value  = ConnState.DISCONNECTED
                    deviceData.value = DeviceData()
                    statusMsg.value  = "Disconnected"
                }
                gatt.close()
                bluetoothGatt = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d("BLE_DEBUG", "MTU changed to $mtu, status=$status — discovering services")
            mainHandler.post { statusMsg.value = "MTU=$mtu, discovering…" }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post { statusMsg.value = "Service discovery failed" }
                return
            }
            val char = gatt.getService(SVC_UUID)?.getCharacteristic(CHAR_UUID)
            if (char == null) {
                mainHandler.post { statusMsg.value = "EPD service not found on device" }
                return
            }
            val notifEnabled = gatt.setCharacteristicNotification(char, true)
            Log.d("BLE_DEBUG", "setCharacteristicNotification=$notifEnabled")
            val desc = char.getDescriptor(CCCD_UUID)
            if (desc == null) {
                Log.e("BLE_DEBUG", "CCCD descriptor not found!")
                mainHandler.post { statusMsg.value = "CCCD not found — notifications unavailable" }
                return
            }
            val writeResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                if (gatt.writeDescriptor(desc)) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
            }
            Log.d("BLE_DEBUG", "writeDescriptor result=$writeResult")
            // Status update moved to onDescriptorWrite to confirm subscription succeeded
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d("BLE_DEBUG", "onDescriptorWrite uuid=${descriptor.uuid} status=$status")
            if (descriptor.uuid == CCCD_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    mainHandler.post {
                        connState.value = ConnState.CONNECTED
                        statusMsg.value = "Connected to ${gatt.device.name ?: DEVICE_NAME} — notifications active"
                    }
                } else {
                    Log.e("BLE_DEBUG", "CCCD write FAILED status=$status")
                    mainHandler.post { statusMsg.value = "Notification setup failed (status=$status)" }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != CHAR_UUID) return
            val json = characteristic.getStringValue(0) ?: return
            parseAndUpdate(json)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid != CHAR_UUID) return
            parseAndUpdate(String(value))
        }
    }

    private fun parseAndUpdate(json: String) {
        try {
            val obj = JSONObject(json)
            val d = DeviceData(
                fsr1  = obj.optInt("fsr1",  0).coerceIn(0, 100),
                fsr2  = obj.optInt("fsr2",  0).coerceIn(0, 100),
                fsr3  = obj.optInt("fsr3",  0).coerceIn(0, 100),
                live1 = obj.optInt("live1", 0).coerceIn(0, 100),
                live2 = obj.optInt("live2", 0).coerceIn(0, 100),
                live3 = obj.optInt("live3", 0).coerceIn(0, 100),
                mode  = obj.optString("mode", "—"),
                snap  = obj.optInt("snap",  0),
                act1  = obj.optInt("act1",  0),
                act2  = obj.optInt("act2",  0),
                act3  = obj.optInt("act3",  0)
            )
            Log.d("BLE_PARSE", "json=$json parsed_mode=${d.mode} fsr1=${d.fsr1}")
            mainHandler.post {
                deviceData.value = d
                if (d.snap == 1) {
                    saveSnapshot(FsrSnapshot(d.fsr1, d.fsr2, d.fsr3, d.mode, System.currentTimeMillis()))
                }
            }
        } catch (e: Exception) {
            Log.e("BLE_PARSE", "parseAndUpdate failed: ${e.message} json=$json")
            mainHandler.post { statusMsg.value = "PARSE ERR: ${e.message}" }
        }
    }

    private fun runDemo() {
        // Mock a connected device state
        connState.value = ConnState.CONNECTED
        statusMsg.value = "Demo Mode: Simulated Data"

        // Create mock data
        val mockData = DeviceData(
            fsr1 = 65, fsr2 = 15, fsr3 = 85,
            live1 = 65, live2 = 15, live3 = 85,
            mode = "IDLE",
            snap = 1,
            act1 = 1200, act2 = 500, act3 = 1800
        )
        deviceData.value = mockData

        // Manually trigger a snapshot save to enable Gemini analysis
        saveSnapshot(FsrSnapshot(mockData.fsr1, mockData.fsr2, mockData.fsr3, mockData.mode, System.currentTimeMillis()))
    }

    @SuppressLint("MissingPermission")
    private fun sendBleCommand(cmd: Byte) {
        val gatt = bluetoothGatt ?: return
        val char = gatt.getService(SVC_UUID)?.getCharacteristic(CMD_UUID) ?: return
        char.value = byteArrayOf(cmd)
        gatt.writeCharacteristic(char)
        Log.d("BLE_CMD", "Sent command 0x${cmd.toString(16)}")
    }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        leScanner?.stopScan(scanCallback)
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        mainHandler.removeCallbacksAndMessages(null)
        connState.value  = ConnState.DISCONNECTED
        deviceData.value = DeviceData()
        statusMsg.value  = "Press CONNECT to scan"
    }
}
