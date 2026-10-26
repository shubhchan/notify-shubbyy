package com.shubbyy.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.ServiceCompat
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject

class MqttForegroundService : Service() {

    companion object {
        const val TAG = "MqttService"
        const val ACTION_START = "com.shubbyy.notify.ACTION_START"
        const val ACTION_STOP = "com.shubbyy.notify.ACTION_STOP"

        const val BROADCAST_STATUS_UPDATE = "com.shubbyy.notify.STATUS_UPDATE"
        const val BROADCAST_EVENT_ARRIVED = "com.shubbyy.notify.EVENT_ARRIVED"

        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_PAYLOAD = "extra_payload"
        const val EXTRA_TIMESTAMP = "extra_timestamp"

        const val PREFS_NAME = "notify_prefs"
        const val PREF_KEY_ENABLED = "pref_enabled"

        // PERMANENT, LOCKED CHANNEL - IDENTICAL ON WEB AND ANDROID
        const val FIXED_TOPIC = "shubbyy/notify/instant"
        const val DEFAULT_BROKER_SSL = "ssl://broker.emqx.io:8883"
    }

    private var mqttClient: MqttAsyncClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isConnected = false

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannels(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NotifyShubbyy::WakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stopping foreground service")
                stopMqtt()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startInForeground()
                startMqtt()
            }
        }

        return START_STICKY
    }

    private fun startInForeground() {
        val notification = NotificationHelper.buildForegroundServiceNotification(
            this,
            "Connecting...",
            FIXED_TOPIC
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.NOTIFICATION_ID_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_SERVICE, notification)
        }
    }

    private fun startMqtt() {
        if (mqttClient != null && mqttClient?.isConnected == true) {
            return
        }

        broadcastStatus("Connecting to Broker...")
        updateNotification("Connecting...")

        try {
            val clientId = "android_" + System.currentTimeMillis().toString().takeLast(6)
            mqttClient = MqttAsyncClient(DEFAULT_BROKER_SSL, clientId, MemoryPersistence())

            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = false
                keepAliveInterval = 60
                connectionTimeout = 30
                socketFactory = javax.net.ssl.SSLSocketFactory.getDefault()
            }

            mqttClient?.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    isConnected = true
                    val msg = if (reconnect) "Reconnected" else "Connected & Listening"
                    broadcastStatus(msg)
                    updateNotification("Active (Listening)")
                    subscribeToTopic()
                }

                override fun connectionLost(cause: Throwable?) {
                    isConnected = false
                    broadcastStatus("Reconnecting...")
                    updateNotification("Reconnecting...")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.toString() ?: ""
                    handleIncomingTrigger(payload)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            mqttClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "MQTT connected")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    broadcastStatus("Broker connecting...")
                }
            })

        } catch (e: Exception) {
            broadcastStatus("Init error")
        }
    }

    private fun subscribeToTopic() {
        try {
            mqttClient?.subscribe(FIXED_TOPIC, 1, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "Subscribed to $FIXED_TOPIC")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed subscribe $FIXED_TOPIC", exception)
                }
            })
        } catch (e: Exception) {}
    }

    private fun handleIncomingTrigger(payload: String) {
        wakeLock?.acquire(7000)

        var alertType = "beep"
        var timestamp = System.currentTimeMillis()
        try {
            if (payload.startsWith("{")) {
                val json = JSONObject(payload)
                alertType = json.optString("type", "beep")
                timestamp = json.optLong("timestamp", System.currentTimeMillis())
            }
        } catch (e: Exception) {}

        triggerHardwareAlert(alertType)

        NotificationHelper.showHeadsUpAlertNotification(
            this,
            "⚡ Web Page Triggered!",
            "Alert: ${alertType.uppercase()} | Channel: $FIXED_TOPIC"
        )

        val intent = Intent(BROADCAST_EVENT_ARRIVED).apply {
            putExtra(EXTRA_PAYLOAD, payload)
            putExtra(EXTRA_TIMESTAMP, timestamp)
            setPackage(packageName)
        }
        sendBroadcast(intent)

        sendAcknowledgment(timestamp)
    }

    private fun triggerHardwareAlert(type: String) {
        try {
            AlertSoundPlayer.playAlert(this, type)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing alert tone", e)
        }
    }

    private fun sendAcknowledgment(sentAt: Long) {
        try {
            if (mqttClient?.isConnected == true) {
                val ackTopic = "$FIXED_TOPIC/ack"
                val ackJson = JSONObject().apply {
                    put("status", "received")
                    put("sentAt", sentAt)
                    put("receivedAt", System.currentTimeMillis())
                }
                val msg = MqttMessage(ackJson.toString().toByteArray()).apply { qos = 0 }
                mqttClient?.publish(ackTopic, msg)
            }
        } catch (e: Exception) {}
    }

    private fun stopMqtt() {
        try {
            if (mqttClient?.isConnected == true) {
                mqttClient?.disconnect()
            }
            mqttClient?.close()
        } catch (e: Exception) {}
        isConnected = false
    }

    private fun broadcastStatus(status: String) {
        val intent = Intent(BROADCAST_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS, status)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun updateNotification(statusText: String) {
        val notification = NotificationHelper.buildForegroundServiceNotification(
            this,
            statusText,
            FIXED_TOPIC
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NotificationHelper.NOTIFICATION_ID_SERVICE, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMqtt()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
