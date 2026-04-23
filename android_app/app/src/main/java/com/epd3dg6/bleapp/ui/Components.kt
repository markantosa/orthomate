package com.epd3dg6.bleapp.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epd3dg6.bleapp.R
import com.epd3dg6.bleapp.data.ConnState

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardBg)
            .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
            .padding(20.dp),
        content = content
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier = modifier
    )
}

@Composable
fun BleStatusPill(connState: ConnState) {
    val color by animateColorAsState(
        targetValue = when (connState) {
            ConnState.CONNECTED    -> Color(0xFF22C55E)
            ConnState.SCANNING,
            ConnState.CONNECTING   -> Color(0xFFF59E0B)
            ConnState.DISCONNECTED -> Color(0xFF475569)
        },
        animationSpec = tween(400), label = "pillColor"
    )
    val label = when (connState) {
        ConnState.CONNECTED    -> "Connected"
        ConnState.SCANNING     -> "Scanning"
        ConnState.CONNECTING   -> "Connecting"
        ConnState.DISCONNECTED -> "Offline"
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun FootHeatmap(fsr1: Int, fsr2: Int, fsr3: Int, active: Boolean) {
    val animFSR1 by animateFloatAsState(targetValue = if (active) fsr1 / 100f else 0f, animationSpec = tween(500), label = "fsr1")
    val animFSR2 by animateFloatAsState(targetValue = if (active) fsr2 / 100f else 0f, animationSpec = tween(500), label = "fsr2")
    val animFSR3 by animateFloatAsState(targetValue = if (active) fsr3 / 100f else 0f, animationSpec = tween(500), label = "fsr3")

    Box(modifier = Modifier.size(width = 160.dp, height = 290.dp)) {
        Image(
            painter = painterResource(R.drawable.foot_outline),
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            listOf(
                Triple(animFSR1, Offset(w * 0.48f, h * 0.20f), fsrColor((animFSR1 * 100).toInt())),
                Triple(animFSR2, Offset(w * 0.45f, h * 0.45f), fsrColor((animFSR2 * 100).toInt())),
                Triple(animFSR3, Offset(w * 0.49f, h * 0.75f), fsrColor((animFSR3 * 100).toInt()))
            ).forEach { (value, center, color) ->
                val radius = 18.dp.toPx() + 42.dp.toPx() * value
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to color.copy(alpha = 0.88f),
                        0.55f to color.copy(alpha = 0.40f),
                        1.0f to Color.Transparent,
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center,
                    blendMode = BlendMode.Screen
                )
            }
        }
    }
}

@Composable
fun FsrBar(label: String, pct: Int, active: Boolean) {
    val animPct by animateFloatAsState(
        targetValue = if (active) pct / 100f else 0f,
        animationSpec = tween(600), label = "pct_$label"
    )
    val barColor by animateColorAsState(
        targetValue = if (active) fsrColor(pct) else Color(0xFF1E293B),
        animationSpec = tween(400), label = "color_$label"
    )
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(82.dp))
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF1E293B))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animPct)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(barColor.copy(alpha = 0.6f), barColor)))
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (active) "$pct%" else "—",
            color = if (active) barColor else TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(32.dp)
        )
    }
}

fun fsrColor(pct: Int): Color = when {
    pct < 20 -> Color(0xFF3B82F6)
    pct < 50 -> Color(0xFF22C55E)
    pct < 75 -> Color(0xFFF59E0B)
    else     -> Color(0xFFEF4444)
}
