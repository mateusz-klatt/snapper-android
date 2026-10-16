package ch.snapper.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ch.snapper.android.R
import ch.snapper.android.UiError
import java.math.BigDecimal
import java.util.Locale
import ch.snapper.android.data.DisplayNumbers

internal val LocalNumberLocale = staticCompositionLocalOf { Locale.ROOT }

@Composable
internal fun BigDecimal?.display(): String = DisplayNumbers.format(this, LocalNumberLocale.current)

@Composable
internal fun Metric(label: Int, value: String, color: Color = LocalContentColor.current) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun FinancialMetric(label: Int, value: BigDecimal?) = Metric(label, value.display(), financialColor(value))

@Composable
internal fun RecordCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun ErrorCard(error: UiError, retry: () -> Unit, actionLabel: Int = R.string.retry) {
    val message = when (error) {
        UiError.INVALID_ORIGIN -> R.string.error_origin
        UiError.INVALID_CREDENTIALS -> R.string.error_credentials
        UiError.SESSION_EXPIRED -> R.string.error_session
        UiError.NETWORK -> R.string.error_network
        UiError.SERVICE -> R.string.error_service
        UiError.UNKNOWN -> R.string.error_unknown
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(message))
            TextButton(onClick = retry, modifier = Modifier.testTag("error.action")) { Text(stringResource(actionLabel)) }
        }
    }
}
