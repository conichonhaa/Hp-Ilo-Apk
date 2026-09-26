package io.github.conichonhaa.ilo.ui.theme

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

private val Green = Color(0xFF01A982)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B52),
    secondary = Color(0xFF4B635A),
    tertiary = Color(0xFF3F6375),
)

private val DarkColors = darkColorScheme(
    primary = Green,
    secondary = Color(0xFFB2CCC1),
    tertiary = Color(0xFFA7CCE1),
)

@Composable
fun IloTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
