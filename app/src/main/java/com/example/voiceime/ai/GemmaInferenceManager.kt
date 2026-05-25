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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

// Low temperature for deterministic correction; small topK still allows the
// minor lexical variance needed to fix homophones without hallucinating.
private const val TOP_K = 10
private const val TOP_P = 0.95
private const val TEMPERATURE = 0.3

private enum class SocVendor { QUALCOMM, MEDIATEK, GOOGLE_TENSOR, UNKNOWN }

// Sentinel thrown inside suspendCancellableCoroutine when LiteRT-LM rejects the
// image with the pre-patch "more images than expected" error (Issue #1874).
// Caught one frame up in transcribeOnce() to transparently retry text-only.
private class ImageRejectedByRuntimeException : Exception()

sealed class EngineState {
    object Uninitialized : EngineState()
    object Loading : EngineState()
    data class Ready(val backend: String) : EngineState()
    data class Error(val message: String) : EngineState()
}

@Singleton
class GemmaInferenceManager @Inject constructor(
    private val context: Context,
    private val deviceCapability: DeviceCapability
) {
    @Volatile private var engine: Engine? = null
    private var activeBackend: String = "CPU"

    @Volatile
    var state: EngineState = EngineState.Uninitialized
        private set

    // Guards concurrent initialize() calls — prevents double engine creation.
    private val initMutex = Mutex()

    // ── Initialisation ────────────────────────────────────────────────────────

    suspend fun initialize(): EngineState = withContext(Dispatchers.IO) {
        if (state is EngineState.Ready) return@withContext state
        initMutex.withLock {
            // Double-check after acquiring lock — a concurrent call may have finished first.
            if (state is EngineState.Ready) return@withLock state
            state = EngineState.Loading
            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)

            val modelPath = resolveModelPath() ?: run {
                return@withContext EngineState.Error(
                    "找不到 $MODEL_FILENAME。請將模型複製到：\nAndroid/data/com.example.voiceime/files/"
                ).also { state = it }
            }

            val vendor = detectSocVendor()
            val (eng, backendLabel) = tryCreateEngine(modelPath, vendor)
            if (eng == null) {
                return@withContext EngineState.Error("模型初始化失敗，請確認裝置記憶體是否充足。")
                    .also { state = it }
            }
            engine = eng
            activeBackend = backendLabel
            EngineState.Ready(backendLabel).also {
                state = it
                Log.i(TAG, "Gemma 4 E2B ready — backend=$backendLabel  model=$modelPath")
            }
        }
    }

    fun isModelInstalled(): Boolean = resolveModelPath() != null

    // ── Per-event stateless transcription ─────────────────────────────────────
    //
    // Creates a fresh Conversation for each call and closes it on completion,
    // releasing the KV-cache and all native allocations immediately.
    //
    // Modality contract:
    //   • Audio  — LiteRT-LM has no Content.Audio type. Audio is transcribed
    //              upstream by Android's offline SpeechRecognizer and arrives
    //              here as text already embedded in [textPrompt].
    //   • Image  — Passed as Content.ImageBytes when [screenshot] is non-null.
    //              The Gemma 4 image bug (Issue #1874) is fixed in current LiteRT-LM;
    //              we still keep a graceful fallback for pre-patch builds.
    //   • Text   — Screen text + rough ASR transcript + dictionary, all in [textPrompt].

    suspend fun transcribeOnce(
        systemInstruction: String,
        textPrompt: String,
        screenshot: Bitmap?
    ): String = withContext(Dispatchers.IO) {
        val eng = engine ?: error("Engine not initialised — call initialize() first")
        var conversation: Conversation? = null
        try {
            val samplerCfg = if (activeBackend == "NPU") null   // NPU uses fixed quantisation
            else SamplerConfig(topK = TOP_K, topP = TOP_P, temperature = TEMPERATURE.toDouble())

            conversation = eng.createConversation(
                ConversationConfig(
                    samplerConfig = samplerCfg,
                    systemInstruction = Contents.of(listOf(Content.Text(systemInstruction)))
                )
            )

            if (screenshot != null) {
                tryWithImage(conversation, textPrompt, screenshot)
            } else {
                sendText(conversation, textPrompt)
            }
        } finally {
            conversation?.close()
            conversation = null
        }
    }

    // Attempts image + text; if the pre-patch runtime rejects the image,
    // falls back to text-only transparently.
    private suspend fun tryWithImage(
        conversation: Conversation,
        textPrompt: String,
        bitmap: Bitmap
    ): String {
        // Heap guard: PNG encoding + KV-cache allocation ≈ 5× raw bitmap size.
        val estimatedMb = (bitmap.byteCount.toLong() * 5 / 1_048_576L).toInt().coerceAtLeast(30)
        if (!deviceCapability.hasHeapFor(estimatedMb)) {
            Log.w(TAG, "Heap guard: skipping image  est=${estimatedMb}MB")
            return sendText(conversation, textPrompt)
        }

        return try {
            suspendCancellableCoroutine { cont ->
                val active = AtomicBoolean(true)
                val sb = StringBuilder()
                val imageBytes = bitmap.toPng()
                val contents = Contents.of(listOf(Content.ImageBytes(imageBytes), Content.Text(textPrompt)))

                conversation.sendMessageAsync(contents, object : MessageCallback {
                    override fun onMessage(message: Message) {
                        if (active.get()) sb.append(message.toString())
                    }
                    override fun onDone() {
                        if (active.compareAndSet(true, false)) cont.resume(sb.toString().trim())
                    }
                    override fun onError(throwable: Throwable) {
                        if (!active.compareAndSet(true, false)) return
                        val msg = throwable.message ?: ""
                        if (msg.contains("more images than expected", ignoreCase = true)) {
                            // Pre-patch build: Issue #1874 not yet applied on this device.
                            cont.resumeWithException(ImageRejectedByRuntimeException())
                        } else {
                            Log.e(TAG, "Multimodal error", throwable)
                            cont.resumeWithException(throwable)
                        }
                    }
                }, emptyMap())
                cont.invokeOnCancellation { active.set(false) }
            }
        } catch (_: ImageRejectedByRuntimeException) {
            Log.i(TAG, "Image rejected by pre-patch LiteRT-LM — retrying text-only")
            sendText(conversation, textPrompt)
        }
    }

    private suspend fun sendText(conversation: Conversation, textPrompt: String): String =
        suspendCancellableCoroutine { cont ->
            val active = AtomicBoolean(true)
            val sb = StringBuilder()
            conversation.sendMessageAsync(
                Contents.of(listOf(Content.Text(textPrompt))),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        if (active.get()) sb.append(message.toString())
                    }
                    override fun onDone() {
                        if (active.compareAndSet(true, false)) cont.resume(sb.toString().trim())
                    }
                    override fun onError(throwable: Throwable) {
                        if (active.compareAndSet(true, false)) {
                            Log.e(TAG, "Text inference error", throwable)
                            cont.resumeWithException(throwable)
                        }
                    }
                },
                emptyMap()
            )
            cont.invokeOnCancellation { active.set(false) }
        }

    // ── Backend selection (mirrors gemmakey) ──────────────────────────────────

    private fun tryCreateEngine(modelPath: String, vendor: SocVendor): Pair<Engine?, String> {
        if (vendor == SocVendor.QUALCOMM || vendor == SocVendor.MEDIATEK) {
            runCatching {
                buildEngine(modelPath, Backend.NPU(context.applicationInfo.nativeLibraryDir), Backend.CPU())
            }.onSuccess { return it to "NPU" }
             .onFailure { Log.w(TAG, "NPU unavailable: ${it.message}") }
        }
        if (vendor == SocVendor.GOOGLE_TENSOR) {
            Log.i(TAG, "Google Tensor NPU requires AOT model — using GPU")
        }
        runCatching { buildEngine(modelPath, Backend.GPU(), Backend.CPU()) }
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

    private fun resolveModelPath(): String? =
        listOf(
            File(context.getExternalFilesDir(null), MODEL_FILENAME),
            File(context.filesDir, MODEL_FILENAME),
            File("/data/local/tmp", MODEL_FILENAME)
        ).firstOrNull { it.exists() && it.length() > 0 }?.absolutePath

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
