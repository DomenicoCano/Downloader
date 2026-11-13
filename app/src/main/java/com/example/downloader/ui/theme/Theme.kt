package com.example.downloader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

enum class ThemeMode(val title: String) {
    SYSTEM("Как в системе"),
    LIGHT("Светлая"),
    DARK("Тёмная"),
    OLED("Чёрная (OLED)"),
}

private val LightScheme = lightColorScheme(
    primary = YouTubeRed, onPrimary = Color.White,
    secondary = Brass, onSecondary = Color.White,
    background = ConcreteLight, onBackground = InkLight,
    surface = PlasterLight, onSurface = InkLight,
    surfaceVariant = Color(0xFFDDD5C9), onSurfaceVariant = Color(0xFF5A514A),
    outline = Color(0xFF8A7F74), error = Color(0xFFB3261E),
)

private val DarkScheme = darkColorScheme(
    primary = YouTubeRedLight, onPrimary = Color.Black,
    secondary = BrassLight, onSecondary = Color.Black,
    background = GraphiteDark, onBackground = InkDark,
    surface = GraphiteSurface, onSurface = InkDark,
    surfaceVariant = Color(0xFF3A3531), onSurfaceVariant = Color(0xFFC9BFB2),
    outline = Color(0xFF8C8176),
)

private val OledScheme = DarkScheme.copy(
    background = OledBlack,
    surface = OledSurface,
    surfaceVariant = Color(0xFF1E1A18),
)

// Строгие, почти прямоугольные формы — «железо и кирпич»
private val LoftShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(6.dp),
)

@Composable
fun DownloaderTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val scheme = when (mode) {
        ThemeMode.LIGHT -> LightScheme
        ThemeMode.DARK -> DarkScheme
        ThemeMode.OLED -> OledScheme
        ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) DarkScheme else LightScheme
    }
    MaterialTheme(colorScheme = scheme, typography = Typography, shapes = LoftShapes, content = content)
}
