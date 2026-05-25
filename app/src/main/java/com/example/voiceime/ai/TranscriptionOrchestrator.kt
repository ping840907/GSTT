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
        // ── 主要輸入（唯一的輸出來源）────────────────────────────────────────
        append("【主要輸入 — 語音辨識結果】\n")
        append("「").append(roughText).append("」\n\n")

        // ── 輔助參考（僅供校正諧音/拼法，不得照抄輸出）────────────────────
        val hasAuxiliary = screenText.isNotBlank() || dictTerms.isNotEmpty() || hasScreenshot
        if (hasAuxiliary) {
            append("【輔助參考資料 — 僅用於修正諧音或拼字，不得直接輸出以下任何內容】\n")
            if (screenText.isNotBlank()) {
                append("▸ 畫面文字（UI 上下文，辨識專有名詞用）：\n")
                append(screenText).append("\n\n")
            }
            if (dictTerms.isNotEmpty()) {
                append("▸ 自訂詞彙（遇到諧音時優先採用這些正確拼法）：")
                append(dictTerms.joinToString("、")).append("\n\n")
            }
            if (hasScreenshot) {
                append("▸ 截圖：附上作為視覺情境參考（判斷當前使用情境）。\n")
            }
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
            你是一個嚴格的語音轉文字校正助手，在繁體中文環境中運作。所有處理完全在本機離線完成。

            ══ 核心任務 ══
            將【主要輸入】中的語音辨識結果校正為精確文字。
            語音辨識結果是輸出的唯一內容來源。

            ══ 強制限制 ══
            • 輸出內容必須且只能源自【主要輸入】的語音辨識結果
            • 【輔助參考資料】（畫面文字、截圖、自訂詞彙）僅用於修正諧音/拼字錯誤
            • 嚴禁將輔助資料中的任何句子、段落或無關文字複製進輸出
            • 嚴禁根據畫面情境自行補充、擴展或推測用戶未說出的內容
            • 嚴禁添加、刪除或改變語意；若辨識結果已明顯正確則直接輸出

            ══ 校正規則 ══
            1. 遇到明顯諧音字或同音字，查閱「畫面文字」或「自訂詞彙」確認正確寫法
            2. 若有截圖，僅用於理解使用情境（正在用哪個 App、輸入框的語境），不得引用截圖文字
            3. 「自訂詞彙」的正確拼法優先於其他來源

            ══ 輸出格式（必須嚴格遵守，不得輸出任何其他說明）══
            [TEXT]最終校正文字[/TEXT]
            [TERMS]本次辨識到的新特殊詞彙，逗號分隔；若無則留空[/TERMS]
        """.trimIndent()
    }
}
