package ch.snapper.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import ch.snapper.android.data.DisplayPreferences
import ch.snapper.android.data.FinancialColorPreference
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DisplayPreferencesContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun countrySelectionUsesCountryCodeAndCanReturnToDeviceDefault() {
        val state = mutableStateOf(DisplayPreferences())
        val calls = mutableListOf<String>()
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DisplayPreferencesContent(state.value, { calls += it; state.value = state.value.copy(countryCode = it) }, {})
            }
        }
        compose.onNodeWithTag("display.country").performClick()
        compose.onNodeWithTag("display.country.pl").performScrollTo().performClick()
        compose.onNodeWithTag("display.country").assertTextContains("Polska", substring = true)
        compose.runOnIdle { assertEquals(listOf("pl"), calls) }
        compose.onNodeWithTag("display.country").performClick()
        compose.onNodeWithTag("display.country.default").performClick()
        compose.runOnIdle { assertEquals(listOf("pl", ""), calls) }
    }

    @Test fun explicitConventionOverridesAutomaticAndCurrentSubtitleOnlyAppearsForAuto() {
        val state = mutableStateOf(DisplayPreferences(countryCode = "jp"))
        var changes = 0
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DisplayPreferencesContent(state.value, {}, {
                    changes++; state.value = state.value.copy(financialColor = it)
                }, Locale.US)
            }
        }
        compose.onNodeWithTag("display.financial.auto").assertIsSelected()
        compose.onNodeWithTag("display.financial.effective").assertExists()
        compose.onNodeWithTag("display.financial.rising-green").performScrollTo().performClick()
        compose.onNodeWithTag("display.financial.rising-green").assertIsSelected()
        compose.onNodeWithTag("display.financial.effective").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, changes)
            assertEquals(FinancialColorPreference.RISING_GREEN, state.value.financialColor)
            assertEquals("jp", state.value.countryCode)
        }
    }
}
