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
        const val ACTION_SCAN_RECENT = "com.example.callscheduler.ACTION_SCAN_RECENT"

        fun enqueueRecentMediaStoreScan(context: Context) {
            val projection = arrayOf(
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_ADDED
            )
            val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            runCatching {
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
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
                            if (file.exists() && file.length() > 0L) {
                                enqueueFileAnalysis(context, file.absolutePath)
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
        startForegroundWithNotification()
        startWatchingCallDirectories()
        registerMediaStoreObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SCAN_RECENT) {
            enqueueRecentMediaStoreScan(applicationContext)
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
            .setContentTitle("통화 녹음 자동 일정 감지 중")
            .setContentText("MediaStore 및 통화 녹음 폴더를 실시간 감시하고 있습니다.")
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
        fileObservers.forEach { runCatching { it.stopWatching() } }
        fileObservers.clear()
        mediaStoreObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
