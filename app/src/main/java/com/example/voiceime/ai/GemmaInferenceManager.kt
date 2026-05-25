package com.example.voiceime.ai

import android.content.Context
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
import java.io.File
import java.lang.reflect.Constructor
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
    data class Ready(val backend: String, val supportsAudio: Boolean) : EngineState()
    data class Error(val message: String) : EngineState()
}

@Singleton
class GemmaInferenceManager @Inject constructor(
    private val context: Context
) {
    private var engine: Engine? = null
    private var activeBackend: String = "CPU"

    // Checked once at engine-ready time via reflection; non-null means Content.Audio is usable.
    private var audioContentCtor: Constructor<*>? = null

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

        // Probe for Content.Audio — available in LiteRT-LM when Gemma 4 audio support lands.
        // LiteRT-LM 0.11.0 only has Text and ImageBytes; a future or custom build may add Audio.
        audioContentCtor = probeAudioContentConstructor()

        EngineState.Ready(backendLabel, audioContentCtor != null).also {
            state = it
            Log.i(TAG, "Gemma ready — backend=$backendLabel  audioNative=${audioContentCtor != null}  model=$modelPath")
        }
    }

    fun isModelInstalled(): Boolean = resolveModelPath() != null

    // ── Per-event stateless transcription: Audio (or text) + Screen text ──────
    //
    // Each call creates a fresh Conversation and closes it immediately after the
    // LLM response, releasing KV-cache and all native allocations. Image input is
    // deliberately omitted: Gemma 4 in LiteRT-LM 0.11.0 has a known crash when
    // image content is sent (ConversationConfig doesn't expose the required prompt
    // template placeholder; see LiteRT-LM Issue #1874).

    /**
     * @param audioWavBytes  Raw WAV bytes (16 kHz, 16-bit mono). Passed to Gemma 4 directly
     *                       if [audioContentCtor] is non-null. Otherwise the caller should
     *                       include a rough ASR transcript in [textPrompt] as fallback.
     * @param textPrompt     The assembled text prompt (screen context + rough transcript +
     *                       dictionary terms).
     */
    suspend fun transcribeOnce(
        systemInstruction: String,
        textPrompt: String,
        audioWavBytes: ByteArray?
    ): String = withContext(Dispatchers.IO) {
        val eng = engine ?: error("Engine not initialised — call initialize() first")
        var conversation: Conversation? = null
        try {
            // Low temperature for deterministic transcription correction.
            val samplerCfg = if (activeBackend == "NPU") null
            else SamplerConfig(topK = 1, topP = 0.95, temperature = 0.1)

            conversation = eng.createConversation(
                ConversationConfig(
                    samplerConfig = samplerCfg,
                    systemInstruction = Contents.of(listOf(Content.Text(systemInstruction)))
                )
            )

            val contentParts = buildList {
                // Attempt native audio content if supported by current LiteRT-LM build
                val audioCtor = audioContentCtor
                if (audioWavBytes != null && audioCtor != null) {
                    runCatching {
                        @Suppress("UNCHECKED_CAST")
                        add(audioCtor.newInstance(audioWavBytes) as Content)
                    }.onFailure {
                        Log.w(TAG, "Content.Audio instantiation failed: ${it.message}")
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

    // ── Audio Content probe ───────────────────────────────────────────────────
    //
    // LiteRT-LM does not document Content.Audio publicly as of 0.11.0.
    // We probe at runtime so the same APK works on both old (text-only fallback)
    // and new (native audio) LiteRT-LM builds without recompilation.
    //
    // Expected signatures when available:
    //   Content.Audio(wavBytes: ByteArray)          — WAV with embedded sample-rate
    //   Content.Audio(pcmBytes: ByteArray, hz: Int) — Raw PCM + explicit rate

    private fun probeAudioContentConstructor(): Constructor<*>? {
        val candidates = listOf(
            "com.google.ai.edge.litertlm.Content\$Audio" to arrayOf<Class<*>>(ByteArray::class.java),
            "com.google.ai.edge.litertlm.Content\$Audio" to arrayOf(ByteArray::class.java, Int::class.javaPrimitiveType!!)
        )
        for ((cls, params) in candidates) {
            runCatching {
                val c = Class.forName(cls).getDeclaredConstructor(*params)
                c.isAccessible = true
                Log.i(TAG, "Content.Audio found with params: ${params.map { it.simpleName }}")
                return c
            }
        }
        Log.i(TAG, "Content.Audio not available in current LiteRT-LM — will use ASR fallback path")
        return null
    }

    val nativeAudioSupported: Boolean get() = audioContentCtor != null

    // ── Backend selection ─────────────────────────────────────────────────────

    private fun tryCreateEngine(modelPath: String, vendor: SocVendor): Pair<Engine?, String> {
        if (vendor == SocVendor.QUALCOMM || vendor == SocVendor.MEDIATEK) {
            runCatching {
                buildEngine(modelPath, Backend.NPU(context.applicationInfo.nativeLibraryDir), Backend.CPU())
            }.onSuccess { return it to "NPU" }
             .onFailure { Log.w(TAG, "NPU unavailable: ${it.message}") }
        }
        if (vendor == SocVendor.GOOGLE_TENSOR) {
            Log.i(TAG, "Google Tensor NPU requires AOT model — falling through to GPU")
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

    fun close() {
        engine?.close()
        engine = null
        audioContentCtor = null
        state = EngineState.Uninitialized
    }
}
