package ch.snapper.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun SnapperTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF2ED47A), background = Color(0xFF0B0D10), surface = Color(0xFF15191E))
    } else {
        lightColorScheme(primary = Color(0xFF169B62), background = Color(0xFFF7F5F2), surface = Color.White)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
