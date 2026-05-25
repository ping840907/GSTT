package com.example.voiceime.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "GemmaIME"
private const val MODEL_FILENAME = "model.litertlm"
private const val MAX_TOKENS = 8192

private enum class SocVendor { QUALCOMM, MEDIATEK, GOOGLE_TENSOR, UNKNOWN }

sealed class EngineState {
    object Uninitialized : EngineState()
    object Loading : EngineState()
    data class Ready(val backend: String) : EngineState()
    data class Error(val message: String) : EngineState()
}

@Singleton
class GemmaInferenceManager @Inject constructor(
    private val context: Context
) {
    private var engine: Engine? = null
    private var activeBackend: String = "CPU"

    @Volatile
    var state: EngineState = EngineState.Uninitialized
        private set

    // ── Initialization ────────────────────────────────────────────────────────

    suspend fun initialize(): EngineState = withContext(Dispatchers.IO) {
        if (state is EngineState.Ready) return@withContext state
        state = EngineState.Loading
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)

        val modelPath = resolveModelPath() ?: run {
            return@withContext EngineState.Error("找不到 $MODEL_FILENAME。請依照說明安裝模型。").also { state = it }
        }

        val vendor = detectSocVendor()
        val (eng, backendLabel) = tryCreateEngine(modelPath, vendor)
        if (eng == null) {
            return@withContext EngineState.Error("模型初始化失敗，請確認裝置記憶體是否充足。").also { state = it }
        }
        engine = eng
        activeBackend = backendLabel
        EngineState.Ready(backendLabel).also { state = it }
            .also { Log.i(TAG, "Gemma ready — backend=$backendLabel  model=$modelPath") }
    }

    fun isModelInstalled(): Boolean = resolveModelPath() != null

    // ── Single-event stateless transcription (image + text) ───────────────────
    //
    // Creates a fresh Conversation for each call and closes it immediately on
    // completion to ensure KV-cache and all references are freed. This fulfils
    // the strict offline per-event stateless contract.

    suspend fun transcribeOnce(
        systemInstruction: String,
        textPrompt: String,
        screenshot: Bitmap?
    ): String = withContext(Dispatchers.IO) {
        val eng = engine ?: error("Engine not initialised — call initialize() first")
        var conversation: Conversation? = null
        try {
            val samplerCfg = if (activeBackend == "NPU") null
            else SamplerConfig(topK = 40, topP = 0.95, temperature = 0.1)

            conversation = eng.createConversation(
                ConversationConfig(
                    samplerConfig = samplerCfg,
                    systemInstruction = Contents.of(listOf(Content.Text(systemInstruction)))
                )
            )

            val contentParts = buildList {
                if (screenshot != null) {
                    // Heap guard: PNG encoding + KV-cache allocation ~5× raw bitmap size
                    val estimatedMb = (screenshot.byteCount.toLong() * 5) / 1_048_576L
                    val runtime = Runtime.getRuntime()
                    val freeHeapMb = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / 1_048_576L
                    if (freeHeapMb > estimatedMb + 64) {
                        add(Content.ImageBytes(screenshot.toPng()))
                    } else {
                        Log.w(TAG, "Heap guard: skipping image. free=${freeHeapMb}MB estimated=${estimatedMb}MB")
                    }
                }
                add(Content.Text(textPrompt))
            }

            suspendCancellableCoroutine { cont ->
                val active = AtomicBoolean(true)
                val sb = StringBuilder()
                conversation!!.sendMessageAsync(
                    Contents.of(contentParts),
                    object : MessageCallback {
                        override fun onMessage(message: Message) {
                            if (active.get()) sb.append(message.toString())
                        }
                        override fun onDone() {
                            if (active.compareAndSet(true, false)) cont.resume(sb.toString().trim())
                        }
                        override fun onError(throwable: Throwable) {
                            if (active.compareAndSet(true, false)) {
                                Log.e(TAG, "Inference error", throwable)
                                cont.resumeWithException(throwable)
                            }
                        }
                    },
                    emptyMap()
                )
                cont.invokeOnCancellation { active.set(false) }
            }
        } finally {
            conversation?.close()
            conversation = null
        }
    }

    // ── Backend selection (mirrors gemmakey logic) ────────────────────────────

    private fun tryCreateEngine(modelPath: String, vendor: SocVendor): Pair<Engine?, String> {
        if (vendor == SocVendor.QUALCOMM || vendor == SocVendor.MEDIATEK) {
            runCatching {
                buildEngine(modelPath, Backend.NPU(context.applicationInfo.nativeLibraryDir), Backend.CPU())
            }.onSuccess { return it to "NPU" }
             .onFailure { Log.w(TAG, "NPU unavailable: ${it.message}") }
        }
        if (vendor == SocVendor.GOOGLE_TENSOR) {
            Log.i(TAG, "Google Tensor NPU requires AOT model — skipping, falling through to GPU")
        }
        runCatching { buildEngine(modelPath, Backend.GPU(), Backend.GPU()) }
            .onSuccess { return it to "GPU" }
            .onFailure { Log.w(TAG, "GPU unavailable: ${it.message}") }
        runCatching { buildEngine(modelPath, Backend.CPU(), Backend.CPU()) }
            .onSuccess { return it to "CPU" }
            .onFailure { Log.e(TAG, "CPU also failed: ${it.message}") }
        return null to "CPU"
    }

    private fun buildEngine(modelPath: String, backend: Backend, visionBackend: Backend): Engine {
        val cfg = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            visionBackend = visionBackend,
            maxNumTokens = MAX_TOKENS,
            cacheDir = if (modelPath.startsWith("/data/local/tmp"))
                context.getExternalFilesDir(null)?.absolutePath else null
        )
        return Engine(cfg).also { it.initialize() }
    }

    private fun detectSocVendor(): SocVendor {
        val mfr = Build.SOC_MANUFACTURER.lowercase()
        val model = Build.SOC_MODEL.lowercase()
        val brand = Build.BRAND.lowercase()
        return when {
            mfr.contains("qualcomm") -> SocVendor.QUALCOMM
            mfr.contains("mediatek") -> SocVendor.MEDIATEK
            brand == "google" || mfr.contains("google")
                || model.startsWith("gs") || model.startsWith("zuma")
                || model == "tango" || model == "rio" -> SocVendor.GOOGLE_TENSOR
            else -> SocVendor.UNKNOWN
        }
    }

    private fun resolveModelPath(): String? {
        val locations = listOf(
            File(context.getExternalFilesDir(null), MODEL_FILENAME),
            File(context.filesDir, MODEL_FILENAME),
            File("/data/local/tmp", MODEL_FILENAME)
        )
        return locations.firstOrNull { it.exists() && it.length() > 0 }?.absolutePath
    }

    private fun Bitmap.toPng(): ByteArray {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    fun close() {
        engine?.close()
        engine = null
        state = EngineState.Uninitialized
    }
}
