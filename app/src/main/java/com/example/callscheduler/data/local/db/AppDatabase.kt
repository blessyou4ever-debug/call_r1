package com.example.callscheduler.data.local.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

data class CallRecordEntity(
    val id: Long = 0,
    val filePath: String,
    val contactName: String,
    val phoneNumber: String,
    val callTimestampEpochMillis: Long,
    val transcript: String,
    val engineUsed: String
)

data class ActionItemEntity(
    val id: Long = 0,
    val callRecordId: Long,
    val contactName: String,
    val phoneNumber: String,
    val actionType: String,   // APPOINTMENT, TODO, CALLBACK
    val title: String,
    val description: String,
    val startEpochMillis: Long?,
    val endEpochMillis: Long?,
    val location: String?,
    val status: String,       // PENDING, CONFIRMED, DISMISSED
    val calendarEventId: Long? = null
)

class CallSchedulerDao(private val dbHelper: AppDatabase) {
    private val itemsFlow = MutableStateFlow<List<ActionItemEntity>>(emptyList())
    private val recordsFlow = MutableStateFlow<List<CallRecordEntity>>(emptyList())

    init {
        refreshFlowSync()
    }

    fun observeAllActionItems(): Flow<List<ActionItemEntity>> = itemsFlow
    fun observeAllCallRecords(): Flow<List<CallRecordEntity>> = recordsFlow

    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete("action_items", null, null)
        db.delete("call_records", null, null)
        refreshFlowSync()
    }

    suspend fun hasProcessedFile(filePath: String): Boolean = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT id FROM call_records WHERE filePath = ? AND engineUsed NOT LIKE 'ERROR%' LIMIT 1",
            arrayOf(filePath)
        ).use {
            it.moveToFirst()
        }
    }

    suspend fun getCallRecordById(id: Long): CallRecordEntity? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT * FROM call_records WHERE id = ? LIMIT 1",
            arrayOf(id.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) readRecordCursorRow(cursor) else null
        }
    }

    suspend fun findRecordIdByFilePath(filePath: String): Long? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT id FROM call_records WHERE filePath = ? ORDER BY id DESC LIMIT 1",
            arrayOf(filePath)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    suspend fun upsertCallRecord(
        existingId: Long?,
        record: CallRecordEntity,
        clearPendingActionsOnUpdate: Boolean = true
    ): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val targetId = existingId?.takeIf { it > 0L } ?: findRecordIdByFilePath(record.filePath)
        val values = ContentValues().apply {
            put("filePath", record.filePath)
            put("contactName", record.contactName)
            put("phoneNumber", record.phoneNumber)
            put("callTimestampEpochMillis", record.callTimestampEpochMillis)
            put("transcript", record.transcript)
            put("engineUsed", record.engineUsed)
        }
        val finalId = if (targetId != null && targetId > 0L) {
            db.update("call_records", values, "id = ?", arrayOf(targetId.toString()))
            if (clearPendingActionsOnUpdate) {
                db.delete("action_items", "callRecordId = ? AND status = 'PENDING'", arrayOf(targetId.toString()))
            }
            targetId
        } else {
            db.insert("call_records", null, values)
        }
        refreshFlowSync()
        finalId
    }

    suspend fun insertCallRecord(record: CallRecordEntity): Long = upsertCallRecord(null, record)

    suspend fun insertActionItem(item: ActionItemEntity): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("callRecordId", item.callRecordId)
            put("contactName", item.contactName)
            put("phoneNumber", item.phoneNumber)
            put("actionType", item.actionType)
            put("title", item.title)
            put("description", item.description)
            if (item.startEpochMillis != null) put("startEpochMillis", item.startEpochMillis)
            if (item.endEpochMillis != null) put("endEpochMillis", item.endEpochMillis)
            if (item.location != null) put("location", item.location)
            put("status", item.status)
            if (item.calendarEventId != null) put("calendarEventId", item.calendarEventId)
        }
        val id = db.insert("action_items", null, values)
        refreshFlowSync()
        id
    }

    suspend fun getActionItemById(id: Long): ActionItemEntity? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT * FROM action_items WHERE id = ? LIMIT 1", arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) readActionCursorRow(cursor) else null
        }
    }

    suspend fun updateActionItem(item: ActionItemEntity) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("title", item.title)
            put("description", item.description)
            put("location", item.location)
            put("startEpochMillis", item.startEpochMillis)
            put("endEpochMillis", item.endEpochMillis)
            put("status", item.status)
            if (item.calendarEventId != null) put("calendarEventId", item.calendarEventId)
        }
        db.update("action_items", values, "id = ?", arrayOf(item.id.toString()))
        refreshFlowSync()
    }

    suspend fun updateActionStatus(id: Long, status: String, calendarEventId: Long? = null) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("status", status)
            if (calendarEventId != null) put("calendarEventId", calendarEventId)
        }
        db.update("action_items", values, "id = ?", arrayOf(id.toString()))
        refreshFlowSync()
    }

    private fun refreshFlowSync() {
        val actionList = mutableListOf<ActionItemEntity>()
        val recordList = mutableListOf<CallRecordEntity>()
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT * FROM action_items ORDER BY id DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                actionList.add(readActionCursorRow(cursor))
            }
        }
        db.rawQuery("SELECT * FROM call_records ORDER BY id DESC LIMIT 30", null).use { cursor ->
            while (cursor.moveToNext()) {
                recordList.add(readRecordCursorRow(cursor))
            }
        }
        itemsFlow.value = actionList
        recordsFlow.value = recordList
    }

    private fun readRecordCursorRow(cursor: android.database.Cursor): CallRecordEntity {
        return CallRecordEntity(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            filePath = cursor.getString(cursor.getColumnIndexOrThrow("filePath")) ?: "",
            contactName = cursor.getString(cursor.getColumnIndexOrThrow("contactName")) ?: "",
            phoneNumber = cursor.getString(cursor.getColumnIndexOrThrow("phoneNumber")) ?: "",
            callTimestampEpochMillis = cursor.getLong(cursor.getColumnIndexOrThrow("callTimestampEpochMillis")),
            transcript = cursor.getString(cursor.getColumnIndexOrThrow("transcript")) ?: "",
            engineUsed = cursor.getString(cursor.getColumnIndexOrThrow("engineUsed")) ?: ""
        )
    }

    private fun readActionCursorRow(cursor: android.database.Cursor): ActionItemEntity {
        val startIdx = cursor.getColumnIndexOrThrow("startEpochMillis")
        val endIdx = cursor.getColumnIndexOrThrow("endEpochMillis")
        val locIdx = cursor.getColumnIndexOrThrow("location")
        val calIdx = cursor.getColumnIndexOrThrow("calendarEventId")
        return ActionItemEntity(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            callRecordId = cursor.getLong(cursor.getColumnIndexOrThrow("callRecordId")),
            contactName = cursor.getString(cursor.getColumnIndexOrThrow("contactName")) ?: "",
            phoneNumber = cursor.getString(cursor.getColumnIndexOrThrow("phoneNumber")) ?: "",
            actionType = cursor.getString(cursor.getColumnIndexOrThrow("actionType")) ?: "APPOINTMENT",
            title = cursor.getString(cursor.getColumnIndexOrThrow("title")) ?: "",
            description = cursor.getString(cursor.getColumnIndexOrThrow("description")) ?: "",
            startEpochMillis = if (cursor.isNull(startIdx)) null else cursor.getLong(startIdx),
            endEpochMillis = if (cursor.isNull(endIdx)) null else cursor.getLong(endIdx),
            location = if (cursor.isNull(locIdx)) null else cursor.getString(locIdx),
            status = cursor.getString(cursor.getColumnIndexOrThrow("status")) ?: "PENDING",
            calendarEventId = if (cursor.isNull(calIdx)) null else cursor.getLong(calIdx)
        )
    }
}

class AppDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "call_scheduler.db", null, 1) {

    private val daoInstance by lazy { CallSchedulerDao(this) }
    fun dao(): CallSchedulerDao = daoInstance

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS call_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                filePath TEXT NOT NULL,
                contactName TEXT NOT NULL,
                phoneNumber TEXT NOT NULL,
                callTimestampEpochMillis INTEGER NOT NULL,
                transcript TEXT NOT NULL,
                engineUsed TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS action_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                callRecordId INTEGER NOT NULL,
                contactName TEXT NOT NULL,
                phoneNumber TEXT NOT NULL,
                actionType TEXT NOT NULL,
                title TEXT NOT NULL,
                description TEXT NOT NULL,
                startEpochMillis INTEGER,
                endEpochMillis INTEGER,
                location TEXT,
                status TEXT NOT NULL,
                calendarEventId INTEGER
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppDatabase(context).also { INSTANCE = it }
            }
        }
    }
}
