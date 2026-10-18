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
import java.io.File

class CallMonitoringForegroundService : Service() {

    companion object {
        val CANDIDATE_CALL_DIRS = listOf(
            "/storage/emulated/0/Recordings/Call",
            "/storage/emulated/0/Call",
            "/storage/emulated/0/Recordings",
            "/storage/emulated/0/MIUI/sound_recorder/call_rec"
        )
        private const val MONITOR_CHANNEL_ID = "call_monitor_service_channel"
        private const val NOTIFICATION_ID = 9001
        private const val PREFS_NAME = "call_monitor_watermark_prefs"
        private const val KEY_WATERMARK_MILLIS = "watermark_epoch_millis"
        private const val KEY_SEEN_PATHS = "seen_file_paths"
        const val ACTION_RESET_BASELINE = "com.example.callscheduler.ACTION_RESET_BASELINE"

        /**
         * 감시 시작 시점 기준 시각(Watermark)을 현재 시각으로 설정하고,
         * 기존에 폴더/MediaStore에 이미 있던 녹음 파일들을 스냅샷 처리하여 절대 긁어오지 않도록 차단합니다.
         */
        fun initializeOrResetBaseline(context: Context, forceResetToNow: Boolean = false) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val existingWatermark = prefs.getLong(KEY_WATERMARK_MILLIS, 0L)

            if (forceResetToNow || existingWatermark == 0L) {
                val seen = (prefs.getStringSet(KEY_SEEN_PATHS, emptySet()) ?: emptySet()).toMutableSet()
                // 현재 폴더에 이미 존재하는 모든 녹음 파일을 seen 목록에 등록하여 기존 파일 분석 원천 차단
                for (dirPath in CANDIDATE_CALL_DIRS) {
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
            if (!file.exists() || file.length() <= 0L) return false
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val watermark = prefs.getLong(KEY_WATERMARK_MILLIS, System.currentTimeMillis())
            val seen = prefs.getStringSet(KEY_SEEN_PATHS, emptySet()) ?: emptySet()

            if (file.absolutePath in seen) return false
            // 감시 시작 시각(watermark) 이전에 생성/수정된 기존 파일은 무조건 제외
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
         * MediaStore 변화 또는 통화 종료 시 호출되며, 오직 감시 시작 시각(Watermark) 이후에
         * 새로 추가된 통화 녹음 파일만 찾아 큐에 넣습니다. (기존 파일 스캔 원천 차단)
         */
        fun enqueueRecentMediaStoreScan(context: Context) {
            val appCtx = context.applicationContext
            initializeOrResetBaseline(appCtx, forceResetToNow = false)
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val watermarkMillis = prefs.getLong(KEY_WATERMARK_MILLIS, System.currentTimeMillis())
            val watermarkSeconds = (watermarkMillis / 1000L) - 2L

            val projection = arrayOf(
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATE_MODIFIED
            )
            // DATE_ADDED가 감시 시작 시점 이후인 신규 오디오만 조회
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
                        if (!path.isNullOrBlank() && isLikelyCallRecording(path, name ?: "")) {
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

        private fun isLikelyCallRecording(path: String, displayName: String): Boolean {
            val lowerPath = path.lowercase()
            val lowerName = displayName.lowercase()
            val validExt = lowerPath.endsWith(".m4u") || lowerPath.endsWith(".m4a") || lowerPath.endsWith(".amr") ||
                lowerPath.endsWith(".mp3") || lowerPath.endsWith(".wav") || lowerPath.endsWith(".ogg")
            val isCallFolderOrName = lowerPath.contains("/call/") || lowerPath.contains("/recordings/") ||
                lowerName.contains("통화") || lowerName.contains("call")
            return validExt && isCallFolderOrName
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

    override fun onCreate() {
        super.onCreate()
        initializeOrResetBaseline(applicationContext, forceResetToNow = false)
        startForegroundWithNotification()
        startWatchingCallDirectories()
        registerMediaStoreObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESET_BASELINE) {
            initializeOrResetBaseline(applicationContext, forceResetToNow = true)
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

        val notification = NotificationCompat.Builder(this, MONITOR_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("새 통화 녹음 실시간 감지 중")
            .setContentText("기존 파일은 제외하고 지금부터 새로 생성되는 통화 녹음만 분석합니다.")
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
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                enqueueRecentMediaStoreScan(applicationContext)
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
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        for (dirPath in CANDIDATE_CALL_DIRS) {
            val dir = File(dirPath)
            if (!dir.exists()) {
                runCatching { dir.mkdirs() }
            }
            if (dir.exists() && dir.isDirectory) {
                val obs = object : FileObserver(dir.absolutePath, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        val eventType = event and FileObserver.ALL_EVENTS
                        if ((eventType == FileObserver.CLOSE_WRITE || eventType == FileObserver.MOVED_TO) && path != null) {
                            val file = File(dir, path)
                            if (file.extension.lowercase() in listOf("m4u", "m4a", "amr", "mp3", "wav", "ogg", "3gp", "aac")) {
                                if (isStrictlyNewAndUnseenFile(applicationContext, file)) {
                                    markFileAsSeenAndAdvanceWatermark(applicationContext, file)
                                    enqueueFileAnalysis(applicationContext, file.absolutePath)
                                }
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
        fileObservers.forEach { runCatching { it.stopWatching() } }
        fileObservers.clear()
        mediaStoreObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
