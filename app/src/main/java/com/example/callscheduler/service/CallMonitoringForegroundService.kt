package com.example.callscheduler.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.callscheduler.data.local.datastore.AiSettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class CallMonitoringForegroundService : Service() {

    companion object {
        private val SUPPORTED_AUDIO_EXTS = setOf("m4u", "m4a", "amr", "mp3", "wav", "ogg", "3gp", "aac")
        private const val MONITOR_CHANNEL_ID = "call_monitor_service_channel"
        private const val NOTIFICATION_ID = 9001
        private const val PREFS_NAME = "call_monitor_watermark_prefs"
        private const val KEY_WATERMARK_MILLIS = "watermark_epoch_millis"
        private const val KEY_SEEN_PATHS = "seen_file_paths"

        // 장시간 통화(최대 20분)에서도 DATE_ADDED 기준 누락이 발생하지 않도록 20분 창을 허용하되,
        // 감시 시작 시각(Watermark) 이전에 생성/수정된 과거 녹음은 100% 원천 차단합니다.
        private const val MAX_AUTO_DETECT_AGE_MILLIS = 20 * 60 * 1000L
        private const val MAX_SEEN_CACHE_SIZE = 500

        const val ACTION_RESET_BASELINE = "com.example.callscheduler.ACTION_RESET_BASELINE"
        const val ACTION_RELOAD_DIRS = "com.example.callscheduler.ACTION_RELOAD_DIRS"
        const val ACTION_FORCE_SCAN_LATEST = "com.example.callscheduler.ACTION_FORCE_SCAN_LATEST"

        private val pendingFileSizes = ConcurrentHashMap<String, Pair<Long, Long>>() // path -> (lastSize, firstSeenMillis)
        private val analyzedFileSizes = ConcurrentHashMap<String, Long>() // path -> sizeWhenAnalyzed
        private val inMemorySeenPaths = ConcurrentHashMap.newKeySet<String>()
        @Volatile
        private var cachedWatermarkMillis: Long = 0L

        // 백그라운드 전용 스캔 스레드 풀 (메인 스레드 블로킹 및 상시 폴링으로 인한 배터리 소모 완벽 제거)
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val isScanRunning = AtomicBoolean(false)
        @Volatile
        private var debouncedScanJob: Job? = null

        fun getMonitoredDirectories(context: Context): List<String> {
            return AiSettingsDataStore(context.applicationContext).getActiveMonitoredDirs()
        }

        private fun ensureCacheLoaded(context: Context) {
            if (cachedWatermarkMillis > 0L && inMemorySeenPaths.isNotEmpty()) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            cachedWatermarkMillis = prefs.getLong(KEY_WATERMARK_MILLIS, 0L)
            val saved = prefs.getStringSet(KEY_SEEN_PATHS, emptySet()) ?: emptySet()
            inMemorySeenPaths.addAll(saved)
        }

        private fun trimSeenSetIfNeeded(seen: MutableSet<String>): MutableSet<String> {
            if (seen.size <= MAX_SEEN_CACHE_SIZE) return seen
            val excess = seen.size - MAX_SEEN_CACHE_SIZE
            return seen.drop(excess).toMutableSet()
        }

        /**
         * 최초 설치/권한 승인 또는 사용자가 명시적으로 기준선 리셋(폴더 변경·목록 비우기)을 요청했을 때만
         * 현재 존재하는 모든 기존 녹음 파일(폴더 + MediaStore + SAF)을 seen에 등록하고 기준 시각(Watermark)을 설정합니다.
         */
        fun initializeOrResetBaseline(context: Context, forceResetToNow: Boolean = false) {
            val appCtx = context.applicationContext
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val existingWatermark = prefs.getLong(KEY_WATERMARK_MILLIS, 0L)
            val existingSeen = prefs.getStringSet(KEY_SEEN_PATHS, emptySet()) ?: emptySet()

            if (!forceResetToNow && existingWatermark > 0L && existingSeen.isNotEmpty()) {
                cachedWatermarkMillis = existingWatermark
                inMemorySeenPaths.addAll(existingSeen)
                return
            }

            ioScope.launch {
                val seen = existingSeen.toMutableSet()

                // 1) 감시 대상 폴더의 기존 파일들을 모두 seen에 등록
                for (dirPath in getMonitoredDirectories(appCtx)) {
                    val dir = File(dirPath)
                    if (dir.exists() && dir.isDirectory) {
                        dir.listFiles()?.forEach { f ->
                            if (f.isFile) {
                                seen.add(f.absolutePath)
                                analyzedFileSizes[f.absolutePath] = f.length()
                            }
                        }
                    }
                }

                // 2) MediaStore 상의 기존 통화 녹음 파일들도 모두 seen에 등록
                runCatching {
                    val projection = arrayOf(
                        MediaStore.Audio.Media.DATA,
                        MediaStore.Audio.Media.DISPLAY_NAME
                    )
                    val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
                    appCtx.contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        null,
                        null,
                        sortOrder
                    )?.use { cursor ->
                        val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                        val nameIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                        var count = 0
                        while (cursor.moveToNext() && count < 120) {
                            count++
                            val path = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                            val name = if (nameIdx >= 0) cursor.getString(nameIdx) else ""
                            if (!path.isNullOrBlank() && isLikelyCallRecording(appCtx, path, name ?: "")) {
                                seen.add(path)
                                val f = File(path)
                                if (f.exists()) {
                                    analyzedFileSizes[path] = f.length()
                                }
                            }
                        }
                    }
                }

                // 3) SAF Tree URI에 기존에 있던 파일들도 모두 seen 등록
                scanSafTreeUriFiles(appCtx, onlyRegisterBaseline = true, seenSet = seen)

                val boundedSeen = trimSeenSetIfNeeded(seen)
                val newWatermark = if (forceResetToNow || existingWatermark == 0L) now else existingWatermark
                inMemorySeenPaths.clear()
                inMemorySeenPaths.addAll(boundedSeen)
                cachedWatermarkMillis = newWatermark

                prefs.edit()
                    .putStringSet(KEY_SEEN_PATHS, boundedSeen)
                    .putLong(KEY_WATERMARK_MILLIS, newWatermark)
                    .apply()
            }
        }

        private fun isReadyNewCallRecordingFile(context: Context, file: File): Boolean {
            if (!file.exists() || !file.isFile) return false
            val currentLength = file.length()
            if (currentLength < 512L) return false
            if (file.extension.lowercase() !in SUPPORTED_AUDIO_EXTS) return false

            val appCtx = context.applicationContext
            ensureCacheLoaded(appCtx)
            val path = file.absolutePath
            val now = System.currentTimeMillis()
            val lastMod = file.lastModified()
            val watermark = if (cachedWatermarkMillis > 0L) cachedWatermarkMillis else now

            // 감시 시작 시각(watermark) 이전에 만들어진 파일이거나 오래된 과거 파일은 자동 분석 제외
            if (lastMod < watermark || (now - lastMod) > MAX_AUTO_DETECT_AGE_MILLIS) {
                if (path !in inMemorySeenPaths) {
                    markFileAsSeen(appCtx, file, advanceWatermark = false)
                }
                return false
            }

            val prevAnalyzedSize = analyzedFileSizes[path] ?: 0L
            if (path in inMemorySeenPaths) {
                if (prevAnalyzedSize <= 0L || currentLength <= prevAnalyzedSize + 4096L) {
                    return false
                }
            }

            val prevPending = pendingFileSizes[path]
            val ageSinceModified = now - lastMod

            if (prevPending == null) {
                if (ageSinceModified >= 1500L) {
                    return true
                }
                pendingFileSizes[path] = Pair(currentLength, now)
                return false
            } else {
                val (lastSize, firstSeenTime) = prevPending
                if (currentLength == lastSize || (now - firstSeenTime) >= 3500L || ageSinceModified >= 1500L) {
                    pendingFileSizes.remove(path)
                    return true
                } else {
                    pendingFileSizes[path] = Pair(currentLength, firstSeenTime)
                    return false
                }
            }
        }

        private fun markFileAsSeen(context: Context, file: File, advanceWatermark: Boolean = true) {
            val appCtx = context.applicationContext
            ensureCacheLoaded(appCtx)
            val added = inMemorySeenPaths.add(file.absolutePath)
            analyzedFileSizes[file.absolutePath] = file.length()
            val newWatermark = if (advanceWatermark) {
                maxOf(cachedWatermarkMillis, file.lastModified())
            } else {
                cachedWatermarkMillis
            }
            val watermarkUpdated = newWatermark != cachedWatermarkMillis
            cachedWatermarkMillis = newWatermark

            // 실제로 새로운 파일 경로가 추가되었거나 Watermark가 갱신되었을 때만 SharedPreferences에 기록 (디스크 I/O 절감)
            if (added || watermarkUpdated) {
                val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val boundedSeen = trimSeenSetIfNeeded(inMemorySeenPaths.toMutableSet())
                val editor = prefs.edit().putStringSet(KEY_SEEN_PATHS, boundedSeen)
                if (advanceWatermark) {
                    editor.putLong(KEY_WATERMARK_MILLIS, cachedWatermarkMillis)
                }
                editor.apply()
            }
        }

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
                markFileAsSeen(appCtx, latestFile, advanceWatermark = false)
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

        fun scanMonitoredDirectoriesForNewFiles(context: Context) {
            val appCtx = context.applicationContext
            ensureCacheLoaded(appCtx)
            if (cachedWatermarkMillis == 0L) {
                initializeOrResetBaseline(appCtx, forceResetToNow = true)
                return
            }

            val now = System.currentTimeMillis()
            val minModMillis = maxOf(cachedWatermarkMillis, now - MAX_AUTO_DETECT_AGE_MILLIS)

            for (dirPath in getMonitoredDirectories(appCtx)) {
                val dir = File(dirPath)
                if (!dir.exists() || !dir.isDirectory) continue
                val files = dir.listFiles() ?: continue
                // 전체 파일을 매번 정렬하지 않고 기준 시각(minModMillis) 이후에 수정된 새 오디오 파일만 필터링!
                val recentCandidates = files.filter { f ->
                    f.isFile && f.lastModified() >= minModMillis && f.extension.lowercase() in SUPPORTED_AUDIO_EXTS
                }

                for (file in recentCandidates) {
                    if (isReadyNewCallRecordingFile(appCtx, file)) {
                        markFileAsSeen(appCtx, file, advanceWatermark = true)
                        enqueueFileAnalysis(appCtx, file.absolutePath, forceReprocess = false)
                    }
                }
            }

            scanSafTreeUriFiles(appCtx, onlyRegisterBaseline = false, seenSet = null)
        }

        private fun scanSafTreeUriFiles(
            context: Context,
            onlyRegisterBaseline: Boolean,
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
                val now = System.currentTimeMillis()
                ensureCacheLoaded(context)
                val watermark = if (cachedWatermarkMillis > 0L) cachedWatermarkMillis else now
                val currentSeen = seenSet ?: inMemorySeenPaths
                var seenChanged = false

                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val docId = cursor.getString(0) ?: continue
                        val name = cursor.getString(1) ?: continue
                        val lastMod = cursor.getLong(2)
                        val size = cursor.getLong(3)
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext !in SUPPORTED_AUDIO_EXTS || size < 512L) continue

                        val virtualKey = "saf://$docId"
                        if (onlyRegisterBaseline) {
                            currentSeen.add(virtualKey)
                        } else if (lastMod < watermark || (now - lastMod) > MAX_AUTO_DETECT_AGE_MILLIS) {
                            if (currentSeen.add(virtualKey)) {
                                seenChanged = true
                            }
                        } else if (virtualKey !in currentSeen && (now - lastMod) >= 1500L) {
                            currentSeen.add(virtualKey)
                            cachedWatermarkMillis = maxOf(watermark, lastMod)
                            seenChanged = true
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                            val targetFile = File(context.cacheDir, name)
                            context.contentResolver.openInputStream(docUri)?.use { input ->
                                FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                            }
                            if (lastMod > 0L) {
                                runCatching { targetFile.setLastModified(lastMod) }
                            }
                            if (targetFile.exists() && targetFile.length() > 512L) {
                                enqueueFileAnalysis(context, targetFile.absolutePath, forceReprocess = true)
                            }
                        }
                    }
                }

                if (!onlyRegisterBaseline && seenChanged) {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putStringSet(KEY_SEEN_PATHS, trimSeenSetIfNeeded(currentSeen.toMutableSet()))
                        .putLong(KEY_WATERMARK_MILLIS, cachedWatermarkMillis)
                        .apply()
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
            if (bestMod > 0L) {
                runCatching { targetFile.setLastModified(bestMod) }
            }
            targetFile.takeIf { it.exists() && it.length() > 0L }
        }.getOrNull()

        /**
         * 상시 폴링(3.5초 무한 루프)을 완전히 제거하고, 이벤트(FileObserver / ContentObserver / 통화 종료)가
         * 발생했을 때만 백그라운드 IO 코루틴에서 디바운싱하여 스캔을 수행합니다.
         */
        fun scheduleEventDrivenScan(context: Context, delayMillis: Long = 1200L) {
            val appCtx = context.applicationContext
            debouncedScanJob?.cancel()
            debouncedScanJob = ioScope.launch {
                if (delayMillis > 0L) delay(delayMillis)
                runMediaStoreAndDirectoryScanOnIo(appCtx)
                // 만약 방금 생성된 녹음 파일이 아직 쓰기 중(pendingFileSizes)이라면 최대 3회까지만 2초 간격으로 확인 후 즉시 종료
                var retryCount = 0
                while (pendingFileSizes.isNotEmpty() && retryCount < 3) {
                    retryCount++
                    delay(2000L)
                    runMediaStoreAndDirectoryScanOnIo(appCtx)
                }
            }
        }

        fun enqueueRecentMediaStoreScan(context: Context) {
            scheduleEventDrivenScan(context, delayMillis = 300L)
        }

        private fun runMediaStoreAndDirectoryScanOnIo(context: Context) {
            if (!isScanRunning.compareAndSet(false, true)) return
            try {
                val appCtx = context.applicationContext
                ensureCacheLoaded(appCtx)
                if (cachedWatermarkMillis == 0L) {
                    initializeOrResetBaseline(appCtx, forceResetToNow = true)
                    return
                }

                scanMonitoredDirectoriesForNewFiles(appCtx)

                val minAllowedSeconds = maxOf(
                    cachedWatermarkMillis / 1000L,
                    (System.currentTimeMillis() - MAX_AUTO_DETECT_AGE_MILLIS) / 1000L
                )

                val projection = arrayOf(
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.DATE_MODIFIED
                )
                val selection = "${MediaStore.Audio.Media.DATE_ADDED} >= ? OR ${MediaStore.Audio.Media.DATE_MODIFIED} >= ?"
                val selectionArgs = arrayOf(minAllowedSeconds.toString(), minAllowedSeconds.toString())
                val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

                runCatching {
                    appCtx.contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        selection,
                        selectionArgs,
                        sortOrder
                    )?.use { cursor ->
                        val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                        val nameIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                        var checked = 0
                        while (cursor.moveToNext() && checked < 5) {
                            checked++
                            val path = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                            val name = if (nameIdx >= 0) cursor.getString(nameIdx) else ""
                            if (!path.isNullOrBlank() && isLikelyCallRecording(appCtx, path, name ?: "")) {
                                val file = File(path)
                                if (isReadyNewCallRecordingFile(appCtx, file)) {
                                    markFileAsSeen(appCtx, file, advanceWatermark = true)
                                    enqueueFileAnalysis(appCtx, file.absolutePath, forceReprocess = false)
                                    break
                                }
                            }
                        }
                    }
                }
            } finally {
                isScanRunning.set(false)
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
            CallAnalysisRunner.triggerAnalysisNow(
                context = context.applicationContext,
                filePath = filePath,
                forceReprocess = forceReprocess
            )
        }
    }

    private val fileObservers = mutableListOf<FileObserver>()
    private var mediaStoreObserver: ContentObserver? = null
    private var audioModeListener: Any? = null

    override fun onCreate() {
        super.onCreate()
        initializeOrResetBaseline(applicationContext, forceResetToNow = false)
        startForegroundWithNotification()
        startWatchingCallDirectories()
        registerMediaStoreObserver()
        registerCallEndAudioModeTrigger()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESET_BASELINE, ACTION_RELOAD_DIRS -> {
                initializeOrResetBaseline(applicationContext, forceResetToNow = true)
                startWatchingCallDirectories()
                startForegroundWithNotification()
            }
            ACTION_FORCE_SCAN_LATEST -> {
                scheduleEventDrivenScan(applicationContext, delayMillis = 300L)
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
            "절전형 이벤트 감지 중 (대기 배터리 0%): $customDir"
        } else {
            "절전형 이벤트 감지 중 (통화 종료 시에만 작동 · 대기 배터리 0%)"
        }

        val notification = NotificationCompat.Builder(this, MONITOR_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("새 통화 녹음 절전 감지 중 (v3.5)")
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
                // 미디어 스캐너의 연속 알림을 1.5초 디바운싱하여 백그라운드 IO 스레드에서 단 1회만 스캔
                scheduleEventDrivenScan(applicationContext, delayMillis = 1500L)
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

    /**
     * 전화 상태 권한(READ_PHONE_STATE) 없이도 통화 종료 시점(MODE_IN_CALL -> MODE_NORMAL)을 감지하여
     * SAF 전용 폴더 사용자까지 대기 중 폴링(0%) 없이 통화 직후에만 정확히 스캔합니다.
     */
    private fun registerCallEndAudioModeTrigger() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
                var wasInCall = false
                val listener = AudioManager.OnModeChangedListener { mode ->
                    if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
                        wasInCall = true
                    } else if (wasInCall && mode == AudioManager.MODE_NORMAL) {
                        wasInCall = false
                        // 통화가 끝난 직후 파일 저장 완료 시점에 맞춰 이벤트 스캔 예약
                        scheduleEventDrivenScan(applicationContext, delayMillis = 2000L)
                    }
                }
                audioManager.addOnModeChangedListener(ContextCompat.getMainExecutor(this), listener)
                audioModeListener = listener
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startWatchingCallDirectories() {
        fileObservers.forEach { runCatching { it.stopWatching() } }
        fileObservers.clear()

        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        for (dirPath in getMonitoredDirectories(applicationContext)) {
            val dir = File(dirPath)
            if (dir.exists() && dir.isDirectory) {
                val obs = object : FileObserver(dir.absolutePath, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        val eventType = event and FileObserver.ALL_EVENTS
                        if ((eventType == FileObserver.CLOSE_WRITE || eventType == FileObserver.MOVED_TO) && path != null) {
                            val file = File(dir, path)
                            if (file.extension.lowercase() in SUPPORTED_AUDIO_EXTS) {
                                scheduleEventDrivenScan(applicationContext, delayMillis = 1200L)
                            }
                        }
                    }
                }
                obs.startWatching()
                fileObservers.add(obs)
            }
        }
    }

    override fun onDestroy() {
        debouncedScanJob?.cancel()
        fileObservers.forEach { runCatching { it.stopWatching() } }
        fileObservers.clear()
        mediaStoreObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val listener = audioModeListener as? AudioManager.OnModeChangedListener
                if (audioManager != null && listener != null) {
                    audioManager.removeOnModeChangedListener(listener)
                }
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
