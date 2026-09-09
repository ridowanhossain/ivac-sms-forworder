package com.ivac.otpforwarder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object FirebaseClient {

    const val DEFAULT_FIREBASE_URL = "https://ivac-otp-receiver-default-rtdb.asia-southeast1.firebasedatabase.app"

    suspend fun sendOtpToFirebase(
        baseUrl: String,
        otp: String,
        phoneNumber: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = baseUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/latest_otp.json"
            val url = URL(endpoint)

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("User-Agent", "IVAC-OTP-Forwarder/1.0")
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
            }

            // Minimal JSON payload: only phone, extracted 6-digit OTP, timestamp, and used flag
            val payload = JSONObject().apply {
                put("phone", phoneNumber)
                put("otp", otp)
                put("timestamp", System.currentTimeMillis())
                put("used", false)
            }.toString()

            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(payload)
                writer.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                Result.success("Sent OTP [$otp] to Firebase (HTTP $responseCode)")
            } else {
                val error = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Result.failure(Exception("HTTP Error $responseCode: $error"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun extractOtpFromText(text: String): String? {
        val str = text.trim()

        // 1. Direct 6 numbers (e.g. 123456)
        val numRegex = Regex("""\b\d{6}\b""")
        val numMatch = numRegex.find(str)
        if (numMatch != null) return numMatch.value

        // 2. English word digits sequence: "Six-Seven-Eight-One-Four-One"
        val wordMap = mapOf(
            "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
            "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9"
        )

        val tokens = str.lowercase()
            .replace(Regex("[^a-z0-9]"), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }

        val wordDigits = mutableListOf<String>()
        for (token in tokens) {
            val digit = wordMap[token]
            if (digit != null) {
                wordDigits.add(digit)
            } else if (token.length == 1 && token[0].isDigit()) {
                wordDigits.add(token)
            } else {
                if (wordDigits.size == 6) break
                wordDigits.clear()
            }
            if (wordDigits.size == 6) break
        }

        if (wordDigits.size == 6) return wordDigits.joinToString("")

        // 3. Fallback: all number words found anywhere in text in order
        val allWords = mutableListOf<String>()
        val allRegex = Regex("""\b(zero|one|two|three|four|five|six|seven|eight|nine)\b""", RegexOption.IGNORE_CASE)
        allRegex.findAll(str).forEach { match ->
            val digit = wordMap[match.value.lowercase()]
            if (digit != null) allWords.add(digit)
        }
        if (allWords.size == 6) return allWords.joinToString("")

        return null
    }
}
