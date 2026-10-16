package ch.snapper.android

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanguageViewModelTest {
    @Test fun supportedLanguagesReachSettingsAndInvalidInputDoesNot() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val settings = LanguageSettings()
            val model = SnapperViewModel(settings, { error("No backend should be created") }, "", false)
            models.put("language", model)
            advanceUntilIdle()
            for (tag in listOf("fr", "ar", "zh-Hant", "")) {
                model.selectLanguage(tag); advanceUntilIdle()
                assertEquals(tag, model.state.value.language)
            }
            val writes = settings.writes
            model.selectLanguage("not-a-supported-tag"); advanceUntilIdle()
            assertEquals(writes, settings.writes)
            assertEquals("", model.state.value.language)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    private class LanguageSettings : SettingsStore {
        override val preferences = MutableStateFlow(ClientPreferences(null))
    override suspend fun updateDisplayPreferences(transform: (ch.snapper.android.data.DisplayPreferences) -> ch.snapper.android.data.DisplayPreferences): ch.snapper.android.data.DisplayPreferences {
        val updated = transform(preferences.value.displayPreferences)
        preferences.value = preferences.value.copy(displayPreferences = updated)
        return updated
    }

        var writes = 0
        override suspend fun setBackendUrl(origin: String) { error("Unexpected backend mutation") }
        override suspend fun setLanguage(language: String) {
            writes++
            preferences.value = preferences.value.copy(language = language)
        }
    }
}
