package com.example.callscheduler.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.callscheduler.data.calendar.CalendarSyncResult
import com.example.callscheduler.data.calendar.GoogleCalendarSyncManager
import com.example.callscheduler.data.local.db.ActionItemEntity
import com.example.callscheduler.data.local.db.AppDatabase
import com.example.callscheduler.service.ConfirmationNotificationHelper
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ActionEditBottomSheetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val actionId = intent.getLongExtra(ConfirmationNotificationHelper.EXTRA_ACTION_ID, -1L)
        if (actionId == -1L) {
            finish()
            return
        }

        val dao = AppDatabase.getInstance(this).dao()
        val calendarManager = GoogleCalendarSyncManager(this)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        setContent {
            MaterialTheme {
                var itemState by remember { mutableStateOf<ActionItemEntity?>(null) }
                var title by remember { mutableStateOf("") }
                var description by remember { mutableStateOf("") }
                var location by remember { mutableStateOf("") }
                var startTimeText by remember { mutableStateOf("") }
                var durationMinutesText by remember { mutableStateOf("60") }
                val scope = rememberCoroutineScope()

                LaunchedEffect(actionId) {
                    val loaded = dao.getActionItemById(actionId)
                    if (loaded != null) {
                        itemState = loaded
                        title = loaded.title
                        description = loaded.description
                        location = loaded.location ?: ""
                        val startMillis = loaded.startEpochMillis ?: (System.currentTimeMillis() + 3600_000L)
                        val endMillis = loaded.endEpochMillis ?: (startMillis + 3600_000L)
                        val startLdt = LocalDateTime.ofInstant(Instant.ofEpochMilli(startMillis), ZoneId.systemDefault())
                        startTimeText = startLdt.format(formatter)
                        val diffMin = ((endMillis - startMillis) / 60_000L).coerceAtLeast(15L)
                        durationMinutesText = diffMin.toString()
                    } else {
                        finish()
                    }
                }

                itemState?.let { current ->
                    Surface(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("일정 및 할 일 확인·수정", style = MaterialTheme.typography.titleLarge)
                            Text("통화 상대: ${current.contactName} (${current.phoneNumber})")
                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it },
                                label = { Text("제목") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = startTimeText,
                                onValueChange = { startTimeText = it },
                                label = { Text("시작 일시 (yyyy-MM-dd HH:mm)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = durationMinutesText,
                                onValueChange = { durationMinutesText = it },
                                label = { Text("소요 시간 (분)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = location,
                                onValueChange = { location = it },
                                label = { Text("장소 또는 연락처") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = description,
                                onValueChange = { description = it },
                                label = { Text("상세 메모") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { finish() }) {
                                    Text("취소")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(onClick = {
                                    scope.launch {
                                        val parsedStartMillis = runCatching {
                                            LocalDateTime.parse(startTimeText.trim(), formatter)
                                                .atZone(ZoneId.systemDefault())
                                                .toInstant()
                                                .toEpochMilli()
                                        }.getOrNull() ?: current.startEpochMillis ?: System.currentTimeMillis()
                                        val durationMin = durationMinutesText.toLongOrNull()?.coerceAtLeast(10L) ?: 60L
                                        val parsedEndMillis = parsedStartMillis + durationMin * 60_000L

                                        val updated = current.copy(
                                            title = title,
                                            description = description,
                                            location = location.ifBlank { null },
                                            startEpochMillis = parsedStartMillis,
                                            endEpochMillis = parsedEndMillis,
                                            status = "CONFIRMED"
                                        )
                                        when (val syncResult = calendarManager.insertEventOrLaunchFallback(updated)) {
                                            is CalendarSyncResult.InsertedDirectly -> {
                                                dao.updateActionItem(updated.copy(calendarEventId = syncResult.eventId))
                                                Toast.makeText(
                                                    this@ActionEditBottomSheetActivity,
                                                    "수정된 일정이 구글 캘린더에 등록되었습니다.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            is CalendarSyncResult.LaunchedCalendarIntent -> {
                                                dao.updateActionItem(updated)
                                                Toast.makeText(
                                                    this@ActionEditBottomSheetActivity,
                                                    "캘린더 앱에서 일정을 저장해 주세요.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            is CalendarSyncResult.Failed -> {
                                                Toast.makeText(
                                                    this@ActionEditBottomSheetActivity,
                                                    "캘린더 등록 실패: ${syncResult.reason}",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                        finish()
                                    }
                                }) {
                                    Text("저장 및 캘린더 등록")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
