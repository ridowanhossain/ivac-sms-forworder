package com.ivac.otpforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
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

        val firebaseUrl = prefs.getString("firebase_url", FirebaseClient.DEFAULT_FIREBASE_URL)
            ?: FirebaseClient.DEFAULT_FIREBASE_URL

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
            // Other personal/bank SMS - DROP COMPLETELY!
            Log.d("SmsReceiver", "Non-IVAC SMS ignored from $sender")
            return
        }

        // Extract 6-digit OTP locally on the device
        val extractedOtp = FirebaseClient.extractOtpFromText(bodyText)
        if (extractedOtp == null) {
            Log.d("SmsReceiver", "IVAC SMS received but could not parse 6-digit OTP. Skipping.")
            return
        }

        val userPhone = prefs.getString("user_phone", "") ?: ""

        // Notify local UI
        broadcastLog(context, "IVAC OTP Detected: [$extractedOtp] (Phone: $userPhone)")

        // Send ONLY Phone & OTP in JSON to Firebase
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = FirebaseClient.sendOtpToFirebase(
                    baseUrl = firebaseUrl,
                    otp = extractedOtp,
                    phoneNumber = userPhone
                )
                if (result.isSuccess) {
                    broadcastLog(context, "SUCCESS: OTP [$extractedOtp] synced to Firebase!")
                } else {
                    broadcastLog(context, "ERROR: " + (result.exceptionOrNull()?.message ?: "Failed"))
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun broadcastLog(context: Context, logMessage: String) {
        val intent = Intent("com.ivac.otpforwarder.NEW_LOG").apply {
            putExtra("log", logMessage)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }
}
