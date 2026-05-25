package com.example.voiceime.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AudioCapture"

/** 16 kHz mono 16-bit PCM — matches Gemma 4's audio encoder requirement */
const val SAMPLE_RATE_HZ = 16000
const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

@Singleton
class AudioCaptureManager @Inject constructor(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT)
        .coerceAtLeast(4096)

    val hasMicPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Records until [stopSignal] returns true, then returns the raw PCM bytes.
     * Caller must hold RECORD_AUDIO permission before invoking this.
     * Returns null if recording fails or permission is missing.
     */
    suspend fun record(stopSignal: () -> Boolean): ByteArray? = withContext(Dispatchers.IO) {
        if (!hasMicPermission) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            return@withContext null
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBufferSize * 4
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            recorder.release()
            return@withContext null
        }

        audioRecord = recorder
        val out = ByteArrayOutputStream()
        val buf = ShortArray(minBufferSize / 2)

        try {
            recorder.startRecording()
            Log.d(TAG, "Recording started at ${SAMPLE_RATE_HZ}Hz")

            while (isActive && !stopSignal()) {
                val read = recorder.read(buf, 0, buf.size)
                if (read > 0) {
                    // Convert ShortArray → ByteArray (little-endian PCM)
                    for (i in 0 until read) {
                        val s = buf[i]
                        out.write(s.toInt() and 0xFF)
                        out.write((s.toInt() shr 8) and 0xFF)
                    }
                }
            }

            recorder.stop()
            Log.d(TAG, "Recording stopped: ${out.size()} PCM bytes")
            out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Recording error", e)
            null
        } finally {
            recorder.release()
            audioRecord = null
        }
    }

    /** Builds a minimal WAV header so the PCM bytes can be recognised as audio. */
    fun pcmToWav(pcmBytes: ByteArray): ByteArray {
        val totalDataLen = pcmBytes.size + 36
        val byteRate = SAMPLE_RATE_HZ * 2 // 1 channel × 16-bit
        return ByteArray(44 + pcmBytes.size).also { wav ->
            fun Int.le4(off: Int) { wav[off]=(this and 0xFF).toByte(); wav[off+1]=((this shr 8) and 0xFF).toByte(); wav[off+2]=((this shr 16) and 0xFF).toByte(); wav[off+3]=((this shr 24) and 0xFF).toByte() }
            fun Int.le2(off: Int) { wav[off]=(this and 0xFF).toByte(); wav[off+1]=((this shr 8) and 0xFF).toByte() }
            "RIFF".toByteArray().copyInto(wav, 0)
            totalDataLen.le4(4)
            "WAVE".toByteArray().copyInto(wav, 8)
            "fmt ".toByteArray().copyInto(wav, 12)
            16.le4(16);  1.le2(20);  1.le2(22)  // PCM, mono
            SAMPLE_RATE_HZ.le4(24); byteRate.le4(28); 2.le2(32); 16.le2(34)
            "data".toByteArray().copyInto(wav, 36)
            pcmBytes.size.le4(40)
            pcmBytes.copyInto(wav, 44)
        }
    }
}
