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
                "단말기 내장 음성 인식기가 오디오 변환을 완료하지 못했습니다. Gemini API 키를 설정하면 끊김 없는 전체 통화 인식이 가능합니다."
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
            hasFathersDay -> "아빠의 날 행사${if (!location.isNullOrBlank()) " ($location)" else ""}"
            hasMothersDay -> "엄마의 날 행사${if (!location.isNullOrBlank()) " ($location)" else ""}"
            transcript.contains("유치원") && hasPickup -> "유치원 하원"
            transcript.contains("어린이집") && hasPickup -> "어린이집 하원"
            transcript.contains("유치원") && hasDropoff -> "유치원 등원"
            transcript.contains("병원") || transcript.contains("치과") || transcript.contains("검진") -> "${location ?: "병원"} 방문 예약"
            transcript.contains("미팅") || transcript.contains("회의") -> "${contactName}님과 미팅${if (!location.isNullOrBlank()) " ($location)" else ""}"
            transcript.contains("식사") || transcript.contains("점심") || transcript.contains("저녁") -> "${contactName}님과 식사 약속"
            !location.isNullOrBlank() -> "${contactName}님과 일정 ($location)"
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
        val locPart = if (!location.isNullOrBlank() && !coreTopic.contains(location)) " $location에서" else ""
        return "${contactName}님과 통화 중 $dateTimeLabel$locPart $coreTopic 일정 확인"
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
        return "${contactName}님과 통화 중 $dateTimeLabel $coreTopic 관련 다시 연락하기로 확인"
            .replace(multiSpaceRegex, " ")
            .trim()
    }

    private fun summarizeTodo(
        contactName: String,
        transcript: String
    ): String {
        val coreTopic = extractCleanedCoreTopic(transcript, "요청 사항")
        return "${contactName}님과 통화 중 $coreTopic 처리 및 확인 필요"
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
        return "${dt.monthValue}월 ${dt.dayOfMonth}일 $meridiem ${displayHour}시$minPart"
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
            "gemini-3.5-flash-lite",
            "gemini-3.6-flash",
            "gemini-3.8-flash",
            "gemini-3.5-flash",
            "gemini-3.1-flash-lite-preview",
            "gemini-3-flash-preview",
            "gemini-2.5-flash"
        )
        private val SUGGESTED_MODEL_REGEX = Regex("""models/([a-zA-Z0-9._-]+)""")
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
                put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            }.toString()

            val responseText = postGeminiJsonWithFallback(apiKey, requestJson)
            val parsedTranscript = unwrapTranscriptJson(responseText)

            require(parsedTranscript.isNotBlank()) { "오디오에서 음성을 추출하지 못했습니다." }

            SttResult(
                transcript = parsedTranscript,
                confidence = 0.96f,
                engineName = "CLOUD_GEMINI_AUDIO"
            )
        }
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
        val modelsToTry = LinkedHashSet<String>(CANDIDATE_MODELS)
        var lastError: Exception? = null

        for (modelName in modelsToTry.toList()) {
            try {
                return callSingleModel(apiKey, modelName, requestJson)
            } catch (e: GeminiRetryableHttpException) {
                lastError = e
                if (e.statusCode == 404) {
                    val suggested = SUGGESTED_MODEL_REGEX.findAll(e.errorBody)
                        .map { it.groupValues[1] }
                        .firstOrNull { it != modelName }
                    if (suggested != null && !modelsToTry.contains(suggested)) {
                        try {
                            return callSingleModel(apiKey, suggested, requestJson)
                        } catch (inner: Exception) {
                            lastError = inner
                        }
                    }
                }
                delay(400L)
            } catch (e: Exception) {
                lastError = e
            }
        }

        val discoveredList = discoverAvailableFlashModels(apiKey)
        for (discovered in discoveredList) {
            if (discovered in modelsToTry) continue
            try {
                return callSingleModel(apiKey, discovered, requestJson)
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw lastError ?: IllegalStateException("사용 가능한 Gemini 모델을 찾을 수 없습니다.")
    }

    private class GeminiRetryableHttpException(val statusCode: Int, modelName: String, val errorBody: String) :
        IllegalStateException("Gemini API HTTP $statusCode ($modelName): ${errorBody.take(240)}")

    private fun callSingleModel(apiKey: String, modelName: String, requestJson: String): String {
        val cleanModel = modelName.removePrefix("models/")
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$apiKey")
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
        if (responseCode in listOf(404, 429, 500, 502, 503, 504)) {
            val errBody = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull() ?: ""
            throw GeminiRetryableHttpException(responseCode, cleanModel, errBody)
        }
        if (responseCode !in 200..299) {
            val errBody = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull() ?: ""
            throw IllegalStateException("Gemini API HTTP $responseCode ($cleanModel): ${errBody.take(240)}")
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

    private fun discoverAvailableFlashModels(apiKey: String): List<String> = runCatching {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        if (conn.responseCode !in 200..299) return@runCatching emptyList()
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val models = JSONObject(body).optJSONArray("models") ?: return@runCatching emptyList()
        val found = mutableListOf<String>()
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            val name = m.optString("name", "").removePrefix("models/")
            val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
            if (name.contains("gemini") && name.contains("flash") && methods.contains("generateContent") && !name.contains("2.5")) {
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
        val hasApiKey = settingsDataStore.getCurrentApiKey().isNotBlank()

        return runCatching {
            var sttResult = when (mode) {
                EngineMode.ON_DEVICE -> {
                    onDeviceStt.transcribe(audioFile).recoverCatching { localErr ->
                        if (hasApiKey) cloudEngine.transcribe(audioFile).getOrThrow() else throw localErr
                    }.getOrThrow()
                }
                EngineMode.CLOUD_API -> cloudEngine.transcribe(audioFile).recoverCatching { cloudErr ->
                    onDeviceStt.transcribe(audioFile).getOrElse { throw cloudErr }
                }.getOrThrow()
                EngineMode.AUTO_FALLBACK -> {
                    val local = onDeviceStt.transcribe(audioFile).getOrNull()
                    if (local != null && local.confidence >= MIN_CONFIDENCE_THRESHOLD) {
                        local
                    } else if (hasApiKey) {
                        cloudEngine.transcribe(audioFile).getOrElse { local ?: throw it }
                    } else {
                        local ?: throw IllegalStateException("온디바이스 음성 인식 결과가 너무 짧고 API 키가 설정되지 않았습니다.")
                    }
                }
            }

            var actions = when (mode) {
                EngineMode.ON_DEVICE -> onDeviceExtractor.extractActions(metadata, sttResult.transcript).getOrThrow()
                EngineMode.CLOUD_API, EngineMode.AUTO_FALLBACK -> {
                    if (hasApiKey) {
                        cloudEngine.extractActions(metadata, sttResult.transcript).recoverCatching {
                            onDeviceExtractor.extractActions(metadata, sttResult.transcript).getOrThrow()
                        }.getOrThrow()
                    } else {
                        onDeviceExtractor.extractActions(metadata, sttResult.transcript).getOrThrow()
                    }
                }
            }

            if (actions.isEmpty() && sttResult.engineName == "ANDROID_ON_DEVICE_STT" && sttResult.transcript.length < 25 && hasApiKey) {
                val rescuedStt = cloudEngine.transcribe(audioFile).getOrNull()
                if (rescuedStt != null && rescuedStt.transcript.length > sttResult.transcript.length) {
                    sttResult = rescuedStt
                    actions = if (mode == EngineMode.ON_DEVICE) {
                        onDeviceExtractor.extractActions(metadata, sttResult.transcript).getOrDefault(emptyList())
                    } else {
                        cloudEngine.extractActions(metadata, sttResult.transcript)
                            .recoverCatching { onDeviceExtractor.extractActions(metadata, sttResult.transcript).getOrThrow() }
                            .getOrDefault(emptyList())
                    }
                }
            }

            Triple(sttResult.transcript, sttResult.engineName, actions)
        }
    }
}
