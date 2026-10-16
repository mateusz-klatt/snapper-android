package ch.snapper.android

import androidx.lifecycle.ViewModelStore
import ch.snapper.android.data.*
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DisplayPreferencesViewModelTest {
    @Test fun restoreAndQueuedCountryColorChangesPreserveLanguageAndEachOther() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val settings = Settings()
            val model = SnapperViewModel(settings, { error("No session expected") }, "", false)
            models.put("display", model)
            advanceUntilIdle()
            assertEquals(DisplayPreferences("pl"), model.state.value.displayPreferences)
            val gate = CompletableDeferred<Unit>()
            settings.gate = gate
            model.selectCountry("jp")
            runCurrent()
            model.selectFinancialColor(FinancialColorPreference.RISING_GREEN)
            runCurrent()
            assertEquals(DisplayPreferences("pl"), model.state.value.displayPreferences)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(DisplayPreferences("jp", FinancialColorPreference.RISING_GREEN), model.state.value.displayPreferences)
            assertEquals("fr", model.state.value.language)
            model.selectCountry("en")
            advanceUntilIdle()
            assertEquals(2, settings.writes)
        } finally { models.clear(); Dispatchers.resetMain() }
    }

    @Test fun failedPersistenceDoesNotPublishAnUncommittedPreference() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val settings = Settings().apply { fail = true }
            val model = SnapperViewModel(settings, { error("No session expected") }, "", false)
            models.put("display", model)
            advanceUntilIdle()
            model.selectCountry("jp")
            advanceUntilIdle()
            assertEquals(DisplayPreferences("pl"), model.state.value.displayPreferences)
            assertEquals(UiError.SERVICE, model.state.value.error)
            settings.fail = false
            model.selectCountry("")
            advanceUntilIdle()
            assertEquals(DisplayPreferences(), model.state.value.displayPreferences)
        } finally { models.clear(); Dispatchers.resetMain() }
    }

    private class Settings : SettingsStore {
        override val preferences = MutableStateFlow(ClientPreferences(null, "fr", DisplayPreferences("pl")))
        var gate: CompletableDeferred<Unit>? = null
        var writes = 0
        var fail = false
        override suspend fun setBackendUrl(origin: String) { error("Unexpected backend change") }
        override suspend fun setLanguage(language: String) { error("Unexpected language change") }
        override suspend fun updateDisplayPreferences(transform: (DisplayPreferences) -> DisplayPreferences): DisplayPreferences {
            gate?.await()
            if (fail) throw IOException("Unavailable")
            writes++
            val updated = transform(preferences.value.displayPreferences)
            preferences.value = preferences.value.copy(displayPreferences = updated)
            return updated
        }
    }
}
