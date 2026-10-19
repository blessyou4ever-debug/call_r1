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
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

class CallProcessWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val filePath = inputData.getString("KEY_FILE_PATH") ?: return Result.failure()
        val simulatedTranscript = inputData.getString("KEY_SIMULATED_TRANSCRIPT")
        val forceReprocess = inputData.getBoolean("KEY_FORCE_REPROCESS", false)
        val existingRecordId = inputData.getLong("KEY_EXISTING_RECORD_ID", -1L).takeIf { it > 0L }
        val overrideModeName = inputData.getString("KEY_OVERRIDE_MODE")
        val overrideMode = overrideModeName?.let { runCatching { EngineMode.valueOf(it) }.getOrNull() }
        val targetFile = File(filePath)

        val db = AppDatabase.getInstance(applicationContext)
        val dao = db.dao()

        if (!forceReprocess && simulatedTranscript.isNullOrBlank() && dao.hasProcessedFile(filePath)) {
            return Result.success()
        }

        val metadata = CallFilenameParser.parse(targetFile)
        val settings = AiSettingsDataStore(applicationContext)
        val onDeviceExtractor = OnDeviceLiteRtExtractor(applicationContext)
        val cloudEngine = CloudGeminiEngine(settings)
        val processor = HybridCallAiProcessor(
            settingsDataStore = settings,
            onDeviceStt = OnDeviceWhisperSttEngine(applicationContext),
            onDeviceExtractor = onDeviceExtractor,
            cloudEngine = cloudEngine
        )

        val processOutcome = runCatching {
            if (!simulatedTranscript.isNullOrBlank()) {
                val effectiveMode = overrideMode ?: settings.getCurrentMode()
                val useCloud = effectiveMode != EngineMode.ON_DEVICE &&
                    settings.getCurrentApiKey().isNotBlank()
                val actions = if (useCloud) {
                    cloudEngine.extractActions(metadata, simulatedTranscript)
                        .recoverCatching { onDeviceExtractor.extractActions(metadata, simulatedTranscript).getOrThrow() }
                        .getOrDefault(emptyList())
                } else {
                    onDeviceExtractor.extractActions(metadata, simulatedTranscript).getOrDefault(emptyList())
                }
                Triple(simulatedTranscript, if (useCloud) "CLOUD_GEMINI_TEXT" else "ON_DEVICE_NLP", actions)
            } else {
                processor.process(targetFile, metadata, overrideMode).getOrThrow()
            }
        }

        val callEpochMillis = metadata.callDateTime
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        if (processOutcome.isFailure) {
            val errorMsg = processOutcome.exceptionOrNull()?.message ?: "알 수 없는 분석 오류"
            dao.upsertCallRecord(
                existingId = existingRecordId,
                record = CallRecordEntity(
                    filePath = metadata.filePath,
                    contactName = metadata.contactName,
                    phoneNumber = metadata.phoneNumber,
                    callTimestampEpochMillis = callEpochMillis,
                    transcript = "[분석 실패] ${errorMsg}\n(아래 [다시 분석 시도] 또는 [온디바이스로 분석] 버튼을 눌러 즉시 재시도할 수 있습니다.)",
                    engineUsed = "ERROR"
                )
            )
            return Result.failure()
        }

        val (transcript, engineUsed, extractedDtos) = processOutcome.getOrThrow()

        val callRecordId = dao.upsertCallRecord(
            existingId = existingRecordId,
            record = CallRecordEntity(
                filePath = metadata.filePath,
                contactName = metadata.contactName,
                phoneNumber = metadata.phoneNumber,
                callTimestampEpochMillis = callEpochMillis,
                transcript = transcript,
                engineUsed = engineUsed
            )
        )

        val notificationHelper = ConfirmationNotificationHelper(applicationContext)

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
                callRecordId = callRecordId,
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

        return Result.success()
    }
}
