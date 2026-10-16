package ch.snapper.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import ch.snapper.android.R
import ch.snapper.android.data.DisplayPreferences
import ch.snapper.android.data.WalletDto
import ch.snapper.android.features.pnl.PnlActions
import ch.snapper.android.features.pnl.PnlState
import java.util.Locale
import org.junit.Rule
import org.junit.Test

class PnlDisplayLocaleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun countryChangesDecimalSeparatorWithoutChangingTranslatedLabelOrFinancialPrecision() {
        val locale = mutableStateOf(Locale.US)
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.positions_timeline_chart_net)
        val state = PnlState(wallet = WalletDto("w", "Demo", isPaper = true), permitted = true, data = pnlScreenFixture())
        compose.setContent {
            CompositionLocalProvider(LocalNumberLocale provides locale.value) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PnlTimelineScreen(state, PnlActions({}, {}, {}, {}))
                }
            }
        }
        compose.onNodeWithText("$label: 123.123456789123456789").performScrollTo().assertExists()
        compose.runOnIdle { locale.value = DisplayPreferences("pl").formattingLocale(Locale.US) }
        compose.onNodeWithText("$label: 123,123456789123456789").performScrollTo().assertExists()
        compose.onNodeWithText("$label: 123.123456789123456789").assertDoesNotExist()
    }
}
