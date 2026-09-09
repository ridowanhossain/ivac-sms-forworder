package com.ivac.otpforwarder

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var etUserPhone: EditText
    private lateinit var etFirebaseUrl: EditText
    private lateinit var switchService: SwitchCompat
    private lateinit var btnSave: Button
    private lateinit var btnTest: Button
    private lateinit var tvLogs: TextView
    private lateinit var btnClearLogs: Button

    private val logList = mutableListOf<String>()

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra("log") ?: return
            addLog(msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadPreferences()
        checkAndRequestPermissions()
        requestIgnoreBatteryOptimizations()

        // Start background service if enabled
        if (switchService.isChecked) {
            OtpForegroundService.startService(this)
        }
    }

    private fun initViews() {
        etUserPhone = findViewById(R.id.etUserPhone)
        etFirebaseUrl = findViewById(R.id.etFirebaseUrl)
        switchService = findViewById(R.id.switchService)
        btnSave = findViewById(R.id.btnSave)
        btnTest = findViewById(R.id.btnTest)
        tvLogs = findViewById(R.id.tvLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)

        btnSave.setOnClickListener {
            val phone = etUserPhone.text.toString().trim()
            val url = etFirebaseUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Enter a valid Firebase URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            getSharedPreferences("ivac_prefs", Context.MODE_PRIVATE).edit()
                .putString("user_phone", phone)
                .putString("firebase_url", url)
                .apply()
            addLog("Saved: Phone=[$phone], Firebase URL=[$url]")
            Toast.makeText(this, "Settings saved successfully", Toast.LENGTH_SHORT).show()
        }

        switchService.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("ivac_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean("is_forwarding_enabled", isChecked)
                .apply()

            if (isChecked) {
                OtpForegroundService.startService(this)
                addLog("Service started. Forwarding active.")
            } else {
                OtpForegroundService.stopService(this)
                addLog("Service stopped. Forwarding paused.")
            }
        }

        btnTest.setOnClickListener {
            val url = etFirebaseUrl.text.toString().trim()
            val phone = etUserPhone.text.toString().trim()
            addLog("Testing connection with sample IVAC OTP...")
            btnTest.isEnabled = false

            CoroutineScope(Dispatchers.Main).launch {
                val sampleText = "(IVACBD) For security, type the following sequence when prompted Six-Seven-Eight-One-Four-One ."
                val otp = FirebaseClient.extractOtpFromText(sampleText) ?: "678141"
                val result = FirebaseClient.sendOtpToFirebase(url, otp, phone)

                btnTest.isEnabled = true
                if (result.isSuccess) {
                    addLog("TEST SUCCESS: Sent { phone: '$phone', otp: '$otp' } to Firebase!")
                    Toast.makeText(this@MainActivity, "Test OTP sent to Firebase!", Toast.LENGTH_LONG).show()
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Unknown error"
                    addLog("TEST FAILED: $err")
                    Toast.makeText(this@MainActivity, "Test Failed: $err", Toast.LENGTH_LONG).show()
                }
            }
        }

        btnClearLogs.setOnClickListener {
            logList.clear()
            tvLogs.text = "Logs will appear here..."
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("ivac_prefs", Context.MODE_PRIVATE)
        val phone = prefs.getString("user_phone", "")
        val url = prefs.getString("firebase_url", FirebaseClient.DEFAULT_FIREBASE_URL)
        val enabled = prefs.getBoolean("is_forwarding_enabled", true)

        etUserPhone.setText(phone)
        etFirebaseUrl.setText(url)
        switchService.isChecked = enabled
    }

    fun addLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$time] $msg"
        logList.add(0, entry)
        if (logList.size > 50) logList.removeLast()
        tvLogs.text = logList.joinToString("\n\n")
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Ignore if device doesn't support direct intent
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.ivac.otpforwarder.NEW_LOG")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(logReceiver)
        } catch (e: Exception) {
            // Ignored
        }
    }
}
