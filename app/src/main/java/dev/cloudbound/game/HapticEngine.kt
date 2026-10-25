package dev.cloudbound.game

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Native Android haptic feedback controller.
 * Supports API 26+ VibrationEffect with safe fallback and permission checks.
 */
class HapticEngine(context: Context) {
    var hapticsEnabled: Boolean = true

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val hasVibrator: Boolean = vibrator?.hasVibrator() == true

    fun vibrateCoin() {
        if (!hapticsEnabled || !hasVibrator) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(18L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(18L)
            }
        } catch (_: Throwable) {}
    }

    fun vibrateHazard() {
        if (!hapticsEnabled || !hasVibrator) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(75L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(75L)
            }
        } catch (_: Throwable) {}
    }

    fun vibrateGameOver() {
        if (!hapticsEnabled || !hasVibrator) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0L, 45L, 50L, 90L)
                val amplitudes = intArrayOf(0, 180, 0, 255)
                vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0L, 45L, 50L, 90L), -1)
            }
        } catch (_: Throwable) {}
    }

    fun vibrateButton() {
        if (!hapticsEnabled || !hasVibrator) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(12L, 100))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(12L)
            }
        } catch (_: Throwable) {}
    }
}
