package com.example.voiceime.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "AudioRecorder"
private const val SAMPLE_RATE = 16000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
private const val MAX_SECONDS = 30

/**
 * Captures PCM audio from the microphone and wraps it in a WAV container.
 *
 * Design:
 *  - [start] runs the recording loop on the IO dispatcher, writing every
 *    chunk into [pcmBuffer] under [lock] as it arrives.
 *  - [stopAndGetWav] only flips [isRecording] and snapshots [pcmBuffer] —
 *    it never calls AudioRecord.read(), so it never blocks the main thread.
 *  - AudioRecord lifecycle (startRecording / stop / release) stays entirely
 *    inside the background coroutine.
 */
class AudioRecorder {
    @Volatile private var isRecording = false
    private val pcmBuffer = ByteArrayOutputStream()
    private val lock = Any()

    suspend fun start(onAmplitude: ((Float) -> Unit)? = null) = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        // ~100 ms per read so the loop reacts to stopAndGetWav() within 100 ms.
        val chunkBytes = (SAMPLE_RATE * 2 * 0.1).toInt().coerceAtLeast(minBuf)
        val recordBufSize = chunkBytes * 4

        @Suppress("MissingPermission")
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, recordBufSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            Log.e(TAG, "AudioRecord failed to initialise")
            return@withContext
        }

        synchronized(lock) { pcmBuffer.reset() }
        isRecording = true
        record.startRecording()
        Log.i(TAG, "Recording started — chunkBytes=$chunkBytes")

        val buf = ByteArray(chunkBytes)
        val maxBytes = SAMPLE_RATE * 2 * MAX_SECONDS
        var totalBytes = 0

        while (isActive && isRecording && totalBytes < maxBytes) {
            val n = record.read(buf, 0, buf.size)
            if (n <= 0) break
            totalBytes += n
            synchronized(lock) { pcmBuffer.write(buf, 0, n) }
            onAmplitude?.invoke(peakAmplitude(buf, n))
        }

        record.stop()
        record.release()
        Log.i(TAG, "Recording ended — totalBytes=$totalBytes")
    }

    /**
     * Signals the recording loop to stop and immediately returns all audio
     * captured so far as a RIFF/WAV byte array.
     *
     * This function is safe to call on the main thread — it never blocks.
     * Returns null when less than ~0.1 s of audio was captured.
     */
    fun stopAndGetWav(): ByteArray? {
        isRecording = false
        val pcm = synchronized(lock) { pcmBuffer.toByteArray() }
        return if (pcm.size < 3200) null else buildWav(pcm)
    }

    // ── WAV header ────────────────────────────────────────────────────────────

    private fun buildWav(pcm: ByteArray): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val totalSize = 44 + dataSize

        val wav = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        wav.put("RIFF".toByteArray())
        wav.putInt(totalSize - 8)
        wav.put("WAVE".toByteArray())

        wav.put("fmt ".toByteArray())
        wav.putInt(16)
        wav.putShort(1)
        wav.putShort(channels.toShort())
        wav.putInt(SAMPLE_RATE)
        wav.putInt(byteRate)
        wav.putShort(blockAlign.toShort())
        wav.putShort(bitsPerSample.toShort())

        wav.put("data".toByteArray())
        wav.putInt(dataSize)
        wav.put(pcm)

        return wav.array()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun peakAmplitude(buf: ByteArray, n: Int): Float {
        var peak = 0
        var i = 0
        while (i + 1 < n) {
            val sample = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            val abs = Math.abs(sample.toShort().toInt())
            if (abs > peak) peak = abs
            i += 2
        }
        return (peak / 32767f).coerceIn(0f, 1f)
    }
}
