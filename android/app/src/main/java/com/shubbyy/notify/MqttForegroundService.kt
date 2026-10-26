package com.shubbyy.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
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
        const val ACTION_UPDATE_TOPIC = "com.shubbyy.notify.ACTION_UPDATE_TOPIC"

        const val BROADCAST_STATUS_UPDATE = "com.shubbyy.notify.STATUS_UPDATE"
        const val BROADCAST_EVENT_ARRIVED = "com.shubbyy.notify.EVENT_ARRIVED"

        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_PAYLOAD = "extra_payload"
        const val EXTRA_TIMESTAMP = "extra_timestamp"

        const val PREFS_NAME = "notify_prefs"
        const val PREF_KEY_TOPIC = "pref_topic"
        const val PREF_KEY_ENABLED = "pref_enabled"
        const val DEFAULT_TOPIC = "shubbyy/alert/default_channel"
        const val DEFAULT_BROKER_TCP = "tcp://broker.hivemq.com:1883"
    }

    private var mqttClient: MqttAsyncClient? = null
    private var currentTopic: String = DEFAULT_TOPIC
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
                Log.d(TAG, "Stopping foreground service requested")
                stopMqtt()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_TOPIC -> {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val newTopic = prefs.getString(PREF_KEY_TOPIC, DEFAULT_TOPIC) ?: DEFAULT_TOPIC
                if (newTopic != currentTopic) {
                    currentTopic = newTopic
                    reconnectMqtt()
                }
            }
            ACTION_START -> {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                currentTopic = prefs.getString(PREF_KEY_TOPIC, DEFAULT_TOPIC) ?: DEFAULT_TOPIC
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
            currentTopic
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.NOTIFICATION_ID_SERVICE,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_SERVICE, notification)
        }
    }

    private fun startMqtt() {
        if (mqttClient != null && mqttClient?.isConnected == true) {
            return
        }

        broadcastStatus("Connecting to HiveMQ broker...")
        updateNotification("Connecting...")

        try {
            val clientId = "android_" + System.currentTimeMillis().toString().takeLast(6)
            mqttClient = MqttAsyncClient(DEFAULT_BROKER_TCP, clientId, MemoryPersistence())

            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = false
                keepAliveInterval = 60 // 60-second passive keep-alive = negligible battery impact
                connectionTimeout = 30
            }

            mqttClient?.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    isConnected = true
                    val msg = if (reconnect) "Reconnected to Broker" else "Connected & Listening"
                    Log.i(TAG, "MQTT Connection complete: $msg")
                    broadcastStatus(msg)
                    updateNotification("Active (Listening)")
                    subscribeToTopic()
                }

                override fun connectionLost(cause: Throwable?) {
                    isConnected = false
                    Log.w(TAG, "MQTT Connection lost: ${cause?.message}")
                    broadcastStatus("Connection lost. Auto-reconnecting...")
                    updateNotification("Reconnecting...")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.toString() ?: ""
                    Log.i(TAG, "Message arrived on $topic: $payload")
                    handleIncomingTrigger(topic, payload)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            mqttClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "Initial connect success")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Initial connect failed: ${exception?.message}")
                    broadcastStatus("Broker connect failed. Retrying...")
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Error starting MQTT", e)
            broadcastStatus("Init error: ${e.localizedMessage}")
        }
    }

    private fun subscribeToTopic() {
        try {
            mqttClient?.subscribe(currentTopic, 1, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "Subscribed successfully to: $currentTopic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to subscribe to $currentTopic", exception)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Subscribe error", e)
        }
    }

    private fun handleIncomingTrigger(topic: String?, payload: String) {
        // 1. Acquire partial wakelock so CPU stays alive even if device is asleep
        wakeLock?.acquire(5000)

        // 2. Parse payload type
        var alertType = "beep"
        var timestamp = System.currentTimeMillis()
        try {
            if (payload.startsWith("{")) {
                val json = JSONObject(payload)
                alertType = json.optString("type", "beep")
                timestamp = json.optLong("timestamp", System.currentTimeMillis())
            }
        } catch (e: Exception) {
            // fallback plain string
        }

        // 3. Play physical sound & vibration
        triggerHardwareAlert(alertType)

        // 4. Show Heads-Up High Priority Notification
        NotificationHelper.showHeadsUpAlertNotification(
            this,
            "⚡ Web Page Triggered!",
            "Channel: $currentTopic [Mode: ${alertType.uppercase()}]"
        )

        // 5. Broadcast to MainActivity to update live log
        val intent = Intent(BROADCAST_EVENT_ARRIVED).apply {
            putExtra(EXTRA_PAYLOAD, payload)
            putExtra(EXTRA_TIMESTAMP, timestamp)
            setPackage(packageName)
        }
        sendBroadcast(intent)

        // 6. Send optional latency ACK back to web client
        sendAcknowledgment(timestamp)
    }

    private fun triggerHardwareAlert(type: String) {
        try {
            // Hardware Tone Generator
            val toneType = if (type == "urgent") {
                ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK
            } else {
                ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
            }
            val toneDuration = if (type == "urgent") 800 else 400

            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            toneGen.startTone(toneType, toneDuration)

            // Hardware Vibration
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val timings = if (type == "urgent") {
                        longArrayOf(0, 400, 200, 400, 200, 600)
                    } else {
                        longArrayOf(0, 300, 150, 300)
                    }
                    val amplitudes = if (type == "urgent") {
                        intArrayOf(0, 255, 0, 255, 0, 255)
                    } else {
                        intArrayOf(0, 200, 0, 200)
                    }
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 300, 150, 300), -1)
                }
            }

            // Release tone generator after sound ends
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    toneGen.release()
                } catch (e: Exception) {}
            }, (toneDuration + 200).toLong())

        } catch (e: Exception) {
            Log.e(TAG, "Error playing alert tone or vibration", e)
        }
    }

    private fun sendAcknowledgment(sentAt: Long) {
        try {
            if (mqttClient?.isConnected == true) {
                val ackTopic = "$currentTopic/ack"
                val ackJson = JSONObject().apply {
                    put("status", "received")
                    put("sentAt", sentAt)
                    put("receivedAt", System.currentTimeMillis())
                }
                val msg = MqttMessage(ackJson.toString().toByteArray()).apply { qos = 0 }
                mqttClient?.publish(ackTopic, msg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to publish ACK: ${e.message}")
        }
    }

    private fun reconnectMqtt() {
        stopMqtt()
        startMqtt()
    }

    private fun stopMqtt() {
        try {
            if (mqttClient?.isConnected == true) {
                mqttClient?.disconnect()
            }
            mqttClient?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting MQTT", e)
        }
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
            currentTopic
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
