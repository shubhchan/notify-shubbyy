package com.shubbyy.notify

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.shubbyy.notify.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val prefs by lazy {
        getSharedPreferences(MqttForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Notification permission active", Toast.LENGTH_SHORT).show()
            updatePermissionButtonsState()
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
                    val timestamp = intent.getLongExtra(MqttForegroundService.EXTRA_TIMESTAMP, System.currentTimeMillis())
                    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
                    val logEntry = "[$timeStr] TRIGGER ARRIVED\n"
                    binding.tvEventLog.append(logEntry)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        NotificationHelper.createNotificationChannels(this)
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
        } catch (e: Exception) {}
    }

    private fun setupViews() {
        // Toggle Service switch
        binding.switchService.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(MqttForegroundService.PREF_KEY_ENABLED, isChecked).apply()
            if (isChecked) {
                startForegroundListener()
            } else {
                stopForegroundListener()
            }
        }

        // Battery optimization exemption button
        binding.btnBatteryOpt.setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        // Notification permission button
        binding.btnNotificationPerm.setOnClickListener {
            requestNotificationPermission()
        }

        // Test Hardware Beep
        binding.btnTestBeep.setOnClickListener {
            testHardwareBeep()
        }

        // Clear log
        binding.btnClearLog.setOnClickListener {
            binding.tvEventLog.text = "[Ready] Logs cleared.\n"
        }
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
        binding.tvLiveStatus.text = status

        if (status.contains("Connected", ignoreCase = true) || status.contains("Listening", ignoreCase = true)) {
            binding.viewStatusDot.setBackgroundColor(Color.parseColor("#4ADE80")) // M3 Success
            binding.tvLiveStatus.setTextColor(Color.parseColor("#4ADE80"))
        } else if (status.contains("Connecting", ignoreCase = true) || status.contains("Starting", ignoreCase = true)) {
            binding.viewStatusDot.setBackgroundColor(Color.parseColor("#80D4FF")) // M3 Primary
            binding.tvLiveStatus.setTextColor(Color.parseColor("#80D4FF"))
        } else {
            binding.viewStatusDot.setBackgroundColor(Color.parseColor("#FFB4AB")) // M3 Error
            binding.tvLiveStatus.setTextColor(Color.parseColor("#FFB4AB"))
        }
    }

    private fun updatePermissionButtonsState() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoringBattery = powerManager.isIgnoringBatteryOptimizations(packageName)
        if (isIgnoringBattery) {
            binding.btnBatteryOpt.text = "✅ Battery: Unrestricted"
            binding.btnBatteryOpt.isEnabled = false
        } else {
            binding.btnBatteryOpt.text = "🔋 Request Battery Exemption"
            binding.btnBatteryOpt.isEnabled = true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotifPerm = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (hasNotifPerm) {
                binding.btnNotificationPerm.text = "✅ Notifications Active"
                binding.btnNotificationPerm.isEnabled = false
            } else {
                binding.btnNotificationPerm.text = "🔔 Enable Notifications"
                binding.btnNotificationPerm.isEnabled = true
            }
        } else {
            binding.btnNotificationPerm.text = "✅ Notifications Active"
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
            Toast.makeText(this, "Already exempt from battery optimization", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun testHardwareBeep() {
        Toast.makeText(this, "Testing sound & vibration...", Toast.LENGTH_SHORT).show()

        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)

            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(
                            longArrayOf(0, 300, 150, 300),
                            intArrayOf(0, 255, 0, 255),
                            -1
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 300, 150, 300), -1)
                }
            }

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    toneGen.release()
                } catch (e: Exception) {}
            }, 600)

            binding.tvEventLog.append("[Local Test] Beep & vibration OK\n")

        } catch (e: Exception) {
            Toast.makeText(this, "Test error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
