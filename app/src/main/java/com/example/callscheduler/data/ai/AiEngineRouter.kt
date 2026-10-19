package com.example.callscheduler.data.ai

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import com.example.callscheduler.data.local.datastore.AiSettingsDataStore
import com.example.callscheduler.data.local.datastore.EngineMode
import com.example.callscheduler.data.parser.AudioPcmDecoder
import com.example.callscheduler.data.parser.CallMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import kotlin.coroutines.resume

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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val pcmTemp = File(context.cacheDir, "decoded_${System.currentTimeMillis()}.pcm")
                val chunkFiles = mutableListOf<File>()
                try {
                    val decoded = AudioPcmDecoder.decodeToPcmInfo(audioOrPcmFile, pcmTemp).getOrThrow()
                    chunkFiles.addAll(AudioPcmDecoder.splitAndTrimSilenceIntoChunks(decoded, context.cacheDir, chunkSeconds = 8))

                    val segments = mutableListOf<String>()
                    for (chunk in chunkFiles) {
                        val part = recognizeSinglePcmChunk(
                            pcmFile = chunk,
                            sampleRate = decoded.sampleRate,
                            channelCount = decoded.channelCount
                        )
                        if (!part.isNullOrBlank()) {
                            segments.add(part.trim())
                        }
                        delay(150L)
                    }

                    val fullTranscript = segments.joinToString(" ").trim()
                    if (fullTranscript.isNotBlank()) {
                        val expectedMinChars = (decoded.durationSeconds * 2.2f).toInt().coerceAtLeast(10)
                        val completenessRatio = (fullTranscript.length.toFloat() / expectedMinChars.toFloat()).coerceIn(0.2f, 1.0f)
                        val confidence = if (fullTranscript.length < 12 && decoded.durationSeconds >= 4f) {
                            0.35f
                        } else {
                            (0.65f + 0.25f * completenessRatio).coerceAtMost(0.90f)
                        }

                        return@runCatching SttResult(
                            transcript = fullTranscript,
                            confidence = confidence,
                            engineName = "ANDROID_ON_DEVICE_STT"
                        )
                    }
                } finally {
                    chunkFiles.forEach { runCatching { it.delete() } }
                    runCatching { pcmTemp.delete() }
                }
            }

            throw IllegalStateException(
                "단말기 내장 음성 인식기가 오디오 변환을 완료하지 못했습니다. 잠시 후 [온디바이스로 재분석] 또는 [Gemini API로 재시도] 버튼을 눌러 주세요."
            )
        }
    }

    private suspend fun recognizeSinglePcmChunk(
        pcmFile: File,
        sampleRate: Int,
        channelCount: Int
    ): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return withTimeoutOrNull(18_000L) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val recognizer = runCatching {
                        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                        } else {
                            SpeechRecognizer.createSpeechRecognizer(context)
                        }
                    }.getOrNull()

                    if (recognizer == null) {
                        if (cont.isActive) cont.resume(null)
                        return@suspendCancellableCoroutine
                    }

                    val pfd = runCatching {
                        ParcelFileDescriptor.open(pcmFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    }.getOrNull()

                    if (pfd == null) {
                        runCatching { recognizer.destroy() }
                        if (cont.isActive) cont.resume(null)
                        return@suspendCancellableCoroutine
                    }

                    val collectedPieces = mutableListOf<String>()
                    var latestPartial = ""

                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}

                        override fun onPartialResults(partialResults: Bundle?) {
                            val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = list?.firstOrNull()?.trim()
                            if (!text.isNullOrBlank()) {
                                if (latestPartial.length >= 6 && text.length < latestPartial.length / 2) {
                                    collectedPieces.add(latestPartial)
                                }
                                latestPartial = text
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val finalText = list?.firstOrNull()?.trim().takeUnless { it.isNullOrBlank() } ?: latestPartial
                            if (finalText.isNotBlank()) {
                                collectedPieces.add(finalText)
                            }
                            val combined = collectedPieces.distinct().joinToString(" ").trim()
                            runCatching { pfd.close() }
                            runCatching { recognizer.destroy() }
                            if (cont.isActive) cont.resume(combined.ifBlank { null })
                        }

                        override fun onError(error: Int) {
                            if (latestPartial.isNotBlank()) {
                                collectedPieces.add(latestPartial)
                            }
                            val combined = collectedPieces.distinct().joinToString(" ").trim()
                            runCatching { pfd.close() }
                            runCatching { recognizer.destroy() }
                            if (cont.isActive) cont.resume(combined.ifBlank { null })
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })

                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 12000L)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, channelCount.coerceIn(1, 2))
                        putExtra(
                            RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,
                            if (sampleRate in listOf(8000, 16000, 22050, 24000, 44100, 48000)) sampleRate else 16000
                        )
                    }

                    cont.invokeOnCancellation {
                        runCatching { pfd.close() }
                        runCatching { recognizer.destroy() }
                    }

                    runCatching {
                        recognizer.startListening(intent)
                    }.onFailure {
                        runCatching { pfd.close() }
                        runCatching { recognizer.destroy() }
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
    }
}

class OnDeviceLiteRtExtractor(private val context: Context) : ScheduleExtractor {

    private val locationRegex = Regex("""([가-힣0-9A-Za-z]{0,15}(?:유치원|어린이집|초등학교|중학교|고등학교|학교|학원|역|카페|스타벅스|투썸|사무실|빌딩|회의실|병원|의원|치과|식당|레스토랑|호텔|센터|지점|본사|호점|공원|마트|백화점|공항|터미널)(?:\s*[가-힣0-9A-Za-z]{1,10})?)""")
    private val atLocationRegex = Regex("""([가-힣0-9A-Za-z\s]{2,15})에서\s*(?:만나|미팅|보기로|뵙|모임|진행|식사|회의|하원|등원|출발|집합|모여)""")
    private val monthDayRegex = Regex("""(\d{1,2})\s*월\s*(\d{1,2})\s*일""")
    private val dayOnlyRegex = Regex("""(?:^|\s)(\d{1,2})\s*일(?:이|에|날|\s|$)""")
    private val timeRegex = Regex("""(오전|오후|아침|점심|저녁|밤)?\s*(\d{1,2})\s*시(?:\s*(\d{1,2})\s*분|\s*(반))?""")

    private val relativeDateCleanRegex = Regex("""(?:이번\s*주|다음\s*주|내일모레|모레|내일|오늘)(?:에|에는|은|는)?\s*(?:[월화수목금토일]요일(?:에)?)?""")
    private val monthDayCleanRegex = Regex("""\d{1,2}\s*월\s*\d{1,2}\s*일(?:이|에|날|은|는)?""")
    private val dayOnlyCleanRegex = Regex("""(?:^|\s)\d{1,2}\s*일(?:이|에|날|은|는)""")
    private val timeCleanRegex = Regex("""(?:오전|오후|아침|점심|저녁|밤)?\s*\d{1,2}\s*시(?:\s*\d{1,2}\s*분|\s*반)?(?:까지|에|쯤|부터)?""")
    private val closingCleanRegex = Regex("""(?:음|네|예|응|그래|어)?\s*(?:알겠어|알겠어요|알겠습니다|알았어|알았어요|그렇게\s*해요|그렇게\s*할게|이따\s*봐|수고하세요|끊을게|고마워).*$""")
    private val endingCleanRegex = Regex("""(이거든|거든요|거든|잖아|잖아요|인데요|인데|더라고|하면\s*돼요|하면\s*돼|하면\s*됩니다|오면\s*돼요|오면\s*돼|가면\s*돼요|가면\s*돼|진행하시죠|보기로\s*했어|보기로|만나기로|만나요|시죠|봬요|할게요|드리겠습니다)""")
    private val multiSpaceRegex = Regex("""\s+""")

    private val stopWords = setOf(
        "아", "어", "음", "그", "저기", "에", "응", "네", "예", "여보", "남편", "자기야", "자기",
        "아내", "엄마", "아빠", "우리", "내가", "제가", "혹시", "일단", "근데", "그래서", "그러면",
        "그럼", "이제", "진짜", "정말", "맞다", "있잖아", "돼", "돼요", "됩니다"
    )

    private val appointmentKeywords = listOf(
        "약속", "미팅", "만나", "시죠", "봬요", "회의", "식사", "보기로",
        "하원", "등원", "데리러", "픽업", "행사", "아빠의 날", "엄마의 날", "부모참여",
        "예약", "방문", "수업", "병원", "치과", "검진", "모임", "출발", "까지 와", "까지 오", "까지 가"
    )

    override suspend fun extractActions(
        metadata: CallMetadata,
        transcript: String
    ): Result<List<ExtractedActionDto>> = withContext(Dispatchers.Default) {
        runCatching {
            val results = mutableListOf<ExtractedActionDto>()
            val baseTime = metadata.callDateTime
            val parsedTime = parseKoreanDateTime(baseTime, transcript)
            val extractedLocation = extractLocation(transcript)
            val hasAppointmentKeyword = appointmentKeywords.any { transcript.contains(it) }

            if (hasAppointmentKeyword || parsedTime != null) {
                val start = parsedTime ?: baseTime.plusDays(1).withHour(14).withMinute(0).withSecond(0)
                val inferredTitle = inferAppointmentTitle(metadata.contactName, transcript, extractedLocation)
                val conciseSummary = summarizeAppointment(
                    contactName = metadata.contactName,
                    start = start,
                    location = extractedLocation,
                    title = inferredTitle,
                    transcript = transcript
                )
                results.add(
                    ExtractedActionDto(
                        actionType = "APPOINTMENT",
                        title = inferredTitle,
                        description = conciseSummary,
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
                val callbackSummary = summarizeCallback(
                    contactName = metadata.contactName,
                    callbackTime = callbackTime,
                    transcript = transcript
                )
                results.add(
                    ExtractedActionDto(
                        actionType = "CALLBACK",
                        title = "${metadata.contactName}님 다시 연락(콜백)",
                        description = callbackSummary,
                        startDateTimeIso = callbackTime.withNano(0).toString(),
                        endDateTimeIso = callbackTime.plusMinutes(30).withNano(0).toString(),
                        location = null
                    )
                )
            }

            if (transcript.contains("확인") || transcript.contains("검토") || transcript.contains("자료") ||
                transcript.contains("견적") || transcript.contains("보내드") || transcript.contains("전달") ||
                transcript.contains("작성") || transcript.contains("챙겨") || transcript.contains("준비해")
            ) {
                val todoTime = baseTime.plusHours(2).withMinute(0).withSecond(0)
                val todoSummary = summarizeTodo(
                    contactName = metadata.contactName,
                    transcript = transcript
                )
                results.add(
                    ExtractedActionDto(
                        actionType = "TODO",
                        title = "[할 일] ${metadata.contactName}님 요청 사항",
                        description = todoSummary,
                        startDateTimeIso = todoTime.withNano(0).toString(),
                        endDateTimeIso = todoTime.plusMinutes(30).withNano(0).toString(),
                        location = null
                    )
                )
            }

            results
        }
    }

    private fun inferAppointmentTitle(contactName: String, transcript: String, location: String?): String {
        val hasFathersDay = transcript.contains("아빠의 날")
        val hasMothersDay = transcript.contains("엄마의 날")
        val hasKindergarten = transcript.contains("유치원") || transcript.contains("어린이집")
        val hasPickup = transcript.contains("하원") || transcript.contains("데리러") || transcript.contains("픽업")
        val hasDropoff = transcript.contains("등원") || transcript.contains("등공")

        return when {
            hasFathersDay && hasKindergarten && hasPickup -> "아빠의 날 유치원 하원"
            hasFathersDay && hasKindergarten && hasDropoff -> "아빠의 날 유치원 등원"
            hasFathersDay -> "아빠의 날 행사${if (!location.isNullOrBlank()) " (${location})" else ""}"
            hasMothersDay -> "엄마의 날 행사${if (!location.isNullOrBlank()) " (${location})" else ""}"
            transcript.contains("유치원") && hasPickup -> "유치원 하원"
            transcript.contains("어린이집") && hasPickup -> "어린이집 하원"
            transcript.contains("유치원") && hasDropoff -> "유치원 등원"
            transcript.contains("병원") || transcript.contains("치과") || transcript.contains("검진") -> "${location ?: "병원"} 방문 예약"
            transcript.contains("미팅") || transcript.contains("회의") -> "${contactName}님과 미팅${if (!location.isNullOrBlank()) " (${location})" else ""}"
            transcript.contains("식사") || transcript.contains("점심") || transcript.contains("저녁") -> "${contactName}님과 식사 약속"
            !location.isNullOrBlank() -> "${contactName}님과 일정 (${location})"
            else -> "${contactName}님과 약속 일정"
        }
    }

    private fun summarizeAppointment(
        contactName: String,
        start: LocalDateTime,
        location: String?,
        title: String,
        transcript: String
    ): String {
        val dateTimeLabel = formatKoreanFriendlyDateTime(start)
        val coreTopic = extractCleanedCoreTopic(transcript, title)
        val locPart = if (!location.isNullOrBlank() && !coreTopic.contains(location)) " ${location}에서" else ""
        return "${contactName}님과 통화 중 ${dateTimeLabel}${locPart} ${coreTopic} 일정 확인"
            .replace(multiSpaceRegex, " ")
            .trim()
    }

    private fun summarizeCallback(
        contactName: String,
        callbackTime: LocalDateTime,
        transcript: String
    ): String {
        val dateTimeLabel = formatKoreanFriendlyDateTime(callbackTime)
        val coreTopic = extractCleanedCoreTopic(transcript, "논의 사항")
        return "${contactName}님과 통화 중 ${dateTimeLabel} ${coreTopic} 관련 다시 연락하기로 확인"
            .replace(multiSpaceRegex, " ")
            .trim()
    }

    private fun summarizeTodo(
        contactName: String,
        transcript: String
    ): String {
        val coreTopic = extractCleanedCoreTopic(transcript, "요청 사항")
        return "${contactName}님과 통화 중 ${coreTopic} 처리 및 확인 필요"
            .replace(multiSpaceRegex, " ")
            .trim()
    }

    private fun formatKoreanFriendlyDateTime(dt: LocalDateTime): String {
        val meridiem = if (dt.hour < 12) "오전" else "오후"
        val displayHour = when {
            dt.hour == 0 -> 12
            dt.hour > 12 -> dt.hour - 12
            else -> dt.hour
        }
        val minPart = if (dt.minute > 0) " ${dt.minute}분" else ""
        return "${dt.monthValue}월 ${dt.dayOfMonth}일 ${meridiem} ${displayHour}시${minPart}"
    }

    private fun extractCleanedCoreTopic(transcript: String, fallbackTitle: String): String {
        var text = transcript
            .replace(relativeDateCleanRegex, " ")
            .replace(monthDayCleanRegex, " ")
            .replace(dayOnlyCleanRegex, " ")
            .replace(timeCleanRegex, " ")
            .replace(closingCleanRegex, "")
            .replace("하원에서 등공하면", "하원")
            .replace("하원에서 등원하면", "하원")
            .replace("등공하면", "등원")
            .replace(endingCleanRegex, "")
            .replace("하원에서", "하원")
            .replace("등원에서", "등원")
            .trim()

        val tokens = text.split(multiSpaceRegex)
        val deduped = mutableListOf<String>()
        for (rawTok in tokens) {
            val tok = rawTok.trim()
            if (tok.isEmpty() || tok in stopWords) continue
            if (deduped.lastOrNull() != tok) {
                deduped.add(tok)
            }
        }
        text = deduped.joinToString(" ")
            .removeSuffix("에서")
            .removeSuffix("으로")
            .removeSuffix("로")
            .trim()

        if (text.length > 32) {
            text = text.take(32).trim()
        }

        return if (text.length >= 3) text else fallbackTitle
    }

    private fun cleanPlaceName(raw: String): String {
        return raw.trim()
            .removeSuffix("에서")
            .removeSuffix("으로")
            .removeSuffix("로")
            .removeSuffix("까지")
            .removeSuffix("하원")
            .removeSuffix("등원")
            .removeSuffix("등공")
            .trim()
    }

    private fun extractLocation(transcript: String): String? {
        locationRegex.find(transcript)?.groupValues?.getOrNull(1)?.let {
            val cleaned = cleanPlaceName(it)
            if (cleaned.isNotBlank()) return cleaned
        }
        atLocationRegex.find(transcript)?.groupValues?.getOrNull(1)?.let {
            val cleaned = cleanPlaceName(it)
            if (cleaned.isNotBlank()) return cleaned
        }
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
            val dMatch = dayOnlyRegex.find(text)
            if (dMatch != null) {
                val d = dMatch.groupValues[1].toIntOrNull()
                if (d != null && d in 1..31) {
                    runCatching {
                        var candidate = date.withDayOfMonth(d.coerceAtMost(date.lengthOfMonth()))
                        if (candidate.isBefore(date)) {
                            val nextMonth = date.plusMonths(1)
                            candidate = nextMonth.withDayOfMonth(d.coerceAtMost(nextMonth.lengthOfMonth()))
                        }
                        date = candidate
                        dateMatched = true
                    }
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
                else -> if (rawHour in 1..8) rawHour + 12 else rawHour
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

    companion object {
        private val CANDIDATE_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
            "gemini-1.5-flash",
            "gemini-3-flash-preview",
            "gemini-3.1-flash-lite-preview"
        )
        private val SUGGESTED_MODEL_REGEX = Regex("""models/([a-zA-Z0-9._-]+)""")
    }

    private fun isAudioCapableModelName(name: String): Boolean {
        val lower = name.lowercase()
        if (!lower.contains("gemini") || !lower.contains("flash")) return false
        val unsupportedKeywords = listOf("tts", "image", "vision", "embedding", "aqa", "bisect", "thinking")
        return unsupportedKeywords.none { lower.contains(it) }
    }

    /**
     * 사용자가 설정 화면에서 [API 키 · 모델 연결 진단] 버튼을 눌렀을 때
     * 실제 Google 서버에 모델 목록 조회 및 경량 테스트 호출을 수행하여 정확한 상태나 오류 원인을 반환합니다.
     */
    suspend fun diagnoseApiConnection(apiKeyOverride: String? = null): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = (apiKeyOverride ?: settingsDataStore.getCurrentApiKey()).trim()
            if (apiKey.isBlank()) {
                return@runCatching "❌ Gemini API 키가 비어 있습니다. 먼저 API 키를 입력하고 저장해 주세요."
            }

            val listUrl = URL("https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey}")
            val listConn = (listUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 12_000
            }
            val listCode = listConn.responseCode
            if (listCode !in 200..299) {
                val errRaw = runCatching { listConn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull() ?: ""
                val cleanErr = extractCleanGoogleErrorMessage(errRaw)
                return@runCatching "❌ API 키 인증 또는 모델 목록 조회 실패 (HTTP ${listCode})\n• 원인: ${cleanErr}"
            }

            val body = listConn.inputStream.bufferedReader().use { it.readText() }
            val modelsJson = JSONObject(body).optJSONArray("models") ?: JSONArray()
            val audioModels = mutableListOf<String>()
            for (i in 0 until modelsJson.length()) {
                val m = modelsJson.getJSONObject(i)
                val name = m.optString("name", "").removePrefix("models/")
                val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                if (isAudioCapableModelName(name) && methods.contains("generateContent")) {
                    audioModels.add(name)
                }
            }

            val primaryModel = audioModels.firstOrNull { it == "gemini-2.5-flash" }
                ?: audioModels.firstOrNull()
                ?: "gemini-2.5-flash"

            val testPayload = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", "연결 테스트입니다. OK 라고만 답하세요."))
                        )
                    )
                )
            }.toString()

            return@runCatching try {
                val reply = callSingleModel(apiKey, primaryModel, testPayload).trim()
                "✅ Gemini API 정상 연결 확인!\n" +
                    "• 대표 활성 모델: ${primaryModel}\n" +
                    "• 테스트 응답: ${reply.take(40)}\n" +
                    "• 계정의 오디오 지원 Flash 모델(${audioModels.size}개): ${audioModels.take(5).joinToString(", ")}"
            } catch (e: Exception) {
                "⚠️ 모델 목록 조회는 성공했으나 실제 생성 호출에서 오류가 발생했습니다.\n" +
                    "• 시도 모델: ${primaryModel}\n" +
                    "• 상세 오류: ${e.message}"
            }
        }
    }

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

            val firstAttempt = runCatching {
                requestAudioTranscription(apiKey, mimeType, base64Audio, useJsonMimeConfig = true)
            }

            val responseText = if (firstAttempt.isSuccess) {
                firstAttempt.getOrThrow()
            } else {
                val firstErr = firstAttempt.exceptionOrNull()
                val errMsg = firstErr?.message ?: ""
                // 만약 .m4u / 특이 오디오 포맷 거부(HTTP 400) 또는 JSON MIME 충돌이면 표준 WAV(audio/wav)로 디코딩하여 재시도
                if (errMsg.contains("HTTP 400") || errMsg.contains("INVALID_ARGUMENT") || errMsg.contains("format")) {
                    val wavAttempt = AudioPcmDecoder.transcodeToWavBytes(audioOrPcmFile)
                    if (wavAttempt.isSuccess) {
                        val wavBase64 = Base64.encodeToString(wavAttempt.getOrThrow(), Base64.NO_WRAP)
                        requestAudioTranscription(apiKey, "audio/wav", wavBase64, useJsonMimeConfig = false)
                    } else {
                        requestAudioTranscription(apiKey, mimeType, base64Audio, useJsonMimeConfig = false)
                    }
                } else {
                    throw firstErr ?: IllegalStateException("Gemini 오디오 STT 호출 실패")
                }
            }

            val parsedTranscript = unwrapTranscriptJson(responseText)
            require(parsedTranscript.isNotBlank()) { "Gemini API가 빈 텍스트를 반환했습니다." }

            SttResult(
                transcript = parsedTranscript,
                confidence = 0.96f,
                engineName = "CLOUD_GEMINI_AUDIO"
            )
        }
    }

    private suspend fun requestAudioTranscription(
        apiKey: String,
        mimeType: String,
        base64Audio: String,
        useJsonMimeConfig: Boolean
    ): String {
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
                    "이 통화 녹음 오디오를 한국어로 빠짐없이 받아쓰기(STT)한 뒤 JSON 객체 {\"transcript\": \"전체 통화 내용\"} 형식으로만 응답하세요."
                )
            )
        }

        val requestJson = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            if (useJsonMimeConfig) {
                put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            }
        }.toString()

        return postGeminiJsonWithFallback(apiKey, requestJson)
    }

    private fun unwrapTranscriptJson(raw: String): String {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return runCatching {
            when {
                cleaned.startsWith("[") -> {
                    val arr = JSONArray(cleaned)
                    val sb = StringBuilder()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i)
                        val t = item?.optString("transcript") ?: arr.optString(i)
                        if (!t.isNullOrBlank()) {
                            if (sb.isNotEmpty()) sb.append(" ")
                            sb.append(t.trim())
                        }
                    }
                    sb.toString().ifBlank { cleaned }
                }
                cleaned.startsWith("{") -> {
                    JSONObject(cleaned).optString("transcript", cleaned).trim()
                }
                else -> cleaned
            }
        }.getOrDefault(cleaned).trim()
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

            val textContent = postGeminiJsonWithFallback(apiKey, requestJson)
            parseExtractedActionsJson(textContent)
        }
    }

    private suspend fun postGeminiJsonWithFallback(apiKey: String, requestJson: String): String {
        // 1단계: 현재 API 키로 사용 가능한 실제 Flash 모델 목록을 먼저 빠르게 조회하여 폐기된 모델(404) 호출을 방지
        val discovered = discoverAvailableFlashModels(apiKey)
        val orderedModels = LinkedHashSet<String>()
        if (discovered.isNotEmpty()) {
            // 검증된 정식 모델을 우선 배치
            CANDIDATE_MODELS.filter { it in discovered }.forEach { orderedModels.add(it) }
            discovered.forEach { orderedModels.add(it) }
        } else {
            orderedModels.addAll(CANDIDATE_MODELS)
        }

        var primaryError: Exception? = null
        val errorTraces = mutableListOf<String>()

        for (modelName in orderedModels.toList()) {
            try {
                return callSingleModel(apiKey, modelName, requestJson)
            } catch (e: GeminiRetryableHttpException) {
                if (primaryError == null || e.statusCode != 404) {
                    primaryError = e
                }
                errorTraces.add("${modelName}(HTTP ${e.statusCode}: ${e.cleanReason.take(90)})")

                // 401/403 API 키 자체 오류는 다른 모델을 찔러도 동일하므로 즉시 중단하여 빠른 에러 리포트 제공
                if (e.statusCode == 401 || e.statusCode == 403 || e.cleanReason.contains("API_KEY_INVALID")) {
                    break
                }

                if (e.statusCode == 404) {
                    val suggested = SUGGESTED_MODEL_REGEX.findAll(e.errorBody)
                        .map { it.groupValues[1] }
                        .firstOrNull { it != modelName && isAudioCapableModelName(it) }
                    if (suggested != null && !orderedModels.contains(suggested)) {
                        try {
                            return callSingleModel(apiKey, suggested, requestJson)
                        } catch (inner: GeminiRetryableHttpException) {
                            errorTraces.add("${suggested}(HTTP ${inner.statusCode}: ${inner.cleanReason.take(80)})")
                        } catch (inner: Exception) {
                            errorTraces.add("${suggested}(${inner.message?.take(80)})")
                        }
                    }
                }
                delay(250L)
            } catch (e: Exception) {
                if (primaryError == null) primaryError = e
                errorTraces.add("${modelName}(${e.message?.take(90)})")
            }
        }

        val traceSummary = if (errorTraces.isNotEmpty()) {
            errorTraces.take(4).joinToString(" / ")
        } else {
            primaryError?.message ?: "알 수 없는 네트워크 또는 API 오류"
        }
        throw IllegalStateException("Gemini API 호출 실패 — ${traceSummary}")
    }

    private fun extractCleanGoogleErrorMessage(rawErrorBody: String): String {
        if (rawErrorBody.isBlank()) return "응답 본문 없음"
        return runCatching {
            val errObj = JSONObject(rawErrorBody).optJSONObject("error")
            if (errObj != null) {
                val status = errObj.optString("status", "")
                val msg = errObj.optString("message", rawErrorBody)
                if (status.isNotBlank()) "[${status}] ${msg}" else msg
            } else {
                rawErrorBody.replace(Regex("\\s+"), " ").take(200)
            }
        }.getOrDefault(rawErrorBody.replace(Regex("\\s+"), " ").take(200))
    }

    private inner class GeminiRetryableHttpException(
        val statusCode: Int,
        val modelName: String,
        val errorBody: String
    ) : IllegalStateException("HTTP ${statusCode} (${modelName}): ${extractCleanGoogleErrorMessage(errorBody)}") {
        val cleanReason: String = extractCleanGoogleErrorMessage(errorBody)
    }

    private fun callSingleModel(apiKey: String, modelName: String, requestJson: String): String {
        val cleanModel = modelName.removePrefix("models/")
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${cleanModel}:generateContent?key=${apiKey}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }

        conn.outputStream.use { os ->
            os.write(requestJson.toByteArray(Charsets.UTF_8))
        }

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            val errBody = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull() ?: ""
            throw GeminiRetryableHttpException(responseCode, cleanModel, errBody)
        }
        val responseStr = conn.inputStream.bufferedReader().use { it.readText() }

        val root = JSONObject(responseStr)
        val candidates = root.optJSONArray("candidates")
            ?: throw IllegalStateException("응답에 candidates가 없습니다 (${cleanModel}): ${responseStr.take(160)}")
        if (candidates.length() == 0) {
            val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason", "UNKNOWN")
            throw IllegalStateException("응답이 차단되었거나 비어 있습니다 (사유: ${blockReason})")
        }

        val parts = candidates.getJSONObject(0)
            .optJSONObject("content")
            ?.optJSONArray("parts")
            ?: throw IllegalStateException("응답에 content.parts가 없습니다 (${cleanModel})")

        // Gemini 2.5 / 3.x 모델의 Thinking 파트("thought": true)를 건너뛰고 실제 텍스트 파트만 추출
        var fallbackText = ""
        for (i in 0 until parts.length()) {
            val partObj = parts.optJSONObject(i) ?: continue
            val text = partObj.optString("text", "")
            val isThought = partObj.optBoolean("thought", false)
            if (text.isNotBlank()) {
                if (!isThought) {
                    return text
                }
                fallbackText = text
            }
        }
        if (fallbackText.isNotBlank()) return fallbackText
        throw IllegalStateException("모델(${cleanModel}) 응답 파트에서 텍스트를 찾을 수 없습니다.")
    }

    private fun discoverAvailableFlashModels(apiKey: String): List<String> = runCatching {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        if (conn.responseCode !in 200..299) return@runCatching emptyList()
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val models = JSONObject(body).optJSONArray("models") ?: return@runCatching emptyList()
        val found = mutableListOf<String>()
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            val name = m.optString("name", "").removePrefix("models/")
            val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
            if (isAudioCapableModelName(name) && methods.contains("generateContent")) {
                found.add(name)
            }
        }
        found
    }.getOrDefault(emptyList())

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
- 통화 상대방 이름: ${metadata.contactName}
- 통화 종료 일시(기준 시각): ${metadata.callDateTime}
중요 규칙:
1. 제목(title), 요약(description), 장소(location) 어디에도 전화번호(010-xxxx-xxxx 등 숫자 번호)나 파일명 정보를 절대 포함하지 마세요. 오직 통화 상대방 이름(${metadata.contactName})만 사용하세요.
2. 반드시 기준 시각을 바탕으로 '오늘', '내일', '모레', '다음 주', '21일' 등의 날짜를 정확한 ISO-8601(yyyy-MM-dd'T'HH:mm:ss) 시각으로 계산하세요.
JSON 배열 스키마로만 응답하세요:
[{"actionType":"APPOINTMENT|TODO|CALLBACK","title":"제목","description":"요약","startDateTimeIso":"2026-10-19T15:00:00","endDateTimeIso":"2026-10-19T16:00:00","location":"장소"}]

[통화 텍스트]
${transcript}
""".trimIndent()

class HybridCallAiProcessor(
    private val settingsDataStore: AiSettingsDataStore,
    private val onDeviceStt: SttEngine,
    private val onDeviceExtractor: ScheduleExtractor,
    private val cloudEngine: CloudGeminiEngine
) {
    /**
     * 1) forceCloudOnly == true ([Gemini API로 재시도] 버튼 클릭 시):
     *    온디바이스로 넘어가지 않고 오직 Gemini API로만 분석을 시도하며, 실패 시 정확한 Gemini API 오류를 그대로 반환합니다.
     * 2) 일반 자동 실행 시:
     *    Gemini API가 실패하면 즉시 온디바이스로 자동 전환하되, 왜 API가 실패했는지 상세 오류 로그(cloudSttErrorDetail)를
     *    결과 원문 하단에 반드시 함께 기록하여 사용자가 원인을 투명하게 확인할 수 있게 합니다.
     */
    suspend fun process(
        audioFile: File,
        metadata: CallMetadata,
        overrideMode: EngineMode? = null,
        forceCloudOnly: Boolean = false
    ): Result<Triple<String, String, List<ExtractedActionDto>>> {
        val mode = if (forceCloudOnly) EngineMode.CLOUD_API else (overrideMode ?: settingsDataStore.getCurrentMode())
        val hasApiKey = settingsDataStore.getCurrentApiKey().isNotBlank()

        return runCatching {
            if (forceCloudOnly) {
                require(hasApiKey) { "Gemini API 키가 입력되지 않았습니다. [3. AI 음성 인식 모드]에서 API 키를 먼저 저장해 주세요." }
                val cloudStt = cloudEngine.transcribe(audioFile).getOrThrow()
                val cloudActions = cloudEngine.extractActions(metadata, cloudStt.transcript).recoverCatching {
                    onDeviceExtractor.extractActions(metadata, cloudStt.transcript).getOrThrow()
                }.getOrThrow()
                return@runCatching Triple(cloudStt.transcript, "CLOUD_GEMINI_AUDIO (API 재시도 성공)", cloudActions)
            }

            var switchedFromCloudError = false
            var cloudErrorDiagnostic: String? = null

            var sttResult = when (mode) {
                EngineMode.ON_DEVICE -> {
                    onDeviceStt.transcribe(audioFile).getOrThrow()
                }
                EngineMode.CLOUD_API, EngineMode.AUTO_FALLBACK -> {
                    if (hasApiKey) {
                        val cloudAttempt = cloudEngine.transcribe(audioFile)
                        if (cloudAttempt.isSuccess) {
                            cloudAttempt.getOrThrow()
                        } else {
                            switchedFromCloudError = true
                            cloudErrorDiagnostic = cloudAttempt.exceptionOrNull()?.message ?: "알 수 없는 Gemini API 오류"
                            // 온디바이스로 자동 전환 시도 (최대 2회) & 만약 온디바이스도 실패하면 1차 API 오류와 2차 온디바이스 오류를 모두 표시!
                            val localAttempt = onDeviceStt.transcribe(audioFile).recoverCatching {
                                delay(400L)
                                onDeviceStt.transcribe(audioFile).getOrThrow()
                            }
                            if (localAttempt.isSuccess) {
                                localAttempt.getOrThrow()
                            } else {
                                val localErrMsg = localAttempt.exceptionOrNull()?.message ?: "온디바이스 음성 인식 실패"
                                throw IllegalStateException(
                                    "❌ [1차 Gemini API 실패 원인]\n• ${cloudErrorDiagnostic}\n\n" +
                                        "❌ [2차 온디바이스 자동전환 실패 원인]\n• ${localErrMsg}"
                                )
                            }
                        }
                    } else {
                        if (mode == EngineMode.CLOUD_API) {
                            switchedFromCloudError = true
                            cloudErrorDiagnostic = "Gemini API 키가 설정되지 않아 온디바이스로 전환되었습니다."
                        }
                        onDeviceStt.transcribe(audioFile).getOrThrow()
                    }
                }
            }

            val cleanTranscriptForExtraction = sttResult.transcript

            val actions = when {
                mode == EngineMode.ON_DEVICE || switchedFromCloudError || !hasApiKey -> {
                    onDeviceExtractor.extractActions(metadata, cleanTranscriptForExtraction).getOrThrow()
                }
                else -> {
                    cloudEngine.extractActions(metadata, cleanTranscriptForExtraction).recoverCatching { extractErr ->
                        cloudErrorDiagnostic = "STT는 성공했으나 일정 추출 API 호출 실패: ${extractErr.message}"
                        onDeviceExtractor.extractActions(metadata, cleanTranscriptForExtraction).getOrThrow()
                    }.getOrThrow()
                }
            }

            val finalEngineLabel = if (switchedFromCloudError) {
                "ON_DEVICE_FALLBACK (API 실패 자동전환)"
            } else {
                sttResult.engineName
            }

            val finalDisplayTranscript = if (!cloudErrorDiagnostic.isNullOrBlank()) {
                "${cleanTranscriptForExtraction}\n\n" +
                    "⚠️ [Gemini API 실패 원인 (온디바이스 자동전환됨)]\n" +
                    "• ${cloudErrorDiagnostic}\n" +
                    "👉 아래 [Gemini API로 재시도] 버튼을 누르면 API로 다시 분석합니다."
            } else {
                cleanTranscriptForExtraction
            }

            Triple(finalDisplayTranscript, finalEngineLabel, actions)
        }
    }
}
