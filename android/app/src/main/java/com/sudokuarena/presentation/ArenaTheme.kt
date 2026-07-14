package com.sudokuarena.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object ArenaColors {
    val Canvas = Color(0xFFF7F8FC)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceSoft = Color(0xFFEEF2FF)
    val ElectricBlue = Color(0xFF0057D9)
    val Violet = Color(0xFF6D28D9)
    val Ink = Color(0xFF111827)
    val InkMuted = Color(0xFF374151)
    val Border = Color(0xFFCBD5E1)
    val Error = Color(0xFFB42318)
}

private val HighContrastScheme = lightColorScheme(
    primary = ArenaColors.ElectricBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF082A63),
    secondary = ArenaColors.Violet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE4FF),
    onSecondaryContainer = Color(0xFF351078),
    background = ArenaColors.Canvas,
    onBackground = ArenaColors.Ink,
    surface = ArenaColors.Surface,
    onSurface = ArenaColors.Ink,
    surfaceVariant = ArenaColors.SurfaceSoft,
    onSurfaceVariant = ArenaColors.InkMuted,
    outline = ArenaColors.Border,
    error = ArenaColors.Error,
    onError = Color.White,
)

@Composable
fun SudokuArenaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HighContrastScheme, content = content)
}
