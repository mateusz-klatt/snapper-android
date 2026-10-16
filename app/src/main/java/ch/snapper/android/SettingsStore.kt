package ch.snapper.android

import android.content.Context
import ch.snapper.android.data.SupportedLanguages
import ch.snapper.android.data.DisplayPreferences
import ch.snapper.android.data.DisplayCountries
import ch.snapper.android.data.FinancialColorPreference
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

data class ClientPreferences(val backendUrl: String?, val language: String = "", val displayPreferences: DisplayPreferences = DisplayPreferences())

interface SettingsStore {
    val preferences: Flow<ClientPreferences>
    suspend fun setBackendUrl(origin: String)
    suspend fun updateDisplayPreferences(transform: (DisplayPreferences) -> DisplayPreferences): DisplayPreferences
    suspend fun setLanguage(language: String)
}

private val Context.settingsDataStore by preferencesDataStore(name = "snapper_settings")

class DataStoreSettings internal constructor(private val store: DataStore<Preferences>) : SettingsStore {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)
    private val backendKey = stringPreferencesKey("backend_origin")
    private val languageKey = stringPreferencesKey("language")

    private val countryKey = stringPreferencesKey("display_country")
    private val financialColorKey = stringPreferencesKey("financial_color")

    override val preferences: Flow<ClientPreferences> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { ClientPreferences(it[backendKey], it[languageKey].orEmpty().takeIf { tag -> tag.isEmpty() || SupportedLanguages.find(tag) != null }.orEmpty(), displayPreferences(it)) }

    private fun displayPreferences(values: Preferences): DisplayPreferences = DisplayPreferences(
        countryCode = values[countryKey].orEmpty().takeIf { it.isEmpty() || DisplayCountries.find(it) != null }.orEmpty(),
        financialColor = values[financialColorKey]?.let(FinancialColorPreference::parse) ?: FinancialColorPreference.AUTO,
    )

    override suspend fun updateDisplayPreferences(transform: (DisplayPreferences) -> DisplayPreferences): DisplayPreferences {
        val committed = store.edit { values ->
            val updated = transform(displayPreferences(values))
            values[countryKey] = updated.countryCode
            values[financialColorKey] = updated.financialColor.wireValue
        }
        return displayPreferences(committed)
    }

    override suspend fun setBackendUrl(origin: String) {
        store.edit { it[backendKey] = origin }
    }

    override suspend fun setLanguage(language: String) {
        require(language.isEmpty() || SupportedLanguages.find(language) != null)
        store.edit { it[languageKey] = language }
    }
}
