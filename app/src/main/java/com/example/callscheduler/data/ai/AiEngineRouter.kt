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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        try {
            require(audioOrPcmFile.exists() && audioOrPcmFile.length() > 0L) {
                "오디오 파일을 찾을 수 없거나 비어 있습니다: ${audioOrPcmFile.name}"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val pcmTemp = File(context.cacheDir, "decoded_${System.currentTimeMillis()}.pcm")
                val chunkFiles = mutableListOf<File>()
                try {
                    val decoded = AudioPcmDecoder.decodeToPcmInfo(audioOrPcmFile, pcmTemp).getOrThrow()
                    chunkFiles.addAll(AudioPcmDecoder.splitAndTrimSilenceIntoChunks(decoded, context.cacheDir, chunkSeconds = 10))

                    val segments = mutableListOf<String>()
                    // 기본 내장 음성 인식기(오프라인 언어팩 불필요, 무료)를 1순위로 사용하고, 안 될 경우 OnDevice 전용 인식기로 즉시 교차 시도
                    var preferStandardRecognizer = true
                    var lastRecognizerErrorCode = -1

                    for ((index, chunk) in chunkFiles.take(6).withIndex()) {
                        val firstTry = recognizeSinglePcmChunkViaPipe(
                            pcmFile = chunk,
                            useStandardRecognizer = preferStandardRecognizer
                        )
                        val chunkText = if (!firstTry.first.isNullOrBlank()) {
                            firstTry.first
                        } else if (index == 0) {
                            // 첫 번째 청크가 실패하면 즉시 반대쪽 엔진(표준 <-> 온디바이스 전용)으로 교차 재시도!
                            val secondTry = recognizeSinglePcmChunkViaPipe(
                                pcmFile = chunk,
                                useStandardRecognizer = !preferStandardRecognizer
                            )
                            if (!secondTry.first.isNullOrBlank()) {
                                preferStandardRecognizer = !preferStandardRecognizer
                                secondTry.first
                            } else {
                                lastRecognizerErrorCode = secondTry.second.takeIf { it != -1 } ?: firstTry.second
                                null
                            }
                        } else {
                            lastRecognizerErrorCode = firstTry.second
                            null
                        }

                        if (!chunkText.isNullOrBlank()) {
                            segments.add(chunkText.trim())
                        } else if (index == 0 && segments.isEmpty()) {
                            // 첫 번째 청크가 양쪽 인식기에서 모두 실패하면 15개 청크를 2분 30초 동안 기다리지 않고 3초 만에 즉시 종료(Fail-Fast)!
                            break
                        }
                        delay(80L)
                    }

                    val fullTranscript = segments.joinToString(" ").trim()
                    if (fullTranscript.isNotBlank()) {
                        val expectedMinChars = (decoded.durationSeconds * 2.0f).toInt().coerceAtLeast(8)
                        val completenessRatio = (fullTranscript.length.toFloat() / expectedMinChars.toFloat()).coerceIn(0.25f, 1.0f)
                        val confidence = (0.70f + 0.25f * completenessRatio).coerceAtMost(0.92f)

                        return@withContext Result.success(
                            SttResult(
                                transcript = fullTranscript,
                                confidence = confidence,
                                engineName = "ANDROID_BUILTIN_STT"
                            )
                        )
                    }

                    val errorReason = describeSpeechErrorCode(lastRecognizerErrorCode)
                    return@withContext Result.failure(
                        IllegalStateException(
                            "단말기 내장 음성 인식 실패 (${errorReason}). 휴대폰 [설정 → 애플리케이션 → 기본 앱 → 디지털 어시스턴트 앱 / 음성 입력]에서 Google 음성 인식이 켜져 있는지 확인하거나 [Gemini API로 재시도]를 눌러 주세요."
                        )
                    )
                } finally {
                    chunkFiles.forEach { runCatching { it.delete() } }
                    runCatching { pcmTemp.delete() }
                }
            }

            Result.failure(
                IllegalStateException("단말기 안드로이드 버전(Android 13 미만)에서 오디오 파이프 STT를 지원하지 않습니다. Gemini API 모드를 사용해 주세요.")
            )
        } catch (t: Throwable) {
            Result.failure(IllegalStateException("내장 음성 인식 오류: ${t.message ?: t.javaClass.simpleName}"))
        }
    }

    private fun describeSpeechErrorCode(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "음성 매칭 결과 없음(ERROR_NO_MATCH)"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "음성 입력 시간 초과(ERROR_SPEECH_TIMEOUT)"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, 12 -> "한국어 오프라인 음성팩 미설치(ERROR_LANGUAGE_UNAVAILABLE)"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한 필요(ERROR_INSUFFICIENT_PERMISSIONS)"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성 인식기 사용 중(ERROR_RECOGNIZER_BUSY)"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 연결 지연"
        -1 -> "인식기 응답 시간 초과(5초)"
        else -> "오류 코드 $code"
    }

    /**
     * 정적 파일 디스크립터 대신 ParcelFileDescriptor.createPipe()로 16kHz Mono PCM 스트림을 주입하고
     * 쓰기 완료 즉시 파이프를 닫아(EOF) SpeechRecognizer가 12초를 기다리지 않고 1~2초 만에 결과를 반환하게 만듭니다.
     */
    private suspend fun recognizeSinglePcmChunkViaPipe(
        pcmFile: File,
        useStandardRecognizer: Boolean
    ): Pair<String?, Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return Pair(null, -1)
        val pcmBytes = runCatching { pcmFile.readBytes() }.getOrNull() ?: return Pair(null, -1)
        if (pcmBytes.isEmpty()) return Pair(null, -1)

        var lastErr = -1
        val text = withTimeoutOrNull(3_800L) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<String?> { cont ->
                    val recognizer = runCatching {
                        if (useStandardRecognizer) {
                            SpeechRecognizer.createSpeechRecognizer(context)
                        } else if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                        } else {
                            SpeechRecognizer.createSpeechRecognizer(context)
                        }
                    }.getOrNull()

                    if (recognizer == null) {
                        if (cont.isActive) cont.resume(null)
                        return@suspendCancellableCoroutine
                    }

                    val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull()
                    if (pipe == null) {
                        runCatching { recognizer.destroy() }
                        if (cont.isActive) cont.resume(null)
                        return@suspendCancellableCoroutine
                    }
                    val readSide = pipe[0]
                    val writeSide = pipe[1]

                    // 백그라운드에서 PCM 데이터를 실시간 스트리밍 후 즉시 EOF(close) 전송
                    val writerJob = CoroutineScope(Dispatchers.IO).launch {
                        runCatching {
                            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { os ->
                                val frameChunk = 6400 // 200ms at 16kHz 16-bit mono
                                var offset = 0
                                while (offset < pcmBytes.size) {
                                    val len = (pcmBytes.size - offset).coerceAtMost(frameChunk)
                                    os.write(pcmBytes, offset, len)
                                    os.flush()
                                    offset += len
                                    delay(15L)
                                }
                            }
                        }
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
                            val part = list?.firstOrNull()?.trim()
                            if (!part.isNullOrBlank()) {
                                if (latestPartial.length >= 6 && part.length < latestPartial.length / 2) {
                                    collectedPieces.add(latestPartial)
                                }
                                latestPartial = part
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val finalText = list?.firstOrNull()?.trim().takeUnless { it.isNullOrBlank() } ?: latestPartial
                            if (finalText.isNotBlank()) {
                                collectedPieces.add(finalText)
                            }
                            val combined = collectedPieces.distinct().joinToString(" ").trim()
                            writerJob.cancel()
                            runCatching { readSide.close() }
                            runCatching { writeSide.close() }
                            runCatching { recognizer.destroy() }
                            if (cont.isActive) cont.resume(combined.ifBlank { null })
                        }

                        override fun onError(error: Int) {
                            lastErr = error
                            if (latestPartial.isNotBlank()) {
                                collectedPieces.add(latestPartial)
                            }
                            val combined = collectedPieces.distinct().joinToString(" ").trim()
                            writerJob.cancel()
                            runCatching { readSide.close() }
                            runCatching { writeSide.close() }
                            runCatching { recognizer.destroy() }
                            if (cont.isActive) cont.resume(combined.ifBlank { null })
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })

                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                    }

                    cont.invokeOnCancellation {
                        writerJob.cancel()
                        runCatching { readSide.close() }
                        runCatching { writeSide.close() }
                        runCatching { recognizer.destroy() }
                    }

                    runCatching {
                        recognizer.startListening(intent)
                    }.onFailure {
                        writerJob.cancel()
                        runCatching { readSide.close() }
                        runCatching { writeSide.close() }
                        runCatching { recognizer.destroy() }
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
        return Pair(text, lastErr)
    }
}

class OnDeviceLiteRtExtractor(private val context: Context) : ScheduleExtractor {

    private val locationRegex = Regex("""([가-힣0-9A-Za-z]{0,15}(?:유치원|어린이집|초등학교|중학교|고등학교|학교|학원|역|카페|스타벅스|투썸|사무실|빌딩|회의실|병원|의원|치과|식당|레스토랑|호텔|센터|지점|본사|호점|공원|마트|백화점|공항|터미널)(?:\s*[가-힣0-9A-Za-z]{1,10})?)""")
    private val atLocationRegex = Regex("""([가-힣0-9A-Za-z\s]{2,15})에서\s*(?:만나|미팅|보기로|뵙|모임|진행|식사|회의|하원|등원|출발|집합|모여)""")
    private val monthDayRegex = Regex("""(\d{1,2})\s*월\s*(\d{1,2})\s*일""")
    private val dayOnlyRegex = Regex("""(?:^|\s)(\d{1,2})\s*일(?:이|에|날|\s|$)""")
    private val timeRegex = Regex("""(오전|오후|아침|점심|저녁|밤)?\s*(\d{1,2})\s*시(?:\s*(\d{1,2})\s*분|\s*(반))?""")

    private val relativeDateCleanRegex = Regex("""(?:이번\s*주말|주말|이번\s*주|다음\s*주|내일모레|모레|글피|내일|오늘|이따가|이따)(?:에|에는|은|는)?\s*(?:[월화수목금토일]요일(?:에)?)?|[월화수목금토일]요일(?:에|에는|은|는)?""")
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
                text.contains("오늘") || text.contains("이따가") || text.contains("이따") -> { dateMatched = true }
                text.contains("다음 주") || text.contains("다음주") -> {
                    val dow = parseDayOfWeek(text) ?: DayOfWeek.MONDAY
                    date = date.with(TemporalAdjusters.next(dow))
                    dateMatched = true
                }
                text.contains("이번 주") || text.contains("이번주") -> {
                    val dow = parseDayOfWeek(text) ?: DayOfWeek.SATURDAY
                    date = date.with(TemporalAdjusters.nextOrSame(dow))
                    dateMatched = true
                }
                text.contains("주말") -> {
                    date = date.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
                    dateMatched = true
                }
                else -> {
                    // '이번 주' 없이 단독으로 '금요일 오후 3시', '수요일 11시'라고 말한 경우에도 다가오는 해당 요일로 정확히 매칭
                    val standaloneDow = parseDayOfWeek(text)
                    if (standaloneDow != null) {
                        date = date.with(TemporalAdjusters.nextOrSame(standaloneDow))
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
        // 특정 세대(예: 3.x 계열)의 무료 쿼터가 소진(HTTP 429)되더라도,
        // 별도 무료 쿼터 버킷을 가진 Lite / 2.0 / 2.5 / 1.5 세대로 즉시 우회하도록 세대별로 다변화합니다.
        private val CROSS_FAMILY_CANDIDATE_MODELS = listOf(
            "gemini-2.5-flash-lite",
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite-preview",
            "gemini-2.5-flash",
            "gemini-1.5-flash",
            "gemini-3.8-flash",
            "gemini-3.6-flash"
        )
        private val USE_MODEL_REGEX = Regex("""use\s+models/([a-zA-Z0-9._-]+)""", RegexOption.IGNORE_CASE)
        private val SUGGESTED_MODEL_REGEX = Regex("""models/([a-zA-Z0-9._-]+)""")
    }

    private fun isAudioCapableModelName(name: String): Boolean {
        val lower = name.lowercase()
        if (!lower.contains("gemini") || !lower.contains("flash")) return false
        val unsupportedKeywords = listOf("tts", "image", "vision", "embedding", "aqa", "bisect", "thinking")
        return unsupportedKeywords.none { lower.contains(it) }
    }

    /**
     * 모델의 쿼터 버킷 패밀리(세대)를 판별합니다.
     * 예: gemini-3.6-flash에서 HTTP 429(쿼터 초과)가 발생하면, 같은 3.x 일반 Flash 버킷(3.8-flash, 3.5-flash)을
     * 중복 호출해 시간을 낭비하지 않고 즉시 다른 쿼터 버킷(2.5-flash-lite, 2.0-flash, 1.5-flash 등)으로 건너뜁니다!
     */
    private fun getQuotaBucketFamily(modelName: String): String {
        val m = modelName.lowercase()
        return when {
            m.startsWith("gemini-3") && m.contains("lite") -> "family_3_lite"
            m.startsWith("gemini-3") -> "family_3_std"
            m.startsWith("gemini-2.5") && m.contains("lite") -> "family_25_lite"
            m.startsWith("gemini-2.5") -> "family_25_std"
            m.startsWith("gemini-2.0") && m.contains("lite") -> "family_20_lite"
            m.startsWith("gemini-2.0") -> "family_20_std"
            m.startsWith("gemini-1.5") -> "family_15"
            else -> m
        }
    }

    suspend fun diagnoseApiConnection(apiKeyOverride: String? = null): Result<String> = withContext(Dispatchers.IO) {
        try {
            val apiKey = (apiKeyOverride ?: settingsDataStore.getCurrentApiKey()).trim()
            if (apiKey.isBlank()) {
                return@withContext Result.success("❌ Gemini API 키가 비어 있습니다. 먼저 API 키를 입력하고 저장해 주세요.")
            }

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

            return@withContext try {
                val (reply, winningModel) = postGeminiJsonWithFallback(apiKey, testPayload, maxAttempts = 6)
                settingsDataStore.setPreferredModelSync(winningModel)
                Result.success(
                    "✅ Gemini API 쿼터 정상 모델 탐색 및 고정 완료!\n" +
                        "• 현재 무료 할당량이 살아있는 모델: ${winningModel}\n" +
                        "• 테스트 응답: ${reply.trim().take(40)}\n" +
                        "• 이제 429 쿼터 오류 없이 [${winningModel}] 모델로 즉시 분석합니다."
                )
            } catch (t: Throwable) {
                Result.success(
                    "❌ Gemini API 모든 모델 호출 실패\n• ${t.message}\n\n" +
                        "💡 [해결 팁] Google AI Studio에서 해당 프로젝트의 무료 일일 할당량(Quota)이 모두 소진된 상태입니다. " +
                        "AI Studio에서 [새 프로젝트(New Project)]를 만들어 새 API 키를 발급받거나, 상단 모드를 [온디바이스 우선 (무료·보안)]으로 선택해 주세요."
                )
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun transcribe(audioOrPcmFile: File): Result<SttResult> = withContext(Dispatchers.IO) {
        try {
            val apiKey = settingsDataStore.getCurrentApiKey()
            require(apiKey.isNotBlank()) { "Gemini API 키가 설정되지 않았습니다." }
            require(audioOrPcmFile.exists() && audioOrPcmFile.length() > 0L) {
                "오디오 파일이 존재하지 않거나 비어 있습니다: ${audioOrPcmFile.absolutePath}"
            }
            require(audioOrPcmFile.length() <= 18L * 1024L * 1024L) {
                "녹음 파일 크기가 너무 큽니다 (${audioOrPcmFile.length() / 1024}KB)."
            }

            val mimeType = resolveAudioMimeType(audioOrPcmFile.extension)
            val audioBytes = audioOrPcmFile.readBytes()
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

            val firstAttempt = runCatching {
                requestAudioTranscription(apiKey, mimeType, base64Audio)
            }

            val (responseText, usedModel) = if (firstAttempt.isSuccess) {
                firstAttempt.getOrThrow()
            } else {
                val firstErr = firstAttempt.exceptionOrNull()
                val errMsg = firstErr?.message ?: ""
                if ((errMsg.contains("HTTP 400") || errMsg.contains("INVALID_ARGUMENT")) && audioOrPcmFile.length() <= 1_500_000L) {
                    val wavAttempt = runCatching { AudioPcmDecoder.transcodeToWavBytes(audioOrPcmFile).getOrThrow() }
                    if (wavAttempt.isSuccess) {
                        val wavBase64 = Base64.encodeToString(wavAttempt.getOrThrow(), Base64.NO_WRAP)
                        requestAudioTranscription(apiKey, "audio/wav", wavBase64)
                    } else {
                        throw firstErr ?: IllegalStateException("Gemini 오디오 STT 호출 실패")
                    }
                } else {
                    throw firstErr ?: IllegalStateException("Gemini 오디오 STT 호출 실패")
                }
            }

            val parsedTranscript = unwrapTranscriptJson(responseText)
            require(parsedTranscript.isNotBlank()) { "Gemini API(${usedModel})가 빈 텍스트를 반환했습니다." }

            Result.success(
                SttResult(
                    transcript = parsedTranscript,
                    confidence = 0.97f,
                    engineName = "CLOUD_GEMINI (${usedModel})"
                )
            )
        } catch (t: Throwable) {
            Result.failure(IllegalStateException(t.message ?: "Gemini 오디오 STT 오류 (${t.javaClass.simpleName})"))
        }
    }

    private suspend fun requestAudioTranscription(
        apiKey: String,
        mimeType: String,
        base64Audio: String
    ): Pair<String, String> {
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
                    "이 통화 녹음 오디오의 대화 내용을 한국어로 빠짐없이 받아쓰기(STT)해 주세요. 부연 설명 없이 실제 통화 대화 내용 텍스트만 그대로 출력하세요."
                )
            )
        }

        val requestJson = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", parts)))
        }.toString()

        return postGeminiJsonWithFallback(apiKey, requestJson, maxAttempts = 5)
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
        try {
            val apiKey = settingsDataStore.getCurrentApiKey()
            require(apiKey.isNotBlank()) { "Gemini API 키가 설정되지 않았습니다." }

            val prompt = buildExtractionPrompt(metadata, transcript)
            val plainRequest = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                ))
            }.toString()

            val (textContent, _) = postGeminiJsonWithFallback(apiKey, plainRequest, maxAttempts = 3)
            Result.success(parseExtractedActionsJson(textContent))
        } catch (t: Throwable) {
            Result.failure(IllegalStateException(t.message ?: "일정 추출 오류"))
        }
    }

    private suspend fun postGeminiJsonWithFallback(
        apiKey: String,
        requestJson: String,
        maxAttempts: Int = 5
    ): Pair<String, String> {
        val preferred = settingsDataStore.getPreferredModel().ifBlank { "gemini-2.5-flash-lite" }

        val initialPool = LinkedHashSet<String>().apply {
            add(preferred)
            addAll(CROSS_FAMILY_CANDIDATE_MODELS)
        }

        val triedModels = mutableSetOf<String>()
        val exhaustedFamilies = mutableSetOf<String>()
        val errorTraces = mutableListOf<String>()
        val queue = ArrayDeque<String>(initialPool)

        while (queue.isNotEmpty() && triedModels.size < maxAttempts) {
            val modelName = queue.removeFirst().removePrefix("models/")
            val family = getQuotaBucketFamily(modelName)
            // 이미 HTTP 429(쿼터 초과)가 발생한 세대(예: family_3_std)의 다른 모델들은 즉시 건너뛰어 다른 세대 모델을 시도합니다!
            if (family in exhaustedFamilies) continue
            if (!triedModels.add(modelName)) continue

            try {
                val responseText = callSingleModel(apiKey, modelName, requestJson)
                settingsDataStore.setPreferredModelSync(modelName)
                return Pair(responseText, modelName)
            } catch (e: GeminiRetryableHttpException) {
                errorTraces.add("${modelName}(HTTP ${e.statusCode}: ${e.cleanReason.take(75)})")

                if (e.statusCode == 401 || e.statusCode == 403 || e.cleanReason.contains("API_KEY_INVALID")) {
                    break
                }

                if (e.statusCode == 429 || e.cleanReason.contains("RESOURCE_EXHAUSTED")) {
                    // 해당 모델 패밀리의 쿼터가 소진되었으므로 같은 패밀리는 모두 스킵!
                    exhaustedFamilies.add(family)
                    continue
                }

                val explicitRecommended = USE_MODEL_REGEX.find(e.errorBody)?.groupValues?.getOrNull(1)
                    ?: SUGGESTED_MODEL_REGEX.findAll(e.errorBody)
                        .map { it.groupValues[1] }
                        .firstOrNull { it != modelName && isAudioCapableModelName(it) }

                if (!explicitRecommended.isNullOrBlank() && explicitRecommended !in triedModels) {
                    queue.addFirst(explicitRecommended)
                    continue
                }
            } catch (t: Throwable) {
                errorTraces.add("${modelName}(${t.message?.take(75) ?: t.javaClass.simpleName})")
            }
        }

        // 서버의 활성 모델 목록에서 아직 시도하지 않은 세대의 모델이 있다면 1회 추가 탐색
        val discovered = discoverAvailableFlashModels(apiKey).firstOrNull {
            it !in triedModels && getQuotaBucketFamily(it) !in exhaustedFamilies
        }
        if (discovered != null) {
            try {
                val responseText = callSingleModel(apiKey, discovered, requestJson)
                settingsDataStore.setPreferredModelSync(discovered)
                return Pair(responseText, discovered)
            } catch (t: Throwable) {
                errorTraces.add("${discovered}(${t.message?.take(75)})")
            }
        }

        val allQuotaExhausted = errorTraces.isNotEmpty() && errorTraces.all { it.contains("429") || it.contains("RESOURCE_EXHAUSTED") }
        val traceSummary = if (errorTraces.isNotEmpty()) {
            errorTraces.take(3).joinToString(" / ")
        } else {
            "알 수 없는 네트워크 또는 API 오류"
        }

        val quotaHint = if (allQuotaExhausted) {
            "\n💡 현재 입력된 API 키의 Google 무료 할당량(Quota)이 초과되었습니다. Google AI Studio에서 [새 프로젝트(New Project)]로 API 키를 새로 발급받거나 [온디바이스 우선] 모드를 사용해 주세요."
        } else {
            ""
        }
        throw IllegalStateException("Gemini API 호출 실패 — ${traceSummary}${quotaHint}")
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
                rawErrorBody.replace(Regex("\\s+"), " ").take(180)
            }
        }.getOrDefault(rawErrorBody.replace(Regex("\\s+"), " ").take(180))
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
            connectTimeout = 7_000
            readTimeout = 16_000
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
            ?: throw IllegalStateException("응답에 candidates가 없습니다 (${cleanModel})")
        if (candidates.length() == 0) {
            val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason", "UNKNOWN")
            throw IllegalStateException("응답이 차단되었거나 비어 있습니다 (사유: ${blockReason})")
        }

        val parts = candidates.getJSONObject(0)
            .optJSONObject("content")
            ?.optJSONArray("parts")
            ?: throw IllegalStateException("응답에 content.parts가 없습니다 (${cleanModel})")

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
            connectTimeout = 6_000
            readTimeout = 6_000
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
        // 무료 쿼터가 가장 넉넉한 lite 모델을 우선 배치
        found.sortedWith(compareByDescending<String> { it.contains("lite") }.thenByDescending { it })
    }.getOrDefault(emptyList())

    private fun parseExtractedActionsJson(rawJson: String): List<ExtractedActionDto> {
        var cleaned = rawJson.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val firstBracket = cleaned.indexOf('[')
        val lastBracket = cleaned.lastIndexOf(']')
        if (firstBracket >= 0 && lastBracket > firstBracket) {
            cleaned = cleaned.substring(firstBracket, lastBracket + 1)
        } else {
            val firstBrace = cleaned.indexOf('{')
            val lastBrace = cleaned.lastIndexOf('}')
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                cleaned = cleaned.substring(firstBrace, lastBrace + 1)
            }
        }

        val array = if (cleaned.startsWith("{")) {
            JSONObject(cleaned).optJSONArray("actions") ?: JSONArray()
        } else {
            JSONArray(cleaned)
        }
        val list = mutableListOf<ExtractedActionDto>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
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
    suspend fun process(
        audioFile: File,
        metadata: CallMetadata,
        overrideMode: EngineMode? = null,
        forceCloudOnly: Boolean = false,
        onProgressUpdate: (suspend (String) -> Unit)? = null
    ): Result<Triple<String, String, List<ExtractedActionDto>>> {
        val mode = if (forceCloudOnly) EngineMode.CLOUD_API else (overrideMode ?: settingsDataStore.getCurrentMode())
        val hasApiKey = settingsDataStore.getCurrentApiKey().isNotBlank()

        return try {
            if (forceCloudOnly) {
                require(hasApiKey) { "Gemini API 키가 입력되지 않았습니다. [3. AI 음성 인식 모드]에서 API 키를 먼저 저장해 주세요." }
                onProgressUpdate?.invoke("⏳ [1/2단계] Gemini API(${settingsDataStore.getPreferredModel()}) 및 여유 쿼터 모델로 음성 인식(STT) 중...")
                val cloudStt = cloudEngine.transcribe(audioFile).getOrThrow()
                onProgressUpdate?.invoke("⏳ [2/2단계] 음성 인식 완료! 대화 내용에서 약속·할 일 추출 중...")
                val cloudActions = cloudEngine.extractActions(metadata, cloudStt.transcript).recoverCatching {
                    onDeviceExtractor.extractActions(metadata, cloudStt.transcript).getOrThrow()
                }.getOrThrow()
                return Result.success(Triple(cloudStt.transcript, "${cloudStt.engineName} (API 성공)", cloudActions))
            }

            var switchedFromCloudError = false
            var cloudErrorDiagnostic: String? = null

            val sttResult = when (mode) {
                EngineMode.ON_DEVICE -> {
                    onProgressUpdate?.invoke("⏳ [1/2단계] 내장 고속 파이프 엔진(16kHz)으로 통화 음성 인식(STT) 중...")
                    onDeviceStt.transcribe(audioFile).getOrThrow()
                }
                EngineMode.CLOUD_API, EngineMode.AUTO_FALLBACK -> {
                    if (hasApiKey) {
                        onProgressUpdate?.invoke("⏳ [1/2단계] Gemini API(${settingsDataStore.getPreferredModel()})로 오디오 음성 인식(STT) 요청 중...")
                        val cloudAttempt = cloudEngine.transcribe(audioFile)
                        if (cloudAttempt.isSuccess) {
                            cloudAttempt.getOrThrow()
                        } else {
                            switchedFromCloudError = true
                            cloudErrorDiagnostic = cloudAttempt.exceptionOrNull()?.message ?: "알 수 없는 Gemini API 오류"
                            onProgressUpdate?.invoke("⚠️ Gemini API 쿼터 초과/실패 → 내장 고속 음성 인식기(16kHz 파이프)로 즉시 전환 중...")
                            val localAttempt = onDeviceStt.transcribe(audioFile)
                            if (localAttempt.isSuccess) {
                                localAttempt.getOrThrow()
                            } else {
                                val localErrMsg = localAttempt.exceptionOrNull()?.message ?: "내장 음성 인식 실패"
                                throw IllegalStateException(
                                    "❌ [1차 Gemini API 실패 원인]\n• ${cloudErrorDiagnostic}\n\n" +
                                        "❌ [2차 내장 음성인식 자동전환 실패 원인]\n• ${localErrMsg}"
                                )
                            }
                        }
                    } else {
                        if (mode == EngineMode.CLOUD_API) {
                            switchedFromCloudError = true
                            cloudErrorDiagnostic = "Gemini API 키가 설정되지 않아 내장 엔진으로 전환되었습니다."
                        }
                        onProgressUpdate?.invoke("⏳ [1/2단계] 내장 고속 파이프 엔진(16kHz)으로 통화 음성 인식(STT) 중...")
                        onDeviceStt.transcribe(audioFile).getOrThrow()
                    }
                }
            }

            val cleanTranscriptForExtraction = sttResult.transcript
            onProgressUpdate?.invoke("⏳ [2/2단계] 음성 인식 완료 (${cleanTranscriptForExtraction.length}자) → 약속·할 일·콜백 일정 추출 중...")

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
                "BUILTIN_STT_FALLBACK (API 쿼터초과 자동전환)"
            } else {
                sttResult.engineName
            }

            val finalDisplayTranscript = if (!cloudErrorDiagnostic.isNullOrBlank()) {
                "${cleanTranscriptForExtraction}\n\n" +
                    "⚠️ [Gemini API 실패 원인 (내장 STT로 자동전환 완료)]\n" +
                    "• ${cloudErrorDiagnostic}\n" +
                    "👉 아래 [Gemini API로 재시도] 버튼을 누르면 여유 쿼터 모델로 다시 분석합니다."
            } else {
                cleanTranscriptForExtraction
            }

            Result.success(Triple(finalDisplayTranscript, finalEngineLabel, actions))
        } catch (t: Throwable) {
            Result.failure(IllegalStateException(t.message ?: "분석 처리 중 예외 발생 (${t.javaClass.simpleName})"))
        }
    }
}
