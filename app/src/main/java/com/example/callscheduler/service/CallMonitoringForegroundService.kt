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
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.callscheduler.data.local.datastore.AiSettingsDataStore
import java.io.File

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

        fun getMonitoredDirectories(context: Context): List<String> {
            return AiSettingsDataStore(context.applicationContext).getActiveMonitoredDirs()
        }

        /**
         * 감시 시작 또는 폴더 경로 변경 시 기준 시각(Watermark)을 현재 시각으로 갱신하고,
         * 감시 폴더에 이미 들어있는 기존 녹음 파일들을 모두 seen 목록에 등록해 기존 파일 분석을 차단합니다.
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
                            if (f.isFile) seen.add(f.absolutePath)
                        }
                    }
                }
                prefs.edit()
                    .putLong(KEY_WATERMARK_MILLIS, now)
                    .putStringSet(KEY_SEEN_PATHS, seen)
                    .apply()
            }
        }

        private fun isStrictlyNewAndUnseenFile(context: Context, file: File): Boolean {
            if (!file.exists() || !file.isFile || file.length() <= 0L) return false
            if (file.extension.lowercase() !in SUPPORTED_AUDIO_EXTS) return false
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val watermark = prefs.getLong(KEY_WATERMARK_MILLIS, System.currentTimeMillis())
            val seen = prefs.getStringSet(KEY_SEEN_PATHS, emptySet()) ?: emptySet()

            if (file.absolutePath in seen) return false
            if (file.lastModified() < watermark) return false
            return true
        }

        private fun markFileAsSeenAndAdvanceWatermark(context: Context, file: File) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentWatermark = prefs.getLong(KEY_WATERMARK_MILLIS, System.currentTimeMillis())
            val seen = (prefs.getStringSet(KEY_SEEN_PATHS, emptySet()) ?: emptySet()).toMutableSet()
            seen.add(file.absolutePath)
            val nextWatermark = maxOf(currentWatermark, file.lastModified())
            prefs.edit()
                .putLong(KEY_WATERMARK_MILLIS, nextWatermark)
                .putStringSet(KEY_SEEN_PATHS, seen)
                .apply()
        }

        /**
         * T전화(.nomedia가 있는 TPhoneCallRecords 등)나 사용자 지정 폴더에서 MediaStore 인덱싱이 생략되더라도
         * 감시 시작 시각(Watermark) 이후 새로 생성된 녹음 파일을 즉시 찾아냅니다.
         */
        fun scanMonitoredDirectoriesForNewFiles(context: Context) {
            val appCtx = context.applicationContext
            initializeOrResetBaseline(appCtx, forceResetToNow = false)
            for (dirPath in getMonitoredDirectories(appCtx)) {
                val dir = File(dirPath)
                if (!dir.exists() || !dir.isDirectory) continue
                val files = dir.listFiles() ?: continue
                val newestFirst = files.sortedByDescending { it.lastModified() }.take(5)
                for (file in newestFirst) {
                    if (isStrictlyNewAndUnseenFile(appCtx, file)) {
                        markFileAsSeenAndAdvanceWatermark(appCtx, file)
                        enqueueFileAnalysis(appCtx, file.absolutePath)
                    }
                }
            }
        }

        fun enqueueRecentMediaStoreScan(context: Context) {
            val appCtx = context.applicationContext
            initializeOrResetBaseline(appCtx, forceResetToNow = false)
            scanMonitoredDirectoriesForNewFiles(appCtx)

            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val watermarkMillis = prefs.getLong(KEY_WATERMARK_MILLIS, System.currentTimeMillis())
            val watermarkSeconds = (watermarkMillis / 1000L) - 2L

            val projection = arrayOf(
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATE_MODIFIED
            )
            val selection = "${MediaStore.Audio.Media.DATE_ADDED} >= ? OR ${MediaStore.Audio.Media.DATE_MODIFIED} >= ?"
            val selectionArgs = arrayOf(watermarkSeconds.toString(), watermarkSeconds.toString())
            val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

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
                            if (isStrictlyNewAndUnseenFile(appCtx, file)) {
                                markFileAsSeenAndAdvanceWatermark(appCtx, file)
                                enqueueFileAnalysis(appCtx, file.absolutePath)
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

        fun enqueueFileAnalysis(context: Context, filePath: String) {
            val inputData = Data.Builder()
                .putString("KEY_FILE_PATH", filePath)
                .build()
            val request = OneTimeWorkRequestBuilder<CallProcessWorker>()
                .setInputData(inputData)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "analyze_${filePath.hashCode()}",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    private val fileObservers = mutableListOf<FileObserver>()
    private var mediaStoreObserver: ContentObserver? = null
    private val periodicHandler = Handler(Looper.getMainLooper())
    private val periodicScanRunnable = object : Runnable {
        override fun run() {
            runCatching { scanMonitoredDirectoriesForNewFiles(applicationContext) }
            periodicHandler.postDelayed(this, 4000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        initializeOrResetBaseline(applicationContext, forceResetToNow = false)
        startForegroundWithNotification()
        startWatchingCallDirectories()
        registerMediaStoreObserver()
        periodicHandler.postDelayed(periodicScanRunnable, 4000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESET_BASELINE -> {
                initializeOrResetBaseline(applicationContext, forceResetToNow = true)
                startWatchingCallDirectories()
                startForegroundWithNotification()
            }
            ACTION_RELOAD_DIRS -> {
                initializeOrResetBaseline(applicationContext, forceResetToNow = true)
                startWatchingCallDirectories()
                startForegroundWithNotification()
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
            "삼성 기본전화 + T전화(TPhoneCallRecords) 자동 감시 중"
        }

        val notification = NotificationCompat.Builder(this, MONITOR_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("새 통화 녹음 실시간 감지 중")
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
                enqueueRecentMediaStoreScan(applicationContext)
                handler.postDelayed({
                    enqueueRecentMediaStoreScan(applicationContext)
                }, 2500L)
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
                            if (isStrictlyNewAndUnseenFile(applicationContext, file)) {
                                markFileAsSeenAndAdvanceWatermark(applicationContext, file)
                                enqueueFileAnalysis(applicationContext, file.absolutePath)
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
        periodicHandler.removeCallbacksAndMessages(null)
        fileObservers.forEach { runCatching { it.stopWatching() } }
        fileObservers.clear()
        mediaStoreObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
