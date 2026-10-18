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
                val useCloud = settings.getCurrentMode() != EngineMode.ON_DEVICE &&
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
                processor.process(targetFile, metadata).getOrThrow()
            }
        }

        if (processOutcome.isFailure) {
            val errorMsg = processOutcome.exceptionOrNull()?.message ?: "알 수 없는 분석 오류"
            dao.insertCallRecord(
                CallRecordEntity(
                    filePath = metadata.filePath,
                    contactName = metadata.contactName,
                    phoneNumber = metadata.phoneNumber,
                    callTimestampEpochMillis = System.currentTimeMillis(),
                    transcript = "[분석 실패] $errorMsg",
                    engineUsed = "ERROR"
                )
            )
            return Result.failure()
        }

        val (transcript, engineUsed, extractedDtos) = processOutcome.getOrThrow()

        val callRecordId = dao.insertCallRecord(
            CallRecordEntity(
                filePath = metadata.filePath,
                contactName = metadata.contactName,
                phoneNumber = metadata.phoneNumber,
                callTimestampEpochMillis = metadata.callDateTime
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
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
                phoneNumber = metadata.phoneNumber,
                actionType = dto.actionType,
                title = dto.title,
                description = dto.description,
                startEpochMillis = startMillis,
                endEpochMillis = endMillis,
                location = dto.location,
                status = "PENDING"
            )
            val insertedId = dao.insertActionItem(entity)
            notificationHelper.showActionConfirmationNotification(entity.copy(id = insertedId))
        }

        return Result.success()
    }
}
