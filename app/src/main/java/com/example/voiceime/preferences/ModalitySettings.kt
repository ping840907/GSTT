package com.example.voiceime.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists per-device modality enable/disable choices.
 * Gemma falls back to audio-only when both are disabled.
 */
@Singleton
class ModalitySettings @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("modality_prefs", Context.MODE_PRIVATE)

    var useScreenText: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_TEXT, true)
        set(v) { prefs.edit().putBoolean(KEY_SCREEN_TEXT, v).apply() }

    var useScreenshot: Boolean
        get() = prefs.getBoolean(KEY_SCREENSHOT, true)
        set(v) { prefs.edit().putBoolean(KEY_SCREENSHOT, v).apply() }

    companion object {
        private const val KEY_SCREEN_TEXT = "use_screen_text"
        private const val KEY_SCREENSHOT = "use_screenshot"
    }
}
