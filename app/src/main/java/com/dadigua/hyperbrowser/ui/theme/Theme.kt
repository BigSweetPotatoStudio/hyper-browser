package com.dadigua.hyperbrowser.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HyperLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF126D6A),
    onPrimary = Color.White,
    secondary = Color(0xFF4A635F),
    surface = Color(0xFFF7F9F8),
    background = Color(0xFFF7F9F8),
    error = Color(0xFFB3261E)
)

@Composable
fun HyperBrowserTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HyperLight,
        typography = MaterialTheme.typography,
        content = content
    )
}
