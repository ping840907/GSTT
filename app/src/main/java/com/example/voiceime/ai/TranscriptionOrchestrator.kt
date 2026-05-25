package com.example.voiceime.ai

import android.graphics.Bitmap
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
     * Refines [roughText] (from Android offline SpeechRecognizer) using:
     *   • [screenshot] — low-res screen capture from AccessibilityService (nullable)
     *   • [screenText] — text extracted from accessibility node tree
     *
     * Modality breakdown:
     *   Audio  → handled upstream by Android SpeechRecognizer → arrives as [roughText].
     *            LiteRT-LM has no Content.Audio type; this is the correct and only path.
     *   Image  → [screenshot] passed as Content.ImageBytes. Gemma 4 image bug (Issue #1874)
     *            is fixed; pre-patch builds fall back to text-only automatically.
     *   Text   → [screenText] + [roughText] + dictionary terms assembled into one prompt.
     *
     * [screenshot] is recycled before this function returns — no reference escapes.
     */
    suspend fun transcribe(
        roughText: String,
        screenText: String,
        screenshot: Bitmap?
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        if (roughText.isBlank()) return@withContext TranscriptionResult("", emptyList())

        val dictTerms = dictionaryDao.getTopTerms(DICT_MAX_TERMS)
        val prompt = buildPrompt(roughText, screenText.take(SCREEN_TEXT_MAX_CHARS), dictTerms, screenshot != null)

        return@withContext try {
            val raw = gemma.transcribeOnce(
                systemInstruction = SYSTEM_INSTRUCTION,
                textPrompt = prompt,
                screenshot = screenshot
            )
            parseOutput(raw, dictionaryDao)
        } catch (e: Exception) {
            Log.e(TAG, "Gemma failed — returning raw ASR text as-is", e)
            TranscriptionResult(roughText, emptyList())
        } finally {
            screenshot?.recycle()
        }
    }

    private fun buildPrompt(
        roughText: String,
        screenText: String,
        dictTerms: List<String>,
        hasScreenshot: Boolean
    ): String = buildString {
        append("初步語音辨識：「").append(roughText).append("」\n\n")
        if (screenText.isNotBlank()) {
            append("畫面文字（當前 UI 上下文）：\n").append(screenText).append("\n\n")
        }
        if (dictTerms.isNotEmpty()) {
            append("自訂詞彙（優先採用這些拼法）：").append(dictTerms.joinToString("、")).append("\n\n")
        }
        if (hasScreenshot) {
            append("截圖已附上作為視覺情境參考。")
        }
    }

    private suspend fun parseOutput(raw: String, dao: DictionaryDao): TranscriptionResult {
        val text = Regex("\\[TEXT](.*?)\\[/TEXT]", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.get(1)?.trim()
            ?: raw.lines().firstOrNull { it.isNotBlank() }
            ?: raw.trim()

        val terms = Regex("\\[TERMS](.*?)\\[/TERMS]", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.get(1)?.trim()
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.length in 2..20 && it.isNotBlank() }
            ?: emptyList()

        terms.forEach { runCatching { dao.addCandidateTerm(it) } }
        return TranscriptionResult(text, terms)
    }

    companion object {
        private val SYSTEM_INSTRUCTION = """
            你是一個嚴格的語音轉文字校正助手，在繁體中文環境中運作。所有處理完全在本機離線完成，不傳送任何資料。

            任務：將 Android 語音辨識的初步結果（可能有諧音或同音字錯誤）校正為精確文字。

            規則：
            1. 嚴格保留用戶說話的語意，不添加、刪除或改變內容
            2. 參考「畫面文字」修正可能因諧音錯誤的專有名詞（品牌、人名、地名、術語）
            3. 若有截圖，參考畫面情境（正在使用的 App、輸入框周圍語境）輔助判斷
            4. 優先採用「自訂詞彙」中提供的正確拼法
            5. 若初步辨識結果明顯正確，直接輸出不做修改

            輸出格式（必須嚴格遵守，不得輸出任何其他說明）：
            [TEXT]最終文字[/TEXT]
            [TERMS]新識別到的特殊詞彙，逗號分隔；若無則留空[/TERMS]
        """.trimIndent()
    }
}
