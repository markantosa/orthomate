package com.epd3dg6.bleapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epd3dg6.bleapp.data.ConnState
import com.epd3dg6.bleapp.data.DeviceData
import com.epd3dg6.bleapp.data.FsrSnapshot
import kotlinx.coroutines.launch

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
    onAnalyse: () -> Unit,
    onDemo: () -> Unit,
    onCapture: () -> Unit,
    onButton: () -> Unit,
    onHome: () -> Unit,
    onEstop: () -> Unit
) {
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val isConnected = connState == ConnState.CONNECTED
    val isActuating = data.mode == "ACTUATING"
    val isDone      = data.mode == "DONE"
    val currentMode = if (isConnected) data.mode else snapshot?.mode ?: "OFFLINE"
    val liveFsr1    = if (isConnected) data.live1 else 0
    val liveFsr2    = if (isConnected) data.live2 else 0
    val liveFsr3    = if (isConnected) data.live3 else 0

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = BgDeep
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(scrollState)
        ) {
            Spacer(Modifier.height(32.dp))

            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Orthomate ⚡", color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("Adaptive Insole System", color = TextMuted, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (connState == ConnState.DISCONNECTED) {
                        TextButton(onClick = onDemo) {
                            Text("DEMO", color = Purple1, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    BleStatusPill(connState)
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Mode card ─────────────────────────────────────────────────────
            val modeGrad = when (currentMode) {
                "ACTUATING"         -> listOf(Color(0xFF0E7490), Color(0xFF164E63))
                "DONE"              -> listOf(Color(0xFF15803D), Color(0xFF14532D))
                "MEASURING",
                "MEASURED"          -> listOf(Color(0xFFB45309), Color(0xFF78350F))
                "HOMING"            -> listOf(Color(0xFFC2410C), Color(0xFF7C2D12))
                "IDLE"              -> listOf(Color(0xFF5B21B6), Color(0xFF312E81))
                else                -> listOf(Color(0xFF1E1B4B), Color(0xFF0F0F1A))
            }
            val modeIcon = when (currentMode) {
                "ACTUATING" -> "⚙"; "DONE" -> "✓"; "MEASURING" -> "◎"
                "MEASURED"  -> "◉"; "HOMING" -> "↺"; "IDLE" -> "◈"; else -> "—"
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.horizontalGradient(modeGrad))
                    .padding(horizontal = 20.dp, vertical = 20.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        SectionLabel("DEVICE STATUS")
                        Spacer(Modifier.height(8.dp))
                        Text(currentMode, color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(modeIcon, fontSize = 22.sp)
                    }
                }
            }

            // ── Actuator status ───────────────────────────────────────────────
            if (isActuating || isDone) {
                Spacer(Modifier.height(16.dp))
                AppCard {
                    SectionLabel("ACTUATORS")
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(
                            Triple("ACT 1", data.act1, Color(0xFF06B6D4)),
                            Triple("ACT 2", data.act2, Color(0xFF8B5CF6)),
                            Triple("ACT 3", data.act3, Color(0xFFF59E0B))
                        ).forEach { (label, steps, color) ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(color.copy(alpha = 0.10f))
                                    .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                    .padding(vertical = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        if (isActuating) "MOVING" else "$steps",
                                        color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold
                                    )
                                    if (isDone) Text("steps", color = TextMuted, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Heatmap card ──────────────────────────────────────────────────
            AppCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionLabel("PRESSURE MAP")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isConnected) {
                            TextButton(
                                onClick = onCapture,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("CAPTURE", color = Color(0xFFEF4444), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0xFF22C55E).copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text("LIVE", color = Color(0xFF22C55E), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    FootHeatmap(liveFsr1, liveFsr2, liveFsr3, isConnected)
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Live pressure card ────────────────────────────────────────────
            AppCard {
                SectionLabel("LIVE PRESSURE")
                Spacer(Modifier.height(16.dp))
                FsrBar("Metatarsal", liveFsr1, isConnected)
                Spacer(Modifier.height(12.dp))
                FsrBar("Arch",       liveFsr2, isConnected)
                Spacer(Modifier.height(12.dp))
                FsrBar("Heel",       liveFsr3, isConnected)
            }

            // ── Measured snapshot card ────────────────────────────────────────
            if (data.snap == 1 || snapshot != null) {
                val mFsr1 = if (data.snap == 1) data.fsr1 else snapshot?.fsr1 ?: 0
                val mFsr2 = if (data.snap == 1) data.fsr2 else snapshot?.fsr2 ?: 0
                val mFsr3 = if (data.snap == 1) data.fsr3 else snapshot?.fsr3 ?: 0
                Spacer(Modifier.height(16.dp))
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("MEASURED VALUES")
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFFEF4444).copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text("SNAPSHOT", color = Color(0xFFEF4444), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text("Used to calculate actuation", color = TextMuted, fontSize = 11.sp)
                    Spacer(Modifier.height(16.dp))
                    FsrBar("Metatarsal", mFsr1, true)
                    Spacer(Modifier.height(12.dp))
                    FsrBar("Arch",       mFsr2, true)
                    Spacer(Modifier.height(12.dp))
                    FsrBar("Heel",       mFsr3, true)
                }
            }

            // ── Device controls ───────────────────────────────────────────────
            if (isConnected) {
                Spacer(Modifier.height(16.dp))
                AppCard {
                    SectionLabel("CONTROLS")
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            onClick = onButton,
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = Color.Transparent
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize()
                                    .background(Brush.verticalGradient(listOf(Color(0xFF2563EB), Color(0xFF1D4ED8)))),
                                contentAlignment = Alignment.Center
                            ) { Text("BUTTON", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                        }
                        Surface(
                            onClick = onHome,
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = Color.Transparent
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize()
                                    .background(Brush.verticalGradient(listOf(Color(0xFF16A34A), Color(0xFF15803D)))),
                                contentAlignment = Alignment.Center
                            ) { Text("HOME", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        onClick = onEstop,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = Color.Transparent
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .background(Color(0xFF7F1D1D))
                                .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("⏹  EMERGENCY STOP", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Gemini card ───────────────────────────────────────────────────
            AppCard {
                SectionLabel("AI ANALYSIS")
                Spacer(Modifier.height(14.dp))
                Surface(
                    onClick = {
                        if (isLoading) return@Surface
                        if (snapshot == null) {
                            scope.launch { snackbarHostState.showSnackbar("No measurement stored yet — capture a snapshot first.") }
                        } else { onAnalyse() }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Transparent
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .background(Brush.horizontalGradient(listOf(Purple1, Purple2))),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoading) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = TextPrimary, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("Gemini is thinking...", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        } else {
                            Text("Analyse with Gemini 2.5  ✦", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
                geminiText?.let { text ->
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = CardBorder)
                    Spacer(Modifier.height(14.dp))
                    Text(text, color = TextSecondary, fontSize = 13.sp, lineHeight = 20.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                statusMsg, color = TextMuted, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(12.dp))

            // ── Connect / Disconnect button ────────────────────────────────────
            Surface(
                onClick = if (connState == ConnState.DISCONNECTED) onConnect else onDisconnect,
                enabled = connState != ConnState.SCANNING && connState != ConnState.CONNECTING,
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color.Transparent
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        if (connState == ConnState.CONNECTED)
                            Brush.horizontalGradient(listOf(Color(0xFFB91C1C), Color(0xFF991B1B)))
                        else
                            Brush.horizontalGradient(listOf(Color(0xFF2563EB), Color(0xFF1D4ED8)))
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (connState) {
                            ConnState.DISCONNECTED -> "Connect to Device"
                            ConnState.SCANNING     -> "Scanning…"
                            ConnState.CONNECTING   -> "Connecting…"
                            ConnState.CONNECTED    -> "Disconnect"
                        },
                        color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp
                    )
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Preview(showSystemUi = true, device = "id:pixel_5")
@Composable
fun MainScreenPreview() {
    OrthomateTheme {
        MainScreen(
            connState = ConnState.CONNECTED,
            data = DeviceData(
                live1 = 45, live2 = 20, live3 = 80,
                act1 = 1200, act2 = 800, act3 = 1500,
                mode = "ACTUATING"
            ),
            statusMsg = "Connected to EPD3DG6",
            snapshot = FsrSnapshot(50, 30, 70, "MEASURED", 0L),
            geminiText = "Based on your pressure map, we are adjusting the arch support to reduce heel strain.",
            isLoading = false,
            onConnect = {}, onDisconnect = {}, onAnalyse = {}, onDemo = {}, onCapture = {},
            onButton = {}, onHome = {}, onEstop = {}
        )
    }
}
