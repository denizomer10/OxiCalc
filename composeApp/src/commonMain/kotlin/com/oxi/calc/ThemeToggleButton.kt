package com.oxi.calc

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Modern theme toggle.
 * - Dark theme: a white circular button showing a sun (tap to switch to light).
 * - Light theme: a black circular button showing a moon (tap to switch to dark).
 *
 * The icon is drawn with [Canvas] instead of an emoji/icon-font, so no icon pack
 * is pulled into the APK and there is no glyph-rendering variance across devices.
 */
@Composable
fun ThemeToggleButton(
    isDarkMode: Boolean,
    buttonSize: Dp,
    onToggle: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (isDarkMode) Color.White else Color.Black,
        label = "themeToggleContainer"
    )

    Surface(
        onClick = onToggle,
        shape = CircleShape,
        color = containerColor,
        modifier = Modifier.size(buttonSize)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Crossfade(targetState = isDarkMode, label = "themeToggleIcon") { dark ->
                SunMoonIcon(
                    isDark = dark,
                    container = if (dark) Color.White else Color.Black,
                    content = if (dark) Color.Black else Color.White,
                    modifier = Modifier.size(buttonSize * 0.55f)
                )
            }
        }
    }
}

@Composable
private fun SunMoonIcon(
    isDark: Boolean,
    container: Color,
    content: Color,
    modifier: Modifier
) {
    Canvas(modifier = modifier) {
        val d = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)

        if (isDark) {
            // Sun: solid core plus eight rounded rays.
            drawCircle(color = content, radius = d * 0.20f, center = center)
            val inner = d * 0.30f
            val outer = d * 0.46f
            val stroke = (d * 0.08f).coerceAtLeast(1f)
            for (i in 0 until 8) {
                val angle = (PI / 4.0 * i).toFloat()
                drawLine(
                    color = content,
                    start = Offset(center.x + cos(angle) * inner, center.y + sin(angle) * inner),
                    end = Offset(center.x + cos(angle) * outer, center.y + sin(angle) * outer),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
        } else {
            // Crescent moon: a filled disc with an offset "bite" in the button colour.
            val radius = d * 0.40f
            val moonCenter = Offset(center.x - d * 0.06f, center.y)
            drawCircle(color = content, radius = radius, center = moonCenter)
            drawCircle(
                color = container,
                radius = radius * 0.95f,
                center = Offset(moonCenter.x + d * 0.24f, moonCenter.y - d * 0.16f)
            )
        }
    }
}
