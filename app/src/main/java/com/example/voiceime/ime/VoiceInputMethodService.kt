package com.example.voiceime.ime

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.example.voiceime.R
import com.example.voiceime.accessibility.ScreenContextService
import com.example.voiceime.ai.DeviceCapability
import com.example.voiceime.ai.EngineState
import com.example.voiceime.ai.GemmaInferenceManager
import com.example.voiceime.ai.TranscriptionOrchestrator
import com.example.voiceime.dictionary.DictionaryDao
import com.example.voiceime.preferences.ModalitySettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "VoiceIME"

@AndroidEntryPoint
class VoiceInputMethodService : InputMethodService() {

    @Inject lateinit var gemmaManager: GemmaInferenceManager
    @Inject lateinit var orchestrator: TranscriptionOrchestrator
    @Inject lateinit var deviceCapability: DeviceCapability
    @Inject lateinit var dictionaryDao: DictionaryDao
    @Inject lateinit var modalitySettings: ModalitySettings

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var speechRecognizer: SpeechRecognizer? = null

    // Screen context captured at recording start (UI is most stable at that moment)
    private var capturedScreenText = ""
    private var capturedScreenshot: Bitmap? = null
    private var partialAsrText = ""

    private var isListening = false
    private var isProcessing = false

    private var statusLabel: TextView? = null
    private var micButton: ImageButton? = null
    private var progressBar: ProgressBar? = null
    private var pulseRing: View? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        scope.launch(Dispatchers.IO) { gemmaManager.initialize() }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        capturedScreenshot?.recycle()
        capturedScreenshot = null
        scope.cancel()
        super.onDestroy()
    }

    // ── Keyboard view ─────────────────────────────────────────────────────────

    override fun onCreateInputView(): View = buildKeyboardView()

    private fun buildKeyboardView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(192)
            )
        }

        statusLabel = TextView(this).apply {
            text = idleStatus()
            textSize = 13f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(statusLabel, lp(match = true, wrap = false))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.INVISIBLE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(3)
        ).also { it.setMargins(dp(40), dp(2), dp(40), 0) })

        val micFrame = FrameLayout(this)

        pulseRing = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33E53935"))
            }
            visibility = View.INVISIBLE
        }
        micFrame.addView(pulseRing, FrameLayout.LayoutParams(dp(88), dp(88), Gravity.CENTER))

        micButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            contentDescription = getString(R.string.hold_to_speak)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BLUE)
            }
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setColorFilter(Color.WHITE)
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { onMicDown(); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { onMicUp(); true }
                    else -> false
                }
            }
        }
        micFrame.addView(micButton, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))

        root.addView(micFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        bar.addView(TextView(this).apply {
            text = "📖 字典"
            textSize = 12f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { openDictionary() }
        })
        root.addView(bar, lp(match = true, wrap = false))

        return root
    }

    // ── Mic press / release ───────────────────────────────────────────────────

    private fun onMicDown() {
        if (isListening || isProcessing) return
        isListening = true
        vibrate(25)
        setUiState(UiMode.RECORDING)
        captureContextAsync()
        startSpeechRecognizer()
    }

    private fun onMicUp() {
        if (!isListening) return
        isListening = false
        speechRecognizer?.stopListening()
        // onResults will drive the next step; setUiState to PROCESSING done there.
    }

    // ── Context capture (concurrent with recording) ───────────────────────────

    private fun captureContextAsync() = scope.launch {
        val accessibility = ScreenContextService.instance ?: return@launch

        capturedScreenText = if (modalitySettings.useScreenText) {
            accessibility.getScreenText()
        } else ""

        capturedScreenshot?.recycle()
        capturedScreenshot = if (modalitySettings.useScreenshot) {
            val px = deviceCapability.screenshotSizePx
            accessibility.captureScreen(px, px)
        } else null
    }

    // ── SpeechRecognizer ──────────────────────────────────────────────────────
    //
    // createOnDeviceSpeechRecognizer requires API 33+ and an on-device recognition
    // service. Falls back to createSpeechRecognizer with PREFER_OFFLINE on earlier
    // APIs or when on-device is unavailable.

    private fun startSpeechRecognizer() {
        partialAsrText = ""
        if (speechRecognizer == null) {
            speechRecognizer = buildSpeechRecognizer()
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun buildSpeechRecognizer(): SpeechRecognizer {
        val useOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        val sr = if (useOnDevice) {
            Log.i(TAG, "Using on-device SpeechRecognizer (API ${Build.VERSION.SDK_INT})")
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            Log.i(TAG, "Using default SpeechRecognizer with PREFER_OFFLINE")
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let { partialAsrText = it }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim()
                    ?: partialAsrText.trim()

                if (best.isBlank()) {
                    resetUi()
                    toast(getString(R.string.transcription_failed))
                    return
                }
                processWithGemma(best)
            }

            override fun onError(error: Int) {
                isListening = false
                // If we have partial results, use them rather than giving up
                val rough = partialAsrText.trim()
                if (rough.isNotBlank()) {
                    Log.w(TAG, "ASR error $error — using partial result: \"$rough\"")
                    processWithGemma(rough)
                    return
                }
                resetUi()
                toast(asrErrorMessage(error))
                Log.w(TAG, "ASR error: $error")
            }
        })
        return sr
    }

    // ── Gemma pipeline ────────────────────────────────────────────────────────

    private fun processWithGemma(roughText: String) {
        if (isProcessing) return
        isProcessing = true
        setUiState(UiMode.PROCESSING)

        val screenText = capturedScreenText
        val screenshot = capturedScreenshot
        capturedScreenshot = null   // ownership transferred to orchestrator
        capturedScreenText = ""

        scope.launch {
            val result = orchestrator.transcribe(roughText, screenText, screenshot)
            // screenshot recycled inside orchestrator

            withContext(Dispatchers.Main) {
                if (result.text.isNotBlank()) {
                    currentInputConnection?.commitText(result.text, 1)
                    vibrate(18)
                }
                isProcessing = false
                resetUi()
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private enum class UiMode { IDLE, RECORDING, PROCESSING }

    private fun setUiState(mode: UiMode) {
        statusLabel?.text = when (mode) {
            UiMode.IDLE -> idleStatus()
            UiMode.RECORDING -> getString(R.string.recording)
            UiMode.PROCESSING -> getString(R.string.processing)
        }
        progressBar?.visibility = if (mode == UiMode.PROCESSING) View.VISIBLE else View.INVISIBLE
        pulseRing?.visibility = if (mode == UiMode.RECORDING) View.VISIBLE else View.INVISIBLE
        (micButton?.background as? GradientDrawable)?.setColor(
            when (mode) {
                UiMode.IDLE -> BLUE
                UiMode.RECORDING -> Color.parseColor("#E53935")
                UiMode.PROCESSING -> Color.parseColor("#757575")
            }
        )
        micButton?.isEnabled = mode == UiMode.IDLE
    }

    private fun resetUi() = setUiState(UiMode.IDLE)

    private fun idleStatus(): String = when (val s = gemmaManager.state) {
        is EngineState.Ready -> getString(R.string.hold_to_speak)
        is EngineState.Loading -> getString(R.string.model_loading)
        is EngineState.Error -> "⚠ ${s.message.take(30)}"
        else -> getString(R.string.model_loading)
    }

    private fun openDictionary() = startActivity(
        Intent(this, com.example.voiceime.ui.DictionaryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun vibrate(ms: Long) = runCatching {
        (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
            .defaultVibrator
            .vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun asrErrorMessage(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "未辨識到語音，請再說一次"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "語音輸入逾時"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "辨識器忙碌，請稍後再試"
        SpeechRecognizer.ERROR_NOT_SUPPORTED -> "請安裝繁體中文離線語言包"
        else -> "語音辨識錯誤（$code）"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun lp(match: Boolean, wrap: Boolean) = LinearLayout.LayoutParams(
        if (match) LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT,
        if (wrap) LinearLayout.LayoutParams.WRAP_CONTENT else LinearLayout.LayoutParams.WRAP_CONTENT
    )

    companion object {
        private val BLUE = Color.parseColor("#1565C0")
    }
}
