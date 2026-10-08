package com.oxi.calc

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import com.russhwolf.settings.Settings

/** Platform-provided persistent key/value store (SharedPreferences on Android, NSUserDefaults on iOS). */
@Composable
expect fun rememberSettings(): Settings

/** Material You dynamic color when the platform supports it, otherwise null so the caller falls back. */
@Composable
expect fun dynamicColorSchemeOrNull(darkTheme: Boolean): ColorScheme?
