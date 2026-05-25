package com.example.voiceime.ai

import android.util.Log
import com.example.voiceime.dictionary.DictionaryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Transcription"
private const val SCREEN_TEXT_MAX_CHARS = 600
private const val DICT_MAX_TERMS = 50

data class TranscriptionResult(
    val text: String,
    val detectedTerms: List<String>
)

@Singleton
class TranscriptionOrchestrator @Inject constructor(
    private val gemma: GemmaInferenceManager,
    private val dictionaryDao: DictionaryDao
) {
    /**
     * Transcribes user speech using Gemma 4 E2B.
     *
     * Modality priority (audio > text context, image dropped due to LiteRT-LM Gemma 4 bug):
     *
     *   Path A — Native audio (requires LiteRT-LM with Gemma 4 audio support):
     *     [audioWavBytes] + screen text → Gemma 4 E2B
     *     Gemma receives the raw WAV directly and transcribes without pre-processing.
     *
     *   Path B — ASR fallback (LiteRT-LM 0.11.0 without Content.Audio):
     *     [roughTextFallback] + screen text → Gemma 4 E2B for correction
     *     Rough text comes from Android's offline SpeechRecognizer in the calling layer.
     *
     * [audioWavBytes] is cleared from memory inside this function (array reference dropped).
     */
    suspend fun transcribe(
        audioWavBytes: ByteArray?,
        roughTextFallback: String,
        screenText: String
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        if (audioWavBytes == null && roughTextFallback.isBlank()) {
            return@withContext TranscriptionResult("", emptyList())
        }

        val dictTerms = dictionaryDao.getTopTerms(DICT_MAX_TERMS)
        val trimmedScreenText = screenText.take(SCREEN_TEXT_MAX_CHARS)
        val usingNativeAudio = audioWavBytes != null && gemma.nativeAudioSupported

        val textPrompt = buildTextPrompt(
            roughText = if (usingNativeAudio) null else roughTextFallback,
            screenText = trimmedScreenText,
            dictTerms = dictTerms,
            nativeAudio = usingNativeAudio
        )

        val system = if (usingNativeAudio) SYSTEM_NATIVE_AUDIO else SYSTEM_ASR_CORRECTION

        return@withContext try {
            val raw = gemma.transcribeOnce(
                systemInstruction = system,
                textPrompt = textPrompt,
                audioWavBytes = if (usingNativeAudio) audioWavBytes else null
            )
            parseGemmaOutput(raw, dictionaryDao)
        } catch (e: Exception) {
            Log.e(TAG, "Gemma failed — returning rough fallback", e)
            TranscriptionResult(roughTextFallback, emptyList())
        }
        // audioWavBytes reference is not stored; GC will reclaim after this scope exits.
    }

    private fun buildTextPrompt(
        roughText: String?,
        screenText: String,
        dictTerms: List<String>,
        nativeAudio: Boolean
    ): String = buildString {
        if (!nativeAudio && !roughText.isNullOrBlank()) {
            append("初步語音辨識：「").append(roughText).append("」\n\n")
        }
        if (screenText.isNotBlank()) {
            append("畫面文字（當前 UI 上下文，可用於修正專有名詞）：\n").append(screenText).append("\n\n")
        }
        if (dictTerms.isNotEmpty()) {
            append("自訂詞彙（優先採用這些拼法）：").append(dictTerms.joinToString("、")).append("\n\n")
        }
        if (nativeAudio) {
            append("語音已隨附，請直接辨識並校正後輸出。")
        } else {
            append("請根據初步辨識結果和畫面文字校正輸出。")
        }
    }

    private suspend fun parseGemmaOutput(raw: String, dao: DictionaryDao): TranscriptionResult {
        val textMatch = Regex("\\[TEXT](.*?)\\[/TEXT]", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.get(1)?.trim()
        val termsMatch = Regex("\\[TERMS](.*?)\\[/TERMS]", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.get(1)?.trim()

        val finalText = textMatch ?: raw.lines().firstOrNull { it.isNotBlank() } ?: raw.trim()

        val detectedTerms = termsMatch
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.length in 2..20 && it.isNotBlank() }
            ?: emptyList()

        detectedTerms.forEach { term -> runCatching { dao.addCandidateTerm(term) } }

        return TranscriptionResult(finalText, detectedTerms)
    }

    companion object {
        // Native audio path: Gemma 4 directly transcribes the WAV.
        // Low temperature (via topK=1) enforces deterministic, literal output.
        private val SYSTEM_NATIVE_AUDIO = """
            你是一個嚴格的語音轉文字助手，在繁體中文環境中運作。所有處理完全在本機完成，不傳送任何資料。

            任務：辨識所附語音，輸出精確的繁體中文（或其他語言）文字。

            規則：
            1. 嚴格輸出用戶說的內容，不添加、刪改或延伸
            2. 參考「畫面文字」修正可能因諧音誤判的專有名詞
            3. 優先採用「自訂詞彙」中的正確拼法
            4. 若語音混合多語，依實際發音輸出對應語言文字

            輸出格式（必須嚴格遵守）：
            [TEXT]最終文字[/TEXT]
            [TERMS]新識別到的特殊詞彙，逗號分隔；若無則留空[/TERMS]
        """.trimIndent()

        // ASR-fallback path: Gemma 4 corrects Android SpeechRecognizer's rough output.
        private val SYSTEM_ASR_CORRECTION = """
            你是一個嚴格的語音轉文字校正助手，在繁體中文環境中運作。所有處理完全在本機完成，不傳送任何資料。

            任務：將 Android 語音辨識的初步結果（可能有諧音錯誤）校正為精確文字。

            規則：
            1. 嚴格保留原始語意，不添加或刪除用戶未說的內容
            2. 參考「畫面文字」修正可能因諧音誤判的專有名詞
            3. 優先採用「自訂詞彙」中的正確拼法
            4. 若初步辨識明顯正確，直接輸出不做修改

            輸出格式（必須嚴格遵守）：
            [TEXT]最終文字[/TEXT]
            [TERMS]新識別到的特殊詞彙，逗號分隔；若無則留空[/TERMS]
        """.trimIndent()
    }
}
