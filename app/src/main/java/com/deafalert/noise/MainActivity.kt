package com.deafalert.noise

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.deafalert.noise.alert.AlertController
import com.deafalert.noise.audio.AudioMonitor
import com.deafalert.noise.audio.NoiseDetectionResult
import com.deafalert.noise.audio.NoiseSample
import com.deafalert.noise.audio.SustainedLoudnessDetector
import com.deafalert.noise.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var alertController: AlertController

    private val noiseDetector = SustainedLoudnessDetector(
        thresholdDb = SustainedLoudnessDetector.DEFAULT_THRESHOLD_DB,
        sustainedDurationMs = SustainedLoudnessDetector.DEFAULT_SUSTAINED_DURATION_MS,
    )
    private var audioMonitor: AudioMonitor? = null
    private var hasFiredForCurrentStreak = false

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            binding.tvPermissionWarning.visibility = View.GONE
            startMonitoring()
        } else {
            binding.tvPermissionWarning.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        alertController = AlertController(this)

        binding.btnToggle.setOnClickListener {
            if (audioMonitor?.isActive == true) {
                stopMonitoring()
            } else if (hasAllPermissions()) {
                startMonitoring()
            } else {
                permissionLauncher.launch(requiredPermissions)
            }
        }
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission") // guarded by hasAllPermissions() at both call sites
    private fun startMonitoring() {
        if (!hasAllPermissions() || audioMonitor?.isActive == true) return

        noiseDetector.reset()
        hasFiredForCurrentStreak = false

        val monitor = AudioMonitor(
            onSample = ::handleNoiseSample,
            onError = ::handleMonitorError,
        )
        audioMonitor = monitor
        monitor.start()

        binding.btnToggle.text = getString(R.string.btn_stop)
        binding.tvStatus.text = getString(R.string.status_monitoring)
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_monitoring))
    }

    private fun stopMonitoring() {
        audioMonitor?.stop()
        audioMonitor = null
        noiseDetector.reset()
        hasFiredForCurrentStreak = false
        alertController.stopAlert()

        binding.btnToggle.text = getString(R.string.btn_start)
        binding.tvDecibel.text = "0.0"
        binding.progressDecibel.progress = 0
        binding.progressElapsed.progress = 0
        binding.tvElapsed.text = "0.0 / 5.0초"
        binding.tvStatus.text = getString(R.string.status_idle)
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_idle))
    }

    private fun handleNoiseSample(sample: NoiseSample) {
        binding.tvDecibel.text = String.format(Locale.getDefault(), "%.1f", sample.decibelValue)
        binding.progressDecibel.progress = sample.decibelValue.toInt().coerceIn(0, binding.progressDecibel.max)

        val result = noiseDetector.evaluate(sample)
        updateElapsedUi(result)
        updateStatusUi(result)
    }

    private fun updateElapsedUi(result: NoiseDetectionResult) {
        val cappedElapsedMs = result.elapsedAboveThresholdMs.coerceAtMost(
            SustainedLoudnessDetector.DEFAULT_SUSTAINED_DURATION_MS
        )
        binding.progressElapsed.progress = cappedElapsedMs.toInt()
        binding.tvElapsed.text = String.format(
            Locale.getDefault(),
            "%.1f / 5.0초",
            cappedElapsedMs / 1000.0,
        )
    }

    private fun updateStatusUi(result: NoiseDetectionResult) {
        when {
            result.isTriggered -> {
                binding.tvStatus.text = getString(R.string.status_triggered)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_triggered))
                if (!hasFiredForCurrentStreak) {
                    hasFiredForCurrentStreak = true
                    alertController.triggerAlert()
                }
            }
            result.elapsedAboveThresholdMs > 0 -> {
                binding.tvStatus.text = getString(R.string.status_monitoring)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_monitoring))
            }
            else -> {
                binding.tvStatus.text = getString(R.string.status_idle)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_idle))
                hasFiredForCurrentStreak = false
            }
        }
    }

    private fun handleMonitorError(error: Throwable) {
        Log.e(TAG, "Audio monitoring error", error)
        stopMonitoring()
        binding.tvPermissionWarning.visibility = View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        // Foreground-only for this MVP: release the mic/camera when the screen isn't visible
        // instead of silently failing under background mic restrictions on newer Android.
        if (audioMonitor?.isActive == true) {
            stopMonitoring()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioMonitor?.stop()
        alertController.release()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
