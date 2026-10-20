package com.example.callscheduler.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.callscheduler.data.ai.CloudGeminiEngine
import com.example.callscheduler.data.ai.HybridCallAiProcessor
import com.example.callscheduler.data.ai.OnDeviceLiteRtExtractor
import com.example.callscheduler.data.ai.OnDeviceWhisperSttEngine
import com.example.callscheduler.data.local.datastore.AiSettingsDataStore
import com.example.callscheduler.data.local.datastore.EngineMode
import com.example.callscheduler.data.local.db.ActionItemEntity
import com.example.callscheduler.data.local.db.AppDatabase
import com.example.callscheduler.data.local.db.CallRecordEntity
import com.example.callscheduler.data.parser.CallFilenameParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * WorkManager 대기열 지연이나 무반응 문제를 완전히 해결하기 위해,
 * 수동 파일 선택·재시도·실시간 통화 종료 감지 시 즉시(0.1초 이내) DB에 '⏳ 분석 진행 중...' 카드를 먼저 띄우고
 * 백그라운드 코루틴에서 실시간 진행 단계를 업데이트하는 직접 실행 엔진입니다.
 */
object CallAnalysisRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activePaths = ConcurrentHashMap.newKeySet<String>()
    private val runnerMutex = Mutex()

    fun triggerAnalysisNow(
        context: Context,
        filePath: String,
        simulatedTranscript: String? = null,
        forceReprocess: Boolean = false,
        forceCloudOnly: Boolean = false,
        existingRecordId: Long? = null,
        overrideMode: EngineMode? = null
    ) {
        val appCtx = context.applicationContext
        scope.launch {
            executeAnalysisSuspend(
                appContext = appCtx,
                filePath = filePath,
                simulatedTranscript = simulatedTranscript,
                forceReprocess = forceReprocess,
                forceCloudOnly = forceCloudOnly,
                existingRecordId = existingRecordId,
                overrideMode = overrideMode
            )
        }
    }

    suspend fun executeAnalysisSuspend(
        appContext: Context,
        filePath: String,
        simulatedTranscript: String? = null,
        forceReprocess: Boolean = false,
        forceCloudOnly: Boolean = false,
        existingRecordId: Long? = null,
        overrideMode: EngineMode? = null
    ): Boolean {
        val addedToActive = activePaths.add(filePath)
        if (!addedToActive && !forceReprocess) {
            // 자동 감시에서 이미 동일한 파일이 분석 진행 중이면 중복 실행 방지 (단, 사용자의 수동 재시도 클릭은 대기열에서 정상 실행)
            return true
        }

        try {
            val targetFile = File(filePath)
            val db = AppDatabase.getInstance(appContext)
            val dao = db.dao()

            if (!forceReprocess && simulatedTranscript.isNullOrBlank() && dao.hasProcessedFile(filePath)) {
                return true
            }

            val metadata = CallFilenameParser.parse(targetFile)
            val callEpochMillis = metadata.callDateTime
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val nowTimeStr = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())

            val existingRecord = existingRecordId?.let { dao.getCallRecordById(it) }
                ?: dao.findRecordIdByFilePath(filePath)?.let { dao.getCallRecordById(it) }

            val previousTranscriptBackup = existingRecord?.transcript

            // 1) 즉시 DB에 '⏳ 분석 진행 중...' 레코드를 먼저 생성/갱신하여 화면에 즉각 반응 표시!
            val liveRecordId = dao.upsertCallRecord(
                existingId = existingRecord?.id ?: existingRecordId,
                record = CallRecordEntity(
                    filePath = metadata.filePath,
                    contactName = metadata.contactName,
                    phoneNumber = metadata.phoneNumber,
                    callTimestampEpochMillis = callEpochMillis,
                    transcript = "⏳ [분석 시작 (${nowTimeStr})] 녹음 파일(${targetFile.name}, ${targetFile.length() / 1024}KB)을 확인했습니다. AI 음성 인식을 준비 중입니다...",
                    engineUsed = "⏳ 분석 진행 중..."
                ),
                clearPendingActionsOnUpdate = false
            )

            // 동시 다발적인 오디오 디코딩/네트워크 충돌 방지를 위해 순차 락 안에서 본 분석 수행
            return runnerMutex.withLock {
                val settings = AiSettingsDataStore(appContext)
                val timedOutcome = kotlinx.coroutines.withTimeoutOrNull(35_000L) {
                val onDeviceExtractor = OnDeviceLiteRtExtractor(appContext)
                val cloudEngine = CloudGeminiEngine(settings)
                val processor = HybridCallAiProcessor(
                    settingsDataStore = settings,
                    onDeviceStt = OnDeviceWhisperSttEngine(appContext),
                    onDeviceExtractor = onDeviceExtractor,
                    cloudEngine = cloudEngine
                )

                val processOutcome = runCatching {
                    if (!simulatedTranscript.isNullOrBlank()) {
                        val effectiveMode = if (forceCloudOnly) EngineMode.CLOUD_API else (overrideMode ?: settings.getCurrentMode())
                        val useCloud = effectiveMode != EngineMode.ON_DEVICE &&
                            settings.getCurrentApiKey().isNotBlank()
                        val actions = if (useCloud) {
                            if (forceCloudOnly) {
                                cloudEngine.extractActions(metadata, simulatedTranscript).getOrThrow()
                            } else {
                                cloudEngine.extractActions(metadata, simulatedTranscript)
                                    .recoverCatching { onDeviceExtractor.extractActions(metadata, simulatedTranscript).getOrThrow() }
                                    .getOrDefault(emptyList())
                            }
                        } else {
                            onDeviceExtractor.extractActions(metadata, simulatedTranscript).getOrDefault(emptyList())
                        }
                        Triple(simulatedTranscript, if (useCloud) "CLOUD_GEMINI_TEXT" else "ON_DEVICE_NLP", actions)
                    } else {
                        processor.process(
                            audioFile = targetFile,
                            metadata = metadata,
                            overrideMode = overrideMode,
                            forceCloudOnly = forceCloudOnly,
                            onProgressUpdate = { stepMsg ->
                                dao.upsertCallRecord(
                                    existingId = liveRecordId,
                                    record = CallRecordEntity(
                                        id = liveRecordId,
                                        filePath = metadata.filePath,
                                        contactName = metadata.contactName,
                                        phoneNumber = metadata.phoneNumber,
                                        callTimestampEpochMillis = callEpochMillis,
                                        transcript = stepMsg,
                                        engineUsed = "⏳ 분석 진행 중..."
                                    ),
                                    clearPendingActionsOnUpdate = false
                                )
                            }
                        ).getOrThrow()
                    }
                }

                if (processOutcome.isFailure) {
                    val errorMsg = processOutcome.exceptionOrNull()?.message ?: "알 수 없는 분석 오류"
                    val errTimeStr = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())

                    if (forceCloudOnly && !previousTranscriptBackup.isNullOrBlank() &&
                        !previousTranscriptBackup.startsWith("[분석 실패") &&
                        !previousTranscriptBackup.startsWith("⏳")
                    ) {
                        val baseText = previousTranscriptBackup
                            .substringBefore("\n\n⚠️ [Gemini API")
                            .substringBefore("\n\n❌ [Gemini API 재시도 실패")
                            .trim()
                        val updatedTranscript = "${baseText}\n\n" +
                            "❌ [Gemini API 재시도 실패 (${errTimeStr})]\n" +
                            "• ${errorMsg}\n" +
                            "(기존 온디바이스 분석 원문 및 추출된 일정은 그대로 유지됩니다.)"
                        dao.upsertCallRecord(
                            existingId = liveRecordId,
                            record = CallRecordEntity(
                                id = liveRecordId,
                                filePath = metadata.filePath,
                                contactName = metadata.contactName,
                                phoneNumber = metadata.phoneNumber,
                                callTimestampEpochMillis = callEpochMillis,
                                transcript = updatedTranscript,
                                engineUsed = "ON_DEVICE_FALLBACK (API 재시도 실패)"
                            ),
                            clearPendingActionsOnUpdate = false
                        )
                        return@withLock false
                    }

                    dao.upsertCallRecord(
                        existingId = liveRecordId,
                        record = CallRecordEntity(
                            id = liveRecordId,
                            filePath = metadata.filePath,
                            contactName = metadata.contactName,
                            phoneNumber = metadata.phoneNumber,
                            callTimestampEpochMillis = callEpochMillis,
                            transcript = "[분석 실패 (${errTimeStr})]\n${errorMsg}\n(아래 [Gemini API로 재시도] 또는 [온디바이스로 재분석] 버튼을 눌러 다시 시도할 수 있습니다.)",
                            engineUsed = "ERROR (분석 실패)"
                        ),
                        clearPendingActionsOnUpdate = false
                    )
                    return@withLock false
                }

                val (transcript, engineUsed, extractedDtos) = processOutcome.getOrThrow()

                val finalRecordId = dao.upsertCallRecord(
                    existingId = liveRecordId,
                    record = CallRecordEntity(
                        id = liveRecordId,
                        filePath = metadata.filePath,
                        contactName = metadata.contactName,
                        phoneNumber = metadata.phoneNumber,
                        callTimestampEpochMillis = callEpochMillis,
                        transcript = transcript,
                        engineUsed = engineUsed
                    ),
                    clearPendingActionsOnUpdate = true
                )

                val notificationHelper = ConfirmationNotificationHelper(appContext)

                for (dto in extractedDtos) {
                    val startMillis = dto.startDateTimeIso?.let {
                        runCatching {
                            LocalDateTime.parse(it).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        }.getOrNull()
                    }
                    val endMillis = dto.endDateTimeIso?.let {
                        runCatching {
                            LocalDateTime.parse(it).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        }.getOrNull()
                    }

                    val entity = ActionItemEntity(
                        callRecordId = finalRecordId,
                        contactName = metadata.contactName,
                        phoneNumber = "",
                        actionType = dto.actionType,
                        title = CallFilenameParser.stripPhoneNumbers(dto.title).ifBlank { "${metadata.contactName}님과 일정" },
                        description = CallFilenameParser.stripPhoneNumbers(dto.description),
                        startEpochMillis = startMillis,
                        endEpochMillis = endMillis,
                        location = dto.location?.let { CallFilenameParser.stripPhoneNumbers(it) }?.ifBlank { null },
                        status = "PENDING"
                    )
                    val insertedId = dao.insertActionItem(entity)
                    notificationHelper.showActionConfirmationNotification(entity.copy(id = insertedId))
                }

                true
                } // withTimeoutOrNull end

                if (timedOutcome == null) {
                    val timeoutTimeStr = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
                    dao.upsertCallRecord(
                        existingId = liveRecordId,
                        record = CallRecordEntity(
                            id = liveRecordId,
                            filePath = metadata.filePath,
                            contactName = metadata.contactName,
                            phoneNumber = metadata.phoneNumber,
                            callTimestampEpochMillis = callEpochMillis,
                            transcript = "[분석 시간 초과 (35초, ${timeoutTimeStr})]\n단말기 음성 처리 시간이 초과되어 안전하게 중단했습니다. (더 이상 멈춰있지 않습니다)\n무료 Gemini API 키를 등록하면 3~5초 만에 정확한 전체 통화 분석이 완료됩니다.",
                            engineUsed = "ERROR (시간 초과)"
                        ),
                        clearPendingActionsOnUpdate = false
                    )
                    false
                } else {
                    timedOutcome
                }
            }
        } catch (t: Throwable) {
            val errStr = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
            runCatching {
                val db = AppDatabase.getInstance(appContext)
                val liveId = db.dao().findRecordIdByFilePath(filePath)
                if (liveId != null) {
                    val meta = CallFilenameParser.parse(File(filePath))
                    db.dao().upsertCallRecord(
                        existingId = liveId,
                        record = CallRecordEntity(
                            id = liveId,
                            filePath = filePath,
                            contactName = meta.contactName,
                            phoneNumber = meta.phoneNumber,
                            callTimestampEpochMillis = meta.callDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                            transcript = "[분석 중단 (${errStr})]\n${t.message ?: "작업 취소 또는 단말기 리소스 부족"}\n아래 [Gemini API로 재시도] 또는 [내장 엔진으로 재분석]을 눌러 다시 시도할 수 있습니다.",
                            engineUsed = "ERROR (중단됨)"
                        ),
                        clearPendingActionsOnUpdate = false
                    )
                }
            }
            false
        } finally {
            activePaths.remove(filePath)
        }
    }
}

class CallProcessWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val filePath = inputData.getString("KEY_FILE_PATH") ?: return Result.failure()
        val simulatedTranscript = inputData.getString("KEY_SIMULATED_TRANSCRIPT")
        val forceReprocess = inputData.getBoolean("KEY_FORCE_REPROCESS", false)
        val forceCloudOnly = inputData.getBoolean("KEY_FORCE_CLOUD_ONLY", false)
        val existingRecordId = inputData.getLong("KEY_EXISTING_RECORD_ID", -1L).takeIf { it > 0L }
        val overrideModeName = inputData.getString("KEY_OVERRIDE_MODE")
        val overrideMode = overrideModeName?.let { runCatching { EngineMode.valueOf(it) }.getOrNull() }

        val ok = CallAnalysisRunner.executeAnalysisSuspend(
            appContext = applicationContext,
            filePath = filePath,
            simulatedTranscript = simulatedTranscript,
            forceReprocess = forceReprocess,
            forceCloudOnly = forceCloudOnly,
            existingRecordId = existingRecordId,
            overrideMode = overrideMode
        )
        return if (ok) Result.success() else Result.failure()
    }
}
