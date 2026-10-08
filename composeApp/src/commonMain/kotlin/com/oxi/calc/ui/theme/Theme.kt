package com.oxi.calc.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.oxi.calc.dynamicColorSchemeOrNull

private val DarkColorScheme = darkColorScheme(
    primary = WaterBluePrimaryDark,
    secondary = WaterBlueSecondaryDark,
    tertiary = WaterBlueTertiaryDark
)

private val LightColorScheme = lightColorScheme(
    primary = WaterBluePrimary,
    secondary = WaterBlueSecondary,
    tertiary = WaterBlueTertiary
)

@Composable
fun OxiCalcTheme(
    darkTheme: Boolean,
    // Dynamic color (Material You) is used when the platform provides it (Android 12+).
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = (if (dynamicColor) dynamicColorSchemeOrNull(darkTheme) else null)
        ?: if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
