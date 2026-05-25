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
        if (_state.value is DownloadState.Downloading) return
        downloadJob?.cancel()
        downloadJob = scope.launch {
            val destDir = context.getExternalFilesDir(null) ?: context.filesDir
            val tempFile = File(destDir, "$MODEL_FILENAME.tmp")
            val finalFile = File(destDir, MODEL_FILENAME)

            try {
                _state.value = DownloadState.Downloading(0f, 0L, 0L)

                val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true
                conn.connect()

                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    conn.disconnect()
                    _state.value = DownloadState.Failed("伺服器錯誤 HTTP ${conn.responseCode}")
                    return@launch
                }

                val total = conn.contentLengthLong
                var downloaded = 0L

                conn.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
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
                tempFile.delete()
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
