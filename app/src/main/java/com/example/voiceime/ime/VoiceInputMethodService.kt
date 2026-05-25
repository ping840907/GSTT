package com.example.voiceime.ime

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
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
import com.example.voiceime.ai.GemmaInferenceManager
import com.example.voiceime.ai.TranscriptionOrchestrator
import com.example.voiceime.dictionary.DictionaryDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "VoiceIME"

@AndroidEntryPoint
class VoiceInputMethodService : InputMethodService() {

    @Inject lateinit var gemmaManager: GemmaInferenceManager
    @Inject lateinit var orchestrator: TranscriptionOrchestrator
    @Inject lateinit var dictionaryDao: DictionaryDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var speechRecognizer: SpeechRecognizer? = null
    private var statusLabel: TextView? = null
    private var micButton: ImageButton? = null
    private var progressBar: ProgressBar? = null

    private var isListening = false
    private var isProcessing = false

    override fun onCreate() {
        super.onCreate()
        initGemmaAsync()
    }

    private fun initGemmaAsync() = scope.launch {
        withContext(Dispatchers.IO) { gemmaManager.initialize() }
        Log.i(TAG, "Gemma init done: ${gemmaManager.state}")
    }

    // ── IME view ──────────────────────────────────────────────────────────────

    override fun onCreateInputView(): View {
        return buildKeyboardView()
    }

    private fun buildKeyboardView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dpToPx(180)
            )
        }

        statusLabel = TextView(this).apply {
            text = getString(R.string.tap_to_speak)
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(12), 0, 0)
        }
        root.addView(statusLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.INVISIBLE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(4)
        ).also { it.setMargins(dpToPx(32), dpToPx(4), dpToPx(32), 0) })

        val micContainer = FrameLayout(this)
        micButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            contentDescription = getString(R.string.tap_to_speak)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1976D2"))
            }
            setPadding(dpToPx(20), dpToPx(20), dpToPx(20), dpToPx(20))
            setColorFilter(Color.WHITE)

            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { startListening(); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { stopListening(); true }
                    else -> false
                }
            }
        }
        micContainer.addView(micButton, FrameLayout.LayoutParams(
            dpToPx(72), dpToPx(72), Gravity.CENTER
        ))
        root.addView(micContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1f
        ))

        val hintsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(8))
        }

        val dictBtn = TextView(this).apply {
            text = "📖 字典"
            textSize = 12f
            setTextColor(Color.parseColor("#1976D2"))
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
            setOnClickListener { openDictionary() }
        }
        hintsRow.addView(dictBtn)

        root.addView(hintsRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        return root
    }

    // ── Speech recognition ────────────────────────────────────────────────────

    private fun startListening() {
        if (isListening || isProcessing) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast(getString(R.string.transcription_failed))
            return
        }

        isListening = true
        vibrate(30)
        setStatus(getString(R.string.recording))
        micButton?.setColorFilter(Color.parseColor("#FF5252"))

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                .also { sr -> sr.setRecognitionListener(buildRecognitionListener()) }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun stopListening() {
        if (!isListening) return
        // SpeechRecognizer delivers final results via onResults even after stopListening
        speechRecognizer?.stopListening()
    }

    private fun buildRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            isListening = false
            micButton?.setColorFilter(Color.WHITE)
            setStatus(getString(R.string.processing))
        }
        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onResults(results: Bundle?) {
            isListening = false
            micButton?.setColorFilter(Color.WHITE)
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val roughText = matches?.firstOrNull()?.trim() ?: ""
            if (roughText.isBlank()) {
                resetUI()
                toast(getString(R.string.transcription_failed))
                return
            }
            processWithGemma(roughText)
        }

        override fun onError(error: Int) {
            isListening = false
            micButton?.setColorFilter(Color.WHITE)
            resetUI()
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "未辨識到語音，請再試一次"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "語音輸入逾時"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "辨識器忙碌中"
                SpeechRecognizer.ERROR_NOT_SUPPORTED -> "不支援離線辨識，請安裝語言包"
                else -> "語音辨識錯誤（$error）"
            }
            toast(msg)
            Log.w(TAG, "SpeechRecognizer error: $error")
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ── Gemma refinement pipeline ─────────────────────────────────────────────

    private fun processWithGemma(roughText: String) {
        if (isProcessing) return
        isProcessing = true
        setStatus(getString(R.string.processing))
        progressBar?.visibility = View.VISIBLE
        micButton?.isEnabled = false

        scope.launch {
            val accessibility = ScreenContextService.instance
            // Capture screen context concurrently while still on main thread
            val screenText = accessibility?.getScreenText() ?: ""
            val screenshot: Bitmap? = accessibility?.captureScreen()

            val result = orchestrator.transcribe(roughText, screenText, screenshot)
            // screenshot is recycled inside orchestrator.transcribe()

            withContext(Dispatchers.Main) {
                if (result.text.isNotBlank()) {
                    currentInputConnection?.commitText(result.text, 1)
                    vibrate(20)
                }
                resetUI()
                isProcessing = false
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun resetUI() {
        setStatus(getString(R.string.tap_to_speak))
        progressBar?.visibility = View.INVISIBLE
        micButton?.isEnabled = true
        micButton?.setColorFilter(Color.WHITE)
    }

    private fun setStatus(text: String) {
        statusLabel?.text = text
    }

    private fun openDictionary() {
        val intent = Intent(this, com.example.voiceime.ui.DictionaryActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun vibrate(ms: Long) {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                    ?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        scope.cancel()
        super.onDestroy()
    }
}
