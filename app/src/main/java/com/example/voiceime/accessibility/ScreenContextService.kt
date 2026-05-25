package com.example.voiceime.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

private const val TAG = "ScreenContext"
private const val SCREENSHOT_SIZE = 336

class ScreenContextService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ── Public API called by VoiceInputMethodService ──────────────────────────

    fun getScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        return try {
            buildString { collectText(root, this) }.trim()
        } catch (e: Exception) {
            Log.w(TAG, "getScreenText failed", e)
            ""
        }
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        node.text?.takeIf { it.isNotBlank() }?.let { sb.append(it).append(' ') }
        node.contentDescription?.takeIf { it.isNotBlank() }?.let { sb.append(it).append(' ') }
        node.hintText?.takeIf { it.isNotBlank() }?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectText(child, sb)
                child.recycle()
            }
        }
    }

    /** Returns a [SCREENSHOT_SIZE]×[SCREENSHOT_SIZE] bitmap, or null on API < 30 or failure. */
    suspend fun captureScreen(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            suspendCancellableCoroutine { cont ->
                val executor = Executors.newSingleThreadExecutor()
                takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    executor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val hw = screenshot.hardwareBitmap
                            val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                            hw.recycle()
                            val scaled = Bitmap.createScaledBitmap(soft, SCREENSHOT_SIZE, SCREENSHOT_SIZE, true)
                            if (scaled !== soft) soft.recycle()
                            executor.shutdown()
                            cont.resume(scaled)
                        }
                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "takeScreenshot failed errorCode=$errorCode")
                            executor.shutdown()
                            cont.resume(null)
                        }
                    }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "captureScreen exception", e)
            null
        }
    }

    companion object {
        @Volatile
        var instance: ScreenContextService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }
}
