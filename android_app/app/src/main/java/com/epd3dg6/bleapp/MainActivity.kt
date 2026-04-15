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
import android.os.ParcelUuid
import android.util.Log
import android.location.LocationManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

// ── BLE constants (must match firmware) ─────────────────────────────────────
private const val DEVICE_NAME    = "EPD3DG6"
private val SVC_UUID  = UUID.fromString("4fa0c560-78a3-11ee-b962-0242ac120002")
private val CHAR_UUID = UUID.fromString("4fa0c561-78a3-11ee-b962-0242ac120002")
private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

// ── Enums ────────────────────────────────────────────────────────────────────
enum class ConnState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

// ── Data model ───────────────────────────────────────────────────────────────
data class DeviceData(
    val fsr1: Int = 0,      // 0-100 relative %
    val fsr2: Int = 0,
    val fsr3: Int = 0,
    val mode: String = "—",
    val batt: Int = 0,      // 0-100 %
    val snap: Int = 0       // 0 or 1
)

data class FsrSnapshot(
    val fsr1: Int,
    val fsr2: Int,
    val fsr3: Int,
    val timestamp: Long
)

// ── Gemini API ────────────────────────────────────────────────────────────────
interface GeminiService {
    @POST("v1beta/models/gemini-1.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): GeminiResponse
}

data class GeminiRequest(val contents: List<Content>)
data class Content(val parts: List<Part>)
data class Part(val text: String)
data class GeminiResponse(val candidates: List<Candidate>)
data class Candidate(val content: Content)

// ── MainActivity ─────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {

    // States exposed to Compose
    private val connState  = mutableStateOf(ConnState.DISCONNECTED)
    private val deviceData = mutableStateOf(DeviceData())
    private val statusMsg  = mutableStateOf("Press CONNECT to scan")
    private val lastSnapshot = mutableStateOf<FsrSnapshot?>(null)
    private val geminiResponse = mutableStateOf<String?>(null)
    private val isGeminiLoading = mutableStateOf(false)
    private val showKeyDialog = mutableStateOf(false)

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.all { it.value }) startScan()
        else statusMsg.value = "Permissions denied"
    }

    private lateinit var encryptedPrefs: SharedPreferences

    // ... (existing BLE handles)
    private var bluetoothGatt: BluetoothGatt? = null
    private var leScanner: BluetoothLeScanner? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        encryptedPrefs = EncryptedSharedPreferences.create(
            "secret_shared_prefs",
            masterKeyAlias,
            this,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        loadSnapshot()

        setContent {
            OrthomateTheme {
                Surface(modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background) {
                    MainScreen(
                        connState  = connState.value,
                        data       = deviceData.value,
                        statusMsg  = statusMsg.value,
                        snapshot   = lastSnapshot.value,
                        geminiText = geminiResponse.value,
                        isLoading  = isGeminiLoading.value,
                        onConnect  = { handleConnect() },
                        onDisconnect = { disconnect() },
                        onAnalyse  = { checkApiKeyAndAnalyse() }
                    )

                    if (showKeyDialog.value) {
                        ApiKeyDialog(
                            onDismiss = { showKeyDialog.value = false },
                            onSave = { key ->
                                encryptedPrefs.edit().putString("gemini_api_key", key).apply()
                                showKeyDialog.value = false
                                analyseWithGemini(key)
                            }
                        )
                    }
                }
            }
        }
    }

    private fun loadSnapshot() {
        val json = encryptedPrefs.getString("last_snapshot", null)
        if (json != null) {
            lastSnapshot.value = Gson().fromJson(json, FsrSnapshot::class.java)
        }
    }

    private fun saveSnapshot(s: FsrSnapshot) {
        lastSnapshot.value = s
        encryptedPrefs.edit().putString("last_snapshot", Gson().toJson(s)).apply()
    }

    private fun checkApiKeyAndAnalyse() {
        val key = encryptedPrefs.getString("gemini_api_key", null)
        if (key.isNullOrBlank()) {
            showKeyDialog.value = true
        } else {
            analyseWithGemini(key)
        }
    }

    private fun analyseWithGemini(apiKey: String) {
        val snap = lastSnapshot.value ?: return
        isGeminiLoading.value = true
        geminiResponse.value = null

        val prompt = "I used a smart insole that measured the relative pressure distribution across my foot. " +
                "Results: Metatarsal (ball of foot): ${snap.fsr1}%, Arch: ${snap.fsr2}%, Heel: ${snap.fsr3}%. " +
                "Based on this distribution, give me: 1. What this pattern suggests about my foot shape or posture " +
                "2. Any foot health considerations I should be aware of 3. Practical tips for footwear, stretches, " +
                "or habits that suit this profile. Keep the response clear and non-medical in tone."

        val retrofit = Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val service = retrofit.create(GeminiService::class.java)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = service.generateContent(apiKey, GeminiRequest(listOf(Content(listOf(Part(prompt))))))
                val text = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
                withContext(Dispatchers.Main) {
                    geminiResponse.value = text ?: "No response from Gemini."
                    isGeminiLoading.value = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    geminiResponse.value = "Error: ${e.message}"
                    isGeminiLoading.value = false
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BLE logic
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleConnect() {
        if (connState.value != ConnState.DISCONNECTED) return
        val perms = requiredPermissions()
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startScan() else permLauncher.launch(missing.toTypedArray())
    }

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            statusMsg.value = "Bluetooth is off — enable it and retry"
            return
        }

        // Check Location Services for API <= 30
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            if (!isGpsEnabled && !isNetworkEnabled) {
                statusMsg.value = "Please enable Location Services"
                return
            }
        }

        leScanner = adapter.bluetoothLeScanner
        connState.value = ConnState.SCANNING
        statusMsg.value = "Scanning for EPD service…"
        Log.d("BLE_DEBUG", "Starting scan with SVC_UUID filter: $SVC_UUID")

        // Filter by Service UUID instead of Device Name
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SVC_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        leScanner?.startScan(listOf(filter), settings, scanCallback)

        // Stop scan after 10 s if nothing found
        mainHandler.postDelayed({
            if (connState.value == ConnState.SCANNING) {
                Log.d("BLE_DEBUG", "Scan timed out after 10s")
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
            Log.d("BLE_DEBUG", "Found device: $deviceName [${result.device.address}]")
            
            leScanner?.stopScan(this)
            mainHandler.removeCallbacksAndMessages(null)
            connState.value = ConnState.CONNECTING
            statusMsg.value = "Connecting to $deviceName…"
            result.device.connectGatt(
                this@MainActivity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLE_DEBUG", "Scan failed with error code: $errorCode")
            connState.value = ConnState.DISCONNECTED
            statusMsg.value = "Scan failed (err $errorCode)"
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d("BLE_DEBUG", "onConnectionStateChange: status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                bluetoothGatt = gatt
                Log.d("BLE_DEBUG", "Connected, discovering services...")
                gatt.discoverServices()
                mainHandler.post { statusMsg.value = "Discovering services…" }
            } else {
                Log.d("BLE_DEBUG", "Disconnected or error")
                mainHandler.post {
                    connState.value  = ConnState.DISCONNECTED
                    deviceData.value = DeviceData()
                    statusMsg.value  = "Disconnected"
                }
                gatt.close()
                bluetoothGatt = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d("BLE_DEBUG", "onServicesDiscovered: status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post { statusMsg.value = "Service discovery failed" }
                return
            }
            
            // Log all found services for debugging
            gatt.services.forEach { service ->
                Log.d("BLE_DEBUG", "Service found: ${service.uuid}")
            }

            val char = gatt.getService(SVC_UUID)?.getCharacteristic(CHAR_UUID)
            if (char == null) {
                Log.e("BLE_DEBUG", "Target service or characteristic not found!")
                mainHandler.post { statusMsg.value = "EPD service not found on device" }
                return
            }
            // Enable notifications
            Log.d("BLE_DEBUG", "Enabling notifications for characteristic...")
            gatt.setCharacteristicNotification(char, true)
            val desc = char.getDescriptor(CCCD_UUID)
            desc?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }
            mainHandler.post {
                connState.value = ConnState.CONNECTED
                statusMsg.value = "Connected to ${gatt.device.name ?: DEVICE_NAME}"
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != CHAR_UUID) return
            val json = characteristic.getStringValue(0) ?: return
            parseAndUpdate(json)
        }

        // API 33+ override
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid != CHAR_UUID) return
            parseAndUpdate(String(value))
        }
    }

    private fun parseAndUpdate(json: String) {
        try {
            val obj = JSONObject(json)
            val d = DeviceData(
                fsr1 = obj.optInt("fsr1", 0).coerceIn(0, 100),
                fsr2 = obj.optInt("fsr2", 0).coerceIn(0, 100),
                fsr3 = obj.optInt("fsr3", 0).coerceIn(0, 100),
                mode = obj.optString("mode", "—"),
                batt = obj.optInt("batt", 0).coerceIn(0, 100),
                snap = obj.optInt("snap", 0)
            )
            mainHandler.post {
                deviceData.value = d
                if (d.snap == 1) {
                    saveSnapshot(FsrSnapshot(d.fsr1, d.fsr2, d.fsr3, System.currentTimeMillis()))
                }
            }
        } catch (_: Exception) { /* malformed packet — ignore */ }
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

// ─────────────────────────────────────────────────────────────────────────────
// Compose UI
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MainScreen(
    connState: ConnState,
    data: DeviceData,
    statusMsg: String,
    snapshot: FsrSnapshot?,
    geminiText: String?,
    isLoading: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onAnalyse: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(20.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // ── Header row ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Battery
            Text(
                text = if (connState == ConnState.CONNECTED) "BATTERY: ${data.batt}%" else "BATTERY: —",
                color = Color(0xFF64B5F6),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            // BLE status dot
            BleStatusDot(connState)
        }

        Spacer(Modifier.height(8.dp))

        // ── Mode ───────────────────────────────────────────────────────────
        Text(
            text = "MODE:",
            color = Color(0xFF64B5F6),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
        Text(
            text = if (connState == ConnState.CONNECTED) data.mode else "—",
            color = Color(0xFF64B5F6),
            fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic,
            fontSize = 16.sp
        )

        Spacer(Modifier.height(24.dp))
        Divider(color = Color(0xFF333333))
        Spacer(Modifier.height(24.dp))

        // ── Foot diagram ───────────────────────────────────────────────────
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("PRESSURE MAP", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))

                val displayFsr1 = if (data.snap == 1) data.fsr1 else if (connState == ConnState.CONNECTED) data.fsr1 else 0
                val displayFsr2 = if (data.snap == 1) data.fsr2 else if (connState == ConnState.CONNECTED) data.fsr2 else 0
                val displayFsr3 = if (data.snap == 1) data.fsr3 else if (connState == ConnState.CONNECTED) data.fsr3 else 0

                FootDiagram(
                    fsr1 = displayFsr1,
                    fsr2 = displayFsr2,
                    fsr3 = displayFsr3,
                    active = connState == ConnState.CONNECTED || data.snap == 1
                )
            }

            if (data.snap == 1) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xFFFF5252).copy(alpha = 0.9f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("MEASURED", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        // ── FSR legend ─────────────────────────────────────────────────────
        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
            val isActive = connState == ConnState.CONNECTED || data.snap == 1
            FsrLegendRow("Metatarsal (FSR1)", if (data.snap == 1) data.fsr1 else data.fsr1, isActive)
            Spacer(Modifier.height(8.dp))
            FsrLegendRow("Arch (FSR2)", if (data.snap == 1) data.fsr2 else data.fsr2, isActive)
            Spacer(Modifier.height(8.dp))
            FsrLegendRow("Heel (FSR3)", if (data.snap == 1) data.fsr3 else data.fsr3, isActive)
        }

        if (snapshot != null) {
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onAnalyse,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E24AA))
            ) {
                Text("ANALYSE WITH GEMINI", fontWeight = FontWeight.Bold)
            }
        }

        if (isLoading) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(color = Color(0xFF8E24AA))
        }

        geminiText?.let {
            Spacer(Modifier.height(16.dp))
            Surface(
                color = Color(0xFF2C2C2C),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    it,
                    color = Color.White,
                    modifier = Modifier.padding(16.dp),
                    fontSize = 14.sp
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Status text ────────────────────────────────────────────────────
        Text(statusMsg, color = Color.Gray, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))

        // ── Connect / Disconnect button ────────────────────────────────────
        Button(
            onClick = if (connState == ConnState.DISCONNECTED) onConnect else onDisconnect,
            enabled = connState != ConnState.SCANNING && connState != ConnState.CONNECTING,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (connState == ConnState.CONNECTED)
                    Color(0xFFF44336) else Color(0xFF1E88E5)
            ),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(
                text = when (connState) {
                    ConnState.DISCONNECTED -> "CONNECT"
                    ConnState.SCANNING     -> "SCANNING…"
                    ConnState.CONNECTING   -> "CONNECTING…"
                    ConnState.CONNECTED    -> "DISCONNECT"
                },
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ── BLE status indicator dot ──────────────────────────────────────────────────
@Composable
fun BleStatusDot(connState: ConnState) {
    val dotColor by animateColorAsState(
        targetValue = when (connState) {
            ConnState.CONNECTED    -> Color(0xFF4CAF50)
            ConnState.SCANNING,
            ConnState.CONNECTING   -> Color(0xFFFFC107)
            ConnState.DISCONNECTED -> Color(0xFF757575)
        },
        animationSpec = tween(300), label = "dotColor"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(4.dp))
        Text(
            text = when (connState) {
                ConnState.CONNECTED    -> "CONNECTED"
                ConnState.SCANNING     -> "SCANNING"
                ConnState.CONNECTING   -> "CONNECTING"
                ConnState.DISCONNECTED -> "NOT CONNECTED"
            },
            color = dotColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
        )
    }
}

// ── Foot pressure diagram ─────────────────────────────────────────────────────
@Composable
fun FootDiagram(fsr1: Int, fsr2: Int, fsr3: Int, active: Boolean) {
    // Dot sizes: 16 dp baseline + up to +40 dp based on force %
    val maxGrow = 40.dp

    @Composable
    fun fsrDot(pct: Int, label: String) {
        val dotSize: Dp by animateDpAsState(
            targetValue = if (active) 16.dp + (maxGrow * pct / 100f) else 16.dp,
            animationSpec = tween(300), label = "size_$label"
        )
        val dotColor by animateColorAsState(
            targetValue = if (active) fsrColor(pct) else Color(0xFF444444),
            animationSpec = tween(300), label = "color_$label"
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(dotSize).clip(CircleShape).background(dotColor))
            Spacer(Modifier.height(4.dp))
            Text(label, color = Color.Gray, fontSize = 10.sp)
        }
    }

    // Approximate foot layout: FSR1 top-center, FSR2 mid-left, FSR3 bottom-center
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        fsrDot(fsr1, "FSR1")
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
            fsrDot(fsr2, "FSR2")
            Spacer(Modifier.width(8.dp))
        }
        Spacer(Modifier.height(16.dp))
        fsrDot(fsr3, "FSR3")
    }
}

// ── FSR legend row with progress bar ─────────────────────────────────────────
@Composable
fun FsrLegendRow(label: String, pct: Int, active: Boolean) {
    val barColor by animateColorAsState(
        targetValue = if (active) fsrColor(pct) else Color(0xFF444444),
        animationSpec = tween(300), label = "bar_$label"
    )
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color.LightGray, fontSize = 13.sp)
            Text(
                text = if (active) "$pct%" else "—",
                color = barColor, fontWeight = FontWeight.Bold, fontSize = 13.sp
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (active) pct / 100f else 0f },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = barColor,
            trackColor = Color(0xFF333333)
        )
    }
}

fun fsrColor(pct: Int): Color {
    return when {
        pct < 20 -> Color(0xFF2196F3) // Blue
        pct < 50 -> Color(0xFF4CAF50) // Green
        pct < 80 -> Color(0xFFFFEB3B) // Yellow
        else     -> Color(0xFFF44336) // Red
    }
}

@Composable
fun ApiKeyDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter Gemini API Key") },
        text = {
            Column {
                Text("Your API key is stored securely in EncryptedSharedPreferences.", fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(key) }) { Text("SAVE & ANALYSE") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL") }
        }
    )
}

// ── Theme ─────────────────────────────────────────────────────────────────────
@Composable
fun OrthomateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary   = Color(0xFF8E24AA),
            background = Color(0xFF121212),
            surface   = Color(0xFF1E1E1E)
        ),
        content = content
    )
}
