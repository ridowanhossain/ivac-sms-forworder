package com.ivac.otpforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val prefs = context.getSharedPreferences("ivac_prefs", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("is_forwarding_enabled", true)
        if (!isEnabled) {
            Log.d("SmsReceiver", "Forwarding is disabled in settings.")
            return
        }

        val messages: Array<SmsMessage> = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val fullBody = StringBuilder()
        var sender = ""

        for (sms in messages) {
            sender = sms.displayOriginatingAddress ?: ""
            fullBody.append(sms.displayMessageBody ?: "")
        }

        val bodyText = fullBody.toString()
        Log.d("SmsReceiver", "Received SMS from: $sender: $bodyText")

        // STRICT FILTERING: Only process IVAC OTP SMS
        val isIvacSender = sender.contains("IVAC", ignoreCase = true)
        val isIvacContent = bodyText.contains("IVAC", ignoreCase = true) &&
                (bodyText.contains("sequence", ignoreCase = true) ||
                 bodyText.contains("prompted", ignoreCase = true) ||
                 bodyText.contains("security", ignoreCase = true) ||
                 bodyText.contains("OTP", ignoreCase = true) ||
                 bodyText.contains("code", ignoreCase = true))

        if (!isIvacSender && !isIvacContent) {
            Log.d("SmsReceiver", "Non-IVAC SMS ignored from $sender")
            return
        }

        // Extract 6-digit OTP locally on the device
        val extractedOtp = FirebaseClient.extractOtpFromText(bodyText)
        if (extractedOtp == null) {
            Log.d("SmsReceiver", "IVAC SMS received but could not parse 6-digit OTP. Skipping.")
            return
        }

        // Dual SIM detection
        val detectedSlot = detectSimSlot(context, intent)
        val sim1Phone = prefs.getString("sim1_phone", "")?.trim() ?: ""
        val sim2Phone = prefs.getString("sim2_phone", "")?.trim() ?: ""
        val legacyPhone = prefs.getString("user_phone", "")?.trim() ?: ""

        val simLabel: String
        val targetPhone: String

        when (detectedSlot) {
            0 -> {
                simLabel = "SIM 1"
                targetPhone = sim1Phone.ifEmpty { legacyPhone.ifEmpty { sim2Phone } }
            }
            1 -> {
                simLabel = "SIM 2"
                targetPhone = sim2Phone.ifEmpty { legacyPhone.ifEmpty { sim1Phone } }
            }
            else -> {
                // If slot could not be determined automatically from intent extras:
                if (sim1Phone.isNotEmpty() && sim2Phone.isEmpty()) {
                    simLabel = "SIM 1"
                    targetPhone = sim1Phone
                } else if (sim2Phone.isNotEmpty() && sim1Phone.isEmpty()) {
                    simLabel = "SIM 2"
                    targetPhone = sim2Phone
                } else if (sim1Phone.isNotEmpty()) {
                    simLabel = "SIM 1 (Default)"
                    targetPhone = sim1Phone
                } else {
                    simLabel = "SIM"
                    targetPhone = legacyPhone.ifEmpty { sim2Phone }
                }
            }
        }

        // Notify local UI
        val phoneDisplay = if (targetPhone.isNotEmpty()) targetPhone else "No phone saved"
        broadcastLog(context, "[$simLabel] IVAC OTP: [$extractedOtp] (Phone: $phoneDisplay)")

        // Send to Firebase with fixed database URL
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = FirebaseClient.sendOtpToFirebase(
                    baseUrl = FirebaseClient.FIXED_FIREBASE_URL,
                    otp = extractedOtp,
                    phoneNumber = targetPhone
                )
                if (result.isSuccess) {
                    broadcastLog(context, "SUCCESS: [$simLabel] OTP [$extractedOtp] synced to Cloud!")
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Sync failed"
                    broadcastLog(context, "ERROR: [$simLabel] $err")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun detectSimSlot(context: Context, intent: Intent): Int {
        val bundle: Bundle = intent.extras ?: return -1

        // 1. Direct slot keys common across various Android OEMs (Xiaomi, Samsung, Oppo, Vivo, etc.)
        val slotKeys = arrayOf(
            "slot", "slot_id", "simSlot", "sim_slot", "slotIndex",
            "android.telephony.extra.SLOT_INDEX", "phone", "simId", "sim_id", "simnum"
        )
        for (key in slotKeys) {
            val v = bundle.get(key) ?: continue
            val slot = when (v) {
                is Int -> v
                is Long -> v.toInt()
                is String -> v.toIntOrNull() ?: -1
                is Byte -> v.toInt()
                is Short -> v.toInt()
                else -> -1
            }
            if (slot in 0..1) return slot
        }

        // 2. Subscription ID extras
        val subKeys = arrayOf("subscription", "sub_id", "subscription_id", "android.telephony.extra.SUBSCRIPTION_INDEX")
        for (key in subKeys) {
            val v = bundle.get(key) ?: continue
            val subId = when (v) {
                is Int -> v
                is Long -> v.toInt()
                is String -> v.toIntOrNull() ?: -1
                else -> -1
            }
            if (subId != -1) {
                try {
                    val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                    val info = sm?.getActiveSubscriptionInfo(subId)
                    if (info != null && info.simSlotIndex in 0..1) {
                        return info.simSlotIndex
                    }
                } catch (e: Exception) {
                    Log.w("SmsReceiver", "Could not query SubscriptionManager: ${e.message}")
                }
            }
        }

        return -1
    }

    private fun broadcastLog(context: Context, logMessage: String) {
        val intent = Intent("com.ivac.otpforwarder.NEW_LOG").apply {
            putExtra("log", logMessage)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }
}
