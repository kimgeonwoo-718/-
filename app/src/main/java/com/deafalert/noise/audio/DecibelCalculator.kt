package com.deafalert.noise.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Converts raw 16-bit PCM samples into a decibel value using RMS + 20*log10(RMS).
 *
 * The result is NOT calibrated to a physical dB SPL meter - the raw amplitude scale of a
 * 16-bit PCM buffer only loosely tracks real-world sound pressure, and the mapping differs
 * per device/microphone gain. [CALIBRATION_OFFSET_DB] exists so the app can be tuned against
 * a reference sound level meter without touching the math.
 */
object DecibelCalculator {

    /** Adjust after comparing readings against a reference SPL meter on target devices. */
    const val CALIBRATION_OFFSET_DB: Double = 0.0

    /** Silence floor: RMS below this is treated as 0 dB to avoid log10(0) / negative infinity. */
    private const val MIN_RMS = 1.0

    fun calculateDecibel(buffer: ShortArray, sampleCount: Int): Double {
        if (sampleCount <= 0) return 0.0

        var sumOfSquares = 0.0
        for (i in 0 until sampleCount) {
            val sample = buffer[i].toDouble()
            sumOfSquares += sample * sample
        }
        val rms = sqrt(sumOfSquares / sampleCount)
        if (rms < MIN_RMS) return 0.0

        val decibel = 20 * log10(rms) + CALIBRATION_OFFSET_DB
        return decibel.coerceAtLeast(0.0)
    }
}
