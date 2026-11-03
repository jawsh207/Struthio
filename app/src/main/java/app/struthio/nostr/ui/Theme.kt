package app.struthio.nostr.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightFallback = lightColorScheme(
    primary = Color(0xFF5F5791), onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFE), onPrimaryContainer = Color(0xFF463F77),
    secondaryContainer = Color(0xFFE5DEFD), onSecondaryContainer = Color(0xFF48435C),
    tertiary = Color(0xFF834F5F), tertiaryContainer = Color(0xFFFFD9E3), onTertiaryContainer = Color(0xFF693847),
    surface = Color(0xFFFAF8FF), onSurface = Color(0xFF1C1A22), onSurfaceVariant = Color(0xFF484551),
    surfaceContainerLow = Color(0xFFF5F2FE), surfaceContainer = Color(0xFFEFECF8),
    surfaceContainerHigh = Color(0xFFE9E7F2), surfaceContainerHighest = Color(0xFFE4E1EC),
    outlineVariant = Color(0xFFC8C4D3),
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFFC9BFFF), onPrimary = Color(0xFF30285F),
    primaryContainer = Color(0xFF463F77), onPrimaryContainer = Color(0xFFE6DEFE),
    secondaryContainer = Color(0xFF48435C), onSecondaryContainer = Color(0xFFE5DEFD),
    tertiary = Color(0xFFF5B7C8), tertiaryContainer = Color(0xFF693847), onTertiaryContainer = Color(0xFFFFD9E3),
    surface = Color(0xFF131218), onSurface = Color(0xFFE5E1EA), onSurfaceVariant = Color(0xFFC9C5D3),
    surfaceContainerLow = Color(0xFF1C1A22), surfaceContainer = Color(0xFF201E26),
    surfaceContainerHigh = Color(0xFF2B2931), surfaceContainerHighest = Color(0xFF36343C),
    outlineVariant = Color(0xFF484551),
)

/** Colors come from the system on Android 12 and up, so the app follows the wallpaper. */
@Composable
fun StruthioTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkFallback
        else -> LightFallback
    }
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}
