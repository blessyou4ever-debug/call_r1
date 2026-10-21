package com.example.callscheduler.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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

        // 장시간 통화(최대 30분) 후에도 파일 생성 시각(DATE_ADDED)이나 수정 시각 기준으로 누락되지 않도록 30분 허용
        // (감시 시작 기준선 Watermark 이전의 과거 파일은 100% 차단됨)
        private const val MAX_AUTO_DETECT_AGE_MILLIS = 30 * 60 * 1000L
        private const val MAX_SEEN_CACHE_SIZE = 500

        const val ACTION_RESET_BASELINE = "com.example.callscheduler.ACTION_RESET_BASELINE"
        const val ACTION_RELOAD_DIRS = "com.example.callscheduler.ACTION_RELOAD_DIRS"
        const val ACTION_FORCE_SCAN_LATEST = "com.example.callscheduler.ACTION_FORCE_SCAN_LATEST"

        private val pendingFileSizes = ConcurrentHashMap<String, Pair<Long, Long>>() // path -> (lastSize, firstSeenMillis)
        private val analyzedFileSizes = ConcurrentHashMap<String, Long>() // path -> sizeWhenAnalyzed
        private val inMemorySeenPaths = ConcurrentHashMap.newKeySet<String>()
        @Volatile
        private var cachedWatermarkMillis: Long = 0L

        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val isScanRunning = AtomicBoolean(false)
        @Volatile
        private var multiStageScanJob: Job? = null

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
         * 현재 전화 통화가 진행 중인지 확인합니다 (권한 불필요).
         * 통화 진행 중에는 녹음 파일이 생성되더라도 아직 녹음이 끝나지 않았으므로,
         * 섣불리 seen 처리하거나 불완전한 파일을 분석하지 않고 통화 종료 시점까지 대기합니다.
         */
        private fun isPhoneCallCurrentlyActive(context: Context): Boolean {
            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            val mode = am.mode
            return mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION ||
                mode == AudioManager.MODE_RINGTONE
        }

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
            // 통화가 아직 진행 중이면 녹음 파일이 계속 쓰이는 중이므로 pending에 등록만 하고 통화 종료 후 분석!
            if (isPhoneCallCurrentlyActive(appCtx)) {
                pendingFileSizes[file.absolutePath] = Pair(currentLength, System.currentTimeMillis())
                return false
            }

            ensureCacheLoaded(appCtx)
            val path = file.absolutePath
            val now = System.currentTimeMillis()
            val lastMod = file.lastModified()
            val watermark = if (cachedWatermarkMillis > 0L) cachedWatermarkMillis else now

            // 감시 시작 시각(watermark) 이전에 만들어진 과거 파일은 자동 분석 제외
            if (lastMod < watermark || (now - lastMod) > MAX_AUTO_DETECT_AGE_MILLIS) {
                if (path !in inMemorySeenPaths) {
                    markFileAsSeen(appCtx, file, advanceWatermark = false)
                }
                return false
            }

            val prevAnalyzedSize = analyzedFileSizes[path] ?: 0L
            if (path in inMemorySeenPaths) {
                if (prevAnalyzedSize <= 0L || currentLength <= prevAnalyzedSize + 2048L) {
                    return false
                }
            }

            val prevPending = pendingFileSizes[path]
            val ageSinceModified = now - lastMod

            if (prevPending == null) {
                if (ageSinceModified >= 900L) {
                    return true
                }
                pendingFileSizes[path] = Pair(currentLength, now)
                return false
            } else {
                val (lastSize, firstSeenTime) = prevPending
                if (currentLength == lastSize || (now - firstSeenTime) >= 2500L || ageSinceModified >= 900L) {
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

        /**
         * 새 녹음 파일을 발견하여 분석 큐에 넣었으면 true를 반환합니다.
         */
        fun scanMonitoredDirectoriesForNewFiles(context: Context): Boolean {
            val appCtx = context.applicationContext
            ensureCacheLoaded(appCtx)
            if (cachedWatermarkMillis == 0L) {
                initializeOrResetBaseline(appCtx, forceResetToNow = true)
                return false
            }

            if (isPhoneCallCurrentlyActive(appCtx)) {
                return false
            }

            var foundNew = false
            val now = System.currentTimeMillis()
            val minModMillis = maxOf(cachedWatermarkMillis, now - MAX_AUTO_DETECT_AGE_MILLIS)

            for (dirPath in getMonitoredDirectories(appCtx)) {
                val dir = File(dirPath)
                if (!dir.exists() || !dir.isDirectory) continue
                val files = dir.listFiles() ?: continue
                val recentCandidates = files.filter { f ->
                    f.isFile && f.lastModified() >= minModMillis && f.extension.lowercase() in SUPPORTED_AUDIO_EXTS
                }.sortedByDescending { it.lastModified() }

                for (file in recentCandidates.take(5)) {
                    if (isReadyNewCallRecordingFile(appCtx, file)) {
                        markFileAsSeen(appCtx, file, advanceWatermark = true)
                        enqueueFileAnalysis(appCtx, file.absolutePath, forceReprocess = false)
                        foundNew = true
                    }
                }
            }

            if (scanSafTreeUriFiles(appCtx, onlyRegisterBaseline = false, seenSet = null)) {
                foundNew = true
            }
            return foundNew
        }

        private fun scanSafTreeUriFiles(
            context: Context,
            onlyRegisterBaseline: Boolean,
            seenSet: MutableSet<String>?
        ): Boolean {
            val treeUriStr = AiSettingsDataStore(context).getCustomTreeUri()
            if (treeUriStr.isBlank()) return false
            var triggeredAnalysis = false
            runCatching {
                val treeUri = Uri.parse(treeUriStr)
                val treeDocId = DocumentsContract.getTreeDocumentId(treeUri) ?: return false
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
                        } else if (virtualKey !in currentSeen) {
                            if ((now - lastMod) < 900L) {
                                // 파일이 방금 생성되어 쓰기 중이면 pending에 넣어 다음 단계 스캔(2.5초 뒤)에서 확정
                                pendingFileSizes[virtualKey] = Pair(size, now)
                            } else {
                                pendingFileSizes.remove(virtualKey)
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
                                    triggeredAnalysis = true
                                }
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
            return triggeredAnalysis
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
         * 통화 종료 또는 파일 이벤트 발생 시:
         * 1) 화면이 꺼진 절전(Doze) 상태에서도 스캔 코루틴이 멈추지 않도록 최대 15초짜리 단기 WakeLock을 획득합니다.
         * 2) 전화 앱(삼성 전화 / T전화)이 통화 종료 후 녹음 파일을 인코딩·저장·미디어스캔하는 지연 시간(1~12초)을 모두 커버하기 위해
         *    [1.5초 -> 3.5초 -> 7.0초 -> 12.0초] 총 4단계 확인을 수행하되, 새 녹음을 찾는 즉시 조기 종료하고 WakeLock을 해제합니다.
         */
        fun scheduleEventDrivenScan(
            context: Context,
            delayMillis: Long = 1200L,
            isCallEndBurst: Boolean = false
        ) {
            val appCtx = context.applicationContext
            multiStageScanJob?.cancel()
            multiStageScanJob = ioScope.launch {
                val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CallScheduler:PostCallScanWakeLock")?.apply {
                    setReferenceCounted(false)
                    runCatching { acquire(15_000L) }
                }
                try {
                    val waitIntervals = if (isCallEndBurst) {
                        // 통화 종료 직후에는 1.5초, +2.5초(4초), +3.5초(7.5초), +4.5초(12초)까지 최대 4회 다단계 스캔
                        listOf(delayMillis.coerceAtLeast(1200L), 2500L, 3500L, 4500L)
                    } else {
                        // 일반 파일/MediaStore 이벤트 시 1.2초, +2.5초 총 2회 확인
                        listOf(delayMillis.coerceAtLeast(400L), 2500L)
                    }

                    for (waitMs in waitIntervals) {
                        delay(waitMs)
                        // 아직 통화 중이라면 통화가 끝날 때까지 기다림
                        if (isPhoneCallCurrentlyActive(appCtx)) continue
                        val detected = runMediaStoreAndDirectoryScanOnIo(appCtx)
                        if (detected && pendingFileSizes.isEmpty()) {
                            break
                        }
                    }
                } finally {
                    runCatching {
                        if (wakeLock?.isHeld == true) {
                            wakeLock.release()
                        }
                    }
                }
            }
        }

        fun enqueueRecentMediaStoreScan(context: Context) {
            scheduleEventDrivenScan(context, delayMillis = 400L, isCallEndBurst = false)
        }

        private fun runMediaStoreAndDirectoryScanOnIo(context: Context): Boolean {
            if (!isScanRunning.compareAndSet(false, true)) return false
            try {
                val appCtx = context.applicationContext
                ensureCacheLoaded(appCtx)
                if (cachedWatermarkMillis == 0L) {
                    initializeOrResetBaseline(appCtx, forceResetToNow = true)
                    return false
                }

                if (isPhoneCallCurrentlyActive(appCtx)) return false

                var foundAny = scanMonitoredDirectoriesForNewFiles(appCtx)

                val minAllowedSeconds = maxOf(
                    (cachedWatermarkMillis / 1000L) - 5L,
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
                        while (cursor.moveToNext() && checked < 8) {
                            checked++
                            val path = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                            val name = if (nameIdx >= 0) cursor.getString(nameIdx) else ""
                            if (!path.isNullOrBlank() && isLikelyCallRecording(appCtx, path, name ?: "")) {
                                val file = File(path)
                                if (isReadyNewCallRecordingFile(appCtx, file)) {
                                    markFileAsSeen(appCtx, file, advanceWatermark = true)
                                    enqueueFileAnalysis(appCtx, file.absolutePath, forceReprocess = false)
                                    foundAny = true
                                    break
                                }
                            }
                        }
                    }
                }
                return foundAny
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
                lowerPath.contains("/recordings/call") ||
                lowerPath.contains("tphonecallrecords") ||
                lowerPath.contains("tphone") ||
                lowerPath.contains("call_rec") ||
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
    private var audioRecordingCallback: AudioManager.AudioRecordingCallback? = null

    override fun onCreate() {
        super.onCreate()
        initializeOrResetBaseline(applicationContext, forceResetToNow = false)
        startForegroundWithNotification()
        startWatchingCallDirectories()
        registerMediaStoreObserver()
        registerCallEndTriggers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESET_BASELINE, ACTION_RELOAD_DIRS -> {
                initializeOrResetBaseline(applicationContext, forceResetToNow = true)
                startWatchingCallDirectories()
                startForegroundWithNotification()
            }
            ACTION_FORCE_SCAN_LATEST -> {
                scheduleEventDrivenScan(applicationContext, delayMillis = 300L, isCallEndBurst = true)
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
            "통화 종료 즉시 자동 감지 중 (대기 배터리 0%): $customDir"
        } else {
            "통화 종료 즉시 자동 감지 중 (대기 배터리 0%)"
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
                scheduleEventDrivenScan(applicationContext, delayMillis = 1200L, isCallEndBurst = true)
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
     * 위험 권한(READ_PHONE_STATE) 없이 통화 종료 및 통화 녹음 완료 시점을 2중으로 정확히 포착합니다:
     * 1) AudioRecordingCallback (API 24+): 삼성 전화·T전화의 통화 녹음 세션이 종료되는 정확한 순간 포착
     * 2) OnModeChangedListener (API 31+): 통화 오디오 모드(MODE_IN_CALL)가 일반(MODE_NORMAL)로 복귀하는 순간 포착
     */
    private fun registerCallEndTriggers() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        // 1) 통화 녹음기(마이크/통화 스트림) 종료 감지 (모든 안드로이드 버전 동작, 대기 전력 0%)
        runCatching {
            var hadActiveRecording = false
            val recCallback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                    super.onRecordingConfigChanged(configs)
                    val currentlyRecording = !configs.isNullOrEmpty()
                    if (currentlyRecording) {
                        hadActiveRecording = true
                    } else if (hadActiveRecording) {
                        hadActiveRecording = false
                        // 통화 녹음기가 파일 쓰기를 마치고 닫힌 직후이므로 다단계 스캔(1.5s, 4s, 7.5s, 12s) 즉시 가동!
                        scheduleEventDrivenScan(applicationContext, delayMillis = 1500L, isCallEndBurst = true)
                    }
                }
            }
            audioManager.registerAudioRecordingCallback(recCallback, Handler(Looper.getMainLooper()))
            audioRecordingCallback = recCallback
        }

        // 2) 통화 오디오 모드 종료 감지 (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                var wasInCall = false
                val listener = AudioManager.OnModeChangedListener { mode ->
                    if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE) {
                        wasInCall = true
                    } else if (wasInCall && mode == AudioManager.MODE_NORMAL) {
                        wasInCall = false
                        scheduleEventDrivenScan(applicationContext, delayMillis = 1500L, isCallEndBurst = true)
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

        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE
        for (dirPath in getMonitoredDirectories(applicationContext)) {
            val dir = File(dirPath)
            if (dir.exists() && dir.isDirectory) {
                val obs = object : FileObserver(dir.absolutePath, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path != null) {
                            val ext = path.substringAfterLast('.', "").lowercase()
                            if (ext in SUPPORTED_AUDIO_EXTS) {
                                scheduleEventDrivenScan(applicationContext, delayMillis = 1200L, isCallEndBurst = true)
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
        multiStageScanJob?.cancel()
        fileObservers.forEach { runCatching { it.stopWatching() } }
        fileObservers.clear()
        mediaStoreObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioRecordingCallback?.let { cb ->
            runCatching { audioManager?.unregisterAudioRecordingCallback(cb) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
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
