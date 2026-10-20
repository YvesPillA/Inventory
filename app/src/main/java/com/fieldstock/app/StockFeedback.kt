package com.fieldstock.app

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Feedback is best effort: an unavailable motor/audio device never turns a saved row into an error. */
internal class StockFeedback(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private var tone: ToneGenerator? = null

    fun play(duplicate: Boolean, vibration: Boolean, sound: Boolean) {
        if (vibration) runCatching {
            val motor = if (Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java)?.defaultVibrator
                else @Suppress("DEPRECATION") (app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            if (motor?.hasVibrator() == true) motor.vibrate(if (duplicate)
                VibrationEffect.createWaveform(longArrayOf(0, 70, 70, 120), -1)
                else VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        if (sound) runCatching {
            val audio = app.getSystemService(AudioManager::class.java)
            if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL && audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) > 0) {
                val player = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60).also { tone = it }
                player.startTone(if (duplicate) ToneGenerator.TONE_PROP_NACK else ToneGenerator.TONE_PROP_ACK, if (duplicate) 300 else 140)
            }
        }
    }

    override fun close() { tone?.release(); tone = null }
}
