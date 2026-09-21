package app.signal.isolate.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
    primary = Color(0xFF7BE3C3),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF0E4D3F),
    onPrimaryContainer = Color(0xFFA6FFE2),
    secondary = Color(0xFF9FCFFF),
    onSecondary = Color(0xFF00324F),
    background = Color(0xFF0B0B0D),
    onBackground = Color(0xFFE6E6E9),
    surface = Color(0xFF131316),
    onSurface = Color(0xFFE6E6E9),
    surfaceVariant = Color(0xFF1C1C21),
    onSurfaceVariant = Color(0xFFB6B6BE),
    outline = Color(0xFF3A3A42),
    error = Color(0xFFFFB4AB),
)

@Composable
fun SignalIsolateTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Typography(), content = content)
}
