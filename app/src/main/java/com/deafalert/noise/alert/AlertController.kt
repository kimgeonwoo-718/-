package com.deafalert.noise.alert

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.CombinedVibration
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Fires the physical alert for a confirmed noise event: a blinking camera flashlight plus a
 * strong vibration pattern. Both run for [ALERT_DURATION_MS] and are safe to call repeatedly -
 * a call while an alert is already in progress is ignored so a sustained trigger doesn't stack
 * overlapping blink loops.
 */
class AlertController(context: Context) {

    private val appContext = context.applicationContext
    private val cameraManager =
        appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val flashCameraId: String? = findCameraWithFlash()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var blinkRunnable: Runnable? = null
    private var torchOn = false

    @Volatile
    var isAlerting = false
        private set

    fun triggerAlert(durationMs: Long = ALERT_DURATION_MS) {
        if (isAlerting) return
        isAlerting = true

        startFlashBlink()
        vibrate(durationMs)

        mainHandler.postDelayed({ stopAlert() }, durationMs)
    }

    fun stopAlert() {
        blinkRunnable?.let { mainHandler.removeCallbacks(it) }
        blinkRunnable = null
        setTorch(false)
        isAlerting = false
    }

    fun release() {
        stopAlert()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun startFlashBlink() {
        if (flashCameraId == null) return

        val runnable = object : Runnable {
            override fun run() {
                torchOn = !torchOn
                setTorch(torchOn)
                mainHandler.postDelayed(this, BLINK_INTERVAL_MS)
            }
        }
        blinkRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun setTorch(on: Boolean) {
        val id = flashCameraId ?: return
        try {
            cameraManager.setTorchMode(id, on)
        } catch (_: CameraAccessException) {
            // Camera may be in use by another app - nothing we can do, skip this toggle.
        }
        if (!on) torchOn = false
    }

    private fun vibrate(durationMs: Long) {
        // VibrationEffect only exists from API 26 onward - it must not be referenced at all
        // in code paths that can run on API 24/25, or class verification crashes there.
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val effect = VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                val manager =
                    appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.vibrate(CombinedVibration.createParallel(effect))
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                val effect = VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                @Suppress("DEPRECATION")
                val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(effect)
            }
            else -> {
                @Suppress("DEPRECATION")
                val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        }
    }

    private fun findCameraWithFlash(): String? = try {
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (_: CameraAccessException) {
        null
    }

    companion object {
        private const val BLINK_INTERVAL_MS = 300L
        const val ALERT_DURATION_MS = 4000L
    }
}
