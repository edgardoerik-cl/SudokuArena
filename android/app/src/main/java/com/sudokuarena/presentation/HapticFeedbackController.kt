package com.sudokuarena.presentation

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Adaptador Android aislado: el ViewModel sólo emite intenciones hápticas. */
class HapticFeedbackController(context: Context) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    fun play(cue: HapticCue) {
        if (!vibrator.hasVibrator()) return
        val effect = when (cue) {
            HapticCue.CLICK -> VibrationEffect.createOneShot(28, 90)
            HapticCue.DANGER -> VibrationEffect.createWaveform(
                longArrayOf(0, 110, 70, 190),
                intArrayOf(0, 220, 0, 255),
                -1,
            )
            HapticCue.CRESCENDO -> VibrationEffect.createWaveform(
                longArrayOf(0, 45, 45, 65, 35, 90),
                intArrayOf(0, 70, 0, 150, 0, 255),
                -1,
            )
        }
        vibrator.vibrate(effect)
    }
}
