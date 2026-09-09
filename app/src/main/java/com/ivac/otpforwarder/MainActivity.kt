package com.ivac.otpforwarder

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var switchService: SwitchCompat
    private lateinit var tvSim1Status: TextView
    private lateinit var etSim1Phone: EditText
    private lateinit var tvSim2Status: TextView
    private lateinit var etSim2Phone: EditText
    private lateinit var btnSave: Button
    private lateinit var btnClearLogs: Button
    private lateinit var tvLogs: TextView

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
        switchService = findViewById(R.id.switchService)
        tvSim1Status = findViewById(R.id.tvSim1Status)
        etSim1Phone = findViewById(R.id.etSim1Phone)
        tvSim2Status = findViewById(R.id.tvSim2Status)
        etSim2Phone = findViewById(R.id.etSim2Phone)
        btnSave = findViewById(R.id.btnSave)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        tvLogs = findViewById(R.id.tvLogs)

        btnSave.setOnClickListener {
            val sim1 = etSim1Phone.text.toString().trim()
            val sim2 = etSim2Phone.text.toString().trim()

            getSharedPreferences("ivac_prefs", Context.MODE_PRIVATE).edit()
                .putString("sim1_phone", sim1)
                .putString("sim2_phone", sim2)
                .putString("user_phone", if (sim1.isNotEmpty()) sim1 else sim2)
                .apply()

            val msg = buildString {
                append("Phone numbers saved:\n")
                append("• SIM 1: ${if (sim1.isNotEmpty()) sim1 else "(none)"}\n")
                append("• SIM 2: ${if (sim2.isNotEmpty()) sim2 else "(none)"}")
            }
            addLog(msg)
            Toast.makeText(this, "Numbers saved successfully", Toast.LENGTH_SHORT).show()
        }

        switchService.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("ivac_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean("is_forwarding_enabled", isChecked)
                .apply()

            if (isChecked) {
                OtpForegroundService.startService(this)
                addLog("Service started. SMS Forwarding active.")
            } else {
                OtpForegroundService.stopService(this)
                addLog("Service stopped. SMS Forwarding paused.")
            }
        }

        btnClearLogs.setOnClickListener {
            logList.clear()
            tvLogs.text = "Waiting for incoming IVAC SMS..."
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("ivac_prefs", Context.MODE_PRIVATE)
        val legacy = prefs.getString("user_phone", "") ?: ""
        val sim1 = prefs.getString("sim1_phone", legacy) ?: ""
        val sim2 = prefs.getString("sim2_phone", "") ?: ""
        val enabled = prefs.getBoolean("is_forwarding_enabled", true)

        etSim1Phone.setText(sim1)
        etSim2Phone.setText(sim2)
        switchService.isChecked = enabled
    }

    private fun detectSimCards() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            tvSim1Status.text = "Permission required"
            tvSim1Status.setTextColor(Color.parseColor("#FFD54F"))
            tvSim2Status.text = "Permission required"
            tvSim2Status.setTextColor(Color.parseColor("#FFD54F"))
            return
        }

        try {
            val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val subList: List<SubscriptionInfo> = sm?.activeSubscriptionInfoList ?: emptyList()

            // Slot 0 (SIM 1)
            val sim1 = subList.find { it.simSlotIndex == 0 }
            if (sim1 != null) {
                val carrier = sim1.carrierName?.toString()?.takeIf { it.isNotBlank() }
                    ?: sim1.displayName?.toString()?.takeIf { it.isNotBlank() }
                    ?: "Active"
                tvSim1Status.text = "Detected: $carrier"
                tvSim1Status.setTextColor(Color.parseColor("#69F0AE"))

                val detectedNumber = extractNumber(sm, sim1)
                if (!detectedNumber.isNullOrBlank() && etSim1Phone.text.isNullOrBlank()) {
                    etSim1Phone.setText(detectedNumber)
                    addLog("Auto-detected SIM 1 number: $detectedNumber")
                }
            } else {
                tvSim1Status.text = "No SIM in Slot 1"
                tvSim1Status.setTextColor(Color.parseColor("#FFAB91"))
            }

            // Slot 1 (SIM 2)
            val sim2 = subList.find { it.simSlotIndex == 1 }
            if (sim2 != null) {
                val carrier = sim2.carrierName?.toString()?.takeIf { it.isNotBlank() }
                    ?: sim2.displayName?.toString()?.takeIf { it.isNotBlank() }
                    ?: "Active"
                tvSim2Status.text = "Detected: $carrier"
                tvSim2Status.setTextColor(Color.parseColor("#69F0AE"))

                val detectedNumber = extractNumber(sm, sim2)
                if (!detectedNumber.isNullOrBlank() && etSim2Phone.text.isNullOrBlank()) {
                    etSim2Phone.setText(detectedNumber)
                    addLog("Auto-detected SIM 2 number: $detectedNumber")
                }
            } else {
                tvSim2Status.text = "No SIM in Slot 2"
                tvSim2Status.setTextColor(Color.parseColor("#FFAB91"))
            }
        } catch (e: Exception) {
            addLog("SIM detection error: ${e.message}")
        }
    }

    private fun extractNumber(sm: SubscriptionManager?, info: SubscriptionInfo): String? {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val num = sm?.getPhoneNumber(info.subscriptionId)
                if (!num.isNullOrBlank()) return num
            }
            @Suppress("DEPRECATION")
            val num = info.number
            if (!num.isNullOrBlank()) return num
        } catch (_: Exception) {
        }
        return null
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
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            permissions.add(Manifest.permission.READ_PHONE_NUMBERS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        } else {
            detectSimCards()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            detectSimCards()
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
                } catch (_: Exception) {
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        detectSimCards()
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
        } catch (_: Exception) {
        }
    }
}
