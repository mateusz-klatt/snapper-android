package ch.snapper.android

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LanguageSettingsTest {
    @Test fun additionalAndScriptLanguagesPersistAndInvalidTagsCannotOverwriteThem() = runTest {
        val store = MemoryPreferences()
        val settings = DataStoreSettings(store)
        for (tag in listOf("fr", "ar", "zh-Hans", "zh-Hant", "sr-Latn", "")) {
            settings.setLanguage(tag)
            assertEquals(tag, settings.preferences.first().language)
            assertEquals(tag, DataStoreSettings(store).preferences.first().language)
        }
        settings.setLanguage("fr")
        assertTrue(runCatching { settings.setLanguage("unsupported") }.exceptionOrNull() is IllegalArgumentException)
        assertEquals("fr", settings.preferences.first().language)
    }

    @Test fun unsupportedPersistedLanguageFallsBackToSystemWithoutLosingBackend() = runTest {
        val store = MemoryPreferences()
        store.values.value = mutablePreferencesOf(stringPreferencesKey("language") to "invalid_tag",
            stringPreferencesKey("backend_origin") to "https://snapper.example")
        val settings = DataStoreSettings(store)
        assertEquals("", settings.preferences.first().language)
        assertEquals("https://snapper.example", settings.preferences.first().backendUrl)
    }

    private class MemoryPreferences : DataStore<Preferences> {
        val values = MutableStateFlow<Preferences>(mutablePreferencesOf())
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(values.value).also { values.value = it }
    }
}
