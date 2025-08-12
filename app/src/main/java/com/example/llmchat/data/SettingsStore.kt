package com.example.llmchat.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.llmchat.net.LlmSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "llm_settings")

class SettingsStore(private val context: Context) {
    companion object {
        private val KEY_PROVIDER = intPreferencesKey("provider")
        private val KEY_BASE_URL = stringPreferencesKey("base_url")
        private val KEY_API_KEY = stringPreferencesKey("api_key")
        private val KEY_MODEL = stringPreferencesKey("model")

        private val KEY_FORCE_JSON = booleanPreferencesKey("force_json_schema") // NEW

    }

    val flow: Flow<LlmSettings> = context.dataStore.data.map { p ->
        LlmSettings(
            provider = LlmSettings.Provider.values().getOrElse(p[KEY_PROVIDER] ?: 0) { LlmSettings.Provider.OpenAI },
            baseUrl = p[KEY_BASE_URL] ?: "https://api.openai.com",
            apiKey = p[KEY_API_KEY] ?: "",
            model = p[KEY_MODEL] ?: "gpt-4o-mini",
            forceJsonSchema = p[KEY_FORCE_JSON] ?: true // NEW (default = true)
        )
    }

    suspend fun save(s: LlmSettings) {
        context.dataStore.edit { e ->
            e[KEY_PROVIDER] = s.provider.ordinal
            e[KEY_BASE_URL] = s.baseUrl
            e[KEY_API_KEY] = s.apiKey
            e[KEY_MODEL] = s.model
            e[KEY_FORCE_JSON] = s.forceJsonSchema // NEW
        }
    }
}
