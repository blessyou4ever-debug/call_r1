package com.example.callscheduler.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
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
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.callscheduler.data.ai.CloudGeminiEngine
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
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settingsDataStore = AiSettingsDataStore(this)
        val cloudDiagnosticEngine = CloudGeminiEngine(settingsDataStore)
        val dao = AppDatabase.getInstance(this).dao()
        val calendarManager = GoogleCalendarSyncManager(this)

        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val currentMode by settingsDataStore.engineModeFlow.collectAsState(initial = EngineMode.AUTO_FALLBACK)
                val apiKey by settingsDataStore.apiKeyFlow.collectAsState(initial = "")
                val customCallDir by settingsDataStore.customCallDirFlow.collectAsState(initial = "")
                val preferredModel by settingsDataStore.preferredModelFlow.collectAsState(initial = "gemini-3.8-flash")
                val actionItems by dao.observeAllActionItems().collectAsState(initial = emptyList())
                val callRecords by dao.observeAllCallRecords().collectAsState(initial = emptyList())

                var apiKeyInput by remember(apiKey) { mutableStateOf(apiKey) }
                var customDirInput by remember(customCallDir) { mutableStateOf(customCallDir) }
                var apiDiagnosticReport by remember { mutableStateOf<String?>(null) }
                var isDiagnosingApi by remember { mutableStateOf(false) }
                var customTestText by remember {
                    mutableStateOf("김대리님, 모레 오전 10시 반에 판교역 스타벅스에서 프로젝트 미팅 진행하시죠. 요청하신 견적서 자료도 검토해서 오늘 저녁에 다시 연락드리겠습니다.")
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { grantMap ->
                    val serviceIntent = Intent(this, CallMonitoringForegroundService::class.java).apply {
                        action = CallMonitoringForegroundService.ACTION_RESET_BASELINE
                    }
                    runCatching {
                        ContextCompat.startForegroundService(this, serviceIntent)
                    }
                    val deniedCount = grantMap.values.count { !it }
                    val msg = if (deniedCount == 0) {
                        "실시간 감시를 시작했습니다. (기존 녹음은 제외하고 지금부터 새로 생성되는 통화만 자동 분석합니다)"
                    } else {
                        "감시 서비스를 시작했습니다. (일부 권한이 거부되어 캘린더 인텐트 연동 모드로 동작할 수 있습니다)"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }

                val folderPickerLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { treeUri: Uri? ->
                    if (treeUri != null) {
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                treeUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        val resolvedPath = resolveTreeUriToFilePath(treeUri)
                        if (!resolvedPath.isNullOrBlank()) {
                            customDirInput = resolvedPath
                            scope.launch {
                                settingsDataStore.setCustomCallDir(resolvedPath)
                                reloadMonitoringDirectories(this@MainActivity)
                                Toast.makeText(
                                    this@MainActivity,
                                    "통화 녹음 폴더가 설정되었습니다: ${resolvedPath}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "선택한 폴더 경로를 확인해 주세요.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

                val audioPickerLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    if (uri != null) {
                        scope.launch {
                            val copiedFile = copyUriToCacheFile(this@MainActivity, uri)
                            if (copiedFile != null) {
                                enqueueReprocessCallRecord(
                                    context = this@MainActivity,
                                    filePath = copiedFile.absolutePath,
                                    existingRecordId = null,
                                    overrideMode = null,
                                    forceCloudOnly = false
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
                                Text("통화 녹음 자동 일정·할 일 관리 (v3.0)", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "최신 gemini-3.8-flash 기본 탑재 + [Gemini API로 재시도] 전용 버튼 및 연결 진단",
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
                                                Manifest.permission.RECORD_AUDIO
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
                                        Text("권한 허용 및 새 통화 실시간 감시 시작 (기존 파일 제외)")
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
                                            val samplePath = "/storage/emulated/0/Recordings/TPhoneCallRecords/김대리_01012345678_20261019143022.m4u"
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
                                            Toast.makeText(this@MainActivity, "T전화 형식(이름만 추출) 테스트 분석을 실행했습니다.", Toast.LENGTH_SHORT).show()
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
                                    Text("2. 통화 녹음 폴더 경로 설정 (기기·전화 앱별 맞춤)", style = MaterialTheme.typography.titleMedium)
                                    val statusDesc = if (customCallDir.isBlank()) {
                                        "현재: 통합 자동 감시 (삼성 Recordings/Call + T전화 TPhoneCallRecords 모두 감시)"
                                    } else {
                                        val exists = File(customCallDir).exists()
                                        "현재 지정 경로: ${customCallDir} (${if (exists) "폴더 확인됨" else "폴더 생성 시 자동 감시"})"
                                    }
                                    Text(statusDesc, style = MaterialTheme.typography.bodySmall)

                                    FilledTonalButton(
                                        onClick = { folderPickerLauncher.launch(null) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("스마트폰 폴더 탐색기로 통화 녹음 폴더 직접 선택")
                                    }

                                    Text("또는 사용하는 전화 앱 기본 경로 빠른 선택:", style = MaterialTheme.typography.labelMedium)
                                    AiSettingsDataStore.DEFAULT_CALL_DIR_PRESETS.forEach { preset ->
                                        OutlinedButton(
                                            onClick = {
                                                customDirInput = preset.path
                                                scope.launch {
                                                    settingsDataStore.setCustomCallDir(preset.path)
                                                    reloadMonitoringDirectories(this@MainActivity)
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        "[${preset.label}] 경로로 설정되었습니다.",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("${preset.label}: ${preset.path}", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }

                                    OutlinedTextField(
                                        value = customDirInput,
                                        onValueChange = { customDirInput = it },
                                        label = { Text("직접 폴더 경로 입력 (비워두면 모든 경로 통합 감시)") },
                                        placeholder = { Text("/storage/emulated/0/Recordings/TPhoneCallRecords") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    settingsDataStore.setCustomCallDir(customDirInput)
                                                    reloadMonitoringDirectories(this@MainActivity)
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        "통화 녹음 폴더 경로가 저장 및 적용되었습니다.",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("경로 저장 및 감시 적용")
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                customDirInput = ""
                                                scope.launch {
                                                    settingsDataStore.setCustomCallDir("")
                                                    reloadMonitoringDirectories(this@MainActivity)
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        "모든 통화 녹음 경로 통합 감시 모드로 초기화했습니다.",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        ) {
                                            Text("통합 감시 초기화")
                                        }
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
                                    Text("3. AI 음성 인식(STT) 및 일정 추출 모드", style = MaterialTheme.typography.titleMedium)
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
                                        Text(
                                            "현재 우선 호출 모델: ${preferredModel} (구버전 2.5 차단 해결 완료)",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                        OutlinedTextField(
                                            value = apiKeyInput,
                                            onValueChange = { apiKeyInput = it },
                                            label = { Text("Gemini API Key (gemini-3.8-flash 우선 호출)") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = {
                                                    scope.launch {
                                                        settingsDataStore.setGeminiApiKey(apiKeyInput)
                                                        Toast.makeText(this@MainActivity, "API 키가 저장 및 자동 백업되었습니다.", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("API 키 저장")
                                            }
                                            FilledTonalButton(
                                                onClick = {
                                                    scope.launch {
                                                        settingsDataStore.setGeminiApiKey(apiKeyInput)
                                                        isDiagnosingApi = true
                                                        apiDiagnosticReport = "gemini-3.8-flash 및 최신 모델로 연결을 확인하는 중입니다..."
                                                        val res = cloudDiagnosticEngine.diagnoseApiConnection(apiKeyInput)
                                                        apiDiagnosticReport = res.getOrElse { "❌ 진단 중 예외 발생: ${it.message}" }
                                                        isDiagnosingApi = false
                                                    }
                                                },
                                                enabled = !isDiagnosingApi,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(if (isDiagnosingApi) "진단 중..." else "API 키 · 모델 연결 진단")
                                            }
                                        }

                                        apiDiagnosticReport?.let { report ->
                                            Surface(
                                                tonalElevation = 2.dp,
                                                shape = MaterialTheme.shapes.medium,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = report,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.padding(12.dp)
                                                )
                                            }
                                        }
                                    }

                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                    Text("환경설정(API 키 · 폴더 경로 · 모드) 원클릭 백업 및 복원", style = MaterialTheme.typography.labelLarge)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = {
                                                val json = settingsDataStore.exportSettingsJson()
                                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                cm.setPrimaryClip(ClipData.newPlainText("CallSchedulerSettings", json))
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "현재 환경설정(API 키·경로·모드)이 클립보드에 복사되었습니다.",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("설정 클립보드 백업")
                                        }
                                        Button(
                                            onClick = {
                                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                val clipText = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                                scope.launch {
                                                    if (settingsDataStore.importSettingsJson(clipText)) {
                                                        reloadMonitoringDirectories(this@MainActivity)
                                                        Toast.makeText(
                                                            this@MainActivity,
                                                            "클립보드에서 환경설정을 모두 복원했습니다!",
                                                            Toast.LENGTH_LONG
                                                        ).show()
                                                    } else {
                                                        Toast.makeText(
                                                            this@MainActivity,
                                                            "클립보드에 백업된 환경설정 데이터가 없습니다.",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                }
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("클립보드 설정 복원")
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("4. 감지된 약속·할 일·콜백 (${actionItems.size}건)", style = MaterialTheme.typography.titleMedium)
                                if (actionItems.isNotEmpty() || callRecords.isNotEmpty()) {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                CallMonitoringForegroundService.initializeOrResetBaseline(this@MainActivity, forceResetToNow = true)
                                                dao.clearAllHistory()
                                                Toast.makeText(this@MainActivity, "기존 목록과 로그를 모두 비웠습니다.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    ) {
                                        Text("목록·로그 전체 비우기")
                                    }
                                }
                            }
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
                                val retryableRecords = callRecords.filter {
                                    it.engineUsed.startsWith("ERROR") || it.engineUsed.contains("FALLBACK")
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "5. 최근 통화 분석 로그 및 API 재시도 (${callRecords.size}건)",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        if (retryableRecords.isNotEmpty()) {
                                            FilledTonalButton(
                                                onClick = {
                                                    var count = 0
                                                    retryableRecords.forEach { rec ->
                                                        if (File(rec.filePath).exists()) {
                                                            count++
                                                            enqueueReprocessCallRecord(
                                                                context = this@MainActivity,
                                                                filePath = rec.filePath,
                                                                existingRecordId = rec.id,
                                                                overrideMode = EngineMode.CLOUD_API,
                                                                forceCloudOnly = true
                                                            )
                                                        }
                                                    }
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        "폴백·실패 항목 ${count}건을 gemini-3.8-flash API로 일괄 재시도합니다.",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            ) {
                                                Text("Gemini API 일괄 재시도 (${retryableRecords.size}건)")
                                            }
                                        }
                                    }
                                    Text(
                                        "이제 [Gemini API로 재시도]를 누르면 구버전(2.5) 대신 최신 gemini-3.8-flash 모델로 즉시 호출합니다.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            items(callRecords) { rec ->
                                CallRecordLogCard(
                                    record = rec,
                                    onRetryGeminiApi = {
                                        if (File(rec.filePath).exists()) {
                                            enqueueReprocessCallRecord(
                                                context = this@MainActivity,
                                                filePath = rec.filePath,
                                                existingRecordId = rec.id,
                                                overrideMode = EngineMode.CLOUD_API,
                                                forceCloudOnly = true
                                            )
                                            Toast.makeText(
                                                this@MainActivity,
                                                "${rec.contactName} 통화 녹음을 Gemini API(${preferredModel})로 재시도합니다...",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            Toast.makeText(
                                                this@MainActivity,
                                                "원본 녹음 파일을 찾을 수 없습니다: ${rec.filePath}",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    },
                                    onRetryOnDevice = {
                                        if (File(rec.filePath).exists()) {
                                            enqueueReprocessCallRecord(
                                                context = this@MainActivity,
                                                filePath = rec.filePath,
                                                existingRecordId = rec.id,
                                                overrideMode = EngineMode.ON_DEVICE,
                                                forceCloudOnly = false
                                            )
                                            Toast.makeText(
                                                this@MainActivity,
                                                "${rec.contactName} 통화 녹음을 온디바이스 엔진으로 재분석합니다...",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            Toast.makeText(
                                                this@MainActivity,
                                                "원본 녹음 파일을 찾을 수 없습니다: ${rec.filePath}",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun enqueueReprocessCallRecord(
        context: Context,
        filePath: String,
        existingRecordId: Long?,
        overrideMode: EngineMode?,
        forceCloudOnly: Boolean
    ) {
        val builder = Data.Builder()
            .putString("KEY_FILE_PATH", filePath)
            .putBoolean("KEY_FORCE_REPROCESS", true)
            .putBoolean("KEY_FORCE_CLOUD_ONLY", forceCloudOnly)
        if (existingRecordId != null && existingRecordId > 0L) {
            builder.putLong("KEY_EXISTING_RECORD_ID", existingRecordId)
        }
        if (overrideMode != null) {
            builder.putString("KEY_OVERRIDE_MODE", overrideMode.name)
        }
        val req = OneTimeWorkRequestBuilder<CallProcessWorker>()
            .setInputData(builder.build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "reanalyze_${filePath.hashCode()}",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    private fun reloadMonitoringDirectories(context: Context) {
        CallMonitoringForegroundService.initializeOrResetBaseline(context, forceResetToNow = true)
        val serviceIntent = Intent(context, CallMonitoringForegroundService::class.java).apply {
            action = CallMonitoringForegroundService.ACTION_RELOAD_DIRS
        }
        runCatching {
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }

    private fun resolveTreeUriToFilePath(treeUri: Uri): String? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(treeUri) ?: return@runCatching null
        val decoded = URLDecoder.decode(docId, "UTF-8")
        when {
            decoded.startsWith("primary:") -> {
                val subPath = decoded.removePrefix("primary:").trim('/')
                if (subPath.isEmpty()) "/storage/emulated/0" else "/storage/emulated/0/${subPath}"
            }
            decoded.contains(":") -> {
                val parts = decoded.split(":", limit = 2)
                val volumeId = parts[0]
                val subPath = parts.getOrElse(1) { "" }.trim('/')
                if (subPath.isEmpty()) "/storage/${volumeId}" else "/storage/${volumeId}/${subPath}"
            }
            else -> "/storage/emulated/0/${decoded.trim('/')}"
        }
    }.getOrNull()

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
            Text("통화 상대: ${item.contactName}", style = MaterialTheme.typography.bodySmall)
            Text("예정 시각: ${timeText} | 장소: ${item.location ?: "없음"}", style = MaterialTheme.typography.bodySmall)
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
fun CallRecordLogCard(
    record: CallRecordEntity,
    onRetryGeminiApi: () -> Unit,
    onRetryOnDevice: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${record.contactName} · 엔진: ${record.engineUsed}",
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                "기준 일시: ${dateFormat.format(Date(record.callTimestampEpochMillis))}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(record.transcript, style = MaterialTheme.typography.bodySmall)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onRetryGeminiApi,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Gemini API로 재시도")
                }
                OutlinedButton(
                    onClick = onRetryOnDevice,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("온디바이스로 재분석")
                }
            }
        }
    }
}
