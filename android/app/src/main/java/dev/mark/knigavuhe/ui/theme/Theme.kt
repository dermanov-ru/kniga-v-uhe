package dev.mark.knigavuhe.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Amber = Color(0xFFF4D06F)
private val Plum = Color(0xFF8E7DBE)
private val Ink = Color(0xFF14141F)

private val DarkScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF3A2E00),
    secondary = Plum,
    background = Ink,
    surface = Color(0xFF1C1C2A),
    surfaceVariant = Color(0xFF272738),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF7A5B00),
    secondary = Color(0xFF5B4B8A),
    background = Color(0xFFFFFBF2),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun KnigaVUheTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
