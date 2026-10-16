package ch.snapper.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import ch.snapper.android.R
import ch.snapper.android.data.*
import java.util.Locale

/** Independent country/formatting selector; changing it never implicitly changes catalog language. */
@Composable
fun DisplayPreferencesContent(
    preferences: DisplayPreferences,
    onCountry: (String) -> Unit,
    onFinancialColor: (FinancialColorPreference) -> Unit,
    deviceLocale: Locale = Locale.getDefault(),
) {
    Column(Modifier.testTag("displayPreferences")) {
        CountryPreference(preferences.countryCode, onCountry)
        Text(stringResource(R.string.display_financial_title))
        FinancialColorPreference.entries.forEach { preference ->
            FilterChip(selected = preferences.financialColor == preference,
                onClick = { onFinancialColor(preference) },
                label = { Text(stringResource(financialPreferenceLabel(preference))) },
                modifier = Modifier.testTag("display.financial.${preference.wireValue}"))
        }
        if (preferences.financialColor == FinancialColorPreference.AUTO) {
            val label = if (preferences.convention(deviceLocale) == FinancialColorConvention.RISING_RED)
                R.string.display_financial_red_short else R.string.display_financial_green_short
            Text(stringResource(R.string.display_financial_current, stringResource(label)), Modifier.testTag("display.financial.effective"))
        }
    }
}

@Composable
private fun CountryPreference(code: String, onCountry: (String) -> Unit) {
    var expanded by remember(code) { mutableStateOf(false) }
    val name = DisplayCountries.find(code)?.nativeName ?: stringResource(R.string.system_default)
    Box {
        Button(onClick = { expanded = true }, modifier = Modifier.testTag("display.country")) {
            Text("${stringResource(R.string.display_country)}: $name")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.system_default)) },
                onClick = { expanded = false; onCountry("") }, modifier = Modifier.testTag("display.country.default"))
            DisplayCountries.all.forEach { country ->
                DropdownMenuItem(text = { Text(country.nativeName) },
                    onClick = { expanded = false; onCountry(country.code) },
                    modifier = Modifier.testTag("display.country.${country.code}"))
            }
        }
    }
}

private fun financialPreferenceLabel(preference: FinancialColorPreference): Int = when (preference) {
    FinancialColorPreference.AUTO -> R.string.display_financial_auto
    FinancialColorPreference.RISING_GREEN -> R.string.display_financial_green
    FinancialColorPreference.RISING_RED -> R.string.display_financial_red
}
