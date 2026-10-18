package com.example.callscheduler.data.local.datastore

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

enum class EngineMode(val label: String) {
    ON_DEVICE("온디바이스 우선 (무료·보안)"),
    AUTO_FALLBACK("하이브리드 (인식률 저하 시 클라우드 전환)"),
    CLOUD_API("클라우드 API 고정 (최고 인식률)")
}

data class CallFolderPreset(
    val label: String,
    val path: String
)

class AiSettingsDataStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ai_engine_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ENGINE_MODE = "engine_mode"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_CUSTOM_CALL_DIR = "custom_call_dir"

        val DEFAULT_CALL_DIR_PRESETS = listOf(
            CallFolderPreset("삼성 기본 전화", "/storage/emulated/0/Recordings/Call"),
            CallFolderPreset("T전화·에이닷 (Recordings)", "/storage/emulated/0/Recordings/TPhoneCallRecords"),
            CallFolderPreset("T전화 (Music 폴더)", "/storage/emulated/0/Music/TPhoneCallRecords"),
            CallFolderPreset("T전화 (내장메모리 루트)", "/storage/emulated/0/TPhoneCallRecords"),
            CallFolderPreset("구형 갤럭시 Call", "/storage/emulated/0/Call")
        )

        private val modeState = MutableStateFlow(EngineMode.AUTO_FALLBACK)
        private val apiKeyState = MutableStateFlow("")
        private val customCallDirState = MutableStateFlow("")
        private var initialized = false
    }

    init {
        if (!initialized) {
            val savedMode = prefs.getString(KEY_ENGINE_MODE, EngineMode.AUTO_FALLBACK.name) ?: EngineMode.AUTO_FALLBACK.name
            modeState.value = runCatching { EngineMode.valueOf(savedMode) }.getOrDefault(EngineMode.AUTO_FALLBACK)
            apiKeyState.value = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
            customCallDirState.value = prefs.getString(KEY_CUSTOM_CALL_DIR, "") ?: ""
            initialized = true
        }
    }

    val engineModeFlow: Flow<EngineMode> = modeState
    val apiKeyFlow: Flow<String> = apiKeyState
    val customCallDirFlow: Flow<String> = customCallDirState

    fun getCurrentMode(): EngineMode = modeState.value
    fun getCurrentApiKey(): String = apiKeyState.value
    fun getCustomCallDir(): String = customCallDirState.value

    /**
     * 사용자가 지정한 경로를 최우선으로 포함하고, 삼성 기본전화 및 T전화 기본 경로들을 함께 반환합니다.
     */
    fun getActiveMonitoredDirs(): List<String> {
        val result = LinkedHashSet<String>()
        val custom = customCallDirState.value.trim()
        if (custom.isNotEmpty()) {
            result.add(custom)
        }
        DEFAULT_CALL_DIR_PRESETS.forEach { result.add(it.path) }
        result.add("/storage/emulated/0/Recordings")
        result.add("/storage/emulated/0/MIUI/sound_recorder/call_rec")
        return result.toList()
    }

    suspend fun setEngineMode(mode: EngineMode) {
        prefs.edit().putString(KEY_ENGINE_MODE, mode.name).apply()
        modeState.value = mode
    }

    suspend fun setGeminiApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        prefs.edit().putString(KEY_GEMINI_API_KEY, trimmed).apply()
        apiKeyState.value = trimmed
    }

    suspend fun setCustomCallDir(dirPath: String) {
        val trimmed = dirPath.trim().removeSuffix("/")
        prefs.edit().putString(KEY_CUSTOM_CALL_DIR, trimmed).apply()
        customCallDirState.value = trimmed
    }
}
