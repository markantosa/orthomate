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

// ── BLE constants (must match firmware) ─────────────────────────────────────
private const val DEVICE_NAME    = "EPD3DG6"
private val SVC_UUID  = UUID.fromString("4fa0c560-78a3-11ee-b962-0242ac120002")
private val CHAR_UUID = UUID.fromString("4fa0c561-78a3-11ee-b962-0242ac120002")
private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

// ── Data model ───────────────────────────────────────────────────────────────
data class DeviceData(
    val fsr1: Int = 0,      // 0-100 relative %
    val fsr2: Int = 0,
    val fsr3: Int = 0,
    val mode: String = "—",
    val batt: Int = 0       // 0-100 %
)

enum class ConnState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

// ── Colour helpers ────────────────────────────────────────────────────────────
private fun fsrColor(pct: Int): Color = when {
    pct < 34 -> Color(0xFF4CAF50)   // green  — low force
    pct < 67 -> Color(0xFFFFC107)   // yellow — medium force
    else     -> Color(0xFFF44336)   // red    — high force
}

// ─────────────────────────────────────────────────────────────────────────────
// MainActivity
// ─────────────────────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {

    // States exposed to Compose
    private val connState  = mutableStateOf(ConnState.DISCONNECTED)
    private val deviceData = mutableStateOf(DeviceData())
    private val statusMsg  = mutableStateOf("Press CONNECT to scan")

    // BLE handles
    private var bluetoothGatt: BluetoothGatt? = null
    private var leScanner: BluetoothLeScanner? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Permission launcher ───────────────────────────────────────────────────
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startScan()
        else statusMsg.value = "BLE permissions denied"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EPD3DG6Theme {
                Surface(modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background) {
                    MainScreen(
                        connState  = connState.value,
                        data       = deviceData.value,
                        statusMsg  = statusMsg.value,
                        onConnect  = { handleConnect() },
                        onDisconnect = { disconnect() }
                    )
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
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            statusMsg.value = "Bluetooth is off — enable it and retry"
            return
        }
        leScanner = adapter.bluetoothLeScanner
        connState.value = ConnState.SCANNING
        statusMsg.value = "Scanning for $DEVICE_NAME…"

        val filter = ScanFilter.Builder().setDeviceName(DEVICE_NAME).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        leScanner?.startScan(listOf(filter), settings, scanCallback)

        // Stop scan after 10 s if nothing found
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
            leScanner?.stopScan(this)
            mainHandler.removeCallbacksAndMessages(null)
            connState.value = ConnState.CONNECTING
            statusMsg.value = "Connecting…"
            result.device.connectGatt(
                this@MainActivity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
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
                gatt.discoverServices()
                mainHandler.post { statusMsg.value = "Discovering services…" }
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
            // Enable notifications
            gatt.setCharacteristicNotification(char, true)
            val desc = char.getDescriptor(CCCD_UUID)
            desc?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }
            mainHandler.post {
                connState.value = ConnState.CONNECTED
                statusMsg.value = "Connected to $DEVICE_NAME"
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
                batt = obj.optInt("batt", 0).coerceIn(0, 100)
            )
            mainHandler.post { deviceData.value = d }
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
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(20.dp),
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
        Text("PRESSURE MAP", color = Color.Gray, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))

        FootDiagram(
            fsr1 = data.fsr1,
            fsr2 = data.fsr2,
            fsr3 = data.fsr3,
            active = connState == ConnState.CONNECTED
        )

        Spacer(Modifier.height(32.dp))

        // ── FSR legend ─────────────────────────────────────────────────────
        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
            FsrLegendRow("FSR1 (metatarsal)", data.fsr1, connState == ConnState.CONNECTED)
            Spacer(Modifier.height(8.dp))
            FsrLegendRow("FSR2 (lateral)",    data.fsr2, connState == ConnState.CONNECTED)
            Spacer(Modifier.height(8.dp))
            FsrLegendRow("FSR3 (heel)",        data.fsr3, connState == ConnState.CONNECTED)
        }

        Spacer(Modifier.weight(1f))

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

// ── Theme ─────────────────────────────────────────────────────────────────────
@Composable
fun EPD3DG6Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary   = Color(0xFF1E88E5),
            background = Color(0xFF121212),
            surface   = Color(0xFF1E1E1E)
        ),
        content = content
    )
}
