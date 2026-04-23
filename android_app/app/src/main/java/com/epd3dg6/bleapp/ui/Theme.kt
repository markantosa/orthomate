package com.epd3dg6.bleapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BgDeep        = Color(0xFF0C0C14)
val CardBg        = Color(0xFF13131F)
val CardBorder    = Color(0xFF1E1E32)
val Purple1       = Color(0xFF7C3AED)
val Purple2       = Color(0xFF4338CA)
val TextPrimary   = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)
val TextMuted     = Color(0xFF475569)

@Composable
fun OrthomateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary      = Purple1,
            background   = BgDeep,
            surface      = CardBg,
            onPrimary    = TextPrimary,
            onBackground = TextPrimary,
            onSurface    = TextPrimary
        ),
        content = content
    )
}
