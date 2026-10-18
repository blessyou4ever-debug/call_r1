package com.example.callscheduler.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.callscheduler.data.local.db.ActionItemEntity
import com.example.callscheduler.ui.ActionEditBottomSheetActivity

class ConfirmationNotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "call_action_confirm_channel"
        const val ACTION_CONFIRM = "com.example.callscheduler.ACTION_CONFIRM"
        const val ACTION_DISMISS = "com.example.callscheduler.ACTION_DISMISS"
        const val EXTRA_ACTION_ID = "extra_action_id"
    }

    fun showActionConfirmationNotification(item: ActionItemEntity) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            "통화 일정 및 할 일 확인",
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(channel)

        val confirmIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_CONFIRM
            putExtra(EXTRA_ACTION_ID, item.id)
        }
        val confirmPendingIntent = PendingIntent.getBroadcast(
            context,
            item.id.toInt(),
            confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val editIntent = Intent(context, ActionEditBottomSheetActivity::class.java).apply {
            putExtra(EXTRA_ACTION_ID, item.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val editPendingIntent = PendingIntent.getActivity(
            context,
            item.id.toInt() + 10000,
            editIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_DISMISS
            putExtra(EXTRA_ACTION_ID, item.id)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            item.id.toInt() + 20000,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val typeLabel = when (item.actionType) {
            "APPOINTMENT" -> "약속 일정"
            "CALLBACK" -> "다시 연락(콜백)"
            else -> "확인할 할 일"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("[$typeLabel 감지] ${item.contactName} 통화 요약")
            .setContentText(item.title)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${item.title}\n• 상세: ${item.description}\n• 장소/연락처: ${item.location ?: "미지정"}")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(editPendingIntent)
            .addAction(0, "구글 캘린더 등록", confirmPendingIntent)
            .addAction(0, "내용 수정", editPendingIntent)
            .addAction(0, "무시", dismissPendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(item.id.toInt(), notification)
    }
}
