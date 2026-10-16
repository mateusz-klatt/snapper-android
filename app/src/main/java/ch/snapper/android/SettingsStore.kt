package ch.snapper.android

import android.content.Context
import ch.snapper.android.data.SupportedLanguages
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

data class ClientPreferences(val backendUrl: String?, val language: String = "")

interface SettingsStore {
    val preferences: Flow<ClientPreferences>
    suspend fun setBackendUrl(origin: String)
    suspend fun setLanguage(language: String)
}

private val Context.settingsDataStore by preferencesDataStore(name = "snapper_settings")

class DataStoreSettings internal constructor(private val store: DataStore<Preferences>) : SettingsStore {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)
    private val backendKey = stringPreferencesKey("backend_origin")
    private val languageKey = stringPreferencesKey("language")

    override val preferences: Flow<ClientPreferences> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { ClientPreferences(it[backendKey], it[languageKey].orEmpty().takeIf { tag -> tag.isEmpty() || SupportedLanguages.find(tag) != null }.orEmpty()) }

    override suspend fun setBackendUrl(origin: String) {
        store.edit { it[backendKey] = origin }
    }

    override suspend fun setLanguage(language: String) {
        require(language.isEmpty() || SupportedLanguages.find(language) != null)
        store.edit { it[languageKey] = language }
    }
}
