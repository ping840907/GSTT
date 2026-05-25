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
import android.view.inputmethod.EditorInfo
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

    // Job for the concurrent screenshot/text capture so processWithGemma can join it.
    private var captureJob: Job? = null

    // Repeating-delete job while backspace is held down.
    private var backspaceJob: Job? = null

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
        backspaceJob?.cancel()
        backspaceJob = null
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
        val mp = LinearLayout.LayoutParams.MATCH_PARENT
        val wc = LinearLayout.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            layoutParams = FrameLayout.LayoutParams(mp, dp(240))
        }

        // ── Status label ──────────────────────────────────────────────────────
        statusLabel = TextView(this).apply {
            text = idleStatus()
            textSize = 12.5f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), 0)
        }
        root.addView(statusLabel, LinearLayout.LayoutParams(mp, wc))

        // ── Progress bar ──────────────────────────────────────────────────────
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.INVISIBLE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(mp, dp(3))
            .also { it.setMargins(dp(40), dp(2), dp(40), 0) })

        // ── Mic frame (fills remaining space via weight) ───────────────────────
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

        root.addView(micFrame, LinearLayout.LayoutParams(mp, 0, 1f))

        // ── Function key row: [⌫ Backspace] ─── [↵ Enter] ────────────────────
        val funcRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }

        val backspaceBtn = buildFuncKey("⌫", "刪除", onClick = null)
        backspaceBtn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    backspaceJob?.cancel()
                    backspaceJob = scope.launch {
                        performBackspace()                  // immediate first delete
                        delay(400L)                         // initial long-press threshold
                        while (true) {
                            performBackspace()
                            delay(50L)                      // repeat every 50 ms while held
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    backspaceJob?.cancel()
                    backspaceJob = null
                    true
                }
                else -> false
            }
        }
        funcRow.addView(backspaceBtn, LinearLayout.LayoutParams(dp(80), dp(44)))
        funcRow.addView(View(this),                               // spacer
            LinearLayout.LayoutParams(0, 1, 1f))
        funcRow.addView(buildFuncKey("↵", "換行/確認") { performEnter() },
            LinearLayout.LayoutParams(dp(80), dp(44)))
        root.addView(funcRow, LinearLayout.LayoutParams(mp, wc))

        // ── Dictionary bar ────────────────────────────────────────────────────
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        bar.addView(TextView(this).apply {
            text = "📖 字典"
            textSize = 12f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener { openDictionary() }
        })
        root.addView(bar, LinearLayout.LayoutParams(mp, wc))

        return root
    }

    private fun buildFuncKey(label: String, contentDesc: String, onClick: (() -> Unit)?): TextView =
        TextView(this).apply {
            text = label
            contentDescription = contentDesc
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(BLUE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#E3EBF8"))
            }
            if (onClick != null) setOnClickListener { onClick() }
        }

    // ── Mic press / release ───────────────────────────────────────────────────

    private fun onMicDown() {
        if (isListening || isProcessing) return
        isListening = true
        vibrate(25)
        setUiState(UiMode.RECORDING)
        captureContextAsync()
        try {
            startSpeechRecognizer()
        } catch (e: Exception) {
            Log.e(TAG, "SpeechRecognizer start failed", e)
            isListening = false
            setUiState(UiMode.IDLE)
            toast("語音服務啟動失敗，請重試")
        }
    }

    private fun onMicUp() {
        if (!isListening) return
        isListening = false
        speechRecognizer?.stopListening()
    }

    // ── Context capture (concurrent with recording) ───────────────────────────

    private fun captureContextAsync() {
        captureJob?.cancel()
        captureJob = scope.launch {
            val accessibility = ScreenContextService.instance ?: return@launch

            capturedScreenText = if (modalitySettings.useScreenText) {
                accessibility.getScreenText()
            } else ""

            capturedScreenshot?.recycle()
            capturedScreenshot = if (modalitySettings.useScreenshot) {
                accessibility.captureScreen(deviceCapability.screenshotSizePx, deviceCapability.screenshotSizePx)
            } else null
        }
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
                // Destroy the recognizer on error — a stale instance may not recover
                // reliably for the next startListening() call.
                speechRecognizer?.destroy()
                speechRecognizer = null

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

        scope.launch {
            // Wait for the concurrent screenshot capture to finish before reading.
            // join() is a no-op if the job already completed or was never started.
            captureJob?.join()

            val screenText = capturedScreenText
            val screenshot = capturedScreenshot
            capturedScreenshot = null   // ownership transferred to orchestrator
            capturedScreenText = ""

            val result = orchestrator.transcribe(roughText, screenText, screenshot)
            // screenshot recycled inside orchestrator.finally{}

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

    // ── Function keys ─────────────────────────────────────────────────────────

    private fun performBackspace() {
        currentInputConnection?.deleteSurroundingText(1, 0)
        vibrate(10)
    }

    private fun performEnter() {
        val ic = currentInputConnection ?: return
        val action = (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
        vibrate(10)
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

    companion object {
        private val BLUE = Color.parseColor("#1565C0")
    }
}
