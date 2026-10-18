package com.example.callscheduler.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        if (state == TelephonyManager.EXTRA_STATE_IDLE) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // 통화 종료 직후 녹음기 앱이 파일을 저장하고 MediaStore에 인덱싱할 시간을 대기
                    delay(3500L)
                    CallMonitoringForegroundService.enqueueRecentMediaStoreScan(context.applicationContext)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
