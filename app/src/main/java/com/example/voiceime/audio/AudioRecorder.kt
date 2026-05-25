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
 * Usage: call [start], then [stopAndGetWav] when the user releases the button.
 * Mirrors the recording approach from AI Edge Gallery (AudioRecorderPanel.kt).
 */
class AudioRecorder {
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false

    /**
     * Starts recording on the IO dispatcher. Returns immediately; audio is
     * captured in the background until [stopAndGetWav] is called or the
     * coroutine is cancelled.
     *
     * [onAmplitude] receives a 0–1 normalised peak amplitude each buffer for UI.
     */
    suspend fun start(onAmplitude: ((Float) -> Unit)? = null) = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufSize = minBuf.coerceAtLeast(SAMPLE_RATE * 2)   // ≥ 1 s worth of data

        @Suppress("MissingPermission")
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            Log.e(TAG, "AudioRecord failed to initialise")
            return@withContext
        }
        audioRecord = record
        isRecording = true

        record.startRecording()
        Log.i(TAG, "Recording started — sampleRate=$SAMPLE_RATE bufSize=$bufSize")

        val chunk = ShortArray(bufSize / 2)
        val maxSamples = SAMPLE_RATE * MAX_SECONDS
        var samplesRead = 0

        while (isActive && isRecording && samplesRead < maxSamples) {
            val n = record.read(chunk, 0, chunk.size)
            if (n <= 0) break
            samplesRead += n
            onAmplitude?.invoke(peakAmplitude(chunk, n))
        }
        record.stop()
        Log.i(TAG, "Recording loop ended — samplesRead=$samplesRead")
    }

    /**
     * Signals the recording loop to stop and returns all recorded audio as a
     * complete WAV (RIFF) byte array ready for [Content.AudioBytes].
     * Returns null if no audio was captured.
     */
    fun stopAndGetWav(): ByteArray? {
        isRecording = false
        val record = audioRecord ?: return null
        audioRecord = null

        val pcm = collectPcm(record)
        record.release()
        if (pcm.isEmpty()) return null
        return buildWav(pcm)
    }

    // ── PCM collection ────────────────────────────────────────────────────────

    private fun collectPcm(record: AudioRecord): ByteArray {
        // Read whatever the AudioRecord still has buffered after we called stop().
        val out = ByteArrayOutputStream()
        val tmp = ByteArray(record.bufferSizeInFrames * 2)
        var n: Int
        while (record.read(tmp, 0, tmp.size).also { n = it } > 0) {
            out.write(tmp, 0, n)
        }
        return out.toByteArray()
    }

    // ── WAV header ────────────────────────────────────────────────────────────

    /**
     * Wraps raw PCM bytes in a standard 44-byte RIFF/WAVE header.
     * Matches the genByteArrayForWav() implementation in AI Edge Gallery.
     */
    private fun buildWav(pcm: ByteArray): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val totalSize = 44 + dataSize

        val wav = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk
        wav.put("RIFF".toByteArray())
        wav.putInt(totalSize - 8)
        wav.put("WAVE".toByteArray())

        // fmt sub-chunk
        wav.put("fmt ".toByteArray())
        wav.putInt(16)                       // sub-chunk size for PCM
        wav.putShort(1)                      // PCM format
        wav.putShort(channels.toShort())
        wav.putInt(SAMPLE_RATE)
        wav.putInt(byteRate)
        wav.putShort(blockAlign.toShort())
        wav.putShort(bitsPerSample.toShort())

        // data sub-chunk
        wav.put("data".toByteArray())
        wav.putInt(dataSize)
        wav.put(pcm)

        return wav.array()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun peakAmplitude(chunk: ShortArray, n: Int): Float {
        var peak = 0
        for (i in 0 until n) {
            val abs = Math.abs(chunk[i].toInt())
            if (abs > peak) peak = abs
        }
        return (peak / 32767f).coerceIn(0f, 1f)
    }
}
