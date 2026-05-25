package com.example.voiceime.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.voiceime.dictionary.DictionaryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Transcription"
private const val SCREEN_TEXT_MAX_CHARS = 800
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
     * Refines [roughText] (from Android SpeechRecognizer) using:
     *  - [screenshot]: low-res current screen image (nullable, freed inside this call)
     *  - [screenText]: text extracted from accessibility node tree
     *
     * Contract: [screenshot] is recycled before this function returns. No references escape.
     */
    suspend fun transcribe(
        roughText: String,
        screenText: String,
        screenshot: Bitmap?
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        if (roughText.isBlank()) return@withContext TranscriptionResult("", emptyList())

        val dictTerms = dictionaryDao.getTopTerms(DICT_MAX_TERMS)
        val trimmedScreenText = screenText.take(SCREEN_TEXT_MAX_CHARS)

        val prompt = buildUserPrompt(roughText, trimmedScreenText, dictTerms)

        return@withContext try {
            val raw = gemma.transcribeOnce(
                systemInstruction = SYSTEM_INSTRUCTION,
                textPrompt = prompt,
                screenshot = screenshot
            )
            parseGemmaOutput(raw, dictionaryDao)
        } catch (e: Exception) {
            Log.e(TAG, "Gemma transcription failed, falling back to raw ASR result", e)
            TranscriptionResult(roughText, emptyList())
        } finally {
            screenshot?.recycle()
        }
    }

    private fun buildUserPrompt(
        roughText: String,
        screenText: String,
        dictTerms: List<String>
    ): String = buildString {
        append("初步語音辨識：「").append(roughText).append("」\n\n")
        if (screenText.isNotBlank()) {
            append("畫面文字（當前 UI 內容）：\n").append(screenText).append("\n\n")
        }
        if (dictTerms.isNotEmpty()) {
            append("自訂詞彙（優先採用這些拼法）：").append(dictTerms.joinToString("、")).append("\n\n")
        }
        append("截圖已附上作為視覺上下文參考（如有）。")
    }

    /** Parses structured Gemma output and side-effects new candidate terms into the DB */
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

        detectedTerms.forEach { term ->
            runCatching { dao.addCandidateTerm(term) }
        }

        return TranscriptionResult(finalText, detectedTerms)
    }

    companion object {
        // Low temperature (0.1) is set in GemmaInferenceManager for deterministic correction.
        // The structured output tags prevent Gemma from adding explanatory prose.
        private val SYSTEM_INSTRUCTION = """
            你是一個嚴格的語音轉文字校正助手，在繁體中文環境中運作。所有處理完全離線，不傳送任何資料。

            任務：將有誤的初步語音辨識結果校正為正確文字。

            規則：
            1. 嚴格保留用戶說話的語意，不得添加、刪除或改變內容
            2. 參考「畫面文字」修正可能因諧音錯誤的專有名詞（品牌、人名、地名、術語）
            3. 參考截圖了解當前使用情境（正在使用的 App、輸入框周圍的語境）
            4. 優先採用「自訂詞彙」中提供的正確拼法
            5. 若無需修正，直接輸出原文

            輸出格式（必須嚴格遵守，不得輸出任何其他內容）：
            [TEXT]最終文字[/TEXT]
            [TERMS]新識別到的特殊詞彙，逗號分隔；若無則留空[/TERMS]
        """.trimIndent()
    }
}
