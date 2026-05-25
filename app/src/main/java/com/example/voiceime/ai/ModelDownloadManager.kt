package com.example.voiceime.ai

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ModelDownload"

@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(
            val progress: Float,
            val downloadedMb: Long,
            val totalMb: Long
        ) : DownloadState()
        object Done : DownloadState()
        data class Failed(val error: String) : DownloadState()
    }

    companion object {
        // Gemma 4 E2B LiteRT-LM from HuggingFace (same source as gemmakey)
        const val MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        const val MODEL_FILENAME = "model.litertlm"
        const val MODEL_SIZE_GB = 2
    }

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    // Own scope so download survives Activity/ViewModel recreation
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null

    fun startDownload() {
        // Both the guard check and the initial state write must happen synchronously
        // (before the coroutine launches) so rapid double-taps cannot both pass the
        // check while the state is still Idle.
        if (_state.value is DownloadState.Downloading) return
        _state.value = DownloadState.Downloading(0f, 0L, 0L)
        downloadJob?.cancel()
        downloadJob = scope.launch {
            val destDir = context.getExternalFilesDir(null) ?: context.filesDir
            val tempFile = File(destDir, "$MODEL_FILENAME.tmp")
            val finalFile = File(destDir, MODEL_FILENAME)

            // Resume from where we left off if a partial download exists.
            val resumeOffset = if (tempFile.exists()) tempFile.length() else 0L

            try {
                val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true
                if (resumeOffset > 0) conn.setRequestProperty("Range", "bytes=$resumeOffset-")
                conn.connect()

                val responseCode = conn.responseCode
                val isResume = responseCode == HttpURLConnection.HTTP_PARTIAL  // 206
                val isFull   = responseCode == HttpURLConnection.HTTP_OK       // 200
                if (!isResume && !isFull) {
                    conn.disconnect()
                    _state.value = DownloadState.Failed("伺服器錯誤 HTTP $responseCode")
                    return@launch
                }

                // Server returned 200 (ignored Range header) — restart from 0.
                val startOffset = if (isResume) resumeOffset else 0L
                val remaining = conn.contentLengthLong
                val total = if (remaining > 0) startOffset + remaining else -1L
                var downloaded = startOffset

                conn.inputStream.use { input ->
                    // append=true resumes writing; append=false overwrites on full-content 200.
                    FileOutputStream(tempFile, isResume).use { output ->
                        val buf = ByteArray(65_536)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            ensureActive()
                            output.write(buf, 0, n)
                            downloaded += n
                            val progress = if (total > 0) downloaded.toFloat() / total else 0f
                            _state.value = DownloadState.Downloading(
                                progress,
                                downloaded / 1_048_576L,
                                total / 1_048_576L
                            )
                        }
                    }
                }
                conn.disconnect()

                // Atomic rename; fallback to copy+delete if rename crosses filesystem boundaries
                if (!tempFile.renameTo(finalFile)) {
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                }
                Log.i(TAG, "Model downloaded → ${finalFile.absolutePath}")
                _state.value = DownloadState.Done
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Keep tempFile so the next startDownload() can resume from the same offset.
                _state.value = DownloadState.Idle
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                tempFile.delete()
                _state.value = DownloadState.Failed(e.message ?: "下載失敗，請檢查網路連線")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _state.value = DownloadState.Idle
    }
}
