package com.example.callscheduler.data.local.datastore

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

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
        private const val KEY_PREFERRED_MODEL = "preferred_gemini_model"

        private val AUTO_BACKUP_FILES = listOf(
            "/storage/emulated/0/Download/.callscheduler_settings_backup.json",
            "/storage/emulated/0/Recordings/.callscheduler_settings_backup.json",
            "/storage/emulated/0/Recordings/Call/.callscheduler_settings_backup.json",
            "/storage/emulated/0/Recordings/TPhoneCallRecords/.callscheduler_settings_backup.json"
        )

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
        private val preferredModelState = MutableStateFlow("gemini-3.8-flash")
        private var initialized = false
    }

    init {
        if (!initialized) {
            var savedMode = prefs.getString(KEY_ENGINE_MODE, null)
            var savedKey = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
            var savedDir = prefs.getString(KEY_CUSTOM_CALL_DIR, "") ?: ""
            val savedModel = prefs.getString(KEY_PREFERRED_MODEL, "gemini-3.8-flash") ?: "gemini-3.8-flash"

            // 앱 삭제 후 재설치되어 SharedPreferences가 비어있을 경우 외장 백업 파일에서 자동 복원 시도
            if (savedKey.isBlank() && savedDir.isBlank() && savedMode == null) {
                val restored = tryRestoreFromAutoBackupFile()
                if (restored != null) {
                    savedMode = restored.first.name
                    savedKey = restored.second
                    savedDir = restored.third
                    prefs.edit()
                        .putString(KEY_ENGINE_MODE, savedMode)
                        .putString(KEY_GEMINI_API_KEY, savedKey)
                        .putString(KEY_CUSTOM_CALL_DIR, savedDir)
                        .apply()
                }
            }

            val finalModeName = savedMode ?: EngineMode.AUTO_FALLBACK.name
            modeState.value = runCatching { EngineMode.valueOf(finalModeName) }.getOrDefault(EngineMode.AUTO_FALLBACK)
            apiKeyState.value = savedKey
            customCallDirState.value = savedDir
            preferredModelState.value = savedModel.ifBlank { "gemini-3.8-flash" }
            initialized = true
        }
    }

    val engineModeFlow: Flow<EngineMode> = modeState
    val apiKeyFlow: Flow<String> = apiKeyState
    val customCallDirFlow: Flow<String> = customCallDirState
    val preferredModelFlow: Flow<String> = preferredModelState

    fun getCurrentMode(): EngineMode = modeState.value
    fun getCurrentApiKey(): String = apiKeyState.value
    fun getCustomCallDir(): String = customCallDirState.value
    fun getPreferredModel(): String = preferredModelState.value

    fun setPreferredModelSync(modelName: String) {
        val clean = modelName.removePrefix("models/").trim()
        if (clean.isNotBlank()) {
            prefs.edit().putString(KEY_PREFERRED_MODEL, clean).apply()
            preferredModelState.value = clean
        }
    }

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
        writeAutoBackupFile()
    }

    suspend fun setGeminiApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        prefs.edit().putString(KEY_GEMINI_API_KEY, trimmed).apply()
        apiKeyState.value = trimmed
        writeAutoBackupFile()
    }

    suspend fun setCustomCallDir(dirPath: String) {
        val trimmed = dirPath.trim().removeSuffix("/")
        prefs.edit().putString(KEY_CUSTOM_CALL_DIR, trimmed).apply()
        customCallDirState.value = trimmed
        writeAutoBackupFile()
    }

    fun exportSettingsJson(): String {
        return JSONObject().apply {
            put("engine_mode", modeState.value.name)
            put("gemini_api_key", apiKeyState.value)
            put("custom_call_dir", customCallDirState.value)
            put("preferred_gemini_model", preferredModelState.value)
        }.toString()
    }

    suspend fun importSettingsJson(jsonStr: String): Boolean {
        return runCatching {
            val obj = JSONObject(jsonStr.trim())
            val modeName = obj.optString("engine_mode", EngineMode.AUTO_FALLBACK.name)
            val mode = runCatching { EngineMode.valueOf(modeName) }.getOrDefault(EngineMode.AUTO_FALLBACK)
            val key = obj.optString("gemini_api_key", "").trim()
            val dir = obj.optString("custom_call_dir", "").trim().removeSuffix("/")
            val model = obj.optString("preferred_gemini_model", "gemini-3.8-flash").trim().ifBlank { "gemini-3.8-flash" }

            prefs.edit()
                .putString(KEY_ENGINE_MODE, mode.name)
                .putString(KEY_GEMINI_API_KEY, key)
                .putString(KEY_CUSTOM_CALL_DIR, dir)
                .putString(KEY_PREFERRED_MODEL, model)
                .apply()

            modeState.value = mode
            apiKeyState.value = key
            customCallDirState.value = dir
            preferredModelState.value = model
            writeAutoBackupFile()
            true
        }.getOrDefault(false)
    }

    private fun writeAutoBackupFile() {
        val json = exportSettingsJson()
        val targets = mutableListOf<String>()
        val custom = customCallDirState.value.trim()
        if (custom.isNotBlank()) {
            targets.add("${custom}/.callscheduler_settings_backup.json")
        }
        targets.addAll(AUTO_BACKUP_FILES)

        for (path in targets) {
            runCatching {
                val file = File(path)
                if (file.parentFile?.exists() == true) {
                    file.writeText(json, Charsets.UTF_8)
                }
            }
        }
    }

    private fun tryRestoreFromAutoBackupFile(): Triple<EngineMode, String, String>? {
        for (path in AUTO_BACKUP_FILES) {
            val file = File(path)
            if (file.exists() && file.canRead() && file.length() > 0L) {
                val parsed = runCatching {
                    val obj = JSONObject(file.readText(Charsets.UTF_8))
                    val mode = runCatching {
                        EngineMode.valueOf(obj.optString("engine_mode", EngineMode.AUTO_FALLBACK.name))
                    }.getOrDefault(EngineMode.AUTO_FALLBACK)
                    val key = obj.optString("gemini_api_key", "")
                    val dir = obj.optString("custom_call_dir", "")
                    if (key.isNotBlank() || dir.isNotBlank()) Triple(mode, key, dir) else null
                }.getOrNull()
                if (parsed != null) return parsed
            }
        }
        return null
    }
}
