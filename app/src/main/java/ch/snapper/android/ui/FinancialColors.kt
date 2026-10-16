package ch.snapper.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import ch.snapper.android.data.FinancialColorConvention
import ch.snapper.android.data.FinancialDirectionColor
import ch.snapper.android.data.financialDirectionColor
import java.math.BigDecimal

val LocalFinancialColorConvention = staticCompositionLocalOf { FinancialColorConvention.RISING_GREEN }

/** Supply a signed value; zero/unavailable remain neutral. Severity colors are independent. */
@Composable
fun financialColor(value: BigDecimal?, convention: FinancialColorConvention = LocalFinancialColorConvention.current): Color = when (financialDirectionColor(value, convention)) {
    FinancialDirectionColor.GREEN -> Color(0xFF16A34A)
    FinancialDirectionColor.RED -> Color(0xFFDC2626)
    FinancialDirectionColor.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}
