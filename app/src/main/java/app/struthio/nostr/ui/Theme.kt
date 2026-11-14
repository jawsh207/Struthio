package app.struthio.nostr.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.struthio.nostr.R

/** Google Sans Flex: one variable font file, one entry per weight (SIL OFL 1.1). */
val GoogleSansFlex: FontFamily = FontFamily(
    listOf(
        FontWeight.Thin, FontWeight.ExtraLight, FontWeight.Light, FontWeight.Normal, FontWeight.Medium,
        FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold, FontWeight.Black,
    ).map { weight -> Font(R.font.google_sans_flex, weight) },
)

/** Every Material type style, re-set in [family]. */
fun Typography.withFontFamily(family: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

/**
 * The same theme as Dialtone, Twill and Contacts: wallpaper colour following the system's
 * light/dark setting, Google Sans Flex, Material 3 Expressive. Colour roles: pages use
 * background/surface (surfaceContainerLow), the bottom bar keeps its default surfaceContainer,
 * cards use surfaceBright, search bars use surfaceContainerHighest. Accent colours are only for
 * highlights: the FAB, selection, links, own message bubbles.
 */
@Composable
fun ZinoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    // Dynamic colour needs Android 12; older versions get the standard Material scheme.
    val wallpaper = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    val colorScheme = wallpaper.copy(
        background = wallpaper.surfaceContainerLow,
        surface = wallpaper.surfaceContainerLow,
    )
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = Typography().withFontFamily(GoogleSansFlex),
        content = content,
    )
}
