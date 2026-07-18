package com.deafalert.noise.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.annotation.RequiresPermission
import kotlin.math.max

/**
 * Wraps [AudioRecord] and continuously reports decibel readings computed by
 * [DecibelCalculator]. Reading happens on a dedicated background thread; callbacks are
 * always delivered on the main thread so callers can update UI directly.
 */
class AudioMonitor(
    private val onSample: (NoiseSample) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    @Volatile
    private var isRunning = false

    val isActive: Boolean get() = isRunning

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (isRunning) return

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            onError(IllegalStateException("이 기기에서 지원하지 않는 오디오 설정입니다."))
            return
        }
        val bufferSizeInBytes = max(minBufferSize, minBufferSize * 2)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_HZ,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSizeInBytes,
            )
        } catch (e: SecurityException) {
            onError(e)
            return
        } catch (e: IllegalArgumentException) {
            onError(e)
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError(IllegalStateException("마이크를 초기화할 수 없습니다."))
            return
        }

        audioRecord = record
        isRunning = true

        // PCM_16BIT: 2 bytes per sample.
        val bufferSizeInShorts = bufferSizeInBytes / 2
        val thread = Thread({ runRecordingLoop(record, bufferSizeInShorts) }, "AudioMonitor-Thread")
        recordingThread = thread
        thread.start()
    }

    private fun runRecordingLoop(record: AudioRecord, bufferSizeInShorts: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ShortArray(bufferSizeInShorts)

        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            isRunning = false
            mainHandler.post { onError(e) }
            return
        }

        while (isRunning) {
            val readCount = record.read(buffer, 0, buffer.size)
            if (readCount > 0) {
                val decibel = DecibelCalculator.calculateDecibel(buffer, readCount)
                val timestamp = System.currentTimeMillis()
                mainHandler.post { onSample(NoiseSample(decibel, timestamp)) }
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false

        recordingThread?.join(500)
        recordingThread = null

        audioRecord?.let { record ->
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (_: IllegalStateException) {
                // Already stopped - nothing to clean up.
            }
            record.release()
        }
        audioRecord = null
    }

    companion object {
        private const val SAMPLE_RATE_HZ = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
}
