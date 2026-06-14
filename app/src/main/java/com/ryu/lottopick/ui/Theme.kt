package com.ryu.lottopick.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Brand {
    // Cute coral/pink primary accent
    val Accent = Color(0xFFFF7BA9)
    val AccentSoft = Color(0xFFFFB3CE)     // lighter coral for highlights/pressed
    val Mint = Color(0xFF7CD9C2)           // mint accent (secondary actions)
    val Lavender = Color(0xFFB9A7F0)       // lavender accent

    // Backgrounds
    val Cream = Color(0xFFFFF6F2)          // light app background
    val CreamTop = Color(0xFFFFE9F0)       // top of light gradient (soft pink)
    val CardLight = Color(0xFFFFFFFF)      // light cards
    val OnCardLight = Color(0xFF4A3640)    // warm dark text

    // Dark mode equivalents
    val NightBg = Color(0xFF231A20)        // warm near-black
    val NightTop = Color(0xFF2E2029)       // top of dark gradient
    val CardDark = Color(0xFF332530)       // dark cards
    val OnCardDark = Color(0xFFF6E9EF)     // soft light text
}

private val Dark = darkColorScheme(
    primary = Brand.Accent,
    onPrimary = Color(0xFF3A1226),
    secondary = Brand.Mint,
    background = Brand.NightBg,
    onBackground = Brand.OnCardDark,
    surface = Brand.CardDark,
    onSurface = Brand.OnCardDark,
    surfaceVariant = Color(0xFF42323D),
    onSurfaceVariant = Color(0xFFD6BFCC),
)

private val Light = lightColorScheme(
    primary = Brand.Accent,
    onPrimary = Color.White,
    secondary = Brand.Mint,
    background = Brand.Cream,
    onBackground = Brand.OnCardLight,
    surface = Brand.CardLight,
    onSurface = Brand.OnCardLight,
    surfaceVariant = Color(0xFFFFE4EC),
    onSurfaceVariant = Color(0xFF9C7787),
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}
