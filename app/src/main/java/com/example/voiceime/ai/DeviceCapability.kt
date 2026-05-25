package com.example.voiceime.ai

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.core.app.ActivityManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DeviceCapability"

/**
 * Device-aware resource limits — mirrors the same class in gemmakey.
 *
 * Tier | RAM         | screenshotPx | heapGuardMb
 * -----|-------------|--------------|------------
 *   0  | < 3 GB      |    224       |     80
 *   1  | 3–5 GB      |    336       |     60
 *   2  | ≥ 6 GB      |    448       |     40
 */
@Singleton
class DeviceCapability @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    val isLowRamDevice: Boolean by lazy { ActivityManagerCompat.isLowRamDevice(am) }

    val totalRamMb: Long by lazy {
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / 1_048_576L
    }

    val tier: Int by lazy {
        val t = when {
            isLowRamDevice || totalRamMb < 3_072 -> 0
            totalRamMb < 6_144 -> 1
            else -> 2
        }
        Log.i(TAG, "tier=$t  totalRam=${totalRamMb}MB  lowRam=$isLowRamDevice")
        t
    }

    /** Max edge pixels for screenshots passed to Gemma 4 image encoder. */
    val screenshotSizePx: Int get() = intArrayOf(224, 336, 448)[tier]

    /**
     * Returns true when the JVM has at least [requiredMb] of free heap.
     * Uses maxMemory() − totalMemory() + freeMemory() to approximate the
     * largest allocation the GC can satisfy without OOM.
     */
    fun hasHeapFor(requiredMb: Int): Boolean {
        val rt = Runtime.getRuntime()
        val freeMb = (rt.maxMemory() - rt.totalMemory() + rt.freeMemory()) / 1_048_576L
        return freeMb >= requiredMb
    }
}
