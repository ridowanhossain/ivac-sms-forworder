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
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
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

        // Auto-save immediately on keystroke so numbers are never lost even without pressing save or restarting
        etSim1Phone.doAfterTextChanged { text ->
            val num = text?.toString()?.trim() ?: ""
            getSharedPreferences("ivac_prefs", Context.MODE_PRIVATE).edit()
                .putString("sim1_phone", num)
                .apply()
        }

        etSim2Phone.doAfterTextChanged { text ->
            val num = text?.toString()?.trim() ?: ""
            getSharedPreferences("ivac_prefs", Context.MODE_PRIVATE).edit()
                .putString("sim2_phone", num)
                .apply()
        }

        btnSave.setOnClickListener {
            val sim1 = etSim1Phone.text.toString().trim()
            val sim2 = etSim2Phone.text.toString().trim()

            // Synchronous commit to disk immediately
            getSharedPreferences("ivac_prefs", Context.MODE_PRIVATE).edit()
                .putString("sim1_phone", sim1)
                .putString("sim2_phone", sim2)
                .putString("user_phone", sim1.ifEmpty { sim2 })
                .commit()

            val msg = buildString {
                append("Phone numbers saved successfully:\n")
                append("• SIM 1: ${if (sim1.isNotEmpty()) sim1 else "(none)"}\n")
                append("• SIM 2: ${if (sim2.isNotEmpty()) sim2 else "(none)"}")
            }
            addLog(msg)
            Toast.makeText(this, "✓ Phone numbers saved!", Toast.LENGTH_SHORT).show()
        }

        switchService.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("ivac_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean("is_forwarding_enabled", isChecked)
                .commit()

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
        try {
            val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
            val subList = if (hasPermission) {
                try { sm?.activeSubscriptionInfoList ?: emptyList() } catch (_: Exception) { emptyList() }
            } else {
                emptyList()
            }

            // 1. Resolve SIM 1 (Slot 0)
            val s1 = subList.find { it.simSlotIndex == 0 } ?: subList.getOrNull(0)
            var sim1Name: String? = null
            var sim1Ready = false

            if (s1 != null) {
                sim1Ready = true
                sim1Name = s1.carrierName?.toString()?.takeIf { it.isNotBlank() }
                    ?: s1.displayName?.toString()?.takeIf { it.isNotBlank() }
                if (sim1Name.isNullOrBlank()) {
                    try {
                        val specificTm = tm.createForSubscriptionId(s1.subscriptionId)
                        sim1Name = specificTm.simOperatorName.takeIf { it.isNotBlank() }
                            ?: specificTm.networkOperatorName.takeIf { it.isNotBlank() }
                    } catch (_: Exception) {}
                }
            } else {
                val state0 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try { tm.getSimState(0) } catch (_: Exception) { TelephonyManager.SIM_STATE_UNKNOWN }
                } else {
                    tm.simState
                }
                if (state0 == TelephonyManager.SIM_STATE_READY) {
                    sim1Ready = true
                }
            }

            // 2. Resolve SIM 2 (Slot 1)
            val s2 = subList.find { it.simSlotIndex == 1 } ?: (if (subList.size > 1 && subList[1] != s1) subList[1] else null)
            var sim2Name: String? = null
            var sim2Ready = false

            if (s2 != null) {
                sim2Ready = true
                sim2Name = s2.carrierName?.toString()?.takeIf { it.isNotBlank() }
                    ?: s2.displayName?.toString()?.takeIf { it.isNotBlank() }
                if (sim2Name.isNullOrBlank()) {
                    try {
                        val specificTm = tm.createForSubscriptionId(s2.subscriptionId)
                        sim2Name = specificTm.simOperatorName.takeIf { it.isNotBlank() }
                            ?: specificTm.networkOperatorName.takeIf { it.isNotBlank() }
                    } catch (_: Exception) {}
                }
            } else {
                val state1 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try { tm.getSimState(1) } catch (_: Exception) { TelephonyManager.SIM_STATE_UNKNOWN }
                } else {
                    TelephonyManager.SIM_STATE_UNKNOWN
                }
                if (state1 == TelephonyManager.SIM_STATE_READY) {
                    sim2Ready = true
                }
            }

            // Update UI for SIM 1
            if (sim1Ready) {
                tvSim1Status.text = "✓ ${sim1Name ?: "SIM 1 (Active)"}"
                tvSim1Status.setTextColor(Color.parseColor("#69F0AE"))
            } else {
                tvSim1Status.text = "Slot 1 Empty"
                tvSim1Status.setTextColor(Color.parseColor("#FFAB91"))
            }

            // Update UI for SIM 2
            if (sim2Ready) {
                tvSim2Status.text = "✓ ${sim2Name ?: "SIM 2 (Active)"}"
                tvSim2Status.setTextColor(Color.parseColor("#69F0AE"))
            } else {
                tvSim2Status.text = "Slot 2 Empty"
                tvSim2Status.setTextColor(Color.parseColor("#FFAB91"))
            }

            if (subList.isNotEmpty()) {
                val summary = subList.joinToString(", ") { "Slot ${it.simSlotIndex}: ${it.carrierName ?: it.displayName}" }
                addLog("Detected: $summary")
            }
        } catch (e: Exception) {
            tvSim1Status.text = "✓ SIM 1 (Active)"
            tvSim2Status.text = "✓ SIM 2 (Active)"
        }
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
