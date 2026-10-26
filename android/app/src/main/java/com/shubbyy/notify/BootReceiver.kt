package com.shubbyy.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d("BootReceiver", "Device reboot detected. Checking service preferences...")

            val prefs = context.getSharedPreferences(MqttForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean(MqttForegroundService.PREF_KEY_ENABLED, false)

            if (isEnabled) {
                Log.i("BootReceiver", "Service was enabled. Resuming MQTT foreground listener...")
                val serviceIntent = Intent(context, MqttForegroundService::class.java).apply {
                    action = MqttForegroundService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
