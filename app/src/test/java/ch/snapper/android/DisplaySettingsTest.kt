package ch.snapper.android

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import ch.snapper.android.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DisplaySettingsTest {
    @Test fun atomicDisplayUpdatesPreserveBackendLanguageAndReloadBothChoices() = runTest {
        val memory = Memory()
        val settings = DataStoreSettings(memory)
        settings.setBackendUrl("https://snapper.example")
        settings.setLanguage("pl")
        settings.updateDisplayPreferences { it.copy(countryCode = "jp") }
        settings.updateDisplayPreferences { it.copy(financialColor = FinancialColorPreference.RISING_GREEN) }
        val restored = DataStoreSettings(memory).preferences.first()
        assertEquals("https://snapper.example", restored.backendUrl)
        assertEquals("pl", restored.language)
        assertEquals(DisplayPreferences("jp", FinancialColorPreference.RISING_GREEN), restored.displayPreferences)
        settings.setLanguage("fr")
        assertEquals(restored.displayPreferences, settings.preferences.first().displayPreferences)
        assertTrue(runCatching { settings.updateDisplayPreferences { it.copy(countryCode = "en") } }.isFailure)
        assertEquals(restored.displayPreferences, settings.preferences.first().displayPreferences)
    }

    @Test fun malformedPersistedValuesFallBackIndependentlyWithoutLosingOtherSettings() = runTest {
        val memory = Memory()
        memory.data.value = mutablePreferencesOf(stringPreferencesKey("display_country") to "PL",
            stringPreferencesKey("financial_color") to "risingRed", stringPreferencesKey("language") to "pl",
            stringPreferencesKey("backend_origin") to "https://snapper.example")
        val settings = DataStoreSettings(memory)
        val restored = settings.preferences.first()
        assertEquals(DisplayPreferences(), restored.displayPreferences)
        assertEquals("pl", restored.language)
        assertEquals("https://snapper.example", restored.backendUrl)
        settings.updateDisplayPreferences { it.copy(countryCode = "hk") }
        assertEquals(DisplayPreferences("hk"), settings.preferences.first().displayPreferences)
    }

    private class Memory : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(mutablePreferencesOf())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
}
