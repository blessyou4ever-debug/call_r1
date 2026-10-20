package com.example.callscheduler.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.example.callscheduler.data.local.datastore.AiSettingsDataStore
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class CallMonitoringForegroundService : Service() {

    companion object {
        private val SUPPORTED_AUDIO_EXTS = setOf("m4u", "m4a", "amr", "mp3", "wav", "ogg", "3gp", "aac")
        private const val MONITOR_CHANNEL_ID = "call_monitor_service_channel"
        private const val NOTIFICATION_ID = 9001
        private const val PREFS_NAME = "call_monitor_watermark_prefs"
        private const val KEY_WATERMARK_MILLIS = "watermark_epoch_millis"
        private const val KEY_SEEN_PATHS = "seen_file_paths"
        const val ACTION_RESET_BASELINE = "com.example.callscheduler.ACTION_RESET_BASELINE"
        const val ACTION_RELOAD_DIRS = "com.example.callscheduler.ACTION_RELOAD_DIRS"
        const val ACTION_FORCE_SCAN_LATEST = "com.example.callscheduler.ACTION_FORCE_SCAN_LATEST"

        // 통화 중 녹음 파일이 아직 쓰이는 도중에 성급하게 seen 처리되어 통화 종료 후 무시되는 문제를 막기 위한 파일 크기 추적 맵
        private val pendingFileSizes = ConcurrentHashMap<String, Pair<Long, Long>>() // path -> (lastSize, firstSeenMillis)
        private val analyzedFileSizes = ConcurrentHashMap<String, Long>() // path -> sizeWhenAnalyzed

        fun getMonitoredDirectories(context: Context): List<String> {
            return AiSettingsDataStore(context.applicationContext).getActiveMonitoredDirs()
        }

        /**
         * 감시 시작 시 현재 폴더에 이미 존재하는 파일들(크기가 확정된 파일들)을 기준선(seen)으로 등록하여
         * 과거 통화 녹음이 한꺼번에 분석되는 것을 방지합니다.
         */
        fun initializeOrResetBaseline(context: Context, forceResetToNow: Boolean = false) {
            val appCtx = context.applicationContext
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val existingWatermark = prefs.getLong(KEY_WATERMARK_MILLIS, 0L)

            if (forceResetToNow || existingWatermark == 0L) {
                val seen = (prefs.getStringSet(KEY_SEEN_PATHS, emptySet()) ?: emptySet()).toMutableSet()
                for (dirPath in getMonitoredDirectories(appCtx)) {
                    val dir = File(dirPath)
                    if (dir.exists() && dir.isDirectory) {
                        dir.listFiles()?.forEach { f ->
                            // 최근 8초 이내에 방금 생성/수정된 따끈따끈한 통화 파일은 기준선 제외(=즉시 분석 대상 유지)
                            if (f.isFile && (now - f.lastModified()) > 8_000L) {
                                seen.add(f.absolutePath)
                                analyzedFileSizes[f.absolutePath] = f.length()
                            }
                        }
                    }
                }
                // SAF Tree URI에 기존에 있던 파일들도 8초 이전 파일만 seen 등록
                scanSafTreeUriFiles(appCtx, onlyRegisterBaselineOlderThanMillis = now - 8_000L, seenSet = seen)

                prefs.edit()
                    .putLong(KEY_WATERMARK_MILLIS, now - 5_000L)
                    .putStringSet(KEY_SEEN_PATHS, seen)
                    .apply()
            }
        }

        /**
         * 통화 종료 후 생성된 녹음 파일이 쓰기 완료(안정화)되었는지 확인합니다.
         * - 통화 중에는 파일 크기가 계속 증가하므로, 최소 800바이트 이상이고
         *   (마지막 수정 시각이 1.2초 이상 지났거나 이전 스캔과 파일 크기가 동일할 때) 분석을 시작합니다.
         * - 만약 이전에 seen으로 기록되었더라도 통화 종료 시점에 파일 크기가 2KB 이상 더 커졌다면 새 완성본으로 인정합니다!
         */
        private fun isReadyNewCallRecordingFile(context: Context, file: File): Boolean {
            if (!file.exists() || !file.isFile) return false
            val currentLength = file.length()
            if (currentLength < 512L) return false
            if (file.extension.lowercase() !in SUPPORTED_AUDIO_EXTS) return false

            val path = file.absolutePath
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val seen = prefs.getStringSet(KEY_SEEN_PATHS, emptySet()) ?: emptySet()
            val prevAnalyzedSize = analyzedFileSizes[path] ?: 0L

            if (path in seen) {
                // 이미 seen에 있더라도, 통화 중 미완성 상태에서 등록되었다가 통화 종료 후 크기가 4KB 이상 커졌다면 재분석 허용
                if (prevAnalyzedSize <= 0L || currentLength <= prevAnalyzedSize + 4096L) {
                    return false
                }
            }

            val now = System.currentTimeMillis()
            val prevPending = pendingFileSizes[path]
            val ageSinceModified = now - file.lastModified()

            if (prevPending == null) {
                // 파일이 수정된 지 이미 1.5초 이상 지났다면 통화 종료 후 저장이 끝난 상태이므로 즉시 분석!
                if (ageSinceModified >= 1500L) {
                    return true
                }
                // 아직 통화 중이거나 방금 생성된 경우 다음 스캔(2~3초 뒤)에서 크기 안정화 확인
                pendingFileSizes[path] = Pair(currentLength, now)
                return false
            } else {
                val (lastSize, firstSeenTime) = prevPending
                if (currentLength == lastSize || (now - firstSeenTime) >= 3500L || ageSinceModified >= 1500L) {
                    pendingFileSizes.remove(path)
                    return true
                } else {
                    // 아직 파일 크기가 커지고 있는 중(통화 녹음 진행 중) -> 최신 크기 갱신 후 대기
                    pendingFileSizes[path] = Pair(currentLength, firstSeenTime)
                    return false
                }
            }
        }

        private fun markFileAsSeen(context: Context, file: File) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val seen = (prefs.getStringSet(KEY_SEEN_PATHS, emptySet()) ?: emptySet()).toMutableSet()
            seen.add(file.absolutePath)
            analyzedFileSizes[file.absolutePath] = file.length()
            prefs.edit()
                .putStringSet(KEY_SEEN_PATHS, seen)
                .apply()
        }

        /**
         * 사용자가 메인 화면에서 [폴더 내 최신 통화 녹음 즉시 스캔·분석] 버튼을 눌렀을 때
         * seen 여부와 상관없이 감시 폴더에서 가장 최근에 녹음된 오디오 파일 1개를 찾아 즉시 분석합니다.
         */
        fun forceAnalyzeLatestRecordingInFolders(context: Context): String? {
            val appCtx = context.applicationContext
            val candidates = mutableListOf<File>()
            for (dirPath in getMonitoredDirectories(appCtx)) {
                val dir = File(dirPath)
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.filter {
                        it.isFile && it.length() > 512L && it.extension.lowercase() in SUPPORTED_AUDIO_EXTS
                    }?.let { candidates.addAll(it) }
                }
            }
            val latestFile = candidates.maxByOrNull { it.lastModified() }
            if (latestFile != null) {
                markFileAsSeen(appCtx, latestFile)
                enqueueFileAnalysis(appCtx, latestFile.absolutePath, forceReprocess = true)
                return latestFile.name
            }
            val safLatest = forceCopyLatestFromSafTree(appCtx)
            if (safLatest != null) {
                enqueueFileAnalysis(appCtx, safLatest.absolutePath, forceReprocess = true)
                return safLatest.name
            }
            return null
        }

        /**
         * T전화(TPhoneCallRecords) 및 삼성 기본전화 폴더, 그리고 SAF 폴더 URI까지 모두 스캔하여
         * 통화 종료 후 생성된 새 녹음 파일을 즉시 찾아냅니다.
         */
        fun scanMonitoredDirectoriesForNewFiles(context: Context) {
            val appCtx = context.applicationContext
            initializeOrResetBaseline(appCtx, forceResetToNow = false)

            for (dirPath in getMonitoredDirectories(appCtx)) {
                val dir = File(dirPath)
                if (!dir.exists() || !dir.isDirectory) continue
                val files = dir.listFiles() ?: continue
                val newestFirst = files
                    .filter { it.isFile && it.extension.lowercase() in SUPPORTED_AUDIO_EXTS }
                    .sortedByDescending { it.lastModified() }
                    .take(6)

                for (file in newestFirst) {
                    if (isReadyNewCallRecordingFile(appCtx, file)) {
                        markFileAsSeen(appCtx, file)
                        enqueueFileAnalysis(appCtx, file.absolutePath, forceReprocess = false)
                    }
                }
            }

            // SAF Tree URI가 설정된 경우 Android 13/14/15 Scoped Storage 제약을 우회하여 새 파일 감지
            scanSafTreeUriFiles(appCtx, onlyRegisterBaselineOlderThanMillis = null, seenSet = null)
        }

        private fun scanSafTreeUriFiles(
            context: Context,
            onlyRegisterBaselineOlderThanMillis: Long?,
            seenSet: MutableSet<String>?
        ) {
            val treeUriStr = AiSettingsDataStore(context).getCustomTreeUri()
            if (treeUriStr.isBlank()) return
            runCatching {
                val treeUri = Uri.parse(treeUriStr)
                val treeDocId = DocumentsContract.getTreeDocumentId(treeUri) ?: return
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_SIZE
                )
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val currentSeen = seenSet ?: (prefs.getStringSet(KEY_SEEN_PATHS, emptySet()) ?: emptySet()).toMutableSet()

                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val docId = cursor.getString(0) ?: continue
                        val name = cursor.getString(1) ?: continue
                        val lastMod = cursor.getLong(2)
                        val size = cursor.getLong(3)
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext !in SUPPORTED_AUDIO_EXTS || size < 512L) continue

                        val virtualKey = "saf://$docId"
                        if (onlyRegisterBaselineOlderThanMillis != null) {
                            if (lastMod < onlyRegisterBaselineOlderThanMillis) {
                                currentSeen.add(virtualKey)
                            }
                        } else if (virtualKey !in currentSeen && (System.currentTimeMillis() - lastMod) >= 1200L) {
                            currentSeen.add(virtualKey)
                            prefs.edit().putStringSet(KEY_SEEN_PATHS, currentSeen).apply()
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                            val targetFile = File(context.cacheDir, name)
                            context.contentResolver.openInputStream(docUri)?.use { input ->
                                FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                            }
                            if (targetFile.exists() && targetFile.length() > 512L) {
                                enqueueFileAnalysis(context, targetFile.absolutePath, forceReprocess = true)
                            }
                        }
                    }
                }
            }
        }

        private fun forceCopyLatestFromSafTree(context: Context): File? = runCatching {
            val treeUriStr = AiSettingsDataStore(context).getCustomTreeUri()
            if (treeUriStr.isBlank()) return null
            val treeUri = Uri.parse(treeUriStr)
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri) ?: return null
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE
            )
            var bestDocId: String? = null
            var bestName: String? = null
            var bestMod = -1L

            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val lastMod = cursor.getLong(2)
                    val size = cursor.getLong(3)
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in SUPPORTED_AUDIO_EXTS && size > 512L && lastMod > bestMod) {
                        bestMod = lastMod
                        bestDocId = docId
                        bestName = name
                    }
                }
            }
            val finalDocId = bestDocId ?: return null
            val finalName = bestName ?: "통화 녹음.m4u"
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, finalDocId)
            val targetFile = File(context.cacheDir, finalName)
            context.contentResolver.openInputStream(docUri)?.use { input ->
                FileOutputStream(targetFile).use { output -> input.copyTo(output) }
            }
            targetFile.takeIf { it.exists() && it.length() > 0L }
        }.getOrNull()

        fun enqueueRecentMediaStoreScan(context: Context) {
            val appCtx = context.applicationContext
            initializeOrResetBaseline(appCtx, forceResetToNow = false)
            scanMonitoredDirectoriesForNewFiles(appCtx)

            val projection = arrayOf(
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATE_MODIFIED
            )
            val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

            runCatching {
                appCtx.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    val nameIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                    var checked = 0
                    while (cursor.moveToNext() && checked < 8) {
                        checked++
                        val path = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx) else ""
                        if (!path.isNullOrBlank() && isLikelyCallRecording(appCtx, path, name ?: "")) {
                            val file = File(path)
                            if (isReadyNewCallRecordingFile(appCtx, file)) {
                                markFileAsSeen(appCtx, file)
                                enqueueFileAnalysis(appCtx, file.absolutePath, forceReprocess = false)
                                break
                            }
                        }
                    }
                }
            }
        }

        private fun isLikelyCallRecording(context: Context, path: String, displayName: String): Boolean {
            val lowerPath = path.lowercase()
            val lowerName = displayName.lowercase()
            val ext = lowerPath.substringAfterLast('.', "")
            if (ext !in SUPPORTED_AUDIO_EXTS) return false

            val customDir = AiSettingsDataStore(context).getCustomCallDir().trim().lowercase()
            if (customDir.isNotEmpty() && lowerPath.startsWith(customDir)) return true

            val isCallFolderOrName = lowerPath.contains("/call/") ||
                lowerPath.contains("/recordings/") ||
                lowerPath.contains("tphonecallrecords") ||
                lowerPath.contains("tphone") ||
                lowerName.contains("통화") ||
                lowerName.contains("call") ||
                lowerName.contains("_010")
            return isCallFolderOrName
        }

        fun enqueueFileAnalysis(context: Context, filePath: String, forceReprocess: Boolean = false) {
            // WorkManager 큐 대기 없이 즉시 코루틴 분석 엔진을 직접 가동하여 0.1초 내 반응 보장
            CallAnalysisRunner.triggerAnalysisNow(
                context = context.applicationContext,
                filePath = filePath,
                forceReprocess = forceReprocess
            )
        }
    }

    private val fileObservers = mutableListOf<FileObserver>()
    private var mediaStoreObserver: ContentObserver? = null
    private val periodicHandler = Handler(Looper.getMainLooper())
    private val periodicScanRunnable = object : Runnable {
        override fun run() {
            runCatching { enqueueRecentMediaStoreScan(applicationContext) }
            periodicHandler.postDelayed(this, 3000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        initializeOrResetBaseline(applicationContext, forceResetToNow = false)
        startForegroundWithNotification()
        startWatchingCallDirectories()
        registerMediaStoreObserver()
        periodicHandler.postDelayed(periodicScanRunnable, 2000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESET_BASELINE -> {
                initializeOrResetBaseline(applicationContext, forceResetToNow = true)
                startWatchingCallDirectories()
                startForegroundWithNotification()
                periodicHandler.postDelayed({ enqueueRecentMediaStoreScan(applicationContext) }, 1500L)
            }
            ACTION_RELOAD_DIRS -> {
                initializeOrResetBaseline(applicationContext, forceResetToNow = true)
                startWatchingCallDirectories()
                startForegroundWithNotification()
                periodicHandler.postDelayed({ enqueueRecentMediaStoreScan(applicationContext) }, 1500L)
            }
            ACTION_FORCE_SCAN_LATEST -> {
                enqueueRecentMediaStoreScan(applicationContext)
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            MONITOR_CHANNEL_ID,
            "통화 녹음 자동 감지 서비스",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        val customDir = AiSettingsDataStore(this).getCustomCallDir()
        val folderSummary = if (customDir.isNotBlank()) {
            "지정 경로 감시 중: $customDir"
        } else {
            "삼성 기본전화 + T전화(TPhoneCallRecords) 실시간 감시 중"
        }

        val notification = NotificationCompat.Builder(this, MONITOR_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("새 통화 녹음 실시간 감지 중 (v3.2)")
            .setContentText(folderSummary)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun registerMediaStoreObserver() {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                handler.postDelayed({
                    enqueueRecentMediaStoreScan(applicationContext)
                }, 1500L)
                handler.postDelayed({
                    enqueueRecentMediaStoreScan(applicationContext)
                }, 4000L)
            }
        }
        runCatching {
            contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            mediaStoreObserver = observer
        }
    }

    @Suppress("DEPRECATION")
    private fun startWatchingCallDirectories() {
        fileObservers.forEach { runCatching { it.stopWatching() } }
        fileObservers.clear()

        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE or FileObserver.MODIFY
        for (dirPath in getMonitoredDirectories(applicationContext)) {
            val dir = File(dirPath)
            if (dir.exists() && dir.isDirectory) {
                val obs = object : FileObserver(dir.absolutePath, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        val eventType = event and FileObserver.ALL_EVENTS
                        if ((eventType == FileObserver.CLOSE_WRITE || eventType == FileObserver.MOVED_TO) && path != null) {
                            val file = File(dir, path)
                            periodicHandler.postDelayed({
                                if (isReadyNewCallRecordingFile(applicationContext, file)) {
                                    markFileAsSeen(applicationContext, file)
                                    enqueueFileAnalysis(applicationContext, file.absolutePath, forceReprocess = false)
                                }
                            }, 1200L)
                        }
                    }
                }
                obs.startWatching()
                fileObservers.add(obs)
            }
        }
    }

    override fun onDestroy() {
        periodicHandler.removeCallbacksAndMessages(null)
        fileObservers.forEach { runCatching { it.stopWatching() } }
        fileObservers.clear()
        mediaStoreObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
