package com.shubbyy.notify

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.shubbyy.notify.databinding.ActivityMainBinding
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val prefs by lazy {
        getSharedPreferences(MqttForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val logLines = mutableListOf<String>()

    private var signalCount = 0

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "NOTIFICATIONS ENABLED", Toast.LENGTH_SHORT).show()
            updatePermissionButtonsState()
        } else {
            Toast.makeText(this, "NOTIFICATIONS BLOCKED", Toast.LENGTH_SHORT).show()
        }
    }

    private val serviceUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MqttForegroundService.BROADCAST_STATUS_UPDATE -> {
                    val status = intent.getStringExtra(MqttForegroundService.EXTRA_STATUS) ?: "Unknown"
                    updateStatusDisplay(status)
                }

                MqttForegroundService.BROADCAST_EVENT_ARRIVED -> {
                    val payload = intent.getStringExtra(MqttForegroundService.EXTRA_PAYLOAD) ?: ""
                    val timestamp = intent.getLongExtra(
                        MqttForegroundService.EXTRA_TIMESTAMP,
                        System.currentTimeMillis()
                    )

                    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(Date(timestamp))
                    val alertType = parseAlertType(payload)

                    signalCount += 1
                    prefs.edit()
                        .putInt(PREF_KEY_SIGNAL_COUNT, signalCount)
                        .putString(PREF_KEY_LAST_ACK, timeStr)
                        .apply()

                    binding.tvSignalCount.text = signalCount.toString()
                    binding.tvLastAck.text = timeStr

                    appendLog("[$timeStr] ACK · $alertType TRIGGER ARRIVED")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        NotificationHelper.createNotificationChannels(this)
        restoreStats()
        setupViews()
        loadPreferences()
        updatePermissionButtonsState()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionButtonsState()

        val filter = IntentFilter().apply {
            addAction(MqttForegroundService.BROADCAST_STATUS_UPDATE)
            addAction(MqttForegroundService.BROADCAST_EVENT_ARRIVED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceUpdateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(serviceUpdateReceiver)
        } catch (e: Exception) {
            // Receiver was not registered; safe to ignore.
        }
    }

    private fun setupViews() {
        binding.switchService.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(MqttForegroundService.PREF_KEY_ENABLED, isChecked).apply()
            if (isChecked) {
                startForegroundListener()
            } else {
                stopForegroundListener()
            }
        }

        binding.btnBatteryOpt.setOnClickListener { requestBatteryOptimizationExemption() }
        binding.btnNotificationPerm.setOnClickListener { requestNotificationPermission() }
        binding.btnTestBeep.setOnClickListener { testHardwareBeep() }
        binding.btnTestSiren.setOnClickListener { testHardwareSiren() }
        binding.btnTestVibrate.setOnClickListener { testHardwareVibration() }

        binding.btnClearLog.setOnClickListener {
            logLines.clear()
            appendLog("[READY] LOG CLEARED. AWAITING SIGNALS...")
            Toast.makeText(this, "LOG CLEARED", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restoreStats() {
        signalCount = prefs.getInt(PREF_KEY_SIGNAL_COUNT, 0)
        binding.tvSignalCount.text = signalCount.toString()
        binding.tvLastAck.text = prefs.getString(PREF_KEY_LAST_ACK, "--") ?: "--"

        logLines.add("[BOOT] PIXEL SIGNAL OS v1.0")
        logLines.add("[BOOT] AWAITING SIGNALS...")
        renderLog()
    }

    private fun loadPreferences() {
        val isEnabled = prefs.getBoolean(MqttForegroundService.PREF_KEY_ENABLED, false)
        binding.switchService.isChecked = isEnabled

        if (isEnabled) {
            updateStatusDisplay("Connecting...")
            startForegroundListener()
        } else {
            updateStatusDisplay("Inactive")
        }
    }

    private fun startForegroundListener() {
        val intent = Intent(this, MqttForegroundService::class.java).apply {
            action = MqttForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateStatusDisplay("Connecting...")
    }

    private fun stopForegroundListener() {
        val intent = Intent(this, MqttForegroundService::class.java).apply {
            action = MqttForegroundService.ACTION_STOP
        }
        startService(intent)
        updateStatusDisplay("Inactive")
    }

    private fun updateStatusDisplay(status: String) {
        val connected = status.contains("Connected", ignoreCase = true) ||
            status.contains("Listening", ignoreCase = true)
        val connecting = status.contains("Connecting", ignoreCase = true) ||
            status.contains("Starting", ignoreCase = true) ||
            status.contains("Reconnect", ignoreCase = true) ||
            status.contains("Broker", ignoreCase = true)

        val display = when {
            connected -> "ONLINE"
            connecting && status.contains("Reconnect", ignoreCase = true) -> "RETRY"
            connecting -> "LINKING"
            else -> "OFFLINE"
        }
        val color = when {
            connected -> ContextCompat.getColor(this, R.color.success)
            connecting -> ContextCompat.getColor(this, R.color.warning)
            else -> ContextCompat.getColor(this, R.color.error)
        }

        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(color)
        binding.tvLiveStatus.setTextColor(color)
        binding.tvLiveStatus.text = display
    }

    private fun updatePermissionButtonsState() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoringBattery = powerManager.isIgnoringBatteryOptimizations(packageName)

        if (isIgnoringBattery) {
            binding.btnBatteryOpt.text = "BATTERY: UNRESTRICTED"
            binding.btnBatteryOpt.isEnabled = false
        } else {
            binding.btnBatteryOpt.text = "REQUEST BATTERY EXEMPTION"
            binding.btnBatteryOpt.isEnabled = true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotifPerm = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (hasNotifPerm) {
                binding.btnNotificationPerm.text = "NOTIFICATIONS ACTIVE"
                binding.btnNotificationPerm.isEnabled = false
            } else {
                binding.btnNotificationPerm.text = "ENABLE NOTIFICATIONS"
                binding.btnNotificationPerm.isEnabled = true
            }
        } else {
            binding.btnNotificationPerm.text = "NOTIFICATIONS ACTIVE"
            binding.btnNotificationPerm.isEnabled = false
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            }
        } else {
            Toast.makeText(this, "ALREADY EXEMPT FROM BATTERY OPTIMIZATION", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun testHardwareBeep() {
        Toast.makeText(this, "BEEP FIRED", Toast.LENGTH_SHORT).show()
        try {
            AlertSoundPlayer.playAlert(this, "beep")
            appendLog("[LOCAL TEST] BEEP + HAPTICS FIRED")
        } catch (e: Exception) {
            Toast.makeText(this, "TEST ERROR: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun testHardwareSiren() {
        Toast.makeText(this, "SIREN WAILING", Toast.LENGTH_SHORT).show()
        try {
            AlertSoundPlayer.playAlert(this, "urgent")
            appendLog("[LOCAL TEST] EMERGENCY SIREN FIRED")
        } catch (e: Exception) {
            Toast.makeText(this, "TEST ERROR: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun testHardwareVibration() {
        Toast.makeText(this, "HAPTICS FIRED", Toast.LENGTH_SHORT).show()
        try {
            AlertSoundPlayer.triggerVibration(this, "vibrate")
            appendLog("[LOCAL TEST] VIBRATION HAPTICS FIRED")
        } catch (e: Exception) {
            Toast.makeText(this, "TEST ERROR: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseAlertType(payload: String): String {
        return try {
            if (payload.startsWith("{")) {
                JSONObject(payload).optString("type", "beep").uppercase(Locale.getDefault())
            } else {
                "BEEP"
            }
        } catch (e: Exception) {
            "BEEP"
        }
    }

    private fun appendLog(line: String) {
        logLines.add(line)
        while (logLines.size > MAX_LOG_LINES) {
            logLines.removeAt(0)
        }
        renderLog()
    }

    private fun renderLog() {
        binding.tvEventLog.text = logLines.joinToString("\n")
        binding.tvEventLog.append("\n")
    }

    companion object {
        private const val PREF_KEY_SIGNAL_COUNT = "pref_signal_count"
        private const val PREF_KEY_LAST_ACK = "pref_last_ack"
        private const val MAX_LOG_LINES = 9
    }
}
