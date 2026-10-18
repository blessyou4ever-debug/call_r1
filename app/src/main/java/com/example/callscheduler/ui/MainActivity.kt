package com.example.callscheduler.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.callscheduler.data.calendar.CalendarSyncResult
import com.example.callscheduler.data.calendar.GoogleCalendarSyncManager
import com.example.callscheduler.data.local.datastore.AiSettingsDataStore
import com.example.callscheduler.data.local.datastore.EngineMode
import com.example.callscheduler.data.local.db.ActionItemEntity
import com.example.callscheduler.data.local.db.AppDatabase
import com.example.callscheduler.data.local.db.CallRecordEntity
import com.example.callscheduler.service.CallMonitoringForegroundService
import com.example.callscheduler.service.CallProcessWorker
import com.example.callscheduler.service.ConfirmationNotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settingsDataStore = AiSettingsDataStore(this)
        val dao = AppDatabase.getInstance(this).dao()
        val calendarManager = GoogleCalendarSyncManager(this)

        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val currentMode by settingsDataStore.engineModeFlow.collectAsState(initial = EngineMode.AUTO_FALLBACK)
                val apiKey by settingsDataStore.apiKeyFlow.collectAsState(initial = "")
                val actionItems by dao.observeAllActionItems().collectAsState(initial = emptyList())
                val callRecords by dao.observeAllCallRecords().collectAsState(initial = emptyList())
                var apiKeyInput by remember(apiKey) { mutableStateOf(apiKey) }
                var customTestText by remember {
                    mutableStateOf("김대리님, 모레 오전 10시 반에 판교역 스타벅스에서 프로젝트 미팅 진행하시죠. 요청하신 견적서 자료도 검토해서 오늘 저녁에 다시 연락드리겠습니다.")
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { grantMap ->
                    val serviceIntent = Intent(this, CallMonitoringForegroundService::class.java).apply {
                        action = CallMonitoringForegroundService.ACTION_SCAN_RECENT
                    }
                    runCatching {
                        ContextCompat.startForegroundService(this, serviceIntent)
                    }
                    val deniedCount = grantMap.values.count { !it }
                    val msg = if (deniedCount == 0) {
                        "모든 권한이 승인되어 실시간 통화 녹음 감시 및 최근 녹음 스캔을 시작했습니다."
                    } else {
                        "감시 서비스를 시작했습니다. (일부 권한이 거부되어 캘린더 인텐트 연동 모드로 동작할 수 있습니다)"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }

                val audioPickerLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    if (uri != null) {
                        scope.launch {
                            val copiedFile = copyUriToCacheFile(this@MainActivity, uri)
                            if (copiedFile != null) {
                                val input = Data.Builder()
                                    .putString("KEY_FILE_PATH", copiedFile.absolutePath)
                                    .putBoolean("KEY_FORCE_REPROCESS", true)
                                    .build()
                                WorkManager.getInstance(this@MainActivity).enqueue(
                                    OneTimeWorkRequestBuilder<CallProcessWorker>()
                                        .setInputData(input)
                                        .build()
                                )
                                Toast.makeText(
                                    this@MainActivity,
                                    "선택한 녹음 파일(${copiedFile.name}) 분석을 시작했습니다.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(this@MainActivity, "오디오 파일을 읽을 수 없습니다.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                Scaffold(
                    topBar = {
                        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("통화 녹음 자동 일정·할 일 관리 (v2)", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "MediaStore 실시간 감지 + 통화 종료 자동 스캔 + 직접 파일 선택 지원",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("1. 권한 승인 · 녹음 파일 선택 · 즉시 스캔", style = MaterialTheme.typography.titleMedium)
                                    Button(
                                        onClick = {
                                            val perms = mutableListOf(
                                                Manifest.permission.READ_CALENDAR,
                                                Manifest.permission.WRITE_CALENDAR,
                                                Manifest.permission.READ_PHONE_STATE
                                            )
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                perms.add(Manifest.permission.READ_MEDIA_AUDIO)
                                                perms.add(Manifest.permission.POST_NOTIFICATIONS)
                                            } else {
                                                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                                            }
                                            permissionLauncher.launch(perms.toTypedArray())
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("권한 허용 및 자동 감시·최근 녹음 스캔 시작")
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            audioPickerLauncher.launch(arrayOf("audio/*", "application/octet-stream", "*/*"))
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("내 폰의 통화 녹음 파일(.m4u/.m4a 등) 직접 선택하여 분석")
                                    }

                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                    Text("통화 문장 시뮬레이션 테스트", style = MaterialTheme.typography.labelLarge)
                                    OutlinedTextField(
                                        value = customTestText,
                                        onValueChange = { customTestText = it },
                                        label = { Text("테스트할 통화 대화 내용") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            val samplePath = "/storage/emulated/0/Recordings/Call/통화 김대리_261018_121018.m4u"
                                            val input = Data.Builder()
                                                .putString("KEY_FILE_PATH", samplePath)
                                                .putString("KEY_SIMULATED_TRANSCRIPT", customTestText)
                                                .putBoolean("KEY_FORCE_REPROCESS", true)
                                                .build()
                                            WorkManager.getInstance(this@MainActivity).enqueue(
                                                OneTimeWorkRequestBuilder<CallProcessWorker>()
                                                    .setInputData(input)
                                                    .build()
                                            )
                                            Toast.makeText(this@MainActivity, "통화 내용 분석 및 알림을 실행했습니다.", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("위 대화 내용으로 일정 추출 및 푸시 알림 실행")
                                    }
                                }
                            }
                        }

                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("2. AI 음성 인식(STT) 및 일정 추출 모드", style = MaterialTheme.typography.titleMedium)
                                    EngineMode.values().forEach { mode ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(
                                                selected = (currentMode == mode),
                                                onClick = { scope.launch { settingsDataStore.setEngineMode(mode) } }
                                            )
                                            Text(text = mode.label)
                                        }
                                    }

                                    if (currentMode != EngineMode.ON_DEVICE) {
                                        OutlinedTextField(
                                            value = apiKeyInput,
                                            onValueChange = { apiKeyInput = it },
                                            label = { Text("Gemini API Key (실제 오디오 STT 및 정밀 추출용)") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    settingsDataStore.setGeminiApiKey(apiKeyInput)
                                                    Toast.makeText(this@MainActivity, "API 키가 저장되었습니다.", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        ) {
                                            Text("API 키 저장")
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Text("3. 감지된 약속·할 일·콜백 목록 (${actionItems.size}건)", style = MaterialTheme.typography.titleMedium)
                        }

                        items(actionItems) { item ->
                            ActionItemCard(
                                item = item,
                                onConfirm = {
                                    scope.launch {
                                        when (val res = calendarManager.insertEventOrLaunchFallback(item)) {
                                            is CalendarSyncResult.InsertedDirectly -> {
                                                dao.updateActionStatus(item.id, "CONFIRMED", res.eventId)
                                                Toast.makeText(this@MainActivity, "구글 캘린더에 즉시 등록되었습니다.", Toast.LENGTH_SHORT).show()
                                            }
                                            is CalendarSyncResult.LaunchedCalendarIntent -> {
                                                dao.updateActionStatus(item.id, "CONFIRMED", null)
                                                Toast.makeText(this@MainActivity, "캘린더 앱에서 저장 버튼을 눌러 완료해 주세요.", Toast.LENGTH_SHORT).show()
                                            }
                                            is CalendarSyncResult.Failed -> {
                                                Toast.makeText(this@MainActivity, "등록 실패: ${res.reason}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                },
                                onEdit = {
                                    val editIntent = Intent(this@MainActivity, ActionEditBottomSheetActivity::class.java).apply {
                                        putExtra(ConfirmationNotificationHelper.EXTRA_ACTION_ID, item.id)
                                    }
                                    startActivity(editIntent)
                                },
                                onDismiss = {
                                    scope.launch {
                                        dao.updateActionStatus(item.id, "DISMISSED", null)
                                    }
                                }
                            )
                        }

                        if (callRecords.isNotEmpty()) {
                            item {
                                Text("4. 최근 통화 분석 로그 및 STT 원문 (${callRecords.size}건)", style = MaterialTheme.typography.titleMedium)
                            }
                            items(callRecords) { rec ->
                                CallRecordLogCard(rec)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun copyUriToCacheFile(context: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
        runCatching {
            var displayName = "통화 선택파일_261018_121018.m4u"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    displayName = cursor.getString(nameIndex) ?: displayName
                }
            }
            val targetFile = File(context.cacheDir, displayName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            targetFile
        }.getOrNull()
    }
}

@Composable
fun ActionItemCard(
    item: ActionItemEntity,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA) }
    val timeText = item.startEpochMillis?.let { dateFormat.format(Date(it)) } ?: "시간 미지정"

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("[${item.actionType}] ${item.title}", style = MaterialTheme.typography.titleMedium)
            Text("통화 상대: ${item.contactName} (${item.phoneNumber})", style = MaterialTheme.typography.bodySmall)
            Text("예정 시각: $timeText | 장소: ${item.location ?: "없음"}", style = MaterialTheme.typography.bodySmall)
            Text(item.description, style = MaterialTheme.typography.bodyMedium)
            Text("상태: ${item.status}", style = MaterialTheme.typography.labelMedium)

            if (item.status == "PENDING") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onConfirm) {
                        Text("캘린더 등록")
                    }
                    OutlinedButton(onClick = onEdit) {
                        Text("일시·내용 수정")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("무시")
                    }
                }
            }
        }
    }
}

@Composable
fun CallRecordLogCard(record: CallRecordEntity) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${record.contactName} (${record.phoneNumber.ifBlank { "번호 미상" }}) · 엔진: ${record.engineUsed}",
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                "기준 일시: ${dateFormat.format(Date(record.callTimestampEpochMillis))}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(record.transcript, style = MaterialTheme.typography.bodySmall)
        }
    }
}
