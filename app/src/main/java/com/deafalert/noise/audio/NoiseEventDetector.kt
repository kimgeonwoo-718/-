package com.deafalert.noise.audio

/**
 * A single dB reading paired with the wall-clock time it was measured at.
 * Using a timestamp instead of a sample counter keeps detection correct
 * regardless of how often [AudioMonitor] happens to deliver readings.
 */
data class NoiseSample(val decibelValue: Double, val timestampMs: Long)

/**
 * Outcome of feeding one [NoiseSample] into a detector.
 *
 * @param elapsedAboveThresholdMs how long the qualifying condition has been held continuously,
 *   reset to 0 the moment it stops holding.
 * @param isTriggered true once the detector's own trigger condition (e.g. sustained duration)
 *   has been satisfied. Stays true while the condition keeps holding.
 */
data class NoiseDetectionResult(
    val decibelValue: Double,
    val elapsedAboveThresholdMs: Long,
    val isTriggered: Boolean,
)

/**
 * Strategy interface for deciding whether a stream of noise samples should raise an alert.
 *
 * [SustainedLoudnessDetector] is today's implementation (plain volume + duration threshold).
 * Swapping in a future doorbell-pattern recognizer only requires a new implementation of this
 * interface - callers (e.g. MainActivity) do not need to change.
 */
interface NoiseEventDetector {
    fun evaluate(sample: NoiseSample): NoiseDetectionResult
    fun reset()
}

/**
 * Triggers when the decibel level stays at or above [thresholdDb] continuously for at least
 * [sustainedDurationMs]. Dropping below the threshold for even one sample resets the streak,
 * which is what keeps momentary spikes from firing a false alert.
 */
class SustainedLoudnessDetector(
    private val thresholdDb: Double = DEFAULT_THRESHOLD_DB,
    private val sustainedDurationMs: Long = DEFAULT_SUSTAINED_DURATION_MS,
) : NoiseEventDetector {

    private var streakStartMs: Long? = null

    override fun evaluate(sample: NoiseSample): NoiseDetectionResult {
        val startedAt = streakStartMs

        if (sample.decibelValue >= thresholdDb) {
            if (startedAt == null) {
                streakStartMs = sample.timestampMs
            }
        } else {
            streakStartMs = null
        }

        val elapsed = streakStartMs?.let { sample.timestampMs - it } ?: 0L
        return NoiseDetectionResult(
            decibelValue = sample.decibelValue,
            elapsedAboveThresholdMs = elapsed,
            isTriggered = elapsed >= sustainedDurationMs,
        )
    }

    override fun reset() {
        streakStartMs = null
    }

    companion object {
        const val DEFAULT_THRESHOLD_DB = 60.0
        const val DEFAULT_SUSTAINED_DURATION_MS = 5000L
    }
}
