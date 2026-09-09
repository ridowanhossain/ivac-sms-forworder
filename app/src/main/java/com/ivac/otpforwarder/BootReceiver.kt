package com.ivac.otpforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val prefs = context.getSharedPreferences("ivac_prefs", Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("is_forwarding_enabled", true)
            if (isEnabled) {
                OtpForegroundService.startService(context)
            }
        }
    }
}
