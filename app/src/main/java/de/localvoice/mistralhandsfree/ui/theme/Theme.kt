package de.localvoice.mistralhandsfree.ui.theme

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

// Warm orange on a dark ground. Only used before Android 12; from there on the
// system's own (wallpaper-based) palette takes over, like in any other app.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB68A),
    onPrimary = Color(0xFF4F2000),
    primaryContainer = Color(0xFF723600),
    onPrimaryContainer = Color(0xFFFFDCC8),
    secondary = Color(0xFFE5BFA8),
    background = Color(0xFF17120F),
    surface = Color(0xFF1F1915),
    surfaceVariant = Color(0xFF2C2420),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFB04A00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCC8),
    onPrimaryContainer = Color(0xFF370E00),
    secondary = Color(0xFF765848),
    background = Color(0xFFFFF8F5),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF5E3D9),
)

@Composable
fun HandsfreeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
