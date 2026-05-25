package com.example.voiceime.ime

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
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
import com.example.voiceime.ai.EngineState
import com.example.voiceime.ai.GemmaInferenceManager
import com.example.voiceime.ai.TranscriptionOrchestrator
import com.example.voiceime.audio.AudioCaptureManager
import com.example.voiceime.dictionary.DictionaryDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val TAG = "VoiceIME"

@AndroidEntryPoint
class VoiceInputMethodService : InputMethodService() {

    @Inject lateinit var gemmaManager: GemmaInferenceManager
    @Inject lateinit var orchestrator: TranscriptionOrchestrator
    @Inject lateinit var audioCapture: AudioCaptureManager
    @Inject lateinit var dictionaryDao: DictionaryDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Atomic flag set to true when the user releases the mic button, signalling AudioRecord to stop
    private val stopRecording = AtomicBoolean(false)

    private var recordingJob: Job? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private var statusLabel: TextView? = null
    private var micButton: ImageButton? = null
    private var progressBar: ProgressBar? = null
    private var audioIndicator: View? = null

    private var isRecording = false
    private var isProcessing = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        scope.launch(Dispatchers.IO) { gemmaManager.initialize() }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        scope.cancel()
        super.onDestroy()
    }

    // ── IME keyboard view ─────────────────────────────────────────────────────

    override fun onCreateInputView(): View = buildKeyboardView()

    private fun buildKeyboardView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dpToPx(192)
            )
        }

        // Status label row
        statusLabel = TextView(this).apply {
            text = statusText()
            textSize = 13f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(10), 0, 0)
        }
        root.addView(statusLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Indeterminate progress bar (hidden until processing)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.INVISIBLE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(3)
        ).also { it.setMargins(dpToPx(40), dpToPx(2), dpToPx(40), 0) })

        // Mic button (centered)
        val micFrame = FrameLayout(this)

        // Pulsing ring shown while recording
        audioIndicator = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33FF5252"))
            }
            visibility = View.INVISIBLE
        }
        micFrame.addView(audioIndicator, FrameLayout.LayoutParams(
            dpToPx(88), dpToPx(88), Gravity.CENTER
        ))

        micButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            contentDescription = getString(R.string.hold_to_speak)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(micColor())
            }
            setPadding(dpToPx(18), dpToPx(18), dpToPx(18), dpToPx(18))
            setColorFilter(Color.WHITE)
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { onMicPressed(); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { onMicReleased(); true }
                    else -> false
                }
            }
        }
        micFrame.addView(micButton, FrameLayout.LayoutParams(
            dpToPx(72), dpToPx(72), Gravity.CENTER
        ))

        root.addView(micFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // Bottom toolbar
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), 0, dpToPx(8), dpToPx(8))
        }
        toolbar.addView(TextView(this).apply {
            text = "📖 字典"
            textSize = 12f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
            setOnClickListener { openDictionary() }
        })
        root.addView(toolbar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        return root
    }

    // ── Input handling ─────────────────────────────────────────────────────────

    private fun onMicPressed() {
        if (isRecording || isProcessing) return
        if (!audioCapture.hasMicPermission) {
            toast(getString(R.string.mic_permission_required))
            return
        }

        isRecording = true
        stopRecording.set(false)
        vibrate(25)
        updateUI(recording = true)

        // Decide path: native AudioRecord (preferred) or Android SpeechRecognizer fallback.
        // We always record via AudioRecord regardless, but also run SpeechRecognizer in parallel
        // as the ASR fallback in case Content.Audio isn't supported by current LiteRT-LM.
        startAudioRecord()
        if (!gemmaManager.nativeAudioSupported) {
            startSpeechRecognizer()
        }
    }

    private fun onMicReleased() {
        if (!isRecording) return
        isRecording = false
        stopRecording.set(true)
        speechRecognizer?.stopListening()
        updateUI(recording = false, processing = true)
    }

    // ── AudioRecord path ──────────────────────────────────────────────────────

    private fun startAudioRecord() {
        recordingJob = scope.launch {
            val pcm = audioCapture.record(stopSignal = { stopRecording.get() })
            val wav = pcm?.let { audioCapture.pcmToWav(it) }

            // Only dispatch Gemma processing here if NOT using SpeechRecognizer fallback
            // (i.e., native audio is supported). The SpeechRecognizer path dispatches from onResults.
            if (gemmaManager.nativeAudioSupported) {
                processWithGemma(audioWavBytes = wav, roughText = "")
            }
            // If ASR fallback: wav is still captured but SpeechRecognizer drives the Gemma call.
            // wav reference is dropped here — GC reclaims it when processWithGemma is done.
        }
    }

    // ── SpeechRecognizer fallback path ────────────────────────────────────────
    //
    // Used when Content.Audio is unavailable in current LiteRT-LM. Android's offline
    // SpeechRecognizer provides a rough transcript which Gemma 4 then corrects
    // using the screen text context.

    private var asrRoughText = ""

    private fun startSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        asrRoughText = ""

        if (speechRecognizer == null) {
            // Use createOnDeviceSpeechRecognizer when available for true offline operation.
            speechRecognizer = runCatching {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            }.getOrElse {
                SpeechRecognizer.createSpeechRecognizer(this)
            }.also { it.setRecognitionListener(buildAsrListener()) }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Extended silence tolerance for hold-to-speak pattern
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun buildAsrListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partial: Bundle?) {
            partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.let { asrRoughText = it }
        }
        override fun onResults(results: Bundle?) {
            val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim() ?: asrRoughText
            // Dispatch Gemma call with rough text; AudioRecord wav may be nil if recording
            // finished before SpeechRecognizer returned results.
            if (best.isNotBlank() || asrRoughText.isNotBlank()) {
                processWithGemma(audioWavBytes = null, roughText = best.ifBlank { asrRoughText })
            } else {
                resetUI()
                toast(getString(R.string.transcription_failed))
            }
        }
        override fun onError(error: Int) {
            Log.w(TAG, "ASR error $error — processing with empty rough text")
            // Still attempt Gemma with whatever partial text we have
            if (asrRoughText.isNotBlank()) {
                processWithGemma(audioWavBytes = null, roughText = asrRoughText)
            } else {
                resetUI()
                toast("語音辨識失敗 ($error)，請再試")
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ── Gemma processing pipeline ─────────────────────────────────────────────

    private fun processWithGemma(audioWavBytes: ByteArray?, roughText: String) {
        if (isProcessing) return
        isProcessing = true
        updateUI(processing = true)

        scope.launch {
            val screenText = ScreenContextService.instance?.getScreenText() ?: ""

            val result = orchestrator.transcribe(
                audioWavBytes = audioWavBytes,
                roughTextFallback = roughText,
                screenText = screenText
            )
            // audioWavBytes reference is cleared inside orchestrator

            withContext(Dispatchers.Main) {
                if (result.text.isNotBlank()) {
                    currentInputConnection?.commitText(result.text, 1)
                    vibrate(18)
                }
                isProcessing = false
                resetUI()
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun updateUI(recording: Boolean = false, processing: Boolean = false) {
        statusLabel?.text = when {
            recording -> getString(R.string.recording)
            processing -> getString(R.string.processing)
            else -> statusText()
        }
        progressBar?.visibility = if (processing) View.VISIBLE else View.INVISIBLE
        audioIndicator?.visibility = if (recording) View.VISIBLE else View.INVISIBLE
        (micButton?.background as? GradientDrawable)?.setColor(
            when {
                recording -> Color.parseColor("#E53935")
                processing -> Color.parseColor("#757575")
                else -> Color.parseColor("#1565C0")
            }
        )
        micButton?.isEnabled = !processing
    }

    private fun resetUI() {
        statusLabel?.text = statusText()
        progressBar?.visibility = View.INVISIBLE
        audioIndicator?.visibility = View.INVISIBLE
        (micButton?.background as? GradientDrawable)?.setColor(micColor())
        micButton?.isEnabled = true
    }

    private fun statusText(): String {
        val state = gemmaManager.state
        return when {
            state is EngineState.Error -> "⚠ 模型錯誤"
            state is EngineState.Loading -> getString(R.string.model_loading)
            state is EngineState.Ready && state.supportsAudio -> getString(R.string.hold_to_speak)
            state is EngineState.Ready -> getString(R.string.hold_to_speak) + "（ASR模式）"
            else -> getString(R.string.model_loading)
        }
    }

    private fun micColor() = Color.parseColor("#1565C0")

    private fun openDictionary() {
        startActivity(Intent(this, com.example.voiceime.ui.DictionaryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun vibrate(ms: Long) = runCatching {
        (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
            .defaultVibrator
            .vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()
}
