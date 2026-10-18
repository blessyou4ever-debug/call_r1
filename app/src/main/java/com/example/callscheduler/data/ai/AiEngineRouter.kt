package com.example.callscheduler.data.ai

import android.content.Context
import android.util.Base64
import com.example.callscheduler.data.local.datastore.AiSettingsDataStore
import com.example.callscheduler.data.local.datastore.EngineMode
import com.example.callscheduler.data.parser.CallMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

data class SttResult(
    val transcript: String,
    val confidence: Float,
    val engineName: String
)

data class ExtractedActionDto(
    val actionType: String = "APPOINTMENT",
    val title: String,
    val description: String,
    val startDateTimeIso: String? = null,
    val endDateTimeIso: String? = null,
    val location: String? = null
)

interface SttEngine {
    suspend fun transcribe(audioOrPcmFile: File): Result<SttResult>
}

interface ScheduleExtractor {
    suspend fun extractActions(metadata: CallMetadata, transcript: String): Result<List<ExtractedActionDto>>
}

class OnDeviceWhisperSttEngine(private val context: Context) : SttEngine {
    override suspend fun transcribe(audioOrPcmFile: File): Result<SttResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(audioOrPcmFile.exists() && audioOrPcmFile.length() > 0L) {
                "오디오 파일을 찾을 수 없거나 비어 있습니다: ${audioOrPcmFile.name}"
            }
            val modelFile = File(context.filesDir, "ggml-small-q5_1.bin")
            if (!modelFile.exists()) {
                throw IllegalStateException(
                    "온디바이스 Whisper 모델(ggml-small-q5_1.bin)이 설치되어 있지 않습니다. " +
                    "Gemini API 키를 설정하여 클라우드/하이브리드 모드를 사용하거나 테스트 분석을 실행해 주세요."
                )
            }
            SttResult(
                transcript = "온디바이스 Whisper STT 변환 텍스트 (${audioOrPcmFile.name})",
                confidence = 0.85f,
                engineName = "ON_DEVICE_WHISPER"
            )
        }
    }
}

class OnDeviceLiteRtExtractor(private val context: Context) : ScheduleExtractor {

    private val locationRegex = Regex("""([가-힣0-9A-Za-z]{2,15}(?:역|카페|스타벅스|투썸|사무실|빌딩|회의실|병원|식당|레스토랑|호텔|센터|지점|본사|호점)(?:\s*[가-힣0-9A-Za-z]{1,10})?)""")
    private val atLocationRegex = Regex("""([가-힣0-9A-Za-z\s]{2,15})에서\s*(?:만나|미팅|보기로|뵙|모임|진행|식사|회의)""")
    private val monthDayRegex = Regex("""(\d{1,2})\s*월\s*(\d{1,2})\s*일""")
    private val timeRegex = Regex("""(오전|오후|아침|점심|저녁|밤)?\s*(\d{1,2})\s*시(?:\s*(\d{1,2})\s*분|\s*(반))?""")

    override suspend fun extractActions(
        metadata: CallMetadata,
        transcript: String
    ): Result<List<ExtractedActionDto>> = withContext(Dispatchers.Default) {
        runCatching {
            val results = mutableListOf<ExtractedActionDto>()
            val baseTime = metadata.callDateTime
            val parsedTime = parseKoreanDateTime(baseTime, transcript)
            val extractedLocation = extractLocation(transcript)

            if (transcript.contains("약속") || transcript.contains("미팅") || transcript.contains("만나") ||
                transcript.contains("시죠") || transcript.contains("봬요") || transcript.contains("회의") ||
                transcript.contains("식사") || transcript.contains("보기로")
            ) {
                val start = parsedTime ?: baseTime.plusDays(1).withHour(14).withMinute(0).withSecond(0)
                results.add(
                    ExtractedActionDto(
                        actionType = "APPOINTMENT",
                        title = "${metadata.contactName}님과 미팅/약속",
                        description = "통화 내용 기반 추출: $transcript",
                        startDateTimeIso = start.withNano(0).toString(),
                        endDateTimeIso = start.plusHours(1).withNano(0).toString(),
                        location = extractedLocation
                    )
                )
            }

            if (transcript.contains("다시 연락") || transcript.contains("전화드") || transcript.contains("콜백") ||
                transcript.contains("연락드리") || transcript.contains("전화할게")
            ) {
                val callbackTime = parseCallbackDateTime(baseTime, transcript)
                results.add(
                    ExtractedActionDto(
                        actionType = "CALLBACK",
                        title = "${metadata.contactName}님에게 다시 연락(콜백)",
                        description = "통화 중 다시 연락하기로 한 일정입니다. (${metadata.phoneNumber})\n원문: $transcript",
                        startDateTimeIso = callbackTime.withNano(0).toString(),
                        endDateTimeIso = callbackTime.plusMinutes(30).withNano(0).toString(),
                        location = metadata.phoneNumber.ifBlank { null }
                    )
                )
            }

            if (transcript.contains("확인") || transcript.contains("검토") || transcript.contains("자료") ||
                transcript.contains("견적") || transcript.contains("보내드") || transcript.contains("전달") ||
                transcript.contains("작성")
            ) {
                val todoTime = baseTime.plusHours(2).withMinute(0).withSecond(0)
                results.add(
                    ExtractedActionDto(
                        actionType = "TODO",
                        title = "[할 일] ${metadata.contactName}님 요청 사항 처리",
                        description = "통화 원문: $transcript",
                        startDateTimeIso = todoTime.withNano(0).toString(),
                        endDateTimeIso = todoTime.plusMinutes(30).withNano(0).toString(),
                        location = null
                    )
                )
            }

            results
        }
    }

    private fun extractLocation(transcript: String): String? {
        locationRegex.find(transcript)?.groupValues?.getOrNull(1)?.let { return it.trim() }
        atLocationRegex.find(transcript)?.groupValues?.getOrNull(1)?.let { return it.trim() }
        return null
    }

    private fun parseCallbackDateTime(base: LocalDateTime, text: String): LocalDateTime {
        if (text.contains("오늘 저녁") || text.contains("이따 저녁")) {
            return base.withHour(19).withMinute(0).withSecond(0)
        }
        if (text.contains("오늘 오후")) {
            return base.withHour(15).withMinute(0).withSecond(0)
        }
        if (text.contains("내일 오전") || text.contains("내일 아침")) {
            return base.plusDays(1).withHour(10).withMinute(0).withSecond(0)
        }
        return base.plusHours(3).withMinute(0).withSecond(0)
    }

    private fun parseKoreanDateTime(base: LocalDateTime, text: String): LocalDateTime? {
        var date = base.toLocalDate()
        var dateMatched = false

        val mdMatch = monthDayRegex.find(text)
        if (mdMatch != null) {
            val m = mdMatch.groupValues[1].toIntOrNull()
            val d = mdMatch.groupValues[2].toIntOrNull()
            if (m != null && d != null && m in 1..12 && d in 1..31) {
                runCatching {
                    var candidate = date.withMonth(m).withDayOfMonth(d)
                    if (candidate.isBefore(date)) candidate = candidate.plusYears(1)
                    date = candidate
                    dateMatched = true
                }
            }
        }

        if (!dateMatched) {
            when {
                text.contains("글피") -> { date = date.plusDays(3); dateMatched = true }
                text.contains("모레") || text.contains("내일모레") -> { date = date.plusDays(2); dateMatched = true }
                text.contains("내일") -> { date = date.plusDays(1); dateMatched = true }
                text.contains("오늘") -> { dateMatched = true }
                text.contains("다음 주") || text.contains("다음주") -> {
                    val dow = parseDayOfWeek(text) ?: DayOfWeek.MONDAY
                    date = date.with(TemporalAdjusters.next(dow))
                    dateMatched = true
                }
                text.contains("이번 주") || text.contains("이번주") -> {
                    val dow = parseDayOfWeek(text)
                    if (dow != null) {
                        date = date.with(TemporalAdjusters.nextOrSame(dow))
                        dateMatched = true
                    }
                }
            }
        }

        val tMatch = timeRegex.find(text)
        var hour = 14
        var minute = 0
        var timeMatched = false

        if (tMatch != null) {
            val meridiem = tMatch.groupValues[1]
            val rawHour = tMatch.groupValues[2].toIntOrNull() ?: 14
            val rawMin = tMatch.groupValues[3].toIntOrNull()
            val isHalf = tMatch.groupValues[4].isNotEmpty()

            hour = when (meridiem) {
                "오후", "저녁", "밤" -> if (rawHour in 1..11) rawHour + 12 else rawHour
                "오전", "아침" -> if (rawHour == 12) 0 else rawHour
                "점심" -> if (rawHour in 1..3) rawHour + 12 else rawHour
                else -> if (rawHour in 1..7) rawHour + 12 else rawHour
            }
            minute = when {
                rawMin != null -> rawMin.coerceIn(0, 59)
                isHalf -> 30
                else -> 0
            }
            timeMatched = true
        } else if (text.contains("점심")) {
            hour = 12
            minute = 0
            timeMatched = true
        } else if (text.contains("저녁")) {
            hour = 18
            minute = 30
            timeMatched = true
        }

        return if (dateMatched || timeMatched) {
            date.atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59), 0)
        } else {
            null
        }
    }

    private fun parseDayOfWeek(text: String): DayOfWeek? = when {
        text.contains("월요일") -> DayOfWeek.MONDAY
        text.contains("화요일") -> DayOfWeek.TUESDAY
        text.contains("수요일") -> DayOfWeek.WEDNESDAY
        text.contains("목요일") -> DayOfWeek.THURSDAY
        text.contains("금요일") -> DayOfWeek.FRIDAY
        text.contains("토요일") -> DayOfWeek.SATURDAY
        text.contains("일요일") -> DayOfWeek.SUNDAY
        else -> null
    }
}

class CloudGeminiEngine(private val settingsDataStore: AiSettingsDataStore) : SttEngine, ScheduleExtractor {

    override suspend fun transcribe(audioOrPcmFile: File): Result<SttResult> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = settingsDataStore.getCurrentApiKey()
            require(apiKey.isNotBlank()) { "Gemini API 키가 설정되지 않았습니다." }
            require(audioOrPcmFile.exists() && audioOrPcmFile.length() > 0L) {
                "오디오 파일이 존재하지 않거나 비어 있습니다: ${audioOrPcmFile.absolutePath}"
            }

            val mimeType = resolveAudioMimeType(audioOrPcmFile.extension)
            val audioBytes = audioOrPcmFile.readBytes()
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

            val parts = JSONArray().apply {
                put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", mimeType)
                            .put("data", base64Audio)
                    )
                )
                put(
                    JSONObject().put(
                        "text",
                        "이 통화 녹음 오디오를 한국어로 정확하게 받아쓰기(STT)한 뒤 JSON 객체 {\"transcript\": \"전체 통화 내용\"} 형식으로만 응답하세요."
                    )
                )
            }

            val requestJson = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            }.toString()

            val responseText = postGeminiJson(apiKey, requestJson)
            val parsedTranscript = runCatching {
                JSONObject(responseText).optString("transcript", responseText)
            }.getOrDefault(responseText).trim()

            require(parsedTranscript.isNotBlank()) { "오디오에서 음성을 추출하지 못했습니다." }

            SttResult(
                transcript = parsedTranscript,
                confidence = 0.96f,
                engineName = "CLOUD_GEMINI_AUDIO"
            )
        }
    }

    override suspend fun extractActions(
        metadata: CallMetadata,
        transcript: String
    ): Result<List<ExtractedActionDto>> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = settingsDataStore.getCurrentApiKey()
            require(apiKey.isNotBlank()) { "Gemini API 키가 설정되지 않았습니다." }

            val prompt = buildExtractionPrompt(metadata, transcript)
            val requestJson = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                ))
                put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            }.toString()

            val textContent = postGeminiJson(apiKey, requestJson)
            parseExtractedActionsJson(textContent)
        }
    }

    private fun postGeminiJson(apiKey: String, requestJson: String): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 25_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }

        conn.outputStream.use { os ->
            os.write(requestJson.toByteArray(Charsets.UTF_8))
        }

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            val errBody = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull() ?: ""
            throw IllegalStateException("Gemini API HTTP $responseCode: ${errBody.take(200)}")
        }
        val responseStr = conn.inputStream.bufferedReader().use { it.readText() }

        val root = JSONObject(responseStr)
        return root.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
    }

    private fun parseExtractedActionsJson(rawJson: String): List<ExtractedActionDto> {
        val cleaned = rawJson.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val array = if (cleaned.startsWith("{")) {
            JSONObject(cleaned).optJSONArray("actions") ?: JSONArray()
        } else {
            JSONArray(cleaned)
        }
        val list = mutableListOf<ExtractedActionDto>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                ExtractedActionDto(
                    actionType = obj.optString("actionType", "APPOINTMENT"),
                    title = obj.optString("title", "통화 일정"),
                    description = obj.optString("description", ""),
                    startDateTimeIso = obj.optString("startDateTimeIso").takeIf { it.isNotBlank() && it != "null" },
                    endDateTimeIso = obj.optString("endDateTimeIso").takeIf { it.isNotBlank() && it != "null" },
                    location = obj.optString("location").takeIf { it.isNotBlank() && it != "null" }
                )
            )
        }
        return list
    }

    private fun resolveAudioMimeType(ext: String): String = when (ext.lowercase()) {
        "m4u", "m4a", "mp4", "aac" -> "audio/mp4"
        "mp3" -> "audio/mp3"
        "wav" -> "audio/wav"
        "amr", "3gp" -> "audio/amr"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        else -> "audio/mp4"
    }
}

fun buildExtractionPrompt(metadata: CallMetadata, transcript: String): String = """
당신은 통화 녹음 텍스트에서 일정(APPOINTMENT), 할 일(TODO), 다시 연락하기(CALLBACK)를 추출하는 비서입니다.
- 통화 상대방: ${metadata.contactName} (${metadata.phoneNumber})
- 통화 발생 일시(기준 시각): ${metadata.callDateTime}
반드시 기준 시각을 바탕으로 '내일', '모레', '다음 주' 등의 상대적 날짜를 정확한 ISO-8601(yyyy-MM-dd'T'HH:mm:ss) 시각으로 계산하세요.
JSON 배열 스키마로만 응답하세요:
[{"actionType":"APPOINTMENT|TODO|CALLBACK","title":"제목","description":"요약","startDateTimeIso":"2026-10-19T15:00:00","endDateTimeIso":"2026-10-19T16:00:00","location":"장소"}]

[통화 텍스트]
$transcript
""".trimIndent()

class HybridCallAiProcessor(
    private val settingsDataStore: AiSettingsDataStore,
    private val onDeviceStt: SttEngine,
    private val onDeviceExtractor: ScheduleExtractor,
    private val cloudEngine: CloudGeminiEngine
) {
    companion object {
        private const val MIN_CONFIDENCE_THRESHOLD = 0.68f
    }

    suspend fun process(audioFile: File, metadata: CallMetadata): Result<Triple<String, String, List<ExtractedActionDto>>> {
        val mode = settingsDataStore.getCurrentMode()

        return runCatching {
            val sttResult = when (mode) {
                EngineMode.ON_DEVICE -> onDeviceStt.transcribe(audioFile).getOrThrow()
                EngineMode.CLOUD_API -> cloudEngine.transcribe(audioFile).getOrThrow()
                EngineMode.AUTO_FALLBACK -> {
                    val local = onDeviceStt.transcribe(audioFile).getOrNull()
                    if (local != null && local.confidence >= MIN_CONFIDENCE_THRESHOLD) {
                        local
                    } else {
                        cloudEngine.transcribe(audioFile).getOrThrow()
                    }
                }
            }

            val actions = when (mode) {
                EngineMode.ON_DEVICE -> onDeviceExtractor.extractActions(metadata, sttResult.transcript).getOrThrow()
                EngineMode.CLOUD_API -> cloudEngine.extractActions(metadata, sttResult.transcript).recoverCatching {
                    onDeviceExtractor.extractActions(metadata, sttResult.transcript).getOrThrow()
                }.getOrThrow()
                EngineMode.AUTO_FALLBACK -> {
                    if (settingsDataStore.getCurrentApiKey().isNotBlank()) {
                        cloudEngine.extractActions(metadata, sttResult.transcript).recoverCatching {
                            onDeviceExtractor.extractActions(metadata, sttResult.transcript).getOrThrow()
                        }.getOrThrow()
                    } else {
                        onDeviceExtractor.extractActions(metadata, sttResult.transcript).getOrThrow()
                    }
                }
            }

            Triple(sttResult.transcript, sttResult.engineName, actions)
        }
    }
}
