package com.example.callscheduler.data.local.datastore

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

enum class EngineMode(val label: String) {
    ON_DEVICE("온디바이스 우선 (무료·보안)"),
    AUTO_FALLBACK("하이브리드 (인식률 저하 시 클라우드 전환)"),
    CLOUD_API("클라우드 API 고정 (최고 인식률)")
}

class AiSettingsDataStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ai_engine_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ENGINE_MODE = "engine_mode"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private val modeState = MutableStateFlow(EngineMode.AUTO_FALLBACK)
        private val apiKeyState = MutableStateFlow("")
        private var initialized = false
    }

    init {
        if (!initialized) {
            val savedMode = prefs.getString(KEY_ENGINE_MODE, EngineMode.AUTO_FALLBACK.name) ?: EngineMode.AUTO_FALLBACK.name
            modeState.value = runCatching { EngineMode.valueOf(savedMode) }.getOrDefault(EngineMode.AUTO_FALLBACK)
            apiKeyState.value = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
            initialized = true
        }
    }

    val engineModeFlow: Flow<EngineMode> = modeState
    val apiKeyFlow: Flow<String> = apiKeyState

    fun getCurrentMode(): EngineMode = modeState.value
    fun getCurrentApiKey(): String = apiKeyState.value

    suspend fun setEngineMode(mode: EngineMode) {
        prefs.edit().putString(KEY_ENGINE_MODE, mode.name).apply()
        modeState.value = mode
    }

    suspend fun setGeminiApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        prefs.edit().putString(KEY_GEMINI_API_KEY, trimmed).apply()
        apiKeyState.value = trimmed
    }
}
