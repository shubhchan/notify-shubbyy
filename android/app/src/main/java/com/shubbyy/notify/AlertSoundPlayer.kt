package com.shubbyy.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.cos
import kotlin.math.sin

object AlertSoundPlayer {

    private const val SAMPLE_RATE = 44100
    private var currentTrack: AudioTrack? = null

    @Synchronized
    fun playAlert(context: Context, type: String) {
        stopCurrentAudio()

        when (type.lowercase()) {
            "urgent", "siren" -> {
                playSirenTone(context)
                triggerVibration(context, "siren")
            }
            "vibrate" -> {
                triggerVibration(context, "vibrate")
            }
            else -> {
                playChimeTone(context)
                triggerVibration(context, "beep")
            }
        }
    }

    @Synchronized
    fun stopCurrentAudio() {
        try {
            currentTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
            currentTrack = null
        } catch (e: Exception) {}
    }

    /**
     * Synthesizes an authentic, loud emergency siren wail.
     * Sweeps frequency cyclically from 650Hz to 1600Hz with rich harmonic synthesis
     * to cut through ambient noise and pocket fabric.
     */
    private fun playSirenTone(context: Context) {
        Thread {
            try {
                val durationSec = 2.6
                val totalSamples = (SAMPLE_RATE * durationSec).toInt()
                val pcm = ShortArray(totalSamples)

                val cyclePeriod = 0.55 // seconds per wail cycle (~4.7 full cycles)
                val minFreq = 650.0
                val maxFreq = 1600.0

                var phase = 0.0
                val twoPi = 2.0 * Math.PI

                for (i in 0 until totalSamples) {
                    val t = i.toDouble() / SAMPLE_RATE
                    // Frequency frequency modulated smoothly
                    val freq = minFreq + (maxFreq - minFreq) * (0.5 - 0.5 * cos(twoPi * t / cyclePeriod))
                    phase += twoPi * freq / SAMPLE_RATE

                    // Harmonic composition: fundamental + 2nd harmonic + 3rd harmonic for authentic piercing siren
                    var sample = 0.68 * sin(phase) + 0.22 * sin(2.0 * phase) + 0.10 * sin(3.0 * phase)

                    // Smooth anti-pop envelope
                    val fadeInSamples = 1100
                    val fadeOutSamples = 2600
                    if (i < fadeInSamples) {
                        sample *= (i.toDouble() / fadeInSamples)
                    } else if (i > totalSamples - fadeOutSamples) {
                        sample *= ((totalSamples - i).toDouble() / fadeOutSamples)
                    }

                    pcm[i] = (sample * 32000.0).toInt().coerceIn(-32767, 32767).toShort()
                }

                playPcmTrack(pcm)
            } catch (e: Exception) {}
        }.start()
    }

    /**
     * Synthesizes a high-decibel, piercing rapid double chime alert.
     * Uses frequencies (1800Hz & 2600Hz) where human ear and phone speakers are most sensitive.
     */
    private fun playChimeTone(context: Context) {
        Thread {
            try {
                val toneSegments = listOf(
                    Pair(1800.0, 0.14),
                    Pair(0.0, 0.04),
                    Pair(2600.0, 0.22),
                    Pair(0.0, 0.12),
                    Pair(1800.0, 0.14),
                    Pair(0.0, 0.04),
                    Pair(2600.0, 0.38)
                )

                val totalSamples = toneSegments.sumOf { (SAMPLE_RATE * it.second).toInt() }
                val pcm = ShortArray(totalSamples)
                var offset = 0
                val twoPi = 2.0 * Math.PI

                for ((freq, dur) in toneSegments) {
                    val segSamples = (SAMPLE_RATE * dur).toInt()
                    if (freq <= 0.0) {
                        offset += segSamples
                    } else {
                        var phase = 0.0
                        val fadeSamples = (segSamples / 5).coerceAtMost(220)
                        for (i in 0 until segSamples) {
                            phase += twoPi * freq / SAMPLE_RATE
                            var sample = 0.85 * sin(phase) + 0.15 * sin(3.0 * phase)
                            if (i < fadeSamples) {
                                sample *= (i.toDouble() / fadeSamples)
                            } else if (i > segSamples - fadeSamples) {
                                sample *= ((segSamples - i).toDouble() / fadeSamples)
                            }
                            pcm[offset + i] = (sample * 32000.0).toInt().coerceIn(-32767, 32767).toShort()
                        }
                        offset += segSamples
                    }
                }

                playPcmTrack(pcm)
            } catch (e: Exception) {}
        }.start()
    }

    private fun playPcmTrack(pcm: ShortArray) {
        try {
            stopCurrentAudio()

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcm, 0, pcm.size)
            track.play()
            currentTrack = track
        } catch (e: Exception) {}
    }

    fun triggerVibration(context: Context, type: String) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (!vibrator.hasVibrator()) return

            val (timings, amplitudes) = when (type.lowercase()) {
                "urgent", "siren" -> Pair(
                    longArrayOf(0, 500, 150, 500, 150, 500, 150, 500, 150, 500),
                    intArrayOf(0, 255, 0, 255, 0, 255, 0, 255, 0, 255)
                )
                "vibrate" -> Pair(
                    longArrayOf(0, 400, 150, 400, 150, 400, 150, 800),
                    intArrayOf(0, 255, 0, 255, 0, 255, 0, 255)
                )
                else -> Pair(
                    longArrayOf(0, 180, 80, 260, 120, 180, 80, 360),
                    intArrayOf(0, 255, 0, 255, 0, 255, 0, 255)
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
        } catch (e: Exception) {}
    }
}
