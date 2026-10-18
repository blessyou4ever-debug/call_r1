package com.example.callscheduler.data.calendar

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.example.callscheduler.data.local.db.ActionItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone

sealed class CalendarSyncResult {
    data class InsertedDirectly(val eventId: Long) : CalendarSyncResult()
    data object LaunchedCalendarIntent : CalendarSyncResult()
    data class Failed(val reason: String) : CalendarSyncResult()
}

class GoogleCalendarSyncManager(private val context: Context) {

    fun hasCalendarPermissions(): Boolean {
        val readGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        val writeGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        return readGranted && writeGranted
    }

    suspend fun insertEventOrLaunchFallback(item: ActionItemEntity): CalendarSyncResult = withContext(Dispatchers.IO) {
        if (hasCalendarPermissions()) {
            val directId = runCatching { insertEventToGoogleCalendarSync(item) }.getOrNull()
            if (directId != null && directId > 0L) {
                return@withContext CalendarSyncResult.InsertedDirectly(directId)
            }
        }
        return@withContext runCatching {
            launchCalendarInsertIntent(item)
            CalendarSyncResult.LaunchedCalendarIntent
        }.getOrElse { e ->
            CalendarSyncResult.Failed(e.message ?: "캘린더 앱을 열 수 없습니다.")
        }
    }

    @Deprecated("Use insertEventOrLaunchFallback for safe IO and permission handling")
    fun insertEventToGoogleCalendar(item: ActionItemEntity): Long? {
        if (!hasCalendarPermissions()) return null
        return runCatching { insertEventToGoogleCalendarSync(item) }.getOrNull()
    }

    private fun insertEventToGoogleCalendarSync(item: ActionItemEntity): Long? {
        val calendarId = findPrimaryGoogleCalendarId() ?: return null
        val startMillis = item.startEpochMillis ?: (System.currentTimeMillis() + 3600_000L)
        val endMillis = item.endEpochMillis ?: (startMillis + 1800_000L)

        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.TITLE, item.title)
            put(
                CalendarContract.Events.DESCRIPTION,
                "${item.description}\n(통화 상대: ${item.contactName})"
            )
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (!item.location.isNullOrBlank()) {
                put(CalendarContract.Events.EVENT_LOCATION, item.location)
            }
        }

        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        val eventId = uri?.lastPathSegment?.toLongOrNull()

        if (eventId != null) {
            val reminderValues = ContentValues().apply {
                put(CalendarContract.Reminders.MINUTES, 15)
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            runCatching {
                context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
            }
        }

        return eventId
    }

    fun launchCalendarInsertIntent(item: ActionItemEntity) {
        val startMillis = item.startEpochMillis ?: (System.currentTimeMillis() + 3600_000L)
        val endMillis = item.endEpochMillis ?: (startMillis + 1800_000L)
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            putExtra(CalendarContract.Events.TITLE, item.title)
            putExtra(
                CalendarContract.Events.DESCRIPTION,
                "${item.description}\n(통화 상대: ${item.contactName})"
            )
            if (!item.location.isNullOrBlank()) {
                putExtra(CalendarContract.Events.EVENT_LOCATION, item.location)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun findPrimaryGoogleCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.VISIBLE
        )
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            var googleFallbackId: Long? = null
            var anyCalendarId: Long? = null
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val accountType = cursor.getString(1) ?: ""
                val isPrimary = cursor.getInt(2) == 1
                val isVisible = cursor.getInt(3) == 1
                if (anyCalendarId == null && isVisible) anyCalendarId = id
                if (accountType == "com.google") {
                    if (isPrimary) return id
                    if (googleFallbackId == null) googleFallbackId = id
                }
            }
            return googleFallbackId ?: anyCalendarId
        }
        return null
    }
}
