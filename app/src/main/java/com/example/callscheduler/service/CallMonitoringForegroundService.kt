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

        // 장시간 통화(최대 20분)에서도 DATE_ADDED 기준 누락이 발생하지 않도록 20분 창을 허용하되,
        // 감시 시작 시각(Watermark) 이전에 생성/수정된 과거 녹음은 100% 원천 차단합니다.
        private const val MAX_AUTO_DETECT_AGE_MILLIS = 20 * 60 * 1000L
        private const val MAX_SEEN_CACHE_SIZE = 500

        const val ACTION_RESET_BASELINE = "com.example.callscheduler.ACTION_RESET_BASELINE"
        const val ACTION_RELOAD_DIRS = "com.example.callscheduler.ACTION_RELOAD_DIRS"
        const val ACTION_FORCE_SCAN_LATEST = "com.example.callscheduler.ACTION_FORCE_SCAN_LATEST"

        private val pendingFileSizes = ConcurrentHashMap<String, Pair<Long, Long>>() // path -> (lastSize, firstSeenMillis)
        private val analyzedFileSizes = ConcurrentHashMap<String, Long>() // path -> sizeWhenAnalyzed

        fun getMonitoredDirectories(context: Context): List<String> {
            return AiSettingsDataStore(context.applicationContext).getActiveMonitoredDirs()
        }

        private fun trimSeenSetIfNeeded(seen: MutableSet<String>): MutableSet<String> {
            if (seen.size <= MAX_SEEN_CACHE_SIZE) return seen
            val excess = seen.size - MAX_SEEN_CACHE_SIZE
            val trimmed = seen.drop(excess).toMutableSet()
            return trimmed
        }

        /**
         * 최초 설치/권한 승인 또는 사용자가 명시적으로 기준선 리셋(폴더 변경·목록 비우기)을 요청했을 때만
         * 현재 존재하는 모든 기존 녹음 파일(폴더 + MediaStore + SAF)을 seen에 등록하고 기준 시각(Watermark)을 설정합니다.
         * 이미 감시 중인 상태에서 단순히 앱 화면을 열거나 서비스가 재시작될 때는 기존 Watermark를 유지하여,
         * 통화 종료 직후 앱을 열었을 때 방금 끝난 새 녹음까지 과거 파일로 덮어써지는 현상을 완벽히 방지합니다!
         */
        fun initializeOrResetBaseline(context: Context, forceResetToNow: Boolean = false) {
            val appCtx = context.applicationContext
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val existingWatermark = prefs.getLong(KEY_WATERMARK_MILLIS, 0L)
            val existingSeen = prefs.getStringSet(KEY_SEEN_PATHS, emptySet()) ?: emptySet()

            // 이미 기준 시각(existingWatermark > 0L)이 설정되어 있고 강제 리셋 요청이 아니라면 기존 기준선을 그대로 보존!
            if (!forceResetToNow && existingWatermark > 0L && existingSeen.isNotEmpty()) {
                return
            }

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

            // 2) MediaStore 상의 기존 통화 녹음 파일들도 모두 seen에 등록 (Scoped Storage에서 listFiles 누락 대비)
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
            prefs.edit()
                .putStringSet(KEY_SEEN_PATHS, boundedSeen)
                .putLong(KEY_WATERMARK_MILLIS, newWatermark)
                .apply()
        }

        /**
         * 통화 종료 후 새로 생성된 녹음 파일인지 엄격히 검증합니다.
         * 1) 파일 수정 시각(lastModified)이 감시 시작 기준 시각(Watermark) 이전이면 과거 파일이므로 즉시 seen에 넣고 제외!
         * 2) 파일 수정 시각이 현재 시각으로부터 20분(MAX_AUTO_DETECT_AGE_MILLIS)보다 오래되었으면 과거 파일이므로 즉시 제외!
         * 3) 통화 중 파일 크기가 증가하는 동안에는 대기하고, 쓰기가 완료(안정화)된 직후에만 1회 분석합니다.
         */
        private fun isReadyNewCallRecordingFile(context: Context, file: File): Boolean {
            if (!file.exists() || !file.isFile) return false
            val currentLength = file.length()
            if (currentLength < 512L) return false
            if (file.extension.lowercase() !in SUPPORTED_AUDIO_EXTS) return false

            val appCtx = context.applicationContext
            val path = file.absolutePath
            val now = System.currentTimeMillis()
            val lastMod = file.lastModified()
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val watermark = prefs.getLong(KEY_WATERMARK_MILLIS, now)
            val seen = prefs.getStringSet(KEY_SEEN_PATHS, emptySet()) ?: emptySet()

            // 핵심 방어벽: 감시 시작 시각(watermark) 이전에 만들어진 파일이거나, 오래된 과거 파일은 절대 자동 분석하지 않음!
            if (lastMod < watermark || (now - lastMod) > MAX_AUTO_DETECT_AGE_MILLIS) {
                if (path !in seen) {
                    markFileAsSeen(appCtx, file, advanceWatermark = false)
                }
                return false
            }

            val prevAnalyzedSize = analyzedFileSizes[path] ?: 0L
            if (path in seen) {
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
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val seen = (prefs.getStringSet(KEY_SEEN_PATHS, emptySet()) ?: emptySet()).toMutableSet()
            seen.add(file.absolutePath)
            analyzedFileSizes[file.absolutePath] = file.length()
            val boundedSeen = trimSeenSetIfNeeded(seen)
            val editor = prefs.edit().putStringSet(KEY_SEEN_PATHS, boundedSeen)
            if (advanceWatermark) {
                val currentWatermark = prefs.getLong(KEY_WATERMARK_MILLIS, System.currentTimeMillis())
                editor.putLong(KEY_WATERMARK_MILLIS, maxOf(currentWatermark, file.lastModified()))
            }
            editor.apply()
        }

        /**
         * 사용자가 메인 화면에서 [(수동 실행) 폴더 내 가장 최근 녹음 1건 지금 바로 분석하기] 버튼을 직접 눌렀을 때만
         * 과거 파일 여부를 무시하고 가장 최근 녹음 파일 1개를 찾아 즉시 분석합니다.
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
         * 감시 시작 시각(Watermark) 이후에 새로 생성된 녹음 파일만 스캔합니다.
         */
        fun scanMonitoredDirectoriesForNewFiles(context: Context) {
            val appCtx = context.applicationContext
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getLong(KEY_WATERMARK_MILLIS, 0L) == 0L) {
                initializeOrResetBaseline(appCtx, forceResetToNow = true)
                return
            }

            for (dirPath in getMonitoredDirectories(appCtx)) {
                val dir = File(dirPath)
                if (!dir.exists() || !dir.isDirectory) continue
                val files = dir.listFiles() ?: continue
                val newestFirst = files
                    .filter { it.isFile && it.extension.lowercase() in SUPPORTED_AUDIO_EXTS }
                    .sortedByDescending { it.lastModified() }
                    .take(5)

                for (file in newestFirst) {
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
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val watermark = prefs.getLong(KEY_WATERMARK_MILLIS, now)
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
                        if (onlyRegisterBaseline) {
                            currentSeen.add(virtualKey)
                        } else if (lastMod < watermark || (now - lastMod) > MAX_AUTO_DETECT_AGE_MILLIS) {
                            if (virtualKey !in currentSeen) {
                                currentSeen.add(virtualKey)
                                prefs.edit().putStringSet(KEY_SEEN_PATHS, trimSeenSetIfNeeded(currentSeen)).apply()
                            }
                        } else if (virtualKey !in currentSeen && (now - lastMod) >= 1500L) {
                            currentSeen.add(virtualKey)
                            prefs.edit()
                                .putStringSet(KEY_SEEN_PATHS, trimSeenSetIfNeeded(currentSeen))
                                .putLong(KEY_WATERMARK_MILLIS, maxOf(watermark, lastMod))
                                .apply()
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
         * MediaStore 스캔 시 반드시 감시 시작 시각(watermarkSeconds) 이후에 추가/수정된 오디오만 조회하도록
         * SQL WHERE 조건(selection)을 강제 적용하여 과거 통화 내역이 줄줄이 딸려오는 현상을 원천 차단합니다.
         */
        fun enqueueRecentMediaStoreScan(context: Context) {
            val appCtx = context.applicationContext
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val watermarkMillis = prefs.getLong(KEY_WATERMARK_MILLIS, 0L)
            if (watermarkMillis == 0L) {
                initializeOrResetBaseline(appCtx, forceResetToNow = true)
                return
            }

            scanMonitoredDirectoriesForNewFiles(appCtx)

            val minAllowedSeconds = maxOf(
                watermarkMillis / 1000L,
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
    private val periodicHandler = Handler(Looper.getMainLooper())
    private val periodicScanRunnable = object : Runnable {
        override fun run() {
            runCatching { enqueueRecentMediaStoreScan(applicationContext) }
            periodicHandler.postDelayed(this, 3500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 최초 설치 시에만 현재 시각으로 기준선을 설정하고, 이미 동작 중이던 서비스가 재시작될 때는 기존 Watermark를 유지하여 통화 직후 누락 방지
        initializeOrResetBaseline(applicationContext, forceResetToNow = false)
        startForegroundWithNotification()
        startWatchingCallDirectories()
        registerMediaStoreObserver()
        periodicHandler.postDelayed(periodicScanRunnable, 3500L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESET_BASELINE, ACTION_RELOAD_DIRS -> {
                initializeOrResetBaseline(applicationContext, forceResetToNow = true)
                startWatchingCallDirectories()
                startForegroundWithNotification()
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
            "지정 경로 감시 중 (과거 녹음 자동분석 차단됨): $customDir"
        } else {
            "삼성 전화 + T전화 새 녹음만 감시 중 (과거 녹음 제외)"
        }

        val notification = NotificationCompat.Builder(this, MONITOR_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("새 통화 녹음만 실시간 감지 중 (v3.4)")
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

        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
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
                                    markFileAsSeen(applicationContext, file, advanceWatermark = true)
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
